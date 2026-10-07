package dev.cleansweep.forgeguard.sandbox;

import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.workspace.Workspace;
import java.util.List;

/**
 * Runs a command against a workspace under enforced limits.
 *
 * <p>Two implementations are expected: a Docker-backed one for real
 * verification, and a local-process one used as a test fixture and as the
 * fallback if container execution is unavailable.
 */
public interface Sandbox {
    ExecResult exec(Workspace workspace, List<String> command, Limits limits);
}
