package dev.cleansweep.forgeguard.cli;

import dev.cleansweep.forgeguard.orchestrator.GitDiffInspector;
import dev.cleansweep.forgeguard.orchestrator.VerificationRun;
import dev.cleansweep.forgeguard.producer.ScriptedProducer;
import dev.cleansweep.forgeguard.sandbox.LocalProcessSandbox;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.spec.TaskSpecLoader;
import dev.cleansweep.forgeguard.store.Database;
import dev.cleansweep.forgeguard.store.JdbcRunStore;
import dev.cleansweep.forgeguard.store.RunCount;
import dev.cleansweep.forgeguard.store.RunRecord;
import dev.cleansweep.forgeguard.store.RunState;
import dev.cleansweep.forgeguard.store.RunStore;
import dev.cleansweep.forgeguard.verify.Outcome;
import dev.cleansweep.forgeguard.verify.VerificationStrategies;
import dev.cleansweep.forgeguard.worker.Worker;
import dev.cleansweep.forgeguard.worker.WorkerConfig;
import dev.cleansweep.forgeguard.workspace.JGitWorkspaceProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

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
        subcommands = {ForgeGuardCli.Submit.class, ForgeGuardCli.WorkerCommand.class, ForgeGuardCli.Watch.class,
                ForgeGuardCli.Show.class, ForgeGuardCli.List.class, ForgeGuardCli.Stats.class})
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

    static final String DISAGREES = "The producer claimed success. The harness disagrees.";

    /**
     * A verdict about the change itself. A harness error, or no verdict yet,
     * decided nothing.
     */
    static boolean decided(Outcome outcome) {
        return outcome != null && outcome != Outcome.HARNESS_ERROR;
    }

    /**
     * The disagreement this harness exists to surface: the producer claimed
     * success and the harness decided otherwise. show, watch and stats all use
     * this one rule.
     */
    static boolean disagrees(boolean producerReportedSuccess, Outcome outcome) {
        return producerReportedSuccess && decided(outcome) && outcome != Outcome.PASS;
    }

    /** The line watch ends on once a run has its verdict. */
    private static void printVerdict(RunRecord r, PrintWriter out) {
        out.printf("run %s  %s   (producer reported: %s)%n",
                r.runId(), r.outcome(), r.producerReportedSuccess() ? "success" : "failure");
        if (disagrees(r.producerReportedSuccess(), r.outcome())) {
            out.println(DISAGREES);
        }
    }

    @Command(name = "submit", description = "Queue the change described by a task file for a worker to verify.")
    static final class Submit implements Runnable {

        @Parameters(index = "0", description = "Path to the task YAML file.")
        Path taskFile;

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            var loaded = new TaskSpecLoader().load(taskFile);
            submit(new JdbcRunStore(Database.connect()), VerificationStrategies.builtIn(),
                    loaded.spec(), taskFile, spec.commandLine().getOut());
        }

        /**
         * Queues one run and prints one line: the run id and PENDING. Nothing is
         * verified here. A worker claims the run, and watch waits for its verdict.
         *
         * @throws IllegalArgumentException if the task names an unknown strategy.
         *     That is checked before anything is queued, so a bad task file leaves
         *     no run behind for a worker to fail on.
         */
        static UUID submit(RunStore store, VerificationStrategies strategies, TaskSpec task,
                           Path taskFile, PrintWriter out) {
            strategies.get(task.strategy());
            // Absolute, because a worker may run from another directory.
            UUID runId = store.enqueue(task, taskFile.toAbsolutePath().normalize());
            out.printf("run %s  %s   (queued for a worker)%n", runId, RunState.PENDING);
            return runId;
        }
    }

    @Command(name = "watch", description = "Wait for a run's verdict, printing each change of state.")
    static final class Watch implements Callable<Integer> {

        /** How often the run is read again. */
        static final Duration POLL = Duration.ofSeconds(1);

        @Parameters(index = "0", description = "Run id.")
        UUID runId;

        @Option(names = "--timeout", paramLabel = "SECONDS", description = {
                "Give up after this many seconds without a verdict, and exit 1.",
                "0 checks once. By default, wait for as long as it takes."})
        Integer timeoutSeconds;

        @Spec
        CommandSpec spec;

        /** How watch waits between reads. Tests replace it so that nothing sleeps. */
        interface Sleeper {
            void sleep(Duration d) throws InterruptedException;
        }

        @Override
        public Integer call() {
            if (timeoutSeconds != null && timeoutSeconds < 0) {
                throw new IllegalArgumentException("--timeout must not be negative: " + timeoutSeconds);
            }
            var store = new JdbcRunStore(Database.connect());
            Duration timeout = timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds);
            try {
                if (watch(store, runId, POLL, timeout, Thread::sleep, spec.commandLine().getOut())) {
                    return 0;
                }
                spec.commandLine().getErr().println(
                        "forgeguard: run " + runId + " has no verdict after " + timeoutSeconds + "s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }

        /**
         * Prints the run's state, then a line for each change of state, until it
         * has a verdict. The last line has the shape submit used to print,
         * {@code run <id>  <VERDICT>   (producer reported: ...)}, followed by the
         * disagreement line if there is one. A reclaim leaves the run CLAIMED, so
         * it prints no new line.
         *
         * @param timeout how long to wait in total, or null to wait for as long
         *     as it takes. Zero reads the run once.
         * @return true once the run has a verdict, false if the timeout came first
         * @throws IllegalArgumentException if there is no such run
         */
        static boolean watch(RunStore store, UUID runId, Duration poll, Duration timeout,
                             Sleeper sleeper, PrintWriter out) throws InterruptedException {
            Duration waited = Duration.ZERO;
            RunState shown = null;
            while (true) {
                RunRecord r = store.find(runId).orElseThrow(
                        () -> new IllegalArgumentException("no such run: " + runId));
                if (r.outcome() != null) {
                    printVerdict(r, out);
                    out.flush();
                    return true;
                }
                if (r.state() != shown) {
                    out.printf("run %s  %s%n", runId, r.state());
                    out.flush();
                    shown = r.state();
                }
                if (timeout != null && waited.compareTo(timeout) >= 0) {
                    return false;
                }
                sleeper.sleep(poll);
                waited = waited.plus(poll);
            }
        }
    }

    @Command(name = "stats", description = "Count runs by verdict, and how often a producer's success claim was wrong.")
    static final class Stats implements Runnable {

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            stats(new JdbcRunStore(Database.connect()).counts(), spec.commandLine().getOut());
        }

        /**
         * Prints the verdicts in Outcome order, then the runs still waiting, in
         * RunState order. Groups with no runs are left out.
         *
         * <p>Then the disagreement rate. The denominator is the success claims the
         * harness could check: runs whose producer claimed success and that got
         * a decided verdict. The numerator is those it disagreed with. Harness
         * errors and unfinished runs decided nothing, so they are in neither.
         * Neither is a producer that admitted failure, which made no claim to check.
         */
        static void stats(java.util.List<RunCount> counts, PrintWriter out) {
            Map<Outcome, Long> verdicts = new EnumMap<>(Outcome.class);
            Map<RunState, Long> waiting = new EnumMap<>(RunState.class);
            long total = 0;
            long checked = 0;
            long disagreed = 0;
            for (RunCount c : counts) {
                total += c.runs();
                if (c.outcome() == null) {
                    waiting.merge(c.state(), c.runs(), Long::sum);
                } else {
                    verdicts.merge(c.outcome(), c.runs(), Long::sum);
                }
                if (c.producerReportedSuccess() && decided(c.outcome())) {
                    checked += c.runs();
                }
                if (disagrees(c.producerReportedSuccess(), c.outcome())) {
                    disagreed += c.runs();
                }
            }

            out.printf("%-24s %5s%n", "VERDICT", "RUNS");
            verdicts.forEach((outcome, n) -> out.printf("%-24s %5d%n", outcome, n));
            waiting.forEach((state, n) -> out.printf("%-24s %5d%n", state, n));
            out.printf("%-24s %5d%n", "total", total);
            out.println();
            if (checked == 0) {
                out.println("disagreement rate  -       no success claims checked yet");
            } else {
                out.printf("disagreement rate  %-7s %d of the %d success claims the harness could check were wrong%n",
                        String.format(Locale.ROOT, "%.1f%%", 100.0 * disagreed / checked), disagreed, checked);
            }
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

        private static String disagreementNote(RunRecord r) {
            return disagrees(r.producerReportedSuccess(), r.outcome()) ? "\n" + DISAGREES : "";
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

    @Command(name = "worker", description = {
            "Claim queued runs and verify them, one at a time, until killed.",
            "Settings come from FORGEGUARD_WORKER_ID, FORGEGUARD_LEASE_SECONDS,",
            "FORGEGUARD_POLL_SECONDS and FORGEGUARD_MAX_TIMEOUT_SECONDS."})
    static final class WorkerCommand implements Runnable {

        @Option(names = "--drain", description = "Exit once the queue is empty instead of waiting for more runs.")
        boolean drain;

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            // Before connecting: a worker whose lease is too short refuses to start.
            WorkerConfig config = WorkerConfig.fromEnv(System.getenv());
            var store = new JdbcRunStore(Database.connect());
            PrintWriter out = spec.commandLine().getOut();

            Worker worker = new Worker(config, store, new TaskSpecLoader(),
                    fenced -> new VerificationRun(
                            new JGitWorkspaceProvider(),
                            new LocalProcessSandbox(),
                            VerificationStrategies.builtIn(),
                            fenced,
                            new GitDiffInspector()),
                    task -> new ScriptedProducer(task.patchFile(), task.reportFile()),
                    out);

            out.printf("worker %s: lease %ds, poll %ds, max timeout %ds%s%n",
                    config.workerId(), config.lease().toSeconds(), config.poll().toSeconds(),
                    config.maxTimeoutSeconds(), drain ? ", draining" : "");
            out.flush();
            if (drain) {
                worker.drain();
                return;
            }
            try {
                worker.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
