# ForgeGuard

A local harness that verifies code changes it did not produce. The producer's
self-report is recorded as evidence of a claim; the exit code of a build-and-test
run is what decides the outcome.

## Status: Milestone 1

The end-to-end path works: task file, clean checkout, scripted patch, independent
diff, Maven run, stored evidence. The four fixture tasks produce these verdicts:

| Task | Patch | Verdict |
| --- | --- | --- |
| `correct` | fixes the seeded defect | `PASS` |
| `overclaim` | breaks another behavior, reports success | `TEST_FAIL`, flagged as a disagreement |
| `hang` | adds an infinite loop | `TIMEOUT` |
| `tamper` | deletes the failing test | `PASS` (see below) |

Known limits in M1, all planned for Milestone 2:

- **No isolation.** The verification command runs as a host process with your
  privileges, network, and `~/.m2`. `DockerSandbox` exists but is not wired in.
  Only run patches you trust.
- **No protected-path rejection.** `tamper` passes because deleting the test is
  not yet detected. `ProtectedPathCheck` exists but is not called.
- **No work queue or lease reclaim.** `submit` runs one attempt synchronously.

CS 601 side project, team Clean Sweep: Brandon Haskell and Timothy George.

## Requirements

Java 25, Maven 3.9+, git, Docker, and `docker compose`. In M1 Docker only runs
PostgreSQL; nothing being verified runs in a container.

## Quick start

```bash
docker compose up -d postgres
./fixtures/build-fixtures.sh
mvn -B -f /tmp/forgeguard-fixtures/intervals-java/pom.xml verify -Dmaven.test.failure.ignore=true
mvn -B package
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar submit tasks/overclaim.yaml
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar show <run-id>
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar list
```

The third command warms `~/.m2` with the fixture's plugins and test dependencies.
The verification command runs `mvn -o` (offline), so it fails with `BUILD_FAIL`
without this step. The fixture baseline has one failing test by design, hence the
ignore flag. The schema is created automatically on first connect.

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
verify/        VerificationStrategy   build and test contract per project type
store/         RunStore, RunRecord    persisted evidence
cli/           ForgeGuardCli          submit, show, list
```

See `BUILD_PLAN.md` for scope and `DATAFLOW.md` for where each value comes from.

## Ownership

| Area | Owner |
| --- | --- |
| workspace, sandbox, verify, fixtures | Brandon |
| spec loader, store, queue and lease, cli | Timothy |

Interfaces in the packages above are shared. Changing one is its own PR.

## License

Apache-2.0. See `LICENSE`.
