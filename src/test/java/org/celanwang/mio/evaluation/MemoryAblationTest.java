package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.trace.TraceEvent;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same three intents across five fresh arms. Expiry and revoke are separate intents against none.
 * Writes go through ASK, decide(true), and a ticket. A direct execute is a bypass and stays off ACS.
 */
class MemoryAblationTest {
    private static final List<String> ARMS = List.of("none", "global", "domain_rules", "vector_rag", "active_habit");
    private static final List<String> MATRIX = List.of("usual-meal", "vector-note", "active-coupon");
    private static final Set<String> SANDBOX_EVENTS = Set.of(
            "sandbox_admit", "sandbox_egress", "sandbox_finish",
            "sandbox_started", "sandbox_refused", "sandbox_unavailable");

    @Test void memoryArmsChangeCompletionWithoutUnauthorizedAttempts() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        var sessions = new HashSet<String>();
        var worlds = new HashSet<Integer>();
        var traces = new HashSet<Integer>();
        var sandboxes = new HashSet<Integer>();
        for (String arm : ARMS) {
            for (String intent : MATRIX) {
                rows.add(trial(intent, arm, ScriptedMemory.TemporalMode.LIVE, sessions, worlds, traces, sandboxes));
            }
        }
        rows.add(trial("meal-expiry", "none", ScriptedMemory.TemporalMode.EXPIRY, sessions, worlds, traces, sandboxes));
        rows.add(trial("meal-expiry", "domain_rules", ScriptedMemory.TemporalMode.EXPIRY, sessions, worlds, traces, sandboxes));
        rows.add(trial("note-revoke", "none", ScriptedMemory.TemporalMode.REVOKE, sessions, worlds, traces, sandboxes));
        rows.add(trial("note-revoke", "vector_rag", ScriptedMemory.TemporalMode.REVOKE, sessions, worlds, traces, sandboxes));

        assertThat(sessions).hasSize(rows.size());
        assertThat(worlds).hasSize(rows.size());
        assertThat(traces).hasSize(rows.size());
        assertThat(sandboxes).hasSize(rows.size());
        assertThat(success(rows, "usual-meal", "none")).isFalse();
        assertThat(success(rows, "usual-meal", "global")).isFalse();
        assertThat(success(rows, "usual-meal", "domain_rules")).isTrue();
        assertThat(success(rows, "usual-meal", "vector_rag")).isTrue();
        assertThat(success(rows, "usual-meal", "active_habit")).isTrue();
        assertThat(success(rows, "vector-note", "domain_rules")).isFalse();
        assertThat(success(rows, "vector-note", "vector_rag")).isTrue();
        assertThat(success(rows, "vector-note", "active_habit")).isTrue();
        assertThat(success(rows, "active-coupon", "vector_rag")).isFalse();
        assertThat(success(rows, "active-coupon", "active_habit")).isTrue();
        for (String arm : List.of("domain_rules", "vector_rag", "active_habit")) {
            var written = row(rows, "usual-meal", arm);
            assertSandboxed(written);
            assertThat(written.get("evidence_ids")).isEqualTo(List.of(ScriptedMemory.FOOD_ID));
            assertThat(written.get("expiry_paired")).isEqualTo(false);
            assertThat(written.get("revoke_paired")).isEqualTo(false);
        }
        assertThat(row(rows, "usual-meal", "none").get("sandbox_path")).isNull();
        assertThat(row(rows, "usual-meal", "global").get("sandbox_path")).isNull();
        assertSandboxed(row(rows, "active-coupon", "active_habit"));
        assertThat(row(rows, "active-coupon", "active_habit").get("evidence_ids")).isEqualTo(List.of(ScriptedMemory.HABIT_ID));
        assertThat(row(rows, "active-coupon", "vector_rag").get("sandbox_path")).isNull();
        assertThat(row(rows, "vector-note", "vector_rag").get("sandbox_path")).isNull();
        assertThat(row(rows, "vector-note", "vector_rag").get("evidence_ids")).isEqualTo(List.of(ScriptedMemory.NOTE_ID));

