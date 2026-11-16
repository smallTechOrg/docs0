package com.docs0.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A fake model: plays back a fixed list of turns and records everything it was sent. */
final class ScriptedLlm implements LlmClient {

    /** One batch of tool results as sent back to the model. */
    record Batch(List<ToolResult> results, String note) {}

    private final Deque<Supplier<LlmTurn>> script = new ArrayDeque<>();
    private int callIds;

    int sessions;
    String systemPrompt;
    List<ToolSpec> tools;
    String firstMessage;
    final List<Batch> batches = new ArrayList<>();

    /** A tool_use turn with the given calls, costing 100 input and 10 output tokens. */
    ScriptedLlm calls(ToolCall... calls) {
        return turn(new LlmTurn(StopReason.TOOL_USE, "", List.of(calls), 100, 10, "tool_use"));
    }

    ScriptedLlm turn(LlmTurn turn) {
        script.add(() -> turn);
        return this;
    }

    ScriptedLlm fails(RuntimeException e) {
        script.add(() -> {
            throw e;
        });
        return this;
    }

    ToolCall call(String name, Object... keysAndValues) {
        return new ToolCall("toolu_" + (++callIds), name, map(keysAndValues));
    }

    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** The single result with this tool-use id, across all batches. */
    ToolResult resultFor(ToolCall call) {
        return batches.stream()
                .flatMap(batch -> batch.results().stream())
                .filter(result -> result.toolUseId().equals(call.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result was sent for " + call.id()));
    }

    @Override
    public LlmTurn oneShot(String systemPrompt, String userText, ToolSpec tool) {
        throw new AssertionError("the agent never makes one-shot calls");
    }

    @Override
    public LlmSession start(String systemPrompt, List<ToolSpec> tools) {
        sessions++;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        return new LlmSession() {
            @Override
            public LlmTurn send(String userText) {
                firstMessage = userText;
                return next();
            }

            @Override
            public LlmTurn sendToolResults(List<ToolResult> results, String note) {
                batches.add(new Batch(List.copyOf(results), note));
                return next();
            }
        };
    }

    private LlmTurn next() {
        if (script.isEmpty()) {
            throw new AssertionError("the model was called more often than the script allows");
        }
        return script.poll().get();
    }
}
