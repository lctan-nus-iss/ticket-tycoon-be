package com.tickertycoon.dto;

import lombok.Data;
import java.util.List;

@Data
public class StartGameRequest {
    private List<HumanPlayerRequest> humanPlayers;
    private List<String> aiArchetypeIds;

    @Data
    public static class HumanPlayerRequest {
        private String id;
        private String name;
        private String color;
    }
}
