// brand.js - Single source of truth for product branding on the frontend.
//
// The gateway injects window.__BRAND__ into <head> before any module loads
// (server-side rendering of index.html). The fallback below is the ONLY
// hardcoded brand value allowed under web/ (whitelist) - it keeps the UI
// correct when the page is served without the gateway (static file server,
// tests, worktree previews).

/**
 * @typedef {Object} Brand
 * @property {string} productName  Display name, e.g. "Nebflow"
 * @property {string} lowerName    Lowercase identifier, e.g. "nebflow"
 * @property {string} domain       Primary web domain, e.g. "nebflow.space"
 */

/** @type {Brand} */
const fallback = { productName: 'Nebflow', lowerName: 'nebflow', domain: 'nebflow.space' };

const injected = /** @type {Window & { __BRAND__?: Brand }} */ (window).__BRAND__;

/** @type {Brand} */
export const brand = Object.freeze(injected || fallback);

/**
 * Apply branding to document chrome (the tab title). index.html ships a
 * neutral empty <title>; this sets the real one. There are no brand-bearing
 * meta tags (description/og:title) in index.html at this time.
 * Called at the earliest point in main.js so the title is correct ASAP.
 */
export function initBranding() {
  document.title = brand.productName;
}
