// textStream.js — client for the text-stream protocol (design card §三.4/§三.5).
//
// One client per open large-text tab. The protocol is PULL-shaped on purpose:
// the view asks for the window it needs, one at a time, so flow control is
// structural (the server never buffers a file's worth of bytes for a slow
// reader) and the browser never builds a whole-file model.
//
// Frames (byte-for-byte the card's contract):
//   C→S  {type:"textWindow", reqId, tabId, path, mtimeMs, startByte, endByte}
//   S→C  {type:"textWindow", reqId, tabId, path, mtimeMs, startByte, endByte,
//         text, firstLine, lineCount, eof}            // failure: same shape + error
//   C→S  {type:"textIndex", reqId, tabId, path, mtimeMs}
//   S→C  {type:"textIndex", reqId, tabId, path, size, mtimeMs, totalLines, stride, lineStarts[]}
//   C→S  {type:"textSearch", reqId, tabId, path, mtimeMs, query, caseSensitive, maxHits}
//   S→C  {type:"textSearchHit", reqId, hits:[{line, col, text}]}   (≤200 hits/frame)
//   S→C  {type:"textSearchDone", reqId, scannedBytes, hits, truncated}
//   C→S  {type:"textCancel", reqId}                    (window / index / search share it)
//
// Discipline: every failure is surfaced (rejected promise / error listener), the
// mtimeMs echo is checked on EVERY response (drift ⇒ loaded windows are void, the
// index is rebuilt — never a silent mix of two revisions), and nothing retries
// behind the view's back.

import { sendWs, onMessage } from './ws.js';

/** Window size this client asks for. Mirrors `TextStream.DefaultWindowBytes`
  * (server) — the server caps at `MaxWindowBytes` regardless, so a drift here
  * yields a visible error frame, never a silent partial window. */
export const WINDOW_BYTES = 256 * 1024;

/** Two windows of look-ahead on each side of the viewport (card §三.4). */
export const PREFETCH_WINDOWS = 2;

/** Client-side per-request budget. The server also caps its own work at 10s
  * (card §三.5); this is the outer guard so a lost frame cannot leave a promise
  * pending forever. */
const REQUEST_TIMEOUT_MS = 15000;

let reqIdSeq = 0;
/** @returns {string} unique request id (window / index / search all share it) */
function newReqId(prefix) {
  reqIdSeq += 1;
  return `${prefix}-${Date.now().toString(36)}-${reqIdSeq}`;
}

/** reqId → client. Lets the module-level frame handlers stay stateless. */
const clients = new Map();

onMessage('textWindow', (msg) => {
  const c = clients.get(msg.reqId);
  if (c) c._onWindow(msg);
});

onMessage('textIndex', (msg) => {
  const c = clients.get(msg.reqId);
  if (c) c._onIndex(msg);
});

onMessage('textSearchHit', (msg) => {
  const c = clients.get(msg.reqId);
  if (c) c._onSearchHits(msg);
});

onMessage('textSearchDone', (msg) => {
  const c = clients.get(msg.reqId);
  if (c) c._onSearchDone(msg);
});

// Request-level search failure: the server echoes the REQUEST type + `error`
// (same convention as the window leg).
onMessage('textSearch', (msg) => {
  const c = clients.get(msg.reqId);
  if (c) c._onSearchError(msg);
});

/**
 * One stream over one file. Construct with the metadata from the open-leg
 * descriptor (`fileContent` frame: path / absPath / size / mtimeMs / stream).
 */
