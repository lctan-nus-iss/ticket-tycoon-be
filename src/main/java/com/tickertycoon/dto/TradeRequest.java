package com.tickertycoon.dto;

import lombok.Data;

@Data
public class TradeRequest {
    private String assetId;
    private double amount;   // for BUY
    private double pct;      // for SELL (1-100)
}
