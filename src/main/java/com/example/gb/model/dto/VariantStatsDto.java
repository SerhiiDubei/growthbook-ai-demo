package com.example.gb.model.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Statistics for one A/B variant.
 */
@Getter
@Builder
public class VariantStatsDto {

    /** Variant key: "control", "treatment", etc. */
    private String variantKey;

    /** Human-readable variant name */
    private String variantName;

    /** Declared traffic weight (0.0 .. 1.0) */
    private Double weight;

    /** Total view events logged for this variant */
    private long views;

    /** Total click events logged for this variant */
    private long clicks;

    /** Total conversion events logged for this variant */
    private long conversions;

    /** Unique sessions (users) who saw this variant */
    private long uniqueUsers;

    /** Click-through rate: clicks / views × 100 (%), null if views == 0 */
    private Double ctr;

    /** Conversion rate: conversions / views × 100 (%), null if views == 0 */
    private Double conversionRate;
}
