package com.tickertycoon.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickertycoon.config.LlmProperties;
import com.tickertycoon.port.LlmPort;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.*;

@Component("anthropic")
@RequiredArgsConstructor
@Slf4j
public class AnthropicAdapter implements LlmPort {

    private final LlmProperties     props;
    private final WebClient.Builder  webClientBuilder;
    private final ObjectMapper       mapper = new ObjectMapper();

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start = System.currentTimeMillis();
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("anthropic");

        Map<String, Object> body = buildBody(req, agentCfg, false);

        var raw = webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/v1/messages")
            .header("x-api-key",         provCfg.getApiKey())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type",      "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(agentCfg.getTimeoutSeconds()))
            .retryWhen(Retry.backoff(agentCfg.getRetries(), Duration.ofSeconds(2)))
            .block();

        return LlmResponse.builder()
            .text(raw.path("content").get(0).path("text").asText())
            .model(raw.path("model").asText())
            .provider("anthropic")
            .inputTokens(raw.path("usage").path("input_tokens").asInt())
            .outputTokens(raw.path("usage").path("output_tokens").asInt())
            .latencyMs(System.currentTimeMillis() - start)
            .build();
    }

    @Override
    public Flux<String> stream(LlmRequest req) {
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("anthropic");
        Map<String, Object> body = buildBody(req, agentCfg, true);

        return webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/v1/messages")
            .header("x-api-key",         provCfg.getApiKey())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type",      "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> line.contains("content_block_delta"))
            .mapNotNull(line -> {
                try {
                    JsonNode node = mapper.readTree(line.replace("data: ", "").trim());
                    return node.path("delta").path("text").asText(null);
                } catch (Exception e) { return null; }
            })
            .filter(t -> t != null && !t.isEmpty());
    }

    private Map<String, Object> buildBody(LlmRequest req,
                                           LlmProperties.AgentConfig cfg,
                                           boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (req.getHistory() != null) {
            req.getHistory().forEach(h ->
                messages.add(Map.of("role", h.getRole(), "content", h.getContent())));
        }
        messages.add(Map.of("role", "user", "content", req.getUserPrompt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       cfg.getModel());
        body.put("max_tokens",  req.getMaxTokens()    != null ? req.getMaxTokens()    : cfg.getMaxTokens());
        body.put("temperature", req.getTemperature()  != null ? req.getTemperature()  : cfg.getTemperature());
        body.put("messages",    messages);
        if (req.getSystemPrompt() != null) body.put("system", req.getSystemPrompt());
        if (stream) body.put("stream", true);
        return body;
    }
}
