package org.celanwang.mio.align;

import org.celanwang.mio.align.IntentResolver.EffectHint;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** B8 evidence cannot grant authority: ticketId is null and memoryElevated is always false. */
public record ResolveResult(String schemaVersion, ResolveStatus status, List<DomainCandidate> candidates,
                            List<AmbiguityCode> ambiguityCodes, String humanReason, EffectHint effectHint,
                            Map<String, Object> evidenceRefs, String ticketId) {
    public ResolveResult {
        schemaVersion = "1";
        status = status == null ? ResolveStatus.UNAVAILABLE : status;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        ambiguityCodes = ambiguityCodes == null ? List.of() : List.copyOf(ambiguityCodes);
        Map<String, Object> evidence = new TreeMap<>();
        if (evidenceRefs != null) {
            evidenceRefs.forEach((key, value) -> { if (key != null && value != null) evidence.put(key, value); });
        }
        evidence.put("memoryElevated", false);
        evidenceRefs = Map.copyOf(evidence);
        ticketId = null;
        effectHint = status == ResolveStatus.AMBIGUOUS ? EffectHint.SUGGEST_CLARIFY : EffectHint.NONE;
        if (status != ResolveStatus.AMBIGUOUS) ambiguityCodes = List.of();
        if (status == ResolveStatus.UNAVAILABLE) candidates = List.of();
    }

    public static ResolveResult unavailable() {
        return new ResolveResult("1", ResolveStatus.UNAVAILABLE, List.of(), List.of(),
                "意图解析来源不可用，不能据此推断单一领域。", EffectHint.NONE, Map.of(), null);
    }
}
