package dev.cleansweep.forgeguard.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskSpecLoaderTest {

    /** A complete task; each test removes or replaces one line. */
    private static final String MINIMAL = """
            taskId: t
            repoUri: file:///tmp/x
            baseRef: v1.0
            patchPath: fixtures/t.patch
            reportPath: fixtures/t.report.json
            verifyCommand: ["mvn", "-B", "verify"]
            """;

    @TempDir
    Path repo;

    private final TaskSpecLoader loader = new TaskSpecLoader();

    /** Writes the task where the loader expects it: {@code <repo>/tasks/}. */
    private Path task(String yaml) throws IOException {
        Path file = Files.createDirectories(repo.resolve("tasks")).resolve("t.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private String rejection(String yaml) throws IOException {
        Path file = task(yaml);
        return assertThrows(IllegalArgumentException.class, () -> loader.load(file)).getMessage();
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "overclaim", "tamper", "hang"})
    void committedTasksLoad(String name) {
        var t = loader.load(Path.of("tasks", name + ".yaml"));

        assertEquals("intervals-" + name, t.spec().taskId());
        assertEquals(List.of("mvn", "-B", "-o", "verify"), t.spec().verifyCommand());
        assertEquals(List.of("src/test/**", "pom.xml"), t.spec().protectedPaths());
        assertTrue(Files.isRegularFile(t.patchFile()), t.patchFile().toString());
        assertTrue(Files.isRegularFile(t.reportFile()), t.reportFile().toString());
    }

    @Test
    void omittedLimitsAreTheDefaults() throws IOException {
        assertEquals(Limits.DEFAULTS, loader.load(task(MINIMAL)).spec().limits());
    }

    @Test
    void partialLimitsDefaultTheRest() throws IOException {
        var limits = loader.load(task(MINIMAL + "limits:\n  timeoutSeconds: 60\n")).spec().limits();

        assertEquals(60, limits.timeoutSeconds());
        assertEquals(Limits.DEFAULTS.memoryBytes(), limits.memoryBytes());
    }

    @Test
    void pathsResolveAgainstTheRepositoryRootNotTheWorkingDirectory() throws IOException {
        var t = loader.load(task(MINIMAL));

        assertEquals(repo.resolve("fixtures/t.patch"), t.patchFile());
        assertEquals(repo.resolve("fixtures/t.report.json"), t.reportFile());
    }

    @Test
    void missingTaskIdIsNamed() throws IOException {
        String msg = rejection(MINIMAL.replace("taskId: t\n", ""));
        assertTrue(msg.contains("missing required field: taskId"), msg);
    }

    @Test
    void emptyTaskIdIsMissingNotTheTextNull() throws IOException {
        String msg = rejection(MINIMAL.replace("taskId: t", "taskId:"));
        assertTrue(msg.contains("missing required field: taskId"), msg);
    }

    @Test
    void missingPatchPathIsNamed() throws IOException {
        String msg = rejection(MINIMAL.replace("patchPath: fixtures/t.patch\n", ""));
        assertTrue(msg.contains("missing required field: patchPath"), msg);
    }

    @Test
    void verifyCommandAsAStringIsRejected() throws IOException {
        String msg = rejection(MINIMAL.replace("[\"mvn\", \"-B\", \"verify\"]", "mvn -B verify"));
        assertTrue(msg.contains("verifyCommand must be a list"), msg);
    }

    @Test
    void protectedPathsAsAStringIsRejectedNotEmptied() throws IOException {
        String msg = rejection(MINIMAL + "protectedPaths: src/test/**\n");
        assertTrue(msg.contains("protectedPaths must be a list"), msg);
    }

    @Test
    void misspelledFieldIsRejectedNotIgnored() throws IOException {
        String msg = rejection(MINIMAL + "protectedPath: [\"src/test/**\"]\n");
        assertTrue(msg.contains("unknown field: protectedPath "), msg);
    }

    @Test
    void malformedLimitIsRejectedNotDefaulted() throws IOException {
        String msg = rejection(MINIMAL + "limits:\n  timeoutSeconds: 5m\n");
        assertTrue(msg.contains("limits.timeoutSeconds must be a whole number"), msg);
    }

    @Test
    void numericLookingRefMustBeQuoted() throws IOException {
        String msg = rejection(MINIMAL.replace("baseRef: v1.0", "baseRef: 1.10"));
        assertTrue(msg.contains("baseRef must be a string"), msg);
    }

    @Test
    void missingFileIsReportedByAbsolutePath() {
        Path missing = repo.resolve("tasks/missing.yaml");
        var e = assertThrows(IllegalArgumentException.class, () -> loader.load(missing));
        assertEquals("task file not found: " + missing, e.getMessage());
    }
}
