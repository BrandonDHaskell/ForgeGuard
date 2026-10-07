package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.verify.Outcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for runs and their logs. Kept as an interface so the
 * Postgres implementation can be swapped for an embedded one later without
 * touching the orchestrator.
 */
public interface RunStore {
    UUID create(TaskSpec spec);

    void recordWorkspace(UUID runId, String baseCommit);

    void recordProducerResult(UUID runId, ProducerResult result,
                              String headCommit, String diffSha256);

    void recordVerdict(UUID runId, Outcome outcome, ExecResult exec);

    void appendLog(UUID runId, String stream, String content);

    Optional<RunRecord> find(UUID runId);

    List<RunRecord> recent(int limit);
}
