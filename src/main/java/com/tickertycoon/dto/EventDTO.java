package com.tickertycoon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventDTO {

    private String              name;
    private String              icon;
    private String              severity;      // mild | moderate | severe
    private String              flavor;
    private Map<String, Double> effects;       // assetId -> decimal change
    private String              lesson;
    private Map<String, Double> bankruptRisk;  // assetId -> probability
    private String              generatedBy;   // provider/model that created this

    public static EventDTO fallback() {
        EventDTO e = new EventDTO();
        e.setName("Markets Digest Recent Moves");
        e.setIcon("📊");
        e.setSeverity("mild");
        e.setFlavor("Global markets pause as investors reassess positions following recent volatility.");
        e.setEffects(Map.of("etf_us", -0.02, "bonds", 0.02, "gold", 0.03));
        e.setLesson("Markets often consolidate after large moves as investors re-evaluate risk and reward.");
        e.setBankruptRisk(Map.of());
        e.setGeneratedBy("fallback");
        return e;
    }
}
