package dev.cleansweep.forgeguard.store;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The migrations against a live PostgreSQL, both from nothing and on top of a
 * database that M1 already wrote to.
 *
 * <p>Each test migrates its own throwaway schema, so the developer's public
 * schema and its runs are never touched. Flyway gets its own non-pooled
 * connection source: it changes search_path while migrating, and that must not
 * leak into a pooled connection.
 */
@Tag("integration")
class MigrationTest {

    private final String schema = "fg_migration_"
            + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());

    private Flyway flyway(String target) {
        var cfg = Flyway.configure()
                .dataSource(Database.url(), Database.user(), Database.password())
                .schemas(schema)
                .locations(Database.MIGRATIONS);
        if (target != null) {
            cfg.target(target);
        }
        return cfg.load();
    }

    /** A plain connection with search_path set to this test's schema. */
    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(Database.url(), Database.user(), Database.password());
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + schema);
        }
        return c;
    }

    @AfterEach
    void dropSchema() throws SQLException {
        // flyway.clean() is disabled by default, and dropping is all it would do here.
        try (Connection c = DriverManager.getConnection(Database.url(), Database.user(), Database.password());
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void freshDatabaseAppliesV1ThenV2() throws SQLException {
        MigrateResult first = flyway(null).migrate();

        assertEquals(List.of("1", "2"), appliedVersions());
        assertTrue(first.success);

        Map<String, String> columns = columns();
        assertEquals("integer NOT NULL default 0", columns.get("attempt"));
        assertEquals("text NULL", columns.get("task_file"));
        assertEquals("text NULL", columns.get("sandbox"));

        try (Connection c = connect()) {
            insertTask(c, "fresh");
            SQLException e = assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO runs (run_id, task_id, attempt) VALUES (?, 'fresh', -1)", UUID.randomUUID()));
            assertEquals("23514", e.getSQLState(), "expected a CHECK violation: " + e.getMessage());
        }

        assertEquals(0, flyway(null).migrate().migrationsExecuted, "a second migrate must be a no-op");
    }

    @Test
    void m1DatabaseUpgradesInPlace() throws SQLException {
        flyway("1").migrate();
        assertEquals(List.of("1"), appliedVersions());

        UUID done = UUID.randomUUID();
        UUID stuck = UUID.randomUUID();
        try (Connection c = connect()) {
            insertTask(c, "m1-task");
            // Shaped like the rows M1's JdbcRunStore wrote: one finished, one
            // abandoned mid-run (a submit killed before its verdict).
            exec(c, """
                    INSERT INTO runs (run_id, task_id, state, verify_command, base_commit,
                                      producer_reported_success, outcome, exit_code,
                                      termination_reason, duration_ms)
                    VALUES (?, 'm1-task', 'DONE', 'mvn -B -o verify', 'abc', true,
                            'TEST_FAIL', 1, 'EXITED', 41830)
                    """, done);
            exec(c, """
                    INSERT INTO runs (run_id, task_id, state, verify_command)
                    VALUES (?, 'm1-task', 'PENDING', 'mvn -B -o verify')
                    """, stuck);
        }

        assertEquals(1, flyway(null).migrate().migrationsExecuted);
        assertEquals(List.of("1", "2"), appliedVersions());

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT state, outcome, exit_code, attempt, task_file, sandbox
                     FROM runs WHERE run_id = ?
                     """)) {
            ps.setObject(1, done);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("DONE", rs.getString("state"));
                assertEquals("TEST_FAIL", rs.getString("outcome"));
                assertEquals(1, rs.getInt("exit_code"));
                assertEquals(0, rs.getInt("attempt"));
                assertNull(rs.getString("task_file"));
                assertEquals("local", rs.getString("sandbox"));
            }

            // Left as it was: T2's claim query skips runs with no task_file.
            ps.setObject(1, stuck);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("PENDING", rs.getString("state"));
                assertNull(rs.getString("outcome"));
                assertEquals(0, rs.getInt("attempt"));
                assertNull(rs.getString("task_file"));
                assertEquals("local", rs.getString("sandbox"));
            }
        }
    }

    /**
     * Versioned migrations only. When Flyway creates the schema itself it also
     * records a marker row with no version, which is not a migration.
     */
    private List<String> appliedVersions() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT version, success FROM flyway_schema_history
                     WHERE version IS NOT NULL ORDER BY installed_rank
                     """)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                assertTrue(rs.getBoolean("success"), "migration " + rs.getString("version") + " failed");
                out.add(rs.getString("version"));
            }
            return out;
        }
    }

    /** Each runs column as "type NULL" or "type NOT NULL default x". */
    private Map<String, String> columns() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT column_name, data_type, is_nullable, column_default
                     FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = 'runs'
                     """)) {
            ps.setString(1, schema);
            Map<String, String> out = new TreeMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nullable = "YES".equals(rs.getString("is_nullable")) ? "NULL" : "NOT NULL";
                    String def = rs.getString("column_default");
                    out.put(rs.getString("column_name"),
                            rs.getString("data_type") + " " + nullable + (def == null ? "" : " default " + def));
                }
            }
            return out;
        }
    }

    private static void insertTask(Connection c, String taskId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tasks (task_id, spec_json, spec_sha256) VALUES (?, '{}'::jsonb, 'x')")) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection c, String sql, UUID runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        }
    }
}
