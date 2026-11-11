package dev.cleansweep.forgeguard.orchestrator;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeWorkspace;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.verify.VerificationStrategies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The task's strategy name, not the harness wiring, picks how a run is verified. */
class VerificationRunStrategyTest {

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final VerificationRun run = new VerificationRun(FakeWorkspace.provider(),
            CannedSandbox.passing(), VerificationStrategies.builtIn(), store,
            ws -> new DiffInspector.Diff(ws.baseCommit(), "0".repeat(64), List.of(), ""));

    private static TaskSpec spec(String strategy) {
        return new TaskSpec("t", "file:///unused", "v1.0", null, null,
                List.of("mvn", "verify"), Limits.DEFAULTS, List.of(), strategy);
    }

    @Test
    void namedStrategyVerifiesTheRun() {
        UUID id = run.execute(spec("maven"), ws -> new ProducerResult(true, "done"));

        assertEquals(Outcome.PASS, store.find(id).orElseThrow().outcome());
    }

    @Test
    void unknownStrategyIsRefusedBeforeARunExists() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> run.execute(spec("gradle"), ws -> new ProducerResult(true, "done")));

        assertTrue(e.getMessage().contains("unknown strategy: gradle"), e.getMessage());
        assertTrue(store.recent(10).isEmpty());
    }
}
