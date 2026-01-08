package com.example.gb.controller;

import com.example.gb.service.GbAdminService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gb")
public class GbConsoleController {

    private final GbAdminService gb;

    @PostMapping("/upsert-feature")
    public Mono<ResponseEntity<String>> upsert(@RequestBody UpsertReq req) {
        String env = (req.getEnv() == null || req.getEnv().isBlank())
                ? "production"
                : req.getEnv();

        log.info("⬆️ upsert-feature key={} env={} rules={}",
                req.getKey(),
                env,
                (req.getRules() == null ? 0 : req.getRules().size()));

        return gb.upsertJsonFeatureAdvanced(
                        req.getKey(),
                        env,
                        req.getValue(),
                        req.getRules()
                )
                .map(ResponseEntity::ok)
                .onErrorResume(ex -> {
                    String msg = ex.getMessage() == null ? "Unknown" : ex.getMessage();
                    log.error("❌ upsert-feature error: {}", msg);
                    return Mono.just(
                            ResponseEntity.status(502)
                                    .body("{\"error\":\"" + msg.replace("\"","'") + "\"}")
                    );
                });
    }

    /** Список фіч з GrowthBook (JSON як є) — для дебагу/інспекції */
    @GetMapping("/features")
    public Mono<ResponseEntity<String>> list() {
        return gb.listFeatures()
                .map(ResponseEntity::ok)
                .onErrorResume(ex -> {
                    String msg = ex.getMessage() == null ? "Unknown" : ex.getMessage();
                    return Mono.just(
                            ResponseEntity.status(502)
                                    .body("{\"error\":\"" + msg.replace("\"","'") + "\"}")
                    );
                });
    }

    @Data
    public static class UpsertReq {
        private String key;                     // feature key (site__page__slot)
        private String env;                     // dev|production
        private Map<String, Object> value;      // { "ops": [...] }
        private List<Map<String, Object>> rules; // спрощені правила (type, when, whitelist, percent, etc.)
    }
}
