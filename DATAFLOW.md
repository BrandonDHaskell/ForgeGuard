# ForgeGuard data flow

Where every byte comes from, in order, with the real values from the demo task.

## 0. Where the data originates

Nothing is fetched at run time. Three inputs are committed to this repository
and materialized by `fixtures/build-fixtures.sh`:

| Input | Path | Produced by |
| --- | --- | --- |
| Fixture source | `fixtures/intervals-java/`, `fixtures/intervals-python/` | Written by hand, committed |
| Candidate change | `fixtures/patches/<name>.patch` | `git diff` against the fixture |
| Producer's claim | `fixtures/patches/<name>.report.json` | Written by hand, committed |

`build-fixtures.sh` copies each fixture source into `/tmp/forgeguard-fixtures/<name>`,
runs `git init`, commits with pinned identity and dates and no signature, and
tags `v1.0`. Because author, committer, and both dates are fixed, each commit SHA
is identical on every machine. The expected values are in
`fixtures/BASE_SHA.<name>.txt`:

```
intervals-java    7f8561742a0edcf981fcb73942688403a57e31b6
intervals-python  b3a99d1619958e2757594748e47f9af2393b3027
```

The Python fixture has the same seeded defect and the same four patches, named
`py-*.patch`. This document follows the Java task; a pytest task differs only in
its `strategy`, its verify command (`python -m pytest -q`), and its protected
paths. `PytestStrategy` maps exit 0 to `PASS`, 1 to `TEST_FAIL`, and anything
else, including 5 (no tests collected), to `BUILD_FAIL`.

That SHA makes two run records **source**-comparable. It does not make them
execution-comparable; see section 8.

## 1. The seeded defect

`IntervalMerger.merge` uses a strict inequality:

```java
if (cur.start() < last.end()) {
```

Adjacent intervals `[1,3]` and `[3,5]` therefore stay separate. Six of the seven
tests pass on the baseline. `mergesAdjacentIntervals` fails. That single failing
test is the task.

## 2. The four recorded changes

| Task | Patch does | Claim | Expected verdict |
| --- | --- | --- | --- |
| `correct` | `<` becomes `<=` | success: true | `PASS` |
| `overclaim` | `<` becomes `<=`, and `Interval.extend` drops its `Math.max` | success: true | `TEST_FAIL` |
| `tamper` | Deletes `mergesAdjacentIntervals` | success: true | `PROTECTED_PATH_MODIFIED` (was `PASS` in M1, before the check was wired) |
| `hang` | Adds a sort shortcut to `IntervalMerger` whose loop never increments | success: true | `TIMEOUT` |

`overclaim` is the demo that matters. It genuinely fixes the adjacency bug, so
the originally failing test now passes, but the simplified `extend` breaks
`mergesContainedInterval`: merging `[1,10]` with `[2,3]` yields `[1,3]` instead
of `[1,10]`. The producer claims success and is wrong about a test it never
looked at.

## 3. The sequence, per attempt

