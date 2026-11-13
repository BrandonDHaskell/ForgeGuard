# ForgeGuard

A local harness that verifies code changes it did not produce. A producer
changes a clean checkout and reports whether it succeeded. That report is
recorded as a claim and never decides anything. The verdict comes only from
running the project's own build and tests, and a run where the two disagree is
the result ForgeGuard exists to show.

CS 601 side project, team Clean Sweep: Brandon Haskell and Timothy George.

## Status: final submission (Milestone 2)

| # | Proposal feature | Status |
| --- | --- | --- |
| 1 | Task model and loader | Done |
| 2 | Workspace management | Done |
| 3 | Producer interface ("agent" in the proposal) | Done |
| 4 | Sandboxed execution | Changed: timeout and process-tree kill only. The Docker sandbox was cut. |
| 5 | Verification strategies: Maven and pytest | Done |
| 6 | Run records in PostgreSQL | Done |
| 7 | CLI: submit, watch, show, stats | Done |
| 8 | Work queue with `SELECT ... FOR UPDATE SKIP LOCKED` and leases | Done |
| 9 | Lease expiry and reclaim | Done |

`PROPOSAL_TRACE.md` maps each feature and both use cases to its class, its
tests and its demo step, and lists every change from the proposal with the
reason.

## Course requirements

| Requirement | Where to look |
| --- | --- |
| Object-oriented design | Every outside dependency sits behind an interface: `WorkspaceProvider`, `Workspace`, `Producer`, `DiffInspector`, `Sandbox`, `VerificationStrategy`, `RunStore`. Data is in records, verdicts and states are enums, and `ForgeGuardCli` is the only class that constructs the real implementations. `MavenStrategy` and `PytestStrategy` are chosen by the `strategy:` field of a task file. `RunStoreContractTest` holds the JDBC store and the in-memory test store to one contract. |
| Systems integration | PostgreSQL 17 through JDBC, with a HikariCP pool and Flyway migrations. It stores the run records and logs, and it is the work queue: claims with `FOR UPDATE SKIP LOCKED`, leases timed by the database clock, reclaim, and writes fenced to the worker that holds the run. The harness also drives git (JGit and the git CLI) and operating system processes. |
| Claude Code | Used as the coding assistant throughout. `CLAUDE.md` is the guide it works from, and assisted commits carry a `Co-Authored-By: Claude` trailer. |
| GitHub workflow | Work is done on `bh/` and `tg/` feature branches. Every change after the initial commit reached `main` through a pull request reviewed by the other team member. CI runs the tests and the demo on each pull request. |

## Demo

The same four scenarios exist for a Maven project and a pytest project. In all
eight, the producer claims success.

| Task | Patch | Verdict |
| --- | --- | --- |
| `correct`, `py-correct` | fixes the seeded defect | `PASS` |
| `overclaim`, `py-overclaim` | fixes it and breaks another behavior | `TEST_FAIL`, flagged as a disagreement |
| `hang`, `py-hang` | adds a loop that never ends | `TIMEOUT` |
| `tamper`, `py-tamper` | deletes the failing test | `PROTECTED_PATH_MODIFIED`, never executed |

`demo/run-all.sh` queues all eight, drains the queue with one worker, and prints
each verdict next to the answer key in `demo/expected.tsv`. It exits non-zero on
any mismatch, and CI runs it on every pull request.

```
TASK           EXPECTED                  ACTUAL                    MATCH
correct        PASS                      PASS                      yes
overclaim      TEST_FAIL                 TEST_FAIL                 yes
tamper         PROTECTED_PATH_MODIFIED   PROTECTED_PATH_MODIFIED   yes
hang           TIMEOUT                   TIMEOUT                   yes
py-correct     PASS                      PASS                      yes
py-overclaim   TEST_FAIL                 TEST_FAIL                 yes
py-tamper      PROTECTED_PATH_MODIFIED   PROTECTED_PATH_MODIFIED   yes
py-hang        TIMEOUT                   TIMEOUT                   yes

all 8 verdicts match the answer key
```

The two `hang` tasks each run until their 60-second timeout, so the script takes
a few minutes. The answer key is kept out of the task files on purpose: the
harness never sees the answer it is graded against.

## How a run works

1. `submit` loads a task file and queues a run as `PENDING`. It verifies nothing.
2. A `worker` claims the oldest waiting run under a lease.
3. The worker clones the task's repository into a new directory and checks out
   the base ref. The commit it resolved to is recorded.
4. The producer makes its change and states its claim. The producer delivered
   here replays a committed patch and a committed self-report, so every machine
   gets the same change.
5. The harness diffs the workspace itself and hashes the diff. The producer's
   own list of touched files is never consulted.
6. A change that touches a protected path, such as the tests or the build file,
   is rejected at this point, before anything executes.
7. The task's strategy runs the build and test command under the timeout and
   classifies the result.
8. The logs are stored, then the verdict.

