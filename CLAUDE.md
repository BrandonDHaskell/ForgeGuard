# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

ForgeGuard is a local harness that verifies code changes it did not produce. A **producer** changes a clean checkout and reports whether it succeeded. That claim is stored as evidence and never used to decide anything. The **verdict** (`verify.Outcome`) comes only from running the project's own build and tests. The output that matters is a disagreement: the producer claimed success and the harness says otherwise. This is a CS 601 project (team Clean Sweep: Brandon Haskell, Timothy George), currently at Milestone 1.

## Commands

Requires Java 25, Maven 3.9+, git, and Docker (in M1, Docker only runs PostgreSQL).

```bash
docker compose up -d postgres             # PostgreSQL 17 on :5432, db/user/password all "forgeguard"
./fixtures/build-fixtures.sh              # materializes the fixture git repo at /tmp/forgeguard-fixtures
./fixtures/verify-fixtures.sh             # checks the base SHA matches fixtures/BASE_SHA.txt and every patch applies
# Warm ~/.m2 for the fixture. Task files run `mvn -o`, so skipping this gives BUILD_FAIL.
# The baseline has one failing test on purpose, so test failures are ignored here.
mvn -B -f /tmp/forgeguard-fixtures/intervals-java/pom.xml verify -Dmaven.test.failure.ignore=true

mvn -B package                            # shaded jar: target/forgeguard-0.1.0-SNAPSHOT.jar
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar submit tasks/overclaim.yaml   # only queues; prints the run id
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar worker --drain                 # verifies queued runs, then exits
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar watch <run-id>                 # waits for the verdict (--timeout N)
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar show <run-id>
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar list
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar stats
./demo/run-all.sh                         # every task, graded against demo/expected.tsv (CI runs this too)
```

Tests:

```bash
mvn -B test                                   # unit tests only; no database, git fixture, or Maven fixture needed
mvn -B verify                                 # adds @Tag("integration") tests (failsafe)
mvn -B test -Dtest=TaskSpecLoaderTest         # one unit test class (or -Dtest='TaskSpecLoaderTest#methodName')
mvn -B verify -Dit.test=JdbcRunStoreTest      # one integration test class
```

Surefire excludes the `integration` group and failsafe runs only that group, both matching `**/*Test.java`. So the tag decides where a test runs, not its class name. Integration tests need the running PostgreSQL. The JGit, producer, and diff integration tests also need the built fixture repo. `FORGEGUARD_FIXTURE_DIR` overrides `/tmp/forgeguard-fixtures` for the scripts and those tests, but the task YAMLs hard-code `file:///tmp/forgeguard-fixtures/...`. CI (`.github/workflows/ci.yml`) runs the same steps: build fixtures, warm `~/.m2`, then `mvn -B verify`.

The database is configured only through the environment: `FORGEGUARD_JDBC_URL`, `FORGEGUARD_DB_USER`, and `FORGEGUARD_DB_PASSWORD`, with the defaults shown above. Flyway migrates the schema on every `Database.connect()`.

## Architecture

The package is `dev.cleansweep.forgeguard`. Start with `orchestrator/VerificationRun.java`, which is short and names every collaborator.

**The fixed sequence (`VerificationRun.execute`).** The order matters:
1. `RunStore.create` creates the run as `PENDING`.
2. `WorkspaceProvider.provision` makes a JGit clone and checks out `baseRef`, which resolves to a concrete SHA.
3. `Producer.run` makes the change and returns the claim.
4. `DiffInspector.inspect` independently reads what changed. The producer's own `files_touched` is never trusted.
5. *(M2)* `ProtectedPathCheck` runs here. It has to run **before** execution so that tampering never reaches the sandbox.
6. `VerificationStrategy.verify` runs the command in a `Sandbox` and classifies the result.
7. The verdict and the stdout/stderr logs are persisted.

If `git apply` fails, the outcome is `PATCH_REJECTED`. Any other `RuntimeException` inside the run is caught and recorded as `HARNESS_ERROR`. If the store refuses that write too, as a fenced store does after a lost lease, the refusal propagates with the original failure attached as suppressed.

