package com.docs0.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.Phase6IntegrationTest;
import com.docs0.slack.SlackWorkspaces;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceRepository;
import com.docs0.source.drive.DriveConnection;
import com.docs0.source.drive.DriveConnector;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.gmail.GmailConnection;
import com.docs0.source.gmail.GmailConnector;
import com.docs0.source.gmail.GmailKey;
import com.docs0.source.gmail.TestKeys;
import com.docs0.source.grain.FakeGrainClient;
import com.docs0.source.grain.GrainConnection;
import com.docs0.source.grain.GrainConnector;
import com.docs0.source.linear.FakeLinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearConnector;
import com.docs0.source.slack.SlackChannel;
import com.docs0.source.slack.SlackHistoryConnection;
import com.docs0.source.slack.SlackHistoryConnector;
import com.docs0.source.trello.FakeTrelloClient;
import com.docs0.source.trello.TrelloConnection;
import com.docs0.source.trello.TrelloConnector;
import com.docs0.web.admin.FakeGoogleConfig;
import com.docs0.web.admin.FakeToolClientsConfig;
import com.docs0.web.admin.TwoTenantAdminConfig;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * "Sync now" with DOCS0_SLACK_TRANSPORT=http and no sync job: the sync runs inside the request (a background thread
 * would be frozen once the response is sent), on every Sources section.
 */
@Import({FakeGoogleConfig.class, FakeToolClientsConfig.class})
@TestPropertySource(properties = {
        "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=",
        "docs0.google.oauth-client-id=" + DriveTestSetup.CLIENT_ID,
        "docs0.google.oauth-client-secret=" + DriveTestSetup.CLIENT_SECRET,
        "docs0.slack.bot-token=xoxb-test-token",
        "docs0.slack.transport=http", "docs0.slack.signing-secret=testsecret"})
class SyncNowHttpTransportTest extends Phase6IntegrationTest {

    @MockitoBean JobLauncher launcher;
    /** The gateway registers the pilot workspace at startup in http mode: keep that off the real Slack API. */
    @MockitoBean com.docs0.tenant.local.LocalSlackPilot pilot;

    @Autowired MockMvc mvc;
    @Autowired SourceRepository sources;
    @Autowired DriveConnection drive;
    @Autowired DriveConnector driveConnector;
    @Autowired LinearConnection linear;
    @Autowired LinearConnector linearConnector;
    @Autowired TrelloConnection trello;
    @Autowired TrelloConnector trelloConnector;
    @Autowired GrainConnection grain;
    @Autowired GrainConnector grainConnector;
    @Autowired GmailConnection gmail;
    @Autowired GmailConnector gmailConnector;
    @Autowired SlackHistoryConnection slackHistory;
    @Autowired SlackHistoryConnector slackConnector;
    @Autowired SlackWorkspaces workspaces;

    MockHttpSession session;

    /** One Sources section: how to connect it for a tenant, where its button posts, and its connector. */
    record Section(String name, String path, Consumer<Long> connect, SourceConnector connector, String running) {}

    List<Section> sections;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        sections = List.of(
                new Section("drive", "/admin/sources/sync", t -> drive.connect(t, "asha@example.com", "refresh-1"),
                        driveConnector, "A sync is already running."),
                new Section("linear", "/admin/sources/linear/sync", t -> linear.connect(t, "Acme", FakeLinearClient.GOOD_KEY),
                        linearConnector, "A Linear sync is already running."),
                new Section("trello", "/admin/sources/trello/sync",
                        t -> trello.connect(t, "Asha", FakeTrelloClient.GOOD_KEY, FakeTrelloClient.GOOD_TOKEN),
                        trelloConnector, "A Trello sync is already running."),
                new Section("grain", "/admin/sources/grain/sync", t -> grain.connect(t, "Acme", FakeGrainClient.GOOD_TOKEN),
                        grainConnector, "A Grain sync is already running."),
                new Section("gmail", "/admin/sources/gmail/sync",
                        t -> gmail.connect(t, new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID), TestKeys.json()),
                        gmailConnector, "A Gmail sync is already running."),
                new Section("slack", "/admin/sources/slack/sync",
                        t -> slackHistory.setChannels(t, List.of(new SlackChannel("C1", "general", false))),
                        slackConnector, "A Slack history sync is already running."));
    }

    private MockHttpServletRequestBuilder click(String path, long asTenant) throws Exception {
        mvc.perform(get("/admin/sources").header(TwoTenantAdminConfig.HEADER, asTenant).session(session))
                .andExpect(status().isOk());
        return post(path).header(TwoTenantAdminConfig.HEADER, asTenant).session(session)
                .param("_csrf", (String) session.getAttribute("docs0.csrf"));
    }

    private long sourceOf(long tenantId, SourceConnector connector) {
        return sources.find(tenantId, connector.kind(), connector.name()).orElseThrow().id();
    }

    private long syncRuns() {
        return jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single();
    }

    private SyncRunRow lastRun(SourceConnector connector) {
        return jdbc.sql("SELECT status, finished_at IS NOT NULL FROM sync_run WHERE source_id = :s")
                .param("s", sourceOf(tenant.id(), connector)).query((rs, n) -> new SyncRunRow(rs.getString(1), rs.getBoolean(2)))
                .single();
    }

    record SyncRunRow(String status, boolean finished) {}

    @Test
    void everySectionSyncsInsideTheRequestSoNothingIsLeftRunningAfterTheResponse() throws Exception {
        for (Section section : sections) {
            section.connect().accept(tenant.id());

            mvc.perform(click(section.path(), tenant.id())).andExpect(status().isFound());

            // No waiting: in hosted mode a thread left running after the response would be frozen.
            SyncRunRow run = lastRun(section.connector());
            assertThat(run.finished()).as(section.name()).isTrue();
            assertThat(run.status()).as(section.name()).isIn("OK", "FAILED");
            verify(launcher, never()).runSync(anyLong(), anyLong());
        }
    }

    @Autowired javax.sql.DataSource dataSource;

    @Test
    void aSyncHeldByAnotherProcessIsNotStartedAgain() throws Exception {
        linear.connect(tenant.id(), "Acme", FakeLinearClient.GOOD_KEY);
        long source = sourceOf(tenant.id(), linearConnector);

        try (com.docs0.source.SyncLock.Held other = new com.docs0.source.SyncLock(dataSource).tryLock(tenant.id(), source)) {
            assertThat(other).isNotNull();
            mvc.perform(click("/admin/sources/linear/sync", tenant.id())).andExpect(status().isFound());
        }

        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isZero();
        assertThat(mvc.perform(get("/admin/sources").header(TwoTenantAdminConfig.HEADER, tenant.id()).session(session))
                .andReturn().getResponse().getContentAsString()).contains("A Linear sync is already running.");
    }
}
