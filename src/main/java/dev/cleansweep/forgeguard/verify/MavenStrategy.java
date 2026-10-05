package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.Workspace;

/**
 * Verifies a Maven project.
 *
 * <p>Maven exits 0 on success and 1 on nearly everything else, so the exit code
 * alone cannot distinguish a compile error from a failing test. The distinction
 * is recovered from the build log, which is why the raw output is persisted
 * rather than summarized: the stored evidence must support the classification.
 */
public final class MavenStrategy implements VerificationStrategy {

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public Verification verify(Workspace workspace, Sandbox sandbox, TaskSpec spec) {
        ExecResult r = sandbox.exec(workspace, spec.verifyCommand(), spec.limits());
        return new Verification(classify(r), r);
    }

    private Outcome classify(ExecResult r) {

        if (r.termination() == Termination.TIMEOUT) {
            return Outcome.TIMEOUT;
        }
        if (r.termination() == Termination.OOM_KILLED) {
            return Outcome.RESOURCE_EXCEEDED;
        }
        if (r.termination() == Termination.HARNESS_ERROR) {
            return Outcome.HARNESS_ERROR;
        }
        if (r.exitCode() == 0) {
            return Outcome.PASS;
        }

        String log = r.stdout();
        if (log.contains("BUILD FAILURE") && log.contains("Failures:")) {
            return Outcome.TEST_FAIL;
        }
        if (log.contains("COMPILATION ERROR") || log.contains("Could not resolve dependencies")) {
            return Outcome.BUILD_FAIL;
        }
        // Maven failed in a way the log did not explain. Default to the
        // conservative reading: something about the build, not the tests.
        return Outcome.BUILD_FAIL;
    }
}
