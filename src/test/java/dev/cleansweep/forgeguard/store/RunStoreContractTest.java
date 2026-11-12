package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.verify.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The behaviour every RunStore must have. Each implementation binds it by
 * subclassing; when both bindings pass, the orchestrator cannot tell them apart.
 *
 * <p>Every run here belongs to a fresh task named {@code contract-*}. A binding
 * must give each test an empty store: claim() takes the oldest runnable run in
 * the whole store, so a store shared with other runs could hand a test a run it
 * never enqueued.
 */
public abstract class RunStoreContractTest {

    protected static final String TASK_PREFIX = "contract-";

    private static final String BASE = "ff904429438a8fe1dabcfd41c4a833e97155d704";
    private static final List<String> COMMAND = List.of("mvn", "-B", "-o", "verify");
    private static final Path TASK_FILE = Path.of("/repo/tasks/contract.yaml");
    private static final Duration HOUR = Duration.ofHours(1);

    protected abstract RunStore store();

    protected static TaskSpec spec() {
        return new TaskSpec(TASK_PREFIX + UUID.randomUUID(), "file:///tmp/forgeguard-fixtures/x",
                "v1.0", "fixtures/patches/x.patch", null, COMMAND, Limits.DEFAULTS, List.of("src/test/**"));
    }

    private static ExecResult exec(int exitCode, Termination termination) {
        return new ExecResult(exitCode, "out", "err", termination, 41830L, COMMAND);
    }

    private RunRecord find(UUID runId) {
        return store().find(runId).orElseThrow(() -> new AssertionError("run not found: " + runId));
    }

    @Test
    void createdRunIsPendingWithNothingObserved() {
        TaskSpec spec = spec();
        RunRecord r = find(store().create(spec));

        assertEquals(spec.taskId(), r.taskId());
        assertEquals(RunState.PENDING, r.state());
        assertEquals("mvn -B -o verify", r.verifyCommand());
        assertNull(r.baseCommit());
        assertNull(r.producerReport());
        assertNull(r.outcome());
        assertNull(r.exitCode());
        assertNull(r.startedAt());
        assertNull(r.finishedAt());
    }

    @Test
    void everyCreateIsANewRunEvenForTheSameTask() {
        TaskSpec spec = spec();
        UUID first = store().create(spec);
        UUID second = store().create(spec);

        assertNotEquals(first, second);
        assertEquals(spec.taskId(), find(first).taskId());
        assertEquals(spec.taskId(), find(second).taskId());
    }

