package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.celanwang.mio.config.TencentRideProperties;
import org.celanwang.mio.sandbox.Admission;
import org.celanwang.mio.sandbox.ContainerRuntimeHook;
import org.celanwang.mio.sandbox.CredentialLease;
import org.celanwang.mio.sandbox.CredentialProxy;
import org.celanwang.mio.sandbox.ExecutionTicket;
import org.celanwang.mio.sandbox.LinuxProcessSandbox;
import org.celanwang.mio.sandbox.PrincipalId;
import org.celanwang.mio.sandbox.ProcessLaunch;
import org.celanwang.mio.sandbox.ProcessSandbox;
import org.celanwang.mio.sandbox.SandboxExecutor;
import org.celanwang.mio.sandbox.SandboxProperties;
import org.celanwang.mio.sandbox.SandboxRefusedException;
import org.celanwang.mio.sandbox.SceneCatalog;
import org.celanwang.mio.sandbox.TicketIssuer;
import org.celanwang.mio.sandbox.TraceSandboxAudit;
import org.celanwang.mio.sandbox.WorkspaceLayout;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.tool.TencentRideTool;
import org.celanwang.mio.trace.TraceEvent;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline sandbox ACS. No bubblewrap and no real ride process. The single-arg tool path stays available
 * for unit tests and is not exported as a pass.
 */
class SandboxAuthorityTest {
    private static final String TOKEN = "synthetic-acs-token";

    @TempDir Path temp;

    @Test void missingTicketRefusesWriteIrreversibleAndSubprocess() throws Exception {
        var world = world();
        assertThat(world.policy.isEnforce()).isFalse();
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("policy-mode: ${MIO_SECURITY_POLICY_MODE:shadow}");
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(refused(world, "jvm-reversible", new Admission(PrincipalId.LOCAL_OWNER, "acs-jvm-reversible", "c",
                "auto-bind-coupons", Map.of(), false, false, "mcd", null, null)));
        rows.add(refused(world, "jvm-irreversible", new Admission(PrincipalId.LOCAL_OWNER, "acs-jvm-irreversible", "c",
                "create-order", Map.of(), false, false, "mcd", null, null)));
        rows.add(rideWithoutTicket(world, "subprocess-reversible", "acs-ride-reversible", "set-resident-city"));
        rows.add(rideWithoutTicket(world, "subprocess-irreversible", "acs-ride-irreversible", "create-order"));
        assertThatThrownBy(() -> new LinuxProcessSandbox(Path.of("bwrap")).plan(launch(null)))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");
        assertThat(world.policy.isEnforce()).isFalse();
        SandboxAuthorityObservations.export(rows);
    }

