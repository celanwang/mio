package org.celanwang.mio.align;

import org.celanwang.mio.config.ToolPolicies;

/** Skill directory that owns a tool, or null when the tool is not in a known domain. */
public final class ToolDomains {
    private ToolDomains() {
    }

    public static String of(String tool) {
        if (tool == null) {
            return null;
        }
        if (tool.startsWith("tencent_ride")) {
            return "tencent-ride";
        }
        if (tool.contains("weibo")) {
            return "weibo-assistant";
        }
        if (ToolPolicies.READ_ONLY.contains(tool) || ToolPolicies.HUMAN_ONLY.contains(tool)
                || ToolPolicies.JEV_REVIEWABLE.contains(tool)) {
            return "mcd-ordering";
        }
        return null;
    }
}
