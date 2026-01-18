package com.example.gb.controller;

import com.example.gb.service.DomInventoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/gb/dom-inventory")
@RequiredArgsConstructor
public class DomInventoryController {

    private final DomInventoryService domInventoryService;

    @PostMapping
    public ResponseEntity<Void> save(@RequestBody DomInventory payload) {

        String url = Optional.ofNullable(payload.getUrl()).orElse("");
        var items = Optional.ofNullable(payload.getInventory()).orElseGet(List::of);

        var res = domInventoryService.saveFromPageUrl(url, items);

        // ВАЖЛИВО: items тут "сирі" (з bridge), тому featureKey може бути порожнім/не канонічним.
        // Канонічні featureKey вже присвоєні всередині DomInventoryService під час normalize().
        log.info("📥 dom-inventory POST url={} inventoryItems={} savedPageKey={} hash={}",
                url, items.size(), res.pageKey(), res.hash());

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
        private String selector; // CSS selector
        private String kind;     // heading/cta/...
        private String text;     // current text
        private String featureKey; // may be ignored by backend
    }
}
