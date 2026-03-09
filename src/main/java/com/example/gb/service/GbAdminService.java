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
        try {
            log.info("📤 [GB] {} POST /features/{} body={}",
                    label, key, shortBody(om.writeValueAsString(featureUpdate)));
        } catch (Exception _e) { /* ignore serialization error in log */ }
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
    // A/B VARIANT RULES  (experiment rule — GB SDK does bucketing client-side)
    // -------------------------------------------------------------------------

    /**
     * Replaces feature rules with ONE native GB "experiment" type rule per environment.
     * <p>
     * GB SDK reads this rule, hashes gb.attributes.id (= sessionTag) and assigns
     * the variant deterministically — no server round-trip per pageload.
     * Each recipe value has {@code __variantKey__} injected so the bridge can
     * identify the variant name without relying on numeric variation index.
     */
    public Mono<Void> upsertVariantRules(String featureKey, String experimentKey, List<ExperimentVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return Mono.empty();
        }

        log.info("🔀 [GB] upsertExperimentRule key={} expKey={} variants={}", featureKey, experimentKey, variants.size());

        return getFeatureRaw(featureKey)
                .flatMap(raw -> {
                    try {
                        ObjectNode feature = extractFeatureObject(raw);

                        ObjectNode envs = feature.has("environments") && feature.get("environments").isObject()
                                ? (ObjectNode) feature.get("environments")
                                : om.createObjectNode();

                        for (String envName : List.of("production", "dev")) {
                            ObjectNode envObj = ensureEnvObject(envs, envName);
                            ensureEnabled(envObj);
                            var rules = om.createArrayNode();
                            // deep copy: each env gets its own independent rule node
                            rules.add(buildExperimentRule(experimentKey, variants));
                            envObj.set("rules", rules);
                        }

                        ObjectNode updateFeature = om.createObjectNode();
                        updateFeature.put("project", project);
                        updateFeature.put("owner", owner);
                        updateFeature.set("environments", envs);

                        return postFeatureUpdate(featureKey, updateFeature, "upsertExperimentRule");
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException(
                                "upsertExperimentRule failed for key=" + featureKey + ": " + e.getMessage(), e));
                    }
                });
    }

    /**
     * Builds a single GB "experiment" rule with one value entry per variant.
     * hashAttribute = "id" → GB SDK uses gb.attributes.id for bucketing.
     * Returns a fresh deep-copy each call so it is safe to add to multiple ArrayNodes.
     */
    private ObjectNode buildExperimentRule(String experimentKey, List<ExperimentVariant> variants) {
        ObjectNode rule = om.createObjectNode();
        rule.put("type", "experiment");
        rule.put("trackingKey", experimentKey);
        rule.put("hashAttribute", "id");
        rule.put("coverage", 1);      // integer 1 = 100%
        rule.put("condition", "{}");  // required by some GB versions

        var values = om.createArrayNode();
        for (ExperimentVariant v : variants) {
            String enrichedRecipe = injectVariantKey(v.getRecipeJson(), v.getKey());
            ObjectNode valueItem = om.createObjectNode();
            putJsonValue(valueItem, "value", gbJsonValue(enrichedRecipe));
            valueItem.put("weight", v.getWeight() == null ? 0.5 : v.getWeight());
            values.add(valueItem);
        }
        rule.set("values", values);
        return rule;
    }

    /**
     * Injects {@code __variantKey__} into the recipe JSON so the frontend bridge
     * can identify the assigned variant by name, not by numeric variation index.
     */
    private String injectVariantKey(String recipeJson, String variantKey) {
        try {
            String s = (recipeJson == null || recipeJson.isBlank()) ? "{}" : recipeJson;
            ObjectNode recipe = (ObjectNode) om.readTree(s);
            recipe.put("__variantKey__", variantKey);
            return om.writeValueAsString(recipe);
        } catch (Exception e) {
            log.warn("[GB] Failed to inject __variantKey__ for variant={}: {}", variantKey, e.getMessage());
            return recipeJson == null ? "{}" : recipeJson;
        }
    }

    // -------------------------------------------------------------------------
    // NATIVE GB EXPERIMENTS  (POST /api/v1/experiments)
    // -------------------------------------------------------------------------

    /**
     * Result of creating/updating a native GB Experiment.
     * Contains the GB experiment ID and a mapping of our variantKey → GB variationId.
     */
    public record NativeExperimentResult(
            String gbExperimentId,
            Map<String, String> variantKeyToVariationId   // e.g. "control" → "var_abc123"
    ) {}

    /**
     * Creates or updates a native GrowthBook Experiment entity.
     * <p>
     * If {@code existingGbExpId} is null → POST /api/v1/experiments (create).
     * Otherwise              → POST /api/v1/experiments/{id} (update).
     * <p>
     * Returns {@link NativeExperimentResult} with the GB experiment ID and
     * per-variant mapping variantKey → gbVariationId.
     */
    public Mono<NativeExperimentResult> ensureNativeExperiment(
            String existingGbExpId,
            String trackingKey,
            String name,
            String description,
            String status,
            List<ExperimentVariant> variants) {

        ObjectNode body = buildNativeExperimentBody(trackingKey, name, description, status, variants);

        log.info("🧪 [GB] {} native experiment trackingKey={} status={}",
                existingGbExpId == null ? "CREATE" : "UPDATE", trackingKey, status);

        Mono<String> request;
        if (existingGbExpId == null || existingGbExpId.isBlank()) {
            // CREATE: response contains full experiment with variation IDs
            request = admin.post()
                    .uri("/experiments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB createNativeExp error"))
                    .bodyToMono(String.class);
        } else {
            // UPDATE: response may not contain variation IDs → do GET afterwards to get fresh data
            request = admin.post()
                    .uri("/experiments/{id}", existingGbExpId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB updateNativeExp error"))
                    .bodyToMono(String.class)
                    .flatMap(ignored -> {
                        log.debug("📥 [GB] UPDATE done, fetching fresh experiment data id={}", existingGbExpId);
                        return getNativeExperimentRaw(existingGbExpId);
                    });
        }

        return request.map(raw -> parseNativeExperimentResult(raw, variants));
    }

    private ObjectNode buildNativeExperimentBody(
            String trackingKey, String name, String description,
            String status, List<ExperimentVariant> variants) {

        ObjectNode body = om.createObjectNode();
        body.put("trackingKey", trackingKey);
        body.put("name", name == null || name.isBlank() ? trackingKey : name);
        if (description != null && !description.isBlank()) body.put("description", description);
        body.put("project", project);
        body.put("hashAttribute", "id");
        body.put("status", mapStatus(status));
        body.put("assignmentQueryId", "");  // required by GB API; empty = no datasource

        var variationsArr = om.createArrayNode();
        var weightsArr = om.createArrayNode();

        for (int i = 0; i < variants.size(); i++) {
            ExperimentVariant v = variants.get(i);
            ObjectNode variation = om.createObjectNode();
            variation.put("key", String.valueOf(i));        // "0", "1", "2"...
            variation.put("name", v.getName() != null ? v.getName() : v.getKey());
            variation.set("screenshots", om.createArrayNode());
            variationsArr.add(variation);
            weightsArr.add(v.getWeight() == null ? 0.5 : v.getWeight());
        }
        body.set("variations", variationsArr);

        // Build phase
        ObjectNode phase = om.createObjectNode();
        phase.put("name", "Main");
        phase.put("dateStarted", java.time.Instant.now().toString());
        phase.put("coverage", 1);
        phase.set("variationWeights", weightsArr.deepCopy());
        phase.put("condition", "{}");
        ObjectNode ns = om.createObjectNode();
        ns.put("enabled", false);
        ns.put("namespaceId", "");  // required by GB API
        ns.put("name", "");
        ns.set("range", om.createArrayNode().add(0).add(1));
        phase.set("namespace", ns);

        body.set("phases", om.createArrayNode().add(phase));

        log.debug("📤 [GB] nativeExperiment body={}", shortBody(body.toString()));
        return body;
    }

    private String mapStatus(String status) {
        if (status == null) return "draft";
        return switch (status.toUpperCase()) {
            case "ACTIVE"                  -> "running";
            case "FINISHED", "PAUSED",
                 "FAILED", "STOPPED"       -> "stopped";
            case "RUNNING"                 -> "running";  // pass-through if already GB format
            default                        -> "draft";
        };
    }

    private NativeExperimentResult parseNativeExperimentResult(
            String raw, List<ExperimentVariant> variants) {
        try {
            JsonNode root = om.readTree(raw);
            JsonNode expNode = root.has("experiment") ? root.get("experiment") : root;

            String gbExpId = expNode.path("id").asText(null);
            if (gbExpId == null || gbExpId.isBlank()) {
                throw new IllegalStateException("GB did not return experiment id. raw=" + shortBody(raw));
            }

            // Map variation index → gbVariationId, then map to our variantKey
            JsonNode gbVariations = expNode.path("variations");
            Map<String, String> result = new java.util.LinkedHashMap<>();

            for (int i = 0; i < variants.size(); i++) {
                String variantKey = variants.get(i).getKey();
                String gbVariationId = null;

                if (gbVariations.isArray() && i < gbVariations.size()) {
                    JsonNode varNode = gbVariations.get(i);
                    // GB REST API returns "variationId" (not "id")
                    gbVariationId = varNode.path("variationId").asText(null);
                    if (gbVariationId == null || gbVariationId.isBlank()) {
                        gbVariationId = varNode.path("id").asText(null); // fallback
                    }
                }
                if (gbVariationId != null && !gbVariationId.isBlank()) {
                    result.put(variantKey, gbVariationId);
                }
            }

            log.info("✅ [GB] native experiment OK id={} variationMapping={}", gbExpId, result);
            return new NativeExperimentResult(gbExpId, result);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GB native experiment response: " + e.getMessage(), e);
        }
    }

    /**
     * Replaces feature rules with ONE experiment-ref rule that links to
     * a native GB Experiment. GB SDK uses the experiment's split to assign variants.
     */
    public Mono<Void> upsertExperimentRefRule(
            String featureKey,
            String gbExperimentId,
            List<ExperimentVariant> variants) {

        log.info("🔗 [GB] upsertExperimentRefRule featureKey={} gbExpId={}", featureKey, gbExperimentId);

        return getFeatureRaw(featureKey)
                .flatMap(raw -> {
                    try {
                        ObjectNode feature = extractFeatureObject(raw);
                        ObjectNode envs = feature.has("environments") && feature.get("environments").isObject()
                                ? (ObjectNode) feature.get("environments")
                                : om.createObjectNode();

                        for (String envName : List.of("production", "dev")) {
                            ObjectNode envObj = ensureEnvObject(envs, envName);
                            ensureEnabled(envObj);
                            var rules = om.createArrayNode();
                            rules.add(buildExperimentRefRule(gbExperimentId, variants));
                            envObj.set("rules", rules);
                        }

                        ObjectNode updateFeature = om.createObjectNode();
                        updateFeature.put("project", project);
                        updateFeature.put("owner", owner);
                        updateFeature.set("environments", envs);

                        return postFeatureUpdate(featureKey, updateFeature, "upsertExperimentRefRule");
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException(
                                "upsertExperimentRefRule failed key=" + featureKey + ": " + e.getMessage(), e));
                    }
                });
    }

    private ObjectNode buildExperimentRefRule(String gbExperimentId, List<ExperimentVariant> variants) {
        ObjectNode rule = om.createObjectNode();
        rule.put("type", "experiment-ref");
        rule.put("experimentId", gbExperimentId);
        rule.put("condition", "{}");

        var variations = om.createArrayNode();
        for (ExperimentVariant v : variants) {
            ObjectNode varItem = om.createObjectNode();
            String gbVarId = v.getGbVariationId();
            if (gbVarId == null || gbVarId.isBlank()) {
                log.warn("⚠️ [GB] variant key={} has no gbVariationId, skipping in ref rule", v.getKey());
                continue;
            }
            varItem.put("variationId", gbVarId);
            // Inject __variantKey__ so bridge knows the variant name
            String enrichedRecipe = injectVariantKey(v.getRecipeJson(), v.getKey());
            putJsonValue(varItem, "value", gbJsonValue(enrichedRecipe));
            variations.add(varItem);
        }
        rule.set("variations", variations);
        return rule;
    }

    // -------------------------------------------------------------------------
    // NATIVE GB EXPERIMENTS — full-featured create / get / list / status update
    // -------------------------------------------------------------------------

    /**
     * Creates a new native GrowthBook Experiment with full targeting support.
     *
     * @param trackingKey        unique tracking key
     * @param name               human-readable name
     * @param description        optional description
     * @param hypothesis         optional hypothesis
     * @param variationsJson     JSON array: [{"key":"control","name":"Control"}, ...]
     * @param weightsJson        JSON array: [0.5, 0.5]
     * @param targetingCondition JSON condition: {} = all, {"country":"UA"}, etc.
     * @param coverage           traffic coverage 0.0..1.0
     * @param status             draft | running | stopped
     * @return raw JSON response from GrowthBook API
     */
    public Mono<String> createNativeExperimentFull(
            String trackingKey,
            String name,
            String description,
            String hypothesis,
            String variationsJson,
            String weightsJson,
            String targetingCondition,
            double coverage,
            String status) {

        ObjectNode body = buildFullNativeExpBody(
                trackingKey, name, description, hypothesis,
                variationsJson, weightsJson, targetingCondition, coverage, status);

        log.info("🧪 [GB] createNativeExperimentFull trackingKey={} status={}", trackingKey, status);
        try {
            log.debug("📤 [GB] createNativeExpFull body={}", shortBody(om.writeValueAsString(body)));
        } catch (Exception ignored) {}

        return admin.post()
                .uri("/experiments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB createNativeExpFull error"))
                .bodyToMono(String.class)
                .doOnNext(r -> log.info("✅ [GB] createNativeExpFull OK len={}", r.length()));
    }

    /**
     * Updates an existing native GrowthBook Experiment.
     */
    public Mono<String> updateNativeExperimentFull(
            String gbExperimentId,
            String name,
            String description,
            String hypothesis,
            String variationsJson,
            String weightsJson,
            String targetingCondition,
            double coverage,
            String status) {

        ObjectNode body = buildFullNativeExpBody(
                null, name, description, hypothesis,
                variationsJson, weightsJson, targetingCondition, coverage, status);

        log.info("🔄 [GB] updateNativeExperimentFull id={} status={}", gbExperimentId, status);

        return admin.post()
                .uri("/experiments/{id}", gbExperimentId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB updateNativeExpFull error"))
                .bodyToMono(String.class)
                .doOnNext(r -> log.info("✅ [GB] updateNativeExpFull OK id={}", gbExperimentId));
    }

    /**
     * Updates only the status of an existing native GrowthBook Experiment.
     * status: draft | running | stopped | archived
     */
    public Mono<String> updateNativeExperimentStatus(String gbExperimentId, String newStatus) {
        ObjectNode body = om.createObjectNode();
        body.put("status", newStatus);

        log.info("🔀 [GB] updateNativeExperimentStatus id={} → {}", gbExperimentId, newStatus);

        return admin.post()
                .uri("/experiments/{id}", gbExperimentId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB updateNativeExpStatus error"))
                .bodyToMono(String.class)
                .doOnNext(r -> log.info("✅ [GB] updateNativeExpStatus OK id={}", gbExperimentId));
    }

    /**
     * Gets a native GrowthBook Experiment by its GB ID.
     */
    public Mono<String> getNativeExperimentRaw(String gbExperimentId) {
        log.debug("➡️ [GB] GET /experiments/{}", gbExperimentId);
        return admin.get()
                .uri("/experiments/{id}", gbExperimentId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB getNativeExp error"))
                .bodyToMono(String.class)
                .doOnNext(r -> log.debug("📥 [GB] getNativeExp OK id={} ({} bytes)", gbExperimentId, r.length()));
    }

    /**
     * Lists native GrowthBook Experiments.
     */
    public Mono<String> listNativeExperimentsRaw(int limit, int offset) {
        int safeLimit = Math.min(Math.max(1, limit), 100);
        int safeOffset = Math.max(0, offset);
        log.debug("➡️ [GB] GET /experiments limit={} offset={}", safeLimit, safeOffset);
        return admin.get()
                .uri(u -> u.path("/experiments").queryParam("limit", safeLimit).queryParam("offset", safeOffset).build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB listNativeExps error"))
                .bodyToMono(String.class)
                .doOnNext(r -> log.debug("📦 [GB] listNativeExps OK ({} bytes)", r.length()));
    }

    /**
     * Extracts the GB experiment ID from a createNativeExperimentFull response.
     */
    public String extractGbExpId(String raw) {
        try {
            JsonNode root = om.readTree(raw);
            JsonNode expNode = root.has("experiment") ? root.get("experiment") : root;
            String id = expNode.path("id").asText(null);
            if (id == null || id.isBlank()) throw new IllegalStateException("no id in response");
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract GB experiment id: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildFullNativeExpBody(
            String trackingKey,
            String name,
            String description,
            String hypothesis,
            String variationsJson,
            String weightsJson,
            String targetingCondition,
            double coverage,
            String status) {

        ObjectNode body = om.createObjectNode();
        if (trackingKey != null && !trackingKey.isBlank()) body.put("trackingKey", trackingKey);
        body.put("name", (name != null && !name.isBlank()) ? name : (trackingKey != null ? trackingKey : "Unnamed"));
        if (description != null && !description.isBlank()) body.put("description", description);
        if (hypothesis != null && !hypothesis.isBlank()) body.put("hypothesis", hypothesis);
        body.put("project", project);
        body.put("hashAttribute", "id");
        body.put("status", mapStatus(status));
        body.put("assignmentQueryId", "");

        // Variations
        var variationsArr = om.createArrayNode();
        var weightsArr = om.createArrayNode();

        try {
            JsonNode vars = om.readTree(variationsJson == null || variationsJson.isBlank() ? "[]" : variationsJson);
            JsonNode wts = om.readTree(weightsJson == null || weightsJson.isBlank() ? "[]" : weightsJson);

            for (int i = 0; i < vars.size(); i++) {
                JsonNode v = vars.get(i);
                ObjectNode variation = om.createObjectNode();
                variation.put("key", v.path("key").asText(String.valueOf(i)));
                variation.put("name", v.path("name").asText(v.path("key").asText("Variation " + i)));
                variation.set("screenshots", om.createArrayNode());
                variationsArr.add(variation);

                double w = (wts.isArray() && i < wts.size()) ? wts.get(i).asDouble(0.5) : (1.0 / Math.max(1, vars.size()));
                weightsArr.add(w);
            }
        } catch (Exception e) {
            log.warn("[GB] Failed to parse variationsJson/weightsJson: {}", e.getMessage());
            // Fallback: two equal variants
            for (String vKey : new String[]{"control", "treatment"}) {
                ObjectNode v = om.createObjectNode();
                v.put("key", vKey); v.put("name", vKey); v.set("screenshots", om.createArrayNode());
                variationsArr.add(v);
                weightsArr.add(0.5);
            }
        }
        body.set("variations", variationsArr);

        // Phase with targeting
        String cond = (targetingCondition == null || targetingCondition.isBlank()) ? "{}" : targetingCondition;
        double cov = Math.max(0.0, Math.min(1.0, coverage));

        ObjectNode phase = om.createObjectNode();
        phase.put("name", "Main");
        phase.put("dateStarted", java.time.Instant.now().toString());
        phase.put("coverage", cov);
        phase.set("variationWeights", weightsArr.deepCopy());
        phase.put("condition", cond);

        ObjectNode ns = om.createObjectNode();
        ns.put("enabled", false);
        ns.put("namespaceId", "");
        ns.put("name", "");
        ns.set("range", om.createArrayNode().add(0).add(1));
        phase.set("namespace", ns);

        body.set("phases", om.createArrayNode().add(phase));
        return body;
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

    public Mono<String> deleteExperiment(String gbExperimentId) {
        log.warn("🗑️ [GB] DELETE /experiments/{}", gbExperimentId);
        return admin.delete()
                .uri("/experiments/{id}", gbExperimentId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> errWithBody(resp, "GB deleteExperiment error"))
                .bodyToMono(String.class)
                .doOnNext(_x -> log.info("✅ [GB] deleteExperiment OK: {}", gbExperimentId));
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
