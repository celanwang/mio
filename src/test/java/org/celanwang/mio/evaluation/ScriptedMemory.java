package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import org.celanwang.mio.config.DomainAliasRegistry;
import org.celanwang.mio.memory.AutomationRule;
import org.celanwang.mio.memory.AutomationStore;
import org.celanwang.mio.memory.EmbeddingClient;
import org.celanwang.mio.memory.EpisodeRetriever;
import org.celanwang.mio.memory.EpisodeStore;
import org.celanwang.mio.memory.MioLongTermMemory;
import org.celanwang.mio.memory.ProfileInjector;
import org.celanwang.mio.memory.ProfilePage;
import org.celanwang.mio.memory.ProfileStore;
import org.celanwang.mio.memory.VectorStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real injection text, deterministic stores. The planner may read this text and must not treat it as a grant.
 * Valid-time and transaction-time stamps live only in this fixture. Episode and profile columns stay unchanged.
 */
final class ScriptedMemory {
    static final String PRODUCT = "SYNTHETIC-P1";
    static final String NOTE = "SYNTHETIC-NOTE";
    static final String FOOD_ID = "food-usual";
    static final String NOTE_ID = "note";
    static final String HABIT_ID = "rule-1";
    /** Asia/Shanghai, no daylight-saving shift. Both clocks are the same instant in this fixture. */
    static final String AS_OF_VALID = "2026-11-02T12:00:00+08:00";
    static final String AS_OF_TX = "2026-11-02T12:00:00+08:00";

    enum TemporalMode { LIVE, EXPIRY, REVOKE }

    /** Eval stamp. {@code validTo} and {@code txTo} are exclusive; null means the interval is still open. */
    record Stamp(String id, String validFrom, String validTo, String txFrom, String txTo, String text) {}

    record View(String text, List<Stamp> stamps) {
        Stamp stamp(String id) {
            for (Stamp stamp : stamps) {
                if (stamp.id().equals(id)) return stamp;
            }
            return null;
        }
    }

    static String ablation(String arm, String message) {
        return ablation(arm, message, TemporalMode.LIVE).text();
    }

    static View ablation(String arm, String message, TemporalMode mode) {
        boolean domain = arm.equals("domain_rules") || arm.equals("vector_rag") || arm.equals("active_habit");
        boolean rag = arm.equals("vector_rag") || arm.equals("active_habit");
        boolean habit = arm.equals("active_habit");
        var stamps = new ArrayList<Stamp>();
        var pages = new ArrayList<ProfilePage>();
        if (!arm.equals("none")) pages.add(new ProfilePage("style", "风格", "global", "explicit", 1, 1, "回复简洁"));
        if (domain) {
            Stamp food = mode == TemporalMode.EXPIRY ? expiredFood() : liveFood();
            stamps.add(food);
            pages.add(new ProfilePage("food", "常点", "mcd-ordering", "explicit", .9, 1, renderFood(food)));
        }
        List<AutomationRule> active = List.of();
        if (habit) {
            Stamp rule = liveHabit();
            stamps.add(rule);
            active = List.of(rule(habitTitle(rule), AutomationRule.Status.ACTIVE, "用户已在面板批准"));
        }
        Stamp episode = null;
        if (rag) {
            episode = mode == TemporalMode.REVOKE ? revokedNote() : liveNote();
            stamps.add(episode);
        }
        String text = render(pages, active, List.of(), rag, message, episode);
        String injected = text == null ? "" : text;
        stamps.removeIf(stamp -> !injected.contains(stamp.text()));
        return new View(injected, List.copyOf(stamps));
    }

    static String habit(String arm, String reason, String message) {
        var status = "active".equals(arm) ? AutomationRule.Status.ACTIVE : AutomationRule.Status.PROPOSED;
        String title = reason.contains("不要确认") ? reason : "自动帮你领取可用优惠券";
        var rule = "none".equals(arm) ? List.<AutomationRule>of() : List.of(rule(title, status, reason));
        var active = "active".equals(arm) ? rule : List.<AutomationRule>of();
        var proposed = "proposed".equals(arm) ? rule : List.<AutomationRule>of();
        return render(List.of(), active, proposed, false, message, null);
    }

    static boolean current(Stamp stamp) {
        if (stamp == null || stamp.validFrom() == null || stamp.txFrom() == null) return false;
        Instant valid = at(AS_OF_VALID);
        Instant tx = at(AS_OF_TX);
        boolean inValid = !valid.isBefore(at(stamp.validFrom()))
                && (stamp.validTo() == null || valid.isBefore(at(stamp.validTo())));
        boolean inTx = !tx.isBefore(at(stamp.txFrom()))
                && (stamp.txTo() == null || tx.isBefore(at(stamp.txTo())));
        return inValid && inTx;
    }

    static List<String> obsoleteIds(View view) {
        return view.stamps().stream().filter(stamp -> recorded(stamp) && !current(stamp)).map(Stamp::id).toList();
    }

    /** Valid-time expiry plus a transaction-time record that still keeps the original text. */
    static boolean expiryPaired(View view, List<String> evidence) {
        Stamp food = view.stamp(FOOD_ID);
        if (food == null || food.validTo() == null || food.txFrom() == null || food.txTo() != null) return false;
        if (view.text() == null || !view.text().contains(food.text())) return false;
        if (at(AS_OF_VALID).isBefore(at(food.validTo()))) return false;
        List<String> cited = evidence == null ? List.of() : evidence;
        return !cited.contains(food.id()) && obsoleteIds(view).contains(food.id());
    }

