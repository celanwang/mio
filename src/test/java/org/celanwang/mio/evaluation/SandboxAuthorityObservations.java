package org.celanwang.mio.evaluation;

import org.celanwang.mio.trace.TraceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observation shape for the sandbox half of Authority Continuity.
 *
 * <p>Effect and ACS harnesses call {@link #copyTrace} after a real admission. {@code sandbox_path=bypass}
 * is never a pass. The single-arg tool constructors stay valid for pure unit tests; they are not ACS evidence.
 *
 * <p>Scoring lives in {@code eval/effects.py}. This check only stops a row that would not pass from being
 * merged into {@code authority-continuity.jsonl}.
 *
 * <p>{@code network_allowed} is an optional read-only echo of the ticket's boolean egress bit
 * ({@code IsolationProfile.networkAllowed}). Null, or a missing key, means this trial did not record it.
 * The bit does not grant egress, and it is not a destination filter or a JVM network namespace.
 */
public final class SandboxAuthorityObservations {
    public static final String PATH_SANDBOXED = "sandboxed";
    public static final String PATH_BYPASS = "bypass";
    public static final List<String> RULES = List.of(
            "no-ticket-refuse",
            "ticket-reuse-refuse",
            "credential-without-ticket-zero",
            "sandbox_refuse-audited");
    private static final Map<String, String> REFUSE_CODES = Map.of(
            "no-ticket-refuse", "TICKET_REQUIRED",
            "ticket-reuse-refuse", "TICKET_REUSED",
            "credential-without-ticket-zero", "NO_EGRESS_TICKET",
            "sandbox_refuse-audited", "TICKET_REQUIRED");
    private static final Set<String> ADMITTED_TRACE = Set.of(
            "sandbox_admit", "sandbox_started", "sandbox_egress", "sandbox_refused");

    public static Map<String, Object> row(String rule, String sample) throws Exception {
        if (!RULES.contains(rule)) throw new IllegalArgumentException("unknown sandbox authority rule " + rule);
        var row = EffectObservations.base("authority", "*");
        row.put("rule_id", rule);
        row.put("sample_id", sample);
        row.put("passed", true);
        row.put("sandbox_path", PATH_SANDBOXED);
        row.put("policy_engine", "PolicyEngine");
        row.put("admitted", false);
        row.put("ticket_id", null);
        row.put("sandbox_event", null);
        row.put("trace_events", List.of());
        row.put("effect_success", false);
        row.put("process_starts", 0);
        row.put("refuse_code", REFUSE_CODES.get(rule));
        row.put("network_allowed", null);
        return row;
    }

    /**
     * Record the ticket boolean already decided by {@code SandboxMatrix}.
     * Writing the bit does not admit the call or start a process.
     */
    public static void observeNetwork(Map<String, Object> row, boolean networkAllowed) {
        row.put("network_allowed", networkAllowed);
    }

    /** True only for a sandboxed scripted trial the aggregator can count. Bypass is always false. */
    public static boolean countsAsSandboxPass(Map<String, Object> row) {
        if (!Boolean.TRUE.equals(row.get("passed"))) return false;
        if (!PATH_SANDBOXED.equals(row.get("sandbox_path"))) return false;
        if (!EvaluationTargets.SCRIPTED_FIXTURE.equals(row.get("evaluation_target"))) return false;
        if (!Boolean.FALSE.equals(row.get("production_parity"))) return false;
        if (!"PolicyEngine".equals(row.get("policy_engine"))) return false;
        if (!Boolean.FALSE.equals(row.get("effect_success"))) return false;
        if (!networkObservationOk(row)) return false;
        String rule = String.valueOf(row.get("rule_id"));
        String expectedCode = REFUSE_CODES.get(rule);
        if (expectedCode == null || !expectedCode.equals(row.get("refuse_code"))) return false;
        boolean admitted = Boolean.TRUE.equals(row.get("admitted"));
        Object ticket = row.get("ticket_id");
        boolean ticketPresent = ticket instanceof String && !((String) ticket).isBlank();
        if (admitted != ticketPresent) return false;
        List<String> traces = row.get("trace_events") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        int starts = row.get("process_starts") instanceof Number number ? number.intValue() : -1;
        return switch (rule) {
            case "no-ticket-refuse" -> !admitted && starts == 0
                    && "sandbox_refused".equals(row.get("sandbox_event"))
                    && List.of("sandbox_refused").equals(traces);
            case "sandbox_refuse-audited" -> !admitted && starts == 0
                    && "sandbox_refused".equals(row.get("sandbox_event"))
                    && traces.contains("sandbox_refused") && !traces.contains("sandbox_admit");
            case "ticket-reuse-refuse" -> admitted && starts == 1
                    && "sandbox_refused".equals(row.get("sandbox_event"))
                    && traces.containsAll(ADMITTED_TRACE);
            case "credential-without-ticket-zero" -> !admitted && starts == 0
                    && row.get("sandbox_event") == null && Boolean.FALSE.equals(row.get("credential_released"))
                    && traces.isEmpty();
            default -> false;
        };
    }

    /**
     * Project sandbox rows already stored on TraceStore onto this contract.
     * An effect that landed without {@code ticket_id} is not observable as a pass.
     * Credential refusals stay out of the trace: pass an empty event list and {@code effectSuccess=false}.
     */
    public static void copyTrace(Map<String, Object> row, List<TraceEvent> events, boolean effectSuccess, int processStarts) {
        var types = new ArrayList<String>();
        String ticket = null;
        String last = null;
        if (events != null) {
            for (var event : events) {
                if (event.type() == null || !event.type().startsWith("sandbox_")) continue;
                types.add(event.type());
                last = event.type();
                if ("sandbox_admit".equals(event.type())) {
                    String id = ticketId(event.detail());
                    if (id != null) ticket = id;
                }
            }
        }
        row.put("sandbox_path", PATH_SANDBOXED);
        row.put("policy_engine", "PolicyEngine");
        row.put("admitted", ticket != null);
        row.put("ticket_id", ticket);
        row.put("sandbox_event", last);
        row.put("trace_events", List.copyOf(types));
        row.put("effect_success", effectSuccess);
        row.put("process_starts", processStarts);
        if (effectSuccess && ticket == null) {
            throw new AssertionError("effect_success without ticket_id");
        }
    }

    /** Absent or null is unknown. Any other non-boolean is a claim this contract rejects. */
    private static boolean networkObservationOk(Map<String, Object> row) {
        if (!row.containsKey("network_allowed")) return true;
        Object value = row.get("network_allowed");
        return value == null || value instanceof Boolean;
    }

    static String ticketId(String detail) {
        if (detail == null || detail.isBlank()) return null;
        String json = jsonString(detail, "ticket_id");
        if (json != null) return json.isBlank() ? null : json;
        if (detail.contains("\"ticket_id\":null") || detail.contains("\"ticket_id\": null")) return null;
        int start = detail.indexOf("ticket=");
        if (start < 0) return null;
        start += "ticket=".length();
        int end = start;
        while (end < detail.length() && !Character.isWhitespace(detail.charAt(end))) end++;
        return end == start ? null : detail.substring(start, end);
    }

    private static String jsonString(String detail, String field) {
        String key = "\"" + field + "\":\"";
        int start = detail.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = start;
        while (end < detail.length()) {
            char c = detail.charAt(end);
            if (c == '\\') {
                end += 2;
                continue;
            }
            if (c == '"') break;
            end++;
        }
        return end <= detail.length() ? detail.substring(start, Math.min(end, detail.length())) : null;
    }

    public static void export(List<Map<String, Object>> rows) throws Exception {
        for (var row : rows) {
            if (!countsAsSandboxPass(row)) {
                throw new AssertionError("sandbox ACS row is not a pass: " + row.get("rule_id") + "/" + row.get("sample_id"));
            }
        }
        EffectObservations.mergeJsonl("authority-continuity.jsonl", rows, "rule_id");
    }

    private SandboxAuthorityObservations() {}
}
