package com.docs0.agent;

import static com.docs0.isolation.TwoTenants.QUESTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.LlmClient.ToolCall;
import com.docs0.config.Docs0Properties;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.isolation.TwoTenants;
import com.docs0.isolation.TwoTenants.Side;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.KnowledgeSearch;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole agent loop with a scripted model, for a question asked in one company while the other company holds
 * documents and corrections with the very same text: what the model is sent, what its tools return and what it may
 * cite come from the asking company only.
 */
class TwoTenantAgentTest extends IntegrationTest {

    @Autowired
    KnowledgeSearch search;

    @Autowired
    CorrectionRepository corrections;

    TwoTenants two;

    @BeforeEach
    void seed() {
        two = TwoTenants.seed(jdbc, tenant.id());
    }

    private AgentService service(ScriptedLlm llm) {
        Docs0Properties properties = new Docs0Properties(null, new Docs0Properties.Anthropic("test-key",
                "claude-sonnet-5-5", new BigDecimal("2.00"), new BigDecimal("10.00"), 12),
                null, null, null, null, null, null, null);
        return new AgentService(llm, search, properties, corrections,
                Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC));
    }

    private record Run(AgentResult result, ToolCall find, ToolCall readOwn, ToolCall readOther, ToolCall recent) {}

    /** The model searches, reads its own document and the other company's, lists recent ones and cites both. */
    private Run ask(ScriptedLlm llm, Side asker) {
        Side other = two.other(asker);
        ToolCall find = llm.call("search_knowledge", "query", "refund policy");
        ToolCall readOwn = llm.call("read_document", "document_id", asker.document());
        ToolCall readOther = llm.call("read_document", "document_id", other.document());
        ToolCall recent = llm.call("list_recent_documents");
        ToolCall submit = llm.call("submit_answer", "kind", "answer", "answer", "Refunds are quick [1][2].",
                "confidence", "high", "citations", List.of(
                        Map.of("document_id", asker.document(), "quote", "customers get a refund"),
                        Map.of("document_id", other.document(), "quote", "customers get a refund")),
                "follow_ups", List.of(), "suggested_people", List.of());
        llm.calls(find, readOwn, readOther, recent).calls(submit);
        AgentResult result = service(llm).answer(
                new AgentRequest(asker.tenantId(), Audience.sharedOnly(), QUESTION, List.of()),
                Agent.ProgressListener.NONE);
        return new Run(result, find, readOwn, readOther, recent);
    }

    @Test
    void aQuestionAskedAsTheSecondTenantNeverSeesTheFirstTenantsDocumentsOrCorrections() {
        ScriptedLlm llm = new ScriptedLlm();
        Side b = two.b;
        Side a = two.a;

        Run run = ask(llm, b);

        // Tool results: only B's data.
        assertThat(llm.resultFor(run.find()).content()).contains("Refund policy").doesNotContain("ALPHA")
                .doesNotContain("docs.example.com/ALPHA");
        assertThat(llm.resultFor(run.readOwn()).content()).contains("BETA").doesNotContain("ALPHA");
        assertThat(llm.resultFor(run.readOther()).isError()).isTrue();
        assertThat(llm.resultFor(run.readOther()).content()).doesNotContain("ALPHA").doesNotContain("30 days");
        assertThat(llm.resultFor(run.recent()).content()).doesNotContain("ALPHA");
        // Everything the model was ever sent, including the approved correction with identical text.
        assertThat(llm.firstMessage).contains("BETA").contains("Refunds are paid in 5 days").doesNotContain("ALPHA");
        assertThat(llm.systemPrompt).doesNotContain("ALPHA");
        assertThat(llm.batches.toString()).doesNotContain("ALPHA");
        // The record: B's correction, B's citation only.
        assertThat(run.result().correctionIds()).containsExactly(b.approvedCorrection());
        assertThat(run.result().citations()).hasSize(1);
        assertThat(run.result().citations().get(0).documentId()).isEqualTo(b.document());
        assertThat(run.result().citations()).noneMatch(c -> c.documentId() == a.document());
    }

    @Test
    void theSameHoldsInTheOtherDirection() {
        ScriptedLlm llm = new ScriptedLlm();

        Run run = ask(llm, two.a);

        assertThat(llm.resultFor(run.find()).content()).doesNotContain("BETA");
        assertThat(llm.resultFor(run.readOther()).isError()).isTrue();
        assertThat(llm.firstMessage).contains("Refunds are paid in 30 days").doesNotContain("BETA");
        assertThat(llm.batches.toString()).doesNotContain("BETA");
        assertThat(run.result().correctionIds()).containsExactly(two.a.approvedCorrection());
        assertThat(run.result().citations()).extracting(Agent.Citation::documentId).containsExactly(two.a.document());
    }
}