export class TextStreamClient {
  /**
   * @param {{path: string, size?: number, mtimeMs?: number, tabId?: string, absPath?: string}} opts
   */
  constructor(opts) {
    this.path = opts.path;
    this.absPath = opts.absPath || opts.path;
    this.size = opts.size || 0;
    this.mtimeMs = opts.mtimeMs || 0;
    this.tabId = opts.tabId || '';
    /** Closed clients reject new work and stop accepting frames. */
    this.closed = false;
    /** startByte → window ({startByte, endByte, text, firstLine, lineCount, eof}). */
    this.windows = new Map();
    /** Sparse index once loaded: {stride, lineStarts, totalLines, size, mtimeMs}. */
    this.index = null;
    /** @type {Map<string, {resolve: Function, reject: Function, timer: any, startByte: number}>} */
    this.pendingWindows = new Map();
    this.pendingIndex = null;
    this.pendingSearch = null;
    /** Serializes window requests: at most ONE window in flight per tab. */
    this.chain = Promise.resolve();
    this.indexPromise = null;
    this.listeners = {
      /** @type {Set<Function>} */ index: new Set(),
      /** @type {Set<Function>} */ drift: new Set(),
      /** @type {Set<Function>} */ error: new Set(),
    };
  }

  /** @param {'index'|'drift'|'error'} kind @param {Function} fn */
  on(kind, fn) {
    if (this.listeners[kind]) this.listeners[kind].add(fn);
    return this;
  }

  /** @param {'index'|'drift'|'error'} kind @param {any} payload */
  _emit(kind, payload) {
    const set = this.listeners[kind];
    if (!set) return;
    set.forEach((fn) => {
      try { fn(payload); } catch (err) { console.warn('textStream listener error:', err); }
    });
  }

  _fail(message) {
    this._emit('error', message);
  }

  /**
   * Drift check shared by every response: a frame whose mtimeMs differs from the
   * one we opened with means the file was rewritten under us. Loaded windows
   * become void and the index is invalidated — the view re-requests (card §三.5:
   * no three-way merge, no patch replay).
   * @param {any} msg @returns {boolean} true when this response is stale
   */
  _stale(msg) {
    const m = msg.mtimeMs;
    if (!m || !this.mtimeMs || m === this.mtimeMs) return false;
    this.mtimeMs = m;
    this.windows.clear();
    this.index = null;
    this.indexPromise = null;
    this._emit('drift', { mtimeMs: m, size: msg.size });
    return true;
  }

  // ── index ────────────────────────────────────────────────────────────────

