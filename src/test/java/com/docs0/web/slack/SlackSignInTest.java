package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.docs0.tenant.Tenant;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** Sign in with Slack for a company's admins, the session it creates and what the guard does without one. */
class SlackSignInTest extends MultiTenantTest {

    Tenant acme;

    @BeforeEach
    void installAcme() throws Exception {
        assertThat(install().getResponse().getStatus()).isEqualTo(200);
        acme = tenants.findBySlackTeamId("T_ACME").orElseThrow();
        tenants.setAdminEmails(acme.id(), "Ina@Acme.example, boss@acme.example");
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ---- starting and the state / nonce ----

    @Test
    void loginSendsTheBrowserToSlackWithAFreshStateAndNonce() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mvc.perform(get("/admin/login").session(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://slack.example/openid?state=");
        assertThat(signIn.lastNonce).hasSizeGreaterThanOrEqualTo(40);
        assertThat(param(result.getResponse().getRedirectedUrl(), "state")).isNotEqualTo(signIn.lastNonce);
    }

    @Test
    void theCallbackNeedsTheStateOfThisBrowserOnceAndWithinTenMinutes() throws Exception {
        // No session at all.
        assertThat(mvc.perform(get("/admin/login/callback").param("state", "x").param("code", "c"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // Wrong state.
        MockHttpSession wrong = new MockHttpSession();
        startLogin(wrong);
        assertThat(mvc.perform(get("/admin/login/callback").session(wrong).param("state", "other").param("code", "c"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // Reuse.
        MockHttpSession reuse = new MockHttpSession();
        String state = startLogin(reuse);
        assertThat(finishLogin(reuse, state).getResponse().getStatus()).isEqualTo(302);
        assertThat(finishLogin(reuse, state).getResponse().getStatus()).isEqualTo(400);
        // Expired.
        MockHttpSession late = new MockHttpSession();
        String lateState = startLogin(late);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(finishLogin(late, lateState).getResponse().getStatus()).isEqualTo(400);

        assertThat(signIn.exchangedCodes).hasSize(1);
    }

    @Test
    void aNonceThatDoesNotMatchIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = startLogin(session);
        signIn.wrongNonce = "someone-elses-nonce";

        MvcResult result = finishLogin(session, state);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(session.getAttribute("docs0.slackAdmin")).isNull();
    }

    @Test
    void cancellingOrAFailedExchangeSignsNobodyIn() throws Exception {
        MockHttpSession cancelled = new MockHttpSession();
        String state = startLogin(cancelled);
        MvcResult denied = mvc.perform(get("/admin/login/callback").session(cancelled).param("state", state)
                .param("error", "access_denied")).andReturn();
        assertThat(body(denied)).contains("cancelled");
        assertThat(cancelled.getAttribute("docs0.slackAdmin")).isNull();

        signIn.fails = true;
        MockHttpSession failed = new MockHttpSession();
        MvcResult result = finishLogin(failed, startLogin(failed));
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(failed.getAttribute("docs0.slackAdmin")).isNull();
    }

    // ---- who may sign in ----

    @Test
    void anEmailOnTheCompanysAdminListMaySignInWhateverItsCase() throws Exception {
        MockHttpSession session = signInAs("INA@acme.example", "T_ACME", "UINA");

        String page = mvc.perform(get("/admin/start").session(session).accept(HTML)).andReturn().getResponse()
                .getContentAsString();
        assertThat(page).contains("Acme Inc.");
    }

    @Test
    void aWorkspaceAdminOrOwnerAccordingToSlackMaySignInWithoutBeingOnTheList() throws Exception {
        slack.workspaceAdmins.add("UOWNER");

        MockHttpSession session = signInAs("owner@acme.example", "T_ACME", "UOWNER");

        assertThat(session.getAttribute("docs0.slackAdmin")).isNotNull();
    }

    @Test
    void anOrdinaryMemberIsToldTheyAreNotAnAdminAndGetsNoSession() throws Exception {
        signIn.signsInAs("intern@acme.example", "T_ACME", "UINTERN", "Ian");
        MockHttpSession session = new MockHttpSession();

        MvcResult result = finishLogin(session, startLogin(session));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).contains("You are not an admin").contains("Ask a Slack workspace admin");
        assertThat(session.getAttribute("docs0.slackAdmin")).isNull();
        assertThat(mvc.perform(get("/admin/start").session(session).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(302);
    }

    @Test
    void aWorkspaceDocs0DoesNotKnowCannotSignInEvenWithAMatchingEmail() throws Exception {
        signIn.signsInAs("ina@acme.example", "T_UNKNOWN", "UINA", "Ina");
        MockHttpSession session = new MockHttpSession();

        MvcResult result = finishLogin(session, startLogin(session));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).contains("not installed");
        assertThat(session.getAttribute("docs0.slackAdmin")).isNull();

        signIn.signsInAs("ina@acme.example", null, "UINA", "Ina");
        MockHttpSession noTeam = new MockHttpSession();
        assertThat(finishLogin(noTeam, startLogin(noTeam)).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anAdminEmailOfOneCompanyDoesNothingInAnotherCompanysWorkspace() throws Exception {
        oauth.next = new com.docs0.slack.SlackOAuth.Installed("T_OTHER", "Other Co", "xoxb-other", "UBOT2", "chat:write",
                "UX");
        install();
        // ina@acme.example is on Acme's list only.
        signIn.signsInAs("ina@acme.example", "T_OTHER", "UINA2", "Ina");
        MockHttpSession session = new MockHttpSession();

        MvcResult result = finishLogin(session, startLogin(session));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aWorkspaceThatRemovedDocs0CannotSignIn() throws Exception {
        workspaces.markRevoked("T_ACME");
        signIn.signsInAs("ina@acme.example", "T_ACME", "UINA", "Ina");
        MockHttpSession session = new MockHttpSession();

        MvcResult result = finishLogin(session, startLogin(session));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).contains("removed");
        assertThat(session.getAttribute("docs0.slackAdmin")).isNull();
    }

    // ---- the session ----

    @Test
    void theSessionIdChangesAtSignInHasAnEightHourIdleTimeoutAndHoldsOnlyThisTenant() throws Exception {
        signIn.signsInAs("ina@acme.example", "T_ACME", "UINA", "Ina");
        MockHttpSession session = new MockHttpSession();
        String state = startLogin(session);
        String before = session.getId();

        MvcResult result = finishLogin(session, state);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/start");
        assertThat(session.getId()).isNotEqualTo(before);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(8 * 60 * 60);
        assertThat(session.getAttribute("docs0.slackAdmin"))
                .isEqualTo(new com.docs0.web.admin.AdminPrincipal(acme.id(), "Ina", "ina@acme.example", "UINA"));
    }

    // ---- the session is checked again ----

    private int status(MockHttpSession session, String page) throws Exception {
        return mvc.perform(get(page).session(session).accept(HTML)).andReturn().getResponse().getStatus();
    }

    @Test
    void anAdminTakenOffTheListLosesTheSessionAtTheNextCheckWithinFiveMinutes() throws Exception {
        MockHttpSession session = signInAs("ina@acme.example", "T_ACME", "UINA");
        tenants.setAdminEmails(acme.id(), "boss@acme.example");

        // Checked at sign-in: the next five minutes are not asked again.
        clock.advance(Duration.ofMinutes(4));
        assertThat(status(session, "/admin/sources")).isEqualTo(200);

        clock.advance(Duration.ofMinutes(2));
        MvcResult after = mvc.perform(get("/admin/sources").session(session).accept(HTML)).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(302);
        assertThat(after.getResponse().getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aWorkspaceAdminWhoStoppedBeingOneLosesTheSessionToo() throws Exception {
        slack.workspaceAdmins.add("UOWNER");
        MockHttpSession session = signInAs("owner@acme.example", "T_ACME", "UOWNER");
        assertThat(status(session, "/admin/sources")).isEqualTo(200);
        slack.workspaceAdmins.remove("UOWNER");

        clock.advance(Duration.ofMinutes(6));

        assertThat(status(session, "/admin/sources")).isEqualTo(302);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anAdminWhoStaysAnAdminKeepsTheSessionUntilTheAbsoluteTwelveHours() throws Exception {
        MockHttpSession session = signInAs("ina@acme.example", "T_ACME", "UINA");

        for (int hour = 0; hour < 11; hour++) {
            clock.advance(Duration.ofHours(1));
            assertThat(status(session, "/admin/sources")).as("after %d hours", hour + 1).isEqualTo(200);
        }
        clock.advance(Duration.ofMinutes(61));

        // Still on the list and busy, but twelve hours after sign-in the session ends.
        MvcResult after = mvc.perform(get("/admin/sources").session(session).accept(HTML)).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(302);
        assertThat(after.getResponse().getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void whenSlackCannotBeReachedTheLastGoodCheckHoldsForFifteenMoreMinutesThenTheSessionEnds() throws Exception {
        slack.workspaceAdmins.add("UOWNER");
        MockHttpSession session = signInAs("owner@acme.example", "T_ACME", "UOWNER");
        slack.adminCheckFails = true;

        clock.advance(Duration.ofMinutes(6));
        assertThat(status(session, "/admin/sources")).isEqualTo(200);
        clock.advance(Duration.ofMinutes(13));
        assertThat(status(session, "/admin/sources")).isEqualTo(200);

        clock.advance(Duration.ofMinutes(2));
        assertThat(status(session, "/admin/sources")).isEqualTo(302);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void signingOutEndsTheSessionAndTheGuardSendsTheBrowserBackToSignIn() throws Exception {
        MockHttpSession session = signInAs("ina@acme.example", "T_ACME", "UINA");
        assertThat(mvc.perform(get("/admin/sources").session(session).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        // Without the CSRF token a logout is refused.
        assertThat(mvc.perform(post("/admin/logout").session(session)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        MvcResult out = mvc.perform(form("/admin/logout", session)).andReturn();

        assertThat(out.getResponse().getStatus()).isEqualTo(302);
        assertThat(session.isInvalid()).isTrue();
        MvcResult after = mvc.perform(get("/admin/sources").session(session).accept(HTML)).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(302);
        assertThat(after.getResponse().getRedirectedUrl()).isEqualTo("/admin/login");
    }

    // ---- the guard without a session ----

    @Test
    void unauthenticatedBrowsersAreRedirectedToSignInAndOtherClientsGetUnauthorized() throws Exception {
        for (String page : new String[] {"/admin/start", "/admin/sources", "/admin/costs", "/admin"}) {
            MvcResult browser = mvc.perform(get(page).accept(HTML)).andReturn();
            assertThat(browser.getResponse().getStatus()).as(page).isEqualTo(302);
            assertThat(browser.getResponse().getRedirectedUrl()).isEqualTo("/admin/login");
            assertThat(mvc.perform(get(page)).andReturn().getResponse().getStatus()).as(page).isEqualTo(401);
            assertThat(mvc.perform(get(page).accept("application/json")).andReturn().getResponse().getStatus())
                    .as(page).isEqualTo(401);
        }
        // A form post is never redirected; it is refused.
        assertThat(mvc.perform(post("/admin/sources/sync").accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void theLoopbackShortcutOfThePilotIsOffInMultiTenantMode() throws Exception {
        // MockMvc requests come from 127.0.0.1, which the pilot would let straight in.
        MvcResult result = mvc.perform(get("/admin/sources").accept(HTML).with(r -> {
            ((MockHttpServletRequest) r).setRemoteAddr("127.0.0.1");
            return r;
        })).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/login");
    }

    @Test
    void aForgedPrincipalOrTenantHeaderIsNotAnIdentity() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get("/admin/start").accept(HTML).header("X-Test-Tenant", acme.id()),
                get("/admin/start").accept(HTML).header("X-Forwarded-User", "ina@acme.example"),
                get("/admin/start").accept(HTML).param("tenant", Long.toString(acme.id())),
                get("/admin/start").accept(HTML).cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "forged"))}) {
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(302);
        }
    }

    // ---- a suspended tenant ----

    @Test
    void aSuspendedCompanysAdminsSignInButSeeOnlyTheSuspendedPage() throws Exception {
        MockHttpSession session = signInAs("ina@acme.example", "T_ACME", "UINA");
        tenants.suspend(acme.id());

        MvcResult start = mvc.perform(get("/admin/start").session(session).accept(HTML)).andReturn();
        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        assertThat(start.getResponse().getRedirectedUrl()).isEqualTo("/admin/suspended");
        MvcResult sources = mvc.perform(get("/admin/sources").session(session).accept(HTML)).andReturn();
        assertThat(sources.getResponse().getRedirectedUrl()).isEqualTo("/admin/suspended");
        assertThat(mvc.perform(post("/admin/sources/sync").session(session)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        MvcResult page = mvc.perform(get("/admin/suspended").session(session).accept(HTML)).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(page)).contains("This workspace is suspended").contains("Sign out")
                .doesNotContain("href=\"/admin/sources\"");

        // They can still sign out.
        String csrf = body(page).replaceAll("(?s).*name=\"_csrf\" value=\"([^\"]+)\".*", "$1");
        MvcResult out = mvc.perform(post("/admin/logout").session(session).param("_csrf", csrf)).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(302);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anAdminWhoseCompanyIsLiftedFromSuspensionIsBackAtOnce() throws Exception {
        MockHttpSession session = signInAs("ina@acme.example", "T_ACME", "UINA");
        tenants.suspend(acme.id());
        assertThat(mvc.perform(get("/admin/start").session(session).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(302);

        tenants.activate(acme.id());

        assertThat(mvc.perform(get("/admin/start").session(session).accept(HTML)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
