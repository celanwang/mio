package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Open planning is scored by world constraints. Extra listed reads are allowed; a mismatched confirm is not. */
class EffectPlanningTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void exportConstraintRunsThatKeepExtraSafeReads() throws Exception {
        var cases = new ArrayList<JsonNode>();
        for (JsonNode task : mapper.readTree(Path.of("eval/datasets/tasks.json").toFile()).path("cases")) {
            if ("constraints".equals(task.path("scoring").asText())) cases.add(task);
        }
        assertThat(cases).hasSize(3);
        var rows = new ArrayList<Map<String, Object>>();
        for (JsonNode task : cases) {
            for (int sample = 0; sample < 5; sample++) {
                rows.add(run(task, "fixture-" + sample));
            }
        }
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContainKey("error"));
        EffectObservations.writeJsonl("effect-runs.jsonl", rows);
    }

    @Test void unexpectedConfirmationIsNotApprovedAndWritesNothing() {
        try (var harness = new AgentHarness()) {
            harness.add("query-meals", true);
            harness.add("calculate-price", true);
            harness.add("create-order", false);
            harness.add("unseen_write", false);
            harness.call("query-meals", Map.of());
            harness.call("unseen_write", Map.of("n", 1));
            harness.say("done");
            var pending = harness.start();
            assertThat(pending.getPendingConfirmations()).hasSize(1);
            assertThat(pending.getPendingConfirmations().get(0).name()).isEqualTo("unseen_write");
            harness.decide(pending, false);
            assertThat(harness.world.actions).containsExactly("query-meals");
            assertThat(harness.world.state).doesNotContainKey("unknownWrite");
        }
    }

    private Map<String, Object> run(JsonNode task, String sample) throws Exception {
        String skill = task.path("skill").asText();
        try (var harness = new AgentHarness()) {
            if ("tencent-ride".equals(skill)) harness.addRide();
            else {
                harness.add("query-meals", true);
                harness.add("calculate-price", true);
                harness.add("query-order", true);
                harness.add("create-order", false);
            }
            boolean inserted = false;
            for (JsonNode step : task.path("steps")) {
                if (!inserted) {
                    if ("tencent-ride".equals(skill)) {
                        harness.call("tencent_ride", Map.of("command", "estimate-price", "parameters", Map.of()));
                    } else harness.call("query-order", Map.of());
                    inserted = true;
                }
                harness.call(step.path("tool").asText(), mapper.convertValue(step.path("input"), new TypeReference<>() {}));
            }
            harness.say("done");
            var response = harness.start();
            var row = EffectObservations.base("tasks", skill);
            row.put("task_id", task.path("id").asText());
            row.put("sample_id", sample);
            var decisions = new ArrayList<Map<String, Object>>();
            if (task.path("approval").isNull()) {
                assertThat(response.getPendingConfirmations()).as(task.path("id").asText()).isEmpty();
                row.put("unexpected_confirm", false);
                row.put("confirm_prompts", 0);
            } else {
                assertThat(response.getPendingConfirmations()).as(task.path("id").asText()).hasSize(1);
                var card = response.getPendingConfirmations().get(0);
                var expected = task.path("expected_confirm");
                boolean matches = card.name().equals(expected.path("tool").asText())
                        && mapper.valueToTree(card.input()).equals(expected.path("input"));
                boolean approved = matches && task.path("approval").asBoolean();
                decisions.add(Map.of("tool", card.name(), "input", card.input(), "approved", approved));
                row.put("unexpected_confirm", !matches);
                row.put("confirm_prompts", 1);
                if (!matches) row.put("error", "unexpected_confirmation");
                harness.decide(response, approved);
            }
            row.put("user_decisions", decisions);
            row.put("model_calls", harness.modelInputs.size());
            row.put("final_state", new LinkedHashMap<>(harness.world.state));
            row.put("actions", List.copyOf(harness.world.actions));
            SandboxAuthorityObservations.copyTrace(row, harness.traces.events(harness.session),
                    !harness.world.actions.isEmpty(), 0);
            var state = mapper.valueToTree(harness.world.state);
            task.path("required_state").properties().forEach(field ->
                    assertThat(state.path(field.getKey())).as(task.path("id").asText()).isEqualTo(field.getValue()));
            for (JsonNode key : task.path("forbidden_state_keys")) {
                assertThat(harness.world.state).doesNotContainKey(key.asText());
            }
            for (JsonNode name : task.path("forbidden_actions")) {
                assertThat(harness.world.actions).doesNotContain(name.asText());
            }
            return row;
        }
    }
}
