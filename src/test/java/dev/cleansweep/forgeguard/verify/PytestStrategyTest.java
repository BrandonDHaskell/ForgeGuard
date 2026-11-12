package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Classification is pure, so it is tested against canned sandbox results. */
class PytestStrategyTest {

    private static final List<String> COMMAND = List.of("python", "-m", "pytest", "-q");
    private static final TaskSpec SPEC = new TaskSpec(
            "t", "file:///tmp/x", "v1.0", null, null, COMMAND, Limits.DEFAULTS, List.of());

    private record Canned(ExecResult result) implements Sandbox {
        @Override public ExecResult exec(Workspace w, List<String> c, Limits l) { return result; }
    }

    private record NoWs() implements Workspace {
        @Override public Path root() { return Path.of("/tmp"); }
        @Override public String baseCommit() { return "0".repeat(40); }
        @Override public void close() { }
    }

    private Outcome classify(int exit, Termination t) {
        var sandbox = new Canned(new ExecResult(exit, "", "", t, 10L, COMMAND));
        return new PytestStrategy().verify(new NoWs(), sandbox, SPEC).outcome();
    }

    @Test
    void nameMatchesTaskFiles() {
        assertEquals("pytest", new PytestStrategy().name());
    }

    @Test
    void zeroExitIsPass() {
        assertEquals(Outcome.PASS, classify(0, Termination.EXITED));
    }

    @Test
    void oneIsTestFail() {
        assertEquals(Outcome.TEST_FAIL, classify(1, Termination.EXITED));
    }

    /** 5 is "no tests collected": a suite that checked nothing must not pass. */
    @Test
    void noTestsCollectedIsNeverPass() {
        assertEquals(Outcome.BUILD_FAIL, classify(5, Termination.EXITED));
    }

    /** 2 interrupted or collection error, 3 internal error, 4 usage error, others unknown. */
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 127, -1})
    void otherExitsAreBuildFail(int exit) {
        assertEquals(Outcome.BUILD_FAIL, classify(exit, Termination.EXITED));
    }

    @Test
    void timeoutBeatsExitCode() {
        assertEquals(Outcome.TIMEOUT, classify(0, Termination.TIMEOUT));
    }

    @Test
    void oomIsResourceExceeded() {
        assertEquals(Outcome.RESOURCE_EXCEEDED, classify(137, Termination.OOM_KILLED));
    }

    @Test
    void harnessErrorIsHarnessError() {
        assertEquals(Outcome.HARNESS_ERROR, classify(-1, Termination.HARNESS_ERROR));
    }
}
