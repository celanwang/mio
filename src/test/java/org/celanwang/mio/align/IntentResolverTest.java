package org.celanwang.mio.align;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.align.IntentResolver.EffectHint;
import org.celanwang.mio.config.DomainAliasRegistry;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.orchestration.*;
import org.celanwang.mio.sandbox.TicketIssuer;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.trace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntentResolverTest {
    @TempDir Path skills;
    final ObjectMapper json = new ObjectMapper();
    final IntentResolver resolver = new DeterministicIntentResolver();
    final AgentRegistry registry = new AgentRegistry(List.of(capability("food-agent", "food"), capability("ride-agent", "ride")));

    private static AgentCapability capability(String id, String skill) {
        return new AgentCapability(id, id, "test", skill, "1", List.of());
    }

    private DomainAliasRegistry aliases(String food, String ride) throws Exception {
        for (var entry : Map.of("food", food, "ride", ride).entrySet()) {
            Path dir = Files.createDirectories(skills.resolve(entry.getKey()));
            Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + entry.getKey() + "\naliases: [" + entry.getValue() + "]\n---\n");
        }
        return new DomainAliasRegistry(skills.toString());
    }

    private ResolveRequest request(String text, DomainAliasRegistry aliases, EntityStore entities, PersonalContextSnapshot prefs) {
        var goal = new GoalRecord("goal", "session", 1, text, text, 1, 1);
        return new LiveResolveRequests(registry, aliases, entities).forMessage("session", text, goal, prefs);
    }

    private EntityStore entities() {
        var entities = mock(EntityStore.class, CALLS_REAL_METHODS);
        when(entities.related(anyString())).thenReturn(List.of());
        return entities;
    }

    @Test void ir1SingleAliasAlignsSkillToCapabilityAndIsRoundLocal() throws Exception {
        var request = request("看看汉堡菜单", aliases("汉堡", "打车"), entities(), PersonalContextSnapshot.empty());
        var result = resolver.resolve(request);
        assertThat(request.allowModelDisambiguation()).isFalse();
        assertThat(result.schemaVersion()).isEqualTo("1");
        assertThat(result.status()).isEqualTo(ResolveStatus.CLEAR);
        assertThat(result.candidates()).singleElement().satisfies(c -> {
            assertThat(c.domain()).isEqualTo("food");
            assertThat(c.capabilityIds()).containsExactly("food-agent");
            assertThat(c.matchedAliases()).containsExactly("汉堡");
        });
        assertThat(result.effectHint()).isEqualTo(EffectHint.NONE);
        assertThat(result.ambiguityCodes()).isEmpty();
    }

    @Test void ir2TwoDomainsSuggestMainConversationClarification() throws Exception {
        var result = resolver.resolve(request("汉堡和打车", aliases("汉堡", "打车"), entities(), PersonalContextSnapshot.empty()));
        assertThat(result.status()).isEqualTo(ResolveStatus.AMBIGUOUS);
        assertThat(result.candidates()).extracting(DomainCandidate::domain).containsExactly("food", "ride");
        assertThat(result.ambiguityCodes()).containsExactly(AmbiguityCode.MULTI_DOMAIN);
        assertThat(result.effectHint()).isEqualTo(EffectHint.SUGGEST_CLARIFY).isNotEqualTo(org.celanwang.mio.align.EffectHint.SUGGEST_ASK);
        assertThat(IntentResolveEvents.planningContext(result)).contains("主助手对话", "收到回答后再规划或委派");
    }

    @Test void ir3SharedAliasReportsCollisionDeterministically() throws Exception {
        var request = request("看看门店", aliases("门店", "门店"), entities(), PersonalContextSnapshot.empty());
        var result = resolver.resolve(request);
        assertThat(result.status()).isEqualTo(ResolveStatus.AMBIGUOUS);
        assertThat(result.ambiguityCodes()).containsExactly(AmbiguityCode.MULTI_DOMAIN, AmbiguityCode.ALIAS_COLLISION);
        assertThat(resolver.resolve(request)).isEqualTo(result);
    }

    @Test void shippedAliasesUseSkillAlignmentAndDoNotInventUnregisteredDomains() {
        var registered = new AgentRegistry(List.of(capability("food-agent", "mcd-ordering"), capability("ride-agent", "tencent-ride")));
        var requests = new LiveResolveRequests(registered, new DomainAliasRegistry("./skills"), entities());
        var goal = new GoalRecord("goal", "session", 1, "汉堡和打车", "汉堡和打车", 1, 1);
        var result = resolver.resolve(requests.forMessage("session", goal.latestUserInput(), goal, PersonalContextSnapshot.empty()));
        assertThat(result.status()).isEqualTo(ResolveStatus.AMBIGUOUS);
        assertThat(result.candidates()).extracting(DomainCandidate::domain).containsExactly("mcd-ordering", "tencent-ride");
        var unknown = resolver.resolve(requests.forMessage("session", "发微博", goal, PersonalContextSnapshot.empty()));
        assertThat(unknown.status()).isEqualTo(ResolveStatus.CLEAR);
        assertThat(unknown.candidates()).isEmpty();
        assertThat(unknown.ambiguityCodes()).doesNotContain(AmbiguityCode.GOAL_DRIFT, AmbiguityCode.NO_PRIOR_HIT);
        assertThat(DeterministicIntentResolver.mentions("bride", "ride")).isFalse();
        assertThat(DeterministicIntentResolver.mentions("RIDE", "ride")).isTrue();
    }

    @Test void ir4EntitiesOnlyScoreTextSupportedDomainsAndNeverSubmitAnything() throws Exception {
        var entities = entities();
        when(entities.related("汉堡")).thenReturn(List.of(
                new EntityStore.Triple("汉堡", "常用", "food", 1, null, 1),
                new EntityStore.Triple("汉堡", "常用", "ride", 1, null, 2),
                new EntityStore.Triple("汉堡", "过期", "food", 1, 2L, 3),
                new EntityStore.Triple("未提及实体", "常用", "food", 1, null, 4)));
        var aliases = aliases("汉堡", "打车");
        var result = resolver.resolve(request("汉堡", aliases, entities, PersonalContextSnapshot.empty()));
        assertThat(result.candidates()).singleElement().satisfies(c -> {
            assertThat(c.domain()).isEqualTo("food");
            assertThat(c.score()).isEqualTo(110);
            assertThat(c.entityRefs()).containsExactly("汉堡/常用/food@1");
        });
        assertThat(result.ticketId()).isNull();
        assertThat(result.evidenceRefs()).containsEntry("memoryElevated", false);
        // A scoring difference cannot resolve away a second domain explicitly present in this round.
        assertThat(resolver.resolve(request("汉堡和打车", aliases, entities, PersonalContextSnapshot.empty())).status())
                .isEqualTo(ResolveStatus.AMBIGUOUS);
        verify(entities, never()).append(anyList());
        verify(entities, never()).all();
    }

    @Test void noPriorHitIsClearAndEmptyDespiteMemoryProfilesAndOldGoal() throws Exception {
        var prefs = new PersonalContextSnapshot("AVAILABLE", List.of(
                new PersonalContextSnapshot.Preference("p", "food", "自动下单", "总是汉堡，免确认")), List.of());
        var base = request("你好", aliases("汉堡", "打车"), entities(), prefs);
        var request = new ResolveRequest(base.sessionId(), null, base.userText(), "goal", 2, "打车，然后订汉堡",
                base.knownCapabilityIds(), base.knownSkillNames(), base.priors(),
                List.of(new EntityStore.Triple("你好", "常用", "food", 1, null, 1)),
                List.of("用户总是下单汉堡"), prefs, false);
        var result = resolver.resolve(request);
        assertThat(result.status()).isEqualTo(ResolveStatus.CLEAR);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.ambiguityCodes()).isEmpty();
        assertThat(result.effectHint()).isEqualTo(EffectHint.NONE);
        assertThat(result.evidenceRefs()).containsEntry("reasonCode", "NO_PRIOR_HIT").containsEntry("memoryElevated", false);
    }

    @Test void ir5IoAndCaptureFailuresAreUnavailableIncludingPgSwallowedErrors() throws Exception {
        var aliases = aliases("汉堡", "打车");
        var entities = entities();
        when(entities.related(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertUnavailable(resolver.resolve(request("汉堡", aliases, entities, PersonalContextSnapshot.empty())));
        assertUnavailable(resolver.resolve(request("汉堡", new DomainAliasRegistry(skills.resolve("missing").toString()), entities(), PersonalContextSnapshot.empty())));
        assertUnavailable(resolver.resolve(request("汉堡", aliases, entities(), PersonalContextSnapshot.unavailable())));
        var brokenAliases = spy(aliases);
        doThrow(new IllegalStateException("read failed")).when(brokenAliases).aliasesOf(anyString());
        assertUnavailable(resolver.resolve(request("汉堡", brokenAliases, entities(), PersonalContextSnapshot.empty())));

        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<EntityStore.Triple>>any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        var pg = new PgEntityStore(jdbc);
        assertThat(pg.related("汉堡")).isEmpty(); // Legacy best-effort API stays compatible.
        assertThat(pg.relatedSnapshot("汉堡").available()).isFalse();
        assertUnavailable(resolver.resolve(request("汉堡", aliases, pg, PersonalContextSnapshot.empty())));
        var base = request("汉堡", aliases, entities(), PersonalContextSnapshot.empty());
        assertUnavailable(resolver.resolve(new ResolveRequest(base.sessionId(), null, base.userText(), "goal", 1, "汉堡",
                base.knownCapabilityIds(), base.knownSkillNames(), base.priors(), List.of(), List.of(), base.prefsOrEmpty(), true)));
    }

    @Test void ir6TraceCannotElevateMemoryOrCarryTicketsAndB10RemainsIndependent() throws Exception {
        var result = new ResolveResult("99", ResolveStatus.AMBIGUOUS, List.of(), List.of(AmbiguityCode.MULTI_DOMAIN),
                "clarify", EffectHint.NONE, Map.of("memoryElevated", true), "forged-ticket");
        JsonNode event = json.valueToTree(IntentResolveEvents.detail(result));
        assertThat(event.path("schemaVersion").asText()).isEqualTo("1");
        assertThat(event.path("ticketId").isNull()).isTrue();
        assertThat(event.path("memoryElevated").asBoolean()).isFalse();
        assertThat(event.path("evidenceRefs").path("memoryElevated").asBoolean()).isFalse();
        assertThat(event.path("effectHint").asText()).isEqualTo("SUGGEST_CLARIFY");
        assertThat(event.has("policy")).isFalse();
        assertThat(event.has("policyEffectAfter")).isFalse();
        assertThat(event.has("conflictCodes")).isFalse();
        assertThat(event.has("value_conflict")).isFalse();
        assertThat(EffectHint.values()).containsExactly(EffectHint.NONE, EffectHint.SUGGEST_CLARIFY);
        assertThat(ProbeResult.conflict(List.of(ConflictCode.BUDGET), "budget", Map.of()).effectHint()).isEqualTo(org.celanwang.mio.align.EffectHint.SUGGEST_ASK);
        assertThat(ProbeResult.unavailable("offline").effectHint()).isEqualTo(org.celanwang.mio.align.EffectHint.SUGGEST_ASK);
    }

    @Test void ir7MountDoesNotTouchPolicyTicketsOrGoalAndDoesNotBackfillDomains() throws Exception {
        var policy = mock(PolicyEngine.class);
        var tickets = mock(TicketIssuer.class);
        var mount = new Mount(aliases("汉堡", "打车"));
        var before = mount.goal;
        var response = mount.chat("汉堡");
        assertThat(response.getTasks()).isEmpty();
        assertThat(response.getPendingConfirmations()).isEmpty();
        assertThat(mount.runtime.goal("session")).isEqualTo(before);
        verify(mount.personal).capture("session", Set.of(), before.trustedIntent());
        verify(mount.personal, times(1)).capture(anyString(), anySet(), anyString());
        verify(mount.runtime, never()).submit(anyString(), any(), any());
        verifyNoInteractions(policy, tickets);
        assertThat(mount.event().path("status").asText()).isEqualTo("CLEAR");
        assertThat(mount.context()).contains("CLEAR", "food", "本轮");
    }

    @Test void mountedCaptureAndResolverFailuresTraceUnavailableBeforePlanning() throws Exception {
        var aliases = aliases("汉堡", "打车");
        for (boolean throwsFailure : List.of(false, true)) {
            var mount = new Mount(aliases);
            if (throwsFailure) when(mount.personal.capture(anyString(), anySet(), anyString())).thenThrow(new IllegalStateException("capture"));
            else when(mount.personal.capture(anyString(), anySet(), anyString())).thenReturn(Map.of("state", "UNAVAILABLE"));
            mount.chat("汉堡");
            assertThat(mount.event().path("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(mount.event().path("candidates")).isEmpty();
            assertThat(mount.context()).contains("UNAVAILABLE");
        }
        var mount = new Mount(aliases);
        when(mount.runtime.captureUser(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("captureUser"));
        assertThatThrownBy(() -> mount.chat("汉堡")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(mount.event().path("status").asText()).isEqualTo("UNAVAILABLE");
        verify(mount.factory, never()).create(anyString(), any(), anyString());
        verifyNoInteractions(mount.personal);

        var resolverFailure = new Mount(aliases);
        doThrow(new IllegalStateException("resolver failed")).when(resolverFailure.resolver).resolve(any());
        resolverFailure.chat("汉堡");
        assertThat(resolverFailure.event().path("status").asText()).isEqualTo("UNAVAILABLE");

        var fallback = new Mount(aliases);
        var profiles = mock(ProfileStore.class);
        when(profiles.pagesStrict()).thenThrow(new DataAccessResourceFailureException("fallback capture"));
        var service = new MultiAgentChatService(fallback.factory, fallback.runtime, registry, limits(), Clock.systemUTC(), fallback.traces, null, profiles)
                .withIntentResolver(resolver, new LiveResolveRequests(registry, aliases, entities()), json);
        var input = new ChatRequest(); input.setSessionId("session"); input.setMessage("汉堡");
        service.chat(input).block(Duration.ofSeconds(10));
        assertThat(fallback.event().path("status").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test void clarificationUsesExistingMainConversationWithNoNewTaskOrCard() throws Exception {
        var mount = new Mount(aliases("汉堡", "打车"));
        var response = mount.chat("汉堡和打车");
        assertThat(response.getReply()).isEqualTo("请说明先处理哪个领域。");
        assertThat(response.getTasks()).isEmpty();
        assertThat(response.getWeiboAction()).isNull();
        assertThat(response.getPendingConfirmations()).isEmpty();
        assertThat(mount.context()).contains("SUGGEST_CLARIFY", "主助手对话");
        assertThat(mount.event().path("status").asText()).isEqualTo("AMBIGUOUS");
        assertThat(mount.traces.events("session")).extracting(TraceEvent::type).containsExactly("user", "intent_resolve", "model");
    }

    @Test void controlRequestsKeepExistingRoutingWithoutGlobalIntentResolution() throws Exception {
        var aliases = aliases("汉堡", "打车");
        for (String kind : List.of("approval", "reply", "resume")) {
            var mount = new Mount(aliases);
            var request = new ChatRequest(); request.setSessionId("session");
            switch (kind) {
                case "approval" -> {
                    request.setConfirmations(List.of(new ChatRequest.Confirmation("call", true)));
                    when(mount.runtime.approve(anyString(), anyList(), any())).thenReturn(Mono.empty());
                }
                case "reply" -> {
                    request.setMessage("汉堡"); request.setTaskReply(new ChatRequest.TaskReply("task", "question"));
                    when(mount.runtime.answerTask(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(Mono.empty());
                }
                case "resume" -> {
                    request.setResumeTaskId("task"); var task = mock(TaskRecord.class);
                    when(task.status()).thenReturn(TaskStatus.INPUT_READY); when(mount.runtime.require("session", "task")).thenReturn(task);
                    when(mount.runtime.continueTask(anyString(), anyString(), any())).thenReturn(Mono.empty());
                }
            }
            mount.service.chat(request).block(Duration.ofSeconds(10));
            verify(mount.resolver, never()).resolve(any());
            verify(mount.runtime, never()).captureUser(anyString(), anyString());
            assertThat(mount.traces.events("session")).noneMatch(e -> e.type().equals("intent_resolve"));
        }
    }

    @Test void ir8ResolvePrecedesSupervisorAndToolSelectionWhileB10FollowsBusinessToolSelection() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        var traces = new TraceStore((session, event) -> events.add(event));
        var states = new InMemoryAgentStateStore();
        var cap = capability("food-agent", "food");
        var registry = new AgentRegistry(List.of(cap));
        var goals = new HashMap<String, GoalRecord>();
        var tasks = new HashMap<String, TaskRecord>();
        var store = mock(CoordinationStore.class, CALLS_REAL_METHODS);
        when(store.goal(anyString())).thenAnswer(i -> Optional.ofNullable(goals.get(i.getArgument(0))));
        doAnswer(i -> { GoalRecord g = i.getArgument(0); goals.put(g.sessionId(), g); order.add("captureUser"); return null; }).when(store).createGoal(any());
        when(store.tasks(anyString())).thenAnswer(i -> tasks.values().stream().filter(t -> t.sessionId().equals(i.getArgument(0))).toList());
        when(store.task(anyString())).thenAnswer(i -> Optional.ofNullable(tasks.get(i.getArgument(0))));
        doAnswer(i -> { TaskRecord t = i.getArgument(0); tasks.put(t.id(), t); return null; }).when(store).createTask(any());
        doAnswer(i -> { TaskRecord t = i.getArgument(1); tasks.put(t.id(), t); return null; }).when(store).saveTask(any(), any(), anyString());
        var advisor = new ValueConflictAdvisor(r -> {
            order.add("B10");
            assertThat(r.toolName()).isEqualTo("auto-bind-coupons");
            return ProbeResult.conflict(List.of(ConflictCode.BUDGET), "budget", Map.of());
        }, null);
        AtomicInteger writes = new AtomicInteger();
        var tool = new ToolBase(ToolBase.builder().name("auto-bind-coupons").description("write")
                .inputSchema(Map.of("type", "object")).readOnly(false)) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                writes.incrementAndGet(); return Mono.just(ToolResultBlock.text("should not execute"));
            }
        };
        var domainModel = scriptedModel(new ArrayDeque<>(List.of(List.of(toolCall("auto-bind-coupons", Map.of())))),
                () -> order.add("businessToolSelected"));
        var definition = new DomainDefinition(cap, "test", () -> List.of(tool));
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> jev = mock(ObjectProvider.class);
        var worker = new DomainAgentFactory(List.of(definition), c -> domainModel, states, null, new PolicyEngine("shadow"),
                mock(WeiboMcpService.class), jev, .8, traces, mock(TrustStore.class), null, mock(HabitDetector.class), null, json)
                .withValueConflict(advisor);
        var runtime = new TaskRuntime(store, registry, worker, new PolicyEngine("shadow"), Clock.systemUTC(), 16);
        var rootScript = new ArrayDeque<List<ContentBlock>>();
        rootScript.add(List.of(toolCall("delegate_task", Map.of("key", "coupon", "capabilityId", "food-agent", "objective", "汉堡优惠", "scope", "REQUEST_APPROVAL"))));
        rootScript.add(List.of(TextBlock.builder().text("请确认具体操作。").build()));
        var rootModel = scriptedModel(rootScript, () -> order.add("supervisorModel"));
        var factory = spy(new SupervisorFactory(rootModel, states, registry, runtime, json));
        doAnswer(i -> { order.add("supervisorCreate"); return i.callRealMethod(); }).when(factory).create(anyString(), any(), anyString());
        var personal = mock(PersonalContext.class);
        when(personal.capture(anyString(), anySet(), anyString())).thenAnswer(i -> {
            order.add("personalCapture"); return Map.of("state", "AVAILABLE", "preferences", List.of());
        });
        var service = new MultiAgentChatService(factory, runtime, registry, limits(), Clock.systemUTC(), traces, null, null)
                .withPersonalContext(personal).withIntentResolver(r -> {
                    order.add("resolve"); return resolver.resolve(r);
                }, new LiveResolveRequests(registry, aliases("汉堡", "打车"), entities()), json);
        var input = new ChatRequest(); input.setSessionId("session"); input.setMessage("汉堡优惠");
        var response = service.chat(input).block(Duration.ofSeconds(15));
        assertThat(order).containsSubsequence("captureUser", "personalCapture", "resolve", "supervisorCreate", "supervisorModel", "businessToolSelected", "B10");
        assertThat(writes.get()).isZero();
        assertThat(response.getPendingConfirmations()).hasSize(1);
        assertThat(goals.get("session").trustedIntent()).isEqualTo("汉堡优惠");
        assertThat(events).filteredOn(e -> e.type().equals("intent_resolve")).hasSize(1);
        assertThat(events).filteredOn(e -> e.type().equals("value_conflict_probe")).isNotEmpty();
        JsonNode b8 = json.readTree(events.stream().filter(e -> e.type().equals("intent_resolve")).findFirst().orElseThrow().detail());
        JsonNode b10 = json.readTree(events.stream().filter(e -> e.type().equals("value_conflict_probe")).findFirst().orElseThrow().detail());
        assertThat(b8.path("effectHint").asText()).isEqualTo("NONE");
        assertThat(b8.has("conflictCodes")).isFalse();
        assertThat(b10.path("effectHint").asText()).isEqualTo("SUGGEST_ASK");
        assertThat(b10.has("ambiguityCodes")).isFalse();
    }

    private void assertUnavailable(ResolveResult result) {
        assertThat(result.status()).isEqualTo(ResolveStatus.UNAVAILABLE);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.ambiguityCodes()).doesNotContain(AmbiguityCode.NO_PRIOR_HIT, AmbiguityCode.GOAL_DRIFT);
        assertThat(result.evidenceRefs()).doesNotContainKey("reasonCode").containsEntry("memoryElevated", false);
        assertThat(result.ticketId()).isNull();
    }

    private static RunBudget.Limits limits() { return new RunBudget.Limits(30, 50, 10, 100000, 100000, 60000); }

    private static ToolUseBlock toolCall(String name, Map<String, Object> input) throws Exception {
        return ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(input)
                .content(new ObjectMapper().writeValueAsString(input)).build();
    }

    private static Model scriptedModel(Deque<List<ContentBlock>> turns, Runnable beforeSelection) {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("scripted");
        when(model.stream(anyList(), any(), any())).thenAnswer(i -> Flux.defer(() -> {
            beforeSelection.run();
            return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(turns.removeFirst()).build());
        }));
        return model;
    }

    private final class Mount {
        final GoalRecord goal = new GoalRecord("goal", "session", 1, "汉堡", "汉堡", 1, 1);
        final TaskRuntime runtime = mock(TaskRuntime.class);
        final PersonalContext personal = mock(PersonalContext.class);
        final TraceStore traces = new TraceStore((s, e) -> {});
        final IntentResolver resolver = spy(new DeterministicIntentResolver());
        final SupervisorFactory factory;
        final MultiAgentChatService service;

        Mount(DomainAliasRegistry aliases) {
            when(runtime.findGoal("session")).thenReturn(Optional.of(goal));
            when(runtime.goal("session")).thenReturn(goal);
            when(runtime.captureUser(anyString(), anyString())).thenReturn(goal);
            when(runtime.list("session")).thenReturn(List.of());
            when(personal.capture(anyString(), anySet(), anyString())).thenReturn(Map.of("state", "AVAILABLE", "preferences", List.of()));
            var model = scriptedModel(new ArrayDeque<>(List.of(List.of(TextBlock.builder().text("请说明先处理哪个领域。").build()))), () -> {});
            factory = spy(new SupervisorFactory(model, new InMemoryAgentStateStore(), registry, runtime, json));
            service = new MultiAgentChatService(factory, runtime, registry, limits(), Clock.systemUTC(), traces, null, null)
                    .withPersonalContext(personal).withIntentResolver(resolver, new LiveResolveRequests(registry, aliases, entities()), json);
        }

        org.celanwang.mio.model.vo.ChatResponse chat(String text) {
            var request = new ChatRequest(); request.setSessionId("session"); request.setMessage(text);
            return service.chat(request).block(Duration.ofSeconds(10));
        }

        JsonNode event() throws Exception {
            return json.readTree(traces.events("session").stream().filter(e -> e.type().equals("intent_resolve")).findFirst().orElseThrow().detail());
        }

        String context() {
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(factory).create(eq("session"), any(), captor.capture());
            return captor.getValue();
        }
    }
}
