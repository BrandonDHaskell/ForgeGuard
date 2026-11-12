# ForgeGuard class guide

A tour of every class in ForgeGuard: what each one does, and the part it plays in the whole.

*Describes `main` at `6e8a408` (October 8, 2026): Milestone 1, with TG-1 to TG-4 and BH-1 to BH-4 merged.*

---

## The idea in one paragraph

A **producer** changes a codebase and reports whether it thinks the change worked. A producer can be a coding agent, a script or a person. ForgeGuard doesn't take the producer's word for it. It checks out a clean copy of the code, lets the producer make its change, looks at what actually changed, and then runs the project's own build and tests. The **exit code of that run decides the verdict.** The producer's claim is still stored, as evidence of what it *said*, in a separate field from the verdict. The cases where the two disagree are what ForgeGuard exists to show:

```
$ forgeguard submit tasks/overclaim.yaml
run 18c4eb2f-…  TEST_FAIL   (producer reported: success)

$ forgeguard show 18c4eb2f-…
claim         success: true
verdict       TEST_FAIL

The producer claimed success. The harness disagrees.
```

A finished run, with all its inputs, environment, observations and decision, is called a **verification record**.

---

## How one run moves through the classes

```
forgeguard submit tasks/overclaim.yaml
        │
        ▼
ForgeGuardCli ──► TaskSpecLoader ──► TaskSpec  (plus the patch and report file paths)
        │
        ▼
VerificationRun.execute — the fixed sequence every attempt follows
   1. RunStore.create .................. new run row, state PENDING
   2. WorkspaceProvider.provision ...... clean checkout of baseRef → resolved base commit
   3. Producer.run ..................... makes its change, returns its claim
   4. DiffInspector.inspect ............ what really changed: paths and a stable hash
   5. (Milestone 2) ProtectedPathCheck . reject edits to tests or the build file
   6. VerificationStrategy.verify ...... runs the build in a Sandbox, picks an Outcome
   7. RunStore.recordVerdict, appendLog  verdict, exit code, duration, logs
        │
        ▼
ForgeGuardCli prints one line: run id, verdict, claim
```

**Where to start reading the code:** `VerificationRun` first. It's short, and it names every other piece. Then read the interfaces it depends on.

---

## Five design ideas that explain the shapes of the classes

1. **An interface at every outside dependency, and one place that wires them up.** Git, process execution, the build system and the database each sit behind an interface (`WorkspaceProvider`, `Sandbox`, `VerificationStrategy`, `RunStore` and others). `ForgeGuardCli` is the only class that picks the real implementations. There is no dependency-injection framework, so you can read the whole dependency graph in one file. It's also why tests can swap in fakes.
2. **Claim and verdict are kept apart.** `ProducerResult` is recorded, and it's never used to decide anything. `Outcome` comes only from the build run. `RunRecord` has a separate field for each.
3. **The evidence is what actually ran.** `ExecResult.command` holds the exact argument list given to the process. `DockerSandbox` calls the `docker` command-line tool instead of a library for this reason: the stored command and the executed command are the same data.
4. **Intent versus evidence.** A task names `baseRef: v1.0`, which is what the author meant. The run records the commit SHA that `v1.0` resolved to on that day, which is what actually happened.
5. **Determinism.** The producer replays a committed patch, the fixture repository is built with pinned dates and identity, and the diff hash ignores machine-specific details. The same task therefore gives the same verdict on both laptops and in CI.

---

## `spec/`: what to verify

These classes turn a YAML task file into a validated, immutable description of the job. Nothing here touches Git, processes or the database.

### `TaskSpec` (record)
**The unit of work:** where the code comes from (`repoUri`, `baseRef`), what changes it (`patchPath`), how to check it (`verifyCommand`, `image`, `limits`), and which files the change must not touch (`protectedPaths`). The constructor rejects missing required fields and makes defensive copies of the lists. Every later stage reads from this record. `baseRef` stays exactly as the author wrote it; the resolved commit is recorded on the run, never here.

