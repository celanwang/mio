package org.celanwang.mio.align;

import org.celanwang.mio.config.ToolPolicies;

import java.util.Map;
import java.util.Set;

/** Pure reads are traced only. Reversible and irreversible writes can be held for confirmation. */
public final class WritePath {
    /** Kept aligned with {@code GuardedDomainTool#readOnly} ride read commands. */
    private static final Set<String> RIDE_READ = Set.of(
            "preflight", "get-resident-city", "addr-keys", "addr-get-value",
            "short-cut-keys", "short-cut-get-value", "poi-search", "select-poi", "estimate-price",
            "query-ongoing-order", "query-order", "check-cancel-fee", "query-driver-location",
            "build-static-map-url", "addr-sync-to-state");

    private WritePath() {
    }

    public static boolean isPureReadOnly(String toolName, Map<String, Object> input, boolean declaredReadOnly) {
        if (toolName == null) {
            return declaredReadOnly;
        }
        if (ToolPolicies.HUMAN_ONLY.contains(toolName)) {
            return false;
        }
        if ("tencent_ride".equals(toolName)) {
            Object command = input == null ? null : input.get("command");
            return command instanceof String text && RIDE_READ.contains(text);
        }
        if (ToolPolicies.JEV_REVIEWABLE.contains(toolName)) {
            return false;
        }
        return declaredReadOnly || ToolPolicies.READ_ONLY.contains(toolName);
    }
}
