package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.enums.ExperimentStatus;
import com.example.gb.repository.ExperimentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodic cleanup job that handles stale experiments left in PENDING state.
 *
 * PENDING means the app saved the experiment to DB but crashed (or timed out)
 * before completing the GrowthBook sync. This is the Saga compensation step
 * that runs asynchronously to ensure eventual consistency.
 *
 * What it does:
 *  1. Finds all PENDING experiments older than 5 minutes.
 *  2. For each: attempts to delete the feature from GrowthBook (compensation).
 *  3. Marks the experiment as FAILED with a descriptive error.
 *
 * This ensures that after any crash or timeout, the system self-heals within
 * one cleanup cycle (default: every 5 minutes).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExperimentCleanupJob {

    private static final int STALE_PENDING_MINUTES = 5;

    private final ExperimentRepository experimentRepo;
    private final GrowthBookSyncService gbSync;

    /**
     * Runs every 5 minutes. Finds PENDING experiments older than 5 minutes
     * and compensates: deletes GB feature + marks FAILED.
     */
    @Scheduled(fixedDelayString = "${experiment.cleanup.interval-ms:300000}")
    public void cleanupStalePending() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_PENDING_MINUTES);
        List<Experiment> stale = experimentRepo.findByStatusAndUpdatedAtBefore(
                ExperimentStatus.PENDING, cutoff);

        if (stale.isEmpty()) {
            log.debug("[cleanup] No stale PENDING experiments found");
            return;
        }

        log.warn("[cleanup] Found {} stale PENDING experiment(s) — running compensation", stale.size());

        for (Experiment exp : stale) {
            log.warn("[cleanup] Compensating stale PENDING expId={} featureKey={} updatedAt={}",
                    exp.getId(), exp.getFeatureKey(), exp.getUpdatedAt());
            try {
                // Compensation: delete partially-created GB feature (best-effort)
                gbSync.deleteFeature(exp);
                // Also delete GB experiment if it was partially created
                gbSync.deleteGbExperiment(exp);
            } catch (Exception ex) {
                log.warn("[cleanup] Compensation GB delete failed expId={} err={}", exp.getId(), ex.getMessage());
            }

            exp.setStatus(ExperimentStatus.FAILED);
            exp.setLastError("Stale PENDING: GB sync did not complete within " +
                    STALE_PENDING_MINUTES + " minutes. Compensated by cleanup job.");
            experimentRepo.save(exp);

            log.info("[cleanup] expId={} marked FAILED after stale PENDING compensation", exp.getId());
        }
    }
}
