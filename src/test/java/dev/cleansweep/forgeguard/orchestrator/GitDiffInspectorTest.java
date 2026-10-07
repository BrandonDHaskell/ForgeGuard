package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Needs the repo produced by fixtures/build-fixtures.sh. */
@Tag("integration")
class GitDiffInspectorTest {

    private static final Path PATCHES = Path.of("fixtures", "patches");
    private static final Path FIXTURE = Path.of(
            System.getenv().getOrDefault("FORGEGUARD_FIXTURE_DIR", "/tmp/forgeguard-fixtures"),
            "intervals-java");

    private final GitDiffInspector inspector = new GitDiffInspector();

    private static Workspace patched(String name) {
        Workspace ws = new JGitWorkspaceProvider().provision(new TaskSpec(
                "t", FIXTURE.toUri().toString(), "v1.0", null, null,
                List.of("mvn", "verify"), Limits.DEFAULTS, List.of()));
        new ScriptedProducer(PATCHES.resolve(name + ".patch"), PATCHES.resolve(name + ".report.json")).run(ws);
        return ws;
    }

    private static void gitConfig(Workspace ws, String key, String value) throws Exception {
        Process p = new ProcessBuilder("git", "config", key, value)
                .directory(ws.root().toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
    }

    @Test
    void overclaimTouchesExactlyTwoFiles() {
        try (Workspace ws = patched("overclaim")) {
            var d = inspector.inspect(ws);

            assertEquals(List.of("src/main/java/demo/Interval.java",
                    "src/main/java/demo/IntervalMerger.java"), d.touchedPaths());
            assertEquals(ws.baseCommit(), d.headCommit());
        }
    }

    @Test
    void sameChangeHashesIdenticallyAcrossWorkspaces() {
        try (Workspace a = patched("overclaim"); Workspace b = patched("overclaim")) {
            assertEquals(inspector.inspect(a).sha256(), inspector.inspect(b).sha256());
        }
    }

    @Test
    void differentChangesHashDifferently() {
        try (Workspace a = patched("overclaim"); Workspace b = patched("correct")) {
            assertNotEquals(inspector.inspect(a).sha256(), inspector.inspect(b).sha256());
        }
    }

    @Test
    void gitConfigDoesNotChangeTheHash() throws Exception {
        try (Workspace plain = patched("overclaim"); Workspace hostile = patched("overclaim")) {
            gitConfig(hostile, "color.ui", "always");
            gitConfig(hostile, "diff.noprefix", "true");
            gitConfig(hostile, "diff.context", "9");
            gitConfig(hostile, "diff.algorithm", "patience");
            gitConfig(hostile, "diff.renames", "copies");

            var expected = inspector.inspect(plain);
            var actual = inspector.inspect(hostile);

            assertEquals(expected.sha256(), actual.sha256());
            assertEquals(expected.unifiedDiff(), actual.unifiedDiff());
        }
    }
}
