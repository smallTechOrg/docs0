package com.docs0.stale;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.TestDocs;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.slack.AnswerRenderer;
import com.docs0.source.slack.FakeSlack;
import com.docs0.web.admin.FakeSlackConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

class StaleDocsTest extends com.docs0.Phase6IntegrationTest {

    @Autowired
    StaleDocs stale;

    @Autowired
    FakeSlack slack;

    Conversation conversation;

    @BeforeEach
    void setUp() {
        slack.reset();
        conversation = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private long doc(String id, String title, Instant modified, String ownerEmail, boolean restricted) {
        return TestDocs.document(jdbc, tenant.id(), id, title, "https://docs.example/" + id, "Asha", ownerEmail,
                modified, restricted);
    }

    private static Citation cite(long documentId) {
        return new Citation(1, documentId, "t", "https://x.example", "q");
    }

    private long answerCiting(long... documents) {
        List<Citation> citations = java.util.Arrays.stream(documents).mapToObj(StaleDocsTest::cite).toList();
        AgentResult result = new AgentResult(Kind.ANSWER, "An answer.", Confidence.HIGH, citations, List.of(), List.of(),
                1, 10, 10, BigDecimal.ZERO, 5, null);
        return new com.docs0.conversation.InteractionRepository(jdbc).insert(tenant.id(), conversation.id(), "U1", "q?",
                result, "9." + System.nanoTime() % 100000);
    }

    // ---- detection ----------------------------------------------------------------------------------

    @Test
    void aCitedDocumentOlderThanTheThresholdIsStale() {
        long old = doc("old", "Old", daysAgo(400), null, false);
        long fresh = doc("fresh", "Fresh", daysAgo(100), null, false);
        long edge = doc("edge", "Edge", daysAgo(364), null, false);
        long undated = doc("undated", "Undated", null, null, false);

        List<StaleDocs.Stale> found = stale.staleAmong(tenant.id(), List.of(cite(old), cite(fresh), cite(edge), cite(undated)));

        assertThat(found).extracting(StaleDocs.Stale::documentId).containsExactly(old);
        assertThat(stale.staleAmong(tenant.id(), List.of())).isEmpty();
        assertThat(stale.staleAmong(tenant.id(), null)).isEmpty();
    }

    @Test
    void aDeletedDocumentIsNotFlagged() {
        long gone = doc("gone", "Gone", daysAgo(500), null, false);
        jdbc.sql("UPDATE document SET deleted = true WHERE id = :id").param("id", gone).update();

        assertThat(stale.staleAmong(tenant.id(), List.of(cite(gone)))).isEmpty();
    }

    @Test
    void theFlagIsRecordedOncePerAnswerAndDocumentAndNeverForAPrivateDocument() {
        long old = doc("old", "Old", daysAgo(400), null, false);
        long secret = doc("secret", "Secret", daysAgo(400), null, true);
        long interaction = answerCiting(old, secret);
        List<StaleDocs.Stale> found = stale.staleAmong(tenant.id(), List.of(cite(old), cite(secret)));
        assertThat(found).hasSize(2).anySatisfy(s -> assertThat(s.restricted()).isTrue());

        stale.record(tenant.id(), interaction, found);
        stale.record(tenant.id(), interaction, found);

        assertThat(jdbc.sql("SELECT document_id FROM stale_flag").query(Long.class).list()).containsExactly(old);
        long second = answerCiting(old);
        stale.record(tenant.id(), second, found);
        assertThat(jdbc.sql("SELECT count(*) FROM stale_flag WHERE document_id = :d").param("d", old).query(Long.class).single())
                .isEqualTo(2);
    }

    // ---- the note under an answer ---------------------------------------------------------------------

    private static AgentResult withCitations(List<Citation> citations) {
        return new AgentResult(Kind.ANSWER, "The refund window is 30 days.", Confidence.HIGH, citations, List.of(),
                List.of(), 1, 1, 1, BigDecimal.ZERO, 1, null);
    }

    @Test
    void theNoteNamesTheMonthAndYearOfTheOldestStaleDocumentAndSitsUnderTheSources() {
        Instant oldest = Instant.parse("2023-03-15T10:00:00Z");
        Instant newer = Instant.parse("2024-08-01T10:00:00Z");
        Instant at = StaleDocs.oldest(List.of(new StaleDocs.Stale(1, newer, false), new StaleDocs.Stale(2, oldest, false)));
        assertThat(at).isEqualTo(oldest);

        AnswerRenderer.Rendered rendered = AnswerRenderer.render(withCitations(List.of(cite(1))), null, at);

        List<String> blocks = rendered.blocks().stream().map(Object::toString).toList();
        int sources = indexContaining(blocks, "Sources");
        int note = indexContaining(blocks, ":hourglass: Based on a document last updated March 2023. It may be out of date.");
        assertThat(sources).isGreaterThanOrEqualTo(0);
        assertThat(note).isGreaterThan(sources);
        assertThat(rendered.text()).doesNotContain("hourglass");
    }

    @Test
    void noNoteWhenNothingIsStaleOrThereAreNoSources() {
        assertThat(AnswerRenderer.render(withCitations(List.of(cite(1))), null, null).blocks().toString())
                .doesNotContain("hourglass");
        assertThat(AnswerRenderer.render(withCitations(List.of()), null, Instant.parse("2023-03-15T10:00:00Z")).blocks().toString())
                .doesNotContain("hourglass");
        assertThat(StaleDocs.oldest(List.of())).isNull();
        assertThat(AnswerRenderer.render(withCitations(List.of(cite(1)))).blocks().toString()).doesNotContain("hourglass");
    }

    @Test
    void theNoteNeverPushesAnAnswerOverSlacksBlockLimit() {
        AgentResult big = new AgentResult(Kind.ANSWER, "y".repeat(3000 * 60), Confidence.LOW,
                java.util.stream.IntStream.rangeClosed(1, 80).mapToObj(i -> new Citation(i, i, "Doc " + i,
                        "https://docs.example/" + i, "q".repeat(400))).toList(),
                List.of("a?", "b?", "c?"), List.of("Asha"), 1, 1, 1, BigDecimal.ZERO, 1, null);

        AnswerRenderer.Rendered rendered = AnswerRenderer.render(big, "Lead line", Instant.parse("2023-03-15T10:00:00Z"));

        assertThat(rendered.blocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(rendered.blocks().toString()).contains("hourglass").contains("Lead line");
    }

    private static int indexContaining(List<String> blocks, String text) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    // ---- the report -----------------------------------------------------------------------------------

    @Test
    void theReportCountsReliancePerDocumentAndSkipsPrivateUpdatedAndOldFlags() {
        long popular = doc("popular", "Popular", daysAgo(400), "owner@example.com", false);
        long quiet = doc("quiet", "Quiet", daysAgo(500), null, false);
        long secret = doc("secret", "Secret", daysAgo(400), null, true);
        long updated = doc("updated", "Updated", daysAgo(400), null, false);
        List<StaleDocs.Stale> all = stale.staleAmong(tenant.id(), List.of(cite(popular), cite(quiet), cite(secret), cite(updated)));
        for (int i = 0; i < 3; i++) {
            stale.record(tenant.id(), answerCiting(popular), all.stream().filter(s -> s.documentId() == popular).toList());
        }
        stale.record(tenant.id(), answerCiting(quiet), all.stream().filter(s -> s.documentId() == quiet).toList());
        stale.record(tenant.id(), answerCiting(secret), all.stream().filter(s -> s.documentId() == secret).toList());
        stale.record(tenant.id(), answerCiting(updated), all.stream().filter(s -> s.documentId() == updated).toList());
        jdbc.sql("UPDATE document SET modified_at = now() WHERE id = :id").param("id", updated).update();
        // An old flag, outside a week.
        jdbc.sql("UPDATE stale_flag SET created_at = now() - interval '40 days' WHERE document_id = :id").param("id", quiet).update();

        List<StaleDocs.Row> week = stale.report(tenant.id(), Instant.now().minus(Duration.ofDays(7)), 10);
        List<StaleDocs.Row> quarter = stale.report(tenant.id(), Instant.now().minus(Duration.ofDays(90)), 10);

        assertThat(week).singleElement().satisfies(r -> {
            assertThat(r.documentId()).isEqualTo(popular);
            assertThat(r.reliedOn()).isEqualTo(3);
            assertThat(r.ownerName()).isEqualTo("Asha");
            assertThat(r.ownerKnown()).isTrue();
        });
        assertThat(quarter).extracting(StaleDocs.Row::documentId).containsExactly(popular, quiet);
    }

    // ---- asking the owner -----------------------------------------------------------------------------

    private long flagged(String id, String ownerEmail) {
        long document = doc(id, "Refund policy", Instant.parse("2023-03-15T10:00:00Z"), ownerEmail, false);
        stale.record(tenant.id(), answerCiting(document), stale.staleAmong(tenant.id(), List.of(cite(document))));
        return document;
    }

    @Test
    void theOwnerIsFoundBySlackEmailAndSentADirectMessage() {
        slack.user("U7", "Asha", "Owner@Example.com");
        long document = flagged("refund", "owner@example.com");

        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.SENT);

        assertThat(slack.dmsOpened).containsExactly("U7");
        assertThat(slack.sent).singleElement().satisfies(m -> {
            assertThat(m.channel()).isEqualTo("DU7");
            assertThat(m.threadTs()).isNull();
            assertThat(m.text()).isEqualTo("Hi! People keep asking docs0 questions that rely on \"Refund policy\" "
                    + "(last updated 15 March 2023). Could you check whether it's still right? <https://docs.example/refund>");
        });
        assertThat(jdbc.sql("SELECT owner_pinged_at FROM stale_flag WHERE document_id = :d").param("d", document)
                .query(java.sql.Timestamp.class).single()).isNotNull();
    }

    @Test
    void anOwnerCannotBeAskedTwiceInFourteenDays() {
        slack.user("U7", "Asha", "owner@example.com");
        long document = flagged("refund", "owner@example.com");
        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.SENT);

        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.TOO_SOON);
        assertThat(slack.sent).hasSize(1);

