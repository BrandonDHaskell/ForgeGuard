package dev.cleansweep.forgeguard.store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * A run handed to one worker until its lease expires.
 *
 * <p>attempt is 0 on a run's first claim and counts reclaims after that, so a
 * claim with attempt above 0 is a re-execution: the previous worker's lease ran
 * out before it recorded a verdict, and everything it wrote has been cleared.
 *
 * @param taskFile the task YAML the run was enqueued from, absolute, for the
 *                 worker to load with TaskSpecLoader
 */
public record Claim(UUID runId, Path taskFile, int attempt, Instant leaseExpiresAt) {}
