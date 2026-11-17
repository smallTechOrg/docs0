package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.conversation.Conversation;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class CorrectionsPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FeedbackRepository feedback;

    MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url).session(session)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    /** The text a person reads: no styles, no tags, one space between words. */
    private static String text(String html) {
        return html.replaceAll("(?s)<style.*?</style>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ")
                .replace(" )", ")").replace("( ", "(");
    }

    private String csrf() throws Exception {
        page("/admin/corrections");
        return (String) session.getAttribute("docs0.csrf");
    }

    private MockHttpServletRequestBuilder form(String path) throws Exception {
        return post(path).session(session).param("_csrf", csrf());
    }

    private long pending(String author, String question, String text, boolean dm) {
        return TestData.correction(jdbc, tenant.id(), "PENDING", author, question, text, null, dm);
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM correction WHERE id = :id").param("id", id).query(String.class).single();
    }

    // ---- sections and empty states ----------------------------------------------------------------

    @Test
    void emptyStatesAreInPlainWords() throws Exception {
        assertThat(text(page("/admin/corrections"))).contains("Nothing to review.").contains("Pending (0)");
        assertThat(page("/admin/corrections?tab=unhappy")).contains("No answer has had a thumbs down yet.");
        assertThat(page("/admin/corrections?tab=reviewed")).contains("Nothing reviewed yet.");
    }

    @Test
    void anUnknownTabShowsPending() throws Exception {
        pending("Arjun", "q?", "fact", false);

        assertThat(page("/admin/corrections?tab=bogus")).contains("id=\"pending\"").contains("Arjun");
    }

    @Test
    void pendingShowsWhoSaidWhatWithADmBadgeAndAnEditableFact() throws Exception {
        pending("Arjun", "What does the Team plan cost?", "The Team plan costs $14.", false);
        pending("Priya", "What is my band?", "The band is L5.", true);

        String html = page("/admin/corrections");

        assertThat(text(html)).contains("Pending (2)");
        assertThat(html).contains("Arjun").contains("What does the Team plan cost?")
                .contains("The old answer.").contains("<details>").contains("Approve").contains("Reject");
        assertThat(html).contains(">The Team plan costs $14.</textarea>");
        assertThat(html.split("class=\"badge\"", -1).length - 1).as("one DM badge").isEqualTo(1);
        assertThat(html).contains("Priya").contains("What is my band?").contains("name=\"_csrf\"");
    }

    @Test
    void aLongOriginalAnswerIsCutInTheSummaryAndWholeInsideTheDetails() throws Exception {
        String longAnswer = "start " + "word ".repeat(100) + "THE-END";
        long id = pending("Arjun", "q?", "fact", false);
        jdbc.sql("UPDATE correction SET original_answer = :a WHERE id = :id").param("a", longAnswer).param("id", id)
                .update();

        String html = page("/admin/corrections");

        int summary = html.indexOf("<summary>");
        int closing = html.indexOf("</summary>");
        assertThat(html.substring(summary, closing)).contains("start").doesNotContain("THE-END").contains("…");
        assertThat(html.substring(closing)).contains("THE-END");
    }

    @Test
    void unhappyAnswersShowQuestionAnswerSourcesAndCountsAndHideDmAskers() throws Exception {
        Conversation thread = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        Conversation dm = TestData.conversation(jdbc, tenant.id(), "D1", "", true);
        long open = TestData.interaction(jdbc, thread, "U123", "Public question?", "Public answer.", "9.1", "0.01");
        long secret = TestData.interaction(jdbc, dm, "U777", "Secret question?", "Secret answer.", "9.2", "0.01");
        TestData.interaction(jdbc, thread, "U123", "Happy question?", "Happy answer.", "9.3", "0.01");
        feedback.record(tenant.id(), open, "U2", Rating.DOWN);
        feedback.record(tenant.id(), open, "U3", Rating.DOWN);
        feedback.record(tenant.id(), secret, "U777", Rating.DOWN);

        String html = page("/admin/corrections?tab=unhappy");

        assertThat(text(html)).contains("Unhappy answers (2)");
        assertThat(html).contains("Public question?").contains("Public answer.")
                .contains("U123").contains("It cited no sources.");
        assertThat(html).contains("Secret question?").contains("Secret answer.");
        assertThat(html).doesNotContain("U777").doesNotContain("Happy question?");
        assertThat(html).contains(">DM<");
        assertThat(html.indexOf("Secret question?")).as("newest first").isLessThan(html.indexOf("Public question?"));
        assertThat(html).containsPattern("<span[^>]*>2</span> &#128078;");
    }

    @Test
    void reviewedShowsDecisionTimeAndOnlyApprovedOnesCanBeUnapproved() throws Exception {
        long approved = TestData.correction(jdbc, tenant.id(), "APPROVED", "Arjun", "q1", "Approved fact.", null, false);
        TestData.correction(jdbc, tenant.id(), "REJECTED", "Meera", "q2", "Rejected fact.", null, false);
        pending("Zed", "q3", "Still pending.", false);

        String html = page("/admin/corrections?tab=reviewed");

        assertThat(text(html)).contains("Reviewed (2)");
        assertThat(html).contains("Approved fact.").contains("Rejected fact.")
                .contains("Approved").contains("Rejected");
        assertThat(text(html)).containsPattern("on \\d+ \\w+ \\d{4}, \\d\\d:\\d\\d UTC");
        assertThat(html).doesNotContain("Still pending.");
        assertThat(html.split("Un-approve", -1).length - 1).isEqualTo(1);
        assertThat(html).contains("/admin/corrections/" + approved + "/unapprove");
    }

    @Test
    void reviewedListsOnlyTheLast50() throws Exception {
        for (int i = 0; i < 55; i++) {
            TestData.correction(jdbc, tenant.id(), "REJECTED", "A", "q" + i, "fact number " + i + ".", null, false);
        }

        assertThat(text(page("/admin/corrections?tab=reviewed"))).contains("Reviewed (50)");
    }

    // ---- review actions ---------------------------------------------------------------------------

    @Test
    void approveStoresTheEditedTextWhenItWasChanged() throws Exception {
        long id = pending("Arjun", "q?", "The Team plan costs $14.", false);

        mvc.perform(form("/admin/corrections/" + id + "/approve").param("text", "Team costs $14 per seat."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/corrections?tab=pending&notice=approved"));

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT edited_text FROM correction WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("Team costs $14 per seat.");
        assertThat(jdbc.sql("SELECT reviewed_at IS NOT NULL FROM correction WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
        assertThat(page("/admin/corrections?tab=pending&notice=approved")).contains("Approved.");
    }

    @Test
    void approveWithTheTextUnchangedKeepsNoEdit() throws Exception {
        long id = pending("Arjun", "q?", "The Team plan costs $14.", false);

        mvc.perform(form("/admin/corrections/" + id + "/approve").param("text", "The Team plan costs $14."))
                .andExpect(status().is3xxRedirection());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT edited_text FROM correction WHERE id = :id").param("id", id)
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void rejectAndUnapproveMoveACorrectionOutOfUse() throws Exception {
        long toReject = pending("Arjun", "q?", "fact", false);
        long toUnapprove = TestData.correction(jdbc, tenant.id(), "APPROVED", "Meera", "q", "fact", null, false);

        mvc.perform(form("/admin/corrections/" + toReject + "/reject"))
                .andExpect(redirectedUrl("/admin/corrections?tab=pending&notice=rejected"));
        mvc.perform(form("/admin/corrections/" + toUnapprove + "/unapprove"))
                .andExpect(redirectedUrl("/admin/corrections?tab=reviewed&notice=unapproved"));

        assertThat(statusOf(toReject)).isEqualTo("REJECTED");
        assertThat(statusOf(toUnapprove)).isEqualTo("REJECTED");
    }

    @Test
    void anAlreadyReviewedCorrectionCannotBeReviewedAgain() throws Exception {
        long id = TestData.correction(jdbc, tenant.id(), "REJECTED", "Arjun", "q", "fact", null, false);

        mvc.perform(form("/admin/corrections/" + id + "/approve").param("text", "x"))
                .andExpect(redirectedUrl("/admin/corrections?tab=pending&notice=gone"));

        assertThat(statusOf(id)).isEqualTo("REJECTED");
    }

    @Test
    void aPostWithoutTheCsrfTokenIsRefusedAndChangesNothing() throws Exception {
        long id = pending("Arjun", "q?", "fact", false);
        csrf();

        mvc.perform(post("/admin/corrections/" + id + "/approve").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/corrections/" + id + "/reject").session(session).param("_csrf", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/corrections/" + id + "/unapprove")).andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    // ---- safety -------------------------------------------------------------------------------------

    @Test
    void everythingRenderedIsEscaped() throws Exception {
        String evil = "<script>alert(1)</script>";
        long id = TestData.correction(jdbc, tenant.id(), "PENDING", evil, evil, "</textarea>" + evil, null, false);
        jdbc.sql("UPDATE correction SET original_answer = :a WHERE id = :id").param("a", evil).param("id", id).update();
        Conversation thread = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        long unhappy = TestData.interaction(jdbc, thread, "U1", evil, evil, "9.1", "0.01");
        feedback.record(tenant.id(), unhappy, "U2", Rating.DOWN);
        TestData.correction(jdbc, tenant.id(), "APPROVED", evil, evil, evil, null, false);

        for (String tab : new String[] {"pending", "unhappy", "reviewed"}) {
            String html = page("/admin/corrections?tab=" + tab);
            assertThat(html).as(tab).doesNotContain("<script>alert").doesNotContain("</textarea><script");
            assertThat(html).as(tab).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        }
    }

    @Test
    void theNoticeParameterCannotInjectText() throws Exception {
        assertThat(page("/admin/corrections?notice=<b>hello</b>")).doesNotContain("<b>hello</b>");
    }

    @Test
    void onlyThisTenantsCorrectionsAreListed() throws Exception {
        long other = tenants.ensure("other", "Other Co").id();
        TestData.correction(jdbc, other, "PENDING", "Stranger", "other q", "other fact", null, false);
        pending("Arjun", "our q", "our fact", false);

        assertThat(page("/admin/corrections")).contains("our q").doesNotContain("Stranger");
    }
}
