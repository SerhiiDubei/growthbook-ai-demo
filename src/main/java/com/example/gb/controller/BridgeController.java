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

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/bridge")
@CrossOrigin(origins = "*") // 👈 якщо потрібні запити з інших доменів. За бажанням звузь до свого UI.
public class BridgeController {

    private final BridgeService service;

    /**
     * GET /bridge/recipe?url=...&session=...&features=a,b,c&tag=QA777&percent=50
     * - features можна опустити: тоді використаємо "dom_patches" за замовчуванням
     * - percent нормалізуємо до [0..100], null = без роллаута
     * - tag/session підрізаємо і чистимо від сміття
     */
    @GetMapping(value = "/recipe", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecipeResponse> recipe(
            @RequestParam @NotBlank String url,
            @RequestParam @NotBlank String session,
            @RequestParam(required = false, defaultValue = "dom_patches") String features,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer percent // 0..100
    ) {
        try {
            // 1) Нормалізація параметрів
            final String urlNorm = url.trim();

            final String sessionNorm = trimMax(cleanToken(session), 128);
            if (!StringUtils.hasText(sessionNorm)) {
                return badRequest("session is empty/invalid");
            }

            final String tagNorm = StringUtils.hasText(tag) ? trimMax(cleanToken(tag), 64) : null;

            final List<String> featureIds = normalizeFeatures(features);
            if (featureIds.isEmpty()) {
                return badRequest("features list is empty");
            }

            final Integer percentNorm = normalizePercent(percent);

            // 2) Побудова рецепта
            var payload = service.buildRecipe(urlNorm, sessionNorm, featureIds, tagNorm, percentNorm);

            // 3) Безпечний лог (не показуємо повністю session)
            log.info("🎯 bridge/recipe ok: sess={}… tag={} features={} ops={}",
                    mask(sessionNorm), tagNorm, featureIds, payload.getRecipe().getOps().size());

            // 4) no-store, щоб браузер/CDN не кешували рецепт
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
     * Будь-які події з фронту (exposure/click/goal…).
     * За потреби — збережи у БД чи форвардь в аналітику.
     */
    @PostMapping(value = "/event", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> event(@RequestBody Map<String, Object> evt) {
        // Трохи санітизуємо сесію в логах, якщо є
        Object s = evt.get("session");
        if (s instanceof String ss) {
            evt.put("session", mask(trimMax(ss, 128)));
        }
        log.info("📈 bridge/event: {}", evt);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .location(URI.create("/bridge/event"))
                .build();
    }

    // ===== DTO =====

    @Value
    public static class RecipeResponse {
        String featureId;      // коли features було кілька – можна залишати "merged"
        String experimentId;   // опційно
        String variant;        // "A"/"B" або null
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
                    .map(s -> trimMax(s, 100))
                    .distinct()
                    .limit(50) // захист від DDoS/спаму
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("dom_patches");
        }
    }

    private static Integer normalizePercent(Integer p) {
        if (p == null) return null;
        int v = Math.max(0, Math.min(100, p));
        return v;
    }

    /** залишаємо лат/цифри/дефіс/підкреслення, решту прибираємо */
    private static String cleanToken(String s) {
        return s == null ? null : s.replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private static String trimMax(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String mask(String s) {
        if (!StringUtils.hasText(s)) return s;
        if (s.length() <= 6) return "***";
        return s.substring(0, 3) + "…" + s.substring(s.length() - 3);
    }

    private static ResponseEntity<RecipeResponse> badRequest(String msg) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(new RecipeResponse("bad_request", null, null, new Recipe(List.of())));
    }
}
