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
     * Syncs the native GrowthBook Experiment status to match our local status.
     * Called after every lifecycle transition (start/pause/finish/fail/reset).
     * No-op if experiment has no gbExperimentId yet.
     * Best-effort: logs but does not throw on failure.
     */
    void syncStatus(Experiment exp);

    /**
     * Fetches the current status of the native GrowthBook Experiment from the API.
     * Returns null if the experiment has no gbExperimentId or the API call fails.
     */
    String fetchGbStatus(Experiment exp);

    /**
     * Compensation action: delete feature from GrowthBook.
     * Best-effort: logs but does not throw on failure.
     */
    void deleteFeature(Experiment exp);

    /**
     * Compensation action: delete native GB Experiment from GrowthBook.
     * Best-effort: logs but does not throw on failure.
     */
    void deleteGbExperiment(Experiment exp);

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
