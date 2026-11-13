package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
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

import static org.junit.jupiter.api.Assertions.*;

/** Ordering of the writes VerificationRun makes, with no Git, Maven or database. */
class VerificationRunTest {

    private static final TaskSpec SPEC = new TaskSpec("t", "file:///unused", "v1.0", null, null,
            List.of("mvn", "verify"), Limits.DEFAULTS, List.of());

    private static final DiffInspector NO_DIFF =
            ws -> new DiffInspector.Diff(ws.baseCommit(), "0".repeat(64), List.of(), "");

    @Test
    void failedLogWriteDoesNotOverwriteARealVerdict() {
        var store = new RecordingStore(true);
        var run = new VerificationRun(FakeWorkspace.provider(), CannedSandbox.passing(),
                new MavenStrategy(), store, NO_DIFF);

        UUID id = run.execute(SPEC, ws -> new ProducerResult(true, "done"));

        assertEquals(List.of(Outcome.HARNESS_ERROR), store.verdicts);
        assertEquals(Outcome.HARNESS_ERROR, store.find(id).orElseThrow().outcome());
    }

    @Test
    void logsAreWrittenBeforeTheVerdict() {
        var store = new RecordingStore(false);
        var run = new VerificationRun(FakeWorkspace.provider(), CannedSandbox.passing(),
                new MavenStrategy(), store, NO_DIFF);

        run.execute(SPEC, ws -> new ProducerResult(true, "done"));

        assertEquals(List.of("log:stdout", "log:stderr", "verdict:PASS"), store.calls);
    }

    /** Records write order; optionally fails every log write. */
    private static final class RecordingStore implements RunStore {
        private final InMemoryRunStore delegate = new InMemoryRunStore();
        private final boolean failLogs;
        final List<String> calls = new ArrayList<>();
        final List<Outcome> verdicts = new ArrayList<>();

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

         @Override
          public UUID enqueue(TaskSpec spec, Path taskFile) {
              return delegate.enqueue(spec, taskFile);
          }

          @Override
          public Optional<Claim> claim(String workerId, Duration lease) {
              return delegate.claim(workerId, lease);
          }

          @Override
          public String logs(UUID runId, String stream) {
              return delegate.logs(runId, stream);
          }
    }
}
