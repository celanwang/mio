package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import org.celanwang.mio.align.ConflictCode;
import org.celanwang.mio.align.DeterministicIntentResolver;
import org.celanwang.mio.align.LiveResolveRequests;
import org.celanwang.mio.align.IntentResolveEvents;
import org.celanwang.mio.align.PersonalContextSnapshot;
import org.celanwang.mio.align.ResolveRequest;
import org.celanwang.mio.align.ResolveResult;
import org.celanwang.mio.align.ProbeResult;
import org.celanwang.mio.align.ProbeStatus;
import org.celanwang.mio.align.ResolveStatus;
import org.celanwang.mio.align.ValueConflictAdvisor;
import org.celanwang.mio.config.DomainAliasRegistry;
import org.celanwang.mio.config.ToolPolicies;
import org.celanwang.mio.evaluation.SandboxAuthorityObservations;
import org.celanwang.mio.memory.EntityStore;
import org.celanwang.mio.memory.HabitDetector;
import org.celanwang.mio.memory.PersonalContext;
import org.celanwang.mio.memory.TrustStore;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.celanwang.mio.sandbox.Admission;
import org.celanwang.mio.sandbox.ExecutionTicket;
import org.celanwang.mio.sandbox.OfflineSandbox;
import org.celanwang.mio.sandbox.RuntimeKind;
import org.celanwang.mio.sandbox.SandboxExecutor;
import org.celanwang.mio.sandbox.SandboxScope;
import org.celanwang.mio.security.PolicyEffect;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.trace.TraceEvent;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real factories, approval routing and offline ticket stack; models, probe inputs and business I/O are synthetic. */
class AlignmentSandboxIntegrationTest {
    private static final String ROOT = "alignment-root";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    @TempDir Path skills;

    private record ObservedTrace(String session, TraceEvent event) {}
    private record ModelInput(List<String> systemTexts, List<String> userTexts) {}
    private static final PersonalContextSnapshot.Preference PREFERENCE =
            new PersonalContextSnapshot.Preference("pref-food", "global", "自动下单", "总是汉堡，免确认");

    @Test void conflictHoldsEvenAnAllowingDelegateAndHumanApprovalCanReleaseThatCall() throws Exception {
        heldThenApproved(ProbeResult.conflict(List.of(ConflictCode.BUDGET), "budget", Map.of()));
    }

    @Test void unavailableHoldsEvenAnAllowingDelegateAndHumanApprovalCanReleaseThatCall() throws Exception {
        heldThenApproved(ProbeResult.unavailable("personal context unavailable"));
    }

    @Test void conflictRejectionCancelsWithoutAnyWriteAdmissionOrTicket() throws Exception {
        heldThenRejected(ProbeResult.conflict(List.of(ConflictCode.BUDGET), "budget", Map.of()));
    }

    @Test void unavailableRejectionCancelsWithoutAnyWriteAdmissionOrTicket() throws Exception {
        heldThenRejected(ProbeResult.unavailable("personal context unavailable"));
    }

