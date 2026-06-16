package com.tickertycoon.controller;

import com.tickertycoon.agent.AIPlayerArchetype;
import com.tickertycoon.agent.AdvisorAgent;
import com.tickertycoon.agent.DebriefAgent;
import com.tickertycoon.dto.*;
import com.tickertycoon.service.GameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.util.*;

@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
@Log4j2
public class GameController {

    private final GameService   gameService;
    private final AdvisorAgent  advisorAgent;
    private final DebriefAgent  debriefAgent;

    @PostMapping("/start")
    public ResponseEntity<GameStateDTO> startGame(@RequestBody StartGameRequest req) {
        log.info("Starting game with {} human players and AI archetypes {}",
                req.getHumanPlayers(), req.getAiArchetypeIds());

        return ResponseEntity.ok(
            gameService.startGame(req.getHumanPlayers(), req.getAiArchetypeIds()));
    }

    @PostMapping("/{gameId}/advance")
    public ResponseEntity<GameStateDTO> advanceQuarter(@PathVariable String gameId) {
        log.info("Advancing game {} to next quarter", gameId);
        return ResponseEntity.ok(gameService.advanceQuarter(gameId));
    }

    @PostMapping("/{gameId}/buy")
    public ResponseEntity<GameStateDTO> buy(
            @PathVariable String gameId, @RequestBody TradeRequest req) {
        return ResponseEntity.ok(
            gameService.humanBuy(gameId, req.getPlayerId(), req.getAssetId(), req.getAmount()));
    }

    @PostMapping("/{gameId}/sell")
    public ResponseEntity<GameStateDTO> sell(
            @PathVariable String gameId, @RequestBody TradeRequest req) {
        return ResponseEntity.ok(
            gameService.humanSell(gameId, req.getPlayerId(), req.getAssetId(), req.getPct()));
    }

    @PostMapping("/{gameId}/analyse/{playerId}")
    public ResponseEntity<String> analysePortfolio(
            @PathVariable String gameId, @PathVariable String playerId,
            @RequestBody PortfolioAnalysisRequest req) {
        return ResponseEntity.ok(advisorAgent.analyse(req));
    }

    @GetMapping(value = "/{gameId}/analyse/{playerId}/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyseStream(
            @PathVariable String gameId, @PathVariable String playerId,
            @RequestBody PortfolioAnalysisRequest req) {
        return advisorAgent.analyseStream(req);
    }

    @PostMapping("/{gameId}/debrief/{playerId}")
    public ResponseEntity<DebriefResult> debrief(
            @PathVariable String gameId, @PathVariable String playerId,
            @RequestBody DebriefRequestDTO req) {
        return ResponseEntity.ok(debriefAgent.generate(
            req.getPlayerName(), req.getQuartersPlayed(),
            req.getFinalNetWorth(), req.getPeakNetWorth(),
            req.getTradeHistory(), req.getEventHistory(),
            req.getPortfolioSnapshots(), req.getMissedOpportunities()));
    }

    @GetMapping("/archetypes")
    public ResponseEntity<List<Map<String, Object>>> archetypes() {
        return ResponseEntity.ok(
            Arrays.stream(AIPlayerArchetype.values()).map(a -> Map.<String,Object>of(
                "id",             a.getId(),
                "displayName",    a.getDisplayName(),
                "style",          a.getStyle(),
                "color",          a.getColor(),
                "preferredAssets",a.getPreferredAssets()
            )).toList());
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> providers() {
        return ResponseEntity.ok(Map.of(
            "status",    "ok",
            "providers", List.of("deepseek")));
    }
}
