package org.celanwang.mio.evaluation;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** The offline harness is not the production agent. Missing checks keep the label at scripted-fixture. */
class ProductionAgentScaffoldTest {
    @Test void offlinePathDoesNotClaimProductionParity() throws Exception {
        var checks = new LinkedHashMap<String, Boolean>();
        checks.put("agent_config_prompt", false);
        checks.put("live_memory", false);
        checks.put("jev_router", false);
        checks.put("dynamic_mcp_schema", false);
        assertThat(EvaluationTargets.label(checks)).isEqualTo(EvaluationTargets.SCRIPTED_FIXTURE);
        var complete = new LinkedHashMap<>(checks);
        complete.replaceAll((name, value) -> true);
        assertThat(EvaluationTargets.label(complete)).isEqualTo(EvaluationTargets.PRODUCTION_AGENT);
        try (var harness = new AgentHarness()) {
            assertThat(harness.policy.isEnforce()).isFalse();
        }
        var document = new LinkedHashMap<String, Object>();
        document.put("evaluation_target", EvaluationTargets.SCRIPTED_FIXTURE);
        document.put("production_parity", false);
        document.put("checks", checks);
        document.put("missing", List.copyOf(checks.keySet()));
        document.put("note", "Scaffold only. AgentHarness uses an empty prompt, mock memory, mock Jev, and synthetic tools. "
                + "Do not label observations production-agent until AgentConfig prompt, live MioLongTermMemory, Jev routing, and dynamic MCP schemas are all present.");
        EffectObservations.writeJson("production-agent-scaffold.json", document);
    }
}
