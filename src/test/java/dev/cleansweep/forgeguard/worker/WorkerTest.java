package dev.cleansweep.forgeguard.worker;

import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.store.Claim;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunState;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeRuns;
import dev.cleansweep.forgeguard.support.FakeWorkspace;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The worker loop against the in-memory store, with the repository's real task
 * files and loader but no Git, Maven or PostgreSQL. WorkerJdbcTest repeats the
 * two-worker case against a real database.
 */
class WorkerTest {

    private static final Path TASKS = Path.of("tasks").toAbsolutePath();
    private static final Path CORRECT = TASKS.resolve("correct.yaml");      // timeoutSeconds: 300
    private static final Path OVERCLAIM = TASKS.resolve("overclaim.yaml");
    private static final Duration HOUR = Duration.ofHours(1);

    private static final Producer CLAIMS_SUCCESS = ws -> new ProducerResult(true, "{\"success\":true}");

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final TaskSpecLoader loader = new TaskSpecLoader();
    private final StringWriter printed = new StringWriter();
    private final PrintWriter log = new PrintWriter(printed, true);

    private static WorkerConfig config(String id, Duration lease, int maxTimeoutSeconds) {
        return new WorkerConfig(id, lease, Duration.ofMillis(10), maxTimeoutSeconds);
    }

    private Worker worker(WorkerConfig config, Function<RunStore, VerificationRun> runs, Producer producer) {
        return new Worker(config, store, loader, runs, task -> producer, log);
    }

    private Worker worker(String id, Sandbox sandbox, Producer producer) {
        return worker(config(id, HOUR, 300), FakeRuns.with(sandbox), producer);
    }

    private UUID enqueue(Path taskFile) {
        return store.enqueue(loader.load(taskFile).spec(), taskFile);
    }

    private RunRecord find(UUID id) {
        return store.find(id).orElseThrow();
    }

    @Test
    void emptyQueueRunsNothing() {
        assertTrue(worker("w1", CannedSandbox.passing(), CLAIMS_SUCCESS).runOnce().isEmpty());
    }

    @Test
    void claimedRunIsVerifiedThroughItsFence() {
        UUID id = enqueue(OVERCLAIM);
        List<RunStore> handedToTheRun = new ArrayList<>();
        Function<RunStore, VerificationRun> runs = fenced -> {
            handedToTheRun.add(fenced);
            return FakeRuns.with(CannedSandbox.failingTests()).apply(fenced);
        };

        Claim c = worker(config("w1", HOUR, 300), runs, CLAIMS_SUCCESS).runOnce().orElseThrow();

        assertEquals(id, c.runId());
        assertNotSame(store, handedToTheRun.getFirst(), "the run must write through the fenced store");
        RunRecord r = find(id);
        assertEquals(RunState.DONE, r.state());
        assertEquals(Outcome.TEST_FAIL, r.outcome());
        assertTrue(r.producerReportedSuccess());
        assertEquals(FakeWorkspace.BASE_COMMIT, r.baseCommit());
        assertTrue(store.logs(id, "stdout").contains("mergesContainedInterval"));
        assertTrue(printed.toString().contains("worker w1: run " + id + " attempt 0 -> TEST_FAIL"), printed.toString());
    }

    /** Finish Plan T4, done when: two workers empty the queue with no duplicate claims. */
    @Test
    void twoWorkersEmptyTheQueueWithNoDuplicateClaims() throws Exception {
        Set<UUID> queued = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            queued.add(enqueue(i % 2 == 0 ? CORRECT : OVERCLAIM));
        }

        // Each worker's first run waits until the other worker is also inside a
        // run, so the two provably hold different runs at the same moment.
        CyclicBarrier bothHoldARun = new CyclicBarrier(2);
        AtomicInteger produced = new AtomicInteger();
        Worker w1 = worker("w1", CannedSandbox.passing(), firstWaitsFor(bothHoldARun, produced));
        Worker w2 = worker("w2", CannedSandbox.passing(), firstWaitsFor(bothHoldARun, produced));