  /** Load the sparse index once (idempotent). Resolves with the index. */
  loadIndex() {
    if (this.index) return Promise.resolve(this.index);
    if (this.indexPromise) return this.indexPromise;
    if (this.closed) return Promise.reject(new Error('text stream is closed'));
    const reqId = newReqId('ti');
    clients.set(reqId, this);
    this.indexPromise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingIndex = null;
        this.indexPromise = null;
        clients.delete(reqId);
        reject(new Error('text index request timed out'));
      }, REQUEST_TIMEOUT_MS);
      this.pendingIndex = { reqId, resolve, reject, timer };
      sendWs({ type: 'textIndex', reqId, tabId: this.tabId, path: this.path, mtimeMs: this.mtimeMs });
    });
    return this.indexPromise;
  }

  /** @param {any} msg */
  _onIndex(msg) {
    const pending = this.pendingIndex;
    if (!pending || pending.reqId !== msg.reqId) return;
    clearTimeout(pending.timer);
    this.pendingIndex = null;
    clients.delete(msg.reqId);
    if (msg.error) {
      this.indexPromise = null;
      this._fail(`index: ${msg.error}`);
      pending.reject(new Error(msg.error));
      return;
    }
    if (this._stale(msg)) {
      this.indexPromise = null;
      pending.reject(new Error('file changed while the index was being built'));
      return;
    }
    this.index = {
      stride: msg.stride,
      lineStarts: Array.isArray(msg.lineStarts) ? msg.lineStarts : [],
      totalLines: msg.totalLines || 0,
      size: msg.size || 0,
      mtimeMs: msg.mtimeMs || this.mtimeMs,
    };
    pending.resolve(this.index);
    this._emit('index', this.index);
  }

  // ── windows ──────────────────────────────────────────────────────────────

  /**
   * Fetch the window starting at `startByte` (cached). Windows are requested one
   * at a time — that single-flight queue IS the flow control.
   * @param {number} startByte
   * @param {number} [bytes]
   * @returns {Promise<any>}
   */
  window(startByte, bytes) {
    const cached = this.windows.get(startByte);
    if (cached) return Promise.resolve(cached);
    const run = () => new Promise((resolve, reject) => {
      if (this.closed) { reject(new Error('text stream is closed')); return; }
      const reqId = newReqId('tw');
      clients.set(reqId, this);
      const timer = setTimeout(() => {
        this.pendingWindows.delete(reqId);
        clients.delete(reqId);
        reject(new Error('text window request timed out'));
      }, REQUEST_TIMEOUT_MS);
      this.pendingWindows.set(reqId, { resolve, reject, timer, startByte });
      sendWs({
        type: 'textWindow',
        reqId,
        tabId: this.tabId,
        path: this.path,
        mtimeMs: this.mtimeMs,
        startByte,
        endByte: startByte + (bytes || WINDOW_BYTES),
      });
    });
    const p = this.chain.then(run, run);
    // Keep the chain alive across rejections (a failed window must not wedge the
    // queue for every later request).
    this.chain = p.then(() => {}, () => {});
    return p;
  }

  /** @param {any} msg */
  _onWindow(msg) {
    const pending = this.pendingWindows.get(msg.reqId);
    if (!pending) return; // stale / cancelled
    clearTimeout(pending.timer);
    this.pendingWindows.delete(msg.reqId);
    clients.delete(msg.reqId);
    if (msg.error) {
      this._fail(`window ${pending.startByte}: ${msg.error}`);
      pending.reject(new Error(msg.error));
      return;
    }
    if (this._stale(msg)) {
      pending.reject(new Error('file changed while the window was being read'));
      return;
    }
    const win = {
      startByte: msg.startByte,
      endByte: msg.endByte,
      text: typeof msg.text === 'string' ? msg.text : '',
      firstLine: msg.firstLine,
      lineCount: msg.lineCount,
      eof: !!msg.eof,
    };
    this.windows.set(win.startByte, win);
    pending.resolve(win);
  }

  /** Drop cached windows (memory bound: the view keeps only what it shows). */
  evictWindows(keepStartBytes) {
    const keep = new Set(keepStartBytes);
    for (const key of Array.from(this.windows.keys())) {
      if (!keep.has(key)) this.windows.delete(key);
    }
  }

  // ── search ───────────────────────────────────────────────────────────────

  /**
   * Server-side literal scan (card §三.1: never "search only what is loaded" —
   * that would report "no match" for a file that has matches).
   * @param {string} query
   * @param {{caseSensitive?: boolean, maxHits?: number, onHits?: Function}} [opts]
   * @returns {Promise<{hits: any[], truncated: boolean, scannedBytes: number, cancelled?: boolean, reportedHits?: number}>}
   */
  search(query, opts) {
    const o = opts || {};
    this.cancelSearch();
    return new Promise((resolve, reject) => {
      if (this.closed) { reject(new Error('text stream is closed')); return; }
      const reqId = newReqId('ts');
      clients.set(reqId, this);
      const timer = setTimeout(() => {
        this.pendingSearch = null;
        clients.delete(reqId);
        reject(new Error('text search request timed out'));
      }, REQUEST_TIMEOUT_MS);
      this.pendingSearch = {
        reqId, timer, resolve,
        hits: [],
        onHits: o.onHits || null,
      };
      sendWs({
        type: 'textSearch',
        reqId,
        tabId: this.tabId,
        path: this.path,
        mtimeMs: this.mtimeMs,
        query,
        caseSensitive: !!o.caseSensitive,
        maxHits: o.maxHits || 5000,
      });
    });
  }

  /** Cancel the in-flight search (the view treats it as "stopped", not failed). */
  cancelSearch() {
    const pending = this.pendingSearch;
    if (!pending) return;
    this.pendingSearch = null;
    clearTimeout(pending.timer);
    clients.delete(pending.reqId);
    sendWs({ type: 'textCancel', reqId: pending.reqId });
    pending.resolve({ hits: pending.hits, truncated: true, scannedBytes: 0, cancelled: true });
  }

  /** @param {any} msg */
  _onSearchHits(msg) {
    const pending = this.pendingSearch;
    if (!pending || pending.reqId !== msg.reqId) return;
    const hits = Array.isArray(msg.hits) ? msg.hits : [];
    pending.hits.push(...hits);
    if (pending.onHits) pending.onHits(hits);
  }

  /** @param {any} msg */
  _onSearchDone(msg) {
    const pending = this.pendingSearch;
    if (!pending || pending.reqId !== msg.reqId) return;
    this.pendingSearch = null;
    clearTimeout(pending.timer);
    clients.delete(msg.reqId);
    pending.resolve({
      hits: pending.hits,
      truncated: !!msg.truncated,
      scannedBytes: msg.scannedBytes || 0,
      reportedHits: typeof msg.hits === 'number' ? msg.hits : pending.hits.length,
    });
  }

  /** @param {any} msg */
  _onSearchError(msg) {
    const pending = this.pendingSearch;
    if (!pending || pending.reqId !== msg.reqId) return;
    this.pendingSearch = null;
    clearTimeout(pending.timer);
    clients.delete(msg.reqId);
    this._fail(`search: ${msg.error}`);
    pending.reject(new Error(msg.error));
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  /**
   * Tab closed / unloaded: release the server side (card §三.5 — no cross-session
   * resume; reopening rebuilds the index and re-fetches the visible window).
   */
  cancel() {
    if (this.closed) return;
    this.closed = true;
    for (const [reqId, pending] of this.pendingWindows) {
      clearTimeout(pending.timer);
      clients.delete(reqId);
      sendWs({ type: 'textCancel', reqId });
      pending.reject(new Error('text stream closed'));
    }
    this.pendingWindows.clear();
    if (this.pendingIndex) {
      clearTimeout(this.pendingIndex.timer);
      clients.delete(this.pendingIndex.reqId);
      sendWs({ type: 'textCancel', reqId: this.pendingIndex.reqId });
      this.pendingIndex.reject(new Error('text stream closed'));
      this.pendingIndex = null;
    }
    if (this.pendingSearch) {
      clearTimeout(this.pendingSearch.timer);
      clients.delete(this.pendingSearch.reqId);
      sendWs({ type: 'textCancel', reqId: this.pendingSearch.reqId });
      this.pendingSearch = null;
    }
    this.windows.clear();
    this.index = null;
    this.indexPromise = null;
  }

  /**
   * The open path saw a new (mtimeMs, size) for this file (background refresh).
   * Changing ⇒ everything cached here is void; the view re-requests.
   * @param {{mtimeMs?: number, size?: number}} meta
   * @returns {boolean} true when the cached state was invalidated
   */
  acceptMeta(meta) {
    if (!meta) return false;
    const changed = (meta.mtimeMs && this.mtimeMs && meta.mtimeMs !== this.mtimeMs)
      || (meta.size && this.size && meta.size !== this.size);
    if (!changed) return false;
    if (meta.mtimeMs) this.mtimeMs = meta.mtimeMs;
    if (meta.size) this.size = meta.size;
    this.windows.clear();
    this.index = null;
    this.indexPromise = null;
    this._emit('drift', { mtimeMs: this.mtimeMs, size: this.size });
    return true;
  }
}

/**
 * Convenience factory used by the viewer.
 * @param {{path: string, size?: number, mtimeMs?: number, tabId?: string, absPath?: string}} opts
 * @returns {TextStreamClient}
 */
export function createTextStreamClient(opts) {
  return new TextStreamClient(opts);
}
