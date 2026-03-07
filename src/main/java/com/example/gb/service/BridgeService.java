package com.example.gb.service;

import com.example.gb.controller.BridgeController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Debug/inspection endpoint: returns GrowthBook feature defaultValue as DOM recipe ops.
 * All A/B variant assignment is handled exclusively by the GrowthBook SDK (client-side).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BridgeService {

    private final GbAdminService gb;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * Reads GrowthBook feature defaultValue for each featureId and
     * returns merged DOM recipe ops. No A/B assignment happens here —
     * the GB SDK handles all experiment bucketing on the client.
     */
    public BridgeController.RecipeResponse buildRecipe(
            String url,
            List<String> featureIds
    ) {
        List<Map<String, Object>> mergedOps = new ArrayList<>();

        for (String fid : featureIds) {
            GBFeature feature = fetchFeature(fid);
            if (feature == null) continue;

            if (!feature.devEnabled()) {
                log.debug("↷ feature '{}' dev.disabled → skip", fid);
                continue;
            }

            Map<String, Object> recipe = safeParseJson(feature.defaultValue());
            List<Map<String, Object>> ops = getOps(recipe);

            if (!ops.isEmpty()) {
                mergedOps.addAll(ops);
                log.debug("➕ {} ops from feature defaultValue '{}'", ops.size(), fid);
            }
        }

        String responseFeatureId = featureIds.size() == 1 ? featureIds.get(0) : "merged";
        return new BridgeController.RecipeResponse(
                responseFeatureId,
                null,
                null,
                new BridgeController.Recipe(mergedOps)
        );
    }

    // ===== helpers =====

    private Map<String, Object> safeParseJson(String s) {
        try { return om.readValue(s, Map.class); }
        catch (Exception e) {
            log.warn("⚠️ bad JSON recipe, return empty: {}", e.getMessage());
            return Map.of("ops", List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getOps(Map<String, Object> recipe) {
        Object v = recipe == null ? null : recipe.get("ops");
        if (v instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private GBFeature fetchFeature(String id) {
        try {
            String json = gb.getFeatureRaw(id).blockOptional().orElse(null);
            if (json == null) return null;
            JsonNode root = om.readTree(json);
            return parseFeature(root);
        } catch (Exception e) {
            log.error("❌ fetchFeature {} failed: {}", id, e.getMessage());
            return null;
        }
    }

    private GBFeature parseFeature(JsonNode n) {
        String defaultValue = val(n, "defaultValue");
        JsonNode dev = n.path("environments").path("dev");
        boolean devEnabled = dev.path("enabled").asBoolean(false);
        return new GBFeature(defaultValue, devEnabled);
    }

    private String val(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isTextual()) return v.asText();
        if (!v.isMissingNode() && !v.isNull()) {
            try { return om.writeValueAsString(om.convertValue(v, Object.class)); }
            catch (Exception ignored) {}
        }
        return "{}";
    }

    record GBFeature(String defaultValue, boolean devEnabled) {}
}
