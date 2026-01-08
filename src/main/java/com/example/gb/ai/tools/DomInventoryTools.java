package com.example.gb.ai.tools;

import com.example.gb.repository.DomInventoryLatestRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DomInventoryTools {

    private final DomInventoryLatestRepo latestRepo;
    private final ObjectMapper objectMapper;
    private final com.example.gb.service.GbAdminService gbAdmin;

    // ---------- PAGE HELPERS ----------

    @Tool("""
          List known pages that have DOM inventory (most recently updated first).
          Returns JSON array of objects: [{"pageKey":"...","pageUrl":"..."}, ...]
          """)
    public String listPagesJson() {
        try {
            List<Map<String, Object>> pages = latestRepo.findTop50ByOrderByUpdatedAtDesc().stream()
                    .map(e -> Map.<String, Object>of(
                            "pageKey", e.getPageKey(),
                            "pageUrl", e.getPageUrl()
                    ))
                    .toList();
            return toJson(pages);
        } catch (Exception e) {
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Resolve pageKey from host, port, and pageId.
          Example: host=localhost port=8080 pageId=home -> localhost_8080__home
          """)
    public String resolvePageKey(
            @P("Host name, e.g. localhost") String host,
            @P("Port, e.g. 8080") Integer port,
            @P("Page id, e.g. home") String pageId
    ) {
        String h = (host == null || host.isBlank()) ? "localhost" : host.trim();
        int p = (port == null) ? 80 : port;
        String pid = (pageId == null || pageId.isBlank()) ? "home" : pageId.trim();
        return h + "_" + p + "__" + pid;
    }

    // ---------- INVENTORY (JSON) ----------

    @Tool("""
          Get latest DOM inventory items for a pageKey.
          Returns JSON object:
          {
            "pageKey":"...",
            "pageUrl":"...",
            "itemsHash":"...",
            "items":[{"kind":"...","text":"...","selector":"...","featureKey":"..."}, ...]
          }
          """)
    public String getInventoryItemsByPageKeyJson(@P("Page key, e.g. localhost_8080__home") String pageKey) {
        try {
            var e = latestRepo.findByPageKey(pageKey)
                    .orElseThrow(() -> new IllegalArgumentException("No inventory for pageKey=" + pageKey));

            List<Map<String, Object>> items = parseItems(e.getItemsJson()).stream()
                    .map(i -> Map.<String, Object>of(
                            "kind", i.getKind(),
                            "text", i.getText(),
                            "selector", i.getSelector(),
                            "featureKey", i.getFeatureKey()
                    ))
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pageKey", e.getPageKey());
            result.put("pageUrl", e.getPageUrl());
            result.put("itemsHash", e.getItemsHash());
            result.put("items", items);

            return toJson(result);
        } catch (Exception ex) {
            return "ERROR: " + safeMsg(ex);
        }
    }

    @Tool("""
          Get latest DOM inventory items for a pageUrl.
          Returns same JSON structure as getInventoryItemsByPageKeyJson.
          """)
    public String getInventoryItemsByUrlJson(@P("Page URL") String pageUrl) {
        try {
            var e = latestRepo.findFirstByPageUrlOrderByUpdatedAtDesc(pageUrl)
                    .orElseThrow(() -> new IllegalArgumentException("No inventory for pageUrl=" + pageUrl));

            List<Map<String, Object>> items = parseItems(e.getItemsJson()).stream()
                    .map(i -> Map.<String, Object>of(
                            "kind", i.getKind(),
                            "text", i.getText(),
                            "selector", i.getSelector(),
                            "featureKey", i.getFeatureKey()
                    ))
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pageKey", e.getPageKey());
            result.put("pageUrl", e.getPageUrl());
            result.put("itemsHash", e.getItemsHash());
            result.put("items", items);

            return toJson(result);
        } catch (Exception ex) {
            return "ERROR: " + safeMsg(ex);
        }
    }

    // ---------- INVENTORY SEARCH ----------

    @Tool("""
          Find a single best matching inventory item by pageKey.
          Provide kindHint (cta/heading) and/or textContains.
          Returns JSON object:
          {"kind":"...","text":"...","selector":"...","featureKey":"..."}
          """)
    public String findInventoryItemJson(
            @P("Page key") String pageKey,
            @P(value = "Kind hint, e.g. cta/heading (optional)", required = false) String kindHint,
            @P(value = "Text contains filter (optional)", required = false) String textContains
    ) {
        try {
            // беремо інвентар
            var e = latestRepo.findByPageKey(pageKey)
                    .orElseThrow(() -> new IllegalArgumentException("No inventory for pageKey=" + pageKey));

            String k = norm(kindHint);
            String q = norm(textContains);

            // парсимо і конвертимо в прості мапи
            List<ItemJson> parsed = parseItems(e.getItemsJson());

            var candidates = parsed.stream()
                    .filter(i -> k.isBlank() || norm(i.getKind()).contains(k))
                    .filter(i -> q.isBlank() || norm(i.getText()).contains(q))
                    .toList();

            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("No matching item for pageKey=" + pageKey +
                        ", kindHint=" + kindHint + ", textContains=" + textContains);
            }

            ItemJson best = candidates.stream()
                    .sorted(Comparator
                            .comparingInt((ItemJson i) -> score(i, q)).reversed()
                            .thenComparingInt(i -> i.getSelector() == null ? 9999 : i.getSelector().length())
                    )
                    .findFirst()
                    .orElseThrow();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", best.getKind());
            out.put("text", best.getText());
            out.put("selector", best.getSelector());
            out.put("featureKey", best.getFeatureKey());

            return toJson(out);
        } catch (Exception ex) {
            return "ERROR: " + safeMsg(ex);
        }
    }

    // ---------- LEGACY (RAW) ----------

    @Tool("""
          Get latest DOM inventory raw JSON by pageKey (debug tool).
          Returns JSON object:
          {"pageKey":"...","pageUrl":"...","itemsHash":"...","itemsJson":"..."}
          """)
    public String getLatestInventoryRawByPageKeyJson(@P("Page key") String pageKey) {
        try {
            var e = latestRepo.findByPageKey(pageKey)
                    .orElseThrow(() -> new IllegalArgumentException("No inventory for pageKey=" + pageKey));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pageKey", e.getPageKey());
            result.put("pageUrl", e.getPageUrl());
            result.put("itemsHash", e.getItemsHash());
            result.put("itemsJson", e.getItemsJson());

            return toJson(result);
        } catch (Exception ex) {
            return "ERROR: " + safeMsg(ex);
        }
    }

    @Tool("Ensure a JSON feature exists (creates skeleton if missing). Returns OK or ERROR: ...")
    public String ensureFeatureExists(@P("Feature key") String featureId) {
        try {
            gbAdmin.ensureJsonFeatureSkeleton(featureId)
                    .blockOptional()
                    .orElse(null);
            return "OK";
        } catch (Exception e) {
            return "ERROR: " + safeMsg(e);
        }
    }

    // ---------- Parsing helpers ----------

    private List<ItemJson> parseItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<ItemJson>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse items_json", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "ERROR: " + safeMsg(e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static int score(ItemJson i, String q) {
        if (q == null || q.isBlank()) return 0;
        String t = norm(i.getText());
        if (t.equals(q)) return 100;
        if (t.contains(q)) return 50;
        return 0;
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    // DTO for JSON parsing (items_json)
    public static class ItemJson {
        private String kind;
        private String text;
        private String selector;
        private String featureKey;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getSelector() { return selector; }
        public void setSelector(String selector) { this.selector = selector; }

        public String getFeatureKey() { return featureKey; }
        public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }
    }
}
