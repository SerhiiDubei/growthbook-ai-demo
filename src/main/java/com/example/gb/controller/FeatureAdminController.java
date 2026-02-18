package com.example.gb.controller;

import com.example.gb.service.GbAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/gb/feature")
@RequiredArgsConstructor
@Validated
public class FeatureAdminController {

    private final GbAdminService gb;

    /** Upsert JSON-фічі (без sessionTag-правила). json — це об’єкт, НЕ рядок. */
    @PostMapping(
            path = "/upsert",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public Mono<ResponseEntity<String>> upsert(@Valid @RequestBody UpsertReq req) {
        return gb.upsertJsonFeatureRaw(req.getId(), req.getJson())
                .map(ResponseEntity::ok)
                .onErrorResume(ex ->
                        Mono.just(ResponseEntity.status(500).body("GB error: " + ex.getMessage())));
    }

    @Data
    public static class UpsertReq {
        @NotBlank private String id;                 // напр. dom_patch_hero_v1
        @NotNull  private Map<String, Object> json;  // напр. {"ops":[...]}
    }

    /** Upsert JSON-фічі з правилом на конкретний sessionTag у 'dev'. */
    @PostMapping(
            path = "/upsertForTag",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public Mono<ResponseEntity<String>> upsertForTag(@Valid @RequestBody UpsertForTagReq req) {
        return gb.upsertJsonFeatureForTag(req.getId(), req.getTag(), req.getOwner(), req.getJson())
                .map(ResponseEntity::ok)
                .onErrorResume(ex ->
                        Mono.just(ResponseEntity.status(500).body("GB error: " + ex.getMessage())));
    }

    @Data
    public static class UpsertForTagReq {
        @NotBlank private String id;                 // feature id
        @NotBlank private String tag;                // sessionTag, напр. QA123
        private String owner;                        // опційно (замінює дефолт Owner)
        @NotNull  private Map<String, Object> json;  // рецепт
    }
}
