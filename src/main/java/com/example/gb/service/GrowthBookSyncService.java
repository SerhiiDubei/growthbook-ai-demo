package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;

import java.util.List;

public interface GrowthBookSyncService {

    /** Ensure feature exists + upsert recipe (defaultValue) */
    void upsertRecipe(Experiment exp);

    /**
     * Ensure feature exists + upsert recipe considering A/B variants.
     * When variants are present, syncs the control variant's recipe as defaultValue.
     * Each variant is also pushed as a force rule keyed by __variant__ attribute
     * so future GB-SDK-based delivery is possible without bridge changes.
     */
    void upsertRecipeWithVariants(Experiment exp, List<ExperimentVariant> variants);

    /** Enable feature in GrowthBook for this experiment */
    void enable(Experiment exp);

    /** Disable feature in GrowthBook for this experiment */
    void disable(Experiment exp);
}