    @Test
    void unknownRunIsEmpty() {
        assertTrue(store().find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void workspaceRecordsBaseCommitAndStartTime() {
        UUID id = store().create(spec());
        store().recordWorkspace(id, BASE);

        RunRecord r = find(id);
        assertEquals(BASE, r.baseCommit());
        assertNotNull(r.startedAt());
        assertEquals(RunState.PENDING, r.state());
    }

    @Test
    void producerReportRoundTripsExactly() {
        String report = "{\"success\":true,\"summary\":\"Changed < to <= so \\\"touching\\\" intervals merge — étude\"}";
        UUID id = store().create(spec());
        store().recordProducerResult(id, new ProducerResult(true, report), "abc123", "d1ff");

        RunRecord r = find(id);
        assertTrue(r.producerReportedSuccess());
        assertEquals(report, r.producerReport());
        assertEquals("abc123", r.headCommit());
        assertEquals("d1ff", r.diffSha256());
    }

    @Test
    void verdictCompletesTheRun() {
        UUID id = store().create(spec());
        store().recordVerdict(id, Outcome.TIMEOUT, exec(-1, Termination.TIMEOUT));

        RunRecord r = find(id);
        assertEquals(RunState.DONE, r.state());
        assertEquals(Outcome.TIMEOUT, r.outcome());
        assertEquals(-1, r.exitCode());
        assertEquals(Termination.TIMEOUT, r.termination());
        assertEquals(41830L, r.durationMs());
        assertNotNull(r.finishedAt());
    }

    /** The orchestrator's order. The claim and the verdict disagree, and both survive. */
    @Test
    void fullRunKeepsClaimAndVerdictApart() {
        UUID id = store().create(spec());
        store().recordWorkspace(id, BASE);
        store().recordProducerResult(id, new ProducerResult(true, "{\"success\":true}"), BASE, "d1ff");
        store().recordVerdict(id, Outcome.TEST_FAIL, exec(1, Termination.EXITED));
        store().appendLog(id, "stdout", "BUILD FAILURE\n");

        RunRecord r = find(id);
        assertTrue(r.producerReportedSuccess());
        assertEquals(Outcome.TEST_FAIL, r.outcome());
        assertEquals(BASE, r.baseCommit());
        assertEquals(1, r.exitCode());
    }

    @Test
    void recentIsNewestFirstAndLimited() {
        UUID a = store().create(spec());
        UUID b = store().create(spec());
        UUID c = store().create(spec());

        assertEquals(List.of(c, b), store().recent(2).stream().map(RunRecord::runId).toList());
        assertEquals(List.of(c, b, a), store().recent(3).stream().map(RunRecord::runId).toList());
    }

    @Test
    void writesToAnUnknownRunFailAndNameIt() {
        UUID ghost = UUID.randomUUID();
        List<Executable> writes = List.of(
                () -> store().recordWorkspace(ghost, BASE),
                () -> store().recordProducerResult(ghost, new ProducerResult(true, "{}"), BASE, "d1ff"),
                () -> store().recordVerdict(ghost, Outcome.PASS, exec(0, Termination.EXITED)),
                () -> store().appendLog(ghost, "stdout", "x"));

        for (Executable write : writes) {
            var e = assertThrows(RuntimeException.class, write);
            assertTrue(e.getMessage().contains(ghost.toString()), e.getMessage());
        }
    }

    // --- the work queue ---------------------------------------------------

    private Claim claim(String worker, Duration lease) {
        return store().claim(worker, lease).orElseThrow(() -> new AssertionError("nothing was claimed"));
    }

    @Test
    void emptyQueueClaimsNothing() {
        assertTrue(store().claim("w1", HOUR).isEmpty());
    }

    @Test
    void claimTakesTheOldestRunAndLeasesIt() {
        Path fileA = Path.of("/repo/tasks/a.yaml");
        Path fileB = Path.of("/repo/tasks/b.yaml");
        UUID a = store().enqueue(spec(), fileA);
        UUID b = store().enqueue(spec(), fileB);
        assertEquals(RunState.PENDING, find(a).state());

        Instant before = Instant.now();
        Claim first = claim("w1", HOUR);
        Claim second = claim("w2", HOUR);
        Instant after = Instant.now();

        assertEquals(List.of(a, b), List.of(first.runId(), second.runId()));
        assertEquals(List.of(fileA, fileB), List.of(first.taskFile(), second.taskFile()));
        assertEquals(List.of(0, 0), List.of(first.attempt(), second.attempt()));
        assertEquals(RunState.CLAIMED, find(a).state());
        assertEquals(RunState.CLAIMED, find(b).state());

        // JdbcRunStore sets the lease by the database clock, so allow for drift from the JVM's.
        Duration skew = Duration.ofMinutes(1);
        assertTrue(first.leaseExpiresAt().isAfter(before.plus(HOUR).minus(skew)), first.toString());
        assertTrue(first.leaseExpiresAt().isBefore(after.plus(HOUR).plus(skew)), first.toString());

        assertTrue(store().claim("w3", HOUR).isEmpty());
    }

    /** A run from create() is being executed by the process that created it, or was left by M1. */
    @Test
    void createdRunsAreNeverClaimed() {
        UUID id = store().create(spec());

        assertTrue(store().claim("w1", Duration.ZERO).isEmpty());
        assertEquals(RunState.PENDING, find(id).state());
    }

    @Test
    void liveLeaseIsNotReclaimed() {
        UUID id = store().enqueue(spec(), TASK_FILE);
        claim("w1", HOUR);

        assertTrue(store().claim("w2", HOUR).isEmpty());
        assertEquals(RunState.CLAIMED, find(id).state());
    }

    @Test
    void finishedRunsAreNeverClaimed() {
        UUID id = store().enqueue(spec(), TASK_FILE);
        claim("w1", Duration.ZERO);
        store().recordVerdict(id, Outcome.PASS, exec(0, Termination.EXITED));

        assertTrue(store().claim("w2", Duration.ZERO).isEmpty());
        assertEquals(RunState.DONE, find(id).state());
        assertEquals(Outcome.PASS, find(id).outcome());
    }

    /** Finish Plan T2, done when: a reclaimed run keeps nothing from the first attempt. */
    @Test
    void reclaimKeepsNothingFromTheFirstAttempt() {
        TaskSpec spec = spec();
        UUID id = store().enqueue(spec, TASK_FILE);
        assertEquals(id, claim("w1", Duration.ZERO).runId());

        // What a worker leaves when it dies after the build but before its verdict:
        // logs are written first, so that is everything except the verdict itself.
        store().recordWorkspace(id, BASE);
        store().recordProducerResult(id, new ProducerResult(true, "{\"success\":true}"), BASE, "d1ff");
        store().appendLog(id, "stdout", "BUILD FAILURE\n");
        store().appendLog(id, "stderr", "warning\n");

        Claim again = claim("w2", HOUR);
        assertEquals(id, again.runId());
        assertEquals(1, again.attempt());
        assertEquals(TASK_FILE, again.taskFile());

        RunRecord r = find(id);
        assertEquals(RunState.CLAIMED, r.state());
        assertNull(r.baseCommit());
        assertNull(r.headCommit());
        assertNull(r.diffSha256());
        assertFalse(r.producerReportedSuccess());
        assertNull(r.producerReport());
        assertNull(r.outcome());
        assertNull(r.exitCode());
        assertNull(r.termination());
        assertNull(r.startedAt());
        assertNull(r.finishedAt());
        assertNull(r.durationMs());
        assertEquals("", store().logs(id, "stdout"));
        assertEquals("", store().logs(id, "stderr"));

        // The inputs are the run's, not the attempt's.
        assertEquals(spec.taskId(), r.taskId());
        assertEquals("mvn -B -o verify", r.verifyCommand());
    }

    @Test
    void everyReclaimIncrementsAttempt() {
        store().enqueue(spec(), TASK_FILE);

        List<Integer> attempts = List.of(
                claim("w1", Duration.ZERO).attempt(),
                claim("w2", Duration.ZERO).attempt(),
                claim("w3", HOUR).attempt());

        assertEquals(List.of(0, 1, 2), attempts);
    }

    @Test
    void concurrentClaimsNeverShareARun() throws Exception {
        Set<UUID> queued = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            queued.add(store().enqueue(spec(), TASK_FILE));
        }

        List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                String worker = "w" + w;
                workers.add(pool.submit(() -> {
                    Optional<Claim> c;
                    while ((c = store().claim(worker, HOUR)).isPresent()) {
                        claimed.add(c.get().runId());
                    }
                }));
            }
            for (Future<?> f : workers) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertEquals(12, claimed.size(), "a run was claimed twice: " + claimed);
        assertEquals(queued, new HashSet<>(claimed));
    }

