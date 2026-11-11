package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.LocalProcessSandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.verify.VerificationStrategies;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pytest half of the demo: the committed py-* task files, run through the
 * same harness as the Maven ones. Needs the repo from fixtures/build-fixtures.sh
 * and a {@code python} on PATH with pytest installed.
 */
@Tag("integration")
class PytestEndToEndTest {

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final VerificationRun run = new VerificationRun(
            new JGitWorkspaceProvider(), new LocalProcessSandbox(),
            VerificationStrategies.builtIn(), store, new GitDiffInspector());

    /** Runs tasks/py-&lt;name&gt;.yaml as committed, except for an optional shorter timeout. */
    private RunRecord execute(String name, Integer timeoutSeconds) {
        var loaded = new TaskSpecLoader().load(Path.of("tasks", "py-" + name + ".yaml"));
        TaskSpec spec = loaded.spec();
        if (timeoutSeconds != null) {
            Limits l = spec.limits();
            spec = new TaskSpec(spec.taskId(), spec.repoUri(), spec.baseRef(), spec.patchPath(),
                    spec.image(), spec.verifyCommand(),
                    new Limits(timeoutSeconds, l.memoryBytes(), l.cpus(), l.pidsLimit(), l.networkEnabled()),
                    spec.protectedPaths(), spec.strategy());
        }
        var id = run.execute(spec, new ScriptedProducer(loaded.patchFile(), loaded.reportFile()));
        return store.find(id).orElseThrow();
    }

    @Test
    void correctPatchPasses() {
        var r = execute("correct", null);

        assertEquals(Outcome.PASS, r.outcome());
        assertEquals(0, r.exitCode());
    }

    @Test
    void overclaimIsCaughtDespiteTheProducerClaimingSuccess() {
        var r = execute("overclaim", null);

        assertTrue(r.producerReportedSuccess());
        assertEquals(Outcome.TEST_FAIL, r.outcome());
        assertTrue(store.logs(r.runId(), "stdout").contains("test_merges_contained_interval"));
    }

    @Test
    void tamperIsRejectedWithoutRunning() {
        var r = execute("tamper", null);

        assertEquals(Outcome.PROTECTED_PATH_MODIFIED, r.outcome());
        assertTrue(store.logs(r.runId(), "stdout").isEmpty(), "pytest must not have run");
        assertTrue(store.logs(r.runId(), "stderr").contains("tests/test_merger.py"));
    }

    @Test
    void hangTimesOut() {
        var r = execute("hang", 10);

        assertEquals(Outcome.TIMEOUT, r.outcome());
        assertEquals(Termination.TIMEOUT, r.termination());
    }
}
