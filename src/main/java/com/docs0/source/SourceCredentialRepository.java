package com.docs0.source;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stored grants, one per source. Every statement is tenant-scoped. */
@Repository
public class SourceCredentialRepository {

    private final JdbcClient jdbc;

    public SourceCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SourceCredential> find(long tenantId, long sourceId) {
        return jdbc.sql("""
                        SELECT tenant_id, source_id, account_email, encrypted_refresh_token, encrypted_secret, created_at
                        FROM source_credential
                        WHERE tenant_id = :tenant AND source_id = :source
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query((rs, n) -> new SourceCredential(rs.getLong("tenant_id"), rs.getLong("source_id"),
                        rs.getString("account_email"), rs.getString("encrypted_refresh_token"),
                        rs.getString("encrypted_secret"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Stores the grant of a source, replacing any earlier one. */
    public void save(long tenantId, long sourceId, String accountEmail, String encryptedRefreshToken) {
        jdbc.sql("""
                        INSERT INTO source_credential (tenant_id, source_id, account_email, encrypted_refresh_token)
                        SELECT s.tenant_id, s.id, :email, :token FROM source s
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        ON CONFLICT (source_id) DO UPDATE SET
                            account_email = EXCLUDED.account_email,
                            encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                            encrypted_secret = NULL,
                            created_at = now()
                        """)
                .param("email", accountEmail)
                .param("token", encryptedRefreshToken)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update();
    }

    /** Stores an encrypted JSON secret (Linear, Trello) for a source, replacing any earlier credential. */
    public void saveSecret(long tenantId, long sourceId, String accountName, String encryptedSecret) {
        jdbc.sql("""
                        INSERT INTO source_credential (tenant_id, source_id, account_email, encrypted_secret)
                        SELECT s.tenant_id, s.id, :name, :secret FROM source s
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        ON CONFLICT (source_id) DO UPDATE SET
                            account_email = EXCLUDED.account_email,
                            encrypted_refresh_token = NULL,
                            encrypted_secret = EXCLUDED.encrypted_secret,
                            created_at = now()
                        """)
                .param("name", accountName)
                .param("secret", encryptedSecret)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update();
    }

    /** Returns true when a grant was stored and is now gone. */
    public boolean delete(long tenantId, long sourceId) {
        return jdbc.sql("DELETE FROM source_credential WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update() > 0;
    }
}
