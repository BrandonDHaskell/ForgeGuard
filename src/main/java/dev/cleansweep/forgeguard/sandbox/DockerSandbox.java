package dev.cleansweep.forgeguard.sandbox;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command in a throwaway container.
 *
 * <p>The docker CLI is invoked rather than a client library so that the argument
 * list stored as evidence is the same object that was executed. A library would
 * mean reconstructing an approximation of the command for the record.
 *
 * <p>The workspace is copied into the container with {@code docker cp} rather
 * than bind-mounted, so a process inside cannot reach host files through the
 * mount, and a crash leaves the host tree untouched.
 */
public final class DockerSandbox implements Sandbox {

    private final String image;
    private final Path dependencyCache;

    public DockerSandbox(String image, Path dependencyCache) {
        this.image = image;
        this.dependencyCache = dependencyCache;
    }

    @Override
    public ExecResult exec(Workspace workspace, List<String> command, Limits limits) {
        String container = "forgeguard-" + System.nanoTime();
        List<String> create = createArgs(container, limits, command);
        long start = System.nanoTime();
        try {
            run(create);
            run(List.of("docker", "cp", workspace.root() + "/.", container + ":/work"));
            if (dependencyCache != null) {
                run(List.of("docker", "cp", dependencyCache + "/.", container + ":/cache"));
            }

            Process p = new ProcessBuilder(List.of("docker", "start", "-a", container))
                    .redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());

            boolean finished = p.waitFor(limits.timeoutSeconds() + 10L, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - start) / 1_000_000;

            if (!finished) {
                run(List.of("docker", "kill", container));
                return new ExecResult(-1, out, err, Termination.TIMEOUT, ms, create);
            }

            int exit = p.exitValue();
            Termination how = classifyTermination(container, exit);
            return new ExecResult(exit, out, err, how, ms, create);

        } catch (IOException e) {
            return new ExecResult(-1, "", e.toString(), Termination.HARNESS_ERROR, 0L, create);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, "", "interrupted", Termination.HARNESS_ERROR, 0L, create);
        } finally {
            quietly(List.of("docker", "rm", "-f", container));
        }
    }

    /**
     * Builds the container with every hardening flag the proposal commits to.
     * This list is the threat model expressed as arguments.
     */
    private List<String> createArgs(String name, Limits limits, List<String> command) {
        List<String> a = new ArrayList<>(List.of(
                "docker", "create",
                "--name", name,
                "--network", limits.networkEnabled() ? "bridge" : "none",
                "--user", "1000:1000",
                "--read-only",
                "--tmpfs", "/work:rw,exec,size=1g",
                "--tmpfs", "/tmp:rw,size=256m",
                "--memory", Long.toString(limits.memoryBytes()),
                "--memory-swap", Long.toString(limits.memoryBytes()),
                "--cpus", Double.toString(limits.cpus()),
                "--pids-limit", Integer.toString(limits.pidsLimit()),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--workdir", "/work",
                "--stop-timeout", Integer.toString(limits.timeoutSeconds()),
                image,
                "timeout", "--signal=KILL", limits.timeoutSeconds() + "s"));
        a.addAll(command);
        return List.copyOf(a);
    }

    /**
     * {@code timeout --signal=KILL} reports 137 when it kills the child, and the
     * kernel OOM killer produces the same code. They are distinguished by asking
     * Docker which one happened, because the verdict differs: TIMEOUT means the
     * work did not finish, RESOURCE_EXCEEDED means it could not.
     */
    private Termination classifyTermination(String container, int exit) throws IOException, InterruptedException {
        if (exit != 137) {
            return Termination.EXITED;
        }
        Process p = new ProcessBuilder(List.of(
                "docker", "inspect", "-f", "{{.State.OOMKilled}}", container)).start();
        String oom = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return "true".equals(oom) ? Termination.OOM_KILLED : Termination.TIMEOUT;
    }

    private void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IOException("command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private void quietly(List<String> cmd) {
        try {
            new ProcessBuilder(cmd).start().waitFor();
        } catch (IOException | InterruptedException ignored) {
            // teardown is best effort
        }
    }
}
