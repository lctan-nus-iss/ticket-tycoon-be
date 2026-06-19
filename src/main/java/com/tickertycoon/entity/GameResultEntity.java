package com.tickertycoon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "game_results", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"game_id", "player_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class GameResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    private double finalNetWorth;
    private int rank;
    private boolean winner;
    private int quartersPlayed;

    public GameResultEntity(String gameId, String playerId, double finalNetWorth,
                             int rank, boolean winner, int quartersPlayed) {
        this.gameId = gameId;
        this.playerId = playerId;
        this.finalNetWorth = finalNetWorth;
        this.rank = rank;
        this.winner = winner;
        this.quartersPlayed = quartersPlayed;
    }
}
