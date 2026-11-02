package org.celanwang.mio.align;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import org.celanwang.mio.memory.HabitDetector;
import org.celanwang.mio.memory.MioLongTermMemory;
import org.celanwang.mio.memory.TrustStore;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.celanwang.mio.security.PolicyEffect;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.ChatService;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValueConflictProbeTest {
    private final DeterministicValueConflictProbe probe = new DeterministicValueConflictProbe();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rideCallWithoutACommandIsNotAPureRead() {
        assertThat(WritePath.isPureReadOnly("tencent_ride", Map.of(), false)).isFalse();
        assertThat(WritePath.isPureReadOnly("tencent_ride", null, false)).isFalse();
        assertThat(WritePath.isPureReadOnly("tencent_ride", Map.of("command", "estimate-price"), false)).isTrue();
    }

    @Test
    void vc1ReadOnlyWithoutPreferencesIsClear() {
        ProbeResult result = probe.probe(ProbeRequest.baseline("s", "c", "query-meals", Map.of(), true));
        assertThat(result.status()).isEqualTo(ProbeStatus.CLEAR);
        assertThat(result.effectHint()).isEqualTo(EffectHint.NONE);
        assertThat(result.conflictCodes()).isEmpty();
        assertThat(result.schemaVersion()).isEqualTo("1");
    }

    @Test
    void vc2DietaryOrderConflictsAndIssuesNoTicket() throws Exception {
        var prefs = new PersonalContextSnapshot("AVAILABLE",
                List.of(new PersonalContextSnapshot.Preference("profile-1", "mcd-ordering", "忌口", "忌口：花生、香菜")),
                List.of());
        var request = new ProbeRequest("s", "call-1", "create-order",
                Map.of("items", List.of(Map.of("name", "花生酱"))), false, "mcd-ordering", null,
                prefs, List.of(), List.of());
        ProbeResult result = probe.probe(request);
        assertThat(result.status()).isEqualTo(ProbeStatus.CONFLICT);
        assertThat(result.conflictCodes()).contains(ConflictCode.DIETARY);
        assertThat(result.effectHint()).isEqualTo(EffectHint.SUGGEST_ASK);
        assertThat(result.evidenceRefs()).containsEntry("profileIds", List.of("profile-1"));

        try (Fixture fixture = new Fixture(advisor(probe, (session, call, tool, input, readOnly, intent) -> request))) {
            fixture.tool("create-order", false);
            fixture.call("create-order", Map.of("items", List.of(Map.of("name", "花生酱"))));
            fixture.say("done");
            ChatResponse pending = fixture.start();
            assertThat(fixture.executions.get()).isZero();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            JsonNode event = event(fixture, "value_conflict_probe");
            assertThat(event.get("ticketId").isNull()).isTrue();
            assertThat(event.get("status").asText()).isEqualTo("CONFLICT");
            assertThat(event.get("conflictCodes").get(0).asText()).isEqualTo("DIETARY");
            assertThat(event.get("effectHint").asText()).isEqualTo("SUGGEST_ASK");
            assertThat(event(fixture, "policy_decision").has("conflictCodes")).isFalse();
        }
    }

    @Test
    void vc3BudgetHardConstraintSuggestsAsk() {
        ProbeResult result = probe.probe(new ProbeRequest("s", "c", "create-order", Map.of("total", 48), false,
                "mcd-ordering", "goal-1", PersonalContextSnapshot.empty(),
                List.of(new HardConstraint("budget-1", "预算不超过 30")), List.of()));
        assertThat(result.status()).isEqualTo(ProbeStatus.CONFLICT);
        assertThat(result.conflictCodes()).containsExactly(ConflictCode.BUDGET);
        assertThat(result.effectHint()).isEqualTo(EffectHint.SUGGEST_ASK);
        assertThat(result.evidenceRefs()).containsEntry("constraintIds", List.of("budget-1"));
    }

    @Test
    void vc4UnavailableContextSuggestsAskAndHoldsTheWrite() {
        ProbeResult missing = probe.probe(ProbeRequest.unavailable("s", "c", "create-order", Map.of(), false, null));
        assertThat(missing.status()).isEqualTo(ProbeStatus.UNAVAILABLE);
        assertThat(missing.effectHint()).isEqualTo(EffectHint.SUGGEST_ASK);
        assertThat(missing.conflictCodes()).isEmpty();

        var requests = new LiveProbeRequests(
                (session, domains, intent) -> { throw new IllegalStateException("personal context down"); },
                session -> { throw new IllegalStateException("goal down"); },
                new SkillRedlineCatalog(true, Map.of()));
        ProbeResult failed = probe.probe(requests.forCall("s", "c", "auto-bind-coupons", Map.of(), false, "下单"));
        assertThat(failed.status()).isEqualTo(ProbeStatus.UNAVAILABLE);
        assertThat(failed.effectHint()).isEqualTo(EffectHint.SUGGEST_ASK);
        var advisor = new ValueConflictAdvisor(probe, requests);
        assertThat(advisor.holdWrite("s", "auto-bind-coupons", Map.of(), false, "下单")).isTrue();
        assertThat(advisor.holdWrite("s", "query-meals", Map.of(), true, "看看菜单")).isFalse();
    }

    @Test
    void vc5RejectedAskDoesNotExecuteTheWriteAndJevCannotBypass() throws Exception {
        JevClient jev = eagerJev();
        ValueConflictProbe conflicting = request -> "auto-bind-coupons".equals(request.toolName())
                ? ProbeResult.conflict(List.of(ConflictCode.BUDGET), "超过预算，建议先问用户。", Map.of("constraintIds", List.of("budget-1")))
                : ProbeResult.clear("未发现价值冲突。", Map.of());
        try (Fixture fixture = new Fixture(new ValueConflictAdvisor(conflicting, null), jev)) {
            fixture.tool("auto-bind-coupons", false);
            fixture.call("auto-bind-coupons", Map.of("coupon", "SYNTHETIC"));
            fixture.say("done");
            ChatResponse pending = fixture.start();
            assertThat(fixture.executions.get()).as("write body runs").isZero();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            verify(jev, never()).systemOne(any());
            JsonNode event = event(fixture, "value_conflict_probe");
            assertThat(event.get("ticketId").isNull()).isTrue();
            assertThat(event.get("effectHint").asText()).isEqualTo("SUGGEST_ASK");
            fixture.decide(pending, false);
            assertThat(fixture.executions.get()).as("rejected write still has not run").isZero();
        }
    }

    @Test
    void clearWriteCanStillBeExecutedByJevSoTheZeroCountIsTheProbe() throws Exception {
        JevClient jev = eagerJev();
        try (Fixture fixture = new Fixture(ValueConflictAdvisor.baseline(), jev)) {
            fixture.tool("auto-bind-coupons", false);
            fixture.call("auto-bind-coupons", Map.of());
            fixture.say("done");
            ChatResponse done = fixture.start();
            assertThat(fixture.executions.get()).isEqualTo(1);
            assertThat(done.getPendingConfirmations()).isEmpty();
            verify(jev).systemOne(any());
        }
    }

    @Test
    void readOnlyConflictIsTracedAndDoesNotInterrupt() throws Exception {
        ValueConflictProbe privacy = request -> ProbeResult.conflict(List.of(ConflictCode.PRIVACY),
                "只读查询带了隐私字段，只留痕。", Map.of("profileIds", List.of("p")));
        try (Fixture fixture = new Fixture(new ValueConflictAdvisor(privacy, null))) {
            fixture.tool("query-meals", true);
            fixture.call("query-meals", Map.of("phone", "13800138000"));
            fixture.say("done");
            ChatResponse done = fixture.start();
            assertThat(fixture.executions.get()).isEqualTo(1);
            assertThat(done.getPendingConfirmations()).isEmpty();
            JsonNode event = event(fixture, "value_conflict_probe");
            assertThat(event.get("status").asText()).isEqualTo("CONFLICT");
            assertThat(event.get("effectHint").asText()).isEqualTo("SUGGEST_ASK");
            assertThat(event.get("policyEffectAfter").asText()).isEqualTo("ALLOW");
            assertThat(event.get("ticketId").isNull()).isTrue();
        }
    }

    @Test
    void vc6ProbeCannotGrantOrDenyAndStaysBesidePolicy() throws Exception {
        ProbeResult conflict = new ProbeResult("9", ProbeStatus.CONFLICT, List.of(ConflictCode.BUDGET),
                "超预算", EffectHint.NONE, Map.of());
        assertThat(conflict.schemaVersion()).isEqualTo("1");
        assertThat(conflict.effectHint()).isEqualTo(EffectHint.SUGGEST_ASK);
        assertThat(ProbeStatus.values()).containsExactly(ProbeStatus.CLEAR, ProbeStatus.CONFLICT, ProbeStatus.UNAVAILABLE);
        assertThat(EffectHint.values()).containsExactly(EffectHint.NONE, EffectHint.SUGGEST_ASK);
        assertThat(ProbeResult.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("schemaVersion", "status", "conflictCodes", "humanReason", "effectHint", "evidenceRefs");
        var engine = new PolicyEngine("shadow");
        PolicyEffect before = engine.evaluate("create-order", false).effect();
        probe.probe(new ProbeRequest("s", "c", "create-order", Map.of("total", 99), false, null, "g",
                PersonalContextSnapshot.empty(), List.of(new HardConstraint("b", "预算不超过 10")), List.of()));
        assertThat(engine.evaluate("create-order", false).effect()).isEqualTo(before).isEqualTo(PolicyEffect.ASK);
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/org/celanwang/mio/align/DeterministicValueConflictProbe.java"));
        assertThat(source).doesNotContain("TicketIssuer", "PolicyEffect", "ALLOW", "DENY");
        JsonNode event = json.readTree(json.writeValueAsString(ValueConflictEvents.detail(
                "c", "create-order", null, conflict, PolicyEffect.ASK)));
        assertThat(event.get("ticketId").isNull()).isTrue();
        assertThat(event.get("policyEffectAfter").asText()).isEqualTo("ASK");
        assertThat(event.has("effect")).isFalse();
    }

    @Test
    void publishedSkillProseDoesNotBecomeAConflictByItself() {
        var catalog = new SkillRedlineCatalog("./skills");
        assertThat(catalog.available()).isTrue();
        ProbeResult result = probe.probe(new ProbeRequest("s", "c", "create-order", Map.of("total", 10), false,
                "mcd-ordering", null, PersonalContextSnapshot.empty(), List.of(), catalog.forDomain("mcd-ordering")));
        assertThat(result.status()).isEqualTo(ProbeStatus.CLEAR);
        assertThat(result.effectHint()).isEqualTo(EffectHint.NONE);
    }

    private static ValueConflictAdvisor advisor(ValueConflictProbe probe, ProbeRequests requests) {
        return new ValueConflictAdvisor(probe, requests);
    }

    private JsonNode event(Fixture fixture, String type) throws Exception {
        String detail = fixture.traces.events(fixture.session).stream()
                .filter(event -> type.equals(event.type())).findFirst().orElseThrow().detail();
        return json.readTree(detail);
    }

    private static JevClient eagerJev() {
        JevClient jev = mock(JevClient.class);
        when(jev.systemOne(any())).thenReturn(Mono.just(new SystemOneResult("mock",
                Map.of("consistent", new NoulAnswer(.99)), null)));
        return jev;
    }

    private static final class Fixture implements AutoCloseable {
        final Toolkit toolkit = new Toolkit();
        final PolicyEngine policy = new PolicyEngine("shadow");
        final TraceStore traces = new TraceStore((session, event) -> {});
        final AtomicInteger executions = new AtomicInteger();
        final Deque<List<ContentBlock>> script = new ArrayDeque<>();
        final String session = UUID.randomUUID().toString();
        final ValueConflictAdvisor align;
        final JevClient jev;
        ReActAgent agent;
        ChatService service;

        Fixture(ValueConflictAdvisor align) {
            this(align, null);
        }

        Fixture(ValueConflictAdvisor align, JevClient jev) {
            this.align = align;
            this.jev = jev;
        }

        private void open() {
            if (agent != null) {
                return;
            }
            Model model = mock(Model.class);
            when(model.getModelName()).thenReturn("scripted-fixture-v1");
            when(model.stream(any(), any(), any())).thenAnswer(invocation -> Flux.defer(() -> {
                if (script.isEmpty()) {
                    return Flux.error(new AssertionError("script exhausted"));
                }
                return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(script.removeFirst()).build());
            }));
            agent = ReActAgent.builder().name("probe").sysPrompt("offline")
                    .model(model).toolkit(toolkit).stateStore(new InMemoryAgentStateStore())
                    .permissionContext(policy.permissionContext()).maxIters(6).build();
            @SuppressWarnings("unchecked") ObjectProvider<JevClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(jev);
            var memory = mock(MioLongTermMemory.class);
            when(memory.record(any(), any())).thenReturn(Mono.empty());
            service = new ChatService(agent, mock(WeiboMcpService.class), provider, .8, "scripted", "mock-jev",
                    traces, mock(TrustStore.class), policy, memory, mock(HabitDetector.class), new ObjectMapper(),
                    org.celanwang.mio.service.ChatExecutionOptions.USER_CONVERSATION, align);
        }

        void tool(String name, boolean readOnly) {
            toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name(name).description(name).readOnly(readOnly)
                    .inputSchema(Map.of("type", "object"))) {
                @Override
                public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
                    return Mono.just(PermissionDecision.passthrough(name));
                }

                @Override
                public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                    return Mono.fromCallable(() -> {
                        executions.incrementAndGet();
                        return ToolResultBlock.text("ok");
                    });
                }
            });
        }

        void call(String name, Map<String, Object> input) throws Exception {
            script.add(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(input)
                    .content(new ObjectMapper().writeValueAsString(input)).build()));
        }

        void say(String text) {
            script.add(List.of(TextBlock.builder().text(text).build()));
        }

        ChatResponse start() {
            open();
            var request = new ChatRequest();
            request.setSessionId(session);
            request.setMessage("请处理");
            return service.chat(request).block(Duration.ofSeconds(10));
        }

        ChatResponse decide(ChatResponse pending, boolean approved) {
            var request = new ChatRequest();
            request.setSessionId(session);
            request.setConfirmations(pending.getPendingConfirmations().stream()
                    .map(item -> new ChatRequest.Confirmation(item.toolCallId(), approved)).toList());
            return service.chat(request).block(Duration.ofSeconds(10));
        }

        @Override
        public void close() {
            if (agent != null) {
                agent.close();
            }
        }
    }
}
