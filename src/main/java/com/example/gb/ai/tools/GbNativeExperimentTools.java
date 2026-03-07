package com.example.gb.ai.tools;

import com.example.gb.model.GbNativeExperiment;
import com.example.gb.service.GbAdminService;
import com.example.gb.service.GbNativeExperimentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent tools for managing native GrowthBook Experiments.
 *
 * These tools create/update/start/stop REAL GrowthBook experiments via the Admin API
 * with full targeting rule support (country, device, user segment, etc.).
 * All operations are persisted to the local DB (gb_native_experiments table).
 *
 * FLOW:
 *   createGbExperiment → (optional) startGbExperiment → getGbExperiment (check status)
 *   → stopGbExperiment (when done)
 */
@Component
@RequiredArgsConstructor
public class GbNativeExperimentTools {

    private static final Logger log = LoggerFactory.getLogger(GbNativeExperimentTools.class);
    private static final String ACTOR = "agent";

    private final GbNativeExperimentService service;
    private final GbAdminService gbAdmin;
    private final ObjectMapper om;

    // -------------------------------------------------------------------------
    // CREATE
    // -------------------------------------------------------------------------

    @Tool("""
          Create a native GrowthBook Experiment with full targeting support.
          The experiment is saved to DB and pushed to GrowthBook Admin API.

          VARIATIONS format (JSON array):
            [{"key":"control","name":"Control Group"},{"key":"treatment","name":"New Variant"}]
          More variants allowed: [{"key":"A"},{"key":"B"},{"key":"C"}]

          WEIGHTS format (JSON array, must sum to 1.0):
            [0.5, 0.5]  — equal split
            [0.34, 0.33, 0.33]  — three-way split

          TARGETING CONDITION (JSON — GrowthBook condition syntax):
            {}                              — all users (no filter)
            {"country": "UA"}               — users from Ukraine only
            {"deviceType": "mobile"}        — mobile users only
            {"premium": true}               — premium users only
            {"$and":[{"country":"UA"},{"premium":true}]}  — AND conditions
            {"$or":[{"country":"UA"},{"country":"US"}]}   — OR conditions
            {"age": {"$gte": 18}}           — users aged 18+

          COVERAGE: 0.0..1.0 — fraction of traffic included (default 1.0 = 100%)

          STATUS: draft (default) or running (start immediately)

          Returns JSON: {ok, gbExperimentId, trackingKey, status, name, ...}
          """)
    public String createGbExperiment(
            @P("Unique tracking key, e.g. hero_button_color_01") String trackingKey,
            @P("Human-readable experiment name") String name,
            @P(value = "Hypothesis (optional)", required = false) String hypothesis,
            @P(value = "Description (optional)", required = false) String description,
            @P("Variations JSON array, e.g. [{\"key\":\"control\",\"name\":\"Control\"},{\"key\":\"treatment\",\"name\":\"Blue Button\"}]")
                String variationsJson,
            @P("Weights JSON array summing to 1.0, e.g. [0.5,0.5]") String weightsJson,
            @P("Targeting condition JSON. Use {} for all users, or {\"country\":\"UA\"} etc.") String targetingCondition,
            @P("Traffic coverage 0.0..1.0 (default 1.0 = all traffic)") double coverage,
            @P("Initial status: draft or running") String status
    ) {
        try {
            log.info("[GB-EXP TOOL] createGbExperiment trackingKey={}", trackingKey);

            GbNativeExperiment saved = service.create(
                    trackingKey, name, description, hypothesis,
                    variationsJson, weightsJson, targetingCondition,
                    coverage, status, ACTOR
            );

            return ok(saved, Map.of("op", "create"));

        } catch (Exception e) {
            log.error("[GB-EXP TOOL] createGbExperiment FAILED trackingKey={}", trackingKey, e);
            return error("createGbExperiment", e);
        }
    }

    // -------------------------------------------------------------------------
    // STATUS TRANSITIONS
    // -------------------------------------------------------------------------

    @Tool("""
          Start a GrowthBook Experiment (set status → running).
          GrowthBook SDK will begin assigning users to variants automatically.
          Returns JSON: {ok, gbExperimentId, status, ...}
          """)
    public String startGbExperiment(
            @P("GrowthBook experiment ID (starts with exp_...)") String gbExperimentId
    ) {
        try {
            log.info("[GB-EXP TOOL] startGbExperiment id={}", gbExperimentId);
            GbNativeExperiment saved = service.start(gbExperimentId);
            return ok(saved, Map.of("op", "start"));
        } catch (Exception e) {
            log.error("[GB-EXP TOOL] startGbExperiment FAILED id={}", gbExperimentId, e);
            return error("startGbExperiment", e);
        }
    }

