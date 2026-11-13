package dev.cleansweep.forgeguard.cli;

import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.store.Claim;
import dev.cleansweep.forgeguard.store.RunCount;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunState;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeRuns;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.verify.VerificationStrategies;
import dev.cleansweep.forgeguard.worker.Worker;
import dev.cleansweep.forgeguard.worker.WorkerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The commands' output, wired with fakes: no Git, Docker, Maven, or PostgreSQL.
 * A run goes the way it does for real: submit queues it, a worker verifies it,
 * and watch and show read the result.
 */
class ForgeGuardCliTest {

    /** The repository's own task file, so the worker can load it again by path, as a real one does. */
    private static final Path OVERCLAIM_TASK = Path.of("tasks", "overclaim.yaml").toAbsolutePath();
    private static final TaskSpec OVERCLAIM = new TaskSpecLoader().load(OVERCLAIM_TASK).spec();
    private static final Duration HOUR = Duration.ofHours(1);
    private static final String NL = System.lineSeparator();

    /** Claims success without touching the workspace, as the recorded overclaim producer does. */
    private static final Producer CLAIMS_SUCCESS =
            ws -> new ProducerResult(true, "{\"success\":true,\"summary\":\"All tests pass.\"}");

    /** watch's sleeper when the run is already finished: waiting at all is a failure. */
    private static final ForgeGuardCli.Watch.Sleeper NEVER =
            d -> fail("watch waited for a run that already had its verdict");

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final StringWriter printed = new StringWriter();
    private final PrintWriter out = new PrintWriter(printed, true);

    private UUID submit() {
        return ForgeGuardCli.Submit.submit(store, VerificationStrategies.builtIn(), OVERCLAIM, OVERCLAIM_TASK, out);
    }

    /** Queues the overclaim task, then lets one worker verify it against the given build result. */
    private UUID submitAndVerify(Sandbox sandbox) {
        UUID id = submit();
        Worker worker = new Worker(new WorkerConfig("w1", HOUR, Duration.ofMillis(10), 300), store,
                new TaskSpecLoader(), FakeRuns.with(sandbox), task -> CLAIMS_SUCCESS,
                new PrintWriter(new StringWriter()));
        assertEquals(id, worker.runOnce().orElseThrow().runId());
        printed.getBuffer().setLength(0);
        return id;
    }

    /** watch on a run that already has its verdict, so it must not wait. */
    private String watch(UUID runId) throws InterruptedException {
        printed.getBuffer().setLength(0);
        assertTrue(ForgeGuardCli.Watch.watch(store, runId, ForgeGuardCli.Watch.POLL, null, NEVER, out));
        return printed.toString();
    }

    private String show(UUID runId) {
        printed.getBuffer().setLength(0);
        ForgeGuardCli.Show.show(store.find(runId).orElseThrow(), store.logs(runId, "stdout"), out);
        return printed.toString();
    }

    // --- submit -------------------------------------------------------------

    @Test
    void submitQueuesTheRunWithoutVerifyingIt() {
        UUID id = ForgeGuardCli.Submit.submit(store, VerificationStrategies.builtIn(), OVERCLAIM,
                Path.of("tasks", "overclaim.yaml"), out);

        assertEquals("run " + id + "  PENDING   (queued for a worker)" + NL, printed.toString());
        RunRecord r = store.find(id).orElseThrow();
        assertEquals(RunState.PENDING, r.state());
        assertNull(r.baseCommit(), "nothing has run yet");
        Claim c = store.claim("w1", HOUR).orElseThrow();
        assertEquals(id, c.runId(), "a worker can claim it");
        assertEquals(OVERCLAIM_TASK, c.taskFile(), "queued with an absolute path, so a worker anywhere can load it");
    }

    @Test
    void submitRefusesAnUnknownStrategyBeforeQueueingAnything() {
        TaskSpec gradle = new TaskSpec(OVERCLAIM.taskId(), OVERCLAIM.repoUri(), OVERCLAIM.baseRef(),
                OVERCLAIM.patchPath(), OVERCLAIM.image(), OVERCLAIM.verifyCommand(), OVERCLAIM.limits(),
                OVERCLAIM.protectedPaths(), "gradle");

        var e = assertThrows(IllegalArgumentException.class, () -> ForgeGuardCli.Submit.submit(
                store, VerificationStrategies.builtIn(), gradle, OVERCLAIM_TASK, out));

        assertTrue(e.getMessage().startsWith("unknown strategy: gradle"), e.getMessage());
        assertEquals(List.of(), store.recent(10), "no run is left for a worker to fail on");
        assertEquals("", printed.toString());
    }

    // --- submit, a worker, then watch and show -----------------------------

