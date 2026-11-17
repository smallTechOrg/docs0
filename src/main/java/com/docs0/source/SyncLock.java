package com.docs0.source;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A lock on one tenant's source that works across processes: the service, a Cloud Run Job and a second job can
 * each try to sync the same source, and only one may. It is a Postgres session advisory lock, held on a dedicated
 * connection (not one from the small pool, so a sync never starves the pool) for exactly as long as the run, and
 * released when the run ends or the connection drops, so a crashed process never leaves it stuck.
 */
@Component
public class SyncLock {

    private static final Logger log = LoggerFactory.getLogger(SyncLock.class);

    /** A held lock. Closing it releases the lock. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    private final DataSource dataSource;

    public SyncLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The lock, or null when another process (or connection) holds it. */
    public Held tryLock(long tenantId, long sourceId) {
        Connection connection = null;
        try {
            connection = open();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT pg_try_advisory_lock(hashtext('docs0-sync'), hashtext(?))")) {
                ps.setString(1, tenantId + ":" + sourceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        Connection held = connection;
                        return () -> release(held, tenantId, sourceId);
                    }
                }
            }
            connection.close();
            return null;
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Could not take the sync lock", e);
        }
    }

    private Connection open() throws SQLException {
        if (dataSource instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
            return DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
        }
        return dataSource.getConnection();
    }

    private static void release(Connection connection, long tenantId, long sourceId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pg_advisory_unlock(hashtext('docs0-sync'), hashtext(?))")) {
            ps.setString(1, tenantId + ":" + sourceId);
            ps.execute();
        } catch (SQLException e) {
            log.debug("Sync lock release failed (closing the connection releases it): {}", e.getClass().getName());
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Connection did not close cleanly: {}", e.getClass().getName());
            }
        }
    }
}