        jdbc.sql("UPDATE stale_flag SET owner_pinged_at = now() - interval '15 days'").update();
        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.SENT);
        assertThat(slack.sent).hasSize(2);
    }

    @Test
    void whenTheOwnerCannotBeFoundNobodyIsMessaged() {
        long noEmail = flagged("a", null);
        long unknown = flagged("b", "nobody@example.com");

        assertThat(stale.askOwner(tenant.id(), noEmail)).isEqualTo(StaleDocs.PingResult.NO_OWNER);
        assertThat(stale.askOwner(tenant.id(), unknown)).isEqualTo(StaleDocs.PingResult.NOT_IN_SLACK);

        assertThat(slack.sent).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM stale_flag WHERE owner_pinged_at IS NOT NULL").query(Long.class).single()).isZero();
    }

    @Test
    void aSlackFailureIsReportedAndDoesNotUseUpTheAllowance() {
        slack.user("U7", "Asha", "owner@example.com");
        long document = flagged("refund", "owner@example.com");
        slack.lookupFails = true;

        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.FAILED);
        slack.lookupFails = false;
        slack.postsFail = true;
        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.FAILED);
        slack.postsFail = false;

        assertThat(stale.askOwner(tenant.id(), document)).isEqualTo(StaleDocs.PingResult.SENT);
    }

    @Test
    void onlyADocumentThatWasFlaggedAndIsNotPrivateCanBeAskedAbout() {
        slack.user("U7", "Asha", "owner@example.com");
        long unflagged = doc("x", "X", daysAgo(400), "owner@example.com", false);
        long secret = doc("y", "Y", daysAgo(400), "owner@example.com", true);

        assertThat(stale.askOwner(tenant.id(), unflagged)).isEqualTo(StaleDocs.PingResult.GONE);
        assertThat(stale.askOwner(tenant.id(), secret)).isEqualTo(StaleDocs.PingResult.GONE);
        assertThat(stale.askOwner(tenant.id(), 999_999)).isEqualTo(StaleDocs.PingResult.GONE);
        assertThat(slack.sent).isEmpty();
    }
}
