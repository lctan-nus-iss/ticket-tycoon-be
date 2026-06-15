package com.tickertycoon.agent;

import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.router.LlmRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NarratorAgent {

    private final LlmRouter llm;

    private static final String SYSTEM = """
        You are the financial news narrator for Ticker Tycoon. Write immersive 2-3 paragraph
        newspaper excerpts that connect this quarter's event to the recent macro story.
        Teach cause-and-effect naturally through storytelling. No headers, pure prose.
        """;

    public String generateHeadlines(String currentEvent, String recentEvents,
                                     String biggestMovers, int quarter, int year) {
        String prompt = """
            Quarter: Q%d Year %d
            This quarter's event: %s
            Recent events: %s
            Biggest price movers: %s

            Write a 2-3 paragraph TickerTimes newspaper excerpt.
            """.formatted(quarter, year, currentEvent, recentEvents, biggestMovers);

        return llm.complete(LlmRequest.builder()
            .agentName("narrator-agent")
            .systemPrompt(SYSTEM)
            .userPrompt(prompt)
            .build()).getText();
    }
}