    @Test
    void claimAndEnqueueRejectBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> store().claim(" ", HOUR));
        assertThrows(IllegalArgumentException.class, () -> store().claim("w1", Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> store().enqueue(spec(), Path.of("tasks/correct.yaml")));
    }

    // --- fenced writes ----------------------------------------------------

    private static void assertEachLosesTheLease(List<Executable> writes) {
        for (Executable write : writes) {
            assertThrows(LeaseLostException.class, write);
        }
    }

    @Test
    void holderWritesThroughItsFence() {
        UUID id = store().enqueue(spec(), TASK_FILE);
        RunStore held = store().fencedTo(claim("w1", HOUR));

        held.recordWorkspace(id, BASE);
        held.recordProducerResult(id, new ProducerResult(true, "{\"success\":true}"), BASE, "d1ff");
        held.appendLog(id, "stdout", "BUILD SUCCESS\n");
        held.recordVerdict(id, Outcome.PASS, exec(0, Termination.EXITED));

        RunRecord r = find(id);
        assertEquals(RunState.DONE, r.state());
        assertEquals(Outcome.PASS, r.outcome());
        assertEquals(BASE, r.baseCommit());
        assertEquals("d1ff", r.diffSha256());
        assertTrue(r.producerReportedSuccess());
        assertEquals("BUILD SUCCESS\n", held.logs(id, "stdout"));
    }

    /** Finish Plan T3, done when: a worker that lost its lease can't overwrite the new result. */
    @Test
    void lostLeaseCannotOverwriteTheNewResult() {
        UUID id = store().enqueue(spec(), TASK_FILE);
        RunStore zombie = store().fencedTo(claim("w1", Duration.ZERO));
        Claim taken = claim("w2", HOUR);
        assertEquals(1, taken.attempt());
        RunStore holder = store().fencedTo(taken);

        // The first worker wakes up after the reclaim and tries every write. None lands.
        assertEachLosesTheLease(List.of(
                () -> zombie.recordWorkspace(id, "zombie-base"),
                () -> zombie.recordProducerResult(id, new ProducerResult(true, "{\"zombie\":true}"), "z", "z"),
                () -> zombie.appendLog(id, "stdout", "zombie log\n"),
                () -> zombie.recordVerdict(id, Outcome.TEST_FAIL, exec(1, Termination.EXITED))));
        RunRecord untouched = find(id);
        assertNull(untouched.baseCommit());
        assertNull(untouched.producerReport());
        assertNull(untouched.outcome());
        assertEquals("", store().logs(id, "stdout"));

        holder.recordWorkspace(id, BASE);
        holder.appendLog(id, "stdout", "BUILD SUCCESS\n");
        holder.recordVerdict(id, Outcome.PASS, exec(0, Termination.EXITED));

        // Nor after the new holder has finished.
        assertEachLosesTheLease(List.of(
                () -> zombie.recordVerdict(id, Outcome.TEST_FAIL, exec(1, Termination.EXITED)),
                () -> zombie.appendLog(id, "stdout", "zombie log\n")));

        RunRecord r = find(id);
        assertEquals(RunState.DONE, r.state());
        assertEquals(Outcome.PASS, r.outcome());
        assertEquals(0, r.exitCode());
        assertEquals(BASE, r.baseCommit());
        assertEquals("BUILD SUCCESS\n", store().logs(id, "stdout"));
    }

    /** A run is taken away by a reclaim, not by the clock. */
    @Test
    void expiredLeaseIsStillHeldUntilReclaimed() {
        UUID id = store().enqueue(spec(), TASK_FILE);
        RunStore slow = store().fencedTo(claim("w1", Duration.ZERO));

        slow.recordVerdict(id, Outcome.PASS, exec(0, Termination.EXITED));

        assertEquals(Outcome.PASS, find(id).outcome());
        assertTrue(store().claim("w2", HOUR).isEmpty(), "a finished run is not reclaimed");
    }

    /** Why the fence includes the attempt: a worker id alone cannot tell its own attempts apart. */
    @Test
    void staleAttemptOfTheSameWorkerIsFenced() {
        UUID id = store().enqueue(spec(), TASK_FILE);
        RunStore stale = store().fencedTo(claim("w1", Duration.ZERO));
        RunStore current = store().fencedTo(claim("w1", HOUR));

        assertThrows(LeaseLostException.class,
                () -> stale.recordVerdict(id, Outcome.TEST_FAIL, exec(1, Termination.EXITED)));
        current.recordVerdict(id, Outcome.PASS, exec(0, Termination.EXITED));

        assertEquals(Outcome.PASS, find(id).outcome());
    }

    @Test
    void fencedViewRejectsOtherRuns() {
        UUID mine = store().enqueue(spec(), TASK_FILE);
        UUID other = store().create(spec());
        Claim c = claim("w1", HOUR);
        assertEquals(mine, c.runId());
        RunStore held = store().fencedTo(c);

        List<Executable> writes = List.of(
                () -> held.recordWorkspace(other, BASE),
                () -> held.recordProducerResult(other, new ProducerResult(true, "{}"), BASE, "d1ff"),
                () -> held.appendLog(other, "stdout", "x"),
                () -> held.recordVerdict(other, Outcome.PASS, exec(0, Termination.EXITED)));
        for (Executable write : writes) {
            assertThrows(IllegalArgumentException.class, write);
        }

        RunRecord r = find(other);
        assertNull(r.baseCommit());
        assertNull(r.producerReport());
        assertNull(r.outcome());
        assertEquals("", store().logs(other, "stdout"));
    }
}
