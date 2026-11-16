package com.docs0.agent;

import java.util.List;
import java.util.Map;

/**
 * The network edge to the language model. The real implementation talks to Anthropic;
 * tests substitute a scripted fake. Nothing outside the implementation sees SDK types.
 */
public interface LlmClient {

    /** Starts one conversation with a fixed system prompt and tool set. Makes no network call. */
    LlmSession start(String systemPrompt, List<ToolSpec> tools);

    /** One conversation. The session keeps the message history; each call is one model turn. */
    interface LlmSession {

        /** Sends a plain user message and returns the model's turn. */
        LlmTurn send(String userText);

        /**
         * Sends the results of every tool call from the previous turn in one user message.
         * {@code note}, when not null, is appended as text after the results.
         */
        LlmTurn sendToolResults(List<ToolResult> results, String note);
    }

    /**
     * A tool the model may call. {@code properties} is the JSON Schema "properties" object
     * as plain maps. A strict tool must list every property in {@code required}.
     */
    record ToolSpec(
            String name, String description, Map<String, Object> properties, List<String> required, boolean strict) {}

    /** A tool call from the model. {@code input} is the parsed JSON object, or null if it was not an object. */
    record ToolCall(String id, String name, Map<String, Object> input) {}

    record ToolResult(String toolUseId, String content, boolean isError) {}

    enum StopReason { TOOL_USE, END_TURN, MAX_TOKENS, REFUSAL, OTHER }

    /**
     * One model turn. Token counts are for this turn only; input tokens include cache reads
     * and cache writes. {@code stopDetail} is the raw stop reason or refusal category, for logs.
     */
    record LlmTurn(
            StopReason stopReason,
            String text,
            List<ToolCall> toolCalls,
            long inputTokens,
            long outputTokens,
            String stopDetail) {}

    /** A failed model call. {@code type} names the failure, e.g. "RateLimitException (rate_limit_error, HTTP 429)". */
    class LlmException extends RuntimeException {

        private final String type;

        public LlmException(String type, String message, Throwable cause) {
            super(message, cause);
            this.type = type;
        }

        public String type() {
            return type;
        }
    }
}
