package com.docs0.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.feedback.FeedbackRepository.UnhappyAnswer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class FeedbackRepositoryTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FeedbackRepository feedback = new FeedbackRepository(jdbc);
    private long tenant;
    private Conversation thread;
    private Conversation dm;

    @BeforeEach
    void setUp() {
        tenant = TestDatabase.resetWithTenant();
        thread = TestData.conversation(jdbc, tenant, "C1", "1.1", false);
        dm = TestData.conversation(jdbc, tenant, "D1", "", true);
    }

    @Test
    void aUsersReactionIsOneRowThatChangesAndDisappears() {
        long id = TestData.interaction(jdbc, thread, "U1", "q", "a", "9.1", "0.01");

        feedback.record(tenant, id, "U2", Rating.UP);
        feedback.record(tenant, id, "U2", Rating.DOWN);
        assertThat(feedback.count(tenant, id, Rating.UP)).isZero();
        assertThat(feedback.count(tenant, id, Rating.DOWN)).isEqualTo(1);

        feedback.remove(tenant, id, "U2", Rating.DOWN);
        assertThat(feedback.count(tenant, id, Rating.DOWN)).isZero();
    }

    @Test
    void feedbackForAnotherTenantsInteractionIsNotWritten() {
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
        long id = TestData.interaction(jdbc, thread, "U1", "q", "a", "9.1", "0.01");

        feedback.record(other, id, "U2", Rating.DOWN);

        assertThat(feedback.count(tenant, id, Rating.DOWN)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM feedback").query(Long.class).single()).isZero();
        assertThat(feedback.createPrompt(other, thread.id(), id)).isFalse();
    }

    @Test
    void aPromptIsCreatedOncePerAnswerAndWaitsUntilClearedOrOld() {
        long id = TestData.interaction(jdbc, thread, "U1", "q", "a", "9.1", "0.01");

        assertThat(feedback.createPrompt(tenant, thread.id(), id)).isTrue();
        assertThat(feedback.createPrompt(tenant, thread.id(), id)).isFalse();
        assertThat(feedback.pendingPrompt(tenant, thread.id())).contains(id);

        feedback.clearPrompts(tenant, thread.id());
        assertThat(feedback.pendingPrompt(tenant, thread.id())).isEmpty();
        assertThat(feedback.createPrompt(tenant, thread.id(), id)).as("never asked twice").isFalse();
    }

    @Test
    void unhappyAnswersAreThoseWithAThumbsDownNewestFirstWithCitationsAndCounts() {
        long first = TestData.interaction(jdbc, thread, "U1", "first q", "first a", "9.1", "0.01");
        long happy = TestData.interaction(jdbc, thread, "U1", "happy q", "happy a", "9.2", "0.01");
        long dmAnswer = TestData.interaction(jdbc, dm, "U7", "private q", "private a", "9.3", "0.01");
        long cited = new InteractionRepository(jdbc).insert(tenant, thread.id(), "U3", "cited q",
                new AgentResult(Kind.ANSWER, "cited a [1]", Confidence.HIGH,
                        List.of(new Citation(1, 4, "Pricing 2026", "https://docs.example/4", "quote")), List.of(),
                        List.of(), 1, 1, 1, BigDecimal.ZERO, 1, null),
                "9.4");
        feedback.record(tenant, first, "U2", Rating.DOWN);
        feedback.record(tenant, first, "U3", Rating.DOWN);
        feedback.record(tenant, first, "U4", Rating.UP);
        feedback.record(tenant, happy, "U2", Rating.UP);
        feedback.record(tenant, dmAnswer, "U7", Rating.DOWN);
        feedback.record(tenant, cited, "U2", Rating.DOWN);

        List<UnhappyAnswer> unhappy = feedback.unhappy(tenant, 10);

        assertThat(unhappy).extracting(UnhappyAnswer::interactionId).containsExactly(cited, dmAnswer, first);
        assertThat(unhappy.get(0).citations()).containsExactly(
                new FeedbackRepository.CitedSource("Pricing 2026", "https://docs.example/4"));
        assertThat(unhappy.get(1).dm()).isTrue();
        assertThat(unhappy.get(2).thumbsDown()).isEqualTo(2);
        assertThat(unhappy.get(2).thumbsUp()).isEqualTo(1);
        assertThat(feedback.unhappy(tenant + 1, 10)).isEmpty();
    }

    @Test
    void theCorrectionsUsedAreRecordedOnTheInteraction() {
        AgentResult result = new AgentResult(Kind.ANSWER, "a", Confidence.HIGH, List.of(), List.of(), List.of(), 1, 1,
                1, BigDecimal.ZERO, 1, null, List.of(5L, 8L));

        long id = new InteractionRepository(jdbc).insert(tenant, thread.id(), "U1", "q", result, "9.1");

        String stored = jdbc.sql("SELECT correction_ids::text FROM interaction WHERE id = :id").param("id", id)
                .query(String.class).single();
        assertThat(stored).isEqualTo("[5, 8]");
        long without = TestData.interaction(jdbc, thread, "U1", "q2", "a", "9.2", "0.01");
        assertThat(jdbc.sql("SELECT correction_ids::text FROM interaction WHERE id = :id").param("id", without)
                .query(String.class).single()).isEqualTo("[]");
    }
}
