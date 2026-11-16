package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.source.Folder;
import com.docs0.source.SyncService;
import com.docs0.source.drive.DriveClient;
import com.docs0.source.drive.DriveConnection;
import com.docs0.source.drive.DriveConnector;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.drive.FakeDriveClient;
import com.docs0.source.drive.FakeGoogleOAuth;
import com.docs0.source.drive.GoogleOAuth;
import com.docs0.web.StatusContributor.State;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

/** The Sources page end to end, with Google's OAuth and Drive endpoints faked. */
@AutoConfigureMockMvc
@Import(FakeGoogleConfig.class)
@TestPropertySource(properties = {
        "docs0.google.oauth-client-id=" + DriveTestSetup.CLIENT_ID,
        "docs0.google.oauth-client-secret=" + DriveTestSetup.CLIENT_SECRET,
        "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE="
})
class SourcesPageTest extends IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    private static final String RECONNECT =
            "Google access was revoked or expired. Reconnect Google Drive on the Sources page.";

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeDriveClient drive;

    @Autowired
    FakeGoogleOAuth google;

    @Autowired
    DriveConnection connection;

    @Autowired
    DriveConnector connector;

    @Autowired
    SyncService syncService;

    @Autowired
    FolderCounts counts;

    @Autowired
    KnowledgeSearch search;

    MockHttpSession session;

    @BeforeEach
    void setUp() {
        assertThat(DriveTestSetup.ENCRYPTION_KEY).isEqualTo("ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=");
        drive.reset();
        google.reset();
        counts.forget(tenant.id());
        session = new MockHttpSession();
    }

    /** A background sync must not outlive its test and write into the next one's tables. */
    @AfterEach
    void letSyncFinish() {
        if (drive.holdListing != null) {
            drive.holdListing.countDown();
        }
        awaitSyncDone();
    }

    // --- states ---------------------------------------------------------------------------

    @Test
    void notConnectedShowsTheConnectButton() throws Exception {
        String html = page("/admin/sources");

        assertThat(html).contains("id=\"not-connected\"").contains("Connect Google Drive")
                .contains("href=\"/admin/sources/google/connect\"");
        assertThat(html).doesNotContain("id=\"connected\"").doesNotContain("id=\"setup-incomplete\"");
        assertThat(drive.calls).isZero();
    }

    @Test
    void connectedShowsTheAccountTheSelectionWithFileCountsAndTheThreeBrowserSections() throws Exception {
        connect();
        drive.folder("hr", "HR", DriveClient.MY_DRIVE);
        drive.folder("fin", "Finance", FakeDriveClient.SHARED_WITH_ME);
        drive.sharedDrive("0ACompany", "Company drive");
        drive.put("a", "Alpha", "text/plain", T0, "alpha");
        drive.put("b", "Beta", "application/pdf", T0, "beta");
        drive.put("img", "photo.png", "image/png", T0, "not indexable");
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        connection.addFolder(tenant.id(), new Folder("hr", "HR"));

        String html = page("/admin/sources");

        assertThat(html).contains("id=\"connected\"").contains("Connected as <b>asha@example.com</b>");
        assertThat(html).doesNotContain("id=\"not-connected\"");
        String selection = section(html, "selection");
        assertThat(selection).contains(">Main<").contains("2 files docs0 can read")
                .contains(">HR<").contains("0 files docs0 can read").contains("Remove");
        assertThat(section(html, "browse-my-drive")).contains("My Drive").contains(">HR<").contains(">Main<")
                .contains("href=\"/admin/sources?p=hr%7CHR\"").contains("Added").doesNotContain(">Finance<");
        assertThat(section(html, "browse-shared-with-me")).contains("Shared with me").contains(">Finance<")
                .contains("href=\"/admin/sources?p=fin%7CFinance\"").contains(">Add<");
        assertThat(section(html, "browse-shared-drives")).contains("Shared drives").contains(">Company drive<")
                .contains("href=\"/admin/sources?p=0ACompany%7CCompany+drive\"");
        assertThat(html).contains("Sync now").contains("No sync has run yet.").contains("Disconnect");
        assertThat(html).doesNotContain("http-equiv=\"refresh\"");
        // The stored grant never reaches the page.
        assertThat(html).doesNotContain("refresh-token").doesNotContain(storedToken());
    }

    @Test
    void openingAFolderShowsItsSubFoldersAndABreadcrumbBackUp() throws Exception {
        connect();
        drive.folder("hr", "HR & People", DriveClient.MY_DRIVE);
        drive.folder("pol", "Policies", "hr");
        drive.folder("old", "Archive", "pol");

        // MockMvc, unlike Tomcat, does not read "+" as a space, so requests here use %20.
        String inHr = page("/admin/sources?p=hr%7CHR%20%26%20People");
        String card = section(inHr, "browse-folder");
        assertThat(card).contains("<a href=\"/admin/sources\">All folders</a>").contains("<b>HR &amp; People</b>")
                .contains(">Policies<")
                .contains("href=\"/admin/sources?p=hr%7CHR+%26+People&amp;p=pol%7CPolicies\"");
        assertThat(inHr).doesNotContain("id=\"browse-my-drive\"");
        // Forms on this page come back to this folder.
        assertThat(card).contains("name=\"back\" value=\"/admin/sources?p=hr%7CHR+%26+People\"");

        String inPolicies = page("/admin/sources?p=hr%7CHR%20%26%20People&p=pol%7CPolicies");
        assertThat(section(inPolicies, "browse-folder"))
                .contains("<a href=\"/admin/sources?p=hr%7CHR+%26+People\">HR &amp; People</a>")
                .contains("<b>Policies</b>").contains(">Archive<");

        String empty = page("/admin/sources?p=old%7CArchive");
        assertThat(section(empty, "browse-folder")).contains("No folders inside this one.");

        // A malformed trail falls back to the top.
        assertThat(page("/admin/sources?p=not+an+id")).contains("id=\"browse-my-drive\"");
    }

    @Test
    void folderNamesFromDriveAreEscaped() throws Exception {
        connect();
        String nasty = "<script>alert(1)</script> \"R&D\" 'x'";
        drive.folder("evil", nasty, DriveClient.MY_DRIVE);
        connection.addFolder(tenant.id(), new Folder("evil", nasty));

        for (String url : new String[] {"/admin/sources", "/admin/sources?p=evil%7C%3Cscript%3Ealert(1)%3C%2Fscript%3E"}) {
            String html = page(url);
            assertThat(html).doesNotContain("<script").doesNotContain("\"R&D\"");
            assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        }
        assertThat(page("/admin/sources")).contains("&lt;script&gt;alert(1)&lt;/script&gt; &quot;R&amp;D&quot; &#39;x&#39;");
    }

    @Test
    void aFolderWithManyFilesShowsACappedCount() throws Exception {
        connect();
        drive.folder("big", "Big", DriveClient.MY_DRIVE);
        for (int i = 0; i < 2001; i++) {
            drive.putIn("big", "f" + i, "File " + i, "text/plain", T0, "");
        }
        connection.addFolder(tenant.id(), new Folder("big", "Big"));
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        drive.put("one", "One", "text/plain", T0, "");

        String selection = section(page("/admin/sources"), "selection");

        assertThat(selection).contains("2,000+ files docs0 can read").contains("1 file docs0 can read");
    }

    @Test
    void aRevokedGrantWhileBrowsingShowsTheReconnectMessageNotAStackTrace() throws Exception {
        connect();
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        drive.revoked = true;

        String html = page("/admin/sources");

        assertThat(section(html, "drive-error")).contains(RECONNECT);
        assertThat(html).doesNotContain("Exception").doesNotContain("at com.docs0");
        // Still usable: the account, the selection, reconnect and disconnect.
        assertThat(html).contains("asha@example.com").contains(">Main<").contains("not checked")
                .contains("Reconnect").contains("Disconnect").doesNotContain("id=\"browse-my-drive\"");
    }

    // --- connecting -----------------------------------------------------------------------

    @Test
    void signingInStoresTheGrantEncryptedAndShowsTheAccount() throws Exception {
        String state = startConnect();

        MvcResult callback = mvc.perform(get("/admin/sources/google/callback").session(session)
                        .param("state", state).param("code", "auth-code-1"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources")).andReturn();

        assertThat(google.exchangedCodes).containsExactly("auth-code-1");
        Map<String, Object> row = jdbc.sql("""
                SELECT c.tenant_id, c.account_email, c.encrypted_refresh_token, s.kind, s.name
                FROM source_credential c JOIN source s ON s.id = c.source_id
                """).query().singleRow();
        assertThat(row.get("tenant_id")).isEqualTo(tenant.id());
        assertThat(row.get("kind")).isEqualTo("GOOGLE_DRIVE");
        assertThat(row.get("name")).isEqualTo("Google Drive");
        assertThat(row.get("account_email")).isEqualTo("asha@example.com");
        String stored = (String) row.get("encrypted_refresh_token");
        assertThat(stored).doesNotContain("refresh-token-1");
        assertThat(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY).decrypt(stored)).isEqualTo("refresh-token-1");

        String html = follow(callback);
        assertThat(html).contains("Connected as asha@example.com. Now choose the folders to index.")
                .contains("Connected as <b>asha@example.com</b>").contains("No folders chosen yet");
        assertThat(connector.status().state()).isEqualTo(State.OFF);
        assertThat(connector.status().detail()).isEqualTo("connected as asha@example.com, no folders chosen yet");
    }

    @Test
    void theStateIsSingleUse() throws Exception {
        String state = startConnect();
        follow(mvc.perform(get("/admin/sources/google/callback").session(session)
                        .param("state", state).param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources")).andReturn());
        connection.disconnect(tenant.id());

        MvcResult replay = mvc.perform(get("/admin/sources/google/callback").session(session)
                        .param("state", state).param("code", "c2"))
                .andExpect(redirectedUrl("/admin/sources")).andReturn();

        assertThat(google.exchangedCodes).containsExactly("c1");
        assertThat(credentialRows()).isZero();
        assertThat(follow(replay)).contains("That sign-in link has expired or was already used.")
                .contains("id=\"not-connected\"");
    }

    @Test
    void aCallbackWithAMissingOrWrongStateIsRejectedAndUsesUpTheState() throws Exception {
        String state = startConnect();

        MvcResult wrong = mvc.perform(get("/admin/sources/google/callback").session(session)
                        .param("state", state + "x").param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources")).andReturn();
        assertThat(follow(wrong)).contains("That sign-in link has expired or was already used.");

        // The failed attempt used the state up: the right value no longer works either.
        mvc.perform(get("/admin/sources/google/callback").session(session).param("state", state).param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources"));

        String fresh = startConnect();
        assertThat(fresh).isNotEqualTo(state);
        mvc.perform(get("/admin/sources/google/callback").session(session).param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources"));

        assertThat(google.exchangedCodes).isEmpty();
        assertThat(credentialRows()).isZero();
    }

    @Test
    void aCallbackNobodyStartedIsRejected() throws Exception {
        // No session at all, and a session that never began a sign-in.
        mvc.perform(get("/admin/sources/google/callback").param("state", "guess").param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources"));
        mvc.perform(get("/admin/sources/google/callback").session(session).param("state", "guess").param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources"));
        // A state begun in another browser session does not count.
        String state = startConnect();
        mvc.perform(get("/admin/sources/google/callback").session(new MockHttpSession())
                        .param("state", state).param("code", "c1"))
                .andExpect(redirectedUrl("/admin/sources"));

        assertThat(google.exchangedCodes).isEmpty();
        assertThat(credentialRows()).isZero();
    }

    @Test
    void reconnectingReplacesTheStoredGrant() throws Exception {
        assertThat(follow(signIn("c1"))).contains("Connected as <b>asha@example.com</b>");
        String first = storedToken();
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));

        google.grant = new GoogleOAuth.Grant("refresh-token-2", "ben@example.com", FakeGoogleOAuth.ALL_SCOPES);
        String html = follow(signIn("c2"));

        assertThat(credentialRows()).isEqualTo(1);
        assertThat(storedToken()).isNotEqualTo(first);
        assertThat(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY).decrypt(storedToken())).isEqualTo("refresh-token-2");
        assertThat(html).contains("Connected as <b>ben@example.com</b>").doesNotContain("asha@example.com");
        // The folder selection survives a reconnect.
        assertThat(section(html, "selection")).contains(">Main<");
    }

    @Test
    void withoutARefreshTokenNothingIsStoredAndTheAdminIsAskedToRetry() throws Exception {
        google.grant = new GoogleOAuth.Grant(null, "asha@example.com", FakeGoogleOAuth.ALL_SCOPES);

        String html = follow(signIn("c1"));

        assertThat(credentialRows()).isZero();
        assertThat(html).contains("Google did not give docs0 lasting access. Click Connect Google Drive to try again.")
                .contains("id=\"not-connected\"");
    }

    @Test
    void aCancelledFailedOrDriveLessSignInStoresNothingAndSaysWhy() throws Exception {
        String state = startConnect();
        MvcResult cancelled = mvc.perform(get("/admin/sources/google/callback").session(session)
                        .param("state", state).param("error", "access_denied"))
                .andExpect(redirectedUrl("/admin/sources")).andReturn();
        assertThat(follow(cancelled)).contains("Google sign-in was cancelled. Nothing was connected.");
        assertThat(google.exchangedCodes).isEmpty();

        google.fails = true;
        assertThat(follow(signIn("c1"))).contains("Google sign-in did not complete. Try again.")
                .doesNotContain("invalid_grant");

        google.fails = false;
        google.grant = new GoogleOAuth.Grant("refresh-token-1", "asha@example.com", "openid email");
        assertThat(follow(signIn("c2"))).contains("docs0 was not given permission to read Google Drive.");

        assertThat(credentialRows()).isZero();
    }

    // --- choosing folders -----------------------------------------------------------------

    @Test
    void addingAndRemovingFoldersIsPersistedInTheSourceConfig() throws Exception {
        connect();
        drive.folder("hr", "HR", DriveClient.MY_DRIVE);
        String csrf = csrf();

        MvcResult added = mvc.perform(form("/admin/sources/folders/add", csrf).param("id", "hr").param("name", "HR")
                        .param("back", "/admin/sources?p=main%7CMain"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources?p=main%7CMain")).andReturn();
        assertThat(follow(added)).contains("Added &quot;HR&quot;. It is indexed at the next sync.");
        mvc.perform(form("/admin/sources/folders/add", csrf).param("id", "main").param("name", "Main"))
                .andExpect(redirectedUrl("/admin/sources"));

        assertThat(config()).isEqualTo("{\"folders\": [{\"id\": \"hr\", \"name\": \"HR\"}, "
                + "{\"id\": \"main\", \"name\": \"Main\"}]}");
        assertThat(connection.folders(tenant.id())).containsExactly(new Folder("hr", "HR"), new Folder("main", "Main"));

        mvc.perform(form("/admin/sources/folders/remove", csrf).param("id", "hr"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources"));

        assertThat(config()).isEqualTo("{\"folders\": [{\"id\": \"main\", \"name\": \"Main\"}]}");
        String html = page("/admin/sources");
        assertThat(section(html, "selection")).contains(">Main<").doesNotContain(">HR<");
    }

    @Test
    void aFormNeverRedirectsAwayFromTheSourcesPage() throws Exception {
        connect();
        String csrf = csrf();

        for (String back : new String[] {"https://evil.example/", "//evil.example", "/admin/sourcesX", "/other",
                "/admin/sources?x={y}"}) {
            mvc.perform(form("/admin/sources/folders/remove", csrf).param("id", "x").param("back", back))
                    .andExpect(redirectedUrl("/admin/sources"));
        }
    }

    @Test
    void foldersCannotBeAddedWithABadIdOrWhileNotConnected() throws Exception {
        String csrf = csrf();
        mvc.perform(form("/admin/sources/folders/add", csrf).param("id", "main").param("name", "Main"))
                .andExpect(redirectedUrl("/admin/sources"));
        assertThat(jdbc.sql("SELECT count(*) FROM source").query(Long.class).single()).isZero();

        connect();
        mvc.perform(form("/admin/sources/folders/add", csrf).param("id", "bad id'").param("name", "Main"))
                .andExpect(redirectedUrl("/admin/sources"));
        mvc.perform(form("/admin/sources/folders/add", csrf).param("id", "main").param("name", "  "))
                .andExpect(redirectedUrl("/admin/sources"));
        assertThat(connection.folders(tenant.id())).isEmpty();
    }

    // --- CSRF -----------------------------------------------------------------------------

    @Test
    void postsWithoutOrWithTheWrongCsrfTokenAreRejectedAndChangeNothing() throws Exception {
        connect();
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        String csrf = csrf();

        String[] posts = {"/admin/sources/folders/add", "/admin/sources/folders/remove", "/admin/sources/sync",
                "/admin/sources/disconnect"};
        for (String path : posts) {
            mvc.perform(post(path).session(session).param("id", "main").param("name", "Other"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).param("_csrf", csrf + "x").param("id", "main")
                            .param("name", "Other"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).param("_csrf", "").param("id", "main").param("name", "Other"))
                    .andExpect(status().isForbidden());
            // The right token, but from another session.
            mvc.perform(post(path).session(new MockHttpSession()).param("_csrf", csrf).param("id", "main")
                            .param("name", "Other"))
                    .andExpect(status().isForbidden());
        }

        assertThat(connection.folders(tenant.id())).containsExactly(new Folder(FakeDriveClient.MAIN, "Main"));
        assertThat(credentialRows()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isZero();

        // With the session's token the same request goes through.
        mvc.perform(form("/admin/sources/folders/remove", csrf).param("id", "main"))
                .andExpect(status().isFound());
        assertThat(connection.folders(tenant.id())).isEmpty();
    }

    @Test
    void everyFormOnThePageCarriesTheSessionsCsrfToken() throws Exception {
        connect();
        drive.folder("hr", "HR", DriveClient.MY_DRIVE);
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));

        String html = page("/admin/sources?disconnect=ask");
        String csrf = csrf();

        int forms = html.split("<form ", -1).length - 1;
        int tokens = html.split("name=\"_csrf\" value=\"" + csrf + "\"", -1).length - 1;
        assertThat(forms).isEqualTo(6); // disconnect, remove, sync, add, and the Linear and Trello connect forms
        assertThat(tokens).isEqualTo(forms);
        assertThat(html).doesNotContain("method=\"get\"");
    }

    // --- sync now -------------------------------------------------------------------------

    @Test
    void syncNowRunsInTheBackgroundAndThePageShowsItRunningThenTheResult() throws Exception {
        connect();
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        drive.put("img", "photo.png", "image/png", T0, "");
        drive.holdListing = new CountDownLatch(1);

        mvc.perform(form("/admin/sources/sync", csrf()).param("back", "/admin/sources"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources"));

        String running = page("/admin/sources");
        assertThat(running).contains("Sync running").contains("<meta http-equiv=\"refresh\" content=\"4\">")
                .contains(">Running<");
        assertThat(section(running, "sync")).doesNotContain("Sync now");

        // A second click while it runs does not start another.
        MvcResult again = mvc.perform(form("/admin/sources/sync", csrf())).andReturn();
        assertThat(follow(again)).contains("A sync is already running.");
        // Nor can Drive be disconnected under a running sync.
        MvcResult refused = mvc.perform(form("/admin/sources/disconnect", csrf())).andReturn();
        assertThat(follow(refused)).contains("A sync is running. Disconnect when it has finished.");
        assertThat(credentialRows()).isEqualTo(1);

        drive.holdListing.countDown();
        awaitSyncDone();

        String done = page("/admin/sources");
        assertThat(done).doesNotContain("Sync running").doesNotContain("http-equiv=\"refresh\"");
        assertThat(section(done, "sync")).contains("Sync now").contains(">Complete<")
                .contains("<span>1</span> added").contains("<span>1</span> skipped");
        assertThat(done).contains("<span>1</span> documents indexed");
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isEqualTo(1);
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "okapi", 8).hits()).hasSize(1);
    }

    @Test
    void thePageListsTheLastFiveRunsWithTheirErrors() throws Exception {
        connect();
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        for (int i = 0; i < 6; i++) {
            syncService.run(connector);
        }
        drive.revoked = true;
        syncService.run(connector);

        String sync = section(page("/admin/sources"), "sync");

        assertThat(sync.split("class=\"state ", -1).length - 1).isEqualTo(5);
        assertThat(sync).contains(">Failed<").contains(RECONNECT).contains(">Complete<");
        assertThat(connector.status().state()).isEqualTo(State.DOWN);
    }

    // --- disconnect -----------------------------------------------------------------------

    @Test
    void disconnectAsksFirstThenDeletesTheGrantAndHidesTheDocuments() throws Exception {
        connect();
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        syncService.run(connector);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        // The button on the page only leads to the question.
        String html = page("/admin/sources");
        assertThat(html).contains("href=\"/admin/sources?disconnect=ask\"").doesNotContain("id=\"confirm-disconnect\"");
        String asking = page("/admin/sources?disconnect=ask");
        assertThat(section(asking, "confirm-disconnect")).contains("Disconnect Google Drive?")
                .contains("action=\"/admin/sources/disconnect\"").contains("Yes, disconnect").contains("Cancel");
        assertThat(credentialRows()).isEqualTo(1);

        MvcResult gone = mvc.perform(form("/admin/sources/disconnect", csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources")).andReturn();

        assertThat(credentialRows()).isZero();
        assertThat(search.count(tenant.id())).isZero();
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "okapi", 8).hits()).isEmpty();
        assertThat(follow(gone)).contains("Google Drive disconnected.").contains("id=\"not-connected\"");
        assertThat(connector.status().detail()).isEqualTo("not connected — connect it on the Sources page");
    }

    // --- helpers --------------------------------------------------------------------------

    private void connect() {
        connection.connect(tenant.id(), "asha@example.com", "refresh-token-1");
    }

    /** Clicks Connect Google Drive and returns the state sent to Google. */
    private String startConnect() throws Exception {
        String location = mvc.perform(get("/admin/sources/google/connect").session(session))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://accounts.example/auth?state=");
        String state = location.substring(location.indexOf("state=") + 6);
        assertThat(state).hasSizeGreaterThanOrEqualTo(40);
        return state;
    }

    private MvcResult signIn(String code) throws Exception {
        String state = startConnect();
        return mvc.perform(get("/admin/sources/google/callback").session(session)
                        .param("state", state).param("code", code))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources")).andReturn();
    }

    private String page(String url) throws Exception {
        // As a URI, so the already-encoded query is sent as written.
        return mvc.perform(get(java.net.URI.create(url)).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Loads the page a form redirected to, as a browser would, so its one-time message shows. */
    private String follow(MvcResult redirect) throws Exception {
        return page(redirect.getResponse().getRedirectedUrl());
    }

    private String csrf() throws Exception {
        String html = page("/admin/sources");
        Object token = session.getAttribute("docs0.csrf");
        assertThat(token).isInstanceOf(String.class);
        assertThat(html).isNotEmpty();
        return (String) token;
    }

    private MockHttpServletRequestBuilder form(String path, String csrf) {
        return post(path).session(session).param("_csrf", csrf);
    }

    /** The element with this id, up to the next card. Enough to tell the page's parts apart. */
    private static String section(String html, String id) {
        int start = html.indexOf("id=\"" + id + "\"");
        assertThat(start).as("element #%s", id).isNotNegative();
        int next = html.indexOf("<h2>", start);
        int nextCard = html.indexOf("<div class=\"card", start);
        int end = html.length();
        if (next > 0) {
            end = Math.min(end, next);
        }
        if (nextCard > 0) {
            end = Math.min(end, nextCard);
        }
        return html.substring(start, end);
    }

    private String storedToken() {
        return jdbc.sql("SELECT encrypted_refresh_token FROM source_credential").query(String.class).single();
    }

    private long credentialRows() {
        return jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single();
    }

    private String config() {
        return jdbc.sql("SELECT config::text FROM source WHERE tenant_id = :t").param("t", tenant.id())
                .query(String.class).single();
    }

    private void awaitSyncDone() {
        await().atMost(15, TimeUnit.SECONDS).until(() -> connection.source(tenant.id())
                .map(s -> !syncService.isRunning(s.id())).orElse(true));
    }
}
