package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.docs0.IntegrationTest;
import com.docs0.config.Docs0Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** With the three multi-tenant settings blank (the smallTech pilot), nothing about docs0 changes. */
@AutoConfigureMockMvc
class PilotModeTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired Docs0Properties props;

    @Test
    void theModeIsOffWhenTheSettingsAreBlankOrIncomplete() {
        assertThat(props.multiTenant()).isFalse();
        assertThat(new Docs0Properties.Slack("", "", "id", "secret", "").multiTenant()).isFalse();
        assertThat(new Docs0Properties.Slack("", "", "id", "", "sign").multiTenant()).isFalse();
        assertThat(new Docs0Properties.Slack("", "", "", "secret", "sign").multiTenant()).isFalse();
        assertThat(new Docs0Properties.Slack("", "", "id", "secret", "sign").multiTenant()).isTrue();
        assertThat(new Docs0Properties.Slack("xoxb", "xapp").multiTenant()).isFalse();
    }

    @Test
    void theRootIsStillTheStatusPageAndHealthStillReportsTheComponents() throws Exception {
        String home = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String health = mvc.perform(get("/health")).andReturn().getResponse().getContentAsString();

        assertThat(home).contains("Knowledge agent for").contains("smallTech").doesNotContain("Add to Slack");
        assertThat(health).contains("\"status\":\"UP\"").contains("\"tenant\":\"smallTech\"")
                .contains("\"components\"").contains("\"documents\"");
    }

    @Test
    void adminPagesAreStillServedToThisMachineWithoutAnyNavigationChange() throws Exception {
        for (String page : new String[] {"/admin/sources", "/admin/channels", "/admin/costs"}) {
            String html = mvc.perform(get(page)).andReturn().getResponse().getContentAsString();
            assertThat(html).as(page).doesNotContain("href=\"/admin/start\"").doesNotContain("/admin/logout");
        }
        assertThat(mvc.perform(get("/admin/sources")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/admin/sources").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        })).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anonymousHitsOnTheInstallAndSignInPagesCreateNoSessionOutsideMultiTenantMode() throws Exception {
        for (String page : new String[] {"/slack/install", "/admin/login", "/slack/oauth/callback", "/admin/login/callback"}) {
            MvcResult result = mvc.perform(get(page)).andReturn();
            assertThat(result.getRequest().getSession(false)).as(page).isNull();
        }
    }

    @Test
    void slackEventsRejectEverythingBecauseThereIsNoSigningSecret() throws Exception {
        assertThat(mvc.perform(post("/slack/events").contentType("application/json")
                .content("{\"type\":\"url_verification\",\"challenge\":\"abc\"}")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(post("/slack/interactions").contentType("application/x-www-form-urlencoded")
                .content("payload={}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        // Even a signature made with an empty key is no good.
        String ts = Long.toString(java.time.Instant.now().getEpochSecond());
        assertThat(mvc.perform(post("/slack/events").contentType("application/json").content("{}")
                        .header("X-Slack-Request-Timestamp", ts)
                        .header("X-Slack-Signature", "v0=" + "0".repeat(64))).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void addToSlackAndSignInWithSlackSayTheyAreNotEnabled() throws Exception {
        MvcResult install = mvc.perform(get("/slack/install")).andReturn();
        assertThat(install.getResponse().getStatus()).isEqualTo(404);
        assertThat(install.getResponse().getContentAsString()).contains("Add to Slack is not enabled");
        assertThat(mvc.perform(get("/slack/oauth/callback").param("code", "x").param("state", "y")).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult login = mvc.perform(get("/admin/login")).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(404);
        assertThat(login.getResponse().getContentAsString()).contains("not enabled");
        // The guard still protects the login paths in pilot mode.
        assertThat(mvc.perform(get("/admin/login").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        })).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void thePilotSessionCookieIsLeftAlone() throws Exception {
        MockServletContext context = new MockServletContext();
        new MultiTenantConfig(props).adminSessionCookie().onStartup(context);

        assertThat(context.getSessionCookieConfig().getAttribute("SameSite")).isNull();
        assertThat(context.getSessionCookieConfig().isSecure()).isFalse();
    }
}
