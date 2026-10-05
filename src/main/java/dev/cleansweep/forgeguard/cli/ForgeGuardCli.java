package dev.cleansweep.forgeguard.cli;

import dev.cleansweep.forgeguard.orchestrator.GitDiffInspector;
import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.LocalProcessSandbox;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.store.Database;
import dev.cleansweep.forgeguard.store.JdbcRunStore;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.verify.MavenStrategy;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Entry point. Wiring happens here and nowhere else: every collaborator is
 * constructed in one place and passed down, so the dependency graph is readable
 * without a framework.
 */
@Command(name = "forgeguard", mixinStandardHelpOptions = true,
        subcommands = {ForgeGuardCli.Submit.class, ForgeGuardCli.Show.class, ForgeGuardCli.List.class})
public final class ForgeGuardCli implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ForgeGuardCli()).execute(args));
    }

    @Command(name = "submit", description = "Verify the change described by a task file.")
    static final class Submit implements Runnable {

        @Parameters(index = "0", description = "Path to the task YAML file.")
        Path taskFile;

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

            UUID runId = runner.execute(loaded.spec(),
                    new ScriptedProducer(loaded.patchFile(), loaded.reportFile()));

            RunRecord r = store.find(runId).orElseThrow();
            System.out.printf("run %s  %s   (producer reported: %s)%n",
                    runId, r.outcome(), r.producerReportedSuccess() ? "success" : "failure");
        }
    }

    @Command(name = "show", description = "Print the stored evidence for a run.")
    static final class Show implements Runnable {

        @Parameters(index = "0", description = "Run id.")
        UUID runId;

        @Override
        public void run() {
            var store = new JdbcRunStore(Database.connect());
            RunRecord r = store.find(runId).orElseThrow(
                    () -> new IllegalArgumentException("no such run: " + runId));

            System.out.printf("""
                    run           %s
                    task          %s
                    base commit   %s
                    diff sha256   %s
                    command       %s
                    exit code     %s
                    termination   %s
                    duration      %s ms

                    claim         %s
                    verdict       %s
                    %s
                    """,
                    r.runId(), r.taskId(), r.baseCommit(), r.diffSha256(), r.verifyCommand(),
                    r.exitCode(), r.termination(), r.durationMs(),
                    r.producerReportedSuccess() ? "success: true" : "success: false",
                    r.outcome(),
                    disagreementNote(r));

            System.out.println("--- stdout ---");
            System.out.println(store.logs(runId, "stdout"));
        }

        private static String disagreementNote(RunRecord r) {
            boolean passed = r.outcome() != null && r.outcome().name().equals("PASS");
            return (r.producerReportedSuccess() && !passed)
                    ? "\nThe producer claimed success. The harness disagrees."
                    : "";
        }
    }

    @Command(name = "list", description = "Show recent runs and their verdicts.")
    static final class List implements Runnable {

        @Override
        public void run() {
            var store = new JdbcRunStore(Database.connect());
            System.out.printf("%-38s %-24s %-10s %s%n", "RUN", "TASK", "VERDICT", "CLAIM");
            for (RunRecord r : store.recent(20)) {
                System.out.printf("%-38s %-24s %-10s %s%n",
                        r.runId(), r.taskId(), r.outcome(),
                        r.producerReportedSuccess() ? "success" : "failure");
            }
        }
    }
}
