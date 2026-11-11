package dev.cleansweep.forgeguard.sandbox;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;
import java.util.List;

/**
 * Runs a command against a workspace under enforced limits.
 *
 * <p>LocalProcessSandbox is the implementation in use. Each implementation
 * documents which limits it actually enforces.
 */
public interface Sandbox {
    ExecResult exec(Workspace workspace, List<String> command, Limits limits);
}
