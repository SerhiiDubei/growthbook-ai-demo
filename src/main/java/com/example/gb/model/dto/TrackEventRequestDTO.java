package com.example.gb.model.dto;

import lombok.Data;

import java.util.Map;

@Data
public class TrackEventRequestDTO {

    /** GrowthBook feature id, типу: localhost_8080__home__cta_... */
    private String featureKey;

    /** Варіація: "on", "off", "A", "B", ... */
    private String variation;

    /** Твій sessionTag / gbtag / gb_tag cookie */
    private String sessionTag;

    /** Логічна сторінка: "home", "signup", "pricing"... */
    private String page;

    /** Подія: "view", "click", "conversion", "custom" і т.д. */
    private String action;

    /**
     * A/B variant key assigned by the bridge (e.g. "control", "treatment").
     * Sent back from /bridge/recipe response and forwarded here by ai-bridge.js.
     * Null for events without A/B context.
     */
    private String variantKey;

    /** Додаткові дані (будь-який JSON ↔ Map) */
    private Map<String, Object> meta;
}
