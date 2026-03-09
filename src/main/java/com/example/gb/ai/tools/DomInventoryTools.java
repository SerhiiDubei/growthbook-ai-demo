package com.example.gb.ai.tools;

import com.example.gb.repository.DomInventoryLatestRepo;
import com.example.gb.util.DomPageKeyUtil;
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
          Resolve pageKey from a full pageUrl using backend logic (canonical).
          Example: http://localhost:8080/ -> localhost_8080__home
          """)
    public String resolvePageKeyFromUrl(@P("Page URL") String pageUrl) {
        try {
            if (pageUrl == null || pageUrl.isBlank()) {
                throw new IllegalArgumentException("pageUrl is blank");
            }
            return DomPageKeyUtil.pageKeyFromUrl(pageUrl);
        } catch (Exception e) {
            return "ERROR: " + safeMsg(e);
        }
    }

    @Tool("""
          Resolve pageKey from host, port, and pageId (manual helper).
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
            String normalizedUrl = DomPageKeyUtil.normalizeUrl(pageUrl);
            // Try by normalized URL first, fallback to pageKey lookup
            var e = latestRepo.findFirstByPageUrlOrderByUpdatedAtDesc(normalizedUrl)
                    .or(() -> latestRepo.findByPageKey(DomPageKeyUtil.pageKeyFromUrl(normalizedUrl)))
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
            var e = latestRepo.findByPageKey(pageKey)
                    .orElseThrow(() -> new IllegalArgumentException("No inventory for pageKey=" + pageKey));

            String k = norm(kindHint);
            String q = norm(textContains);

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

    // ---------- SELECTOR VERIFICATION ----------

    @Tool("""
          Verify that a CSS selector exists in the stored DOM inventory for a page.
          Use this BEFORE passing any selector to RecipeTools to confirm it points to
          the expected element.
          Returns JSON:
            {"found":true,  "selector":"...","kind":"...","text":"...","featureKey":"..."}
            {"found":false, "selector":"...","error":"No item with this selector"}
          ALWAYS call this for every selector you plan to use in addSwapVariant,
          addTextVariant, addStyleVariant, etc. to confirm text matches your expectation.
          """)
    public String verifySelectorInInventory(
            @P("Page key") String pageKey,
            @P("CSS selector to verify") String selector
    ) {
        try {
            var e = latestRepo.findByPageKey(pageKey)
                    .orElseThrow(() -> new IllegalArgumentException("No inventory for pageKey=" + pageKey));

            String normalizedSelector = selector == null ? "" : selector.trim();

            List<ItemJson> items = parseItems(e.getItemsJson());
            var match = items.stream()
                    .filter(i -> normalizedSelector.equalsIgnoreCase(
                            i.getSelector() == null ? "" : i.getSelector().trim()))
                    .findFirst();

            Map<String, Object> out = new LinkedHashMap<>();
            if (match.isPresent()) {
                ItemJson item = match.get();
                out.put("found", true);
                out.put("selector", item.getSelector());
                out.put("kind", item.getKind());
                out.put("text", item.getText());
                out.put("featureKey", item.getFeatureKey());
            } else {
                out.put("found", false);
                out.put("selector", normalizedSelector);
                out.put("error", "No item with this selector found in inventory for pageKey=" + pageKey);
                // suggest similar items by text search
                List<String> suggestions = items.stream()
                        .filter(i -> i.getSelector() != null && !i.getSelector().isBlank())
                        .limit(5)
                        .map(i -> i.getSelector() + "  [" + i.getKind() + ": " + i.getText() + "]")
                        .toList();
                if (!suggestions.isEmpty()) {
                    out.put("availableSelectors", suggestions);
                }
            }
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

    // DTO for JSON parsing (itemsJson)
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
