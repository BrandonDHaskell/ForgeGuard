package dev.cleansweep.forgeguard.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A throwaway PostgreSQL schema, migrated with the real migrations, so an
 * integration test never reads or writes the developer's public schema and
 * always starts from a known state. Connects through the same environment
 * variables as the CLI.
 *
 * <p>Flyway gets its own non-pooled connection source: it changes search_path
 * while migrating, and that must not leak into a pooled connection. The pool
 * handed to the code under test pins search_path to this schema instead.
 */
final class ScratchSchema implements AutoCloseable {

    final String name = "fg_test_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());

    private HikariDataSource pool;

    /** Applies every migration. */
    MigrateResult migrate() {
        return migrate(null);
    }

    /** Applies migrations up to and including target, or all of them if target is null. */
    MigrateResult migrate(String target) {
        var cfg = Flyway.configure()
                .dataSource(Database.url(), Database.user(), Database.password())
                .schemas(name)
                .locations(Database.MIGRATIONS);
        if (target != null) {
            cfg.target(target);
        }
        return cfg.load().migrate();
    }

    /** A pool whose connections all resolve unqualified names in this schema. Built on first use. */
    synchronized DataSource dataSource() {
        if (pool == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(Database.url());
            cfg.setUsername(Database.user());
            cfg.setPassword(Database.password());
            cfg.setSchema(name);
            cfg.setMaximumPoolSize(8);
            pool = new HikariDataSource(cfg);
        }
        return pool;
    }

    /** Empties every table, so the next test starts with no runs. */
    void truncate() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE tasks, runs, run_logs");
        }
    }

    /** Closes the pool and drops the schema. flyway.clean() is disabled by default, and would only drop it. */
    @Override
    public synchronized void close() throws SQLException {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        try (Connection c = DriverManager.getConnection(Database.url(), Database.user(), Database.password());
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
        }
    }
}
