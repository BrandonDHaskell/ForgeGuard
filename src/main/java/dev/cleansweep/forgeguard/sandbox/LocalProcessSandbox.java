package dev.cleansweep.forgeguard.sandbox;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the verification command as a host process in the workspace directory.
 *
 * <p>This is the Milestone 1 sandbox. It enforces a wall-clock timeout and kills
 * the whole process tree, but it does not isolate the filesystem, the network,
 * or memory. The isolation claim in the proposal belongs to DockerSandbox, which
 * replaces this in Milestone 2.
 *
 * <p>It stays in the codebase afterward as the unit-test fixture, so the test
 * suite never has to start a container.
 */
public final class LocalProcessSandbox implements Sandbox {

    @Override
    public ExecResult exec(Workspace workspace, List<String> command, Limits limits) {
        long start = System.nanoTime();
        Process p = null;
        try {
            p = new ProcessBuilder(command)
                    .directory(workspace.root().toFile())
                    .start();

            // Drained on separate threads: a full pipe buffer deadlocks the child
            // before the timeout can fire.
            var outReader = p.inputReader();
            var errReader = p.errorReader();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread t1 = Thread.startVirtualThread(() -> drain(outReader, out));
            Thread t2 = Thread.startVirtualThread(() -> drain(errReader, err));

            boolean finished = p.waitFor(limits.timeoutSeconds(), TimeUnit.SECONDS);
            long ms = (System.nanoTime() - start) / 1_000_000;

            if (!finished) {
                killTree(p);
                t1.join(2000);
                t2.join(2000);
                return new ExecResult(-1, snapshot(out), snapshot(err),
                        Termination.TIMEOUT, ms, command);
            }

            t1.join(5000);
            t2.join(5000);
            return new ExecResult(p.exitValue(), snapshot(out), snapshot(err),
                    Termination.EXITED, ms, command);

        } catch (IOException e) {
            return new ExecResult(-1, "", e.toString(), Termination.HARNESS_ERROR, 0L, command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                killTree(p);
            }
            return new ExecResult(-1, "", "interrupted", Termination.HARNESS_ERROR, 0L, command);
        }
    }

    /** A drain thread may still be appending if a surviving child holds the pipe open. */
    private static String snapshot(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString();
        }
    }

    private static void drain(java.io.BufferedReader r, StringBuilder sink) {
        try {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // stream closed when the process died
        }
    }

    /**
     * Maven forks a JVM for surefire, so destroying only the direct child leaves
     * the test process running and the timeout does nothing.
     */
    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
