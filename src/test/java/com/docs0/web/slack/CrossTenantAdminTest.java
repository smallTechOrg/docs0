package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.docs0.isolation.TwoTenants;
import com.docs0.isolation.TwoTenants.Side;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Two companies with real Slack sign-in sessions. An admin of one company, signed in and holding a valid session,
 * cannot read or change the other company's data through any admin page or form, whatever ids or parameters they send.
 */
class CrossTenantAdminTest extends MultiTenantTest {

    TwoTenants two;
    Side a;
    Side b;
    MockHttpSession sessionA;
    MockHttpSession sessionB;

    @BeforeEach
    void seedAndSignIn() throws Exception {
        two = TwoTenants.seed(jdbc, tenant.id());
        a = two.a;
        b = two.b;
        tenants.setAdminEmails(a.tenantId(), "admin@alpha.example");
        tenants.setAdminEmails(b.tenantId(), "admin@beta.example");
        slack.workspaceFor(b.tenantId(), new com.docs0.source.slack.FakeSlack());
        sessionA = signInAs("admin@alpha.example", a.teamId(), "UAA");
        sessionB = signInAs("admin@beta.example", b.teamId(), "UBB");
    }

    private String page(String url, MockHttpSession session) throws Exception {
        return mvc.perform(get(url).session(session).accept(HTML)).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    @Test
    void eachAdminSeesOnlyTheirOwnCompanyOnEveryPage() throws Exception {
        for (String url : List.of("/admin/start", "/admin/sources", "/admin/corrections",
                "/admin/corrections?tab=reviewed", "/admin/insights?days=30", "/admin/costs")) {
            String htmlA = page(url, sessionA);
            String htmlB = page(url, sessionB);
            assertThat(htmlA).as(url + " as A").doesNotContain("BETA").doesNotContain("Acme").doesNotContain("T_BETA");
            assertThat(htmlB).as(url + " as B").doesNotContain("ALPHA").doesNotContain("smallTech")
                    .doesNotContain("T_ALPHA");
        }
        assertThat(page("/admin/corrections", sessionA)).contains("Refund pending fact ALPHA");
        assertThat(page("/admin/corrections", sessionB)).contains("Refund pending fact BETA");
        assertThat(page("/admin/costs", sessionA)).contains("11.00").doesNotContain("4.50");
        assertThat(page("/admin/costs", sessionB)).contains("4.50").doesNotContain("11.00");
        assertThat(page("/admin/start", sessionA)).contains("admin@alpha.example").doesNotContain("admin@beta.example");
    }

    @Test
    void craftedIdsAndTenantParametersInFormsAndUrlsReachNothingOfTheOtherCompany() throws Exception {
        Map<String, Long> before = TwoTenants.counts(jdbc, b.tenantId());
        String tenantOfB = Long.toString(b.tenantId());

        // Corrections of B, posted by A's admin, with B's tenant id offered in every way a form could.
        for (String action : List.of("approve", "reject", "unapprove")) {
            for (long id : new long[] {b.pendingCorrection(), b.approvedCorrection()}) {
                MockHttpServletRequestBuilder request = form("/admin/corrections/" + id + "/" + action, sessionA)
                        .param("text", "hijack").param("tenant", tenantOfB).param("tenantId", tenantOfB)
                        .param("tenant_id", tenantOfB).header("X-Test-Tenant", tenantOfB)
                        .header("X-Tenant-Id", tenantOfB);
                var result = mvc.perform(request).andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(302);
            }
        }
        // Stale document pings, source actions, channel settings, admin list: all A's principal only.
        mvc.perform(form("/admin/insights/stale/" + b.document() + "/ping", sessionA)).andReturn();
        mvc.perform(form("/admin/sources/disconnect", sessionA).param("tenantId", tenantOfB)).andReturn();
        for (String tool : List.of("linear", "trello", "grain")) {
            mvc.perform(form("/admin/sources/" + tool + "/disconnect", sessionA).param("sourceId",
                    Long.toString(b.driveSource()))).andReturn();
            mvc.perform(form("/admin/sources/" + tool + "/sync", sessionA)).andReturn();
        }
        mvc.perform(form("/admin/sources/folders/remove", sessionA).param("id", "any").param("tenantId", tenantOfB))
                .andReturn();
        mvc.perform(form("/admin/sources/sync", sessionA).param("sourceId", Long.toString(b.driveSource())))
                .andReturn();
        mvc.perform(form("/admin/channels", sessionA).param("proactive", "C1", "C2").param("tenantId", tenantOfB))
                .andReturn();
        mvc.perform(form("/admin/start/admins/add", sessionA).param("email", "evil@alpha.example")
                .param("tenantId", tenantOfB)).andReturn();
        mvc.perform(form("/admin/start/admins/remove", sessionA).param("email", "admin@beta.example")
                .param("tenantId", tenantOfB)).andReturn();

        // B's data is exactly as it was; B's admin list too; and the new email landed on A.
        assertThat(TwoTenants.counts(jdbc, b.tenantId())).isEqualTo(before);
        assertThat(statusOf(b.pendingCorrection())).isEqualTo("PENDING");
        assertThat(statusOf(b.approvedCorrection())).isEqualTo("APPROVED");
        assertThat(tenants.findById(b.tenantId()).orElseThrow().adminEmails()).isEqualTo("admin@beta.example");
        assertThat(tenants.findById(a.tenantId()).orElseThrow().adminEmails())
                .isEqualTo("admin@alpha.example,evil@alpha.example");
        assertThat(jdbc.sql("SELECT count(*) FROM channel_setting WHERE tenant_id = :t AND proactive")
                .param("t", b.tenantId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM source_credential WHERE tenant_id = :t").param("t", b.tenantId())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM slack_installation WHERE tenant_id = :t AND revoked_at IS NULL")
                .param("t", b.tenantId()).query(Long.class).single()).isEqualTo(1);
    }

    private String statusOf(long correctionId) {
        return jdbc.sql("SELECT status FROM correction WHERE id = :i").param("i", correctionId).query(String.class)
                .single();
    }

    @Test
    void aSessionIsBoundToItsTenantAndCannotBeSwappedForTheOthersByAnyRequestData() throws Exception {
        // A's session with B's identifiers in the URL, header, cookie-less params: still A.
        String html = mvc.perform(get("/admin/start?tenant=" + b.tenantId() + "&tenantId=" + b.tenantId())
                .session(sessionA).accept(HTML).header("X-Test-Tenant", b.tenantId())).andReturn().getResponse()
                .getContentAsString();

        assertThat(html).contains("admin@alpha.example").doesNotContain("admin@beta.example").doesNotContain("Acme");
        // And an admin of B who is only a member of A's Slack workspace has no standing in B.
        signIn.signsInAs("admin@beta.example", a.teamId(), "UCROSS", "Cross");
        MockHttpSession cross = new MockHttpSession();
        var result = finishLogin(cross, startLogin(cross));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void oneAdminSigningOutDoesNotEndTheOthersSession() throws Exception {
        mvc.perform(form("/admin/logout", sessionA)).andReturn();

        assertThat(sessionA.isInvalid()).isTrue();
        assertThat(mvc.perform(get("/admin/start").session(sessionB).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void aSuspendedCompanyDoesNotAffectTheOthersAdmins() throws Exception {
        tenants.suspend(b.tenantId());

        assertThat(mvc.perform(get("/admin/start").session(sessionB).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(302);
        assertThat(mvc.perform(get("/admin/start").session(sessionA).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
