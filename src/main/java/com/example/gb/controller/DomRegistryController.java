package com.example.gb.controller;

import com.example.gb.controller.DomInventoryController.Item;
import com.example.gb.service.DomRegistryService;
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
@RequiredArgsConstructor
@RequestMapping("/api/gb/dom-registry")
public class DomRegistryController {

    private final DomRegistryService registry;

    /**
     * Викликається автоматично з DomInventoryController.save
     * або вручну для тесту.
     */
    @PostMapping("/sync")
    public ResponseEntity<Void> sync(@RequestBody DomInventoryController.DomInventory payload) {
        String url = Optional.ofNullable(payload.getUrl()).orElse("unknown");
        List<Item> items = Optional.ofNullable(payload.getInventory()).orElseGet(List::of);
        log.info("🧩 registry/sync url={} → {} items", url, items.size());

        registry.syncAsync(url, items);

        return ResponseEntity.accepted()
                .location(URI.create("/api/gb/dom-registry/map"))
                .build();
    }

    /** Подивитись, які фічі згенерувалися (pageKey -> selector -> featureKey) */
    @GetMapping("/map")
    public ResponseEntity<Map<String, Map<String, String>>> map() {
        return ResponseEntity.ok(registry.snapshot());
    }
}
