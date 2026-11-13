package dev.cleansweep.forgeguard.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.support.FakeRuns;
import dev.cleansweep.forgeguard.worker.Worker;
import dev.cleansweep.forgeguard.worker.WorkerConfig;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A worker that WorkerCrashTest starts in its own JVM and then kills with
 * SIGKILL, partway through a run.
 *
 * <p>It claims one run and gets as far as a real attempt does before the build:
 * it records the workspace and the producer's result.
 *
 * <p>The build then writes some partial output to the run's log, as a build
 * that streams its log would. That gives the test evidence that only a reclaim
 * can remove. The build then blocks: it prints {@link #HOLDING} and waits to be
 * killed. The process never gets to clean up, so the run is left CLAIMED,
 * exactly as a crashed worker leaves it.
 *
 * <p>Arguments: the scratch schema, the worker id, the lease in milliseconds,
 * and the run id it will claim. The lease is short on purpose; WorkerConfig.fromEnv
 * would refuse it, but the record's constructor doesn't apply that rule. It
 * connects through the same FORGEGUARD_* environment variables as the CLI.
 */
final class DoomedWorker {

    /** Printed on stdout once the worker is inside the build, holding its run. */
    static final String HOLDING = "HOLDING";

    /** What this worker's producer reports, so the test can tell its evidence from the survivor's. */
    static final String REPORT = "{\"worker\":\"doomed\"}";

    /** The build output it writes before it is killed. */
    static final String PARTIAL_LOG = "[INFO] Building intervals-java (doomed worker, killed before it finished)\n";

    public static void main(String[] args) {
        String schema = args[0];
        String workerId = args[1];
        Duration lease = Duration.ofMillis(Long.parseLong(args[2]));
        UUID runId = UUID.fromString(args[3]);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(Database.url());
        cfg.setUsername(Database.user());
        cfg.setPassword(Database.password());
        cfg.setSchema(schema);
        cfg.setMaximumPoolSize(2);
        var store = new JdbcRunStore(new HikariDataSource(cfg));   // never closed: the process is killed

        // The store the worker fences to its claim. The build writes through it, as VerificationRun does.
        AtomicReference<RunStore> fenced = new AtomicReference<>();
        Sandbox blocksUntilKilled = (ws, command, limits) -> {
            fenced.get().appendLog(runId, "stdout", PARTIAL_LOG);
            System.out.println(HOLDING);
            System.out.flush();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("this worker was meant to be killed while it held the run");
        };

        Worker worker = new Worker(new WorkerConfig(workerId, lease, Duration.ofMillis(50), 300), store,
                new TaskSpecLoader(),
                fencedToClaim -> {
                    fenced.set(fencedToClaim);
                    return FakeRuns.with(blocksUntilKilled).apply(fencedToClaim);
                },
                task -> ws -> new ProducerResult(true, REPORT), new PrintWriter(System.err, true));
        if (worker.runOnce().isEmpty()) {
            System.err.println("doomed worker: nothing to claim");
            System.exit(3);
        }
        System.err.println("doomed worker: finished a run it was meant to be killed in");
        System.exit(4);
    }

    private DoomedWorker() {
    }
}
