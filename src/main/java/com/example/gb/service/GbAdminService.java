package com.example.gb.service;

import com.example.gb.model.ExperimentVariant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GbAdminService {

    private static final String ENV_DEV = "dev";
    private static final String ENV_PROD = "production";

    private static final String MARKER_NOT_FOUND = "__GB_NOT_FOUND__";

    @Qualifier("gbAdminClient")
    private final WebClient admin;

    private final ObjectMapper om;

    @Value("${growthbook.owner:Owner}")
    private String owner;

    @Value("${growthbook.project:Project}")
    private String project;

    /**
     * string (default): valueType=json але defaultValue/value передаємо як String з JSON
     * native: defaultValue/value передаємо як JsonNode (реальний JSON)
     */
    @Value("${growthbook.json-mode:string}")
    private String jsonMode;

    // -------------------------------------------------------------------------
    // Logging / errors
    // -------------------------------------------------------------------------

    private static String shortBody(String s) {
        if (s == null) return "null";
        String x = s.replaceAll("\\s+", " ").trim();
        return x.length() <= 500 ? x : x.substring(0, 500) + "...(" + x.length() + " chars)";
    }

    private Mono<String> readBody(ClientResponse resp) {
        return resp.bodyToMono(String.class).defaultIfEmpty("");
    }

    private Mono<? extends Throwable> errWithBody(ClientResponse resp, String label) {
        return readBody(resp).flatMap(body -> {
            String msg = label + " " + resp.statusCode() + (body.isBlank() ? "" : (": " + shortBody(body)));
            log.error("❌ {}", msg);
            return Mono.error(new RuntimeException(msg));
        });
    }

    private boolean looksLikeAlreadyExists(int sc, String body) {
        if (sc == 409) return true;
        if (sc == 400 && body != null) {
            String b = body.toLowerCase(Locale.ROOT);
            return b.contains("exist") || b.contains("already");
        }
        return false;
    }

    private boolean looksLikeNotFound(int sc, String body) {
        if (sc == 404) return true;
        if (sc == 400 && body != null) {
            String b = body.toLowerCase(Locale.ROOT);
            return b.contains("could not find a feature with that key") || b.contains("not found");
        }
        return false;
    }

    private boolean isNativeMode() {
        return "native".equalsIgnoreCase(jsonMode);
    }

    /**
     * Повертає значення для GrowthBook valueType=json:
     * - string-mode: String з JSON
     * - native-mode: JsonNode (реальний JSON)
     */
    private Object gbJsonValue(String json) {
        String s = (json == null || json.isBlank()) ? "{}" : json;
        if (isNativeMode()) {
            try {
                JsonNode n = om.readTree(s);
                return (n == null || n.isNull()) ? om.createObjectNode() : n;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
            }
        }
        return s;
    }

    private void putJsonValue(ObjectNode obj, String field, Object value) {
        if (value == null) {
            if (isNativeMode()) obj.set(field, om.createObjectNode());
            else obj.put(field, "{}");
            return;
        }
        if (isNativeMode()) obj.set(field, (JsonNode) value);
        else obj.put(field, String.valueOf(value));
    }

    // -------------------------------------------------------------------------
    // Feature wrapper helpers  ✅ NEW
    // -------------------------------------------------------------------------

    /**
     * GrowthBook часто повертає: { "feature": { ... } }
     * Але інколи може повернути одразу feature object.
     */
    private ObjectNode extractFeatureObject(String raw) {
        try {
            JsonNode rootN = om.readTree(raw);
            if (rootN == null || rootN.isNull() || !rootN.isObject()) {
                throw new IllegalStateException("Invalid GB response: expected JSON object");
            }
            ObjectNode root = (ObjectNode) rootN;

            JsonNode featureN = root.get("feature");
            if (featureN != null && featureN.isObject()) {
                return (ObjectNode) featureN;
            }

            // fallback: якщо API повернув сам feature без wrapper
            if (root.has("environments") && root.get("environments").isObject()) {
                return root;
            }

            throw new IllegalStateException("GB response has no 'feature' object");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GB response: " + e.getMessage(), e);
        }
    }

    private ObjectNode ensureEnvObject(ObjectNode envs, String envName) {
        JsonNode existing = envs.get(envName);
        if (existing != null && existing.isObject()) return (ObjectNode) existing;

        ObjectNode created = om.createObjectNode();
        envs.set(envName, created);
        return created;
    }

    private void ensureEnabled(ObjectNode envObj) {
        if (!envObj.has("enabled")) envObj.put("enabled", false);
    }

    private com.fasterxml.jackson.databind.node.ArrayNode ensureRules(ObjectNode envObj) {
        JsonNode rulesN = envObj.get("rules");
        if (rulesN != null && rulesN.isArray()) {
            return (com.fasterxml.jackson.databind.node.ArrayNode) rulesN;
        }
        var arr = om.createArrayNode();
        envObj.set("rules", arr);
        return arr;
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    public Mono<String> getFeatureRaw(String key) {
        log.debug("➡️ [GB] GET /features/{}", key);
        return admin.get()
                .uri("/features/{id}", key)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB getFeatureRaw error"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.debug("📥 [GB] getFeatureRaw {} OK ({} bytes)", key, json.length()));
    }

    public Mono<String> listFeaturesRaw() {
        log.debug("➡️ [GB] GET /features");
        return admin.get()
                .uri("/features")
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB listFeatures error"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("📦 [GB] listFeatures OK ({} bytes)", json.length()));
    }

    // -------------------------------------------------------------------------
    // CREATE skeleton (create-then-ignore if already exists)
    // -------------------------------------------------------------------------

    public Mono<Void> ensureJsonFeatureSkeleton(String key) {
        log.debug("➡️ [GB] ensureJsonFeatureSkeleton key={}", key);

        return admin.get()
                .uri("/features/{id}", key)
                .exchangeToMono(resp -> readBody(resp).flatMap(body -> {
                    int sc = resp.statusCode().value();

                    if (resp.statusCode().is2xxSuccessful()) {
                        log.debug("✔ [GB] feature exists: {}", key);
                        return Mono.just(body);
                    }

                    if (looksLikeNotFound(sc, body)) {
                        return Mono.error(new IllegalStateException(MARKER_NOT_FOUND));
                    }

                    String msg = "GB ensureSkeleton GET error " + resp.statusCode()
                            + (body.isBlank() ? "" : (": " + shortBody(body)));
                    log.error("❌ {}", msg);
                    return Mono.error(new RuntimeException(msg));
                }))
                .then()
                .onErrorResume(IllegalStateException.class, ex -> {
                    if (!MARKER_NOT_FOUND.equals(ex.getMessage())) return Mono.error(ex);

                    Object empty = gbJsonValue("{}");

                    ObjectNode dev = om.createObjectNode();
                    dev.put("enabled", false); // safe default
                    putJsonValue(dev, "defaultValue", empty);
                    dev.set("rules", om.createArrayNode());

                    ObjectNode prod = om.createObjectNode();
                    prod.put("enabled", false); // safe default
                    putJsonValue(prod, "defaultValue", empty);
                    prod.set("rules", om.createArrayNode());

                    ObjectNode envs = om.createObjectNode();
                    envs.set(ENV_DEV, dev);
                    envs.set(ENV_PROD, prod);

                    ObjectNode createBody = om.createObjectNode();
                    createBody.put("id", key);
                    createBody.put("valueType", "json");
                    putJsonValue(createBody, "defaultValue", empty);
                    createBody.put("project", project);
                    createBody.put("owner", owner);
                    createBody.set("environments", envs);

                    // ✅ деякі версії API очікують wrapper навіть на create — тому обгорнемо
                    ObjectNode wrapper = om.createObjectNode();
                    wrapper.set("feature", createBody);

                    log.info("➕ [GB] create skeleton feature key={}", key);

                    return admin.post()
                            .uri("/features")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(createBody)
                            .exchangeToMono(resp -> readBody(resp).flatMap(body -> {
                                int sc = resp.statusCode().value();
                                if (resp.statusCode().is2xxSuccessful()) {
                                    log.info("✅ [GB] skeleton created key={}", key);
                                    return Mono.empty();
                                }
                                if (looksLikeAlreadyExists(sc, body)) {
                                    log.debug("ℹ️ [GB] skeleton already exists key={}", key);
                                    return Mono.empty();
                                }
                                String msg = "GB ensureSkeleton CREATE error " + resp.statusCode()
                                        + (body.isBlank() ? "" : (": " + shortBody(body)));
                                log.error("❌ {}", msg);
                                return Mono.error(new RuntimeException(msg));
                            }));
                });
    }

    // -------------------------------------------------------------------------
    // UPDATE helper: always send { "feature": ... } ✅ FIX
    // -------------------------------------------------------------------------

    private Mono<Void> postFeatureUpdate(String key, ObjectNode featureUpdate, String label) {
        // IMPORTANT: this GB API expects a "flat" feature object on UPDATE (no {"feature": ...})
        return admin.post()
                .uri("/features/{id}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(featureUpdate)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB " + label + " error"))
                .bodyToMono(String.class)
                .doOnNext(_x -> log.info("✅ [GB] {} OK key={}", label, key))
                .then();
    }


    // -------------------------------------------------------------------------
    // SYNC METHODS
    // -------------------------------------------------------------------------

    public Mono<Void> upsertRecipe(String key, String recipeJson) {
        Object newValue = gbJsonValue(recipeJson);

        log.info("🧩 [GB] upsertRecipe key={} mode={}", key, isNativeMode() ? "native" : "string");

        return ensureJsonFeatureSkeleton(key)
                .then(getFeatureRaw(key))
                .flatMap(raw -> {
                    try {
                        ObjectNode feature = extractFeatureObject(raw);

                        ObjectNode envs = feature.has("environments") && feature.get("environments").isObject()
                                ? (ObjectNode) feature.get("environments")
                                : om.createObjectNode();

                        // DEV
                        ObjectNode dev = ensureEnvObject(envs, ENV_DEV);
                        ensureEnabled(dev);
                        ensureRules(dev);
                        putJsonValue(dev, "defaultValue", newValue);

                        // PROD
                        ObjectNode prod = ensureEnvObject(envs, ENV_PROD);
                        ensureEnabled(prod);
                        ensureRules(prod);
                        putJsonValue(prod, "defaultValue", newValue);

                        // update body = feature object
                        ObjectNode updateFeature = om.createObjectNode();
                        updateFeature.put("project", project);
                        updateFeature.put("owner", owner);
                        putJsonValue(updateFeature, "defaultValue", newValue);
                        updateFeature.set("environments", envs);

                        return postFeatureUpdate(key, updateFeature, "upsertRecipe");
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("upsertRecipe parse/patch failed: " + e.getMessage(), e));
                    }
                });
    }

    public Mono<Void> setFeatureEnabled(String key, boolean enabled, String... targetEnvs) {
        Set<String> envSet = (targetEnvs == null || targetEnvs.length == 0)
                ? Set.of(ENV_DEV, ENV_PROD)
                : new HashSet<>(Arrays.asList(targetEnvs));

        log.info("{} [GB] setFeatureEnabled key={} envs={}",
                enabled ? "🟢" : "🔴", key, envSet);

        return getFeatureRaw(key)
                .flatMap(raw -> {
                    try {
                        ObjectNode feature = extractFeatureObject(raw);

                        if (!feature.has("environments") || !feature.get("environments").isObject()) {
                            return Mono.error(new IllegalStateException("Feature has no environments: " + key));
                        }

                        ObjectNode envs = (ObjectNode) feature.get("environments");

                        for (String env : envSet) {
                            if (env == null || env.isBlank()) continue;
                            if (!envs.has(env) || !envs.get(env).isObject()) continue;

                            ObjectNode envObj = (ObjectNode) envs.get(env);
                            ensureRules(envObj);          // ✅ API requires
                            ensureEnabled(envObj);        // ✅ API requires
                            envObj.put("enabled", enabled);
                        }

                        ObjectNode updateFeature = om.createObjectNode();
                        updateFeature.put("project", project);
                        updateFeature.put("owner", owner);
                        updateFeature.set("environments", envs);

                        return postFeatureUpdate(key, updateFeature, "setFeatureEnabled");
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("setFeatureEnabled parse/patch failed: " + e.getMessage(), e));
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Convenience "raw" methods for tools/agent
    // -------------------------------------------------------------------------

    public Mono<String> upsertJsonFeatureRaw(String key, String recipeJson) {
        return upsertRecipe(key, recipeJson).thenReturn("{\"ok\":true}");
    }

    public Mono<String> upsertJsonFeatureRaw(String key, Map<String, Object> recipe) {
        final String recipeJson;
        try {
            recipeJson = (recipe == null) ? "{}" : om.writeValueAsString(recipe);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Serialize recipe failed: " + e.getMessage(), e));
        }
        return upsertJsonFeatureRaw(key, recipeJson);
    }

    public Mono<String> upsertJsonFeatureRaw(String key, JsonNode recipe) {
        final String recipeJson;
        try {
            recipeJson = (recipe == null || recipe.isNull()) ? "{}" : om.writeValueAsString(recipe);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Serialize recipe failed: " + e.getMessage(), e));
        }
        return upsertJsonFeatureRaw(key, recipeJson);
    }

    // -------------------------------------------------------------------------
    // Tag rule (DEV) - via wrapper ✅ FIX
    // -------------------------------------------------------------------------

    public Mono<Void> upsertJsonFeatureForTagRuleDev(String key, String tag, String recipeJson) {
        Object ruleValue = gbJsonValue(recipeJson);
        String condition = "{\"sessionTag\":{\"$eq\":\"" + tag + "\"}}";

        log.info("🏷️ [GB] upsert tag rule key={} tag={}", key, tag);

        return ensureJsonFeatureSkeleton(key)
                .then(getFeatureRaw(key))
                .flatMap(raw -> {
                    try {
                        ObjectNode feature = extractFeatureObject(raw);

                        ObjectNode envs = feature.has("environments") && feature.get("environments").isObject()
                                ? (ObjectNode) feature.get("environments")
                                : om.createObjectNode();

                        ObjectNode dev = ensureEnvObject(envs, ENV_DEV);
                        ensureEnabled(dev);
                        var rules = ensureRules(dev);

                        ObjectNode rule = om.createObjectNode();
                        rule.put("type", "force");
                        rule.put("enabled", true);
                        rule.put("description", "sessionTag rule");
                        rule.put("condition", condition);
                        putJsonValue(rule, "value", ruleValue);

                        rules.add(rule);
                        dev.set("rules", rules);

                        ObjectNode updateFeature = om.createObjectNode();
                        updateFeature.put("project", project);
                        updateFeature.put("owner", owner);
                        updateFeature.set("environments", envs);

                        return postFeatureUpdate(key, updateFeature, "upsertTagRuleDev");
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("upsertTagRuleDev failed: " + e.getMessage(), e));
                    }
                });
    }

    // -------------------------------------------------------------------------
    // A/B VARIANT RULES
    // -------------------------------------------------------------------------

    /**
     * Replaces all existing rules in production environment with one force rule per variant.
     * Each rule is conditioned on { "__variant__": { "$eq": "variantKey" } }.
     * <p>
     * The bridge sets __variant__ in the GB attributes so GB SDK can also deliver
     * the correct recipe without server-side bridge changes (future).
     * <p>
     * Rule order matches variant sortOrder (control first).
     */
    public Mono<Void> upsertVariantRules(String featureKey, String experimentKey, List<ExperimentVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return Mono.empty();
        }

        log.info("🔀 [GB] upsertVariantRules key={} expKey={} variants={}", featureKey, experimentKey, variants.size());

        return getFeatureRaw(featureKey)
                .flatMap(raw -> {
                    try {
                        ObjectNode feature = extractFeatureObject(raw);

                        ObjectNode envs = feature.has("environments") && feature.get("environments").isObject()
                                ? (ObjectNode) feature.get("environments")
                                : om.createObjectNode();

                        // Replace rules in BOTH environments
                        for (String envName : List.of("production", "dev")) {
                            ObjectNode envObj = ensureEnvObject(envs, envName);
                            ensureEnabled(envObj);

                            var rules = om.createArrayNode();

                            for (ExperimentVariant v : variants) {
                                String condition = "{\"__variant__\":{\"$eq\":\"" + v.getKey() + "\"}}";
                                Object value = gbJsonValue(
                                        v.getRecipeJson() == null ? "{\"ops\":[]}" : v.getRecipeJson()
                                );

                                ObjectNode rule = om.createObjectNode();
                                rule.put("type", "force");
                                rule.put("enabled", true);
                                rule.put("description", "A/B variant: " + v.getKey()
                                        + " (" + String.format("%.0f%%", (v.getWeight() == null ? 0.5 : v.getWeight()) * 100) + ")");
                                rule.put("condition", condition);
                                putJsonValue(rule, "value", value);
                                rules.add(rule);
                            }

                            envObj.set("rules", rules);
                        }

                        ObjectNode updateFeature = om.createObjectNode();
                        updateFeature.put("project", project);
                        updateFeature.put("owner", owner);
                        updateFeature.set("environments", envs);

                        return postFeatureUpdate(featureKey, updateFeature, "upsertVariantRules");
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException(
                                "upsertVariantRules failed for key=" + featureKey + ": " + e.getMessage(), e));
                    }
                });
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    public Mono<String> deleteFeature(String key) {
        log.warn("🗑️ [GB] DELETE /features/{}", key);
        return admin.delete()
                .uri("/features/{id}", key)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB delete error"))
                .bodyToMono(String.class)
                .doOnNext(_x -> log.info("✅ [GB] deleteFeature OK: {}", key));
    }

    // -------------------------------------------------------------------------
    // COMPATIBILITY METHODS
    // -------------------------------------------------------------------------

    public Mono<String> listFeatures() {
        return listFeaturesRaw();
    }

    public Mono<String> upsertJsonFeatureForTagRaw(String key, String tag, String ownerOverride, String recipeJson) {
        return upsertJsonFeatureForTagRuleDev(key, tag, recipeJson)
                .thenReturn("{\"ok\":true}");
    }

    public Mono<String> upsertJsonFeatureForTag(String key, String tag, String ownerOverride, Map<String, Object> recipe) {
        final String recipeJson;
        try {
            recipeJson = (recipe == null) ? "{}" : om.writeValueAsString(recipe);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Serialize recipe failed: " + e.getMessage(), e));
        }
        return upsertJsonFeatureForTagRaw(key, tag, ownerOverride, recipeJson);
    }

    public Mono<String> upsertJsonFeatureAdvancedRaw(String key, String env, String defaultJson, String rulesJson) {
        String target = (env == null || env.isBlank()) ? ENV_PROD : env;

        Mono<Void> step1 = upsertRecipe(key, defaultJson);

        Mono<Void> step2;
        if (ENV_DEV.equalsIgnoreCase(target)) {
            step2 = setFeatureEnabled(key, true, ENV_DEV)
                    .then(setFeatureEnabled(key, false, ENV_PROD));
        } else if (ENV_PROD.equalsIgnoreCase(target) || "production".equalsIgnoreCase(target)) {
            step2 = setFeatureEnabled(key, true, ENV_PROD)
                    .then(setFeatureEnabled(key, false, ENV_DEV));
        } else {
            step2 = Mono.empty();
        }

        return step1.then(step2).thenReturn("{\"ok\":true,\"rulesApplied\":false}");
    }

    public Mono<String> upsertJsonFeatureAdvancedRaw(
            String key,
            String env,
            Map<String, Object> value,
            List<Map<String, Object>> rules
    ) {
        final String valueJson;
        final String rulesJson;

        try {
            valueJson = (value == null) ? "{}" : om.writeValueAsString(value);
            rulesJson = (rules == null) ? "[]" : om.writeValueAsString(rules);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Serialize value/rules failed: " + e.getMessage(), e));
        }

        return upsertJsonFeatureAdvancedRaw(key, env, valueJson, rulesJson);
    }
}