```
submit tasks/overclaim.yaml
  |
  v
TaskSpecLoader              YAML -> TaskSpec            (no I/O beyond the file)
  |
  v
RunStore.enqueue            INSERT tasks, INSERT runs (state=PENDING,
  |                         task_file='<repo>/tasks/overclaim.yaml')
  |                         -> run_id = 7c2e...  submit prints it and exits
  v
worker: RunStore.claim      UPDATE runs SET state='CLAIMED', claimed_by='w1',
  |                         lease_expires_at=now()+lease  (FOR UPDATE SKIP LOCKED)
  |                         every write below goes through store.fencedTo(claim)
  v
TaskSpecLoader              the worker loads task_file again
  |
  v
WorkspaceProvider.provision JGit clone file:///tmp/... ; checkout v1.0
  |                         -> /tmp/fg-ws-7c2e/ , baseCommit=7f856174...
  v
RunStore.recordWorkspace    UPDATE runs SET base_commit='7f856174...'
  |
  v
Producer.run                git apply --whitespace=nowarn overclaim.patch
  |                         read overclaim.report.json
  |                         -> ProducerResult(reportedSuccess=true, report={...})
  |                         patch will not apply -> PATCH_REJECTED, stop here
  v
DiffInspector.inspect       git diff -> unified diff, sha256, touched paths
  |                         -> ["src/main/java/demo/IntervalMerger.java",
  |                             "src/main/java/demo/Interval.java"]
  v
RunStore.recordProducerResult
  |                         UPDATE runs SET producer_reported_success=true,
  |                                         producer_report=..., diff_sha256=...
  v
ProtectedPathCheck          globs: src/test/**, pom.xml
  |                         no match -> continue
  |                         match -> stderr log names the path,
  |                                  PROTECTED_PATH_MODIFIED, stop before executing
  v
MavenStrategy.verify        Sandbox.exec(ws, ["mvn","-B","-o","verify"], limits)
  |
  v
LocalProcessSandbox         host process in the workspace dir; timeout kills the tree
  |                         -> ExecResult(exitCode=1, stdout=..., EXITED, 41830ms)
  v
MavenStrategy.classify      exit 1 + "BUILD FAILURE" + "Failures:" -> TEST_FAIL
  |
  v
RunStore.appendLog          INSERT run_logs (stdout), INSERT run_logs (stderr)
  v
RunStore.recordVerdict      UPDATE runs SET outcome='TEST_FAIL', exit_code=1,
  |                                         termination_reason='EXITED', ...
  v
Workspace.close             rm -rf /tmp/fg-ws-7c2e
```

The protected-path check runs **before** the sandbox. A change that edits the
tests is rejected without ever executing, so tampering cannot reach the sandbox
or produce a misleading green log.

Logs are written before the verdict, so a run that has a verdict always has its
logs.

A worker takes the same path from `WorkspaceProvider.provision` down. It calls
`VerificationRun.execute(runId, spec, producer)` with a run it has claimed, so
`RunStore.create` is skipped and every write goes through the store fenced to
that claim. Two outcomes differ from `submit`:

- an unknown `strategy` is recorded as `HARNESS_ERROR`, with the reason as the
  stderr log, because the run already exists and would otherwise stay claimable
- a write refused because the lease was lost propagates out of `execute` rather
  than being swallowed, so the worker can abandon the attempt and leave the run
  to whoever reclaimed it

## 4. The container command (designed, not delivered)

`DockerSandbox` was cut from Milestone 2 (see `PROPOSAL_TRACE.md`). This is the
command it was designed to run, kept as the record of the intended isolation.
Nothing below is in the execution path today; `runs.verify_command` holds the
bare argv that `LocalProcessSandbox` ran.

```
docker create --name forgeguard-1759... \
  --network none \
  --user 1000:1000 \
  --read-only \
  --tmpfs /work:rw,exec,size=1g \
  --tmpfs /tmp:rw,size=256m \
  --memory 2147483648 --memory-swap 2147483648 \
  --cpus 2.0 \
  --pids-limit 256 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --workdir /work \
  --stop-timeout 300 \
  maven:3.9-eclipse-temurin-21 \
  timeout --signal=KILL 300s mvn -B -o verify
```

As designed, this argument list would be stored verbatim in
`runs.verify_command`, so the evidence and the thing that ran would be the same
data. That is why the design calls the docker CLI rather than a client library.

Two notes on the flags. `--network none` plus `mvn -o` means dependencies come
from a cache copied in at `/cache`; a change introducing a new dependency fails
as `BUILD_FAIL`, which is the correct verdict rather than a limitation. And
`timeout --signal=KILL` inside the container exits 137, the same code the OOM
killer produces, so `DockerSandbox` asks `docker inspect` for `.State.OOMKilled`
to tell `TIMEOUT` from `RESOURCE_EXCEEDED`.

## 5. Exit code to verdict

