package dev.cleansweep.forgeguard.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cleansweep.forgeguard.producer.ProducerResult;
import dev.cleansweep.forgeguard.sandbox.ExecResult;
import dev.cleansweep.forgeguard.sandbox.Termination;
import dev.cleansweep.forgeguard.spec.TaskSpec;
import dev.cleansweep.forgeguard.verify.Outcome;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation of the persistence boundary. Plain JDBC, no ORM. */
public final class JdbcRunStore implements RunStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource ds;

    /** Null for the plain store. Otherwise every write must still match this claim's worker and attempt. */
    private final Claim fence;

    public JdbcRunStore(DataSource ds) {
        this(ds, null);
    }

    private JdbcRunStore(DataSource ds, Claim fence) {
        this.ds = ds;
        this.fence = fence;
    }

    /** Shares this store's pool. The fence is checked in each write's own statement, not up front. */
    @Override
    public RunStore fencedTo(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim required");
        }
        return new JdbcRunStore(ds, claim);
    }

    @Override
    public UUID create(TaskSpec spec) {
        return insert(spec, null);
    }

    @Override
    public UUID enqueue(TaskSpec spec, Path taskFile) {
        if (taskFile == null || !taskFile.isAbsolute()) {
            throw new IllegalArgumentException("task file must be an absolute path: " + taskFile);
        }
        return insert(spec, taskFile);
    }

    /** taskFile is null for a run this process executes itself, which no worker may claim. */
    private UUID insert(TaskSpec spec, Path taskFile) {
        UUID runId = UUID.randomUUID();
        String specJson;
        try {
            specJson = JSON.writeValueAsString(spec);
        } catch (Exception e) {
            throw new StoreException("cannot serialize task spec", e);
        }

        // One task row per taskId, many runs. Resubmitting a task reuses its row
        // instead of failing on the primary key, and replaces the stored spec with
        // the one just submitted.
        String upsertTask = """
            INSERT INTO tasks (task_id, spec_json, spec_sha256)
            VALUES (?, ?::jsonb, ?)
            ON CONFLICT (task_id) DO UPDATE SET spec_json = EXCLUDED.spec_json,
                                                spec_sha256 = EXCLUDED.spec_sha256
            """;
        String insertRun = """
            INSERT INTO runs (run_id, task_id, state, verify_command, network_enabled,
                              mem_limit_bytes, cpu_limit, pids_limit, timeout_seconds, task_file)
            VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement t = c.prepareStatement(upsertTask)) {
                t.setString(1, spec.taskId());
                t.setString(2, specJson);
                t.setString(3, sha256(specJson));
                t.executeUpdate();
            }
            try (PreparedStatement r = c.prepareStatement(insertRun)) {
                r.setObject(1, runId);
                r.setString(2, spec.taskId());
                r.setString(3, String.join(" ", spec.verifyCommand()));
                r.setBoolean(4, spec.limits().networkEnabled());
                r.setLong(5, spec.limits().memoryBytes());
                r.setDouble(6, spec.limits().cpus());
                r.setInt(7, spec.limits().pidsLimit());
                r.setInt(8, spec.limits().timeoutSeconds());
                r.setString(9, taskFile == null ? null : taskFile.toString());
                r.executeUpdate();
            }
            c.commit();
            return runId;
        } catch (SQLException e) {
            throw new StoreException("cannot create run", e);
        }
    }

    /**
     * Picks the run and resets it in one UPDATE. SKIP LOCKED lets concurrent
     * workers each take a different row instead of queueing on the same one.
     * SET expressions read the row as it was before the update, so the attempt
     * increment sees the old state. Lease times use the database clock, so
     * workers on different machines agree on when a lease has expired.
     *
     * <p>Every per-attempt column is cleared on every claim. A first claim has
     * nothing to clear; a reclaim must not keep a dead worker's evidence. The
     * logs are deleted in the same transaction, so no reader ever sees one
     * attempt's verdict columns beside another attempt's logs.
     */
    private static final String CLAIM = """
            UPDATE runs SET
                attempt = attempt + CASE WHEN state = 'CLAIMED' THEN 1 ELSE 0 END,
                state = 'CLAIMED', claimed_by = ?, claimed_at = now(),
                lease_expires_at = now() + make_interval(secs => ?),
                base_commit = NULL, head_commit = NULL, diff_sha256 = NULL,
                image_digest = NULL, sandbox = NULL,
                producer_reported_success = NULL, producer_report = NULL,
                exit_code = NULL, outcome = NULL, termination_reason = NULL,
                started_at = NULL, finished_at = NULL, duration_ms = NULL
            WHERE run_id = (
                SELECT run_id FROM runs
                WHERE task_file IS NOT NULL
                  AND (state = 'PENDING' OR (state = 'CLAIMED' AND lease_expires_at <= now()))
                ORDER BY created_at, run_id
                LIMIT 1
                FOR UPDATE SKIP LOCKED)
            RETURNING run_id, task_file, attempt, lease_expires_at
            """;

    @Override
    public Optional<Claim> claim(String workerId, Duration lease) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("worker id required");
        }
        if (lease == null || lease.isNegative()) {
            throw new IllegalArgumentException("lease must not be negative: " + lease);
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                Claim claim = null;
                try (PreparedStatement ps = c.prepareStatement(CLAIM)) {
                    ps.setString(1, workerId);
                    ps.setDouble(2, lease.toMillis() / 1000.0);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            claim = new Claim(rs.getObject("run_id", UUID.class), workerId,
                                    Path.of(rs.getString("task_file")),
                                    rs.getInt("attempt"),
                                    ts(rs, "lease_expires_at"));
                        }
                    }
                }
                if (claim != null) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM run_logs WHERE run_id = ?")) {
                        ps.setObject(1, claim.runId());
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return Optional.ofNullable(claim);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("cannot claim a run: " + e.getMessage(), e);
        }
    }

    @Override
    public void recordWorkspace(UUID runId, String baseCommit) {
        update(runId, "UPDATE runs SET base_commit = ?, started_at = now() WHERE run_id = ?",
                ps -> {
                    ps.setString(1, baseCommit);
                    ps.setObject(2, runId);
                    return 2;
                });
    }

    @Override
    public void recordProducerResult(UUID runId, ProducerResult result,
                                     String headCommit, String diffSha256) {
        update(runId, """
                UPDATE runs SET producer_reported_success = ?, producer_report = ?,
                                head_commit = ?, diff_sha256 = ?
                WHERE run_id = ?
                """,
                ps -> {
                    ps.setBoolean(1, result.reportedSuccess());
                    ps.setString(2, result.report());
                    ps.setString(3, headCommit);
                    ps.setString(4, diffSha256);
                    ps.setObject(5, runId);
                    return 5;
                });
    }

    @Override
    public void recordVerdict(UUID runId, Outcome outcome, ExecResult exec) {
        update(runId, """
                UPDATE runs SET state = 'DONE', outcome = ?, exit_code = ?,
                                termination_reason = ?, duration_ms = ?, finished_at = now()
                WHERE run_id = ?
                """,
                ps -> {
                    ps.setString(1, outcome.name());
                    ps.setInt(2, exec.exitCode());
                    ps.setString(3, exec.termination().name());
                    ps.setLong(4, exec.durationMs());
                    ps.setObject(5, runId);
                    return 5;
                });
    }

    /**
     * An INSERT into run_logs does not lock the runs row, so on its own a
     * worker that lost its lease could slip a log in between a reclaim's UPDATE
     * and its DELETE of the old logs, and that log would survive into the new
     * attempt. The FOR SHARE lock closes that gap. While it is held, a claim's
     * FOR UPDATE SKIP LOCKED passes the run by. If a reclaim got there first,
     * this waits for it to commit, then re-checks the fence and finds the run
     * gone.
     */
    @Override
    public void appendLog(UUID runId, String stream, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        checkFencedRun(runId);
        String lock = "SELECT 1 FROM runs WHERE run_id = ?" + (fence == null ? "" : FENCE) + " FOR SHARE";
        String insert = """
                INSERT INTO run_logs (run_id, stream, seq, content)
                VALUES (?, ?, COALESCE((SELECT max(seq) + 1 FROM run_logs
                                        WHERE run_id = ? AND stream = ?), 0), ?)
                """;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(lock)) {
                    ps.setObject(1, runId);
                    bindFence(ps, 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            throw missing(runId);
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(insert)) {
                    ps.setObject(1, runId);
                    ps.setString(2, stream);
                    ps.setObject(3, runId);
                    ps.setString(4, stream);
                    ps.setString(5, content);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("cannot write run " + runId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<RunRecord> find(UUID runId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM runs WHERE run_id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("cannot read run " + runId, e);
        }
    }

    @Override
    public List<RunRecord> recent(int limit) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM runs ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<RunRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new StoreException("cannot list runs", e);
        }
    }

    @Override
    public String logs(UUID runId, String stream) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content FROM run_logs WHERE run_id = ? AND stream = ? ORDER BY seq")) {
            ps.setObject(1, runId);
            ps.setString(2, stream);
            StringBuilder sb = new StringBuilder();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getString(1));
                }
            }
            return sb.toString();
        } catch (SQLException e) {
            throw new StoreException("cannot read logs for " + runId, e);
        }
    }

    private static RunRecord map(ResultSet rs) throws SQLException {
        return new RunRecord(
                rs.getObject("run_id", UUID.class),
                rs.getString("task_id"),
                RunState.valueOf(rs.getString("state")),
                rs.getString("base_commit"),
                rs.getString("head_commit"),
                rs.getString("diff_sha256"),
                rs.getString("image_digest"),
                rs.getString("verify_command"),
                rs.getBoolean("producer_reported_success"),
                rs.getString("producer_report"),
                (Integer) rs.getObject("exit_code"),
                rs.getString("outcome") == null ? null : Outcome.valueOf(rs.getString("outcome")),
                rs.getString("termination_reason") == null
                        ? null : Termination.valueOf(rs.getString("termination_reason")),
                ts(rs, "started_at"), ts(rs, "finished_at"),
                (Long) rs.getObject("duration_ms"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    /** Binds a statement's own parameters and returns how many it bound. */
    private interface Binder {
        int bind(PreparedStatement ps) throws SQLException;
    }

    /** Appended to a write's WHERE run_id = ? when this store is fenced. */
    private static final String FENCE = " AND claimed_by = ? AND attempt = ?";

    /**
     * Every write targets exactly one existing run. An UPDATE that matches no row
     * succeeds in SQL, so it is checked here; otherwise evidence recorded against a
     * wrong id would vanish without an error.
     *
     * <p>When fenced, the fence is part of the UPDATE's own WHERE clause. The
     * UPDATE locks the row and re-checks that clause against the latest version,
     * so a write racing a reclaim either lands before it (and the reclaim then
     * clears it) or matches no row after it.
     */
    private void update(UUID runId, String sql, Binder binder) {
        checkFencedRun(runId);
        String fenced = fence == null ? sql : sql.stripTrailing() + FENCE;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(fenced)) {
            bindFence(ps, binder.bind(ps));
            if (ps.executeUpdate() == 0) {
                throw missing(runId);
            }
        } catch (SQLException e) {
            throw new StoreException("cannot write run " + runId + ": " + e.getMessage(), e);
        }
    }

    /** A fenced store holds one run and writes to no other. */
    private void checkFencedRun(UUID runId) {
        if (fence != null && !fence.runId().equals(runId)) {
            throw new IllegalArgumentException("this store is fenced to run " + fence.runId()
                    + " and cannot write run " + runId);
        }
    }

    /** Binds the fence after the statement's own parameters, if this store is fenced. */
    private void bindFence(PreparedStatement ps, int bound) throws SQLException {
        if (fence != null) {
            ps.setString(bound + 1, fence.workerId());
            ps.setInt(bound + 2, fence.attempt());
        }
    }

    /** No row matched. For a fenced store the run existed when claimed, so the lease was lost. */
    private RuntimeException missing(UUID runId) {
        return fence != null
                ? new LeaseLostException(fence)
                : new StoreException("no such run: " + runId, null);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
