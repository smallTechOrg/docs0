package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.insights.ThemeService;
import com.docs0.settings.ChannelSettings;
import com.docs0.slack.ProactiveResponder;
import com.docs0.slack.SlackMessenger.SlackMessage;
import com.docs0.source.slack.FakeSlack;
import com.docs0.web.admin.FakeSlackConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-key check of the two small model calls of Phase 6: the repeat-question judge (one paraphrase of a past
 * question and one different question that shares words) and the theme call over about fifteen questions. Prints
 * the verdicts, the themes and the cost. Skipped unless DOCS0_LIVE=1 and DOCS0_ANTHROPIC_API_KEY are set, so the
 * normal gate never spends money. Slack is a fake; nothing is posted anywhere.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
@Import(FakeSlackConfig.class)
class LiveProactiveCheck extends IntegrationTest {

    @DynamicPropertySource
    static void realKey(DynamicPropertyRegistry registry) {
        registry.add("docs0.anthropic.api-key", () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_API_KEY", ""));
        registry.add("docs0.anthropic.model",
                () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_MODEL", "claude-sonnet-5-5"));
    }

    @Autowired
    ProactiveResponder responder;

    @Autowired
    ThemeService themes;

    @Autowired
    InteractionRepository interactions;

    @Autowired
    ChannelSettings channels;

    @Autowired
    FakeSlack slack;

    private void past(Conversation c, String question) {
        AgentResult result = new AgentResult(Kind.ANSWER, "An answer.", Confidence.HIGH, List.of(), List.of(), List.of(),
                1, 100, 10, new BigDecimal("0.01"), 5, null);
        interactions.insert(tenant.id(), c.id(), "U9", question, result, "9." + System.nanoTime() % 1000000);
    }

    private Optional<ProactiveResponder.Offer> consider(String ts, String text) {
        slack.thread("C1", new SlackMessage(ts, ts, "U1", null, null, null, text, 0, null, null, List.of()));
        return responder.consider(tenant.id(), "C1", ts, text);
    }

    private BigDecimal usage() {
        return jdbc.sql("SELECT coalesce(sum(cost_usd), 0) FROM usage_log").query(BigDecimal.class).single();
    }

    @Test
    void theJudgeSeparatesAParaphraseFromADifferentQuestion() {
        slack.reset();
        channels.saveProactive(tenant.id(), List.of(new ChannelSettings.Channel("C1", "general")), Set.of("C1"));
        past(TestData.conversation(jdbc, tenant.id(), "C2", "1.1", false),
                "How many vacation days do new employees get in their first year?");
        past(TestData.conversation(jdbc, tenant.id(), "C2", "1.2", false), "What is the process to reset my VPN password?");

        Optional<ProactiveResponder.Offer> paraphrase = consider("100.1",
                "how much PTO do you get when you just joined the company?");
        System.out.println("LIVE judge paraphrase -> " + paraphrase.isPresent() + " cost=" + usage());
        BigDecimal afterFirst = usage();

        // Shares words with the second past question, but asks something else.
        Optional<ProactiveResponder.Offer> different = consider("100.2",
                "Who is responsible for paying the VPN subscription process each month?");
        System.out.println("LIVE judge different -> " + different.isPresent() + " cost=" + usage());
        System.out.println("LIVE judge total cost=" + usage() + " (the second call cost " + usage().subtract(afterFirst) + ")");

        assertThat(paraphrase).as("a paraphrase of the vacation question").isPresent();
        assertThat(different).as("a different question about the VPN").isEmpty();
    }

    @Test
    void themesOverAboutFifteenQuestions() {
        Conversation channel = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        Conversation dm = TestData.conversation(jdbc, tenant.id(), "D1", "", true);
        String[] questions = {
            "What is our refund policy?", "How long do customers have to ask for a refund?", "Can annual plans be refunded?",
            "How do I reset my VPN password?", "VPN is not connecting from home, who can help?",
            "Where do I submit expenses?", "What is the travel expense limit per day?",
            "How many vacation days do I get?", "How do I request parental leave?",
            "Who owns the pricing page?", "What does the Team plan cost per seat?", "Is there a discount for nonprofits?",
            "How do I get access to the staging environment?"};
        for (int i = 0; i < questions.length; i++) {
            Conversation c = i % 4 == 0 ? TestData.conversation(jdbc, tenant.id(), "C1", "t" + i, false) : channel;
            past(c, questions[i]);
        }
        past(dm, "Is my salary band changing next year?");
        past(dm, "How do I raise a concern about my manager?");

        ThemeService.Result result = themes.refresh(tenant.id(), 30);

        System.out.println("LIVE themes status=" + result.status() + " questions=" + result.questions() + " cost="
                + jdbc.sql("SELECT coalesce(sum(cost_usd), 0) FROM usage_log").query(BigDecimal.class).single());
        result.themes().forEach(t -> System.out.println("LIVE theme: " + t.label() + " (~" + t.count() + ") e.g. " + t.example()));
        assertThat(result.status()).isEqualTo(ThemeService.Status.OK);
        assertThat(result.themes()).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(result.themes()).allSatisfy(t -> assertThat(t.example() == null || !t.example().contains("salary")).isTrue());
    }
}
