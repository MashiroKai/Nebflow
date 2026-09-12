// branding.js - Storage key namespacing and rename-day migration.
//
// Every client-side persistence key (localStorage keys and the token
// cookie) is namespaced by brand.lowerName: key('token') is "nebflow_token"
// today and becomes "neblink_token" after a rename. The gateway-injected
// window.__BRAND__ decides the namespace, so a rename is a server-side
// change only - except that existing users already hold data under the old
// namespace. This module migrates that data at startup.
//
// Import-order contract: main.js imports this module FIRST. Migration runs
// as a top-level side effect during module evaluation, before state.js and
// i18n.js read localStorage in their own module scope.
//
// The legacy spellings below are intentionally hardcoded literals: they are
// the compatibility layer itself, not branding, and must never be derived
// from the current brand.

import { brand } from './brand.js';

/**
 * Namespaced storage key for the current brand.
 * @param {string} suffix e.g. 'token', 'sessions', 'tasks_'
 * @returns {string} e.g. 'nebflow_token'
 */
export function key(suffix) {
  return `${brand.lowerName}_${suffix}`;
}

// --- Legacy namespace (the compatibility layer) ---

const LEGACY_PREFIX = 'nebflow_';

// Legacy keys whose spelling does not follow `nebflow_<suffix>`. Values map
// suffix -> literal. Normalized on every boot (not only on rename day) so
// these pre-standardization spellings disappear immediately.
//
// ⑨-6（作者 2026-09-12 预授权令）：好友域三个裸键（好友信任标记 / 已看请求 /
// 拉黑镜像）此前无视命名空间纪律直接写死在 messages.js / contacts.js 里 ——
// 迁到 `key()` 命名空间（`nebflow_fm_trusted` 等），存量值经本表**启动即迁移**
// （migrateKey 是「新键已有数据则不动」的幂等拷贝，数据不丢；裸键字面自此只
// 存在于这一张兼容表里）。
const LEGACY_IRREGULAR = {
  task_collapsed: 'nebflow-task-collapsed',
  time_format: 'nebflow:timeFormat',
  fm_trusted: 'fm_trusted',
  fm_seen_requests: 'fm_seen_requests',
  fm_blocked: 'fm_blocked',
};

/**
 * Copy oldKey to newKey and remove oldKey. No-op when newKey already has
 * data (newer data always wins) or oldKey is absent.
 * @param {string} oldKey
 * @param {string} newKey
 */
function migrateKey(oldKey, newKey) {
  if (oldKey === newKey) return;
  try {
    if (localStorage.getItem(newKey) !== null) return;
    const v = localStorage.getItem(oldKey);
    if (v === null) return;
    localStorage.setItem(newKey, v);
    localStorage.removeItem(oldKey);
  } catch { /* storage unavailable - never break boot */ }
}

// 1. Cross-brand migration: every key under the legacy prefix moves to the
//    current namespace. Prefix enumeration covers the nebflow_tasks_* family
//    and any forgotten key automatically. No-op when the brand is unchanged.
if (`${brand.lowerName}_` !== LEGACY_PREFIX) {
  try {
    const oldKeys = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith(LEGACY_PREFIX)) oldKeys.push(k);
    }
    for (const k of oldKeys) {
      migrateKey(k, `${brand.lowerName}_${k.slice(LEGACY_PREFIX.length)}`);
    }
  } catch { /* storage unavailable - never break boot */ }
}

// 2. Irregular-spelling normalization: runs on every boot, even without a
//    rename, so 'nebflow-task-collapsed' / 'nebflow:timeFormat' are
//    rewritten into the standard scheme immediately.
for (const [suffix, literal] of Object.entries(LEGACY_IRREGULAR)) {
  migrateKey(literal, key(suffix));
}

// 3. Token cookie: rename-day copy. Copy only, never delete - the backend
//    still accepts the legacy cookie name during the transition, and cookie
//    writes are asynchronous with respect to the network stack, so a
//    delete-then-write sequence risks a handshake landing in the empty
//    window (observed as intermittent WS 403).
const LEGACY_TOKEN_COOKIE = 'nebflow_token';
const tokenCookie = key('token');
if (tokenCookie !== LEGACY_TOKEN_COOKIE) {
  try {
    const old = document.cookie.match(/(?:^|;\s*)nebflow_token=([^;]*)/);
    const hasNew = document.cookie.match(new RegExp(`(?:^|;\\s*)${tokenCookie}=`));
    if (old && !hasNew) {
      document.cookie = `${tokenCookie}=${old[1]}; path=/; SameSite=Strict`;
    }
  } catch { /* cookie unavailable - never break boot */ }
}
