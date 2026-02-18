package com.example.gb.service;

import com.example.gb.model.ExperimentVariant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Deterministic, stateless user-to-variant assignment.
 * <p>
 * Given a sessionTag (gbtag cookie) and an experiment key, always returns
 * the same variant for the same user. No DB write needed — pure hash math.
 * <p>
 * Algorithm:
 * <pre>
 *   bucket = abs(hash(sessionTag + ":" + experimentKey)) mod 10000   → 0..9999
 *   Walk variants in order; pick the first variant whose cumulative
 *   weight × 10000 exceeds bucket.
 * </pre>
 * This guarantees:
 * - Same user always gets same variant (sticky assignment).
 * - Traffic split matches the declared weights (50/50, 80/20, etc.).
 * - No DB reads — O(n) where n = number of variants (typically 2).
 */
@Slf4j
@Service
public class VariantAssignmentService {

    private static final int BUCKET_COUNT = 10_000;

    /**
     * Assign a user to a variant.
     *
     * @param sessionTag    user identifier (gbtag cookie value)
     * @param experimentKey unique experiment key (Experiment.key)
     * @param variants      sorted list of variants with weights summing to ~1.0
     * @return variant key (e.g. "control" or "treatment"), or null if variants is empty
     */
    public String assign(String sessionTag, String experimentKey, List<ExperimentVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        if (variants.size() == 1) {
            return variants.get(0).getKey();
        }

        int bucket = bucket(sessionTag, experimentKey);
        double normalizedBucket = (double) bucket / BUCKET_COUNT;

        double totalWeight = variants.stream()
                .mapToDouble(v -> v.getWeight() == null ? 0.0 : v.getWeight())
                .sum();

        if (totalWeight <= 0) {
            log.warn("[VariantAssign] All weights are zero for experiment={}, returning first variant", experimentKey);
            return variants.get(0).getKey();
        }

        double cumulative = 0.0;
        for (ExperimentVariant variant : variants) {
            double w = (variant.getWeight() == null ? 0.0 : variant.getWeight()) / totalWeight;
            cumulative += w;
            if (normalizedBucket < cumulative) {
                log.debug("[VariantAssign] session={} exp={} bucket={} → variant={}",
                        sessionTag, experimentKey, bucket, variant.getKey());
                return variant.getKey();
            }
        }

        // Floating-point edge case: return last variant
        String last = variants.get(variants.size() - 1).getKey();
        log.debug("[VariantAssign] session={} exp={} bucket={} → variant={} (fallback last)",
                sessionTag, experimentKey, bucket, last);
        return last;
    }

    /**
     * Returns a stable bucket 0..9999 for a given (sessionTag, experimentKey) pair.
     * Uses Java's String.hashCode which is deterministic within a JVM version.
     * For cross-language stability in future, consider MurmurHash3.
     */
    private int bucket(String sessionTag, String experimentKey) {
        String seed = (sessionTag == null ? "" : sessionTag)
                + ":"
                + (experimentKey == null ? "" : experimentKey);
        int h = seed.hashCode();
        return Math.abs(h == Integer.MIN_VALUE ? 0 : h) % BUCKET_COUNT;
    }
}
