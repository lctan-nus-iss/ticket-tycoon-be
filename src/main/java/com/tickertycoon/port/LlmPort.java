package com.tickertycoon.port;

import reactor.core.publisher.Flux;

public interface LlmPort {

    LlmResponse complete(LlmRequest request);

    Flux<String> stream(LlmRequest request);
}
