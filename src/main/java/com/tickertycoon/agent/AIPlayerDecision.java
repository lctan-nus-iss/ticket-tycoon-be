package com.tickertycoon.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

/**
 * The structured output from AIPlayerAgent.
 * The LLM returns a JSON object containing a list of trade decisions.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIPlayerDecision {

    private List<AITradeAction> trades;
    private String              reasoning;  // 1-2 sentence explanation shown in game log

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AITradeAction {
        private Action action;          // BUY | SELL | HOLD
        private String assetId;         // null for HOLD
        private Double amountUsd;       // for BUY — dollar amount
        private Double sellPct;         // for SELL — percentage of position (1-100)
        private String rationale;       // short reason shown in log

        public enum Action { BUY, SELL, HOLD }
    }
}
