package dev.cleansweep.forgeguard.workspace;

import dev.cleansweep.forgeguard.spec.TaskSpec;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Clones the task's repository into a fresh temporary directory and checks out
 * the pinned ref.
 *
 * <p>The ref the task names (a tag, branch, or SHA) is resolved here to a
 * concrete commit SHA, which is what gets recorded on the run. Intent and
 * evidence stay separate: the task says "v1.0", the record says which commit
 * that was on the day it ran.
 */
public final class JGitWorkspaceProvider implements WorkspaceProvider {

    @Override
    public Workspace provision(TaskSpec spec) {
        Path dir;
        try {
            dir = Files.createTempDirectory("fg-ws-");
        } catch (IOException e) {
            throw new WorkspaceException("cannot create workspace directory", e);
        }

        try (Git git = Git.cloneRepository()
                .setURI(spec.repoUri())
                .setDirectory(dir.toFile())
                .call()) {

            git.checkout().setName(spec.baseRef()).call();

            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                throw new WorkspaceException("ref did not resolve: " + spec.baseRef(), null);
            }
            return new TempWorkspace(dir, head.getName());

        } catch (Exception e) {
            deleteTree(dir);
            throw new WorkspaceException(
                    "cannot provision " + spec.repoUri() + " at " + spec.baseRef(), e);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // teardown is best effort
                }
            });
        } catch (IOException ignored) {
            // teardown is best effort
        }
    }

    private record TempWorkspace(Path root, String baseCommit) implements Workspace {
        @Override
        public void close() {
            deleteTree(root);
        }
    }

    public static final class WorkspaceException extends RuntimeException {
        public WorkspaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
