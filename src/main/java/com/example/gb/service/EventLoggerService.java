package com.example.gb.service;

import com.example.gb.model.ExperimentEvent;
import com.example.gb.repository.ExperimentEventRepository;
import com.example.gb.repository.ExperimentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventLoggerService {

    private final ExperimentEventRepository eventRepo;
    private final ExperimentRepository experimentRepo;
    private final ObjectMapper objectMapper;

    /**
     * Базовий метод — лог події з готовим JSON-рядком для meta.
     *
     * @param featureKey ключ фічі / експерименту (GrowthBook feature id)
     * @param variation  варіація, яку отримав користувач ("on", "off", "A", "B"...)
     * @param sessionTag ідентифікатор сесії / користувача (gbtag, gb_tag cookie)
     * @param page       логічна сторінка ("home", "signup", "pricing"...)
     * @param action     тип події ("view", "click", "conversion", "custom")
     * @param metaJson   довільні дані в JSON-форматі (може бути null)
     */
    @Transactional
    public void logEvent(
            String featureKey,
            String variation,
            String sessionTag,
            String page,
            String action,
            String metaJson
    ) {
        logEvent(featureKey, variation, sessionTag, page, action, null, metaJson);
    }

    /**
     * Full method with variantKey — primary path for A/B tracking.
     */
    @Transactional
    public void logEvent(
            String featureKey,
            String variation,
            String sessionTag,
            String page,
            String action,
            String variantKey,
            String metaJson
    ) {
        try {
            ExperimentEvent e = new ExperimentEvent();

            // Prefer ACTIVE experiment; fall back to most-recently-updated if none active
            experimentRepo.findFirstByFeatureKeyAndStatusOrderByUpdatedAtDesc(
                            featureKey, com.example.gb.model.enums.ExperimentStatus.ACTIVE)
                    .or(() -> experimentRepo.findFirstByFeatureKeyOrderByUpdatedAtDesc(featureKey))
                    .ifPresent(e::setExperiment);

            e.setFeatureKey(featureKey);
            e.setVariation(variation);
            e.setSessionTag(sessionTag);
            e.setPage(page);
            e.setAction(action);
            e.setVariantKey(variantKey);
            e.setMetaJson(metaJson);

            ExperimentEvent saved = eventRepo.save(e);

            log.debug("📊 [EventLogger] saved event id={} feature={} experimentId={} variant={} variation={} action={}",
                    saved.getId(), featureKey,
                    saved.getExperiment() != null ? saved.getExperiment().getId() : null,
                    variantKey, variation, action);
        } catch (Exception ex) {
            log.error("❌ [EventLogger] failed to save event feature={} action={} : {}",
                    featureKey, action, ex.getMessage(), ex);
        }
    }

    /**
     * Зручний оверлоад: meta як Map → ми самі перетворюємо в JSON.
     *
     * @param featureKey ключ фічі
     * @param variation  варіація
     * @param sessionTag сесія
     * @param page       сторінка
     * @param action     дія
     * @param meta       мапа довільних даних (null дозволено)
     */
    @Transactional
    public void logEvent(
            String featureKey,
            String variation,
            String sessionTag,
            String page,
            String action,
            Map<String, Object> meta
    ) {
        logEvent(featureKey, variation, sessionTag, page, action, null, meta);
    }

    @Transactional
    public void logEvent(
            String featureKey,
            String variation,
            String sessionTag,
            String page,
            String action,
            String variantKey,
            Map<String, Object> meta
    ) {
        String json = null;
        if (meta != null && !meta.isEmpty()) {
            try {
                json = objectMapper.writeValueAsString(meta);
            } catch (JsonProcessingException e) {
                log.warn("⚠ [EventLogger] meta serialization failed, meta={}: {}",
                        meta, e.getMessage());
            }
        }
        logEvent(featureKey, variation, sessionTag, page, action, variantKey, json);
    }

    /**
     * Спеціалізований метод: лог події "перегляд експерименту"
     * (аналог Experiment Viewed з GrowthBook).
     */
    @Transactional
    public void logExperimentView(
            String featureKey,
            String variation,
            String sessionTag,
            String page
    ) {
        logEvent(featureKey, variation, sessionTag, page, "view", (String) null);
    }

    /**
     * Спеціалізований метод: лог кліку по CTA.
     */
    @Transactional
    public void logClick(
            String featureKey,
            String variation,
            String sessionTag,
            String page,
            Map<String, Object> meta
    ) {
        logEvent(featureKey, variation, sessionTag, page, "click", meta);
    }

    /**
     * Спеціалізований метод: лог конверсії (signup, purchase, і т.д.).
     */
    @Transactional
    public void logConversion(
            String featureKey,
            String variation,
            String sessionTag,
            String page,
            Map<String, Object> meta
    ) {
        logEvent(featureKey, variation, sessionTag, page, "conversion", meta);
    }
}
