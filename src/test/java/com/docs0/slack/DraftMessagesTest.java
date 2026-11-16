package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.action.ActionService;
import com.docs0.action.ActionService.Outcome;
import com.docs0.action.ActionService.Result;
import com.docs0.action.PendingAction;
import com.docs0.action.PendingAction.Kind;
import com.docs0.action.PendingAction.Status;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.element.ButtonElement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How a draft and its outcome look in Slack, and that they stay inside Slack's limits. */
class DraftMessagesTest {

    private static PendingAction draft(Kind kind, Map<String, String> params) {
        return new PendingAction(42, 1, 1, null, "U7", kind, params, Status.PENDING, null, null, Instant.now(), null);
    }

    private static PendingAction issue(String title, String description) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("teamId", "t1");
        params.put("teamKey", "ENG");
        params.put("teamName", "Engineering");
        params.put("title", title);
        params.put("description", description);
        params.put("priority", "high");
        return draft(Kind.LINEAR_ISSUE, params);
    }

    private static PendingAction card() {
        return draft(Kind.TRELLO_CARD, Map.of("boardId", "b1", "boardName", "Roadmap", "listId", "l1",
                "listName", "Doing", "title", "Book venue", "description", "For the offsite"));
    }

    private static String sectionText(AnswerRenderer.Rendered r) {
        return ((SectionBlock) r.blocks().get(0)).getText().getText();
    }

    @Test
    void aLinearDraftIsLabelledAsADraftShowsTargetTitleAndDescriptionAndHasTwoButtons() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.renderDraft(issue("Fix login", "Safari users cannot sign in.\nSteps: open it."));

        assertThat(sectionText(rendered)).contains("Draft Linear issue", "not created yet", "*Title:* Fix login",
                "*Where:* Linear, team ENG (Engineering)", "*Priority:* high",
                "> Safari users cannot sign in. Steps: open it.");
        assertThat(rendered.text()).isEqualTo("Draft Linear issue: Fix login");
        ActionsBlock actions = (ActionsBlock) rendered.blocks().get(1);
        assertThat(actions.getBlockId()).isEqualTo("draft_buttons");
        assertThat(actions.getElements()).hasSize(2);
        ButtonElement create = (ButtonElement) actions.getElements().get(0);
        ButtonElement cancel = (ButtonElement) actions.getElements().get(1);
        assertThat(create.getActionId()).isEqualTo("draft_create");
        assertThat(create.getText().getText()).isEqualTo("Create");
        assertThat(create.getValue()).isEqualTo("42");
        assertThat(create.getStyle()).isEqualTo("primary");
        assertThat(cancel.getActionId()).isEqualTo("draft_cancel");
        assertThat(cancel.getText().getText()).isEqualTo("Cancel");
        assertThat(cancel.getValue()).isEqualTo("42");
        ContextBlock context = (ContextBlock) rendered.blocks().get(2);
        assertThat(context.getElements().toString()).contains("<@U7>").contains("expires after an hour");
    }

    @Test
    void aTrelloDraftNamesBoardAndList() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.renderDraft(card());

        assertThat(sectionText(rendered)).contains("Draft Trello card", "*Where:* Trello, board Roadmap, list Doing",
                "*Title:* Book venue", "> For the offsite").doesNotContain("Priority");
    }

    @Test
    void slackControlCharactersInADraftAreEscaped() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.renderDraft(issue("<!channel> R&D <b>", "see <http://x|y> & more"));

        assertThat(sectionText(rendered)).doesNotContain("<!channel>").contains("&lt;!channel&gt; R&amp;D &lt;b&gt;")
                .contains("see &lt;http://x|y&gt; &amp; more");
    }

    @Test
    void aHugeDraftStaysWithinSlacksLimits() {
        AnswerRenderer.Rendered rendered = AnswerRenderer.renderDraft(issue("T".repeat(5000), "word ".repeat(20000)));

        for (LayoutBlock block : rendered.blocks()) {
            if (block instanceof SectionBlock section) {
                assertThat(section.getText().getText().length()).isLessThanOrEqualTo(AnswerRenderer.MAX_SECTION_CHARS);
            }
        }
        assertThat(rendered.blocks()).hasSize(3);
        assertThat(rendered.text().length()).isLessThan(300);
        assertThat(sectionText(rendered)).contains("…");
        ButtonElement create = (ButtonElement) ((ActionsBlock) rendered.blocks().get(1)).getElements().get(0);
        assertThat(create.getText().getText().length()).isLessThanOrEqualTo(AnswerRenderer.MAX_BUTTON_CHARS);
    }

    @Test
    void aDraftWithNoDescriptionShowsNoQuote() {
        assertThat(sectionText(AnswerRenderer.renderDraft(issue("T", "  ")))).doesNotContain("> ");
    }

    private static Outcome outcome(Result result, PendingAction draft, String identifier, String url) {
        return new Outcome(result, draft, identifier, url);
    }

    @Test
    void aCreatedIssueBecomesALinkWithItsIdentifierAndNoButtons() {
        AnswerRenderer.Rendered r = AnswerRenderer.renderDraftOutcome(outcome(Result.CREATED, issue("Fix | login", "d"),
                "ENG-99", "https://linear.app/acme/issue/ENG-99"));

        assertThat(r.text()).isEqualTo(":white_check_mark: *Created in Linear:* <https://linear.app/acme/issue/ENG-99|ENG-99: Fix ¦ login>");
        assertThat(r.blocks()).hasSize(1).noneMatch(b -> b instanceof ActionsBlock);
    }

    @Test
    void aCreatedCardIsALinkWithItsTitle() {
        AnswerRenderer.Rendered r = AnswerRenderer.renderDraftOutcome(outcome(Result.CREATED, card(), "Book venue",
                "https://trello.com/c/abc"));

        assertThat(r.text()).isEqualTo(":white_check_mark: *Created in Trello:* <https://trello.com/c/abc|Book venue>");
    }

    @Test
    void theOtherOutcomesSayPlainlyWhatHappened() {
        assertThat(AnswerRenderer.renderDraftOutcome(outcome(Result.CANCELLED, issue("T", "d"), null, null)).text())
                .isEqualTo(":no_entry_sign: Draft cancelled. Nothing was created in Linear.");
        assertThat(AnswerRenderer.renderDraftOutcome(outcome(Result.EXPIRED, card(), null, null)).text())
                .isEqualTo(":hourglass: This draft expired, so nothing was created in Trello. Ask me again if you still want it.");
        assertThat(AnswerRenderer.renderDraftOutcome(outcome(Result.FAILED, issue("T", "d"), null, null)).text())
                .isEqualTo("Sorry, I couldn't create that in Linear. Nothing was created. You can ask me to try again.");
        assertThat(AnswerRenderer.renderDraftOutcome(outcome(Result.ALREADY_HANDLED, card(), null, null)).text())
                .isEqualTo("This draft has already been dealt with.");
    }
}
