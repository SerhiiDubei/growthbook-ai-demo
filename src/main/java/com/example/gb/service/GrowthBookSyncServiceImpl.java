package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.enums.ExperimentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper om;

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
     * Full Phase-2 sync:
     * 1. Upsert feature defaultValue (control recipe).
     * 2. Create/update native GB Experiment → get GB Experiment ID + Variation IDs.
     * 3. Replace feature rules with experiment-ref rule pointing to native experiment.
     *
     * Returns {@link SyncResult} with GB IDs so the caller can persist them.
     */
    @Override
    public SyncResult upsertRecipeWithVariants(Experiment exp, List<ExperimentVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            upsertRecipe(exp);
            return SyncResult.empty();
        }

        String featureKey = exp.getFeatureKey();
        log.info("🧩 [GB sync] upsertRecipeWithVariants expId={} featureKey={} variants={}",
                exp.getId(), featureKey, variants.size());

        // 1. Upsert defaultValue = control recipe (or first variant)
        String defaultRecipe = variants.stream()
                .filter(v -> "control".equalsIgnoreCase(v.getKey()))
                .map(ExperimentVariant::getRecipeJson)
                .findFirst()
                .orElse(variants.get(0).getRecipeJson());

        gb.upsertRecipe(featureKey, defaultRecipe)
                .timeout(timeout)
                .block();

        // 2. Create or update native GB Experiment
        // Pass raw ExperimentStatus name (ACTIVE/PAUSED/DRAFT/FINISHED) so that
        // GbAdminService.mapStatus() can correctly map it to GB values (running/stopped/draft).
        // Do NOT pre-map here — mapToGbStatus + mapStatus would double-map and produce "draft".
        String rawStatus = exp.getStatus() != null ? exp.getStatus().name() : "DRAFT";
        GbAdminService.NativeExperimentResult nativeResult = gb.ensureNativeExperiment(
                exp.getGbExperimentId(),
                exp.getKey(),
                exp.getTitle(),
                exp.getDescription(),
                rawStatus,
                variants
        ).timeout(timeout).block();

        if (nativeResult == null || !nativeResult.gbExperimentId().isBlank()) {
            log.info("✅ [GB sync] native experiment ready id={}", nativeResult != null ? nativeResult.gbExperimentId() : "null");
        }

        // 3. Set gbVariationId on each variant (in-memory, caller will persist)
        if (nativeResult != null) {
            variants.forEach(v -> {
                String gbVarId = nativeResult.variantKeyToVariationId().get(v.getKey());
                if (gbVarId != null) v.setGbVariationId(gbVarId);
            });
        }

        // 4. Replace feature rules with experiment-ref rule
        if (nativeResult != null && nativeResult.gbExperimentId() != null) {
            gb.upsertExperimentRefRule(featureKey, nativeResult.gbExperimentId(), variants)
                    .timeout(timeout)
                    .block();
        } else {
            // Fallback: use experiment rule if native experiment creation failed
            log.warn("⚠️ [GB sync] native experiment not created, falling back to experiment rule");
            gb.upsertVariantRules(featureKey, exp.getKey(), variants)
                    .timeout(timeout)
                    .block();
        }

        log.info("✅ [GB sync] upsertRecipeWithVariants OK expId={} featureKey={}", exp.getId(), featureKey);

        return nativeResult != null
                ? new SyncResult(nativeResult.gbExperimentId(), nativeResult.variantKeyToVariationId())
                : SyncResult.empty();
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

    /**
     * Syncs the native GrowthBook Experiment status to match our local ExperimentStatus.
     * No-op if no gbExperimentId. Best-effort: never throws.
     */
    @Override
    public void syncStatus(Experiment exp) {
        String gbExpId = exp.getGbExperimentId();
        if (gbExpId == null || gbExpId.isBlank()) {
            log.debug("[GB sync] syncStatus skipped — no gbExperimentId for expId={}", exp.getId());
            return;
        }

        String gbStatus = mapToGbStatus(exp.getStatus());
        log.info("🔄 [GB sync] syncStatus expId={} gbExpId={} → {}", exp.getId(), gbExpId, gbStatus);

        try {
            gb.updateNativeExperimentStatus(gbExpId, gbStatus)
                    .timeout(timeout)
                    .block();
            log.info("✅ [GB sync] syncStatus OK gbExpId={} status={}", gbExpId, gbStatus);
        } catch (Exception e) {
            log.warn("⚠️ [GB sync] syncStatus failed gbExpId={} status={} err={}",
                    gbExpId, gbStatus, e.getMessage());
        }
    }

    /**
     * Fetches the current status of the native GrowthBook Experiment from the API.
     * Returns null if no gbExperimentId or the API call fails.
     */
    @Override
    public String fetchGbStatus(Experiment exp) {
        String gbExpId = exp.getGbExperimentId();
        if (gbExpId == null || gbExpId.isBlank()) return null;

        try {
            String raw = gb.getNativeExperimentRaw(gbExpId)
                    .timeout(timeout)
                    .block();
            if (raw == null || raw.isBlank()) return null;

            JsonNode root = om.readTree(raw);
            JsonNode expNode = root.has("experiment") ? root.get("experiment") : root;
            String status = expNode.path("status").asText(null);
            return (status == null || status.isBlank()) ? null : status;
        } catch (Exception e) {
            log.warn("⚠️ [GB sync] fetchGbStatus failed gbExpId={} err={}", gbExpId, e.getMessage());
            return null;
        }
    }

    private static String mapToGbStatus(ExperimentStatus status) {
        if (status == null) return "draft";
        return switch (status) {
            case ACTIVE   -> "running";
            case FINISHED -> "stopped";
            case PAUSED   -> "stopped";
            default       -> "draft";
        };
    }
}