**Single wiring point.** There is no DI framework. Every outside dependency (git, process execution, build system, database) sits behind an interface: `WorkspaceProvider`, `Sandbox`, `VerificationStrategy`, `RunStore`, `Producer`, and `DiffInspector`. `cli/ForgeGuardCli` is the only place that constructs the real implementations. Each picocli subcommand builds its collaborators in `run()` and delegates to a static method (`Submit.submit`, `Watch.watch`, `Show.show`, `List.list`, `Stats.stats`) that takes the collaborators and a `PrintWriter`. Unit tests call those static methods with fakes. `Watch.watch` also takes its sleeper, so tests never wait.

**A new `RunStore` method must reach every implementation:** `JdbcRunStore`, `InMemoryRunStore` and its `Fenced` view, and `VerificationRunTest.RecordingStore`. Git can't flag a missing one, because it only shows up as a compile error once branches meet. That's how T2's CI broke.

**Test doubles** live in `src/test/java/.../support/`. `InMemoryRunStore` needs no PostgreSQL. `FakeWorkspace` needs no git. `CannedSandbox` returns trimmed real Maven logs, so `MavenStrategy` classifies them as it would a live build. `RunStoreContractTest` is the abstract spec that every `RunStore` must pass. `InMemoryRunStoreTest` and `JdbcRunStoreTest` subclass it. A change to `RunStore` behavior goes in the contract test so both bindings stay interchangeable. Each contract test needs an empty store, because `claim()` takes the oldest runnable run in the whole store. `InMemoryRunStoreTest` gets a new store per test. `JdbcRunStoreTest`, `MigrationTest`, `WorkerJdbcTest` and `WorkerCrashTest` run in throwaway PostgreSQL schemas (`store/ScratchSchema`), never in `public`. `ForgeGuardCliIntegrationTest` still uses `public` and cleans up its `cli-test-*` rows.

**Work queue (`RunStore.claim`).** `enqueue(spec, taskFile)` creates a run for a worker, and `submit` is its only caller outside tests. `create(spec)` is the synchronous path: it records no `task_file`, so no worker ever claims it. Only `VerificationRun.execute(spec, producer)` uses it, and only tests call that.
- `claim(workerId, lease)` takes the oldest enqueued run that is `PENDING`, or `CLAIMED` with an expired lease. It is one `UPDATE ... FOR UPDATE SKIP LOCKED`, timed by the database clock.
- Every claim nulls all per-attempt columns and deletes the run's logs in the same transaction. Only a reclaim increments `attempt`.
- `JdbcRunStoreTest.everyRunsColumnIsClassified` fails when `runs` gains a column, until that column is put in its KEPT, LEASE or CLEARED set and the claim query is updated to match.
- A worker writes through `store.fencedTo(claim)`. Its writes succeed only while the row still has that claim's `claimed_by` and `attempt`. After a reclaim they throw `LeaseLostException` and change nothing. An expired lease that nobody has reclaimed still counts as held. In JDBC, the fence is part of each UPDATE's WHERE clause, and `appendLog` takes a `FOR SHARE` lock on the runs row. `JdbcRunStoreTest`'s two `zombie*` tests check both against an in-flight reclaim.
- **`forgeguard worker [--drain]`** (`worker/Worker`) claims one run at a time. It executes the run with `VerificationRun.execute(runId, ...)` through the fenced store. `--drain` exits once the queue is empty.
  - Settings come from the environment:

    | Variable | Default |
    | --- | --- |
    | `FORGEGUARD_WORKER_ID` | `worker-<pid>@<host>` (unique per process, which the fence relies on) |
    | `FORGEGUARD_LEASE_SECONDS` | `360` |
    | `FORGEGUARD_POLL_SECONDS` | `2` |
    | `FORGEGUARD_MAX_TIMEOUT_SECONDS` | `300` |

  - `WorkerConfig.fromEnv` refuses to start, before connecting to the database, unless the lease is longer than `FORGEGUARD_MAX_TIMEOUT_SECONDS` plus a 15s margin. The constructor doesn't apply that rule, so tests can use a zero lease to force a reclaim.
  - Two kinds of run are closed as `HARNESS_ERROR` without running: a task whose timeout is over the ceiling, and a task file that won't load. Closing them keeps them from being reclaimed and retried forever.
  - A `LeaseLostException` means another worker has the run. The worker logs it, abandons the run, and carries on. Any other exception ends the process, and the run is reclaimed when its lease expires.
  - There is no graceful shutdown. Ctrl-C also signals the build's child process, so the run is abandoned rather than recorded as a broken build.
  - `support/FakeRuns` builds a `VerificationRun` around whatever fenced store a worker hands it. `WorkerTest` uses the in-memory store, and `store/WorkerJdbcTest` drains 12 runs with 2 workers and with 3 on PostgreSQL. A barrier makes every worker hold a run at once, and the test checks each run was claimed once, finished on attempt 0.

