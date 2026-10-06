package dev.cleansweep.forgeguard.spec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reads a task YAML file into a TaskSpec. Fails loudly on anything malformed.
 *
 * <p>Strict on purpose. A misspelled key, or a string where a list belongs, would
 * otherwise load as an empty value and quietly switch a check off: a task with
 * {@code protectedPath:} has no protected paths. Unknown keys and wrongly typed
 * values are errors, never defaults.
 *
 * <p>Task files live in {@code <repo>/tasks/}. patchPath and reportPath are
 * relative to {@code <repo>}, the task file's grandparent, so the result does not
 * depend on the working directory the CLI was started from.
 */
public final class TaskSpecLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private static final Set<String> FIELDS = Set.of("taskId", "repoUri", "baseRef", "patchPath",
            "reportPath", "image", "verifyCommand", "limits", "protectedPaths");
    private static final Set<String> LIMIT_FIELDS = Set.of("timeoutSeconds", "memoryBytes", "cpus",
            "pidsLimit", "networkEnabled");

    private static final Predicate<JsonNode> INT = v -> v.isIntegralNumber() && v.canConvertToInt();
    private static final Predicate<JsonNode> LONG = v -> v.isIntegralNumber() && v.canConvertToLong();

    public LoadedTask load(Path file) {
        try {
            return parse(file, YAML.readTree(Files.readString(file)));
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("task file not found: " + file.toAbsolutePath(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read task file " + file + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("task file " + file + ": " + e.getMessage(), e);
        }
    }

    private static LoadedTask parse(Path file, JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("expected a mapping of task fields");
        }
        rejectUnknown(n, FIELDS, "");

        TaskSpec spec = new TaskSpec(
                req(n, "taskId"), req(n, "repoUri"), req(n, "baseRef"), req(n, "patchPath"),
                optional(n, "image"),
                strings(n, "verifyCommand"),
                limits(n.get("limits")),
                strings(n, "protectedPaths"));

        Path repo = file.toAbsolutePath().getParent().getParent();
        if (repo == null) {
            throw new IllegalArgumentException("task files belong in <repo>/tasks/");
        }
        return new LoadedTask(spec, repo.resolve(spec.patchPath()), repo.resolve(req(n, "reportPath")));
    }

    private static String req(JsonNode n, String field) {
        String v = optional(n, field);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return v;
    }

    /**
     * A bare {@code field:} reads as YAML null, and an unquoted {@code 1.10} as the
     * number 1.1, so both are caught here rather than turned into text.
     */
    private static String optional(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw wrongType(field, "a string (quote it)", v);
        }
        return v.asText();
    }

    /** A list of scalars. Absent is empty; a bare string is an error, not a one-element list. */
    private static List<String> strings(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw wrongType(field, "a list such as [\"a\", \"b\"]", v);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : v) {
            if (!e.isValueNode() || e.isNull()) {
                throw wrongType(field + " entries", "strings", e);
            }
            out.add(e.asText());
        }
        return out;
    }

    /** Omitted fields take their default; present ones must have the right type. */
    private static Limits limits(JsonNode l) {
        if (l == null || l.isNull()) {
            return Limits.DEFAULTS;
        }
        if (!l.isObject()) {
            throw wrongType("limits", "a mapping", l);
        }
        rejectUnknown(l, LIMIT_FIELDS, "limits.");
        Limits d = Limits.DEFAULTS;
        return new Limits(
                limit(l, "timeoutSeconds", d.timeoutSeconds(), "a whole number", INT, JsonNode::intValue),
                limit(l, "memoryBytes", d.memoryBytes(), "a whole number", LONG, JsonNode::longValue),
                limit(l, "cpus", d.cpus(), "a number", JsonNode::isNumber, JsonNode::doubleValue),
                limit(l, "pidsLimit", d.pidsLimit(), "a whole number", INT, JsonNode::intValue),
                limit(l, "networkEnabled", d.networkEnabled(), "true or false",
                        JsonNode::isBoolean, JsonNode::booleanValue));
    }

    private static <T> T limit(JsonNode l, String field, T fallback, String expected,
                               Predicate<JsonNode> accepts, Function<JsonNode, T> read) {
        JsonNode v = l.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!accepts.test(v)) {
            throw wrongType("limits." + field, expected, v);
        }
        return read.apply(v);
    }

    private static void rejectUnknown(JsonNode n, Set<String> known, String prefix) {
        n.fieldNames().forEachRemaining(k -> {
            if (!known.contains(k)) {
                throw new IllegalArgumentException("unknown field: " + prefix + k
                        + " (expected one of " + new TreeSet<>(known) + ")");
            }
        });
    }

    private static IllegalArgumentException wrongType(String field, String expected, JsonNode v) {
        return new IllegalArgumentException(field + " must be " + expected + ", got "
                + v.getNodeType().name().toLowerCase() + " " + v);
    }

    /** A spec plus the host paths its producer needs. */
    public record LoadedTask(TaskSpec spec, Path patchFile, Path reportFile) {}
}
