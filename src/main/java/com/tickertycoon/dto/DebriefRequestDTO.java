package com.tickertycoon.dto;

import lombok.Data;

@Data
public class DebriefRequestDTO {
    private String playerName;
    private int    quartersPlayed;
    private double finalNetWorth;
    private double peakNetWorth;
    private String tradeHistory;
    private String eventHistory;
    private String portfolioSnapshots;
    private String missedOpportunities;
}