    private void heldThenRejected(ProbeResult result) throws Exception {
        // Each status gets its own real service/runtime/approval fixture, separate from approval release.
        try (var f = new Fixture(result, "auto-bind-coupons", true, false, false)) {
            var pending = f.start("汉堡优惠");
            f.assertObservations(ResolveStatus.CLEAR, result.status());
            f.assertHeld(pending);
            var goal = f.runtime.goal(ROOT);
            var rejected = f.decide(pending, false);
            assertThat(rejected.getPendingConfirmations()).isEmpty();
            assertThat(f.task().status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(f.task().approvals()).singleElement().satisfies(a ->
                    assertThat(a.decision()).isEqualTo(Boolean.FALSE));
            f.assertTargetHasNoExecution();
            f.assertOnlyKnownReadWasAdmitted();
            assertThat(f.task().operations()).isEmpty();
            assertThat(f.runtime.goal(ROOT)).isEqualTo(goal);
            f.assertObservations(ResolveStatus.CLEAR, result.status());
            f.assertControlDidNotResolveAgain(2);
            verify(f.jev, never()).systemOne(any());
            f.assertApprovalCannotReplay(pending);
            // Replay must not smuggle in an unrelated admission or event either.
            f.assertOnlyKnownReadWasAdmitted();
            assertThat(f.task().status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(f.task().operations()).isEmpty();
        }
    }

    private void heldThenApproved(ProbeResult result) throws Exception {
        try (var f = new Fixture(result, "auto-bind-coupons", true, false, false)) {
            var pending = f.start("汉堡优惠");
            f.assertObservations(ResolveStatus.CLEAR, result.status());
            f.assertHeld(pending);
            // Both the delegate and Jev would allow; the real guard must stop before SandboxedTool.
            verify(f.jev, never()).systemOne(any());
            var approved = f.decide(pending, true);
            assertThat(approved.getPendingConfirmations()).isEmpty();
            // Capture the real persisted decision before TaskRecord.start consumes the pending approvals.
            var recorded = ArgumentCaptor.forClass(TaskRecord.class);
            verify(f.store).saveTask(any(TaskRecord.class), recorded.capture(), eq("approval_recorded"));
            assertThat(recorded.getValue().id()).isEqualTo(f.task().id());
            assertThat(recorded.getValue().approvals()).singleElement().satisfies(a -> {
                assertThat(a.id()).isEqualTo(pending.getPendingConfirmations().get(0).toolCallId());
                assertThat(a.id()).isNotEqualTo(f.targetId);
                assertThat(a.callId()).isEqualTo(f.targetId);
                assertThat(a.decision()).isEqualTo(Boolean.TRUE);
            });
            assertThat(f.task().approvals()).isEmpty();
            f.assertExecutedOnce();
            f.assertObservations(ResolveStatus.CLEAR, result.status());
            f.assertControlDidNotResolveAgain(2);
            verify(f.jev, never()).systemOne(any());
            f.assertApprovalCannotReplay(pending);
        }
    }

    @Test void clearClearHumanOnlyWriteStaysAskAndRejectionHasNoTargetAdmission() throws Exception {
        try (var f = new Fixture(ProbeResult.clear("clear", Map.of()), "create-order", false, false, false)) {
            assertThat(ToolPolicies.isJevReviewable(f.write, Map.of())).isFalse();
            var pending = f.start("汉堡下单");
            f.assertObservations(ResolveStatus.CLEAR, ProbeStatus.CLEAR);
            f.assertHeld(pending);
            var goal = f.runtime.goal(ROOT);
            var rejected = f.decide(pending, false);
            assertThat(rejected.getPendingConfirmations()).isEmpty();
            assertThat(f.task().status()).isEqualTo(TaskStatus.CANCELLED);
            f.assertTargetHasNoExecution();
            f.assertOnlyKnownReadWasAdmitted();
            assertThat(f.task().operations()).isEmpty();
            assertThat(f.runtime.goal(ROOT)).isEqualTo(goal);
            f.assertControlDidNotResolveAgain(2);
            verify(f.jev, never()).systemOne(any());
            f.assertApprovalCannotReplay(pending);
        }
    }

    @Test void clearClearHumanApprovalExecutesOnceWithATargetTicketWhileEvidenceTicketsStayNull() throws Exception {
        try (var f = new Fixture(ProbeResult.clear("clear", Map.of()), "create-order", false, false, false)) {
            var pending = f.start("汉堡下单");
            f.assertObservations(ResolveStatus.CLEAR, ProbeStatus.CLEAR);
            f.assertHeld(pending);
            var goal = f.runtime.goal(ROOT);
            f.decide(pending, true);
            f.assertExecutedOnce();
            assertThat(f.tickets.get(0).humanConfirmed()).isTrue();
            f.assertObservations(ResolveStatus.CLEAR, ProbeStatus.CLEAR);
            assertThat(f.runtime.goal(ROOT)).isEqualTo(goal);
            f.assertControlDidNotResolveAgain(2);
            verify(f.jev, never()).systemOne(any());
            f.assertApprovalCannotReplay(pending);
        }
    }

    @Test void reversibleClearWriteRetainsTheExistingJevPathAndThePolicyAskSnapshot() throws Exception {
        try (var f = new Fixture(ProbeResult.clear("clear", Map.of()), "auto-bind-coupons", false, false, false)) {
            var done = f.start("汉堡优惠");
            assertThat(done.getPendingConfirmations()).isEmpty();
            verify(f.jev, times(1)).systemOne(any());
            f.assertObservations(ResolveStatus.CLEAR, ProbeStatus.CLEAR);
            f.assertExecutedOnce();
            assertThat(f.tickets.get(0).humanConfirmed()).isFalse();
        }
    }

    @Test void b8UnavailableStillReachesTheWorkerAndDoesNotBecomeANewHardGate() throws Exception {
        try (var f = new Fixture(ProbeResult.clear("clear", Map.of()), "create-order", false, true, false)) {
            var pending = f.start("汉堡下单");
            f.assertObservations(ResolveStatus.UNAVAILABLE, ProbeStatus.CLEAR);
            f.assertHeld(pending);
            f.decide(pending, true);
            f.assertExecutedOnce();
            f.assertObservations(ResolveStatus.UNAVAILABLE, ProbeStatus.CLEAR);
            f.assertControlDidNotResolveAgain(2);
        }
    }

    @Test void noPriorHitStaysClearAndEmptyWithoutBackfillingPersonalContextDomains() throws Exception {
        try (var f = new Fixture(ProbeResult.clear("clear", Map.of()), "create-order", false, false, false)) {
            var pending = f.start("你好");
            var b8 = f.details(ROOT, "intent_resolve").get(0);
            assertThat(b8.path("status").asText()).isEqualTo("CLEAR");
            assertThat(b8.path("candidates")).isEmpty();
            assertThat(b8.path("evidenceRefs").path("reasonCode").asText()).isEqualTo("NO_PRIOR_HIT");
            f.assertObservations(ResolveStatus.CLEAR, ProbeStatus.CLEAR);
            f.assertHeld(pending);
            f.assertControlDidNotResolveAgain(1);
        }
    }

    @Test void taskReplyAndPersistedAnswerResumeStayOnTheChildRouteWithTheRealSandboxFactory() throws Exception {
        for (boolean resume : List.of(false, true)) {
            try (var f = new Fixture(ProbeResult.clear("clear", Map.of()), "create-order", false, false, true)) {
                f.start("汉堡下单");
                var waiting = f.task();
                assertThat(waiting.status()).isEqualTo(TaskStatus.NEEDS_INPUT);
                var goal = f.runtime.goal(ROOT);
                var request = new ChatRequest();
                request.setSessionId(ROOT);
                if (resume) {
                    // The resume route consumes an already persisted answer, rather than a new global message.
                    var ready = waiting.answered(goal, waiting.questionId(), "本任务的回答", System.currentTimeMillis());
                    f.store.saveTask(waiting, ready, "task_answer_received");
                    assertThat(ready.status()).isEqualTo(TaskStatus.INPUT_READY);
                    request.setResumeTaskId(waiting.id());
                } else {
                    request.setMessage("本任务的回答");
                    request.setTaskReply(new ChatRequest.TaskReply(waiting.id(), waiting.questionId()));
                }
                var pending = f.service.chat(request).block(TIMEOUT);
                assertThat(f.task().childSessionId()).isEqualTo(waiting.childSessionId());
                assertThat(f.runtime.goal(ROOT)).isEqualTo(goal);
                f.assertObservations(ResolveStatus.CLEAR, ProbeStatus.CLEAR);
                f.assertHeld(pending);
                f.assertControlDidNotResolveAgain(2);
                f.decide(pending, true);
                f.assertExecutedOnce();
                f.assertControlDidNotResolveAgain(3);
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final PolicyEngine policy = new PolicyEngine("shadow");
        final List<ObservedTrace> allTraceEvents = new CopyOnWriteArrayList<>();
        final TraceStore traces = new TraceStore((session, event) -> allTraceEvents.add(new ObservedTrace(session, event)));
        final InMemoryCoordinationStore store = spy(new InMemoryCoordinationStore());
        final AtomicInteger bodies = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final List<ExecutionTicket> tickets = new CopyOnWriteArrayList<>();
        final List<ExecutionTicket> readTickets = new CopyOnWriteArrayList<>();
        final List<ResolveResult> resolved = new CopyOnWriteArrayList<>();
        final List<ModelInput> supervisorInputs = new CopyOnWriteArrayList<>();
        final DeterministicIntentResolver resolver = spy(new DeterministicIntentResolver());
        final PersonalContext personal = mock(PersonalContext.class);
        final JevClient jev = mock(JevClient.class);
        final String targetId = "target-" + UUID.randomUUID();
        final String readId = "read-" + UUID.randomUUID();
        final String write;
        final boolean b8Unavailable;
        final OfflineSandbox sandbox;
        final SandboxExecutor executor;
        final TaskRuntime runtime;
        final MultiAgentChatService service;

        Fixture(ProbeResult result, String write, boolean delegateAllows, boolean b8Unavailable,
                boolean askQuestion) throws Exception {
            this.write = write;
            this.b8Unavailable = b8Unavailable;
            doAnswer(invocation -> {
                ResolveResult resultFromResolver = (ResolveResult) invocation.callRealMethod();
                resolved.add(resultFromResolver);
                return resultFromResolver;
            }).when(resolver).resolve(any());
            when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock",
                    Map.of("consistent", new NoulAnswer(.99)), null)));
            @SuppressWarnings("unchecked") ObjectProvider<JevClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(jev);
            when(personal.capture(anyString(), anySet(), anyString())).thenReturn(
                    Map.of("state", b8Unavailable ? "UNAVAILABLE" : "AVAILABLE", "preferences", List.of(
                            Map.of("id", PREFERENCE.id(), "scope", PREFERENCE.scope(),
                                    "title", PREFERENCE.title(), "content", PREFERENCE.content()))));
            var entities = mock(EntityStore.class, CALLS_REAL_METHODS);
            when(entities.related(anyString())).thenReturn(List.of());
            Path dir = Files.createDirectories(skills.resolve(UUID.randomUUID().toString()));
            Path food = Files.createDirectories(dir.resolve("food"));
            Files.writeString(food.resolve("SKILL.md"), "---\nname: food\naliases: [汉堡]\n---\n");
            var cap = new AgentCapability("food-agent", "food", "offline", "food", "1", List.of());
            var registry = new AgentRegistry(List.of(cap));
            var states = new InMemoryAgentStateStore();
            var probe = new ValueConflictAdvisor(request -> write.equals(request.toolName())
                    ? result : ProbeResult.clear("not the target write", Map.of()), null);
            sandbox = new OfflineSandbox(policy, traces);
            executor = spy(sandbox.executor);
            ToolBase target = new ToolBase(ToolBase.builder().name(write).description("synthetic write")
                    .inputSchema(Map.of("type", "object")).readOnly(false)) {
                @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                                           PermissionContextState context) {
                    return Mono.just(delegateAllows ? PermissionDecision.allow("delegate would run now")
                            : PermissionDecision.passthrough(write));
                }
                @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                    return Mono.fromCallable(() -> {
                        bodies.incrementAndGet();
                        tickets.add(SandboxScope.currentTicket().orElseThrow());
                        return ToolResultBlock.text("synthetic receipt; business outcome not verified");
                    });
                }
            };
            ToolBase read = new ToolBase(ToolBase.builder().name("query-meals").description("synthetic read")
                    .inputSchema(Map.of("type", "object")).readOnly(true)) {
                @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                    return Mono.fromCallable(() -> {
                        readTickets.add(SandboxScope.currentTicket().orElseThrow());
                        reads.incrementAndGet();
                        return ToolResultBlock.text("synthetic menu");
                    });
                }
            };
            var domainTurns = new ArrayDeque<List<ContentBlock>>();
            if (askQuestion) domainTurns.add(List.of(call("question", "mio_request_input", Map.of("text", "本任务的补问"))));
            domainTurns.add(List.of(call(readId, "query-meals", Map.of())));
            domainTurns.add(List.of(call(targetId, write, Map.of())));
            domainTurns.add(List.of(call("finish", "mio_finish_task", Map.of("text", "工具已返回，业务结果仍待核查"))));
            var definition = new DomainDefinition(cap, "synthetic business I/O", () -> List.of(read, target));
            var worker = new DomainAgentFactory(List.of(definition), c -> model(domainTurns), states, null, policy,
                    mock(WeiboMcpService.class), provider, .8, traces, mock(TrustStore.class), null,
                    mock(HabitDetector.class), null, json).withValueConflict(probe).withSandbox(executor);
            runtime = new TaskRuntime(store, registry, worker, policy, Clock.systemUTC(), 16);
            var rootTurns = new ArrayDeque<List<ContentBlock>>();
            rootTurns.add(List.of(call("delegate", "delegate_task", Map.of("key", "target", "capabilityId", cap.id(),
                    "objective", "按本次确认处理汉堡", "scope", "REQUEST_APPROVAL"))));
            rootTurns.add(List.of(TextBlock.builder().text("请查看本任务状态。").build()));
            rootTurns.add(List.of(TextBlock.builder().text("已处理本任务的控制请求。").build()));
            rootTurns.add(List.of(TextBlock.builder().text("已处理本次批准。").build()));
            var factory = new SupervisorFactory(model(rootTurns, true), states, registry, runtime, json);
            service = new MultiAgentChatService(factory, runtime, registry,
                    new RunBudget.Limits(50, 100, 10, 100000, 100000, 60000), Clock.systemUTC(), traces, null, null)
                    .withPersonalContext(personal)
                    .withIntentResolver(resolver, new LiveResolveRequests(registry, new DomainAliasRegistry(dir.toString()), entities), json);
        }

        ChatResponse start(String text) {
            var request = new ChatRequest(); request.setSessionId(ROOT); request.setMessage(text);
            var response = service.chat(request).block(TIMEOUT);
            assertResolveAndPlanningInput(text);
            return response;
        }

        void assertResolveAndPlanningInput(String text) {
            var captor = ArgumentCaptor.forClass(ResolveRequest.class);
            verify(resolver, times(1)).resolve(captor.capture());
            var request = captor.getValue();
            assertThat(request.sessionId()).isEqualTo(ROOT);
            assertThat(request.userText()).isEqualTo(text);
            assertThat(request.allowModelDisambiguation()).isFalse();
            var goal = runtime.goal(ROOT);
            assertThat(goal.latestUserInput()).isEqualTo(text);
            assertThat(goal.trustedIntent()).isEqualTo(text);
            if (b8Unavailable) {
                // LiveResolveRequests deliberately returns its unavailable sentinel without goal/priors.
                assertThat(request.goalIdOrNull()).isNull();
                assertThat(request.goalRevisionOrZero()).isZero();
                assertThat(request.trustedIntentExcerpt()).isEmpty();
                assertThat(request.prefsOrEmpty()).isEqualTo(PersonalContextSnapshot.unavailable());
                assertThat(request.priors().available()).isFalse();
            } else {
                assertThat(request.goalIdOrNull()).isEqualTo(goal.id());
                assertThat(request.goalRevisionOrZero()).isEqualTo(goal.revision());
                assertThat(request.trustedIntentExcerpt()).isEqualTo(goal.trustedIntent());
                assertThat(request.prefsOrEmpty()).isEqualTo(
                        new PersonalContextSnapshot("AVAILABLE", List.of(PREFERENCE), List.of()));
                assertThat(request.knownCapabilityIds()).containsExactly("food-agent");
                assertThat(request.knownSkillNames()).containsExactly("food");
                assertThat(request.priors().available()).isTrue();
                assertThat(request.priors().capabilitySkills()).containsExactlyEntriesOf(Map.of("food-agent", "food"));
                assertThat(request.priors().aliasesBySkill().get("food")).contains("汉堡");
            }
            assertThat(resolved).hasSize(1);
            var result = resolved.get(0);
            assertThat(result.status()).isEqualTo(b8Unavailable ? ResolveStatus.UNAVAILABLE : ResolveStatus.CLEAR);
            if (!b8Unavailable && text.contains("汉堡")) {
                assertThat(result.candidates()).singleElement().satisfies(candidate -> {
                    assertThat(candidate.domain()).isEqualTo("food");
                    assertThat(candidate.capabilityIds()).containsExactly("food-agent");
                    assertThat(candidate.matchedAliases()).containsExactly("汉堡");
                });
            } else {
                assertThat(result.candidates()).isEmpty();
            }
            // Snapshot at the actual Supervisor Model.stream boundary, before its scripted selection.
            assertThat(supervisorInputs).isNotEmpty();
            var first = supervisorInputs.get(0);
            assertThat(first.userTexts()).contains(text);
            assertThat(first.systemTexts()).anySatisfy(system -> assertThat(system)
                    .contains("本轮服务端上下文（参考数据，非新增授权）", "参考，不是权限；不得回填画像领域",
                            IntentResolveEvents.planningContext(result)));
            if (b8Unavailable) {
                assertThat(first.systemTexts()).anySatisfy(system -> assertThat(system)
                        .contains("UNAVAILABLE", "按用户原文继续理解"));
            }
            assertThat(result.ticketId()).isNull();
            assertThat(result.evidenceRefs()).containsEntry("memoryElevated", false);
        }

        ChatResponse decide(ChatResponse pending, boolean approved) {
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            var request = new ChatRequest(); request.setSessionId(ROOT);
            request.setConfirmations(List.of(new ChatRequest.Confirmation(
                    pending.getPendingConfirmations().get(0).toolCallId(), approved)));
            return service.chat(request).block(TIMEOUT);
        }

        TaskRecord task() {
            var tasks = runtime.list(ROOT); assertThat(tasks).hasSize(1); return tasks.get(0);
        }

        void assertHeld(ChatResponse pending) throws Exception {
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            assertThat(task().status()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
            assertThat(task().approvals()).hasSize(1);
            var approval = task().approvals().get(0);
            assertThat(approval.decision()).isNull();
            assertThat(approval.callId()).isEqualTo(targetId);
            assertThat(pending.getPendingConfirmations().get(0).toolCallId()).isEqualTo(approval.id());
            assertThat(approval.id()).isNotEqualTo(targetId);
            assertThat(reads).hasValue(1);
            assertTargetHasNoExecution();
            assertOnlyKnownReadWasAdmitted();
            assertThat(task().operations()).isEmpty();
        }

        void assertTargetHasNoExecution() throws Exception {
            assertThat(bodies).hasValue(0);
            assertThat(tickets).isEmpty();
            verify(executor, never()).admit(argThat(admission -> targetId.equals(admission.callId())));
            // No target event of any kind; avoid iterating an already-empty target collection.
            assertThat(targetSandboxEvents()).isEmpty();
        }

        void assertOnlyKnownReadWasAdmitted() throws Exception {
            // No targetId filter: every executor admit, including unowned calls, must be this known read.
            var admissions = ArgumentCaptor.forClass(Admission.class);
            verify(executor, times(1)).admit(admissions.capture());
            var admission = admissions.getValue();
            assertThat(admission.callId()).isEqualTo(readId);
            assertThat(admission.toolName()).isEqualTo("query-meals");
            assertThat(admission.sessionId()).isEqualTo(task().childSessionId());
            assertThat(admission.declaredReadOnly()).isTrue();
            assertThat(readTickets).hasSize(1);
            var ticket = readTickets.get(0);
            assertThat(ticket.id()).isNotBlank();
            assertThat(ticket.callId()).isEqualTo(readId);
            assertThat(ticket.tool()).isEqualTo("query-meals");
            assertThat(ticket.sessionId()).isEqualTo(task().childSessionId());
            assertThat(ticket.policyEffect()).isEqualTo("ALLOW");
            // Collect from the publisher across ALL sessions, so missing/wrong session and call ids fail closed.
            var sandboxEvents = allTraceEvents.stream()
                    .filter(row -> row.event().type().startsWith("sandbox_")).toList();
            assertThat(sandboxEvents).extracting(row -> row.event().type())
                    .containsExactly("sandbox_admit", "sandbox_started", "sandbox_finish");
            for (var observed : sandboxEvents) {
                assertThat(observed.session()).isEqualTo(task().childSessionId());
                var detail = json.readTree(observed.event().detail());
                assertThat(detail.path("callId").asText()).isEqualTo(readId);
                assertThat(detail.path("tool").asText()).isEqualTo("query-meals");
                assertThat(detail.path("ticket_id").asText()).isEqualTo(ticket.id());
                assertThat(detail.path("process_started").isBoolean()).isTrue();
                assertThat(detail.path("process_started").asBoolean()).isFalse();
            }
        }

        void assertExecutedOnce() throws Exception {
            assertThat(bodies).hasValue(1);
            assertThat(tickets).hasSize(1);
            var ticket = tickets.get(0);
            assertThat(ticket.id()).isNotBlank();
            assertThat(ticket.callId()).isEqualTo(targetId);
            assertThat(ticket.sessionId()).isEqualTo(task().childSessionId());
            assertThat(ticket.tool()).isEqualTo(write);
            assertThat(ticket.frameworkAdmitted()).isTrue();
            assertThat(ticket.policyEffect()).isEqualTo("ASK");
            assertThat(ticket.runtime()).isEqualTo(RuntimeKind.JVM);
            verify(executor, times(1)).admit(argThat(admission -> targetId.equals(admission.callId())));
            var target = targetSandboxEvents();
            assertThat(target).extracting(TraceEvent::type)
                    .containsExactly("sandbox_admit", "sandbox_started", "sandbox_finish");
            for (var event : target) assertThat(json.readTree(event.detail()).path("ticket_id").asText()).isEqualTo(ticket.id());
            int starts = processStarts(target);
            assertThat(starts).as("opening a JVM scope does not start a subprocess").isZero();
            var observation = new LinkedHashMap<String, Object>();
            SandboxAuthorityObservations.copyTrace(observation, target, true, starts);
            assertThat(observation).containsEntry("sandbox_path", "sandboxed")
                    .containsEntry("ticket_id", ticket.id()).containsEntry("admitted", true);
            assertThat(task().operations()).hasSize(1);
            assertThat(task().operations().get(0).callId()).isEqualTo(targetId);
        }

        void assertObservations(ResolveStatus b8Status, ProbeStatus b10Status) throws Exception {
            var b8 = details(ROOT, "intent_resolve"); assertThat(b8).hasSize(1);
            assertThat(b8.get(0).path("status").asText()).isEqualTo(b8Status.name());
            assertThat(b8.get(0).has("ticketId")).isTrue();
            assertThat(b8.get(0).path("ticketId").isNull()).isTrue();
            assertThat(b8.get(0).has("memoryElevated")).isTrue();
            assertThat(b8.get(0).path("memoryElevated").isBoolean()).isTrue();
            assertThat(b8.get(0).path("memoryElevated").asBoolean()).isFalse();
            var evidence = b8.get(0).path("evidenceRefs");
            assertThat(evidence.has("memoryElevated")).isTrue();
            assertThat(evidence.path("memoryElevated").isBoolean()).isTrue();
            assertThat(evidence.path("memoryElevated").asBoolean()).isFalse();
            var b10 = targetDetails("value_conflict_probe"); assertThat(b10).isNotEmpty();
            for (var row : b10) {
                assertThat(row.path("tool").asText()).isEqualTo(write);
                assertThat(row.path("status").asText()).isEqualTo(b10Status.name());
                assertThat(row.has("ticketId")).isTrue();
                assertThat(row.path("ticketId").isNull()).isTrue();
                assertThat(row.path("policyEffectAfter").asText()).isEqualTo("ASK");
            }
            assertThat(policy.evaluate(write, false).effect()).isEqualTo(PolicyEffect.ASK);
            var decisions = targetDetails("policy_decision"); assertThat(decisions).isNotEmpty();
            for (var row : decisions) assertThat(row.path("effect").asText()).isEqualTo("ASK");
            assertThat(policy.isEnforce()).isFalse();
        }

        void assertControlDidNotResolveAgain(int captures) {
            verify(resolver, times(1)).resolve(any());
            verify(personal, times(captures)).capture(eq(ROOT), eq(Set.<String>of()), anyString());
            verify(personal, never()).capture(eq(ROOT), argThat(domains -> !domains.isEmpty()), anyString());
        }

        void assertApprovalCannotReplay(ChatResponse pending) throws Exception {
            int beforeBodies = bodies.get(); int beforeEvents = targetSandboxEvents().size();
            int beforeAllEvents = allTraceEvents.size();
            var beforeTickets = List.copyOf(tickets);
            var beforeTask = task();
            int beforeAdmissions = readTickets.size() + tickets.size();
            assertThatThrownBy(() -> decide(pending, true)).isInstanceOf(ResponseStatusException.class);
            assertThat(bodies.get()).isEqualTo(beforeBodies);
            assertThat(tickets).containsExactlyElementsOf(beforeTickets);
            assertThat(targetSandboxEvents()).hasSize(beforeEvents);
            assertThat(allTraceEvents).hasSize(beforeAllEvents);
            assertThat(task()).isEqualTo(beforeTask);
            verify(executor, times(beforeAdmissions)).admit(any());
        }

        List<JsonNode> details(String session, String type) throws Exception {
            var result = new ArrayList<JsonNode>();
            for (var event : traces.events(session)) if (type.equals(event.type())) result.add(json.readTree(event.detail()));
            return result;
        }

        List<JsonNode> targetDetails(String type) throws Exception {
            return details(task().childSessionId(), type).stream()
                    .filter(row -> targetId.equals(row.path("callId").asText())).toList();
        }

        List<TraceEvent> targetSandboxEvents() throws Exception {
            var result = new ArrayList<TraceEvent>();
            for (var event : traces.events(task().childSessionId())) {
                if (event.type().startsWith("sandbox_") && targetId.equals(json.readTree(event.detail()).path("callId").asText()))
                    result.add(event);
            }
            return result;
        }

        int processStarts(List<TraceEvent> events) throws Exception {
            int count = 0;
            for (var event : events) {
                var detail = json.readTree(event.detail());
                assertThat(detail.path("process_started").isBoolean()).isTrue();
                if (detail.path("process_started").asBoolean()) count++;
            }
            return count;
        }

        ToolUseBlock call(String id, String tool, Map<String, Object> input) throws Exception {
            return ToolUseBlock.builder().id(id).name(tool).input(input).content(json.writeValueAsString(input)).build();
        }

        Model model(Deque<List<ContentBlock>> turns) {
            return model(turns, false);
        }

        Model model(Deque<List<ContentBlock>> turns, boolean supervisor) {
            var model = mock(Model.class);
            when(model.getModelName()).thenReturn("scripted-alignment-sandbox");
            when(model.stream(anyList(), any(), any())).thenAnswer(invocation -> Flux.defer(() -> {
                if (supervisor) {
                    List<Msg> messages = invocation.getArgument(0);
                    supervisorInputs.add(new ModelInput(
                            messages.stream().filter(m -> m.getRole() == MsgRole.SYSTEM).map(Msg::getTextContent).toList(),
                            messages.stream().filter(m -> m.getRole() == MsgRole.USER).map(Msg::getTextContent).toList()));
                }
                assertThat(turns).as("unexpected model turn").isNotEmpty();
                return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(turns.removeFirst()).build());
            }));
            return model;
        }

        @Override public void close() { sandbox.close(); }
    }
}
