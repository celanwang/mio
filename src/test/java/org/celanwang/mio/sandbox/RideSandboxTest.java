package org.celanwang.mio.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.celanwang.mio.config.TencentRideProperties;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.tool.TencentRideCreateOrderTool;
import org.celanwang.mio.tool.TencentRideTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RideSandboxTest {
    @TempDir Path temp;

    @Test
    void quoteIsSandboxedAndBookingWithoutATicketNeverStartsAProcess() throws Exception {
        Harness harness = harness();
        String quote = harness.service.execute("session-1", Map.of("command", "estimate-price", "parameters", Map.of()));
        assertThat(harness.sandbox.launches).as(quote + "\n" + harness.stack.audit.text()).isEqualTo(1);
        assertThat(quote).contains("[REDACTED]").doesNotContain("synthetic-ride-token");
        assertThat(harness.sandbox.last.networkAllowed()).isTrue();
        assertThat(harness.sandbox.last.hostCredentialDirs()).isFalse();
        assertThat(harness.sandbox.last.command()).doesNotContain("synthetic-ride-token");
        assertThat(harness.sandbox.last.command().get(0)).isNotEqualTo("python");
        assertThat(harness.sandbox.last.mounts()).allSatisfy(mount -> {
            assertThat(mount.source().toString()).doesNotContain(".env").doesNotContain(".weibo").doesNotContain(".ssh");
        });
        assertThat(harness.sandbox.last.mounts()).anyMatch(mount -> mount.readOnly() && mount.source().endsWith("runtime"));
        assertThat(harness.stack.audit.text()).doesNotContain("synthetic-ride-token");

        harness.sandbox.launches = 0;
        String refused = harness.service.execute("session-1", Map.of("command", "create-order", "parameters", Map.of()));
        assertThat(refused).contains("缺少执行票据");
        assertThat(harness.sandbox.launches).isZero();
    }

    @Test
    void sandboxStartFailureDoesNotFallBackToTheHost() throws Exception {
        Harness harness = harness();
        harness.sandbox.fail = true;
        String result = harness.service.execute("session-1", Map.of("command", "query-order", "parameters", Map.of()));
        assertThat(result).contains("未回退到宿主机");
        assertThat(harness.sandbox.launches).isZero();
    }

    @Test
    void createOrderToolIssuesAHumanTicketBeforeTheProcessStarts() {
        Harness harness = harness();
        var tool = new TencentRideCreateOrderTool(harness.service, harness.stack.executor);
        var input = Map.of("price_estimate_keys", List.of("quote-key"), "stateVersion", "v", "summary", "from A to B");
        String result = tool.callAsync(ToolCallParam.builder().input(input)
                .runtimeContext(RuntimeContext.builder().sessionId("session-1").build())
                .toolUseBlock(ToolUseBlock.builder().id("order-1").name("tencent_ride_create_order").input(input).content("{}").build())
                .build()).block().getOutput().toString();
        assertThat(harness.sandbox.launches).isEqualTo(1);
        assertThat(harness.sandbox.last.ticket().humanConfirmed()).isTrue();
        assertThat(harness.sandbox.last.ticket().risk()).isEqualTo(RiskClass.IRREVERSIBLE);
        assertThat(harness.sandbox.last.ticket().runtime()).isEqualTo(RuntimeKind.SUBPROCESS);
        assertThat(result).contains("[REDACTED]").doesNotContain("synthetic-ride-token");
        assertThatThrownByTicketReuse(harness);
    }

    @Test
    void anotherPrincipalDoesNotReceiveTheLocalOwnerRideDirectory() throws Exception {
        Harness harness = harness();
        ExecutionTicket ticket = harness.stack.issuer.issue(new Admission(new PrincipalId("rider-b"), "session-1", "c",
                "tencent_ride", Map.of("command", "estimate-price"), false, true, "tencent-ride", null, null));
        harness.service.execute("session-1", Map.of("command", "estimate-price", "parameters", Map.of()), ticket);
        assertThat(harness.sandbox.last.cwd().toString()).contains("rider-b").doesNotContain(".tencent-ride");
        assertThat(harness.sandbox.last.cwd().startsWith(harness.stack.workspace.principalRoot(PrincipalId.LOCAL_OWNER))).isFalse();
    }

    @Test
    void readToolStillGoesThroughTheSandboxWhenWrapped() {
        Harness harness = harness();
        var tool = new TencentRideTool(harness.service, harness.stack.executor);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("command", "estimate-price");
        tool.callAsync(ToolCallParam.builder().input(input)
                .runtimeContext(RuntimeContext.builder().sessionId("session-1").build())
                .toolUseBlock(ToolUseBlock.builder().id("quote-1").name("tencent_ride").input(input).content("{}").build())
                .build()).block();
        assertThat(harness.sandbox.launches).isEqualTo(1);
        assertThat(harness.sandbox.last.ticket().risk()).isEqualTo(RiskClass.READONLY);
    }

    private void assertThatThrownByTicketReuse(Harness harness) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.sandbox.last.ticket().authorizeSubprocess())
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REUSED");
    }

    private Harness harness() {
        SandboxStack stack = new SandboxStack(temp.resolve("ws"), new PolicyEngine("shadow"), Clock.systemUTC(),
                () -> "", () -> "synthetic-ride-token", () -> "");
        RecordingSandbox sandbox = new RecordingSandbox();
        TencentRideProperties properties = new TencentRideProperties();
        properties.setEnabled(true);
        properties.setToken("synthetic-ride-token");
        properties.setPython("/usr/bin/true");
        properties.setDataDirectory(temp.resolve("ride-data"));
        TencentRideService service = new TencentRideService(properties, new ObjectMapper(), "skills",
                stack.executor, sandbox, stack.credentials, stack.workspace, stack.audit, stack.scenes);
        return new Harness(stack, sandbox, service);
    }

    private record Harness(SandboxStack stack, RecordingSandbox sandbox, TencentRideService service) {
    }

    private static final class RecordingSandbox implements ProcessSandbox {
        ProcessLaunch last;
        int launches;
        boolean fail;
        final List<String> commands = new ArrayList<>();

        @Override
        public List<String> plan(ProcessLaunch launch) {
            return List.of("sandboxed");
        }

        @Override
        public Process launch(ProcessLaunch launch) throws java.io.IOException {
            if (fail) {
                throw new SandboxUnavailableException("SANDBOX_UNAVAILABLE", "bubblewrap missing");
            }
            launch.ticket().authorizeSubprocess();
            last = launch;
            launches++;
            commands.add(String.join(" ", launch.command()));
            ProcessBuilder builder = new ProcessBuilder("/bin/cat");
            builder.redirectErrorStream(true);
            builder.redirectOutput(launch.stdout().toFile());
            return builder.start();
        }
    }
}
