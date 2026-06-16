package com.tickertycoon.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@Log4j2
public class SpringAiConfig {

    @Bean
    @Qualifier("deepSeekChatModel")
    public OpenAiChatModel deepSeekChatModel(LlmProperties props) {
        var cfg = props.getProviders().get("deepseek");
        if (cfg == null) {
            throw new IllegalStateException("Missing llm.providers.deepseek configuration");
        }

        String apiKey = resolveApiKey(cfg);
        var api = OpenAiApi.builder()
            .baseUrl(cfg.getBaseUrl())
            .apiKey(apiKey)
            .build();
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(cfg.getDefaultModel())
                .build())
            .build();
    }

    private String resolveApiKey(LlmProperties.ProviderConfig cfg) {
        String apiKey = normalizeApiKey(cfg.getApiKey());
        if (isPresent(apiKey)) {
            return apiKey;
        }

        String apiKeyFile = cfg.getApiKeyFile();
        if (isPresent(apiKeyFile)) {
            Path path = Path.of(apiKeyFile);
            if (Files.exists(path)) {
                try {
                    apiKey = normalizeApiKey(Files.readString(path));
                    if (isPresent(apiKey)) {
                        log.info("Loaded DeepSeek API key from {}", path.toAbsolutePath());
                        return apiKey;
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read DeepSeek API key file: " + path, e);
                }
            }
        }

        throw new IllegalStateException(
            "DeepSeek API key is missing. Set DEEPSEEK_API_KEY=sk-... or create ds-key.");
    }

    private String normalizeApiKey(String value) {
        if (value == null) {
            return "";
        }

        String apiKey = value.trim();
        if (apiKey.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            apiKey = apiKey.substring("Bearer ".length()).trim();
        }
        return apiKey;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