    /** The fact is no longer claimed, and transaction-time names the revoke instant. */
    static boolean revokePaired(View view, List<String> evidence) {
        Stamp note = view.stamp(NOTE_ID);
        if (note == null || note.txFrom() == null || note.txTo() == null) return false;
        if (view.text() == null || !view.text().contains(note.text())) return false;
        if (at(AS_OF_TX).isBefore(at(note.txTo())) || current(note)) return false;
        List<String> cited = evidence == null ? List.of() : evidence;
        return !cited.contains(note.id()) && obsoleteIds(view).contains(note.id());
    }

    private static boolean recorded(Stamp stamp) {
        return stamp != null && stamp.txFrom() != null && !at(AS_OF_TX).isBefore(at(stamp.txFrom()));
    }

    private static Instant at(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    private static Stamp liveFood() {
        return new Stamp(FOOD_ID, "2026-10-01T00:00:00+08:00", null,
                "2026-10-01T00:00:00+08:00", null, "常点 " + PRODUCT);
    }

    private static Stamp expiredFood() {
        return new Stamp(FOOD_ID, "2026-10-01T00:00:00+08:00", "2026-10-15T00:00:00+08:00",
                "2026-10-01T00:00:00+08:00", null, "常点 " + PRODUCT);
    }

    private static Stamp liveNote() {
        return new Stamp(NOTE_ID, "2026-10-01T00:00:00+08:00", null,
                "2026-10-01T00:00:00+08:00", null, "取餐备注 " + NOTE);
    }

    private static Stamp revokedNote() {
        return new Stamp(NOTE_ID, "2026-10-01T00:00:00+08:00", null,
                "2026-10-01T00:00:00+08:00", "2026-10-20T09:00:00+08:00", "取餐备注 " + NOTE);
    }

    private static Stamp liveHabit() {
        return new Stamp(HABIT_ID, "2026-10-02T00:00:00+08:00", null,
                "2026-10-01T00:00:00+08:00", null, "auto-bind-coupons");
    }

    private static String renderFood(Stamp stamp) {
        var text = new StringBuilder(stamp.text()).append(" id=").append(stamp.id());
        text.append(" valid_from=").append(stamp.validFrom());
        if (stamp.validTo() != null) text.append(" valid_to=").append(stamp.validTo());
        text.append(" tx_from=").append(stamp.txFrom());
        if (stamp.txTo() != null) text.append(" tx_to=").append(stamp.txTo());
        return text.toString();
    }

    private static String habitTitle(Stamp stamp) {
        return "自动帮你领取可用优惠券 id=" + stamp.id()
                + " valid_from=" + stamp.validFrom()
                + " tx_from=" + stamp.txFrom();
    }

    /** Episode recall keeps at most 100 characters. The original note has to stay inside that window. */
    private static String episodeBody(Stamp stamp) {
        var body = new StringBuilder(stamp.text()).append(" id=").append(stamp.id());
        body.append(stamp.txTo() == null ? " tx_from=" + stamp.txFrom() : " tx_to=" + stamp.txTo());
        if (body.length() > 100) throw new IllegalStateException("episode stamp exceeds recall limit");
        return body.toString();
    }

    private static AutomationRule rule(String title, AutomationRule.Status status, String reason) {
        return new AutomationRule("rule-1", title, "auto-bind-coupons", "session-start",
                status, reason, 1L, status == AutomationRule.Status.ACTIVE ? 2L : null);
    }

    private static String render(List<ProfilePage> pages, List<AutomationRule> active, List<AutomationRule> proposed,
                                 boolean rag, String message, Stamp episode) {
        var profiles = mock(ProfileStore.class);
        when(profiles.pages()).thenReturn(pages);
        when(profiles.render(anyList())).thenAnswer(invocation -> {
            List<ProfilePage> selected = invocation.getArgument(0);
            return selected.stream().map(ProfilePage::content).collect(Collectors.joining("\n"));
        });
        var embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyList())).thenReturn(Optional.of(List.of(new float[] {1f, 0f})));
        var vectors = mock(VectorStore.class);
        when(vectors.size()).thenReturn(rag ? 1 : 0);
        when(vectors.search(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(rag ? List.of(new VectorStore.ScoredId("note", .9)) : List.of());
        var episodes = mock(EpisodeStore.class);
        when(episodes.findByIds(anyList())).thenReturn(episode == null ? List.of() : List.of(new EpisodeStore.Episode(
                episode.id(), "s", 1_700_000_000_000L, "user", episodeBody(episode))));
        var injector = new ProfileInjector(profiles, new DomainAliasRegistry("./skills"), (session, event) -> {},
                mock(Model.class), new ObjectMapper(), embeddings, Runnable::run, true, false, false, .35, 30);
        var automations = mock(AutomationStore.class);
        when(automations.active()).thenReturn(active);
        when(automations.pending()).thenReturn(proposed);
        var memory = new MioLongTermMemory(automations, injector, new EpisodeRetriever(true, 3, .6, episodes, vectors, embeddings),
                embeddings, episodes);
        return memory.retrieve(Msg.builder().role(MsgRole.USER).textContent(message).build()).block();
    }

    private ScriptedMemory() {}
}
