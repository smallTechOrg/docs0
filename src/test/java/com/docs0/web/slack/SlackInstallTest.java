package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.docs0.slack.FakeSlackOAuth;
import com.docs0.slack.SlackOAuth.Installed;
import com.docs0.slack.SlackSignature;
import com.docs0.tenant.Tenant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** Add to Slack: the state handling, creating or renewing a tenant, the welcome message and uninstalling. */
@ExtendWith(OutputCaptureExtension.class)
class SlackInstallTest extends MultiTenantTest {

    private long tenantCount() {
        return jdbc.sql("SELECT count(*) FROM tenant").query(Long.class).single();
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ---- starting ----

    @Test
    void installSendsTheBrowserToSlackWithTheClientScopesRedirectAndAFreshState() throws Exception {
        // The real client builds the link; here the fake stands in, so check what the controller hands over.
        MockHttpSession session = new MockHttpSession();
        MvcResult first = mvc.perform(get("/slack/install").session(session)).andReturn();
        MvcResult second = mvc.perform(get("/slack/install").session(new MockHttpSession())).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(302);
        assertThat(first.getResponse().getRedirectedUrl()).startsWith("https://slack.example/authorize?state=");
        assertThat(param(first.getResponse().getRedirectedUrl(), "state")).hasSizeGreaterThanOrEqualTo(40)
                .isNotEqualTo(param(second.getResponse().getRedirectedUrl(), "state"));
    }

    // ---- the callback's state ----

    @Test
    void aCallbackWithoutAMismatchedOrReusedOrExpiredStateInstallsNothing() throws Exception {
        long before = tenantCount();
        MockHttpSession mismatch = new MockHttpSession();
        startInstall(mismatch);
        MockHttpSession reused = new MockHttpSession();
        String good = startInstall(reused);
        MockHttpSession expired = new MockHttpSession();
        String late = startInstall(expired);

        // No session at all (so no state), then a state that does not match the one handed out.
        assertThat(mvc.perform(get("/slack/oauth/callback").param("state", "x").param("code", "c1")).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/slack/oauth/callback").session(mismatch).param("state", "other").param("code", "c1"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // Reuse: the first callback with the right state is accepted, the second is not.
        MvcResult ok = mvc.perform(get("/slack/oauth/callback").session(reused).param("state", good).param("code", "c1"))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        MvcResult again = mvc.perform(get("/slack/oauth/callback").session(reused).param("state", good).param("code", "c1"))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(again)).contains("expired or was already used");
        // Expired after ten minutes.
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        MvcResult old = mvc.perform(get("/slack/oauth/callback").session(expired).param("state", late).param("code", "c2"))
                .andReturn();
        assertThat(old.getResponse().getStatus()).isEqualTo(400);

        assertThat(oauth.exchangedCodes).containsExactly("c1"); // only the one good callback reached Slack
        assertThat(tenantCount()).isEqualTo(before + 1);
    }

    @Test
    void aStateStillWorksJustInsideTenMinutes() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = startInstall(session);
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));

        MvcResult result = mvc.perform(get("/slack/oauth/callback").session(session).param("state", state)
                .param("code", "c1")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void cancellingOnSlacksPageShowsAFriendlyPageAndInstallsNothing() throws Exception {
        long before = tenantCount();
        MockHttpSession session = new MockHttpSession();
        String state = startInstall(session);

        MvcResult result = mvc.perform(get("/slack/oauth/callback").session(session).param("state", state)
                .param("error", "access_denied")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).contains("Nothing was installed").contains("href=\"/slack/install\"");
        assertThat(tenantCount()).isEqualTo(before);
        assertThat(oauth.exchangedCodes).isEmpty();
    }

    @Test
    void aFailedExchangeShowsAPlainErrorAndInstallsNothing() throws Exception {
        long before = tenantCount();
        oauth.fails = true;

        MvcResult result = install();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(result)).contains("did not complete").doesNotContain("invalid_code");
        assertThat(tenantCount()).isEqualTo(before);
    }

    // ---- a new company ----

    @Test
    void aNewWorkspaceBecomesATenantWithItsTokenStoredEncryptedAndTheInstallerAsAdmin(CapturedOutput output)
            throws Exception {
        slack.user("UINSTALLER", "Ina", "Ina@Acme.example");

        MvcResult result = install();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String html = body(result);
        assertThat(html).contains("docs0 is installed in Acme Inc.").contains("Next: sign in to connect your docs.")
                .contains("href=\"/admin/login\"");
        Tenant created = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        assertThat(created.slug()).isEqualTo("acme-inc");
        assertThat(created.name()).isEqualTo("Acme Inc.");
        assertThat(created.status()).isEqualTo(Tenant.ACTIVE);
        assertThat(created.adminEmails()).isEqualTo("ina@acme.example");
        var installation = workspaces.byTeamId("T_ACME").orElseThrow();
        assertThat(installation.tenantId()).isEqualTo(created.id());
        assertThat(installation.botUserId()).isEqualTo("UBOTACME");
        assertThat(installation.installedBy()).isEqualTo("UINSTALLER");
        assertThat(installation.scopes()).isEqualTo("chat:write,users:read");
        // Stored encrypted; only decrypted on demand; never in a page or in the logs.
        String stored = jdbc.sql("SELECT encrypted_bot_token FROM slack_installation WHERE tenant_id = :t")
                .param("t", created.id()).query(String.class).single();
        assertThat(stored).doesNotContain(FakeSlackOAuth.BOT_TOKEN).isNotBlank();
        assertThat(workspaces.botToken(created.id())).contains(FakeSlackOAuth.BOT_TOKEN);
        assertThat(html).doesNotContain(FakeSlackOAuth.BOT_TOKEN);
        assertThat(output.getAll()).doesNotContain(FakeSlackOAuth.BOT_TOKEN).doesNotContain("ina@acme.example");
    }

    @Test
    void theSlugIsUrlSafeAndUniqueAcrossCompanies() throws Exception {
        String[][] teams = {{"T1", "Acme Inc."}, {"T2", "Acme Inc."}, {"T3", "ACME, inc"}, {"T4", "Café Münch & Co!"},
                {"T5", "!!!"}};
        for (String[] team : teams) {
            oauth.next = new Installed(team[0], team[1], "xoxb-" + team[0], "UBOT", "chat:write", "UINSTALLER");
            assertThat(install().getResponse().getStatus()).isEqualTo(200);
        }

        List<String> slugs = jdbc.sql("SELECT t.slug FROM tenant t WHERE t.slack_team_id IS NOT NULL ORDER BY t.id")
                .query(String.class).list();
        assertThat(slugs).containsExactly("acme-inc", "acme-inc-2", "acme-inc-3", "cafe-munch-co", "workspace");
        assertThat(slugs).allMatch(s -> s.matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }

    @Test
    void anInstallerWhoseEmailCannotBeReadStillInstallsAndIsLeftToSignInAsAWorkspaceAdmin() throws Exception {
        // No such user in the fake workspace: users.info fails, as it would without the email scope.
        MvcResult result = install();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(tenants.findBySlackTeamId("T_ACME").orElseThrow().adminEmails()).isNull();
    }

    // ---- an existing company ----

    @Test
    void reinstallingRenewsTheTokenAndKeepsTheTenantItsDataAndItsAdmins() throws Exception {
        slack.user("UINSTALLER", "Ina", "ina@acme.example");
        install();
        Tenant first = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        tenants.setAdminEmails(first.id(), "boss@acme.example");
        new com.docs0.source.SourceRepository(jdbc).findOrCreate(first.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED");
        oauth.next = new Installed("T_ACME", "Acme Renamed", "xoxb-the-new-token", "UBOTNEW", "chat:write", "UOTHER");

        MvcResult result = install();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(tenantCount()).isEqualTo(2); // the local pilot tenant and Acme
        Tenant again = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.adminEmails()).isEqualTo("boss@acme.example");
        assertThat(workspaces.botToken(first.id())).contains("xoxb-the-new-token");
        assertThat(workspaces.byTeamId("T_ACME").orElseThrow().botUserId()).isEqualTo("UBOTNEW");
        assertThat(jdbc.sql("SELECT count(*) FROM source WHERE tenant_id = :t").param("t", first.id())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reinstallingAfterUninstallingServesTheWorkspaceAgain() throws Exception {
        install();
        assertThat(workspaces.markRevoked("T_ACME")).isTrue();
        assertThat(workspaces.byTeamId("T_ACME")).isEmpty();

        install();

        assertThat(workspaces.byTeamId("T_ACME")).isPresent();
    }

    @Test
    void reinstallingASuspendedTenantDoesNotReactivateIt() throws Exception {
        install();
        Tenant acme = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        tenants.suspend(acme.id());
        slack.sent.clear();

        MvcResult result = install();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).contains("suspended");
        assertThat(tenants.findById(acme.id()).orElseThrow().status()).isEqualTo(Tenant.SUSPENDED);
        assertThat(slack.sent).as("no welcome message for a suspended tenant").isEmpty();
    }

    // ---- the welcome message ----

    @Test
    void theInstallerGetsAWelcomeDmWithALinkToTheStartPage() throws Exception {
        install();

        assertThat(slack.dmsOpened).containsExactly("UINSTALLER");
        assertThat(slack.sent).hasSize(1);
        assertThat(slack.sent.get(0).channel()).isEqualTo("DUINSTALLER");
        assertThat(slack.sent.get(0).text()).contains("https://docs0.example.test/admin/start")
                .contains("mention @docs0");
    }

    @Test
    void aWelcomeMessageThatCannotBeSentDoesNotFailTheInstall() throws Exception {
        slack.postsFail = true;

        MvcResult result = install();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(workspaces.byTeamId("T_ACME")).isPresent();
    }

    // ---- uninstalling, over the signed HTTP endpoint ----

    @Test
    void appUninstalledOverHttpRevokesTheInstallationButKeepsTheTenant() throws Exception {
        install();
        Tenant acme = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        String body = "{\"type\":\"event_callback\",\"team_id\":\"T_ACME\",\"event_id\":\"EvU\","
                + "\"event\":{\"type\":\"app_uninstalled\"}}";
        String ts = Long.toString(clock.instant().getEpochSecond());

        mvc.perform(post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts)
                        .header(SlackSignature.SIGNATURE_HEADER,
                                SlackSignature.sign("signing-secret-1", ts, body.getBytes(StandardCharsets.UTF_8))))
                .andReturn();

        for (int i = 0; i < 100 && workspaces.byTeamId("T_ACME").isPresent(); i++) {
            Thread.sleep(20);
        }
        assertThat(workspaces.byTeamId("T_ACME")).isEmpty();
        assertThat(workspaces.forTenant(acme.id())).isEmpty();
        assertThat(tenants.findById(acme.id())).isPresent();
        assertThat(tenants.findBySlackTeamId("T_ACME")).isPresent();
        assertThat(jdbc.sql("SELECT count(*) FROM slack_installation WHERE tenant_id = :t").param("t", acme.id())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theSignedEndpointsWorkInTheRealApplicationContext() throws Exception {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";
        String ts = Long.toString(clock.instant().getEpochSecond());

        mvc.perform(post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts)
                        .header(SlackSignature.SIGNATURE_HEADER,
                                SlackSignature.sign("signing-secret-1", ts, body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("abc123"));
        assertThat(mvc.perform(post("/slack/events").contentType("application/json").content(body)).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }
}