    @Test
    void failingTestsGiveTestFailAndWatchAndShowPrintTheDisagreement() throws Exception {
        UUID id = submitAndVerify(CannedSandbox.failingTests());

        assertEquals("run " + id + "  TEST_FAIL   (producer reported: success)" + NL
                + ForgeGuardCli.DISAGREES + NL, watch(id));

        String shown = show(id);
        assertTrue(shown.contains("claim         success: true"), shown);
        assertTrue(shown.contains("verdict       TEST_FAIL"), shown);
        assertTrue(shown.contains(ForgeGuardCli.DISAGREES), shown);
        assertTrue(shown.contains("mergesContainedInterval"), "stdout log is printed: " + shown);
    }

    static Stream<Arguments> cannedResults() {
        return Stream.of(
                arguments(CannedSandbox.passing(), Outcome.PASS),
                arguments(CannedSandbox.failingTests(), Outcome.TEST_FAIL),
                arguments(CannedSandbox.compileError(), Outcome.BUILD_FAIL),
                arguments(CannedSandbox.timedOut(), Outcome.TIMEOUT),
                arguments(CannedSandbox.oomKilled(), Outcome.RESOURCE_EXCEEDED));
    }

    @ParameterizedTest
    @MethodSource("cannedResults")
    void watchPrintsTheVerdictTheStrategyReaches(CannedSandbox sandbox, Outcome expected) throws Exception {
        UUID id = submitAndVerify(sandbox);

        assertEquals(expected, store.find(id).orElseThrow().outcome());
        assertTrue(watch(id).startsWith("run " + id + "  " + expected + "   "), printed.toString());
    }

    @Test
    void agreementHasNoDisagreementLine() throws Exception {
        UUID id = submitAndVerify(CannedSandbox.passing());

        assertEquals("run " + id + "  PASS   (producer reported: success)" + NL, watch(id));
        assertFalse(show(id).contains("disagrees"));
    }

    @Test
    void harnessErrorIsNotADisagreement() throws Exception {
        UUID id = submitAndVerify((ws, command, limits) -> {
            throw new IllegalStateException("docker daemon unreachable");
        });

        assertEquals(Outcome.HARNESS_ERROR, store.find(id).orElseThrow().outcome());
        assertFalse(watch(id).contains("disagrees"));
        assertFalse(show(id).contains("disagrees"));
    }

    @Test
    void pendingRunShowsItsStateAndDashesNotNull() {
        UUID done = submitAndVerify(CannedSandbox.failingTests());
        UUID pending = submit();

        String shown = show(pending);
        assertTrue(shown.contains("verdict       PENDING"), shown);
        assertFalse(shown.contains("null"), shown);

        printed.getBuffer().setLength(0);
        ForgeGuardCli.List.list(store, out);
        List<String> rows = printed.toString().lines().toList();
        assertTrue(rows.stream().anyMatch(l -> l.startsWith(pending.toString()) && l.contains("PENDING")), rows.toString());
        assertTrue(rows.stream().anyMatch(l -> l.startsWith(done.toString()) && l.contains("TEST_FAIL")), rows.toString());
        assertFalse(printed.toString().contains("null"), rows.toString());
    }

    @Test
    void listKeepsClaimColumnAlignedForLongestVerdict() {
        UUID id = store.create(OVERCLAIM);
        store.recordVerdict(id, Outcome.PROTECTED_PATH_MODIFIED,
                new ExecResult(-1, "", "", Termination.EXITED, 0L, List.of()));
        submitAndVerify(CannedSandbox.passing());

        printed.getBuffer().setLength(0);
        ForgeGuardCli.List.list(store, out);
        List<String> rows = printed.toString().lines().toList();
        int claimColumn = rows.get(0).indexOf("CLAIM");
        for (String row : rows.subList(1, rows.size())) {
            String claim = row.substring(claimColumn);
            assertTrue(claim.equals("success") || claim.equals("failure"), row);
        }
    }

    // --- watch --------------------------------------------------------------

    @Test
    void watchPrintsEachChangeOfStateUntilTheVerdict() throws Exception {
        UUID id = submit();
        printed.getBuffer().setLength(0);
        // Each wait lets a worker take one more step. The middle step changes
        // nothing, so it prints nothing.
        AtomicReference<Claim> claim = new AtomicReference<>();
        Deque<Runnable> steps = new ArrayDeque<>(List.of(
                () -> claim.set(store.claim("w1", HOUR).orElseThrow()),
                () -> { },
                () -> FakeRuns.with(CannedSandbox.failingTests()).apply(store.fencedTo(claim.get()))
                        .execute(id, OVERCLAIM, CLAIMS_SUCCESS)));

        assertTrue(ForgeGuardCli.Watch.watch(store, id, ForgeGuardCli.Watch.POLL, null,
                d -> steps.removeFirst().run(), out));

        assertEquals(List.of(
                "run " + id + "  PENDING",
                "run " + id + "  CLAIMED",
                "run " + id + "  TEST_FAIL   (producer reported: success)",
                ForgeGuardCli.DISAGREES), printed.toString().lines().toList());
        assertTrue(steps.isEmpty());
    }

