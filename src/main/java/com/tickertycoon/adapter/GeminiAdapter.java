package com.tickertycoon.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickertycoon.config.LlmProperties;
import com.tickertycoon.port.LlmPort;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.*;

@Component("gemini")
@ConditionalOnProperty("llm.providers.gemini.api-key")
@RequiredArgsConstructor
public class GeminiAdapter implements LlmPort {

    private final LlmProperties     props;
    private final WebClient.Builder  webClientBuilder;

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start = System.currentTimeMillis();
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("gemini");

        // Gemini uses a different request format
        List<Map<String, Object>> contents = new ArrayList<>();
        if (req.getSystemPrompt() != null) {
            contents.add(Map.of("role", "user",
                "parts", List.of(Map.of("text", "[SYSTEM] " + req.getSystemPrompt()))));
            contents.add(Map.of("role", "model",
                "parts", List.of(Map.of("text", "Understood."))));
        }
        if (req.getHistory() != null) {
            req.getHistory().forEach(h -> contents.add(Map.of(
                "role",  h.getRole().equals("assistant") ? "model" : "user",
                "parts", List.of(Map.of("text", h.getContent()))
            )));
        }
        contents.add(Map.of("role", "user",
            "parts", List.of(Map.of("text", req.getUserPrompt()))));

        Map<String, Object> body = Map.of(
            "contents", contents,
            "generationConfig", Map.of(
                "temperature",    req.getTemperature() != null ? req.getTemperature() : agentCfg.getTemperature(),
                "maxOutputTokens", req.getMaxTokens() != null ? req.getMaxTokens() : agentCfg.getMaxTokens()
            )
        );

        String url = provCfg.getBaseUrl()
            + "/v1beta/models/" + agentCfg.getModel()
            + ":generateContent?key=" + provCfg.getApiKey();

        JsonNode raw = webClientBuilder.build()
            .post().uri(url)
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(agentCfg.getTimeoutSeconds()))
            .retryWhen(Retry.backoff(agentCfg.getRetries(), Duration.ofSeconds(2)))
            .block();

        String text = raw.path("candidates").get(0)
            .path("content").path("parts").get(0).path("text").asText();
        int inTokens  = raw.path("usageMetadata").path("promptTokenCount").asInt();
        int outTokens = raw.path("usageMetadata").path("candidatesTokenCount").asInt();

        return LlmResponse.builder()
            .text(text).model(agentCfg.getModel()).provider("gemini")
            .inputTokens(inTokens).outputTokens(outTokens)
            .latencyMs(System.currentTimeMillis() - start)
            .build();
    }

    @Override
    public Flux<String> stream(LlmRequest req) {
        // Gemini streaming uses SSE — simplified: fall back to complete() for now
        return Flux.just(complete(req).getText());
    }
}
