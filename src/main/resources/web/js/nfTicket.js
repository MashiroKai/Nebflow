// nfTicket.js — short-lived, per-path read tickets for /api/nf-file.
//
// Why this exists (2026-09-11, C batch "断凭据腿"): GET /api/nf-file used to
// be gated by the permanent, path-agnostic gateway token, which every rendered
// asset URL carried in its query string — so the token leaked into srcdoc,
// DOM, screenshots and every network log entry, and any holder could read any
// path the extension whitelist allowed. The endpoint now takes a per-path
// ticket minted by POST /api/nf-ticket (itself authenticated by the cookie /
// Bearer token, never by a URL parameter), with a TTL (R3 = 1800s default,
// `nfFile.ticketTtlSeconds` in nebflow.json) and unlimited reads inside it
// (R4 — pdf Range requests, .svg double-fetch and video seek all need it).
//
// Placement/import discipline (plan D-17): this module is imported by BOTH
// viewers/shared.js and cardRegistry.js. It therefore MUST NOT statically
// import viewers/shared.js (that would form shared → nfTicket → shared), and
// it deliberately does not import ws.js either: the mint request rides the
// same-origin cookie, with a Bearer retry as the fallback.

import { key } from './branding.js';

/** @typedef {{ t: string, exp: number }} CachedTicket */

const MINT_URL = '/api/nf-ticket';
const MINT_TIMEOUT_MS = 8000;

/** §4.3 F6: three consecutive failures in one round short-circuits the rest. */
const FAILURE_BREAKER = 3;
/** §4.3 F6: the breaker is round-scoped — it re-arms after this window. */
const BREAKER_WINDOW_MS = 15000;
/** Re-mint a little before the server-side expiry so an in-flight use wins. */
const EXPIRY_SKEW_MS = 5000;

/** @type {Map<string, CachedTicket>} */
const cache = new Map();
/** @type {Promise<unknown>|null} in-flight mint round (dedupe, §4.3 F4) */
let inFlight = null;
let consecutiveFailures = 0;
let breakerUntil = 0;

/**
 * Remove the credential-bearing parameters (`token` / `ticket`) from a URL.
 *
 * One implementation for every display surface — the card failure placeholder
 * (cardRegistry.js) and the markdown image tooltip (viewers/markdown.js) both
 * call it, so the two can never drift into two different regexes. T2: a
 * credential must never reach a serializable export or a UI text face.
 * @param {unknown} url
 * @returns {string}
 */
export function stripCredentialParams(url) {
  return String(url == null ? '' : url)
    .replace(/([?&])(token|ticket)=[^&]*/g, '$1')
    .replace(/[?&]$/, '');
}

/** Fresh matcher for an `/api/nf-file` URL embedded in HTML text.
 *
 * The character class deliberately STOPS at `)` (plus quotes, whitespace and
 * the tag/attr delimiters, C2-21 / self-correction ⑨). The pre-batch class
 * `[^"'\s]+` did not, and the Card tool emits UNQUOTED CSS `url()`
 * (CardToolScanFaceSpec:103 locks that exact output) — so on one line the
 * match ran through `)}</style><img` and the credential was appended after a
 * tag name (DOM mangled), and across lines it landed OUTSIDE the `)`, leaving
 * the URL unauthenticated and the declaration broken. Quoted `src="…"` /
 * `url("…")` stop at the quote; unquoted `url(…)` stops at the paren.
 *
 * ONE matcher for the card path (cardRegistry.js) and the Canvas viewer path
 * (viewers/shared.js) — two copies of this regex is exactly how the original
 * bug survived in one place after being fixed in the other. */
