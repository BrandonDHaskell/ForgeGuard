package dev.cleansweep.forgeguard.spec;

/** Resource ceilings enforced on a single sandboxed execution. */
public record Limits(int timeoutSeconds, long memoryBytes, double cpus,
                     int pidsLimit, boolean networkEnabled) {

    public static final Limits DEFAULTS =
        new Limits(300, 2L * 1024 * 1024 * 1024, 2.0, 256, false);

    public Limits {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        if (memoryBytes <= 0) throw new IllegalArgumentException("memoryBytes must be positive");
        if (cpus <= 0) throw new IllegalArgumentException("cpus must be positive");
        if (pidsLimit <= 0) throw new IllegalArgumentException("pidsLimit must be positive");
    }
}
