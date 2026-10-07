package dev.cleansweep.forgeguard.verify;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Optional;

/**
 * Rejects a change that edits files the task declared off limits, typically the
 * test suite and the build descriptor.
 *
 * <p>Without this, the harness's core claim is defeated by the simplest possible
 * attack: delete the failing test and the suite goes green. The check runs on the
 * diff before anything is executed, so a tampering change never reaches the
 * sandbox.
 */
public final class ProtectedPathCheck {

    private final List<PathMatcher> matchers;

    public ProtectedPathCheck(List<String> globs) {
        this.matchers = globs.stream()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g))
                .toList();
    }

    /** Returns the first touched path that is protected, if any. */
    public Optional<String> firstViolation(List<String> touchedPaths) {
        return touchedPaths.stream()
                .filter(p -> matchers.stream()
                        .anyMatch(m -> m.matches(java.nio.file.Path.of(p))))
                .findFirst();
    }
}
