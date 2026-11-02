package org.celanwang.mio.align;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProbeRequest(
        String sessionId,
        String callId,
        String toolName,
        Map<String, Object> input,
        boolean readOnlyDeclared,
        String domainOrNull,
        String goalIdOrNull,
        PersonalContextSnapshot prefs,
        List<HardConstraint> hardConstraints,
        List<String> skillRedlines
) {
    public ProbeRequest {
        input = copy(input);
        hardConstraints = hardConstraints == null ? List.of() : List.copyOf(hardConstraints);
        skillRedlines = skillRedlines == null ? List.of() : List.copyOf(skillRedlines);
        if (prefs == null) {
            prefs = PersonalContextSnapshot.unavailable();
        }
    }

    public static ProbeRequest baseline(String sessionId, String callId, String toolName,
                                        Map<String, Object> input, boolean readOnlyDeclared) {
        return new ProbeRequest(sessionId, callId, toolName, input, readOnlyDeclared, null, null,
                PersonalContextSnapshot.empty(), List.of(), List.of());
    }

    public static ProbeRequest unavailable(String sessionId, String callId, String toolName,
                                           Map<String, Object> input, boolean readOnlyDeclared, String domainOrNull) {
        return new ProbeRequest(sessionId, callId, toolName, input, readOnlyDeclared, domainOrNull, null,
                PersonalContextSnapshot.unavailable(), List.of(), List.of());
    }

    private static Map<String, Object> copy(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}
