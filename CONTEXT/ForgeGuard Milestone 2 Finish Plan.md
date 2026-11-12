# ForgeGuard Milestone 2 Finish Plan

&#32;@Brandon

## Summary

Milestone 2 is due Nov 12, 2026, and the repo has no commits since Oct 7, so every Milestone 2 item is still open along with the M1 items carried over.

The plan: build the four Milestone 2 features from the proposal (work queue, multiple workers, lease reclaim, pytest), fix the known correctness bugs, demo against an answer key, and formally cut Docker. That is about 8 hours each for Brandon and Timothy.

If either of you has work that isn't pushed, push it so this plan can be updated.

## Decision: cut Docker

Docker is cut and reported as "designed, not delivered," because `DockerSandbox` doesn't work as written:

- It copies the workspace into `/work`, which is a tmpfs mount, and `docker cp` can't copy into tmpfs.
- Files copied into a container are owned by root, so the container user (1000) can't write `target/`.
- The Maven cache is copied to `/cache`, but Maven is never told to use it, so offline builds fail.

Reword the threat model to match what is actually enforced: a wall-clock timeout and killing the whole process tree. The reasoning is in the last section.

## Brandon: workspace, verification, fixtures

Nine tasks, about 8 hours. Do B1 to B4 first: they make the demo verdicts correct.

| # | Task | Est. | Done when | Status |
| --- | --- | --- | --- | --- |
| B1 | Diff fix: run `git add -A`, then `git diff --cached` | 30m | A new file appears in the touched list and the hash | Not started |
| B2 | Write logs before the verdict | 15m | A failed log write can't overwrite a real verdict | Not started |
| B3 | Wire in `ProtectedPathCheck` between the diff and the sandbox | 45m | `tamper` gives PROTECTED\_PATH\_MODIFIED | Not started |
| B4 | Move `hang.patch`'s infinite loop into `IntervalMerger.java` | 15m | `hang` still gives TIMEOUT (today it edits a test file, so B3 would reject it) | Not started |
| B5 | Add `strategy: maven` or `pytest` to the task file, plus a name-to-strategy lookup | 1h | Adding pytest needs no change to the run logic | Not started |
| B6 | `PytestStrategy`, a Python fixture, and the same 4 patches | 3h | All 4 scenarios give the same verdicts as Maven | Not started |
| B7 | Record only the limits that were actually enforced | 30m | `show` no longer claims network is off or a 2 GB cap | Not started |
| B8 | `demo/run-all.sh` with an answer key, also run in CI (CI needs pytest installed) | 1h | Prints expected, actual and match for all 8 tasks, and fails on any mismatch | Not started |
| B9 | `PROPOSAL_TRACE.md`, README, and remove "pick the top two or three" from `BUILD_PLAN.md` | 1h | Each proposal feature maps to a class, a test and a demo step | Not started |

- **Pytest exit codes (B6):** 0 is PASS, 1 is TEST\_FAIL, anything else is BUILD\_FAIL. Exit 5 ("no tests collected") must never count as PASS.
- **Answer key (B8):** keep it out of the task YAML. The loader rejects unknown fields, and the harness shouldn't grade itself against stored answers.

## Timothy: store, queue, CLI

Six tasks, about 8 hours. T1 and T2 come first: the worker, CLI and tests all build on them.

| # | Task | Est. | Done when | Status |
| --- | --- | --- | --- | --- |
| T1 | V2 migration adding `attempt`, `task_file` and `sandbox` columns | 30m | Applies to both a fresh database and an M1 database | Not started |
| T2 | Claim query (`SKIP LOCKED`) picks up pending runs or claimed runs whose lease expired. Reclaiming clears the verdict, deletes the logs and increments `attempt` | 2h | A reclaimed run keeps nothing from the first attempt | Not started |
| T3 | Every write from a worker is limited to runs it still holds (`WHERE run_id = ? AND claimed_by = ?`) | 45m | A worker that lost its lease can't overwrite the new result | Not started |
| T4 | `worker` loop with lease and poll settings from environment variables; refuses to start unless the lease is longer than the longest timeout plus a margin | 1.5h | Two workers empty the queue with no duplicate claims | Not started |
| T5 | `submit` only queues the run; add `watch` and `stats` (verdict counts and disagreement rate) | 1.5h | Proposal feature 7 is complete | Not started |
| T6 | Concurrency and crash tests | 1.5h | 3 workers and 12 runs, each claimed once; `kill -9` on a worker leaves one verdict with `attempt = 1` | Not started |

Keep PRs small so each of you can review the other's quickly.

## Demo

The demo shows every verdict next to its expected answer, so the instructor can judge correctness without taking our word for it.

1. `run-all.sh`: 8 rows, all matching the answer key.
2. `overclaim` in detail: the producer's claim next to the verdict, the failing test, and the database row.
3. Pytest: the same 4 scenarios, where the only difference in the task file is `strategy:`.
4. Use case 2 live: 3 workers and 12 runs, then kill one worker. Set timeouts to 30s and the lease to 60s so reclaim happens in about a minute.
5. `stats` showing the disagreement rate.
6. Record a backup of the full run tonight.

## Changes from the proposal

Send these to the instructor before the demo, so no difference is first discovered during it.

| Change | Reason |
| --- | --- |
| Docker sandbox cut | Not working; the time went to the proposal's Milestone 2 features |
| "Agent" renamed "Producer" | The harness checks changes from any source, not just agents |
| The separate reaper became part of the claim query | One less process, same recovery behavior |
| Use case 2 rewritten (it still says "broker redelivers") | Left over from RabbitMQ |
| Overview "wrong or hostile" changed to "wrong" | Matches the threat model |
| Added: independent diff hash, protected paths, 8 verdict types, answer-key demo | Each one checks the producer's claim against evidence |

## If we fall behind

Drop in this order:

1. `watch` (use `show` instead)
2. Pytest `hang` and `tamper` (keep `correct` and `overclaim`)
3. The live crash demo (show the recording instead)

Never drop the queue and reclaim, B1 through B4, or the answer-key script. Those answer the instructor's feedback directly.

## Why cut Docker

The queue, reclaim and pytest are the Milestone 2 features the instructor approved, and the feedback was that the results couldn't be verified. A harness that clearly works without containers makes a stronger demo than a container setup that half works.

Docker is the biggest change on the list, which is why it gets reported before the demo instead of discovered during it.

## Sources

- [docker container cp man page](https://man.archlinux.org/man/extra/docker/docker-container-cp.1.en): tmpfs can't be copied into; copied files are owned by root
- [ForgeGuard repository](https://github.com/BrandonDHaskell/ForgeGuard), `main` as of Nov 11 (last commit Oct 7)
- Side Project Proposal v2 (Brandon Haskell and Timothy George)
