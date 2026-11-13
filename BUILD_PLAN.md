# Milestone 1 build plan

**Due October 7.** Everything below is written and verified end to end on 2026-10-07:
unit and integration tests pass, and the four fixture tasks produce the verdicts
in the table under "Verify before you trust the demo".

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
| 1 | Task loader | `TaskSpecLoader` | verified |
| 2 | Workspace | `JGitWorkspaceProvider` | verified |
| 3 | Producer | `ScriptedProducer` | verified |
| 4 | Sandbox | `LocalProcessSandbox` | verified |
| 5 | Strategy | `MavenStrategy` | verified |
| 6 | Diff | `GitDiffInspector` | verified |
| 7 | Store | `JdbcRunStore`, `Database`, `V1__init.sql` | verified |
| 8 | CLI | `ForgeGuardCli` (submit, show, list) | verified |
| 9 | Orchestrator | `VerificationRun` | verified |

## What is NOT in M1

Docker, remote Git, protected-path rejection, the work queue, lease reclaim,
pytest, coverage, hunks, metrics, GitHub anything, live agents.

`DockerSandbox` and `ProtectedPathCheck` are in the tree as M2 targets. Neither
is wired into the M1 path.

## Milestone 2 scope

Decided, not ranked: the proposal's Milestone 2 features, plus the fixes that
make the demo verdicts correct.

1. **Work queue** with `SELECT ... FOR UPDATE SKIP LOCKED` and leases, and
   several workers drawing from it.
2. **Lease expiry and reclaim**, folded into the claim query rather than a
   separate reaper.
3. **`PytestStrategy`** with a Python fixture and the same four scenarios.
4. **`ProtectedPathCheck`** wired in before the sandbox, so `tamper` is rejected.

**`DockerSandbox` is cut** and reported as designed, not delivered. As written it
copies into a tmpfs (which `docker cp` cannot do), leaves root-owned files the
container user cannot write, and never points Maven at the copied cache.
`PROPOSAL_TRACE.md` maps each feature to its class, test and demo step, and
lists every change from the proposal.

## Known gaps, named

- **`LocalProcessSandbox` does not isolate.** It enforces a timeout and kills the
  process tree. It does not bound memory, filesystem, or network. With Docker
  cut in M2, this is the final threat model: process-level containment only.
- **Dependency cache.** The host `~/.m2` is used, so it only mattered for
  `--network none`, which left with Docker.
- **Reclaim.** When a lease expires, the claim transaction must null every
  verdict column and delete that run's `run_logs` rows, or a record ends up with
  one worker's exit code and another's logs. Done in T2, and T3 fences a stale
  worker's writes.

## Split

Timothy's task list with acceptance criteria is in `WORK_TIMOTHY.md`. Brandon's
was tracked outside the repository. The Milestone 2 task lists for both are in
`CONTEXT/ForgeGuard Milestone 2 Finish Plan.md`. Summary:

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
| `hang` | adds `while (true)` | `TIMEOUT` |

`tamper` passing in M1 is the honest state of the project and worth saying out
loud: without the protected-path check, deleting the failing test defeats the
harness. That is the argument for building it in M2.

Out of scope for CS 601 entirely, however good the ideas are: live LLM producers,
an auditor agent, hidden evaluator test injection, a labelled corpus of 12 to 20
cases, and reproducibility measured as an experimental result. The instructor
removed the agentic requirement, and adding an LLM to the producer would destroy
the determinism that makes this demo work on two machines and in CI. These belong
to the master's project, not to this one.
