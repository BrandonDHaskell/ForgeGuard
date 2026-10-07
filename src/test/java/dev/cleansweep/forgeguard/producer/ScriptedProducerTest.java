package dev.cleansweep.forgeguard.producer;

import dev.cleansweep.forgeguard.producer.ScriptedProducer.PatchRejectedException;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Needs the repo produced by fixtures/build-fixtures.sh. */
@Tag("integration")
class ScriptedProducerTest {

    private static final Path PATCHES = Path.of("fixtures", "patches");
    private static final Path FIXTURE = Path.of(
            System.getenv().getOrDefault("FORGEGUARD_FIXTURE_DIR", "/tmp/forgeguard-fixtures"),
            "intervals-java");

    private static Workspace workspace() {
        return new JGitWorkspaceProvider().provision(new TaskSpec(
                "t", FIXTURE.toUri().toString(), "v1.0", null, null,
                List.of("mvn", "verify"), Limits.DEFAULTS, List.of()));
    }

    private static ScriptedProducer producer(String name) {
        return new ScriptedProducer(PATCHES.resolve(name + ".patch"), PATCHES.resolve(name + ".report.json"));
    }

    @Test
    void correctPatchLeavesTheLooserComparison() throws Exception {
        try (Workspace ws = workspace()) {
            producer("correct").run(ws);

            String src = Files.readString(ws.root().resolve("src/main/java/demo/IntervalMerger.java"));
            assertTrue(src.contains("cur.start() <= last.end()"), src);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "overclaim", "tamper", "hang"})
    void everyFixtureClaimsSuccess(String name) {
        try (Workspace ws = workspace()) {
            ProducerResult r = producer(name).run(ws);

            assertTrue(r.reportedSuccess());
            assertTrue(r.report().contains("\"success\":true"), r.report());
        }
    }

    @Test
    void corruptedPatchIsRejected() throws Exception {
        Path bad = Files.createTempFile("fg-bad-", ".patch");
        try (Workspace ws = workspace()) {
            String good = Files.readString(PATCHES.resolve("correct.patch"));
            Files.writeString(bad, good.replace("-            if (cur.start() < last.end()) {",
                    "-            if (cur.start() < nothing.like.this()) {"));

            assertThrows(PatchRejectedException.class,
                    () -> new ScriptedProducer(bad, PATCHES.resolve("correct.report.json")).run(ws));
        } finally {
            Files.deleteIfExists(bad);
        }
    }
}
