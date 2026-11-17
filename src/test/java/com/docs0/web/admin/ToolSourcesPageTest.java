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
import com.docs0.source.linear.FakeLinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearConnector;
import com.docs0.source.linear.LinearTeam;
import com.docs0.source.trello.FakeTrelloClient;
import com.docs0.source.trello.TrelloBoard;
import com.docs0.source.trello.TrelloConnection;
import com.docs0.source.trello.TrelloConnector;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The Linear and Trello sections of the Sources page, with both tools' network edges faked. */
@AutoConfigureMockMvc
@Import(FakeToolClientsConfig.class)
@TestPropertySource(properties = "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=")
class ToolSourcesPageTest extends IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeLinearClient linearApi;

    @Autowired
    FakeTrelloClient trelloApi;

    @Autowired
    LinearConnection linear;

    @Autowired
    TrelloConnection trello;

    @Autowired
    LinearConnector linearConnector;

    @Autowired
    TrelloConnector trelloConnector;

    @Autowired
    SyncService syncService;

    @Autowired
    KnowledgeSearch search;

    @Autowired
    ToolPanels panels;

    MockHttpSession session;

    @BeforeEach
    void setUp() {
        assertThat(DriveTestSetup.ENCRYPTION_KEY).isEqualTo("ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=");
        linearApi.reset();
        trelloApi.reset();
        panels.forget(tenant.id());
        session = new MockHttpSession();
    }

    /** A background sync must not outlive its test and write into the next one's tables. */
    @AfterEach
    void letSyncsFinish() {
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                linear.source(tenant.id()).map(s -> !syncService.isRunning(tenant.id(), s.id())).orElse(true)
                        && trello.source(tenant.id()).map(s -> !syncService.isRunning(tenant.id(), s.id())).orElse(true));
    }

    // --- not connected ---------------------------------------------------------------------

    @Test
    void notConnectedShowsTheStepsAndPasswordFieldsForBothTools() throws Exception {
        String html = page("/admin/sources");

        assertThat(html).contains("id=\"linear-not-connected\"", "id=\"trello-not-connected\"");
        assertThat(html).contains("Personal API keys", "Security &amp; access", "lin_api_");
        assertThat(html).contains("trello.com/power-ups/admin", "read and write");
        assertThat(html).contains("<input type=\"password\" name=\"apiKey\"", "<input type=\"password\" name=\"token\"");
        assertThat(html).contains("action=\"/admin/sources/linear/connect\"", "action=\"/admin/sources/trello/connect\"");
        assertThat(html).doesNotContain("id=\"linear-connected\"").doesNotContain("id=\"trello-connected\"");
        assertThat(linearApi.keysSeen).isEmpty();
    }

    @Test
    void theStatusPageListsBothTools() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Linear", "Trello", "not connected");
    }

    // --- Linear -------------------------------------------------------------------------------

    @Test
    void aKeyLinearRejectsIsNotStoredAndTheAdminIsToldSo() throws Exception {
        linearApi.acceptedKey = FakeLinearClient.GOOD_KEY;

        MvcResult result = mvc.perform(form("/admin/sources/linear/connect").param("apiKey", "lin_api_wrong"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources#linear")).andReturn();

        assertThat(follow(result)).contains("Linear did not accept that key").contains("Nothing was saved")
                .contains("id=\"linear-not-connected\"").doesNotContain("lin_api_wrong");
        assertThat(credentialRows()).isZero();
        assertThat(linear.credential(tenant.id())).isEmpty();
    }

    @Test
    void anEmptyKeyIsRefusedWithoutCallingLinear() throws Exception {
        MvcResult result = mvc.perform(form("/admin/sources/linear/connect").param("apiKey", "   "))
                .andExpect(status().isFound()).andReturn();

        assertThat(follow(result)).contains("Paste your Linear API key first.");
        assertThat(credentialRows()).isZero();
        assertThat(linearApi.keysSeen).isEmpty();
    }

    @Test
    void aValidKeyIsStoredEncryptedAndTheWorkspaceNameShown() throws Exception {
        linearApi.acceptedKey = FakeLinearClient.GOOD_KEY;

        MvcResult result = mvc.perform(form("/admin/sources/linear/connect")
                        .param("apiKey", "  " + FakeLinearClient.GOOD_KEY + "  "))
                .andExpect(status().isFound()).andReturn();
        String html = follow(result);

        assertThat(credentialRows()).isEqualTo(1);
        String stored = jdbc.sql("SELECT CAST(source_credential AS text) FROM source_credential")
                .query(String.class).single();
        assertThat(stored).doesNotContain(FakeLinearClient.GOOD_KEY).doesNotContain("lin_api");
        assertThat(linear.apiKey(tenant.id())).isEqualTo(FakeLinearClient.GOOD_KEY);
        assertThat(html).contains("Connected to Acme.").contains("id=\"linear-connected\"")
                .contains("Connected to <b>Acme</b>").contains("not shown")
                .doesNotContain(FakeLinearClient.GOOD_KEY).doesNotContain("id=\"linear-not-connected\"");
        assertThat(html).contains("Engineering (ENG)", "Operations (OPS)").doesNotContain("checked=\"checked\"");
    }

    @Test
    void savingTheTeamSelectionStoresKeyAndNameAndTheBoxesStayTicked() throws Exception {
        connectLinear();

        MvcResult result = mvc.perform(form("/admin/sources/linear/teams").param("choice", "team-eng")
                        .param("choice", "team-bogus"))
                .andExpect(status().isFound()).andReturn();
        String html = follow(result);

        assertThat(linear.teams(tenant.id())).containsExactly(new LinearTeam("team-eng", "ENG", "Engineering"));
        assertThat(jdbc.sql("SELECT config::text FROM source WHERE kind = 'LINEAR'").query(String.class).single())
                .contains("\"teams\"").contains("\"key\": \"ENG\"").contains("\"name\": \"Engineering\"")
                .doesNotContain("bogus");
        assertThat(html).contains("Saved. 1 team is indexed at the next sync.");
        String choices = html.substring(html.indexOf("id=\"linear-choices\""));
        assertThat(choices.substring(0, choices.indexOf("Save"))).contains("value=\"team-eng\" checked=\"checked\"")
                .doesNotContain("value=\"team-ops\" checked");

        mvc.perform(form("/admin/sources/linear/teams")).andExpect(status().isFound());
        assertThat(linear.teams(tenant.id())).isEmpty();
        assertThat(follow(mvc.perform(form("/admin/sources/linear/teams")).andReturn()))
                .contains("No teams chosen. Nothing from Linear is indexed.");
    }

    @Test
    void teamNamesAreEscaped() throws Exception {
        connectLinear();
        String nasty = "<script>alert(1)</script> \"R&D\"";
        linearApi.teams.add(new LinearTeam("team-x", "XSS", nasty));
        linear.setTeams(tenant.id(), List.of(new LinearTeam("team-x", "XSS", nasty)));

        String html = page("/admin/sources");

        assertThat(html).doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &quot;R&amp;D&quot; (XSS)");
    }

    @Test
    void checkAccessCountsWhatTheSelectionCanSee() throws Exception {
        connectLinear();
        linear.setTeams(tenant.id(), List.of(new LinearTeam("team-eng", "ENG", "Engineering")));
        linearApi.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "One", T0),
                FakeLinearClient.issue("i2", "ENG-2", "Two", T0)));

        String html = follow(mvc.perform(form("/admin/sources/linear/check")).andExpect(status().isFound()).andReturn());

        assertThat(html).contains("docs0 can read Engineering: 2 issues.");
        assertThat(credentialRows()).isEqualTo(1);
    }

    @Test
    void checkAccessReportsARejectedKeyWithTheReconnectMessage() throws Exception {
        connectLinear();
        linear.setTeams(tenant.id(), List.of(new LinearTeam("team-eng", "ENG", "Engineering")));
        linearApi.acceptedKey = "something-else";

        String html = follow(mvc.perform(form("/admin/sources/linear/check")).andReturn());

        assertThat(html).contains("Linear rejected the API key. Reconnect Linear on the Sources page.");
    }

    @Test
    void syncNowRunsInTheBackgroundAndThePageShowsTheResultAndTheCountsPerKind() throws Exception {
        connectLinear();
        linear.setTeams(tenant.id(), List.of(new LinearTeam("team-eng", "ENG", "Engineering")));
        linearApi.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "Fix login", T0)));

        mvc.perform(form("/admin/sources/linear/sync")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/sources#linear"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !syncService.isRunning(tenant.id(), linear.source(tenant.id()).orElseThrow().id()));

        String html = page("/admin/sources");
        assertThat(html).contains("<span>1</span> added").contains("Complete").contains("<span>1</span> documents indexed");
        assertThat(html).doesNotContain("http-equiv=\"refresh\"");
        String counts = html.substring(html.indexOf("id=\"kind-counts\""), html.indexOf("id=\"drive\""));
        assertThat(counts).contains("<td>Linear</td><td>1</td>");
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "login", 5).hits()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run r JOIN source s ON s.id = r.source_id WHERE s.kind = 'LINEAR'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFailedSyncShowsItsErrorInTheLog() throws Exception {
        connectLinear();
        linear.setTeams(tenant.id(), List.of(new LinearTeam("team-eng", "ENG", "Engineering")));
        linearApi.acceptedKey = "no-longer-valid";

        mvc.perform(form("/admin/sources/linear/sync")).andExpect(status().isFound());
        await().atMost(15, TimeUnit.SECONDS).until(() -> !syncService.isRunning(tenant.id(), linear.source(tenant.id()).orElseThrow().id()));

        assertThat(page("/admin/sources")).contains("Failed")
                .contains("Linear rejected the API key. Reconnect Linear on the Sources page.");
    }

    @Test
    void whenLinearCannotBeAskedThePageStillShowsTheConnectionAndDisconnect() throws Exception {
        connectLinear();
        linearApi.readFailure = new java.io.IOException("Could not reach Linear (ConnectException)");

        String html = page("/admin/sources");

        assertThat(html).contains("id=\"linear-error\"").contains("Linear could not be reached")
                .contains("id=\"linear-connected\"").contains("Disconnect").doesNotContain("id=\"linear-choices\"");
    }

    @Test
    void disconnectAsksFirstThenDeletesTheKeyAndTheDocumentsAndKeepsTheSelection() throws Exception {
        connectLinear();
        linear.setTeams(tenant.id(), List.of(new LinearTeam("team-eng", "ENG", "Engineering")));
        linearApi.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "Fix login", T0)));
        syncService.run(tenant.id(), linearConnector);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        String ask = page("/admin/sources?confirm=linear");
        assertThat(ask).contains("id=\"linear-confirm-disconnect\"").contains("Disconnect Linear?")
                .contains("action=\"/admin/sources/linear/disconnect\"");
        assertThat(credentialRows()).isEqualTo(1);

        MvcResult done = mvc.perform(form("/admin/sources/linear/disconnect")).andExpect(status().isFound()).andReturn();

        assertThat(credentialRows()).isZero();
        assertThat(search.count(tenant.id())).isZero();
        assertThat(linear.teams(tenant.id())).hasSize(1);
        assertThat(follow(done)).contains("Linear disconnected.").contains("id=\"linear-not-connected\"");
        assertThat(linearConnector.status(tenant.id()).detail()).isEqualTo("not connected — connect it on the Sources page");
    }

    // --- Trello -------------------------------------------------------------------------------

    @Test
    void aKeyOrTokenTrelloRejectsIsNotStored() throws Exception {
        trelloApi.acceptedKey = FakeTrelloClient.GOOD_KEY;
        trelloApi.acceptedToken = FakeTrelloClient.GOOD_TOKEN;

        MvcResult result = mvc.perform(form("/admin/sources/trello/connect").param("apiKey", FakeTrelloClient.GOOD_KEY)
                        .param("token", "wrong-token"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources#trello")).andReturn();

        assertThat(follow(result)).contains("Trello did not accept that key and token").contains("Nothing was saved")
                .doesNotContain("wrong-token");
        assertThat(credentialRows()).isZero();
        MvcResult missing = mvc.perform(form("/admin/sources/trello/connect").param("apiKey", FakeTrelloClient.GOOD_KEY))
                .andReturn();
        assertThat(follow(missing)).contains("Paste both the Trello API key and the token.");
        assertThat(credentialRows()).isZero();
    }

    @Test
    void validCredentialsAreStoredEncryptedAndTheAccountNameShown() throws Exception {
        MvcResult result = mvc.perform(form("/admin/sources/trello/connect").param("apiKey", FakeTrelloClient.GOOD_KEY)
                        .param("token", FakeTrelloClient.GOOD_TOKEN))
                .andExpect(status().isFound()).andReturn();
        String html = follow(result);

        String stored = jdbc.sql("SELECT CAST(source_credential AS text) FROM source_credential").query(String.class).single();
        assertThat(stored).doesNotContain(FakeTrelloClient.GOOD_KEY).doesNotContain(FakeTrelloClient.GOOD_TOKEN);
        assertThat(trello.credentials(tenant.id()).key()).isEqualTo(FakeTrelloClient.GOOD_KEY);
        assertThat(trello.credentials(tenant.id()).token()).isEqualTo(FakeTrelloClient.GOOD_TOKEN);
        assertThat(html).contains("Connected as Asha Rao (@asharao).").contains("id=\"trello-connected\"")
                .contains("Connected to <b>Asha Rao (@asharao)</b>").contains(">Roadmap<").contains(">Hiring<")
                .doesNotContain(FakeTrelloClient.GOOD_KEY).doesNotContain(FakeTrelloClient.GOOD_TOKEN);
    }

    @Test
    void savingTheBoardSelectionStoresIdAndName() throws Exception {
        connectTrello();

        String html = follow(mvc.perform(form("/admin/sources/trello/boards").param("choice", "b2")
                .param("choice", "b-bogus")).andExpect(status().isFound()).andReturn());

        assertThat(trello.boards(tenant.id())).containsExactly(new TrelloBoard("b2", "Hiring"));
        assertThat(jdbc.sql("SELECT config::text FROM source WHERE kind = 'TRELLO'").query(String.class).single())
                .contains("\"boards\"").contains("\"name\": \"Hiring\"").doesNotContain("bogus");
        assertThat(html).contains("Saved. 1 board is indexed at the next sync.");
        String choices = html.substring(html.indexOf("id=\"trello-choices\""));
        assertThat(choices.substring(0, choices.indexOf("Save"))).contains("value=\"b2\" checked=\"checked\"")
                .doesNotContain("value=\"b1\" checked");
    }

    @Test
    void boardNamesAreEscaped() throws Exception {
        connectTrello();
        trelloApi.boards.add(new TrelloBoard("b3", "<img src=x onerror=alert(1)>"));

        String html = page("/admin/sources");

        assertThat(html).doesNotContain("<img src=x").contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void checkAccessAndSyncAndDisconnectWorkForTrello() throws Exception {
        connectTrello();
        trello.setBoards(tenant.id(), List.of(new TrelloBoard("b1", "Roadmap")));
        trelloApi.content.put("b1", FakeTrelloClient.board("b1", "Roadmap", T0,
                List.of(FakeTrelloClient.card("c1", "Book venue", "l-todo", T0))));

        assertThat(follow(mvc.perform(form("/admin/sources/trello/check")).andReturn()))
                .contains("docs0 can read Roadmap: 1 card.");

        mvc.perform(form("/admin/sources/trello/sync")).andExpect(status().isFound());
        await().atMost(15, TimeUnit.SECONDS).until(() -> !syncService.isRunning(tenant.id(), trello.source(tenant.id()).orElseThrow().id()));
        String html = page("/admin/sources");
        assertThat(html).contains("<span>2</span> added").contains("Complete");
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "venue", 5).hits()).hasSize(1);

        assertThat(page("/admin/sources?confirm=trello")).contains("id=\"trello-confirm-disconnect\"");
        mvc.perform(form("/admin/sources/trello/disconnect")).andExpect(status().isFound());
        assertThat(credentialRows()).isZero();
        assertThat(search.count(tenant.id())).isZero();
        assertThat(trello.boards(tenant.id())).hasSize(1);
    }

    // --- forms need the session's token ---------------------------------------------------------

    @Test
    void everyPostNeedsTheCsrfToken() throws Exception {
        String csrf = csrf();
        for (String path : List.of("/admin/sources/linear/connect", "/admin/sources/linear/teams",
                "/admin/sources/linear/check", "/admin/sources/linear/sync", "/admin/sources/linear/disconnect",
                "/admin/sources/trello/connect", "/admin/sources/trello/boards", "/admin/sources/trello/check",
                "/admin/sources/trello/sync", "/admin/sources/trello/disconnect")) {
            mvc.perform(post(path).session(session).param("apiKey", FakeLinearClient.GOOD_KEY)
                            .param("token", FakeTrelloClient.GOOD_TOKEN))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).param("_csrf", "wrong").param("apiKey", FakeLinearClient.GOOD_KEY))
                    .andExpect(status().isForbidden());
        }
        assertThat(credentialRows()).isZero();
        assertThat(linearApi.keysSeen).isEmpty();
        // With the token the same kind of request is accepted.
        mvc.perform(post("/admin/sources/linear/connect").session(session).param("_csrf", csrf)
                        .param("apiKey", FakeLinearClient.GOOD_KEY))
                .andExpect(status().isFound());
        assertThat(credentialRows()).isEqualTo(1);
    }

    @Test
    void everyFormOnTheConnectedSectionsCarriesTheToken() throws Exception {
        connectLinear();
        connectTrello();
        String html = page("/admin/sources?confirm=linear");
        String csrf = (String) session.getAttribute("docs0.csrf");

        int forms = html.split("<form ", -1).length - 1;
        int tokens = html.split("name=\"_csrf\" value=\"" + csrf + "\"", -1).length - 1;
        assertThat(tokens).isEqualTo(forms);
        assertThat(html).doesNotContain("method=\"get\"");
    }

    // --- helpers --------------------------------------------------------------------------

    private void connectLinear() {
        linear.connect(tenant.id(), "Acme", FakeLinearClient.GOOD_KEY);
    }

    private void connectTrello() {
        trello.connect(tenant.id(), "Asha Rao (@asharao)", FakeTrelloClient.GOOD_KEY, FakeTrelloClient.GOOD_TOKEN);
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

    /** A POST carrying the session's CSRF token. */
    private MockHttpServletRequestBuilder form(String path) throws Exception {
        return post(path).session(session).param("_csrf", csrf());
    }

    private long credentialRows() {
        return jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single();
    }
}
