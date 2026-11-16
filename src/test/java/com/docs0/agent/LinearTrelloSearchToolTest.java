package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.LlmClient.ToolCall;
import com.docs0.agent.LlmClient.ToolSpec;
import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** search_knowledge with the optional source_kinds filter, and the system prompt that tells the model about Linear and Trello. */
class LinearTrelloSearchToolTest {

    private final FakeKnowledge knowledge = new FakeKnowledge()
            .add(1, "Parental leave policy", "Priya", "2026-08-01T00:00:00Z", "Leave is 26 weeks.");
    private final ScriptedLlm llm = new ScriptedLlm();
    private final List<String> progress = new ArrayList<>();

    LinearTrelloSearchToolTest() {
        knowledge.documents.add(new Document(2, FakeKnowledge.TENANT, 2L, "LINEAR", "issue:i1", "ENG-12: Leave tracker",
                "application/vnd.linear.issue", "https://linear.app/acme/issue/ENG-12", "Priya", null,
                Instant.parse("2026-10-01T00:00:00Z"), "Status: In Progress\nLeave tracker work."));
        knowledge.documents.add(new Document(3, FakeKnowledge.TENANT, 3L, "TRELLO", "card:c1", "Leave announcement",
                "application/vnd.trello.card", "https://trello.com/c/c1", "Marco", null,
                Instant.parse("2026-10-02T00:00:00Z"), "Board: Roadmap\nLeave announcement."));
    }

    private AgentService service() {
        Docs0Properties properties = new Docs0Properties(null, new Docs0Properties.Anthropic("test-key",
                "claude-sonnet-5-5", new BigDecimal("2.00"), new BigDecimal("10.00"), 12),
                null, null, null, null, null, null, null);
        return new AgentService(llm, knowledge, properties, Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC));
    }

    private ToolCall submit() {
        return llm.call("submit_answer", "kind", "unknown", "answer", "Nothing.", "confidence", "low",
                "citations", List.of(), "follow_ups", List.of(), "suggested_people", List.of());
    }

    private void ask() {
        service().answer(new AgentRequest(FakeKnowledge.TENANT, Audience.sharedOnly(), "leave?", List.of()), progress::add);
    }

    @Test
    void theFilterLimitsHitsToTheChosenSourcesAndShowsEachSource() {
        ToolCall onlyLinear = llm.call("search_knowledge", "query", "leave", "source_kinds", List.of("LINEAR"));
        ToolCall both = llm.call("search_knowledge", "query", "leave", "source_kinds", List.of("linear", "TRELLO"));
        ToolCall all = llm.call("search_knowledge", "query", "leave");
        llm.calls(onlyLinear, both, all).calls(submit());

        ask();

        String linearOnly = llm.resultFor(onlyLinear).content();
        assertThat(linearOnly).contains("ENG-12: Leave tracker", "source: LINEAR").doesNotContain("Parental", "TRELLO");
        String linearAndTrello = llm.resultFor(both).content();
        assertThat(linearAndTrello).contains("ENG-12: Leave tracker", "Leave announcement", "source: TRELLO")
                .doesNotContain("Parental");
        assertThat(llm.resultFor(all).content()).contains("Parental", "ENG-12", "Leave announcement");
        assertThat(progress).contains("Searching Linear for “leave”…", "Searching Linear and Trello for “leave”…",
                "Searching for “leave”…");
    }

    @Test
    void anUnknownSourceKindIsAnErrorTheModelCanFix() {
        ToolCall bad = llm.call("search_knowledge", "query", "leave", "source_kinds", List.of("GRAIN"));
        ToolCall notAList = llm.call("search_knowledge", "query", "leave", "source_kinds", "LINEAR");
        llm.calls(bad, notAList).calls(submit());

        ask();

        assertThat(llm.resultFor(bad).isError()).isTrue();
        assertThat(llm.resultFor(bad).content()).contains("GOOGLE_DRIVE, LINEAR, TRELLO, SLACK");
        assertThat(llm.resultFor(notAList).isError()).isTrue();
    }

    @Test
    void theSearchToolDeclaresTheFilterAsAnOptionalEnumArray() {
        ToolSpec search = KnowledgeTools.SPECS.stream().filter(s -> s.name().equals("search_knowledge")).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> kinds = (Map<String, Object>) search.properties().get("source_kinds");
        assertThat(kinds.get("type")).isEqualTo("array");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) kinds.get("items");
        assertThat(items.get("enum")).isEqualTo(List.of("GOOGLE_DRIVE", "LINEAR", "TRELLO", "SLACK"));
        assertThat(search.required()).containsExactly("query");
        assertThat(search.description()).contains("Linear", "Trello");
    }

    @Test
    void theSystemPromptIsConstantAndCoversTrackersAndTheConfirmGate() {
        String prompt = SystemPrompt.TEXT;

        assertThat(prompt).contains("Linear issues, projects and documents and Trello cards and boards",
                "Prefer Linear and Trello for questions about status, ownership, deadlines",
                "documents for policy and decisions", "source_kinds", "prefer the most recently modified",
                "ONLY when the person explicitly asks", "never on your own initiative",
                "So never say the item was created", "click Create in the thread");
        // Static: nothing per request, so it can be cached.
        assertThat(prompt).doesNotContainPattern("20\\d\\d-\\d\\d-\\d\\d");
        llm.calls(submit());

        ask();

        assertThat(llm.systemPrompt).isSameAs(SystemPrompt.TEXT);
    }
}
