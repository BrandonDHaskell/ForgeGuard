package dev.cleansweep.forgeguard.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Builds the connection pool and applies migrations. Configuration is environment only. */
public final class Database {

    public static DataSource connect() {
        String url = env("FORGEGUARD_JDBC_URL", "jdbc:postgresql://localhost:5432/forgeguard");
        String user = env("FORGEGUARD_DB_USER", "forgeguard");
        String pass = env("FORGEGUARD_DB_PASSWORD", "forgeguard");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(8);
        HikariDataSource ds = new HikariDataSource(cfg);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return ds;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private Database() {
    }
}
