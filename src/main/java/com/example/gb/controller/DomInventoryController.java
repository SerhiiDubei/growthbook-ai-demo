package com.example.gb.controller;

import com.example.gb.service.DomInventoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    public ResponseEntity<Map<String, Object>> save(@RequestBody DomInventory payload) {

        String url = Optional.ofNullable(payload.getUrl()).orElse("");
        var items = Optional.ofNullable(payload.getInventory()).orElseGet(List::of);

        var res = domInventoryService.saveFromPageUrl(url, items);

        log.info("📥 dom-inventory POST url={} inventoryItems={} savedPageKey={} hash={}",
                url, items.size(), res.pageKey(), res.hash());

        // Return canonical featureKeys so frontend can use them for A/B recipe lookups
        return ResponseEntity.accepted().body(Map.of(
                "pageKey", res.pageKey(),
                "items", res.featureItems()
        ));
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        private String selector;   // CSS selector
        private String kind;       // heading/cta/...
        private String text;       // current text
        private String featureKey; // may be ignored by backend
        // Child elements with their own styles that may override parent CSS
        private List<Map<String, Object>> styledChildren;
    }
}
