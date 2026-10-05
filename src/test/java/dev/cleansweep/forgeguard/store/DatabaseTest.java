package dev.cleansweep.forgeguard.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Connection failures only; nothing listens on port 1, so no PostgreSQL is needed. */
class DatabaseTest {

    private static final String NOTHING_LISTENING = "jdbc:postgresql://127.0.0.1:1/forgeguard";

    @Test
    void unreachableDatabaseFailsWithAClearMessage() {
        var e = assertThrows(Database.DatabaseException.class,
                () -> Database.connect(NOTHING_LISTENING, "forgeguard", "forgeguard"));

        assertTrue(e.getMessage().startsWith("cannot connect to " + NOTHING_LISTENING), e.getMessage());
        assertTrue(e.getMessage().contains("docker compose up -d postgres"), e.getMessage());
    }

    @Test
    void credentialsInTheUrlAreNotEchoed() {
        var e = assertThrows(Database.DatabaseException.class,
                () -> Database.connect(NOTHING_LISTENING + "?password=hunter2", "forgeguard", "x"));

        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    @Test
    void nonPostgresUrlIsRejectedByName() {
        var e = assertThrows(Database.DatabaseException.class,
                () -> Database.connect("postgres://localhost/forgeguard", "forgeguard", "forgeguard"));

        assertTrue(e.getMessage().contains("postgres://localhost/forgeguard"), e.getMessage());
        assertFalse(e.getMessage().contains("docker compose"), "not a reachability problem: " + e.getMessage());
    }
}
