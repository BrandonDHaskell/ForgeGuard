package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.LocalProcessSandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunState;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.MavenStrategy;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The M1 demo as a test: real Git, real Maven, real process, in-memory store.
 * Needs the repo from fixtures/build-fixtures.sh and a warm ~/.m2, since the
 * verify command runs offline.
 */
@Tag("integration")
class VerificationRunEndToEndTest {

    private static final Path PATCHES = Path.of("fixtures", "patches");
    private static final String REPO = Path.of(
            System.getenv().getOrDefault("FORGEGUARD_FIXTURE_DIR", "/tmp/forgeguard-fixtures"),
            "intervals-java").toUri().toString();

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final VerificationRun run = new VerificationRun(
            new JGitWorkspaceProvider(), new LocalProcessSandbox(),
            new MavenStrategy(), store, new GitDiffInspector());

    /** Mirrors tasks/&lt;name&gt;.yaml; the loader is not needed to run the harness. */
    private RunRecord execute(String name, int timeoutSeconds) {
        var limits = new Limits(timeoutSeconds, Limits.DEFAULTS.memoryBytes(),
                Limits.DEFAULTS.cpus(), Limits.DEFAULTS.pidsLimit(), false);
        var spec = new TaskSpec("intervals-" + name, REPO, "v1.0",
                PATCHES.resolve(name + ".patch").toString(), null,
                List.of("mvn", "-B", "-o", "verify"), limits,
                List.of("src/test/**", "pom.xml"));
        var producer = new ScriptedProducer(
                PATCHES.resolve(name + ".patch"), PATCHES.resolve(name + ".report.json"));

        var id = run.execute(spec, producer);
        return store.find(id).orElseThrow();
    }

    @Test
    void correctPatchPasses() {
        var r = execute("correct", 300);

        assertEquals(Outcome.PASS, r.outcome());
        assertEquals(0, r.exitCode());
        assertEquals(RunState.DONE, r.state());
    }

    @Test
    void overclaimIsCaughtDespiteTheProducerClaimingSuccess() {
        var r = execute("overclaim", 300);

        assertTrue(r.producerReportedSuccess());
        assertEquals(Outcome.TEST_FAIL, r.outcome());
        assertEquals(1, r.exitCode());
        assertEquals(Termination.EXITED, r.termination());
        assertNotNull(r.diffSha256());
        assertEquals(r.baseCommit(), r.headCommit());
    }

    @Test
    void overclaimLogNamesTheTestThePatchBroke() {
        var r = execute("overclaim", 300);

        assertTrue(store.logs(r.runId(), "stdout").contains("mergesContainedInterval"));
    }

    /** M1 has no policy check, so deleting the failing test goes green. M2 changes this. */
    @Test
    void tamperPassesBecauseM1HasNoProtectedPathCheck() {
        var r = execute("tamper", 300);

        assertTrue(r.producerReportedSuccess());
        assertEquals(Outcome.PASS, r.outcome());
    }

    @Test
    void hangTimesOut() {
        var r = execute("hang", 20);

        assertTrue(r.producerReportedSuccess());
        assertEquals(Outcome.TIMEOUT, r.outcome());
        assertEquals(Termination.TIMEOUT, r.termination());
    }
}
