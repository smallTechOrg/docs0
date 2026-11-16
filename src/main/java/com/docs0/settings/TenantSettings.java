package com.docs0.settings;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Small named values per tenant: the digest channel, when the last digest went out, cached themes. */
@Repository
public class TenantSettings {

    public static final String DIGEST_CHANNEL = "digest_channel";
    public static final String DIGEST_CHANNEL_NAME = "digest_channel_name";
    public static final String DIGEST_LAST_AT = "digest_last_at";

    private final JdbcClient jdbc;

    public TenantSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(long tenantId, String key) {
        return jdbc.sql("SELECT value FROM tenant_setting WHERE tenant_id = :tenantId AND key = :key")
                .param("tenantId", tenantId).param("key", key).query(String.class).optional();
    }

    public void put(long tenantId, String key, String value) {
        jdbc.sql("""
                        INSERT INTO tenant_setting (tenant_id, key, value, updated_at)
                        VALUES (:tenantId, :key, :value, now())
                        ON CONFLICT (tenant_id, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                        """)
                .param("tenantId", tenantId).param("key", key).param("value", value).update();
    }

    public void remove(long tenantId, String key) {
        jdbc.sql("DELETE FROM tenant_setting WHERE tenant_id = :tenantId AND key = :key")
                .param("tenantId", tenantId).param("key", key).update();
    }

    public Optional<Instant> getInstant(long tenantId, String key) {
        return get(tenantId, key).flatMap(v -> {
            try {
                return Optional.of(Instant.parse(v));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    public void putInstant(long tenantId, String key, Instant value) {
        put(tenantId, key, value.toString());
    }
}
