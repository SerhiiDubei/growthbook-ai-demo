package com.example.gb.ai.tools;

import com.example.gb.model.dto.DomEventStatsDTO;
import com.example.gb.service.DomEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DomAnalyticsTools {

    private final DomEventService domEventService;
    private final ObjectMapper objectMapper; // можеш заюзати існуючий bean Jackson

    @Tool("""
        Get click-through statistics for a DOM/GrowthBook feature by its featureKey
        for the last N days. Returns JSON array of objects:
        [{featureKey, variant, views, clicks, ctr, fromTs, toTs}, ...].
        Use this BEFORE proposing optimizations, so you base your decisions on real numbers.
        """)
    public String getFeatureStats(String featureKey, int lastDays) {
        List<DomEventStatsDTO> stats = domEventService.getStats(featureKey, lastDays);
        try {
            return objectMapper.writeValueAsString(stats);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize stats to JSON", e);
        }
    }
}
