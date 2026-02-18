package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthBookSyncServiceImpl implements GrowthBookSyncService {

    private final GbAdminService gb;

    private final Duration timeout = Duration.ofSeconds(10);

    @Override
    public void upsertRecipe(Experiment exp) {
        String featureKey = exp.getFeatureKey();
        String recipeJson = exp.getRecipeJson();

        log.info("🧩 [GB sync] upsertRecipe expId={} featureKey={}", exp.getId(), featureKey);

        gb.upsertRecipe(featureKey, recipeJson)
                .timeout(timeout)
                .block();
    }

    /**
     * Syncs the GB feature considering A/B variants.
     * <p>
     * Strategy:
     * - defaultValue in GB = control variant recipe (or experiment recipe if no control found).
     * - Each variant is added as a force rule conditioned on __variant__ attribute.
     *   This enables future GB-SDK-side delivery without changing the bridge.
     * - If no variants → falls back to plain upsertRecipe.
     */
    @Override
    public void upsertRecipeWithVariants(Experiment exp, List<ExperimentVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            upsertRecipe(exp);
            return;
        }

        String featureKey = exp.getFeatureKey();
        log.info("🧩 [GB sync] upsertRecipeWithVariants expId={} featureKey={} variants={}",
                exp.getId(), featureKey, variants.size());

        // Use control variant recipe (or first variant) as GB defaultValue
        String defaultRecipe = variants.stream()
                .filter(v -> "control".equalsIgnoreCase(v.getKey()))
                .map(ExperimentVariant::getRecipeJson)
                .findFirst()
                .orElse(variants.get(0).getRecipeJson());

        // 1. Upsert defaultValue = control recipe
        gb.upsertRecipe(featureKey, defaultRecipe)
                .timeout(timeout)
                .block();

        // 2. Add one force rule per variant (conditioned on __variant__ attribute)
        gb.upsertVariantRules(featureKey, exp.getKey(), variants)
                .timeout(timeout)
                .block();

        log.info("✅ [GB sync] upsertRecipeWithVariants OK expId={} featureKey={}", exp.getId(), featureKey);
    }

    @Override
    public void enable(Experiment exp) {
        String featureKey = exp.getFeatureKey();
        log.info("🟢 [GB sync] enable expId={} featureKey={}", exp.getId(), featureKey);

        gb.setFeatureEnabled(featureKey, true, "production")
                .timeout(timeout)
                .block();
    }

    @Override
    public void disable(Experiment exp) {
        String featureKey = exp.getFeatureKey();
        log.info("🔴 [GB sync] disable expId={} featureKey={}", exp.getId(), featureKey);

        gb.setFeatureEnabled(featureKey, false, "production")
                .timeout(timeout)
                .block();
    }
}
