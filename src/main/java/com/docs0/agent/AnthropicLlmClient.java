package com.docs0.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Usage;
import com.docs0.config.Docs0Properties;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Anthropic Messages API (non-beta, non-streaming). The session owns the SDK message list
 * and appends every assistant response back unchanged, thinking blocks included, which
 * the model requires when a tool loop continues.
 */
@Component
public class AnthropicLlmClient implements LlmClient {

    static final long MAX_TOKENS = 16000L;
    static final long ONE_SHOT_MAX_TOKENS = 1000L;

    private final Docs0Properties.Anthropic settings;
    private AnthropicClient client;

    public AnthropicLlmClient(Docs0Properties properties) {
        this.settings = properties.anthropic();
    }

    @Override
    public LlmSession start(String systemPrompt, List<ToolSpec> tools) {
        List<Tool> sdkTools = tools.stream().map(AnthropicLlmClient::toTool).toList();
        return new Session(client(), settings.model(), systemPrompt, sdkTools);
    }

    @Override
    public LlmTurn oneShot(String systemPrompt, String userText, ToolSpec tool) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(ONE_SHOT_MAX_TOKENS)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(systemPrompt)
                .addUserMessage(userText)
                .addTool(toTool(tool));
        Message response;
        try {
            response = client().messages().create(params.build());
        } catch (AnthropicServiceException e) {
            throw new LlmException(serviceFailure(e), e.apiMessage().orElse(e.getMessage()), e);
        } catch (AnthropicIoException e) {
            throw new LlmException(e.getClass().getSimpleName() + " (network)", e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new LlmException(e.getClass().getSimpleName(), e.getMessage(), e);
        }
        return toTurn(response);
    }

    private static String serviceFailure(AnthropicServiceException e) {
        return e.getClass().getSimpleName() + " ("
                + e.errorType().map(t -> t.asString() + ", ").orElse("")
                + "HTTP " + e.statusCode() + ")";
    }

    /** Built on first use so the app starts without a key. */
    private synchronized AnthropicClient client() {
        if (settings == null || !settings.configured()) {
            throw new LlmException("not_configured", "no Anthropic API key configured", null);
        }
        if (client == null) {
            client = AnthropicOkHttpClient.builder().apiKey(settings.apiKey()).build();
        }
        return client;
    }

    static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));
        Tool.InputSchema.Builder schema =
                Tool.InputSchema.builder().properties(properties.build()).required(spec.required());
        Tool.Builder tool = Tool.builder().name(spec.name()).description(spec.description());
        if (spec.strict()) {
            schema.putAdditionalProperty("additionalProperties", JsonValue.from(false));
            tool.strict(true);
        }
        return tool.inputSchema(schema.build()).build();
    }

    /**
     * The request for one turn. No sampling parameters, thinking budget or forced tool
     * choice: the model rejects them.
     */
    static MessageCreateParams buildParams(
            String model, String systemPrompt, List<Tool> tools, List<MessageParam> messages) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .messages(messages);
        tools.forEach(params::addTool);
        return params.build();
    }

    static LlmTurn toTurn(Message response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(use -> calls.add(new ToolCall(use.id(), use.name(), toMap(use._input()))));
        }
        String raw = response.stopReason()
                .map(com.anthropic.models.messages.StopReason::asString)
                .orElse("none");
        StopReason stop = switch (raw) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "end_turn" -> StopReason.END_TURN;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.OTHER;
        };
        String detail = response.stopDetails()
                .flatMap(details -> details.category())
                .map(category -> raw + ": " + category.asString())
                .orElse(raw);
        Usage usage = response.usage();
        long input = usage.inputTokens()
                + usage.cacheReadInputTokens().orElse(0L)
                + usage.cacheCreationInputTokens().orElse(0L);
        return new LlmTurn(stop, text.toString(), List.copyOf(calls), input, usage.outputTokens(), detail);
    }

    private static Map<String, Object> toMap(JsonValue input) {
        try {
            return input.convert(new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Session implements LlmSession {

        private final AnthropicClient client;
        private final String model;
        private final String systemPrompt;
        private final List<Tool> tools;
        private final List<MessageParam> messages = new ArrayList<>();

        Session(AnthropicClient client, String model, String systemPrompt, List<Tool> tools) {
            this.client = client;
            this.model = model;
            this.systemPrompt = systemPrompt;
            this.tools = tools;
        }

        @Override
        public LlmTurn send(String userText) {
            messages.add(MessageParam.ofUserOfContentBlockParams(List.of(text(userText))));
            return call();
        }

        @Override
        public LlmTurn sendToolResults(List<ToolResult> results, String note) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (ToolResult result : results) {
                blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(result.toolUseId())
                        .content(result.content())
                        .isError(result.isError())
                        .build()));
            }
            if (note != null) {
                blocks.add(text(note));
            }
            messages.add(MessageParam.ofUserOfContentBlockParams(blocks));
            return call();
        }

        private static ContentBlockParam text(String text) {
            return ContentBlockParam.ofText(TextBlockParam.builder().text(text).build());
        }

        private LlmTurn call() {
            Message response;
            try {
                response = client.messages().create(buildParams(model, systemPrompt, tools, messages));
            } catch (AnthropicServiceException e) {
                throw new LlmException(serviceFailure(e), e.apiMessage().orElse(e.getMessage()), e);
            } catch (AnthropicIoException e) {
                throw new LlmException(e.getClass().getSimpleName() + " (network)", e.getMessage(), e);
            } catch (AnthropicException e) {
                throw new LlmException(e.getClass().getSimpleName(), e.getMessage(), e);
            }
            // Full content goes back as-is: tool_use blocks and any thinking blocks.
            messages.add(response.toParam());
            return toTurn(response);
        }
    }
}
