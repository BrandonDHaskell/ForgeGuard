package dev.cleansweep.forgeguard.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads a task YAML file into a TaskSpec. Fails loudly on anything malformed. */
public final class TaskSpecLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public LoadedTask load(Path file) {
        try {
            JsonNode n = YAML.readTree(Files.readString(file));
            Path base = file.toAbsolutePath().getParent().getParent();

            Limits limits = Limits.DEFAULTS;
            JsonNode l = n.get("limits");
            if (l != null) {
                limits = new Limits(
                        l.path("timeoutSeconds").asInt(Limits.DEFAULTS.timeoutSeconds()),
                        l.path("memoryBytes").asLong(Limits.DEFAULTS.memoryBytes()),
                        l.path("cpus").asDouble(Limits.DEFAULTS.cpus()),
                        l.path("pidsLimit").asInt(Limits.DEFAULTS.pidsLimit()),
                        l.path("networkEnabled").asBoolean(false));
            }

            TaskSpec spec = new TaskSpec(
                    req(n, "taskId"), req(n, "repoUri"), req(n, "baseRef"),
                    n.path("patchPath").asText(null),
                    n.path("image").asText(null),
                    strings(n.get("verifyCommand")),
                    limits,
                    strings(n.get("protectedPaths")));

            return new LoadedTask(spec,
                    base.resolve(n.path("patchPath").asText()),
                    base.resolve(n.path("reportPath").asText()));

        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read task file " + file + ": " + e.getMessage(), e);
        }
    }

    private static String req(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.asText().isBlank()) {
            throw new IllegalArgumentException("task file is missing required field: " + field);
        }
        return v.asText();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(e -> out.add(e.asText()));
        }
        return out;
    }

    /** A spec plus the host paths its producer needs. */
    public record LoadedTask(TaskSpec spec, Path patchFile, Path reportFile) {}
}
