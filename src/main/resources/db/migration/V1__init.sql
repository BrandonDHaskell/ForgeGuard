-- ForgeGuard initial schema.
-- Tasks are declarative inputs. Runs are attempts, and carry the evidence.

CREATE TABLE tasks (
    task_id         TEXT PRIMARY KEY,
    spec_json       JSONB       NOT NULL,
    spec_sha256     TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TYPE run_state AS ENUM ('PENDING', 'CLAIMED', 'DONE');

CREATE TABLE runs (
    run_id           UUID PRIMARY KEY,
    task_id          TEXT      NOT NULL REFERENCES tasks(task_id),
    state            run_state NOT NULL DEFAULT 'PENDING',

    -- work queue and lease (features 8 and 9)
    claimed_by       TEXT,
    claimed_at       TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,

    -- what was actually run
    base_commit      TEXT,
    head_commit      TEXT,
    diff_sha256      TEXT,
    image_digest     TEXT,
    verify_command   TEXT,

    -- limits in force at execution time
    network_enabled  BOOLEAN,
    mem_limit_bytes  BIGINT,
    cpu_limit        NUMERIC,
    pids_limit       INTEGER,
    timeout_seconds  INTEGER,

    -- the claim, and the finding
    producer_reported_success BOOLEAN,
    producer_report           TEXT,
    exit_code                 INTEGER,
    outcome                   TEXT,
    termination_reason        TEXT,

    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    duration_ms      BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The claim query scans this; keep it narrow.
CREATE INDEX runs_pending_idx ON runs (created_at) WHERE state = 'PENDING';
CREATE INDEX runs_lease_idx   ON runs (lease_expires_at) WHERE state = 'CLAIMED';

CREATE TABLE run_logs (
    run_id   UUID    NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
    stream   TEXT    NOT NULL CHECK (stream IN ('stdout', 'stderr')),
    seq      INTEGER NOT NULL,
    content  TEXT    NOT NULL,
    PRIMARY KEY (run_id, stream, seq)
);
