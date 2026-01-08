// Controller
package com.example.gb.controller;

import com.example.gb.model.dto.DomEventPayload;
import com.example.gb.model.dto.DomEventStatsDTO;
import com.example.gb.service.DomEventService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/gb/dom-events")
public class DomEventsController {

    private final DomEventService domEventService;

    public DomEventsController(DomEventService domEventService) {
        this.domEventService = domEventService;
    }

    @PostMapping
    public ResponseEntity<Void> save(@RequestBody DomEventPayload payload) {
        log.info("DOM-EVENT: type={} url={} feature={} variant={} selector={} session={}",
                payload.getEventType(),
                payload.getUrl(),
                payload.getFeatureKey(),
                payload.getVariant(),
                payload.getSelector(),
                payload.getSessionId()
        );

        if (payload.getItems() != null && !payload.getItems().isEmpty()) {
            log.debug("DOM-EVENT VIEW items: {}", payload.getItems());
        }


        domEventService.save(payload);
        // TODO: тут пізніше:
        //  - агрегації / статистика / дашборд

        return ResponseEntity.accepted().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<List<DomEventStatsDTO>> stats(
            @RequestParam String featureKey,
            @RequestParam(defaultValue = "7") int days
    ) {
        log.info("📊 DOM-EVENT stats: featureKey={}, days={}", featureKey, days);
        List<DomEventStatsDTO> stats = domEventService.getStats(featureKey, days);
        return ResponseEntity.ok(stats);
    }
}
