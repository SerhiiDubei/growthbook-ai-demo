package com.example.gb.service;

import com.example.gb.model.dto.DomEventItemDTO;
import com.example.gb.model.dto.DomEventPayload;
import com.example.gb.model.DomEvent;
import com.example.gb.model.dto.DomEventStatsDTO;
import com.example.gb.model.dto.DomEventStatsProjection;
import com.example.gb.repository.DomEventRepository;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomEventService {

    private final DomEventRepository repository;

    public void save(DomEventPayload payload) {
        List<DomEvent> events = new ArrayList<>();

        Instant ts = payload.getTimestamp() != null
                ? Instant.ofEpochMilli(payload.getTimestamp())
                : Instant.now();

        // Якщо це VIEW з items → створюємо по одному запису на кожен item
        if ("VIEW".equalsIgnoreCase(payload.getEventType())
                && payload.getItems() != null
                && !payload.getItems().isEmpty()) {

            for (DomEventItemDTO item : payload.getItems()) {
                DomEvent e = baseEvent(payload, ts);
                e.setSelector(item.getSelector());
                e.setFeatureKey(item.getFeatureKey());
                e.setVariant(item.getVariant());
                events.add(e);
            }
        } else {
            // CLICK / CONVERSION або VIEW без items
            DomEvent e = baseEvent(payload, ts);
            e.setSelector(payload.getSelector());
            e.setFeatureKey(payload.getFeatureKey());
            e.setVariant(payload.getVariant());
            events.add(e);
        }

        repository.saveAll(events);
        log.info("💾 DomEventService: saved {} events, type={}, url={}",
                events.size(), payload.getEventType(), payload.getUrl());
    }

    private DomEvent baseEvent(DomEventPayload payload, Instant ts) {
        DomEvent e = new DomEvent();
        e.setSessionId(payload.getSessionId());
        e.setUrl(payload.getUrl());
        e.setEventType(payload.getEventType());
        e.setEventTs(ts);
        return e;
    }

    public List<DomEventStatsDTO> getStats(String featureKey, int daysBack) {
        Instant to = Instant.now();
        Instant from = to.minus(daysBack, ChronoUnit.DAYS);

        List<DomEventStatsProjection> rows =
                repository.aggregateByFeatureSince(featureKey, from);

        return rows.stream()
                .map(r -> {
                    DomEventStatsDTO dto = new DomEventStatsDTO();
                    dto.setFeatureKey(r.getFeatureKey());
                    dto.setVariant(r.getVariant());
                    dto.setViews(r.getViews());
                    dto.setClicks(r.getClicks());
                    dto.setCtr(r.getViews() > 0 ? (double) r.getClicks() / r.getViews() : 0.0);
                    dto.setFromTs(from);
                    dto.setToTs(to);
                    return dto;
                })
                .toList();
    }
}
