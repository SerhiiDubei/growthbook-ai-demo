package com.example.gb.model.dto;

import com.example.gb.model.enums.AutonomyLevel;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateExperimentRequest {

    @Size(max = 300)
    private String title;

    private String description;

    @Size(max = 300)
    private String featureKey;

    @Size(max = 100)
    private String owner;

    @Size(max = 64)
    private String primaryMetric;

    private String notes;

    /** Optional: replace recipe */
    private String recipeJson;

    private AutonomyLevel autonomyLevel;
}
