package com.example.gb.service;

import com.example.gb.controller.DomInventoryController.Item;
import com.example.gb.model.DomInventoryLatest;
import com.example.gb.repository.DomInventoryRepository;
import com.example.gb.util.DomPageKeyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomInventoryService {

    private final DomInventoryRepository repo;
    private final ObjectMapper objectMapper;

    /**
     * Зберігає "latest snapshot" для pageKey.
     * Якщо items не змінились (hash той самий) — оновлюємо тільки lastSeenAt.
     */
    public SaveResult saveFromPageUrl(String pageUrl, List<Item> items) {

        String origin = DomPageKeyUtil.originFromUrl(pageUrl);
        String pageKey = DomPageKeyUtil.pageKeyFromUrl(pageUrl);

        List<Item> safeItems = normalize(items);

        String json = toStableJson(safeItems);
        String hash = sha256(json);

        LocalDateTime now = LocalDateTime.now();

        DomInventoryLatest entity = repo.findByPageKey(pageKey)
                .map(existing -> {
                    boolean changed = !Objects.equals(existing.getItemsHash(), hash);

                    existing.setOrigin(origin);
                    existing.setPageUrl(pageUrl);
                    existing.setLastSeenAt(now);

                    if (changed) {
                        existing.setItemsJson(json);
                        existing.setItemsHash(hash);
                        log.info("📦 DomInventory changed: pageKey={}, items={}, hash={}", pageKey, safeItems.size(), shortHash(hash));
                        return existing;
                    } else {
                        // важливо: не перезаписуємо itemsJson зайвий раз
                        log.debug("📦 DomInventory unchanged: pageKey={}, items={}, hash={}", pageKey, safeItems.size(), shortHash(hash));
                        return existing;
                    }
                })
                .orElseGet(() -> {
                    DomInventoryLatest created = new DomInventoryLatest();
                    created.setPageKey(pageKey);
                    created.setOrigin(origin);
                    created.setPageUrl(pageUrl);
                    created.setItemsJson(json);
                    created.setItemsHash(hash);
                    created.setLastSeenAt(now);

                    log.info("📦 DomInventory created: pageKey={}, items={}, hash={}", pageKey, safeItems.size(), shortHash(hash));
                    return created;
                });

        DomInventoryLatest saved = repo.save(entity);

        return new SaveResult(pageKey, origin, safeItems.size(), saved.getItemsHash());
    }

    /**
     * Для консолі: показати останній інвентар по origin
     * (якщо ти поки що не передаєш pageKey у GET).
     */
    public List<Item> getLatestByOrigin(String origin) {
        return repo.findTopByOriginOrderByLastSeenAtDesc(origin)
                .map(this::fromJson)
                .orElseGet(List::of);
    }

    /**
     * Для агента: отримати інвентар конкретної сторінки
     */
    public List<Item> getByPageUrl(String pageUrl) {
        String pageKey = DomPageKeyUtil.pageKeyFromUrl(pageUrl);
        return repo.findByPageKey(pageKey)
                .map(this::fromJson)
                .orElseGet(List::of);
    }

    // ---------------- helpers ----------------

    private List<Item> normalize(List<Item> items) {
        if (items == null) return List.of();

        // 1) прибрати null/порожні селектори
        List<Item> cleaned = new ArrayList<>();
        for (Item it : items) {
            if (it == null) continue;
            if (it.getSelector() == null || it.getSelector().trim().isBlank()) continue;

            Item x = new Item();
            x.setSelector(it.getSelector().trim());
            x.setKind(it.getKind() == null ? null : it.getKind().trim());
            x.setText(it.getText() == null ? null : trimMax(it.getText().trim(), 200));
            x.setFeatureKey(it.getFeatureKey() == null ? null : it.getFeatureKey().trim());
            cleaned.add(x);
        }

        // 2) стабільне сортування (щоб hash не “плавав”)
        cleaned.sort(Comparator
                .comparing((Item i) -> safe(i.getKind()))
                .thenComparing(i -> safe(i.getSelector()))
                .thenComparing(i -> safe(i.getText()))
                .thenComparing(i -> safe(i.getFeatureKey()))
        );

        return cleaned;
    }

    private String toStableJson(List<Item> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize inventory json", e);
        }
    }

    private List<Item> fromJson(DomInventoryLatest e) {
        try {
            if (e.getItemsJson() == null || e.getItemsJson().isBlank()) return List.of();
            // Jackson сам відновить List<Item>, якщо Item - static class в контролері.
            // Якщо будуть проблеми — винесемо Item в окремий DTO-клас.
            return Arrays.asList(objectMapper.readValue(e.getItemsJson(), Item[].class));
        } catch (Exception ex) {
            log.warn("Failed to parse itemsJson for pageKey={}: {}", e.getPageKey(), ex.getMessage());
            return List.of();
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    private static String shortHash(String h) {
        if (h == null) return "null";
        return h.length() <= 8 ? h : h.substring(0, 8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String trimMax(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record SaveResult(String pageKey, String origin, int items, String hash) {}
}
