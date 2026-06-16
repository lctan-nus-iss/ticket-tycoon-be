package com.tickertycoon.port;

import reactor.core.publisher.Flux;

public interface LlmPort {

    LlmResponse complete(LlmRequest request);

    /**
     * Streams the model output incrementally for the given request.
     *
     * @param request the prompt and options used to generate the response
     * @return a {@link Flux} emitting response chunks in order until completion
     */
    Flux<String> stream(LlmRequest request);
}
