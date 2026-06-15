package com.tickertycoon.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickertycoon.config.LlmProperties;
import com.tickertycoon.port.LlmPort;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;

@Component("ollama")
@RequiredArgsConstructor
public class OllamaAdapter implements LlmPort {

    private final LlmProperties     props;
    private final WebClient.Builder  webClientBuilder;

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start = System.currentTimeMillis();
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("ollama");

        List<Map<String, String>> messages = new ArrayList<>();
        if (req.getSystemPrompt() != null)
            messages.add(Map.of("role", "system", "content", req.getSystemPrompt()));
        if (req.getHistory() != null)
            req.getHistory().forEach(h -> messages.add(Map.of("role", h.getRole(), "content", h.getContent())));
        messages.add(Map.of("role", "user", "content", req.getUserPrompt()));

        JsonNode raw = webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/api/chat")
            .header("Content-Type", "application/json")
            .bodyValue(Map.of(
                "model",    agentCfg.getModel(),
                "stream",   false,
                "messages", messages,
                "options",  Map.of(
                    "temperature", req.getTemperature() != null ? req.getTemperature() : agentCfg.getTemperature(),
                    "num_predict", req.getMaxTokens()   != null ? req.getMaxTokens()   : agentCfg.getMaxTokens()
                )
            ))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(agentCfg.getTimeoutSeconds()))
            .block();

        return LlmResponse.builder()
            .text(raw.path("message").path("content").asText())
            .model(agentCfg.getModel())
            .provider("ollama")
            .inputTokens(raw.path("prompt_eval_count").asInt())
            .outputTokens(raw.path("eval_count").asInt())
            .latencyMs(System.currentTimeMillis() - start)
            .build();
    }

    @Override
    public Flux<String> stream(LlmRequest req) {
        // Simplified: wrap blocking call
        return Flux.just(complete(req).getText());
    }
}
