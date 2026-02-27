// /static/js/ai-bridge.js — DOM bridge for GrowthBook (inventory + DOM-op + tracking)
// Variant B: backend is canonical featureKey generator. Frontend does NOT generate feature keys.
(function () {
  // ---------- CONFIG / STATE ----------

  const ALLOWED_CSS_PROPS = new Set([
    "fontSize", "fontWeight", "lineHeight",
    "color", "background", "backgroundColor",
    "padding", "paddingTop", "paddingRight", "paddingBottom", "paddingLeft",
    "margin", "marginTop", "marginRight", "marginBottom", "marginLeft",
    "border", "borderColor", "borderRadius", "boxShadow",
    "width", "maxWidth", "height", "maxHeight",
    "display", "visibility", "opacity", "textAlign",
    "letterSpacing", "gap"
  ]);

  const ALLOWED_ATTRS = new Set([
    "href", "src", "alt", "title", "aria-label", "role", "target",
    "data-variant", "data-test", "rel"
  ]);

  const BRIDGE_ORIGIN =
      window.GB_BRIDGE_ORIGIN ||
      ((document.currentScript && new URL(document.currentScript.src).origin) || location.origin);

  // already "viewed" feature keys (avoid duplicate view events)
  const VIEWED_FEATURES = new Set();
  // elements that already have click listener bound
  const CLICK_BOUND = new WeakSet();
  // variant assigned per featureKey (by GB SDK experiment rule or server fallback)
  const FEATURE_VARIANTS = {};
  // featureKeys where GB SDK already assigned a variant via experiment rule
  // → /bridge/recipe fallback is skipped for these
  const GB_SDK_ASSIGNED = new Set();
  // canonical featureKeys returned by backend after inventory POST
  const KNOWN_FEATURE_KEYS = [];

  // ---------- SITE / PAGE IDS (must match backend DomPageKeyUtil logic) ----------

  function slug(str, fallback) {
    if (!str) return fallback;
    return (
        String(str)
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "") || fallback
    );
  }

  function getSiteId() {
    if (window.GB_SITE_ID) return slug(window.GB_SITE_ID, "site");
    try {
      const u = new URL(location.href);
      const host = u.hostname + (u.port ? "_" + u.port : "");
      return slug(host, "site");
    } catch {
      return "site";
    }
  }

  function getPageId() {
    try {
      const p = location.pathname || "/";
      if (p === "/" || p === "") return "home";
      return slug(
          p.replace(/^\/+/, "").replace(/\/+$/, "").replace(/\//g, "_"),
          "page"
      );
    } catch {
      return "page";
    }
  }

  const SITE_ID = getSiteId();
  const PAGE_ID = getPageId();
  // IMPORTANT: must match backend feature key prefixing scheme
  const FEATURE_PREFIX = SITE_ID + "__" + PAGE_ID + "__";

  // ---------- sessionTag resolution (universal for any visitor) ----------

  // 1) Capture explicit ?gbtag= from URL into persistent cookie
  (function captureGbTagFromUrl() {
    const m = new URLSearchParams(location.search).get("gbtag");
    if (m) {
      document.cookie = "gb_tag=" + m + "; path=/; max-age=" + 7 * 24 * 60 * 60;
    }
  })();

  function readCookie(name) {
    const c = document.cookie.split("; ").find(r => r.startsWith(name + "="));
    return c ? c.split("=").slice(1).join("=") : null;
  }

  function ensureSessionTag() {
    // Priority: gb_tag → gbuuid (set by GrowthBook SDK) → auto-generated UUID
    const gbTag  = readCookie("gb_tag");
    if (gbTag)  return gbTag;

    const gbUuid = readCookie("gbuuid");
    if (gbUuid) {
      // Persist as gb_tag so it's stable across pages
      document.cookie = "gb_tag=" + gbUuid + "; path=/; max-age=" + 365 * 24 * 60 * 60;
      return gbUuid;
    }

    // No GrowthBook SDK → generate a persistent anonymous ID
    const generated = "anon-" + Math.random().toString(36).slice(2, 10)
                               + Math.random().toString(36).slice(2, 10);
    document.cookie = "gb_tag=" + generated + "; path=/; max-age=" + 365 * 24 * 60 * 60;
    return generated;
  }

  const sessionTag = ensureSessionTag();

  // ---------- TRACKING ----------

  async function sendTrackEvent(payload) {
    try {
      const res = await fetch(BRIDGE_ORIGIN + "/api/gb/track", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "omit",
        body: JSON.stringify(payload)
      });
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.debug("[GB-bridge] track non-200:", res.status, text);
      }
    } catch (e) {
      console.debug("[GB-bridge] track send failed", e);
    }
  }

  // ---------- GB SDK HELPERS ----------

  /**
   * Extracts __variantKey__ injected by GbAdminService.injectVariantKey().
   * Returns null if not present or parsing fails.
   */
  function extractVariantKeyFromValue(value) {
    if (!value) return null;
    try {
      const parsed = typeof value === "string" ? JSON.parse(value) : value;
      return (parsed && typeof parsed === "object" && parsed.__variantKey__)
          ? String(parsed.__variantKey__)
          : null;
    } catch (_) {
      return null;
    }
  }

  // ---------- DOM / INVENTORY HELPERS ----------

  function uniqueSelector(el) {
    if (!el || el.nodeType !== 1) return null;

    if (el.hasAttribute && el.hasAttribute("data-gb-id")) {
      return `[data-gb-id="${el.getAttribute("data-gb-id")}"]`;
    }

    if (el.id) return `#${CSS.escape(el.id)}`;

    const parts = [];
    let cur = el;

    while (cur && cur.nodeType === 1 && parts.length < 5) {
      let part = cur.tagName.toLowerCase();

      if (cur.classList.length) {
        const cls = Array.from(cur.classList)
        .slice(0, 2)
        .map(c => `.${CSS.escape(c)}`)
        .join("");
        part += cls;
      }

      const parent = cur.parentElement;
      if (parent) {
        const same = Array.from(parent.children).filter(ch => ch.tagName === cur.tagName);
        if (same.length > 1) {
          part += `:nth-of-type(${same.indexOf(cur) + 1})`;
        }
      }

      parts.unshift(part);
      cur = cur.parentElement;
    }

    return parts.join(" > ");
  }

  function collectInventory() {
    const items = [];

    // Headings
    document.querySelectorAll("h1, h2, h3, [role='heading']").forEach(el => {
      const selector = uniqueSelector(el);
      if (!selector) return;

      const text = (el.textContent || "").trim().slice(0, 140);
      items.push({
        kind: "heading",
        text,
        selector
      });
    });

    // CTA / buttons / links
    document
    .querySelectorAll("button, [role='button'], a, a.btn, a.button, a[class*='btn'], .btn, .btn-cta")
    .forEach(el => {
      const selector = uniqueSelector(el);
      if (!selector) return;

      const label = (el.textContent || el.getAttribute("aria-label") || "").trim().slice(0, 140);
      const href = (el.tagName === "A" ? (el.getAttribute("href") || "") : "");

      items.push({
        kind: "cta",
        text: label,
        href,
        selector
      });
    });

    // Uniq by selector
    const uniq = new Map();
    items.forEach(it => uniq.set(it.selector, it));
    return Array.from(uniq.values());
  }

  async function sendInventory(payload) {
    try {
      const r = await fetch(BRIDGE_ORIGIN + "/api/gb/dom-inventory", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "omit",
        body: JSON.stringify(payload)
      });

      if (!r.ok) {
        const t = await r.text().catch(() => "");
        console.debug("[GB-bridge] inventory non-200:", r.status, t);
        return;
      }

      // Store canonical featureKeys from backend for A/B recipe lookups
      try {
        const data = await r.json();
        if (data.items && Array.isArray(data.items)) {
          data.items.forEach(it => {
            if (it.featureKey && !KNOWN_FEATURE_KEYS.includes(it.featureKey)) {
              KNOWN_FEATURE_KEYS.push(it.featureKey);
            }
          });
          console.debug("[GB-bridge] received featureKeys from backend:", KNOWN_FEATURE_KEYS.length, KNOWN_FEATURE_KEYS);
        }
      } catch (_) {}
    } catch (e) {
      console.debug("[GB-bridge] inventory send failed", e);
    }
  }

  function applyCssVars(vars) {
    const root = document.documentElement;
    for (const [k, v] of Object.entries(vars || {})) {
      if (typeof k === "string" && k.startsWith("--")) {
        root.style.setProperty(k, String(v));
      }
    }
  }

  // ---------- FEATURE VALUE PARSING + OP NORMALIZATION ----------

  function normalizeOp(rawOp) {
    if (!rawOp) return null;
    const op = { ...rawOp };

    // legacy compat: kind -> action
    if (!op.action && op.kind) {
      switch (op.kind) {
        case "text": op.action = "text"; break;
        case "html": op.action = "html:safe"; break;
        case "css":  op.action = "css"; break;
        case "attr": op.action = "attr"; break;
        default:     op.action = op.kind;
      }
    }
    // legacy compat: sel -> selector, prop/name mapping
    if (!op.selector && op.sel) op.selector = op.sel;
    if (!op.name && op.prop) op.name = op.prop;
    if (!op.prop && op.name && op.action === "css") op.prop = op.name;

    if (!op.action) return null;
    return op;
  }

  function parseFeatureValue(val) {
    if (!val) return null;
    if (typeof val === "string") {
      try {
        return JSON.parse(val);
      } catch {
        return null;
      }
    }
    if (typeof val === "object") return val;
    return null;
  }

  // ---------- GrowthBook instance detection ----------

  function isRealGrowthBook(gb) {
    return !!gb &&
        typeof gb.getFeatureValue === "function" &&
        (typeof gb.getAllFeatures === "function" || typeof gb.getFeatures === "function");
  }

  function getGrowthBookInstance() {
    if (isRealGrowthBook(window.growthbook)) {
      console.debug("[GB-bridge] using window.growthbook");
      return window.growthbook;
    }
    if (isRealGrowthBook(window.gb)) {
      console.debug("[GB-bridge] using window.gb");
      return window.gb;
    }
    if (isRealGrowthBook(window.GrowthBookClient)) {
      console.debug("[GB-bridge] using window.GrowthBookClient");
      return window.GrowthBookClient;
    }
    return null;
  }

  // ---------- Apply a single op (with tracking context) ----------

  function makeOpApplier(featureKey, sessTag, pageUrl) {
    return function (rawOp) {
      const op = normalizeOp(rawOp);
      if (!op) return;

      try {
        // vars op (no selector)
        if (op.action === "vars" && op.vars) {
          applyCssVars(op.vars);
          return;
        }

        const nodes = op.selector ? document.querySelectorAll(op.selector) : [];

        const each = (fn) =>
            nodes.forEach((el) => {
              try { fn(el); } catch (e) { console.warn(e); }
            });

        // click tracking (only if featureKey + selector + nodes found)
        if (featureKey && op.selector && nodes.length) {
          nodes.forEach(el => {
            if (CLICK_BOUND.has(el)) return;
            CLICK_BOUND.add(el);

            el.addEventListener("click", () => {
              const tag  = sessTag || readCookie("gb_tag") || sessionTag || "";
              const page = pageUrl || location.href;
              const assignedVariant = FEATURE_VARIANTS[featureKey] || null;

              sendTrackEvent({
                featureKey,
                variation: assignedVariant || "A",
                variantKey: assignedVariant,
                sessionTag: tag,
                page,
                action: "click",
                meta: {
                  source: "dom-bridge",
                  selector: op.selector,
                  kind: op.action
                }
              });
            }, { passive: true });
          });
        }

        // DOM operations
        switch (op.action) {
          case "text":
            each((el) => (el.textContent = String(op.value ?? "")));
            break;

          case "html:safe":
            each((el) => {
              const html = String(op.value ?? "");
              el.innerHTML = window.DOMPurify ? DOMPurify.sanitize(html) : html;
            });
            break;

          case "css":
            console.log("[GB-bridge] CSS op:", {
              originalProp: op.prop,
              selector: op.selector,
              value: op.value,
              nodesFound: nodes.length
            });
            if (!op.prop) {
              console.warn("[GB-bridge] CSS op: no prop");
              return;
            }
            // Convert kebab-case to camelCase for validation only
            const propName = op.prop.replace(/-([a-z])/g, (g) => g[1].toUpperCase());
            console.log("[GB-bridge] CSS converted prop:", propName);
            if (!ALLOWED_CSS_PROPS.has(propName)) {
              console.warn("[GB-bridge] CSS prop not allowed:", propName);
              return;
            }
            console.log("[GB-bridge] Applying CSS to", nodes.length, "elements");
            each((el) => {
              // Use original kebab-case prop for setProperty
              el.style.setProperty(op.prop, String(op.value ?? ""), 'important');
              // background-color won't show if background: linear-gradient is set —
              // clear background-image so the solid color is visible
              if (op.prop === "background-color" || propName === "backgroundColor") {
                el.style.setProperty("background-image", "none", "important");
              }
              console.log("[GB-bridge] Applied", op.prop, "=", op.value, "!important to", el);
            });
            break;

          case "attr":
            if (!op.name || !ALLOWED_ATTRS.has(op.name)) return;
            each((el) => el.setAttribute(op.name, String(op.value ?? "")));
            break;

          case "class:add":
            each((el) => el.classList.add(String(op.value ?? "")));
            break;

          case "class:remove":
            each((el) => el.classList.remove(String(op.value ?? "")));
            break;

          case "image":
            each((el) => {
              if (el.tagName === "IMG" && op.src) {
                el.setAttribute("src", String(op.src));
              }
            });
            break;

          case "remove":
            each((el) => el.remove());
            break;
        }
      } catch (e) {
        console.warn("[GB-bridge] op failed", rawOp, e);
      }
    };
  }

  // ---------- Hook GrowthBook updates ----------

  function hookGrowthBook(gb) {
    if (!gb || gb.__gbDomBridgeHooked) {
      if (gb && gb.__gbDomBridgeHooked) console.debug("[GB-bridge] GrowthBook already hooked");
      return;
    }
    gb.__gbDomBridgeHooked = true;

    console.debug("[GB-bridge] hooking GrowthBook instance");

    // Set id attribute — GB SDK uses "id" for experiment bucketing (hashing)
    if (typeof gb.setAttributes === "function") {
      try {
        const base = (typeof gb.getAttributes === "function" && gb.getAttributes()) || {};
        if (!base.id) {
          gb.setAttributes({
            ...base,
            id: sessionTag,
            url: location.href,
            device: /Mobi/.test(navigator.userAgent) ? "mobile" : "desktop",
            sessionTag
          });
          console.debug("[GB-bridge] GB attributes set: id=", sessionTag);
        }
      } catch (e) {
        console.debug("[GB-bridge] setAttributes in hookGrowthBook failed", e);
      }
    }

    // trackingCallback fires when GB SDK assigns a variant via experiment rule
    if (typeof gb.setTrackingCallback === "function") {
      try {
        gb.setTrackingCallback((experiment, result) => {
          const featureKey = (result && result.featureId) || experiment.key;
          const embeddedKey = extractVariantKeyFromValue(result && result.value);
          const variantKey = embeddedKey
              || (result && result.variationId === 0 ? "control" : "treatment");

          console.info("[GB-bridge] SDK assigned:", featureKey, "→", variantKey,
              "(variationId:", result && result.variationId, ")");

          if (featureKey) {
            FEATURE_VARIANTS[featureKey] = variantKey;
            GB_SDK_ASSIGNED.add(featureKey);
          }

          const trackKey = "sdk_" + featureKey;
          if (featureKey && !VIEWED_FEATURES.has(trackKey)) {
            VIEWED_FEATURES.add(trackKey);
            sendTrackEvent({
              featureKey,
              variation: variantKey,
              variantKey,
              sessionTag,
              page: location.href,
              action: "view",
              meta: {
                source: "gb-sdk",
                experimentKey: experiment.key,
                variationId: result ? result.variationId : null
              }
            });
          }
        });
        console.debug("[GB-bridge] trackingCallback registered for GB SDK assignments");
      } catch (e) {
        console.debug("[GB-bridge] setTrackingCallback failed", e);
      }
    }

    if (typeof gb.subscribe === "function") {
      try {
        gb.subscribe(() => {
          console.debug("[GB-bridge] gb.subscribe → applyDomFeatures");
          applyDomFeatures();
        });
        console.debug("[GB-bridge] hooked via gb.subscribe");
      } catch (e) {
        console.debug("[GB-bridge] subscribe hook failed", e);
      }
    }

    if (typeof gb.setFeatures === "function") {
      const origSetFeatures = gb.setFeatures.bind(gb);
      gb.setFeatures = function () {
        const res = origSetFeatures.apply(gb, arguments);
        try {
          console.debug("[GB-bridge] gb.setFeatures → applyDomFeatures");
          applyDomFeatures();
        } catch (e) {
          console.debug("[GB-bridge] setFeatures hook apply failed", e);
        }
        return res;
      };
      console.debug("[GB-bridge] hooked via gb.setFeatures");
    }

    if (typeof gb.loadFeatures === "function") {
      const origLoadFeatures = gb.loadFeatures.bind(gb);
      gb.loadFeatures = function () {
        const res = origLoadFeatures.apply(gb, arguments);
        Promise.resolve(res)
        .then(() => {
          console.debug("[GB-bridge] gb.loadFeatures resolved → applyDomFeatures");
          applyDomFeatures();
        })
        .catch(() => { /* ignore */ });
        console.debug("[GB-bridge] hooked via gb.loadFeatures");
        return res;
      };
    }
  }

  function waitForGrowthBookAndHook(maxWaitMs) {
    const start = Date.now();
    (function tick() {
      const gb = getGrowthBookInstance();
      if (gb) {
        console.debug("[GB-bridge] waitForGrowthBookAndHook: instance found");
        hookGrowthBook(gb);
        applyDomFeatures();
        return;
      }
      if (Date.now() - start < maxWaitMs) {
        setTimeout(tick, 250);
      } else {
        console.debug("[GB-bridge] no GrowthBook instance detected after wait");
      }
    })();
  }

  // ---------- Apply features + send "view" once ----------

  function applyDomFeatures() {
    const gb = getGrowthBookInstance();
    if (!gb) {
      console.debug("[GB-bridge] applyDomFeatures: no GrowthBook instance yet");
      return;
    }

    const features =
        (typeof gb.getAllFeatures === "function" && gb.getAllFeatures()) ||
        (typeof gb.getFeatures === "function" && gb.getFeatures()) ||
        gb.features ||
        {};

    const allKeys = Object.keys(features || {});
    console.info("[GB-bridge] all feature keys from GB:", allKeys);

    const keys = allKeys.filter(k => k.startsWith(FEATURE_PREFIX));
    if (!keys.length) {
      console.info("[GB-bridge] no matching features for prefix", FEATURE_PREFIX);
      return;
    }

    console.info("[GB-bridge] applying", keys.length, "features with prefix", FEATURE_PREFIX);

    const currentSessionTag = readCookie("gb_tag") || sessionTag || "auto";
    const pageUrl = location.href;

    keys.forEach(key => {
      let v =
          typeof gb.getFeatureValue === "function"
              ? gb.getFeatureValue(key, null)
              : (features[key] && (features[key].defaultValue || features[key].default));

      const val = parseFeatureValue(v);
      if (!val) {
        console.debug("[GB-bridge] feature", key, "has no valid JSON value");
        return;
      }

      // Extract __variantKey__ injected by backend sync (Phase 2: GB SDK experiment rule)
      if (val.__variantKey__) {
        FEATURE_VARIANTS[key] = val.__variantKey__;
        GB_SDK_ASSIGNED.add(key);
        console.debug("[GB-bridge] feature", key, "→ variant:", val.__variantKey__, "(from recipe)");
      }

      const applyOp = makeOpApplier(key, currentSessionTag, pageUrl);

      if (val.vars && typeof val.vars === "object") {
        applyCssVars(val.vars);
      }
      if (Array.isArray(val.ops)) {
        val.ops.forEach(applyOp);
      }

      if (!VIEWED_FEATURES.has(key)) {
        VIEWED_FEATURES.add(key);

        sendTrackEvent({
          featureKey: key,
          variation: FEATURE_VARIANTS[key] || "A",
          variantKey: FEATURE_VARIANTS[key] || null,
          sessionTag: currentSessionTag,
          page: pageUrl,
          action: "view",
          meta: {
            source: "dom-bridge",
            opsCount: Array.isArray(val.ops) ? val.ops.length : 0
          }
        });
      }
    });
  }

  // ---------- Public helper for manual apply ----------

  window.gbDomBridgeApply = function () {
    try {
      console.debug("[GB-bridge] gbDomBridgeApply called");
      const gb = getGrowthBookInstance();
      if (gb) hookGrowthBook(gb);
      applyDomFeatures();
    } catch (e) {
      console.warn("[GB-bridge] gbDomBridgeApply error", e);
    }
  };

  // ---------- A/B Bridge: server-side variant delivery ----------

  /**
   * Calls /bridge/recipe for all page features.
   * If the server has an active A/B experiment for a feature it returns:
   *   { featureId, experimentId, variant: "treatment", recipe: { ops: [...] } }
   * We apply the recipe and store the variantKey for tracking events.
   */
  async function applyAbBridgeRecipes() {
    if (!sessionTag) return;

    // Collect featureKeys this bridge already knows about (from inventory/GB prefix)
    const gbInv = window.gbGetInventory ? window.gbGetInventory() : [];
    const featureKeys = gbInv
        .filter(it => it.featureKey)
        .map(it => it.featureKey);

    // Also include any keys starting with our site/page prefix from GB SDK
    const gbInstance = getGrowthBookInstance();
    if (gbInstance) {
      const features =
          (typeof gbInstance.getAllFeatures === "function" && gbInstance.getAllFeatures()) ||
          (typeof gbInstance.getFeatures === "function" && gbInstance.getFeatures()) ||
          gbInstance.features || {};
      Object.keys(features)
          .filter(k => k.startsWith(FEATURE_PREFIX) && !featureKeys.includes(k))
          .forEach(k => featureKeys.push(k));
    }

    // Also use featureKeys received from backend after inventory POST
    KNOWN_FEATURE_KEYS
        .filter(k => !featureKeys.includes(k))
        .forEach(k => featureKeys.push(k));

    if (!featureKeys.length) {
      console.debug("[GB-bridge] applyAbBridgeRecipes: no featureKeys found yet");
      return;
    }

    // Skip features already assigned by GB SDK experiment rules (Phase 2)
    const serverFeatureKeys = featureKeys.filter(k => !GB_SDK_ASSIGNED.has(k));
    if (!serverFeatureKeys.length) {
      console.debug("[GB-bridge] all features handled by GB SDK experiment rules, skipping /bridge/recipe");
      return;
    }
    if (serverFeatureKeys.length < featureKeys.length) {
      console.debug("[GB-bridge] skipping", featureKeys.length - serverFeatureKeys.length,
          "GB-SDK-assigned features, server fallback for:", serverFeatureKeys);
    }

    const url = `${BRIDGE_ORIGIN}/bridge/recipe?url=${encodeURIComponent(location.href)}&session=${encodeURIComponent(sessionTag)}&features=${encodeURIComponent(serverFeatureKeys.join(","))}`;

    try {
      const res = await fetch(url, { credentials: "omit" });
      if (!res.ok) {
        console.debug("[GB-bridge] /bridge/recipe non-200:", res.status);
        return;
      }

      const data = await res.json();

      // Store variant assignment for tracking
      if (data.variant && data.featureId) {
        featureKeys.forEach(fk => { FEATURE_VARIANTS[fk] = data.variant; });
        console.info("[GB-bridge] A/B variant assigned:", data.variant, "for experiment:", data.experimentId);
      }

      // Apply variant-specific recipe ops if present
      const ops = data.recipe && Array.isArray(data.recipe.ops) ? data.recipe.ops : [];
      if (ops.length) {
        console.info("[GB-bridge] Applying", ops.length, "A/B recipe ops (variant:", data.variant, ")");
        const applyOp = makeOpApplier(data.featureId, sessionTag, location.href);
        ops.forEach(applyOp);
      }

      // Send view event for ANY assigned variant (including control with 0 ops)
      const expFeatureKey = data.featureId || featureKeys[0];
      if (data.variant && expFeatureKey && !VIEWED_FEATURES.has("ab_bridge_" + expFeatureKey)) {
        VIEWED_FEATURES.add("ab_bridge_" + expFeatureKey);
        sendTrackEvent({
          featureKey: expFeatureKey,
          variation: data.variant,
          variantKey: data.variant,
          sessionTag: sessionTag,
          page: location.href,
          action: "view",
          meta: {
            source: "ab-bridge",
            experimentId: data.experimentId,
            opsCount: ops.length
          }
        });
      }
    } catch (e) {
      console.debug("[GB-bridge] applyAbBridgeRecipes failed:", e);
    }
  }

  // ---------- Init ----------

  function init() {
    const inv = collectInventory();
    window.gbGetInventory = () => inv;
    try {
      sessionStorage.setItem("__GB_INV__", JSON.stringify(inv));
    } catch (_) { /* ignore */ }

    window.dispatchEvent(new CustomEvent("gb:inventory", { detail: inv }));

    console.info("[GB-bridge] sending inventory", inv);
    sendInventory({
      url: location.href,
      ts: Date.now(),
      inventory: inv
    });

    const gbNow = getGrowthBookInstance();
    if (gbNow && typeof gbNow.setAttributes === "function") {
      try {
        const base = (typeof gbNow.getAttributes === "function" && gbNow.getAttributes()) || {};
        gbNow.setAttributes({
          ...base,
          id: sessionTag,       // KEY: GB uses "id" for experiment bucketing
          url: location.href,
          device: /Mobi/.test(navigator.userAgent) ? "mobile" : "desktop",
          sessionTag            // backward compat
        });
        console.debug("[GB-bridge] init: GB attributes set, id=", sessionTag);
      } catch (e) {
        console.warn("[GB-bridge] setAttributes skipped", e);
      }
    }

    waitForGrowthBookAndHook(8000);
    setTimeout(applyDomFeatures, 400);
    // A/B bridge: runs after inventory is collected (800ms), overrides with variant recipe
    setTimeout(applyAbBridgeRecipes, 800);
    setTimeout(applyDomFeatures, 1500);
    // Re-apply A/B in case DOM changed
    setTimeout(applyAbBridgeRecipes, 2000);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }

  // ---------- Public API for console ----------

  window.gbPreview = function (recipe) {
    try {
      if (!recipe) return;
      if (recipe.vars) applyCssVars(recipe.vars);

      const applyOp = makeOpApplier(null, null, null);
      if (Array.isArray(recipe.ops)) recipe.ops.forEach(applyOp);

      console.debug("[GB-bridge] preview applied", recipe);
    } catch (e) {
      console.warn("[GB-bridge] preview failed", e);
    }
  };

  window.gbGetInventory = function () {
    try {
      return JSON.parse(sessionStorage.getItem("__GB_INV__") || "[]");
    } catch {
      return [];
    }
  };
})();
