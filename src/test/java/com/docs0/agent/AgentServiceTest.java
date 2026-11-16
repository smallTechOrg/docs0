package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Exchange;
import com.docs0.agent.Agent.Kind;
import com.docs0.agent.Agent.ProgressListener;
import com.docs0.agent.LlmClient.LlmException;
import com.docs0.agent.LlmClient.LlmTurn;
import com.docs0.agent.LlmClient.StopReason;
import com.docs0.agent.LlmClient.ToolCall;
import com.docs0.agent.LlmClient.ToolResult;
import com.docs0.agent.LlmClient.ToolSpec;
import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.Audience;
import com.docs0.web.StatusContributor.State;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentServiceTest {

    private static final String PRICE_QUOTE = "The Team plan costs $12 per seat per month";
    private static final String REFUND_QUOTE = "Refunds are available within 30 days of purchase";

    private final FakeKnowledge knowledge = new FakeKnowledge()
            .add(1, "Pricing 2026", "Priya", "2026-10-03T00:00:00Z",
                    "The Team plan costs $12 per seat per month from January 2026. Annual billing gives a 15% discount.")
            .add(2, "Refund policy", "Marco", "2026-08-01T00:00:00Z",
                    "Refunds are available within 30 days of purchase, no questions asked.");
    private final ScriptedLlm llm = new ScriptedLlm();
    private final List<String> progress = new ArrayList<>();

    private AgentService service(String apiKey, int maxSteps) {
        Docs0Properties properties = new Docs0Properties(
                null,
                new Docs0Properties.Anthropic(
                        apiKey, "claude-sonnet-5-5", new BigDecimal("2.00"), new BigDecimal("10.00"), maxSteps),
                null, null, null, null, null, null, null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC);
        return new AgentService(llm, knowledge, properties, clock);
    }

    private AgentResult ask(String question) {
        return ask(service("test-key", 12), question, List.of());
    }

    private AgentResult ask(AgentService service, String question, List<Exchange> history) {
        return service.answer(
                new AgentRequest(FakeKnowledge.TENANT, Audience.sharedOnly(), question, history), progress::add);
    }

    private ToolCall submit(String kind, String answer, String confidence, Map<?, ?>... citations) {
        return llm.call("submit_answer",
                "kind", kind, "answer", answer, "confidence", confidence, "citations", List.of(citations),
                "follow_ups", List.of("What about annual billing?"), "suggested_people", List.of());
    }

    private static Map<String, Object> cite(long documentId, String quote) {
        return ScriptedLlm.map("document_id", documentId, "quote", quote);
    }

    private static LlmTurn stop(StopReason reason, String text, String detail) {
        return new LlmTurn(reason, text, List.of(), 100, 10, detail);
    }

    @Test
    void searchesReadsAndSubmitsAVerifiedAnswer() {
        ToolCall search = llm.call("search_knowledge", "query", "pricing 2026");
        ToolCall read = llm.call("read_document", "document_id", 1);
        llm.calls(search).calls(read).calls(submit("answer", "Team is *$12 per seat* [1].", "high", cite(1, PRICE_QUOTE)));

        AgentResult result = ask("How much is the Team plan?");

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(result.answer()).isEqualTo("Team is *$12 per seat* [1].");
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.citations())
                .containsExactly(new Citation(1, 1L, "Pricing 2026", "https://docs.example/1", PRICE_QUOTE));
        assertThat(result.followUps()).containsExactly("What about annual billing?");
        assertThat(result.error()).isNull();
        assertThat(result.steps()).isEqualTo(3);
        assertThat(result.inputTokens()).isEqualTo(300);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.costUsd()).isEqualByComparingTo("0.000900");
        assertThat(result.costUsd().scale()).isEqualTo(6);

        assertThat(progress).containsExactly(
                "Searching for “pricing 2026”…", "Reading *Pricing 2026*…", "Writing the answer…");

        assertThat(llm.sessions).isEqualTo(1);
        assertThat(llm.systemPrompt).isEqualTo(SystemPrompt.TEXT);
        assertThat(llm.tools).extracting(ToolSpec::name)
                .containsExactly("search_knowledge", "read_document", "list_recent_documents", "submit_answer");

        ToolResult searchResult = llm.resultFor(search);
        assertThat(searchResult.isError()).isFalse();
        assertThat(searchResult.content()).contains(
                "document_id: 1", "title: Pricing 2026", "source: GOOGLE_DRIVE", "modified: 2026-10-03",
                "owner: Priya", "url: https://docs.example/1", "snippet: The Team plan");
        assertThat(searchResult.content()).doesNotContain("broadened");

        ToolResult readResult = llm.resultFor(read);
        assertThat(readResult.isError()).isFalse();
        assertThat(readResult.content()).contains(
                "title: Pricing 2026", "owner: Priya <priya@example.com>", "has_more: false", PRICE_QUOTE);
        assertThat(readResult.content()).doesNotContain("next_offset");
    }

    @Test
    void dropsAHallucinatedQuoteAndRenumbersMarkers() {
        llm.calls(submit("answer", "Team is $10 [1]. Refunds within 30 days [2].", "high",
                cite(1, "The Team plan costs $10 per seat per month"), cite(2, REFUND_QUOTE)));

        AgentResult result = ask("pricing and refunds?");

        assertThat(result.answer()).isEqualTo("Team is $10. Refunds within 30 days [1].");
        assertThat(result.citations()).containsExactly(
                new Citation(1, 2L, "Refund policy", "https://docs.example/2", REFUND_QUOTE));
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void anAnswerWithOnlyInvalidCitationsBecomesLowConfidenceWithANote() {
        llm.calls(submit("answer", "Team is $10 [1].", "high",
                cite(1, "The Team plan costs $10 per seat per month")));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).isEqualTo("Team is $10.\n\n" + AnswerVerifier.UNVERIFIED_NOTE);
    }

    @Test
    void returnsAClarifyingQuestion() {
        llm.calls(submit("clarify", "Do you mean the Team plan or the Enterprise plan?", "medium"));

        AgentResult result = ask("How much is it?");

        assertThat(result.kind()).isEqualTo(Kind.CLARIFY);
        assertThat(result.answer()).isEqualTo("Do you mean the Team plan or the Enterprise plan?");
        assertThat(result.citations()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    void returnsUnknownWithSuggestedPeople() {
        llm.calls(llm.call("submit_answer",
                "kind", "unknown", "answer", "I searched for the parking policy and found nothing.",
                "confidence", "low", "citations", List.of(cite(1, PRICE_QUOTE)), "follow_ups", List.of(),
                "suggested_people", List.of("Priya", "Marco", "Ana", "Lee")));

        AgentResult result = ask("Where can I park?");

        assertThat(result.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(result.citations()).isEmpty();
        assertThat(result.suggestedPeople()).containsExactly("Priya", "Marco", "Ana");
        assertThat(result.followUps()).isEmpty();
    }

    @Test
    void plainTextAtEndOfTurnIsAnUnverifiedLowConfidenceAnswer() {
        llm.turn(stop(StopReason.END_TURN, "The Team plan is $12 per seat.", "end_turn"));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).startsWith("The Team plan is $12 per seat.").endsWith(AnswerVerifier.UNVERIFIED_NOTE);
        assertThat(result.error()).isNull();
    }

    @Test
    void anEmptyEndOfTurnIsAnError() {
        llm.turn(stop(StopReason.END_TURN, "  ", "end_turn"));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).startsWith("empty_response");
    }

    @Test
    void asksForSubmitOnTheLastTurnAndGivesUpWhenTheStepsRunOut() {
        llm.calls(llm.call("search_knowledge", "query", "pricing"))
                .calls(llm.call("search_knowledge", "query", "refund"))
                .calls(llm.call("search_knowledge", "query", "discount"));

        AgentResult result = ask(service("test-key", 3), "pricing?", List.of());

        assertThat(result.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(result.answer()).isEqualTo(AgentService.OUT_OF_STEPS);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.steps()).isEqualTo(3);
        assertThat(result.inputTokens()).isEqualTo(300);
        // The third turn's search is not run: nothing could use its result.
        assertThat(knowledge.queries).containsExactly("pricing", "refund");
        assertThat(llm.batches).hasSize(2);
        assertThat(llm.batches.get(0).note()).isNull();
        assertThat(llm.batches.get(1).note()).isEqualTo(AgentService.FINAL_TURN_NOTE);
        assertThat(llm.firstMessage).doesNotContain(AgentService.FINAL_TURN_NOTE);
    }

    @Test
    void aSubmitOnTheLastAllowedTurnStillCounts() {
        llm.calls(llm.call("search_knowledge", "query", "refund"))
                .calls(submit("answer", "Within 30 days [1].", "high", cite(2, REFUND_QUOTE)));

        AgentResult result = ask(service("test-key", 2), "refunds?", List.of());

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(result.citations()).hasSize(1);
        assertThat(llm.batches.get(0).note()).isEqualTo(AgentService.FINAL_TURN_NOTE);
    }

    @Test
    void withASingleStepTheFirstMessageAlreadyAsksForSubmit() {
        llm.calls(submit("unknown", "I could not look anything up.", "low"));

        AgentResult result = ask(service("test-key", 1), "refunds?", List.of());

        assertThat(result.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(llm.firstMessage).endsWith(AgentService.FINAL_TURN_NOTE);
    }

    @Test
    void unknownToolsAndMalformedInputsBecomeErrorResultsAndTheLoopContinues() {
        ToolCall unknownTool = llm.call("delete_everything", "sure", true);
        ToolCall noQuery = llm.call("search_knowledge", "limit", 3);
        ToolCall badId = llm.call("read_document", "document_id", "abc");
        ToolCall badOffset = llm.call("read_document", "document_id", 1, "offset", -5);
        ToolCall notAnObject = new ToolCall("toolu_null", "search_knowledge", null);
        ToolCall badSubmit = llm.call("submit_answer", "kind", "maybe", "answer", "x", "confidence", "high");
        ToolCall noConfidence = llm.call("submit_answer", "kind", "answer", "answer", "x");
        llm.calls(unknownTool, noQuery, badId, badOffset, notAnObject)
                .calls(badSubmit)
                .calls(noConfidence)
                .calls(submit("answer", "Within 30 days [1].", "medium", cite(2, REFUND_QUOTE)));

        AgentResult result = ask("refunds?");

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(result.steps()).isEqualTo(4);
        assertThat(result.error()).isNull();
        for (ToolCall call : List.of(unknownTool, noQuery, badId, badOffset, notAnObject, badSubmit, noConfidence)) {
            assertThat(llm.resultFor(call).isError()).as(call.id()).isTrue();
        }
        assertThat(llm.resultFor(unknownTool).content()).contains("no tool named \"delete_everything\"");
        assertThat(llm.resultFor(noQuery).content()).contains("query");
        assertThat(llm.resultFor(badId).content()).contains("document_id");
        assertThat(llm.resultFor(notAnObject).content()).contains("not a JSON object");
        assertThat(llm.resultFor(badSubmit).content()).contains("kind");
        assertThat(llm.resultFor(noConfidence).content()).contains("confidence");
        assertThat(knowledge.queries).isEmpty();
    }

    @Test
    void aFailingToolBecomesAnErrorResultNotAnException() {
        knowledge.failSearch = true;
        ToolCall search = llm.call("search_knowledge", "query", "refund");
        llm.calls(search).calls(submit("unknown", "Search is not working right now.", "low"));

        AgentResult result = ask("refunds?");

        assertThat(result.kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(llm.resultFor(search).isError()).isTrue();
        assertThat(llm.resultFor(search).content()).contains("IllegalStateException").doesNotContain("database is down");
    }

    @Test
    void parallelToolCallsAreAnsweredInOneBatch() {
        ToolCall first = llm.call("search_knowledge", "query", "pricing", "limit", 50);
        ToolCall second = llm.call("search_knowledge", "query", "refund", "limit", 2.0);
        ToolCall read = llm.call("read_document", "document_id", "2");
        llm.calls(first, second, read).calls(submit("answer", "Within 30 days [1].", "high", cite(2, REFUND_QUOTE)));

        AgentResult result = ask("pricing and refunds?");

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(llm.batches).hasSize(1);
        assertThat(llm.batches.get(0).results()).extracting(ToolResult::toolUseId)
                .containsExactly(first.id(), second.id(), read.id());
        assertThat(llm.batches.get(0).results()).noneMatch(ToolResult::isError);
        assertThat(knowledge.limits).containsExactly(20, 2);
        assertThat(progress).containsExactly(
                "Searching for “pricing”…", "Searching for “refund”…",
                "Reading *Refund policy*…", "Writing the answer…");
    }

    @Test
    void aRefusalIsAnError() {
        llm.turn(stop(StopReason.REFUSAL, "", "refusal: cyber"));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).startsWith("refusal").contains("cyber");
        assertThat(result.answer()).isEqualTo(AgentService.COULD_NOT_COMPLETE);
        assertThat(result.citations()).isEmpty();
        assertThat(result.steps()).isEqualTo(1);
    }

    @Test
    void runningOutOfOutputTokensIsAnErrorAndItsToolCallsAreNotRun() {
        llm.turn(new LlmTurn(
                StopReason.MAX_TOKENS, "", List.of(llm.call("search_knowledge", "query", "pricing")), 100, 16000,
                "max_tokens"));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).startsWith("max_tokens");
        assertThat(result.outputTokens()).isEqualTo(16000);
        assertThat(knowledge.queries).isEmpty();
    }

    @Test
    void anUnexpectedStopReasonIsAnError() {
        llm.turn(stop(StopReason.OTHER, "", "pause_turn"));

        assertThat(ask("pricing?").error()).isEqualTo("unexpected_stop: pause_turn");
    }

    @Test
    void anLlmFailureIsAnErrorResultThatKeepsTheUsageSoFar() {
        llm.calls(llm.call("search_knowledge", "query", "pricing"))
                .fails(new LlmException("RateLimitException (rate_limit_error, HTTP 429)", "slow down", null));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).isEqualTo("RateLimitException (rate_limit_error, HTTP 429): slow down");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.costUsd()).isEqualByComparingTo("0.000300");
    }

    @Test
    void anyOtherExceptionIsAnErrorResultNamingItsClass() {
        llm.fails(new IllegalStateException("boom"));

        AgentResult result = ask("pricing?");

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).isEqualTo("java.lang.IllegalStateException");
        assertThat(result.steps()).isZero();
        assertThat(result.costUsd()).isEqualByComparingTo("0");
    }

    @Test
    void sumsTokensAndCostAcrossTurns() {
        llm.turn(new LlmTurn(StopReason.TOOL_USE, "", List.of(llm.call("list_recent_documents")), 1_200_000, 50_000, "tool_use"))
                .turn(new LlmTurn(StopReason.TOOL_USE, "", List.of(llm.call("search_knowledge", "query", "refund")), 300_001, 1, "tool_use"))
                .turn(new LlmTurn(StopReason.END_TURN, "Done.", List.of(), 499_999, 49_999, "end_turn"));

        AgentResult result = ask("what's new?");

        assertThat(result.steps()).isEqualTo(3);
        assertThat(result.inputTokens()).isEqualTo(2_000_000);
        assertThat(result.outputTokens()).isEqualTo(100_000);
        // 2M input at $2 per million plus 100k output at $10 per million.
        assertThat(result.costUsd()).isEqualTo(new BigDecimal("5.000000"));
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void theFirstMessageHasTheDateTheLastTenExchangesOldestFirstAndTheQuestion() {
        List<Exchange> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add(new Exchange(String.format("question-%02d", i), String.format("answer-%02d", i)));
        }
        llm.calls(submit("unknown", "Not found.", "low"));

        ask(service("test-key", 12), "And for annual billing?", history);

        String message = llm.firstMessage;
        assertThat(message).contains("2026-11-17");
        assertThat(message).doesNotContain("question-01", "answer-01", "question-02", "answer-02");
        int previous = -1;
        for (int i = 3; i <= 12; i++) {
            int question = message.indexOf(String.format("question-%02d", i));
            int answer = message.indexOf(String.format("answer-%02d", i));
            assertThat(question).as("question %d", i).isGreaterThan(previous);
            assertThat(answer).as("answer %d", i).isGreaterThan(question);
            previous = answer;
        }
        assertThat(message.indexOf("And for annual billing?")).isGreaterThan(previous);
    }

    @Test
    void withoutHistoryTheFirstMessageIsJustDateAndQuestion() {
        llm.calls(submit("unknown", "Not found.", "low"));

        service("test-key", 12).answer(
                new AgentRequest(FakeKnowledge.TENANT, Audience.sharedOnly(), "Where can I park?", null), null);

        assertThat(llm.firstMessage).contains("2026-11-17", "Where can I park?").doesNotContain("Earlier in this");
    }

    @Test
    void theSystemPromptIsStatic() {
        assertThat(SystemPrompt.TEXT).doesNotContain("2026").doesNotContain("Today");
        ToolSpec submit = KnowledgeTools.SPECS.get(3);
        assertThat(submit.strict()).isTrue();
        assertThat(submit.required()).containsExactlyInAnyOrderElementsOf(submit.properties().keySet());
    }

    @Test
    void aThrowingProgressListenerDoesNotBreakTheLoop() {
        llm.calls(llm.call("list_recent_documents", "limit", 1))
                .calls(submit("answer", "Pricing changed most recently [1].", "medium", cite(1, PRICE_QUOTE)));
        ProgressListener broken = text -> {
            progress.add(text);
            throw new IllegalStateException("Slack is down");
        };

        AgentResult result = service("test-key", 12).answer(
                new AgentRequest(FakeKnowledge.TENANT, Audience.sharedOnly(), "what's new?", List.of()), broken);

        assertThat(result.kind()).isEqualTo(Kind.ANSWER);
        assertThat(progress).containsExactly("Checking recent documents…", "Writing the answer…");
    }

    @Test
    void listsRecentDocumentsNewestFirst() {
        ToolCall recent = llm.call("list_recent_documents");
        llm.calls(recent).calls(submit("unknown", "Nothing relevant.", "low"));

        ask("what's new?");

        String content = llm.resultFor(recent).content();
        assertThat(content.indexOf("Pricing 2026")).isPositive().isLessThan(content.indexOf("Refund policy"));
    }

    @Test
    void readDocumentPagesThroughALongBody() {
        knowledge.add(9, "Handbook", "Ana", "2026-05-01T00:00:00Z",
                "A".repeat(60_000) + "B".repeat(60_000) + "C".repeat(10_000));
        ToolCall page1 = llm.call("read_document", "document_id", 9);
        ToolCall page2 = llm.call("read_document", "document_id", 9, "offset", 60_000);
        ToolCall page3 = llm.call("read_document", "document_id", 9, "offset", 120_000);
        ToolCall pastEnd = llm.call("read_document", "document_id", 9, "offset", 130_000);
        llm.calls(page1).calls(page2, page3, pastEnd).calls(submit("unknown", "Nothing useful.", "low"));

        ask("handbook?");

        String first = llm.resultFor(page1).content();
        assertThat(first).contains("total_chars: 130000", "offset: 0", "has_more: true", "next_offset: 60000");
        assertThat(body(first)).isEqualTo("A".repeat(60_000));

        String second = llm.resultFor(page2).content();
        assertThat(second).contains("offset: 60000", "has_more: true", "next_offset: 120000");
        assertThat(body(second)).isEqualTo("B".repeat(60_000));

        String third = llm.resultFor(page3).content();
        assertThat(third).contains("offset: 120000", "has_more: false").doesNotContain("next_offset");
        assertThat(body(third)).isEqualTo("C".repeat(10_000));

        assertThat(llm.resultFor(pastEnd).isError()).isTrue();
        assertThat(llm.resultFor(pastEnd).content()).contains("past the end", "130000");
    }

    private static String body(String readResult) {
        return readResult.substring(readResult.indexOf(" ---\n") + 5);
    }

    @Test
    void readingAMissingDocumentSaysItDoesNotExist() {
        ToolCall read = llm.call("read_document", "document_id", 404);
        llm.calls(read).calls(submit("unknown", "Not found.", "low"));

        ask("anything?");

        assertThat(llm.resultFor(read).isError()).isTrue();
        assertThat(llm.resultFor(read).content()).contains("No document with document_id 404 exists");
    }

    @Test
    void searchResultsSayWhenTheSearchWasBroadenedOrFoundNothing() {
        ToolCall broadened = llm.call("search_knowledge", "query", "refunds zebra");
        ToolCall nothing = llm.call("search_knowledge", "query", "zebra");
        llm.calls(broadened, nothing).calls(submit("unknown", "Not found.", "low"));

        ask("zebra?");

        assertThat(llm.resultFor(broadened).content()).contains("broadened", "Refund policy");
        assertThat(llm.resultFor(nothing).content()).contains("No documents matched \"zebra\"");
        assertThat(llm.resultFor(nothing).isError()).isFalse();
    }

    @Test
    void withoutAKeyTheAgentIsUnavailableAndOff() {
        for (String key : new String[] {null, "", "  "}) {
            AgentService service = service(key, 12);

            assertThat(service.available()).isFalse();
            assertThat(service.status(1L).name()).isEqualTo("Anthropic");
            assertThat(service.status(1L).state()).isEqualTo(State.OFF);
            assertThat(service.status(1L).detail()).isEqualTo("no API key configured");

            AgentResult result = ask(service, "pricing?", List.of());
            assertThat(result.kind()).isEqualTo(Kind.ERROR);
            assertThat(result.error()).startsWith("not_configured");
        }
        assertThat(llm.sessions).isZero();
    }

    @Test
    void withAKeyTheAgentIsAvailableAndUpWithTheModelName() {
        AgentService service = service("test-key", 12);

        assertThat(service.available()).isTrue();
        assertThat(service.status(1L).name()).isEqualTo("Anthropic");
        assertThat(service.status(1L).state()).isEqualTo(State.UP);
        assertThat(service.status(1L).detail()).isEqualTo("claude-sonnet-5-5");
        assertThat(llm.sessions).isZero();
    }
}
