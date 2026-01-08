package com.example.gb.ai.tools;

import com.example.gb.service.GbAdminService;
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

/**
 * GrowthBook tools.
 * IMPORTANT:
 * - Tool params are primitive/String only.
 * - We pass raw JSON strings to service to avoid Map/generic schema issues and preserve exact payload.
 * - Prefer composite tools (changeTextAndUpsert, cssPropAndUpsert, ...) to avoid manual JSON crafting by LLM.
 */
@Component
@RequiredArgsConstructor
public class GrowthBookTools {

    private static final Logger log = LoggerFactory.getLogger(GrowthBookTools.class);

    private final GbAdminService gbAdmin;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------------
    // COMPOSITE TOOLS (вариант 3) — LLM не торкається JSON руками
    // ---------------------------------------------------------------------

    @Tool("""
          Change text for a selector and immediately upsert into GrowthBook (SAFE).
          Use this instead of crafting recipe JSON.
          """)
    public String changeTextAndUpsert(
            @P("Feature key") String featureId,
            @P("CSS selector") String selector,
            @P("New text") String newText
    ) {
        try {
            log.info("[GB TOOL] changeTextAndUpsert featureId={} selector={} newTextLen={}",
                    featureId, selector, newText == null ? 0 : newText.length());

            String recipeJson = changeText(selector, newText);
            return upsertJsonRecipe(featureId, recipeJson);
        } catch (Exception e) {
            log.error("[GB TOOL] changeTextAndUpsert FAILED featureId={} selector={}", featureId, selector, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Change CSS property for a selector and immediately upsert into GrowthBook (SAFE).
          """)
    public String cssPropAndUpsert(
            @P("Feature key") String featureId,
            @P("CSS selector") String selector,
            @P("CSS property name, e.g. fontSize") String prop,
            @P("CSS value, e.g. 52px") String value
    ) {
        try {
            log.info("[GB TOOL] cssPropAndUpsert featureId={} selector={} prop={} value={}",
                    featureId, selector, prop, value);

            String recipeJson = cssProp(selector, prop, value);
            return upsertJsonRecipe(featureId, recipeJson);
        } catch (Exception e) {
            log.error("[GB TOOL] cssPropAndUpsert FAILED featureId={} selector={}", featureId, selector, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Set attribute for a selector and immediately upsert into GrowthBook (SAFE).
          """)
    public String setAttrAndUpsert(
            @P("Feature key") String featureId,
            @P("CSS selector") String selector,
            @P("Attribute name, e.g. href") String name,
            @P("Attribute value") String value
    ) {
        try {
            log.info("[GB TOOL] setAttrAndUpsert featureId={} selector={} name={} valueLen={}",
                    featureId, selector, name, value == null ? 0 : value.length());

            String recipeJson = setAttr(selector, name, value);
            return upsertJsonRecipe(featureId, recipeJson);
        } catch (Exception e) {
            log.error("[GB TOOL] setAttrAndUpsert FAILED featureId={} selector={}", featureId, selector, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Replace HTML (safe) for a selector and immediately upsert into GrowthBook (SAFE).
          """)
    public String htmlSafeAndUpsert(
            @P("Feature key") String featureId,
            @P("CSS selector") String selector,
            @P("HTML string") String html
    ) {
        try {
            log.info("[GB TOOL] htmlSafeAndUpsert featureId={} selector={} htmlLen={}",
                    featureId, selector, html == null ? 0 : html.length());

            String recipeJson = htmlSafe(selector, html);
            return upsertJsonRecipe(featureId, recipeJson);
        } catch (Exception e) {
            log.error("[GB TOOL] htmlSafeAndUpsert FAILED featureId={} selector={}", featureId, selector, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    // ---------------------------------------------------------------------
    // GB READ
    // ---------------------------------------------------------------------

    @Tool("Get raw JSON of a GrowthBook feature by id (debug/verification).")
    public String getFeatureRaw(@P("Feature id/key in GrowthBook") String featureId) {
        try {
            log.info("[GB TOOL] getFeatureRaw featureId={}", featureId);
            String res = gbAdmin.getFeatureRaw(featureId).blockOptional().orElse("");
            log.info("[GB TOOL] getFeatureRaw OK len={}", res == null ? 0 : res.length());
            return res;
        } catch (Exception e) {
            log.error("[GB TOOL] getFeatureRaw FAILED featureId={}", featureId, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("List features raw JSON (debug).")
    public String listFeaturesRaw() {
        try {
            log.info("[GB TOOL] listFeaturesRaw");
            String res = gbAdmin.listFeatures().blockOptional().orElse("");
            log.info("[GB TOOL] listFeaturesRaw OK len={}", res == null ? 0 : res.length());
            return res;
        } catch (Exception e) {
            log.error("[GB TOOL] listFeaturesRaw FAILED", e);
            return "ERROR: " + safeMsg(e);
        }
    }

    // ---------------------------------------------------------------------
    // UPSERTS (RAW JSON)
    // ---------------------------------------------------------------------

    @Tool("""
          Create or update a JSON feature.
          Args:
          - featureId: feature key
          - recipeJson: JSON object string (e.g. {"ops":[...]}).
          Returns GrowthBook Admin API response string.
          """)
    public String upsertJsonRecipe(
            @P("Feature key") String featureId,
            @P("JSON object string with recipe, e.g. {\"ops\":[...]}") String recipeJson
    ) {
        try {
            log.info("[GB TOOL] upsertJsonRecipe featureId={} recipeLen={}",
                    featureId, recipeJson == null ? 0 : recipeJson.length());

            validateJsonObject(recipeJson, "recipeJson");

            String res = gbAdmin.upsertJsonFeatureRaw(featureId, recipeJson)
                    .blockOptional()
                    .orElse("OK");

            log.info("[GB TOOL] upsertJsonRecipe OK featureId={} resLen={}",
                    featureId, res == null ? 0 : res.length());
            return res;

        } catch (Exception e) {
            log.error("[GB TOOL] upsertJsonRecipe FAILED featureId={} recipeJson={}", featureId, recipeJson, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Create or update a JSON feature for a specific sessionTag in DEV environment.
          Args:
          - featureId: feature key
          - sessionTag: your session tag
          - owner: optional owner label
          - recipeJson: JSON object string
          Returns API response.
          """)
    public String upsertJsonRecipeForTag(
            @P("Feature key") String featureId,
            @P("Session tag") String sessionTag,
            @P(value = "Owner (optional)", required = false) String owner,
            @P("JSON object string with recipe, e.g. {\"ops\":[...]}") String recipeJson
    ) {
        try {
            log.info("[GB TOOL] upsertJsonRecipeForTag featureId={} sessionTag={} owner={} recipeLen={}",
                    featureId, sessionTag, owner, recipeJson == null ? 0 : recipeJson.length());

            validateJsonObject(recipeJson, "recipeJson");

            String res = gbAdmin.upsertJsonFeatureForTagRaw(featureId, sessionTag, owner, recipeJson)
                    .blockOptional()
                    .orElse("OK");

            log.info("[GB TOOL] upsertJsonRecipeForTag OK featureId={} resLen={}",
                    featureId, res == null ? 0 : res.length());
            return res;

        } catch (Exception e) {
            log.error("[GB TOOL] upsertJsonRecipeForTag FAILED featureId={} sessionTag={} recipeJson={}",
                    featureId, sessionTag, recipeJson, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Advanced upsert: updates a JSON feature in chosen environment with optional rollout/QA rules.
          Args:
          - key: feature key
          - env: dev|production
          - defaultJson: JSON object string
          - rulesJson: optional JSON array string of rule objects (e.g. [{"condition":...}, ...])
          Returns API response.
          """)
    public String upsertJsonFeatureAdvanced(
            @P("Feature key") String key,
            @P("Environment: dev or production") String env,
            @P("Default JSON object string") String defaultJson,
            @P(value = "Rules JSON array string (optional)", required = false) String rulesJson
    ) {
        try {
            log.info("[GB TOOL] upsertJsonFeatureAdvanced key={} env={} defaultLen={} rulesLen={}",
                    key, env,
                    defaultJson == null ? 0 : defaultJson.length(),
                    rulesJson == null ? 0 : rulesJson.length());

            validateJsonObject(defaultJson, "defaultJson");
            if (rulesJson != null && !rulesJson.isBlank()) validateJsonArray(rulesJson, "rulesJson");

            String res = gbAdmin.upsertJsonFeatureAdvancedRaw(key, env, defaultJson, rulesJson)
                    .blockOptional()
                    .orElse("OK");

            log.info("[GB TOOL] upsertJsonFeatureAdvanced OK key={} resLen={}",
                    key, res == null ? 0 : res.length());
            return res;

        } catch (Exception e) {
            log.error("[GB TOOL] upsertJsonFeatureAdvanced FAILED key={} env={}", key, env, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    // ---------------------------------------------------------------------
    // RECIPE BUILDERS (SAFE) — повертають JSON string у форматі dom-bridge
    // ---------------------------------------------------------------------

    @Tool("""
          Build a minimal text replacement recipe JSON.
          Returns: {"ops":[{"action":"text","selector":"...","value":"..."}]}
          """)
    public String changeText(
            @P("CSS selector") String selector,
            @P("New text value") String newText
    ) {
        Map<String, Object> op = baseOp("text", selector);
        op.put("value", newText == null ? "" : newText);
        return toJson(Map.of("ops", List.of(op)));
    }

    @Tool("""
          Build a minimal HTML replacement recipe JSON (sanitized on client).
          Returns: {"ops":[{"action":"html:safe","selector":"...","value":"..."}]}
          """)
    public String htmlSafe(
            @P("CSS selector") String selector,
            @P("HTML string") String html
    ) {
        Map<String, Object> op = baseOp("html:safe", selector);
        op.put("value", html == null ? "" : html);
        return toJson(Map.of("ops", List.of(op)));
    }

    @Tool("""
          Build a minimal CSS change recipe JSON.
          Returns: {"ops":[{"action":"css","selector":"...","prop":"...","value":"..."}]}
          """)
    public String cssProp(
            @P("CSS selector") String selector,
            @P("CSS property name") String prop,
            @P("CSS value") String value
    ) {
        Map<String, Object> op = baseOp("css", selector);
        op.put("prop", prop == null ? "" : prop);
        op.put("value", value == null ? "" : value);
        return toJson(Map.of("ops", List.of(op)));
    }

    @Tool("""
          Build a minimal attribute change recipe JSON.
          Returns: {"ops":[{"action":"attr","selector":"...","name":"...","value":"..."}]}
          """)
    public String setAttr(
            @P("CSS selector") String selector,
            @P("Attribute name") String name,
            @P("Attribute value") String value
    ) {
        Map<String, Object> op = baseOp("attr", selector);
        op.put("name", name == null ? "" : name);
        op.put("value", value == null ? "" : value);
        return toJson(Map.of("ops", List.of(op)));
    }

    @Tool("""
          Build a recipe from multiple ops.
          opsJson: JSON array string of op objects (each must contain action+selector and fields).
          Returns: {"ops":[...]}
          """)
    public String recipeFromOps(@P("JSON array string of op objects") String opsJson) {
        try {
            validateJsonArray(opsJson, "opsJson");
            // We don't parse into Map in schema; internal parsing is OK:
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ops = objectMapper.readValue(opsJson, List.class);
            return toJson(Map.of("ops", ops == null ? List.of() : ops));
        } catch (Exception e) {
            return "ERROR: " + safeMsg(e);
        }
    }

    // ---------------------------------------------------------------------
    // INTERNAL HELPERS
    // ---------------------------------------------------------------------

    private Map<String, Object> baseOp(String action, String selector) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("action", action == null ? "" : action);
        op.put("selector", selector == null ? "" : selector);
        return op;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "ERROR: " + safeMsg(e);
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

    private void validateJsonArray(String json, String label) {
        if (json == null || json.isBlank()) return;
        try {
            var node = objectMapper.readTree(json);
            if (!node.isArray()) {
                throw new IllegalArgumentException(label + " must be a JSON array");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " invalid JSON: " + e.getMessage());
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