| Signal | Verdict |
| --- | --- |
| `git apply` non-zero | `PATCH_REJECTED` |
| Protected glob matched | `PROTECTED_PATH_MODIFIED` |
| exit 0 | `PASS` |
| exit 1, log has `BUILD FAILURE` and `Failures:` | `TEST_FAIL` |
| exit 1, log has `COMPILATION ERROR` or `Could not resolve dependencies` | `BUILD_FAIL` |
| exit 137, `OOMKilled=true` | `RESOURCE_EXCEEDED` |
| exit 137, `OOMKilled=false` | `TIMEOUT` |
| anything else | `BUILD_FAIL` (conservative) |

Maven exits 1 for both a compile error and a failing test, so the exit code alone
is not enough and the log must be read. This is why raw output is persisted
rather than summarized: the stored evidence has to support the classification.

## 6. The row that is the point

After the `overclaim` run:

```sql
SELECT task_id, producer_reported_success, outcome, exit_code
FROM runs WHERE run_id = '7c2e...';

     task_id          | producer_reported_success | outcome    | exit_code
----------------------+---------------------------+------------+-----------
 intervals-overclaim  | t                         | TEST_FAIL  |         1
```

The disagreement query across a task set:

```sql
SELECT task_id, outcome, count(*)
FROM runs
WHERE producer_reported_success AND outcome <> 'PASS'
GROUP BY task_id, outcome;
```

## 7. Where real agent output enters

The `ScriptedProducer` replays a committed patch and a committed report. To use
real output, run a coding agent against the fixture once, save `git diff` as the
patch and its structured response as the report, then commit both. The task file
and every downstream step are unchanged, because the producer boundary is the
only thing that knows the difference.

That is also the seam for a future `ReplayProducer` reading captured sessions,
and a `SubprocessProducer` invoking an agent live. Neither is in scope before
November 6.

## 8. Source reproducibility is not execution reproducibility

The pinned commit SHA guarantees that two runs started from the same source. It
guarantees nothing about what executed against that source.

`maven:3.9-eclipse-temurin-21` is a mutable tag. It can resolve to different
bytes next month, with a different JDK patch level and a different Maven build.
Two runs with identical `base_commit` and identical `diff_sha256` can therefore
produce different verdicts, and nothing currently recorded would explain why.

The fix is to resolve the tag to a digest once and run by digest:

```
$ docker inspect -f '{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-21
maven@sha256:8f0f...
```

`runs.image_digest` exists in the schema for this and is currently unpopulated.
Wiring it belongs with `DockerSandbox`, because there is no image to pin until
containers are in the path, so it left Milestone 2 with the Docker cut.

What a fully reproducible record would pin:

| Field | Status |
| --- | --- |
| task spec hash | recorded (`tasks.spec_sha256`) |
| base commit | recorded |
| diff sha256 | recorded |
| verify command argv | recorded |
| resource limits | timeout recorded; memory, CPU and pids NULL because nothing enforces them (requested values are in `tasks.spec_json`) |
| network policy | NULL: not enforced; the verification run has the host's network |
| container image digest | column exists, not populated (Docker cut) |
| JDK and Maven versions | not recorded; implied by the image digest once pinned |
| dependency cache identity | not recorded (only matters with `--network none`, cut with Docker) |
| ForgeGuard version | not recorded |

Stating this distinction is more useful than closing every gap. A verification
record that silently implies reproducibility it does not have is worse than one
that names what it pins and what it does not.

## 9. Vocabulary

A completed row in `runs` plus its `run_logs` is a **verification record**: the
inputs, the environment, the observations, and the decision, together, for one
attempt. The system produces verification records. It is not a test runner that
happens to store output.

| Part | Fields |
| --- | --- |
| Identity | `run_id`, `task_id`, `attempt`, `created_at` |
| Inputs | `task_file`, `tasks.spec_sha256`, `base_commit`, `head_commit`, `diff_sha256`, `producer_report` |
| Environment | `sandbox`, `verify_command`, `image_digest`, `network_enabled`, `mem_limit_bytes`, `cpu_limit`, `pids_limit`, `timeout_seconds` |
| Observations | `exit_code`, `termination_reason`, `duration_ms`, `run_logs` |
| Decision | `producer_reported_success`, `outcome` |

The last row is the one that distinguishes this from CI. Everything above it,
a continuous integration system also records.
