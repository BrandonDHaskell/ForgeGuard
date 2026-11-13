package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.verify.Outcome;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for runs and their logs. Kept as an interface so the
 * Postgres implementation can be swapped for an embedded one later without
 * touching the orchestrator.
 *
 * <p>It is also the work queue. A run is created one of two ways: create() for
 * an attempt this process executes immediately, which no worker ever claims, or
 * enqueue() for a run that waits for a worker to claim() it.
 */
public interface RunStore {
    /** A PENDING run this process is about to execute itself. claim() never returns it. */
    UUID create(TaskSpec spec);

    /**
     * A PENDING run for a worker to claim. taskFile is the YAML the spec was
     * loaded from, which the worker loads again; it must be absolute, since the
     * worker may not share this process's working directory.
     *
     * @throws IllegalArgumentException if taskFile is relative
     */
    UUID enqueue(TaskSpec spec, Path taskFile);

    /**
     * Hands the oldest runnable run to workerId until the lease expires, or
     * returns empty if nothing is runnable. A run is runnable if it was enqueued
     * and is either PENDING or CLAIMED with an expired lease. No two callers are
     * ever handed the same run under the same lease.
     *
     * <p>A claim clears everything a previous attempt recorded, every evidence
     * field and every log, so a reclaimed run keeps nothing from the attempt
     * whose lease ran out. Only a reclaim increments the attempt number.
     *
     * <p>A zero lease is expired as soon as it is granted. That is only useful
     * in tests; a worker must hold a lease longer than its longest timeout.
     *
     * @throws IllegalArgumentException if workerId is blank or lease is negative
     */
    Optional<Claim> claim(String workerId, Duration lease);

    /**
     * This store as seen by the worker holding claim. A worker writes through
     * this view, never through the plain store.
     *
     * <p>Writes to claim's run (recordWorkspace, recordProducerResult,
     * recordVerdict, appendLog) go through only while the claim is current: the
     * run is still held by the same worker, at the same attempt. Once another
     * claim takes the run over, each of those writes throws LeaseLostException
     * and changes nothing, so a worker that lost its lease cannot touch the new
     * attempt's evidence or verdict. An expired lease that nobody has reclaimed
     * is still held: the run is taken away by a reclaim, not by the clock.
     *
     * <p>Writes to any other run throw IllegalArgumentException. Reads, and
     * create, enqueue and claim, behave as on this store.
     */
    RunStore fencedTo(Claim claim);

    void recordWorkspace(UUID runId, String baseCommit);

    void recordProducerResult(UUID runId, ProducerResult result,
                              String headCommit, String diffSha256);

    void recordVerdict(UUID runId, Outcome outcome, ExecResult exec);

    void appendLog(UUID runId, String stream, String content);

    /** The stream's chunks joined in append order, or "" if there are none. */
    String logs(UUID runId, String stream);

    Optional<RunRecord> find(UUID runId);

    List<RunRecord> recent(int limit);

    /**
     * Every run, grouped by state, verdict and producer claim: one entry per
     * group that has at least one run, in no particular order, and an empty
     * list if there are no runs.
     */
    List<RunCount> counts();
}
