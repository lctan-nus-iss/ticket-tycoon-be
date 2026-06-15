package com.tickertycoon.port;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LlmResponse {

    String text;
    String model;
    String provider;
    int    inputTokens;
    int    outputTokens;
    long   latencyMs;

    /**
     * Chain-of-thought content from deepseek-reasoner (R1).
     * Null for all other models/providers.
     */
    String reasoningContent;
}
