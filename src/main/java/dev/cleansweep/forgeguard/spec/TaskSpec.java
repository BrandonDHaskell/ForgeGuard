package dev.cleansweep.forgeguard.spec;

import java.util.List;

/**
 * A unit of work: where to get the code, what should change it, and how to
 * decide whether the result is correct. Loaded from a YAML task file.
 *
 * <p>strategy names the VerificationStrategy that builds and tests the project,
 * such as {@code maven} or {@code pytest}. It is a name rather than a type so
 * that supporting a new build system never touches this record.
 *
 * <p>baseRef is what the author wrote (a tag, branch, or SHA). The resolved
 * commit SHA is recorded on the run, not here, so that intent and evidence
 * stay distinguishable.
 */
public record TaskSpec(String taskId, String repoUri, String baseRef, String patchPath,
                       String image, List<String> verifyCommand, Limits limits,
                       List<String> protectedPaths, String strategy) {

    /** The strategy used when code builds a spec without naming one. Task files must name it. */
    public static final String DEFAULT_STRATEGY = "maven";

    public TaskSpec(String taskId, String repoUri, String baseRef, String patchPath,
                    String image, List<String> verifyCommand, Limits limits,
                    List<String> protectedPaths) {
        this(taskId, repoUri, baseRef, patchPath, image, verifyCommand, limits, protectedPaths,
                DEFAULT_STRATEGY);
    }

    public TaskSpec {
        if (strategy == null || strategy.isBlank()) throw new IllegalArgumentException("strategy required");
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
