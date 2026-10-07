package dev.cleansweep.forgeguard.workspace;

import dev.cleansweep.forgeguard.spec.TaskSpec;

/** Materializes a clean workspace for one attempt. */
public interface WorkspaceProvider {
    Workspace provision(TaskSpec spec);
}
