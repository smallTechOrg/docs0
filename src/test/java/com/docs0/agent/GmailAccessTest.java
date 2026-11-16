package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.LlmClient.ToolCall;
import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Audience.Member;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.gmail.FakeGmailClient;
import com.docs0.source.gmail.GmailConnection;
import com.docs0.source.gmail.GmailConnector;
import com.docs0.source.gmail.GmailKey;
import com.docs0.source.gmail.Mailbox;
import com.docs0.source.gmail.TestKeys;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Mail synced by the real Gmail connector, asked about through the real search and the real agent loop
 * (with a scripted model): it appears for its owner alone in a DM, and for no other audience.
 */
class GmailAccessTest extends IntegrationTest {

    private static final String SECRET = "the acquisition of Birchwood closes on Friday";
    private static final String SHARED_SECRET = "the refund for order 5512 was approved";

    @Autowired
    KnowledgeSearch search;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    SyncRunRepository runs;

    final ScriptedLlm llm = new ScriptedLlm();
    long mailId;
    long supportId;

    @BeforeEach
    void mailIsSynced() throws Exception {
        FakeGmailClient gmail = new FakeGmailClient();
        gmail.put("ana@example.com", "t1", "1", "Birchwood plans", SECRET, Instant.parse("2026-11-10T10:00:00Z"));
        gmail.put("support@example.com", "s1", "1", "Refund 5512", SHARED_SECRET, Instant.parse("2026-11-10T10:00:00Z"));
        GmailConnection connection = new GmailConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources,
                credentials, store);
        connection.connect(tenant.id(), new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID), FakeGmailClient.KEY);
        connection.setMailboxes(tenant.id(), List.of(new Mailbox("ana@example.com", List.of()),
                new Mailbox("support@example.com", List.of("lee@example.com"))));
        GmailConnector connector = new GmailConnector(gmail, connection, store, runs, tenants, 90);
        connector.sync(tenant.id(), connection.source(tenant.id()).orElseThrow().id());
        mailId = id("gmail:ana@example.com:t1");
        supportId = id("gmail:support@example.com:s1");
    }

    private long id(String externalId) {
        return jdbc.sql("SELECT id FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.id()).param("e", externalId).query(Long.class).single();
    }

    private static Audience dm(String slackId, String email) {
        return new Audience(Set.of(new Member(slackId, email)));
    }

    private AgentService service() {
        Docs0Properties properties = new Docs0Properties(null, new Docs0Properties.Anthropic("test-key",
                "claude-sonnet-5-5", new BigDecimal("2.00"), new BigDecimal("10.00"), 12),
                null, null, null, null, null, null, null);
        return new AgentService(llm, search, properties, Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC));
    }

    ToolCall find;
    ToolCall read;
    ToolCall recent;
    ToolCall gmailOnly;

    private AgentResult ask(Audience audience, long documentId, String quote) {
        find = llm.call("search_knowledge", "query", "Birchwood");
        gmailOnly = llm.call("search_knowledge", "query", "Birchwood", "source_kinds", List.of("GMAIL"));
        read = llm.call("read_document", "document_id", documentId);
        recent = llm.call("list_recent_documents");
        ToolCall submit = llm.call("submit_answer", "kind", "answer", "answer", "It closes Friday [1].",
                "confidence", "high", "citations", List.of(Map.of("document_id", documentId, "quote", quote)),
                "follow_ups", List.of(), "suggested_people", List.of());
        llm.calls(find, gmailOnly, read, recent).calls(submit);
        return service().answer(new AgentRequest(tenant.id(), audience, "when does Birchwood close?", List.of()), p -> {});
    }

    @Test
    void theOwnerInTheirOwnDmGetsTheirMailWithAVerifiedCitation() {
        AgentResult result = ask(dm("U1", "Ana@Example.com"), mailId, SECRET);

        assertThat(llm.resultFor(find).content()).contains("Birchwood plans").contains("source: GMAIL");
        assertThat(llm.resultFor(gmailOnly).content()).contains("Birchwood plans");
        assertThat(llm.resultFor(read).content()).contains(SECRET).contains("From: Ana <ana@example.com>");
        assertThat(llm.resultFor(recent).content()).contains("Birchwood plans");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).url()).isEqualTo("https://mail.google.com/mail/u/ana@example.com/#all/t1");
    }

    @Test
    void aChannelWithTheOwnerAndAColleagueNeverSeesTheMailInAnyToolOrCitation() {
        Audience channel = new Audience(Set.of(new Member("U1", "ana@example.com"), new Member("U2", "bob@example.com")));

        AgentResult result = ask(channel, mailId, SECRET);

        assertNothingLeaked(result);
    }

    @Test
    void someoneElsesDmNeverSeesIt() {
        AgentResult result = ask(dm("U2", "bob@example.com"), mailId, SECRET);

        assertNothingLeaked(result);
    }

    @Test
    void whenTheOwnersSlackEmailIsUnknownTheMailIsHiddenEvenInTheirOwnDm() {
        AgentResult result = ask(dm("U1", null), mailId, SECRET);

        assertNothingLeaked(result);
    }

    @Test
    void anEmptyAudienceAndAnUnknownChannelSeeNoMail() {
        assertNothingLeaked(ask(Audience.sharedOnly(), mailId, SECRET));
    }

    @Test
    void aSharedInboxIsSeenByItsViewersDmsOnlyAndNotByTheirColleagues() {
        // support@ lists lee@ as a viewer: in Lee's DM, and in no DM of anyone else.
        llm.calls(llm.call("read_document", "document_id", supportId));
        ToolCall submit = llm.call("submit_answer", "kind", "answer", "answer", "Approved [1].", "confidence", "high",
                "citations", List.of(Map.of("document_id", supportId, "quote", SHARED_SECRET)), "follow_ups", List.of(),
                "suggested_people", List.of());
        llm.calls(submit);
        AgentResult viewer = service().answer(new AgentRequest(tenant.id(), dm("U3", "lee@example.com"), "refund?",
                List.of()), p -> {});
        assertThat(viewer.citations()).hasSize(1);

        assertThat(search.findById(tenant.id(), dm("U1", "ana@example.com"), supportId)).isEmpty();
        assertThat(search.findById(tenant.id(), dm("U3", "lee@example.com"), mailId)).isEmpty();
        assertThat(search.findById(tenant.id(), dm("U3", "lee@example.com"), supportId)).isPresent();
        // The inbox itself (its own address as a principal) also reads it, and a channel of Lee and Ana does not.
        assertThat(search.findById(tenant.id(), dm("U4", "support@example.com"), supportId)).isPresent();
        assertThat(search.findById(tenant.id(),
                new Audience(Set.of(new Member("U3", "lee@example.com"), new Member("U1", "ana@example.com"))),
                supportId)).isEmpty();
    }

    private void assertNothingLeaked(AgentResult result) {
        assertThat(llm.resultFor(find).content()).doesNotContain("Birchwood plans").doesNotContain(SECRET)
                .doesNotContain("GMAIL");
        assertThat(llm.resultFor(gmailOnly).content()).doesNotContain("Birchwood plans").doesNotContain(SECRET);
        assertThat(llm.resultFor(read).isError()).isTrue();
        assertThat(llm.resultFor(read).content()).doesNotContain(SECRET).doesNotContain("Birchwood plans");
        assertThat(llm.resultFor(recent).content()).doesNotContain("Birchwood plans").doesNotContain(SECRET);
        assertThat(llm.batches.toString()).doesNotContain(SECRET).doesNotContain("Birchwood plans")
                .doesNotContain("ana@example.com");
        assertThat(result.citations()).isEmpty();
    }
}
