package com.docs0.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
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
 * "Sync now" with DOCS0_SYNC_JOB_NAME set: on every Sources section the click asks the job launcher for the admin's own
 * tenant and source and syncs nothing in this process.
 */
@Import({FakeGoogleConfig.class, FakeToolClientsConfig.class})
@TestPropertySource(properties = {
        "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=",
        "docs0.google.oauth-client-id=" + DriveTestSetup.CLIENT_ID,
        "docs0.google.oauth-client-secret=" + DriveTestSetup.CLIENT_SECRET,
        "docs0.slack.bot-token=xoxb-test-token",
        "docs0.hosted.sync-job-name=docs0-sync", "docs0.hosted.gcp-project=p", "docs0.hosted.gcp-region=r"})
class SyncNowHostedTest extends Phase6IntegrationTest {

    static final String REQUESTED = "A sync was requested. It starts in a few seconds; this page refreshes while it runs.";

    @MockitoBean JobLauncher launcher;

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

    @Test
    void everySectionAsksTheJobForTheAdminsOwnTenantAndSourceAndSyncsNothingHere() throws Exception {
        for (Section section : sections) {
            section.connect().accept(tenant.id());
            org.mockito.Mockito.clearInvocations(launcher);

            mvc.perform(click(section.path(), tenant.id())).andExpect(status().isFound());

            verify(launcher).runSync(tenant.id(), sourceOf(tenant.id(), section.connector()));
            org.mockito.Mockito.verifyNoMoreInteractions(launcher);
            assertThat(syncRuns()).as(section.name()).isZero();
        }
    }

    @Test
    void thePageSaysTheSyncWasRequested() throws Exception {
        for (Section section : sections) {
            section.connect().accept(tenant.id());
            mvc.perform(click(section.path(), tenant.id())).andExpect(status().isFound());
            String html = mvc.perform(get("/admin/sources").header(TwoTenantAdminConfig.HEADER, tenant.id())
                    .session(session)).andReturn().getResponse().getContentAsString();
            assertThat(html).as(section.name()).contains(REQUESTED);
        }
    }

    @Test
    void whenTheJobCannotBeStartedThePageSaysSoAndHowItWillRunAnyway() throws Exception {
        for (Section section : sections) {
            section.connect().accept(tenant.id());
            doThrow(new JobLauncher.LaunchFailedException("Cloud Run answered 503")).when(launcher)
                    .runSync(anyLong(), anyLong());
            mvc.perform(click(section.path(), tenant.id())).andExpect(status().isFound());
            String html = mvc.perform(get("/admin/sources").header(TwoTenantAdminConfig.HEADER, tenant.id())
                    .session(session)).andReturn().getResponse().getContentAsString();

            assertThat(html).as(section.name()).contains(
                    "Could not start the sync job (Cloud Run answered 503). It will run at the next scheduled time.")
                    .doesNotContain(REQUESTED);
        }
    }

    @Test
    void aSyncAlreadyRunningInAnotherProcessIsNotStartedAgain() throws Exception {
        for (Section section : sections) {
            section.connect().accept(tenant.id());
            long source = sourceOf(tenant.id(), section.connector());
            jdbc.sql("INSERT INTO sync_run (tenant_id, source_id, status) VALUES (:t, :s, 'RUNNING')")
                    .param("t", tenant.id()).param("s", source).update();
            org.mockito.Mockito.clearInvocations(launcher);

            mvc.perform(click(section.path(), tenant.id())).andExpect(status().isFound());

            verify(launcher, never()).runSync(anyLong(), anyLong());
            assertThat(mvc.perform(get("/admin/sources").header(TwoTenantAdminConfig.HEADER, tenant.id())
                    .session(session)).andReturn().getResponse().getContentAsString())
                    .as(section.name()).contains(section.running());
            jdbc.sql("DELETE FROM sync_run").update();
        }
    }

    @Test
    void theSourcesPageShowsASyncRunningInAnotherProcessAndHidesTheButton() throws Exception {
        linear.connect(tenant.id(), "Acme", FakeLinearClient.GOOD_KEY);
        long source = sourceOf(tenant.id(), linearConnector);
        jdbc.sql("INSERT INTO sync_run (tenant_id, source_id, status) VALUES (:t, :s, 'RUNNING')")
                .param("t", tenant.id()).param("s", source).update();

        String html = mvc.perform(get("/admin/sources").header(TwoTenantAdminConfig.HEADER, tenant.id())
                .session(session)).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Sync running").contains("http-equiv=\"refresh\"").contains("Running");
        assertThat(html).doesNotContain("action=\"/admin/sources/linear/sync\"");
    }

    @Test
    void aTenantCanOnlyEverRequestItsOwnSource() throws Exception {
        long other = tenants.create("other", "Other").id();
        linear.connect(other, "Other Co", FakeLinearClient.GOOD_KEY);
        long othersSource = sourceOf(other, linearConnector);

        // The first tenant has not connected Linear: nothing is launched, not even for the other tenant's source.
        mvc.perform(click("/admin/sources/linear/sync", tenant.id())).andExpect(status().isFound());
        verify(launcher, never()).runSync(anyLong(), anyLong());

        mvc.perform(click("/admin/sources/linear/sync", other)).andExpect(status().isFound());
        verify(launcher).runSync(other, othersSource);
        verify(launcher, never()).runSync(tenant.id(), othersSource);
    }
}