    @Test void oneTicketCannotStartTwoProcesses() throws Exception {
        var world = world();
        String session = "acs-reuse";
        var input = new LinkedHashMap<String, Object>();
        input.put("command", "create-order");
        input.put("parameters", Map.of());
        ExecutionTicket ticket = world.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, session, "order-1",
                "tencent_ride_create_order", Map.of("summary", "from A to B"), false, true, "tencent-ride", null, null));
        assertThat(ticket.humanConfirmed()).isTrue();
        assertThat(ticket.policyEffect()).isEqualTo("ASK");
        assertThat(ticket.policySource()).isEqualTo("default-ask");
        assertThat(world.policy.isEnforce()).isFalse();
        world.ride.execute(session, input, ticket);
        String second = world.ride.execute(session, input, ticket);
        assertThat(world.processes.launches).isEqualTo(1);
        assertThat(world.processes.lastCommand()).noneMatch(arg -> arg.contains(TOKEN));
        assertThat(second).contains("未回退到宿主机");
        var events = world.traces.events(session);
        assertThat(events).anyMatch(event -> "sandbox_admit".equals(event.type())
                && event.detail().contains("\"ticket_id\":\"" + ticket.id() + "\""));
        assertThat(events).anyMatch(event -> "sandbox_refused".equals(event.type()) && event.title().contains("TICKET_REUSED"));
        var started = events.stream().filter(event -> "sandbox_started".equals(event.type())).findFirst().orElseThrow();
        var startedDetail = new ObjectMapper().readTree(started.detail());
        assertThat(startedDetail.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(startedDetail.get("process_started").asBoolean()).isTrue();
        assertThat(startedDetail.get("callId").asText()).isEqualTo("order-1");
        assertThat(startedDetail.get("joinKey").asText()).startsWith("jk1|");
        assertThat(text(events)).doesNotContain(TOKEN);
        var row = SandboxAuthorityObservations.row("ticket-reuse-refuse", "subprocess");
        row.put("admitted", true);
        row.put("ticket_id", ticket.id());
        row.put("sandbox_event", "sandbox_refused");
        row.put("trace_events", types(events));
        row.put("process_starts", world.processes.launches);
        row.put("effect_success", false);
        SandboxAuthorityObservations.export(List.of(row));
    }

    @Test void credentialProxyReleasesNothingWithoutATicket() throws Exception {
        var world = world();
        var released = new AtomicBoolean();
        var proxy = new CredentialProxy(world.lease, world.audit, () -> {
            released.set(true);
            return TOKEN;
        }, () -> TOKEN, () -> TOKEN);
        assertThatThrownBy(() -> proxy.materialize(null, "mcd"))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("NO_EGRESS_TICKET");
        assertThatThrownBy(() -> proxy.materializeCurrent("mcd"))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("NO_EGRESS_TICKET");
        assertThat(released).isFalse();
        var absent = credentialRow("absent");
        var issuer = new TicketIssuer(world.policy, world.scenes, Clock.systemUTC(), Duration.ZERO);
        ExecutionTicket expired = issuer.issue(new Admission(PrincipalId.LOCAL_OWNER, "acs-credential", "c",
                "query-meals", Map.of(), true, true, "mcd", null, null));
        assertThat(expired.expired(Clock.systemUTC())).isTrue();
        assertThatThrownBy(() -> proxy.materialize(expired, "mcd"))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("NO_EGRESS_TICKET");
        assertThat(released).isFalse();
        assertThat(world.traces.events("acs-credential")).isEmpty();
        assertThat(text(world.traces.events("acs-credential"))).doesNotContain(TOKEN);
        SandboxAuthorityObservations.export(List.of(absent, credentialRow("expired")));
    }

    @Test void refusalIsAuditedOnTheTraceStore() throws Exception {
        var world = world();
        String session = "acs-audit";
        assertThatThrownBy(() -> world.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, session, "c",
                "create-order", Map.of(), false, false, "mcd", null, null)))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");
        var events = world.traces.events(session);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.node()).isEqualTo("sandbox");
            assertThat(event.type()).isEqualTo("sandbox_refused");
            assertThat(event.title()).contains("TICKET_REQUIRED");
            var node = new ObjectMapper().readTree(event.detail());
            assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(node.get("refuse_code").asText()).isEqualTo("TICKET_REQUIRED");
            assertThat(node.get("ticket_id").isNull()).isTrue();
            assertThat(node.get("joinKey").asText()).startsWith("jk1|");
        });
        assertThat(events).noneMatch(event -> "sandbox_admit".equals(event.type()));
        assertThat(text(events)).doesNotContain(TOKEN);
        var row = SandboxAuthorityObservations.row("sandbox_refuse-audited", "trace");
        row.put("sandbox_event", "sandbox_refused");
        row.put("trace_events", types(events));
        SandboxAuthorityObservations.export(List.of(row));
    }

    @Test void bypassPathCannotCountAsAcsGreen() throws Exception {
        var ride = mock(TencentRideService.class);
        when(ride.execute(nullable(String.class), anyMap())).thenReturn("bypass-ok");
        var tool = new TencentRideTool(ride);
        var input = new LinkedHashMap<String, Object>();
        input.put("command", "estimate-price");
        input.put("parameters", Map.of());
        String output = tool.callAsync(ToolCallParam.builder().input(input)
                .runtimeContext(RuntimeContext.builder().sessionId("bypass").build())
                .toolUseBlock(ToolUseBlock.builder().id("b").name("tencent_ride").input(input).content("{}").build())
                .build()).block().getOutput().toString();
        assertThat(output).contains("bypass-ok");
        var properties = new TencentRideProperties();
        properties.setEnabled(true);
        String bare = new TencentRideService(properties, new ObjectMapper(), "skills")
                .execute("bare", Map.of("command", "cancel-order", "parameters", Map.of()));
        assertThat(bare).contains("未回退到宿主机");
        var row = SandboxAuthorityObservations.row("no-ticket-refuse", "bypass");
        row.put("passed", true);
        row.put("sandbox_path", SandboxAuthorityObservations.PATH_BYPASS);
        row.put("effect_success", true);
        row.put("trace_events", List.of());
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(row)).isFalse();
        var admittedWithoutTicket = SandboxAuthorityObservations.row("no-ticket-refuse", "missing-ticket");
        admittedWithoutTicket.put("admitted", true);
        admittedWithoutTicket.put("ticket_id", null);
        admittedWithoutTicket.put("effect_success", true);
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(admittedWithoutTicket)).isFalse();
    }

    @Test void promotionFlagStaysClosed() throws Exception {
        assertThat(Files.readString(Path.of("eval/gates.json"))).contains("\"approved_for_promotion\": false");
    }

    @Test void networkAllowedIsReadOnlyAndDoesNotGrantEgress() throws Exception {
        var closed = refusedShape("net-closed");
        assertThat(closed.get("network_allowed")).isNull();
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(closed)).isTrue();
        SandboxAuthorityObservations.observeNetwork(closed, false);
        assertThat(closed.get("network_allowed")).isEqualTo(false);
        assertThat(closed.get("admitted")).isEqualTo(false);
        assertThat(closed.get("effect_success")).isEqualTo(false);
        assertThat(closed.get("process_starts")).isEqualTo(0);
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(closed)).isTrue();

        var open = refusedShape("net-open");
        SandboxAuthorityObservations.observeNetwork(open, true);
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(open)).isTrue();
        assertThat(open.get("effect_success")).isEqualTo(false);

        var legacy = refusedShape("net-legacy");
        legacy.remove("network_allowed");
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(legacy)).isTrue();

        var bypass = SandboxAuthorityObservations.row("no-ticket-refuse", "net-bypass");
        bypass.put("sandbox_path", SandboxAuthorityObservations.PATH_BYPASS);
        bypass.put("effect_success", true);
        SandboxAuthorityObservations.observeNetwork(bypass, true);
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(bypass)).isFalse();

        var claimed = refusedShape("net-claim");
        claimed.put("network_allowed", "destination-filter");
        assertThat(SandboxAuthorityObservations.countsAsSandboxPass(claimed)).isFalse();
    }

    private static Map<String, Object> refusedShape(String sample) throws Exception {
        var row = SandboxAuthorityObservations.row("no-ticket-refuse", sample);
        row.put("sandbox_event", "sandbox_refused");
        row.put("trace_events", List.of("sandbox_refused"));
        return row;
    }

    private Map<String, Object> refused(World world, String sample, Admission admission) throws Exception {
        assertThatThrownBy(() -> world.executor.admit(admission))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");
        var events = world.traces.events(admission.sessionId());
        assertThat(types(events)).containsExactly("sandbox_refused");
        assertThat(text(events)).doesNotContain(TOKEN);
        var row = SandboxAuthorityObservations.row("no-ticket-refuse", sample);
        row.put("sandbox_event", "sandbox_refused");
        row.put("trace_events", types(events));
        row.put("process_starts", 0);
        row.put("effect_success", false);
        return row;
    }

    private Map<String, Object> rideWithoutTicket(World world, String sample, String session, String command) throws Exception {
        int before = world.processes.launches;
        String result = world.ride.execute(session, Map.of("command", command, "parameters", Map.of()));
        assertThat(result).contains("缺少执行票据");
        assertThat(world.processes.launches).isEqualTo(before);
        var events = world.traces.events(session);
        assertThat(types(events)).containsExactly("sandbox_refused");
        assertThat(text(events)).doesNotContain(TOKEN);
        var row = SandboxAuthorityObservations.row("no-ticket-refuse", sample);
        row.put("sandbox_event", "sandbox_refused");
        row.put("trace_events", types(events));
        row.put("process_starts", 0);
        row.put("effect_success", false);
        return row;
    }

    private static Map<String, Object> credentialRow(String sample) throws Exception {
        var row = SandboxAuthorityObservations.row("credential-without-ticket-zero", sample);
        row.put("credential_released", false);
        row.put("sandbox_event", null);
        row.put("trace_events", List.of());
        return row;
    }

    private ProcessLaunch launch(ExecutionTicket ticket) throws Exception {
        Path cwd = temp.resolve("cwd");
        Files.createDirectories(cwd);
        Path stdout = temp.resolve("stdout");
        Files.createFile(stdout);
        return new ProcessLaunch(ticket, List.of("/bin/true"), List.of(new ProcessLaunch.Mount(cwd, cwd, false)),
                false, false, cwd, stdout, Duration.ofSeconds(1));
    }

    private World world() {
        var traces = new TraceStore((session, event) -> {});
        var audit = new TraceSandboxAudit(traces);
        var policy = new PolicyEngine("shadow");
        var scenes = new SceneCatalog();
        var properties = new SandboxProperties();
        properties.setRoot(temp.resolve("sandbox-root"));
        properties.setPrincipal("local-owner");
        properties.setContainerEnabled(false);
        properties.setTicketTtl(Duration.ofMinutes(2));
        properties.setBwrap("bwrap");
        var issuer = new TicketIssuer(policy, scenes, Clock.systemUTC(), properties.getTicketTtl());
        var workspace = new WorkspaceLayout(properties);
        var lease = new CredentialLease();
        var executor = new SandboxExecutor(issuer, workspace, lease, audit, properties, new ContainerRuntimeHook(properties));
        var credentials = new CredentialProxy(lease, audit, () -> TOKEN, () -> TOKEN, () -> TOKEN);
        var processes = new RecordingSandbox();
        var rideProperties = new TencentRideProperties();
        rideProperties.setEnabled(true);
        rideProperties.setToken(TOKEN);
        rideProperties.setPython(Files.isExecutable(Path.of("/bin/true")) ? "/bin/true" : "/usr/bin/true");
        rideProperties.setDataDirectory(temp.resolve("ride-data"));
        rideProperties.setTimeout(Duration.ofSeconds(5));
        var ride = new TencentRideService(rideProperties, new ObjectMapper(), "skills",
                executor, processes, credentials, workspace, audit, scenes);
        return new World(policy, scenes, lease, audit, executor, traces, processes, ride);
    }

    private static List<String> types(List<TraceEvent> events) {
        return events.stream().map(TraceEvent::type).toList();
    }

    private static String text(List<TraceEvent> events) {
        var builder = new StringBuilder();
        for (var event : events) builder.append(event.type()).append(' ').append(event.title()).append(' ').append(event.detail()).append('\n');
        return builder.toString();
    }

    private record World(PolicyEngine policy, SceneCatalog scenes, CredentialLease lease, TraceSandboxAudit audit,
                         SandboxExecutor executor, TraceStore traces, RecordingSandbox processes, TencentRideService ride) {}

    /** Stands in for bubblewrap. The ticket is still consumed, and a second start is refused. */
    private static final class RecordingSandbox implements ProcessSandbox {
        private ProcessLaunch last;
        private int launches;

        private List<String> lastCommand() { return last == null ? List.of() : last.command(); }

        @Override public List<String> plan(ProcessLaunch launch) { return List.of("sandboxed"); }

        @Override public Process launch(ProcessLaunch launch) throws java.io.IOException {
            launch.ticket().authorizeSubprocess();
            last = launch;
            launches++;
            var builder = new ProcessBuilder("/bin/cat");
            builder.redirectErrorStream(true);
            builder.redirectOutput(launch.stdout().toFile());
            return builder.start();
        }
    }
}
