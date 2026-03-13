package com.example.gb.ai.tools;

import com.example.gb.model.ExperimentVariant;
import com.example.gb.model.dto.AddVariantRequest;
import com.example.gb.service.DomInventoryService;
import com.example.gb.service.ExperimentService;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private final DomInventoryService domInventoryService;
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

            // Auto-resolve: if inventory item has styledChildren overriding this property,
            // use the child selector instead to ensure the CSS change is actually visible
            String resolvedSelector = resolveStyledChildSelector(experimentId, selector, cssProperty);

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "css",
                            "selector", resolvedSelector,
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
    // MULTIPLE STYLES — change several CSS properties at once (setStyle)
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that changes MULTIPLE CSS properties of ONE element at once.
          Use this when you need to change more than one style property on the same element
          (e.g. background-color + color + border-radius together).
          styleJson: JSON object where keys are camelCase CSS property names and values are CSS values.
            e.g. {"backgroundColor":"#ff0000","color":"#fff","borderRadius":"8px"}
            Allowed properties: backgroundColor, color, fontSize, fontWeight, borderRadius,
              padding, margin, opacity, display, textAlign, letterSpacing, boxShadow,
              border, borderColor, maxWidth, width, height.
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addMultiStyleVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'Bold red CTA'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element to style (from inventory)") String selector,
            @P("JSON object of camelCase CSS properties, e.g. {\"backgroundColor\":\"#f00\",\"color\":\"#fff\"}") String styleJson
    ) {
        try {
            log.info("[RECIPE TOOL] addMultiStyleVariant experimentId={} selector={}", experimentId, selector);

            if (selector == null || selector.isBlank()) throw new IllegalArgumentException("selector is blank");
            if (styleJson == null || styleJson.isBlank()) throw new IllegalArgumentException("styleJson is blank");

            Map<String, Object> styleMap = om.readValue(styleJson, new TypeReference<>() {});

            // Auto-resolve: check if any of the CSS properties is overridden by a styledChild
            // Use first color-related property found for resolution
            String resolvedSelector = selector;
            for (String prop : styleMap.keySet()) {
                // Convert camelCase to kebab for resolution
                String kebab = prop.replaceAll("([A-Z])", "-$1").toLowerCase();
                String resolved = resolveStyledChildSelector(experimentId, selector, kebab);
                if (!resolved.equals(selector)) {
                    resolvedSelector = resolved;
                    break;
                }
            }

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "setStyle",
                            "selector", resolvedSelector,
                            "value", styleMap
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addMultiStyleVariant FAILED experimentId={}", experimentId, e);
            return error("addMultiStyleVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // HTML CONTENT — replace inner HTML of an element
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that replaces the inner HTML of a DOM element.
          Use this when you need to change complex HTML content (not just plain text).
          The HTML is sanitized server-side before being applied.
          selector: CSS selector from DOM inventory.
          htmlContent: the new inner HTML string, e.g. "<strong>Buy now</strong> →"
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addHtmlVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'Bold CTA label'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("New inner HTML content") String htmlContent
    ) {
        try {
            log.info("[RECIPE TOOL] addHtmlVariant experimentId={} selector={}", experimentId, selector);

            if (selector == null || selector.isBlank()) throw new IllegalArgumentException("selector is blank");
            if (htmlContent == null) throw new IllegalArgumentException("htmlContent is null");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "html:safe",
                            "selector", selector,
                            "value", htmlContent
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addHtmlVariant FAILED experimentId={}", experimentId, e);
            return error("addHtmlVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE — change an HTML attribute of an element
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that changes an HTML ATTRIBUTE of a DOM element.
          Use this when you want to change href, src, alt, title, aria-label, target, etc.
          selector: CSS selector from DOM inventory.
          attributeName: one of: href, src, alt, title, aria-label, role, target,
                         data-variant, data-test, rel
          attributeValue: the new value for the attribute.
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addAttrVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'New link target'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("Attribute name: href | src | alt | title | aria-label | role | target | data-variant | data-test | rel") String attributeName,
            @P("New attribute value") String attributeValue
    ) {
        try {
            log.info("[RECIPE TOOL] addAttrVariant experimentId={} selector={} attr={}", experimentId, selector, attributeName);

            if (selector == null || selector.isBlank())       throw new IllegalArgumentException("selector is blank");
            if (attributeName == null || attributeName.isBlank()) throw new IllegalArgumentException("attributeName is blank");
            if (attributeValue == null)                        throw new IllegalArgumentException("attributeValue is null");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "attr",
                            "selector", selector,
                            "name", attributeName,
                            "value", attributeValue
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addAttrVariant FAILED experimentId={}", experimentId, e);
            return error("addAttrVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // IMAGE — change image src
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that changes the SRC of an <img> element.
          Use this when you want to A/B test different images.
          selector: CSS selector of the <img> element from DOM inventory.
          imageSrc: new image URL (absolute or relative).
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addImageVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'Product photo B'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the <img> element (from inventory)") String selector,
            @P("New image URL, e.g. '/images/hero-b.jpg'") String imageSrc
    ) {
        try {
            log.info("[RECIPE TOOL] addImageVariant experimentId={} selector={}", experimentId, selector);

            if (selector == null || selector.isBlank()) throw new IllegalArgumentException("selector is blank");
            if (imageSrc == null || imageSrc.isBlank()) throw new IllegalArgumentException("imageSrc is blank");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "image",
                            "selector", selector,
                            "src", imageSrc
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addImageVariant FAILED experimentId={}", experimentId, e);
            return error("addImageVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // CSS CLASS — add or remove a CSS class
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that ADDS a CSS class to a DOM element.
          Use this when you want to activate a pre-defined CSS style via class toggle
          (e.g. add class "highlight", "featured", "large").
          selector: CSS selector from DOM inventory.
          className: the CSS class name to add (without the dot prefix).
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addClassAddVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'Highlighted CTA'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("CSS class name to ADD (without dot), e.g. 'featured'") String className
    ) {
        try {
            log.info("[RECIPE TOOL] addClassAddVariant experimentId={} selector={} class={}", experimentId, selector, className);

            if (selector == null || selector.isBlank())   throw new IllegalArgumentException("selector is blank");
            if (className == null || className.isBlank()) throw new IllegalArgumentException("className is blank");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "class:add",
                            "selector", selector,
                            "value", className
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addClassAddVariant FAILED experimentId={}", experimentId, e);
            return error("addClassAddVariant", e);
        }
    }

    @Tool("""
          Add a treatment variant that REMOVES a CSS class from a DOM element.
          Use this when you want to deactivate a style by removing a class.
          selector: CSS selector from DOM inventory.
          className: the CSS class name to remove (without the dot prefix).
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addClassRemoveVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'No highlight'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element (from inventory)") String selector,
            @P("CSS class name to REMOVE (without dot), e.g. 'featured'") String className
    ) {
        try {
            log.info("[RECIPE TOOL] addClassRemoveVariant experimentId={} selector={} class={}", experimentId, selector, className);

            if (selector == null || selector.isBlank())   throw new IllegalArgumentException("selector is blank");
            if (className == null || className.isBlank()) throw new IllegalArgumentException("className is blank");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "class:remove",
                            "selector", selector,
                            "value", className
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addClassRemoveVariant FAILED experimentId={}", experimentId, e);
            return error("addClassRemoveVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // HIDE — hide or remove an element
    // -------------------------------------------------------------------------

    @Tool("""
          Add a treatment variant that HIDES an element from the page.
          Use this when you want to test removing a section, banner, or element entirely.
          selector: CSS selector from DOM inventory.
          weight: traffic share 0.0..1.0. All variants must sum to 1.0.
          Returns JSON: {ok, variantId, experimentId, key, name, weight}
          """)
    public String addHideVariant(
            @P("Experiment id") long experimentId,
            @P("Variant key, e.g. 'treatment'") String variantKey,
            @P("Human-readable name, e.g. 'No promo banner'") String variantName,
            @P("Traffic weight, e.g. 0.5 for 50%") double weight,
            @P("CSS selector of the element to hide (from inventory)") String selector
    ) {
        try {
            log.info("[RECIPE TOOL] addHideVariant experimentId={} selector={}", experimentId, selector);

            if (selector == null || selector.isBlank()) throw new IllegalArgumentException("selector is blank");

            String recipe = om.writeValueAsString(Map.of(
                    "ops", List.of(Map.of(
                            "action", "remove",
                            "selector", selector
                    ))
            ));

            return saveVariant(experimentId, variantKey, variantName, weight, recipe, 1);
        } catch (Exception e) {
            log.error("[RECIPE TOOL] addHideVariant FAILED experimentId={}", experimentId, e);
            return error("addHideVariant", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Automatically resolves the best CSS selector for a CSS property change.
     * If the inventory item for the given selector has styledChildren that override
     * the same CSS property, returns the child's selector instead of the parent.
     * This prevents cases like h1 > span.h1-red overriding color set on h1.
     */
    private String resolveStyledChildSelector(long experimentId, String selector, String cssProperty) {
        try {
            var experiment = experimentService.get(experimentId);
            if (experiment == null || experiment.getPageKey() == null) return selector;

            // Convert kebab-case to camelCase, e.g. "background-color" → "backgroundColor"
            java.util.regex.Matcher kebabMatcher = java.util.regex.Pattern.compile("-([a-z])").matcher(cssProperty);
            StringBuilder camelBuilder = new StringBuilder();
            while (kebabMatcher.find()) {
                kebabMatcher.appendReplacement(camelBuilder, kebabMatcher.group(1).toUpperCase());
            }
            kebabMatcher.appendTail(camelBuilder);
            String camelProp = camelBuilder.toString();

            var items = domInventoryService.getByPageUrl(experiment.getPageUrl() != null
                    ? experiment.getPageUrl() : "");

            for (var item : items) {
                if (selector == null || !selector.trim().equalsIgnoreCase(
                        item.getSelector() == null ? "" : item.getSelector().trim())) continue;

                if (item.getStyledChildren() == null || item.getStyledChildren().isEmpty()) break;

                // Find a child that has a non-neutral computed color for this property
                for (var child : item.getStyledChildren()) {
                    Object computedColor = child.get("computedColor");
                    String childSelector = (String) child.get("selector");
                    if (childSelector == null || childSelector.isBlank()) continue;

                    // For "color" property: if child has non-black computed color → it overrides
                    if (("color".equals(cssProperty) || "color".equals(camelProp))
                            && computedColor != null
                            && !computedColor.toString().equals("rgb(0, 0, 0)")
                            && !computedColor.toString().equals("rgba(0, 0, 0, 1)")) {
                        log.info("[RECIPE TOOL] resolveStyledChildSelector: replacing '{}' → '{}' (styledChildren override for {})",
                                selector, childSelector, cssProperty);
                        return childSelector;
                    }

                    // For other properties: if child has inlineStyles with the same property
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inlineStyles = (Map<String, Object>) child.get("inlineStyles");
                    if (inlineStyles != null && (inlineStyles.containsKey(cssProperty)
                            || inlineStyles.containsKey(camelProp))) {
                        log.info("[RECIPE TOOL] resolveStyledChildSelector: replacing '{}' → '{}' (inline style override for {})",
                                selector, childSelector, cssProperty);
                        return childSelector;
                    }
                }
                break;
            }
        } catch (Exception e) {
            log.warn("[RECIPE TOOL] resolveStyledChildSelector failed for selector={}: {}", selector, e.getMessage());
        }
        return selector;
    }

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
