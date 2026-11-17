package com.docs0.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.TestDatabase;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Exchange;
import com.docs0.agent.Agent.Kind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ConversationRepositoryTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final ConversationRepository conversations = new ConversationRepository(jdbc);
    private final InteractionRepository interactions = new InteractionRepository(jdbc);
    private long tenantId;
    private long otherTenantId;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        otherTenantId = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
    }

    private static AgentResult result(Kind kind, String answer) {
        return new AgentResult(kind, answer, Confidence.HIGH, List.of(), List.of(), List.of(), 1, 10, 5,
                new BigDecimal("0.001"), 100, kind == Kind.ERROR ? "failed" : null);
    }

    @Test
    void findOrCreateReturnsTheSameConversationForTheSameThread() {
        Conversation first = conversations.findOrCreate(tenantId, "C1", "100.1", false);
        Conversation again = conversations.findOrCreate(tenantId, "C1", "100.1", false);
        Conversation otherThread = conversations.findOrCreate(tenantId, "C1", "100.9", false);

        assertThat(first).isEqualTo(new Conversation(first.id(), tenantId, "C1", "100.1", false));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(otherThread.id()).isNotEqualTo(first.id());
    }

    @Test
    void aWholeDmIsOneConversationWithAnEmptyThreadTs() {
        Conversation dm = conversations.findOrCreate(tenantId, "D1", "", true);
        Conversation sameDm = conversations.findOrCreate(tenantId, "D1", null, true);

        assertThat(dm.threadTs()).isEmpty();
        assertThat(dm.dm()).isTrue();
        assertThat(sameDm.id()).isEqualTo(dm.id());
    }

    @Test
    void conversationsAreScopedToTheTenant() {
        Conversation mine = conversations.findOrCreate(tenantId, "C1", "100.1", false);
        Conversation theirs = conversations.findOrCreate(otherTenantId, "C1", "100.1", false);
        interactions.insert(otherTenantId, theirs.id(), "U1", "q", result(Kind.ANSWER, "a"), "100.2");

        assertThat(theirs.id()).isNotEqualTo(mine.id());
        assertThat(conversations.hasInteractions(tenantId, "C1", "100.1")).isFalse();
        assertThat(conversations.hasInteractions(otherTenantId, "C1", "100.1")).isTrue();
        assertThat(conversations.history(tenantId, theirs.id())).isEmpty();
        assertThat(interactions.count(tenantId)).isZero();
        assertThat(interactions.count(otherTenantId)).isEqualTo(1);
    }

    @Test
    void anInteractionCannotBeLoggedOnAnotherTenantsConversation() {
        Conversation theirs = conversations.findOrCreate(otherTenantId, "C1", "100.1", false);

        assertThatThrownBy(() -> interactions.insert(tenantId, theirs.id(), "U1", "q", result(Kind.ANSWER, "a"), "1.2"))
                .isInstanceOf(RuntimeException.class);
        assertThat(interactions.count(otherTenantId)).isZero();
    }

    @Test
    void hasInteractionsIsTrueOnlyOnceSomethingWasLoggedInThatThread() {
        Conversation c = conversations.findOrCreate(tenantId, "C1", "100.1", false);
        assertThat(conversations.hasInteractions(tenantId, "C1", "100.1")).isFalse();

        interactions.insert(tenantId, c.id(), "U1", "q", result(Kind.ANSWER, "a"), "100.2");

        assertThat(conversations.hasInteractions(tenantId, "C1", "100.1")).isTrue();
        assertThat(conversations.hasInteractions(tenantId, "C1", "100.9")).isFalse();
        assertThat(conversations.hasInteractions(tenantId, "C2", "100.1")).isFalse();
    }

    @Test
    void historyIsTheLastTenAnsweredExchangesOldestFirst() {
        Conversation c = conversations.findOrCreate(tenantId, "D1", "", true);
        Conversation other = conversations.findOrCreate(tenantId, "D2", "", true);
        for (int i = 1; i <= 12; i++) {
            interactions.insert(tenantId, c.id(), "U1", "q" + i, result(Kind.ANSWER, "a" + i), "1." + i);
        }
        interactions.insert(tenantId, c.id(), "U1", "clarify me", result(Kind.CLARIFY, "which one?"), "2.1");
        interactions.insert(tenantId, c.id(), "U1", "unknown", result(Kind.UNKNOWN, "found nothing"), "2.2");
        interactions.insert(tenantId, c.id(), "U1", "broken", result(Kind.ERROR, "partial text"), "2.3");
        interactions.insert(tenantId, c.id(), "U1", "no answer", result(Kind.ANSWER, null), "2.4");
        interactions.insertOverBudget(tenantId, c.id(), "U1", "too late", "budget used up", "2.5");
        interactions.insert(tenantId, other.id(), "U2", "elsewhere", result(Kind.ANSWER, "other"), "3.1");

        List<Exchange> history = conversations.history(tenantId, c.id());

        assertThat(history).hasSize(10);
        assertThat(history).extracting(Exchange::question).containsExactly("q5", "q6", "q7", "q8", "q9", "q10", "q11",
                "q12", "clarify me", "unknown");
        assertThat(history.get(9)).isEqualTo(new Exchange("unknown", "found nothing"));
    }

    @Test
    void insertStoresEveryFieldOfTheResult() {
        Conversation c = conversations.findOrCreate(tenantId, "C1", "100.1", false);
        AgentResult result = new AgentResult(Kind.ANSWER, "It is $10 [1].", Confidence.LOW,
                List.of(new Citation(1, 42, "Pricing \"2026\"", "https://docs.example/p", "Seats cost $10.")),
                List.of("And enterprise?"), List.of("Asha"), 5, 2000, 300, new BigDecimal("0.007"), 3100, null);

        long id = interactions.insert(tenantId, c.id(), "U9", "what is pricing?", result, "100.2");

        Map<String, Object> row = jdbc.sql("""
                        SELECT *, citations::text AS citations_json FROM interaction
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).query().singleRow();
        assertThat(row).containsEntry("conversation_id", c.id())
                .containsEntry("slack_user_id", "U9")
                .containsEntry("question", "what is pricing?")
                .containsEntry("answer", "It is $10 [1].")
                .containsEntry("kind", "ANSWER")
                .containsEntry("confidence", "LOW")
                .containsEntry("steps", 5)
                .containsEntry("input_tokens", 2000L)
                .containsEntry("output_tokens", 300L)
                .containsEntry("duration_ms", 3100L)
                .containsEntry("error", null)
                .containsEntry("answer_ts", "100.2");
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.007");
        assertThat((String) row.get("citations_json")).isEqualTo(
                "[{\"url\": \"https://docs.example/p\", \"index\": 1, \"quote\": \"Seats cost $10.\", "
                        + "\"title\": \"Pricing \\\"2026\\\"\", \"documentId\": 42}]");
    }

    @Test
    void insertStoresErrorsAndOverBudgetRefusals() {
        Conversation c = conversations.findOrCreate(tenantId, "C1", "100.1", false);

        long errorId = interactions.insert(tenantId, c.id(), "U1", "q", new AgentResult(Kind.ERROR, null, null, null,
                null, null, 0, 0, 0, null, 40, "rate limited"), "100.2");
        long refusedId = interactions.insertOverBudget(tenantId, c.id(), "U1", "q2", "budget used up", "100.3");

        Map<String, Object> error = jdbc.sql("SELECT * FROM interaction WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId).param("id", errorId).query().singleRow();
        Map<String, Object> refused = jdbc.sql("SELECT * FROM interaction WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId).param("id", refusedId).query().singleRow();
        assertThat(error).containsEntry("kind", "ERROR").containsEntry("answer", null)
                .containsEntry("confidence", null).containsEntry("error", "rate limited");
        assertThat(error.get("citations").toString()).isEqualTo("[]");
        assertThat(refused).containsEntry("kind", "OVER_BUDGET").containsEntry("answer", "budget used up")
                .containsEntry("answer_ts", "100.3").containsEntry("input_tokens", 0L);
        assertThat((BigDecimal) refused.get("cost_usd")).isEqualByComparingTo("0");
    }

    private Boolean channelPrivate(Conversation c) {
        return jdbc.sql("SELECT channel_private FROM conversation WHERE id = :id").param("id", c.id())
                .query((rs, n) -> rs.getObject(1, Boolean.class)).list().get(0);
    }

    @Test
    void channelPrivacyIsRecordedFilledInLaterAndNeverBlankedOrFlipped() {
        Conversation unknown = conversations.findOrCreate(tenantId, "C1", "1.1", false, null);
        assertThat(channelPrivate(unknown)).isNull();

        conversations.findOrCreate(tenantId, "C1", "1.1", false, false);
        assertThat(channelPrivate(unknown)).isFalse();

        conversations.findOrCreate(tenantId, "C1", "1.1", false, null);
        assertThat(channelPrivate(unknown)).isFalse();

        Conversation priv = conversations.findOrCreate(tenantId, "C2", "1.1", false, true);
        conversations.findOrCreate(tenantId, "C2", "1.1", false, null);
        assertThat(channelPrivate(priv)).isTrue();
        // The four-argument form: a DM is private, a channel is unknown.
        assertThat(channelPrivate(conversations.findOrCreate(tenantId, "D1", "", true))).isTrue();
        assertThat(channelPrivate(conversations.findOrCreate(tenantId, "C3", "1.1", false))).isNull();
    }
}
