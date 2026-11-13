package dev.cleansweep.forgeguard.support;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.store.Claim;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunState;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.Outcome;

import java.nio.file.Path;
import java.time.Duration;
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

    /** In creation order; recent() reads it backwards, and claim() takes the oldest first. */
    private final SequencedMap<UUID, Row> runs = new LinkedHashMap<>();

    @Override
    public synchronized UUID create(TaskSpec spec) {
        return insert(spec, null);
    }

    @Override
    public synchronized UUID enqueue(TaskSpec spec, Path taskFile) {
        if (taskFile == null || !taskFile.isAbsolute()) {
            throw new IllegalArgumentException("task file must be an absolute path: " + taskFile);
        }
        return insert(spec, taskFile);
    }

    private UUID insert(TaskSpec spec, Path taskFile) {
        UUID runId = UUID.randomUUID();
        runs.put(runId, new Row(runId, spec.taskId(), String.join(" ", spec.verifyCommand()), taskFile));
        return runId;
    }

    /** The same rules as JdbcRunStore's claim query, with the JVM clock in place of the database's. */
    @Override
    public synchronized Optional<Claim> claim(String workerId, Duration lease) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("worker id required");
        }
        if (lease == null || lease.isNegative()) {
            throw new IllegalArgumentException("lease must not be negative: " + lease);
        }
        Instant now = Instant.now();
        for (Row r : runs.values()) {
            if (r.taskFile == null) {
                continue;
            }
            boolean expired = r.state == RunState.CLAIMED && !r.leaseExpiresAt.isAfter(now);
            if (r.state != RunState.PENDING && !expired) {
                continue;
            }
            if (expired) {
                r.attempt++;
            }
            r.clearAttempt();
            r.state = RunState.CLAIMED;
            r.claimedBy = workerId;
            r.leaseExpiresAt = now.plus(lease);
            return Optional.of(new Claim(r.runId, r.taskFile, r.attempt, r.leaseExpiresAt));
        }
        return Optional.empty();
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

    @Override
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
        final Path taskFile;
        final Map<String, List<String>> logs = new HashMap<>();
        RunState state = RunState.PENDING;
        int attempt;
        String claimedBy;
        Instant leaseExpiresAt;
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

        Row(UUID runId, String taskId, String verifyCommand, Path taskFile) {
            this.runId = runId;
            this.taskId = taskId;
            this.verifyCommand = verifyCommand;
            this.taskFile = taskFile;
        }

        /** Everything an attempt records. A claim starts from none of it. */
        void clearAttempt() {
            baseCommit = null;
            headCommit = null;
            diffSha256 = null;
            producerReportedSuccess = false;
            producerReport = null;
            exitCode = null;
            outcome = null;
            termination = null;
            startedAt = null;
            finishedAt = null;
            durationMs = null;
            logs.clear();
        }

        RunRecord toRecord() {
            return new RunRecord(runId, taskId, state, baseCommit, headCommit, diffSha256,
                    null, verifyCommand, producerReportedSuccess, producerReport,
                    exitCode, outcome, termination, startedAt, finishedAt, durationMs);
        }
    }
}
