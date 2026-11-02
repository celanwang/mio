package org.celanwang.mio.evaluation;

import java.util.List;
import java.util.Map;

/** Labels stay distinct. production-agent is only the conjunction of the parity checks. */
public final class EvaluationTargets {
    public static final String SCRIPTED_FIXTURE = "scripted-fixture";
    public static final String SKILL_AND_PERMISSION_SIMULATION = "skill-and-permission-simulation";
    public static final String PRODUCTION_AGENT = "production-agent";
    public static final List<String> PARITY_CHECKS = List.of(
            "agent_config_prompt", "live_memory", "jev_router", "dynamic_mcp_schema");

    public static String label(Map<String, Boolean> checks) {
        boolean parity = PARITY_CHECKS.stream().allMatch(name -> Boolean.TRUE.equals(checks.get(name)));
        return parity ? PRODUCTION_AGENT : SCRIPTED_FIXTURE;
    }

    private EvaluationTargets() {}
}
