package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The contract against a live PostgreSQL, plus the checks only SQL can see.
 * Connects through the same environment variables as the CLI.
 */
@Tag("integration")
public final class JdbcRunStoreTest extends RunStoreContractTest {

    // One pool for the class. JUnit builds a new instance per test, and a pool per
    // instance holds 8 open connections each, enough to exhaust PostgreSQL's limit.
    private static DataSource db;

    private final JdbcRunStore store = new JdbcRunStore(db);

    @BeforeAll
    static void connect() {
        db = Database.connect();
    }

    @AfterAll
    static void removeContractRunsAndClose() throws Exception {
        if (db == null) {
            return;
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM runs WHERE task_id LIKE '" + TASK_PREFIX + "%'");
            s.executeUpdate("DELETE FROM tasks WHERE task_id LIKE '" + TASK_PREFIX + "%'");
        }
        if (db instanceof AutoCloseable pool) {
            pool.close();
        }
    }

    @Override
    protected RunStore store() {
        return store;
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
