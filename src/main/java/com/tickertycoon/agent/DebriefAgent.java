package com.tickertycoon.agent;

import com.tickertycoon.dto.DebriefResult;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import com.tickertycoon.router.LlmRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DebriefAgent {

    private final LlmRouter llm;

    private static final String SYSTEM = """
        You are a financial education retrospective analyst for Ticker Tycoon.
        Analyse the player's entire game history and provide honest, educational post-game feedback.
        Write in HTML: <h4> for sections, <p> for paragraphs, <ul><li> for bullets, <strong> for emphasis.
        Be warm but candid. Teach real-world investing lessons through the player's actual decisions.
        """;

    public DebriefResult generate(String playerName, int quartersPlayed,
                                   double finalNetWorth, double peakNetWorth,
                                   String tradeHistory, String eventHistory,
                                   String portfolioSnapshots, String missedOpportunities) {
        String prompt = """
            PLAYER: %s
            Quarters played: %d | Final net worth: $%,.0f | Peak net worth: $%,.0f

            TRADE HISTORY (chronological):
            %s

            MARKET EVENTS THAT OCCURRED:
            %s

            PORTFOLIO SNAPSHOTS (per quarter):
            %s

            ASSETS PLAYER NEVER HELD (missed opportunities):
            %s

            Write a post-game debrief in 5 sections:
            1. <h4>Game Summary</h4>
            2. <h4>Best Decision</h4>
            3. <h4>Costliest Mistake</h4>
            4. <h4>Your Investment Style</h4>
            5. <h4>Three Lessons for Real Life</h4>
            """.formatted(playerName, quartersPlayed, finalNetWorth, peakNetWorth,
                          tradeHistory, eventHistory, portfolioSnapshots, missedOpportunities);

        LlmResponse resp = llm.complete(LlmRequest.builder()
            .agentName("debrief-agent")
            .systemPrompt(SYSTEM)
            .userPrompt(prompt)
            .build());

        return DebriefResult.builder()
            .html(resp.getText())
            .reasoningContent(resp.getReasoningContent())  // R1 chain-of-thought
            .provider(resp.getProvider())
            .model(resp.getModel())
            .build();
    }
}
