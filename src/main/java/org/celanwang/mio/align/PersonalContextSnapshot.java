package org.celanwang.mio.align;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only preference view. Unavailable is not an empty profile. */
public record PersonalContextSnapshot(String state, List<Preference> preferences, List<String> excludedProfileIds) {
    public record Preference(String id, String scope, String title, String content) {
        public Preference {
            id = id == null ? "" : id;
            scope = scope == null ? "" : scope;
            title = title == null ? "" : title;
            content = content == null ? "" : content;
        }
    }

    public PersonalContextSnapshot {
        state = "AVAILABLE".equals(state) ? "AVAILABLE" : "UNAVAILABLE";
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        excludedProfileIds = excludedProfileIds == null ? List.of() : List.copyOf(excludedProfileIds);
        if ("UNAVAILABLE".equals(state)) {
            preferences = List.of();
        }
    }

    public boolean available() {
        return "AVAILABLE".equals(state);
    }

    public static PersonalContextSnapshot empty() {
        return new PersonalContextSnapshot("AVAILABLE", List.of(), List.of());
    }

    public static PersonalContextSnapshot unavailable() {
        return new PersonalContextSnapshot("UNAVAILABLE", List.of(), List.of());
    }

    public static PersonalContextSnapshot fromCapture(Map<String, Object> captured) {
        if (captured == null || !"AVAILABLE".equals(String.valueOf(captured.get("state")))) {
            return unavailable();
        }
        List<Preference> preferences = new ArrayList<>();
        if (captured.get("preferences") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    preferences.add(new Preference(text(map.get("id")), text(map.get("scope")),
                            text(map.get("title")), text(map.get("content"))));
                }
            }
        }
        List<String> excluded = new ArrayList<>();
        if (captured.get("excludedProfileIds") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row != null && !row.toString().isBlank()) {
                    excluded.add(row.toString());
                }
            }
        }
        return new PersonalContextSnapshot("AVAILABLE", preferences, excluded);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
