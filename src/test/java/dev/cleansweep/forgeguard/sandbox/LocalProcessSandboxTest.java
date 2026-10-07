package dev.cleansweep.forgeguard.sandbox;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    @Test
    void outputLargerThanThePipeBufferDoesNotDeadlock() throws Exception {
        // ~120 KB on each stream, well past the 64 KB pipe buffer.
        String script = "yes 0123456789abcdef | head -n 7000; "
                + "yes 0123456789abcdef | head -n 7000 >&2";
        var r = assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                new LocalProcessSandbox().exec(ws(), List.of("sh", "-c", script), Limits.DEFAULTS));

        assertEquals(Termination.EXITED, r.termination());
        assertEquals(0, r.exitCode());
        assertEquals(7000, r.stdout().lines().count());
        assertEquals(7000, r.stderr().lines().count());
    }

    @Test
    void timeoutKillsForkedChildrenNotJustTheDirectChild() throws Exception {
        var limits = new Limits(1, Limits.DEFAULTS.memoryBytes(), 1.0, 64, false);
        // The shell forks a grandchild that would outlive it, as surefire does under Maven.
        var r = new LocalProcessSandbox().exec(ws(),
                List.of("sh", "-c", "sleep 300 & echo $!; wait"), limits);

        assertEquals(Termination.TIMEOUT, r.termination());
        long pid = Long.parseLong(r.stdout().trim());
        try {
            assertTrue(diesWithin(pid, Duration.ofSeconds(3)), "forked child " + pid + " survived the timeout");
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static boolean diesWithin(long pid, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
