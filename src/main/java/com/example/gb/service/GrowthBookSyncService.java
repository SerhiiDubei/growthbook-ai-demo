package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;

import java.util.List;
import java.util.Map;

public interface GrowthBookSyncService {

    /** Ensure feature exists + upsert recipe (defaultValue) */
    void upsertRecipe(Experiment exp);

    /**
     * Ensure feature + upsert A/B variants.
     * Creates (or updates) a native GB Experiment, stores the experiment-ref rule
     * on the feature, and returns sync result with GB IDs.
     *
     * @return {@link SyncResult} containing gbExperimentId and per-variant gbVariationId mapping.
     *         Returns empty result if no variants were provided.
     */
    SyncResult upsertRecipeWithVariants(Experiment exp, List<ExperimentVariant> variants);

    /** Enable feature in GrowthBook for this experiment */
    void enable(Experiment exp);

    /** Disable feature in GrowthBook for this experiment */
    void disable(Experiment exp);

    /**
     * Result of syncing an experiment with variants to GrowthBook.
     * Contains native GB Experiment ID and per-variant GB Variation ID mapping.
     */
    record SyncResult(
            String gbExperimentId,
            Map<String, String> variantKeyToGbVariationId  // "control" → "var_abc123"
    ) {
        public static SyncResult empty() {
            return new SyncResult(null, Map.of());
        }

        public boolean hasGbExperiment() {
            return gbExperimentId != null && !gbExperimentId.isBlank();
        }
    }
}
