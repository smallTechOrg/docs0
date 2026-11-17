package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.DividerBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.element.ButtonElement;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerRendererTest {

    private static AgentResult result(Kind kind, String answer, Confidence confidence, List<Citation> citations,
            List<String> followUps, List<String> people) {
        return new AgentResult(kind, answer, confidence, citations, followUps, people, 3, 100, 50,
                new BigDecimal("0.01"), 1200, null);
    }

    private static List<String> sectionTexts(AnswerRenderer.Rendered rendered) {
        return rendered.blocks().stream()
                .filter(SectionBlock.class::isInstance)
                .map(b -> ((SectionBlock) b).getText().getText())
                .toList();
    }

    private static List<String> contextTexts(AnswerRenderer.Rendered rendered) {
        return rendered.blocks().stream()
                .filter(ContextBlock.class::isInstance)
                .flatMap(b -> ((ContextBlock) b).getElements().stream())
                .map(e -> ((MarkdownTextObject) e).getText())
                .toList();
    }

    private static List<ButtonElement> buttons(AnswerRenderer.Rendered rendered) {
        return rendered.blocks().stream()
                .filter(ActionsBlock.class::isInstance)
                .flatMap(b -> ((ActionsBlock) b).getElements().stream())
                .map(ButtonElement.class::cast)
                .toList();
    }

    @Test
    void answerWithCitationsShowsNumberedLinksAndQuotes() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER,
                "Seats cost $10 [1]. Enterprise is custom [2].", Confidence.HIGH,
                List.of(new Citation(1, 7, "Pricing 2026", "https://docs.example/pricing", "Each seat costs $10."),
                        new Citation(2, 9, "Sales <FAQ> & notes", "https://docs.example/faq",
                                "Enterprise pricing\nis negotiated.")),
                List.of(), List.of()));

        List<LayoutBlock> blocks = rendered.blocks();
        assertThat(blocks.get(0)).isInstanceOf(SectionBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(DividerBlock.class);
        List<String> sections = sectionTexts(rendered);
        assertThat(sections.get(0)).isEqualTo("Seats cost $10 [1]. Enterprise is custom [2].");
        assertThat(sections.get(1)).isEqualTo("""
                *Sources*

                *[1]* <https://docs.example/pricing|Pricing 2026>
                > Each seat costs $10.

                *[2]* <https://docs.example/faq|Sales &lt;FAQ&gt; &amp; notes>
                > Enterprise pricing is negotiated.""");
        assertThat(rendered.text()).isEqualTo("Seats cost $10 [1]. Enterprise is custom [2].");
        assertThat(contextTexts(rendered)).isEmpty();
        assertThat(buttons(rendered)).isEmpty();
    }

    @Test
    void longQuotesAreTruncatedWithAnEllipsis() {
        String quote = "word ".repeat(200);
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, "See [1].", Confidence.HIGH,
                List.of(new Citation(1, 7, "Long doc", null, quote)), List.of(), List.of()));

        String sources = sectionTexts(rendered).get(1);
        String quoteLine = sources.substring(sources.indexOf("> ") + 2);
        assertThat(quoteLine).hasSizeLessThanOrEqualTo(AnswerRenderer.MAX_QUOTE_CHARS).endsWith("…");
        // No url: the title is shown without a link.
        assertThat(sources).contains("*[1]* Long doc\n");
    }

    @Test
    void lowConfidenceIsVisible() {
        AnswerRenderer.Rendered cited = AnswerRenderer.render(result(Kind.ANSWER, "Probably $10 [1].", Confidence.LOW,
                List.of(new Citation(1, 7, "Pricing", "https://docs.example/p", "about $10")), List.of(), List.of()));
        AnswerRenderer.Rendered uncited = AnswerRenderer.render(result(Kind.ANSWER, "Probably $10.", Confidence.LOW,
                List.of(), List.of(), List.of()));
        AnswerRenderer.Rendered medium = AnswerRenderer.render(result(Kind.ANSWER, "It is $10.", Confidence.MEDIUM,
                List.of(), List.of(), List.of()));

        assertThat(contextTexts(cited)).containsExactly(AnswerRenderer.LOW_CONFIDENCE);
        assertThat(contextTexts(uncited)).containsExactly(AnswerRenderer.LOW_CONFIDENCE_UNVERIFIED);
        assertThat(AnswerRenderer.LOW_CONFIDENCE).contains("Low confidence");
        assertThat(AnswerRenderer.LOW_CONFIDENCE_UNVERIFIED).contains("Low confidence");
        assertThat(contextTexts(medium)).isEmpty();
    }

    @Test
    void followUpsBecomeAtMostThreeButtonsCarryingTheQuestion() {
        String longQuestion = "What about " + "very ".repeat(30) + "long questions?";
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, "It is $10.", Confidence.HIGH,
                List.of(), List.of("And enterprise?", longQuestion, " ", "Who owns pricing?", "A fourth one?"),
                List.of()));

        List<ButtonElement> buttons = buttons(rendered);
        assertThat(buttons).hasSize(3);
        assertThat(buttons).extracting(ButtonElement::getActionId)
                .containsExactly("follow_up_0", "follow_up_1", "follow_up_2");
        assertThat(buttons).extracting(ButtonElement::getValue)
                .containsExactly("And enterprise?", longQuestion, "Who owns pricing?");
        assertThat(buttons.get(0).getText().getText()).isEqualTo("And enterprise?");
        assertThat(buttons.get(1).getText().getText()).hasSizeLessThanOrEqualTo(75).endsWith("…");
        assertThat(rendered.blocks().get(rendered.blocks().size() - 1)).isInstanceOf(ActionsBlock.class);
    }

    @Test
    void suggestedPeopleAreShown() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, "Probably $10.", Confidence.LOW,
                List.of(), List.of(), List.of("Asha Rao", "Ravi <K>")));

        assertThat(contextTexts(rendered)).contains("People who may know more: Asha Rao, Ravi &lt;K&gt;");
    }

    @Test
    void convertsMarkdownToSlackMrkdwn() {
        String markdown = """
                # Pricing overview
                The plan is **$10 per seat**, see [the pricing doc](https://docs.example/pricing?a=1&b=2).

                - First point
                  - nested __point__
                * Star bullet with `code <b>`
                ## Details ##
                1. numbered stays
                ~~old~~ new""";

        assertThat(AnswerRenderer.toMrkdwn(markdown, java.util.Set.of("docs.example"))).isEqualTo("""
                *Pricing overview*
                The plan is *$10 per seat*, see <https://docs.example/pricing?a=1&b=2|the pricing doc>.

                • First point
                  • nested *point*
                • Star bullet with `code &lt;b&gt;`
                *Details*
                1. numbered stays
                ~old~ new""");
    }

    @Test
    void anAnswerOnlyLinksToTheCitedDocumentsHostsAndSlack() {
        String text = "Read [the pricing doc](https://docs.example/pricing), [Slack help](https://my.slack.com/help), "
                + "[a phish](https://evil.example/login), <https://evil.example/x|slack.com>, <http://docs.example/plain|plain http>, "
                + "<https://slack.com.evil.example/a> and [x](https://docs.example@evil.example/).";
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, text, Confidence.HIGH,
                List.of(new Citation(1, 7, "Pricing 2026", "https://docs.example/pricing", "Each seat costs $10.")),
                List.of(), List.of()));

        String answer = sectionTexts(rendered).get(0);
        assertThat(answer).contains("<https://docs.example/pricing|the pricing doc>")
                .contains("<https://my.slack.com/help|Slack help>");
        // Anything else is plain code text: no link to click, nothing Slack can turn into one.
        assertThat(answer).contains("a phish (`https://evil.example/login`)")
                .contains("slack.com (`https://evil.example/x`)")
                .contains("plain http (`http://docs.example/plain`)")
                .contains("`https://slack.com.evil.example/a`")
                .contains("x (`https://docs.example@evil.example/`)");
        assertThat(answer).doesNotContain("<https://evil").doesNotContain("<http://").doesNotContain("evil.example|");
    }

    @Test
    void withoutCitationsNoLinkOfTheModelsOwnSurvives() {
        assertThat(AnswerRenderer.toMrkdwn("See [docs](https://docs.example/a)."))
                .isEqualTo("See docs (`https://docs.example/a`).");
    }

    @Test
    void escapesSlackControlCharactersInPlainText() {
        assertThat(AnswerRenderer.toMrkdwn("R&D says a < b > c, ping <!channel> and <@U123>"))
                .isEqualTo("R&amp;D says a &lt; b &gt; c, ping &lt;!channel&gt; and &lt;@U123&gt;");
        // Already-escaped entities, existing Slack links and quote lines survive.
        assertThat(AnswerRenderer.toMrkdwn("Q&amp;A at <https://x.example/a|the *site*>\n> quoted **line**",
                java.util.Set.of("x.example")))
                .isEqualTo("Q&amp;A at <https://x.example/a|the *site*>\n> quoted *line*");
        assertThat(AnswerRenderer.toMrkdwn("```java\nif (a < b) {}\n```")).isEqualTo("```\nif (a &lt; b) {}\n```");
        assertThat(AnswerRenderer.toMrkdwn(null)).isEmpty();
    }

    @Test
    void longAnswersAreSplitIntoSectionsWithinSlackLimits() {
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            answer.append("Paragraph ").append(i).append(" explains one more detail of the policy.\n\n");
        }
        answer.append("x".repeat(7000));

        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, answer.toString(),
                Confidence.HIGH, List.of(new Citation(1, 7, "Policy", "https://docs.example/p", "detail")),
                List.of("More?"), List.of("Asha")));

        List<String> sections = sectionTexts(rendered);
        assertThat(sections.size()).isGreaterThan(5);
        assertThat(sections).allSatisfy(s -> assertThat(s).isNotBlank().hasSizeLessThanOrEqualTo(3000));
        assertThat(sections.get(0)).startsWith("Paragraph 0 explains");
        assertThat(String.join("", sections)).contains("Paragraph 399 explains").contains("x".repeat(3000));
        assertThat(rendered.blocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(rendered.text()).hasSizeLessThanOrEqualTo(3000);
    }

    @Test
    void neverExceedsFiftyBlocks() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, "y".repeat(3000 * 60),
                Confidence.LOW,
                java.util.stream.IntStream.rangeClosed(1, 80)
                        .mapToObj(i -> new Citation(i, i, "Doc " + i, "https://docs.example/" + i, "q".repeat(400)))
                        .toList(),
                List.of("a?", "b?", "c?"), List.of("Asha")));

        assertThat(rendered.blocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(rendered.blocks().get(rendered.blocks().size() - 1)).isInstanceOf(ActionsBlock.class);
        assertThat(sectionTexts(rendered)).allSatisfy(s -> assertThat(s).hasSizeLessThanOrEqualTo(3000));
    }

    @Test
    void clarifyIsPlainTextWithNoSources() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.CLARIFY,
                "Do you mean **list** pricing or partner pricing?", Confidence.MEDIUM,
                List.of(new Citation(1, 7, "Pricing", "https://docs.example/p", "quote")), List.of("ignored?"),
                List.of("Asha")));

        assertThat(rendered.blocks()).isEmpty();
        assertThat(rendered.text()).isEqualTo("Do you mean *list* pricing or partner pricing?");
    }

    @Test
    void unknownShowsTheAnswerAndSuggestedPeopleWithoutSources() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.UNKNOWN,
                "I searched Drive for \"sabbatical policy\" and found nothing.", Confidence.LOW,
                List.of(new Citation(1, 7, "Unrelated", "https://docs.example/u", "quote")), List.of(),
                List.of("Asha Rao")));

        assertThat(sectionTexts(rendered))
                .containsExactly("I searched Drive for \"sabbatical policy\" and found nothing.");
        assertThat(contextTexts(rendered)).containsExactly("People who may know more: Asha Rao");
        assertThat(rendered.blocks()).noneMatch(DividerBlock.class::isInstance);
    }

    @Test
    void anEmptyAnswerStillRendersAMessage() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.render(result(Kind.ANSWER, "  ", Confidence.HIGH, null,
                null, null));

        assertThat(sectionTexts(rendered)).containsExactly(AnswerRenderer.EMPTY_ANSWER);
    }
}
