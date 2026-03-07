package com.example.gb.ai.tools;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.dto.AddVariantRequest;
import com.example.gb.model.dto.CreateExperimentRequest;
import com.example.gb.model.dto.ExperimentFailRequest;
import com.example.gb.model.dto.ExperimentFinishRequest;
import com.example.gb.model.dto.ExperimentStatsResponse;
import com.example.gb.model.dto.UpdateExperimentRequest;
import com.example.gb.model.enums.AutonomyLevel;
import com.example.gb.service.ExperimentService;
import com.example.gb.service.GrowthBookSyncService;
import com.example.gb.service.StatisticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExperimentTools {

    private static final Logger log = LoggerFactory.getLogger(ExperimentTools.class);

    private static final String ACTOR = "agent"; // ✅ зафіксовано

    private final ExperimentService experimentService;
    private final GrowthBookSyncService gbSync;
    private final StatisticsService statisticsService;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------
    // CREATE
    // ------------------------------------------------------------

    @Tool("""
          Create an experiment in DRAFT status and sync skeleton+recipe to GrowthBook (disabled).
          featureKey cannot be changed later by the agent.
          Returns JSON: {ok,id,status,pageKey,key,featureKey}
          """)
    public String createExperiment(
            @P("Page key (e.g. home)") String pageKey,
            @P("Page URL (optional)") String pageUrl,
            @P("Experiment key unique within pageKey (e.g. hero_h1_test_01)") String key,
            @P("Title") String title,
            @P("Description (optional)") String description,
            @P("GrowthBook feature key") String featureKey,
            @P("Owner (optional)") String owner,
            @P("Primary metric (optional)") String primaryMetric,
            @P("Notes (optional)") String notes,
            @P("Recipe JSON object string, e.g. {\"ops\":[]}") String recipeJson,
            @P(value = "Autonomy level, e.g. AGENT_FULL (optional)", required = false) String autonomyLevel
    ) {
        try {
            log.info("[EXP TOOL] createExperiment pageKey={} key={} featureKey={}", pageKey, key, featureKey);

            validateJsonObject(recipeJson, "recipeJson");

            CreateExperimentRequest req = new CreateExperimentRequest();
            req.setPageKey(pageKey);
            req.setPageUrl(pageUrl);
            req.setKey(key);
            req.setTitle(title);
            req.setDescription(description);
            req.setFeatureKey(featureKey);
            req.setOwner(owner);
            req.setPrimaryMetric(primaryMetric);
            req.setNotes(notes);
            req.setRecipeJson(recipeJson);

            AutonomyLevel lvl = parseAutonomy(autonomyLevel);
            req.setAutonomyLevel(lvl == null ? AutonomyLevel.AGENT_FULL : lvl);

            Experiment saved = experimentService.create(req, ACTOR);
            return ok(saved, Map.of("op", "create"));

        } catch (Exception e) {
            log.error("[EXP TOOL] createExperiment FAILED", e);
            return error("createExperiment", e);
        }
    }

    // ------------------------------------------------------------
    // UPDATE (без featureKey)
    // ------------------------------------------------------------

    @Tool("""
          Update experiment fields. Agent is NOT allowed to change featureKey.
          If recipeJson provided, must be a JSON object string.
          Returns JSON: {ok,id,status,...}
          """)
    public String updateExperiment(
            @P("Experiment id") long id,
            @P(value = "Title (optional)", required = false) String title,
            @P(value = "Description (optional)", required = false) String description,
            @P(value = "Owner (optional)", required = false) String owner,
            @P(value = "Primary metric (optional)", required = false) String primaryMetric,
            @P(value = "Notes (optional)", required = false) String notes,
            @P(value = "Recipe JSON object string (optional)", required = false) String recipeJson,
            @P(value = "Autonomy level (optional)", required = false) String autonomyLevel,
            @P(value = "featureKey (MUST be null/blank, changing is forbidden)", required = false) String featureKey
    ) {
        try {
            log.info("[EXP TOOL] updateExperiment id={}", id);

            // ✅ заборона зміни featureKey
            if (featureKey != null && !featureKey.isBlank()) {
                throw new IllegalArgumentException("featureKey change is forbidden for agent");
            }

            if (recipeJson != null && !recipeJson.isBlank()) {
                validateJsonObject(recipeJson, "recipeJson");
            }

            UpdateExperimentRequest req = new UpdateExperimentRequest();
            req.setTitle(title);
            req.setDescription(description);
            req.setOwner(owner);
            req.setPrimaryMetric(primaryMetric);
            req.setNotes(notes);
            req.setRecipeJson(recipeJson);

            AutonomyLevel lvl = parseAutonomy(autonomyLevel);
            if (lvl != null) req.setAutonomyLevel(lvl);

            // НЕ ставимо req.setFeatureKey(...)

            Experiment saved = experimentService.update(id, req, ACTOR);
            return ok(saved, Map.of("op", "update"));

        } catch (Exception e) {
            log.error("[EXP TOOL] updateExperiment FAILED id={}", id, e);
            return error("updateExperiment", e);
        }
    }

    @Tool("""
          Update ONLY recipeJson (safe helper). Agent is NOT allowed to change featureKey.
          Returns JSON: {ok,id,status,...}
          """)
    public String updateRecipe(
            @P("Experiment id") long id,
            @P("Recipe JSON object string, e.g. {\"ops\":[...]}") String recipeJson
    ) {
        try {
            log.info("[EXP TOOL] updateRecipe id={} recipeLen={}", id, recipeJson == null ? 0 : recipeJson.length());
            validateJsonObject(recipeJson, "recipeJson");

            UpdateExperimentRequest req = new UpdateExperimentRequest();
            req.setRecipeJson(recipeJson);

            Experiment saved = experimentService.update(id, req, ACTOR);
            return ok(saved, Map.of("op", "updateRecipe"));

        } catch (Exception e) {
            log.error("[EXP TOOL] updateRecipe FAILED id={}", id, e);
            return error("updateRecipe", e);
        }
    }

    // ------------------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------------------

    @Tool("Start experiment (DRAFT/PAUSED -> ACTIVE) and enable in GrowthBook. Returns JSON result.")
    public String startExperiment(@P("Experiment id") long id) {
        try {
            log.info("[EXP TOOL] startExperiment id={}", id);
            Experiment saved = experimentService.start(id, ACTOR);
            return ok(saved, Map.of("op", "start"));
        } catch (Exception e) {
            log.error("[EXP TOOL] startExperiment FAILED id={}", id, e);
            return error("startExperiment", e);
        }
    }

    @Tool("Pause experiment (ACTIVE -> PAUSED) and disable in GrowthBook. Returns JSON result.")
    public String pauseExperiment(@P("Experiment id") long id) {
        try {
            log.info("[EXP TOOL] pauseExperiment id={}", id);
            Experiment saved = experimentService.pause(id, ACTOR);
            return ok(saved, Map.of("op", "pause"));
        } catch (Exception e) {
            log.error("[EXP TOOL] pauseExperiment FAILED id={}", id, e);
            return error("pauseExperiment", e);
        }
    }

    @Tool("Resume experiment (PAUSED -> ACTIVE) and enable in GrowthBook. Returns JSON result.")
    public String resumeExperiment(@P("Experiment id") long id) {
        try {
            log.info("[EXP TOOL] resumeExperiment id={}", id);
            Experiment saved = experimentService.resume(id, ACTOR);
            return ok(saved, Map.of("op", "resume"));
        } catch (Exception e) {
            log.error("[EXP TOOL] resumeExperiment FAILED id={}", id, e);
            return error("resumeExperiment", e);
        }
    }

    @Tool("""
          Finish experiment (ACTIVE/PAUSED -> FINISHED) and disable in GrowthBook.
          notes optional.
          Returns JSON result.
          """)
    public String finishExperiment(
            @P("Experiment id") long id,
            @P(value = "Finish notes (optional)", required = false) String notes
    ) {
        try {
            log.info("[EXP TOOL] finishExperiment id={} notesLen={}", id, notes == null ? 0 : notes.length());

            ExperimentFinishRequest req = null;
            if (notes != null && !notes.isBlank()) {
                req = new ExperimentFinishRequest();
                req.setNotes(notes);
            }

            Experiment saved = experimentService.finish(id, req, ACTOR);
            return ok(saved, Map.of("op", "finish"));
        } catch (Exception e) {
            log.error("[EXP TOOL] finishExperiment FAILED id={}", id, e);
            return error("finishExperiment", e);
        }
    }

    @Tool("""
          Mark experiment as FAILED and disable in GrowthBook.
          error message optional.
          Returns JSON result.
          """)
    public String failExperiment(
            @P("Experiment id") long id,
            @P(value = "Error message (optional)", required = false) String error
    ) {
        try {
            log.info("[EXP TOOL] failExperiment id={} errLen={}", id, error == null ? 0 : error.length());

            ExperimentFailRequest req = null;
            if (error != null && !error.isBlank()) {
                req = new ExperimentFailRequest();
                req.setError(error);
            }

            Experiment saved = experimentService.fail(id, req, ACTOR);
            return ok(saved, Map.of("op", "fail"));
        } catch (Exception e) {
            log.error("[EXP TOOL] failExperiment FAILED id={}", id, e);
            return error("failExperiment", e);
        }
    }

    @Tool("Reset experiment to DRAFT and disable in GrowthBook. Returns JSON result.")
    public String resetExperimentToDraft(@P("Experiment id") long id) {
        try {
            log.info("[EXP TOOL] resetExperimentToDraft id={}", id);
            Experiment saved = experimentService.resetToDraft(id, ACTOR);
            return ok(saved, Map.of("op", "reset"));
        } catch (Exception e) {
            log.error("[EXP TOOL] resetExperimentToDraft FAILED id={}", id, e);
            return error("resetExperimentToDraft", e);
        }
    }

    // ------------------------------------------------------------
    // READ
    // ------------------------------------------------------------

    @Tool("""
          Get experiment by id.
          Always fetches the real-time status from GrowthBook API (source of truth).
          Returns JSON: {ok, id, localStatus, gbStatus, gbExperimentId, pageKey, key, featureKey, ...}
          - localStatus: status stored in our DB
          - gbStatus: REAL status from GrowthBook (draft|running|stopped) — use this as authoritative
          """)
    public String getExperiment(@P("Experiment id") long id) {
        try {
            log.info("[EXP TOOL] getExperiment id={}", id);
            Experiment e = experimentService.get(id);

            // Fetch real-time status from GrowthBook API
            String gbStatus = gbSync.fetchGbStatus(e);

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("op", "get");
            extra.put("gbStatus", gbStatus != null ? gbStatus : "unknown (no gbExperimentId yet)");
            extra.put("gbExperimentId", e.getGbExperimentId());

            return ok(e, extra);
        } catch (Exception e) {
            log.error("[EXP TOOL] getExperiment FAILED id={}", id, e);
            return error("getExperiment", e);
        }
    }

    @Tool("""
          List experiments by pageKey with paging.
          Returns JSON with ids, localStatus (from DB) and gbStatus (from GrowthBook API).
          gbStatus is the SOURCE OF TRUTH — always prefer it over localStatus.
          gbStatus values: draft | running | stopped | unknown
          """)
    public String listExperiments(
            @P("Page key") String pageKey,
            @P("Page number (0-based)") int page,
            @P("Page size") int size
    ) {
        try {
            log.info("[EXP TOOL] listExperiments pageKey={} page={} size={}", pageKey, page, size);

            var pageable = org.springframework.data.domain.PageRequest.of(
                    Math.max(0, page),
                    Math.min(100, Math.max(1, size))
            );

            var p = experimentService.listByPageKey(pageKey, pageable);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pageKey", pageKey);
            out.put("page", p.getNumber());
            out.put("size", p.getSize());
            out.put("totalElements", p.getTotalElements());
            out.put("totalPages", p.getTotalPages());

            // Enrich each item with real-time GB status
            var items = p.getContent().stream().map(exp -> {
                Map<String, Object> m = brief(exp);
                String gbStatus = gbSync.fetchGbStatus(exp);
                m.put("gbStatus", gbStatus != null ? gbStatus : "unknown");
                m.put("gbExperimentId", exp.getGbExperimentId());
                return m;
            }).toList();
            out.put("items", items);
            out.put("note", "gbStatus is fetched from GrowthBook API — use it as authoritative status");

            return toJson(out);

        } catch (Exception e) {
            log.error("[EXP TOOL] listExperiments FAILED pageKey={}", pageKey, e);
            return error("listExperiments", e);
        }
    }

    // ------------------------------------------------------------
    // A/B VARIANTS
    // ------------------------------------------------------------

    @Tool("""
          Add an A/B variant to an experiment (must be DRAFT or PAUSED).
          Always add at least 2 variants: "control" (weight=0.5, recipeJson={"ops":[]}) and
          one or more treatment variants (weight=0.5, recipeJson with actual ops).
          Weights across all variants MUST sum to 1.0.
          Returns JSON: {ok, variantId, key, name, weight, experimentId}
          """)
    public String addVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key: 'control', 'treatment', 'A', 'B' (immutable after creation)") String key,
            @P("Human-readable name, e.g. 'Control' or 'Yellow Button'") String name,
            @P("Traffic weight 0.0..1.0 (e.g. 0.5 for 50%). All variants must sum to 1.0") double weight,
            @P("Recipe JSON for this variant. Control = '{\"ops\":[]}'. Treatment = recipe with actual ops.") String recipeJson,
            @P(value = "Sort order (0=first, optional)", required = false) Integer sortOrder
    ) {
        try {
            log.info("[EXP TOOL] addVariant experimentId={} key={} weight={}", experimentId, key, weight);

            validateJsonObject(recipeJson, "recipeJson");

            AddVariantRequest req = new AddVariantRequest();
            req.setKey(key);
            req.setName(name);
            req.setWeight(weight);
            req.setRecipeJson(recipeJson);
            req.setSortOrder(sortOrder);

            ExperimentVariant saved = experimentService.addVariant(experimentId, req, ACTOR);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("variantId", saved.getId());
            out.put("experimentId", experimentId);
            out.put("key", saved.getKey());
            out.put("name", saved.getName());
            out.put("weight", saved.getWeight());
            out.put("sortOrder", saved.getSortOrder());
            return toJson(out);

        } catch (Exception e) {
            log.error("[EXP TOOL] addVariant FAILED experimentId={} key={}", experimentId, key, e);
            return error("addVariant", e);
        }
    }

    @Tool("""
          List all A/B variants for an experiment.
          Returns JSON: {ok, experimentId, variants:[{id, key, name, weight, sortOrder}]}
          """)
    public String listVariants(@P("Experiment id") long experimentId) {
        try {
            log.info("[EXP TOOL] listVariants experimentId={}", experimentId);
            List<ExperimentVariant> variants = experimentService.getVariants(experimentId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("experimentId", experimentId);
            out.put("count", variants.size());
            out.put("variants", variants.stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.getId());
                m.put("key", v.getKey());
                m.put("name", v.getName());
                m.put("weight", v.getWeight());
                m.put("sortOrder", v.getSortOrder());
                return m;
            }).toList());
            return toJson(out);

        } catch (Exception e) {
            log.error("[EXP TOOL] listVariants FAILED experimentId={}", experimentId, e);
            return error("listVariants", e);
        }
    }

    // ------------------------------------------------------------
    // STATISTICS
    // ------------------------------------------------------------

    @Tool("""
          Get A/B statistics for an experiment: CTR per variant, Z-test significance, uplift.
          Use this to decide if an experiment has a winner.
          Returns JSON with:
            - variants[]: variantKey, views, clicks, conversions, ctr (%), conversionRate (%)
            - zScore, pValue, significant (true when p<0.05)
            - relativeUpliftPercent: CTR uplift of treatment vs control
            - summary: human-readable conclusion
          Note: significance requires ≥30 views per variant.
          """)
    public String getExperimentStats(@P("Experiment id") long experimentId) {
        try {
            log.info("[EXP TOOL] getExperimentStats experimentId={}", experimentId);

            ExperimentStatsResponse stats = statisticsService.getStats(experimentId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("experimentId", stats.getExperimentId());
            out.put("experimentKey", stats.getExperimentKey());
            out.put("featureKey", stats.getFeatureKey());
            out.put("status", stats.getStatus());
            out.put("startedAt", stats.getStartedAt() != null ? stats.getStartedAt().toString() : null);

            out.put("variants", stats.getVariants().stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("variantKey", v.getVariantKey());
                m.put("variantName", v.getVariantName());
                m.put("weight", v.getWeight());
                m.put("views", v.getViews());
                m.put("clicks", v.getClicks());
                m.put("conversions", v.getConversions());
                m.put("uniqueUsers", v.getUniqueUsers());
                m.put("ctr", v.getCtr() != null ? round2(v.getCtr()) : null);
                m.put("conversionRate", v.getConversionRate() != null ? round2(v.getConversionRate()) : null);
                return m;
            }).toList());

            out.put("zScore", stats.getZScore() != null ? round3(stats.getZScore()) : null);
            out.put("pValue", stats.getPValue() != null ? round4(stats.getPValue()) : null);
            out.put("significant", stats.getSignificant());
            out.put("relativeUpliftPercent",
                    stats.getRelativeUpliftPercent() != null ? round2(stats.getRelativeUpliftPercent()) : null);
            out.put("summary", stats.getSummary());

            return toJson(out);

        } catch (Exception e) {
            log.error("[EXP TOOL] getExperimentStats FAILED experimentId={}", experimentId, e);
            return error("getExperimentStats", e);
        }
    }

    // ------------------------------------------------------------
    // INTERNAL HELPERS
    // ------------------------------------------------------------

    private Map<String, Object> brief(Experiment e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("localStatus", e.getStatus() == null ? null : e.getStatus().name());
        m.put("pageKey", e.getPageKey());
        m.put("key", e.getKey());
        m.put("featureKey", e.getFeatureKey());
        m.put("title", e.getTitle());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    private String ok(Experiment e, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", e.getId());
        out.put("localStatus", e.getStatus() == null ? null : e.getStatus().name());
        out.put("pageKey", e.getPageKey());
        out.put("key", e.getKey());
        out.put("featureKey", e.getFeatureKey());
        out.put("title", e.getTitle());
        out.put("lastError", e.getLastError());
        out.put("startedAt", e.getStartedAt());
        out.put("finishedAt", e.getFinishedAt());
        if (extra != null && !extra.isEmpty()) out.putAll(extra);
        return toJson(out);
    }

    private String error(String op, Exception e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("op", op);
        out.put("error", safeMsg(e));
        return toJson(out);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"json_serialize_failed\"}";
        }
    }

    private void validateJsonObject(String json, String label) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        try {
            var node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " invalid JSON: " + e.getMessage());
        }
    }

    private AutonomyLevel parseAutonomy(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return AutonomyLevel.valueOf(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid autonomyLevel: " + s);
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
