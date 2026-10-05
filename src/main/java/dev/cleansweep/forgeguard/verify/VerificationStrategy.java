package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.workspace.Workspace;

/**
 * Knows how to build and test one kind of project, and how to read its exit
 * code. Supporting a new build system means adding an implementation here,
 * never editing the orchestrator.
 *
 * <p>Returns the evidence alongside the verdict so that the Sandbox stays
 * stateless and the orchestrator never has to ask it what it did last.
 */
public interface VerificationStrategy {
    String name();
    Verification verify(Workspace workspace, Sandbox sandbox, TaskSpec spec);
}
