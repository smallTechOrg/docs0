package com.docs0;

import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.docs0.tenant.local.LocalTenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests that need the real application context and the real Postgres test
 * database. Every test starts from empty tables and one tenant (the configured local one). No real credentials
 * are ever loaded (see application-test.yml).
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TenantService tenants;

    @Autowired
    protected LocalTenantResolver local;

    protected Tenant tenant;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("TRUNCATE interaction, conversation, sync_run, document, source, tenant RESTART IDENTITY CASCADE")
                .update();
        local.reset();
        tenant = local.tenant();
    }
}
