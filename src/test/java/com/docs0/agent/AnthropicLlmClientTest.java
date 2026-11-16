package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.docs0.agent.LlmClient.LlmException;
import com.docs0.agent.LlmClient.LlmTurn;
import com.docs0.agent.LlmClient.StopReason;
import com.docs0.config.Docs0Properties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Request building and response mapping only. Nothing here opens a connection. */
class AnthropicLlmClientTest {

    private static final String RESPONSE = """
            {
              "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-5-5",
              "content": [
                {"type": "thinking", "thinking": "", "signature": "sig-abc"},
                {"type": "text", "text": "Let me look."},
                {"type": "tool_use", "id": "toolu_1", "name": "search_knowledge",
                 "input": {"query": "pricing 2026", "limit": 5}},
                {"type": "tool_use", "id": "toolu_2", "name": "read_document", "input": {"document_id": 12}}
              ],
              "stop_reason": "tool_use", "stop_sequence": null,
              "usage": {"input_tokens": 40, "cache_read_input_tokens": 1000,
                        "cache_creation_input_tokens": 200, "output_tokens": 77}
            }
            """;

    private static JsonNode requestJson() throws Exception {
        List<Tool> tools = KnowledgeTools.SPECS.stream().map(AnthropicLlmClient::toTool).toList();
        MessageCreateParams params = AnthropicLlmClient.buildParams(
                "claude-sonnet-5-5", SystemPrompt.TEXT, tools, List.of(MessageParam.ofUserOfContentBlockParams(List.of())));
        return ObjectMappers.jsonMapper().valueToTree(params._body());
    }

    @Test
    void theRequestUsesAdaptiveThinkingMediumEffortAndACachedSystemPrompt() throws Exception {
        JsonNode body = requestJson();

        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(16000);
        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("thinking").has("budget_tokens")).isFalse();
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(body.get("system")).hasSize(1);
        assertThat(body.get("system").get(0).get("text").asText()).isEqualTo(SystemPrompt.TEXT);
        assertThat(body.get("system").get(0).get("cache_control").get("type").asText()).isEqualTo("ephemeral");
        for (String rejected : List.of("temperature", "top_p", "top_k", "tool_choice", "stream")) {
            assertThat(body.has(rejected)).as(rejected).isFalse();
        }
    }

    @Test
    void theToolsAreDeclaredWithSubmitAnswerStrict() throws Exception {
        JsonNode tools = requestJson().get("tools");

        assertThat(tools).hasSize(4);
        assertThat(tools.findValuesAsText("name"))
                .containsExactly("search_knowledge", "read_document", "list_recent_documents", "submit_answer");

        JsonNode search = tools.get(0);
        assertThat(search.has("strict")).isFalse();
        assertThat(search.get("input_schema").get("type").asText()).isEqualTo("object");
        assertThat(search.get("input_schema").get("properties").get("query").get("type").asText()).isEqualTo("string");
        assertThat(search.get("input_schema").get("required")).hasSize(1);

        JsonNode submit = tools.get(3);
        JsonNode schema = submit.get("input_schema");
        assertThat(submit.get("strict").asBoolean()).isTrue();
        assertThat(schema.get("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.get("required")).hasSize(6).hasSize(schema.get("properties").size());
        assertThat(schema.get("properties").get("kind").get("enum")).hasSize(3);
        JsonNode citation = schema.get("properties").get("citations").get("items");
        assertThat(citation.get("additionalProperties").asBoolean()).isFalse();
        assertThat(citation.get("required")).hasSize(2);
    }

    @Test
    void aResponseBecomesATurnWithToolCallsAndSummedUsage() throws Exception {
        Message response = ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class);

        LlmTurn turn = AnthropicLlmClient.toTurn(response);

        assertThat(turn.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(turn.text()).isEqualTo("Let me look.");
        assertThat(turn.toolCalls()).hasSize(2);
        assertThat(turn.toolCalls().get(0).id()).isEqualTo("toolu_1");
        assertThat(turn.toolCalls().get(0).name()).isEqualTo("search_knowledge");
        assertThat(turn.toolCalls().get(0).input()).containsEntry("query", "pricing 2026");
        assertThat(KnowledgeTools.asLong(turn.toolCalls().get(0).input().get("limit"))).isEqualTo(5L);
        assertThat(KnowledgeTools.asLong(turn.toolCalls().get(1).input().get("document_id"))).isEqualTo(12L);
        // 40 uncached + 1000 cache read + 200 cache write.
        assertThat(turn.inputTokens()).isEqualTo(1240);
        assertThat(turn.outputTokens()).isEqualTo(77);
    }

    @Test
    void theAssistantTurnIsReplayedWithItsThinkingAndToolUseBlocks() throws Exception {
        Message response = ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class);

        JsonNode replayed = ObjectMappers.jsonMapper().valueToTree(response.toParam());

        assertThat(replayed.get("role").asText()).isEqualTo("assistant");
        assertThat(replayed.get("content").findValuesAsText("type"))
                .containsExactly("thinking", "text", "tool_use", "tool_use");
        assertThat(replayed.get("content").get(0).get("signature").asText()).isEqualTo("sig-abc");
        assertThat(replayed.get("content").get(2).get("id").asText()).isEqualTo("toolu_1");
    }

    @Test
    void stopReasonsAreMapped() throws Exception {
        assertThat(turnWithStop("\"end_turn\"").stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(turnWithStop("\"max_tokens\"").stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(turnWithStop("\"refusal\"").stopReason()).isEqualTo(StopReason.REFUSAL);
        assertThat(turnWithStop("\"pause_turn\"").stopReason()).isEqualTo(StopReason.OTHER);
        assertThat(turnWithStop("\"pause_turn\"").stopDetail()).isEqualTo("pause_turn");
        assertThat(turnWithStop("null").stopReason()).isEqualTo(StopReason.OTHER);
    }

    private static LlmTurn turnWithStop(String stopReason) throws Exception {
        String json = RESPONSE.replace("\"stop_reason\": \"tool_use\"", "\"stop_reason\": " + stopReason);
        return AnthropicLlmClient.toTurn(ObjectMappers.jsonMapper().readValue(json, Message.class));
    }

    @Test
    void withoutAKeyTheClientIsCreatedButRefusesToStartASession() {
        Docs0Properties properties = new Docs0Properties(
                null,
                new Docs0Properties.Anthropic("", "claude-sonnet-5-5", BigDecimal.ONE, BigDecimal.TEN, 12),
                null, null, null, null, null, null, null);

        AnthropicLlmClient client = new AnthropicLlmClient(properties);

        assertThatThrownBy(() -> client.start(SystemPrompt.TEXT, KnowledgeTools.SPECS))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no Anthropic API key");
    }
}
