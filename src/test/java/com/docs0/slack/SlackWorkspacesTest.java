package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.TestDatabase;
import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.isolation.TwoTenants;
import com.docs0.isolation.TwoTenants.Side;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The workspace registry: which tenant a Slack team belongs to, and the tenant's own bot token. */
class SlackWorkspacesTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);
    private SlackWorkspaces workspaces;
    private TenantService tenants;
    private TwoTenants two;
    private Side a;
    private Side b;

    @BeforeEach
    void setUp() {
        two = TwoTenants.seed(jdbc, TestDatabase.resetWithTenant());
        a = two.a;
        b = two.b;
        workspaces = new SlackWorkspaces(jdbc, cipher);
        tenants = new TenantService(jdbc);
    }

    @Test
    void aTeamIdResolvesToItsTenantAndAnUnknownTeamResolvesToNothing() {
        assertThat(workspaces.byTeamId(a.teamId())).get().extracting(SlackWorkspaces.Installation::tenantId)
                .isEqualTo(a.tenantId());
        assertThat(workspaces.byTeamId(b.teamId())).get().extracting(SlackWorkspaces.Installation::tenantId)
                .isEqualTo(b.tenantId());
        assertThat(workspaces.byTeamId("T_UNKNOWN")).isEmpty();
        assertThat(workspaces.byTeamId("")).isEmpty();
        assertThat(workspaces.byTeamId(null)).isEmpty();
        assertThat(workspaces.forTenant(b.tenantId())).get().extracting(SlackWorkspaces.Installation::teamId)
                .isEqualTo(b.teamId());
        assertThat(workspaces.forTenant(999_999)).isEmpty();
    }

    @Test
    void theGatewayIgnoresEventsFromAWorkspaceNobodyRegistered() {
        Docs0Properties props = new Docs0Properties(null, null, null, new Docs0Properties.Slack("", ""), null, null,
                null, null, null);
        SlackGateway gateway = new SlackGateway(props, workspaces, null, null, null, null);

        assertThat(gateway.tenantOf(a.teamId())).hasValue(a.tenantId());
        assertThat(gateway.tenantOf(b.teamId())).hasValue(b.tenantId());
        assertThat(gateway.tenantOf("T_UNKNOWN")).isEmpty();
        assertThat(gateway.tenantOf(null)).isEmpty();
    }

    @Test
    void theBotTokenIsEncryptedAtRestAndDecryptedOnDemand() {
        String stored = jdbc.sql("SELECT encrypted_bot_token FROM slack_installation WHERE tenant_id = :t")
                .param("t", a.tenantId()).query(String.class).single();

        assertThat(stored).isNotEqualTo(a.botToken()).doesNotContain("xoxb").doesNotContain(a.botToken());
        assertThat(cipher.decrypt(stored)).isEqualTo(a.botToken());
        assertThat(workspaces.botToken(a.tenantId())).contains(a.botToken());
        assertThat(workspaces.botToken(999_999)).isEmpty();
        // Nothing that describes an installation carries the token.
        assertThat(workspaces.forTenant(a.tenantId()).orElseThrow().toString()).doesNotContain(a.botToken())
                .doesNotContain(stored);
    }

    @Test
    void registeringAgainReplacesTheInstallationAndRefreshesTheClient() {
        WorkspaceSlackMessengers messengers = new WorkspaceSlackMessengers(workspaces);
        SlackMessenger first = messengers.forTenant(a.tenantId());
        assertThat(messengers.forTenant(a.tenantId())).isSameAs(first);
        assertThat(messengers.forTenant(b.tenantId())).isNotSameAs(first);

        workspaces.register(a.tenantId(), a.teamId(), "Renamed", "xoxb-rotated", "UNEW", "admin", null);

        assertThat(workspaces.botToken(a.tenantId())).contains("xoxb-rotated");
        assertThat(messengers.forTenant(a.tenantId())).isNotSameAs(first);
        assertThat(workspaces.forTenant(a.tenantId()).orElseThrow().teamName()).isEqualTo("Renamed");
        // B is untouched.
        assertThat(workspaces.botToken(b.tenantId())).contains(b.botToken());
        assertThat(jdbc.sql("SELECT count(*) FROM slack_installation").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void oneSlackTeamCanNeverServeTwoTenants() {
        assertThatThrownBy(() -> workspaces.register(b.tenantId(), a.teamId(), "Stolen", "xoxb-x", "U", "admin", null))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("xoxb");

        assertThat(workspaces.byTeamId(a.teamId()).orElseThrow().tenantId()).isEqualTo(a.tenantId());
        assertThat(workspaces.forTenant(b.tenantId()).orElseThrow().teamId()).isEqualTo(b.teamId());
        assertThat(tenants.findBySlackTeamId(b.teamId())).get().extracting(Tenant::id).isEqualTo(b.tenantId());
    }

    @Test
    void aTenantWithoutAWorkspaceHasNoMessenger() {
        long lonely = tenants.create("lonely", "Lonely Co").id();
        WorkspaceSlackMessengers messengers = new WorkspaceSlackMessengers(workspaces);

        assertThat(messengers.connected(lonely)).isFalse();
        assertThat(messengers.connected(a.tenantId())).isTrue();
        assertThatThrownBy(() -> messengers.forTenant(lonely)).isInstanceOf(SlackMessenger.SlackCallException.class)
                .extracting(e -> ((SlackMessenger.SlackCallException) e).code()).isEqualTo("no_workspace");
    }

    @Test
    void tenantsAreCreatedAndFoundBySlugAndTeam() {
        Tenant created = tenants.create("globex", "Globex");

        assertThat(created.status()).isEqualTo(Tenant.ACTIVE);
        assertThat(created.monthlyBudgetUsd()).isNull();
        assertThat(tenants.findBySlug("globex")).get().extracting(Tenant::id).isEqualTo(created.id());
        assertThat(tenants.findBySlug("nobody")).isEmpty();
        assertThat(tenants.findBySlackTeamId(a.teamId())).get().extracting(Tenant::slug).isEqualTo("smalltech");
        assertThat(tenants.findBySlackTeamId("T_NONE")).isEmpty();
        assertThatThrownBy(() -> tenants.create("globex", "Again")).isInstanceOf(Exception.class);
        assertThat(tenants.ensure("globex", "Globex Inc").id()).isEqualTo(created.id());
        assertThat(tenants.all()).extracting(Tenant::slug).containsExactly("smalltech", "acme", "globex");

        tenants.suspend(created.id());
        tenants.setMonthlyBudget(created.id(), new BigDecimal("12.50"));
        tenants.setAdminEmails(created.id(), "ceo@globex.example.com, cto@globex.example.com");
        Tenant changed = tenants.findById(created.id()).orElseThrow();
        assertThat(changed.active()).isFalse();
        assertThat(changed.monthlyBudgetUsd()).isEqualByComparingTo("12.50");
        assertThat(changed.adminEmails()).contains("ceo@globex.example.com");
        assertThat(tenants.active()).extracting(Tenant::slug).containsExactly("smalltech", "acme");
        assertThat(tenants.isActive(created.id())).isFalse();
        assertThat(tenants.isActive(999_999)).isFalse();
    }
}
