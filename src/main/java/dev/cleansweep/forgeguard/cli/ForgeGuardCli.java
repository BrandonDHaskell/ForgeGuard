package dev.cleansweep.forgeguard.cli;

import dev.cleansweep.forgeguard.orchestrator.GitDiffInspector;
import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.producer.Producer;
import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.LocalProcessSandbox;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.store.Database;
import dev.cleansweep.forgeguard.store.JdbcRunStore;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.MavenStrategy;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Entry point. Wiring happens here and nowhere else: every collaborator is
 * constructed in one place and passed down, so the dependency graph is readable
 * without a framework.
 *
 * <p>Each command builds its collaborators in run() and hands them to a static
 * method that does the work and the printing. Tests call those methods with
 * fakes, so the output is checked with no Git, Docker, Maven, or PostgreSQL.
 */
@Command(name = "forgeguard", mixinStandardHelpOptions = true,
        subcommands = {ForgeGuardCli.Submit.class, ForgeGuardCli.Show.class, ForgeGuardCli.List.class})
public final class ForgeGuardCli implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** The configured command line. Separate from main so tests can run commands in process. */
    static CommandLine commandLine() {
        return new CommandLine(new ForgeGuardCli())
                .setExecutionExceptionHandler(ForgeGuardCli::reportOperatorError);
    }

    /**
     * Bad input and an unreachable database are for the operator to fix, not bugs,
     * so they print as one line. IllegalArgumentException is this codebase's input
     * error, and VerificationRun turns anything thrown while verifying into a
     * HARNESS_ERROR verdict, so one that reaches here came from the command's own
     * input: a missing or malformed task file, or an unknown run id. Anything else
     * is rethrown for picocli to report in full.
     */
    private static int reportOperatorError(Exception e, CommandLine cmd,
                                           CommandLine.ParseResult parsed) throws Exception {
        if (e instanceof IllegalArgumentException || e instanceof Database.DatabaseException) {
            cmd.getErr().println("forgeguard: " + e.getMessage());
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        }
        throw e;
    }

    /** Fields a run has not reached yet print as a dash rather than "null". */
    private static Object orDash(Object value) {
        return value == null ? "-" : value;
    }

    @Command(name = "submit", description = "Verify the change described by a task file.")
    static final class Submit implements Runnable {

        @Parameters(index = "0", description = "Path to the task YAML file.")
        Path taskFile;

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            var loaded = new TaskSpecLoader().load(taskFile);
            var store = new JdbcRunStore(Database.connect());

            var runner = new VerificationRun(
                    new JGitWorkspaceProvider(),
                    new LocalProcessSandbox(),
                    new MavenStrategy(),
                    store,
                    new GitDiffInspector());

            submit(runner, store, loaded.spec(),
                    new ScriptedProducer(loaded.patchFile(), loaded.reportFile()),
                    spec.commandLine().getOut());
        }

        /** Runs one attempt and prints one line: run id, verdict, and the producer's claim. */
        static UUID submit(VerificationRun runner, RunStore store, TaskSpec task,
                           Producer producer, PrintWriter out) {
            UUID runId = runner.execute(task, producer);
            RunRecord r = store.find(runId).orElseThrow();
            out.printf("run %s  %s   (producer reported: %s)%n",
                    runId, r.outcome(), r.producerReportedSuccess() ? "success" : "failure");
            return runId;
        }
    }

    @Command(name = "show", description = "Print the stored evidence for a run.")
    static final class Show implements Runnable {

        @Parameters(index = "0", description = "Run id.")
        UUID runId;

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            var store = new JdbcRunStore(Database.connect());
            RunRecord r = store.find(runId).orElseThrow(
                    () -> new IllegalArgumentException("no such run: " + runId));
            show(r, store.logs(runId, "stdout"), spec.commandLine().getOut());
        }

        /** Prints the evidence block, the disagreement line if there is one, and the stdout log. */
        static void show(RunRecord r, String stdout, PrintWriter out) {
            out.printf("""
                    run           %s
                    task          %s
                    base commit   %s
                    diff sha256   %s
                    command       %s
                    exit code     %s
                    termination   %s
                    duration      %s

                    claim         %s
                    verdict       %s
                    %s
                    """,
                    r.runId(), r.taskId(), orDash(r.baseCommit()), orDash(r.diffSha256()),
                    orDash(r.verifyCommand()), orDash(r.exitCode()), orDash(r.termination()),
                    r.durationMs() == null ? "-" : r.durationMs() + " ms",
                    r.producerReportedSuccess() ? "success: true" : "success: false",
                    r.outcome() == null ? r.state() : r.outcome(),
                    disagreementNote(r));

            out.println("--- stdout ---");
            out.println(stdout);
        }

        /** Only a decided verdict can disagree. A harness error, or no verdict yet, decided nothing. */
        private static String disagreementNote(RunRecord r) {
            boolean decided = r.outcome() != null && r.outcome() != Outcome.HARNESS_ERROR;
            return r.producerReportedSuccess() && decided && r.outcome() != Outcome.PASS
                    ? "\nThe producer claimed success. The harness disagrees."
                    : "";
        }
    }

    @Command(name = "list", description = "Show recent runs and their verdicts.")
    static final class List implements Runnable {

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            list(new JdbcRunStore(Database.connect()), spec.commandLine().getOut());
        }

        /** The 20 newest runs. One with no verdict yet shows its state instead. */
        static void list(RunStore store, PrintWriter out) {
            out.printf("%-38s %-24s %-24s %s%n", "RUN", "TASK", "VERDICT", "CLAIM");
            for (RunRecord r : store.recent(20)) {
                out.printf("%-38s %-24s %-24s %s%n",
                        r.runId(), r.taskId(), r.outcome() == null ? r.state() : r.outcome(),
                        r.producerReportedSuccess() ? "success" : "failure");
            }
        }
    }
}
