package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.docs0.tenant.Tenant;
import java.math.BigDecimal;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** The public landing page, the redacted health check, the Start checklist and admin email management. */
class OnboardingTest extends MultiTenantTest {

    Tenant acme;
    MockHttpSession session;

    @BeforeEach
    void installAndSignIn() throws Exception {
        slack.user("UINSTALLER", "Ina", "ina@acme.example");
        slack.user("UBOTACME", "docs0 helper", null);
        install();
        acme = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        session = signInAs("ina@acme.example", "T_ACME", "UINSTALLER");
    }

    private String html(String url) throws Exception {
        return mvc.perform(get(url).session(session).accept(HTML)).andReturn().getResponse().getContentAsString();
    }

    // ---- public pages ----

    @Test
    void theLandingPageHasTheInstallButtonAndASignInLinkAndNamesNoCompany() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("href=\"/slack/install\"").contains("Add to Slack").contains("href=\"/admin/login\"")
                .contains("Sign in");
        assertThat(html).doesNotContain("smallTech").doesNotContain("Acme").doesNotContain("pending corrections")
                .doesNotContain("documents indexed");
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void healthSaysOnlyUpWithNoTenantNamesOrCounts() throws Exception {
        MvcResult result = mvc.perform(get("/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
    }

    // ---- the checklist ----

    @Test
    void theStartPageShowsSlackConnectedEverySourceWithALinkAndTheBudget() throws Exception {
        tenants.setMonthlyBudget(acme.id(), new BigDecimal("25"));

        String html = html("/admin/start");

        assertThat(html).contains("Slack is connected").contains("docs0 helper").contains("Acme Inc.");
        for (String anchor : new String[] {"drive", "linear", "trello", "grain", "slack", "gmail"}) {
            assertThat(html).contains("href=\"/admin/sources#" + anchor + "\"");
        }
        assertThat(html).contains("Google Drive").contains("Linear").contains("Trello").contains("Grain")
                .contains("Slack history").contains("Gmail").contains("Not set up");
        assertThat(html).contains("/invite @docs0").contains("Try it").contains("Admins");
        assertThat(html).containsPattern("spent \\$<span>0\\.00</span><span> of the \\$<span>25\\.00</span> monthly cap");
        assertThat(html).contains("ina@acme.example");
    }

    @Test
    void startIsFirstInTheNavigationAndEveryAdminPageHasItWithSignOut() throws Exception {
        for (String page : new String[] {"/admin/start", "/admin/sources", "/admin/channels", "/admin/corrections",
                "/admin/insights", "/admin/costs"}) {
            String html = html(page);
            assertThat(html).as(page).contains("href=\"/admin/start\"").contains("action=\"/admin/logout\"");
            assertThat(html.indexOf("href=\"/admin/start\"")).as(page)
                    .isLessThan(html.indexOf("href=\"/admin/sources\""));
        }
    }

    @Test
    void theStartPageShowsAReinstallLinkWhenDocs0WasRemovedFromSlack() throws Exception {
        workspaces.markRevoked("T_ACME");

        String html = html("/admin/start");

        assertThat(html).contains("Slack is not connected").contains("href=\"/slack/install\"");
    }

    // ---- admin emails ----

    @Test
    void anAdminCanAddAndRemoveAdminEmailsWithTheCsrfToken() throws Exception {
        assertThat(mvc.perform(form("/admin/start/admins/add", session).param("email", " New.Person@Acme.example "))
                .andReturn().getResponse().getRedirectedUrl()).isEqualTo("/admin/start#admins");

        assertThat(tenants.findById(acme.id()).orElseThrow().adminEmails())
                .isEqualTo("ina@acme.example,new.person@acme.example");
        assertThat(html("/admin/start")).contains("new.person@acme.example");

        mvc.perform(form("/admin/start/admins/remove", session).param("email", "NEW.person@acme.example"))
                .andReturn();
        assertThat(tenants.findById(acme.id()).orElseThrow().adminEmails()).isEqualTo("ina@acme.example");
    }

    @Test
    void changingAdminsWithoutTheCsrfTokenOrASessionIsRefused() throws Exception {
        assertThat(mvc.perform(post("/admin/start/admins/add").session(session).param("email", "x@acme.example"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/admin/start/admins/add").param("email", "x@acme.example").accept(HTML))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/admin/start/admins/remove").session(session).param("email", "ina@acme.example"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(tenants.findById(acme.id()).orElseThrow().adminEmails()).isEqualTo("ina@acme.example");
    }

    @Test
    void invalidEmailsAreRejectedWithAMessage() throws Exception {
        for (String bad : new String[] {"", "   ", "not-an-email", "a@b", "<script>alert(1)</script>@x.example",
                "a b@acme.example", "x@acme.example, y@acme.example", "a\"@x.example", "a@@x.example",
                "a".repeat(250) + "@x.example"}) {
            MvcResult result = mvc.perform(form("/admin/start/admins/add", session).param("email", bad)).andReturn();
            assertThat(result.getFlashMap().get("error")).as("[" + bad + "]").isNotNull();
        }
        assertThat(tenants.findById(acme.id()).orElseThrow().adminEmails()).isEqualTo("ina@acme.example");
    }

    @Test
    void aCompanyCanHaveAtMostTwentyAdminEmails() throws Exception {
        for (int i = 2; i <= 20; i++) {
            mvc.perform(form("/admin/start/admins/add", session).param("email", "p" + i + "@acme.example")).andReturn();
        }
        assertThat(com.docs0.tenant.AdminEmails.parse(tenants.findById(acme.id()).orElseThrow().adminEmails()))
                .hasSize(20);

        MvcResult result = mvc.perform(form("/admin/start/admins/add", session).param("email", "one-more@acme.example"))
                .andReturn();

        assertThat((String) result.getFlashMap().get("error")).contains("at most 20");
        assertThat(com.docs0.tenant.AdminEmails.parse(tenants.findById(acme.id()).orElseThrow().adminEmails()))
                .hasSize(20).doesNotContain("one-more@acme.example");
        assertThat(html("/admin/start")).contains("The list is full");
    }

    @Test
    void anAddingTheSamePersonTwiceKeepsOneEntry() throws Exception {
        mvc.perform(form("/admin/start/admins/add", session).param("email", "INA@acme.example")).andReturn();

        assertThat(tenants.findById(acme.id()).orElseThrow().adminEmails()).isEqualTo("ina@acme.example");
    }

    @Test
    void storedEmailsAreEscapedWhenShown() throws Exception {
        tenants.setAdminEmails(acme.id(), "ina@acme.example,\"><script>alert(1)</script>@x.example");

        String html = html("/admin/start");

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;alert(1)");
    }

    @Test
    void theTeamNameFromSlackIsEscapedOnThePages() throws Exception {
        jdbc.sql("UPDATE tenant SET name = :n WHERE id = :t").param("n", "<img src=x onerror=alert(1)>")
                .param("t", acme.id()).update();

        assertThat(html("/admin/start")).doesNotContain("<img src=x").contains("&lt;img src=x");
    }

    @Test
    void manyAdminsInTheListStillRenderTheCapMessageForExactlyTwenty() throws Exception {
        String twenty = String.join(",", IntStream.rangeClosed(1, 20).mapToObj(i -> "a" + i + "@acme.example").toList());
        tenants.setAdminEmails(acme.id(), twenty);

        assertThat(html("/admin/start")).contains("The list is full").doesNotContain("Add admin");
    }
}
