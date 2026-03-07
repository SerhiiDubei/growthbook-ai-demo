package com.example.gb.ai.tools;

import com.example.gb.service.GbAdminService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only GrowthBook tools for debug/verification.
 * All feature writes happen exclusively through ExperimentTools (experiment lifecycle).
 * The GrowthBook SDK performs client-side variant assignment — never server-side.
 */
@Component
@RequiredArgsConstructor
public class GrowthBookTools {

    private static final Logger log = LoggerFactory.getLogger(GrowthBookTools.class);

    private final GbAdminService gbAdmin;

    @Tool("Get raw JSON of a GrowthBook feature by id (debug/verification only).")
    public String getFeatureRaw(@P("Feature id/key in GrowthBook") String featureId) {
        try {
            log.info("[GB TOOL] getFeatureRaw featureId={}", featureId);
            String res = gbAdmin.getFeatureRaw(featureId).blockOptional().orElse("");
            log.info("[GB TOOL] getFeatureRaw OK len={}", res == null ? 0 : res.length());
            return res;
        } catch (Exception e) {
            log.error("[GB TOOL] getFeatureRaw FAILED featureId={}", featureId, e);
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("List all GrowthBook features raw JSON (debug/verification only).")
    public String listFeaturesRaw() {
        try {
            log.info("[GB TOOL] listFeaturesRaw");
            String res = gbAdmin.listFeatures().blockOptional().orElse("");
            log.info("[GB TOOL] listFeaturesRaw OK len={}", res == null ? 0 : res.length());
            return res;
        } catch (Exception e) {
            log.error("[GB TOOL] listFeaturesRaw FAILED", e);
            return "ERROR: " + safeMsg(e);
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
