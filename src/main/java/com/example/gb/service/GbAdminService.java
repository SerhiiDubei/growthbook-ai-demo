package com.example.gb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

/**
 * GrowthBook Admin API client (v4.x)
 *
 * - upsert через "create-then-update"
 * - JSON-фічі зберігаються як String у defaultValue та rules.value
 * - ДОДАНО: RAW methods (String json), нормальні логи і error-body
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbAdminService {

    @Qualifier("gbAdminClient")
    private final WebClient admin;

    private final ObjectMapper objectMapper;

    @Value("${growthbook.owner:Owner}")
    private String owner;

    @Value("${growthbook.project:Project}")
    private String project;

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    /** Серіалізація будь-якого об’єкта в JSON-рядок */
    private String asJsonString(Object any) {
        try {
            return objectMapper.writeValueAsString(any == null ? Map.of() : any);
        } catch (Exception e) {
            throw new RuntimeException("Serialize to JSON failed: " + e.getMessage(), e);
        }
    }

    /** короткий лог для великих JSON */
    private String shortJson(String s) {
        if (s == null) return "null";
        String x = s.replaceAll("\\s+", " ").trim();
        return (x.length() <= 500) ? x : (x.substring(0, 500) + "...(" + x.length() + " chars)");
    }

    /** Зняти тіло помилки в onStatus(...) */
    private Function<ClientResponse, Mono<? extends Throwable>> errWithBody(String prefix) {
        return resp -> resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    String msg = prefix + " " + resp.statusCode() +
                            (body.isBlank() ? "" : (": " + shortJson(body)));
                    log.error("❌ {}", msg);
                    return Mono.error(new RuntimeException(msg));
                });
    }

    private static double toNumber(Object... xs) {
        for (Object x : xs) {
            if (x == null) continue;
            try {
                return Double.parseDouble(String.valueOf(x));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /** Перетворити наш простий when-JSON → JSON-logic рядок */
    @SuppressWarnings("unchecked")
    private String toJsonLogic(Map<String, Object> when) {
        if (when == null || when.isEmpty()) return null;

        Map<String, Object> and = new LinkedHashMap<>();
        for (var e : when.entrySet()) {
            String attr = e.getKey();
            Map<String, Object> spec = (Map<String, Object>) e.getValue();
            if (spec == null) continue;

            String op = String.valueOf(spec.getOrDefault("op", "="));
            String v  = String.valueOf(spec.getOrDefault("v", ""));

            Map<String, Object> expr;
            switch (op) {
                case "="  -> expr = Map.of("$eq", v);
                case "!=" -> expr = Map.of("$ne", v);
                case "^=" -> expr = Map.of("$startsWith", v);
                case "$=" -> expr = Map.of("$endsWith", v);
                case "~=" -> expr = Map.of("$regex", v);
                default   -> expr = Map.of("$eq", v);
            }
            and.put(attr, expr);
        }
        return asJsonString(and);
    }

    /** Helper: detect "already exists" from create response */
    private Mono<? extends Throwable> mapCreateError(ClientResponse resp, String label) {
        return resp.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(body -> {
                    int sc = resp.statusCode().value();
                    String b = body == null ? "" : body;

                    if (sc == 409 || (sc == 400 && b.toLowerCase(Locale.ROOT).contains("exist"))) {
                        return Mono.error(new IllegalStateException("__GB_ALREADY_EXISTS__"));
                    }

                    String msg = "GB " + label + " error " + resp.statusCode() +
                            (b.isBlank() ? "" : (": " + shortJson(b)));
                    return Mono.error(new RuntimeException(msg));
                });
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    public Mono<String> getFeatureRaw(String id) {
        log.debug("➡️ [GB] getFeatureRaw {}", id);
        return admin.get()
                .uri("/features/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, errWithBody("GB getFeatureRaw error"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.debug("📥 [GB] getFeatureRaw {} OK ({} bytes)", id, json.length()));
    }

    public Mono<String> listFeatures() {
        log.debug("➡️ [GB] listFeatures");
        return admin.get()
                .uri("/features")
                .retrieve()
                .onStatus(HttpStatusCode::isError, errWithBody("GB list error"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("📦 [GB] listFeatures OK ({} bytes)", json.length()));
    }

    // -------------------------------------------------------------------------
    // RAW UPSERTS (String JSON)  ✅ для Tools
    // -------------------------------------------------------------------------

    /**
     * Простий UPSERT JSON-фічі (RAW).
     * recipeJson очікується як JSON object string (наприклад {"ops":[...]}).
     * Зберігається як String у defaultValue (як у твоєму старому сервісі).
     */
    public Mono<String> upsertJsonFeatureRaw(String id, String recipeJson) {
        final String payload = (recipeJson == null || recipeJson.isBlank()) ? "{}" : recipeJson;

        Map<String, Object> envs = Map.of(
                "dev", Map.of("enabled", true, "defaultValue", payload, "rules", List.of()),
                "production", Map.of("enabled", true, "defaultValue", payload, "rules", List.of())
        );

        Map<String, Object> createBody = Map.of(
                "id", id,
                "valueType", "json",
                "defaultValue", payload,
                "project", project,
                "owner", owner,
                "environments", envs
        );

        log.info("➡️ [GB] create JSON feature(raw): id={} payload={}", id, shortJson(payload));

        return admin.post()
                .uri("/features")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> mapCreateError(resp, "create(raw)"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("✅ [GB] create feature(raw) OK: {}", id))
                .onErrorResume(IllegalStateException.class, ex -> {
                    if (!"__GB_ALREADY_EXISTS__".equals(ex.getMessage())) return Mono.error(ex);

                    Map<String, Object> updateBody = Map.of(
                            "description", "upsert by app",
                            "owner", owner,
                            "project", project,
                            "defaultValue", payload,
                            "environments", envs
                    );

                    log.info("🔁 [GB] update JSON feature(raw): id={} payload={}", id, shortJson(payload));

                    return admin.post()
                            .uri("/features/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(updateBody)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, errWithBody("GB update(raw) error"))
                            .bodyToMono(String.class)
                            .doOnNext(j -> log.info("🔁 [GB] update feature(raw) OK: {}", id));
                });
    }

    /**
     * UPSERT JSON-фічі для sessionTag (RAW).
     * Зберігає defaultValue="{}" і rules.value = recipeJson (String).
     */
    public Mono<String> upsertJsonFeatureForTagRaw(String id, String tag, String ownerOverride, String recipeJson) {
        final String recipeStr = (recipeJson == null || recipeJson.isBlank()) ? "{}" : recipeJson;
        final String defaultEmpty = "{}";

        Map<String, Object> rule = Map.of(
                "type", "force",
                "enabled", true,
                "description", "sessionTag rule",
                "condition", "{\"sessionTag\":{\"$eq\":\"" + tag + "\"}}",
                "value", recipeStr
        );

        Map<String, Object> createBody = Map.of(
                "id", id,
                "valueType", "json",
                "defaultValue", defaultEmpty,
                "owner", (ownerOverride == null || ownerOverride.isBlank()) ? owner : ownerOverride,
                "project", project,
                "description", "SessionTag=" + tag,
                "environments", Map.of(
                        "dev", Map.of("enabled", true, "defaultValue", defaultEmpty, "rules", List.of(rule)),
                        "production", Map.of("enabled", false, "defaultValue", defaultEmpty, "rules", List.of())
                )
        );

        log.info("➡️ [GB] create feature(tag/raw): id={} tag={} value={}", id, tag, shortJson(recipeStr));

        return admin.post()
                .uri("/features")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> mapCreateError(resp, "create(tag/raw)"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("✅ [GB] create feature(tag/raw) OK: {}", id))
                .onErrorResume(IllegalStateException.class, ex -> {
                    if (!"__GB_ALREADY_EXISTS__".equals(ex.getMessage())) return Mono.error(ex);

                    Map<String, Object> updateBody = Map.of(
                            "description", "SessionTag=" + tag,
                            "owner", (ownerOverride == null || ownerOverride.isBlank()) ? owner : ownerOverride,
                            "project", project,
                            "defaultValue", defaultEmpty,
                            "environments", Map.of(
                                    "dev", Map.of("enabled", true, "defaultValue", defaultEmpty, "rules", List.of(rule))
                            )
                    );

                    log.info("🔁 [GB] update feature(tag/raw): id={} tag={}", id, tag);

                    return admin.post()
                            .uri("/features/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(updateBody)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, errWithBody("GB update(tag/raw) error"))
                            .bodyToMono(String.class)
                            .doOnNext(j -> log.info("🔁 [GB] update feature(tag/raw) OK: {}", id));
                });
    }

    /**
     * Advanced upsert (RAW): defaultJson і rulesJson як strings.
     * rulesJson може бути null/blank => []
     */
    @SuppressWarnings("unchecked")
    public Mono<String> upsertJsonFeatureAdvancedRaw(String key, String env, String defaultJson, String rulesJson) {

        // defaultJson: якщо пустий, то {"ops":[]}
        Map<String, Object> defaultObj;
        try {
            if (defaultJson == null || defaultJson.isBlank()) {
                defaultObj = Map.of("ops", List.of());
            } else {
                defaultObj = objectMapper.readValue(defaultJson, Map.class);
            }
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Invalid defaultJson: " + e.getMessage(), e));
        }

        List<Map<String, Object>> rulesInput;
        try {
            if (rulesJson == null || rulesJson.isBlank()) {
                rulesInput = List.of();
            } else {
                rulesInput = objectMapper.readValue(rulesJson, List.class);
            }
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Invalid rulesJson: " + e.getMessage(), e));
        }

        return upsertJsonFeatureAdvanced(key, env, defaultObj, rulesInput);
    }

    // -------------------------------------------------------------------------
    // LEGACY METHODS (Map-based) — залишив, щоб нічого не зламати
    // -------------------------------------------------------------------------

    public Mono<String> upsertJsonFeature(String id, Map<String, Object> jsonValue) {
        final String payload = asJsonString(jsonValue); // JSON як рядок
        return upsertJsonFeatureRaw(id, payload);
    }

    public Mono<String> upsertJsonFeatureForTag(String id, String tag, String ownerOverride, Map<String, Object> recipe) {
        final String recipeStr = asJsonString(recipe);
        return upsertJsonFeatureForTagRaw(id, tag, ownerOverride, recipeStr);
    }

    public Mono<String> upsertJsonFeatureAdvanced(String key, String env, Map<String, Object> defaultJson, List<Map<String, Object>> rulesInput) {

        final String defStr = asJsonString(defaultJson == null ? Map.of("ops", List.of()) : defaultJson);

        // Перетворення наших "rules" із консолі → GB rules
        List<Map<String, Object>> gbRules = new ArrayList<>();
        if (rulesInput != null) {
            for (Map<String, Object> r : rulesInput) {
                if (r == null) continue;
                String type = String.valueOf(r.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
                Map<String, Object> when = (Map<String, Object>) r.get("when");
                String condition = toJsonLogic(when);

                switch (type) {
                    case "on" -> gbRules.add(Map.of(
                            "type", "force",
                            "enabled", true,
                            "description", "100%",
                            "value", defStr,
                            "condition", condition == null ? "" : condition
                    ));
                    case "percent" -> {
                        double pct = toNumber(r.get("percent"), r.get("coverage"), r.get("param"));
                        double coverage = Math.max(0, Math.min(100, pct)) / 100.0;
                        gbRules.add(Map.of(
                                "type", "rollout",
                                "enabled", true,
                                "description", "rollout " + pct + "%",
                                "coverage", coverage,
                                "hashAttribute", "id",
                                "value", defStr,
                                "condition", condition == null ? "" : condition
                        ));
                    }
                    case "qa" -> {
                        Object wl = r.get("whitelist");
                        String cond = condition;
                        if (wl instanceof List<?> list && !list.isEmpty()) {
                            // простий whitelist по sessionTag
                            cond = "{\"sessionTag\":{\"$in\":" + asJsonString(list) + "}}";
                        }
                        gbRules.add(Map.of(
                                "type", "force",
                                "enabled", true,
                                "description", "QA/whitelist",
                                "value", defStr,
                                "condition", cond == null ? "" : cond
                        ));
                    }
                    default -> {
                        // ignore
                    }
                }
            }
        }

        // середовище, яке редагуємо
        String targetEnv = (env == null || env.isBlank()) ? "production" : env;

        Map<String, Object> envs = new HashMap<>();
        for (String e : List.of("dev", "production")) {
            boolean isTarget = e.equalsIgnoreCase(targetEnv);
            envs.put(e, Map.of(
                    "enabled", isTarget,
                    "defaultValue", defStr,
                    "rules", isTarget ? gbRules : List.of()
            ));
        }

        Map<String, Object> createBody = Map.of(
                "id", key,
                "valueType", "json",
                "defaultValue", defStr,
                "project", project,
                "owner", owner,
                "environments", envs
        );

        log.info("➡️ [GB] create feature(adv) {}", key);

        return admin.post()
                .uri("/features")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> mapCreateError(resp, "create(adv)"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("✅ [GB] create(adv) OK: {}", key))
                .onErrorResume(IllegalStateException.class, ex -> {
                    if (!"__GB_ALREADY_EXISTS__".equals(ex.getMessage())) return Mono.error(ex);

                    Map<String, Object> updateBody = Map.of(
                            "description", "upsert by console",
                            "owner", owner,
                            "project", project,
                            "defaultValue", defStr,
                            "environments", envs
                    );

                    log.info("🔁 [GB] update feature(adv) {}", key);

                    return admin.post()
                            .uri("/features/{id}", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(updateBody)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, errWithBody("GB update(adv) error"))
                            .bodyToMono(String.class)
                            .doOnNext(j -> log.info("🔁 [GB] update(adv) OK: {}", key));
                });
    }

    // -------------------------------------------------------------------------
    // ENSURE SKELETON  ✅ виправлено
    // -------------------------------------------------------------------------

    public Mono<Void> ensureJsonFeatureSkeleton(String key) {

        return admin.get()
                .uri("/features/{id}", key)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(body -> {
                                    int sc = resp.statusCode().value();
                                    String b = body == null ? "" : body;

                                    // ✅ GrowthBook інколи повертає 400 замість 404, коли фіча не існує
                                    boolean notFoundByMessage =
                                            sc == 400 && b.toLowerCase(Locale.ROOT).contains("could not find a feature with that key");

                                    if (sc == 404 || notFoundByMessage) {
                                        // спеціальний маркер "фічі нема" -> підемо створювати
                                        return Mono.error(new IllegalStateException("__GB_NOT_FOUND__"));
                                    }

                                    // інші помилки — це реально помилки
                                    String msg = "GB ensureSkeleton GET error " + resp.statusCode() +
                                            (b.isBlank() ? "" : (": " + b));
                                    log.error("❌ {}", msg);
                                    return Mono.error(new RuntimeException(msg));
                                })
                )
                .bodyToMono(String.class)
                .doOnNext(j -> log.debug("✔ [GB] feature '{}' already exists", key))
                .then()

                // ✅ тут ловимо наш маркер "нема" і створюємо skeleton
                .onErrorResume(IllegalStateException.class, ex -> {
                    if (!"__GB_NOT_FOUND__".equals(ex.getMessage())) return Mono.error(ex);

                    Map<String,Object> envs = Map.of(
                            "dev", Map.of("enabled", true, "defaultValue", "{}", "rules", List.of()),
                            "production", Map.of("enabled", true, "defaultValue", "{}", "rules", List.of())
                    );

                    Map<String,Object> body = Map.of(
                            "id", key,
                            "valueType", "json",
                            "defaultValue", "{}",
                            "project", project,
                            "owner", owner,
                            "environments", envs
                    );

                    log.info("➕ [GB] create skeleton feature: {}", key);

                    return admin.post()
                            .uri("/features")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, errWithBody("GB ensureSkeleton CREATE error"))
                            .bodyToMono(String.class)
                            .doOnNext(r -> log.info("✅ [GB] skeleton created: {}", key))
                            .then();
                });
    }

    public Mono<String> deleteFeature(String id) {
        log.warn("🗑️ [GB] deleteFeature → {}", id);
        return admin.delete()
                .uri("/features/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, errWithBody("GB delete error"))
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("✅ [GB] deleteFeature OK: {}", id));
    }
}
