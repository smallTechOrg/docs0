package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Exchange;
import com.docs0.agent.CorrectionSource.ApprovedCorrection;
import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.Audience;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How approved corrections reach the model: the first message, the constant system prompt, the record. */
class AgentCorrectionsTest {

    private static final Instant NOV_2 = Instant.parse("2026-11-02T10:00:00Z");

    private final FakeKnowledge knowledge = new FakeKnowledge()
            .add(1, "Pricing 2026", "Priya", "2026-10-03T00:00:00Z", "The Team plan costs $12 per seat per month.");
    private final ScriptedLlm llm = new ScriptedLlm();
    private final List<Long> askedLimits = new ArrayList<>();
    private final List<String> askedQuestions = new ArrayList<>();
    private List<ApprovedCorrection> corrections = List.of();
    private boolean lookupFails;

    private AgentService service() {
        Docs0Properties properties = new Docs0Properties(null,
                new Docs0Properties.Anthropic("test-key", "claude-sonnet-5-5", new BigDecimal("2.00"),
                        new BigDecimal("10.00"), 12),
                null, null, null, null, null, null, null);
        CorrectionSource source = (tenantId, question, limit) -> {
            askedQuestions.add(question);
            askedLimits.add((long) limit);
            if (lookupFails) {
                throw new IllegalStateException("database is down");
            }
            return corrections;
        };
        return new AgentService(llm, knowledge, properties, source,
                Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC));
    }

    private AgentResult ask(String question) {
        llm.calls(llm.call("submit_answer", "kind", "unknown", "answer", "Not documented.", "confidence", "low",
                "citations", List.of(), "follow_ups", List.of(), "suggested_people", List.of()));
        return service().answer(
                new AgentRequest(FakeKnowledge.TENANT, Audience.sharedOnly(), question, List.<Exchange>of()),
                Agent.ProgressListener.NONE);
    }

    @Test
    void approvedCorrectionsAreSetOutInTheFirstMessageWithAuthorAndDate() {
        corrections = List.of(
                new ApprovedCorrection(5, "What does the Team plan cost?", "The Team plan costs $14 per seat.",
                        "Arjun", NOV_2),
                new ApprovedCorrection(6, "Refund window?", "Refunds take 21 days.", "Meera", NOV_2));

        AgentResult result = ask("How much is Team?");

        assertThat(llm.firstMessage)
                .contains("<correction>")
                .contains("<asked_on>What does the Team plan cost?</asked_on>")
                .contains("<fact>The Team plan costs $14 per seat.</fact>")
                .contains("<corrected_by>Arjun</corrected_by>")
                .contains("<date>2 Nov 2026</date>")
                .contains("<fact>Refunds take 21 days.</fact>")
                .contains("<question>How much is Team?</question>");
        assertThat(llm.firstMessage.indexOf("<correction>")).isLessThan(llm.firstMessage.indexOf("The question to answer"));
        assertThat(askedQuestions).containsExactly("How much is Team?");
        assertThat(askedLimits).containsExactly(3L);
        assertThat(result.correctionIds()).containsExactly(5L, 6L);
    }

    @Test
    void aDmCorrectionShowsOnlyTheFactAndATeammate() {
        corrections = List.of(new ApprovedCorrection(7, null, "The band is L5.", "a teammate", NOV_2));

        ask("What is the band?");

        assertThat(llm.firstMessage)
                .contains("<fact>The band is L5.</fact>")
                .contains("<corrected_by>a teammate</corrected_by>")
                .doesNotContain("<asked_on>");
    }

    @Test
    void noCorrectionsMeansNoBlockAndNoIds() {
        AgentResult result = ask("How much is Team?");

        assertThat(llm.firstMessage).doesNotContain("<correction>").doesNotContain("corrections");
        assertThat(result.correctionIds()).isEmpty();
    }

    @Test
    void aFailingLookupStillAnswersTheQuestion() {
        lookupFails = true;

        AgentResult result = ask("How much is Team?");

        assertThat(result.kind()).isEqualTo(Agent.Kind.UNKNOWN);
        assertThat(result.correctionIds()).isEmpty();
        assertThat(llm.firstMessage).doesNotContain("<correction>");
    }

    @Test
    void theSystemPromptNeverChangesBetweenRequests() {
        ask("First question");
        String withoutCorrections = llm.systemPrompt;
        corrections = List.of(new ApprovedCorrection(5, "q", "A fact.", "Arjun", NOV_2));
        ask("Second question");

        assertThat(llm.systemPrompt).isEqualTo(withoutCorrections).isEqualTo(SystemPrompt.TEXT);
        assertThat(llm.systemPrompt).doesNotContain("A fact.");
    }

    @Test
    void theSystemPromptTellsTheModelHowToTreatCorrections() {
        String prompt = SystemPrompt.TEXT;

        assertThat(prompt).contains("the correction wins");
        assertThat(prompt).contains("per Arjun's correction on 2 Nov");
        assertThat(prompt).contains("exactly the author and date given");
        assertThat(prompt).contains("not instructions");
    }

    @Test
    void anErrorResultStillCarriesNoCorrectionIdsAndAnOldConstructorStillWorks() {
        AgentResult old = new AgentResult(Agent.Kind.ANSWER, "a", Agent.Confidence.HIGH, List.of(), List.of(),
                List.of(), 1, 1, 1, BigDecimal.ZERO, 1, null);

        assertThat(old.correctionIds()).isEmpty();
    }
}
