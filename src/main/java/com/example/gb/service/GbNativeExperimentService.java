package com.example.gb.service;

import com.example.gb.model.GbNativeExperiment;
import com.example.gb.repository.GbNativeExperimentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GbNativeExperimentService {

    private final GbAdminService gbAdmin;
    private final GbNativeExperimentRepository repo;
    private final ObjectMapper om;

    // -------------------------------------------------------------------------
    // CREATE
    // -------------------------------------------------------------------------

    /**
     * Creates a native GrowthBook experiment and persists it to DB.
     *
     * @param trackingKey        unique experiment key
     * @param name               display name
     * @param description        optional
     * @param hypothesis         optional hypothesis
     * @param variationsJson     JSON array: [{"key":"control","name":"Control"}, ...]
     * @param weightsJson        JSON array: [0.5, 0.5]
     * @param targetingCondition JSON condition: {} = all users, {"country":"UA"}, etc.
     * @param coverage           0.0..1.0 traffic included
     * @param initialStatus      draft | running
     * @param createdBy          who created (default: "agent")
     * @return persisted GbNativeExperiment with gb_experiment_id filled
     */
    @Transactional
    public GbNativeExperiment create(
            String trackingKey,
            String name,
            String description,
            String hypothesis,
            String variationsJson,
            String weightsJson,
            String targetingCondition,
            double coverage,
            String initialStatus,
            String createdBy) {

        String status = normalizeStatus(initialStatus);

        String raw = gbAdmin.createNativeExperimentFull(
                trackingKey, name, description, hypothesis,
                variationsJson, weightsJson, targetingCondition, coverage, status
        ).block();

        String gbExpId = gbAdmin.extractGbExpId(raw);
        log.info("[GbNativeExpSvc] created GB exp id={}", gbExpId);

        GbNativeExperiment entity = new GbNativeExperiment();
        entity.setGbExperimentId(gbExpId);
        entity.setName(name != null ? name : trackingKey);
        entity.setTrackingKey(trackingKey);
        entity.setStatus(status);
        entity.setDescription(description);
        entity.setHypothesis(hypothesis);
        entity.setTargetingCondition(targetingCondition != null ? targetingCondition : "{}");
        entity.setVariationsJson(variationsJson != null ? variationsJson : "[]");
        entity.setWeightsJson(weightsJson != null ? weightsJson : "[]");
        entity.setCoverage(Math.max(0.0, Math.min(1.0, coverage)));
        entity.setCreatedBy(createdBy != null ? createdBy : "agent");

        return repo.save(entity);
    }

    // -------------------------------------------------------------------------
    // STATUS TRANSITIONS
    // -------------------------------------------------------------------------

    @Transactional
    public GbNativeExperiment start(String gbExperimentId) {
        return changeStatus(gbExperimentId, "running");
    }

    @Transactional
    public GbNativeExperiment stop(String gbExperimentId) {
        return changeStatus(gbExperimentId, "stopped");
    }

    @Transactional
    public GbNativeExperiment archive(String gbExperimentId) {
        return changeStatus(gbExperimentId, "archived");
    }

    private GbNativeExperiment changeStatus(String gbExperimentId, String newStatus) {
        gbAdmin.updateNativeExperimentStatus(gbExperimentId, newStatus).block();

        GbNativeExperiment entity = repo.findByGbExperimentId(gbExperimentId)
                .orElseGet(() -> {
                    GbNativeExperiment e = new GbNativeExperiment();
                    e.setGbExperimentId(gbExperimentId);
                    e.setName(gbExperimentId);
                    e.setTrackingKey(gbExperimentId);
                    e.setTargetingCondition("{}");
                    e.setVariationsJson("[]");
                    e.setWeightsJson("[]");
                    return e;
                });

        entity.setStatus(newStatus);
        log.info("[GbNativeExpSvc] status change id={} → {}", gbExperimentId, newStatus);
        return repo.save(entity);
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    public GbNativeExperiment getByGbId(String gbExperimentId) {
        return repo.findByGbExperimentId(gbExperimentId)
                .orElseThrow(() -> new IllegalArgumentException("GB experiment not found in DB: " + gbExperimentId));
    }

    /** Fetches fresh data from GrowthBook API and syncs status to DB. */
    @Transactional
    public Map<String, Object> getFromGbAndSync(String gbExperimentId) {
        String raw = gbAdmin.getNativeExperimentRaw(gbExperimentId).block();

        // Sync status to DB if record exists
        repo.findByGbExperimentId(gbExperimentId).ifPresent(entity -> {
            try {
                JsonNode root = om.readTree(raw);
                JsonNode expNode = root.has("experiment") ? root.get("experiment") : root;
                String gbStatus = expNode.path("status").asText(null);
                if (gbStatus != null && !gbStatus.isBlank() && !gbStatus.equals(entity.getStatus())) {
                    entity.setStatus(gbStatus);
                    repo.save(entity);
                    log.info("[GbNativeExpSvc] synced status id={} → {}", gbExperimentId, gbStatus);
                }
            } catch (Exception e) {
                log.warn("[GbNativeExpSvc] failed to sync status: {}", e.getMessage());
            }
        });

        return parseExpToMap(raw);
    }

    public Page<GbNativeExperiment> listFromDb(int page, int size) {
        return repo.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
    }

    public String listFromGb(int limit, int offset) {
        return gbAdmin.listNativeExperimentsRaw(limit, offset).block();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String normalizeStatus(String s) {
        if (s == null) return "draft";
        return switch (s.toLowerCase()) {
            case "running", "active" -> "running";
            case "stopped", "paused", "finished" -> "stopped";
            case "archived" -> "archived";
            default -> "draft";
        };
    }

    private Map<String, Object> parseExpToMap(String raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode root = om.readTree(raw);
            JsonNode exp = root.has("experiment") ? root.get("experiment") : root;
            result.put("gbExperimentId", exp.path("id").asText());
            result.put("name", exp.path("name").asText());
            result.put("trackingKey", exp.path("trackingKey").asText());
            result.put("status", exp.path("status").asText());
            result.put("hypothesis", exp.path("hypothesis").asText());
            result.put("description", exp.path("description").asText());
            // variations summary
            var variations = exp.path("variations");
            if (variations.isArray()) {
                result.put("variationCount", variations.size());
            }
            // phase targeting
            var phases = exp.path("phases");
            if (phases.isArray() && !phases.isEmpty()) {
                result.put("condition", phases.get(0).path("condition").asText("{}"));
                result.put("coverage", phases.get(0).path("coverage").asDouble(1.0));
            }
        } catch (Exception e) {
            result.put("raw", raw);
        }
        return result;
    }
}