**Crash recovery (T6, `store/WorkerCrashTest`).** The test starts `store/DoomedWorker` in a separate JVM.
- `DoomedWorker` claims the run with a 2s lease, records its workspace, producer report and some partial build output, then prints `HOLDING` and blocks.
- The test kills it with `Process.destroyForcibly()`, which is SIGKILL, the same as `kill -9`, and asserts exit 137. A survivor `Worker` then reclaims the run once the lease expires.
- It asserts one verdict, `attempt = 1`, and none of the dead worker's evidence or logs left.
- The child's class path comes from `surefire.test.class.path`, because failsafe gives the test JVM a manifest-only booter jar. The child connects with the same `FORGEGUARD_*` variables, to the scratch schema named on its command line.
- Mutation-checked: if the claim query stops deleting a reclaimed run's logs, the test fails.

**The queue from the CLI (T5).**
- **`submit`** checks the task's strategy name, then enqueues the run with the task file's absolute path, and prints `run <id>  PENDING`. It verifies nothing.
  - An unknown strategy is refused before anything is queued, so a bad task file leaves no run behind for a worker to fail on.
- **`watch <id> [--timeout N]`** reads the run every second. It prints a line per change of state, then the verdict line, then the disagreement line if there is one.
  - The verdict line has the shape `submit` printed before T5, so scripts can read `$3` of the last `run` line.
  - A reclaim leaves the state `CLAIMED`, so it prints nothing new.
  - On timeout, `watch` exits 1. `--timeout 0` reads the run once.
- **`stats`** prints `RunStore.counts()`: runs grouped by state, verdict and producer claim, as one aggregate query. The output is verdict counts, then the runs still waiting, then the disagreement rate.
  - The rate is disagreements out of the success claims the harness could check, meaning runs whose producer claimed success and that got a verdict other than `HARNESS_ERROR`.
  - `ForgeGuardCli.disagrees` is the one rule that `show`, `watch` and `stats` all use.
- **`demo/run-all.sh`** queues every task, runs `worker --drain`, then reads each verdict with `watch --timeout 0`. Any other runs already waiting in the queue are verified too.

**Verdict classification (`MavenStrategy`).** Maven exits 1 for both compile errors and test failures, so the outcome is read from the log:
- termination `TIMEOUT` → `TIMEOUT`
- termination `OOM_KILLED` → `RESOURCE_EXCEEDED`
- exit 0 → `PASS`
- `BUILD FAILURE` plus `Failures:` → `TEST_FAIL`
- anything else → `BUILD_FAIL`, the conservative default

This is why raw logs are persisted rather than summarized. See `DATAFLOW.md` §5 for the full table.

**Determinism is a design requirement.** It is what makes the same task give the same verdict on both laptops and in CI:
- `build-fixtures.sh` pins author, committer, and dates, so the fixture commit is always `7f8561742a0edcf981fcb73942688403a57e31b6`.
- `ScriptedProducer` replays a committed `.patch` and `.report.json`.
- `GitDiffInspector` pins every diff option that git config could change, and drops `index` lines before hashing.
- `LocalProcessSandbox` kills the whole process tree on timeout, because surefire forks a JVM.

