package com.docs0.web.admin;

import static com.docs0.source.slack.FakeSlack.msg;
import static com.docs0.source.slack.FakeSlack.root;
import static com.docs0.source.slack.FakeSlack.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.source.SyncService;
import com.docs0.source.slack.FakeSlack;
import com.docs0.source.slack.SlackChannel;
import com.docs0.source.slack.SlackHistoryConnection;
import com.docs0.source.slack.SlackHistoryConnector;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The Slack history section of the Sources page, with the Slack Web API faked. */
@AutoConfigureMockMvc
@Import(FakeSlackConfig.class)
@TestPropertySource(properties = "docs0.slack.bot-token=xoxb-test-token")
class SlackHistoryPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSlack slack;

    @Autowired
    SlackHistoryConnection connection;

    @Autowired
    SlackHistoryConnector connector;

    @Autowired
    SlackHistoryPanel panel;

    @Autowired
    SyncService syncService;

    @Autowired
    KnowledgeSearch search;

    MockHttpSession session;

    @BeforeEach
    void setUp() {
        slack.reset();
        panel.forget(tenant.id());
        session = new MockHttpSession();
        slack.channel("C1", "general", false, true).channel("C2", "random", false, false)
                .channel("G1", "leads", true, true).user("U1", "Priya", "priya@example.com");
    }

    @AfterEach
    void letSyncsFinish() {
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                connection.source(tenant.id()).map(s -> !syncService.isRunning(tenant.id(), s.id())).orElse(true));
    }

    @Test
    void listsTheChannelsTheBotCanSeeWithACheckboxEachAndExplainsPrivateOnes() throws Exception {
        String html = page("/admin/sources");

        assertThat(html).contains("id=\"slack\"", "id=\"slack-connected\"", "Slack is connected.");
        assertThat(html).contains("value=\"C1\"", "value=\"C2\"", "value=\"G1\"");
        assertThat(html).contains("action=\"/admin/sources/slack/channels\"");
        // The private channel carries the lock and the explanation; public ones do not.
        assertThat(html).contains("🔒", "only used in answers shown to people who are all in this channel",
                "/invite @docs0");
        assertThat(html).contains("Nothing is indexed until you tick at least one");
        assertThat(html).contains("Direct messages and group messages are never");
        assertThat(html).doesNotContain("checked=\"checked\"");
        assertThat(html).contains("<a href=\"#slack\">Slack history</a>");
    }

    @Test
    void savingStoresTheChosenChannelsWithTheirPrivateFlagAndIgnoresUnknownIds() throws Exception {
        mvc.perform(form("/admin/sources/slack/channels").param("choice", "C1", "G1", "C404", "D999"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources#slack"));

        assertThat(connection.channels(tenant.id())).containsExactlyInAnyOrder(new SlackChannel("C1", "general", false),
                new SlackChannel("G1", "leads", true));
        String html = page("/admin/sources");
        assertThat(html).contains("Saved. 2 channels are indexed at the next sync.");
        assertThat(html).containsPattern("value=\"C1\"[^>]*checked");
        assertThat(html).containsPattern("value=\"G1\"[^>]*checked");
        assertThat(html).doesNotContainPattern("value=\"C2\"[^>]*checked");
        // The stored shape is the documented one.
        assertThat(jdbc.sql("SELECT config::text FROM source WHERE kind = 'SLACK'").query(String.class).single())
                .contains("\"channels\"", "\"private\": true", "\"id\": \"G1\"");
    }

    @Test
    void savingNothingClearsTheSelection() throws Exception {
        connection.setChannels(tenant.id(), List.of(new SlackChannel("C1", "general", false)));

        mvc.perform(form("/admin/sources/slack/channels")).andExpect(status().isFound());

        assertThat(connection.channels(tenant.id())).isEmpty();
        assertThat(page("/admin/sources")).contains("No channels chosen. Nothing from Slack is indexed.");
    }

    @Test
    void aChosenPrivateChannelTheBotIsNotInShowsAnInviteHintAndStaysChosenWhileTicked() throws Exception {
        connection.setChannels(tenant.id(), List.of(new SlackChannel("G9", "secret", true)));

        String html = page("/admin/sources");

        assertThat(html).contains("invite @docs0 to #secret");
        mvc.perform(form("/admin/sources/slack/channels").param("choice", "G9", "C1")).andExpect(status().isFound());
        assertThat(connection.channels(tenant.id())).extracting(SlackChannel::id).containsExactlyInAnyOrder("C1", "G9");
        mvc.perform(form("/admin/sources/slack/channels").param("choice", "C1")).andExpect(status().isFound());
        assertThat(connection.channels(tenant.id())).extracting(SlackChannel::id).containsExactly("C1");
    }

    @Test
    void aPublicChannelNotYetJoinedSaysItWillBe() throws Exception {
        connection.setChannels(tenant.id(), List.of(new SlackChannel("C2", "random", false)));

        assertThat(page("/admin/sources")).contains("docs0 joins this channel at the next sync");
    }

    @Test
    void checkAccessCountsMessagesAndThreadsPerChosenChannel() throws Exception {
        Instant recent = Instant.now().minusSeconds(3600);
        slack.posts("C1", msg(ts(recent), "U1", "one"), msg(ts(recent.plusSeconds(1)), "U1", "two"),
                root(ts(recent.plusSeconds(2)), "U1", "three", 4, ts(recent.plusSeconds(9))));
        slack.failingHistory.add("G1");
        connection.setChannels(tenant.id(), List.of(new SlackChannel("C1", "general", false),
                new SlackChannel("C2", "random", false), new SlackChannel("G1", "leads", true),
                new SlackChannel("G9", "gone", true)));

        mvc.perform(form("/admin/sources/slack/check")).andExpect(status().isFound());
        String html = page("/admin/sources");

        assertThat(html).contains("#general: 3 messages, 1 thread in the last 30 days");
        assertThat(html).contains("#random: docs0 has not joined yet");
        assertThat(html).contains("#leads: could not be read");
        assertThat(html).contains("#gone: not visible to docs0 (invite @docs0 to #gone)");
        assertThat(slack.historyCalls).containsExactly("C1", "G1");
        assertThat(slack.joins).isEmpty(); // checking never joins anything
    }

    @Test
    void checkAccessNeedsAChosenChannel() throws Exception {
        mvc.perform(form("/admin/sources/slack/check")).andExpect(status().isFound());

        assertThat(page("/admin/sources")).contains("Choose at least one channel first.");
        assertThat(slack.historyCalls).isEmpty();
    }

    @Test
    void syncNowRunsInTheBackgroundAndTheDocumentsAppear() throws Exception {
        Instant recent = Instant.now().minusSeconds(3600);
        slack.posts("C1", msg(ts(recent), "U1", "the okapi launch is thursday"));
        connection.setChannels(tenant.id(), List.of(new SlackChannel("C1", "general", false)));

        mvc.perform(form("/admin/sources/slack/sync")).andExpect(status().isFound());

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                !search.search(tenant.id(), Audience.sharedOnly(), "okapi launch", 5).hits().isEmpty());
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                connection.source(tenant.id()).map(s -> !syncService.isRunning(tenant.id(), s.id())).orElse(false));
        String html = page("/admin/sources");
        assertThat(html).containsPattern("1</span>\\s*added").contains("Complete");
        assertThat(html).containsPattern("1\\s*</span>\\s*documents indexed");
        assertThat(html).contains("<td>Slack history</td>");
    }

    @Test
    void syncNowWithNothingChosenSaysSo() throws Exception {
        mvc.perform(form("/admin/sources/slack/sync")).andExpect(status().isFound());

        assertThat(page("/admin/sources")).contains("Choose at least one channel first.");
    }

    @Test
    void theEmailScopeWarningShowsOnlyWhenTheScopeIsMissing() throws Exception {
        slack.scopes = java.util.Set.of("users:read", "channels:read");
        assertThat(page("/admin/sources")).contains("id=\"slack-email-warning\"", "users:read.email");

        slack.scopes = java.util.Set.of("users:read", "users:read.email");
        assertThat(page("/admin/sources")).doesNotContain("id=\"slack-email-warning\"");

        slack.scopes = java.util.Set.of(); // Slack did not say: no false alarm
        assertThat(page("/admin/sources")).doesNotContain("id=\"slack-email-warning\"");
    }

    @Test
    void ifSlackCannotBeAskedThePageSaysSoAndOffersNoChecklist() throws Exception {
        slack.listFails = true;

        String html = page("/admin/sources");

        assertThat(html).contains("id=\"slack-error\"", "Slack could not be reached");
        assertThat(html).doesNotContain("id=\"slack-choices\"");
        mvc.perform(form("/admin/sources/slack/channels").param("choice", "C1")).andExpect(status().isFound());
        assertThat(connection.channels(tenant.id())).isEmpty();
        assertThat(page("/admin/sources")).contains("Could not ask Slack for its channels, so nothing was saved.");
    }

    @Test
    void channelNamesAndNotesAreEscaped() throws Exception {
        slack.channel("C7", "<script>alert(1)</script>", false, true);

        String html = page("/admin/sources");

        assertThat(html).doesNotContain("<script>alert(1)").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void everyPostNeedsTheCsrfTokenAndEveryFormCarriesIt() throws Exception {
        String csrf = csrf();
        for (String path : List.of("/admin/sources/slack/channels", "/admin/sources/slack/check",
                "/admin/sources/slack/sync")) {
            mvc.perform(post(path).session(session).param("choice", "C1")).andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).param("_csrf", "wrong").param("choice", "C1"))
                    .andExpect(status().isForbidden());
        }
        assertThat(connection.channels(tenant.id())).isEmpty();

        connection.setChannels(tenant.id(), List.of(new SlackChannel("C1", "general", false)));
        String html = page("/admin/sources");
        int forms = html.split("<form ", -1).length - 1;
        int tokens = html.split("name=\"_csrf\" value=\"" + csrf + "\"", -1).length - 1;
        assertThat(tokens).isEqualTo(forms);
    }

    @Test
    void theStatusPageAndHealthListSlackHistory() throws Exception {
        assertThat(mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("Slack history");
        String health = mvc.perform(get("/health")).andReturn().getResponse().getContentAsString();
        assertThat(health).contains("Slack history");
    }

    // --- helpers -----------------------------------------------------------------------------

    private String page(String url) throws Exception {
        return mvc.perform(get(java.net.URI.create(url)).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String csrf() throws Exception {
        page("/admin/sources");
        return (String) session.getAttribute("docs0.csrf");
    }

    private MockHttpServletRequestBuilder form(String path) throws Exception {
        return post(path).session(session).param("_csrf", csrf());
    }
}
