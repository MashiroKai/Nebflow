// brand.js - Single source of truth for product branding on the frontend.
//
// The gateway injects window.__BRAND__ into <head> before any module loads
// (server-side rendering of index.html). The fallback below is the ONLY
// hardcoded brand value allowed under web/ (whitelist) - it keeps the UI
// correct when the page is served without the gateway (static file server,
// tests, worktree previews).

/**
 * @typedef {Object} Brand
 * @property {string} productName  Display name, e.g. "nebflow"
 * @property {string} lowerName    Lowercase identifier, e.g. "nebflow"
 * @property {string} domain       Primary web domain, e.g. "nebflow.space"
 * @property {string} [homeDirName] User home directory name, e.g. ".nebflow".
 *   Optional: injected by the gateway from batch 3 onward; absent in older
 *   gateways and static-server contexts, so call sites must fall back to
 *   the legacy literal '.nebflow'.
 */

/** @type {Brand} */
const fallback = { productName: 'nebflow', lowerName: 'nebflow', domain: 'nebflow.space' };

const injected = /** @type {Window & { __BRAND__?: Brand }} */ (window).__BRAND__;

const raw = injected || fallback;

/**
 * Display name is all-lowercase "nebflow" everywhere in the UI (user ruling
 * 2026-08-25 19:18: tab title and brand-name display copy are lowercase;
 * code identifiers/package names are unaffected). Normalize at this single
 * read point so an injected legacy "Nebflow" cannot leak into any display
 * consumer (document.title, {brand} i18n interpolation, sidebar version
 * line, daemon/neblink copy).
 * @type {Brand}
 */
export const brand = Object.freeze({
  ...raw,
  productName: (raw.productName || 'nebflow').toLowerCase(),
});

/**
 * Apply branding to document chrome (the tab title). index.html ships a
 * neutral empty <title>; this sets the real one. There are no brand-bearing
 * meta tags (description/og:title) in index.html at this time.
 * Called at the earliest point in main.js so the title is correct ASAP.
 */
export function initBranding() {
  document.title = brand.productName;
}
