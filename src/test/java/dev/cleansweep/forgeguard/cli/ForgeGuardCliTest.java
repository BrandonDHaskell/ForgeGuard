package dev.cleansweep.forgeguard.cli;

import dev.cleansweep.forgeguard.orchestrator.DiffInspector;
import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.Sandbox;
import dev.cleansweep.forgeguard.spec.Limits;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.support.CannedSandbox;
import dev.cleansweep.forgeguard.support.FakeWorkspace;
import dev.cleansweep.forgeguard.support.InMemoryRunStore;
import dev.cleansweep.forgeguard.verify.MavenStrategy;
import dev.cleansweep.forgeguard.verify.Outcome;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** The commands' output, wired with fakes: no Git, Docker, Maven, or PostgreSQL. */
class ForgeGuardCliTest {

    private static final TaskSpec OVERCLAIM = new TaskSpec("intervals-overclaim",
            "file:///tmp/forgeguard-fixtures/intervals-java", "v1.0", "fixtures/patches/overclaim.patch",
            null, List.of("mvn", "-B", "-o", "verify"), Limits.DEFAULTS, List.of("src/test/**", "pom.xml"));

    /** Claims success without touching the workspace, as the recorded overclaim producer does. */
    private static final Producer CLAIMS_SUCCESS =
            ws -> new ProducerResult(true, "{\"success\":true,\"summary\":\"All tests pass.\"}");

    /** What git diff reports for the overclaim patch, without running git. */
    private static final DiffInspector OVERCLAIM_DIFF = ws -> new DiffInspector.Diff(ws.baseCommit(), "d1ff",
            List.of("src/main/java/demo/IntervalMerger.java", "src/main/java/demo/Interval.java"), "");

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final StringWriter printed = new StringWriter();
    private final PrintWriter out = new PrintWriter(printed, true);

    private UUID submit(Sandbox sandbox) {
        var runner = new VerificationRun(
                FakeWorkspace.provider(), sandbox, new MavenStrategy(), store, OVERCLAIM_DIFF);
        return ForgeGuardCli.Submit.submit(runner, store, OVERCLAIM, CLAIMS_SUCCESS, out);
    }

    private String show(UUID runId) {
        printed.getBuffer().setLength(0);
        ForgeGuardCli.Show.show(store.find(runId).orElseThrow(), store.logs(runId, "stdout"), out);
        return printed.toString();
    }

    @Test
    void failingTestsPrintTestFailAndShowPrintsTheDisagreement() {
        UUID id = submit(CannedSandbox.failingTests());
        assertEquals("run " + id + "  TEST_FAIL   (producer reported: success)" + System.lineSeparator(),
                printed.toString());

        String shown = show(id);
        assertTrue(shown.contains("claim         success: true"), shown);
        assertTrue(shown.contains("verdict       TEST_FAIL"), shown);
        assertTrue(shown.contains("The producer claimed success. The harness disagrees."), shown);
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
    void submitPrintsTheVerdictTheStrategyReaches(CannedSandbox sandbox, Outcome expected) {
        UUID id = submit(sandbox);

        assertEquals(expected, store.find(id).orElseThrow().outcome());
        assertTrue(printed.toString().contains("  " + expected + "   "), printed.toString());
    }

    @Test
    void agreementHasNoDisagreementLine() {
        assertFalse(show(submit(CannedSandbox.passing())).contains("disagrees"));
    }

    @Test
    void harnessErrorIsNotADisagreement() {
        UUID id = submit((ws, command, limits) -> {
            throw new IllegalStateException("docker daemon unreachable");
        });

        assertEquals(Outcome.HARNESS_ERROR, store.find(id).orElseThrow().outcome());
        assertFalse(show(id).contains("disagrees"));
    }

    @Test
    void pendingRunShowsItsStateAndDashesNotNull() {
        UUID pending = store.create(OVERCLAIM);
        UUID done = submit(CannedSandbox.failingTests());

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

    // Errors raised before any database connection: the real command line, in process.

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
                + System.lineSeparator(), errors.toString());
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
}
