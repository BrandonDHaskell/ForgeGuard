package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeRuns;
import dev.cleansweep.forgeguard.worker.Worker;
import dev.cleansweep.forgeguard.worker.WorkerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Several workers draining one queue against real PostgreSQL. The claims go
 * through SKIP LOCKED and the writes through each claim's fence. The build
 * itself is faked, so this needs no Git or Maven. It lives in store/ because
 * ScratchSchema does. WorkerCrashTest covers a worker that dies mid-run.
 */
@Tag("integration")
class WorkerJdbcTest {

    private static ScratchSchema scratch;
    private static DataSource db;

    @BeforeAll
    static void connect() {
        scratch = new ScratchSchema();
        scratch.migrate();
        db = scratch.dataSource();
    }

    /** The tests share one schema, and each counts every run in it. */
    @BeforeEach
    void emptyTheQueue() throws SQLException {
        scratch.truncate();
    }

    @AfterAll
    static void dropScratchSchema() throws SQLException {
        if (scratch != null) {
            scratch.close();
        }
    }

    /** Finish Plan T4, done when: two workers empty the queue with no duplicate claims. */
    @Test
    void twoWorkersEmptyTheQueueWithNoDuplicateClaims() throws Exception {
        assertWorkersClaimEveryRunExactlyOnce(2, 12);
    }

    /** Finish Plan T6, done when: 3 workers and 12 runs, each claimed once. */
    @Test
    void threeWorkersAndTwelveRunsEachClaimedOnce() throws Exception {
        assertWorkersClaimEveryRunExactlyOnce(3, 12);
    }

    /**
     * Queues the runs, then drains them with that many workers at once. Each
     * worker's first run waits until every worker is inside a run, so all of
     * them provably hold different runs at the same moment.
     *
     * <p>Then it checks that every run was claimed exactly once, by exactly one
     * worker, and finished on its first attempt.
     */
    private static void assertWorkersClaimEveryRunExactlyOnce(int workers, int runs) throws Exception {
        JdbcRunStore store = new JdbcRunStore(db);
        TaskSpecLoader loader = new TaskSpecLoader();
        Path taskFile = Path.of("tasks", "correct.yaml").toAbsolutePath();
        TaskSpec spec = loader.load(taskFile).spec();
        Set<UUID> queued = new HashSet<>();
        for (int i = 0; i < runs; i++) {
            queued.add(store.enqueue(spec, taskFile));
        }

        CyclicBarrier allHoldARun = new CyclicBarrier(workers);
        PrintWriter log = new PrintWriter(new StringWriter(), true);
        Map<String, List<Claim>> byWorker = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            Map<String, Future<List<Claim>>> drains = new LinkedHashMap<>();
            for (int w = 1; w <= workers; w++) {
                Worker worker = worker("w" + w, store, loader, firstWaitsFor(allHoldARun), log);
                drains.put("w" + w, pool.submit(worker::drain));
            }
            for (var d : drains.entrySet()) {
                byWorker.put(d.getKey(), d.getValue().get(60, TimeUnit.SECONDS));
            }
        }

        List<UUID> claimed = new ArrayList<>();
        byWorker.values().forEach(claims -> claims.forEach(c -> claimed.add(c.runId())));
        assertEquals(runs, claimed.size(), "a run was claimed twice: " + claimed);
        assertEquals(queued, new HashSet<>(claimed));

        assertEquals(runs, count("SELECT count(*) FROM runs WHERE state = 'DONE' AND outcome = 'PASS' AND attempt = 0"),
                "every run finished on its first attempt: none was reclaimed");
        for (var w : byWorker.entrySet()) {
            assertFalse(w.getValue().isEmpty(), w.getKey() + " claimed nothing");
            assertEquals(w.getValue().size(), count("SELECT count(*) FROM runs WHERE claimed_by = '" + w.getKey() + "'"),
                    "the rows " + w.getKey() + " holds are exactly the ones it was handed");
        }
    }

    private static Worker worker(String id, RunStore store, TaskSpecLoader loader, Producer producer,
                                 PrintWriter log) {
        WorkerConfig config = new WorkerConfig(id, Duration.ofMinutes(10), Duration.ofMillis(10), 300);
        return new Worker(config, store, loader, FakeRuns.with(CannedSandbox.passing()), task -> producer, log);
    }

    /** Each worker's first run waits until every other worker is inside a run too. */
    private static Producer firstWaitsFor(CyclicBarrier barrier) {
        AtomicBoolean first = new AtomicBoolean(true);
        return ws -> {
            if (first.getAndSet(false)) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the other worker never held a run", e);
                }
            }
            return new ProducerResult(true, "{\"success\":true}");
        };
    }

    private static int count(String sql) throws SQLException {
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