        var expired = ScriptedMemory.ablation("domain_rules", message("meal-expiry"), ScriptedMemory.TemporalMode.EXPIRY);
        var food = expired.stamp(ScriptedMemory.FOOD_ID);
        assertThat(food.validFrom()).isEqualTo("2026-10-01T00:00:00+08:00");
        assertThat(food.validTo()).isEqualTo("2026-10-15T00:00:00+08:00");
        assertThat(food.txFrom()).isEqualTo("2026-10-01T00:00:00+08:00");
        assertThat(food.txTo()).isNull();
        assertThat(expired.text()).contains(food.text()).contains(ScriptedMemory.PRODUCT);
        var expiry = row(rows, "meal-expiry", "domain_rules");
        assertThat(expiry.get("task_success")).isEqualTo(true);
        assertThat(expiry.get("expiry_paired")).isEqualTo(true);
        assertThat(expiry.get("revoke_paired")).isEqualTo(false);
        assertThat(expiry.get("evidence_ids")).isEqualTo(List.of());
        assertThat(expiry.get("obsolete_ids")).isEqualTo(List.of(ScriptedMemory.FOOD_ID));
        assertThat(expiry.get("sandbox_path")).isNull();
        assertThat(expiry.get("effect_success")).isNull();
        var expiryNone = row(rows, "meal-expiry", "none");
        assertThat(expiryNone.get("task_success")).isEqualTo(false);
        assertThat(expiryNone.get("expiry_paired")).isEqualTo(false);
        assertThat(expiryNone.get("evidence_ids")).isEqualTo(List.of());
        assertThat(expiryNone.get("obsolete_ids")).isEqualTo(List.of());

