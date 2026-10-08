package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.verify.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The behaviour every RunStore must have. Each implementation binds it by
 * subclassing; when both bindings pass, the orchestrator cannot tell them apart.
 *
 * <p>Every run here belongs to a fresh task named {@code contract-*}, and no test
 * assumes the store starts empty, so a binding may point at a database that
 * already holds real runs.
 */
public abstract class RunStoreContractTest {

    protected static final String TASK_PREFIX = "contract-";

    private static final String BASE = "ff904429438a8fe1dabcfd41c4a833e97155d704";
    private static final List<String> COMMAND = List.of("mvn", "-B", "-o", "verify");

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
}
