package dev.cleansweep.forgeguard.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Builds the connection pool and applies migrations. Configuration is environment only. */
public final class Database {

    public static DataSource connect() {
        return connect(
                env("FORGEGUARD_JDBC_URL", "jdbc:postgresql://localhost:5432/forgeguard"),
                env("FORGEGUARD_DB_USER", "forgeguard"),
                env("FORGEGUARD_DB_PASSWORD", "forgeguard"));
    }

    static DataSource connect(String url, String user, String pass) {
        probe(url, user, pass);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(8);
        HikariDataSource ds = new HikariDataSource(cfg);

        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        } catch (FlywayException e) {
            ds.close();
            throw new DatabaseException("cannot migrate schema at " + redact(url) + ": " + e.getMessage(), e);
        }
        return ds;
    }

    /**
     * Opens one plain connection before the pool exists. An unreachable host or a
     * rejected login then surfaces as the driver's own message, instead of a pool
     * initialization failure that Hikari also logs with a full stack trace.
     */
    private static void probe(String url, String user, String pass) {
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            // reachable, and the credentials were accepted
        } catch (SQLException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            // The driver's message alone can be as vague as "The connection attempt failed."
            String reason = root == e
                    ? e.getMessage()
                    : e.getMessage() + " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
            // A network-level cause means nothing answered at that address.
            String hint = root instanceof IOException
                    ? "\nIs PostgreSQL running? Start it with: docker compose up -d postgres"
                    : "";
            throw new DatabaseException("cannot connect to " + redact(url) + ": " + reason + hint, e);
        }
    }

    /** Drops the query string, which is where a JDBC URL carries credentials. */
    private static String redact(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private Database() {
    }

    /** The database is unreachable, rejected the login, or could not be migrated. */
    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
