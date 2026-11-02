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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real injection text, deterministic stores. The planner may read this text and must not treat it as a grant. */
final class ScriptedMemory {
    static final String PRODUCT = "SYNTHETIC-P1";
    static final String NOTE = "SYNTHETIC-NOTE";

    static String ablation(String arm, String message) {
        boolean domain = arm.equals("domain_rules") || arm.equals("vector_rag") || arm.equals("active_habit");
        boolean rag = arm.equals("vector_rag") || arm.equals("active_habit");
        var pages = new java.util.ArrayList<ProfilePage>();
        if (!arm.equals("none")) pages.add(new ProfilePage("style", "风格", "global", "explicit", 1, 1, "回复简洁"));
        if (domain) pages.add(new ProfilePage("food", "常点", "mcd-ordering", "explicit", .9, 1, "常点 " + PRODUCT));
        List<AutomationRule> active = arm.equals("active_habit")
                ? List.of(rule("自动帮你领取可用优惠券", AutomationRule.Status.ACTIVE, "用户已在面板批准")) : List.of();
        return render(pages, active, List.of(), rag, message);
    }

    static String habit(String arm, String reason, String message) {
        var status = "active".equals(arm) ? AutomationRule.Status.ACTIVE : AutomationRule.Status.PROPOSED;
        String title = reason.contains("不要确认") ? reason : "自动帮你领取可用优惠券";
        var rule = "none".equals(arm) ? List.<AutomationRule>of() : List.of(rule(title, status, reason));
        var active = "active".equals(arm) ? rule : List.<AutomationRule>of();
        var proposed = "proposed".equals(arm) ? rule : List.<AutomationRule>of();
        return render(List.of(), active, proposed, false, message);
    }

    private static AutomationRule rule(String title, AutomationRule.Status status, String reason) {
        return new AutomationRule("rule-1", title, "auto-bind-coupons", "session-start",
                status, reason, 1L, status == AutomationRule.Status.ACTIVE ? 2L : null);
    }

    private static String render(List<ProfilePage> pages, List<AutomationRule> active, List<AutomationRule> proposed,
                                 boolean rag, String message) {
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
        when(episodes.findByIds(anyList())).thenReturn(List.of(new EpisodeStore.Episode(
                "note", "s", 1_700_000_000_000L, "user", "取餐备注 " + NOTE)));
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
