// input/historyLedger.js — pure rules for the sent-input history ledger (no DOM, no storage).
//
// The input history is a plain list of strings that earlier builds and several readers
// understand. The ledger is a parallel list with one slot per string; a slot carries the
// attachment metadata of that message. Because another writer (an older build, another tab)
// can append strings without a slot, the two lists are matched from the tail by a text
// signature: the newest run of entries that provably belong together keeps its attachments,
// everything older degrades to text only instead of being paired with the wrong attachments.

/**
 * @typedef {object} LedgerAtt
 * @property {string} type  image | text | ref | taskRef
 * @property {boolean} [bytes]  true when the original carried a base64 payload that is stored elsewhere
 */

/**
 * @typedef {object} LedgerEntry
 * @property {string} s  signature of the entry text
 * @property {string} [id]  attachment entry id (vault key part)
 * @property {Array<LedgerAtt & Record<string, any>>} [atts]  attachment metadata, original order
 * @property {number} [b]  stored payload size in base64 characters
 */

/** Newest entries that may keep attachments. */
export const ATTACHMENT_ENTRY_CAP = 30;
/** Total stored payload (base64 characters) across the entries that keep attachments. */
export const ATTACHMENT_BYTES_CAP = 64 * 1024 * 1024;
/** A reference whose text is longer than this is stored with the payloads (IndexedDB) instead of inside the ledger
 *  (localStorage, a small quota shared with the rest of the page). */
export const REF_INLINE_CAP = 16 * 1024;

/**
 * Cheap, stable signature of a text: length plus FNV-1a over UTF-16 code units.
 * @param {string} text
 * @returns {string}
 */
export function signatureOf(text) {
  const value = typeof text === 'string' ? text : '';
  let hash = 0x811c9dc5;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return value.length + '.' + hash.toString(36);
}

/**
 * Match a saved ledger to the current text list from the tail. The returned ledger always has
 * exactly one slot per text; slots that cannot be proven to belong to their text carry no
 * attachments.
 * @param {string[]} texts
 * @param {unknown} saved
 * @returns {LedgerEntry[]}
 */
export function alignLedger(texts, saved) {
  const list = Array.isArray(texts) ? texts : [];
  const stored = Array.isArray(saved) ? saved : [];
  /** @type {LedgerEntry[]} */
  const out = list.map((text) => ({ s: signatureOf(text) }));
  let textIndex = list.length - 1;
  let savedIndex = stored.length - 1;
  while (textIndex >= 0 && savedIndex >= 0) {
    const entry = stored[savedIndex];
    if (!entry || typeof entry.s !== 'string' || entry.s !== out[textIndex].s) break;
    out[textIndex] = entry;
    textIndex -= 1;
    savedIndex -= 1;
  }
  return out;
}

/**
 * Which entries must give up their attachments so that the newest ones stay within the caps.
 * Entries are walked from the newest; the first one that crosses a cap and everything older loses
 * its attachments.
 * @param {LedgerEntry[]} ledger
 * @returns {number[]} indices to strip, ascending
 */
export function entriesOverCap(ledger) {
  const strip = [];
  let kept = 0;
  let bytes = 0;
  let over = false;
  for (let i = ledger.length - 1; i >= 0; i -= 1) {
    const entry = ledger[i];
    if (!entry || !entry.atts || entry.atts.length === 0) continue;
    if (!over) {
      kept += 1;
      bytes += entry.b || 0;
      if (kept > ATTACHMENT_ENTRY_CAP || bytes > ATTACHMENT_BYTES_CAP) over = true;
    }
    if (over) strip.push(i);
  }
  return strip.reverse();
}

/**
 * Stored payload keys that no ledger entry refers to. `wantedKeys` is called only after `listKeys` has answered: an entry
 * recorded while the listing was in flight refers to payloads that exist by now and must not be taken for unreferenced.
 * @param {() => Promise<string[]>} listKeys
 * @param {() => Set<string>} wantedKeys
 * @param {string} prefix  only keys with this prefix are ever returned
 * @returns {Promise<string[]>}
 */
export async function unreferencedKeys(listKeys, wantedKeys, prefix) {
  const stored = await listKeys();
  const wanted = wantedKeys();
  return stored.filter((name) => name.startsWith(prefix) && !wanted.has(name));
}

/**
 * Metadata kept for one attachment (never the payload).
 * @param {Record<string, any>} att
 * @returns {LedgerAtt & Record<string, any>}
 */
export function attachmentMeta(att) {
  if (att.type === 'taskRef') {
    return { type: 'taskRef', taskId: att.taskId, sessionId: att.sessionId, subject: att.subject, name: att.subject };
  }
  if (att.type === 'ref') {
    const large = typeof att.content === 'string' && att.content.length > REF_INLINE_CAP;
    return {
      type: 'ref', refType: att.refType, id: att.id, source: att.source, anchor: att.anchor,
      meta: att.meta, display: att.display,
      ...(att.content && !large ? { content: att.content } : {}),
      ...(large ? { bytes: true } : {}),
    };
  }
  const hasBytes = typeof att.data === 'string' && att.data.length > 0;
  return {
    type: att.type, name: att.name, mimeType: att.mimeType,
    hash: att.hash || '', size: att.size || 0, bytes: hasBytes,
  };
}

/**
 * What one attachment keeps in the payload store: the base64 data of a file or image, or the text of a reference that
 * is too long to live in the ledger. Empty when the attachment keeps nothing there.
 * @param {Record<string, any>} att
 * @returns {string}
 */
export function payloadOf(att) {
  if (att.type === 'taskRef') return '';
  if (att.type === 'ref') return typeof att.content === 'string' && att.content.length > REF_INLINE_CAP ? att.content : '';
  return typeof att.data === 'string' ? att.data : '';
}

/**
 * Rebuild an attachment object from its metadata and payload. The result has the same shape the
 * input strip and the send path produce for a freshly added attachment.
 * @param {LedgerAtt & Record<string, any>} meta
 * @param {string} data  base64 payload, '' when the original had none
 * @returns {Record<string, any>}
 */
export function attachmentFromMeta(meta, data) {
  if (meta.type === 'taskRef') {
    return { type: 'taskRef', taskId: meta.taskId, sessionId: meta.sessionId, subject: meta.subject, name: meta.subject };
  }
  if (meta.type === 'ref') {
    const { bytes, ...ref } = meta;
    return bytes ? { ...ref, content: data } : ref;
  }
  const restored = {
    type: meta.type, mimeType: meta.mimeType, data, name: meta.name,
    ...(meta.type === 'image' ? {} : { hash: meta.hash || '', size: meta.size || 0 }),
  };
  if (meta.type === 'image') {
    return { ...restored, preview: 'data:' + (meta.mimeType || 'image/jpeg') + ';base64,' + data };
  }
  return restored;
}
