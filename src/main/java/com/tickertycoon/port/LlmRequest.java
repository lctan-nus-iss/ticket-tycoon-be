package com.tickertycoon.port;

import lombok.Builder;
import lombok.Value;
import java.util.List;

@Value
@Builder
public class LlmRequest {

    String           agentName;
    String           systemPrompt;
    String           userPrompt;
    List<LlmMessage> history;
    Double           temperature;   // null = use agent config value
    Integer          maxTokens;     // null = use agent config value

    @Value
    @Builder
    public static class LlmMessage {
        String role;     // "user" | "assistant"
        String content;
    }
}
