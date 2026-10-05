package dev.cleansweep.forgeguard.workspace;

import java.nio.file.Path;

/** A throwaway checkout. Closing it removes the directory from the host. */
public interface Workspace extends AutoCloseable {
    Path root();

    /** The resolved commit SHA this workspace was checked out at. */
    String baseCommit();

    @Override
    void close();
}
