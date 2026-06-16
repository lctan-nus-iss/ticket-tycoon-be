package com.tickertycoon.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickertycoon.agent.AIPlayerDecision.AITradeAction;
import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.router.LlmRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Log4j2
public class AIPlayerAgent {

    private final LlmRouter    llm;
    private final ObjectMapper mapper;

    /**
     * Generate trading decisions for one AI player based on their archetype,
     * current portfolio state, and the latest market event.
     */
    public AIPlayerDecision decide(
            String archetypeId,
            String playerName,
            double cash,
            double netWorth,
            Map<String, Double> portfolio,    // assetId -> current market value
            Map<String, Double> prices,
            Map<String, Double> priceChanges, // assetId -> % change this quarter
            Map<String, Boolean> bankrupt,
            String eventName,
            String eventSeverity,
            String eventFlavor,
            Map<String, Double> eventEffects,
            int quarter,
            int year
    ) {
        AIPlayerArchetype archetype = AIPlayerArchetype.fromId(archetypeId);

        String userPrompt = buildDecisionPrompt(
            archetype, playerName, cash, netWorth, portfolio, prices,
            priceChanges, bankrupt, eventName, eventSeverity,
            eventFlavor, eventEffects, quarter, year
        );

        try {
            var resp = llm.complete(LlmRequest.builder()
                .agentName("ai-player-agent")
                .systemPrompt(archetype.getSystemPrompt())
                .userPrompt(userPrompt)
                .build());

            String clean = resp.getText()
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("```", "")
                .trim();

            AIPlayerDecision decision = mapper.readValue(clean, AIPlayerDecision.class);
            validateAndSanitise(decision, cash, portfolio, prices, bankrupt);
            return decision;

        } catch (Exception e) {
            log.warn("[AIPlayerAgent] LLM parse failed for {} ({}), using rule-based fallback: {}",
                playerName, archetypeId, e.getMessage());
            return ruleBasedFallback(archetype, cash, netWorth, portfolio, prices,
                priceChanges, bankrupt, eventEffects);
        }
    }

    // ── Prompt builder ──────────────────────────────────────────────────────────

    private String buildDecisionPrompt(
            AIPlayerArchetype arch, String name,
            double cash, double netWorth,
            Map<String, Double> portfolio, Map<String, Double> prices,
            Map<String, Double> priceChanges, Map<String, Boolean> bankrupt,
            String eventName, String eventSeverity, String eventFlavor,
            Map<String, Double> eventEffects, int quarter, int year
    ) {
        double cashPct     = netWorth > 0 ? (cash / netWorth) * 100 : 100;
        double invested    = netWorth - cash;
        String portfolioStr = portfolio.isEmpty() ? "  (no positions — fully in cash)"
            : portfolio.entrySet().stream()
                .map(e -> {
                    double chg = priceChanges.getOrDefault(e.getKey(), 0.0);
                    boolean isBankrupt = bankrupt.getOrDefault(e.getKey(), false);
                    return String.format("  %s: $%,.0f (%.0f%% of portfolio)%s%s",
                        e.getKey().toUpperCase(), e.getValue(),
                        netWorth > 0 ? e.getValue() / netWorth * 100 : 0,
                        chg != 0 ? String.format(" | last move: %+.1f%%", chg * 100) : "",
                        isBankrupt ? " ⚠ BANKRUPT" : "");
                })
                .collect(Collectors.joining("\n"));

        String effectsStr = eventEffects.isEmpty() ? "  (no specific asset effects)"
            : eventEffects.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> String.format("  %s: %+.1f%%", e.getKey().toUpperCase(), e.getValue() * 100))
                .collect(Collectors.joining("\n"));

