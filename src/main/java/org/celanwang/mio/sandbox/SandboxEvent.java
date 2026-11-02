package org.celanwang.mio.sandbox;

/** Audit record. {@link #detail()} is schemaVersion=1 JSON and must not contain credential material. */
public record SandboxEvent(
        String type,
        String code,
        String sessionId,
        String tool,
        String principal,
        String callId,
        String ticketId,
        String refuseCode,
        String eventKind,
        boolean processStarted) {

    static SandboxEvent structured(String type, String code, String sessionId, String tool, PrincipalId principal,
                                   String callId, String ticketId, String refuseCode, String eventKind,
                                   boolean processStarted) {
        return new SandboxEvent(type, code, sessionId, tool, principal == null ? "" : principal.value(),
                callId, ticketId, refuseCode, eventKind, processStarted);
    }

    /** JSON stamped with the current clock. Trace export uses {@link #detailAt(long)} so the key matches the row. */
    public String detail() {
        return detailAt(System.currentTimeMillis());
    }

    public String detailAt(long timestamp) {
        return SandboxAuditDetail.json(sessionId, callId, ticketId, refuseCode, eventKind, processStarted, tool, timestamp);
    }
}
