package org.celanwang.mio.sandbox;

public interface SandboxAudit {
    void record(SandboxEvent event);

    default void admitted(ExecutionTicket ticket) {
        record(new SandboxEvent("sandbox_admit", ticket.shadowDeny() ? "SHADOW_DENY" : "ISSUED",
                ticket.sessionId(), ticket.tool(), ticket.principal().value(),
                "ticket=" + ticket.id()
                        + " risk=" + ticket.risk()
                        + " runtime=" + ticket.runtime()
                        + " policy=" + ticket.policyEffect() + "/" + ticket.policySource()
                        + " human=" + ticket.humanConfirmed()
                        + " credential=" + (ticket.credential() == null ? "" : ticket.credential())));
    }

    default void refused(String sessionId, String tool, PrincipalId principal, String code, String detail) {
        record(new SandboxEvent("sandbox_refuse", code, sessionId, tool,
                principal == null ? "" : principal.value(), detail));
    }

    default void started(ExecutionTicket ticket, String detail) {
        record(new SandboxEvent("sandbox_start", "STARTED", ticket.sessionId(), ticket.tool(),
                ticket.principal().value(), "ticket=" + ticket.id() + " " + detail));
    }

    default void egress(ExecutionTicket ticket, String credential) {
        record(new SandboxEvent("sandbox_egress", "CREDENTIAL", ticket.sessionId(), ticket.tool(),
                ticket.principal().value(), "ticket=" + ticket.id() + " credential=" + credential));
    }

    default void finished(ExecutionTicket ticket, String code) {
        record(new SandboxEvent("sandbox_finish", code, ticket.sessionId(), ticket.tool(),
                ticket.principal().value(), "ticket=" + ticket.id()));
    }
}