        return """
            GAME STATE: Q%d Year %d
            PLAYER: %s (%s)
            NET WORTH: $%,.0f | CASH: $%,.0f (%.0f%% of portfolio)

            CURRENT MARKET EVENT:
              Name: %s (%s)
              What happened: %s
              Asset effects this quarter:
            %s

            YOUR CURRENT PORTFOLIO:
            %s

            AVAILABLE ASSETS TO TRADE (not bankrupt):
              Stocks: nvx, qntm, cldx, prm, vlt, drx, solv, medx, biov, luxe, groc, trns
              REITs: nxdc, apex, vrdx, plzx, logx, svrx, carx, whrx
              ETFs: etf_us, etf_ustech, etf_cn, etf_asia, etf_eu, bonds, reitx, gold, silver, oil

            BANKRUPT ASSETS (cannot trade): %s

            TASK:
            Decide what trades to make this quarter. Stay true to your personality and investment thesis.
            You can make 1-4 trades. You may hold if that aligns with your strategy.

            Respond ONLY with a JSON object in this exact format:
            {
              "reasoning": "1-2 sentences explaining your overall strategy this quarter",
              "trades": [
                {"action": "BUY",  "assetId": "etf_us",  "amountUsd": 15000, "rationale": "brief reason"},
                {"action": "SELL", "assetId": "bonds",   "sellPct": 50,      "rationale": "brief reason"},
                {"action": "HOLD", "assetId": null,      "rationale": "holding remaining positions"}
              ]
            }

            CONSTRAINTS:
            - BUY amountUsd must not exceed available cash ($%,.0f)
            - SELL sellPct must be between 1 and 100
            - Only trade assets that exist in the available list above
            - Do not trade bankrupt assets
            - Maximum 4 trades total
            - If you want to hold everything, return a single HOLD action
            """.formatted(
                quarter, year, name, arch.getDisplayName(),
                netWorth, cash, cashPct,
                eventName, eventSeverity, eventFlavor,
                effectsStr,
                portfolioStr,
                bankrupt.keySet().stream().filter(k -> bankrupt.get(k)).collect(Collectors.joining(", ")),
                cash
        );
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    private void validateAndSanitise(
            AIPlayerDecision decision,
            double cash,
            Map<String, Double> portfolio,
            Map<String, Double> prices,
            Map<String, Boolean> bankrupt
    ) {
        if (decision.getTrades() == null) {
            decision.setTrades(List.of());
            return;
        }

        double remainingCash = cash;
        List<AITradeAction> valid = new ArrayList<>();

        for (AITradeAction t : decision.getTrades()) {
            if (t.getAction() == null) continue;

            switch (t.getAction()) {
                case BUY -> {
                    if (t.getAssetId() == null || bankrupt.getOrDefault(t.getAssetId(), false)) continue;
                    if (!prices.containsKey(t.getAssetId())) continue;
                    if (t.getAmountUsd() == null || t.getAmountUsd() <= 0) continue;
                    // Cap to available cash
                    double buyAmt = Math.min(t.getAmountUsd(), remainingCash * 0.95);
                    if (buyAmt < 100) continue;
                    t.setAmountUsd(buyAmt);
                    remainingCash -= buyAmt;
                    valid.add(t);
                }
                case SELL -> {
                    if (t.getAssetId() == null) continue;
                    if (!portfolio.containsKey(t.getAssetId())) continue;
                    if (bankrupt.getOrDefault(t.getAssetId(), false)) continue;
                    double pct = t.getSellPct() == null ? 100.0
                                 : Math.max(1.0, Math.min(100.0, t.getSellPct()));
                    t.setSellPct(pct);
                    valid.add(t);
                }
                case HOLD -> valid.add(t);
            }
            if (valid.size() >= 4) break; // max 4 trades
        }

        decision.setTrades(valid);
    }

    // ── Rule-based fallback (no LLM) ────────────────────────────────────────────

    private AIPlayerDecision ruleBasedFallback(
            AIPlayerArchetype arch,
            double cash, double netWorth,
            Map<String, Double> portfolio,
            Map<String, Double> prices,
            Map<String, Double> priceChanges,
            Map<String, Boolean> bankrupt,
            Map<String, Double> eventEffects
    ) {
        List<AITradeAction> trades = new ArrayList<>();
        double deployableCash = cash * 0.80;

        // Remove bankrupt positions
        portfolio.keySet().stream()
            .filter(id -> bankrupt.getOrDefault(id, false))
            .forEach(id -> {
                AITradeAction sell = new AITradeAction();
                sell.setAction(AITradeAction.Action.SELL);
                sell.setAssetId(id);
                sell.setSellPct(100.0);
                sell.setRationale("Clearing bankrupt position");
                trades.add(sell);
            });

        // Buy preferred assets with available cash
        if (deployableCash > 500) {
            List<String> preferred = arch.getPreferredAssets().stream()
                .filter(id -> !bankrupt.getOrDefault(id, false))
                .filter(prices::containsKey)
                .toList();

            if (!preferred.isEmpty()) {
                // Find the preferred asset most boosted by the event
                String best = preferred.stream()
                    .max(Comparator.comparingDouble(id -> eventEffects.getOrDefault(id, 0.0)))
                    .orElse(preferred.get(0));

                double buyAmt = Math.min(deployableCash, netWorth * 0.15);
                if (buyAmt > 100) {
                    AITradeAction buy = new AITradeAction();
                    buy.setAction(AITradeAction.Action.BUY);
                    buy.setAssetId(best);
                    buy.setAmountUsd(buyAmt);
                    buy.setRationale("Rebalancing toward preferred assets");
                    trades.add(buy);
                }
            }
        }

        if (trades.isEmpty()) {
            AITradeAction hold = new AITradeAction();
            hold.setAction(AITradeAction.Action.HOLD);
            hold.setRationale("No trades warranted this quarter");
            trades.add(hold);
        }

        AIPlayerDecision d = new AIPlayerDecision();
        d.setTrades(trades);
        d.setReasoning("Rule-based decision (LLM unavailable)");
        return d;
    }
}
