package com.tickertycoon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import java.util.*;

@Configuration
@ConfigurationProperties(prefix = "llm")
@RefreshScope
@Data
public class LlmProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();
    private Map<String, AgentConfig>    agents    = new HashMap<>();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String apiKeyFile;
        private String defaultModel;
        private int    timeoutSeconds = 30;
    }

    @Data
    public static class AgentConfig {
        private String       provider;
        private String       model;
        private double       temperature    = 0.7;
        private int          maxTokens      = 1000;
        private int          timeoutSeconds = 30;
        private int          retries        = 1;
        private List<String> fallbackChain  = new ArrayList<>();
    }
}
