package dev.cleansweep.forgeguard.cli;

import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.store.Database;
import dev.cleansweep.forgeguard.store.JdbcRunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * submit, watch, show, list and stats through the real command line against
 * live PostgreSQL. Every run belongs to a cli-test-* task and is deleted
 * afterwards, including the run submit queues.
 */
@Tag("integration")
class ForgeGuardCliIntegrationTest {

    private static final String TASK_PREFIX = "cli-test-";

    private static DataSource db;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeAll
    static void connect() {
        db = Database.connect();
    }

    @AfterAll
    static void removeTestRunsAndClose() throws Exception {
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

    private int execute(String... args) {
        var cli = ForgeGuardCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    /** Stores what an overclaim run records: a successful claim, then a failing verdict. */
    private static UUID storedOverclaimRun() {
        var store = new JdbcRunStore(db);
        List<String> command = List.of("mvn", "-B", "-o", "verify");
        UUID id = store.create(new TaskSpec(TASK_PREFIX + UUID.randomUUID(), "file:///tmp/x", "v1.0",
                "fixtures/patches/overclaim.patch", null, command, Limits.DEFAULTS, List.of()));
        store.recordWorkspace(id, "ff904429438a8fe1dabcfd41c4a833e97155d704");
        store.recordProducerResult(id, new ProducerResult(true, "{\"success\":true}"), "ff90442", "d1ff");
        store.recordVerdict(id, Outcome.TEST_FAIL,
                new ExecResult(1, "", "", Termination.EXITED, 41830L, command));
        store.appendLog(id, "stdout", "[ERROR] Tests run: 7, Failures: 1\n[INFO] BUILD FAILURE\n");
        return id;
    }

    @Test
    void showUnknownRunIsOneLineAndNonZero() {
        UUID ghost = UUID.randomUUID();

        assertEquals(1, execute("show", ghost.toString()));
        assertEquals("forgeguard: no such run: " + ghost + System.lineSeparator(), err.toString());
    }

    @Test
    void showOverclaimRunPrintsTheDisagreement() {
        UUID id = storedOverclaimRun();

        assertEquals(0, execute("show", id.toString()));
        String shown = out.toString();
        assertTrue(shown.contains("verdict       TEST_FAIL"), shown);
        assertTrue(shown.contains("The producer claimed success. The harness disagrees."), shown);
        assertTrue(shown.contains("BUILD FAILURE"), "stdout log is printed: " + shown);
        assertEquals("", err.toString());
    }

    @Test
    void listShowsTheNewestRunFirst() {
        UUID id = storedOverclaimRun();

        assertEquals(0, execute("list"));
        List<String> rows = out.toString().lines().toList();
        assertTrue(rows.get(0).startsWith("RUN"), rows.toString());
        assertTrue(rows.get(1).startsWith(id.toString()) && rows.get(1).contains("TEST_FAIL"), rows.toString());
    }

    /** submit only queues: the row is PENDING, nothing ran, and it carries the absolute task file for a worker. */
    @Test
    void submitQueuesTheRunForAWorker(@TempDir Path repo) throws Exception {
        String taskId = TASK_PREFIX + UUID.randomUUID();
        Path task = Files.createDirectories(repo.resolve("tasks")).resolve("queued.yaml");
        Files.writeString(task, """
                taskId: %s
                repoUri: file:///tmp/forgeguard-fixtures/intervals-java
                baseRef: v1.0
                patchPath: fixtures/patches/overclaim.patch
                reportPath: fixtures/patches/overclaim.report.json
                strategy: maven
                verifyCommand: ["mvn", "-B", "-o", "verify"]
                """.formatted(taskId));

        assertEquals(0, execute("submit", task.toString()), err.toString());

        String line = out.toString().strip();
        assertTrue(line.matches("run \\S+  PENDING   \\(queued for a worker\\)"), line);
        UUID id = UUID.fromString(line.split("\\s+")[1]);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT task_id, state::text, task_file, base_commit FROM runs WHERE run_id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(taskId, rs.getString(1));
                assertEquals("PENDING", rs.getString(2));
                assertEquals(task.toAbsolutePath().toString(), rs.getString(3));
                assertNull(rs.getString(4), "nothing has run");
            }
        }
    }

    @Test
    void watchOfAFinishedRunPrintsItsVerdictAndTheDisagreement() {
        UUID id = storedOverclaimRun();

        assertEquals(0, execute("watch", id.toString()));
        assertEquals(List.of(
                "run " + id + "  TEST_FAIL   (producer reported: success)",
                "The producer claimed success. The harness disagrees."), out.toString().lines().toList());
        assertEquals("", err.toString());
    }

    @Test
    void watchTimesOutOnARunWithNoVerdict() {
        var store = new JdbcRunStore(db);
        UUID id = store.create(new TaskSpec(TASK_PREFIX + UUID.randomUUID(), "file:///tmp/x", "v1.0",
                "fixtures/patches/overclaim.patch", null, List.of("mvn", "-B", "-o", "verify"),
                Limits.DEFAULTS, List.of()));

        assertEquals(1, execute("watch", "--timeout", "0", id.toString()));
        assertEquals("run " + id + "  PENDING" + System.lineSeparator(), out.toString());
        assertEquals("forgeguard: run " + id + " has no verdict after 0s" + System.lineSeparator(), err.toString());
    }

    @Test
    void watchUnknownRunIsOneLineAndNonZero() {
        UUID ghost = UUID.randomUUID();

        assertEquals(1, execute("watch", ghost.toString()));
        assertEquals("forgeguard: no such run: " + ghost + System.lineSeparator(), err.toString());
    }

    /** The database is shared with real runs, so this checks the shape, not the exact counts. */
    @Test
    void statsCountsTheStoredRuns() {
        storedOverclaimRun();

        assertEquals(0, execute("stats"));
        List<String> lines = out.toString().lines().toList();
        assertTrue(lines.get(0).matches("VERDICT\\s+RUNS"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.matches("TEST_FAIL\\s+\\d+")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.matches("total\\s+\\d+")), lines.toString());
        assertTrue(lines.getLast().matches("disagreement rate  \\d+\\.\\d%\\s+\\d+ of the \\d+ success claims .*"),
                lines.getLast());
    }
}
