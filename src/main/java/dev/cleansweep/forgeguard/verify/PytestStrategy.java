package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.Workspace;

/**
 * Verifies a Python project with pytest.
 *
 * <p>Unlike Maven, pytest's exit code says what happened, so no log parsing is
 * needed: 0 means every collected test passed and 1 means at least one failed.
 * Everything else is a failure to run the suite: 2 is an interrupted run, which
 * includes import and syntax errors during collection; 3 and 4 are internal and
 * usage errors; 5 means no tests were collected.
 *
 * <p>Exit 5 is the one to watch. A change that deletes or renames every test, or
 * breaks discovery, gets a clean-looking run with nothing checked. It must never
 * count as PASS, so it falls through to BUILD_FAIL with the other codes.
 */
public final class PytestStrategy implements VerificationStrategy {

    private static final int ALL_PASSED = 0;
    private static final int TESTS_FAILED = 1;

    @Override
    public String name() {
        return "pytest";
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
        return switch (r.exitCode()) {
            case ALL_PASSED -> Outcome.PASS;
            case TESTS_FAILED -> Outcome.TEST_FAIL;
            default -> Outcome.BUILD_FAIL;
        };
    }
}
