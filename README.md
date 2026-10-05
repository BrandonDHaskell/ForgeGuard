# ForgeGuard

A local harness that verifies code changes it did not produce. The producer's
self-report is recorded as evidence of a claim; the exit code of an isolated
build-and-test run is what decides the outcome.

CS 601 side project, team Clean Sweep: Brandon Haskell and Timothy George.

## Requirements

Java 25, Maven 3.9+, Docker, and `docker compose`.

## Quick start

```bash
docker compose up -d postgres
./fixtures/build-fixtures.sh
mvn -B package
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar submit tasks/overclaim.yaml
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar show <run-id>
```

## Configuration

| Variable | Default |
| --- | --- |
| `FORGEGUARD_JDBC_URL` | `jdbc:postgresql://localhost:5432/forgeguard` |
| `FORGEGUARD_DB_USER` | `forgeguard` |
| `FORGEGUARD_DB_PASSWORD` | `forgeguard` |
| `FORGEGUARD_TASK_TIMEOUT_SECONDS` | `300` |
| `FORGEGUARD_LEASE_SECONDS` | `420` |
| `FORGEGUARD_POLL_INTERVAL_SECONDS` | `5` |

Lease duration must exceed the task timeout. The worker asserts this at startup.

## Layout

```
spec/        TaskSpec, Limits         declarative input
workspace/   WorkspaceProvider        clean checkout per attempt
producer/    Producer                 anything that mutates a workspace
sandbox/     Sandbox, ExecResult      isolated execution under limits
verify/      VerificationStrategy     build and test contract per project type
store/       RunStore, RunRecord      persisted evidence
cli/         ForgeGuardCli            submit, show, watch, summary
```

## Ownership

| Area | Owner |
| --- | --- |
| workspace, sandbox, verify, fixtures | Brandon |
| spec loader, store, queue and lease, cli | Timothy |

Interfaces in the packages above are shared. Changing one is its own PR.

## License

Apache-2.0. See `LICENSE`.
