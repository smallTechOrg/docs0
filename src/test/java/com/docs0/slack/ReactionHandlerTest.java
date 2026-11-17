package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.tenant.TenantService;
import com.slack.api.model.event.ReactionAddedEvent;
import com.slack.api.model.event.ReactionRemovedEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReactionHandlerTest {

    private static final String CHANNEL = "C100";
    private static final String ANSWER_TS = "9000.0001";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private long tenantId;
    private long interactionId;
    private FeedbackRepository feedback;
    private ReactionHandler handler;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"), null, null,
                null, null, null, "", "", "");
        feedback = new FeedbackRepository(jdbc);
        handler = new ReactionHandler(new InteractionRepository(jdbc), feedback, slack);
        Conversation thread = TestData.conversation(jdbc, tenantId, CHANNEL, "100.1", false);
        interactionId = TestData.interaction(jdbc, thread, "U1", "What is the price?", "It is $12.", ANSWER_TS, "0.01");
    }

    private void react(boolean added, String user, String reaction, String channel, String ts) throws Exception {
        handler.handle(tenantId, new ReactionEvent(added, channel, user, reaction, ts)).get(10, TimeUnit.SECONDS);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.sql("SELECT slack_user_id, rating FROM feedback WHERE tenant_id = :t ORDER BY slack_user_id")
                .param("t", tenantId).query().listOfRows();
    }

    private long prompts() {
        return jdbc.sql("SELECT count(*) FROM feedback_prompt WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }

    @Test
    void thumbsUpAndThumbsDownAreRecordedPerUser() throws Exception {
        react(true, "U2", "+1", CHANNEL, ANSWER_TS);
        react(true, "U3", "thumbsdown", CHANNEL, ANSWER_TS);

        assertThat(rows()).containsExactly(
                Map.of("slack_user_id", "U2", "rating", "UP"), Map.of("slack_user_id", "U3", "rating", "DOWN"));
        assertThat(feedback.count(tenantId, interactionId, Rating.UP)).isEqualTo(1);
    }

    @Test
    void allFourNamesAndSkinTonesCount() throws Exception {
        react(true, "U2", "thumbsup", CHANNEL, ANSWER_TS);
        react(true, "U3", "-1", CHANNEL, ANSWER_TS);
        react(true, "U4", "+1::skin-tone-3", CHANNEL, ANSWER_TS);
        react(true, "U5", "thumbsdown::skin-tone-6", CHANNEL, ANSWER_TS);

        assertThat(feedback.count(tenantId, interactionId, Rating.UP)).isEqualTo(2);
        assertThat(feedback.count(tenantId, interactionId, Rating.DOWN)).isEqualTo(2);
    }

    @Test
    void changingAReactionUpdatesTheRowAndRemovingItDeletesTheRow() throws Exception {
        react(true, "U2", "+1", CHANNEL, ANSWER_TS);
        react(true, "U2", "-1", CHANNEL, ANSWER_TS);
        assertThat(rows()).containsExactly(Map.of("slack_user_id", "U2", "rating", "DOWN"));

        // Taking back the thumbs up that was already replaced leaves the thumbs down alone.
        react(false, "U2", "+1", CHANNEL, ANSWER_TS);
        assertThat(rows()).hasSize(1);

        react(false, "U2", "-1", CHANNEL, ANSWER_TS);
        assertThat(rows()).isEmpty();
    }

    @Test
    void reactionsToOtherMessagesOtherEmojiAndTheBotItselfAreIgnored() throws Exception {
        react(true, "U2", "+1", CHANNEL, "9000.9999");        // not an answer
        react(true, "U2", "+1", "C999", ANSWER_TS);           // same ts, other channel
        react(true, "U2", "tada", CHANNEL, ANSWER_TS);        // not a thumb
        react(true, "UBOT", "-1", CHANNEL, ANSWER_TS);        // docs0's own reaction
        handler.handle(tenantId, null).get(10, TimeUnit.SECONDS);
        handler.handle(tenantId, new ReactionEvent(true, CHANNEL, "", "+1", ANSWER_TS)).get(10, TimeUnit.SECONDS);

        assertThat(rows()).isEmpty();
        assertThat(slack.posts).isEmpty();
        assertThat(prompts()).isZero();
    }

    @Test
    void theFirstThumbsDownAsksWhatWasWrongInTheThreadAndOnlyOnce() throws Exception {
        react(true, "U2", "-1", CHANNEL, ANSWER_TS);

        assertThat(slack.onlyPost().threadTs()).isEqualTo("100.1");
        assertThat(slack.onlyPost().channel()).isEqualTo(CHANNEL);
        assertThat(slack.onlyPost().text()).isEqualTo(
                "Sorry about that. What was wrong, or what should the answer have said? Reply here and I'll pass it to an admin.");
        assertThat(prompts()).isEqualTo(1);
        assertThat(feedback.pendingPrompt(tenantId, conversationId())).contains(interactionId);

        // A second person, the same person again after removing and re-adding, and a changed vote: no second prompt.
        react(true, "U3", "thumbsdown", CHANNEL, ANSWER_TS);
        react(false, "U2", "-1", CHANNEL, ANSWER_TS);
        react(true, "U2", "-1", CHANNEL, ANSWER_TS);
        react(true, "U4", "+1", CHANNEL, ANSWER_TS);
        react(true, "U4", "-1", CHANNEL, ANSWER_TS);

        assertThat(slack.posts).hasSize(1);
        assertThat(prompts()).isEqualTo(1);
    }

    @Test
    void aThumbsUpDoesNotAsk() throws Exception {
        react(true, "U2", "+1", CHANNEL, ANSWER_TS);

        assertThat(slack.posts).isEmpty();
        assertThat(prompts()).isZero();
    }

    @Test
    void aPromptThatCouldNotBePostedIsForgottenSoTheNextThumbsDownAsksAgain() throws Exception {
        slack.postsFail = true;
        react(true, "U2", "-1", CHANNEL, ANSWER_TS);
        assertThat(rows()).hasSize(1);
        assertThat(prompts()).isZero();

        slack.postsFail = false;
        react(true, "U3", "-1", CHANNEL, ANSWER_TS);
        assertThat(slack.posts).hasSize(1);
    }

    @Test
    void inADmTheQuestionIsAskedUnthreaded() throws Exception {
        Conversation dm = TestData.conversation(jdbc, tenantId, "D200", "", true);
        TestData.interaction(jdbc, dm, "U1", "secret?", "Here.", "9000.0777", "0.01");

        react(true, "U1", "-1", "D200", "9000.0777");

        assertThat(slack.onlyPost().channel()).isEqualTo("D200");
        assertThat(slack.onlyPost().threadTs()).isNull();
    }

    @Test
    void slackPayloadsMapToReactionEventsAndOnlyForMessages() {
        ReactionAddedEvent added = new ReactionAddedEvent();
        added.setUser("U2");
        added.setReaction("+1");
        added.setItem(addedItem("message", CHANNEL, ANSWER_TS));
        assertThat(SlackGateway.fromReactionAdded(added))
                .isEqualTo(new ReactionEvent(true, CHANNEL, "U2", "+1", ANSWER_TS));

        ReactionRemovedEvent removed = new ReactionRemovedEvent();
        removed.setUser("U2");
        removed.setReaction("-1");
        var item = new ReactionRemovedEvent.Item();
        item.setType("message");
        item.setChannel(CHANNEL);
        item.setTs(ANSWER_TS);
        removed.setItem(item);
        assertThat(SlackGateway.fromReactionRemoved(removed))
                .isEqualTo(new ReactionEvent(false, CHANNEL, "U2", "-1", ANSWER_TS));

        added.setItem(addedItem("file", CHANNEL, ANSWER_TS));
        assertThat(SlackGateway.fromReactionAdded(added)).isNull();
        assertThat(SlackGateway.fromReactionAdded(null)).isNull();
        assertThat(SlackGateway.fromReactionRemoved(new ReactionRemovedEvent())).isNull();
    }

    private static ReactionAddedEvent.Item addedItem(String type, String channel, String ts) {
        var item = new ReactionAddedEvent.Item();
        item.setType(type);
        item.setChannel(channel);
        item.setTs(ts);
        return item;
    }

    private long conversationId() {
        return jdbc.sql("SELECT id FROM conversation WHERE tenant_id = :t AND channel_id = :c AND thread_ts = '100.1'")
                .param("t", tenantId).param("c", CHANNEL).query(Long.class).single();
    }
}
