package org.celanwang.mio.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.celanwang.mio.security.PolicyDecision;
import org.celanwang.mio.security.PolicyEffect;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxIsolationTest {
    private final SceneCatalog scenes = new SceneCatalog();

    @Test
    void matrixTakesTheStricterRiskAndRuntime() {
        Classification mild = new Classification(RiskClass.READONLY, RuntimeKind.JVM, "mcd");
        Classification severe = new Classification(RiskClass.IRREVERSIBLE, RuntimeKind.SUBPROCESS, null);
        Classification merged = SandboxMatrix.stricter(mild, severe);
        assertThat(merged.risk()).isEqualTo(RiskClass.IRREVERSIBLE);
        assertThat(merged.runtime()).isEqualTo(RuntimeKind.SUBPROCESS);
        assertThat(merged.credential()).isEqualTo("mcd");
        assertThat(SandboxMatrix.profile(merged).humanRequired()).isTrue();
        assertThat(SandboxMatrix.profile(merged).processSandbox()).isTrue();
        assertThat(SandboxMatrix.profile(merged).hostCredentialDirs()).isFalse();
        assertThatThrownBy(() -> SandboxMatrix.stricter(
                new Classification(RiskClass.READONLY, RuntimeKind.JVM, "mcd"),
                new Classification(RiskClass.READONLY, RuntimeKind.JVM, "weibo")))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("CREDENTIAL_CONFLICT");
    }

    @Test
    void sceneMappingMatchesTheLockedMatrix() {
        assertThat(scenes.classify("query-meals", Map.of(), false, null))
                .isEqualTo(new Classification(RiskClass.READONLY, RuntimeKind.JVM, "mcd"));
        assertThat(scenes.classify("list-nutrition-foods", Map.of(), false, null).risk()).isEqualTo(RiskClass.READONLY);
        assertThat(scenes.classify("create-order", Map.of(), false, null))
                .isEqualTo(new Classification(RiskClass.IRREVERSIBLE, RuntimeKind.JVM, "mcd"));
        assertThat(scenes.classify("mall-create-order", Map.of(), true, null).risk()).isEqualTo(RiskClass.IRREVERSIBLE);
        assertThat(scenes.classify("prepare_weibo", Map.of(), false, null).risk()).isEqualTo(RiskClass.READONLY);
        assertThat(scenes.classify("search_statuses_limited", Map.of(), true, "weibo"))
                .isEqualTo(new Classification(RiskClass.READONLY, RuntimeKind.JVM, "weibo"));
        assertThat(scenes.classify("comment_status", Map.of(), false, "weibo").risk()).isEqualTo(RiskClass.REVERSIBLE);
        assertThat(scenes.classify("post_status", Map.of(), false, "weibo").risk()).isEqualTo(RiskClass.IRREVERSIBLE);
        assertThat(scenes.classify("destroy_status", Map.of(), false, "weibo").risk()).isEqualTo(RiskClass.IRREVERSIBLE);
        assertThat(scenes.classify("tencent_ride", Map.of("command", "estimate-price"), false, null))
                .isEqualTo(new Classification(RiskClass.READONLY, RuntimeKind.SUBPROCESS, "tencent-ride"));
        assertThat(scenes.classify("tencent_ride", Map.of("command", "poi-search"), false, null).risk()).isEqualTo(RiskClass.READONLY);
        assertThat(scenes.classify("tencent_ride", Map.of("command", "set-resident-city"), false, null).risk())
                .isEqualTo(RiskClass.REVERSIBLE);
        assertThat(scenes.classify("tencent_ride", Map.of("command", "cancel-order"), false, null).risk())
                .isEqualTo(RiskClass.IRREVERSIBLE);
        assertThat(scenes.classify("tencent_ride_create_order", Map.of("summary", "book"), false, null))
                .isEqualTo(new Classification(RiskClass.IRREVERSIBLE, RuntimeKind.SUBPROCESS, "tencent-ride"));
        assertThat(SandboxMatrix.profile(scenes.classify("tencent_ride", Map.of("command", "estimate-price"), false, null))
                .networkAllowed()).isTrue();
        assertThat(SandboxMatrix.profile(new Classification(RiskClass.READONLY, RuntimeKind.SUBPROCESS, null))
                .networkAllowed()).isFalse();
    }

    @Test
    void ticketsFailClosedAndDoNotFlipShadowMode(@TempDir Path temp) throws Exception {
        PolicyEngine shadow = new PolicyEngine("shadow");
        SandboxStack stack = new SandboxStack(temp, shadow, Clock.systemUTC(), () -> "mcd-secret", () -> "ride-secret", () -> "weibo-secret");
        assertThat(shadow.isEnforce()).isFalse();
        assertThat(Files.readString(Path.of("src/main/resources/application.yml"))).contains("policy-mode: ${MIO_SECURITY_POLICY_MODE:shadow}");

        ExecutionTicket menu = stack.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "query-meals",
                Map.of(), true, true, "mcd", null, null));
        assertThat(menu.risk()).isEqualTo(RiskClass.READONLY);
        assertThat(menu.policyEffect()).isEqualTo("ALLOW");

        assertThatThrownBy(() -> stack.issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "create-order",
                Map.of(), false, false, "mcd", null, null)))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");

        ExecutionTicket order = stack.issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "create-order",
                Map.of(), false, true, "mcd", null, null));
        assertThat(order.humanConfirmed()).isTrue();
        assertThat(order.permitsEgress("mcd")).isTrue();

        ExecutionTicket shadowWrite = stack.issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "auto-bind-coupons",
                Map.of(), false, true, "mcd",
                new PolicyDecision(PolicyEffect.DENY, "hardcoded-deny", "no"), null));
        assertThat(shadowWrite.shadowDeny()).isTrue();
        assertThat(shadow.isEnforce()).isFalse();
        PolicyEngine enforce = new PolicyEngine("enforce");
        TicketIssuer enforcing = new TicketIssuer(enforce, stack.scenes, Clock.systemUTC(), Duration.ofMinutes(2));
        assertThatThrownBy(() -> enforcing.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "query-meals",
                Map.of(), true, true, "mcd", new PolicyDecision(PolicyEffect.DENY, "hardcoded-deny", "no"), null)))
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("POLICY_DENY");
        ExecutionTicket shadowed = stack.issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "query-meals",
                Map.of(), true, true, "mcd", new PolicyDecision(PolicyEffect.DENY, "hardcoded-deny", "no"), null));
        assertThat(shadowed.shadowDeny()).isTrue();

        shadow.setCircuitOpen(true);
        assertThatThrownBy(() -> stack.issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "create-order",
                Map.of(), false, false, "mcd", null, null)))
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");
        assertThat(stack.issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "create-order",
                Map.of(), false, true, "mcd", null, null)).policySource()).isEqualTo("kill-switch");
        shadow.setCircuitOpen(false);

        assertThat(stack.audit.text()).doesNotContain("mcd-secret").doesNotContain("ride-secret");
    }

    @Test
    void missingTicketBlocksReadonlyEgressAndDelegate(@TempDir Path temp) throws Exception {
        SandboxStack stack = new SandboxStack(temp, new PolicyEngine("shadow"), Clock.systemUTC(),
                () -> "menu-token", () -> "", () -> "");
        AtomicBoolean called = new AtomicBoolean();
        ToolBase delegate = new ToolBase(ToolBase.builder().name("query-meals").description("menu")
                .inputSchema(Map.of("type", "object")).readOnly(true)) {
            @Override
            public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                called.set(true);
                return reactor.core.publisher.Mono.just(ToolResultBlock.text("should-not-run"));
            }
        };
        SandboxExecutor denying = new SandboxExecutor(new TicketIssuer(stack.policy, stack.scenes, Clock.systemUTC(), Duration.ZERO),
                stack.workspace, stack.lease, stack.audit, stack.properties, stack.containers);
        var wrapped = new SandboxedTool(delegate, denying, "mcd");
        ToolResultBlock result = wrapped.callAsync(call("query-meals", Map.of())).block();
        assertThat(called).isFalse();
        assertThat(result.getOutput().toString()).contains("TICKET_EXPIRED").doesNotContain("menu-token");
        assertThatThrownBy(() -> stack.credentials.materializeCurrent("mcd"))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("NO_EGRESS_TICKET");
    }

    @Test
    void credentialProxyReleasesMaterialOnlyForALiveTicket(@TempDir Path temp) {
        SandboxStack stack = new SandboxStack(temp, new PolicyEngine("shadow"), Clock.systemUTC(),
                () -> "menu-token", () -> "ride-token", () -> "wb-token");
        ExecutionTicket ticket = stack.executor.call(new Admission(null, "s", "c", "query-meals", Map.of(), true, true, "mcd", null, null),
                current -> current);
        assertThat(stack.credentials.materialize(ticket, "mcd")).isEqualTo("menu-token");
        assertThatThrownBy(() -> stack.credentials.materialize(ticket, "weibo")).isInstanceOf(SandboxRefusedException.class);
        assertThat(stack.audit.text()).contains("sandbox_egress").doesNotContain("menu-token");
    }

    @Test
    void workspacesRejectCrossPrincipalAndEscape(@TempDir Path temp) throws Exception {
        WorkspaceLayout layout = new WorkspaceLayout(temp.resolve("root"));
        Path owner = layout.ensure(PrincipalId.LOCAL_OWNER);
        Path other = layout.ensure(new PrincipalId("local-owner-2"));
        assertThat(other).isNotEqualTo(owner);
        assertThat(other.startsWith(owner)).isFalse();
        layout.requireInside(owner, owner.resolve("notes.txt"));
        assertThatThrownBy(() -> layout.requireInside(owner, other.resolve("notes.txt")))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("CROSS_PRINCIPAL");
        assertThatThrownBy(() -> layout.requireInside(owner, temp.resolve("outside")))
                .isInstanceOf(SandboxRefusedException.class);
        Path env = temp.resolve("project");
        Files.createDirectories(env.resolve(".weibo"));
        assertThatThrownBy(() -> MountPolicy.assertNarrow(env, false)).isInstanceOf(SandboxRefusedException.class);
        RowIsolationGuard rows = new RowIsolationGuard();
        rows.check(PrincipalId.LOCAL_OWNER, PrincipalId.LOCAL_OWNER);
        assertThatThrownBy(() -> rows.check(PrincipalId.LOCAL_OWNER, new PrincipalId("other-user")))
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("CROSS_PRINCIPAL");
        assertThat(rows.establishment()).isEqualTo("NOT_ESTABLISHED");
        assertThat(rows.principalPredicate()).isEqualTo("principal_id = ?");
    }

    @Test
    void containerConflictDoesNotRunOnTheHost(@TempDir Path temp) {
        SandboxStack stack = new SandboxStack(temp, new PolicyEngine("shadow"), Clock.systemUTC(), () -> "", () -> "", () -> "");
        AtomicBoolean ran = new AtomicBoolean();
        assertThatThrownBy(() -> stack.executor.call(new Admission(PrincipalId.LOCAL_OWNER, "s", "c", "query-meals", Map.of(),
                true, true, "mcd", null, new Classification(RiskClass.READONLY, RuntimeKind.CONTAINER, "mcd")),
                ticket -> {
                    ran.set(true);
                    return ticket;
                })).isInstanceOf(SandboxUnavailableException.class)
                .extracting(error -> ((SandboxUnavailableException) error).code())
                .isEqualTo("CONTAINER_DISABLED");
        assertThat(ran).isFalse();
        stack.properties.setContainerEnabled(true);
        stack.properties.setContainerRuntime("/bin/true");
        assertThatThrownBy(() -> new ContainerRuntimeHook(stack.properties).launch(stack.issuer.bootstrap(PrincipalId.LOCAL_OWNER, "mcd")))
                .extracting(error -> ((SandboxUnavailableException) error).code())
                .isEqualTo("CONTAINER_NOT_ESTABLISHED");
    }

    @Test
    void statusKeepsDestinationFilterUnestablished() {
        SandboxProperties properties = new SandboxProperties();
        PolicyEngine policy = new PolicyEngine("shadow");
        Map<String, Object> body = new SandboxController(properties, policy, new ContainerRuntimeHook(properties),
                new RowIsolationGuard(), new LinuxProcessSandbox(Path.of("/no/such/bwrap"))).status();
        assertThat(policy.isEnforce()).isFalse();
        assertThat(body.get("policyMode")).isEqualTo("shadow");
        assertThat(body.get("notEstablished")).isEqualTo(List.of(
                "gvisor",
                "database row security",
                "destination network filter",
                "seccomp"));
        assertThat(body.get("established")).isEqualTo(List.of(
                "principal workspace",
                "execution tickets",
                "credential proxy",
                "subprocess bubblewrap required"));
        assertThat(body.get("container")).isEqualTo("NOT_ESTABLISHED");
        assertThat(body.get("rowIsolation")).isEqualTo("NOT_ESTABLISHED");
        assertThat(body.toString()).doesNotContain("netns").doesNotContain("iptables");
    }

    @Test
    void principalApiAcceptsAnotherIdButDefaultsToLocalOwner() {
        assertThat(PrincipalId.parse(null)).isEqualTo(PrincipalId.LOCAL_OWNER);
        assertThat(PrincipalId.parse(" rider-b ").value()).isEqualTo("rider-b");
        assertThatThrownBy(() -> new PrincipalId("../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceLayout(Path.of("/"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ToolCallParam call(String name, Map<String, Object> input) {
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(RuntimeContext.builder().sessionId("session").build())
                .toolUseBlock(ToolUseBlock.builder().id("call-1").name(name).input(input).content("{}").build())
                .build();
    }

    @Test
    void expiredTicketCannotBeReusedForAProcess() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ExecutionTicket ticket = ExecutionTicket.issue(PrincipalId.LOCAL_OWNER, "s", "c", "tencent_ride",
                new Classification(RiskClass.READONLY, RuntimeKind.SUBPROCESS, "tencent-ride"),
                "ALLOW", "static-allow", false, false, false, false, clock, Duration.ZERO);
        assertThat(ticket.expired(clock)).isTrue();
        assertThat(ticket.covers(ticket.classification(), clock)).isFalse();
    }
}
