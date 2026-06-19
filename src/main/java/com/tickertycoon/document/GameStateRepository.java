package com.tickertycoon.document;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface GameStateRepository extends MongoRepository<GameStateDocument, String> {
}
