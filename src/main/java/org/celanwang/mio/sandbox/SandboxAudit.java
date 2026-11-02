package org.celanwang.mio.sandbox;

/**
 * Sandbox observations. These are not a second allow/ask/deny.
 * {@code process_started} is true only after a subprocess or container launch has returned.
 * Opening a JVM {@link SandboxScope} records {@code sandbox_started} with {@code process_started=false}.
 * Human messages passed by callers are not copied into {@code detail}; that text can carry paths.
 */
public interface SandboxAudit {
    void record(SandboxEvent event);

    default void admitted(ExecutionTicket ticket) {
        record(SandboxEvent.structured("sandbox_admit", ticket.shadowDeny() ? "SHADOW_DENY" : "ISSUED",
                ticket.sessionId(), ticket.tool(), ticket.principal(), ticket.callId(), ticket.id(),
                null, "admitted", false));
    }

    default void refused(String sessionId, String tool, PrincipalId principal, String code, String detail) {
        refused(sessionId, null, tool, principal, code);
    }

    default void refused(String sessionId, String callId, String tool, PrincipalId principal, String code) {
        record(SandboxEvent.structured("sandbox_refused", code, sessionId, tool, principal,
                callId, null, code, "refused", false));
    }

    default void refused(ExecutionTicket ticket, String code) {
        record(SandboxEvent.structured("sandbox_refused", code, ticket.sessionId(), ticket.tool(), ticket.principal(),
                ticket.callId(), ticket.id(), code, "refused", false));
    }

    default void unavailable(String sessionId, String callId, String tool, PrincipalId principal, String code) {
        record(SandboxEvent.structured("sandbox_unavailable", code, sessionId, tool, principal,
                callId, null, code, "unavailable", false));
    }

    default void unavailable(ExecutionTicket ticket, String code) {
        record(SandboxEvent.structured("sandbox_unavailable", code, ticket.sessionId(), ticket.tool(),
                ticket.principal(), ticket.callId(), ticket.id(), code, "unavailable", false));
    }

    /** JVM scope open. Does not mean a subprocess was launched. */
    default void started(ExecutionTicket ticket, String detail) {
        started(ticket, false);
    }

    default void started(ExecutionTicket ticket, boolean processStarted) {
        record(SandboxEvent.structured("sandbox_started", "STARTED", ticket.sessionId(), ticket.tool(),
                ticket.principal(), ticket.callId(), ticket.id(), null, "started", processStarted));
    }

    default void egress(ExecutionTicket ticket, String credential) {
        record(SandboxEvent.structured("sandbox_egress", "CREDENTIAL", ticket.sessionId(), ticket.tool(),
                ticket.principal(), ticket.callId(), ticket.id(), null, "egress", false));
    }

    default void finished(ExecutionTicket ticket, String code) {
        record(SandboxEvent.structured("sandbox_finish", code, ticket.sessionId(), ticket.tool(),
                ticket.principal(), ticket.callId(), ticket.id(), null, "finished", false));
    }
}
