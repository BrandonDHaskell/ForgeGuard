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
import java.util.List;
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
 * Finish Plan T4, done when: two workers empty the queue with no duplicate
 * claims, against real PostgreSQL. The claims go through SKIP LOCKED and the
 * writes through each claim's fence. The build itself is faked, so this needs
 * no Git or Maven. It lives in store/ because ScratchSchema does.
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

    @AfterAll
    static void dropScratchSchema() throws SQLException {
        if (scratch != null) {
            scratch.close();
        }
    }

    @Test
    void twoWorkersEmptyTheQueueWithNoDuplicateClaims() throws Exception {
        JdbcRunStore store = new JdbcRunStore(db);
        TaskSpecLoader loader = new TaskSpecLoader();
        Path taskFile = Path.of("tasks", "correct.yaml").toAbsolutePath();
        TaskSpec spec = loader.load(taskFile).spec();
        Set<UUID> queued = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            queued.add(store.enqueue(spec, taskFile));
        }

        CyclicBarrier bothHoldARun = new CyclicBarrier(2);
        PrintWriter log = new PrintWriter(new StringWriter(), true);
        Worker w1 = worker("w1", store, loader, firstWaitsFor(bothHoldARun), log);
        Worker w2 = worker("w2", store, loader, firstWaitsFor(bothHoldARun), log);

        List<Claim> byW1;
        List<Claim> byW2;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<Claim>> a = pool.submit(w1::drain);
            Future<List<Claim>> b = pool.submit(w2::drain);
            byW1 = a.get(60, TimeUnit.SECONDS);
            byW2 = b.get(60, TimeUnit.SECONDS);
        }

        List<UUID> claimed = new ArrayList<>();
        byW1.forEach(c -> claimed.add(c.runId()));
        byW2.forEach(c -> claimed.add(c.runId()));
        assertEquals(12, claimed.size(), "a run was claimed twice: " + claimed);
        assertEquals(queued, new HashSet<>(claimed));
        assertFalse(byW1.isEmpty());
        assertFalse(byW2.isEmpty());

        assertEquals(12, count("SELECT count(*) FROM runs WHERE state = 'DONE' AND outcome = 'PASS' AND attempt = 0"));
        assertEquals(byW1.size(), count("SELECT count(*) FROM runs WHERE claimed_by = 'w1'"));
        assertEquals(byW2.size(), count("SELECT count(*) FROM runs WHERE claimed_by = 'w2'"));
    }

    private static Worker worker(String id, RunStore store, TaskSpecLoader loader, Producer producer,
                                 PrintWriter log) {
        WorkerConfig config = new WorkerConfig(id, Duration.ofMinutes(10), Duration.ofMillis(10), 300);
        return new Worker(config, store, loader, FakeRuns.with(CannedSandbox.passing()), task -> producer, log);
    }

    /** Each worker's first run waits until the other worker is inside a run too. */
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
