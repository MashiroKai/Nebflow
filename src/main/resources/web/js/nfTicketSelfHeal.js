// nfTicketSelfHeal.js — element-leg ticket self-heal for already-rendered media.
//
// Why this exists (author ruling 2026-11-19, 「图片加载不出来」diagnosis): the
// per-path read ticket is a short-lived, in-memory credential. When the gateway
// restarts (the store is per-JVM) or a ticket ages past its TTL, an `<img>` /
// `<video>` / `<audio>` element that ALREADY carries the stale ticket URL keeps
// failing forever — the ticket URL is baked into the element's `src` (or into a
// document's markup) and nothing re-mints it. That is the observed 403
// `invalid-ticket` storm in the gateway's `nf-file denied:` log.
//
// Every dynamic surface that fetches bytes (nfFileCache.nfViewerSource, the Pop
// media face, the viewers) already re-mints once on 401/403. This module is the
// same remedy for the surfaces that CANNOT: elements whose `src` was set from a
// ticket URL by someone else — a replayed card, a markdown document embed, a
// canvas HTML deliverable, a chat history row. One capture-phase `error`
// listener on the window catches them all; a failing element whose `src` is an
// `/api/nf-file?path=…&ticket=…` URL is re-minted exactly once and its `src`
// swapped to the fresh URL (a new ticket ⇒ a new URL ⇒ no stale response reuse).
//
// Single source, zero new protocol: the re-mint is `nfTicket.reMint` — the very
// function `nfFileCache` line ~613 already uses on its own 401/403 leg. The
// server side (ticket store, TTL, verdicts) is untouched: this only improves the
// client's reaction to a refusal it already receives.
//
// Bounded by construction: at most ONE re-mint per element (the `nfTicketReminted`
// dataset flag), so a genuinely dead path (404 / credential refusal) reaches its
// terminal failure face instead of looping. No ticket ⇒ not this module's case
// (the credential-free URL 401 path keeps its existing handling).

/** Dataset flag marking an element that already spent its single re-mint. */
export const REMINTED_FLAG = 'nfTicketReminted';

/**
 * The `path` of an `/api/nf-file` ticket URL, or `null` when the URL is not one
 * (or carries no ticket). Pure and dependency-free so it is unit-testable.
 *
 * @param {string} raw
 * @param {string} [base] absolute base for relative URLs (defaults to `location.href`)
 * @returns {string|null}
 */
export function nfTicketUrlPath(raw, base) {
  if (typeof raw !== 'string' || raw.length === 0) return null;
  const origin = base || (typeof location !== 'undefined' ? location.href : undefined);
  let u;
  try {
    u = new URL(raw, origin);
  } catch {
    return null;
  }
  if (!/\/api\/nf-file$/.test(u.pathname)) return null;
  const path = u.searchParams.get('path');
  const ticket = u.searchParams.get('ticket');
  if (!path || !ticket) return null;
  return path;
}

/** Default re-mint: the ticket module's own path (lazily imported — no import cycle). */
function defaultRemint(path) {
  return import('./nfTicket.js').then((m) => m.reMint(path));
}

/**
 * Heal ONE failing media element: bounded to a single re-mint, then the element
 * is left to the existing terminal failure face.
 *
 * @param {EventTarget|null} el
 * @param {(path: string) => Promise<string>} [remint]
 * @returns {boolean} true when a re-mint was armed for this element
 */
export function healTicketElement(el, remint) {
  const node = /** @type {any} */ (el);
  if (!node || !node.tagName) return false;
  const tag = String(node.tagName).toUpperCase();
  if (tag !== 'IMG' && tag !== 'VIDEO' && tag !== 'AUDIO') return false;
  const ds = node.dataset || (node.dataset = {});
  if (ds[REMINTED_FLAG] === '1') return false;
  const raw =
    (typeof node.getAttribute === 'function' ? node.getAttribute('src') : null) ||
    node.currentSrc ||
    node.src ||
    '';
  const path = nfTicketUrlPath(raw);
  if (!path) return false;
  ds[REMINTED_FLAG] = '1';
  const re = remint || defaultRemint;
  Promise.resolve()
    .then(() => re(path))
    .then((url) => {
      // A failed re-mint yields the credential-free URL — do not overwrite the
      // element with it (the existing 401 face is the honest answer there).
      if (node.isConnected === false) return;
      if (typeof url === 'string' && /[?&]ticket=/.test(url)) node.src = url;
    })
    .catch(() => {});
  return true;
}

/**
 * Install the document-level self-heal (idempotent). Capture phase: resource
 * load errors do not bubble, but they DO reach the window in the capture phase.
 *
 * @param {any} [target] window-like host (defaults to `window`)
 * @param {(path: string) => Promise<string>} [remint] injectable for tests
 * @returns {boolean} true when this call installed the listener
 */
export function installTicketSelfHeal(target, remint) {
  const w = target || (typeof window !== 'undefined' ? window : null);
  if (!w || typeof w.addEventListener !== 'function') return false;
  if (w.__nfTicketSelfHeal) return false;
  w.__nfTicketSelfHeal = true;
  w.addEventListener(
    'error',
    (ev) => {
      let el = /** @type {any} */ (ev && ev.target);
      // A failing <source> child reports itself; the media parent holds the src.
      if (el && el.tagName && String(el.tagName).toUpperCase() === 'SOURCE' && el.parentNode) {
        el = el.parentNode;
      }
      healTicketElement(el, remint);
    },
    true
  );
  return true;
}
