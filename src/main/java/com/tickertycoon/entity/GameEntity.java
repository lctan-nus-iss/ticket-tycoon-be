package com.tickertycoon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "games")
@Getter
@Setter
@NoArgsConstructor
public class GameEntity {

    @Id
    private String id;

    private String name;

    @Enumerated(EnumType.STRING)
    private GameStatus status = GameStatus.WAITING;

    private Integer maxPlayers = 6;

    private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;

    public GameEntity(String id, String name, int maxPlayers) {
        this.id = id;
        this.name = name;
        this.maxPlayers = maxPlayers;
    }
}
