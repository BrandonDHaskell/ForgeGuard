package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Classification is pure, so it is tested against canned sandbox results. */
class MavenStrategyTest {

    private static final TaskSpec SPEC = new TaskSpec(
            "t", "file:///tmp/x", "v1.0", null, null,
            List.of("mvn", "verify"), Limits.DEFAULTS, List.of());

    private record Canned(ExecResult result) implements Sandbox {
        @Override public ExecResult exec(Workspace w, List<String> c, Limits l) { return result; }
    }

    private record NoWs() implements Workspace {
        @Override public Path root() { return Path.of("/tmp"); }
        @Override public String baseCommit() { return "0".repeat(40); }
        @Override public void close() { }
    }

    private Outcome classify(int exit, String log, Termination t) {
        var sandbox = new Canned(new ExecResult(exit, log, "", t, 10L, List.of("mvn")));
        return new MavenStrategy().verify(new NoWs(), sandbox, SPEC).outcome();
    }

    @Test
    void zeroExitIsPass() {
        assertEquals(Outcome.PASS, classify(0, "BUILD SUCCESS", Termination.EXITED));
    }

    @Test
    void failingTestsAreTestFail() {
        assertEquals(Outcome.TEST_FAIL,
                classify(1, "BUILD FAILURE\nTests run: 7, Failures: 1", Termination.EXITED));
    }

    @Test
    void compilationErrorIsBuildFail() {
        assertEquals(Outcome.BUILD_FAIL, classify(1, "COMPILATION ERROR", Termination.EXITED));
    }

    @Test
    void timeoutBeatsExitCode() {
        assertEquals(Outcome.TIMEOUT, classify(0, "", Termination.TIMEOUT));
    }

    @Test
    void oomIsResourceExceeded() {
        assertEquals(Outcome.RESOURCE_EXCEEDED, classify(137, "", Termination.OOM_KILLED));
    }
}
