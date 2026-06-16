package com.tickertycoon.adapter;

import com.tickertycoon.config.LlmProperties;
import com.tickertycoon.port.LlmPort;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component("openai")
@ConditionalOnProperty("llm.providers.openai.api-key")
@RequiredArgsConstructor
public class OpenAiAdapter implements LlmPort {

    private final LlmProperties   props;
    private final OpenAiChatModel chatModel;

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start   = System.currentTimeMillis();
        var agentCfg = props.getAgents().get(req.getAgentName());

        var response = chatModel.call(buildPrompt(req, agentCfg));
        var meta     = response.getMetadata();
        var usage    = meta.getUsage();

        return LlmResponse.builder()
            .text(response.getResult().getOutput().getText())
            .model(meta.getModel())
            .provider("openai")
            .inputTokens(usage.getPromptTokens().intValue())
            .outputTokens(usage.getCompletionTokens().intValue())
            .latencyMs(System.currentTimeMillis() - start)
            .build();
    }

    @Override
    public Flux<String> stream(LlmRequest req) {
        var agentCfg = props.getAgents().get(req.getAgentName());
        return chatModel.stream(buildPrompt(req, agentCfg))
            .mapNotNull(cr -> cr.getResult() != null
                ? cr.getResult().getOutput().getText() : null)
            .filter(t -> t != null && !t.isBlank());
    }

    private Prompt buildPrompt(LlmRequest req, LlmProperties.AgentConfig cfg) {
        List<Message> messages = new ArrayList<>();
        if (req.getSystemPrompt() != null)
            messages.add(new SystemMessage(req.getSystemPrompt()));
        if (req.getHistory() != null) {
            req.getHistory().forEach(h -> {
                if ("assistant".equals(h.getRole()))
                    messages.add(new AssistantMessage(h.getContent()));
                else
                    messages.add(new UserMessage(h.getContent()));
            });
        }
        messages.add(new UserMessage(req.getUserPrompt()));

        var options = OpenAiChatOptions.builder()
            .model(cfg.getModel())
            .temperature(req.getTemperature() != null ? req.getTemperature() : cfg.getTemperature())
            .maxCompletionTokens(req.getMaxTokens() != null ? req.getMaxTokens() : cfg.getMaxTokens())
            .build();
        return new Prompt(messages, options);
    }
}
