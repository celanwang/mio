package org.celanwang.mio.align;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Probe output. CONFLICT and UNAVAILABLE always suggest ASK.
 * There is no allow or deny field; PolicyEngine stays the only verdict.
 */
public record ProbeResult(
        String schemaVersion,
        ProbeStatus status,
        List<ConflictCode> conflictCodes,
        String humanReason,
        EffectHint effectHint,
        Map<String, Object> evidenceRefs
) {
    public ProbeResult {
        schemaVersion = "1";
        if (status == null) {
            status = ProbeStatus.UNAVAILABLE;
        }
        conflictCodes = conflictCodes == null ? List.of() : List.copyOf(conflictCodes);
        evidenceRefs = copy(evidenceRefs);
        if (humanReason == null || humanReason.isBlank()) {
            humanReason = switch (status) {
                case CLEAR -> "未发现价值冲突。";
                case CONFLICT -> "发现价值冲突，建议先询问用户。";
                case UNAVAILABLE -> "价值冲突探测不可用，不能视为没有冲突。";
            };
        }
        if (status == ProbeStatus.CLEAR) {
            effectHint = EffectHint.NONE;
            conflictCodes = List.of();
        } else {
            effectHint = EffectHint.SUGGEST_ASK;
        }
    }

    public static ProbeResult clear(String reason, Map<String, Object> evidence) {
        return new ProbeResult("1", ProbeStatus.CLEAR, List.of(), reason, EffectHint.NONE, evidence);
    }

    public static ProbeResult conflict(List<ConflictCode> codes, String reason, Map<String, Object> evidence) {
        List<ConflictCode> present = codes == null || codes.isEmpty() ? List.of(ConflictCode.OTHER) : codes;
        return new ProbeResult("1", ProbeStatus.CONFLICT, present, reason, EffectHint.SUGGEST_ASK, evidence);
    }

    public static ProbeResult unavailable(String reason) {
        return new ProbeResult("1", ProbeStatus.UNAVAILABLE, List.of(), reason, EffectHint.SUGGEST_ASK, Map.of());
    }

    private static Map<String, Object> copy(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        evidence.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}
