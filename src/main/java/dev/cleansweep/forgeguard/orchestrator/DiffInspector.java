package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.workspace.Workspace;

import java.util.List;

/**
 * Reads what the producer actually changed, independently of what it said it
 * changed. The producer's own files_touched list is recorded as part of its
 * claim and is never used here.
 */
public interface DiffInspector {

    Diff inspect(Workspace workspace);

    /**
     * @param headCommit   commit after the producer ran, or the base commit if it
     *                     left the tree dirty without committing
     * @param sha256       hash of the normalized unified diff, stable across machines
     * @param touchedPaths repository-relative paths with at least one changed line
     * @param unifiedDiff  the diff itself, persisted as evidence
     */
    record Diff(String headCommit, String sha256, List<String> touchedPaths, String unifiedDiff) {
        public Diff {
            touchedPaths = List.copyOf(touchedPaths);
        }
    }
}
