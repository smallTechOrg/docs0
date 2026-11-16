package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.agent.Agent;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.knowledge.Audience;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-key check of corrections: a document states a wrong value, the first answer repeats it,
 * an approved correction with the right value is added, and the same question now gets the
 * corrected value and names the correction's author. Skipped unless DOCS0_LIVE=1 and
 * DOCS0_ANTHROPIC_API_KEY are in the environment, so the normal gate never spends money.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
class LiveCorrectionCheck extends IntegrationTest {

    private static final String QUESTION = "How many days do monthly customers have to ask for a refund?";

    @DynamicPropertySource
    static void realKey(DynamicPropertyRegistry registry) {
        registry.add("docs0.anthropic.api-key", () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_API_KEY", ""));
        registry.add("docs0.anthropic.model",
                () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_MODEL", "claude-sonnet-5-5"));
    }

    @Autowired
    Agent agent;

    @Test
    void anApprovedCorrectionChangesTheAnswerAndIsCredited() {
        long source = jdbc.sql("""
                INSERT INTO source (tenant_id, kind, name) VALUES (:t, 'GOOGLE_DRIVE', 'Google Drive') RETURNING id
                """).param("t", tenant.id()).query(Long.class).single();
        String body = """
                Refund policy (effective January 2026)

                Customers on the monthly plan can request a full refund within 21 days of purchase.
                Refund requests go to billing@smalltech.example and are processed within five business days.
                """;
        jdbc.sql("""
                INSERT INTO document (tenant_id, source_id, external_id, title, mime_type, url, owner_name,
                                      modified_at, body, body_chars)
                VALUES (:t, :s, 'd1', 'Refund policy 2026', 'text/plain', 'https://docs.example/d1', 'Meera Nair',
                        now(), :body, :chars)
                """)
                .param("t", tenant.id()).param("s", source).param("body", body).param("chars", body.length())
                .update();

        AgentResult before = ask();
        print("BEFORE", before);
        assertThat(before.error()).isNull();
        assertThat(before.answer()).contains("21");
        assertThat(before.correctionIds()).isEmpty();

        long correction = TestData.correction(jdbc, tenant.id(), "APPROVED", "Arjun", QUESTION,
                "Monthly plan customers have 30 days, not 21, to ask for a refund. The policy document is out of date.",
                null, false);

        AgentResult after = ask();
        print("AFTER", after);
        assertThat(after.error()).isNull();
        assertThat(after.correctionIds()).containsExactly(correction);
        assertThat(after.answer()).contains("30");
        assertThat(after.answer()).contains("Arjun");
    }

    private AgentResult ask() {
        return agent.answer(new AgentRequest(tenant.id(), Audience.of("U1"), QUESTION, List.of()),
                Agent.ProgressListener.NONE);
    }

    private static void print(String label, AgentResult r) {
        System.out.println("LIVE " + label + " kind=" + r.kind() + " confidence=" + r.confidence() + " steps="
                + r.steps() + " cost=" + r.costUsd() + " corrections=" + r.correctionIds() + " error=" + r.error());
        System.out.println("LIVE " + label + " answer: " + r.answer());
    }
}
