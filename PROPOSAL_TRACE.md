# Proposal trace

Maps every feature in the Side Project Proposal v2 to the class that implements
it, the test that checks it, and the demo step that shows it. Changes from the
proposal are listed after the trace, with the reason for each.

Status is as of the Milestone 2 demo. "Pending" names the task in the Milestone 2
finish plan (B for Brandon, T for Timothy) that delivers it.

## Required features

### Core harness

| # | Feature | Status | Class | Tests | Demo |
| --- | --- | --- | --- | --- | --- |
| 1 | Task model and loader | Done | `TaskSpecLoader`, `TaskSpec`, `Limits` | `TaskSpecLoaderTest` (unknown keys, wrong types and missing fields are errors, never defaults) | 1, 3 |
| 2 | Workspace management | Done | `JGitWorkspaceProvider` | `JGitWorkspaceProviderTest.provisionsTheFixtureAtTheRecordedCommit`, `concurrentProvisionsGetDifferentDirectories`, `closeRemovesTheDirectory` | 1 |
| 3 | Agent interface (renamed Producer) | Done | `Producer`, `ScriptedProducer` | `ScriptedProducerTest`, `VerificationRunEndToEndTest.overclaimIsCaughtDespiteTheProducerClaimingSuccess` | 2 |
| 4 | Sandboxed execution | Changed: process-level only, Docker cut | `LocalProcessSandbox` | `LocalProcessSandboxTest.killsCommandThatExceedsTimeout`, `timeoutKillsForkedChildrenNotJustTheDirectChild`, `outputLargerThanThePipeBufferDoesNotDeadlock` | 1 (`hang`) |
| 5 | Verification strategy, Maven then pytest | Done | `VerificationStrategy`, `MavenStrategy`, `PytestStrategy`, `VerificationStrategies` | `MavenStrategyTest`, `PytestStrategyTest.noTestsCollectedIsNeverPass`, `VerificationStrategiesTest`, `VerificationRunStrategyTest` | 3 |
| 6 | Run records in PostgreSQL | Done | `JdbcRunStore`, `Database`, `V1__init.sql` | `RunStoreContractTest`, `JdbcRunStoreTest`, `DatabaseTest` | 2 |
| 7 | CLI: submit, watch, show, summarize with disagreement rate | Done. `submit` only queues; `watch` waits for the verdict; `stats` summarizes | `ForgeGuardCli`, `RunStore.counts` | `ForgeGuardCliTest.failingTestsGiveTestFailAndWatchAndShowPrintTheDisagreement`, `watchPrintsEachChangeOfStateUntilTheVerdict`, `statsCountsVerdictsAndTheDisagreementRate`, `ForgeGuardCliIntegrationTest.submitQueuesTheRunForAWorker`, `RunStoreContractTest.countsGroupEveryRunByStateVerdictAndClaim` | 2, 5 |

### Distributed execution

| # | Feature | Status | Class | Tests | Demo |
| --- | --- | --- | --- | --- | --- |
| 8 | Work queue with `SELECT ... FOR UPDATE SKIP LOCKED` and leases | Done | `RunStore.claim`, `JdbcRunStore`, `Worker`, `V2__work_queue.sql` | `RunStoreContractTest` (claim and lease tests), `WorkerJdbcTest.threeWorkersAndTwelveRunsEachClaimedOnce` | 4 |
| 9 | Lease expiry and reclaim, idempotent re-execution | Done. Reclaim is part of the claim query, with no separate reaper | `RunStore.claim`, `RunStore.fencedTo`, `LeaseLostException` | `WorkerCrashTest.killDashNineMidRunLeavesOneVerdictWithAttemptOne`, `JdbcRunStoreTest.reclaimNullsEveryPerAttemptColumn`, `RunStoreContractTest.lostLeaseCannotOverwriteTheNewResult` | 4 |

### Milestone 2 scope

The proposal's Milestone 2 is features 8 and 9, a multi-worker configuration,
and the pytest strategy with a Python fixture.

| Item | Status | Where |
| --- | --- | --- |
| Database-backed work queue | Done | Feature 8 |
| Multi-worker configuration | Done | `worker` command; lease and poll settings from the environment (`WorkerConfig`) |
| Lease expiry and reclaim | Done | Feature 9 |
| pytest strategy | Done | `PytestStrategy` |
| Python fixture repository | Done | `fixtures/intervals-python`, patches `py-*.patch` |

## Use cases

