package com.example.gb.controller;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.dto.AddVariantRequest;
import com.example.gb.model.dto.CreateExperimentRequest;
import com.example.gb.model.dto.ExperimentFailRequest;
import com.example.gb.model.dto.ExperimentFinishRequest;
import com.example.gb.model.dto.ExperimentStatsResponse;
import com.example.gb.model.dto.UpdateExperimentRequest;
import com.example.gb.service.ExperimentService;
import com.example.gb.service.StatisticsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;
    private final StatisticsService statisticsService;

    private String actor(String headerActor) {
        if (headerActor == null || headerActor.isBlank()) return "human";
        return headerActor.trim();
    }

    // CREATE
    @PostMapping
    public ResponseEntity<Experiment> create(
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @Valid @RequestBody CreateExperimentRequest req
    ) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments actor={} pageKey={} key={}", a, req.getPageKey(), req.getKey());
        return ResponseEntity.ok(experimentService.create(req, a));
    }

    // READ one
    @GetMapping("/{id}")
    public ResponseEntity<Experiment> get(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id) {
        String a = actor(actor);
        log.debug("HTTP GET /api/experiments/{} actor={}", id, a);
        return ResponseEntity.ok(experimentService.get(id));
    }

    // LIST by pageKey
    @GetMapping
    public ResponseEntity<Page<Experiment>> list(
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestParam String pageKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String a = actor(actor);
        log.debug("HTTP GET /api/experiments actor={} pageKey={} page={} size={}", a, pageKey, page, size);
        return ResponseEntity.ok(experimentService.listByPageKey(pageKey, PageRequest.of(page, size)));
    }

    // UPDATE (partial)
    @PutMapping("/{id}")
    public ResponseEntity<Experiment> update(
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id,
            @RequestBody UpdateExperimentRequest req
    ) {
        String a = actor(actor);
        log.info("HTTP PUT /api/experiments/{} actor={}", id, a);
        return ResponseEntity.ok(experimentService.update(id, req, a));
    }

    // LIFECYCLE
    @PostMapping("/{id}/start")
    public ResponseEntity<Experiment> start(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/start actor={}", id, a);
        return ResponseEntity.ok(experimentService.start(id, a));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Experiment> pause(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/pause actor={}", id, a);
        return ResponseEntity.ok(experimentService.pause(id, a));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Experiment> resume(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/resume actor={}", id, a);
        return ResponseEntity.ok(experimentService.resume(id, a));
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<Experiment> finish(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id,
            @RequestBody(required = false) ExperimentFinishRequest req) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/finish actor={}", id, a);
        return ResponseEntity.ok(experimentService.finish(id, req, a));
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Experiment> fail(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id,
            @RequestBody(required = false) ExperimentFailRequest req) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/fail actor={}", id, a);
        return ResponseEntity.ok(experimentService.fail(id, req, a));
    }

    @PostMapping("/{id}/reset")
    public ResponseEntity<Experiment> reset(@RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/reset actor={}", id, a);
        return ResponseEntity.ok(experimentService.resetToDraft(id, a));
    }

    // ===== STATISTICS =====

    @GetMapping("/{id}/stats")
    public ResponseEntity<ExperimentStatsResponse> stats(@PathVariable long id) {
        log.debug("HTTP GET /api/experiments/{}/stats", id);
        return ResponseEntity.ok(statisticsService.getStats(id));
    }

    // ===== VARIANT CRUD =====

    @PostMapping("/{id}/variants")
    public ResponseEntity<ExperimentVariant> addVariant(
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id,
            @RequestBody AddVariantRequest req
    ) {
        String a = actor(actor);
        log.info("HTTP POST /api/experiments/{}/variants key={} actor={}", id, req.getKey(), a);
        return ResponseEntity.ok(experimentService.addVariant(id, req, a));
    }

    @GetMapping("/{id}/variants")
    public ResponseEntity<List<ExperimentVariant>> listVariants(@PathVariable long id) {
        log.debug("HTTP GET /api/experiments/{}/variants", id);
        return ResponseEntity.ok(experimentService.getVariants(id));
    }

    @DeleteMapping("/{id}/variants/{variantId}")
    public ResponseEntity<Void> deleteVariant(
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable long id,
            @PathVariable long variantId
    ) {
        String a = actor(actor);
        log.info("HTTP DELETE /api/experiments/{}/variants/{} actor={}", id, variantId, a);
        experimentService.deleteVariant(variantId, a);
        return ResponseEntity.noContent().build();
    }
}
