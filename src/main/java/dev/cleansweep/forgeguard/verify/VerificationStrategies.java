package dev.cleansweep.forgeguard.verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Looks up a VerificationStrategy by the name a task file gives it. Supporting a
 * new build system means adding it to {@link #builtIn()}; the orchestrator only
 * ever asks this class for a name.
 */
public final class VerificationStrategies {

    private final Map<String, VerificationStrategy> byName = new LinkedHashMap<>();

    public VerificationStrategies(List<VerificationStrategy> strategies) {
        for (VerificationStrategy s : strategies) {
            if (byName.putIfAbsent(s.name(), s) != null) {
                throw new IllegalArgumentException("duplicate strategy name: " + s.name());
            }
        }
    }

    /** Every strategy the harness ships with. */
    public static VerificationStrategies builtIn() {
        return new VerificationStrategies(List.of(new MavenStrategy()));
    }

    /** @throws IllegalArgumentException naming the known strategies, if {@code name} is not one */
    public VerificationStrategy get(String name) {
        VerificationStrategy s = byName.get(name);
        if (s == null) {
            throw new IllegalArgumentException("unknown strategy: " + name
                    + " (expected one of " + new TreeSet<>(byName.keySet()) + ")");
        }
        return s;
    }
}
