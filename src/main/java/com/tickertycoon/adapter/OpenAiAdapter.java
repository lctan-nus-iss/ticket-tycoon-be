package com.tickertycoon.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickertycoon.config.LlmProperties;
import com.tickertycoon.port.LlmPort;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.*;

@Component("openai")
@RequiredArgsConstructor
public class OpenAiAdapter implements LlmPort {

    private final LlmProperties     props;
    private final WebClient.Builder  webClientBuilder;
    private final ObjectMapper       mapper = new ObjectMapper();

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start = System.currentTimeMillis();
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("openai");

        JsonNode raw = webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/v1/chat/completions")
            .header("Authorization", "Bearer " + provCfg.getApiKey())
            .header("Content-Type",  "application/json")
            .bodyValue(buildBody(req, agentCfg, false))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(agentCfg.getTimeoutSeconds()))
            .retryWhen(Retry.backoff(agentCfg.getRetries(), Duration.ofSeconds(2)))
            .block();

        return LlmResponse.builder()
            .text(raw.path("choices").get(0).path("message").path("content").asText())
            .model(raw.path("model").asText())
            .provider("openai")
            .inputTokens(raw.path("usage").path("prompt_tokens").asInt())
            .outputTokens(raw.path("usage").path("completion_tokens").asInt())
            .latencyMs(System.currentTimeMillis() - start)
            .build();
    }

    @Override
    public Flux<String> stream(LlmRequest req) {
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("openai");

        return webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/v1/chat/completions")
            .header("Authorization", "Bearer " + provCfg.getApiKey())
            .bodyValue(buildBody(req, agentCfg, true))
            .retrieve()
            .bodyToFlux(String.class)
            .filter(l -> l.startsWith("data: ") && !l.contains("[DONE]"))
            .mapNotNull(l -> {
                try {
                    return mapper.readTree(l.substring(6))
                        .path("choices").get(0).path("delta").path("content").asText(null);
                } catch (Exception e) { return null; }
            })
            .filter(t -> t != null && !t.isBlank());
    }

    private Map<String, Object> buildBody(LlmRequest req,
                                           LlmProperties.AgentConfig cfg,
                                           boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (req.getSystemPrompt() != null)
            messages.add(Map.of("role", "system", "content", req.getSystemPrompt()));
        if (req.getHistory() != null)
            req.getHistory().forEach(h -> messages.add(Map.of("role", h.getRole(), "content", h.getContent())));
        messages.add(Map.of("role", "user", "content", req.getUserPrompt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       cfg.getModel());
        body.put("messages",    messages);
        body.put("max_tokens",  req.getMaxTokens()    != null ? req.getMaxTokens()    : cfg.getMaxTokens());
        body.put("temperature", req.getTemperature()  != null ? req.getTemperature()  : cfg.getTemperature());
        if (stream) body.put("stream", true);
        return body;
    }
}
