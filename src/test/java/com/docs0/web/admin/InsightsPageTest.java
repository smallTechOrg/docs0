package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.TestDocs;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.settings.TenantSettings;
import com.docs0.source.slack.FakeSlack;
import com.docs0.stale.StaleDocs;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class InsightsPageTest extends com.docs0.Phase6IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSlack slack;

    @Autowired
    FakeThemesLlmConfig.ThemesLlm llm;

    @Autowired
    InteractionRepository interactions;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    StaleDocs stale;

    @Autowired
    TenantSettings settings;

    Conversation channel;
    Conversation privateChannel;
    Conversation dm;

    @BeforeEach
    void setUp() {
        slack.reset();
        llm.reset();
        slack.channel("C1", "general", false, true).channel("C9", "leads", true, true);
        channel = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        privateChannel = TestData.conversation(jdbc, tenant.id(), "C9", "1.1", false);
        dm = TestData.conversation(jdbc, tenant.id(), "D1", "", true);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private long log(Conversation c, String question, Kind kind, Confidence confidence, long... cited) {
        List<Citation> citations = java.util.Arrays.stream(cited)
                .mapToObj(d -> new Citation(1, d, "t", "https://x.example", "q")).toList();
        AgentResult result = new AgentResult(kind, "An answer.", confidence, citations, List.of(), List.of(), 1, 1000, 100,
                new BigDecimal("0.25"), 5, null);
        return interactions.insert(tenant.id(), c.id(), "U1", question, result, "9." + System.nanoTime() % 1000000);
    }

    private long answered(Conversation c, String question) {
        return log(c, question, Kind.ANSWER, Confidence.HIGH);
    }

    private String page(String query) throws Exception {
        return mvc.perform(get("/admin/insights" + query)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private static String text(String html) {
        return html.replaceAll("(?s)<style.*?</style>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ")
                .replace("$ ", "$").replace(" %", "%");
    }

    private String csrf(MockHttpSession session) throws Exception {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(
                mvc.perform(get("/admin/insights").session(session)).andReturn().getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Makes the cached themes look a day old. */
    private void ageThemes() {
        jdbc.sql("UPDATE tenant_setting SET value = jsonb_set(value::jsonb, '{computedAt}', '\"2020-01-01T00:00:00Z\"')::text WHERE key = 'themes_30'")
                .update();
    }

    private long staleDoc(String id, String title, String ownerEmail, int timesRelied) {
        long doc = TestDocs.document(jdbc, tenant.id(), id, title, "https://docs.example/" + id, "Asha", ownerEmail,
                Instant.parse("2023-03-15T10:00:00Z"), false);
        for (int i = 0; i < timesRelied; i++) {
            long interaction = log(channel, "refund question " + id + i + "?", Kind.ANSWER, Confidence.HIGH, doc);
            stale.record(tenant.id(), interaction, stale.staleAmong(tenant.id(),
                    List.of(new Citation(1, doc, "t", "u", "q"))));
        }
        return doc;
    }

    // ---- empty and basic ------------------------------------------------------------------------------

    @Test
    void anEmptyDatabaseShowsFriendlyEmptyStates() throws Exception {
        String html = page("");

        assertThat(text(html)).contains("Insights").contains("No questions in this period yet.")
                .contains("No gaps in this period.").contains("No old documents were relied on in this period.")
                .contains("No channel chosen, so no digest is posted.");
        assertThat(html).contains("href=\"/admin/insights\" class=\"on\"").contains("href=\"/admin/channels\"");
        assertThat(llm.asked).isEmpty();
    }

    @Test
    void thePeriodToggleChangesWhatIsCounted() throws Exception {
        long recent = answered(channel, "What is the recent policy?");
        long fortnight = answered(channel, "What was the fortnight policy?");
        long ancient = answered(channel, "What was the ancient policy?");
        TestData.backdate(jdbc, fortnight, Instant.now().minus(Duration.ofDays(14)));
        TestData.backdate(jdbc, ancient, Instant.now().minus(Duration.ofDays(60)));

        assertThat(text(page(""))).contains("2 questions").contains("Last 30 days");
        assertThat(text(page("?days=7"))).contains("1 questions");
        assertThat(text(page("?days=90"))).contains("3 questions");
        assertThat(text(page("?days=12345"))).contains("2 questions");
        assertThat(page("?days=7")).contains("href=\"/admin/insights?days=7\" class=\"on\"");
        assertThat(recent).isPositive();
    }

    @Test
    void totalsShowReactionsTheThumbsDownRateRepeatAnswersAndSpend() throws Exception {
        long a = answered(channel, "What is the first policy?");
        long b = answered(channel, "What is the second policy?");
        feedback.record(tenant.id(), a, "U1", Rating.UP);
        feedback.record(tenant.id(), a, "U2", Rating.UP);
        feedback.record(tenant.id(), a, "U3", Rating.UP);
        feedback.record(tenant.id(), b, "U1", Rating.DOWN);
        // One unprompted answer, which got a thumbs down.
        jdbc.sql("""
                        INSERT INTO proactive_offer (tenant_id, channel_id, message_ts, matched_interaction_id, interaction_id, judged_same)
                        VALUES (:t, 'C1', '5.5', :a, :b, true)
                        """).param("t", tenant.id()).param("a", a).param("b", b).update();
        new com.docs0.budget.UsageLog(jdbc).record(tenant.id(), "themes", 1, 1, new BigDecimal("0.40"));

        String t = text(page(""));

        assertThat(t).contains("2 questions").contains("👍 3").contains("👎 1").contains("25% of reactions are 👎")
                .contains("Repeat-question answers offered: 1").contains("$0.90");
        assertThat(t).contains("docs0 offered an answer unprompted 1 time in this period").contains("100% of them got a 👎");
    }

    // ---- themes ---------------------------------------------------------------------------------------

    @Test
    void themesAreComputedOnceCachedAndShownWithAnExample() throws Exception {
        answered(channel, "How do refunds work?");
        answered(channel, "Can I get a refund?");
        llm.themes = List.of(FakeThemesLlmConfig.ThemesLlm.theme("Refunds", 4, 0),
                FakeThemesLlmConfig.ThemesLlm.theme("VPN access", 2, -1));

        String first = page("");
        String second = page("");

        assertThat(llm.asked).hasSize(1);
        assertThat(text(first)).contains("Refunds").contains("about 4").contains("VPN access");
        assertThat(text(first)).contains("For example:");
        assertThat(text(second)).contains("Refunds");
        assertThat(jdbc.sql("SELECT purpose || ':' || cost_usd FROM usage_log").query(String.class).list())
                .containsExactly("themes:0.030000");
        // The page for another period is a separate cache entry.
        page("?days=7");
        assertThat(llm.asked).hasSize(2);
    }

    @Test
    void theRefreshButtonRecomputesButIsRateLimited() throws Exception {
        answered(channel, "How do refunds work?");
        page("");
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);

        mvc.perform(post("/admin/insights/refresh").session(session).param("_csrf", token).param("days", "30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/admin/insights?days=30&notice=too-soon"));
        assertThat(llm.asked).hasSize(1);

        ageThemes();
        mvc.perform(post("/admin/insights/refresh").session(session).param("_csrf", token).param("days", "30"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/admin/insights?days=30&notice=refreshed"));
        assertThat(llm.asked).hasSize(2);
    }

    @Test
    void anExpiredCacheIsRecomputedWhenThePageIsOpened() throws Exception {
        answered(channel, "How do refunds work?");
        page("");
        ageThemes();

        page("");

        assertThat(llm.asked).hasSize(2);
    }

    @Test
    void directMessageQuestionsCountTowardThemesButAreNeverQuotedOrShown() throws Exception {
        answered(channel, "How do refunds work?");
        answered(dm, "What is my colleague Birgit's salary?");
        long privateAnswer = answered(channel, "What is the Birchwood bonus pool?");
        long secretDoc = TestDocs.document(jdbc, tenant.id(), "secret", "Bonus pool", "https://x.example/s", "A", null,
                Instant.now(), true);
        jdbc.sql("UPDATE interaction SET used_restricted = true WHERE id = :id").param("id", privateAnswer).update();
        // The model tries to pick the first quotable question and one out of range.
        llm.themes = List.of(FakeThemesLlmConfig.ThemesLlm.theme("Refunds", 1, 0),
                FakeThemesLlmConfig.ThemesLlm.theme("Pay", 2, 2),
                FakeThemesLlmConfig.ThemesLlm.theme("Bonuses", 1, 7));

        String html = page("");

        String prompt = llm.asked.get(0);
        assertThat(prompt).contains("[0] How do refunds work?");
        String other = prompt.substring(prompt.indexOf("<other_questions>"));
        assertThat(other).contains("Birgit's salary").contains("Birchwood bonus pool");
        assertThat(prompt.substring(0, prompt.indexOf("<other_questions>"))).doesNotContain("Birgit")
                .doesNotContain("Birchwood");
        assertThat(html).doesNotContain("Birgit").doesNotContain("Birchwood").contains("How do refunds work?");
        assertThat(secretDoc).isPositive();
    }

    @Test
    void theThemeCallIsCappedDeduplicatedAndTruncated() throws Exception {
        for (int i = 0; i < 320; i++) {
            answered(channel, "Question number " + i + " " + "word ".repeat(80));
        }
        answered(channel, "A repeated question?");
        answered(channel, "a REPEATED question");

        page("");

        String prompt = llm.asked.get(0);
        long lines = prompt.lines().filter(l -> l.startsWith("[")).count();
        assertThat(lines).isEqualTo(300);
        assertThat(prompt.lines().filter(l -> l.startsWith("[")).mapToInt(String::length).max().orElse(0))
                .isLessThanOrEqualTo(210);
    }

    @Test
    void duplicatesAreSentOnceWithACount() throws Exception {
        answered(channel, "How do refunds work?");
        answered(channel, "how do refunds work");
        answered(channel, "HOW DO REFUNDS WORK?!");

        page("");

        assertThat(llm.asked.get(0).lines().filter(l -> l.toLowerCase().contains("refunds work")).toList()).hasSize(1)
                .allSatisfy(l -> assertThat(l).endsWith(" x3"));
    }

    @Test
    void noQuestionsMeansNoModelCallAndAModelFailureIsShownAndNotCached() throws Exception {
        assertThat(text(page(""))).contains("No questions in this period yet.");
        assertThat(llm.asked).isEmpty();

        answered(channel, "How do refunds work?");
        llm.fails = true;
        assertThat(text(page(""))).contains("Themes could not be worked out right now");
        assertThat(settings.get(tenant.id(), "themes_30")).isEmpty();
        llm.fails = false;
        assertThat(text(page(""))).contains("Refunds");
    }

    @Test
    void withTheBudgetUsedUpThemesAreNotWorkedOut() throws Exception {
        answered(channel, "How do refunds work?");
        log(channel, "A very expensive one?", Kind.ANSWER, Confidence.HIGH);
        jdbc.sql("UPDATE interaction SET cost_usd = 200").update();

        assertThat(text(page(""))).contains("budget is used up");
        assertThat(llm.asked).isEmpty();
    }

    // ---- knowledge gaps -------------------------------------------------------------------------------

    @Test
    void gapsListUnansweredLowConfidenceAndThumbedDownQuestionsNewestFirst() throws Exception {
        llm.themes = List.of();
        long unknown = log(channel, "Where is the lunch menu?", Kind.UNKNOWN, Confidence.LOW);
        long low = log(channel, "How is parental leave calculated?", Kind.ANSWER, Confidence.LOW);
        long down = log(channel, "What is the travel limit?", Kind.ANSWER, Confidence.HIGH);
        answered(channel, "What is a fine question?");
        feedback.record(tenant.id(), down, "U1", Rating.DOWN);
        TestData.backdate(jdbc, unknown, Instant.now().minus(Duration.ofDays(3)));
        TestData.backdate(jdbc, low, Instant.now().minus(Duration.ofDays(2)));

        String html = page("?days=30");

        assertThat(html).contains("Where is the lunch menu?").contains("How is parental leave calculated?")
                .contains("What is the travel limit?").doesNotContain("What is a fine question?");
        assertThat(html.indexOf("What is the travel limit?")).isLessThan(html.indexOf("How is parental leave"));
        assertThat(html.indexOf("How is parental leave")).isLessThan(html.indexOf("Where is the lunch menu?"));
        assertThat(text(html)).contains("No answer found").contains("Low confidence").contains("Got a thumbs down");
    }

    @Test
    void similarQuestionsAreGroupedWithACount() throws Exception {
        llm.themes = List.of();
        log(channel, "Where is the lunch menu?", Kind.UNKNOWN, Confidence.LOW);
        log(channel, "where is the lunch menu", Kind.UNKNOWN, Confidence.LOW);
        log(privateChannel, "WHERE IS THE LUNCH MENU??", Kind.UNKNOWN, Confidence.LOW);
        log(channel, "Where is the gym?", Kind.UNKNOWN, Confidence.LOW);

        String t = text(page(""));

        assertThat(t).contains("asked 3 times").doesNotContain("asked 2 times");
        assertThat(t.split("Where is the gym").length - 1).isEqualTo(1);
        assertThat(t.split("(?i)where is the lunch menu").length - 1).isEqualTo(1);
    }

    @Test
    void directMessageGapsAreOnlyCountedAndPrivateSourceQuestionsAreNotShown() throws Exception {
        llm.themes = List.of();
        log(dm, "Is my manager Birgit leaving?", Kind.UNKNOWN, Confidence.LOW);
        log(dm, "What is Birgit's salary band?", Kind.UNKNOWN, Confidence.LOW);
        long privateAnswer = log(channel, "What is the Birchwood bonus pool?", Kind.ANSWER, Confidence.LOW);
        jdbc.sql("UPDATE interaction SET used_restricted = true WHERE id = :id").param("id", privateAnswer).update();
        log(channel, "Where is the lunch menu?", Kind.UNKNOWN, Confidence.LOW);

        String html = page("");

        assertThat(html).doesNotContain("Birgit").doesNotContain("Birchwood").contains("Where is the lunch menu?");
        assertThat(text(html)).contains("2 questions asked privately").contains("Question not shown (private source)");
    }

    @Test
    void aGapWhoseAnswerCitedAPrivateDocumentIsHiddenEvenWhenTheFlagWasNeverSet() throws Exception {
        long secret = TestDocs.document(jdbc, tenant.id(), "secret", "Secret plans", "https://x.example/s", "A", null,
                Instant.now(), true);
        long id = log(channel, "What are the Birchwood plans?", Kind.ANSWER, Confidence.LOW, secret);
        jdbc.sql("UPDATE interaction SET used_restricted = false WHERE id = :id").param("id", id).update();

        String html = page("");

        assertThat(html).doesNotContain("Birchwood");
        assertThat(text(html)).contains("Question not shown (private source)");
    }

    @Test
    void questionTextIsEscapedAndShortened() throws Exception {
        log(channel, "<script>alert(1)</script> " + "long ".repeat(100) + "THE-END", Kind.UNKNOWN, Confidence.LOW);
        llm.themes = List.of(FakeThemesLlmConfig.ThemesLlm.theme("<img src=x onerror=alert(2)>", 1, 0));

        String html = page("");

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("THE-END").doesNotContain("<img src=x");
    }

    // ---- stale documents -------------------------------------------------------------------------------

    @Test
    void staleDocumentsAreListedByRelianceWithOwnerLinkAndAButton() throws Exception {
        staleDoc("quiet", "Quiet policy", null, 1);
        staleDoc("popular", "Popular <b>policy</b>", "asha@example.com", 3);

        String html = page("");
        String t = text(html);

        assertThat(html.indexOf("Popular")).isLessThan(html.indexOf("Quiet policy"));
        assertThat(t).contains("Relied on 3 times").contains("Relied on 1 time").contains("last updated 15 Mar 2023")
                .contains("owner: Asha");
        assertThat(html).contains("href=\"https://docs.example/popular\"").contains("Popular &lt;b&gt;policy&lt;/b&gt;")
                .doesNotContain("<b>policy</b>").contains("Ask the owner to review");
    }

    @Test
    void theButtonAsksTheOwnerOnceAndSaysSoOnThePage() throws Exception {
        slack.user("U7", "Asha", "asha@example.com");
        long doc = staleDoc("popular", "Popular policy", "asha@example.com", 2);
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);

        mvc.perform(post("/admin/insights/stale/" + doc + "/ping").session(session).param("_csrf", token).param("days", "30"))
                .andExpect(status().is3xxRedirection());

        assertThat(slack.sent).singleElement().satisfies(m -> {
            assertThat(m.channel()).isEqualTo("DU7");
            assertThat(m.text()).contains("rely on \"Popular policy\"").contains("<https://docs.example/popular>");
        });
        assertThat(text(page("?notice=pinged"))).contains("The owner has been asked to review it.").contains("Asked on");
        mvc.perform(post("/admin/insights/stale/" + doc + "/ping").session(session).param("_csrf", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/insights?days=30&notice=ping-too-soon"));
        assertThat(slack.sent).hasSize(1);
    }

    @Test
    void anOwnerWhoCannotBeResolvedIsSaidSoOnThePage() throws Exception {
        long doc = staleDoc("popular", "Popular policy", "ghost@example.com", 1);
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);

        mvc.perform(post("/admin/insights/stale/" + doc + "/ping").session(session).param("_csrf", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/insights?days=30&notice=ping-not-in-slack"));

        assertThat(slack.sent).isEmpty();
        assertThat(text(page("?notice=ping-not-in-slack"))).contains("could not find the owner in Slack");
    }

    @Test
    void thePingNeedsTheCsrfToken() throws Exception {
        slack.user("U7", "Asha", "asha@example.com");
        long doc = staleDoc("popular", "Popular policy", "asha@example.com", 1);

        mvc.perform(post("/admin/insights/stale/" + doc + "/ping")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/insights/stale/" + doc + "/ping").param("_csrf", "nope")).andExpect(status().isForbidden());

        assertThat(slack.sent).isEmpty();
    }

    @Test
    void aPrivateDocumentIsNeverListed() throws Exception {
        long secret = TestDocs.document(jdbc, tenant.id(), "secret", "Secret plans", "https://x.example/s", "A", null,
                Instant.parse("2023-03-15T10:00:00Z"), true);
        long interaction = log(channel, "q?", Kind.ANSWER, Confidence.HIGH, secret);
        jdbc.sql("INSERT INTO stale_flag (tenant_id, document_id, interaction_id, document_modified_at) VALUES (:t, :d, :i, now())")
                .param("t", tenant.id()).param("d", secret).param("i", interaction).update();

        assertThat(page("")).doesNotContain("Secret plans");
    }

    // ---- the digest settings -----------------------------------------------------------------------------

    @Test
    void theDigestChannelIsChosenOnThePageAndSavedWithCsrf() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);
        assertThat(page("")).contains("<option value=\"C1\"").contains("🔒 leads").contains("Post digest now");

        mvc.perform(post("/admin/insights/digest/channel").session(session).param("channel", "C1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/insights/digest/channel").session(session).param("_csrf", token).param("channel", "C1"))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.get(tenant.id(), TenantSettings.DIGEST_CHANNEL)).contains("C1");
        assertThat(page("")).contains("Posting to").contains("#general").contains("value=\"C1\" selected=\"selected\"");

        mvc.perform(post("/admin/insights/digest/channel").session(session).param("_csrf", token).param("channel", "CNOPE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/insights?days=30&notice=channel-bad"));
        assertThat(settings.get(tenant.id(), TenantSettings.DIGEST_CHANNEL)).contains("C1");

        mvc.perform(post("/admin/insights/digest/channel").session(session).param("_csrf", token).param("channel", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(settings.get(tenant.id(), TenantSettings.DIGEST_CHANNEL)).isEmpty();
    }

    @Test
    void postDigestNowPostsToTheChosenChannelAndNeedsOne() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);
        answered(channel, "How do refunds work?");

        mvc.perform(post("/admin/insights/digest/now").session(session).param("_csrf", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/insights?days=30&notice=digest-no-channel"));
        assertThat(slack.sent).isEmpty();

        settings.put(tenant.id(), TenantSettings.DIGEST_CHANNEL, "C1");
        mvc.perform(post("/admin/insights/digest/now").session(session).param("_csrf", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/insights?days=30&notice=posted"));

        assertThat(slack.sent).singleElement().satisfies(m -> assertThat(m.channel()).isEqualTo("C1"));
        mvc.perform(post("/admin/insights/digest/now")).andExpect(status().isForbidden());
    }

    @Test
    void theInsightsPageIsForThisMachineOnlyWithoutAPassword() throws Exception {
        mvc.perform(get("/admin/insights").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        })).andExpect(status().isForbidden());
    }
}
