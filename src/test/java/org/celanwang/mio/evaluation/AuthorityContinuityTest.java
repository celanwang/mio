package org.celanwang.mio.evaluation;

import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exports the policy-continuity rules already covered by ScenarioIntegrationTest.
 * Sandbox ACS rows are merged in by SandboxAuthorityTest. A green file is ACS material, not enforce approval.
 */
class AuthorityContinuityTest {
    static final Map<String, Object> MEAL = Map.of("storeCode", "SYNTHETIC-S1", "productCode", "SYNTHETIC-P1", "quantity", 1);

    @Test void exportAuthorityContinuityObservations() throws Exception {
        approvalIsNotReusableAndPlaintextCannotResume();
        irreversibleWritesNeverAutoRun();
        cancellationStaysHuman();
        malformedJevFallsBackToHuman();
        denialLeavesZeroExecutions();
        var rows = new ArrayList<Map<String, Object>>();
        for (String rule : List.of(
                "approval-not-reusable", "plaintext-cannot-restore-pending", "irreversible-never-jev-auto",
                "cancel-always-human", "malformed-jev-to-human", "post-deny-executions-zero")) {
            var row = EffectObservations.base("authority", "*");
            row.put("rule_id", rule);
            row.put("sample_id", "1");
            row.put("passed", true);
            rows.add(row);
        }
        EffectObservations.mergeJsonl("authority-continuity.jsonl", rows, "rule_id");
    }

    private static void approvalIsNotReusableAndPlaintextCannotResume() {
        try (var harness = new AgentHarness()) {
            harness.add("unseen_write", false);
            harness.call("unseen_write", Map.of("n", 1));
            harness.call("unseen_write", Map.of("n", 2));
            harness.say("done");
            var first = harness.start();
            assertThat(harness.start().getPendingConfirmations()).hasSize(1);
            assertThat(harness.world.actions).isEmpty();
            var second = harness.decide(first, true);
            assertThat(second.getPendingConfirmations()).hasSize(1);
            assertThat(harness.world.actions).containsExactly("unseen_write");
            harness.decide(second, false);
            assertThat(harness.world.actions).containsExactly("unseen_write");
            assertThatThrownBy(() -> harness.decide(first, true)).hasMessageContaining("409");
        }
    }

    private static void irreversibleWritesNeverAutoRun() {
        var jev = confidentJev();
        for (String name : List.of("create-order", "unseen_write", "eval_publish_weibo")) {
            try (var harness = new AgentHarness(jev)) {
                harness.add(name, false);
                harness.call(name, Map.of());
                harness.say("done");
                assertThat(harness.start().getPendingConfirmations()).hasSize(1);
                assertThat(harness.world.actions).isEmpty();
                verifyNoInteractions(jev);
            }
        }
    }

    private static void cancellationStaysHuman() throws Exception {
        var jev = confidentJev();
        try (var harness = new AgentHarness(jev)) {
            harness.addRide();
            harness.call("tencent_ride", Map.of("command", "check-cancel-fee", "parameters", Map.of()));
            harness.call("tencent_ride", Map.of("command", "cancel-order", "parameters", Map.of("orderId", "9007199254740995", "amountFen", 500), "summary", "取消订单，费用5元"));
            harness.say("done");
            var pending = harness.start();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            assertThat(harness.world.state).doesNotContainKey("cancelled");
            verifyNoInteractions(jev);
            harness.decide(pending, false);
            assertThat(harness.world.state).doesNotContainKey("cancelled");
        }
    }

    private static void malformedJevFallsBackToHuman() {
        for (double probability : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 1.01, -0.01}) {
            var jev = mock(JevClient.class);
            when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock", Map.of("consistent", new NoulAnswer(probability)), null)));
            try (var harness = new AgentHarness(jev)) {
                harness.add("auto-bind-coupons", false);
                harness.call("auto-bind-coupons", Map.of());
                harness.say("done");
                assertThat(harness.start().getPendingConfirmations()).hasSize(1);
                assertThat(harness.world.actions).isEmpty();
            }
        }
    }

    private static void denialLeavesZeroExecutions() {
        try (var harness = new AgentHarness()) {
            harness.add("query-meals", true);
            harness.add("calculate-price", true);
            harness.add("create-order", false);
            harness.call("query-meals", Map.of());
            harness.call("calculate-price", MEAL);
            harness.call("create-order", MEAL);
            harness.say("done");
            var pending = harness.start();
            assertThat(harness.world.orders).isEmpty();
            harness.decide(pending, false);
            assertThat(harness.world.orders).isEmpty();
            assertThat(harness.world.actions).doesNotContain("create-order");
        }
    }

    private static JevClient confidentJev() {
        var jev = mock(JevClient.class);
        when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock", Map.of("consistent", new NoulAnswer(.99)), null)));
        return jev;
    }
}