export function nfFileUrlRe() {
  return /\/api\/nf-file\?path=[^"'\s)<>,;]+/g;
}

/** Decoded `path` values referenced by a chunk of HTML (the mint candidates).
 *  Empty set ⇒ zero mint requests (§4.3 F1). */
export function nfFilePathsIn(html) {
  /** @type {Set<string>} */
  const out = new Set();
  String(html || '').replace(nfFileUrlRe(), (url) => {
    const q = /[?&]path=([^&]*)/.exec(url);
    if (q) {
      try { out.add(decodeURIComponent(q[1])); } catch (e) { /* malformed — skip */ }
    }
    return url;
  });
  return [...out];
}

/** Append the per-path ticket to every `/api/nf-file` URL in a chunk of HTML.
 *  URLs that already carry a ticket are left alone (idempotent). */
export function injectTickets(html, tickets) {
  if (!tickets || tickets.size === 0) return html;
  return String(html).replace(nfFileUrlRe(), (url) => {
    if (/[?&]ticket=/.test(url)) return url;
    const q = /[?&]path=([^&]*)/.exec(url);
    if (!q) return url;
    let p;
    try { p = decodeURIComponent(q[1]); } catch (e) { return url; }
    const t = tickets.get(p);
    return t ? url + '&ticket=' + encodeURIComponent(t) : url;
  });
}

/** @returns {string} the raw credential, for the Bearer retry only. */
function storedToken() {
  try {
    return localStorage.getItem(key('token')) || '';
  } catch (e) {
    return '';
  }
}

/** @returns {string} */
function currentSessionId() {
  const w = /** @type {{ __nfSessionId?: string }} */ (/** @type {any} */ (window));
  if (typeof w.__nfSessionId === 'string') return w.__nfSessionId;
  try {
    const raw = localStorage.getItem(key('sessionId'));
    if (raw) return raw;
  } catch (e) { /* storage unavailable */ }
  return '';
}

/** @param {string} p @returns {boolean} */
function isFresh(p) {
  const hit = cache.get(p);
  return !!hit && hit.exp - EXPIRY_SKEW_MS > Date.now();
}

/** @param {string[]} paths @returns {Map<string, string>} */
function collected(paths) {
  /** @type {Map<string, string>} */
  const out = new Map();
  for (const p of paths) {
    const hit = cache.get(p);
    if (hit) out.set(p, hit.t);
  }
  return out;
}

/** @param {unknown} paths @returns {string[]} */
function normalize(paths) {
  if (!Array.isArray(paths)) return [];
  /** @type {string[]} */
  const out = [];
  for (const p of paths) {
    if (typeof p === 'string' && p && !out.includes(p)) out.push(p);
  }
  return out;
}

/**
 * One mint request. NEVER rejects (§4.3 F2/F3): every failure mode resolves
 * with "no tickets for these paths", and the caller builds the same
 * credential-free URL it would have built before this batch — the endpoint
 * then 401s and the existing per-element failure chain (placeholder /
 * onerror) handles it. That is what keeps a mint outage from blanking every
 * card on screen (§4.3 F5).
 * @param {string[]} paths
 * @returns {Promise<void>}
 */
async function mintRound(paths) {
  const body = JSON.stringify({ sessionId: currentSessionId(), paths });
  const send = (/** @type {Record<string, string>} */ extraHeaders) =>
    fetch(MINT_URL, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: Object.assign({ 'Content-Type': 'application/json' }, extraHeaders),
      body,
      signal: AbortSignal.timeout(MINT_TIMEOUT_MS),
    });

  try {
    let resp = await send({});
    if (resp.status === 401 || resp.status === 403) {
      // Cookie blocked (same condition ws.js probes for). The token, when one
      // is stored, goes in the Authorization header — never back into a URL.
      const tok = storedToken();
      if (tok) resp = await send({ Authorization: 'Bearer ' + tok });
    }
    if (!resp.ok) throw new Error('mint HTTP ' + resp.status);
    const data = await resp.json();
    // F3: a 200 whose body is not an object, or carries no `tickets` mapping
    // (the catch-all harness shape `{}`), means "no tickets available". It is
    // counted as a failure for the breaker and logged once — but it is still
    // NOT an error thrown at the caller: rendering continues with credential-
    // free URLs (§4.3 F2/F3 share one degradation path).
    const tickets = data && typeof data === 'object' ? data.tickets : null;
    if (!tickets || typeof tickets !== 'object') throw new Error('mint response carried no tickets');
    for (const p of Object.keys(tickets)) {
      const v = tickets[p];
      const t = v && typeof v === 'object' ? v.t : null;
      const exp = v && typeof v === 'object' ? v.exp : null;
      if (typeof t === 'string' && t) {
        cache.set(p, { t, exp: typeof exp === 'number' ? exp : Date.now() + 1800000 });
      }
    }
    consecutiveFailures = 0;
    breakerUntil = 0;
  } catch (e) {
    consecutiveFailures += 1;
    if (consecutiveFailures >= FAILURE_BREAKER) breakerUntil = Date.now() + BREAKER_WINDOW_MS;
    console.warn('[nfTicket] mint failed:', e && /** @type {Error} */ (e).message ? /** @type {Error} */ (e).message : e);
  }
}