| Verdict | Meaning |
| --- | --- |
| `PASS` | The build and every test succeeded. |
| `TEST_FAIL` | The suite ran and at least one test failed. |
| `BUILD_FAIL` | The suite could not be built or run. Also the default for any result a strategy cannot classify, including a pytest run that collects no tests. |
| `PATCH_REJECTED` | The producer's patch did not apply. |
| `PROTECTED_PATH_MODIFIED` | The change touched a protected path. Nothing was executed. |
| `TIMEOUT` | The run passed the task's timeout and its process tree was killed. |
| `RESOURCE_EXCEEDED` | Reserved for a memory-limit kill. Only the Docker sandbox could report one, so nothing produces it today. |
| `HARNESS_ERROR` | The harness failed, not the change. It decides nothing and is left out of the disagreement rate. |

## Threat model

The producer is assumed to be wrong, not hostile. Verification runs as a host
process in the workspace directory, with your privileges and network. Two
things are enforced on every run: the task's wall-clock timeout, and killing
the whole process tree when it expires. Memory, CPU, process-count and network
limits in a task file are not enforced. Only run patches you trust.

The Docker sandbox in the proposal was cut. `DockerSandbox` is in the tree but
not wired in, and `PROPOSAL_TRACE.md` explains why.

## Prerequisites

Java 25, Maven 3.9+, git, Docker, and `docker compose`. Docker only runs
PostgreSQL; nothing being verified runs in a container.

The pytest tasks also need Python 3.11+ with pytest. They run
`python -m pytest`, so the `python` on your PATH must have pytest installed. A
project venv works:

```bash
python3 -m venv .venv
.venv/bin/pip install pytest
source .venv/bin/activate
```

## Quick start

```bash
docker compose up -d postgres
./fixtures/build-fixtures.sh
./fixtures/verify-fixtures.sh
mvn -B -f /tmp/forgeguard-fixtures/intervals-java/pom.xml verify -Dmaven.test.failure.ignore=true
mvn -B package
./demo/run-all.sh
```

`build-fixtures.sh` builds both fixture repositories (`intervals-java` and
`intervals-python`) under `/tmp/forgeguard-fixtures` with pinned commit SHAs.
`verify-fixtures.sh` checks the SHAs and that every patch applies.

The `mvn -f` command warms `~/.m2` with the Java fixture's plugins and test
dependencies. The Maven tasks run `mvn -o` (offline), so they give `BUILD_FAIL`
without this step. The fixture baseline has one failing test by design, hence
the ignore flag. The database schema is created on first connect.

To follow one run by hand:

```bash
alias forgeguard='java -jar target/forgeguard-0.1.0-SNAPSHOT.jar'

forgeguard submit tasks/overclaim.yaml   # queues a run, prints its id
forgeguard worker --drain                # verifies queued runs, then exits
forgeguard watch <run-id>                # waits for the verdict
forgeguard show <run-id>                 # the stored evidence and the build log
forgeguard list                          # the 20 newest runs
forgeguard stats                         # verdict counts and the disagreement rate
```

Without `--drain`, a worker keeps polling for new runs until it is stopped.
`watch` prints each change of state and ends on the verdict line:

```
run <run-id>  TEST_FAIL   (producer reported: success)
The producer claimed success. The harness disagrees.
```

## Worker crash and reclaim

This is use case 2 of the proposal. Two integration tests cover it against real
PostgreSQL:

```bash
mvn -B verify -Dit.test=WorkerCrashTest   # SIGKILL a worker process mid-run
mvn -B verify -Dit.test=WorkerJdbcTest    # three workers, twelve runs, each claimed once
```

To see it by hand, use the task files in `crash-demo/`. They are the four Maven
tasks with a 60-second timeout, which lets the workers run with a 76-second
lease, the shortest one a worker accepts for a 60-second ceiling. Queue twelve
runs, start three workers, and kill one while it is verifying a run:

```bash
for i in 1 2 3; do for t in crash-demo/*.yaml; do forgeguard submit "$t"; done; done

# in each of three terminals; each prints its id, worker-<pid>@<host>
FORGEGUARD_MAX_TIMEOUT_SECONDS=60 FORGEGUARD_LEASE_SECONDS=76 forgeguard worker

kill -9 <pid>            # one worker, mid-run
```

The killed worker's run stays `CLAIMED` until its lease expires. The next claim
by a surviving worker then takes it back: the first attempt's results and logs
are cleared, `attempt` goes from 0 to 1, and the run is verified again from a
new checkout. Every write from a worker is limited to runs it still holds, so a
worker that lost its lease cannot overwrite the new result. The surviving
worker's log line shows `attempt 1`, and so does the row:

```bash
docker compose exec postgres psql -U forgeguard -c \
  "SELECT run_id, state, claimed_by, attempt, outcome FROM runs ORDER BY created_at"
```

The lease expires within 76 seconds of the kill, and the next surviving worker
to look for work reclaims the run. With the default 360-second lease, the wait
would be up to six minutes. The tasks in `tasks/` cannot be used with
the short lease: most have a 300-second timeout, and a worker closes any task
over its ceiling as `HARNESS_ERROR` without running it.

## Tests

```bash
mvn -B test     # unit tests; no database or fixtures needed
mvn -B verify   # also the @Tag("integration") tests
```

