package org.celanwang.mio.align;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * One deterministic probe shared by the pre-execution hold and the later trace.
 * A missing call still fails toward SUGGEST_ASK for writes.
 */
@Component
public final class ValueConflictAdvisor {
    private final ValueConflictProbe probe;
    private final ProbeRequests requests;
    private final Map<String, ProbeResult> byCall = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ProbeResult> eldest) {
            return size() > 500;
        }
    });
    /** Results already computed inside checkPermissions, replayed when the trace runs after agent.call. */
    private final Map<String, ConcurrentLinkedDeque<ProbeResult>> preExecution = new ConcurrentHashMap<>();

    public ValueConflictAdvisor(ValueConflictProbe probe, ProbeRequests requests) {
        this.probe = probe == null ? new DeterministicValueConflictProbe() : probe;
        this.requests = requests;
    }

    public static ValueConflictAdvisor baseline() {
        return new ValueConflictAdvisor(new DeterministicValueConflictProbe(), null);
    }

    public ProbeResult inspect(String sessionId, String callId, String toolName, Map<String, Object> input,
                               boolean readOnlyDeclared, String intent) {
        if (callId != null && !callId.isBlank()) {
            ProbeResult cached = byCall.get(callId);
            if (cached != null) {
                return cached;
            }
        }
        String queuedKey = key(sessionId, toolName);
        var queued = preExecution.get(queuedKey);
        ProbeResult result = queued == null ? null : queued.poll();
        if (queued != null && queued.isEmpty()) {
            preExecution.remove(queuedKey, queued);
        }
        if (result == null) {
            result = compute(sessionId, callId, toolName, input, readOnlyDeclared, intent);
        }
        if (callId != null && !callId.isBlank()) {
            byCall.put(callId, result);
        }
        return result;
    }

    public boolean suggestsAsk(String callId) {
        ProbeResult result = callId == null ? null : byCall.get(callId);
        return result != null && result.effectHint() == EffectHint.SUGGEST_ASK;
    }

    /** True when a write must stay on the confirmation path. Pure reads return false. */
    public boolean holdWrite(String sessionId, String toolName, Map<String, Object> input,
                             boolean declaredReadOnly, String intent) {
        if (WritePath.isPureReadOnly(toolName, input, declaredReadOnly)) {
            return false;
        }
        ProbeResult result = compute(sessionId, null, toolName, input, false, intent);
        var queue = preExecution.computeIfAbsent(key(sessionId, toolName), ignored -> new ConcurrentLinkedDeque<>());
        queue.add(result);
        while (queue.size() > 8) {
            queue.poll();
        }
        return result.effectHint() == EffectHint.SUGGEST_ASK;
    }

    private static String key(String sessionId, String toolName) {
        return (sessionId == null ? "" : sessionId) + "\0" + (toolName == null ? "" : toolName);
    }

    private ProbeResult compute(String sessionId, String callId, String toolName, Map<String, Object> input,
                                boolean readOnlyDeclared, String intent) {
        try {
            ProbeRequest request = requests == null
                    ? ProbeRequest.baseline(sessionId, callId, toolName, input, readOnlyDeclared)
                    : requests.forCall(sessionId, callId, toolName, input, readOnlyDeclared, intent);
            ProbeResult result = probe.probe(request);
            return result == null ? ProbeResult.unavailable("价值冲突探测没有返回结果，不能视为没有冲突。") : result;
        } catch (RuntimeException failure) {
            return ProbeResult.unavailable("价值冲突探测失败，不能视为没有冲突。");
        }
    }
}
