package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.verify.ProtectedPathCheck;
import dev.cleansweep.forgeguard.verify.VerificationStrategies;
import dev.cleansweep.forgeguard.verify.VerificationStrategy;
import dev.cleansweep.forgeguard.workspace.Workspace;
import dev.cleansweep.forgeguard.workspace.WorkspaceProvider;

import java.util.List;
import java.util.UUID;

/**
 * The single ordered path every attempt takes. This class owns the sequence;
 * each collaborator owns one step of it. Nothing here knows what a Maven
 * project is, what Docker is, or what SQL looks like.
 *
 * <p>The sequence is fixed and the ordering is load-bearing:
 *
 * <ol>
 *   <li>persist the run as PENDING, so a crash after this point is recoverable,
 *       or take a run that already exists, such as one a worker has claimed
 *   <li>provision a clean workspace at the resolved base commit
 *   <li>let the producer mutate it, and record what it claims
 *   <li>diff the workspace and reject protected-path edits BEFORE executing
 *   <li>execute the verification in the sandbox
 *   <li>persist the verdict and the captured output
 * </ol>
 *
 * <p>Step 4 precedes step 5 deliberately. A change that edits the tests is
 * rejected without ever being run, so tampering cannot consume a container slot
 * or produce a misleading green log.
 *
 * <p>The class knows nothing about workers or leases. A worker passes a store
 * that only accepts writes while it holds the run, and any write that store
 * refuses ends the attempt by propagating out of execute.
 */
public final class VerificationRun {

    private final WorkspaceProvider workspaces;
    private final Sandbox sandbox;
    private final VerificationStrategies strategies;
    private final RunStore store;
    private final DiffInspector diffs;

    public VerificationRun(WorkspaceProvider workspaces, Sandbox sandbox,
                           VerificationStrategies strategies, RunStore store,
                           DiffInspector diffs) {
        this.workspaces = workspaces;
        this.sandbox = sandbox;
        this.strategies = strategies;
        this.store = store;
        this.diffs = diffs;
    }

    /** A harness that knows one build system; a task naming any other is refused. */
    public VerificationRun(WorkspaceProvider workspaces, Sandbox sandbox,
                           VerificationStrategy strategy, RunStore store,
                           DiffInspector diffs) {
        this(workspaces, sandbox, new VerificationStrategies(List.of(strategy)), store, diffs);
    }

    /**
     * @throws IllegalArgumentException if the task names an unknown strategy. That
     *     is checked before the run is created, so a bad task file leaves no row
     *     behind rather than a HARNESS_ERROR verdict.
     */
    public UUID execute(TaskSpec spec, Producer producer) {
        VerificationStrategy strategy = strategies.get(spec.strategy());
        UUID runId = store.create(spec);
        verify(runId, spec, producer, strategy);
        return runId;
    }

    /**
     * Runs the same sequence against a run that already exists, such as one a
     * worker has claimed, and records everything against runId. Nothing is
     * created.
     *
     * <p>Unlike {@link #execute(TaskSpec, Producer)}, an unknown strategy does
     * not throw: the run already exists, so it gets a HARNESS_ERROR verdict with
     * the reason as its stderr log, rather than staying runnable forever.
     *
     * <p>If the store refuses a write, the attempt is recorded as HARNESS_ERROR.
     * If it refuses that too, as a store fenced to a lost lease does, the
     * exception propagates, with the original failure attached as suppressed.
     */
    public void execute(UUID runId, TaskSpec spec, Producer producer) {
        VerificationStrategy strategy;
        try {
            strategy = strategies.get(spec.strategy());
        } catch (IllegalArgumentException e) {
            reject(runId, Outcome.HARNESS_ERROR, e.getMessage());
            return;
        }
        verify(runId, spec, producer, strategy);
    }

    private void verify(UUID runId, TaskSpec spec, Producer producer, VerificationStrategy strategy) {
        try (Workspace ws = workspaces.provision(spec)) {
            store.recordWorkspace(runId, ws.baseCommit());

            ProducerResult claim;
            try {
                claim = producer.run(ws);
            } catch (ScriptedProducer.PatchRejectedException e) {
                reject(runId, Outcome.PATCH_REJECTED, e.getMessage());
                return;
            }

            DiffInspector.Diff diff = diffs.inspect(ws);
            store.recordProducerResult(runId, claim, diff.headCommit(), diff.sha256());

            var violation = new ProtectedPathCheck(spec.protectedPaths())
                    .firstViolation(diff.touchedPaths());
            if (violation.isPresent()) {
                reject(runId, Outcome.PROTECTED_PATH_MODIFIED,
                        "protected path modified: " + violation.get());
                return;
            }

            var verification = strategy.verify(ws, sandbox, spec);
            // Logs before the verdict: a failed log write lands in the catch
            // below, which would otherwise overwrite a real verdict with
            // HARNESS_ERROR. A verdict is only recorded once its evidence is.
            store.appendLog(runId, "stdout", verification.evidence().stdout());
            store.appendLog(runId, "stderr", verification.evidence().stderr());
            store.recordVerdict(runId, verification.outcome(), verification.evidence());

        } catch (RuntimeException e) {
            try {
                store.recordVerdict(runId, Outcome.HARNESS_ERROR, harnessNote(e.toString()));
            } catch (RuntimeException refused) {
                refused.addSuppressed(e);
                throw refused;
            }
        }
    }

    /** A verdict reached without executing anything. The reason is kept as the stderr log. */
    private void reject(UUID runId, Outcome outcome, String reason) {
        store.appendLog(runId, "stderr", reason);
        store.recordVerdict(runId, outcome, harnessNote(reason));
    }

    private static ExecResult harnessNote(String message) {
        return new ExecResult(-1, "", message, Termination.HARNESS_ERROR, 0L, List.of());
    }
}
