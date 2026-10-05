package dev.cleansweep.forgeguard.sandbox;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalProcessSandboxTest {

    private record TempWs(Path root) implements Workspace {
        @Override public String baseCommit() { return "0".repeat(40); }
        @Override public void close() { }
    }

    private Workspace ws() throws Exception {
        return new TempWs(Files.createTempDirectory("fg-test-"));
    }

    @Test
    void capturesExitCodeAndOutput() throws Exception {
        var r = new LocalProcessSandbox().exec(ws(),
                List.of("sh", "-c", "echo hello; exit 3"), Limits.DEFAULTS);

        assertEquals(3, r.exitCode());
        assertEquals(Termination.EXITED, r.termination());
        assertTrue(r.stdout().contains("hello"));
    }

    @Test
    void killsCommandThatExceedsTimeout() throws Exception {
        var limits = new Limits(1, Limits.DEFAULTS.memoryBytes(), 1.0, 64, false);
        var r = new LocalProcessSandbox().exec(ws(), List.of("sleep", "30"), limits);

        assertEquals(Termination.TIMEOUT, r.termination());
        assertTrue(r.durationMs() < 10_000, "should not wait for the full sleep");
    }

    @Test
    void storesTheCommandItActuallyRan() throws Exception {
        List<String> cmd = List.of("sh", "-c", "true");
        var r = new LocalProcessSandbox().exec(ws(), cmd, Limits.DEFAULTS);
        assertEquals(cmd, r.command());
    }
}
