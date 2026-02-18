package com.example.gb.model.dto;

import com.example.gb.model.enums.AutonomyLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateExperimentRequest {

    @NotBlank @Size(max = 200)
    private String pageKey;

    @Size(max = 500)
    private String pageUrl;

    @NotBlank @Size(max = 200)
    private String key; // unique within pageKey

    @NotBlank @Size(max = 300)
    private String title;

    private String description;

    @NotBlank @Size(max = 300)
    private String featureKey;

    @Size(max = 100)
    private String owner;

    @Size(max = 64)
    private String primaryMetric;

    private String notes;

    /**
     * JSON object string: {"ops":[...], "vars":{...}}
     * Stored as jsonb (через String).
     */
    @NotBlank
    private String recipeJson;

    private AutonomyLevel autonomyLevel = AutonomyLevel.AGENT_FULL;
}
