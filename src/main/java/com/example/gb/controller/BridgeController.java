package com.example.gb.controller;

import com.example.gb.service.BridgeService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Debug/inspection bridge.
 * NOTE: All A/B variant assignment happens exclusively on the client via the GrowthBook SDK.
 * This endpoint is for admin/debug inspection only — returns GB feature defaultValue ops.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/bridge")
public class BridgeController {

    private final BridgeService service;

    /**
     * GET /bridge/recipe?url=...&features=a,b,c
     * Returns GrowthBook feature defaultValue as DOM recipe ops — for debug inspection only.
     * No A/B assignment is performed here.
     */
    @GetMapping(value = "/recipe", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecipeResponse> recipe(
            @RequestParam @NotBlank String url,
            @RequestParam(required = false, defaultValue = "dom_patches") String features
    ) {
        try {
            final String urlNorm = url.trim();

            final List<String> featureIds = normalizeFeatures(features);
            if (featureIds.isEmpty()) {
                return badRequest("features list is empty");
            }

            var payload = service.buildRecipe(urlNorm, featureIds);

            log.debug("🔍 bridge/recipe [debug] features={} ops={}",
                    featureIds, payload.getRecipe().getOps().size());

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().mustRevalidate())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(payload);

        } catch (Exception e) {
            log.error("❌ bridge/recipe failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).cacheControl(CacheControl.noStore().mustRevalidate())
                    .body(new RecipeResponse("error", null, null, new Recipe(List.of())));
        }
    }

    /**
     * POST /bridge/event — receives exposure/click/goal events from the frontend bridge.
     */
    @PostMapping(value = "/event", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> event(@RequestBody Map<String, Object> evt) {
        log.info("📈 bridge/event: {}", evt);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .location(URI.create("/bridge/event"))
                .build();
    }

    // ===== DTO =====

    @Value
    public static class RecipeResponse {
        String featureId;
        String experimentId;
        String variant;
        Recipe recipe;
    }

    @Value
    public static class Recipe {
        List<Map<String, Object>> ops;
    }

    // ===== Helpers =====

    private static List<String> normalizeFeatures(String features) {
        if (!StringUtils.hasText(features)) return List.of("dom_patches");
        try {
            return Stream.of(features.split("\\s*,\\s*"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(s -> s.length() > 100 ? s.substring(0, 100) : s)
                    .distinct()
                    .limit(50)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("dom_patches");
        }
    }

    private static ResponseEntity<RecipeResponse> badRequest(String msg) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(new RecipeResponse("bad_request", null, null, new Recipe(List.of())));
    }
}
