package com.tickertycoon.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickertycoon.dto.EventDTO;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.router.LlmRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class EventAgent {

    private final LlmRouter    llm;
    private final ObjectMapper mapper;

    private static final String SYSTEM_PROMPT = """
        You are the market event engine for a financial education board game called Ticker Tycoon.
        Players invest in fictitious stocks, REITs, and regional ETFs spanning US, China, Asia-Pacific, and Europe.
        Generate financially realistic, geographically differentiated market events.
        Each event should feel logically connected to the recent macro backdrop provided.
        Always respond with valid JSON only — no markdown, no explanation.
        """;

    public EventDTO generateNext(int quarter, int year, String macroContext,
                                  String recentEventNames, String constraints) {
        String userPrompt = """
            Quarter: Q%d Year %d
            Macro backdrop: %s
            Recent events (avoid repeating): %s
            Constraints: %s

            Assets: nvx(Tech stock), qntm(Tech stock), cldx(Tech stock), prm(Finance stock),
            vlt(Finance stock), drx(Energy stock), solv(Energy stock), medx(Healthcare stock),
            biov(Healthcare stock), luxe(Consumer stock), groc(Consumer stock), trns(Industrial stock),
            nxdc(Data Centre REIT), apex(Office REIT), vrdx(Healthcare REIT), plzx(Mall REIT),
            logx(Industrial REIT), svrx(Office REIT), carx(Healthcare REIT), whrx(Industrial REIT),
            etf_us(US Equity ETF), etf_ustech(US Tech ETF), etf_cn(China Tech ETF),
            etf_asia(Asia-Pac ex-China ETF), etf_eu(Europe ETF),
            bonds(Bond ETF), reitx(REIT ETF), gold(Gold ETF), silver(Silver ETF), oil(Brent Oil ETF)

            Generate ONE event as JSON:
            {"name":"headline 5-8 words","icon":"emoji","severity":"mild|moderate|severe",
             "flavor":"2 sentences of news","effects":{"assetId":decimalChange},
             "lesson":"2-3 sentences explaining geographic and sector logic",
             "bankruptRisk":{"assetId":probability}}

            Rules: mild max ±10%%, moderate ±22%%, severe ±40%%.
            Regional ETFs must reflect geography (Europe war → etf_eu worst hit).
            bankruptRisk only for stocks/REITs, max 0.4, only when severely negative.
            """.formatted(quarter, year, macroContext, recentEventNames, constraints);

        try {
            var resp = llm.complete(LlmRequest.builder()
                .agentName("event-agent")
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(userPrompt)
                .build());

            String clean = resp.getText().replaceAll("```json|```", "").trim();
            EventDTO event = mapper.readValue(clean, EventDTO.class);
            event.setGeneratedBy(resp.getProvider() + "/" + resp.getModel());
            return event;
        } catch (Exception e) {
            log.error("[EventAgent] Failed to generate event from LLM; using fallback. cause={}", e.getMessage());
            return EventDTO.fallback();
        }
    }
}