| Use case | Status | Demo |
| --- | --- | --- |
| 1: Verifying a produced change | Done, without the container or the 2 GB cap. The 5 minute timeout is enforced. | 2 (`overclaim` in detail) |
| 2: Recovering from a worker crash | Done, rewritten below: there is no broker. `WorkerCrashTest` kills a worker process with SIGKILL mid-run | 4 (live crash with 3 workers and 12 runs) |

## Added beyond the proposal

Each of these checks the producer's claim against evidence the harness gathers
itself.

| Addition | Class | Tests | Demo |
| --- | --- | --- | --- |
| Independent diff and hash. The producer's own list of touched files is never consulted. | `GitDiffInspector` | `GitDiffInspectorTest.gitConfigDoesNotChangeTheHash`, `newFileIsTouchedAndChangesTheHash`, `committedChangeIsStillSeen` | 2 |
| Protected paths. A change that edits the tests or build files is rejected before anything runs. | `ProtectedPathCheck`, `VerificationRun` | `VerificationRunTest.protectedPathIsRejectedWithoutExecuting`, `VerificationRunEndToEndTest.tamperIsRejectedWithoutRunning` | 1 (`tamper`) |
| Eight verdict types instead of pass or fail | `Outcome` | `MavenStrategyTest`, `PytestStrategyTest` | 1 |
| Answer-key demo. Every verdict is printed next to its expected answer and CI fails on a mismatch. | `demo/run-all.sh`, `demo/expected.tsv` | Run in CI | 1 |

## Demo steps

1. `demo/run-all.sh`: all 8 tasks (4 Maven, 4 pytest), each verdict next to the
   answer key, all matching.
2. `overclaim` in detail: the producer's claim next to the verdict, the failing
   test, and the database row.
3. pytest: the same 4 scenarios. The only difference in the task file is
   `strategy:`.
4. Use case 2 live: 3 workers, 12 runs, one worker killed. Timeouts are 30s and
   the lease is 60s, so reclaim happens in about a minute.
5. `stats`: verdict counts and the disagreement rate.

## Changes from the proposal

| Change | Reason |
| --- | --- |
| Docker sandbox cut, reported as designed but not delivered | `DockerSandbox` does not work as written (see below). The time went to the proposal's Milestone 2 features. |
| Threat model narrowed to what is enforced | See [Threat model as built](#threat-model-as-built). |
| "Agent" renamed "Producer" | The harness checks changes from any source, not only agents. |
| The separate reaper became part of the claim query | One less process, same recovery behavior. |
| Use case 2 rewritten | The proposal still says "the broker redelivers the run", left over from RabbitMQ. |
| Overview "wrong or hostile" changed to "wrong" | Matches the threat model, which already says the producer is incompetent, not adversarial. |
| Added: independent diff hash, protected paths, 8 verdict types, answer-key demo | Each one checks the producer's claim against evidence. |

### Why Docker was cut

`DockerSandbox` is in the tree and is not wired into any path. As written it
cannot work:

- It copies the workspace into `/work`, which is a tmpfs mount, and `docker cp`
  cannot copy into a tmpfs.
- Files copied into a container are owned by root, so the container user (1000)
  cannot write `target/`.
- The Maven cache is copied to `/cache`, but Maven is never told to use it, so
  offline builds fail.

The queue, reclaim and pytest are the Milestone 2 features the proposal commits
to, and the review feedback was that results could not be verified. A harness
that clearly works without containers is a stronger result than a container
setup that half works.

### Threat model as built

The producer is assumed to be wrong, not hostile. Verification runs as a host
process in the workspace directory, with the user's privileges and network.

What is enforced on every run:

- a wall-clock timeout from the task file, and
- killing the whole process tree when it expires, including test JVMs that Maven
  forks.

What is not enforced, although the task file can state it: memory and CPU caps,
a process-count limit, disabled networking, a read-only filesystem, and running
as a non-root user. Only run patches you trust.

### Use case 2 as built

A developer submits a batch of twelve tasks. Each `submit` only queues a run.
Workers claim pending runs with `SELECT ... FOR UPDATE SKIP LOCKED` and write a
lease expiry onto each claimed run. One worker is killed mid-verification. When
its lease expires, the claim query also picks up runs whose lease has expired, so
a second worker reclaims the run: the claim clears the old verdict, deletes the
old logs and increments `attempt`, then the second worker provisions a new
workspace and verifies from the beginning. Every write from a worker is limited
to runs it still holds, so the dead worker cannot overwrite the new result. The
batch completes with all twelve results recorded, and the affected run shows a
single authoritative outcome with `attempt = 1`.
