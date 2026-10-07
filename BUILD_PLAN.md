# Milestone 1 build plan

**Due October 7.** Everything below is written or stubbed in this repository.

## The one demo

```
$ forgeguard submit tasks/overclaim.yaml
run 7c2e...  TEST_FAIL   (producer reported: success)

$ forgeguard show 7c2e...
base commit   7f8561742a0edcf981fcb73942688403a57e31b6
command       mvn -B verify
exit code     1
claim         success: true
verdict       TEST_FAIL

The producer claimed success. The harness disagrees.
```

## What is in M1

| # | Component | Class | Status |
| --- | --- | --- | --- |
| 1 | Task loader | `TaskSpecLoader` | written |
| 2 | Workspace | `JGitWorkspaceProvider` | written |
| 3 | Producer | `ScriptedProducer` | written |
| 4 | Sandbox | `LocalProcessSandbox` | written |
| 5 | Strategy | `MavenStrategy` | written |
| 6 | Diff | `GitDiffInspector` | written |
| 7 | Store | `JdbcRunStore`, `Database`, `V1__init.sql` | written |
| 8 | CLI | `ForgeGuardCli` (submit, show, list) | written |
| 9 | Orchestrator | `VerificationRun` | written |

## What is NOT in M1

Docker, remote Git, protected-path rejection, the work queue, lease reclaim,
pytest, coverage, hunks, metrics, GitHub anything, live agents.

`DockerSandbox` and `ProtectedPathCheck` are in the tree as M2 targets. Neither
is wired into the M1 path.

## Milestone 2: ranked, pick the top two or three

1. **`ProtectedPathCheck`.** Cheapest and most important. Without it, deleting the
   failing test defeats the harness. Also the clearest demonstration that
   verification is more than running tests, since tampering, network access, and
   resource exhaustion are not expressible as unit tests.
2. **`DockerSandbox`.** Makes the isolation claim in the proposal true. Carries
   two sub-tasks: seeding a `.m2` cache (required once `--network none` is on) and
   pinning the image by digest into `runs.image_digest`.
3. **Work queue.** `SELECT ... FOR UPDATE SKIP LOCKED`, leases, reclaim. This is
   the most substantial use of PostgreSQL in the project and it is in the accepted
   proposal, so cutting it is a third scope revision.
4. **`PytestStrategy`.** Proves the abstraction is not Maven-specific. Cheap, but
   it demonstrates a design property rather than adding a capability.

All four is how the scope grew the first time.

## Known gaps, named

- **`LocalProcessSandbox` does not isolate.** It enforces a timeout and kills the
  process tree. It does not bound memory, filesystem, or network. The isolation
  claim belongs to `DockerSandbox` in M2, and the proposal should say M1 is
  process-level containment only.
- **Dependency cache.** Irrelevant in M1, since the host `~/.m2` is used. Becomes
  a blocker the moment `--network none` appears. Seeding it is an M2 task.
- **Reclaim is not designed.** When a lease expires, the claim transaction must
  null every verdict column and delete that run's `run_logs` rows, or a record
  ends up with one worker's exit code and another's logs. M2.

## Split

Full task lists with acceptance criteria are in `WORK_BRANDON.md` and
`WORK_TIMOTHY.md`. Summary:

| Owner | Files | Branch prefix |
| --- | --- | --- |
| Brandon | `workspace/`, `sandbox/`, `producer/`, `verify/`, `orchestrator/`, fixtures | `bh/` |
| Timothy | `spec/TaskSpecLoader`, `store/`, `V1__init.sql`, `cli/` | `tg/` |

Shared interfaces (`Workspace`, `Sandbox`, `VerificationStrategy`, `RunStore`,
`Producer`, `DiffInspector`) change only by their own PR, reviewed by both.

**Neither half blocks the other.** Test doubles are in
`src/test/java/.../support/`:

- `InMemoryRunStore` lets Brandon run end to end with no PostgreSQL
- `FakeWorkspace` and `CannedSandbox` let Timothy exercise every verdict path
  with no Git, Docker, or Maven

`RunStoreContractTest` defines the behaviour both stores must have. It passes
against `InMemoryRunStore` today; Timothy subclasses it with `JdbcRunStore`.
When both pass, the halves are interchangeable and neither of you has to read
the other's implementation to trust it.

## Running it

```bash
docker compose up -d postgres
./fixtures/build-fixtures.sh
mvn -B package
java -jar target/forgeguard-0.1.0-SNAPSHOT.jar submit tasks/overclaim.yaml
```

Postgres runs in a container. Nothing being verified does, in M1.

## Verify before you trust the demo

The four fixture patches produce four different verdicts by construction:

| Task | Patch | Verdict |
| --- | --- | --- |
| `correct` | `<` becomes `<=` | `PASS` |
| `overclaim` | also drops `Math.max` in `Interval.extend` | `TEST_FAIL` |
| `tamper` | deletes the failing test | `PASS` in M1, `PROTECTED_PATH_MODIFIED` in M2 |

Out of scope for CS 601 entirely, however good the ideas are: live LLM producers,
an auditor agent, hidden evaluator test injection, a labelled corpus of 12 to 20
cases, and reproducibility measured as an experimental result. The instructor
removed the agentic requirement, and adding an LLM to the producer would destroy
the determinism that makes this demo work on two machines and in CI. These belong
to the master's project, not to this one.
| `hang` | adds `while (true)` | `TIMEOUT` |

`tamper` passing in M1 is the honest state of the project and worth saying out
loud: without the protected-path check, deleting the failing test defeats the
harness. That is the argument for building it in M2.
