package dev.cleansweep.forgeguard.support;

import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;

import java.util.List;

/**
 * Returns a recorded result instead of executing anything, so every verdict
 * path can be reached with no Maven or Docker. The result echoes the command it
 * was given, as a real sandbox records the command it actually ran.
 *
 * <p>The logs are trimmed real Maven output, so MavenStrategy classifies them
 * exactly as it would a live build.
 */
public record CannedSandbox(int exitCode, String stdout, Termination termination) implements Sandbox {

    public static CannedSandbox passing() {
        return new CannedSandbox(0, """
                [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """, Termination.EXITED);
    }

    /** What the overclaim patch produces: one test the producer never looked at fails. */
    public static CannedSandbox failingTests() {
        return new CannedSandbox(1, """
                [ERROR] Failures:\s
                [ERROR]   IntervalMergerTest.mergesContainedInterval:41 expected: <[1, 10]> but was: <[1, 3]>
                [ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
                [INFO] BUILD FAILURE
                """, Termination.EXITED);
    }

    public static CannedSandbox compileError() {
        return new CannedSandbox(1, """
                [ERROR] COMPILATION ERROR :\s
                [ERROR] /work/src/main/java/demo/Interval.java:[12,9] cannot find symbol
                [INFO] BUILD FAILURE
                """, Termination.EXITED);
    }

    public static CannedSandbox timedOut() {
        return new CannedSandbox(-1, "[INFO] Running demo.IntervalMergerTest\n", Termination.TIMEOUT);
    }

    public static CannedSandbox oomKilled() {
        return new CannedSandbox(137, "[INFO] Running demo.IntervalMergerTest\n", Termination.OOM_KILLED);
    }

    @Override
    public ExecResult exec(Workspace workspace, List<String> command, Limits limits) {
        return new ExecResult(exitCode, stdout, "", termination, 1234L, command);
    }
}
