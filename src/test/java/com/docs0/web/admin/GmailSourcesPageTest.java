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
import com.docs0.source.gmail.FakeGmailClient;
import com.docs0.source.gmail.GmailClient.DirectoryUser;
import com.docs0.source.gmail.GmailConnection;
import com.docs0.source.gmail.GmailConnector;
import com.docs0.source.gmail.GmailKey;
import com.docs0.source.gmail.HttpGmailClient;
import com.docs0.source.gmail.Mailbox;
import com.docs0.source.gmail.TestKeys;
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

/** The Gmail section of the Sources page, with Gmail's network edge faked. */
@AutoConfigureMockMvc
@Import(FakeToolClientsConfig.class)
@TestPropertySource(properties = "docs0.encryption-key=ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=")
class GmailSourcesPageTest extends IntegrationTest {

    private static final String ANA = "ana@example.com";

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeGmailClient gmailApi;

    @Autowired
    GmailConnection gmail;

    @Autowired
    GmailConnector connector;

    @Autowired
    SyncService syncService;

    @Autowired
    KnowledgeSearch search;

    MockHttpSession session;
    final String key = TestKeys.json();

    @BeforeEach
    void setUp() {
        assertThat(DriveTestSetup.ENCRYPTION_KEY).isEqualTo("ZG9jczAtdGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISE=");
        gmailApi.reset();
        session = new MockHttpSession();
    }

