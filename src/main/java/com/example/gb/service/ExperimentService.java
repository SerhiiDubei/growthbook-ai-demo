package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.dto.AddVariantRequest;
import com.example.gb.model.dto.CreateExperimentRequest;
import com.example.gb.model.dto.ExperimentFailRequest;
import com.example.gb.model.dto.ExperimentFinishRequest;
import com.example.gb.model.dto.UpdateExperimentRequest;
import com.example.gb.model.enums.AutonomyLevel;
import com.example.gb.model.enums.ExperimentStatus;
import com.example.gb.repository.ExperimentRepository;
import com.example.gb.repository.ExperimentVariantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final ExperimentRepository experimentRepo;
    private final ExperimentVariantRepository variantRepo;
    private final ExperimentEventWriter eventWriter;
    private final ObjectMapper objectMapper;
    private final GrowthBookSyncService gbSync;



    @Transactional
    public Experiment create(CreateExperimentRequest req, String actor) {
        log.info("➕ create experiment pageKey={} key={} featureKey={} actor={}",
                req.getPageKey(), req.getKey(), req.getFeatureKey(), actor);

        validateRecipeJson(req.getRecipeJson());

        Experiment e = new Experiment();
        e.setPageKey(req.getPageKey());
        e.setPageUrl(req.getPageUrl());
        e.setKey(req.getKey());

        e.setTitle(req.getTitle());
        e.setDescription(req.getDescription());
        e.setFeatureKey(req.getFeatureKey());

        e.setOwner(req.getOwner());
        e.setPrimaryMetric(req.getPrimaryMetric());
        e.setNotes(req.getNotes());

        e.setRecipeJson(req.getRecipeJson());
        e.setAutonomyLevel(req.getAutonomyLevel() == null ? AutonomyLevel.AGENT_FULL : req.getAutonomyLevel());

        e.setStatus(ExperimentStatus.DRAFT);
        e.setStartedAt(null);
        e.setFinishedAt(null);
        e.setLastError(null);

        Experiment saved;
        try {
            saved = experimentRepo.save(e);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(
                    "Experiment already exists for pageKey=" + req.getPageKey() + " key=" + req.getKey(), ex);
        }

        eventWriter.lifecycle(saved, "create", actor, recipeHash(saved.getRecipeJson()),
                Map.of("result", "OK"), null);

        // GB sync for DRAFT: ensure recipe + DISABLED
        try {
            gbSync.upsertRecipe(saved); // MUST be blocking in impl
            gbSync.disable(saved);      // MUST be blocking in impl

            eventWriter.lifecycle(saved, "create_gb_ok", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "recipe_upserted_disabled"), null);

        } catch (Exception ex) {
            saved.setLastError("GB sync failed on create: " + ex.getMessage());
            saved = experimentRepo.save(saved);

            eventWriter.lifecycle(saved, "create_gb_failed", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "sync_failed"), ex);

            log.warn("⚠️ create OK in DB, but GB sync failed id={} err={}", saved.getId(), ex.getMessage());
        }

        log.info("✅ create OK id={} status={} pageKey={} key={}",
                saved.getId(), saved.getStatus(), saved.getPageKey(), saved.getKey());

        return saved;
    }


    // ---------- READ (читання) ----------


    public Experiment get(long id) {
        log.debug("🔎 get experiment id={}", id);
        return experimentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found id=" + id));
    }

    public Page<Experiment> listByPageKey(String pageKey, Pageable pageable) {
        log.debug("📃 list experiments pageKey={} pageable={}", pageKey, pageable);
        return experimentRepo.findByPageKeyOrderByUpdatedAtDesc(pageKey, pageable);
    }

    // ---------- UPDATE (оновлення) ----------

    @Transactional
    public Experiment update(long id, UpdateExperimentRequest req, String actor) {
        log.info("✏️ update experiment id={} actor={}", id, actor);

        Experiment e = get(id);

        if (req.getTitle() != null) e.setTitle(req.getTitle());
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        if (req.getFeatureKey() != null) e.setFeatureKey(req.getFeatureKey());
        if (req.getOwner() != null) e.setOwner(req.getOwner());
        if (req.getPrimaryMetric() != null) e.setPrimaryMetric(req.getPrimaryMetric());
        if (req.getNotes() != null) e.setNotes(req.getNotes());
        if (req.getAutonomyLevel() != null) e.setAutonomyLevel(req.getAutonomyLevel());

        if (req.getRecipeJson() != null) {
            validateRecipeJson(req.getRecipeJson());
            e.setRecipeJson(req.getRecipeJson());
        }
        if (req.getFeatureKey() != null) {
            throw new IllegalArgumentException("featureKey update is forbidden");
        }

        Experiment saved = experimentRepo.save(e);

        eventWriter.lifecycle(saved, "update", actor, recipeHash(saved.getRecipeJson()),
                Map.of("result", "OK"), null);

        log.info("✅ update OK id={} status={}", saved.getId(), saved.getStatus());
        return saved;
    }

    // ---------- LIFECYCLE (статуси) ----------

    @Transactional
    public Experiment start(long id, String actor) {
        log.info("▶️ start experiment id={} actor={}", id, actor);
        Experiment e = get(id);

        if (e.getStatus() == ExperimentStatus.ACTIVE) return e;
        if (e.getStatus() == ExperimentStatus.FINISHED) throw new IllegalStateException("Already FINISHED");
        if (e.getStatus() == ExperimentStatus.FAILED) throw new IllegalStateException("Already FAILED");

        e.setStatus(ExperimentStatus.ACTIVE);
        e.setStartedAt(e.getStartedAt() == null ? LocalDateTime.now() : e.getStartedAt());
        e.setLastError(null);

        Experiment saved = experimentRepo.save(e);

        try {
            // Use upsertRecipeWithVariants when variants exist:
            // this updates native GB Experiment status → running AND re-fetches variation IDs via GET,
            // so upsertExperimentRefRule gets proper variationIds (not empty variations:[]).
            List<ExperimentVariant> variants =
                    variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(id);
            if (variants.isEmpty()) {
                gbSync.upsertRecipe(saved);
            } else {
                GrowthBookSyncService.SyncResult syncResult = gbSync.upsertRecipeWithVariants(saved, variants);
                persistGbIds(saved, variants, syncResult);
            }
            gbSync.enable(saved);
            gbSync.syncStatus(saved);   // backup status patch → running

            eventWriter.lifecycle(saved, "start", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "enabled"), null);

            log.info("✅ start OK id={} featureKey={}", saved.getId(), saved.getFeatureKey());
            return saved;

        } catch (Exception ex) {
            log.warn("💥 start failed (GB sync) id={} featureKey={} err={}",
                    saved.getId(), saved.getFeatureKey(), ex.getMessage());

            saved.setStatus(ExperimentStatus.FAILED);
            saved.setLastError("GB sync failed on start: " + ex.getMessage());
            Experiment failed = experimentRepo.save(saved);

            eventWriter.lifecycle(failed, "fail", actor, recipeHash(failed.getRecipeJson()),
                    Map.of("reason", "gb_sync_start_failed"), ex);

            return failed;
        }
    }

    @Transactional
    public Experiment pause(long id, String actor) {
        log.info("⏸ pause experiment id={} actor={}", id, actor);
        Experiment e = get(id);

        if (e.getStatus() != ExperimentStatus.ACTIVE) {
            throw new IllegalStateException("Can pause only ACTIVE");
        }

        e.setStatus(ExperimentStatus.PAUSED);
        Experiment saved = experimentRepo.save(e);

        try {
            gbSync.disable(saved);
            gbSync.syncStatus(saved);   // sync GB Experiment status → stopped

            eventWriter.lifecycle(saved, "pause", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "disabled"), null);

            log.info("✅ pause OK id={}", saved.getId());
            return saved;

        } catch (Exception ex) {
            saved.setLastError("GB sync failed on pause: " + ex.getMessage());
            Experiment saved2 = experimentRepo.save(saved);

            eventWriter.lifecycle(saved2, "pause_gb_failed", actor, recipeHash(saved2.getRecipeJson()),
                    Map.of("gb", "disable_failed"), ex);

            log.warn("⚠️ pause OK in DB, but GB disable failed id={} err={}", saved2.getId(), ex.getMessage());
            return saved2;
        }
    }

    @Transactional
    public Experiment resume(long id, String actor) {
        log.info("⏯ resume experiment id={} actor={}", id, actor);
        Experiment e = get(id);

        if (e.getStatus() != ExperimentStatus.PAUSED) {
            throw new IllegalStateException("Can resume only PAUSED");
        }

        e.setStatus(ExperimentStatus.ACTIVE);
        e.setLastError(null);
        Experiment saved = experimentRepo.save(e);

        try {
            List<ExperimentVariant> variants =
                    variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(id);
            if (variants.isEmpty()) {
                gbSync.upsertRecipe(saved);
            } else {
                GrowthBookSyncService.SyncResult syncResult = gbSync.upsertRecipeWithVariants(saved, variants);
                persistGbIds(saved, variants, syncResult);
            }
            gbSync.enable(saved);
            gbSync.syncStatus(saved);   // backup status patch → running

            eventWriter.lifecycle(saved, "resume", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "enabled"), null);

            log.info("✅ resume OK id={}", saved.getId());
            return saved;

        } catch (Exception ex) {
            saved.setStatus(ExperimentStatus.FAILED);
            saved.setLastError("GB sync failed on resume: " + ex.getMessage());
            Experiment failed = experimentRepo.save(saved);

            eventWriter.lifecycle(failed, "fail", actor, recipeHash(failed.getRecipeJson()),
                    Map.of("reason", "gb_sync_resume_failed"), ex);

            log.warn("💥 resume failed → FAILED id={} err={}", failed.getId(), ex.getMessage());
            return failed;
        }
    }

    /**
     * ЄДИНИЙ finish: (опціонально додає нотатку) + намагається вимкнути фічу в GB.
     */
    @Transactional
    public Experiment finish(long id, ExperimentFinishRequest req, String actor) {
        log.info("🏁 finish experiment id={} actor={}", id, actor);
        Experiment e = get(id);

        if (e.getStatus() == ExperimentStatus.FINISHED) return e;
        if (e.getStatus() == ExperimentStatus.DRAFT) throw new IllegalStateException("Cannot finish DRAFT");

        e.setStatus(ExperimentStatus.FINISHED);
        e.setFinishedAt(LocalDateTime.now());

        if (req != null && req.getNotes() != null && !req.getNotes().isBlank()) {
            e.setNotes(appendNote(e.getNotes(), "FINISH: " + req.getNotes()));
        }

        Experiment saved = experimentRepo.save(e);

        try {
            gbSync.disable(saved);
            gbSync.syncStatus(saved);   // sync GB Experiment status → stopped

            eventWriter.lifecycle(saved, "finish", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "disabled"), null);

            log.info("✅ finish OK id={}", saved.getId());
            return saved;

        } catch (Exception ex) {
            saved.setLastError("GB sync failed on finish: " + ex.getMessage());
            Experiment saved2 = experimentRepo.save(saved);

            eventWriter.lifecycle(saved2, "finish_gb_failed", actor, recipeHash(saved2.getRecipeJson()),
                    Map.of("gb", "disable_failed"), ex);

            log.warn("⚠️ finish OK in DB, but GB disable failed id={} err={}", saved2.getId(), ex.getMessage());
            return saved2;
        }
    }

    /**
     * Перевантаження для зручності (без req).
     */
    @Transactional
    public Experiment finish(long id, String actor) {
        return finish(id, null, actor);
    }

    /**
     * fail: ставимо FAILED + намагаємось вимкнути фічу в GB (але не валимо save).
     */
    @Transactional
    public Experiment fail(long id, ExperimentFailRequest req, String actor) {
        log.info("💥 fail experiment id={} actor={}", id, actor);
        Experiment e = get(id);

        e.setStatus(ExperimentStatus.FAILED);
        String err = (req == null) ? null : req.getError();
        e.setLastError(err == null || err.isBlank() ? "FAILED" : err);

        Experiment saved = experimentRepo.save(e);

        try {
            gbSync.disable(saved);
            gbSync.syncStatus(saved);   // sync GB Experiment status → stopped

            eventWriter.lifecycle(saved, "fail", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "disabled"), null);

        } catch (Exception ex) {
            saved.setLastError(appendNote(saved.getLastError(), "GB disable failed on fail: " + ex.getMessage()));
            saved = experimentRepo.save(saved);

            eventWriter.lifecycle(saved, "fail_gb_failed", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "disable_failed"), ex);

            log.warn("⚠️ fail OK in DB, but GB disable failed id={} err={}", saved.getId(), ex.getMessage());
        }

        log.info("✅ fail recorded id={} lastErrorLen={}",
                saved.getId(), saved.getLastError() == null ? 0 : saved.getLastError().length());
        return saved;
    }

    /**
     * reset: вертаємо в DRAFT + намагаємось вимкнути фічу в GB.
     */
    @Transactional
    public Experiment resetToDraft(long id, String actor) {
        log.info("🔄 resetToDraft experiment id={} actor={}", id, actor);
        Experiment e = get(id);

        e.setStatus(ExperimentStatus.DRAFT);
        e.setStartedAt(null);
        e.setFinishedAt(null);
        e.setLastError(null);

        Experiment saved = experimentRepo.save(e);

        try {
            gbSync.disable(saved);
            gbSync.syncStatus(saved);   // sync GB Experiment status → draft

            eventWriter.lifecycle(saved, "reset", actor, recipeHash(saved.getRecipeJson()),
                    Map.of("gb", "disabled"), null);

            log.info("✅ resetToDraft OK id={}", saved.getId());
            return saved;

        } catch (Exception ex) {
            saved.setLastError("GB sync failed on reset: " + ex.getMessage());
            Experiment saved2 = experimentRepo.save(saved);

            eventWriter.lifecycle(saved2, "reset_gb_failed", actor, recipeHash(saved2.getRecipeJson()),
                    Map.of("gb", "disable_failed"), ex);

            log.warn("⚠️ reset OK in DB, but GB disable failed id={} err={}", saved2.getId(), ex.getMessage());
            return saved2;
        }
    }

    // ---------- VARIANTS ----------

    /**
     * Add an A/B variant to an existing experiment.
     * Experiment must be in DRAFT or PAUSED status (not ACTIVE/FINISHED/FAILED).
     * Syncs updated variant rules to GrowthBook.
     */
    @Transactional
    public ExperimentVariant addVariant(long experimentId, AddVariantRequest req, String actor) {
        log.info("➕ addVariant expId={} key={} actor={}", experimentId, req.getKey(), actor);

        Experiment exp = get(experimentId);

        if (exp.getStatus() == ExperimentStatus.FINISHED || exp.getStatus() == ExperimentStatus.FAILED) {
            throw new IllegalStateException("Cannot add variants to a " + exp.getStatus() + " experiment");
        }
        if (req.getKey() == null || req.getKey().isBlank()) {
            throw new IllegalArgumentException("variant key is required");
        }
        if (variantRepo.existsByExperimentIdAndKey(experimentId, req.getKey())) {
            throw new IllegalArgumentException("Variant key='" + req.getKey() + "' already exists for experiment id=" + experimentId);
        }

        ExperimentVariant v = new ExperimentVariant();
        v.setExperiment(exp);
        v.setKey(req.getKey().trim());
        v.setName(req.getName());
        v.setWeight(req.getWeight() == null ? 0.5 : req.getWeight());
        v.setRecipeJson(req.getRecipeJson() == null || req.getRecipeJson().isBlank()
                ? "{\"ops\":[]}" : req.getRecipeJson());
        v.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());

        ExperimentVariant saved = variantRepo.save(v);

        // Sync updated variant rules to GB (best-effort)
        try {
            List<ExperimentVariant> allVariants = variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(experimentId);
            GrowthBookSyncService.SyncResult syncResult = gbSync.upsertRecipeWithVariants(exp, allVariants);
            persistGbIds(exp, allVariants, syncResult);
            log.info("✅ addVariant OK id={} expId={} key={}", saved.getId(), experimentId, saved.getKey());
        } catch (Exception ex) {
            log.warn("⚠️ addVariant saved in DB but GB sync failed expId={} variantKey={} err={}",
                    experimentId, saved.getKey(), ex.getMessage());
        }

        return saved;
    }

    /**
     * Returns all variants for an experiment, ordered by sortOrder then id.
     */
    public List<ExperimentVariant> getVariants(long experimentId) {
        get(experimentId); // validate experiment exists
        return variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(experimentId);
    }

    /**
     * Delete a single variant by its id.
     * Only allowed when experiment is DRAFT or PAUSED.
     */
    @Transactional
    public void deleteVariant(long variantId, String actor) {
        ExperimentVariant v = variantRepo.findById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found id=" + variantId));

        Experiment exp = v.getExperiment();
        if (exp.getStatus() == ExperimentStatus.ACTIVE) {
            throw new IllegalStateException("Cannot delete variants from an ACTIVE experiment. Pause it first.");
        }
        if (exp.getStatus() == ExperimentStatus.FINISHED || exp.getStatus() == ExperimentStatus.FAILED) {
            throw new IllegalStateException("Cannot modify variants of a " + exp.getStatus() + " experiment");
        }

        log.info("🗑️ deleteVariant id={} expId={} key={} actor={}", variantId, exp.getId(), v.getKey(), actor);
        variantRepo.delete(v);

        // Sync remaining variants to GB
        try {
            List<ExperimentVariant> remaining = variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(exp.getId());
            if (remaining.isEmpty()) {
                gbSync.upsertRecipe(exp);
            } else {
                GrowthBookSyncService.SyncResult syncResult = gbSync.upsertRecipeWithVariants(exp, remaining);
                persistGbIds(exp, remaining, syncResult);
            }
        } catch (Exception ex) {
            log.warn("⚠️ deleteVariant removed from DB but GB sync failed variantId={} err={}", variantId, ex.getMessage());
        }
    }

    // ---------- helpers ----------

    /**
     * Action names that are RecipeTools method names — they must NEVER appear
     * as op.action values in recipeJson. If they do, it means the agent incorrectly
     * wrote meta-ops into the recipe instead of calling the proper RecipeTools methods.
     */
    private static final Set<String> FORBIDDEN_META_OPS = Set.of(
            "addControlVariant", "addSwapVariant", "addReorderVariant",
            "addTextVariant", "addStyleVariant", "addMultiStyleVariant",
            "addHtmlVariant", "addAttrVariant", "addImageVariant",
            "addClassAddVariant", "addClassRemoveVariant", "addHideVariant"
    );

    private void validateRecipeJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("recipeJson is blank");
        try {
            var node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException("recipeJson must be JSON object");

            var ops = node.get("ops");
            if (ops != null && ops.isArray()) {
                for (var op : ops) {
                    String action = op.has("action") ? op.get("action").asText("") : "";
                    if (FORBIDDEN_META_OPS.contains(action)) {
                        throw new IllegalArgumentException(
                                "recipeJson contains forbidden meta-op action='" + action +
                                "'. Use RecipeTools (addControlVariant, addSwapVariant, …) " +
                                "instead of writing recipeJson manually.");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("recipeJson invalid JSON: " + e.getMessage());
        }
    }

    /**
     * Re-syncs ALL non-terminal experiments to GrowthBook with updated rules.
     * Use after upgrading rule type (e.g. force → experiment).
     * Returns a summary map: experimentId → "ok" | "skipped" | error message.
     */
    public Map<Long, String> syncAllToGrowthBook() {
        List<Experiment> toSync = experimentRepo.findAll().stream()
                .filter(e -> e.getStatus() == ExperimentStatus.DRAFT
                        || e.getStatus() == ExperimentStatus.ACTIVE
                        || e.getStatus() == ExperimentStatus.PAUSED)
                .toList();

        log.info("🔄 syncAllToGrowthBook: {} experiments to sync", toSync.size());

        Map<Long, String> result = new java.util.LinkedHashMap<>();
        for (Experiment exp : toSync) {
            try {
                List<ExperimentVariant> variants =
                        variantRepo.findByExperimentIdOrderBySortOrderAscIdAsc(exp.getId());
                GrowthBookSyncService.SyncResult syncResult = gbSync.upsertRecipeWithVariants(exp, variants);
                persistGbIds(exp, variants, syncResult);
                result.put(exp.getId(), syncResult.hasGbExperiment()
                        ? "ok (gbExpId=" + syncResult.gbExperimentId() + ")"
                        : "ok");
                log.info("✅ synced expId={} key={} gbExpId={}", exp.getId(), exp.getKey(), syncResult.gbExperimentId());
            } catch (Exception ex) {
                result.put(exp.getId(), "error: " + ex.getMessage());
                log.warn("⚠️ sync failed expId={} key={} err={}", exp.getId(), exp.getKey(), ex.getMessage());
            }
        }
        return result;
    }

    /**
     * Persists GB Experiment ID and per-variant GB Variation IDs returned by sync.
     * Called after every upsertRecipeWithVariants to keep the DB in sync with GB.
     */
    private void persistGbIds(Experiment exp,
                               List<ExperimentVariant> variants,
                               GrowthBookSyncService.SyncResult syncResult) {
        if (syncResult == null || !syncResult.hasGbExperiment()) return;

        boolean changed = false;
        if (!syncResult.gbExperimentId().equals(exp.getGbExperimentId())) {
            exp.setGbExperimentId(syncResult.gbExperimentId());
            experimentRepo.save(exp);
            changed = true;
        }

        for (ExperimentVariant v : variants) {
            String gbVarId = syncResult.variantKeyToGbVariationId().get(v.getKey());
            if (gbVarId != null && !gbVarId.equals(v.getGbVariationId())) {
                v.setGbVariationId(gbVarId);
                variantRepo.save(v);
                changed = true;
            }
        }

        if (changed) {
            log.info("💾 persistGbIds: saved gbExpId={} for expId={}", syncResult.gbExperimentId(), exp.getId());
        }
    }

    private static String appendNote(String base, String extra) {
        if (extra == null || extra.isBlank()) return base;
        if (base == null || base.isBlank()) return extra;
        return base + "\n" + extra;
    }

    public static String recipeHash(String recipeJson) {
        if (recipeJson == null) return "null";
        return sha256(recipeJson);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256_error";
        }
    }
}
