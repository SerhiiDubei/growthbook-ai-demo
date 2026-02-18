package com.example.gb.model.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full A/B statistics response for one experiment.
 */
@Getter
@Builder
public class ExperimentStatsResponse {

    private Long experimentId;
    private String experimentKey;
    private String featureKey;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** Stats broken down per variant */
    private List<VariantStatsDto> variants;

    // ---- Significance (control vs best treatment) ----

    /**
     * Z-score from two-proportion Z-test comparing best treatment CTR vs control CTR.
     * Null if not enough data.
     */
    private Double zScore;

    /**
     * Two-tailed p-value. Values below 0.05 are typically considered significant.
     * Null if not enough data.
     */
    private Double pValue;

    /**
     * True when pValue < 0.05 (95% confidence).
     */
    private Boolean significant;

    /**
     * Relative CTR uplift of best treatment vs control: (ctrB - ctrA) / ctrA × 100 (%)
     * Null if control has 0 views.
     */
    private Double relativeUpliftPercent;

    /**
     * Human-readable summary: "Treatment is winning (+12.3% CTR, p=0.03)"
     */
    private String summary;
}
