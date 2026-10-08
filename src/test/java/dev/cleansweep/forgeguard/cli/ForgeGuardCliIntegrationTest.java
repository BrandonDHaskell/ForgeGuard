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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** show and list through the real command line against live PostgreSQL. */
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
}
