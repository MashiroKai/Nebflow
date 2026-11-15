// input/historyStore.js — sent-input history that keeps attachments.
//
// `state.inputHistory` stays the plain text list (other readers and earlier builds depend on it).
// This module records every sent message there and, next to it, a ledger slot per entry that
// holds the attachment metadata (localStorage). The attachment payloads go to the queue vault's
// IndexedDB under `hist:` keys, plus a bounded in-memory copy for the lifetime of the page, so a
// recall works at once and after a reload. Rules for matching the two lists, and the caps, are in
// historyLedger.js. Nothing here claims more than that: an entry whose payload is gone is reported
// to the caller and is never restored hollow.

import state, { LS_HISTORY_KEY } from '../state.js';
import { key } from '../branding.js';
import {
  put as vaultPut, get as vaultGet, del as vaultDel, keys as vaultKeys,
  historyVaultKey, HISTORY_VAULT_PREFIX,
} from '../queueVault.js';
import {
  alignLedger, attachmentFromMeta, attachmentMeta, entriesOverCap, payloadOf, signatureOf, unreferencedKeys,
} from './historyLedger.js';

const LS_LEDGER_KEY = key('input_history_atts');
/** Same bound the text history always had. */
const HISTORY_CAP = 200;
/** Page-lifetime payload copies, base64 characters. */
const MEMORY_BYTES_CAP = 16 * 1024 * 1024;

/** @typedef {import('./historyLedger.js').LedgerEntry} LedgerEntry */

/** @type {LedgerEntry[]} */
let ledger = alignLedger(Array.isArray(state.inputHistory) ? state.inputHistory : [], readStoredLedger());
/** Entry id -> the attachment objects as they were sent (payload included). */
const memory = new Map();
let swept = false;

function readStoredLedger() {
  try {
    const parsed = JSON.parse(localStorage.getItem(LS_LEDGER_KEY) || 'null');
    return Array.isArray(parsed) ? parsed : [];
  } catch { return []; }
}

/** Keep the ledger one-to-one with `state.inputHistory`, whoever replaced the list. Only the length and
 *  the newest slot are verified here (a full check would re-hash every stored text on each key press);
 *  a slot that is read is verified on its own in `slotAt`. */
function ensureAligned() {
  const texts = state.inputHistory;
  const last = texts.length - 1;
  if (ledger.length === texts.length && (last < 0 || ledger[last].s === signatureOf(texts[last]))) return;
  ledger = alignLedger(texts, ledger);
}

/** The ledger slot of one history entry, or null when it does not belong to that text. */
function slotAt(index) {
  ensureAligned();
  const entry = ledger[index];
  return entry && entry.s === signatureOf(state.inputHistory[index]) ? entry : null;
}

function newEntryId() {
  return 'h' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/** Drop the stored payloads of one entry (history entry evicted or over a cap). */
function releaseEntryBytes(entry) {
  if (!entry || !entry.id) return;
  const count = Array.isArray(entry.atts) ? entry.atts.length : 0;
  for (let i = 0; i < count; i += 1) void vaultDel(historyVaultKey(entry.id, i));
  memory.delete(entry.id);
}

function trimMemory() {
  let total = 0;
  for (const atts of memory.values()) {
    for (const att of atts) total += payloadOf(att).length;
  }
  for (const id of memory.keys()) {
    if (total <= MEMORY_BYTES_CAP) break;
    const atts = memory.get(id) || [];
    for (const att of atts) total -= payloadOf(att).length;
    memory.delete(id);
  }
}

function persist() {
  const write = () => {
    localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory));
    localStorage.setItem(LS_LEDGER_KEY, JSON.stringify(ledger));
  };
  try { write(); return; } catch (error) {
    // Quota exceeded: halve the history and try once more, as the text history always did.
    console.debug('[input] history save failed:', error);
  }
  if (state.inputHistory.length > 100) {
    const excess = state.inputHistory.length - 100;
    state.inputHistory.splice(0, excess);
    for (const entry of ledger.splice(0, excess)) releaseEntryBytes(entry);
    try { write(); } catch { /* storage unavailable: the in-memory history still works */ }
  }
}

