package com.example.gb.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/gb")
public class PreviewController {

    /** Ключ: origin + "#" + sessionTag → JSON ops */
    private final Map<String, Map<String, Object>> previews = new ConcurrentHashMap<>();

    /** Застосувати прев’ю для QA-сеансу (викликає консоль) */
    @PostMapping("/preview")
    public ResponseEntity<Void> apply(@RequestBody PreviewReq req) {
        String key = key(req.getOrigin(), req.getSessionTag());
        previews.put(key, req.getOps() == null ? Map.of("ops", java.util.List.of()) : req.getOps());
        log.info("👁️ preview set for {} (featureId={}): {} ops",
                key, req.getFeatureId(), ((java.util.List<?>)req.getOps().getOrDefault("ops", java.util.List.of())).size());
        return ResponseEntity.accepted().build();
    }

    /** Зняти прев’ю (читає місток на сторінці) */
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> read(
            @RequestParam String origin,
            @RequestParam String sessionTag) {
        String key = key(origin, sessionTag);
        return ResponseEntity.ok(previews.getOrDefault(key, Map.of("ops", java.util.List.of())));
    }

    private static String key(String origin, String tag) { return origin + "#" + tag; }

    @Data
    public static class PreviewReq {
        private String origin;
        private String featureId;
        private String sessionTag;
        private Map<String, Object> ops; // { "ops": [ { sel, kind, value, prop? } ] }
    }
}