/**
 * Mint (or reuse) tickets for a batch of absolute paths.
 *
 * Contract (§4.3, frozen):
 *   F1 candidate set empty      → ZERO requests, resolves an empty Map
 *   F2 network error / 5xx      → resolves the map WITHOUT those paths
 *   F3 200 with no `tickets`    → same as F2
 *   F4 partial success          → per path, never contagious
 *   F5 batch                    → per-path independence, never an all-empty page
 *   F6 repeated failures        → breaker: ≤3 requests per round
 *
 * @param {string[]} paths
 * @returns {Promise<Map<string, string>>}
 */
export async function mintTickets(paths) {
  const wanted = normalize(paths);
  if (wanted.length === 0) return new Map(); // F1 — no request at all
  // Dedupe: an in-flight round may already be minting some of these.
  while (inFlight) {
    const current = inFlight;
    await current;
    if (inFlight === current) break;
  }
  const fresh = wanted.filter((p) => !isFresh(p));
  if (fresh.length === 0) return collected(wanted);
  if (Date.now() < breakerUntil) return collected(wanted); // F6
  const round = mintRound(fresh);
  inFlight = round;
  try {
    await round;
  } finally {
    if (inFlight === round) inFlight = null;
  }
  return collected(wanted);
}

/**
 * The ticket-authenticated URL for one path.
 *
 * When no ticket is available the URL comes back in the pre-batch shape
 * (path only, no credential) — the caller renders it, the endpoint answers
 * 401, and the viewer's existing failure path takes over.
 * @param {string} path
 * @returns {Promise<string>}
 */
export async function ticketUrl(path) {
  const map = await mintTickets([path]);
  const t = map.get(path);
  const base = '/api/nf-file?path=' + encodeURIComponent(path);
  return t ? base + '&ticket=' + encodeURIComponent(t) : base;
}

/**
 * Forget the cached tickets for these paths and mint fresh ones.
 *
 * Used by the T3 self-healing paths (403 → re-mint once → retry) and, more
 * importantly, by every RE-MOUNT: a replayed ticket is forbidden (T2 ②), so a
 * card that comes back from a discarded browsing context mints again instead
 * of re-using the srcdoc it was built with.
 * @param {string[]} paths
 * @returns {Promise<Map<string, string>>}
 */
export async function reMintAll(paths) {
  const list = normalize(paths);
  // Settle any in-flight round FIRST. It may be minting (and caching) the very
  // entries we are about to drop; deleting before it lands would let its write
  // survive the delete, `mintTickets` would then see a "fresh" ticket and skip
  // the request — silently reusing the value this call exists to replace
  // (T2 ②: never reuse a ticket across mounts / lightbox opens).
  while (inFlight) {
    const current = inFlight;
    await current;
    if (inFlight === current) break;
  }
  for (const p of list) cache.delete(p);
  // A deliberate re-mint is a new round: clear the failure breaker so a
  // recovery attempt is not short-circuited by an earlier outage (§4.3 F6 is
  // round-scoped by design).
  consecutiveFailures = 0;
  breakerUntil = 0;
  return mintTickets(list);
}

/**
 * Drop the cached ticket for `path` and mint a fresh one. Returns the new URL,
 * or the credential-free URL when the re-mint failed.
 * @param {string} path
 * @returns {Promise<string>}
 */
export async function reMint(path) {
  const map = await reMintAll([path]);
  return urlWithTicket(path, map.get(path) || '');
}

/** Synchronous accessor for callers that already awaited `ticketUrl`. */
export function cachedTicket(path) {
  const hit = cache.get(path);
  return hit ? hit.t : '';
}

/** Build a URL from an already-known ticket (no await). */
export function urlWithTicket(path, ticket) {
  const base = '/api/nf-file?path=' + encodeURIComponent(path);
  return ticket ? base + '&ticket=' + encodeURIComponent(ticket) : base;
}

/** Test seam: forget every cached ticket and breaker state. */
export function resetTickets() {
  cache.clear();
  inFlight = null;
  consecutiveFailures = 0;
  breakerUntil = 0;
}
