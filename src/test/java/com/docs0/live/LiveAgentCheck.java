package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.agent.Agent;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.knowledge.Audience;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-key check of the whole answering path: real Postgres search, real Anthropic
 * calls. Skipped unless DOCS0_LIVE=1 and DOCS0_ANTHROPIC_API_KEY are in the
 * environment, so the normal gate never spends money.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
class LiveAgentCheck extends IntegrationTest {

    @DynamicPropertySource
    static void realKey(DynamicPropertyRegistry registry) {
        registry.add("docs0.anthropic.api-key", () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_API_KEY", ""));
        registry.add("docs0.anthropic.model",
                () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_MODEL", "claude-sonnet-5-5"));
    }

    @Autowired
    Agent agent;

    @Test
    void answersFromIndexedDocumentsWithAVerifiedQuoteAndAdmitsWhatItCannotFind() {
        long source = jdbc.sql("""
                INSERT INTO source (tenant_id, kind, name) VALUES (:t, 'GOOGLE_DRIVE', 'Google Drive') RETURNING id
                """).param("t", tenant.id()).query(Long.class).single();
        insert(source, "d1", "Refund policy 2026", """
                Refund policy (effective January 2026)

                Customers on the monthly plan can request a full refund within 21 days of purchase.
                Annual plans are refundable pro rata for the first 60 days. Refund requests go to
                billing@smalltech.example and are processed within five business days. Approvals above
                500 USD need sign-off from the finance lead, Meera Nair.
                """);
        insert(source, "d2", "Office wifi and printers", """
                The office wifi network is called ST-Guest. Printers are on the second floor next to
                the kitchen. For access problems, ask the office manager.
                """);

        List<String> progress = new ArrayList<>();
        AgentResult found = agent.answer(new AgentRequest(tenant.id(), Audience.of("U1"),
                "How long do monthly customers have to ask for a refund, and who approves big ones?", List.of()),
                progress::add);
        print("FOUND", found, progress);

        assertThat(found.error()).isNull();
        assertThat(found.kind()).isEqualTo(Agent.Kind.ANSWER);
        assertThat(found.answer()).contains("21");
        assertThat(found.citations()).isNotEmpty();
        assertThat(found.citations().get(0).title()).isEqualTo("Refund policy 2026");
        assertThat(found.costUsd().signum()).isPositive();
        assertThat(progress).isNotEmpty();

        AgentResult missing = agent.answer(new AgentRequest(tenant.id(), Audience.of("U1"),
                "What is our parental leave policy?", List.of()), Agent.ProgressListener.NONE);
        print("MISSING", missing, List.of());

        assertThat(missing.error()).isNull();
        assertThat(missing.kind()).isIn(Agent.Kind.UNKNOWN, Agent.Kind.CLARIFY);
        assertThat(missing.citations()).isEmpty();
    }

    private void insert(long source, String externalId, String title, String body) {
        jdbc.sql("""
                INSERT INTO document (tenant_id, source_id, external_id, title, mime_type, url, owner_name,
                                      modified_at, body, body_chars)
                VALUES (:t, :s, :e, :title, 'application/vnd.google-apps.document', :url, 'Meera Nair', now(),
                        :body, :chars)
                """)
                .param("t", tenant.id()).param("s", source).param("e", externalId).param("title", title)
                .param("url", "https://docs.example/" + externalId).param("body", body)
                .param("chars", body.length()).update();
    }

    private static void print(String label, AgentResult r, List<String> progress) {
        System.out.println("LIVE " + label + " kind=" + r.kind() + " confidence=" + r.confidence() + " steps="
                + r.steps() + " in=" + r.inputTokens() + " out=" + r.outputTokens() + " cost=" + r.costUsd()
                + " ms=" + r.durationMs() + " error=" + r.error());
        System.out.println("LIVE " + label + " answer: " + r.answer());
        r.citations().forEach(c -> System.out.println("LIVE " + label + " cite [" + c.index() + "] " + c.title()
                + " :: " + c.quote()));
        System.out.println("LIVE " + label + " followUps=" + r.followUps() + " people=" + r.suggestedPeople());
        System.out.println("LIVE " + label + " progress=" + progress);
    }
}