### `Limits` (record)
**Resource ceilings for one execution:** timeout, memory, CPUs, process count, and whether the network is allowed. `Limits.DEFAULTS` is 300 seconds, 2 GiB, 2 CPUs, 256 processes, no network. Values must be positive. In M1 only the **timeout** is enforced, by `LocalProcessSandbox`. The rest are recorded on every run now, and `DockerSandbox` will enforce them in M2.

### `TaskSpecLoader` (class)
**Reads a task YAML file into a `TaskSpec`** and returns it as a `LoadedTask` record: the spec plus the absolute paths of the patch and report files. It is **strict on purpose**. Unknown keys, duplicate keys, a string where a list belongs, and wrongly typed limits are all errors that name the field. The reason: a typo like `protectedPath:` would otherwise load as "no protected paths" and quietly switch a safety check off. Patch and report paths resolve against the repository root (the task file's grandparent directory), so the CLI works from any directory. The loader reads nothing except the task file itself.

---

## `workspace/`: a clean place to work

Each attempt gets a fresh, throwaway checkout, so nothing left over from an earlier run can affect the verdict.

### `Workspace` (interface)
**A throwaway checkout:** its directory (`root()`), the commit it was checked out at (`baseCommit()`), and `close()`, which deletes it. It extends `AutoCloseable`, so `VerificationRun` cleans up with try-with-resources even when something fails.

### `WorkspaceProvider` (interface)
**Creates a clean `Workspace` for a `TaskSpec`.** It's the seam that keeps the orchestrator from knowing anything about Git.

### `JGitWorkspaceProvider` (class)
**The real provider.** It clones the task's repository into a new temp directory with JGit (Git as a Java library), checks out `baseRef`, and resolves it to a concrete commit SHA. If anything fails, it deletes the half-made directory and throws `WorkspaceException`. The SHA it returns is the run's `base_commit`, which lets two runs be compared as starting from the same source.

---

## `producer/`: the thing being checked

### `Producer` (interface)
**Anything that changes a workspace and then says how it went:** a scripted patch, a coding agent, or a person. Making this the boundary means a real agent could be added later without changing anything downstream.

### `ProducerResult` (record)
**The producer's claim:** `reportedSuccess` and the raw `report` text (JSON). It's stored as evidence and **never** used to decide the verdict.

### `ScriptedProducer` (class)
**The M1 producer: replays a committed patch and a committed self-report.** It applies the patch with `git apply` and no fuzz. A patch that doesn't apply cleanly throws `PatchRejectedException`, which becomes the verdict `PATCH_REJECTED`: a failure to produce, not a failure to verify. It then reads the report file. If the report can't be read, that's recorded as claiming nothing. Because the patch and report are committed files, every machine gets the same diff and the same claim, so any disagreement comes from the recorded data, not from chance.

---

## `orchestrator/`: the sequence, and an independent look at the change

### `VerificationRun` (class)
**The single ordered path every attempt takes,** shown in the diagram above. It owns the order. Each collaborator owns one step, and this class knows nothing about Maven, Docker or SQL. The order matters:
- The run is saved before any work starts, so a crash still leaves a record.
- Protected paths are checked (M2) **before** anything executes, so a change that edits the tests is rejected without ever running.
- A rejected patch becomes `PATCH_REJECTED`. Any other exception becomes `HARNESS_ERROR` instead of crashing the CLI.

### `DiffInspector` (interface) and its `Diff` record
**Reports what the producer actually changed, regardless of what it says it changed.** `Diff` holds the commit after the producer ran, a SHA-256 of the normalized diff, the list of touched paths, and the diff text. The producer's own `files_touched` list is recorded as part of its claim and never consulted here.

### `GitDiffInspector` (class)
**The real inspector.** It runs `git diff` in the workspace with every option pinned: no color, no external diff tools, no rename detection, a fixed algorithm and context size, fixed path prefixes, and literal non-ASCII paths. User or repository git settings therefore can't change the output. Before hashing, it removes the `index` lines and trailing whitespace, so the same logical change hashes identically on any machine. The hash and the post-change commit are stored on the run. The touched paths are what `ProtectedPathCheck` will check in M2. (The full diff text is computed but not stored yet.)

---

## `sandbox/`: running the build

### `Sandbox` (interface)
**Runs a command in a workspace under limits and reports what happened.** It keeps no state between calls, so the orchestrator never has to ask it "what did you do last?"

### `ExecResult` (record)
**The record of one execution:** exit code, stdout, stderr, how it stopped (`Termination`), how long it took, and the **exact argument list that ran**.

### `Termination` (enum)
**How the process stopped:** `EXITED` normally, `TIMEOUT`, `OOM_KILLED`, or `HARNESS_ERROR` (the harness itself failed, for example because the command couldn't start). This is what separates a test suite that failed from a process that was killed.

### `LocalProcessSandbox` (class)
**The M1 sandbox: runs the command as an ordinary process on the host,** in the workspace directory.
- It enforces the timeout and kills the **whole process tree**, because Maven forks a separate JVM for tests and killing only the direct child would leave that JVM running.
- It reads stdout and stderr on separate virtual threads, so a chatty build can't fill the pipe and deadlock before the timeout fires.
- **It doesn't isolate anything.** The command has your filesystem, your network and your `~/.m2`. That's why M2 replaces it, after which it stays on as a fast test fixture.

### `DockerSandbox` (class), *built, not yet used (M2)*
**Runs the command in a hardened, throwaway container.** The `docker create` flags are the threat model written out as arguments:
- no network, read-only root filesystem, small `tmpfs` for `/work` and `/tmp`
- memory, CPU and process limits from `Limits`
- a non-root user, all Linux capabilities dropped, no privilege escalation

The workspace is **copied** in with `docker cp` rather than mounted, so nothing inside can reach host files. Both timeout and the out-of-memory killer exit with code 137. To tell them apart, it asks `docker inspect` whether the container was `OOMKilled`, then reports `TIMEOUT` or `RESOURCE_EXCEEDED` accordingly.

---

## `verify/`: turning a run into a verdict

### `VerificationStrategy` (interface)
**Knows how to build and test one kind of project, and how to read the result.** Supporting a new build system, such as pytest in M2, means adding an implementation; the orchestrator doesn't change.

### `Verification` (record)
**A verdict plus the execution that produced it** (`Outcome` and `ExecResult`), returned together so the evidence always travels with the decision.

### `MavenStrategy` (class)
**Verifies a Maven project.** It runs the task's `verifyCommand` through the sandbox, then classifies the result:
- How the process stopped is checked first: timeout gives `TIMEOUT`, out of memory gives `RESOURCE_EXCEEDED`, a harness failure gives `HARNESS_ERROR`.
- Exit 0 means `PASS`.
- Maven exits 1 for **both** compile errors and failing tests, so for exit 1 it reads the log:
  - `BUILD FAILURE` together with `Failures:` gives `TEST_FAIL`.
  - `COMPILATION ERROR` or `Could not resolve dependencies` gives `BUILD_FAIL`.
  - Anything unexplained also gives `BUILD_FAIL`, the cautious choice.

This is why raw logs are stored rather than summarized: the evidence has to support the classification.

### `Outcome` (enum)
**The harness's verdict, the result of a run.**

| Value | Meaning | Decided by |
| --- | --- | --- |
| `PASS` | build and tests succeeded | `MavenStrategy` |
| `TEST_FAIL` | it built, but tests failed | `MavenStrategy` |
| `BUILD_FAIL` | it didn't compile, a dependency was missing, or the failure was unexplained | `MavenStrategy` |
| `TIMEOUT` | ran past the time limit and was killed | `MavenStrategy`, from `Termination` |
| `RESOURCE_EXCEEDED` | killed for using too much memory | `MavenStrategy`, from `Termination` |
| `PATCH_REJECTED` | the change didn't apply to the base commit | `VerificationRun` |
| `HARNESS_ERROR` | ForgeGuard itself failed, so nothing was decided | `VerificationRun` |
| `PROTECTED_PATH_MODIFIED` | the change touched files it must not | `ProtectedPathCheck` (M2) |

### `ProtectedPathCheck` (class), *built, not yet used (M2)*
**Rejects a change that edits files the task declared off-limits,** usually `src/test/**` and `pom.xml`. It compiles the task's glob patterns and returns the first touched path that matches. Without it, the simplest attack works: delete the failing test and the suite goes green. That's exactly what the `tamper` fixture does, and why it currently passes. It's meant to run on the diff before anything executes.

---

## `store/`: the evidence, persisted

### `RunStore` (interface)
**The persistence boundary.** It creates a run, records each stage (workspace, producer claim, verdict), appends log output, and reads runs back (`find`, `recent`). Keeping it an interface lets the orchestrator run against PostgreSQL or an in-memory store without any changes.

### `RunRecord` (record)
**One run's stored evidence, as read back:** identity, the commits and diff hash, the command, the claim, the exit code, the verdict, how it stopped, and the timings. `producerReportedSuccess` and `outcome` are separate fields on purpose, because their disagreement is the point.

### `RunState` (enum)
**Where a run is in its life:** `PENDING` (created), `CLAIMED` (reserved for the M2 work queue, when a worker takes a run), `DONE` (verdict recorded).

### `JdbcRunStore` (class)
**The PostgreSQL implementation,** in plain JDBC with no ORM.
- `create` writes the task row (one per `taskId`; resubmitting reuses it) and a new run row, with its limits, in one transaction.
- Every later write must affect **exactly one** row and otherwise fails with `no such run: <id>`, so evidence can't silently go missing.
- Logs are stored in chunks with a per-stream sequence number. `logs()` reads them back for `show`.
- Errors are reported as `StoreException`.

### `Database` (class)
**Connects and prepares the schema.** It reads `FORGEGUARD_JDBC_URL`, `FORGEGUARD_DB_USER` and `FORGEGUARD_DB_PASSWORD` from the environment, and opens one test connection first. An unreachable host or a rejected login then produces a single clear `DatabaseException` instead of a stack trace. After that it builds a small connection pool (HikariCP) and runs the Flyway migrations, so the schema is created on first connect.

**The schema (`V1__init.sql`, not a class):** `tasks` (one row per task spec), `runs` (one row per attempt; it holds the whole verification record), and `run_logs` (stdout and stderr chunks). The lease columns and indexes on `runs` are in place for the M2 work queue.

---

## `cli/`: the front door

### `ForgeGuardCli` (class)
**The program's entry point, and the only place collaborators are wired together.** It's built on picocli and has three subcommands, as nested classes:

| Command | What it prints |
| --- | --- |
| `submit <task.yaml>` | one line: run id, verdict, producer's claim |
| `show <run-id>` | the evidence block, the disagreement line when the producer claimed success but the verdict is a failure (`HARNESS_ERROR` doesn't count, since it decided nothing), and the stdout log |
| `list` | the 20 most recent runs; a run with no verdict yet shows its state |

Each subcommand builds the real collaborators in `run()` and passes them to a static method that does the work and the printing. Tests call those same methods with fakes. Input problems print as one line, `forgeguard: <message>`, with exit code 1. That covers a missing or malformed task file, an unknown run id, and an unreachable database. Unexpected errors still show a full stack trace.

---

## Test support: the stand-ins

These four live under `src/test/` and never ship. They exist so each half of the system can be tested without the other half's infrastructure.

### `RunStoreContractTest` (abstract test)
**The behaviour every `RunStore` must have.** Each store implementation runs it by subclassing. It runs against `JdbcRunStore` (live PostgreSQL) and `InMemoryRunStore`, and when both pass, the two are interchangeable.

### `InMemoryRunStore`
**A `RunStore` kept in memory,** so the whole pipeline can run with no database.

### `FakeWorkspace`
**A temporary directory standing in for a checkout,** at a fixed commit, with no Git involved.

### `CannedSandbox`
**A sandbox that returns recorded Maven output instead of running anything.** It has one factory per outcome: `passing`, `failingTests`, `compileError`, `timedOut`, `oomKilled`. The output is real Maven text, so the real `MavenStrategy` classifies it.

With these, `ForgeGuardCliTest` runs a full `submit` and checks the printed `TEST_FAIL` with **no Git, Docker, Maven or PostgreSQL**.

### The test classes

| Test | Checks | Needs |
| --- | --- | --- |
| `TaskSpecLoaderTest` | each task file loads; every malformed case is rejected with the field named | nothing |
| `MavenStrategyTest` | log and exit-code classification | nothing |
| `LocalProcessSandboxTest` | exit codes, timeouts, killing forked children, large output without deadlock | nothing |
| `DatabaseTest` | clear connection errors, no credentials echoed | nothing |
| `InMemoryRunStoreTest` | the store contract, in memory | nothing |
| `ForgeGuardCliTest` | every sandbox verdict through `submit`; `show` and `list` output; one-line errors | nothing |
| `JdbcRunStoreTest` | the store contract plus SQL-only checks | PostgreSQL |
| `ForgeGuardCliIntegrationTest` | `show` and `list` through the real command line | PostgreSQL |
| `JGitWorkspaceProviderTest` | checkout at the recorded commit, cleanup, bad refs | fixture repo |
| `ScriptedProducerTest` | patches apply, claims are read, corrupt patches are rejected | fixture repo |
| `GitDiffInspectorTest` | touched paths, and a hash that's stable across workspaces and git configs | fixture repo |

`mvn test` runs the first six. `mvn verify` runs all of them; CI provides PostgreSQL and builds the fixture repo.

---

## Built but not yet wired in

Two classes exist and are complete, but `VerificationRun` doesn't call them yet. Both are planned for Milestone 2:

| Class | What it adds | Visible effect today |
| --- | --- | --- |
| `ProtectedPathCheck` | rejects changes to tests or the build file before execution | the `tamper` task (deletes the failing test) **passes** |
| `DockerSandbox` | real isolation: no network, resource limits, no host access | the build runs on the host with your privileges; only run patches you trust |

---

## Quick reference

| Class | Package | Kind | In one line |
| --- | --- | --- | --- |
| `TaskSpec` | spec | record | what to verify and how |
| `Limits` | spec | record | resource ceilings for one execution |
| `TaskSpecLoader` | spec | class | strict YAML → `TaskSpec` |
| `Workspace` | workspace | interface | a throwaway checkout |
| `WorkspaceProvider` | workspace | interface | makes a clean `Workspace` |
| `JGitWorkspaceProvider` | workspace | class | clones and checks out with JGit |
| `Producer` | producer | interface | changes the code, reports a claim |
| `ProducerResult` | producer | record | the claim, never the verdict |
| `ScriptedProducer` | producer | class | replays a committed patch and report |
| `VerificationRun` | orchestrator | class | the fixed order every attempt follows |
| `DiffInspector` | orchestrator | interface | what really changed |
| `GitDiffInspector` | orchestrator | class | pinned, normalized, hashed `git diff` |
| `Sandbox` | sandbox | interface | runs a command under limits |
| `ExecResult` | sandbox | record | what ran and what happened |
| `Termination` | sandbox | enum | how the process stopped |
| `LocalProcessSandbox` | sandbox | class | host process with timeout (M1) |
| `DockerSandbox` | sandbox | class | hardened container (M2, not yet used) |
| `VerificationStrategy` | verify | interface | build-system-specific build and test |
| `Verification` | verify | record | verdict plus its evidence |
| `MavenStrategy` | verify | class | runs Maven, classifies the result |
| `Outcome` | verify | enum | the verdict |
| `ProtectedPathCheck` | verify | class | forbids edits to protected files (M2, not yet used) |
| `RunStore` | store | interface | the persistence boundary |
| `RunRecord` | store | record | one run's stored evidence |
| `RunState` | store | enum | PENDING, CLAIMED, DONE |
| `JdbcRunStore` | store | class | PostgreSQL implementation |
| `Database` | store | class | connection, pool, migrations |
| `ForgeGuardCli` | cli | class | entry point and the only wiring |

For who owns which package, see the Ownership table in `ForgeGuard/README.md`. For where every stored value comes from, see `ForgeGuard/DATAFLOW.md`.
