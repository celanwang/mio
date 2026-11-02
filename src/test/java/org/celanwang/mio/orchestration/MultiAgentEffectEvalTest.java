package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.evaluation.EffectObservations;
import org.celanwang.mio.memory.HabitDetector;
import org.celanwang.mio.memory.MioLongTermMemory;
import org.celanwang.mio.memory.ProfileStore;
import org.celanwang.mio.memory.TrustStore;
import org.celanwang.mio.security.PolicyEffect;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.tool.TencentRideTool;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Effect cases over mock suppliers. A pass here is not a production-agent or free-planning claim. */
class MultiAgentEffectEvalTest {
    @Test void exportMockSupplierEffectCases() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(observe("delegation-correct", delegationWrites()));
        rows.add(observe("fact-contract-mismatch", mismatchWrites()));
        rows.add(observe("goal-satisfied-not-write-granted", satisfiedDoesNotGrantWrite()));
        rows.add(observe("expired-quote-snapshot", expiredQuoteWrites()));
        rows.add(observe("dependency-invalidation-redispatch", redispatchWrites()));
        EffectObservations.writeJsonl("multi-agent-effects.jsonl", rows);
    }

    private static Map<String, Object> observe(String id, int writes) throws Exception {
        var row = EffectObservations.base("multi-agent", "*");
        row.put("case_id", id);
        row.put("sample_id", "1");
        row.put("passed", true);
        row.put("writes", writes);
        return row;
    }

    private static int delegationWrites() throws Exception {
        var json = new ObjectMapper();
        var store = new InMemoryCoordinationStore();
        var states = new InMemoryAgentStateStore();
        var policy = new PolicyEngine("shadow");
        var ride = mock(TencentRideService.class);
        long quotedAt = System.currentTimeMillis() - 1000;
        when(ride.execute(anyString(), anyMap())).thenReturn(ShortLivedFactTest.quote(quotedAt));
        var rideCap = new AgentCapability("ride-domain", "出行", "desc", "ride", "skill-v1", List.of(), CapabilityInputContract.contextual());
        var analysisCap = new AgentCapability("analysis-domain", "分析", "desc", "analysis", "skill-v1", List.of(), CapabilityInputContract.contextual());
        var rideScript = new ArrayDeque<List<ContentBlock>>();
        var analysisScript = new ArrayDeque<List<ContentBlock>>();
        var consumerMessages = new ArrayList<List<Msg>>();
        rideScript.add(turn(json, "tencent_ride", Map.of("command", "estimate-price", "parameters", Map.of())));
        rideScript.add(turn(json, "mio_finish_task", Map.of("text", "报价准备完成")));
        analysisScript.add(turn(json, "mio_finish_task", Map.of("text", "参考快照估算时间，未执行业务操作")));
        var rideModel = model(rideScript, new ArrayList<>());
        var analysisModel = model(analysisScript, consumerMessages);
        var dummy = new ToolBase(ToolBase.builder().name("analyze_context").description("read only").readOnly(true).inputSchema(Map.of())) {};
        var definitions = List.of(
                new DomainDefinition(rideCap, "仅查询", () -> List.of(new TencentRideTool(ride))),
                new DomainDefinition(analysisCap, "分析时间与预算", () -> List.of(dummy)));
        var profiles = mock(ProfileStore.class);
        when(profiles.pages()).thenReturn(List.of());
        when(profiles.render(anyList())).thenReturn("");
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> jev = mock(ObjectProvider.class);
        var factory = new DomainAgentFactory(definitions, capability -> capability.id().equals(rideCap.id()) ? rideModel : analysisModel,
                states, null, policy, mock(WeiboMcpService.class), jev, .8, new TraceStore((session, event) -> {}),
                mock(TrustStore.class), mock(MioLongTermMemory.class), mock(HabitDetector.class), profiles, json);
        var runtime = new TaskRuntime(store, new AgentRegistry(List.of(rideCap, analysisCap)), factory, policy, Clock.systemUTC(), 16,
                List.of(), List.of(), List.of(new TencentQuoteFactExtractor()));
        runtime.captureUser("root", "先询价，再按报价分析预算，不下单");
        var budget = new RunBudget(new RunBudget.Limits(30, 50, 10, 100000, 100000, 20000), Clock.systemUTC());
        var source = runtime.submit("root", new TaskSpec("quote", rideCap.id(), "准备询价", null, null, TaskScope.PREPARE), budget).block(Duration.ofSeconds(10));
        assertThat(source.status()).isEqualTo(TaskStatus.COMPLETED);
        var fact = TaskSummary.of(source, new AgentRegistry(List.of(rideCap))).facts().get(0);
        var spec = new TaskSpec("analysis", analysisCap.id(), "分析交通预算与时间", null, List.of(source.id()), TaskScope.PREPARE,
                List.of(new InputBinding(source.id(), fact.id(), fact.version(), "rideQuote")), "ride-quote-context");
        var consumer = runtime.submit("root", spec, budget).block(Duration.ofSeconds(10));
        assertThat(consumer.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(consumer.childSessionId()).isNotEqualTo(source.childSessionId());
        var userText = consumerMessages.get(0).stream().filter(message -> message.getRole() == MsgRole.USER).map(Msg::getTextContent).reduce("", String::concat);
        assertThat(userText).contains("rideQuote", "ride_quote").doesNotContain("private-key", "stateVersion");
        verify(ride, org.mockito.Mockito.times(1)).execute(eq(source.childSessionId()), anyMap());
        verifyNoMoreInteractions(ride);
        assertThat(source.operations()).isEmpty();
        assertThat(consumer.operations()).isEmpty();
        return source.operations().size() + consumer.operations().size();
    }

    private static int mismatchWrites() {
        var starts = new AtomicInteger();
        var store = new InMemoryCoordinationStore();
        var clock = Clock.systemUTC();
        var capability = new AgentCapability("analysis-domain", "分析", "desc", "analysis", "skill-v1", List.of(), CapabilityInputContract.contextual());
        var runtime = new TaskRuntime(store, new AgentRegistry(List.of(capability)),
                (task, goal, input, execution) -> { starts.incrementAndGet(); return Mono.error(new AssertionError("worker must not start")); },
                new PolicyEngine("shadow"), clock, 16);
        var goal = runtime.captureUser("root", "按报价分析，不要下单");
        long now = clock.millis();
        var source = mismatched(goal, now);
        store.createTask(source);
        var fact = TaskFacts.of(source).get(0);
        assertThat(fact.type()).isEqualTo("menu_item");
        var spec = new TaskSpec("analysis", capability.id(), "分析交通预算", null, List.of(source.id()), TaskScope.PREPARE,
                List.of(new InputBinding(source.id(), fact.id(), fact.version(), "rideQuote")), "ride-quote-context");
        var budget = new RunBudget(new RunBudget.Limits(20, 20, 10, 10000, 10000, 20000), clock);
        var result = runtime.submit("root", spec, budget).block(Duration.ofSeconds(10));
        assertThat(starts).hasValue(0);
        assertThat(result.effectStarted()).isFalse();
        assertThat(result.operations()).isEmpty();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.reply()).contains("事实");
        return result.operations().size();
    }

    private static TaskRecord mismatched(GoalRecord goal, long now) {
        var task = new TaskRecord("source", goal.sessionId(), goal.id(), goal.revision(),
                new TaskSpec("source", "analysis-domain", "准备错误类型", null, null, TaskScope.PREPARE),
                "source-child", "skill-v1", TaskStatus.RUNNING, "", null, List.of(), false, List.of(), 0, now, now);
        var observation = new FactObservation("source-fact", "ride.quote", "trusted-fixture", "query", "hash", now, "", "", null);
        task = task.withObservation(observation, true, now);
        task = task.withObservation(observation.returned(task, "fixture",
                new FactExtractor.Value("menu_item", Map.of("name", "SYNTHETIC"), now + 60000), now), false, now);
        return task.next(TaskStatus.COMPLETED, "已记录", List.of(), false, now);
    }

    private static int satisfiedDoesNotGrantWrite() {
        long now = 1_800_000_000_000L;
        var store = new InMemoryCoordinationStore();
        var goal = new GoalRecord("g", "s", 1, "用户目标", "用户目标", now, now);
        store.createGoal(goal);
        var task = GoalAcceptanceFixtures.quote("quote", goal, now, "900");
        store.createTask(task);
        var criteria = new GoalAcceptance(1, "g", 1, 1, now, List.of(GoalAcceptanceFixtures.requirement(task, List.of())));
        var view = GoalAcceptanceView.evaluate(goal, store.tasks("s"), criteria, GoalAcceptanceFixtures.REGISTRY, store, now);
        assertThat(view.state()).isEqualTo(GoalAcceptanceView.State.SATISFIED);
        assertThat(task.effectStarted()).isFalse();
        assertThat(task.operations()).isEmpty();
        assertThat(new PolicyEngine("shadow").evaluate("create-order", false).effect()).isEqualTo(PolicyEffect.ASK);
        return task.operations().size();
    }

    private static int expiredQuoteWrites() {
        var world = new World();
        var source = world.quote("source", World.NOW + 1);
        var analysis = world.task("analysis", List.of(source.id()), List.of(world.input(source)), TaskStatus.COMPLETED, TaskScope.PREPARE);
        var downstream = world.task("downstream", List.of(analysis.id()), List.of(), TaskStatus.AWAITING_APPROVAL, TaskScope.REQUEST_APPROVAL);
        var unrelated = world.task("unrelated", List.of(), List.of(), TaskStatus.AWAITING_APPROVAL, TaskScope.REQUEST_APPROVAL);
        world.time.incrementAndGet();
        var report = world.runtime((task, goal, input, execution) -> Mono.error(new AssertionError("review must not run"))).reviewPlan("root");
        assertThat(world.store.task(downstream.id()).orElseThrow().status()).isEqualTo(TaskStatus.STALE);
        assertThat(world.store.task(downstream.id()).orElseThrow().approvals()).isEmpty();
        assertThat(world.store.task(downstream.id()).orElseThrow().effectStarted()).isFalse();
        assertThat(world.store.task(unrelated.id()).orElseThrow()).isEqualTo(unrelated);
        assertThat(report.sourceRepairs()).singleElement().satisfies(repair -> assertThat(repair.action()).isEqualTo(PlanReview.Action.REFRESH_PREPARATION));
        assertThatThrownBy(() -> world.runtime((task, goal, input, execution) -> Mono.never())
                .approve("root", List.of(new org.celanwang.mio.model.dto.ChatRequest.Confirmation("approval-downstream", true)), world.budget).block())
                .isInstanceOf(IllegalArgumentException.class);
        return world.store.task(downstream.id()).orElseThrow().operations().size();
    }

    private static int redispatchWrites() {
        var world = new World();
        var source = world.quote("source", World.NOW + 1);
        var middle = world.task("middle", List.of(source.id()), List.of(world.input(source)), TaskStatus.COMPLETED, TaskScope.PREPARE);
        world.time.incrementAndGet();
        var starts = new AtomicInteger();
        var result = world.runtime((task, goal, input, execution) -> { starts.incrementAndGet(); return Mono.never(); })
                .submit("root", new TaskSpec("new-leaf", "domain", "prepare", null, List.of(middle.id()), TaskScope.PREPARE), world.budget)
                .block(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo(TaskStatus.STALE);
        assertThat(starts).hasValue(0);
        assertThat(result.effectStarted()).isFalse();
        return result.operations().size();
    }

    private static List<ContentBlock> turn(ObjectMapper json, String tool, Map<String, Object> args) throws Exception {
        return List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(tool).input(args).content(json.writeValueAsString(args)).build());
    }

    private static Model model(Deque<List<ContentBlock>> script, List<List<Msg>> inputs) {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("fake-domain");
        when(model.stream(anyList(), any(), any())).thenAnswer(invocation -> Flux.defer(() -> {
            inputs.add(List.copyOf(invocation.getArgument(0)));
            if (script.isEmpty()) return Flux.error(new IllegalStateException("script exhausted"));
            return Flux.just(ChatResponse.builder().content(script.removeFirst()).usage(ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build());
        }));
        return model;
    }

    private static final class World {
        static final long NOW = 1_800_000_000_000L;
        final AtomicLong time = new AtomicLong(NOW);
        final Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(time.get()); }
        };
        final InMemoryCoordinationStore store = new InMemoryCoordinationStore();
        final AgentRegistry registry = new AgentRegistry(List.of(new AgentCapability("domain", "领域", "desc", "s", "v1", List.of())));
        final RunBudget budget = new RunBudget(new RunBudget.Limits(50, 50, 50, 10000, 10000, 10000), clock);
        World() { store.createGoal(new GoalRecord("g", "root", 1, "intent", "intent", NOW, NOW)); }
        TaskRuntime runtime(TaskWorker worker) { return new TaskRuntime(store, registry, worker, new PolicyEngine("shadow"), clock, 16); }
        TaskRecord task(String id, List<String> deps, List<InputBinding> inputs, TaskStatus status, TaskScope scope) {
            var pending = status == TaskStatus.AWAITING_APPROVAL
                    ? List.of(new PendingApproval("approval-" + id, "call-" + id, "write", "confirm", Map.of(), "h", "c", NOW + 10000, null))
                    : List.<PendingApproval>of();
            var task = new TaskRecord(id, "root", "g", 1, new TaskSpec(id, "domain", "objective-" + id, List.of(), deps, scope, inputs),
                    "child-" + id, "v1", status, "historical reply", null, pending, false, List.of(), 0, NOW, NOW);
            store.createTask(task);
            return task;
        }
        TaskRecord quote(String id, long expiresAt) {
            var running = task(id, List.of(), List.of(), TaskStatus.RUNNING, TaskScope.PREPARE);
            var observation = new FactObservation(id + "-fact", "ride.quote", "test", "query", "hash", NOW, "", "", null);
            running = running.withObservation(observation, true, NOW);
            running = running.withObservation(observation.returned(running, "synthetic provider evidence",
                    new FactExtractor.Value("ride_quote", Map.of("durationSeconds", "60"), expiresAt), NOW), false, NOW);
            var completed = running.next(TaskStatus.COMPLETED, "quoted", List.of(), false, NOW);
            store.records.put(id, completed);
            return completed;
        }
        InputBinding input(TaskRecord source) {
            var fact = TaskFacts.of(source).get(0);
            return new InputBinding(source.id(), fact.id(), fact.version(), "quote");
        }
    }
}
