package com.tickertycoon.dto;

import lombok.Data;

@Data
public class PortfolioAnalysisRequest {
    private int    quarter;
    private int    year;
    private double netWorth;
    private double cash;
    private double cashPct;
    private double totalIncome;
    private String lastEventName;
    private String positionsSummary;
    private String assetClassMix;
    private String geographicExposure;
    private double weightedVolatility;
    private double annualYield;
    private double bankruptcyExposurePct;
}
