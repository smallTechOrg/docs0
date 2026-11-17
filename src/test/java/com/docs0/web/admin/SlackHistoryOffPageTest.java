package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Without a Slack bot token the section explains the setup and nothing calls Slack. */
@AutoConfigureMockMvc
class SlackHistoryOffPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theSectionExplainsHowToConnectSlackAndHasNoForms() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = mvc.perform(get("/admin/sources").session(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        assertThat(html).contains("id=\"slack-off\"", "DOCS0_SLACK_BOT_TOKEN").doesNotContain("id=\"slack-choices\"")
                .doesNotContain("/admin/sources/slack/");
        String csrf = (String) session.getAttribute("docs0.csrf");
        mvc.perform(post("/admin/sources/slack/sync").session(session).param("_csrf", csrf)).andExpect(status().isFound());
        assertThat(mvc.perform(get("/admin/sources").session(session)).andReturn().getResponse().getContentAsString())
                .contains("Set DOCS0_SLACK_BOT_TOKEN first");
    }

    @Test
    void theStatusPageShowsSlackHistoryAsOff() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Slack history", "no Slack bot token configured");
    }
}
