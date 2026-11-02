package org.celanwang.mio.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * schemaVersion=1 payload for sandbox audit rows.
 * The map is the only text written to {@code TraceEvent.detail}. Free-text notes and secrets stay out.
 */
final class SandboxAuditDetail {
    static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private SandboxAuditDetail() {}

    static String joinKey(String sessionId, String ticketId, String tool, long tsMillis) {
        return "jk1|" + empty(sessionId) + "|" + empty(ticketId) + "|" + empty(tool) + "|" + tsMillis;
    }

    /**
     * Consumer identity. A non-blank callId always wins. A blank callId may use joinKey.
     * Callers must not pair a callId row by joinKey alone.
     */
    static String joinIdentity(String sessionId, String callId, String joinKey) {
        if (prefersCallId(callId)) {
            return sessionId + "\u0000call:" + callId.trim();
        }
        return sessionId + "\u0000jk:" + joinKey;
    }

    static boolean prefersCallId(String callId) {
        return callId != null && !callId.isBlank();
    }

    static String json(String sessionId, String callId, String ticketId, String refuseCode, String eventKind,
                        boolean processStarted, String tool, long tsMillis) {
        String call = blankToNull(callId);
        String ticket = blankToNull(ticketId);
        String name = blankToNull(tool);
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("callId", call);
        body.put("joinKey", joinKey(sessionId, ticket, name, tsMillis));
        body.put("ticket_id", ticket);
        body.put("refuse_code", blankToNull(refuseCode));
        body.put("event_kind", eventKind);
        body.put("process_started", processStarted);
        body.put("tool", name);
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("沙箱审计明细无法序列化", failure);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String empty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
