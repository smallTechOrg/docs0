package com.docs0.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.TestDocs;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.settings.TenantSettings;
import com.docs0.source.slack.FakeSlack;
import com.docs0.stale.StaleDocs;
import com.docs0.web.admin.FakeSlackConfig;
import com.docs0.web.admin.FakeThemesLlmConfig;
import com.docs0.web.admin.FakeThemesLlmConfig.ThemesLlm;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

class DigestServiceTest extends com.docs0.Phase6IntegrationTest {

    @Autowired
    DigestService digest;

    @Autowired
    FakeSlack slack;

    @Autowired
    ThemesLlm llm;

    @Autowired
    TenantSettings settings;

    @Autowired
    InteractionRepository interactions;

    @Autowired
    StaleDocs stale;

    @Autowired
    Environment environment;

    Conversation channel;
    Conversation privateChannel;
    Conversation dm;

    @BeforeEach
    void setUp() {
        slack.reset();
        llm.reset();
        slack.channel("C1", "general", false, true).channel("C2", "digest", false, true).channel("C9", "leads", true, true);
        channel = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false, false);
        privateChannel = TestData.conversation(jdbc, tenant.id(), "C9", "1.1", false, true);
        dm = TestData.conversation(jdbc, tenant.id(), "D1", "", true);
    }

    private long log(Conversation c, String user, String question, Kind kind, Confidence confidence, String cost, long... cited) {
        List<Citation> citations = java.util.Arrays.stream(cited).mapToObj(d -> new Citation(1, d, "t", "u", "q")).toList();
        AgentResult result = new AgentResult(kind, "An answer.", confidence, citations, List.of(), List.of(), 1, 100, 10,
                new BigDecimal(cost), 5, null);
        return interactions.insert(tenant.id(), c.id(), user, question, result, "9." + System.nanoTime() % 1000000);
    }

    private String sentText() {
        return slack.sent.get(0).blocks().toString() + slack.sent.get(0).text();
    }

    // ---- when it posts --------------------------------------------------------------------------------

    @Test
    void nothingIsPostedUntilAChannelIsChosen() {
        log(channel, "U1", "How do refunds work?", Kind.ANSWER, Confidence.HIGH, "0.1");

        assertThat(digest.postIfDue(tenant.id())).isEqualTo(DigestService.Outcome.NO_CHANNEL);
        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.NO_CHANNEL);
        digest.scheduled();

        assertThat(slack.sent).isEmpty();
        assertThat(llm.asked).isEmpty();
    }

    @Test
    void theScheduledRunPostsToTheChosenChannelOnceAWeek() {
        digest.setChannel(tenant.id(), "C2", "digest");
        log(channel, "U1", "How do refunds work?", Kind.ANSWER, Confidence.HIGH, "0.1");

        digest.scheduled();
        digest.scheduled();

        assertThat(slack.sent).singleElement().satisfies(m -> {
            assertThat(m.channel()).isEqualTo("C2");
            assertThat(m.threadTs()).isNull();
        });
        assertThat(digest.lastPostedAt(tenant.id())).isPresent();
        assertThat(digest.postIfDue(tenant.id())).isEqualTo(DigestService.Outcome.ALREADY_POSTED);

        settings.putInstant(tenant.id(), TenantSettings.DIGEST_LAST_AT, Instant.now().minus(Duration.ofDays(7)));
        assertThat(digest.postIfDue(tenant.id())).isEqualTo(DigestService.Outcome.POSTED);
        assertThat(slack.sent).hasSize(2);
    }

    @Test
    void postNowPostsEvenIfOneWentOutThisWeek() {
        digest.setChannel(tenant.id(), "C2", "digest");

        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.POSTED);
        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.POSTED);

        assertThat(slack.sent).hasSize(2);
    }

    @Test
    void skippedWhenTheBudgetIsUsedUp() {
        digest.setChannel(tenant.id(), "C2", "digest");
        log(channel, "U1", "How do refunds work?", Kind.ANSWER, Confidence.HIGH, "500");

        assertThat(digest.postIfDue(tenant.id())).isEqualTo(DigestService.Outcome.OVER_BUDGET);
        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.OVER_BUDGET);

        assertThat(slack.sent).isEmpty();
        assertThat(digest.lastPostedAt(tenant.id())).isEmpty();
    }

    @Test
    void aFailedPostIsNotRememberedSoTheNextRunTriesAgain() {
        digest.setChannel(tenant.id(), "C2", "digest");
        slack.postsFail = true;

        assertThat(digest.postIfDue(tenant.id())).isEqualTo(DigestService.Outcome.FAILED);
        assertThat(digest.lastPostedAt(tenant.id())).isEmpty();
        slack.postsFail = false;
        assertThat(digest.postIfDue(tenant.id())).isEqualTo(DigestService.Outcome.POSTED);
    }

    @Test
    void theScheduleIsOffInTests() {
        assertThat(environment.getProperty("docs0.digest.cron")).isEqualTo("-");
        assertThat(environment.getProperty("docs0.digest.zone")).isEqualTo("UTC");
    }

    @Test
    void choosingNoChannelTurnsTheDigestOff() {
        digest.setChannel(tenant.id(), "C2", "digest");
        digest.setChannel(tenant.id(), "", null);

        assertThat(digest.channel(tenant.id())).isEmpty();
        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.NO_CHANNEL);
    }

    // ---- what it says ---------------------------------------------------------------------------------

    @Test
    void itHasTotalsThemesGapsAndStaleDocumentsButNoPrivateOrDirectMessageContent() {
        digest.setChannel(tenant.id(), "C2", "digest");
        // Public channel gaps: three different questions, the first one asked twice.
        log(channel, "UASKER1", "Where is the lunch menu?", Kind.UNKNOWN, Confidence.LOW, "0.1");
        log(channel, "UASKER2", "where is the lunch menu", Kind.UNKNOWN, Confidence.LOW, "0.1");
        log(channel, "UASKER2", "How is parental leave calculated?", Kind.ANSWER, Confidence.LOW, "0.1");
        // Direct messages and private channels and private sources.
        log(dm, "UDMASKER", "Is my manager Birgit leaving?", Kind.UNKNOWN, Confidence.LOW, "0.1");
        log(privateChannel, "UPRIV", "What is the leads bonus pool?", Kind.UNKNOWN, Confidence.LOW, "0.1");
        long mail = log(channel, "UMAIL", "What did Ana write about Birchwood?", Kind.ANSWER, Confidence.LOW, "0.1");
        jdbc.sql("UPDATE interaction SET used_restricted = true WHERE id = :id").param("id", mail).update();
        // Stale documents people rely on.
        long doc = TestDocs.document(jdbc, tenant.id(), "refund", "Refund policy", "https://docs.example/refund", "Asha",
                null, Instant.parse("2023-03-15T10:00:00Z"), false);
        long cited = log(channel, "UREF", "What is the refund window?", Kind.ANSWER, Confidence.HIGH, "0.1", doc);
        stale.record(tenant.id(), cited, stale.staleAmong(tenant.id(), List.of(new Citation(1, doc, "t", "u", "q"))));
        llm.themes = List.of(ThemesLlm.theme("Refunds", 4, 0), ThemesLlm.theme("Lunch", 2, 1));

        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.POSTED);

        String text = sentText();
        assertThat(text).contains("docs0 weekly digest").contains("7 questions")
                .contains("Refunds (about 4)").contains("Lunch (about 2)")
                .contains("where is the lunch menu (asked 2 times)").contains("How is parental leave calculated?")
                .contains("<https://docs.example/refund|Refund policy>").contains("relied on 1 time")
                .contains("last updated March 2023");
        // Never in a channel: DM text, asker ids, private channel or private source questions, theme examples.
        assertThat(text).doesNotContain("Birgit").doesNotContain("bonus pool").doesNotContain("Birchwood")
                .doesNotContain("UASKER").doesNotContain("UDMASKER").doesNotContain("UPRIV").doesNotContain("UMAIL")
                .doesNotContain("<@").doesNotContain("What is the refund window?");
        // They are counted: one DM + one private channel + one private source.
        assertThat(text).contains("3 more questions not shown");
        // Themes were worked out by one call and paid for in the usage log. The model never saw the DM question or
        // the question behind the private-source answer; they are only counted.
        assertThat(llm.asked).hasSize(1);
        assertThat(llm.asked.get(0)).doesNotContain("Birgit").doesNotContain("Birchwood").doesNotContain("bonus pool");
        assertThat(text).contains("3 questions asked privately, in private channels or using private sources are not part of the themes");
        assertThat(jdbc.sql("SELECT purpose FROM usage_log").query(String.class).list()).containsExactly("themes");
    }

    @Test
    void theDigestShowsAtMostFiveGapsAndFiveStaleDocuments() {
        digest.setChannel(tenant.id(), "C2", "digest");
        for (int i = 0; i < 8; i++) {
            log(channel, "U1", "Unanswerable question number " + i + "?", Kind.UNKNOWN, Confidence.LOW, "0.01");
            long doc = TestDocs.document(jdbc, tenant.id(), "d" + i, "Old doc " + i, "https://docs.example/d" + i, "A",
                    null, Instant.parse("2023-03-15T10:00:00Z"), false);
            long id = log(channel, "U1", "refund " + i + "?", Kind.ANSWER, Confidence.HIGH, "0.01", doc);
            stale.record(tenant.id(), id, stale.staleAmong(tenant.id(), List.of(new Citation(1, doc, "t", "u", "q"))));
        }

        digest.postNow(tenant.id());

        String text = sentText();
        assertThat(text.split("Unanswerable question number").length - 1).isEqualTo(5);
        assertThat(text.split("\\|Old doc").length - 1).isEqualTo(5);
        assertThat(text).contains("3 more questions not shown");
    }

    @Test
    void whenSlackCannotListChannelsNoQuestionIsQuotedAtAll() {
        digest.setChannel(tenant.id(), "C2", "digest");
        log(channel, "U1", "Where is the lunch menu?", Kind.UNKNOWN, Confidence.LOW, "0.1");
        // The channel list is needed to know which channels are public. Break it after the channel was chosen.
        slack.listFails = true;

        assertThat(digest.postNow(tenant.id())).isEqualTo(DigestService.Outcome.POSTED);

        assertThat(sentText()).doesNotContain("lunch menu").contains("1 more question not shown");
    }

    @Test
    void anEmptyWeekStillPostsAFriendlyDigest() {
        digest.setChannel(tenant.id(), "C2", "digest");

        digest.postNow(tenant.id());

        assertThat(sentText()).contains("0 questions").contains("None this week.");
        assertThat(llm.asked).isEmpty();
    }

    @Test
    void textIsEscaped() {
        digest.setChannel(tenant.id(), "C2", "digest");
        log(channel, "U1", "Is <!channel> & <@U123> notified?", Kind.UNKNOWN, Confidence.LOW, "0.1");

        digest.postNow(tenant.id());

        assertThat(sentText()).contains("&lt;!channel&gt; &amp; &lt;@U123&gt;").doesNotContain("<!channel>");
    }
}
