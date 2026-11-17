package com.docs0;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The real Postgres test database without a Spring context, for tests of a single
 * package (repositories, handlers) that should not depend on the whole app wiring.
 * Uses the same database and migrations as {@link IntegrationTest}.
 */
public final class TestDatabase {

    private static JdbcClient client;

    private TestDatabase() {}

    /** A JdbcClient on the migrated test database. Migrations run once per JVM. */
    public static synchronized JdbcClient jdbc() {
        if (client == null) {
            String url = System.getenv().getOrDefault("DOCS0_TEST_DATABASE_URL",
                    "jdbc:postgresql://localhost:5432/docs0_test");
            DataSource ds = new DriverManagerDataSource(url, System.getProperty("user.name"), "");
            Flyway.configure().dataSource(ds).load().migrate();
            client = JdbcClient.create(ds);
        }
        return client;
    }

    /** Empties every table and returns the id of a fresh tenant. */
    public static long resetWithTenant() {
        JdbcClient jdbc = jdbc();
        jdbc.sql("TRUNCATE interaction, conversation, sync_run, document, source, tenant RESTART IDENTITY CASCADE")
                .update();
        return jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('smalltech', 'smallTech') RETURNING id")
                .query(Long.class).single();
    }
}
