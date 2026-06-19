package com.tickertycoon.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tickertycoon.agent.AIPlayerAgent;
import com.tickertycoon.agent.AIPlayerArchetype;
import com.tickertycoon.agent.AIPlayerDecision;
import com.tickertycoon.document.GameStateDocument;
import com.tickertycoon.document.GameStateRepository;
import com.tickertycoon.dto.EventDTO;
import com.tickertycoon.dto.GameStateDTO;
import com.tickertycoon.dto.StartGameRequest;
import com.tickertycoon.entity.GameEntity;
import com.tickertycoon.entity.GamePlayerEntity;
import com.tickertycoon.entity.GameResultEntity;
import com.tickertycoon.entity.GameStatus;
import com.tickertycoon.repository.GamePlayerRepository;
import com.tickertycoon.repository.GameRepository;
import com.tickertycoon.repository.GameResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Core turn engine. Orchestrates:
 *   1. Event generation (EventAgent)
 *   2. Price updates + bankruptcy checks
 *   3. AI player decisions (AIPlayerAgent) — run in parallel
 *   4. Income / dividend payments
 *   5. Win check
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class GameService {

    private final EventPoolService eventPoolService;
    private final AIPlayerAgent aiPlayerAgent;
    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final GameResultRepository gameResultRepository;
    private final GameStateRepository gameStateRepository;

    @Value("${ticker-tycoon.games.max-active:50}")
    private int maxActiveGames;

    @Value("${ticker-tycoon.games.max-players:6}")
    private int maxPlayersPerGame;

    // Active engine cache; durable state lives in Postgres (roster/lifecycle) + Mongo (live state)
    private final Map<String, GameState> games = new ConcurrentHashMap<>();

    // ── Start game ──────────────────────────────────────────────────────────────

    public GameStateDTO startGame(List<StartGameRequest.HumanPlayerRequest> humanPlayers,
                                  List<String> aiArchetypeIds) {
        long activeGames = gameRepository.countByStatusIn(List.of(GameStatus.WAITING, GameStatus.ACTIVE));
        if (activeGames >= maxActiveGames) {
            throw new IllegalStateException("Maximum number of active games (" + maxActiveGames + ") reached");
        }

        List<HumanPlayerSetup> humans = buildHumanPlayers(humanPlayers);
        List<String> safeAiIds = aiArchetypeIds != null ? aiArchetypeIds : List.of();
        int totalPlayers = humans.size() + safeAiIds.size();
        if (totalPlayers > maxPlayersPerGame) {
            throw new IllegalArgumentException(
                "A game allows at most " + maxPlayersPerGame + " players, got " + totalPlayers);
        }

        String gameId = UUID.randomUUID().toString();
        GameState state = new GameState(gameId, humans, safeAiIds);
        games.put(gameId, state);
        log.info("[GameService] Started game {} with {} players", gameId, state.players.size());

        persistNewGame(state);
        writeThrough(state);

        return toDTO(state);
    }

    private void persistNewGame(GameState state) {
        GameEntity entity = new GameEntity(state.id, "Game " + state.id.substring(0, 8), maxPlayersPerGame);
        entity.setStatus(GameStatus.ACTIVE);
        entity.setStartedAt(java.time.Instant.now());
        gameRepository.save(entity);

        int seat = 1;
        for (PlayerState p : state.players) {
            gamePlayerRepository.save(new GamePlayerEntity(
                state.id, p.id, seat++, p.isAI, p.archetypeId, p.name, p.color));
        }
    }

    private void writeThrough(GameState state) {
        gameStateRepository.save(GameStateDocument.fromDTO(toDTO(state)));

        if (state.gameOver) {
            gameRepository.findById(state.id).ifPresent(g -> {
                g.setStatus(GameStatus.FINISHED);
                g.setFinishedAt(java.time.Instant.now());
                gameRepository.save(g);
            });
            List<PlayerState> ranked = state.players.stream()
                .sorted((a, b) -> Double.compare(b.netWorth, a.netWorth))
                .toList();
            for (int i = 0; i < ranked.size(); i++) {
                PlayerState p = ranked.get(i);
                gameResultRepository.save(new GameResultEntity(
                    state.id, p.id, p.netWorth, i + 1,
                    p.id.equals(state.winnerId), state.year * 4 + state.quarter));
            }
        }
    }

    // ── Advance quarter: generate event, run AI, pay income ─────────────────────

    public GameStateDTO advanceQuarter(String gameId) {
        GameState state = getState(gameId);
        if (state.gameOver) throw new IllegalStateException("Game is already over");

        log.info("[GameService] advanceQuarter requested for game={} currentQ{}Y{} poolSizeBefore={}",
            gameId, state.quarter, state.year, eventPoolService.getPoolSize());

        // 1. Generate market event
        String recentEvents = state.eventHistory.stream()
            .limit(6).map(e -> e.getName()).collect(Collectors.joining(", "));
        EventDTO event = eventPoolService.getNextEvent(
            state.quarter, state.year,
            pickMacroContext(state),
            recentEvents,
            buildConstraints(state)
        );
        state.currentEvent = event;
        state.eventHistory.add(0, event);
        log.info("[GameService] game={} Q{}Y{} event={} ({}) poolSizeAfter={}",
            gameId, state.quarter, state.year, event.getName(), event.getSeverity(), eventPoolService.getPoolSize());

        // 2. Apply price changes + bankruptcy checks
        applyPrices(state, event);

        // 3. AI players decide and trade — run in parallel
        runAIPlayers(state, event);

        // 4. Pay income / dividends to all players
        payIncome(state);

        // 5. Recalculate net worths
        recalcNetWorths(state);

        // 6. Check win condition
        state.players.stream()
            .filter(p -> p.netWorth >= 1_000_000)
            .findFirst()
            .ifPresent(winner -> {
                state.gameOver  = true;
                state.winnerId  = winner.id;
                log.info("[GameService] game={} WINNER={} netWorth={}",
                    gameId, winner.name, winner.netWorth);
            });

        // 7. Advance quarter counter
        if (!state.gameOver) {
            state.quarter++;
            if (state.quarter > 4) { state.quarter = 1; state.year++; }
        }

        writeThrough(state);
        return toDTO(state);
    }

    // ── Human player trades ──────────────────────────────────────────────────────

    public GameStateDTO humanBuy(String gameId, String playerId, String assetId, double amount) {
        GameState state = getState(gameId);
        PlayerState human = findHumanPlayer(state, playerId);

        if (state.bankruptAssets.contains(assetId))
            throw new IllegalArgumentException("Asset is bankrupt: " + assetId);
        if (amount <= 0 || amount > human.cash)
            throw new IllegalArgumentException("Invalid amount: " + amount);

        double price  = state.prices.getOrDefault(assetId, 0.0);
        double shares = amount / price;
        human.cash -= amount;

        PositionState pos = human.portfolio.computeIfAbsent(assetId,
            k -> new PositionState(assetId, 0, price));
        double newShares  = pos.shares + shares;
        pos.avgCost       = (pos.shares * pos.avgCost + amount) / newShares;
        pos.shares        = newShares;

        state.log.add(0, new LogEntry("buy",
            String.format("%s bought $%,.0f of %s", human.name, amount, assetId.toUpperCase())));

        recalcNetWorths(state);
        writeThrough(state);
        return toDTO(state);
    }

    public GameStateDTO humanSell(String gameId, String playerId, String assetId, double pct) {
        GameState state = getState(gameId);
        PlayerState human = findHumanPlayer(state, playerId);

        PositionState pos = human.portfolio.get(assetId);
        if (pos == null || pos.shares <= 0)
            throw new IllegalArgumentException("No position in: " + assetId);

        double sharesToSell = pos.shares * (pct / 100.0);
        double price        = state.prices.getOrDefault(assetId, 0.0);
        double proceeds     = sharesToSell * price;

        human.cash += proceeds;
        pos.shares -= sharesToSell;
        if (pos.shares < 0.0001) human.portfolio.remove(assetId);

        state.log.add(0, new LogEntry("sell",
            String.format("%s sold %.0f%% of %s for $%,.0f",
                human.name, pct, assetId.toUpperCase(), proceeds)));

        recalcNetWorths(state);
        writeThrough(state);
        return toDTO(state);
    }

    // ── Price engine ─────────────────────────────────────────────────────────────

    private void applyPrices(GameState state, EventDTO event) {
        state.prevPrices = new HashMap<>(state.prices);
        Map<String, Double> effects = event.getEffects() != null ? event.getEffects() : Map.of();

        AssetRegistry.ALL_ASSETS.forEach(a -> {
            if (state.bankruptAssets.contains(a.id())) return;
            double baseChange = effects.getOrDefault(a.id(), 0.0);
            double noise      = (Math.random() * 2 - 1) * a.volatility() * 0.35;
            double change     = Math.max(-0.48, Math.min(0.65, baseChange + noise));
            double newPrice   = Math.max(0.5, state.prices.getOrDefault(a.id(), a.basePrice()) * (1 + change));
            state.prices.put(a.id(), newPrice);
        });

        // Bankruptcy rolls
        Map<String, Double> bRisk = event.getBankruptRisk() != null ? event.getBankruptRisk() : Map.of();
        bRisk.forEach((assetId, prob) -> {
            AssetRegistry.AssetDef a = AssetRegistry.find(assetId);
            if (a == null || !a.canBankrupt() || state.bankruptAssets.contains(assetId)) return;
            if (Math.random() < prob) {
                double prevPrice = state.prevPrices.getOrDefault(assetId, 0.0);
                state.bankruptAssets.add(assetId);
                state.prices.put(assetId, 0.0);
                log.warn("[GameService] BANKRUPT: {} game={}", assetId, state.id);

                // Wipe all player holdings
                state.players.forEach(p -> {
                    PositionState pos = p.portfolio.remove(assetId);
                    if (pos != null) {
                        double lost = pos.shares * prevPrice;
                        state.log.add(0, new LogEntry("bankrupt",
                            String.format("☠ %s BANKRUPT — %s lost $%,.0f",
                                assetId.toUpperCase(), p.name, lost)));
                    }
                });
            }
        });
    }

    // ── AI player execution ───────────────────────────────────────────────────────

    private void runAIPlayers(GameState state, EventDTO event) {
        List<PlayerState> aiPlayers = state.players.stream()
            .filter(p -> p.isAI)
            .collect(Collectors.toList());

        // Run AI players in parallel (each is independent)
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = aiPlayers.stream()
                .map(p -> executor.<Void>submit(() -> { runOneAIPlayer(state, p, event); return null; }))
                .toList();

            for (Future<Void> f : futures) {
                try { f.get(60, TimeUnit.SECONDS); }
                catch (Exception e) {
                    log.warn("[GameService] AI player task failed: {}", e.getMessage());
                }
            }
        }
    }

    private void runOneAIPlayer(GameState state, PlayerState player, EventDTO event) {
        // Build portfolio value map
        Map<String, Double> portfolioValues = player.portfolio.entrySet().stream()
            .filter(e -> !state.bankruptAssets.contains(e.getKey()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().shares * state.prices.getOrDefault(e.getKey(), 0.0)
            ));

        // Price changes this quarter
        Map<String, Double> changes = state.prices.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> {
                    double prev = state.prevPrices.getOrDefault(e.getKey(), e.getValue());
                    return prev > 0 ? (e.getValue() - prev) / prev : 0;
                }
            ));

        // Bankrupt map
        Map<String, Boolean> bankruptMap = state.bankruptAssets.stream()
            .collect(Collectors.toMap(id -> id, id -> true));

        AIPlayerDecision decision = aiPlayerAgent.decide(
            player.archetypeId,
            player.name,
            player.cash,
            player.netWorth,
            portfolioValues,
            state.prices,
            changes,
            bankruptMap,
            event.getName(),
            event.getSeverity(),
            event.getFlavor(),
            event.getEffects() != null ? event.getEffects() : Map.of(),
            state.quarter,
            state.year
        );

        // Log reasoning
        if (decision.getReasoning() != null) {
            state.log.add(0, new LogEntry("ai",
                player.name + ": " + decision.getReasoning()));
        }

        // Execute trades
        for (AIPlayerDecision.AITradeAction trade : decision.getTrades()) {
            switch (trade.getAction()) {
                case BUY  -> executeBuy(state, player, trade);
                case SELL -> executeSell(state, player, trade);
                case HOLD -> log.debug("[AI] {} holds", player.name);
            }
        }
    }

    private void executeBuy(GameState state, PlayerState player,
                              AIPlayerDecision.AITradeAction trade) {
        String assetId = trade.getAssetId();
        double amount  = trade.getAmountUsd() != null ? trade.getAmountUsd() : 0;
        if (assetId == null || amount <= 0 || amount > player.cash) return;
        if (state.bankruptAssets.contains(assetId)) return;
        double price = state.prices.getOrDefault(assetId, 0.0);
        if (price <= 0) return;

        double shares = amount / price;
        player.cash  -= amount;
        PositionState pos = player.portfolio.computeIfAbsent(assetId,
            k -> new PositionState(k, 0, price));
        double ns    = pos.shares + shares;
        pos.avgCost  = (pos.shares * pos.avgCost + amount) / ns;
        pos.shares   = ns;

        state.log.add(0, new LogEntry("ai",
            String.format("%s BUY %s $%,.0f — %s",
                player.name, assetId.toUpperCase(), amount,
                trade.getRationale() != null ? trade.getRationale() : "")));
    }

    private void executeSell(GameState state, PlayerState player,
                               AIPlayerDecision.AITradeAction trade) {
        String assetId = trade.getAssetId();
        double pct     = trade.getSellPct() != null ? trade.getSellPct() : 100.0;
        if (assetId == null) return;
        PositionState pos = player.portfolio.get(assetId);
        if (pos == null || pos.shares <= 0) return;

        double ss    = pos.shares * (pct / 100.0);
        double price = state.prices.getOrDefault(assetId, 0.0);
        player.cash += ss * price;
        pos.shares  -= ss;
        if (pos.shares < 0.0001) player.portfolio.remove(assetId);

        state.log.add(0, new LogEntry("ai",
            String.format("%s SELL %.0f%% %s — %s",
                player.name, pct, assetId.toUpperCase(),
                trade.getRationale() != null ? trade.getRationale() : "")));
    }

    // ── Income / dividend payments ────────────────────────────────────────────────

    private void payIncome(GameState state) {
        state.players.forEach(p -> {
            double[] income = { p.cash * 0.0025 }; // cash interest
            p.portfolio.forEach((id, pos) -> {
                if (state.bankruptAssets.contains(id)) return;
                AssetRegistry.AssetDef a = AssetRegistry.find(id);
                if (a == null || a.dividendRate() <= 0) return;
                double div = pos.shares * state.prices.getOrDefault(id, 0.0) * a.dividendRate();
                income[0] += div;
                p.totalIncome += div;
            });
            p.cash       += income[0];
            p.totalIncome += p.cash * 0.0025;
            if (income[0] > 50) state.log.add(new LogEntry("income",
                String.format("%s received $%,.0f in income & dividends", p.name, income[0])));
        });
    }

    private void recalcNetWorths(GameState state) {
        state.players.forEach(p -> {
            double w = p.cash;
            for (var entry : p.portfolio.entrySet()) {
                if (!state.bankruptAssets.contains(entry.getKey())) {
                    w += entry.getValue().shares
                        * state.prices.getOrDefault(entry.getKey(), 0.0);
                }
            }
            p.netWorth = w;
        });
    }

    // ── Macro context rotation ────────────────────────────────────────────────────

    private static final List<String> MACRO_CONTEXTS = List.of(
        "global trade war escalating with broad tariff hikes",
        "a global pandemic is emerging, borders are closing",
        "global supply chains are severely disrupted",
        "the US dollar is weakening significantly",
        "global interest rates are rising sharply",
        "a global recession is being forecast",
        "inflation is running hot across major economies",
        "war has broken out in Europe threatening energy supplies",
        "China-Taiwan strait tensions are at a 20-year high",
        "China is cracking down on its domestic technology sector",
        "the US Federal Reserve is cutting interest rates",
        "strong US corporate earnings are beating expectations",
        "China is announcing major economic stimulus measures",
        "Japan is ending its negative interest rate policy",
        "AI investment is surging globally",
        "green energy transition policy is accelerating",
        "commodity prices are surging on supply shocks",
        "OPEC announces surprise production cuts"
    );

    private String pickMacroContext(GameState state) {
        // Rotate through contexts — not purely random, maintains some narrative flow
        return MACRO_CONTEXTS.get((state.macroIdx++) % MACRO_CONTEXTS.size());
    }

    private String buildConstraints(GameState state) {
        // Tell EventAgent what to avoid based on recent history
        if (state.eventHistory.size() < 2) return "no constraints";
        String recent = state.eventHistory.stream().limit(3)
            .map(e -> e.getSeverity()).collect(Collectors.joining(", "));
        boolean tooManySevere = state.eventHistory.stream().limit(3)
            .filter(e -> "severe".equals(e.getSeverity())).count() >= 2;
        return tooManySevere
            ? "recent events have been severe — please generate a mild or moderate event"
            : "vary the geography and sectors from recent events (" + recent + ")";
    }

    // ── State helpers ─────────────────────────────────────────────────────────────

    private GameState getState(String gameId) {
        GameState s = games.get(gameId);
        if (s == null) throw new IllegalArgumentException("Game not found: " + gameId);
        return s;
    }

    private List<HumanPlayerSetup> buildHumanPlayers(List<StartGameRequest.HumanPlayerRequest> requestedHumans) {
        if (requestedHumans == null || requestedHumans.isEmpty()) {
            return List.of(new HumanPlayerSetup("human-1", "Player 1", HUMAN_COLORS.get(0)));
        }

        Set<String> ids = new HashSet<>();
        List<HumanPlayerSetup> humans = new ArrayList<>();
        for (int i = 0; i < requestedHumans.size(); i++) {
            StartGameRequest.HumanPlayerRequest req = requestedHumans.get(i);
            String id = cleanId(req.getId(), "human-" + (i + 1));
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate human player id: " + id);

            String name = cleanName(req.getName(), "Player " + (i + 1));
            String color = cleanColor(req.getColor(), HUMAN_COLORS.get(i % HUMAN_COLORS.size()));
            humans.add(new HumanPlayerSetup(id, name, color));
        }
        return humans;
    }

    private PlayerState findHumanPlayer(GameState state, String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return state.players.stream()
                .filter(p -> !p.isAI)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No human players in game"));
        }

        return state.players.stream()
            .filter(p -> !p.isAI && p.id.equals(playerId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Human player not found: " + playerId));
    }

    private static final List<String> HUMAN_COLORS = List.of(
        "#2D6A5A", "#6B4EFF", "#D97706", "#0E7490", "#BE123C", "#4D7C0F"
    );

    private String cleanId(String value, String fallback) {
        String raw = value == null || value.isBlank() ? fallback : value.trim();
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("(^-|-$)", "");
        return id.isBlank() ? fallback : id;
    }

    private String cleanName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String cleanColor(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private GameStateDTO toDTO(GameState state) {
        // Map internal state to API DTO
        return GameStateDTO.builder()
            .gameId(state.id)
            .quarter(state.quarter)
            .year(state.year)
            .gameOver(state.gameOver)
            .winnerId(state.winnerId)
            .currentEvent(state.currentEvent)
            .prices(state.prices)
            .prevPrices(state.prevPrices)
            .bankruptAssets(new ArrayList<>(state.bankruptAssets))
            .marketAssets(AssetRegistry.ALL_ASSETS.stream()
                .map(a -> GameStateDTO.MarketAssetDTO.builder()
                    .id(a.id())
                    .name(a.name())
                    .ticker(a.ticker())
                    .group(a.group())
                    .sector(a.sector())
                    .region(a.region())
                    .basePrice(a.basePrice())
                    .volatility(a.volatility())
                    .dividendRate(a.dividendRate())
                    .canBankrupt(a.canBankrupt())
                    .currentPrice(state.prices.getOrDefault(a.id(), a.basePrice()))
                    .previousPrice(state.prevPrices.getOrDefault(a.id(), a.basePrice()))
                    .bankrupt(state.bankruptAssets.contains(a.id()))
                    .build())
                .toList())
            .players(state.players.stream().map(this::playerToDTO).toList())
            .log(state.log.stream().limit(50).map(e -> new GameStateDTO.LogEntryDTO(e.type(), e.msg())).toList())
            .build();
    }

    private GameStateDTO.PlayerDTO playerToDTO(PlayerState p) {
        return GameStateDTO.PlayerDTO.builder()
            .id(p.id).name(p.name).isAI(p.isAI)
            .archetypeId(p.archetypeId).color(p.color)
            .cash(p.cash).netWorth(p.netWorth).totalIncome(p.totalIncome)
            .portfolio(p.portfolio.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> new GameStateDTO.PositionDTO(e.getValue().shares, e.getValue().avgCost)
                )))
            .build();
    }

    // ── Inner state classes ───────────────────────────────────────────────────────

    static class GameState {
        String id; int quarter = 1; int year = 1;
        boolean gameOver = false; String winnerId;
        List<PlayerState>  players     = new ArrayList<>();
        Map<String, Double> prices     = new HashMap<>();
        Map<String, Double> prevPrices = new HashMap<>();
        Set<String> bankruptAssets     = new HashSet<>();
        EventDTO currentEvent;
        List<EventDTO> eventHistory    = new ArrayList<>();
        List<LogEntry> log             = new ArrayList<>();
        int macroIdx = 0;

        GameState(String id, List<HumanPlayerSetup> humanPlayers, List<String> aiIds) {
            this.id = id;
            // Init prices
            AssetRegistry.ALL_ASSETS.forEach(a -> prices.put(a.id(), a.basePrice()));
            prevPrices = new HashMap<>(prices);

            humanPlayers.forEach(h ->
                players.add(new PlayerState(h.id(), h.name(), false, null, h.color())));

            // AI players
            Set<String> usedIds = players.stream().map(p -> p.id).collect(Collectors.toSet());
            List<String> safeAiIds = aiIds != null ? aiIds : List.of();
            safeAiIds.forEach(archId -> {
                AIPlayerArchetype arch = AIPlayerArchetype.fromId(archId);
                PlayerState p = new PlayerState(uniquePlayerId(archId, usedIds), arch.getDisplayName(),
                    true, archId, arch.getColor());
                // Pre-invest according to archetype preference
                initialInvest(p, arch, prices);
                players.add(p);
            });
        }

        private void initialInvest(PlayerState p, AIPlayerArchetype arch,
                                    Map<String, Double> prices) {
            double each = 80000.0 / Math.min(arch.getPreferredAssets().size(), 4);
            int count = 0;
            for (String id : arch.getPreferredAssets()) {
                if (count >= 4) break;
                double price = prices.getOrDefault(id, 100.0);
                double shares = each / price;
                p.portfolio.put(id, new PositionState(id, shares, price));
                p.cash -= each;
                count++;
            }
        }

        private String uniquePlayerId(String baseId, Set<String> usedIds) {
            if (usedIds.add(baseId)) return baseId;
            int suffix = 2;
            String candidate = baseId + "-" + suffix;
            while (!usedIds.add(candidate)) {
                suffix++;
                candidate = baseId + "-" + suffix;
            }
            return candidate;
        }
    }

    static class PlayerState {
        String id, name, archetypeId, color;
        boolean isAI;
        double cash = 100_000, netWorth = 100_000, totalIncome = 0;
        Map<String, PositionState> portfolio = new LinkedHashMap<>();

        PlayerState(String id, String name, boolean isAI, String archetypeId, String color) {
            this.id = id; this.name = name; this.isAI = isAI;
            this.archetypeId = archetypeId; this.color = color;
        }
    }

    static class PositionState {
        String id; double shares, avgCost;
        PositionState(String id, double shares, double avgCost) {
            this.id = id; this.shares = shares; this.avgCost = avgCost;
        }
    }

    record LogEntry(String type, String msg) {}
    record HumanPlayerSetup(String id, String name, String color) {}
}