    @Test
    void watchGivesUpOnceTheTimeoutHasPassed() throws Exception {
        UUID id = submit();
        printed.getBuffer().setLength(0);
        AtomicInteger waits = new AtomicInteger();

        assertFalse(ForgeGuardCli.Watch.watch(store, id, Duration.ofSeconds(1), Duration.ofSeconds(3),
                d -> waits.incrementAndGet(), out));

        assertEquals(3, waits.get());
        assertEquals("run " + id + "  PENDING" + NL, printed.toString());
    }

    @Test
    void watchWithAZeroTimeoutReadsTheRunOnce() throws Exception {
        UUID id = submit();

        assertFalse(ForgeGuardCli.Watch.watch(store, id, ForgeGuardCli.Watch.POLL, Duration.ZERO, NEVER, out));
    }

    @Test
    void watchOfAnUnknownRunIsAnInputError() {
        UUID ghost = UUID.randomUUID();

        var e = assertThrows(IllegalArgumentException.class,
                () -> ForgeGuardCli.Watch.watch(store, ghost, ForgeGuardCli.Watch.POLL, null, NEVER, out));
        assertEquals("no such run: " + ghost, e.getMessage());
    }

    // --- stats --------------------------------------------------------------

    private static final String HEADER = String.format("%-24s %5s", "VERDICT", "RUNS");

    private static String row(Object name, long runs) {
        return String.format("%-24s %5d", name, runs);
    }

    @Test
    void statsCountsVerdictsAndTheDisagreementRate() {
        ForgeGuardCli.Stats.stats(List.of(
                new RunCount(RunState.DONE, Outcome.TEST_FAIL, true, 3),
                new RunCount(RunState.PENDING, null, false, 2),
                new RunCount(RunState.DONE, Outcome.PASS, true, 2),
                new RunCount(RunState.DONE, Outcome.HARNESS_ERROR, true, 1),  // decided nothing
                new RunCount(RunState.DONE, Outcome.TIMEOUT, false, 1),       // admitted failure: no claim to check
                new RunCount(RunState.CLAIMED, null, false, 1)), out);

        assertEquals(List.of(
                HEADER,
                row(Outcome.PASS, 2),
                row(Outcome.TEST_FAIL, 3),
                row(Outcome.TIMEOUT, 1),
                row(Outcome.HARNESS_ERROR, 1),
                row(RunState.PENDING, 2),
                row(RunState.CLAIMED, 1),
                row("total", 10),
                "",
                "disagreement rate  60.0%   3 of the 5 success claims the harness could check were wrong"),
                printed.toString().lines().toList());
    }

    @Test
    void statsWithNothingCheckedHasNoRate() {
        ForgeGuardCli.Stats.stats(List.of(new RunCount(RunState.PENDING, null, false, 1)), out);

        assertEquals(List.of(
                HEADER,
                row(RunState.PENDING, 1),
                row("total", 1),
                "",
                "disagreement rate  -       no success claims checked yet"),
                printed.toString().lines().toList());
    }

    @Test
    void statsSummarizesWhatWorkersRecorded() {
        submitAndVerify(CannedSandbox.passing());
        submitAndVerify(CannedSandbox.failingTests());

        ForgeGuardCli.Stats.stats(store.counts(), out);

        assertTrue(printed.toString().contains("disagreement rate  50.0%   1 of the 2 success claims"),
                printed.toString());
    }

    // --- errors raised before any database connection: the real command line, in process

    private final StringWriter errors = new StringWriter();

    private int execute(String... args) {
        var cli = ForgeGuardCli.commandLine();
        cli.setOut(out);
        cli.setErr(new PrintWriter(errors, true));
        return cli.execute(args);
    }

    @Test
    void submitMissingTaskFileIsOneLineAndNonZero() {
        assertEquals(1, execute("submit", "no/such/task.yaml"));
        assertEquals("forgeguard: task file not found: " + Path.of("no/such/task.yaml").toAbsolutePath()
                + NL, errors.toString());
    }

    @Test
    void submitMalformedTaskFileIsOneLineAndNonZero(@TempDir Path repo) throws IOException {
        Path task = Files.createDirectories(repo.resolve("tasks")).resolve("t.yaml");
        Files.writeString(task, """
                taskId: t
                repoUri: file:///tmp/x
                baseRef: v1.0
                patchPath: fixtures/t.patch
                reportPath: fixtures/t.report.json
                verifyCommand: mvn -B verify
                """);

        assertEquals(1, execute("submit", task.toString()));
        assertEquals(1, errors.toString().lines().count(), errors.toString());
        assertTrue(errors.toString().startsWith("forgeguard: task file " + task + ": verifyCommand must be a list"),
                errors.toString());
    }

    @Test
    void showWithAMalformedIdIsAUsageError() {
        assertEquals(2, execute("show", "not-a-uuid"));
        assertTrue(errors.toString().contains("not-a-uuid"), errors.toString());
    }

    @Test
    void watchWithANegativeTimeoutIsOneLineAndNonZero() {
        assertEquals(1, execute("watch", UUID.randomUUID().toString(), "--timeout", "-1"));
        assertEquals("forgeguard: --timeout must not be negative: -1" + NL, errors.toString());
    }
}
