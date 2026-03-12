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
  // features whose DOM ops have already been applied (prevent redundant re-application)
  const APPLIED_FEATURES = new Set();
  // elements that already have click listener bound
  const CLICK_BOUND = new WeakSet();
  // variant assigned per featureKey (by GB SDK experiment rule via trackingCallback)
  const FEATURE_VARIANTS = {};
  // canonical featureKeys returned by backend after inventory POST
  const KNOWN_FEATURE_KEYS = [];
  // featureKey → selector mapping (populated from backend inventory response)
  const FEATURE_KEY_TO_SELECTOR = {};

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

  // Returns true if element lives inside a navigation/chrome area (not main content)
  function isNavOrChrome(el) {
    let cur = el;
    while (cur && cur !== document.body) {
      const tag = cur.tagName && cur.tagName.toLowerCase();
      if (tag === "nav" || tag === "header" || tag === "footer") return true;
      const role = cur.getAttribute && cur.getAttribute("role");
      if (role === "navigation" || role === "banner" || role === "contentinfo") return true;
      const id = (cur.id || "").toLowerCase();
      const cls = (cur.className && typeof cur.className === "string" ? cur.className : "").toLowerCase();
      if (/\b(nav|navbar|header|footer|menu|sidebar|breadcrumb|cookie|consent)\b/.test(id + " " + cls)) return true;
      cur = cur.parentElement;
    }
    return false;
  }

  function collectInventory() {
    const items = [];

    // Headings (include even inside nav, but exclude pure navigation headings)
    document.querySelectorAll("h1, h2, h3, [role='heading']").forEach(el => {
      if (isNavOrChrome(el)) return;
      const selector = uniqueSelector(el);
      if (!selector) return;

      const text = (el.textContent || "").trim().slice(0, 140);
      items.push({
        kind: "heading",
        text,
        selector
      });
    });

    // CTA / buttons / links — skip navigation/chrome, skip empty/icon-only links
    document
    .querySelectorAll("button, [role='button'], a.btn, a.button, a[class*='btn'], .btn, .btn-cta")
    .forEach(el => {
      if (isNavOrChrome(el)) return;
      const selector = uniqueSelector(el);
      if (!selector) return;

      const label = (el.textContent || el.getAttribute("aria-label") || "").trim().slice(0, 140);
      if (!label) return; // skip icon-only / decorative elements

      const href = (el.tagName === "A" ? (el.getAttribute("href") || "") : "");

      items.push({
        kind: "cta",
        text: label,
        href,
        selector
      });
    });

    // Plain <a> tags in main content only (not nav/chrome)
    document.querySelectorAll("main a, article a, section a, [role='main'] a").forEach(el => {
      if (isNavOrChrome(el)) return;
      const selector = uniqueSelector(el);
      if (!selector) return;

      const label = (el.textContent || el.getAttribute("aria-label") || "").trim().slice(0, 140);
      if (!label) return;

      items.push({
        kind: "cta",
        text: label,
        href: el.getAttribute("href") || "",
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
            // Store featureKey → selector mapping for control click tracking
            if (it.featureKey && it.selector) {
              FEATURE_KEY_TO_SELECTOR[it.featureKey] = it.selector;
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
    // window._growthbook — used by auto bundle (@growthbook/growthbook/dist/bundles/auto.min.js)
    if (isRealGrowthBook(window._growthbook)) {
      console.debug("[GB-bridge] using window._growthbook (auto bundle)");
      return window._growthbook;
    }
    // Legacy / manual bundle names
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
              // Also apply to all child elements so inline styles on children
              // (e.g. <span class="h1-red">) don't override the parent's style
              el.querySelectorAll("*").forEach(child => {
                child.style.setProperty(op.prop, String(op.value ?? ""), 'important');
                if (op.prop === "background-color" || propName === "backgroundColor") {
                  child.style.setProperty("background-image", "none", "important");
                }
              });
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

          case "setStyle":
            // Agent may produce {action:"setStyle", selector:"...", value:{backgroundColor:"yellow",...}}
            if (op.value && typeof op.value === "object") {
              each((el) => {
                for (const [prop, val] of Object.entries(op.value)) {
                  if (!val) continue;
                  const kebab = prop.replace(/([A-Z])/g, (g) => "-" + g[0].toLowerCase());
                  el.style.setProperty(kebab, String(val), "important");
                  if (kebab === "background-color" || prop === "backgroundColor") {
                    el.style.setProperty("background-image", "none", "important");
                  }
                  console.log("[GB-bridge] setStyle:", kebab, "=", val, "on", el);
                }
              });
            }
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

          case "swap": {
            // Swap two elements in the DOM, preserving their exact positions.
            // op: { action:"swap", selector1:"#a", selector2:"#b" }
            const el1 = op.selector1 ? document.querySelector(op.selector1) : null;
            const el2 = op.selector2 ? document.querySelector(op.selector2) : null;
            if (!el1 || !el2) {
              console.warn("[GB-bridge] swap: element(s) not found", op.selector1, op.selector2);
              break;
            }
            if (el1 === el2) break;

            // Insert a temporary placeholder so we don't lose el1's position
            const placeholder = document.createComment("gb-swap");
            el1.parentNode.insertBefore(placeholder, el1);

            el2.parentNode.insertBefore(el1, el2);
            placeholder.parentNode.insertBefore(el2, placeholder);
            placeholder.parentNode.removeChild(placeholder);

            console.info("[GB-bridge] swap applied:", op.selector1, "↔", op.selector2);
            break;
          }

          case "reorder": {
            // Reorder direct children of a container element.
            // op: { action:"reorder", container:"#wrapper", order:["#id1","#id2",...] }
            // Elements in `order` are moved to the front in that order;
            // any children not listed remain at the end in their original relative order.
            const containerEl = op.container
                ? document.querySelector(op.container)
                : null;
            if (!containerEl) {
              console.warn("[GB-bridge] reorder: container not found", op.container);
              break;
            }
            if (!Array.isArray(op.order) || !op.order.length) {
              console.warn("[GB-bridge] reorder: `order` must be a non-empty array");
              break;
            }

            // Resolve selectors → elements (only direct children count)
            const children = Array.from(containerEl.children);
            const resolved = op.order.map(sel => {
              const el = document.querySelector(sel);
              return (el && el.parentElement === containerEl) ? el : null;
            });

            const valid = resolved.filter(Boolean);
            if (!valid.length) {
              console.warn("[GB-bridge] reorder: no matching children found for order", op.order);
              break;
            }

            // Move each resolved element to the end of the container in order
            valid.forEach(el => containerEl.appendChild(el));

            // Elements that were not in `order` stay after the ordered ones
            children
              .filter(ch => !valid.includes(ch))
              .forEach(ch => containerEl.appendChild(ch));

            console.info("[GB-bridge] reorder applied in", op.container, "→", op.order);
            break;
          }
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

    // Set id attribute — GB SDK uses "id" for experiment bucketing (hashing).
    // sessionTag always wins: if ?gbtag= is present in URL we must use it so that
    // manual QA/testing of specific variants works reliably even when gbuuid cookie exists.
    if (typeof gb.setAttributes === "function") {
      try {
        const base = (typeof gb.getAttributes === "function" && gb.getAttributes()) || {};
        gb.setAttributes({
          ...base,
          id: sessionTag,
          url: location.href,
          device: /Mobi/.test(navigator.userAgent) ? "mobile" : "desktop",
          sessionTag
        });
        console.debug("[GB-bridge] GB attributes set: id=", sessionTag);
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
          // Features updated — clear applied cache so ops re-apply with new values
          APPLIED_FEATURES.clear();
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

  // ---------- Click tracking for control variant ----------

  /**
   * Control variant has ops:[] — makeOpApplier never runs, so no click listener is bound.
   * This function finds the DOM element for the featureKey via stored inventory
   * (sessionStorage __GB_INV__) and binds a click listener directly.
   * Only binds if the element is found and not already bound (CLICK_BOUND WeakSet).
   */
  function bindClickForControlVariant(featureKey, sessTag, pageUrl) {
    try {
      // Use the featureKey→selector map populated from backend inventory response
      const selector = FEATURE_KEY_TO_SELECTOR[featureKey];
      if (!selector) {
        console.debug("[GB-bridge] bindClickForControl: no selector for featureKey", featureKey);
        return;
      }

      const nodes = document.querySelectorAll(selector);
      if (!nodes.length) {
        console.debug("[GB-bridge] bindClickForControl: element not found in DOM", selector);
        return;
      }

      const assignedVariant = FEATURE_VARIANTS[featureKey] || "control";

      nodes.forEach(el => {
        if (CLICK_BOUND.has(el)) return;
        CLICK_BOUND.add(el);

        el.addEventListener("click", () => {
          const tag  = sessTag || readCookie("gb_tag") || sessionTag || "";
          const page = pageUrl || location.href;

          sendTrackEvent({
            featureKey,
            variation: assignedVariant,
            variantKey: assignedVariant,
            sessionTag: tag,
            page,
            action: "click",
            meta: {
              source: "dom-bridge",
              selector,
              kind: "control"
            }
          });
        }, { passive: true });

        console.debug("[GB-bridge] bindClickForControl: bound click on", selector, "for", featureKey);
      });
    } catch (e) {
      console.debug("[GB-bridge] bindClickForControlVariant failed", featureKey, e);
    }
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

      // Extract __variantKey__ injected by GB sync (set by trackingCallback too)
      if (val.__variantKey__ && !FEATURE_VARIANTS[key]) {
        FEATURE_VARIANTS[key] = val.__variantKey__;
        console.debug("[GB-bridge] feature", key, "→ variant:", val.__variantKey__, "(from recipe)");
      }

      // Apply DOM ops only once per feature (until gb.subscribe clears APPLIED_FEATURES)
      if (!APPLIED_FEATURES.has(key)) {
        APPLIED_FEATURES.add(key);

        const applyOp = makeOpApplier(key, currentSessionTag, pageUrl);

        if (val.vars && typeof val.vars === "object") {
          applyCssVars(val.vars);
        }
        if (Array.isArray(val.ops)) {
          val.ops.forEach(applyOp);
        }
      }

      // For control variant (ops:[]) — click listener is never bound by makeOpApplier.
      // Call bindClickForControlVariant on EVERY applyDomFeatures pass (not just first),
      // because FEATURE_KEY_TO_SELECTOR may not be populated yet on the first call
      // (inventory POST response arrives async after applyDomFeatures runs).
      // CLICK_BOUND WeakSet ensures the listener is bound only once per element.
      const isControl = Array.isArray(val.ops) && val.ops.length === 0;
      if (isControl) {
        bindClickForControlVariant(key, currentSessionTag, pageUrl);
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
