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

  // ---------- sessionTag from ?gbtag= into cookie ----------

  (function captureGbTagFromUrl() {
    const m = new URLSearchParams(location.search).get("gbtag");
    if (m) {
      document.cookie = "gb_tag=" + m + "; path=/; max-age=" + 7 * 24 * 60 * 60;
    }
  })();

  function getSessionTagFromCookie() {
    const c = document.cookie.split("; ").find(r => r.startsWith("gb_tag="));
    return c ? c.split("=")[1] : "";
  }

  const sessionTag = getSessionTagFromCookie();

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
      }
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
              const tag  = sessTag || getSessionTagFromCookie() || "";
              const page = pageUrl || location.href;

              sendTrackEvent({
                featureKey,
                variation: "A",
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
            if (!op.prop || !ALLOWED_CSS_PROPS.has(op.prop)) return;
            each((el) => (el.style[op.prop] = String(op.value ?? "")));
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

    const currentSessionTag = getSessionTagFromCookie() || "auto";
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
          variation: "A",
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

  // ---------- Init ----------

  function init() {
    const inv = collectInventory();
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
          url: location.href,
          device: /Mobi/.test(navigator.userAgent) ? "mobile" : "desktop",
          sessionTag
        });
      } catch (e) {
        console.warn("[GB-bridge] setAttributes skipped", e);
      }
    }

    waitForGrowthBookAndHook(8000);
    setTimeout(applyDomFeatures, 400);
    setTimeout(applyDomFeatures, 1500);
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
