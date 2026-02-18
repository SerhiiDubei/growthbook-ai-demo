package com.example.gb.controller;

import com.example.gb.model.dto.TrackEventRequestDTO;
import com.example.gb.service.EventLoggerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gb/track")
public class TrackingController {

    private final EventLoggerService eventLoggerService;

    @PostMapping
    public ResponseEntity<Void> track(@RequestBody TrackEventRequestDTO req) {
        // трохи захисту від null / пустого
        if (req.getFeatureKey() == null || req.getFeatureKey().isBlank()) {
            log.warn("⚠ [Track] featureKey is empty, skip event: {}", req);
            return ResponseEntity.badRequest().build();
        }

        log.info("📩 Track event: feature={}, variation={}, sessionTag={}, page={}, action={}, meta={}",
                req.getFeatureKey(), req.getVariation(), req.getSessionTag(), req.getPage(),
                req.getAction(), req.getMeta());

        String action = (req.getAction() == null || req.getAction().isBlank())
                ? "custom"
                : req.getAction();

        // Використовуємо базовий метод логування з meta як Map
        eventLoggerService.logEvent(
                req.getFeatureKey(),
                req.getVariation(),
                req.getSessionTag(),
                req.getPage(),
                action,
                req.getVariantKey(),
                req.getMeta()
        );

        // 202 Accepted: подію прийняли, обробка async (БД)
        return ResponseEntity.accepted().build();
    }
}
