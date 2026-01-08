package com.example.gb.service;

import com.example.gb.controller.BridgeController;
import com.example.gb.service.GbAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BridgeService {

    private final GbAdminService gb;              // клієнт Admin API
    private final ObjectMapper om = new ObjectMapper();

    /**
     * Будує зведений рецепт для (session,url) з набору featureIds.
     * - якщо передано tag → беремо rule з condition.sessionTag==tag (підтримуємо і object, і string JSON-logic);
     * - якщо percent!=null → детерміноване бакетування по session (rollout);
     * - інакше беремо defaultValue.
     */
    public BridgeController.RecipeResponse buildRecipe(
            String url,
            String session,
            List<String> featureIds,
            String tag,
            Integer percent // 0..100
    ) {
        List<Map<String, Object>> mergedOps = new ArrayList<>();

        String chosenVariant = null;   // для метаданих відповіді
        String chosenExperiment = null;

        for (String fid : featureIds) {
            var feature = fetchFeature(fid);
            if (feature == null) continue;

            if (!feature.isDevEnabled()) {
                log.info("↷ feature '{}' dev.disabled → skip", fid);
                continue;
            }

            // 1) Якщо є tag і у DEV є правило для tag → беремо його
            Optional<String> ruleValue = feature.findRuleValueBySessionTag(tag, om);

            // 2) Якщо не знайшли rule і заданий percent → A/B по session
            if (ruleValue.isEmpty() && percent != null) {
                int bucket = bucket100(session + ":" + fid);
                boolean in = bucket < Math.max(0, Math.min(100, percent));
                chosenVariant = in ? "B" : "A";
                chosenExperiment = fid + "_rollout_" + percent;

                if (!in) {
                    ruleValue = Optional.empty(); // A → дефолт
                } else {
                    // B → тут теж дефолт (або роби окрему фічу для B і включай її у features=)
                }
            }

            String json = ruleValue.orElse(feature.getDefaultValue());
            Map<String, Object> recipe = safeParseJson(json);
            List<Map<String, Object>> ops = getOps(recipe);

            if (!ops.isEmpty()) {
                mergedOps.addAll(ops);
                log.debug("➕ merge {} ops from feature {}", ops.size(), fid);
            }
        }

        return new BridgeController.RecipeResponse(
                featureIds.size() == 1 ? featureIds.get(0) : "merged",
                chosenExperiment,
                chosenVariant,
                new BridgeController.Recipe(mergedOps)
        );
    }

    // ===== helpers =====

    private static int bucket100(String key) {
        return (Math.abs(murmur32(key)) % 100);
    }

    private static int murmur32(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int c1 = 0xcc9e2d51, c2 = 0x1b873593, r1 = 15, r2 = 13, m = 5, n = 0xe6546b64;
        int hash = 0;
        int len = data.length;
        for (int i = 0; i + 4 <= len; i += 4) {
            int k = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) | ((data[i + 2] & 0xff) << 16) | ((data[i + 3] & 0xff) << 24);
            k *= c1; k = (k << r1) | (k >>> (32 - r1)); k *= c2;
            hash ^= k; hash = (hash << r2) | (hash >>> (32 - r2)); hash = hash * m + n;
        }
        int rem = len & 3;
        int k = 0;
        if (rem == 3) k = (data[len - 3] & 0xff) | ((data[len - 2] & 0xff) << 8) | ((data[len - 1] & 0xff) << 16);
        else if (rem == 2) k = (data[len - 2] & 0xff) | ((data[len - 1] & 0xff) << 8);
        else if (rem == 1) k = (data[len - 1] & 0xff);
        if (rem > 0) {
            k *= c1; k = (k << r1) | (k >>> (32 - r1)); k *= c2; hash ^= k;
        }
        hash ^= len;
        hash ^= (hash >>> 16); hash *= 0x85ebca6b; hash ^= (hash >>> 13); hash *= 0xc2b2ae35; hash ^= (hash >>> 16);
        return hash;
    }

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
        String id = val(n, "id");
        String defaultValue = val(n, "defaultValue");
        JsonNode envs = n.path("environments");
        JsonNode dev = envs.path("dev");
        boolean devEnabled = dev.path("enabled").asBoolean(false);

        List<JsonNode> rules = new ArrayList<>();
        if (dev.has("rules") && dev.get("rules").isArray()) {
            dev.get("rules").forEach(rules::add);
        }
        return new GBFeature(id, defaultValue, devEnabled, rules);
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

    // ===== внутрішня модель витягнутої фічі =====
    @Value
    static class GBFeature {
        String id;
        String defaultValue;          // JSON string
        boolean devEnabled;
        List<JsonNode> devRules;      // сирі ноди правил у DEV

        /**
         * Повертає recipes.value (рядок JSON) для правила з потрібним sessionTag.
         * Підтримує обидва формати condition:
         *  - об'єкт: {"sessionTag":"QA123"} або {"sessionTag":{"$eq":"QA123"}}
         *  - РЯДОК: "{\"sessionTag\":{\"$eq\":\"QA123\"}}"
         */
        Optional<String> findRuleValueBySessionTag(String tag, ObjectMapper om) {
            if (tag == null || tag.isBlank()) return Optional.empty();
            for (JsonNode r : devRules) {
                String type = r.path("type").asText("");
                if (!"force".equals(type)) continue;

                // value/force
                JsonNode v = r.get("value");
                if (v == null || v.isNull()) v = r.get("force");
                if (v == null || !v.isTextual()) continue; // очікуємо рядок JSON

                // condition може бути object або string
                JsonNode condNode = r.get("condition");
                JsonNode condParsed = null;

                if (condNode != null && !condNode.isNull()) {
                    if (condNode.isTextual()) {
                        // рядок JSON-logic → парсимо
                        try { condParsed = om.readTree(condNode.asText()); }
                        catch (Exception ignored) {}
                    } else if (condNode.isObject()) {
                        condParsed = condNode;
                    }
                }

                if (condParsed != null) {
                    // допускаємо 2 форми:
                    // 1) {"sessionTag":"QA123"}
                    // 2) {"sessionTag":{"$eq":"QA123"}}
                    String direct = condParsed.path("sessionTag").isTextual()
                            ? condParsed.path("sessionTag").asText(null)
                            : null;

                    String eq = null;
                    JsonNode st = condParsed.path("sessionTag");
                    if (st.isObject()) eq = st.path("$eq").asText(null);

                    if (Objects.equals(tag, direct) || Objects.equals(tag, eq)) {
                        return Optional.of(v.asText());
                    }
                }
            }
            return Optional.empty();
        }
    }
}
