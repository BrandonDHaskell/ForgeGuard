# Work package: Timothy

**Branch prefix:** `tg/`
**You own:** task input, persistence, and the command line.

You never touch `workspace/`, `sandbox/`, `producer/`, or `verify/`. Use
`FakeWorkspace` and `CannedSandbox` from the test sources to exercise every
code path without Git, Docker, or Maven.

---

## TG-1: schema and migration

Branch `tg/schema`.

Files: `src/main/resources/db/migration/V1__init.sql`, `store/Database.java`

Done when:
- `docker compose up -d postgres` then running any CLI command applies the
  migration with no error
- `\d runs` in psql shows all columns, and `runs_pending_idx` and `runs_lease_idx`
  exist
- running it a second time is a no-op (Flyway records the version)
- `FORGEGUARD_JDBC_URL` pointing at a bad host fails with a clear message rather
  than a stack trace about null pointers

Do not add tables for the work queue yet. `claimed_by`, `claimed_at`, and
`lease_expires_at` are already in `V1` and stay unused until M2.

---

## TG-2: TaskSpecLoader

Branch `tg/loader`. Independent, start here if Postgres is being difficult.

Done when:
- all four files in `tasks/` load without error
- `verifyCommand` parses as a list, not a string: `["mvn","-B","-o","verify"]`
- omitting `limits` yields `Limits.DEFAULTS`
- a missing `taskId` throws `IllegalArgumentException` naming the field
- `patchPath` and `reportPath` resolve relative to the repository root, not the
  current working directory (run the CLI from `/tmp` to prove it)

---

## TG-3: JdbcRunStore

Branch `tg/store`. Depends on TG-1.

`RunStoreContractTest` is already written and already passes against
`InMemoryRunStore`. Add the JDBC binding:

```java
@Tag("integration")
public final class JdbcRunStoreTest extends RunStoreContractTest {
    private final RunStore store = new JdbcRunStore(Database.connect());
    @Override protected RunStore store() { return store; }
}
```

Done when that subclass passes against a live PostgreSQL. If it passes, your
implementation is interchangeable with the in-memory one and Brandon can depend
on it without reading it.

Also verify by hand:
- submitting the same task file twice creates two runs and one task row
- `producer_report` round-trips as valid JSON
- `appendLog` called twice stores `seq` 0 and 1, and `logs()` returns them in order

---

## TG-4: CLI

Branch `tg/cli`. Depends on TG-2 and TG-3.

`ForgeGuardCli` is written. Make the three subcommands work:

- `submit <task.yaml>` prints one line: run id, verdict, claim
- `show <run-id>` prints the evidence block and the stdout log
- `list` prints the recent runs table

Done when:
- `forgeguard show <unknown-uuid>` prints a clear error, exit code non-zero
- `forgeguard submit missing.yaml` prints a clear error, exit code non-zero
- `show` on an `overclaim` run prints the disagreement line
- a wiring test builds `VerificationRun` with `FakeWorkspace.provider()` and
  `CannedSandbox.failingTests()` and asserts the printed output contains
  `TEST_FAIL`, with no Git, Docker, or Maven involved

---

## Order and timing

| Day | Work |
| --- | --- |
| Oct 3 | TG-2, TG-1 |
| Oct 4 | TG-3 |
| Oct 5 | TG-4, integrate with Brandon's execution half |
| Oct 6 | Demo script, README, buffer |

Open each PR when its "done when" list passes. Do not batch them.
