package com.example.gb.service;

import com.example.gb.model.Experiment;
import com.example.gb.model.ExperimentEvent;
import com.example.gb.repository.ExperimentEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentEventWriter {

    private final ExperimentEventRepository eventRepo;
    private final ObjectMapper objectMapper;

    public void lifecycle(
            Experiment exp,
            String op,                 // start|pause|resume|finish|reset|fail|create|update
            String actor,              // "agent" | "human" | username | etc
            String recipeHash,         // sha256 of recipeJson string
            Map<String, Object> meta,  // дод.інфа
            Throwable errorOrNull
    ) {
        try {
            ExperimentEvent ev = new ExperimentEvent();

            // Ключ фічі GB — ок
            ev.setFeatureKey(exp.getFeatureKey());

            ev.setExperiment(exp);


            // ⚠️ variation НЕ = status. Краще:
            // - exp.getKey() (унікально в межах pageKey)
            // - або "exp:" + exp.getKey()
            ev.setVariation(exp.getKey());

            // page: логічно зберігати pageKey як pageId
            ev.setPage(exp.getPageKey());

            // action/op: ок
            ev.setAction(op);

            // sessionTag: actor сюди не пхаємо. Якщо нема окремого поля під actor — кладемо в metaJson.
            // ev.setSessionTag(actor);  // ❌ прибрав

            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("experimentId", exp.getId());
            out.put("pageKey", exp.getPageKey());
            out.put("pageUrl", exp.getPageUrl()); // ✅ корисно
            out.put("key", exp.getKey());
            out.put("featureKey", exp.getFeatureKey());
            out.put("status", exp.getStatus().name());
            out.put("autonomyLevel", exp.getAutonomyLevel().name());
            out.put("recipeHash", recipeHash);

            // ✅ actor — тут
            out.put("actor", actor);

            if (meta != null && !meta.isEmpty()) out.put("meta", meta);
            if (errorOrNull != null) out.put("error", safeMsg(errorOrNull));

            ev.setMetaJson(objectMapper.writeValueAsString(out));

            eventRepo.save(ev);
            log.info("📝 experiment_event saved op={} expId={} featureKey={}", op, exp.getId(), exp.getFeatureKey());
        } catch (Exception e) {
            // Не валимо бізнес-операцію через логування
            log.warn("⚠️ experiment_event save failed op={} expId={} : {}", op, exp.getId(), safeMsg(e));
        }
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
