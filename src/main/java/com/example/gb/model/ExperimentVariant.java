package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One A/B variant for an {@link Experiment}.
 * <p>
 * Each experiment has 2+ variants (e.g. "control" + "treatment").
 * Weights must sum to 1.0 across all variants of one experiment.
 * Each variant carries its own recipeJson (DOM operations for this variant).
 * <p>
 * Example:
 * <pre>
 *   variant key="control"   weight=0.5  recipe={"ops":[]}
 *   variant key="treatment" weight=0.5  recipe={"ops":[{"action":"css","prop":"background-color","value":"#FFD700",...}]}
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(
        name = "experiment_variant",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_exp_variant_exp_key",
                columnNames = {"experiment_id", "key"}
        ),
        indexes = @Index(name = "ix_exp_variant_experiment_id", columnList = "experiment_id")
)
public class ExperimentVariant extends AbstractVersional {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    @JsonIgnoreProperties({"variants", "hibernateLazyInitializer", "handler"})
    private Experiment experiment;

    /**
     * Short identifier: "control", "treatment", "A", "B".
     * Immutable after creation (used for hashing + event tracking).
     */
    @Column(name = "key", nullable = false, length = 100)
    private String key;

    /**
     * Human-readable name shown in UI/reports: "Control", "Yellow Button".
     */
    @Column(name = "name", length = 200)
    private String name;

    /**
     * Traffic weight in range [0.0, 1.0].
     * All variants of one experiment should sum to 1.0.
     * Example: 0.5 = 50% of traffic.
     */
    @Column(name = "weight", nullable = false)
    private Double weight = 0.5;

    /**
     * DOM recipe JSON for this variant.
     * Same format as Experiment.recipeJson ({"ops":[...]}).
     * "control" variant typically has {"ops":[]} (no changes).
     */
    @Column(name = "recipe_json", nullable = false, columnDefinition = "text")
    private String recipeJson = "{\"ops\":[]}";

    /**
     * Display order in UI (0 = first).
     */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /**
     * Native GrowthBook variation ID (e.g. "var_abc123").
     * Returned by GB inside experiment.variations[].id after native experiment sync.
     * Required to build experiment-ref rules on features.
     */
    @Column(name = "gb_variation_id", length = 100)
    private String gbVariationId;
}
