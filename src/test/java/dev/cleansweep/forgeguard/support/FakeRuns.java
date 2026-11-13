package dev.cleansweep.forgeguard.support;

import dev.cleansweep.forgeguard.orchestrator.DiffInspector;
import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.MavenStrategy;

import java.util.List;
import java.util.function.Function;

/**
 * Builds a VerificationRun around whatever store a worker hands it, with no Git,
 * Maven or process involved: a temp-directory workspace, the given sandbox's
 * recorded result, and a fixed diff. MavenStrategy still does the real
 * classification of the sandbox's log.
 */
public final class FakeRuns {

    /** What git diff would report for a one-file fix, without running git. */
    public static final DiffInspector ONE_FILE_DIFF = ws -> new DiffInspector.Diff(ws.baseCommit(), "d1ff",
            List.of("src/main/java/demo/IntervalMerger.java"), "");

    /** The factory a Worker takes: one VerificationRun per claim, writing through that claim's store. */
    public static Function<RunStore, VerificationRun> with(Sandbox sandbox) {
        return store -> new VerificationRun(FakeWorkspace.provider(), sandbox, new MavenStrategy(), store,
                ONE_FILE_DIFF);
    }

    private FakeRuns() {
    }
}
