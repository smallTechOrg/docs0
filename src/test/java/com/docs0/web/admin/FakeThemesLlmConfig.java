package com.docs0.web.admin;

import com.docs0.agent.LlmClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the model with one that answers the themes call from a list, and records what it was sent. */
@TestConfiguration
public class FakeThemesLlmConfig {

    public static final class ThemesLlm implements LlmClient {

        public final List<String> asked = new CopyOnWriteArrayList<>();
        public volatile List<Map<String, Object>> themes = List.of();
        public volatile boolean fails;

        public void reset() {
            asked.clear();
            themes = List.of(theme("Refunds", 4, 0), theme("VPN access", 2, -1));
            fails = false;
        }

        public static Map<String, Object> theme(String label, int count, int example) {
            return Map.of("label", label, "count_estimate", count, "example_index", example);
        }

        @Override
        public LlmTurn oneShot(String systemPrompt, String userText, ToolSpec tool) {
            asked.add(userText);
            if (fails) {
                throw new LlmException("overloaded_error", "busy", null);
            }
            return new LlmTurn(StopReason.TOOL_USE, "",
                    List.of(new ToolCall("t1", tool.name(), Map.of("themes", themes))), 10_000, 1_000, "tool_use");
        }

        @Override
        public LlmSession start(String systemPrompt, List<ToolSpec> tools) {
            throw new UnsupportedOperationException();
        }
    }

    @Bean
    @Primary
    ThemesLlm themesLlm() {
        ThemesLlm llm = new ThemesLlm();
        llm.reset();
        return llm;
    }
}
