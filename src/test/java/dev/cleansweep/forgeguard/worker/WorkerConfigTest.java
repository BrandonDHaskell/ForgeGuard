package dev.cleansweep.forgeguard.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static dev.cleansweep.forgeguard.worker.WorkerConfig.LEASE_SECONDS;
import static dev.cleansweep.forgeguard.worker.WorkerConfig.MAX_TIMEOUT_SECONDS;
import static dev.cleansweep.forgeguard.worker.WorkerConfig.POLL_SECONDS;
import static dev.cleansweep.forgeguard.worker.WorkerConfig.WORKER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Where a worker refuses to start. No database: the check runs before connecting. */
class WorkerConfigTest {

    @Test
    void defaultsAreLongEnoughForTheDefaultTimeout() {
        WorkerConfig c = WorkerConfig.fromEnv(Map.of());

        assertEquals(Duration.ofSeconds(360), c.lease());
        assertEquals(Duration.ofSeconds(2), c.poll());
        assertEquals(300, c.maxTimeoutSeconds());
        assertTrue(c.workerId().startsWith("worker-" + ProcessHandle.current().pid() + "@"), c.workerId());
    }

    @Test
    void readsEveryVariable() {
        WorkerConfig c = WorkerConfig.fromEnv(Map.of(
                WORKER_ID, "w7", LEASE_SECONDS, "600", POLL_SECONDS, "5", MAX_TIMEOUT_SECONDS, "500"));

        assertEquals(new WorkerConfig("w7", Duration.ofSeconds(600), Duration.ofSeconds(5), 500), c);
    }

    @Test
    void blankValuesFallBackToDefaults() {
        WorkerConfig c = WorkerConfig.fromEnv(Map.of(WORKER_ID, " ", LEASE_SECONDS, "", POLL_SECONDS, "  "));

        assertEquals(Duration.ofSeconds(360), c.lease());
        assertEquals(Duration.ofSeconds(2), c.poll());
        assertFalse(c.workerId().isBlank());
    }

    static Stream<Arguments> badValues() {
        return Stream.of(LEASE_SECONDS, POLL_SECONDS, MAX_TIMEOUT_SECONDS)
                .flatMap(name -> Stream.of("abc", "0", "-5", "1.5").map(v -> arguments(name, v)));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("badValues")
    void badValueIsRejectedByName(String name, String value) {
        var e = assertThrows(IllegalArgumentException.class, () -> WorkerConfig.fromEnv(Map.of(name, value)));

        assertTrue(e.getMessage().startsWith(name + " must be"), e.getMessage());
    }

    @Test
    void refusesALeaseNotLongerThanTheCeilingPlusTheMargin() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> WorkerConfig.fromEnv(Map.of(LEASE_SECONDS, "60")));

        assertTrue(e.getMessage().contains("FORGEGUARD_LEASE_SECONDS=60 must be longer than "
                + "FORGEGUARD_MAX_TIMEOUT_SECONDS=300 plus a 15s margin (at least 316)"), e.getMessage());
    }

    @Test
    void theLeaseRuleIsStrict() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkerConfig.fromEnv(Map.of(MAX_TIMEOUT_SECONDS, "30", LEASE_SECONDS, "45")));

        assertEquals(Duration.ofSeconds(46),
                WorkerConfig.fromEnv(Map.of(MAX_TIMEOUT_SECONDS, "30", LEASE_SECONDS, "46")).lease());
    }

    /** The Finish Plan's crash demo: 30-second timeouts, a 60-second lease. */
    @Test
    void theDemoSettingsStart() {
        WorkerConfig c = WorkerConfig.fromEnv(Map.of(MAX_TIMEOUT_SECONDS, "30", LEASE_SECONDS, "60"));

        assertEquals(30, c.maxTimeoutSeconds());
        assertEquals(Duration.ofSeconds(60), c.lease());
    }
}