    @Tool("""
          Stop a GrowthBook Experiment (set status → stopped).
          Returns JSON: {ok, gbExperimentId, status, ...}
          """)
    public String stopGbExperiment(
            @P("GrowthBook experiment ID (starts with exp_...)") String gbExperimentId
    ) {
        try {
            log.info("[GB-EXP TOOL] stopGbExperiment id={}", gbExperimentId);
            GbNativeExperiment saved = service.stop(gbExperimentId);
            return ok(saved, Map.of("op", "stop"));
        } catch (Exception e) {
            log.error("[GB-EXP TOOL] stopGbExperiment FAILED id={}", gbExperimentId, e);
            return error("stopGbExperiment", e);
        }
    }

    @Tool("""
          Archive a GrowthBook Experiment (set status → archived). Experiment is
          no longer visible in the default GrowthBook experiments list.
          Returns JSON: {ok, gbExperimentId, status, ...}
          """)
    public String archiveGbExperiment(
            @P("GrowthBook experiment ID (starts with exp_...)") String gbExperimentId
    ) {
        try {
            log.info("[GB-EXP TOOL] archiveGbExperiment id={}", gbExperimentId);
            GbNativeExperiment saved = service.archive(gbExperimentId);
            return ok(saved, Map.of("op", "archive"));
        } catch (Exception e) {
            log.error("[GB-EXP TOOL] archiveGbExperiment FAILED id={}", gbExperimentId, e);
            return error("archiveGbExperiment", e);
        }
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    @Tool("""
          Get details of a GrowthBook Experiment by its GB ID.
          Fetches fresh data from GrowthBook API and syncs status to DB.
          Returns JSON: {ok, gbExperimentId, name, trackingKey, status, condition, coverage, variationCount, ...}
          """)
    public String getGbExperiment(
            @P("GrowthBook experiment ID (starts with exp_...)") String gbExperimentId
    ) {
        try {
            log.info("[GB-EXP TOOL] getGbExperiment id={}", gbExperimentId);
            Map<String, Object> data = service.getFromGbAndSync(gbExperimentId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.putAll(data);
            return toJson(out);
        } catch (Exception e) {
            log.error("[GB-EXP TOOL] getGbExperiment FAILED id={}", gbExperimentId, e);
            return error("getGbExperiment", e);
        }
    }

    @Tool("""
          List GrowthBook Experiments stored in local DB (created by agent).
          Returns JSON: {ok, page, size, totalElements, items:[{gbExperimentId, name, trackingKey, status, ...}]}
          """)
    public String listGbExperimentsFromDb(
            @P("Page number (0-based)") int page,
            @P("Page size (max 50)") int size
    ) {
        try {
            log.info("[GB-EXP TOOL] listGbExperimentsFromDb page={} size={}", page, size);
            Page<GbNativeExperiment> p = service.listFromDb(page, size);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("page", p.getNumber());
            out.put("size", p.getSize());
            out.put("totalElements", p.getTotalElements());
            out.put("items", p.getContent().stream().map(this::brief).toList());
            return toJson(out);

        } catch (Exception e) {
            log.error("[GB-EXP TOOL] listGbExperimentsFromDb FAILED", e);
            return error("listGbExperimentsFromDb", e);
        }
    }

    @Tool("""
          List GrowthBook Experiments directly from GrowthBook API (all experiments, not just agent-created).
          Returns raw JSON from GrowthBook. Use this to discover existing experiments.
          limit: 1..100, offset: 0+
          """)
    public String listGbExperimentsFromApi(
            @P("Max results to return (1..100)") int limit,
            @P("Offset for pagination (0+)") int offset
    ) {
        try {
            log.info("[GB-EXP TOOL] listGbExperimentsFromApi limit={} offset={}", limit, offset);
            return service.listFromGb(limit, offset);
        } catch (Exception e) {
            log.error("[GB-EXP TOOL] listGbExperimentsFromApi FAILED", e);
            return error("listGbExperimentsFromApi", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> brief(GbNativeExperiment e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("gbExperimentId", e.getGbExperimentId());
        m.put("name", e.getName());
        m.put("trackingKey", e.getTrackingKey());
        m.put("status", e.getStatus());
        m.put("featureKey", e.getFeatureKey());
        m.put("targetingCondition", e.getTargetingCondition());
        m.put("coverage", e.getCoverage());
        m.put("createdBy", e.getCreatedBy());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private String ok(GbNativeExperiment e, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", e.getId());
        out.put("gbExperimentId", e.getGbExperimentId());
        out.put("name", e.getName());
        out.put("trackingKey", e.getTrackingKey());
        out.put("status", e.getStatus());
        out.put("featureKey", e.getFeatureKey());
        out.put("targetingCondition", e.getTargetingCondition());
        out.put("coverage", e.getCoverage());
        out.put("createdBy", e.getCreatedBy());
        out.put("createdAt", e.getCreatedAt());
        if (extra != null) out.putAll(extra);
        return toJson(out);
    }

    private String error(String op, Exception e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("op", op);
        out.put("error", safeMsg(e));
        return toJson(out);
    }

    private String toJson(Object obj) {
        try {
            return om.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"json_serialize_failed\"}";
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
