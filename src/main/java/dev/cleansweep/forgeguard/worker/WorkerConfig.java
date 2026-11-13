package dev.cleansweep.forgeguard.worker;

import dev.cleansweep.forgeguard.spec.Limits;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;

/**
 * A worker's settings, read from the environment.
 *
 * <p>The lease is the time a worker is given to finish a run before another
 * worker may reclaim it. It must outlast the longest build the worker will
 * start, or a slow but healthy run is reclaimed and executed twice. A worker
 * cannot know the timeouts of runs it has not claimed yet, so it is configured
 * with a ceiling, maxTimeoutSeconds: it refuses to start unless the lease
 * exceeds the ceiling plus MARGIN, and it refuses to run any task whose timeout
 * exceeds the ceiling.
 *
 * <p>The constructor checks only that each value is usable. The lease rule is
 * enforced by {@link #fromEnv}, the one place a worker is configured for real,
 * so tests can build a worker with a short lease on purpose.
 */
public record WorkerConfig(String workerId, Duration lease, Duration poll, int maxTimeoutSeconds) {

    public static final String WORKER_ID = "FORGEGUARD_WORKER_ID";
    public static final String LEASE_SECONDS = "FORGEGUARD_LEASE_SECONDS";
    public static final String POLL_SECONDS = "FORGEGUARD_POLL_SECONDS";
    public static final String MAX_TIMEOUT_SECONDS = "FORGEGUARD_MAX_TIMEOUT_SECONDS";

    /**
     * Time an attempt needs beyond its build: cloning the repo, applying the
     * patch, diffing, and writing the verdict and logs.
     */
    public static final Duration MARGIN = Duration.ofSeconds(15);

    static final int DEFAULT_LEASE_SECONDS = 360;
    static final int DEFAULT_POLL_SECONDS = 2;
    static final int DEFAULT_MAX_TIMEOUT_SECONDS = Limits.DEFAULTS.timeoutSeconds();

    public WorkerConfig {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("worker id must not be blank");
        }
        if (lease == null || lease.isNegative()) {
            throw new IllegalArgumentException("lease must not be negative: " + lease);
        }
        if (poll == null || poll.isNegative() || poll.isZero()) {
            throw new IllegalArgumentException("poll interval must be positive: " + poll);
        }
        if (maxTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("max timeout must be positive: " + maxTimeoutSeconds);
        }
    }

    /**
     * Reads the four variables, with defaults for any that are unset or blank.
     *
     * @throws IllegalArgumentException naming the variable, if a value is not a
     *     positive whole number, or if the lease is not longer than the ceiling
     *     plus MARGIN. This is the worker refusing to start.
     */
    public static WorkerConfig fromEnv(Map<String, String> env) {
        String id = env.get(WORKER_ID);
        if (id == null || id.isBlank()) {
            id = defaultWorkerId();
        }
        int lease = seconds(env, LEASE_SECONDS, DEFAULT_LEASE_SECONDS);
        int poll = seconds(env, POLL_SECONDS, DEFAULT_POLL_SECONDS);
        int maxTimeout = seconds(env, MAX_TIMEOUT_SECONDS, DEFAULT_MAX_TIMEOUT_SECONDS);

        long minimum = maxTimeout + MARGIN.toSeconds() + 1;
        if (lease < minimum) {
            throw new IllegalArgumentException(LEASE_SECONDS + "=" + lease + " must be longer than "
                    + MAX_TIMEOUT_SECONDS + "=" + maxTimeout + " plus a " + MARGIN.toSeconds()
                    + "s margin (at least " + minimum + "), or a run could outlive its lease and be "
                    + "executed twice");
        }
        return new WorkerConfig(id, Duration.ofSeconds(lease), Duration.ofSeconds(poll), maxTimeout);
    }

    private static int seconds(Map<String, String> env, String name, int fallback) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number of seconds, got: " + raw);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
        return value;
    }

    /** Unique per process, which the lease fence relies on: two live workers never share an id. */
    static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "localhost";
        }
        return "worker-" + ProcessHandle.current().pid() + "@" + host;
    }
}
