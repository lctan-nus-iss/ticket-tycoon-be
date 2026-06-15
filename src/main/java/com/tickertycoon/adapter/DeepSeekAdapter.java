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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.*;

@Component("deepseek")
@RequiredArgsConstructor
@Slf4j
public class DeepSeekAdapter implements LlmPort {

    private final LlmProperties     props;
    private final WebClient.Builder  webClientBuilder;
    private final ObjectMapper       mapper = new ObjectMapper();

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start = System.currentTimeMillis();
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("deepseek");
        boolean isReasoner = isReasonerModel(agentCfg.getModel());

        Map<String, Object> body = buildBody(req, agentCfg, false);

        JsonNode raw = webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/v1/chat/completions")
            .header("Authorization", "Bearer " + provCfg.getApiKey())
            .header("Content-Type",  "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(agentCfg.getTimeoutSeconds()))
            .retryWhen(Retry.backoff(agentCfg.getRetries(), Duration.ofSeconds(3))
                .filter(e -> e instanceof WebClientResponseException.ServiceUnavailable
                          || e instanceof WebClientResponseException.TooManyRequests))
            .block();

        JsonNode message = raw.path("choices").get(0).path("message");
        String content   = message.path("content").asText();

        // R1 reasoning chain-of-thought (deepseek-reasoner only)
        String reasoning = message.path("reasoning_content").asText(null);
        if (reasoning != null && !reasoning.isBlank()) {
            log.debug("[DeepSeek R1] agent={} reasoning preview: {}",
                req.getAgentName(),
                reasoning.substring(0, Math.min(300, reasoning.length())));
        }

        return LlmResponse.builder()
            .text(content)
            .model(raw.path("model").asText())
            .provider("deepseek")
            .inputTokens(raw.path("usage").path("prompt_tokens").asInt())
            .outputTokens(raw.path("usage").path("completion_tokens").asInt())
            .latencyMs(System.currentTimeMillis() - start)
            .reasoningContent(reasoning)
            .build();
    }

    @Override
    public Flux<String> stream(LlmRequest req) {
        var agentCfg = props.getAgents().get(req.getAgentName());
        var provCfg  = props.getProviders().get("deepseek");
        Map<String, Object> body = buildBody(req, agentCfg, true);

        return webClientBuilder.build()
            .post()
            .uri(provCfg.getBaseUrl() + "/v1/chat/completions")
            .header("Authorization", "Bearer " + provCfg.getApiKey())
            .header("Content-Type",  "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> line.startsWith("data: ") && !line.contains("[DONE]"))
            .mapNotNull(line -> {
                try {
                    JsonNode node = mapper.readTree(line.substring(6).trim());
                    String delta = node.path("choices").get(0)
                                       .path("delta").path("content").asText(null);
                    return (delta != null && !delta.isBlank()) ? delta : null;
                } catch (Exception e) { return null; }
            });
    }

    private Map<String, Object> buildBody(LlmRequest req,
                                           LlmProperties.AgentConfig cfg,
                                           boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", req.getSystemPrompt()));
        }
        if (req.getHistory() != null) {
            req.getHistory().forEach(h ->
                messages.add(Map.of("role", h.getRole(), "content", h.getContent())));
        }
        messages.add(Map.of("role", "user", "content", req.getUserPrompt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",    cfg.getModel());
        body.put("messages", messages);
        body.put("max_tokens", req.getMaxTokens() != null ? req.getMaxTokens() : cfg.getMaxTokens());

        // deepseek-reasoner ignores temperature — keep it at 1.0
        double temp = isReasonerModel(cfg.getModel()) ? 1.0
                    : (req.getTemperature() != null ? req.getTemperature() : cfg.getTemperature());
        body.put("temperature", temp);
        if (stream) body.put("stream", true);
        return body;
    }

    private boolean isReasonerModel(String model) {
        return model != null && model.contains("reasoner");
    }
}