        List<Claim> byW1;
        List<Claim> byW2;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<Claim>> a = pool.submit(w1::drain);
            Future<List<Claim>> b = pool.submit(w2::drain);
            byW1 = a.get(30, TimeUnit.SECONDS);
            byW2 = b.get(30, TimeUnit.SECONDS);
        }

        List<UUID> claimed = new ArrayList<>();
        byW1.forEach(c -> claimed.add(c.runId()));
        byW2.forEach(c -> claimed.add(c.runId()));
        assertEquals(12, claimed.size(), "a run was claimed twice: " + claimed);
        assertEquals(queued, new HashSet<>(claimed));
        assertFalse(byW1.isEmpty());
        assertFalse(byW2.isEmpty());
        assertEquals(12, produced.get());
        for (UUID id : queued) {
            assertEquals(RunState.DONE, find(id).state());
            assertEquals(Outcome.PASS, find(id).outcome());
        }
        assertTrue(store.claim("w3", HOUR).isEmpty());
    }

    /** A producer whose first call waits at the barrier; every call is counted. */
    private static Producer firstWaitsFor(CyclicBarrier barrier, AtomicInteger produced) {
        AtomicBoolean first = new AtomicBoolean(true);
        return ws -> {
            produced.incrementAndGet();
            if (first.getAndSet(false)) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the other worker never held a run", e);
                }
            }
            return new ProducerResult(true, "{\"success\":true}");
        };
    }

    @Test
    void timeoutOverTheCeilingIsClosedNotRun() {
        UUID id = enqueue(CORRECT);
        AtomicInteger executed = new AtomicInteger();
        Sandbox counting = (ws, command, limits) -> {
            executed.incrementAndGet();
            return CannedSandbox.passing().exec(ws, command, limits);
        };
        Worker w = worker(config("w1", HOUR, 60), FakeRuns.with(counting), CLAIMS_SUCCESS);

        assertEquals(id, w.runOnce().orElseThrow().runId());

        RunRecord r = find(id);
        assertEquals(RunState.DONE, r.state());
        assertEquals(Outcome.HARNESS_ERROR, r.outcome());
        assertTrue(store.logs(id, "stderr").contains("exceeds this worker's FORGEGUARD_MAX_TIMEOUT_SECONDS=60"),
                store.logs(id, "stderr"));
        assertEquals(0, executed.get(), "a run over the ceiling must never reach the sandbox");
        assertTrue(w.runOnce().isEmpty());
    }

    @Test
    void unloadableTaskFileIsClosedNotRetried() {
        UUID id = store.enqueue(loader.load(CORRECT).spec(), TASKS.resolve("deleted-since-enqueue.yaml"));
        Worker w = worker("w1", CannedSandbox.passing(), CLAIMS_SUCCESS);

        w.runOnce();

        assertEquals(Outcome.HARNESS_ERROR, find(id).outcome());
        assertTrue(store.logs(id, "stderr").contains("cannot load task file"), store.logs(id, "stderr"));
        assertTrue(w.runOnce().isEmpty(), "a closed run is not claimed again");
    }

    @Test
    void lostLeaseAbandonsTheRunAndCarriesOn() {
        UUID id = enqueue(OVERCLAIM);
        AtomicReference<Claim> stolen = new AtomicReference<>();
        // With a zero lease, another worker may reclaim the run while this one is still in it.
        Producer reclaimedMidRun = ws -> {
            stolen.set(store.claim("thief", HOUR).orElseThrow());
            return new ProducerResult(true, "{\"success\":true}");
        };
        Worker w = worker(config("w1", Duration.ZERO, 300), FakeRuns.with(CannedSandbox.failingTests()),
                reclaimedMidRun);

        Claim mine = assertDoesNotThrow(() -> w.runOnce().orElseThrow());

        assertEquals(id, mine.runId());
        assertEquals(1, stolen.get().attempt());
        RunRecord r = find(id);
        assertEquals(RunState.CLAIMED, r.state());
        assertNull(r.outcome());
        assertNull(r.baseCommit(), "the reclaim cleared it and nothing from w1 landed after");
        assertNull(r.producerReport());
        assertEquals("", store.logs(id, "stdout"));
        assertTrue(printed.toString().contains("lost its lease"), printed.toString());
        assertTrue(w.runOnce().isEmpty(), "the thief holds a live lease");
    }

    @Test
    void runKeepsTakingRunsUntilStopped() throws Exception {
        Worker w = worker("w1", CannedSandbox.passing(), CLAIMS_SUCCESS);
        Thread loop = Thread.ofVirtual().start(() -> {
            try {
                w.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        UUID id = enqueue(CORRECT);   // arrives while the worker is already polling
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (find(id).state() != RunState.DONE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        w.stop();
        loop.join(5000);

        assertEquals(Outcome.PASS, find(id).outcome());
        assertFalse(loop.isAlive(), "run() must return after stop()");
    }
}
