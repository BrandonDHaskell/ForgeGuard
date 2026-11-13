package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.store.Claim;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeWorkspace;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.MavenStrategy;
import dev.cleansweep.forgeguard.verify.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Ordering and gating of the writes VerificationRun makes, with no Git, Maven or database. */
class VerificationRunTest {

    private static final TaskSpec SPEC = new TaskSpec("t", "file:///unused", "v1.0", null, null,
            List.of("mvn", "verify"), Limits.DEFAULTS, List.of("src/test/**", "pom.xml"));

    private static final String TEST_FILE = "src/test/java/demo/IntervalMergerTest.java";
    private static final String MAIN_FILE = "src/main/java/demo/IntervalMerger.java";

    private static DiffInspector touching(String... paths) {
        return ws -> new DiffInspector.Diff(ws.baseCommit(), "0".repeat(64), List.of(paths), "");
    }

    private static UUID execute(RunStore store, Sandbox sandbox, DiffInspector diffs) {
        return new VerificationRun(FakeWorkspace.provider(), sandbox, new MavenStrategy(), store, diffs)
                .execute(SPEC, ws -> new ProducerResult(true, "done"));
    }

    @Test
    void failedLogWriteDoesNotOverwriteARealVerdict() {
        var store = new RecordingStore(true);

        UUID id = execute(store, CannedSandbox.passing(), touching(MAIN_FILE));

        assertEquals(List.of(Outcome.HARNESS_ERROR), store.verdicts);
        assertEquals(Outcome.HARNESS_ERROR, store.find(id).orElseThrow().outcome());
    }

    @Test
    void logsAreWrittenBeforeTheVerdict() {
        var store = new RecordingStore(false);

        execute(store, CannedSandbox.passing(), touching(MAIN_FILE));

        assertEquals(List.of("log:stdout", "log:stderr", "verdict:PASS"), store.calls);
    }

    @Test
    void protectedPathIsRejectedWithoutExecuting() {
        var store = new RecordingStore(false);
        var execs = new AtomicInteger();
        Sandbox counting = (ws, cmd, limits) -> {
            execs.incrementAndGet();
            return CannedSandbox.passing().exec(ws, cmd, limits);
        };

        execute(store, counting, touching(MAIN_FILE, TEST_FILE));

        assertEquals(0, execs.get());
        assertEquals(List.of(Outcome.PROTECTED_PATH_MODIFIED), store.verdicts);
        assertEquals(List.of("protected path modified: " + TEST_FILE), store.stderr);
    }

    @Test
    void unprotectedChangeIsExecuted() {
        var store = new RecordingStore(false);

        execute(store, CannedSandbox.passing(), touching(MAIN_FILE));

        assertEquals(List.of(Outcome.PASS), store.verdicts);
    }

    @Test
    void rejectedPatchKeepsTheReasonAsEvidence() {
        var store = new RecordingStore(false);

        new VerificationRun(FakeWorkspace.provider(), CannedSandbox.passing(), new MavenStrategy(),
                store, touching()).execute(SPEC, ws -> {
                    throw new ScriptedProducer.PatchRejectedException("git apply exited 1: corrupt patch");
                });

        assertEquals(List.of(Outcome.PATCH_REJECTED), store.verdicts);
        assertEquals(List.of("git apply exited 1: corrupt patch"), store.stderr);
    }

    /** Records write order and stderr content; optionally fails every log write. */
    private static final class RecordingStore implements RunStore {
        private final InMemoryRunStore delegate = new InMemoryRunStore();
        private final boolean failLogs;
        final List<String> calls = new ArrayList<>();
        final List<Outcome> verdicts = new ArrayList<>();
        final List<String> stderr = new ArrayList<>();

        RecordingStore(boolean failLogs) {
            this.failLogs = failLogs;
        }

        @Override
        public UUID create(TaskSpec spec) {
            return delegate.create(spec);
        }

        @Override
        public void recordWorkspace(UUID runId, String baseCommit) {
            delegate.recordWorkspace(runId, baseCommit);
        }

        @Override
        public void recordProducerResult(UUID runId, ProducerResult result,
                                         String headCommit, String diffSha256) {
            delegate.recordProducerResult(runId, result, headCommit, diffSha256);
        }

        @Override
        public void recordVerdict(UUID runId, Outcome outcome, ExecResult exec) {
            calls.add("verdict:" + outcome);
            verdicts.add(outcome);
            delegate.recordVerdict(runId, outcome, exec);
        }

        @Override
        public void appendLog(UUID runId, String stream, String content) {
            calls.add("log:" + stream);
            if (failLogs) {
                throw new IllegalStateException("log write failed");
            }
            if (stream.equals("stderr") && !content.isEmpty()) {
                stderr.add(content);
            }
            delegate.appendLog(runId, stream, content);
        }

        @Override
        public Optional<RunRecord> find(UUID runId) {
            return delegate.find(runId);
        }

        @Override
        public List<RunRecord> recent(int limit) {
            return delegate.recent(limit);
        }

        // The work-queue methods (T2, T3). VerificationRun never calls them.

        @Override
        public UUID enqueue(TaskSpec spec, Path taskFile) {
            return delegate.enqueue(spec, taskFile);
        }

        @Override
        public Optional<Claim> claim(String workerId, Duration lease) {
            return delegate.claim(workerId, lease);
        }

        @Override
        public RunStore fencedTo(Claim claim) {
            return delegate.fencedTo(claim);
        }

        @Override
        public String logs(UUID runId, String stream) {
            return delegate.logs(runId, stream);
        }
    }
}