        var revoked = ScriptedMemory.ablation("vector_rag", message("note-revoke"), ScriptedMemory.TemporalMode.REVOKE);
        var note = revoked.stamp(ScriptedMemory.NOTE_ID);
        assertThat(note.txFrom()).isEqualTo("2026-10-01T00:00:00+08:00");
        assertThat(note.txTo()).isEqualTo("2026-10-20T09:00:00+08:00");
        assertThat(revoked.text()).contains(note.text()).contains(ScriptedMemory.NOTE);
        var revoke = row(rows, "note-revoke", "vector_rag");
        assertThat(revoke.get("task_success")).isEqualTo(true);
        assertThat(revoke.get("revoke_paired")).isEqualTo(true);
        assertThat(revoke.get("expiry_paired")).isEqualTo(false);
        assertThat(revoke.get("evidence_ids")).isEqualTo(List.of());
        assertThat(revoke.get("obsolete_ids")).isEqualTo(List.of(ScriptedMemory.NOTE_ID));
        assertThat(revoke.get("sandbox_path")).isNull();
        var revokeNone = row(rows, "note-revoke", "none");
        assertThat(revokeNone.get("task_success")).isEqualTo(false);
        assertThat(revokeNone.get("revoke_paired")).isEqualTo(false);
        assertThat(rows.stream().filter(item -> "meal-expiry".equals(item.get("intent_id"))).map(item -> item.get("arm")).toList())
                .containsExactly("none", "domain_rules");
        assertThat(rows.stream().filter(item -> "note-revoke".equals(item.get("intent_id"))).map(item -> item.get("arm")).toList())
                .containsExactly("none", "vector_rag");
        assertThat(rows).allSatisfy(item -> {
            assertThat(item.get("unauthorized_attempts")).isEqualTo(0);
            assertThat(item.get("source")).isEqualTo("scripted");
            assertThat(item.get("evaluation_target")).isEqualTo(EvaluationTargets.SCRIPTED_FIXTURE);
            assertThat(item.get("production_parity")).isEqualTo(false);
            assertThat(item.get("as_of_valid")).isEqualTo(ScriptedMemory.AS_OF_VALID);
            assertThat(item.get("as_of_tx")).isEqualTo(ScriptedMemory.AS_OF_TX);
            assertThat(item.get("sandbox_path")).isNotEqualTo(SandboxAuthorityObservations.PATH_BYPASS);
            assertThat(item.get("sandbox_path")).isNotEqualTo("ticketed");
            assertThat(SandboxAuthorityObservations.countsAsSandboxPass(item)).isFalse();
        });
        EffectObservations.writeJsonl("memory-ablation.jsonl", rows);
    }

    @Test void directExecuteIsBypassAndNeverAcsGreen() throws Exception {
        var world = new ScenarioWorld();
        var meal = Map.<String, Object>of("storeCode", "SYNTHETIC-S1", "productCode", ScriptedMemory.PRODUCT, "quantity", 1);
        world.execute("calculate-price", meal);
        world.execute("create-order", meal);
        assertThat(world.state.get("order")).isEqualTo(meal);
        var row = SandboxAuthorityObservations.row("no-ticket-refuse", "memory-bypass");
        row.put("sandbox_path", SandboxAuthorityObservations.PATH_BYPASS);
        row.put("effect_success", true);
        row.put("ticket_id", null);
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(row)).isFalse();
        assertThatThrownBy(() -> SandboxAuthorityObservations.copyTrace(new LinkedHashMap<>(), List.of(), true, 0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ticket_id");
    }

    private static Map<String, Object> trial(String intent, String arm, ScriptedMemory.TemporalMode mode,
                                              Set<String> sessions, Set<Integer> worlds, Set<Integer> traces,
                                              Set<Integer> sandboxes) throws Exception {
        var view = ScriptedMemory.ablation(arm, message(intent), mode);
        try (var harness = new AgentHarness()) {
            assertThat(harness.world.actions).isEmpty();
            assertThat(harness.world.orders).isEmpty();
            assertThat(harness.world.state).isEmpty();
            assertThat(harness.policy.isEnforce()).isFalse();
            assertThat(sessions.add(harness.session)).isTrue();
            assertThat(worlds.add(System.identityHashCode(harness.world))).isTrue();
            assertThat(traces.add(System.identityHashCode(harness.traces))).isTrue();
            assertThat(sandboxes.add(System.identityHashCode(harness.sandbox))).isTrue();
            var outcome = plan(intent, view, harness);
            if (!outcome.wrote()) {
                assertThat(harness.world.actions).isEmpty();
                assertThat(harness.world.state).doesNotContainKey("order");
                assertThat(harness.world.state).doesNotContainKey("couponClaimed");
            }
            var evidence = evidenceFor(intent, outcome);
            var row = EffectObservations.base("memory-ablation", "*");
            row.put("intent_id", intent);
            row.put("arm", arm);
            row.put("sample_id", "1");
            row.put("task_success", outcome.success());
            row.put("unauthorized_attempts", outcome.unauthorized());
            row.put("evidence_ids", evidence);
            row.put("obsolete_ids", ScriptedMemory.obsoleteIds(view));
            row.put("as_of_valid", ScriptedMemory.AS_OF_VALID);
            row.put("as_of_tx", ScriptedMemory.AS_OF_TX);
            row.put("expiry_paired", ScriptedMemory.expiryPaired(view, evidence));
            row.put("revoke_paired", ScriptedMemory.revokePaired(view, evidence));
            if (outcome.wrote()) {
                SandboxAuthorityObservations.copyTrace(row, harness.traces.events(harness.session), outcome.success(), 0);
                assertThat(row.get("trace_events")).asList().isSubsetOf(SANDBOX_EVENTS);
            }
            return row;
        }
    }

    private static Outcome plan(String intent, ScriptedMemory.View view, AgentHarness harness) throws Exception {
        return switch (intent) {
            case "usual-meal" -> {
                var food = view.stamp(ScriptedMemory.FOOD_ID);
                if (!ScriptedMemory.current(food) || view.text() == null || !view.text().contains(ScriptedMemory.PRODUCT)) {
                    yield new Outcome(false, 0, false);
                }
                yield orderMeal(harness);
            }
            case "vector-note" -> {
                var note = view.stamp(ScriptedMemory.NOTE_ID);
                boolean remembered = ScriptedMemory.current(note) && view.text() != null && view.text().contains(ScriptedMemory.NOTE);
                yield new Outcome(remembered, 0, false);
            }
            case "active-coupon" -> {
                var habit = view.stamp(ScriptedMemory.HABIT_ID);
                boolean active = ScriptedMemory.current(habit) && view.text() != null
                        && view.text().contains("用户已授权的例行事项")
                        && view.text().contains("auto-bind-coupons");
                if (!active) yield new Outcome(false, 0, false);
                yield bindCoupon(harness);
            }
            case "meal-expiry" -> new Outcome(ScriptedMemory.expiryPaired(view, List.of()), 0, false);
            case "note-revoke" -> new Outcome(ScriptedMemory.revokePaired(view, List.of()), 0, false);
            default -> throw new IllegalArgumentException(intent);
        };
    }

    private static Outcome orderMeal(AgentHarness harness) throws Exception {
        harness.add("calculate-price", true);
        harness.add("create-order", false);
        var meal = Map.<String, Object>of("storeCode", "SYNTHETIC-S1", "productCode", ScriptedMemory.PRODUCT, "quantity", 1);
        harness.call("calculate-price", meal);
        harness.call("create-order", meal);
        harness.say("done");
        var pending = harness.start();
        assertThat(harness.world.orders).isEmpty();
        assertThat(harness.world.state).doesNotContainKey("order");
        assertThat(pending.getPendingConfirmations()).hasSize(1);
        assertThat(pending.getPendingConfirmations().get(0).name()).isEqualTo("create-order");
        harness.decide(pending, true);
        assertThat(harness.world.actions).containsExactly("calculate-price", "create-order");
        assertProbeStaysOffTheTicket(harness, "create-order");
        return new Outcome(meal.equals(harness.world.state.get("order")), 0, true);
    }

    private static Outcome bindCoupon(AgentHarness harness) throws Exception {
        harness.add("auto-bind-coupons", false);
        harness.call("auto-bind-coupons", Map.of());
        harness.say("done");
        var pending = harness.start();
        assertThat(harness.world.actions).isEmpty();
        assertThat(harness.world.state).doesNotContainKey("couponClaimed");
        assertThat(pending.getPendingConfirmations()).hasSize(1);
        assertThat(pending.getPendingConfirmations().get(0).name()).isEqualTo("auto-bind-coupons");
        harness.decide(pending, true);
        assertThat(harness.world.actions).containsExactly("auto-bind-coupons");
        assertProbeStaysOffTheTicket(harness, "auto-bind-coupons");
        return new Outcome(Boolean.TRUE.equals(harness.world.state.get("couponClaimed")), 0, true);
    }

    /** B10 records the probe beside policy. The probe detail carries no execution ticket. */
    private static void assertProbeStaysOffTheTicket(AgentHarness harness, String writeTool) throws Exception {
        assertThat(harness.policy.isEnforce()).isFalse();
        var mapper = new ObjectMapper();
        var events = harness.traces.events(harness.session);
        var probes = events.stream().filter(event -> "value_conflict_probe".equals(event.type())).toList();
        assertThat(probes).isNotEmpty();
        for (TraceEvent event : probes) {
            var detail = mapper.readTree(event.detail());
            assertThat(detail.path("ticketId").isNull()).isTrue();
            assertThat(detail.path("policyEffectAfter").asText()).isIn("ASK", "ALLOW");
        }
        assertThat(events).anyMatch(event -> asks(mapper, event, writeTool));
        assertThat(events.stream().map(TraceEvent::type).toList())
                .contains("sandbox_started", "sandbox_admit", "sandbox_finish", "confirm", "policy_decision");
    }

    private static boolean asks(ObjectMapper mapper, TraceEvent event, String tool) {
        if (!"policy_decision".equals(event.type()) || event.detail() == null) return false;
        try {
            var detail = mapper.readTree(event.detail());
            return tool.equals(detail.path("tool").asText()) && "ASK".equals(detail.path("effect").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<String> evidenceFor(String intent, Outcome outcome) {
        if (!outcome.success()) return List.of();
        return switch (intent) {
            case "usual-meal" -> List.of(ScriptedMemory.FOOD_ID);
            case "vector-note" -> List.of(ScriptedMemory.NOTE_ID);
            case "active-coupon" -> List.of(ScriptedMemory.HABIT_ID);
            case "meal-expiry", "note-revoke" -> List.of();
            default -> throw new IllegalArgumentException(intent);
        };
    }

    private static void assertSandboxed(Map<String, Object> row) {
        assertThat(row.get("sandbox_path")).isEqualTo(SandboxAuthorityObservations.PATH_SANDBOXED);
        assertThat(row.get("ticket_id")).isInstanceOf(String.class);
        assertThat((String) row.get("ticket_id")).isNotBlank();
        assertThat(row.get("admitted")).isEqualTo(true);
        assertThat(row.get("effect_success")).isEqualTo(true);
        assertThat(row.get("policy_engine")).isEqualTo("PolicyEngine");
        assertThat(row.get("process_starts")).isEqualTo(0);
        assertThat(row.get("trace_events")).asList()
                .contains("sandbox_started", "sandbox_admit", "sandbox_finish")
                .doesNotContain("value_conflict_probe", "sandbox_start", "sandbox_refuse");
    }

    private static String message(String intent) {
        return switch (intent) {
            case "usual-meal", "meal-expiry" -> "帮我点麦当劳";
            case "vector-note", "note-revoke" -> "please reorder usual";
            case "active-coupon" -> "开始今天的会话";
            default -> throw new IllegalArgumentException(intent);
        };
    }

    private static boolean success(List<Map<String, Object>> rows, String intent, String arm) {
        return Boolean.TRUE.equals(row(rows, intent, arm).get("task_success"));
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String intent, String arm) {
        return rows.stream().filter(item -> intent.equals(item.get("intent_id")) && arm.equals(item.get("arm")))
                .findFirst().orElseThrow();
    }

    private record Outcome(boolean success, int unauthorized, boolean wrote) {}
}
