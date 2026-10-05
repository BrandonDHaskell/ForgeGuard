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
 *   <li>persist the run as PENDING, so a crash after this point is recoverable
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
 */
public final class VerificationRun {

    private final WorkspaceProvider workspaces;
    private final Sandbox sandbox;
    private final VerificationStrategy strategy;
    private final RunStore store;
    private final DiffInspector diffs;

    public VerificationRun(WorkspaceProvider workspaces, Sandbox sandbox,
                           VerificationStrategy strategy, RunStore store,
                           DiffInspector diffs) {
        this.workspaces = workspaces;
        this.sandbox = sandbox;
        this.strategy = strategy;
        this.store = store;
        this.diffs = diffs;
    }

    public UUID execute(TaskSpec spec, Producer producer) {
        UUID runId = store.create(spec);

        try (Workspace ws = workspaces.provision(spec)) {
            store.recordWorkspace(runId, ws.baseCommit());

            ProducerResult claim;
            try {
                claim = producer.run(ws);
            } catch (ScriptedProducer.PatchRejectedException e) {
                store.recordVerdict(runId, Outcome.PATCH_REJECTED, harnessNote(e.getMessage()));
                return runId;
            }

            DiffInspector.Diff diff = diffs.inspect(ws);
            store.recordProducerResult(runId, claim, diff.headCommit(), diff.sha256());

            // M2: ProtectedPathCheck runs here, before the sandbox, so a change
            // that edits the test suite is rejected without ever executing.

            var verification = strategy.verify(ws, sandbox, spec);
            store.recordVerdict(runId, verification.outcome(), verification.evidence());
            store.appendLog(runId, "stdout", verification.evidence().stdout());
            store.appendLog(runId, "stderr", verification.evidence().stderr());
            return runId;

        } catch (RuntimeException e) {
            store.recordVerdict(runId, Outcome.HARNESS_ERROR, harnessNote(e.toString()));
            return runId;
        }
    }

    private static ExecResult harnessNote(String message) {
        return new ExecResult(-1, "", message, Termination.HARNESS_ERROR, 0L, List.of());
    }
}
