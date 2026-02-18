package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.dto.ExperimentStatsResponse;
import com.example.gb.model.dto.VariantStatsDto;
import com.example.gb.repository.ExperimentEventRepository;
import com.example.gb.repository.ExperimentVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes A/B experiment statistics from experiment_event table.
 * <p>
 * Statistics per variant:
 *   - views, clicks, conversions, unique users
 *   - CTR = clicks / views
 *   - Conversion rate = conversions / views
 * <p>
 * Significance test:
 *   Two-proportion Z-test comparing best treatment CTR vs control CTR.
 *   Formula:
 *     p_pooled = (clicks_A + clicks_B) / (views_A + views_B)
 *     z = (ctr_B - ctr_A) / sqrt(p_pooled × (1 - p_pooled) × (1/n_A + 1/n_B))
 *     p-value = 2 × (1 - Φ(|z|))   [two-tailed]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private static final int MIN_VIEWS_FOR_SIGNIFICANCE = 30;

    private final ExperimentEventRepository eventRepo;
    private final ExperimentVariantRepository variantRepo;
    private final ExperimentService experimentService;

    public ExperimentStatsResponse getStats(long experimentId) {
        Experiment exp = experimentService.get(experimentId);
        List<ExperimentVariant> variants =
                variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(experimentId);

        String featureKey = exp.getFeatureKey();

        // Build per-variant stats
        List<VariantStatsDto> variantStats;
        if (variants.isEmpty()) {
            // No A/B variants — show aggregate by action only
            variantStats = buildAggregateStats(featureKey);
        } else {
            variantStats = variants.stream()
                    .map(v -> buildVariantStats(featureKey, v))
                    .collect(Collectors.toList());

            // Also include variants that have events but no DB variant record (edge case)
            List<String> knownKeys = variants.stream()
                    .map(ExperimentVariant::getKey).collect(Collectors.toList());
            eventRepo.findDistinctVariantKeysByFeatureKey(featureKey).stream()
                    .filter(k -> !knownKeys.contains(k))
                    .forEach(k -> variantStats.add(buildOrphanVariantStats(featureKey, k)));
        }

        // Significance test: find control vs best treatment
        SignificanceResult sig = computeSignificance(variantStats);

        return ExperimentStatsResponse.builder()
                .experimentId(exp.getId())
                .experimentKey(exp.getKey())
                .featureKey(featureKey)
                .status(exp.getStatus().name())
                .startedAt(exp.getStartedAt())
                .finishedAt(exp.getFinishedAt())
                .variants(variantStats)
                .zScore(sig.zScore)
                .pValue(sig.pValue)
                .significant(sig.significant)
                .relativeUpliftPercent(sig.relativeUplift)
                .summary(sig.summary)
                .build();
    }

    // -------------------------------------------------------------------------
    // Per-variant stats builders
    // -------------------------------------------------------------------------

    private VariantStatsDto buildVariantStats(String featureKey, ExperimentVariant variant) {
        String vk = variant.getKey();
        long views       = eventRepo.countByFeatureKeyAndVariantKeyAndAction(featureKey, vk, "view");
        long clicks      = eventRepo.countByFeatureKeyAndVariantKeyAndAction(featureKey, vk, "click");
        long conversions = eventRepo.countByFeatureKeyAndVariantKeyAndAction(featureKey, vk, "conversion");
        long unique      = eventRepo.countDistinctSessionsByFeatureKeyAndVariantKeyAndAction(featureKey, vk, "view");

        return VariantStatsDto.builder()
                .variantKey(vk)
                .variantName(variant.getName() != null ? variant.getName() : vk)
                .weight(variant.getWeight())
                .views(views)
                .clicks(clicks)
                .conversions(conversions)
                .uniqueUsers(unique)
                .ctr(ctr(clicks, views))
                .conversionRate(ctr(conversions, views))
                .build();
    }

    private VariantStatsDto buildOrphanVariantStats(String featureKey, String variantKey) {
        long views       = eventRepo.countByFeatureKeyAndVariantKeyAndAction(featureKey, variantKey, "view");
        long clicks      = eventRepo.countByFeatureKeyAndVariantKeyAndAction(featureKey, variantKey, "click");
        long conversions = eventRepo.countByFeatureKeyAndVariantKeyAndAction(featureKey, variantKey, "conversion");
        long unique      = eventRepo.countDistinctSessionsByFeatureKeyAndVariantKeyAndAction(featureKey, variantKey, "view");

        return VariantStatsDto.builder()
                .variantKey(variantKey)
                .variantName(variantKey)
                .weight(null)
                .views(views)
                .clicks(clicks)
                .conversions(conversions)
                .uniqueUsers(unique)
                .ctr(ctr(clicks, views))
                .conversionRate(ctr(conversions, views))
                .build();
    }

    private List<VariantStatsDto> buildAggregateStats(String featureKey) {
        long views       = eventRepo.countByFeatureKeyAndAction(featureKey, "view");
        long clicks      = eventRepo.countByFeatureKeyAndAction(featureKey, "click");
        long conversions = eventRepo.countByFeatureKeyAndAction(featureKey, "conversion");

        VariantStatsDto all = VariantStatsDto.builder()
                .variantKey("all")
                .variantName("All (no A/B split)")
                .weight(1.0)
                .views(views)
                .clicks(clicks)
                .conversions(conversions)
                .uniqueUsers(0L)
                .ctr(ctr(clicks, views))
                .conversionRate(ctr(conversions, views))
                .build();
        return List.of(all);
    }

    // -------------------------------------------------------------------------
    // Two-proportion Z-test
    // -------------------------------------------------------------------------

    private SignificanceResult computeSignificance(List<VariantStatsDto> variants) {
        if (variants.size() < 2) {
            return SignificanceResult.notEnoughData("Need at least 2 variants for significance test");
        }

        // Find control variant
        VariantStatsDto control = variants.stream()
                .filter(v -> "control".equalsIgnoreCase(v.getVariantKey()))
                .findFirst()
                .orElse(variants.get(0));

        // Find best treatment (highest CTR, excluding control)
        Optional<VariantStatsDto> bestTreatmentOpt = variants.stream()
                .filter(v -> !v.getVariantKey().equals(control.getVariantKey()))
                .filter(v -> v.getCtr() != null)
                .max(Comparator.comparingDouble(VariantStatsDto::getCtr));

        if (bestTreatmentOpt.isEmpty()) {
            return SignificanceResult.notEnoughData("No treatment variant with data found");
        }

        VariantStatsDto treatment = bestTreatmentOpt.get();

        long nA = control.getViews();
        long nB = treatment.getViews();

        if (nA < MIN_VIEWS_FOR_SIGNIFICANCE || nB < MIN_VIEWS_FOR_SIGNIFICANCE) {
            return SignificanceResult.notEnoughData(
                    String.format("Need ≥%d views per variant (control=%d, treatment=%d)",
                            MIN_VIEWS_FOR_SIGNIFICANCE, nA, nB));
        }

        double pA = control.getCtr() / 100.0;       // proportion (0..1)
        double pB = treatment.getCtr() / 100.0;

        long clicksA = control.getClicks();
        long clicksB = treatment.getClicks();

        // Pooled proportion
        double pPooled = (double)(clicksA + clicksB) / (nA + nB);

        double se = Math.sqrt(pPooled * (1 - pPooled) * (1.0 / nA + 1.0 / nB));
        if (se == 0) {
            return SignificanceResult.notEnoughData("Standard error is zero (identical proportions)");
        }

        double z = (pB - pA) / se;
        double pValue = 2.0 * (1.0 - normalCdf(Math.abs(z)));
        boolean significant = pValue < 0.05;

        Double uplift = pA > 0 ? (pB - pA) / pA * 100.0 : null;

        String summary = buildSummary(control, treatment, z, pValue, significant, uplift);

        log.info("📊 [Stats] expFeature={} control={} (ctr={}%) treatment={} (ctr={}%) z={} p={} sig={}",
                control.getVariantKey(), String.format("%.2f", control.getCtr()),
                treatment.getVariantKey(), String.format("%.2f", treatment.getCtr()),
                String.format("%.3f", z), String.format("%.4f", pValue), significant);

        return new SignificanceResult(z, pValue, significant, uplift, summary);
    }

    private String buildSummary(VariantStatsDto control, VariantStatsDto treatment,
                                double z, double pValue, boolean significant, Double uplift) {
        String upliftStr = uplift != null
                ? String.format("%+.1f%%", uplift)
                : "N/A";
        String confidence = String.format("%.0f%%", (1 - pValue) * 100);

        if (significant) {
            double ctrB = treatment.getCtr() != null ? treatment.getCtr() : 0;
            double ctrA = control.getCtr() != null ? control.getCtr() : 0;
            String winner = ctrB > ctrA ? treatment.getVariantName() : control.getVariantName();
            return String.format("'%s' is winning: CTR uplift %s, confidence %s (p=%.4f)",
                    winner, upliftStr, confidence, pValue);
        } else {
            return String.format("No significant difference yet (p=%.4f, need p<0.05 for 95%% confidence). " +
                    "CTR uplift: %s. Collect more data.", pValue, upliftStr);
        }
    }

    // -------------------------------------------------------------------------
    // Math helpers
    // -------------------------------------------------------------------------

    private static Double ctr(long numerator, long denominator) {
        if (denominator == 0) return null;
        return (double) numerator / denominator * 100.0;
    }

    /**
     * Cumulative distribution function for standard normal distribution.
     * Abramowitz & Stegun approximation — max error 7.5e-8.
     */
    private static double normalCdf(double x) {
        if (x < 0) return 1 - normalCdf(-x);
        double t = 1.0 / (1.0 + 0.2316419 * x);
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));
        return 1.0 - (1.0 / Math.sqrt(2 * Math.PI)) * Math.exp(-0.5 * x * x) * poly;
    }

    // -------------------------------------------------------------------------
    // Result holder
    // -------------------------------------------------------------------------

    private record SignificanceResult(
            Double zScore,
            Double pValue,
            Boolean significant,
            Double relativeUplift,
            String summary
    ) {
        static SignificanceResult notEnoughData(String reason) {
            return new SignificanceResult(null, null, null, null, reason);
        }
    }
}
