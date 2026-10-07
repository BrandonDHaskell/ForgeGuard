package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.verify.Outcome;
import java.time.Instant;
import java.util.UUID;

/**
 * The persisted evidence for one attempt.
 *
 * <p>producerReportedSuccess and outcome are kept as separate fields on
 * purpose: their disagreement is the thing this system exists to surface.
 */
public record RunRecord(UUID runId, String taskId, RunState state,
                        String baseCommit, String headCommit, String diffSha256,
                        String imageDigest, String verifyCommand,
                        boolean producerReportedSuccess, String producerReport,
                        Integer exitCode, Outcome outcome, Termination termination,
                        Instant startedAt, Instant finishedAt, Long durationMs) {}
