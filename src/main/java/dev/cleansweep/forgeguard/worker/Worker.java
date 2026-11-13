package dev.cleansweep.forgeguard.worker;

import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader.LoadedTask;
import dev.cleansweep.forgeguard.store.Claim;
import dev.cleansweep.forgeguard.store.LeaseLostException;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.Outcome;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Takes runs off the queue and verifies them, one at a time.
 *
 * <p>Each run is claimed under a lease and executed through a store fenced to
 * that claim, so if this worker stalls past its lease and another worker
 * reclaims the run, nothing this worker writes afterwards lands. A lost lease
 * is an expected outcome, not a failure: the worker logs it and moves on.
 *
 * <p>There is no graceful shutdown. Stopping a worker mid-run (Ctrl-C, kill,
 * a crash) leaves its run CLAIMED; once the lease expires another worker
 * reclaims it and starts over. A shutdown hook that let the run finish would be
 * worse: Ctrl-C in a terminal also interrupts the build's child process, so the
 * run would record a broken build as its verdict.
 */
public final class Worker {

    private final WorkerConfig config;
    private final RunStore store;
    private final TaskSpecLoader loader;
    private final Function<RunStore, VerificationRun> runs;
    private final Function<LoadedTask, Producer> producers;
    private final PrintWriter log;
    private final CountDownLatch stopped = new CountDownLatch(1);

    /**
     * @param runs builds the verification for one claim, given the store fenced
     *     to it; every write for the run must go through that store
     * @param producers builds the producer for a loaded task
     */
    public Worker(WorkerConfig config, RunStore store, TaskSpecLoader loader,
                  Function<RunStore, VerificationRun> runs, Function<LoadedTask, Producer> producers,
                  PrintWriter log) {
        this.config = config;
        this.store = store;
        this.loader = loader;
        this.runs = runs;
        this.producers = producers;
        this.log = log;
    }

    /** Claims one run and finishes with it. Empty if nothing was runnable. */
    public Optional<Claim> runOnce() {
        Optional<Claim> next = store.claim(config.workerId(), config.lease());
        next.ifPresent(this::handle);
        return next;
    }

    /** Runs until the queue is empty. Returns every claim this worker took, in order. */
    public List<Claim> drain() {
        List<Claim> taken = new ArrayList<>();
        Optional<Claim> c;
        while ((c = runOnce()).isPresent()) {
            taken.add(c.get());
        }
        return taken;
    }

    /** Runs until stop(), waiting the poll interval whenever the queue is empty. */
    public void run() throws InterruptedException {
        while (stopped.getCount() > 0) {
            if (runOnce().isEmpty()) {
                stopped.await(config.poll().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Ends run() before its next claim. A run in progress is finished first. */
    public void stop() {
        stopped.countDown();
    }

    private void handle(Claim claim) {
        RunStore fenced = store.fencedTo(claim);
        try {
            LoadedTask task;
            try {
                task = loader.load(claim.taskFile());
            } catch (IllegalArgumentException e) {
                // Closed rather than abandoned: otherwise every worker would claim it,
                // fail the same way, and leave it to be reclaimed, forever.
                close(fenced, claim, "cannot load task file: " + e.getMessage());
                return;
            }

            int timeout = task.spec().limits().timeoutSeconds();
            if (timeout > config.maxTimeoutSeconds()) {
                close(fenced, claim, "task timeout " + timeout + "s exceeds this worker's "
                        + WorkerConfig.MAX_TIMEOUT_SECONDS + "=" + config.maxTimeoutSeconds()
                        + "; it could outlive the " + config.lease().toSeconds()
                        + "s lease and be executed twice");
                return;
            }

            runs.apply(fenced).execute(claim.runId(), task.spec(), producers.apply(task));
            report(claim, fenced.find(claim.runId()).map(RunRecord::outcome).map(Outcome::name).orElse("?"));
        } catch (LeaseLostException e) {
            // From close(), or out of execute(): VerificationRun records a refused
            // write as HARNESS_ERROR, the fence refuses that too, and it propagates.
            report(claim, "lost its lease; abandoned to the worker that reclaimed it");
        }
    }

    /** Records a verdict without running anything. The note goes to the run's stderr log. */
    private void close(RunStore fenced, Claim claim, String note) {
        fenced.appendLog(claim.runId(), "stderr", note + "\n");
        fenced.recordVerdict(claim.runId(), Outcome.HARNESS_ERROR,
                new ExecResult(-1, "", note, Termination.HARNESS_ERROR, 0L, List.of()));
        report(claim, Outcome.HARNESS_ERROR + ": " + note);
    }

    private void report(Claim claim, String what) {
        log.printf("worker %s: run %s attempt %d -> %s%n", config.workerId(), claim.runId(), claim.attempt(), what);
        log.flush();
    }
}
