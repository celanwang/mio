package org.celanwang.mio.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.trace.TraceEvent;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceSandboxAuditDetailTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "synthetic-acs-token";
    private static final String HEADER = "Authorization: Bearer " + TOKEN;
    private static final String COOKIE = "Cookie: session=synthetic-acs-token";

    @TempDir Path temp;

    @Test
    void startedDetail_includesSchemaVersionJoinKeyAndCallId() throws Exception {
        var traces = traces();
        var audit = new TraceSandboxAudit(traces);
        ExecutionTicket ticket = ticket("sess-9", "call_abc123", "tencent_ride");
        audit.started(ticket, true);
        TraceEvent event = traces.events("sess-9").get(0);
        JsonNode detail = JSON.readTree(event.detail());
        assertThat(event.type()).isEqualTo("sandbox_started");
        assertThat(event.node()).isEqualTo("sandbox");
        assertThat(detail.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(detail.get("callId").asText()).isEqualTo("call_abc123");
        assertThat(detail.get("joinKey").asText()).startsWith("jk1|sess-9|" + ticket.id() + "|tencent_ride|");
        assertThat(detail.get("joinKey").asText()).endsWith("|" + event.timestamp());
        assertThat(detail.get("ticket_id").asText()).isEqualTo(ticket.id());
        assertThat(detail.get("refuse_code").isNull()).isTrue();
        assertThat(detail.get("process_started").asBoolean()).isTrue();
        assertThat(detail.get("event_kind").asText()).isEqualTo("started");
        assertThat(detail.get("tool").asText()).isEqualTo("tencent_ride");
    }

    @Test
    void refusedBeforeTicket_allowsNullCallIdButRequiresJoinKey() throws Exception {
        World world = world();
        assertThatThrownBy(() -> world.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, "sess-9", null,
                "create-order", java.util.Map.of(), false, false, "mcd", null, null)))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");
        List<TraceEvent> events = world.traces.events("sess-9");
        assertThat(events).hasSize(1);
        TraceEvent event = events.get(0);
        JsonNode detail = JSON.readTree(event.detail());
        assertThat(event.type()).isEqualTo("sandbox_refused");
        assertThat(detail.get("callId").isNull()).isTrue();
        assertThat(detail.get("ticket_id").isNull()).isTrue();
        assertThat(detail.get("joinKey").asText()).startsWith("jk1|sess-9||create-order|");
        assertThat(detail.get("joinKey").asText()).endsWith("|" + event.timestamp());
        assertThat(detail.get("refuse_code").asText()).isEqualTo("TICKET_REQUIRED");
        assertThat(detail.get("process_started").asBoolean()).isFalse();
        assertThat(detail.get("event_kind").asText()).isEqualTo("refused");
    }

    @Test
    void unavailable_usesDistinctType() throws Exception {
        World world = world();
        assertThatThrownBy(() -> world.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, "sess-9", "call_abc123",
                "query-meals", java.util.Map.of(), true, true, "mcd", null,
                new Classification(RiskClass.READONLY, RuntimeKind.CONTAINER, "mcd"))))
                .isInstanceOf(SandboxUnavailableException.class);
        List<TraceEvent> events = world.traces.events("sess-9");
        assertThat(events.stream().map(TraceEvent::type)).contains("sandbox_unavailable");
        assertThat(events.stream().map(TraceEvent::type)).doesNotContain("sandbox_refused");
        assertThat(events.stream().filter(event -> "sandbox_started".equals(event.type()))).isEmpty();
        TraceEvent unavailable = events.stream().filter(event -> "sandbox_unavailable".equals(event.type()))
                .findFirst().orElseThrow();
        JsonNode detail = JSON.readTree(unavailable.detail());
        assertThat(detail.get("event_kind").asText()).isEqualTo("unavailable");
        assertThat(detail.get("process_started").asBoolean()).isFalse();
        assertThat(detail.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(detail.get("callId").asText()).isEqualTo("call_abc123");
        assertThat(detail.get("ticket_id").asText()).isNotBlank();
        assertThat(detail.get("refuse_code").asText()).isEqualTo("CONTAINER_DISABLED");
    }

    @Test
    void whenCallIdPresent_joinKeyStillPresent() {
        String joinKey = SandboxAuditDetail.joinKey("sess-9", "ticket-uuid-1", "tencent_ride", 1762099200123L);
        assertThat(joinKey).isEqualTo("jk1|sess-9|ticket-uuid-1|tencent_ride|1762099200123");
        assertThat(SandboxAuditDetail.prefersCallId("call_abc123")).isTrue();
        assertThat(SandboxAuditDetail.joinIdentity("sess-9", "call_abc123", joinKey))
                .isEqualTo("sess-9\u0000call:call_abc123");
        assertThat(SandboxAuditDetail.prefersCallId(null)).isFalse();
        assertThat(SandboxAuditDetail.prefersCallId("  ")).isFalse();
        assertThat(SandboxAuditDetail.joinIdentity("sess-9", null, joinKey))
                .isEqualTo("sess-9\u0000jk:" + joinKey);
    }

    @Test
    void detailNeverContainsCredentialMaterial() throws Exception {
        var traces = traces();
        var audit = new TraceSandboxAudit(traces);
        ExecutionTicket ticket = ticket("sess-9", "call_abc123", "tencent_ride");
        audit.admitted(ticket);
        audit.started(ticket, HEADER + " " + COOKIE + " " + TOKEN);
        audit.egress(ticket, TOKEN);
        audit.refused(ticket, "TICKET_REUSED");
        String text = traces.events("sess-9").stream()
                .map(event -> event.type() + " " + event.title() + " " + event.detail())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(text).doesNotContain(TOKEN).doesNotContain("Authorization").doesNotContain("Cookie")
                .doesNotContain("Bearer");
    }

    @Test
    void admitRefused_recordsSandboxRefused() {
        World world = world();
        assertThatThrownBy(() -> world.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, "sess-refused", "call-1",
                "create-order", java.util.Map.of(), false, false, "mcd", null, null)))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(error -> ((SandboxRefusedException) error).code())
                .isEqualTo("TICKET_REQUIRED");
        assertThat(world.traces.events("sess-refused")).extracting(TraceEvent::type).containsExactly("sandbox_refused");
    }

    @Test
    void admitUnavailable_recordsSandboxUnavailable() {
        World world = world();
        assertThatThrownBy(() -> world.executor.admit(new Admission(PrincipalId.LOCAL_OWNER, "sess-down", "call-1",
                "query-meals", java.util.Map.of(), true, true, "mcd", null,
                new Classification(RiskClass.READONLY, RuntimeKind.CONTAINER, "mcd"))))
                .isInstanceOf(SandboxUnavailableException.class);
        assertThat(world.traces.events("sess-down")).extracting(TraceEvent::type)
                .contains("sandbox_unavailable")
                .doesNotContain("sandbox_refused", "sandbox_started");
    }

    private static ExecutionTicket ticket(String sessionId, String callId, String tool) {
        return ExecutionTicket.issue(PrincipalId.LOCAL_OWNER, sessionId, callId, tool,
                new Classification(RiskClass.IRREVERSIBLE, RuntimeKind.SUBPROCESS, "tencent-ride"),
                "ASK", "default-ask", true, true, false, false, Clock.systemUTC(), Duration.ofMinutes(2));
    }

    private static TraceStore traces() {
        return new TraceStore((session, event) -> {});
    }

    private World world() {
        var store = traces();
        var audit = new TraceSandboxAudit(store);
        var policy = new PolicyEngine("shadow");
        var scenes = new SceneCatalog();
        var properties = new SandboxProperties();
        properties.setRoot(temp.resolve("sandbox-root"));
        properties.setContainerEnabled(false);
        properties.setTicketTtl(Duration.ofMinutes(2));
        var issuer = new TicketIssuer(policy, scenes, Clock.systemUTC(), properties.getTicketTtl());
        var executor = new SandboxExecutor(issuer, new WorkspaceLayout(properties), new CredentialLease(), audit,
                properties, new ContainerRuntimeHook(properties));
        return new World(executor, store);
    }

    private record World(SandboxExecutor executor, TraceStore traces) {}
}
