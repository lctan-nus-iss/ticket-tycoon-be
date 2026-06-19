package com.tickertycoon.repository;

import com.tickertycoon.entity.GamePlayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GamePlayerRepository extends JpaRepository<GamePlayerEntity, Long> {
    List<GamePlayerEntity> findByGameId(String gameId);
    long countByGameId(String gameId);
}
