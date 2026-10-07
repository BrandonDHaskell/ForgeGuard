package dev.cleansweep.forgeguard.producer;

import dev.cleansweep.forgeguard.workspace.Workspace;

/**
 * Anything that mutates a workspace: a scripted patch applier, a coding agent,
 * or a human. Implementations edit the workspace in place and return their own
 * account of what happened.
 */
public interface Producer {
    ProducerResult run(Workspace workspace);
}
