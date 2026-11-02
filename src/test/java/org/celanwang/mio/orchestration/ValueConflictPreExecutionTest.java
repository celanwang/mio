package org.celanwang.mio.orchestration;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.celanwang.mio.align.ConflictCode;
import org.celanwang.mio.align.ProbeResult;
import org.celanwang.mio.align.ValueConflictAdvisor;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A jev-reviewable write whose own check returns ALLOW would run inside agent.call,
 * before ChatService.auditPolicyDecisions. The guard must stop that body first.
 */
class ValueConflictPreExecutionTest {
    @Test
    void suggestAskStopsAWriteThatPermissionWouldAllow() {
        assertThat(run(true).executions).isZero();
        assertThat(run(false).executions).isEqualTo(1);
    }

    private static Run run(boolean conflict) {
        AtomicInteger executions = new AtomicInteger();
        ToolBase delegate = new ToolBase(ToolBase.builder().name("auto-bind-coupons").description("reversible write")
                .readOnly(false).inputSchema(Map.of("type", "object"))) {
            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
                return Mono.just(PermissionDecision.allow("delegate would execute now"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                return Mono.fromCallable(() -> {
                    executions.incrementAndGet();
                    return ToolResultBlock.text("ran");
                });
            }
        };
        var execution = mock(TaskExecution.class);
        var task = mock(TaskRecord.class);
        when(execution.businessActive()).thenReturn(task);
        when(execution.budget()).thenReturn(mock(RunBudget.class));
        when(execution.observationStarting(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
        when(execution.beforeEffect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(null);
        when(task.sessionId()).thenReturn("goal-session");
        when(task.spec()).thenReturn(new TaskSpec("bind", "domain", "领券", List.of(), List.of(), TaskScope.REQUEST_APPROVAL));
        var advisor = conflict
                ? new ValueConflictAdvisor(request -> ProbeResult.conflict(List.of(ConflictCode.BUDGET), "超预算", Map.of()), null)
                : null;
        var guarded = new GuardedDomainTool(delegate, execution, new PolicyEngine("shadow"),
                () -> CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN, "CONFIGURED", "configured"), false)
                .withAdvisor(advisor);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(guarded);
        var script = new ArrayDeque<List<ContentBlock>>();
        try {
            script.add(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name("auto-bind-coupons")
                    .input(Map.of()).content("{}").build()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        script.add(List.of(TextBlock.builder().text("done").build()));
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("scripted");
        when(model.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> Flux.defer(() -> script.isEmpty()
                        ? Flux.error(new AssertionError("script exhausted"))
                        : Flux.just(io.agentscope.core.model.ChatResponse.builder().content(script.removeFirst()).build())));
        var permission = PermissionContextState.builder().mode(PermissionMode.DEFAULT)
                .addAllowRule("auto-bind-coupons", new PermissionRule("auto-bind-coupons", "", PermissionBehavior.ALLOW, "mio"))
                .build();
        var agent = ReActAgent.builder().name("gate").sysPrompt("offline").model(model).toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore()).permissionContext(permission).maxIters(4).build();
        try {
            agent.call(List.of(new UserMessage("go")), RuntimeContext.builder().sessionId("child").build())
                    .block(Duration.ofSeconds(10));
        } finally {
            agent.close();
        }
        return new Run(executions.get());
    }

    private record Run(int executions) {
    }
}
