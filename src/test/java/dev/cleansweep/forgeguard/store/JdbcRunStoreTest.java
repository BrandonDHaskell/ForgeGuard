package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract against a live PostgreSQL, plus the checks only SQL can see.
 *
 * <p>Runs in a scratch schema emptied before every test, as the contract
 * requires: a claim takes the oldest runnable run in the whole table, so in a
 * shared schema it could take a developer's queued run.
 */
@Tag("integration")
public final class JdbcRunStoreTest extends RunStoreContractTest {

    // One pool for the class. JUnit builds a new instance per test, and a pool per
    // instance holds 8 open connections each, enough to exhaust PostgreSQL's limit.
    private static ScratchSchema scratch;
    private static DataSource db;

    private final JdbcRunStore store = new JdbcRunStore(db);

    @BeforeAll
    static void connect() {
        scratch = new ScratchSchema();
        scratch.migrate();
        db = scratch.dataSource();
    }

    @BeforeEach
    void emptyTheStore() throws SQLException {
        scratch.truncate();
    }

    @AfterAll
    static void dropScratchSchema() throws SQLException {
        if (scratch != null) {
            scratch.close();
        }
    }

    @Override
    protected RunStore store() {
        return store;
    }

    // What a reclaim does with each runs column. Together the three sets must be
    // exactly the table's columns, so a new column cannot slip past the decision.

    /** The run's inputs. They describe the task, not an attempt at it. */
    private static final Set<String> KEPT = Set.of("run_id", "task_id", "task_file", "verify_command",
            "network_enabled", "mem_limit_bytes", "cpu_limit", "pids_limit", "timeout_seconds", "created_at");

    /** Rewritten by every claim. */
    private static final Set<String> LEASE = Set.of("state", "attempt", "claimed_by", "claimed_at",
            "lease_expires_at");

    /** One attempt's evidence. A reclaim must not keep any of it. */
    private static final Set<String> CLEARED = Set.of("base_commit", "head_commit", "diff_sha256",
            "image_digest", "sandbox", "producer_reported_success", "producer_report", "exit_code",
            "outcome", "termination_reason", "started_at", "finished_at", "duration_ms");

    @Test
    void everyRunsColumnIsClassified() throws SQLException {
        Set<String> columns = new TreeSet<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT column_name FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = 'runs'
                     """)) {
            ps.setString(1, scratch.name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }

        Set<String> classified = Stream.of(KEPT, LEASE, CLEARED).flatMap(Set::stream)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(classified, columns, "runs gained or lost a column: decide whether a reclaim keeps it, "
                + "then update KEPT, LEASE or CLEARED here and the claim query in JdbcRunStore");
    }

    /**
     * The contract test reaches the columns a store method writes. This one fills
     * every per-attempt column directly, including those no method writes while a
     * run is still CLAIMED, so the check does not depend on today's write order.
     */
    @Test
    void reclaimNullsEveryPerAttemptColumn() throws SQLException {
        UUID id = store.enqueue(spec(), Path.of("/repo/tasks/t.yaml"));
        store.claim("w1", Duration.ZERO).orElseThrow();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE runs SET base_commit = 'b', head_commit = 'h', diff_sha256 = 'd',
                                image_digest = 'sha256:0f', sandbox = 'local',
                                producer_reported_success = true, producer_report = '{}',
                                exit_code = 1, outcome = 'TEST_FAIL', termination_reason = 'EXITED',
                                started_at = now(), finished_at = now(), duration_ms = 5
                WHERE run_id = ?
                """)) {
            ps.setObject(1, id);
            assertEquals(1, ps.executeUpdate());
        }
        store.appendLog(id, "stdout", "first attempt\n");
        Map<String, Object> keptBefore = columns(id, KEPT);

        assertEquals(1, store.claim("w2", Duration.ofMinutes(5)).orElseThrow().attempt());

        Map<String, Object> cleared = columns(id, CLEARED);
        List<String> survived = cleared.entrySet().stream()
                .filter(e -> e.getValue() != null).map(Map.Entry::getKey).sorted().toList();
        assertEquals(List.of(), survived, "kept from the first attempt");
        assertEquals(keptBefore, columns(id, KEPT));
        assertEquals(List.of(0), ints("SELECT count(*) FROM run_logs WHERE run_id = ?", id));
        assertEquals(List.of(300), ints(
                "SELECT extract(epoch FROM lease_expires_at - claimed_at)::int FROM runs WHERE run_id = ?", id));
        assertEquals("w2", columns(id, Set.of("claimed_by")).get("claimed_by"));
    }

    /** The named columns of one run. The names come from the constants above, never from input. */
    private static Map<String, Object> columns(UUID runId, Set<String> names) throws SQLException {
        List<String> ordered = names.stream().sorted().toList();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + String.join(", ", ordered) + " FROM runs WHERE run_id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no such run: " + runId);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (String n : ordered) {
                    out.put(n, rs.getObject(n));
                }
                return out;
            }
        }
    }

    /** LocalProcessSandbox enforces only the timeout, so the row must not claim more. */
    @Test
    void onlyTheEnforcedTimeoutIsRecorded() throws SQLException {
        UUID id = store.create(spec());

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT timeout_seconds, network_enabled, mem_limit_bytes, cpu_limit, pids_limit
                     FROM runs WHERE run_id = ?
                     """)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(Limits.DEFAULTS.timeoutSeconds(), rs.getInt("timeout_seconds"));
                for (String unenforced : List.of("network_enabled", "mem_limit_bytes", "cpu_limit", "pids_limit")) {
                    assertNull(rs.getObject(unenforced), unenforced + " must be NULL, not a claimed limit");
                }
            }
        }
    }

    @Test
    void sameTaskTwiceIsTwoRunsAndOneTaskRow() throws SQLException {
        TaskSpec spec = spec();
        store.create(spec);
        store.create(spec);

        assertEquals(List.of(1), ints("SELECT count(*) FROM tasks WHERE task_id = ?", spec.taskId()));
        assertEquals(List.of(2), ints("SELECT count(*) FROM runs WHERE task_id = ?", spec.taskId()));
    }

    @Test
    void producerReportIsStoredAsValidJson() throws SQLException {
        UUID id = store.create(spec());
        store.recordProducerResult(id,
                new ProducerResult(true, "{\"success\":true,\"files_touched\":[\"a.java\"]}"), "h", "d");

        // The column is TEXT; the cast is the check, since PostgreSQL rejects invalid JSON.
        assertEquals(List.of(1), ints(
                "SELECT jsonb_array_length(producer_report::jsonb -> 'files_touched') FROM runs WHERE run_id = ?", id));
    }

    @Test
    void logsAppendInSequenceAndReadBackInOrder() throws SQLException {
        UUID id = store.create(spec());
        store.appendLog(id, "stdout", "first\n");
        store.appendLog(id, "stdout", "second\n");
        store.appendLog(id, "stderr", "");

        assertEquals(List.of(0, 1), ints("SELECT seq FROM run_logs WHERE run_id = ? ORDER BY seq", id));
        assertEquals("first\nsecond\n", store.logs(id, "stdout"));
        assertEquals("", store.logs(id, "stderr"));
    }

    private static List<Integer> ints(String sql, Object param) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                List<Integer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
                return out;
            }
        }
    }
}
