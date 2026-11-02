package org.celanwang.mio.align;

import org.celanwang.mio.security.PolicyEffect;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parallel trace payload. ticketId is always null and the event is not a policy decision. */
public final class ValueConflictEvents {
    private ValueConflictEvents() {
    }

    public static Map<String, Object> detail(String callId, String tool, String command,
                                              ProbeResult result, PolicyEffect policyEffectAfter) {
        ProbeResult observed = result == null ? ProbeResult.unavailable("价值冲突探测没有返回结果，不能视为没有冲突。") : result;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("schemaVersion", 1);
        detail.put("callId", callId);
        detail.put("tool", tool);
        if (command != null && !command.isBlank()) {
            detail.put("command", command);
        }
        detail.put("status", observed.status().name());
        detail.put("conflictCodes", observed.conflictCodes().stream().map(Enum::name).toList());
        detail.put("effectHint", observed.effectHint().name());
        detail.put("humanReason", observed.humanReason());
        detail.put("evidenceRefs", observed.evidenceRefs());
        detail.put("policyEffectAfter", policyEffectAfter == null ? null : policyEffectAfter.name());
        detail.put("ticketId", null);
        return detail;
    }
}
