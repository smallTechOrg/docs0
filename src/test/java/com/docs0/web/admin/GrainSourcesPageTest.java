package com.docs0.web.admin;

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
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.grain.FakeGrainClient;
import com.docs0.source.grain.GrainConnection;
import com.docs0.source.grain.GrainConnector;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The Grain section of the Sources page, with Grain's network edge faked. */
@AutoConfigureMockMvc
@Import(FakeToolClientsConfig.class)
@TestPropertySource(properties = "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=")
class GrainSourcesPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeGrainClient grainApi;

    @Autowired
    GrainConnection grain;

    @Autowired
    GrainConnector grainConnector;

    @Autowired
    SyncService syncService;

    @Autowired
    KnowledgeSearch search;

    MockHttpSession session;

    @BeforeEach
    void setUp() {
        assertThat(DriveTestSetup.ENCRYPTION_KEY).isEqualTo("ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=");
        grainApi.reset();
        session = new MockHttpSession();
    }

    @AfterEach
    void letSyncsFinish() {
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                grain.source(tenant.id()).map(s -> !syncService.isRunning(s.id())).orElse(true));
    }

    @Test
    void notConnectedShowsTheStepsAPasswordFieldAndTheJumpLink() throws Exception {
        String html = page("/admin/sources");

        assertThat(html).contains("id=\"grain-not-connected\"", "Settings → Integrations → Public API",
                "workspace access token", "personal access token", "<input type=\"password\" name=\"token\"",
                "action=\"/admin/sources/grain/connect\"", "href=\"#grain\"", "last <span>30</span> days");
        assertThat(html).doesNotContain("id=\"grain-connected\"");
        assertThat(grainApi.tokensSeen).isEmpty();
    }

    @Test
    void aTokenGrainRejectsIsNotStoredAndTheAdminIsToldSo() throws Exception {
        grainApi.acceptedToken = FakeGrainClient.GOOD_TOKEN;

        MvcResult result = mvc.perform(form("/admin/sources/grain/connect").param("token", "grain_pat_wrong"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources#grain")).andReturn();

        assertThat(follow(result)).contains("Grain did not accept that token").contains("Nothing was saved")
                .contains("id=\"grain-not-connected\"").doesNotContain("grain_pat_wrong");
        assertThat(credentialRows()).isZero();
    }

    @Test
    void anEmptyTokenIsRefusedWithoutCallingGrain() throws Exception {
        MvcResult result = mvc.perform(form("/admin/sources/grain/connect").param("token", "  "))
                .andExpect(status().isFound()).andReturn();

        assertThat(follow(result)).contains("Paste your Grain access token first.");
        assertThat(credentialRows()).isZero();
        assertThat(grainApi.tokensSeen).isEmpty();
    }

    @Test
    void aValidTokenIsStoredEncryptedAndTheAccountNameShownButNotTheToken() throws Exception {
        grainApi.acceptedToken = FakeGrainClient.GOOD_TOKEN;
        grainApi.account = new com.docs0.source.grain.GrainClient.Account("<b>Acme</b> & Co", "workspace");

        String html = follow(mvc.perform(form("/admin/sources/grain/connect")
                .param("token", "  " + FakeGrainClient.GOOD_TOKEN + " ")).andExpect(status().isFound()).andReturn());

        assertThat(credentialRows()).isEqualTo(1);
        String stored = jdbc.sql("SELECT CAST(source_credential AS text) FROM source_credential").query(String.class).single();
        assertThat(stored).doesNotContain(FakeGrainClient.GOOD_TOKEN).doesNotContain("grain_pat");
        assertThat(grain.token(tenant.id())).isEqualTo(FakeGrainClient.GOOD_TOKEN);
        assertThat(html).contains("id=\"grain-connected\"").contains("Connected to <b>&lt;b&gt;Acme&lt;/b&gt; &amp; Co</b>")
                .contains("not shown").doesNotContain(FakeGrainClient.GOOD_TOKEN)
                .doesNotContain("<b>Acme</b> & Co").doesNotContain("id=\"grain-not-connected\"");
        assertThat(html).doesNotContain("id=\"grain-choices\"").doesNotContain("Nothing is indexed until you tick");
    }

    @Test
    void checkAccessCountsTheRecordingsInTheWindow() throws Exception {
        connect();
        Instant start = Instant.now().minusSeconds(86400);
        grainApi.recordings.put("r1", FakeGrainClient.recording("r1", "One", start, start.plusSeconds(600)));
        grainApi.recordings.put("r2", FakeGrainClient.recording("r2", "Two", start, start.plusSeconds(600)));
        grainApi.recordings.put("old", FakeGrainClient.recording("old", "Old", Instant.now().minusSeconds(90 * 86400L),
                Instant.now().minusSeconds(90 * 86400L - 600)));

        String html = follow(mvc.perform(form("/admin/sources/grain/check")).andExpect(status().isFound()).andReturn());

        assertThat(html).contains("docs0 can read 2 recordings from the last 30 days.");
    }

    @Test
    void checkAccessReportsARejectedTokenWithTheReconnectMessage() throws Exception {
        connect();
        grainApi.acceptedToken = "something-else";

        String html = follow(mvc.perform(form("/admin/sources/grain/check")).andReturn());

        assertThat(html).contains("Grain rejected the access token. Reconnect Grain on the Sources page.");
    }

    @Test
    void syncNowRunsInTheBackgroundAndThePageShowsTheResultAndTheCountsPerKind() throws Exception {
        connect();
        Instant start = Instant.now().minusSeconds(86400);
        grainApi.recordings.put("r1", FakeGrainClient.recording("r1", "Launch sync", start, start.plusSeconds(600)));

        mvc.perform(form("/admin/sources/grain/sync")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/sources#grain"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !syncService.isRunning(grain.source(tenant.id()).orElseThrow().id()));

        String html = page("/admin/sources");
        assertThat(html).contains("<span>1</span> added").contains("Complete").contains("<span>1</span> documents indexed");
        String counts = html.substring(html.indexOf("id=\"kind-counts\""), html.indexOf("id=\"drive\""));
        assertThat(counts).contains("<td>Grain</td><td>1</td>");
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "launch", 5).hits()).hasSize(1);
    }

    @Test
    void disconnectAsksFirstThenDeletesTheTokenAndTheDocuments() throws Exception {
        connect();
        Instant start = Instant.now().minusSeconds(86400);
        grainApi.recordings.put("r1", FakeGrainClient.recording("r1", "Launch sync", start, start.plusSeconds(600)));
        syncService.run(grainConnector);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        String ask = page("/admin/sources?confirm=grain");
        assertThat(ask).contains("id=\"grain-confirm-disconnect\"").contains("Disconnect Grain?")
                .contains("action=\"/admin/sources/grain/disconnect\"");
        assertThat(credentialRows()).isEqualTo(1);

        MvcResult done = mvc.perform(form("/admin/sources/grain/disconnect")).andExpect(status().isFound()).andReturn();

        assertThat(credentialRows()).isZero();
        assertThat(search.count(tenant.id())).isZero();
        assertThat(follow(done)).contains("Grain disconnected.").contains("id=\"grain-not-connected\"");
        assertThat(grainConnector.status().detail()).isEqualTo("not connected — connect it on the Sources page");
    }

    @Test
    void theStatusPageListsGrain() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Grain", "not connected");
    }

    @Test
    void everyPostNeedsTheCsrfTokenAndEveryFormCarriesIt() throws Exception {
        String csrf = csrf();
        for (String path : java.util.List.of("/admin/sources/grain/connect", "/admin/sources/grain/check",
                "/admin/sources/grain/sync", "/admin/sources/grain/disconnect")) {
            mvc.perform(post(path).session(session).param("token", FakeGrainClient.GOOD_TOKEN))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).param("_csrf", "wrong").param("token", FakeGrainClient.GOOD_TOKEN))
                    .andExpect(status().isForbidden());
        }
        assertThat(credentialRows()).isZero();
        assertThat(grainApi.tokensSeen).isEmpty();

        connect();
        String html = page("/admin/sources?confirm=grain");
        int forms = html.split("<form ", -1).length - 1;
        int tokens = html.split("name=\"_csrf\" value=\"" + csrf + "\"", -1).length - 1;
        assertThat(tokens).isEqualTo(forms);
    }

    // --- helpers --------------------------------------------------------------------------

    private void connect() {
        grain.connect(tenant.id(), "Acme Workspace", FakeGrainClient.GOOD_TOKEN);
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(java.net.URI.create(url)).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String follow(MvcResult redirect) throws Exception {
        String location = redirect.getResponse().getRedirectedUrl();
        return page(location.contains("#") ? location.substring(0, location.indexOf('#')) : location);
    }

    private String csrf() throws Exception {
        page("/admin/sources");
        return (String) session.getAttribute("docs0.csrf");
    }

    private MockHttpServletRequestBuilder form(String path) throws Exception {
        return post(path).session(session).param("_csrf", csrf());
    }

    private long credentialRows() {
        return jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single();
    }
}
