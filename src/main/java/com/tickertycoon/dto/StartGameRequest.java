package com.tickertycoon.dto;

import lombok.Data;
import java.util.List;

@Data
public class StartGameRequest {
    private String       humanName;
    private List<String> aiArchetypeIds;
}
