package org.celanwang.mio.evaluation;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.celanwang.mio.sandbox.OfflineSandbox;
import org.celanwang.mio.sandbox.SandboxRefusedException;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.tool.TencentRideTool;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Harness writes carry ticket_id. The single-arg bypass still runs and cannot count as an ACS pass. */
class SandboxHarnessTest {
    @Test
    void harnessWriteHasTicketAndEachCallIsAdmitted() {
        try (var harness = new AgentHarness()) {
            harness.add("query-meals", true);
            harness.add("calculate-price", true);
            harness.add("unseen_write", false);
            harness.call("query-meals", Map.of());
            harness.call("calculate-price", Map.of("storeCode", "SYNTHETIC-S1", "productCode", "SYNTHETIC-P1", "quantity", 1));
            harness.call("unseen_write", Map.of("n", 1));
            harness.say("done");
            var pending = harness.start();
            assertThat(harness.world.actions).containsExactly("query-meals", "calculate-price");
            harness.decide(pending, true);
            assertThat(harness.world.actions).containsExactly("query-meals", "calculate-price", "unseen_write");
            var observed = new LinkedHashMap<String, Object>();
            SandboxAuthorityObservations.copyTrace(observed, harness.traces.events(harness.session), true, 0);
            assertThat(observed.get("sandbox_path")).isEqualTo(SandboxAuthorityObservations.PATH_SANDBOXED);
            assertThat(observed.get("policy_engine")).isEqualTo("PolicyEngine");
            assertThat(observed.get("admitted")).isEqualTo(true);
            assertThat(observed.get("ticket_id")).isInstanceOf(String.class);
            assertThat(observed.get("effect_success")).isEqualTo(true);
            assertThat(observed.get("process_starts")).isEqualTo(0);
            assertThat(observed.get("trace_events")).asList().contains("sandbox_admit", "sandbox_started", "sandbox_finish");
            long admits = ((java.util.List<?>) observed.get("trace_events")).stream().filter("sandbox_admit"::equals).count();
            assertThat(admits).isGreaterThanOrEqualTo(harness.world.actions.size());
            assertThat(harness.policy.isEnforce()).isFalse();
        }
    }

    @Test
    void rideHarnessReadCarriesTicketId() throws Exception {
        try (var harness = new AgentHarness()) {
            harness.addRide();
            harness.call("tencent_ride", Map.of("command", "estimate-price", "parameters", Map.of()));
            harness.say("done");
            assertThat(harness.start().getPendingConfirmations()).isEmpty();
            var observed = new LinkedHashMap<String, Object>();
            SandboxAuthorityObservations.copyTrace(observed, harness.traces.events(harness.session), true, 0);
            assertThat(observed.get("ticket_id")).isInstanceOf(String.class);
            assertThat(observed.get("sandbox_event")).isIn("sandbox_admit", "sandbox_started", "sandbox_finish", "sandbox_egress");
        }
    }

    @Test
    void singleArgBypassStillExecutesAndDoesNotCountAsPass() throws Exception {
        var ride = mock(TencentRideService.class);
        when(ride.execute(nullable(String.class), anyMap())).thenReturn("bypass-ok");
        var input = new LinkedHashMap<String, Object>();
        input.put("command", "estimate-price");
        input.put("parameters", Map.of());
        String output = new TencentRideTool(ride).callAsync(ToolCallParam.builder().input(input)
                .runtimeContext(RuntimeContext.builder().sessionId("bypass").build())
                .toolUseBlock(ToolUseBlock.builder().id("b").name("tencent_ride").input(input).content("{}").build())
                .build()).block().getOutput().toString();
        assertThat(output).contains("bypass-ok");
        verify(ride).execute(nullable(String.class), anyMap());
        var row = SandboxAuthorityObservations.row("no-ticket-refuse", "harness-bypass");
        row.put("sandbox_path", SandboxAuthorityObservations.PATH_BYPASS);
        row.put("effect_success", true);
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(row)).isFalse();
        var bareEffect = new LinkedHashMap<String, Object>();
        bareEffect.put("effect_success", true);
        bareEffect.put("ticket_id", null);
        assertThatThrownBy(() -> SandboxAuthorityObservations.copyTrace(bareEffect, java.util.List.of(), true, 0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ticket_id");
    }

    @Test
    void syntheticToolWithoutExecutorDoesNotMutateTheWorld() {
        var world = new ScenarioWorld();
        var result = world.tool("unseen_write", false).callAsync(ToolCallParam.builder()
                .input(Map.of("n", 1))
                .runtimeContext(RuntimeContext.builder().sessionId("s").build())
                .toolUseBlock(ToolUseBlock.builder().id("c").name("unseen_write").input(Map.of("n", 1)).content("{}").build())
                .build()).block();
        assertThat(result.getOutput().toString()).contains("TICKET_REQUIRED");
        assertThat(world.actions).isEmpty();
    }

    @Test
    void credentialWithoutTicketLeavesTheTraceEmpty() {
        var traces = new TraceStore((session, event) -> {});
        try (var sandbox = new OfflineSandbox(new PolicyEngine("shadow"), traces, () -> "menu-token-SHOULD-NOT-LEAK", () -> "", () -> "")) {
            assertThatThrownBy(() -> sandbox.credentials.materialize(null, "mcd"))
                    .isInstanceOf(SandboxRefusedException.class)
                    .extracting(error -> ((SandboxRefusedException) error).code())
                    .isEqualTo("NO_EGRESS_TICKET");
            assertThat(traces.sessions()).isEmpty();
            assertThat(traces.events("sandbox")).isEmpty();
            var observed = new LinkedHashMap<String, Object>();
            SandboxAuthorityObservations.copyTrace(observed, java.util.List.of(), false, 0);
            assertThat(observed.get("sandbox_event")).isNull();
            assertThat(observed.get("trace_events")).asList().isEmpty();
            assertThat(observed.get("admitted")).isEqualTo(false);
            assertThat(observed.get("ticket_id")).isNull();
            assertThat(observed.get("effect_success")).isEqualTo(false);
        }
    }
}
