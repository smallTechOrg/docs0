package com.docs0.isolation;

import static com.docs0.web.admin.TwoTenantAdminConfig.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.Phase6IntegrationTest;
import com.docs0.agent.Agent;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.isolation.TwoTenants.Side;
import com.docs0.source.slack.FakeSlack;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The admin pages as the administrators of two different companies: each page shows only the signed-in company's
 * data, and posting the other company's ids changes nothing.
 */
class AdminIsolationTest extends Phase6IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FakeSlack slack;
    @Autowired ConversationRepository conversations;
    @Autowired InteractionRepository interactions;

    TwoTenants two;
    Side a;
    Side b;
    FakeSlack workspaceB;

    @BeforeEach
    void seed() {
        two = TwoTenants.seed(jdbc, tenant.id());
        a = two.a;
        b = two.b;
        slack.reset();
        workspaceB = new FakeSlack();
        slack.workspaceFor(b.tenantId(), workspaceB);
        slack.channel("C1", "general-alpha", false, true);
        workspaceB.channel("C1", "general-beta", false, true).channel("C2", "random-beta", false, true);
        for (Side side : List.of(a, b)) {
            var unknown = new Agent.AgentResult(Agent.Kind.UNKNOWN, "No idea.", Agent.Confidence.LOW, List.of(),
                    List.of(), List.of(), 1, 10, 1, BigDecimal.ZERO, 5, null);
            long conversation = conversations.findOrCreate(side.tenantId(), "C2", "999.1", false).id();
            interactions.insert(side.tenantId(), conversation, "U1", side.marker() + " only question about parking?",
                    unknown, "999.2");
        }
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Side side) {
        return request.header(HEADER, side.tenantId());
    }

    private String page(String url, Side side) throws Exception {
        return mvc.perform(as(get(url), side)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private String csrf(MockHttpSession session, Side side) throws Exception {
        String html = mvc.perform(as(get("/admin/channels").session(session), side)).andReturn().getResponse()
                .getContentAsString();
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private MockHttpServletRequestBuilder form(String url, Side side, MockHttpSession session) throws Exception {
        return as(post(url).session(session).param("_csrf", csrf(session, side)), side);
    }

    // ---- every page shows only the signed-in tenant's data ---------------------------------------------

    @Test
    void theCorrectionsPageListsOnlyTheSignedInTenantsCorrections() throws Exception {
        for (Side side : List.of(a, b)) {
            String html = page("/admin/corrections", side);
            assertThat(html).contains("Refund pending fact " + side.marker()).doesNotContain(two.other(side).marker());
            String reviewed = page("/admin/corrections?tab=reviewed", side);
            assertThat(reviewed).contains("Refunds are paid in").contains(side.marker())
                    .contains("/admin/corrections/" + side.approvedCorrection() + "/unapprove")
                    .doesNotContain(two.other(side).marker())
                    .doesNotContain("/admin/corrections/" + two.other(side).approvedCorrection() + "/");
            assertThat(html).contains("/admin/corrections/" + side.pendingCorrection() + "/approve")
                    .doesNotContain("/admin/corrections/" + two.other(side).pendingCorrection() + "/");
        }
    }

    @Test
    void theCostsPageShowsOnlyTheSignedInTenantsSpendAndCap() throws Exception {
        String htmlA = page("/admin/costs", a);
        String htmlB = page("/admin/costs", b);

        assertThat(htmlA).contains("11.00").doesNotContain("4.50");
        assertThat(htmlB).contains("4.50").doesNotContain("11.00");
        assertThat(htmlB).doesNotContain(a.marker());
    }

    @Test
    void theChannelsPageListsTheChannelsOfTheTenantsOwnWorkspace() throws Exception {
        String htmlA = page("/admin/channels", a);
        String htmlB = page("/admin/channels", b);

        assertThat(htmlA).contains("general-alpha").doesNotContain("beta");
        assertThat(htmlB).contains("general-beta").contains("random-beta").doesNotContain("alpha");
    }

    @Test
    void theInsightsPageShowsOnlyTheSignedInTenantsQuestionsAndStaleDocuments() throws Exception {
        for (Side side : List.of(a, b)) {
            String html = page("/admin/insights?days=30", side);
            assertThat(html).contains(side.marker() + " only question about parking?")
                    .contains("docs.example.com/" + side.marker() + "/refunds")
                    .doesNotContain(two.other(side).marker());
        }
    }

    @Test
    void theSourcesPageShowsOnlyTheSignedInTenant() throws Exception {
        String htmlA = page("/admin/sources", a);
        String htmlB = page("/admin/sources", b);

        assertThat(htmlA).contains("smallTech").doesNotContain("Acme");
        assertThat(htmlB).contains("Acme").doesNotContain("smallTech");
    }

    @Test
    void theStatusPageAndHealthReportOnlyTheConfiguredLocalTenant() throws Exception {
        String health = mvc.perform(get("/health")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String home = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(health).contains("smallTech").doesNotContain("Acme").doesNotContain("acme");
        assertThat(home).contains("smallTech").doesNotContain("Acme");
    }

    // ---- posting another tenant's ids ---------------------------------------------------------------------

    @Test
    void approvingRejectingOrUnapprovingAnotherTenantsCorrectionIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String corrections = "/admin/corrections";

        mvc.perform(form(corrections + "/" + a.pendingCorrection() + "/approve", b, session).param("text", "hijack"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(form(corrections + "/" + a.pendingCorrection() + "/reject", b, session))
                .andExpect(status().is3xxRedirection());
        mvc.perform(form(corrections + "/" + a.approvedCorrection() + "/unapprove", b, session))
                .andExpect(status().is3xxRedirection());

        assertThat(statusOf(a.pendingCorrection())).isEqualTo("PENDING");
        assertThat(statusOf(a.approvedCorrection())).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT edited_text FROM correction WHERE id = :i").param("i", a.pendingCorrection())
                .query(String.class).optional()).isEmpty();
        // The redirect says there is no such correction for this admin.
        String location = mvc.perform(form(corrections + "/" + a.pendingCorrection() + "/reject", b, session))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).contains("gone");

        // Its own works.
        mvc.perform(form(corrections + "/" + b.pendingCorrection() + "/reject", b, session))
                .andExpect(status().is3xxRedirection());
        assertThat(statusOf(b.pendingCorrection())).isEqualTo("REJECTED");
        assertThat(statusOf(a.pendingCorrection())).isEqualTo("PENDING");
    }

    private String statusOf(long correctionId) {
        return jdbc.sql("SELECT status FROM correction WHERE id = :i").param("i", correctionId).query(String.class)
                .single();
    }

    @Test
    void pingingTheOwnerOfAnotherTenantsStaleDocumentSendsNothing() throws Exception {
        MockHttpSession session = new MockHttpSession();
        slack.user("U1", "Priya", "priya@smalltech.example.com");
        workspaceB.user("U1", "Priya", "priya@acme.example.com");

        String location = mvc.perform(form("/admin/insights/stale/" + a.document() + "/ping", b, session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();

        assertThat(location).contains("ping-gone");
        assertThat(slack.sent).isEmpty();
        assertThat(slack.dmsOpened).isEmpty();
        assertThat(workspaceB.sent).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM stale_flag WHERE tenant_id = :t AND owner_pinged_at IS NOT NULL")
                .param("t", a.tenantId()).query(Long.class).single()).isZero();
    }

    @Test
    void disconnectingAsOneTenantLeavesTheOthersConnectionsAlone() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(credentials(a)).isEqualTo(1);
        assertThat(credentials(b)).isEqualTo(1);

        mvc.perform(form("/admin/sources/disconnect", b, session)).andExpect(status().is3xxRedirection());

        assertThat(credentials(b)).isZero();
        assertThat(credentials(a)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT account_email FROM source_credential WHERE tenant_id = :t")
                .param("t", a.tenantId()).query(String.class).single()).isEqualTo("Account ALPHA");
        // Linear, Trello and Grain disconnects only ever act on the signed-in tenant's own sources.
        for (String tool : List.of("linear", "trello", "grain")) {
            mvc.perform(form("/admin/sources/" + tool + "/disconnect", b, session)).andExpect(status().is3xxRedirection());
        }
        assertThat(credentials(a)).isEqualTo(1);
    }

    @Test
    void syncNowAsOneTenantNeverRunsOrRecordsASyncForTheOther() throws Exception {
        MockHttpSession session = new MockHttpSession();
        long runsOfA = runs(a);

        mvc.perform(form("/admin/sources/sync", b, session)).andExpect(status().is3xxRedirection());
        mvc.perform(form("/admin/sources/linear/sync", b, session)).andExpect(status().is3xxRedirection());
        mvc.perform(form("/admin/sources/slack/sync", b, session)).andExpect(status().is3xxRedirection());

        assertThat(runs(a)).isEqualTo(runsOfA);
    }

    private long credentials(Side side) {
        return jdbc.sql("SELECT count(*) FROM source_credential WHERE tenant_id = :t").param("t", side.tenantId())
                .query(Long.class).single();
    }

    private long runs(Side side) {
        return jdbc.sql("SELECT count(*) FROM sync_run WHERE tenant_id = :t").param("t", side.tenantId())
                .query(Long.class).single();
    }

    @Test
    void savingChannelsAsOneTenantOnlyChangesThatTenantsSettings() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(form("/admin/channels", b, session).param("proactive", "C2")).andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT channel_id FROM channel_setting WHERE tenant_id = :t AND proactive")
                .param("t", b.tenantId()).query(String.class).list()).containsExactly("C2");
        assertThat(jdbc.sql("SELECT channel_id FROM channel_setting WHERE tenant_id = :t AND proactive")
                .param("t", a.tenantId()).query(String.class).list()).containsExactly("C1");
        // A channel id that exists only in the other company's workspace is not saved for this one.
        mvc.perform(form("/admin/channels", a, session).param("proactive", "C1", "C2")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT channel_id FROM channel_setting WHERE tenant_id = :t AND proactive")
                .param("t", a.tenantId()).query(String.class).list()).containsExactly("C1");
    }

    @Test
    void withoutASignedInPrincipalTheAdminPagesAreRefused() throws Exception {
        // From a non-local address with no password set, the configured sign-in lets nobody in.
        mvc.perform(get("/admin/corrections").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        })).andExpect(status().isForbidden());
    }
}
