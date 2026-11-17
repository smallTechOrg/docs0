package com.docs0.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates the application's database on first boot when the Postgres role is allowed to (a Cloud SQL role made
 * with {@code gcloud sql users create} has CREATEDB). It connects to the {@code postgres} database on the same
 * server with the same credentials, so the role ends up owning the new database and Flyway can create tables.
 *
 * <p>Runs before the application's DataSource opens its first connection, see
 * {@link DatabaseBootstrapPostProcessor}. Nothing here logs or returns a credential.
 */
public final class DatabaseBootstrap {

    /** Postgres identifiers we are willing to create: lower case, digits and underscore only. */
    static final Pattern DATABASE_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static final Pattern JDBC_URL = Pattern.compile("^jdbc:postgresql://([^/?]+)/([^?]*)(\\?.*)?$");

    /** Arbitrary constant: every docs0 instance that bootstraps takes this advisory lock on the postgres database. */
    private static final long LOCK_KEY = 0x646f637330L;

    private static final int LOGIN_TIMEOUT_SECONDS = 15;

    /** The result of a bootstrap, for the log line. */
    public enum Outcome { CREATED, ALREADY_EXISTS }

    private DatabaseBootstrap() {}

    /** The target database name in a jdbc:postgresql URL, or throws if the URL or the name is not acceptable. */
    static String databaseName(String jdbcUrl) {
        Matcher m = JDBC_URL.matcher(jdbcUrl == null ? "" : jdbcUrl.strip());
        if (!m.matches()) {
            throw new IllegalStateException(
                    "database bootstrap: DOCS0_DATABASE_URL must look like jdbc:postgresql://host:5432/name");
        }
        String name = m.group(2);
        if (!DATABASE_NAME.matcher(name).matches()) {
            throw new IllegalStateException("database bootstrap: database name '" + name
                    + "' is not allowed; use lower case letters, digits and underscore, starting with a letter");
        }
        return name;
    }

    /** The same server and connection parameters (for example sslmode) but the {@code postgres} database. */
    static String maintenanceUrl(String jdbcUrl) {
        Matcher m = JDBC_URL.matcher(jdbcUrl.strip());
        if (!m.matches()) {
            throw new IllegalStateException("database bootstrap: unsupported DOCS0_DATABASE_URL");
        }
        return "jdbc:postgresql://" + m.group(1) + "/postgres" + (m.group(3) == null ? "" : m.group(3));
    }

    /** Creates the database named in the URL if it does not exist yet. Safe to run from several instances at once. */
    public static Outcome ensureDatabase(String jdbcUrl, String user, String password) {
        String name = databaseName(jdbcUrl);
        String adminUrl = maintenanceUrl(jdbcUrl);
        Properties credentials = new Properties();
        if (user != null && !user.isBlank()) {
            credentials.setProperty("user", user);
        }
        credentials.setProperty("password", password == null ? "" : password);
        credentials.setProperty("loginTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
        try (Connection connection = DriverManager.getConnection(adminUrl, credentials)) {
            connection.setAutoCommit(true);
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");
            }
            try {
                if (exists(connection, name)) {
                    return Outcome.ALREADY_EXISTS;
                }
                try (Statement create = connection.createStatement()) {
                    // The name passed DATABASE_NAME, so quoting it is safe; Postgres cannot bind identifiers.
                    create.execute("CREATE DATABASE \"" + name + "\"");
                }
                return Outcome.CREATED;
            } finally {
                try (Statement unlock = connection.createStatement()) {
                    unlock.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
                } catch (SQLException ignored) {
                    // Closing the connection releases the lock anyway.
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database bootstrap failed for database '" + name + "' (SQLSTATE "
                    + e.getSQLState() + "): " + firstLine(e.getMessage())
                    + ". The database role needs the CREATEDB privilege (on Cloud SQL, a role made with"
                    + " 'gcloud sql users create' has it), and the server must be reachable from here."
                    + " Or create the database yourself and leave DOCS0_AUTO_CREATE_DATABASE unset.", e);
        }
    }

    private static boolean exists(Connection connection, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "no detail";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
