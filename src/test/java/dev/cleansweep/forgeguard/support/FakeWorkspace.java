package dev.cleansweep.forgeguard.support;

import dev.cleansweep.forgeguard.workspace.Workspace;
import dev.cleansweep.forgeguard.workspace.WorkspaceProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * An empty temporary directory standing in for a checkout, at a fixed commit.
 * No Git is involved; closing it deletes the directory, as a real one would.
 */
public record FakeWorkspace(Path root) implements Workspace {

    /** The fixture's pinned base commit, so records look like real ones. */
    public static final String BASE_COMMIT = "ff904429438a8fe1dabcfd41c4a833e97155d704";

    /** Provisions a fresh directory per attempt, whatever the task says. */
    public static WorkspaceProvider provider() {
        return spec -> {
            try {
                return new FakeWorkspace(Files.createTempDirectory("fg-fake-ws-"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @Override
    public String baseCommit() {
        return BASE_COMMIT;
    }

    @Override
    public void close() {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // teardown is best effort
        }
    }
}
