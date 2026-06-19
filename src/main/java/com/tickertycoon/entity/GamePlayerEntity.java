package com.tickertycoon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "game_players", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"game_id", "player_id"}),
    @UniqueConstraint(columnNames = {"game_id", "seat_no"})
})
@Getter
@Setter
@NoArgsConstructor
public class GamePlayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    private boolean isAi;
    private String archetypeId;
    private String name;
    private String color;

    private Instant joinedAt = Instant.now();

    public GamePlayerEntity(String gameId, String playerId, int seatNo,
                             boolean isAi, String archetypeId, String name, String color) {
        this.gameId = gameId;
        this.playerId = playerId;
        this.seatNo = seatNo;
        this.isAi = isAi;
        this.archetypeId = archetypeId;
        this.name = name;
        this.color = color;
    }
}
