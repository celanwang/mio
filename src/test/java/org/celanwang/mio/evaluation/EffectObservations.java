package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned scripted observations. These files are local artifacts, not model evidence. */
public final class EffectObservations {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static Map<String, Object> base(String dataset, String skill) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("source", "scripted");
        row.put("model", "scripted-fixture-v1");
        row.put("route", "fixture");
        row.put("temperature", 0);
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("dataset_sha256", FixtureBaselineTest.sha(Path.of("eval/datasets", dataset + ".json")));
        row.put("skill_sha256", "*".equals(skill)
                ? FixtureBaselineTest.fingerprintTree(Path.of("skills"))
                : FixtureBaselineTest.skillSha(skill));
        row.put("prompt_sha256", FixtureBaselineTest.sha(Path.of("src/test/java/org/celanwang/mio/evaluation/AgentHarness.java")));
        row.put("evaluation_target", EvaluationTargets.SCRIPTED_FIXTURE);
        row.put("production_parity", false);
        return row;
    }

    public static void writeJsonl(String name, List<Map<String, Object>> rows) throws Exception {
        var directory = Path.of("target/evaluation");
        Files.createDirectories(directory);
        var text = new StringBuilder();
        for (var row : rows) text.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(directory.resolve(name), text);
    }

    public static void writeJson(String name, Map<String, Object> document) throws Exception {
        var directory = Path.of("target/evaluation");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }

    private EffectObservations() {}
}
