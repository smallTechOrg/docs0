package com.docs0.source.slack;

import static com.docs0.source.slack.FakeSlack.msg;
import static com.docs0.source.slack.FakeSlack.reply;
import static com.docs0.source.slack.FakeSlack.root;
import static com.docs0.source.slack.FakeSlack.withSubtype;
import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.slack.SlackMessenger.SlackMessage;
import com.docs0.source.slack.SlackHistoryRender.Rendered;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackHistoryRenderTest {

    private static final Map<String, String> NAMES = Map.of("U1", "Priya", "U2", "Marco", "UBOT", "docs0");
    // 2026-11-02 10:15:00 UTC
    private static final String T1015 = "1793614500.000100";
    private static final String T1020 = "1793614800.000200";

    private static Rendered day(SlackMessage... messages) {
        return SlackHistoryRender.day("C1", "general", LocalDate.parse("2026-11-02"), List.of(messages), "UBOT", NAMES::get);
    }

    @Test
    void aDayDocumentListsTheMessagesOfTheDayWithTimesAndNames() {
        Rendered r = day(msg(T1015, "U1", "Standup moved to <#C2|eng> &amp; <@U2> knows"), msg(T1020, "U2", "ok"));

        assertThat(r.doc().externalId()).isEqualTo("day:C1:2026-11-02");
        assertThat(r.doc().title()).isEqualTo("#general — 2 Nov 2026");
        assertThat(r.doc().mimeType()).isEqualTo("application/vnd.slack.day");
        assertThat(r.doc().body()).isEqualTo("""
                [2026-11-02 10:15] Priya: Standup moved to #eng & @Marco knows
                [2026-11-02 10:20] Marco: ok""");
        assertThat(r.doc().ownerName()).isEqualTo("Priya");
        assertThat(r.doc().modifiedAt()).isEqualTo(Instant.parse("2026-11-02T10:20:00.000200Z"));
        assertThat(r.firstTs()).isEqualTo(T1015);
        assertThat(r.doc().url()).isNull();
    }

    @Test
    void aThreadDocumentHoldsTheRootAndTheRepliesAndTitlesItselfFromTheFirstMessage() {
        Rendered r = SlackHistoryRender.thread("C1", "general", List.of(
                root(T1015, "U1", "Where do we keep the brand kit? It used to be in the old drive and now nobody knows",
                        1, T1020),
                reply(T1020, T1015, "U2", "In the shared folder")), "UBOT", NAMES::get);

        assertThat(r.doc().externalId()).isEqualTo("thread:C1:" + T1015);
        assertThat(r.doc().title()).isEqualTo("#general — Where do we keep the brand kit? It used to be in the old dri…");
        assertThat(r.doc().title().length()).isLessThan(80);
        assertThat(r.doc().mimeType()).isEqualTo("application/vnd.slack.thread");
        assertThat(r.doc().body()).contains("Priya: Where do we keep", "Marco: In the shared folder");
        assertThat(r.doc().modifiedAt()).isEqualTo(Instant.parse("2026-11-02T10:20:00.000200Z"));
    }

    @Test
    void joinsLeavesAndOtherNonConversationSubtypesAreSkipped() {
        Rendered r = day(withSubtype(T1015, "U1", "channel_join", "<@U1> has joined the channel"),
                withSubtype(T1015, "U1", "channel_topic", "set the channel topic"),
                msg(T1020, "U2", "real message"));

        assertThat(r.doc().body()).isEqualTo("[2026-11-02 10:20] Marco: real message");
        assertThat(day(withSubtype(T1015, "U1", "channel_join", "<@U1> has joined the channel"))).isNull();
    }

    @Test
    void docs0sOwnMessagesAreSkippedSoItNeverLearnsFromItself() {
        assertThat(day(msg(T1015, "UBOT", "The refund policy is 30 days."))).isNull();
        Rendered r = day(msg(T1015, "UBOT", "My own answer"), msg(T1020, "U1", "thanks"));
        assertThat(r.doc().body()).doesNotContain("My own answer").contains("Priya: thanks");
        assertThat(r.doc().title()).doesNotContain("own answer");
        // A thread that docs0 answered keeps the human parts only.
        Rendered t = SlackHistoryRender.thread("C1", "general", List.of(root(T1015, "U1", "refund policy?", 1, T1020),
                reply(T1020, T1015, "UBOT", "30 days")), "UBOT", NAMES::get);
        assertThat(t.doc().body()).isEqualTo("[2026-11-02 10:15] Priya: refund policy?");
        // The bot's reply still counts as activity, so the thread is not refetched forever.
        assertThat(t.doc().modifiedAt()).isEqualTo(Instant.parse("2026-11-02T10:20:00.000200Z"));
    }

    @Test
    void fileTitlesAreIncludedAsText() {
        SlackMessage withFile = new SlackMessage(T1015, null, "U1", null, null, "file_share", "", 0, null, null,
                List.of("Q4 plan.pdf"));

        assertThat(day(withFile).doc().body()).isEqualTo("[2026-11-02 10:15] Priya: (file: Q4 plan.pdf)");
        SlackMessage withBoth = new SlackMessage(T1015, null, "U1", null, null, null, "here you go", 0, null, null,
                List.of("a.pdf", "b.png"));
        assertThat(day(withBoth).doc().body()).endsWith("here you go (file: a.pdf) (file: b.png)");
    }

    @Test
    void anUnknownAuthorFallsBackToTheIdAndBotPostsToTheirUsername() {
        SlackMessage bot = new SlackMessage(T1020, null, null, "B1", "Deploy bot", "bot_message", "deployed", 0, null, null,
                List.of());

        Rendered r = day(msg(T1015, "U9", "hello"), bot);

        assertThat(r.doc().body()).isEqualTo("""
                [2026-11-02 10:15] U9: hello
                [2026-11-02 10:20] Deploy bot: deployed""");
    }

    @Test
    void anEditedMessageCountsAsActivity() {
        SlackMessage edited = new SlackMessage(T1015, null, "U1", null, null, null, "fixed text", 0, null,
                "1793700000.000000", List.of());

        assertThat(day(edited).doc().modifiedAt()).isEqualTo(Instant.ofEpochSecond(1793700000));
    }

    @Test
    void tsParsingKeepsMicroseconds() {
        assertThat(SlackHistoryRender.instant("1793614500.000100")).isEqualTo(Instant.parse("2026-11-02T10:15:00.000100Z"));
        assertThat(SlackHistoryRender.instant("1793614500")).isEqualTo(Instant.parse("2026-11-02T10:15:00Z"));
    }

    @Test
    void idsAreStable() {
        assertThat(SlackHistoryRender.threadId("C1", "1.2")).isEqualTo("thread:C1:1.2");
        assertThat(SlackHistoryConnector.channelOf("thread:C1:1.2")).isEqualTo("C1");
        assertThat(SlackHistoryConnector.channelOf("day:C1:2026-11-02")).isEqualTo("C1");
    }
}