Don't add anything nondeterministic to the producer path. A live LLM producer is explicitly out of scope; see `BUILD_PLAN.md`.

**Task files (`spec/TaskSpecLoader`)** are strict on purpose:
- Unknown keys, wrong types, bare strings where lists belong, and unquoted numbers in string fields are all errors, never defaults. A typo like `protectedPath:` must not silently disable a check.
- `patchPath` and `reportPath` resolve against the task file's grandparent (the repo root), not the CWD.
- A new task field has to be added to the loader's `FIELDS`/`LIMIT_FIELDS` sets and to the `TaskSpec` record.

**Intent vs. evidence.** A task names `baseRef: v1.0`, which is intent. The run records the resolved SHA, which is evidence. Keep that distinction when adding fields. `ExecResult.command` and `runs.verify_command` hold the exact argv that ran.

**Errors at the CLI.** `IllegalArgumentException` is this codebase's input-error type. Along with `Database.DatabaseException`, it prints as a single `forgeguard: ...` line with a non-zero exit. Any other exception reaching picocli is a bug and prints in full.

**Schema** is in `src/main/resources/db/migration/` (Flyway). Applied migrations are checksummed, so never edit one: schema changes go in the next file, `V3__...`. `V2__work_queue.sql` adds `runs.attempt` (0 for the first execution; only a lease reclaim increments it), `task_file` (absolute path of the submitted task YAML, so a worker can reload it; NULL on M1 runs), and `sandbox` (which `Sandbox` executed the run; M1 rows are backfilled `'local'`). `enqueue` writes `task_file`, and the claim query writes the lease columns and `attempt`. Nothing writes `image_digest` or `sandbox` yet. `MigrationTest` migrates throwaway schemas to check both a fresh database and an upgrade of an M1 database.

## M1 limits to be aware of

- `DockerSandbox` and `ProtectedPathCheck` exist in the tree but are **not wired in**. Verification runs as a host process with the user's privileges, network, and `~/.m2`. `LocalProcessSandbox` enforces only a wall-clock timeout, and the `limits` block's memory, CPU, pids, and network values are not enforced.
- Because protected paths are not checked, `tamper` (which deletes the failing test) gives `PASS`. Both `tamper.patch` and `hang.patch` edit `src/test/`, so once `ProtectedPathCheck` is wired in, `hang` will also be rejected unless its loop moves into main code.
- `GitDiffInspector` runs a plain `git diff` with no staging, so files the producer *creates* (untracked) are missing from the touched paths and the hash.

Expected fixture verdicts: `correct` → `PASS`, `overclaim` → `TEST_FAIL` (the disagreement demo), `hang` → `TIMEOUT`, `tamper` → `PASS` in M1 and `PROTECTED_PATH_MODIFIED` once the check is wired.

## Collaboration conventions

- Ownership: Brandon (`bh/` branches) owns `workspace/`, `sandbox/`, `producer/`, `verify/`, `orchestrator/`, and the fixtures. Timothy (`tg/` branches) owns `spec/TaskSpecLoader`, `store/`, the migrations, and `cli/`.
- The shared interfaces (`Workspace`, `Sandbox`, `VerificationStrategy`, `RunStore`, `Producer`, `DiffInspector`) change only in their own PR, reviewed by both people.
- Commit subjects use `TG-n:` / `BH-n:` work-item prefixes. `WORK_TIMOTHY.md` defines the TG items. `BUILD_PLAN.md` refers to a `WORK_BRANDON.md` that is not committed.
- Out of scope for this course: live LLM producers, an auditor agent, hidden evaluator test injection, and a labelled corpus.

Further reading: `DATAFLOW.md` (where every value comes from, the planned Docker flags, reproducibility gaps) and `BUILD_PLAN.md` (milestone scope and the ranked M2 list).
