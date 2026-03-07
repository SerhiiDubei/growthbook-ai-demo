package com.example.gb.ai.tools;

import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.dto.AddVariantRequest;
import com.example.gb.service.ExperimentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level semantic tools for building experiment variants.
 *
 * The agent NEVER writes raw recipeJson or op names.
 * All JSON is constructed here in code — agent only provides human-readable parameters.
 *
 * Each method adds ONE variant to an existing experiment.
 * Use addControlVariant first, then one of the change variants.
 */
@Component
@RequiredArgsConstructor
public class RecipeTools {

    private static final Logger log = LoggerFactory.getLogger(RecipeTools.class);
    private static final String ACTOR = "agent";

    private final ExperimentService experimentService;
    private final ObjectMapper om;

    // -------------------------------------------------------------------------
    // CONTROL (always required as the baseline variant)
    // -------------------------------------------------------------------------

    @Tool("""
          Add a CONTROL variant to an experiment — no DOM changes, original page layout.
          ALWAYS add this first before any treatment variant.
          weight: traffic share 0.0..1.0. All variants across the experiment must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addControlVariant(
            @P("Experiment id") long experimentId,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight
    ) {
        try {
            log.info("[RECIPE TOOL] addControlVariant experimentId={} weight={}", experimentId, weight);
            return saveVariant(experimentId, "control", "Control", weight, "{\"ops\":[]}", 0);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addControlVariant FAILED experimentId={}", experimentId, e);
            return error("addControlVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // LAYOUT — swap two elements
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that SWAPS two DOM elements (each takes the other's position).
          Use this when the user wants to exchange the position of exactly 2 elements.
          selector1 and selector2 MUST be CSS selectors from DOM inventory.
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addSwapVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment' or 'swap_b'") String variantKey,
            @P("Human-readable name, e.g. 'Swap hero and stats'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of first element (from inventory)") String selector1,
            @P("CSS selector of second element (from inventory)") String selector2
    ) {
        try {
            log.info("[RECIPE TOOL] addSwapVariant experimentId={} sel1={} sel2={}", experimentId, selector1, selector2);

            if (selector1 == null || selector1.isBlank()) throw new IllegalArgumentException("selector1 is blank");
            if (selector2 == null || selector2.isBlank()) throw new IllegalArgumentException("selector2 is blank");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "swap",
                            "selector1", selector1,
                            "selector2", selector2
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addSwapVariant FAILED experimentId={}", experimentId, e);
            return error("addSwapVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // LAYOUT — reorder 3+ elements inside a container
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that REORDERS direct children inside a container element.
          Use this when the user wants to change the order of 3 or more elements.
          containerSelector: CSS selector for the parent element.
          orderedSelectors: comma-separated CSS selectors of children in desired order,
            e.g. "#block-stats,#block-hero,#block-features"
            Elements NOT listed will remain after the listed ones in their original relative order.
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addReorderVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment' or 'reorder_b'") String variantKey,
            @P("Human-readable name, e.g. 'Stats first layout'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the container whose children will be reordered") String containerSelector,
            @P("Comma-separated CSS selectors of children in desired order, e.g. '#block-stats,#block-hero'") String orderedSelectors
    ) {
        try {
            log.info("[RECIPE TOOL] addReorderVariant experimentId={} container={} order={}",
                    experimentId, containerSelector, orderedSelectors);

            if (containerSelector == null || containerSelector.isBlank())
                throw new IllegalArgumentException("containerSelector is blank");
            if (orderedSelectors == null || orderedSelectors.isBlank())
                throw new IllegalArgumentException("orderedSelectors is blank");

            List<String> order = List.of(orderedSelectors.split(","))
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (order.size() < 2)
                throw new IllegalArgumentException("orderedSelectors must contain at least 2 selectors");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "reorder",
                            "container", containerSelector,
                            "order", order
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addReorderVariant FAILED experimentId={}", experimentId, e);
            return error("addReorderVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // CONTENT — change text of an element
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that changes the TEXT of a DOM element.
          Use this when the user wants to test a different heading, button label, or any copy.
          selector: CSS selector from DOM inventory.
          newText: the new text content (plain text only, no HTML).
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addTextVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment' or 'text_b'") String variantKey,
            @P("Human-readable name, e.g. 'New CTA copy'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element to change (from inventory)") String selector,
            @P("New text content") String newText
    ) {
        try {
            log.info("[RECIPE TOOL] addTextVariant experimentId={} selector={}", experimentId, selector);

            if (selector == null || selector.isBlank()) throw new IllegalArgumentException("selector is blank");
            if (newText == null) throw new IllegalArgumentException("newText is null");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "text",
                            "selector", selector,
                            "value", newText
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addTextVariant FAILED experimentId={}", experimentId, e);
            return error("addTextVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // STYLE — change a CSS property of an element
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that changes a CSS STYLE property of a DOM element.
          Use this when the user wants to test a different color, size, font, background, etc.
          selector: CSS selector from DOM inventory.
          cssProperty: kebab-case CSS property name, e.g. "background-color", "font-size", "color".
            Allowed: background-color, color, font-size, font-weight, border-radius,
                     padding, margin, opacity, display, text-align, letter-spacing, box-shadow.
          cssValue: the CSS value, e.g. "#ff0000", "18px", "bold".
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addStyleVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment' or 'style_b'") String variantKey,
            @P("Human-readable name, e.g. 'Red button'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element to style (from inventory)") String selector,
            @P("CSS property in kebab-case, e.g. 'background-color'") String cssProperty,
            @P("CSS value, e.g. '#ff0000' or '18px'") String cssValue
    ) {
        try {
            log.info("[RECIPE TOOL] addStyleVariant experimentId={} selector={} prop={} value={}",
                    experimentId, selector, cssProperty, cssValue);

            if (selector == null || selector.isBlank())   throw new IllegalArgumentException("selector is blank");
            if (cssProperty == null || cssProperty.isBlank()) throw new IllegalArgumentException("cssProperty is blank");
            if (cssValue == null || cssValue.isBlank())   throw new IllegalArgumentException("cssValue is blank");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "css",
                            "selector", selector,
                            "prop", cssProperty,
                            "value", cssValue
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addStyleVariant FAILED experimentId={}", experimentId, e);
            return error("addStyleVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String saveVariant(long experimentId, String key, String name,
                                double weight, String recipeJson, int sortOrder) throws Exception {
        AddVariantRequest req = new AddVariantRequest();
        req.setKey(key);
        req.setName(name);
        req.setWeight(weight);
        req.setRecipeJson(recipeJson);
        req.setSortOrder(sortOrder);

        ExperimentVariant saved = experimentService.addVariant(experimentId, req, ACTOR);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("variantId", saved.getId());
        out.put("experimentId", experimentId);
        out.put("key", saved.getKey());
        out.put("name", saved.getName());
        out.put("weight", saved.getWeight());
        return toJson(out);
    }

    private String error(String op, Exception e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("op", op);
        out.put("error", safeMsg(e));
        return toJson(out);
    }

    private String toJson(Object obj) {
        try {
            return om.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"json_serialize_failed\"}";
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
