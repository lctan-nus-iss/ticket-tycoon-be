package com.tickertycoon.agent;

import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.router.LlmRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CoachAgent {

    private final LlmRouter llm;

    private static final String SYSTEM = """
        You are a concise financial education coach in a game. When a player makes a notable trade,
        provide a 2-3 sentence teaching moment. Be encouraging but educational. Plain text only.
        """;

    /**
     * Returns a coaching message if the trade warrants one, empty otherwise.
     * Rule-based pre-filter avoids unnecessary LLM calls.
     */
    public Optional<String> evaluate(String playerName, String action, String assetName,
                                      double amountUsd, double portfolioPct,
                                      double assetVolatility, boolean isBankruptRisk,
                                      double cashPct, int quartersHoldingCash) {

        String trigger = detectTrigger(amountUsd, portfolioPct, assetVolatility,
                                        isBankruptRisk, cashPct, quartersHoldingCash);
        if (trigger == null) return Optional.empty();

        String prompt = """
            Player: %s | Action: %s %s ($%,.0f = %.0f%% of portfolio)
            Asset volatility: %.0f/30 | Bankruptcy risk: %s
            Trigger: %s

            Give a 2-3 sentence financial education tip about this trade.
            """.formatted(playerName, action, assetName, amountUsd, portfolioPct * 100,
                          assetVolatility * 100, isBankruptRisk ? "YES" : "NO", trigger);

        String msg = llm.complete(LlmRequest.builder()
            .agentName("coach-agent")
            .systemPrompt(SYSTEM)
            .userPrompt(prompt)
            .build()).getText();

        return Optional.of(msg);
    }

    private String detectTrigger(double amount, double portfolioPct, double vol,
                                   boolean bankruptRisk, double cashPct, int quartersHoldingCash) {
        if (portfolioPct > 0.40 && bankruptRisk)     return "HIGH_CONCENTRATION_BANKRUPT_RISK";
        if (portfolioPct > 0.50)                      return "EXCESSIVE_CONCENTRATION";
        if (vol > 0.22 && portfolioPct > 0.30)        return "HIGH_VOLATILITY_LARGE_POSITION";
        if (cashPct > 0.50 && quartersHoldingCash > 2) return "CASH_DRAG";
        return null;
    }
}
