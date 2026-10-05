package dev.cleansweep.forgeguard.spec;

import java.util.List;

/**
 * A unit of work: where to get the code, what should change it, and how to
 * decide whether the result is correct. Loaded from a YAML task file.
 *
 * <p>baseRef is what the author wrote (a tag, branch, or SHA). The resolved
 * commit SHA is recorded on the run, not here, so that intent and evidence
 * stay distinguishable.
 */
public record TaskSpec(String taskId, String repoUri, String baseRef, String patchPath,
                       String image, List<String> verifyCommand, Limits limits,
                       List<String> protectedPaths) {

    public TaskSpec {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId required");
        if (repoUri == null || repoUri.isBlank()) throw new IllegalArgumentException("repoUri required");
        if (baseRef == null || baseRef.isBlank()) throw new IllegalArgumentException("baseRef required");
        if (verifyCommand == null || verifyCommand.isEmpty())
            throw new IllegalArgumentException("verifyCommand required");
        verifyCommand = List.copyOf(verifyCommand);
        protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        limits = limits == null ? Limits.DEFAULTS : limits;
    }
}