    @AfterEach
    void letSyncsFinish() {
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                gmail.source(tenant.id()).map(s -> !syncService.isRunning(s.id())).orElse(true));
    }

    // ---- the key ---------------------------------------------------------------------------

    @Test
    void notConnectedShowsTheStepsAndATextareaButNoClientId() throws Exception {
        String html = page("/admin/sources");

        assertThat(html).contains("id=\"gmail-not-connected\"", "href=\"#gmail\"", "<textarea name=\"key\"",
                "action=\"/admin/sources/gmail/connect\"", "Gmail API", "Admin SDK API", "JSON",
                "docs0 can read every mailbox you add", "only ever shown to that person", "last <span>7</span> days");
        assertThat(html).doesNotContain("id=\"gmail-connected\"").doesNotContain("id=\"gmail-client-id\"");
    }

    @Test
    void textThatIsNotAServiceAccountKeyStoresNothingAndIsNotEchoed() throws Exception {
        for (String bad : List.of("not json SECRET-MARK", "[\"SECRET-MARK\"]", "{\"type\":\"authorized_user\",\"note\":\"SECRET-MARK\"}",
                key.replace("service_account", "SECRET-MARK"), key.replace(TestKeys.CLIENT_ID, "SECRET-MARK"), "  ")) {
            MvcResult result = mvc.perform(form("/admin/sources/gmail/connect").param("key", bad))
                    .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/sources#gmail")).andReturn();

            String html = follow(result);
            assertThat(html).contains("That is not a Google service account key").contains("Nothing was saved")
                    .contains("id=\"gmail-not-connected\"").doesNotContain("SECRET-MARK");
        }
        assertThat(credentialRows()).isZero();
    }

    @Test
    void aValidKeyIsStoredEncryptedAndOnlyTheAccountEmailAndClientIdAreShown() throws Exception {
        String html = follow(mvc.perform(form("/admin/sources/gmail/connect").param("key", "  " + key + "\n"))
                .andExpect(status().isFound()).andReturn());

        assertThat(credentialRows()).isEqualTo(1);
        String stored = jdbc.sql("SELECT CAST(source_credential AS text) FROM source_credential").query(String.class).single();
        assertThat(stored).doesNotContain(TestKeys.KEY_FRAGMENT).doesNotContain("PRIVATE KEY").doesNotContain("private_key")
                .doesNotContain(TestKeys.CLIENT_ID).doesNotContain("abcdef0123456789");
        assertThat(gmail.keyJson(tenant.id())).isEqualTo(key);
        assertThat(gmail.account(tenant.id())).contains(new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID));

        assertThat(html).contains("id=\"gmail-connected\"").contains(TestKeys.EMAIL)
                .contains("id=\"gmail-client-id\"").contains("value=\"" + TestKeys.CLIENT_ID + "\"")
                .contains("https://www.googleapis.com/auth/gmail.readonly")
                .contains("https://www.googleapis.com/auth/admin.directory.user.readonly")
                .contains("Domain-wide delegation").contains("stored encrypted")
                .doesNotContain("id=\"gmail-not-connected\"");
        // Nothing of the key is ever rendered, on this page or on a later one.
        for (String later : List.of(html, page("/admin/sources"), page("/"))) {
            assertThat(later).doesNotContain(TestKeys.KEY_FRAGMENT).doesNotContain("PRIVATE KEY")
                    .doesNotContain("private_key").doesNotContain("abcdef0123456789");
        }
        assertThat(gmailApi.keysSeen).isEmpty(); // saving the key calls nobody
    }

    // ---- mailboxes -------------------------------------------------------------------------

    @Test
    void mailboxesTypedOnePerLineAreCleanedAndSavedWithNoViewersAndNothingIsIndexedBeforeThat() throws Exception {
        connect();
        assertThat(gmail.mailboxes(tenant.id())).isEmpty();
        assertThat(page("/admin/sources")).contains("Nothing is indexed until you choose at least one mailbox");

        String html = follow(mvc.perform(form("/admin/sources/gmail/mailboxes")
                .param("newMailboxes", "  Ana@Example.com \n\n bob@example.com\nana@example.com\r\n"))
                .andExpect(status().isFound()).andReturn());

        assertThat(gmail.mailboxes(tenant.id())).containsExactly(new Mailbox(ANA, List.of()),
                new Mailbox("bob@example.com", List.of()));
        assertThat(html).contains("Saved. 2 mailboxes are indexed at the next sync.")
                .contains("value=\"ana@example.com\" checked", "value=\"bob@example.com\" checked");
        assertThat(jdbc.sql("SELECT CAST(config AS text) FROM source WHERE kind = 'GMAIL'").query(String.class).single())
                .contains("\"mailboxes\"").contains("\"email\": \"ana@example.com\"").contains("\"viewers\": []");
    }

    @Test
    void anAddressWithoutADomainIsRefusedAndNothingIsSaved() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of())));

        for (String bad : List.of("sam", "sam@localhost", "sam@", "@example.com", "a b@example.com", "sam@example.com, kim@example.com")) {
            String html = follow(mvc.perform(form("/admin/sources/gmail/mailboxes")
                    .param("mailbox", ANA).param("newMailboxes", "bob@example.com\n" + bad))
                    .andExpect(status().isFound()).andReturn());

            assertThat(html).contains("Line 2 is not an email address on a domain").contains("Nothing was saved");
        }
        assertThat(gmail.mailboxes(tenant.id())).containsExactly(new Mailbox(ANA, List.of()));
    }

    @Test
    void tickedMailboxesKeepTheirAlsoVisibleToListAndUntickedOnesAreRemoved() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox("support@example.com", List.of()), new Mailbox(ANA, List.of())));

        String html = follow(mvc.perform(form("/admin/sources/gmail/mailboxes")
                .param("mailbox", "support@example.com")
                .param("viewers:support@example.com", " Lee@Example.com, kim@example.com;  lee@example.com")
                .param("viewers:" + ANA, "ignored@example.com"))
                .andExpect(status().isFound()).andReturn());

        assertThat(gmail.mailboxes(tenant.id())).containsExactly(
                new Mailbox("support@example.com", List.of("lee@example.com", "kim@example.com")));
        assertThat(html).contains("Saved. 1 mailbox is indexed")
                .contains("name=\"viewers:support@example.com\"").contains("value=\"lee@example.com, kim@example.com\"")
                .doesNotContain("ignored@example.com");
        assertThat(jdbc.sql("SELECT CAST(config AS text) FROM source WHERE kind = 'GMAIL'").query(String.class).single())
                .contains("\"viewers\": [\"lee@example.com\", \"kim@example.com\"]");

        String bad = follow(mvc.perform(form("/admin/sources/gmail/mailboxes").param("mailbox", "support@example.com")
                .param("viewers:support@example.com", "lee@example.com, not-an-address")).andReturn());
        assertThat(bad).contains("also visible to").contains("Nothing was saved");
        assertThat(gmail.mailboxes(tenant.id()).get(0).viewers()).hasSize(2);

        follow(mvc.perform(form("/admin/sources/gmail/mailboxes")).andReturn());
        assertThat(gmail.mailboxes(tenant.id())).isEmpty();
    }

    @Test
    void savingMailboxesWithoutAKeyStoresNothing() throws Exception {
        String html = follow(mvc.perform(form("/admin/sources/gmail/mailboxes").param("newMailboxes", ANA)).andReturn());

        assertThat(html).contains("Add the service account key first.");
        assertThat(gmail.mailboxes(tenant.id())).isEmpty();
    }

    // ---- the directory ---------------------------------------------------------------------

    @Test
    void loadingPeopleFromWorkspaceListsThemToTickAndRemembersTheAdmin() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of())));
        gmailApi.directory.add(new DirectoryUser(ANA, "Ana Example"));
        gmailApi.directory.add(new DirectoryUser("bob@example.com", "<b>Bob</b> & Co"));
        gmailApi.directory.add(new DirectoryUser("cy@example.com", null));

        String html = follow(mvc.perform(form("/admin/sources/gmail/directory").param("adminEmail", " Admin@Example.com "))
                .andExpect(status().isFound()).andReturn());

        assertThat(gmailApi.directoryAdminSeen).isEqualTo("admin@example.com");
        assertThat(gmailApi.keysSeen).containsOnly(key);
        assertThat(html).contains("Loaded 3 people from Google Workspace")
                .contains("value=\"ana@example.com\" checked").contains("value=\"bob@example.com\"")
                .contains("value=\"cy@example.com\"").contains("value=\"admin@example.com\"")
                .contains("&lt;b&gt;Bob&lt;/b&gt; &amp; Co").doesNotContain("<b>Bob</b>");
        assertThat(html).doesNotContain("value=\"bob@example.com\" checked");
        // Nothing is selected by loading: bob is only chosen once ticked and saved.
        assertThat(gmail.mailboxes(tenant.id())).extracting(Mailbox::email).containsExactly(ANA);
        assertThat(gmail.adminEmail(tenant.id())).contains("admin@example.com");

        follow(mvc.perform(form("/admin/sources/gmail/mailboxes").param("mailbox", ANA).param("mailbox", "bob@example.com"))
                .andReturn());
        assertThat(gmail.mailboxes(tenant.id())).extracting(Mailbox::email).containsExactly(ANA, "bob@example.com");
        assertThat(jdbc.sql("SELECT CAST(config AS text) FROM source WHERE kind = 'GMAIL'").query(String.class).single())
                .contains("\"adminEmail\": \"admin@example.com\"").contains("\"mailboxes\"");
    }

    @Test
    void theDirectoryImportExplainsAnyRefusalAndNeedsAnAdminEmail() throws Exception {
        connect();
        gmailApi.directoryFailure = new com.docs0.source.SourceAccessException(HttpGmailClient.DIRECTORY_API_NOT_ENABLED);

        assertThat(follow(mvc.perform(form("/admin/sources/gmail/directory").param("adminEmail", "admin@example.com"))
                .andReturn())).contains("Admin SDK API is not enabled for this Google Cloud project.");
        assertThat(gmail.adminEmail(tenant.id())).isEmpty();

        gmailApi.keysSeen.clear();
        assertThat(follow(mvc.perform(form("/admin/sources/gmail/directory").param("adminEmail", "nobody")).andReturn()))
                .contains("Type the email of a Google Workspace administrator first.");
        assertThat(gmailApi.keysSeen).isEmpty();
    }

    // ---- check access ----------------------------------------------------------------------

    @Test
    void checkAccessShowsATickWithTheMessageCountOrThePlainReasonPerMailbox() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of()), new Mailbox("bob@example.com", List.of()),
                new Mailbox("gone@example.com", List.of()), new Mailbox("<x>@example.com", List.of())));
        gmailApi.put(ANA, "t1", "1", "One", "text", Instant.now());
        gmailApi.put(ANA, "t2", "1", "Two", "text", Instant.now());
        gmailApi.refusals.put("bob@example.com", "Gmail access isn't authorised for bob@example.com yet. "
                + "Check domain-wide delegation in the Workspace Admin console.");
        gmailApi.refusals.put("gone@example.com", "gone@example.com was not found.");

        String html = follow(mvc.perform(form("/admin/sources/gmail/check")).andExpect(status().isFound()).andReturn());

        assertThat(html).contains("id=\"gmail-check\"");
        String section = html.substring(html.indexOf("id=\"gmail-check\""), html.indexOf("id=\"gmail-sync\""));
        assertThat(section).contains("20 messages").contains("isn&#39;t authorised for bob@example.com yet")
                .contains("gone@example.com was not found.");
        assertThat(section.split("✓", -1).length - 1).isEqualTo(1);
        assertThat(section.split("✗", -1).length - 1).isEqualTo(2);
        // A later load of the page no longer shows the one-off result.
        assertThat(page("/admin/sources")).doesNotContain("id=\"gmail-check\"");
    }

    @Test
    void checkAccessEscapesWhateverTheReasonSays() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of())));
        gmailApi.refusals.put(ANA, "<script>alert(1)</script>");

        String html = follow(mvc.perform(form("/admin/sources/gmail/check")).andReturn());

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)");
    }

    @Test
    void checkAccessWithARejectedKeyShowsTheKeyMessageAndWithoutMailboxesAsksForOne() throws Exception {
        connect();
        assertThat(follow(mvc.perform(form("/admin/sources/gmail/check")).andReturn()))
                .contains("Add the key and choose at least one mailbox first.");

        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of())));
        gmailApi.keyRejected = true;
        assertThat(follow(mvc.perform(form("/admin/sources/gmail/check")).andReturn())).contains(HttpGmailClient.KEY_REJECTED);
    }

    // ---- sync and disconnect -----------------------------------------------------------------

    @Test
    void syncNowRunsInTheBackgroundAndThePageShowsTheResultAndTheGmailCount() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of())));
        gmailApi.put(ANA, "t1", "1", "Launch mail", "launch text", Instant.now());

        mvc.perform(form("/admin/sources/gmail/sync")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/sources#gmail"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !syncService.isRunning(gmail.source(tenant.id()).orElseThrow().id()));

        String html = page("/admin/sources");
        assertThat(html).contains("<span>1</span> added").contains("Complete").contains("<span>1</span> mail threads indexed");
        String counts = html.substring(html.indexOf("id=\"kind-counts\""), html.indexOf("id=\"drive\""));
        assertThat(counts).contains("<td>Gmail</td><td>1</td>");
        // The admin page does not become a way to read the mail: shared-only search finds nothing.
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "launch", 5).hits()).isEmpty();
        assertThat(html).doesNotContain("Launch mail").doesNotContain("launch text");
    }

    @Test
    void disconnectAsksFirstThenDeletesTheKeyAndMarksTheMailDeleted() throws Exception {
        connect();
        gmail.setMailboxes(tenant.id(), List.of(new Mailbox(ANA, List.of())));
        gmailApi.put(ANA, "t1", "1", "Launch mail", "launch text", Instant.now());
        syncService.run(connector);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        String ask = page("/admin/sources?confirm=gmail");
        assertThat(ask).contains("id=\"gmail-confirm-disconnect\"").contains("Disconnect Gmail?")
                .contains("action=\"/admin/sources/gmail/disconnect\"");
        assertThat(credentialRows()).isEqualTo(1);

        MvcResult done = mvc.perform(form("/admin/sources/gmail/disconnect")).andExpect(status().isFound()).andReturn();

        assertThat(credentialRows()).isZero();
        assertThat(search.count(tenant.id())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE NOT deleted").query(Long.class).single()).isZero();
        assertThat(gmail.mailboxes(tenant.id())).isEmpty();
        assertThat(follow(done)).contains("Gmail disconnected.").contains("id=\"gmail-not-connected\"");
        assertThat(connector.status().detail()).contains("not connected");
    }

    @Test
    void theStatusPageListsGmail() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Gmail", "not connected");
    }

    // ---- CSRF ------------------------------------------------------------------------------

    @Test
    void everyPostNeedsTheCsrfTokenAndEveryFormCarriesIt() throws Exception {
        String csrf = csrf();
        for (String path : List.of("/admin/sources/gmail/connect", "/admin/sources/gmail/mailboxes",
                "/admin/sources/gmail/directory", "/admin/sources/gmail/check", "/admin/sources/gmail/sync",
                "/admin/sources/gmail/disconnect")) {
            mvc.perform(post(path).session(session).param("key", key).param("newMailboxes", ANA)
                    .param("adminEmail", "admin@example.com")).andExpect(status().isForbidden());
            mvc.perform(post(path).session(session).param("_csrf", "wrong").param("key", key)
                    .param("newMailboxes", ANA).param("adminEmail", "admin@example.com")).andExpect(status().isForbidden());
        }
        assertThat(credentialRows()).isZero();
        assertThat(gmailApi.keysSeen).isEmpty();

        connect();
        String html = page("/admin/sources?confirm=gmail");
        String section = html.substring(html.indexOf("id=\"gmail\""), html.indexOf("</main>"));
        int forms = section.split("<form ", -1).length - 1;
        int tokens = section.split("name=\"_csrf\" value=\"" + csrf + "\"", -1).length - 1;
        assertThat(forms).isGreaterThan(4);
        assertThat(tokens).isEqualTo(forms);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void connect() {
        gmail.connect(tenant.id(), new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID), key);
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(java.net.URI.create(url)).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String follow(MvcResult redirect) throws Exception {
        String location = redirect.getResponse().getRedirectedUrl();
        // Flash attributes (the notice, the check result) ride on the session, as in a browser.
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
