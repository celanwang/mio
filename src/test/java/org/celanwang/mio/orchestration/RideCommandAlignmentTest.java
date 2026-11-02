package org.celanwang.mio.orchestration;

import org.celanwang.mio.sandbox.RiskClass;
import org.celanwang.mio.sandbox.SceneCatalog;
import org.celanwang.mio.tool.TencentRideTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RideCommandAlignmentTest {
    @Test
    void sandboxRiskFollowsTheDomainReadBoundary() {
        var scenes = new SceneCatalog();
        var tool = new TencentRideTool(null);
        for (String command : TencentRideTool.COMMANDS) {
            boolean read = GuardedDomainTool.readOnly(tool, Map.of("command", command));
            RiskClass risk = scenes.classify("tencent_ride", Map.of("command", command), false, null).risk();
            if (read) {
                assertThat(risk).as(command).isEqualTo(RiskClass.READONLY);
            } else if ("cancel-order".equals(command)) {
                assertThat(risk).isEqualTo(RiskClass.IRREVERSIBLE);
            } else {
                assertThat(risk).as(command).isEqualTo(RiskClass.REVERSIBLE);
            }
        }
    }
}
