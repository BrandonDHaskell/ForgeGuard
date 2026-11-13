# ForgeGuard

A local harness that verifies code changes it did not produce. The producer's
self-report is recorded as evidence of a claim; the exit code of a build-and-test
run is what decides the outcome.

## Status: Milestone 2

Every task runs end to end: task file, clean checkout, scripted patch,
independent diff, protected-path check, build and test run, stored evidence.
The same four scenarios exist for a Maven project and a pytest project:

| Task | Patch | Verdict |
| --- | --- | --- |
| `correct`, `py-correct` | fixes the seeded defect | `PASS` |
| `overclaim`, `py-overclaim` | breaks another behavior, reports success | `TEST_FAIL`, flagged as a disagreement |
| `hang`, `py-hang` | adds a loop that never ends | `TIMEOUT` |
| `tamper`, `py-tamper` | deletes the failing test | `PROTECTED_PATH_MODIFIED`, never executed |

`demo/run-all.sh` runs all eight and checks each verdict against an answer key.
`PROPOSAL_TRACE.md` maps every proposal feature to its class, test and demo step,
and lists every change from the proposal.

## Threat model

The producer is assumed to be wrong, not hostile. Verification runs as a host
process in the workspace directory, with your privileges and network. Two
things are enforced on every run: the task's wall-clock timeout, and killing
the whole process tree when it expires. Memory, CPU, process-count and network
limits in a task file are not enforced. Only run patches you trust.

The Docker sandbox in the proposal was cut; `DockerSandbox` is in the tree but
not wired in. `PROPOSAL_TRACE.md` explains why.

CS 601 side project, team Clean Sweep: Brandon Haskell and Timothy George.

## Requirements

Java 25, Maven 3.9+, git, Docker, and `docker compose`. Docker only runs
PostgreSQL; nothing being verified runs in a container.

The pytest tasks also need Python 3.11+ with pytest, run as `python -m pytest`,
so `python` on your PATH must have pytest installed. A project venv works:

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
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar submit tasks/overclaim.yaml
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar show <run-id>
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar list
```

`build-fixtures.sh` builds both fixture repos (`intervals-java` and
`intervals-python`) with pinned commit SHAs, and `verify-fixtures.sh` checks the
SHAs and that every patch applies.

The `mvn -f` command warms `~/.m2` with the fixture's plugins and test
dependencies. The verification command runs `mvn -o` (offline), so it fails with
`BUILD_FAIL` without this step. The fixture baseline has one failing test by
design, hence the ignore flag. The schema is created automatically on first
connect.

## Tests

```bash
mvn -B test     # unit tests; no database needed
mvn -B verify   # also the @Tag("integration") tests, against the PostgreSQL above
```

## Configuration

| Variable | Default |
| --- | --- |
| `FORGEGUARD_JDBC_URL` | `jdbc:postgresql://localhost:5432/forgeguard` |
| `FORGEGUARD_DB_USER` | `forgeguard` |
| `FORGEGUARD_DB_PASSWORD` | `forgeguard` |

Per-run limits (timeout, memory, CPUs, pids, network) come from the `limits`
block of each task file, not from the environment. Lease and polling settings
arrive with the work queue in Milestone 2.

## Layout

```
spec/          TaskSpec, Limits       declarative input
workspace/     WorkspaceProvider      clean checkout per attempt
producer/      Producer               anything that mutates a workspace
orchestrator/  VerificationRun        the ordered path every attempt takes,
               DiffInspector          and the independent read of the diff
sandbox/       Sandbox, ExecResult    execution under limits
verify/        VerificationStrategy   build and test contract per project type:
               MavenStrategy, PytestStrategy, looked up by the task's strategy:
               ProtectedPathCheck     files a change may not touch
store/         RunStore, RunRecord    persisted evidence
cli/           ForgeGuardCli          submit, show, list
```

See `PROPOSAL_TRACE.md` for scope against the proposal, `BUILD_PLAN.md` for the
Milestone 1 plan, and `DATAFLOW.md` for where each value comes from.

## Ownership

| Area | Owner |
| --- | --- |
| workspace, sandbox, verify, fixtures | Brandon |
| spec loader, store, queue and lease, cli | Timothy |

Interfaces in the packages above are shared. Changing one is its own PR.

## License

Apache-2.0. See `LICENSE`.
