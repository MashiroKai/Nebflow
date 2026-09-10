// knownAccounts.js — local account memory behind the settings-page
// 「切换账号」(switch account) modal (2026-09-10).
//
// Stores ONLY {email, displayName} — never any credential/token (red line:
// the only identity fields the client may persist are display strings,
// decoded server-side from the id_token's email/name claims and surfaced
// via /api/neblink/status device.email/device.displayName).
//
// Semantics (switch-account spec §3/§6):
//   - recency order: head = most recent successful login
//   - dedup by email (case-insensitive), re-login moves to head
//   - capped at MAX_ACCOUNTS (oldest dropped)
//   - survives logout on purpose: the switch flow logs out first and the
//     list IS the thing being switched between (no code path removes keys;
//     verified 2026-09-10: the existing logout chain clears nothing in
//     localStorage — only avatarCache's own key).
//
// Pattern mirrors avatarCache.js: branding-namespaced key, memoised read,
// every access try/catch-wrapped (private mode / quota → silent no-op).

import { key } from './branding.js';

const LS_KEY = key('known_accounts');

/** List cap (switch-account spec §3). */
export const MAX_ACCOUNTS = 4;

/** @typedef {{ email: string, displayName: string }} KnownAccount */

/** @type {KnownAccount[]|null} */
let memo = null;
let memoLoaded = false;

function load() {
  if (memoLoaded) return memo;
  memoLoaded = true;
  memo = null;
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        // Defensive re-shape: keep only the two whitelisted string fields.
        memo = parsed
          .filter(a => a && typeof a.email === 'string' && a.email)
          .map(a => ({ email: a.email, displayName: typeof a.displayName === 'string' ? a.displayName : '' }));
      }
    }
  } catch { /* corrupted or unavailable — behave as empty */ }
  return memo;
}

function save(list) {
  memo = list;
  memoLoaded = true;
  try { localStorage.setItem(LS_KEY, JSON.stringify(list)); } catch { /* quota — best-effort */ }
}

/** All known accounts, recency-ordered (head = most recent login).
 *  @returns {KnownAccount[]} */
export function getKnownAccounts() {
  return (load() || []).slice();
}

/** The remembered entry for an email, or null.
 *  @param {string} email
 *  @returns {KnownAccount|null} */
export function findKnownAccount(email) {
  const k = (email || '').trim().toLowerCase();
  if (!k) return null;
  return (load() || []).find(a => a.email.toLowerCase() === k) || null;
}

/**
 * Record a successful login: dedup by email (case-insensitive), move to
 * head, cap the list. No-op when email is empty.
 * @param {{ email: string, displayName?: string }} acct
 */
export function rememberAccount(acct) {
  const email = (acct?.email || '').trim();
  if (!email) return;
  const displayName = (acct?.displayName || '').trim();
  const k = email.toLowerCase();
  const rest = (load() || []).filter(a => a.email.toLowerCase() !== k);
  save([{ email, displayName }, ...rest].slice(0, MAX_ACCOUNTS));
}