/**
 * Remember one sent message and its attachments. Called once per message, at the point where the
 * frame is written. An empty text without attachments is not recorded.
 * @param {string} text
 * @param {Array<Record<string, any>>} [attachments]  the attachments exactly as they were sent
 */
export function recordSentInput(text, attachments) {
  const atts = Array.isArray(attachments) ? attachments.filter(Boolean) : [];
  const body = typeof text === 'string' ? text : '';
  if (!body && atts.length === 0) return;
  ensureAligned();
  /** @type {LedgerEntry} */
  const entry = { s: signatureOf(body) };
  if (atts.length > 0) {
    entry.id = newEntryId();
    entry.atts = atts.map(attachmentMeta);
    entry.b = 0;
    atts.forEach((att, i) => {
      const payload = payloadOf(att);
      if (!payload) return;
      entry.b = (entry.b || 0) + payload.length;
      void vaultPut(historyVaultKey(entry.id, i), payload);
    });
    memory.set(entry.id, atts.map((att) => ({ ...att })));
    trimMemory();
  }
  state.inputHistory.push(body);
  ledger.push(entry);
  if (state.inputHistory.length > HISTORY_CAP) {
    const excess = state.inputHistory.length - HISTORY_CAP;
    state.inputHistory.splice(0, excess);
    for (const dropped of ledger.splice(0, excess)) releaseEntryBytes(dropped);
  }
  for (const index of entriesOverCap(ledger)) {
    releaseEntryBytes(ledger[index]);
    ledger[index] = { s: ledger[index].s };
  }
  persist();
  scheduleSweep();
}

/** True when the entry at `index` of `state.inputHistory` has attachments to restore. */
export function entryHasAttachments(index) {
  const entry = slotAt(index);
  return !!(entry && entry.atts && entry.atts.length > 0);
}

/**
 * Rebuild the attachments of one history entry. `lost` counts the attachments whose payload is no
 * longer stored; they are not part of `atts`.
 * @param {number} index  index into `state.inputHistory`
 * @returns {Promise<{ atts: Array<Record<string, any>>, lost: number }>}
 */
export async function restoreEntryAttachments(index) {
  const entry = slotAt(index);
  if (!entry || !entry.id || !entry.atts || entry.atts.length === 0) return { atts: [], lost: 0 };
  const live = memory.get(entry.id);
  const atts = [];
  let lost = 0;
  for (let i = 0; i < entry.atts.length; i += 1) {
    const meta = entry.atts[i];
    if (!meta.bytes) {
      atts.push(attachmentFromMeta(meta, ''));
      continue;
    }
    let data = live && live[i] ? payloadOf(live[i]) : '';
    if (!data) data = (await vaultGet(historyVaultKey(entry.id, i))) || '';
    if (data) atts.push(attachmentFromMeta(meta, data));
    else lost += 1;
  }
  return { atts, lost };
}

/** Once per page: delete stored payloads that no ledger entry refers to any more. */
function scheduleSweep() {
  if (swept) return;
  swept = true;
  setTimeout(async () => {
    try {
      const wantedKeys = () => {
        const wanted = new Set();
        for (const entry of ledger) {
          const count = entry.id && entry.atts ? entry.atts.length : 0;
          for (let i = 0; i < count; i += 1) wanted.add(historyVaultKey(entry.id, i));
        }
        return wanted;
      };
      const orphans = await unreferencedKeys(vaultKeys, wantedKeys, HISTORY_VAULT_PREFIX);
      await Promise.all(orphans.map((name) => vaultDel(name)));
    } catch { /* the sweep is best effort */ }
  }, 5000);
}
