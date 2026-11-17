package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.AnswerVerifier.Draft;
import com.docs0.agent.AnswerVerifier.DraftCitation;
import com.docs0.agent.LlmClient.ToolCall;
import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.source.SourceRepository;
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

/** The agent, the tools and the verifier against the real database: a hidden document never gets through. */
class AccessAwareAgentTest extends IntegrationTest {

    private static final String SECRET = "the secret bonus pool is forty percent";

    @Autowired
    KnowledgeSearch search;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    final ScriptedLlm llm = new ScriptedLlm();
    long hiddenId;
    long sharedId;

    @BeforeEach
    void documents() {
        long slack = sources.findOrCreate(tenant.id(), "SLACK", "Slack history", "SHARED").id();
        long drive = sources.findOrCreate(tenant.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
        store.upsertRestricted(tenant.id(), slack, doc("thread:C9:1.1", "#leads - bonus pool", SECRET), Set.of("u1", "u2"));
        store.upsert(tenant.id(), drive, doc("handbook", "Bonus handbook", "Bonuses are discussed each spring."));
        hiddenId = id("thread:C9:1.1");
        sharedId = id("handbook");
    }

    private static DocumentWrite doc(String externalId, String title, String body) {
        return new DocumentWrite(externalId, title, "text/plain", "https://example.com/" + externalId, "Priya", null,
                Instant.parse("2026-11-01T10:00:00Z"), body);
    }

    private long id(String externalId) {
        return jdbc.sql("SELECT id FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.id()).param("e", externalId).query(Long.class).single();
    }

    private AgentService service() {
        Docs0Properties properties = new Docs0Properties(null, new Docs0Properties.Anthropic("test-key",
                "claude-sonnet-5-5", new BigDecimal("2.00"), new BigDecimal("10.00"), 12),
                null, null, null, null, null, null, null);
        return new AgentService(llm, search, properties, Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC));
    }

    /** The model searches, reads the hidden document by id, lists recent documents and cites it. */
    private AgentResult askAs(Audience audience) {
        ToolCall find = llm.call("search_knowledge", "query", "bonus pool");
        ToolCall read = llm.call("read_document", "document_id", hiddenId);
        ToolCall recent = llm.call("list_recent_documents");
        ToolCall submit = llm.call("submit_answer", "kind", "answer", "answer", "The pool is forty percent [1].",
                "confidence", "high", "citations", List.of(Map.of("document_id", hiddenId, "quote", SECRET)),
                "follow_ups", List.of(), "suggested_people", List.of());
        llm.calls(find, read, recent).calls(submit);
        found = find;
        readCall = read;
        recentCall = recent;
        return service().answer(new AgentRequest(tenant.id(), audience, "what is the bonus pool?", List.of()), p -> {});
    }

    ToolCall found;
    ToolCall readCall;
    ToolCall recentCall;

    @Test
    void anAudienceThatIsAllInsideTheChannelGetsTheDocumentAndACitation() {
        AgentResult result = askAs(Audience.of("U1", "U2"));

        assertThat(llm.resultFor(found).content()).contains("#leads - bonus pool");
        assertThat(llm.resultFor(readCall).content()).contains(SECRET);
        assertThat(result.citations()).hasSize(1);
    }

    private AgentResult readThenCite(Audience audience, long documentToRead, List<Map<String, Object>> citations) {
        llm.calls(llm.call("read_document", "document_id", documentToRead)).calls(llm.call("submit_answer", "kind",
                "answer", "answer", "Bonuses are discussed each spring.", "confidence", "high", "citations",
                citations, "follow_ups", List.of(), "suggested_people", List.of()));
        return service().answer(new AgentRequest(tenant.id(), audience, "when are bonuses discussed?", List.of()),
                p -> {});
    }

    @Test
    void readingARestrictedDocumentMarksTheResultWhetherOrNotTheAnswerCitesIt() {
        Map<String, Object> sharedCitation = Map.of("document_id", sharedId, "quote", "Bonuses are discussed each spring.");

        AgentResult citesOnlyShared = readThenCite(Audience.of("U1", "U2"), hiddenId, List.of(sharedCitation));
        assertThat(citesOnlyShared.citations()).extracting(Agent.Citation::documentId).containsExactly(sharedId);
        assertThat(citesOnlyShared.usedRestricted()).isTrue();

        AgentResult citesNothing = readThenCite(Audience.of("U1", "U2"), hiddenId, List.of());
        assertThat(citesNothing.citations()).isEmpty();
        assertThat(citesNothing.usedRestricted()).isTrue();
    }

    @Test
    void theResultIsNotMarkedWhenNoRestrictedDocumentWasReturned() {
        Map<String, Object> sharedCitation = Map.of("document_id", sharedId, "quote", "Bonuses are discussed each spring.");

        // The shared document only; and the restricted one asked for by an audience that may not see it.
        assertThat(readThenCite(Audience.of("U1", "U2"), sharedId, List.of(sharedCitation)).usedRestricted()).isFalse();
        assertThat(readThenCite(Audience.of("U1", "U2", "U3"), hiddenId, List.of(sharedCitation)).usedRestricted())
                .isFalse();
    }

    @Test
    void anAudienceWithOneOutsiderNeverSeesTheDocumentInAnyToolResultOrCitation() {
        AgentResult result = askAs(Audience.of("U1", "U2", "U3"));

        assertThat(llm.resultFor(found).content()).doesNotContain("#leads").doesNotContain("secret bonus")
                .contains("Bonus handbook");
        assertThat(llm.resultFor(readCall).isError()).isTrue();
        assertThat(llm.resultFor(readCall).content()).doesNotContain(SECRET).doesNotContain("#leads");
        assertThat(llm.resultFor(recentCall).content()).doesNotContain("#leads").doesNotContain("secret bonus");
        // Every message sent to the model, not only these results, is free of it.
        assertThat(llm.batches.toString()).doesNotContain(SECRET);
        // The verifier cannot confirm a quote from it either.
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void anEmptyAudienceSeesOnlySharedKnowledge() {
        AgentResult result = askAs(Audience.sharedOnly());

        assertThat(llm.resultFor(found).content()).doesNotContain("#leads").contains("Bonus handbook");
        assertThat(llm.resultFor(readCall).isError()).isTrue();
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void theVerifierDropsAQuoteFromADocumentTheAudienceCannotSee() {
        AnswerVerifier verifier = new AnswerVerifier(search);
        Draft draft = new Draft(Agent.Kind.ANSWER, "The pool is forty percent [1].", Agent.Confidence.HIGH,
                List.of(new DraftCitation(hiddenId, SECRET)), List.of(), List.of());

        assertThat(verifier.verify(draft, tenant.id(), Audience.of("U1", "U2")).citations()).hasSize(1);
        assertThat(verifier.verify(draft, tenant.id(), Audience.of("U1", "U2", "U3")).citations()).isEmpty();
        assertThat(verifier.verify(draft, tenant.id(), Audience.sharedOnly()).citations()).isEmpty();
        assertThat(verifier.verify(draft, tenant.id(), Audience.sharedOnly()).droppedCitations()).isEqualTo(1);
    }
}
