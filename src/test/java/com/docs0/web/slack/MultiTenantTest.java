package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.docs0.IntegrationTest;
import com.docs0.slack.FakeSlackOAuth;
import com.docs0.slack.FakeSlackSignIn;
import com.docs0.slack.SlackInstaller;
import com.docs0.slack.SlackWorkspaces;
import com.docs0.source.slack.FakeSlack;
import com.docs0.web.admin.FakeSlackConfig;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for the multi-tenant tests: the three Slack app settings are set, a public https URL, an encryption key,
 * and Slack's OAuth, sign-in and Web API are fakes. All of them share one application context.
 */
@AutoConfigureMockMvc
@Import({FakeSlackConfig.class, MultiTenantTestConfig.class})
@TestPropertySource(properties = {
        "docs0.slack.client-id=client-id-1",
        "docs0.slack.client-secret=client-secret-1",
        "docs0.slack.signing-secret=signing-secret-1",
        "docs0.public-url=https://docs0.example.test",
        "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE="
})
public abstract class MultiTenantTest extends IntegrationTest {

    protected static final String HTML = "text/html";

    @Autowired protected MockMvc mvc;
    @Autowired protected FakeSlackOAuth oauth;
    @Autowired protected FakeSlackSignIn signIn;
    @Autowired protected FakeSlack slack;
    @Autowired protected MutableClock clock;
    @Autowired protected SlackInstaller installer;
    @Autowired protected SlackWorkspaces workspaces;

    @BeforeEach
    void resetFakes() {
        oauth.reset();
        signIn.reset();
        slack.reset();
        clock.reset();
    }

    /** The value of a query parameter of a redirect. */
    protected static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(URI.create(url).getRawQuery() == null ? ""
                : "?" + URI.create(url).getRawQuery());
        return m.find() ? m.group(1) : null;
    }

    /** Starts Add to Slack in this browser session and returns the state Slack was given. */
    protected String startInstall(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/slack/install").session(session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        return param(result.getResponse().getRedirectedUrl(), "state");
    }

    /** A whole successful install; returns the response of the callback. */
    protected MvcResult install() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = startInstall(session);
        return mvc.perform(get("/slack/oauth/callback").session(session).param("state", state).param("code", "c1"))
                .andReturn();
    }

    /** Starts sign-in and returns the state. */
    protected String startLogin(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/admin/login").session(session).accept(HTML)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        return param(result.getResponse().getRedirectedUrl(), "state");
    }

    protected MvcResult finishLogin(MockHttpSession session, String state) throws Exception {
        return mvc.perform(get("/admin/login/callback").session(session).param("state", state).param("code", "c1"))
                .andReturn();
    }

    /** Signs in with Slack as this person and returns the signed-in session (the id may differ from the one used). */
    protected MockHttpSession signInAs(String email, String team, String userId) throws Exception {
        signIn.signsInAs(email, team, userId, "Name " + userId);
        MockHttpSession session = new MockHttpSession();
        MvcResult result = finishLogin(session, startLogin(session));
        assertThat(result.getResponse().getStatus()).as("sign-in of " + userId).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/start");
        return session;
    }

    protected static MockHttpServletRequestBuilder browser(MockHttpServletRequestBuilder request) {
        return request.accept(HTML);
    }

    protected String csrf(MockHttpSession session) throws Exception {
        String html = mvc.perform(get("/admin/start").session(session).accept(HTML)).andReturn().getResponse()
                .getContentAsString();
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).as("a form with a CSRF token on /admin/start").isTrue();
        return m.group(1);
    }

    protected MockHttpServletRequestBuilder form(String url, MockHttpSession session) throws Exception {
        return post(url).session(session).param("_csrf", csrf(session));
    }
}
