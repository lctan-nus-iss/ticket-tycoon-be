package com.tickertycoon.router;

import com.tickertycoon.config.LlmProperties;
import com.tickertycoon.exception.LlmUnavailableException;
import com.tickertycoon.port.LlmPort;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Log4j2
public class LlmRouter implements LlmPort {

    /** All LlmPort beans keyed by their @Component name */
    private final Map<String, LlmPort> adapters;
    private final LlmProperties        props;
    private final MeterRegistry        metrics;

    @Override
    public LlmResponse complete(LlmRequest request) {
        var cfg   = resolveConfig(request.getAgentName());
        var chain = buildChain(cfg);

        for (String providerName : chain) {
            try {
                LlmResponse response = adapters.get(providerName).complete(request);
                recordMetrics(request.getAgentName(), providerName,
                              response.getModel(), response.getLatencyMs(),
                              response.getInputTokens(), response.getOutputTokens());
                log.info("[LlmRouter] OK agent={} provider={} model={} in={} out={} ms={}",
                    request.getAgentName(), providerName, response.getModel(),
                    response.getInputTokens(), response.getOutputTokens(), response.getLatencyMs());
                return response;
            } catch (Exception ex) {
                log.warn("[LlmRouter] FAIL agent={} provider={} — trying next. cause={}",
                    request.getAgentName(), providerName, ex.getMessage());
            }
        }
        throw new LlmUnavailableException(
            "All providers exhausted for agent: " + request.getAgentName());
    }

    @Override
    public Flux<String> stream(LlmRequest request) {
        var cfg          = resolveConfig(request.getAgentName());
        var providerName = cfg.getProvider();
        var adapter      = adapters.get(providerName);
        if (adapter == null) throw new LlmUnavailableException(
            "Adapter not found: " + providerName);
        return adapter.stream(request)
            .doOnError(e -> log.error("[LlmRouter] stream error agent={}: {}",
                request.getAgentName(), e.getMessage()));
    }

    private List<String> buildChain(LlmProperties.AgentConfig cfg) {
        List<String> chain = new ArrayList<>();
        chain.add(cfg.getProvider());
        if (cfg.getFallbackChain() != null) chain.addAll(cfg.getFallbackChain());
        return chain;
    }

    private LlmProperties.AgentConfig resolveConfig(String agentName) {
        var cfg = props.getAgents().get(agentName);
        if (cfg == null) throw new IllegalArgumentException(
            "No LLM config found for agent: " + agentName);
        return cfg;
    }

    private void recordMetrics(String agent, String provider,
                                String model, long latencyMs,
                                int inTokens, int outTokens) {
        metrics.counter("llm.calls.total",
            "agent", agent, "provider", provider, "model", model).increment();
        metrics.timer("llm.latency",
            "agent", agent, "provider", provider)
            .record(latencyMs, TimeUnit.MILLISECONDS);
        metrics.counter("llm.tokens.input",
            "agent", agent, "provider", provider).increment(inTokens);
        metrics.counter("llm.tokens.output",
            "agent", agent, "provider", provider).increment(outTokens);
    }
}
