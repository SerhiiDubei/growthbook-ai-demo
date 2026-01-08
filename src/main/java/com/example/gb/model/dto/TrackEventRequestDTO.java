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

    /** Додаткові дані (будь-який JSON ↔ Map) */
    private Map<String, Object> meta;
}
