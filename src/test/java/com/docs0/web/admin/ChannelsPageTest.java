package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.settings.ChannelSettings;
import com.docs0.source.slack.FakeSlack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

class ChannelsPageTest extends com.docs0.Phase6IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSlack slack;

    @Autowired
    ChannelSettings settings;

    @BeforeEach
    void workspace() {
        slack.reset();
        slack.channel("C1", "general", false, true).channel("C2", "random", false, true)
                .channel("C3", "leads", true, true).channel("C4", "newcomers", false, false);
    }

    private String page() throws Exception {
        return mvc.perform(get("/admin/channels")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private String csrf(MockHttpSession session) throws Exception {
        String html = mvc.perform(get("/admin/channels").session(session)).andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void listsTheChannelsTheBotCanSeeWithNothingTickedByDefault() throws Exception {
        String html = page();

        assertThat(html).contains("#general").contains("#random").contains("🔒 leads").contains("#newcomers")
                .contains("docs0 is not in this channel yet").contains("Answer repeat questions")
                .contains("at most\n      <b>3</b> times an hour").contains("giving its answer a 👎");
        assertThat(html).doesNotContain("checked=\"checked\"");
        assertThat(html).contains("href=\"/admin/channels\" class=\"on\"");
    }

    @Test
    void savingTicksAndUnticksChannelsAndTheTickStaysOnThePage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);

        mvc.perform(post("/admin/channels").session(session).param("_csrf", token).param("proactive", "C1", "C3"))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.proactiveChannels(tenant.id())).containsExactlyInAnyOrder("C1", "C3");
        String html = page();
        assertThat(html.split("checked=\"checked\"", -1).length - 1).isEqualTo(2);

        mvc.perform(post("/admin/channels").session(session).param("_csrf", token).param("proactive", "C2"))
                .andExpect(status().is3xxRedirection());
        assertThat(settings.proactiveChannels(tenant.id())).containsExactly("C2");
        mvc.perform(post("/admin/channels").session(session).param("_csrf", token)).andExpect(status().is3xxRedirection());
        assertThat(settings.proactiveChannels(tenant.id())).isEmpty();
    }

    @Test
    void aSaveWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/admin/channels").param("proactive", "C1")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/channels").param("_csrf", "wrong").param("proactive", "C1"))
                .andExpect(status().isForbidden());

        assertThat(settings.proactiveChannels(tenant.id())).isEmpty();
    }

    @Test
    void channelNamesAreEscaped() throws Exception {
        slack.channel("C5", "<script>alert(1)</script>", false, true);

        String html = page();

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void whenSlackCannotBeReachedThePageSaysSoAndSavingChangesNothing() throws Exception {
        settings.saveProactive(tenant.id(), java.util.List.of(new ChannelSettings.Channel("C1", "general")), java.util.Set.of("C1"));
        slack.listFails = true;
        MockHttpSession session = new MockHttpSession();
        // Obtain a token while Slack works, then break it.
        slack.listFails = false;
        String token = csrf(session);
        slack.listFails = true;

        assertThat(page()).contains("Slack could not be reached");
        mvc.perform(post("/admin/channels").session(session).param("_csrf", token))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.proactiveChannels(tenant.id())).containsExactly("C1");
    }

    @Test
    void anEmptyWorkspaceSaysSo() throws Exception {
        slack.channels.clear();

        assertThat(page()).contains("can't see any channels yet");
    }

    @Test
    void theChannelsPageIsForThisMachineOnlyWithoutAPassword() throws Exception {
        mvc.perform(get("/admin/channels").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        })).andExpect(status().isForbidden());
    }
}
