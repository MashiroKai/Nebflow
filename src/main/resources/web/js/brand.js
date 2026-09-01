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
 * @property {string} domain       Display-only product domain. NEVER build a
 *   URL from this: brand.conf ships the placeholder `neblink.example` and the
 *   server contract (WebSocketRoutes.brandScriptTag) declares the field
 *   display-only. Use `getProfileUrl()` / a dedicated injected URL field for
 *   anything navigable.
 * @property {string} [homeDirName] User home directory name, e.g. ".nebflow".
 *   Optional: injected by the gateway from batch 3 onward; absent in older
 *   gateways and static-server contexts, so call sites must fall back to
 *   the legacy literal '.nebflow'.
 * @property {string} [profileUrl] Absolute https URL of the account profile
 *   page (login-chain fix 2026-09-01). Optional: gateways that predate the
 *   field omit it, so read it through getProfileUrl().
 */

/** @type {Brand} */
const fallback = { productName: 'Nebflow', lowerName: 'nebflow', domain: 'nebflow.space' };

const injected = /** @type {Window & { __BRAND__?: Brand }} */ (window).__BRAND__;

/** @type {Brand} */
export const brand = Object.freeze(injected || fallback);

/**
 * Account profile page URL.
 *
 * WHY a dedicated field instead of `https://${brand.domain}/profile`: `domain`
 * carries the brand.conf placeholder (`neblink.example`), so the old
 * string-built link opened a domain that does not resolve — the client's
 * "profile page is broken" report (login-chain analysis 2026-09-01, §3
 * item 1). The gateway now injects the real URL as `__BRAND__.profileUrl`;
 * this helper is the only place the frontend decides what that URL is.
 *
 * Only absolute https values are honoured — the result goes into
 * window.open(), so a javascript:/data: value must never be navigable.
 * Fallback (older gateway, static file server, worktree preview) is the live
 * product profile page, verified reachable in the same analysis.
 */
const PROFILE_URL_FALLBACK = 'https://neblink.space/profile';

export function getProfileUrl() {
  const url = /** @type {Brand} */ (brand).profileUrl;
  return typeof url === 'string' && url.startsWith('https://') ? url : PROFILE_URL_FALLBACK;
}

/**
 * Apply branding to document chrome (the tab title). index.html ships a
 * neutral empty <title>; this sets the real one. There are no brand-bearing
 * meta tags (description/og:title) in index.html at this time.
 * Called at the earliest point in main.js so the title is correct ASAP.
 *
 * The tab title uses lowerName (all-lowercase "nebflow") per user ruling
 * 2026-08-25 19:18; productName keeps the brand.conf display spelling for
 * other UI copy (sidebar version line, daemon hints) until the rename.
 */
export function initBranding() {
  document.title = brand.lowerName;
}
