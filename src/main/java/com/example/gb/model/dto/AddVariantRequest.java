package com.example.gb.model.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Request to add one A/B variant to an existing experiment.
 */
@Getter
@Setter
public class AddVariantRequest {

    /** Short identifier: "control", "treatment", "A", "B". Immutable after creation. */
    private String key;

    /** Human-readable name: "Control", "Yellow Button". */
    private String name;

    /**
     * Traffic weight in [0.0, 1.0]. All variants of one experiment should sum to 1.0.
     * Default 0.5 if omitted.
     */
    private Double weight;

    /**
     * DOM recipe JSON for this variant: {"ops":[...]}.
     * "control" typically has {"ops":[]} (no changes).
     */
    private String recipeJson;

    /** Display order (0 = first). Optional. */
    private Integer sortOrder;
}
