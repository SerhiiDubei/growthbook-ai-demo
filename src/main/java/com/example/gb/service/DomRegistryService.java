package com.example.gb.service;

import com.example.gb.controller.DomInventoryController.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomRegistryService {

    /**
     * key:  site__page (host[_port]__home)
     * val:  selector -> featureKey (той що прийшов з bridge)
     */
    private final Map<String, Map<String, String>> registry = new ConcurrentHashMap<>();

    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        registry.forEach((k, v) -> out.put(k, new LinkedHashMap<>(v)));
        log.info("🗺 DomRegistry snapshot: {}", out.keySet());
        return out;
    }

    /**
     * Sync inventory -> in-memory selector->featureKey map.
     * IMPORTANT: DOES NOT create/update GrowthBook features.
     */
    public void syncAsync(String origin, java.util.List<Item> items) {
        if (origin == null || "unknown".equals(origin) || items == null || items.isEmpty()) {
            log.info("↷ DomRegistry: nothing to sync (origin={}, items={})",
                    origin, items == null ? 0 : items.size());
            return;
        }

        String site = originHost(origin);
        String pageKey = site + "__home"; // поки так, як було

        Map<String, String> selectors = registry.computeIfAbsent(pageKey, k -> new ConcurrentHashMap<>());

        log.info("🧠 DomRegistry: sync for {} ({} items)", pageKey, items.size());

        for (Item it : items) {
            String selector = safe(it.getSelector());
            String kindRaw = safe(it.getKind()).toLowerCase(Locale.ROOT);
            String featureKey = safe(it.getFeatureKey());

            if (selector.isEmpty() || kindRaw.isEmpty()) {
                log.debug("↷ skip item without kind/selector: {}", it);
                continue;
            }

            // опціонально: залишимо тільки підтримувані типи (як було)
            if (!isSupportedKind(kindRaw)) {
                log.debug("↷ skip unsupported kind='{}': {}", kindRaw, it);
                continue;
            }

            if (featureKey.isEmpty()) {
                // ВАЖЛИВО: якщо featureKey пустий — не генеруємо на сервері, щоб не було 2 схем.
                log.warn("⚠ DomRegistry: item without featureKey from bridge. selector='{}', kind='{}' (skip)", selector, kindRaw);
                continue;
            }

            // якщо вже є mapping для цього селектора — не перезаписуємо
            selectors.putIfAbsent(selector, featureKey);
        }
    }

    private static boolean isSupportedKind(String kindRaw) {
        return switch (kindRaw) {
            case "heading", "h1", "h2", "h3", "cta", "button", "btn", "link" -> true;
            default -> false;
        };
    }

    private static String originHost(String origin) {
        try {
            URI uri = URI.create(origin);
            String host = Optional.ofNullable(uri.getHost()).orElse("site");
            int port = uri.getPort();
            return (port > 0) ? host + "_" + port : host;
        } catch (Exception e) {
            return "site";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
