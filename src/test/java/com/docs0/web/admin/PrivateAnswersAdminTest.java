package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.SourceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin pages never show an answer that used a private source (a mailbox, a private channel): its text,
 * the titles and quotes it cited, and the original answer of a correction made in reply to it.
 */
@AutoConfigureMockMvc
class PrivateAnswersAdminTest extends IntegrationTest {

    private static final String NOTICE = "This answer used a private source, so it isn&#39;t shown here.";
    private static final String PRIVATE_ANSWER = "Birchwood closes on Friday for 12 million.";
    private static final String PRIVATE_TITLE = "Re: Birchwood acquisition";
    private static final String PRIVATE_QUOTE = "closes on Friday";

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    @Autowired
    InteractionRepository interactions;

    @Autowired
    FeedbackRepository feedback;

    long mailDoc;
    long channelDoc;
    long sharedDoc;
    Conversation channel;
    Conversation dm;

    @BeforeEach
    void documents() {
        long gmail = sources.findOrCreate(tenant.id(), "GMAIL", "Gmail", "SHARED").id();
        long slack = sources.findOrCreate(tenant.id(), "SLACK", "Slack history", "SHARED").id();
        long drive = sources.findOrCreate(tenant.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
        store.upsertRestricted(tenant.id(), gmail, doc("gmail:ana@example.com:t1", PRIVATE_TITLE), Set.of("ana@example.com"));
        store.upsertRestricted(tenant.id(), slack, doc("thread:C9:1.1", "#leads - bonus pool"), Set.of("u1"));
        store.upsert(tenant.id(), drive, doc("handbook", "Public handbook"));
        mailDoc = id("gmail:ana@example.com:t1");
        channelDoc = id("thread:C9:1.1");
        sharedDoc = id("handbook");
        channel = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        dm = TestData.conversation(jdbc, tenant.id(), "D1", "", true);
    }

    private static DocumentWrite doc(String externalId, String title) {
        return new DocumentWrite(externalId, title, "text/plain", "https://example.com/" + externalId, "Ana", null,
                Instant.parse("2026-11-01T10:00:00Z"), "body of " + externalId);
    }

    private long id(String externalId) {
        return jdbc.sql("SELECT id FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.id()).param("e", externalId).query(Long.class).single();
    }

    private long answer(Conversation conversation, String question, String text, String cost, long documentId,
                        String title) {
        List<Citation> citations = documentId == 0 ? List.of()
                : List.of(new Citation(1, documentId, title, "https://example.com/doc", PRIVATE_QUOTE));
        AgentResult result = new AgentResult(Kind.ANSWER, text, Confidence.HIGH, citations, List.of(), List.of(), 2,
                1000, 100, new BigDecimal(cost), 10, null);
        return interactions.insert(tenant.id(), conversation.id(), "U1", question, result, "9." + System.nanoTime() % 100000);
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void assertNoPrivateContent(String html) {
        assertThat(html).doesNotContain(PRIVATE_ANSWER).doesNotContain(PRIVATE_TITLE).doesNotContain(PRIVATE_QUOTE)
                .doesNotContain("#leads - bonus pool").doesNotContain("https://example.com/gmail:");
    }

    // ---- recording ---------------------------------------------------------------------------

    @Test
    void theAnswerIsMarkedAtLoggingTimeWhenItCitesARestrictedDocument() {
        long mail = answer(dm, "when?", PRIVATE_ANSWER, "0.01", mailDoc, PRIVATE_TITLE);
        long channelThread = answer(channel, "bonus?", "A bonus.", "0.01", channelDoc, "#leads - bonus pool");
        long shared = answer(channel, "handbook?", "Read it.", "0.01", sharedDoc, "Public handbook");
        long none = answer(channel, "hi?", "Hello.", "0.01", 0, null);

        assertThat(flag(mail)).isTrue();
        assertThat(flag(channelThread)).isTrue();
        assertThat(flag(shared)).isFalse();
        assertThat(flag(none)).isFalse();
        assertThat(interactions.usedRestricted(tenant.id(), List.of(mail, channelThread, shared, none)))
                .containsExactlyInAnyOrder(mail, channelThread);
        assertThat(interactions.usedRestricted(tenant.id(), List.of())).isEmpty();
    }

    @Test
    void answersLoggedBeforeTheFlagExistedAreStillCoveredByTheJoin() {
        long old = answer(dm, "when?", PRIVATE_ANSWER, "0.01", mailDoc, PRIVATE_TITLE);
        jdbc.sql("UPDATE interaction SET used_restricted = FALSE").update();

        assertThat(flag(old)).isFalse();
        assertThat(interactions.usedRestricted(tenant.id(), List.of(old))).containsExactly(old);
    }

    private boolean flag(long interactionId) {
        return jdbc.sql("SELECT used_restricted FROM interaction WHERE id = :id").param("id", interactionId)
                .query(Boolean.class).single();
    }

    // ---- a restricted document that was read but not cited -----------------------------------

    /** An answer the model built after the tools returned a restricted document, whatever it cited. */
    private long answerAfterReadingRestricted(Conversation conversation, String question, String text, String cost,
            Kind kind, Confidence confidence, List<Citation> citations) {
        AgentResult result = new AgentResult(kind, text, confidence, citations, List.of(), List.of(), 2, 1000, 100,
                new BigDecimal(cost), 10, null, List.of(), List.of(), true);
        return interactions.insert(tenant.id(), conversation.id(), "U1", question, result, "9." + System.nanoTime() % 100000);
    }

    @Test
    void anAnswerThatReadARestrictedDocumentIsMarkedEvenWhenItCitesOnlyASharedOneOrNothing() {
        long citesShared = answerAfterReadingRestricted(channel, "bonus?", PRIVATE_ANSWER, "0.01", Kind.ANSWER,
                Confidence.HIGH, List.of(new Citation(1, sharedDoc, "Public handbook", "https://example.com/doc", "x")));
        long citesNothing = answerAfterReadingRestricted(channel, "bonus again?", PRIVATE_ANSWER, "0.01", Kind.UNKNOWN,
                Confidence.LOW, List.of());
        long plain = answer(channel, "handbook?", "Read it.", "0.01", sharedDoc, "Public handbook");

        assertThat(flag(citesShared)).isTrue();
        assertThat(flag(citesNothing)).isTrue();
        assertThat(flag(plain)).isFalse();
        assertThat(interactions.usedRestricted(tenant.id(), List.of(citesShared, citesNothing, plain)))
                .containsExactlyInAnyOrder(citesShared, citesNothing);
    }

    @Test
    void everyAdminViewHidesAnAnswerThatReadARestrictedDocumentWithoutCitingIt() throws Exception {
        long hidden = answerAfterReadingRestricted(channel, "Secret question about Birchwood", PRIVATE_ANSWER, "40.00",
                Kind.ANSWER, Confidence.LOW, List.of(new Citation(1, sharedDoc, "Public handbook",
                        "https://example.com/doc", "x")));
        feedback.record(tenant.id(), hidden, "U2", Rating.DOWN);
        long correction = TestData.correction(jdbc, tenant.id(), "PENDING", "Ana", "Secret question about Birchwood",
                "It closes on Monday.", null, false);
        jdbc.sql("UPDATE correction SET interaction_id = :i, original_answer = :a WHERE id = :id")
                .param("i", hidden).param("a", PRIVATE_ANSWER).param("id", correction).update();

        for (String url : List.of("/admin/corrections", "/admin/corrections?tab=unhappy")) {
            assertThat(page(url)).as(url).doesNotContain(PRIVATE_ANSWER).contains(NOTICE);
        }
        assertThat(page("/admin/costs")).doesNotContain("Secret question about Birchwood")
                .contains("Question not shown (private source)");
        assertThat(page("/admin/insights")).doesNotContain("Secret question about Birchwood");
    }

    // ---- unhappy answers ---------------------------------------------------------------------

    @Test
    void unhappyAnswersThatUsedAPrivateSourceShowTheNoticeInsteadOfTheAnswerTitlesAndQuotes() throws Exception {
        long mail = answer(dm, "When does Birchwood close?", PRIVATE_ANSWER, "0.01", mailDoc, PRIVATE_TITLE);
        long privateChannel = answer(channel, "What is the bonus?", PRIVATE_ANSWER, "0.01", channelDoc, "#leads - bonus pool");
        long shared = answer(channel, "Where is the handbook?", "The handbook is in Drive.", "0.01", sharedDoc, "Public handbook");
        for (long id : List.of(mail, privateChannel, shared)) {
            feedback.record(tenant.id(), id, "U2", Rating.DOWN);
        }

        String html = page("/admin/corrections?tab=unhappy");

        assertNoPrivateContent(html);
        assertThat(html.split(NOTICE, -1).length - 1).isEqualTo(2);
        // Questions and the rest stay; the public answer is shown as before.
        assertThat(html).contains("When does Birchwood close?").contains("What is the bonus?")
                .contains("The handbook is in Drive.").contains("Public handbook");
        assertThat(html).contains(">DM<");
    }

    @Test
    void anAnswerLoggedBeforeTheFlagThatCitedAPrivateDocumentIsHiddenToo() throws Exception {
        long old = answer(dm, "When does Birchwood close?", PRIVATE_ANSWER, "0.01", mailDoc, PRIVATE_TITLE);
        jdbc.sql("UPDATE interaction SET used_restricted = FALSE").update();
        feedback.record(tenant.id(), old, "U2", Rating.DOWN);

        String html = page("/admin/corrections?tab=unhappy");

        assertNoPrivateContent(html);
        assertThat(html).contains(NOTICE);
    }

    // ---- the correction queue ----------------------------------------------------------------

    @Test
    void aCorrectionMadeInReplyToAPrivateAnswerShowsItsOriginalAnswerRedacted() throws Exception {
        long mail = answer(dm, "When does Birchwood close?", PRIVATE_ANSWER, "0.01", mailDoc, PRIVATE_TITLE);
        long shared = answer(channel, "Where is the handbook?", "The handbook is in Drive.", "0.01", sharedDoc, "Public handbook");
        long privateCorrection = TestData.correction(jdbc, tenant.id(), "PENDING", "Ana", "When does Birchwood close?",
                "It closes on Monday.", null, true);
        long sharedCorrection = TestData.correction(jdbc, tenant.id(), "PENDING", "Bob", "Where is the handbook?",
                "It is in Notion.", null, false);
        jdbc.sql("UPDATE correction SET interaction_id = :i, original_answer = :a WHERE id = :id")
                .param("i", mail).param("a", PRIVATE_ANSWER).param("id", privateCorrection).update();
        jdbc.sql("UPDATE correction SET interaction_id = :i, original_answer = :a WHERE id = :id")
                .param("i", shared).param("a", "The handbook is in Drive.").param("id", sharedCorrection).update();

        String html = page("/admin/corrections");

        assertNoPrivateContent(html);
        assertThat(html).contains(NOTICE).contains("It closes on Monday.").contains("It is in Notion.")
                .contains("The handbook is in Drive.").contains("Ana").contains("Bob");
        assertThat(html.split(NOTICE, -1).length - 1).isEqualTo(1);
        // The approve form still works on the redacted one.
        assertThat(html).contains("/admin/corrections/" + privateCorrection + "/approve");
    }

    @Test
    void theReviewedTabNeverShowsOriginalAnswersToBeginWith() throws Exception {
        long mail = answer(dm, "When does Birchwood close?", PRIVATE_ANSWER, "0.01", mailDoc, PRIVATE_TITLE);
        long reviewed = TestData.correction(jdbc, tenant.id(), "APPROVED", "Ana", "When?", "It closes on Monday.", null, true);
        jdbc.sql("UPDATE correction SET interaction_id = :i, original_answer = :a WHERE id = :id")
                .param("i", mail).param("a", PRIVATE_ANSWER).param("id", reviewed).update();

        assertNoPrivateContent(page("/admin/corrections?tab=reviewed"));
    }

    // ---- costs ---------------------------------------------------------------------------------

    @Test
    void theCostsPageDoesNotShowTheQuestionOfAnAnswerThatUsedAPrivateSource() throws Exception {
        answer(dm, "My private DM question", PRIVATE_ANSWER, "30.00", mailDoc, PRIVATE_TITLE);
        answer(channel, "What is the secret bonus pool?", PRIVATE_ANSWER, "20.00", channelDoc, "#leads - bonus pool");
        answer(channel, "What is the refund policy?", "Thirty days.", "10.00", sharedDoc, "Public handbook");

        String html = page("/admin/costs");

        assertNoPrivateContent(html);
        assertThat(html).doesNotContain("What is the secret bonus pool?").doesNotContain("My private DM question")
                .contains("DM question").contains("Question not shown (private source)")
                .contains("What is the refund policy?");
    }
}
