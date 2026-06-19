package com.tickertycoon.repository;

import com.tickertycoon.entity.GameEntity;
import com.tickertycoon.entity.GameStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface GameRepository extends JpaRepository<GameEntity, String> {
    long countByStatusIn(Collection<GameStatus> statuses);
}
