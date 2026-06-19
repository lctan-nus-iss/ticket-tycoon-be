package com.tickertycoon.repository;

import com.tickertycoon.entity.GameResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameResultRepository extends JpaRepository<GameResultEntity, Long> {
}
