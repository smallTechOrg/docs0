package com.docs0.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

/** Creates and drops uniquely named scratch databases on the local Postgres that the other tests use. */
class DatabaseBootstrapTest {

    private static final String SERVER = System.getenv().getOrDefault("DOCS0_TEST_DATABASE_URL",
            "jdbc:postgresql://localhost:5432/docs0_test").replaceFirst("/[^/?]*(\\?.*)?$", "/");
    private static final String USER = System.getProperty("user.name");

    private static String scratchName() {
        return "docs0_bootstrap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean exists(String name) throws Exception {
        try (Connection c = DriverManager.getConnection(SERVER + "postgres", USER, "");
                var ps = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, name);
            return ps.executeQuery().next();
        }
    }

    private static void drop(String name) throws Exception {
        try (Connection c = DriverManager.getConnection(SERVER + "postgres", USER, "");
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS \"" + name + "\"");
        }
    }

    @Test
    void createsTheDatabaseOnceAndThenFindsIt() throws Exception {
        String name = scratchName();
        try {
            assertThat(exists(name)).isFalse();
            assertThat(DatabaseBootstrap.ensureDatabase(SERVER + name, USER, "")).isEqualTo(DatabaseBootstrap.Outcome.CREATED);
            assertThat(exists(name)).isTrue();
            assertThat(DatabaseBootstrap.ensureDatabase(SERVER + name + "?sslmode=prefer", USER, ""))
                    .isEqualTo(DatabaseBootstrap.Outcome.ALREADY_EXISTS);
        } finally {
            drop(name);
        }
    }

    @Test
    void theRoleOwnsTheNewDatabase() throws Exception {
        String name = scratchName();
        try {
            DatabaseBootstrap.ensureDatabase(SERVER + name, USER, "");
            try (Connection c = DriverManager.getConnection(SERVER + "postgres", USER, "");
                    var ps = c.prepareStatement(
                            "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = ?")) {
                ps.setString(1, name);
                var rs = ps.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(USER);
            }
        } finally {
            drop(name);
        }
    }

    @Test
    void rejectsNamesThatAreNotPlainIdentifiers() {
        for (String bad : new String[] {"Docs0", "docs0;drop", "0docs", "a-b", "", "docs0\"x",
                "a".repeat(64)}) {
            assertThatThrownBy(() -> DatabaseBootstrap.databaseName("jdbc:postgresql://h:5432/" + bad))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(DatabaseBootstrap.databaseName("jdbc:postgresql://h:5432/docs0?sslmode=require")).isEqualTo("docs0");
        assertThat(DatabaseBootstrap.databaseName("jdbc:postgresql://h/" + "a".repeat(63))).hasSize(63);
        assertThatThrownBy(() -> DatabaseBootstrap.databaseName("mysql://h/docs0"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectsToThePostgresDatabaseKeepingTheParameters() {
        assertThat(DatabaseBootstrap.maintenanceUrl("jdbc:postgresql://10.0.0.7:5432/docs0?sslmode=require"))
                .isEqualTo("jdbc:postgresql://10.0.0.7:5432/postgres?sslmode=require");
        assertThat(DatabaseBootstrap.maintenanceUrl("jdbc:postgresql://h/docs0"))
                .isEqualTo("jdbc:postgresql://h/postgres");
    }

    @Test
    void failureNamesTheCreatedbPrivilegeAndLeaksNoPassword() {
        String secret = "s3cret-pw-" + UUID.randomUUID();
        assertThatThrownBy(() -> DatabaseBootstrap.ensureDatabase(
                        SERVER + scratchName() + "?sslmode=disable", "no_such_role_" + scratchName(), secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATEDB")
                .hasMessageContaining("database bootstrap failed")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
    }

    @Test
    void thePostProcessorDoesNothingUnlessSwitchedOn() throws Exception {
        String name = scratchName();
        var processor = new DatabaseBootstrapPostProcessor(new DeferredLogs());
        try {
            processor.postProcessEnvironment(new MockEnvironment().withProperty("spring.datasource.url", SERVER + name),
                    null);
            assertThat(exists(name)).isFalse();

            processor.postProcessEnvironment(new MockEnvironment()
                    .withProperty("docs0.database.auto-create", "true")
                    .withProperty("spring.datasource.url", SERVER + name)
                    .withProperty("spring.datasource.username", USER), null);
            assertThat(exists(name)).isTrue();
        } finally {
            drop(name);
        }
    }
}
