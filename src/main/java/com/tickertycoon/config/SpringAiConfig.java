package com.tickertycoon.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean
    @Qualifier("deepSeekChatModel")
    public OpenAiChatModel deepSeekChatModel(LlmProperties props) {
        var cfg = props.getProviders().get("deepseek");
        var api = OpenAiApi.builder()
            .baseUrl(cfg.getBaseUrl())
            .apiKey(cfg.getApiKey())
            .build();
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(cfg.getDefaultModel())
                .build())
            .build();
    }
}
