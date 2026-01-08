package com.example.gb.controller;

import com.example.gb.service.DomInventoryService;
import com.example.gb.service.DomRegistryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/gb/dom-inventory")
@RequiredArgsConstructor
public class DomInventoryController {

    private final DomInventoryService domInventoryService;
    private final DomRegistryService registry;

    @PostMapping
    public ResponseEntity<Void> save(@RequestBody DomInventory payload) {

        var items = Optional.ofNullable(payload.getInventory()).orElseGet(List::of);

        var res = domInventoryService.saveFromPageUrl(payload.getUrl(), items);
        log.info("📥 dom-inventory POST url={} inventoryItems={} sampleKey={}",
                payload.getUrl(), items.size(),
                items.isEmpty() ? "-" : items.get(0).getFeatureKey());

        registry.syncAsync(res.origin(), items);
        return ResponseEntity.accepted().build();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestParam String origin) {
        var items = domInventoryService.getLatestByOrigin(origin);
        return ResponseEntity.ok(Map.of("origin", origin, "items", items));
    }


    // ---- прості моделі для інвентаря ----
    @Data
    public static class DomInventory {
        private String url;
        private List<Item> inventory;
    }

    @Data
    public static class Item {
        private String selector; // CSS-селектор або data-gb-key
        private String kind;     // heading/cta/...
        private String text;     // поточний текст (для прев’ю)
        private String featureKey;
    }
}
