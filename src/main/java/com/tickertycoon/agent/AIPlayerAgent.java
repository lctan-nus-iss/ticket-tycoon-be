package com.tickertycoon.agent;

import com.tickertycoon.agent.AIPlayerDecision.AITradeAction;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@Log4j2
public class AIPlayerAgent {

    /**
     * Generate trading decisions for one AI player using only rule-based logic.
     */
    public AIPlayerDecision decide(
            String archetypeId,
            String playerName,
            double cash,
            double netWorth,
            Map<String, Double> portfolio,
            Map<String, Double> prices,
            Map<String, Double> priceChanges,
            Map<String, Boolean> bankrupt,
            String eventName,
            String eventSeverity,
            String eventFlavor,
            Map<String, Double> eventEffects,
            int quarter,
            int year
    ) {
        AIPlayerArchetype archetype = AIPlayerArchetype.fromId(archetypeId);
        AIPlayerDecision decision = ruleBasedDecision(
            archetype, cash, netWorth, portfolio, prices, priceChanges, bankrupt, eventEffects
        );
        validateAndSanitise(decision, cash, portfolio, prices, bankrupt);

        log.debug("[AIPlayerAgent] LLM disabled for {} ({}); using rule-based decisioning",
            playerName, archetypeId);
        return decision;
    }

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

        for (AITradeAction trade : decision.getTrades()) {
            if (trade.getAction() == null) {
                continue;
            }

            switch (trade.getAction()) {
                case BUY -> {
                    if (trade.getAssetId() == null || bankrupt.getOrDefault(trade.getAssetId(), false)) {
                        continue;
                    }
                    if (!prices.containsKey(trade.getAssetId())) {
                        continue;
                    }
                    if (trade.getAmountUsd() == null || trade.getAmountUsd() <= 0) {
                        continue;
                    }

                    double buyAmt = Math.min(trade.getAmountUsd(), remainingCash * 0.95);
                    if (buyAmt < 100) {
                        continue;
                    }

                    trade.setAmountUsd(buyAmt);
                    remainingCash -= buyAmt;
                    valid.add(trade);
                }
                case SELL -> {
                    if (trade.getAssetId() == null) {
                        continue;
                    }
                    if (!portfolio.containsKey(trade.getAssetId())) {
                        continue;
                    }
                    if (bankrupt.getOrDefault(trade.getAssetId(), false)) {
                        continue;
                    }

                    double pct = trade.getSellPct() == null ? 100.0
                        : Math.max(1.0, Math.min(100.0, trade.getSellPct()));
                    trade.setSellPct(pct);
                    valid.add(trade);
                }
                case HOLD -> valid.add(trade);
            }

            if (valid.size() >= 4) {
                break;
            }
        }

        decision.setTrades(valid);
    }

    private AIPlayerDecision ruleBasedDecision(
            AIPlayerArchetype arch,
            double cash,
            double netWorth,
            Map<String, Double> portfolio,
            Map<String, Double> prices,
            Map<String, Double> priceChanges,
            Map<String, Boolean> bankrupt,
            Map<String, Double> eventEffects
    ) {
        List<AITradeAction> trades = new ArrayList<>();
        double deployableCash = cash * 0.80;

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

        if (deployableCash > 500) {
            List<String> preferred = arch.getPreferredAssets().stream()
                .filter(id -> !bankrupt.getOrDefault(id, false))
                .filter(prices::containsKey)
                .toList();

            if (!preferred.isEmpty()) {
                String best = preferred.stream()
                    .max(Comparator.comparingDouble(id ->
                        scorePreferredAsset(id, eventEffects, priceChanges)))
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

        AIPlayerDecision decision = new AIPlayerDecision();
        decision.setTrades(trades);
        decision.setReasoning("Making strategic moves based on market conditions.");
        return decision;
    }

    private double scorePreferredAsset(
            String assetId,
            Map<String, Double> eventEffects,
            Map<String, Double> priceChanges
    ) {
        return eventEffects.getOrDefault(assetId, 0.0) + (priceChanges.getOrDefault(assetId, 0.0) * 0.25);
    }
}
