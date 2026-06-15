package com.tickertycoon.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DebriefResult {
    String html;
    String reasoningContent;  // DeepSeek R1 chain-of-thought, may be null
    String provider;
    String model;
}
