package dev.cleansweep.forgeguard.support;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunState;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.Outcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;

/**
 * A RunStore held in memory, so the orchestrator can run end to end with no
 * PostgreSQL. RunStoreContractTest holds it to the same behaviour as JdbcRunStore.
 */
public final class InMemoryRunStore implements RunStore {

    /** In creation order; recent() reads it backwards. */
    private final SequencedMap<UUID, Row> runs = new LinkedHashMap<>();

    @Override
    public synchronized UUID create(TaskSpec spec) {
        UUID runId = UUID.randomUUID();
        runs.put(runId, new Row(runId, spec.taskId(), String.join(" ", spec.verifyCommand())));
        return runId;
    }

    @Override
    public synchronized void recordWorkspace(UUID runId, String baseCommit) {
        Row r = row(runId);
        r.baseCommit = baseCommit;
        r.startedAt = Instant.now();
    }

    @Override
    public synchronized void recordProducerResult(UUID runId, ProducerResult result,
                                                  String headCommit, String diffSha256) {
        Row r = row(runId);
        r.producerReportedSuccess = result.reportedSuccess();
        r.producerReport = result.report();
        r.headCommit = headCommit;
        r.diffSha256 = diffSha256;
    }

    @Override
    public synchronized void recordVerdict(UUID runId, Outcome outcome, ExecResult exec) {
        Row r = row(runId);
        r.state = RunState.DONE;
        r.outcome = outcome;
        r.exitCode = exec.exitCode();
        r.termination = exec.termination();
        r.durationMs = exec.durationMs();
        r.finishedAt = Instant.now();
    }

    @Override
    public synchronized void appendLog(UUID runId, String stream, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        row(runId).logs.computeIfAbsent(stream, s -> new ArrayList<>()).add(content);
    }

    @Override
    public synchronized Optional<RunRecord> find(UUID runId) {
        return Optional.ofNullable(runs.get(runId)).map(Row::toRecord);
    }

    @Override
    public synchronized List<RunRecord> recent(int limit) {
        return runs.sequencedValues().reversed().stream().limit(limit).map(Row::toRecord).toList();
    }

    /** Same result as JdbcRunStore.logs: the stream's chunks joined in append order. */
    public synchronized String logs(UUID runId, String stream) {
        Row r = runs.get(runId);
        return r == null ? "" : String.join("", r.logs.getOrDefault(stream, List.of()));
    }

    private Row row(UUID runId) {
        Row r = runs.get(runId);
        if (r == null) {
            throw new IllegalArgumentException("no such run: " + runId);
        }
        return r;
    }

    private static final class Row {
        final UUID runId;
        final String taskId;
        final String verifyCommand;
        final Map<String, List<String>> logs = new HashMap<>();
        RunState state = RunState.PENDING;
        String baseCommit;
        String headCommit;
        String diffSha256;
        boolean producerReportedSuccess;
        String producerReport;
        Integer exitCode;
        Outcome outcome;
        Termination termination;
        Instant startedAt;
        Instant finishedAt;
        Long durationMs;

        Row(UUID runId, String taskId, String verifyCommand) {
            this.runId = runId;
            this.taskId = taskId;
            this.verifyCommand = verifyCommand;
        }

        RunRecord toRecord() {
            return new RunRecord(runId, taskId, state, baseCommit, headCommit, diffSha256,
                    null, verifyCommand, producerReportedSuccess, producerReport,
                    exitCode, outcome, termination, startedAt, finishedAt, durationMs);
        }
    }
}
