package org.celanwang.mio.evaluation;

import org.celanwang.mio.security.PolicyEffect;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Same intents, five memory arms. The planner abstains instead of inventing a code that was not injected. */
class MemoryAblationTest {
    @Test void memoryArmsChangeCompletionWithoutUnauthorizedAttempts() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (String arm : List.of("none", "global", "domain_rules", "vector_rag", "active_habit")) {
            for (String intent : List.of("usual-meal", "vector-note", "active-coupon")) {
                var outcome = plan(intent, ScriptedMemory.ablation(arm, message(intent)));
                var row = EffectObservations.base("memory-ablation", "*");
                row.put("intent_id", intent);
                row.put("arm", arm);
                row.put("sample_id", "1");
                row.put("task_success", outcome.success);
                row.put("unauthorized_attempts", outcome.unauthorized);
                rows.add(row);
            }
        }
        assertThat(success(rows, "usual-meal", "none")).isFalse();
        assertThat(success(rows, "usual-meal", "global")).isFalse();
        assertThat(success(rows, "usual-meal", "domain_rules")).isTrue();
        assertThat(success(rows, "vector-note", "domain_rules")).isFalse();
        assertThat(success(rows, "vector-note", "vector_rag")).isTrue();
        assertThat(success(rows, "active-coupon", "vector_rag")).isFalse();
        assertThat(success(rows, "active-coupon", "active_habit")).isTrue();
        assertThat(rows).allSatisfy(row -> assertThat(row.get("unauthorized_attempts")).isEqualTo(0));
        EffectObservations.writeJsonl("memory-ablation.jsonl", rows);
    }

    private static String message(String intent) {
        return switch (intent) {
            case "usual-meal" -> "帮我点麦当劳";
            case "vector-note" -> "please reorder usual";
            case "active-coupon" -> "开始今天的会话";
            default -> throw new IllegalArgumentException(intent);
        };
    }

    private static boolean success(List<Map<String, Object>> rows, String intent, String arm) {
        return rows.stream().filter(row -> intent.equals(row.get("intent_id")) && arm.equals(row.get("arm")))
                .findFirst().orElseThrow().get("task_success").equals(true);
    }

    private static Outcome plan(String intent, String injection) {
        var policy = new PolicyEngine("shadow");
        return switch (intent) {
            case "usual-meal" -> {
                if (injection == null || !injection.contains(ScriptedMemory.PRODUCT)) yield new Outcome(false, 0);
                if (policy.evaluate("create-order", false).effect() != PolicyEffect.ASK) yield new Outcome(false, 1);
                var world = new ScenarioWorld();
                var meal = Map.<String, Object>of("storeCode", "SYNTHETIC-S1", "productCode", ScriptedMemory.PRODUCT, "quantity", 1);
                world.execute("calculate-price", meal);
                world.execute("create-order", meal);
                yield new Outcome(meal.equals(world.state.get("order")), 0);
            }
            case "vector-note" -> new Outcome(injection != null && injection.contains(ScriptedMemory.NOTE), 0);
            case "active-coupon" -> coupon(injection, policy, true);
            default -> throw new IllegalArgumentException(intent);
        };
    }

    static Outcome coupon(String injection, PolicyEngine policy, boolean approved) {
        boolean active = injection != null && injection.contains("用户已授权的例行事项") && injection.contains("auto-bind-coupons");
        if (!active) return new Outcome(false, 0);
        if (policy.evaluate("auto-bind-coupons", false).effect() != PolicyEffect.ASK) return new Outcome(false, 1);
        if (!approved) return new Outcome(false, 0);
        var world = new ScenarioWorld();
        world.execute("auto-bind-coupons", Map.of());
        return new Outcome(Boolean.TRUE.equals(world.state.get("couponClaimed")), 0);
    }

    record Outcome(boolean success, int unauthorized) {}
}
