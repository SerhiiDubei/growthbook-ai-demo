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

    private static final String ACTOR = "agent";

    private final ExperimentService experimentService;
    private final GrowthBookSyncService gbSync;
    private final StatisticsService statisticsService;
    private final ObjectMapper objectMapper;
    private final RecipeTools recipeTools;

    // ------------------------------------------------------------
    // CREATE
    // ------------------------------------------------------------

    @Tool("""
          Create an experiment in DRAFT status and sync skeleton to GrowthBook (disabled).
          The experiment is created with an empty recipe {"ops":[]} — variants are added
          separately via RecipeTools (addControlVariant, addSwapVariant, addReorderVariant, etc.).
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
            @P(value = "Notes (optional)", required = false) String notes,
            @P(value = "Autonomy level, e.g. AGENT_FULL (optional)", required = false) String autonomyLevel
    ) {
        try {
            log.info("[EXP TOOL] createExperiment pageKey={} key={} featureKey={}", pageKey, key, featureKey);

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
            req.setRecipeJson("{\"ops\":[]}");

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

    /**
     * Internal-only helper: update raw recipeJson.
     * The @Tool annotation is intentionally removed — agents MUST use RecipeTools
     * (addControlVariant, addSwapVariant, etc.) instead of writing recipeJson manually.
     */
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
    // A/B VARIANTS (delegate to RecipeTools — semantic wrappers, no raw JSON)
    // ------------------------------------------------------------

    @Tool("""
          Add CONTROL variant to an experiment — no DOM changes, original layout.
          ALWAYS add first before any treatment. weight e.g. 0.5 for 50%%.
          """)
    public String addControlVariant(
            @P("Experiment id") long experimentId,
            @P("Traffic weight, e.g. 0.5") double weight
    ) {
        return recipeTools.addControlVariant(experimentId, weight);
    }

    @Tool("""
          Add SWAP treatment variant — exchange position of two elements.
          selector1, selector2 from DOM inventory. weight e.g. 0.5.
          """)
    public String addSwapVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Swapped buttons'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector first element (from inventory)") String selector1,
            @P("CSS selector second element (from inventory)") String selector2
    ) {
        return recipeTools.addSwapVariant(experimentId, variantKey, variantName, weight, selector1, selector2);
    }

    @Tool("""
          Add REORDER treatment variant — reorder 3+ direct children inside a container.
          containerSelector: CSS selector of the parent. orderedSelectors: comma-separated child selectors.
          weight e.g. 0.5.
          """)
    public String addReorderVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Stats first layout'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the container element (from inventory)") String containerSelector,
            @P("Comma-separated CSS selectors of children in desired order") String orderedSelectors
    ) {
        return recipeTools.addReorderVariant(experimentId, variantKey, variantName, weight, containerSelector, orderedSelectors);
    }

    @Tool("""
          Add TEXT treatment variant — change plain text content of ONE element.
          Use when: "change heading", "rename button", "test different copy".
          selector from DOM inventory. weight e.g. 0.5.
          """)
    public String addTextVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'New CTA copy'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("New text content") String newText
    ) {
        return recipeTools.addTextVariant(experimentId, variantKey, variantName, weight, selector, newText);
    }

    @Tool("""
          Add STYLE treatment variant — change ONE CSS property of ONE element.
          cssProperty: kebab-case, e.g. "background-color". cssValue: e.g. "#ff0000".
          weight e.g. 0.5.
          """)
    public String addStyleVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Red button'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("CSS property in kebab-case, e.g. 'background-color'") String cssProperty,
            @P("CSS value, e.g. '#ff0000' or '18px'") String cssValue
    ) {
        return recipeTools.addStyleVariant(experimentId, variantKey, variantName, weight, selector, cssProperty, cssValue);
    }

    @Tool("""
          Add MULTI-STYLE treatment variant — change MULTIPLE CSS properties at once on ONE element.
          styleJson: camelCase JSON, e.g. {"backgroundColor":"#f00","color":"#fff","borderRadius":"8px"}.
          weight e.g. 0.5.
          """)
    public String addMultiStyleVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Bold red CTA'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("JSON object of camelCase CSS properties, e.g. {\"backgroundColor\":\"#f00\",\"color\":\"#fff\"}") String styleJson
    ) {
        return recipeTools.addMultiStyleVariant(experimentId, variantKey, variantName, weight, selector, styleJson);
    }

    @Tool("""
          Add HTML treatment variant — replace inner HTML of ONE element.
          Use when: "add icon to label", "change HTML structure inside element".
          selector from DOM inventory. weight e.g. 0.5.
          """)
    public String addHtmlVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Bold CTA label'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("New inner HTML content") String htmlContent
    ) {
        return recipeTools.addHtmlVariant(experimentId, variantKey, variantName, weight, selector, htmlContent);
    }

    @Tool("""
          Add ATTR treatment variant — change an HTML attribute of ONE element.
          attributeName: href | src | alt | title | aria-label | role | target | data-variant | data-test | rel.
          weight e.g. 0.5.
          """)
    public String addAttrVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'New link target'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("Attribute name: href | src | alt | title | aria-label | role | target | data-variant | data-test | rel") String attributeName,
            @P("New attribute value") String attributeValue
    ) {
        return recipeTools.addAttrVariant(experimentId, variantKey, variantName, weight, selector, attributeName, attributeValue);
    }

    @Tool("""
          Add IMAGE treatment variant — change the src of an <img> element.
          Use when: "test different image", "change hero photo", "swap banner image".
          selector from DOM inventory. weight e.g. 0.5.
          """)
    public String addImageVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Product photo B'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the <img> element (from inventory)") String selector,
            @P("New image URL, e.g. '/images/hero-b.jpg'") String imageSrc
    ) {
        return recipeTools.addImageVariant(experimentId, variantKey, variantName, weight, selector, imageSrc);
    }

    @Tool("""
          Add CLASS-ADD treatment variant — add a CSS class to ONE element.
          Use when: "highlight this section", "activate a pre-existing style via class".
          selector from DOM inventory. weight e.g. 0.5.
          """)
    public String addClassAddVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'Highlighted CTA'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("CSS class name to ADD (without dot), e.g. 'featured'") String className
    ) {
        return recipeTools.addClassAddVariant(experimentId, variantKey, variantName, weight, selector, className);
    }

    @Tool("""
          Add CLASS-REMOVE treatment variant — remove a CSS class from ONE element.
          Use when: "remove highlight", "deactivate a style class".
          selector from DOM inventory. weight e.g. 0.5.
          """)
    public String addClassRemoveVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'No highlight'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("CSS class name to REMOVE (without dot), e.g. 'featured'") String className
    ) {
        return recipeTools.addClassRemoveVariant(experimentId, variantKey, variantName, weight, selector, className);
    }

    @Tool("""
          Add HIDE treatment variant — hide/remove ONE element from the page entirely.
          Use when: "hide the promo banner", "remove section", "test without element".
          selector from DOM inventory. weight e.g. 0.5.
          """)
    public String addHideVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human name, e.g. 'No promo banner'") String variantName,
            @P("Traffic weight, e.g. 0.5") double weight,
            @P("CSS selector of the element to hide (from inventory)") String selector
    ) {
        return recipeTools.addHideVariant(experimentId, variantKey, variantName, weight, selector);
    }

    /**
     * Low-level addVariant — not exposed to agent (no @Tool).
     * Agent must use addControlVariant, addSwapVariant above.
     */
    public String addVariant(
            long experimentId, String key, String name, double weight,
            String recipeJson, Integer sortOrder
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
