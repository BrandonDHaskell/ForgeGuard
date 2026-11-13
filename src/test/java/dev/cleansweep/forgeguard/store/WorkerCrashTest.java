package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeRuns;
import dev.cleansweep.forgeguard.support.FakeWorkspace;
import dev.cleansweep.forgeguard.worker.Worker;
import dev.cleansweep.forgeguard.worker.WorkerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finish Plan T6, done when: {@code kill -9} on a worker leaves one verdict
 * with {@code attempt = 1}. This is use case 2 against real PostgreSQL.
 *
 * <p>The doomed worker is a real separate process (DoomedWorker), killed with
 * SIGKILL while it is inside the build. It gets no chance to clean up, so
 * recovery depends entirely on the lease: once it expires, the next claim
 * reclaims the run, clears what the dead worker recorded, and starts over.
 *
 * <p>The builds are faked, so this needs no Git or Maven. It lives in store/
 * because ScratchSchema and Database's environment readers do.
 */
@Tag("integration")
class WorkerCrashTest {

    private static final Duration DOOMED_LEASE = Duration.ofSeconds(2);
    private static final String SURVIVOR_REPORT = "{\"worker\":\"survivor\"}";

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
    void killDashNineMidRunLeavesOneVerdictWithAttemptOne(@TempDir Path tmp) throws Exception {
        JdbcRunStore store = new JdbcRunStore(db);
        TaskSpecLoader loader = new TaskSpecLoader();
        Path taskFile = Path.of("tasks", "correct.yaml").toAbsolutePath();
        UUID id = store.enqueue(loader.load(taskFile).spec(), taskFile);

        // 1. A worker in another process claims the run and is killed inside the build.
        Path doomedErr = tmp.resolve("doomed.err");
        Process doomed = startDoomedWorker(id, doomedErr);
        try {
            awaitHolding(doomed, doomedErr);
            doomed.destroyForcibly();   // SIGKILL: no shutdown hook, no finally block, no cleanup
            assertTrue(doomed.waitFor(10, TimeUnit.SECONDS), "the doomed worker did not die");
            assertEquals(137, doomed.exitValue(), "killed by signal 9 (exit 128 + 9)");
        } finally {
            doomed.destroyForcibly();
        }

        // 2. The run is left as the crash left it: claimed, mid-attempt, with no verdict.
        Run dead = run(id);
        assertEquals("CLAIMED", dead.state());
        assertEquals("doomed", dead.claimedBy());
        assertEquals(0, dead.attempt());
        assertEquals(DoomedWorker.REPORT, dead.producerReport(), "the dead worker got as far as the build");
        assertEquals(DoomedWorker.PARTIAL_LOG, store.logs(id, "stdout"), "and left partial build output");
        assertNull(dead.outcome());

        // 3. Once its lease expires, another worker reclaims the run and verifies it from the start.
        Worker survivor = new Worker(new WorkerConfig("survivor", Duration.ofMinutes(10), Duration.ofMillis(10), 300),
                store, loader, FakeRuns.with(CannedSandbox.passing()),
                task -> ws -> new ProducerResult(true, SURVIVOR_REPORT), new PrintWriter(new StringWriter()));
        var reclaimed = awaitClaim(survivor, Duration.ofSeconds(30));
        assertEquals(id, reclaimed.runId());
        assertEquals(1, reclaimed.attempt(), "a reclaim is the second attempt");

        // 4. One authoritative verdict, attempt 1, and nothing kept from the dead worker.
        Run r = run(id);
        assertEquals("DONE", r.state());
        assertEquals("PASS", r.outcome());
        assertEquals(1, r.attempt());
        assertEquals("survivor", r.claimedBy());
        assertEquals(SURVIVOR_REPORT, r.producerReport());
        assertEquals(FakeWorkspace.BASE_COMMIT, r.baseCommit());
        assertEquals(CannedSandbox.passing().stdout(), store.logs(id, "stdout"),
                "the reclaim deleted the dead worker's partial output; only the survivor's build log is left");
        assertEquals(1, count("SELECT count(*) FROM runs"), "the crash created no second run");
        assertTrue(survivor.runOnce().isEmpty(), "nothing is left to claim");
    }

    // --- the doomed worker's process --------------------------------------

    private static Process startDoomedWorker(UUID runId, Path stderr) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Failsafe may hand this JVM a manifest-only booter jar as its class path;
        // surefire.test.class.path is the real list when it is set.
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classpath, DoomedWorker.class.getName(),
                scratch.name, "doomed", String.valueOf(DOOMED_LEASE.toMillis()), runId.toString())
                .redirectError(stderr.toFile())
                .start();
    }

    /** Waits for the doomed worker to say it is inside the build, holding the run. */
    private static void awaitHolding(Process doomed, Path stderr) throws Exception {
        CompletableFuture<Void> holding = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader out = doomed.inputReader()) {
                String line;
                while ((line = out.readLine()) != null) {
                    if (line.equals(DoomedWorker.HOLDING)) {
                        holding.complete(null);
                    }
                }
                holding.completeExceptionally(new AssertionError("the doomed worker exited without holding the run"));
            } catch (IOException e) {
                holding.completeExceptionally(e);
            }
        });
        try {
            holding.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            String err = Files.exists(stderr) ? Files.readString(stderr) : "";
            throw new AssertionError("the doomed worker never held the run; its stderr:\n" + err,
                    e instanceof TimeoutException ? e : e.getCause());
        }
    }

    /** runOnce until the expired lease lets the survivor claim the run. */
    private static Claim awaitClaim(Worker survivor, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<Claim> c;
        while ((c = survivor.runOnce()).isEmpty()) {
            if (System.nanoTime() > deadline) {
                fail("the survivor could not reclaim the run within " + timeout.toSeconds() + "s");
            }
            Thread.sleep(100);
        }
        return c.get();
    }

    // --- reading the row ------------------------------------------------------

    private record Run(String state, String claimedBy, int attempt, String producerReport, String outcome,
                       String baseCommit) {}

    private static Run run(UUID id) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT state::text, claimed_by, attempt, producer_report, outcome, base_commit
                FROM runs WHERE run_id = ?
                """)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no such run: " + id);
                return new Run(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6));
            }
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
