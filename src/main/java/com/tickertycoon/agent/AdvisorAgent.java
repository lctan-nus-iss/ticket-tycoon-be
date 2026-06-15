package com.tickertycoon.agent;

import com.tickertycoon.dto.PortfolioAnalysisRequest;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.router.LlmRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class AdvisorAgent {

    private final LlmRouter llm;

    private static final String SYSTEM_PROMPT = """
        You are a financial education advisor for a turn-based investment game called Ticker Tycoon.
        Analyse the player's portfolio and provide clear, educational, actionable feedback.
        Write in HTML using <h4> for sections, <p> for paragraphs, <ul><li> for bullets, <strong> for emphasis.
        Be warm but honest. Write for someone learning about investing. Keep total response under 400 words.
        """;

    public String analyse(PortfolioAnalysisRequest req) {
        var resp = llm.complete(LlmRequest.builder()
            .agentName("advisor-agent")
            .systemPrompt(SYSTEM_PROMPT)
            .userPrompt(buildPrompt(req))
            .build());
        return resp.getText();
    }

    public Flux<String> analyseStream(PortfolioAnalysisRequest req) {
        return llm.stream(LlmRequest.builder()
            .agentName("advisor-agent")
            .systemPrompt(SYSTEM_PROMPT)
            .userPrompt(buildPrompt(req))
            .build());
    }

    private String buildPrompt(PortfolioAnalysisRequest r) {
        return """
            GAME STATE
            - Quarter: Q%d Year %d | Net worth: $%,.0f (target $1,000,000)
            - Cash: $%,.0f (%.0f%% of net worth) | Total income earned: $%,.0f
            - Last event: %s

            POSITIONS
            %s

            SUMMARY
            - Asset class mix: %s
            - Geographic exposure: %s
            - Weighted volatility index: %.0f/30
            - Estimated annual yield: %.1f%%
            - Bankruptcy-risk exposure: %.0f%% of invested portfolio

            Provide analysis in 5 sections:
            1. <h4>Overall Assessment</h4>
            2. <h4>Strengths</h4>
            3. <h4>Risks & Weaknesses</h4>
            4. <h4>Strategic Recommendations</h4>
            5. <h4>Educational Insight</h4>
            """.formatted(
                r.getQuarter(), r.getYear(), r.getNetWorth(),
                r.getCash(), r.getCashPct(), r.getTotalIncome(),
                r.getLastEventName(),
                r.getPositionsSummary(),
                r.getAssetClassMix(), r.getGeographicExposure(),
                r.getWeightedVolatility() * 100,
                r.getAnnualYield() * 100,
                r.getBankruptcyExposurePct()
        );
    }
}
