package com.tickertycoon.dto;

import lombok.Builder;
import lombok.Value;
import java.util.*;

@Value
@Builder
public class GameStateDTO {

    String          gameId;
    int             quarter;
    int             year;
    boolean         gameOver;
    String          winnerId;
    EventDTO        currentEvent;
    Map<String, Double> prices;
    Map<String, Double> prevPrices;
    List<String>    bankruptAssets;
    List<MarketAssetDTO> marketAssets;
    List<PlayerDTO> players;
    List<LogEntryDTO> log;

    @Value
    @Builder
    public static class MarketAssetDTO {
        String  id;
        String  name;
        String  ticker;
        String  group;
        String  sector;
        String  region;
        double  basePrice;
        double  volatility;
        double  dividendRate;
        boolean canBankrupt;
        double  currentPrice;
        double  previousPrice;
        boolean bankrupt;
    }

    @Value
    @Builder
    public static class PlayerDTO {
        String  id;
        String  name;
        boolean isAI;
        String  archetypeId;
        String  color;
        double  cash;
        double  netWorth;
        double  totalIncome;
        Map<String, PositionDTO> portfolio;
    }

    @Value
    public static class PositionDTO {
        double shares;
        double avgCost;
    }

    public record LogEntryDTO(String type, String msg) {}
}
