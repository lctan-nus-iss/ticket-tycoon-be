package com.tickertycoon.document;

import com.tickertycoon.dto.EventDTO;
import com.tickertycoon.dto.GameStateDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "games")
public class GameStateDocument {

    @Id
    private String id;

    private int quarter;
    private int year;
    private boolean gameOver;
    private String winnerId;
    private int macroIdx;

    private Map<String, Double> prices = new HashMap<>();
    private Map<String, Double> prevPrices = new HashMap<>();
    private List<String> bankruptAssets = new ArrayList<>();

    private EventDTO currentEvent;
    private List<EventDTO> eventHistory = new ArrayList<>();

    private Map<String, PlayerState> players = new HashMap<>();
    private List<LogEntry> log = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerState {
        private String name;
        private boolean isAi;
        private String archetypeId;
        private String color;
        private double cash;
        private double netWorth;
        private double totalIncome;
        private Map<String, Position> portfolio = new HashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Position {
        private double shares;
        private double avgCost;
    }

    public record LogEntry(String type, String msg) {}

    public static GameStateDocument fromDTO(GameStateDTO dto) {
        GameStateDocument doc = new GameStateDocument();
        doc.id = dto.getGameId();
        doc.quarter = dto.getQuarter();
        doc.year = dto.getYear();
        doc.gameOver = dto.isGameOver();
        doc.winnerId = dto.getWinnerId();
        doc.prices = new HashMap<>(dto.getPrices());
        doc.prevPrices = new HashMap<>(dto.getPrevPrices());
        doc.bankruptAssets = new ArrayList<>(dto.getBankruptAssets());
        doc.currentEvent = dto.getCurrentEvent();

        dto.getPlayers().forEach(p -> {
            PlayerState ps = new PlayerState();
            ps.name = p.getName();
            ps.isAi = p.isAI();
            ps.archetypeId = p.getArchetypeId();
            ps.color = p.getColor();
            ps.cash = p.getCash();
            ps.netWorth = p.getNetWorth();
            ps.totalIncome = p.getTotalIncome();
            p.getPortfolio().forEach((assetId, pos) ->
                ps.portfolio.put(assetId, new Position(pos.getShares(), pos.getAvgCost())));
            doc.players.put(p.getId(), ps);
        });

        dto.getLog().forEach(l -> doc.log.add(new LogEntry(l.type(), l.msg())));

        return doc;
    }
}