The integration tests need the PostgreSQL above, and the ones that use real git
and real builds need the fixtures built and `~/.m2` warmed. CI
(`.github/workflows/ci.yml`) builds the fixtures, warms the cache, runs
`mvn -B verify` and then runs the demo, on every pull request and on every push
to `main`.

## Configuration

| Variable | Default |
| --- | --- |
| `FORGEGUARD_JDBC_URL` | `jdbc:postgresql://localhost:5432/forgeguard` |
| `FORGEGUARD_DB_USER` | `forgeguard` |
| `FORGEGUARD_DB_PASSWORD` | `forgeguard` |

Per-run limits come from the `limits` block of each task file, not from the
environment. Only `timeoutSeconds` is enforced. A worker's settings do come from
the environment:

| Variable | Default |
| --- | --- |
| `FORGEGUARD_WORKER_ID` | `worker-<pid>@<host>` |
| `FORGEGUARD_LEASE_SECONDS` | `360` |
| `FORGEGUARD_POLL_SECONDS` | `2` |
| `FORGEGUARD_MAX_TIMEOUT_SECONDS` | `300` |

A worker refuses to start unless its lease is longer than
`FORGEGUARD_MAX_TIMEOUT_SECONDS` plus 15 seconds. It closes any task whose
timeout is over that ceiling as `HARNESS_ERROR` without running it.

## Layout

```
src/main/java/dev/cleansweep/forgeguard/
  spec/          TaskSpec, Limits       declarative input, strictly validated
  workspace/     WorkspaceProvider      clean checkout per attempt
  producer/      Producer               anything that changes a workspace
  orchestrator/  VerificationRun        the ordered path every attempt takes,
                 DiffInspector          and the independent read of the diff
  sandbox/       Sandbox, ExecResult    execution under the timeout
  verify/        VerificationStrategy   build and test contract per project type:
                 MavenStrategy, PytestStrategy
                 ProtectedPathCheck     files a change may not touch
  store/         RunStore, RunRecord    persisted evidence, and the queue
  worker/        Worker, WorkerConfig   claim, verify, repeat; lease settings
  cli/           ForgeGuardCli          submit, worker, watch, show, list, stats
src/main/resources/db/migration/        Flyway migrations (V1 schema, V2 work queue)
tasks/                                  the eight task files graded by the demo
crash-demo/                             60-second copies of the Maven tasks for the reclaim demo
fixtures/                               fixture sources, patches and self-reports
demo/                                   run-all.sh and its answer key
```

## Documentation

| File | Contents |
| --- | --- |
| `PROPOSAL_TRACE.md` | Scope against the proposal: feature, class, tests, demo step, and every change |
| `DATAFLOW.md` | Where each value in a run record comes from |
| `BUILD_PLAN.md` | The Milestone 1 plan, kept as history |
| `CLAUDE.md` | Architecture notes and conventions for Claude Code |
| `CONTEXT/` | Milestone 2 planning notes, and a class guide written at Milestone 1 |

## Ownership

| Area | Owner |
| --- | --- |
| workspace, producer, orchestrator, sandbox, verify, fixtures, demo | Brandon |
| spec loader, store and migrations, queue and lease, worker, cli | Timothy |

Interfaces in the packages above are shared. Changing one is its own PR.

## AI Use

Both team members used Claude Code as their coding assistant. It worked from
`CLAUDE.md`, and the commits it helped with carry a `Co-Authored-By: Claude`
trailer. The main uses were:

| Task | Examples |
| --- | --- |
| Scaffolding | The Milestone 1 skeleton: package layout, interfaces, records, the picocli CLI and the first Flyway migration |
| Planning | A plan for each finish-plan task, written in plan mode before any code. Open decisions went to the developer. |
| Feature work | Finish-plan items such as the pytest strategy, the protected-path check and the worker loop |
| Test generation | The `RunStore` contract tests, the integration tests in throwaway PostgreSQL schemas, and the end-to-end fixture tests |
| Debugging | A CI break caused by a `RunStore` method that each branch compiled without and that failed only once the branches merged. Also fixture SHAs that changed under global commit signing, and library log messages silently dropped by an old SLF4J version. |
| Documentation | `CLAUDE.md`, `PROPOSAL_TRACE.md`, commit messages and pull request descriptions |

**Example: the worker side of the work queue.** Features 8 and 9 and use case 2
were finished with Claude Code in tasks T4 to T6, each on its own branch:

- **T4:** the `Worker` loop and its environment settings, including the rule
  that a worker's lease must outlast the longest task timeout.
- **T5:** `submit` changed to only queue a run. The task also added `watch` and
  `stats`.
- **T6:** `WorkerCrashTest`, which kills a worker process with SIGKILL partway
  through a build. It checks that a second worker reclaims the run and leaves a
  single verdict with `attempt = 1`.

To show the crash test can fail, Claude Code also broke the reclaim on purpose
and confirmed the test caught it. Decisions it was unsure of went to the
developer, such as whether to wait for an open pull request or how to test
without touching the real database schema. The other team member reviewed and
merged each pull request.

## License

CC0 1.0 Universal (public domain dedication). See `LICENSE`.