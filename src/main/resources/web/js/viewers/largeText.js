// viewers/largeText.js — read-only virtual-scroll view for STREAMED text files
// (design card §三.3 流式态 / §三.4 / §三.5; author ruling ① = b′+c).
//
// Why a new viewer instead of Monaco: Monaco builds a model from a whole string,
// so a 100MiB file cannot be opened in it at all (the server also refuses to send
// that string — one frame would be 106.4M characters). This view keeps BOTH ends
// bounded: the browser holds only the windows around the viewport, the server
// hands them out one pull at a time.
//
// Properties the card fixes and this file implements:
//   · virtual scroll over LINES, word wrap DISABLED (a variable row height would
//     break both the virtual list and the line↔gutter mapping) — long lines
//     scroll horizontally instead;
//   · line numbers from the sparse index (gutter is 1:1 with rows);
//   · first window + index requested in PARALLEL — the first screen paints
//     immediately, the scrollbar (mapped by LINE, not by byte, so the thumb
//     travels evenly) and jump-to-line switch on when the index lands;
//   · search = the server-side scan leg (never "search what is loaded" — that
//     would report "no match" for matches that exist);
//   · read-only, explicitly labelled (the inbound WS frame cap is 10MB, so a save
//     of a >10MB buffer can never reach the server — see monacoEditor.js for the
//     direct-edit side of the same limit);
//   · every failure is a VISIBLE state with a manual retry; mtimeMs drift voids
//     the loaded windows and rebuilds the index.
//
// Compressed scroll space (why the line↔pixel mapping is not always 20px/line):
//   engines cap how tall a layout box may be (Chromium ≈ 2^25 px), so a document
//   above ~1.67M lines cannot be expressed as `totalLines × 20px` — the tail would
//   be unreachable and every jump past the cap would fail. The mapping therefore
//   compresses (`pxPerLine = MAX_SCROLL_PX / totalLines`) once the honest height
//   would cross that ceiling. Rows keep their fixed 20px height (a variable row
//   height would break the virtual list): only the CONTAINING block is anchored to
//   the compressed scroll space, which keeps every line reachable, keeps the
//   gutter aligned with its row, and never lets the block poke out of the sizer.
//
// Line text is the line CONTENT: a trailing CR (CRLF files) is the terminator and
// is not part of the line — the direct-state Monaco path agrees (`getLineContent`
// excludes it too). A leading BOM stays (it is part of line 1; the fixture
// generator defines it that way as well).
//
// Automation hooks (data-* on the DOM, so assertions do not depend on wording):
//   root:     data-stream-path / data-stream-total-lines / data-index-ready / data-stream-size
//             data-stream-px-per-line / data-stream-compressed / data-stream-scroll-px
//             (the effective compressed mapping, so a reachability reading does not
//              have to assume 20px/line)
//   row:      data-line="N"  (+ data-partial="1" when the line was clipped by a window seam)
//   gutter:   data-line="N"
//   status:   data-hits / data-hit-lines / data-truncated / data-state

import { t } from '../i18n.js';
import { createTextStreamClient, WINDOW_BYTES, PREFETCH_WINDOWS } from '../textStream.js';

// View styles, injected from the module (same pattern as monacoEditor.js's caret
// fix): they belong to this viewer alone and need no new static route. Colours and
// metrics come from the existing Sapphire Glass tokens — no new colour values, and
// no accent bar / highlight strip (visual-style iron rules).
if (!document.getElementById('large-text-styles')) {
  const style = document.createElement('style');
  style.id = 'large-text-styles';
  style.textContent = [
    '.lt-root{display:flex;flex-direction:column;height:100%;min-height:0;background:transparent;color:var(--color-text)}',
    '.lt-toolbar{display:flex;align-items:center;gap:8px;padding:6px 10px;border-bottom:1px solid var(--color-border);flex:0 0 auto}',
    '.lt-badge{font:500 11px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:var(--color-text);border:1px solid var(--color-border);border-radius:6px;padding:2px 7px;white-space:nowrap}',
    '.lt-meta{font:400 12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:var(--color-text-muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:32%}',
    '.lt-search{flex:1 1 auto;min-width:120px;padding:4px 8px;border-radius:8px;border:1px solid var(--color-border);background:transparent;color:var(--color-text);font:400 12px -apple-system,sans-serif}',
    '.lt-jump{width:110px;padding:4px 8px;border-radius:8px;border:1px solid var(--color-border);background:transparent;color:var(--color-text);font:400 12px -apple-system,sans-serif}',
    '.lt-btn{cursor:pointer;padding:3px 8px;border-radius:8px;border:1px solid var(--color-border);background:transparent;color:var(--color-text);font:500 12px -apple-system,sans-serif}',
    '.lt-status{flex:0 0 auto;padding:4px 10px;font:400 12px -apple-system,sans-serif;color:var(--color-text-muted);display:flex;align-items:center;gap:8px;min-height:22px}',
    '.lt-status[data-state="error"]{color:var(--color-text)}',
    '.lt-main{flex:1 1 auto;display:flex;min-height:0;overflow:hidden}',
    '.lt-gutter{flex:0 0 auto;width:72px;overflow:hidden;position:relative;border-right:1px solid var(--color-border)}',
    '.lt-gutter-inner{position:absolute;top:0;left:0;right:0}',
    '.lt-num{position:absolute;right:8px;height:20px;line-height:20px;font:500 12px "SF Mono","JetBrains Mono",Menlo,monospace;font-variant-numeric:tabular-nums;color:var(--color-text-muted);white-space:nowrap}',
    '.lt-scroll{flex:1 1 auto;overflow:auto;position:relative;min-width:0}',
    '.lt-sizer{position:relative;width:100%}',
    '.lt-rows{position:absolute;top:0;left:0;right:0}',
    '.lt-row{position:absolute;left:0;width:max-content;min-width:100%;height:20px;line-height:20px;white-space:pre;font:400 13px "SF Mono","JetBrains Mono","Fira Code",Menlo,monospace;color:var(--color-text);padding:0 12px;box-sizing:border-box}',
    '.lt-row.lt-pending{opacity:0.35}',
    '.lt-row.lt-hit{background:rgba(91,127,191,0.16)}',
    '.lt-row.lt-hit-active{background:rgba(91,127,191,0.30)}',
    '.lt-row.lt-flash{background:rgba(91,127,191,0.22)}',
  ].join('\n');
  document.head.appendChild(style);
}

/** Fixed row height. Wrap is off, so every line is exactly this tall. */
const LINE_HEIGHT = 20;
/** Largest scroll-space height the view will ask an engine to lay out. Chromium
  * clamps an element's height at 2^25 px (33,554,428) — the ceiling is also the
  * limit of its 1/64px layout units — so the compressed mapping stays well below
  * it and the slack covers the overscan rows that hang below the viewport. */
const MAX_SCROLL_PX = 32000000;
/** Rows rendered beyond the viewport on each side. */
const OVERSCAN = 10;
/** Loaded windows kept at once (memory = windows × window size). */
const MAX_LOADED_WINDOWS = 12;
/** Bounded walk when a requested line is not covered by the first window. */
const MAX_SEEK_ATTEMPTS = 4;

/** @param {string} s @returns {string} */
function formatBytes(s) {
  const n = Number(s) || 0;
  if (n >= 1024 * 1024 * 1024) return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${n} B`;
}

/** Byte length of a JS string as UTF-8 (line start offsets must be BYTE offsets —
  * the protocol is byte-addressed, and a character-count offset would drift on
  * the first non-ASCII line). */
const encoder = new TextEncoder();
/** @param {string} s @returns {number} */
function byteLen(s) { return encoder.encode(s).length; }

class LargeTextView {
  /**
   * @param {HTMLElement} pane
   * @param {any} ctx — render context (`stream`, `path`, `absPath`, `size`, `mtimeMs`, `itemType`)
   */
  constructor(pane, ctx) {
    this.pane = pane;
    this.ctx = ctx;
    this.path = ctx.path || ctx.absPath || '';
    this.client = createTextStreamClient({
      path: this.path,
      absPath: ctx.absPath,
      size: ctx.size,
      mtimeMs: ctx.mtimeMs,
      tabId: `file:${ctx.absPath || ctx.path}`,
    });
    /** @type {Map<number, any>} startByte → {firstLine, lines, lineOffsets, startByte, endByte, eof, partialFirst} */
    this.windows = new Map();
    /** Highest line number known from a loaded window (drives the sizer before the index). */
    this.knownLines = 0;
    this.index = null;
    this.closed = false;
    this.rendering = false;
    this.pendingRender = false;
    /** @type {any[]} search hits [{line, col, text}] */
    this.hits = [];
    this.hitLines = new Set();
    this.activeHit = -1;
    this.searchToken = 0;
    this.error = null;
    this.status = 'loading';
  }

  // ── DOM ───────────────────────────────────────────────────────────────────

  build() {
    const pane = this.pane;
    pane.innerHTML = '';
    const root = document.createElement('div');
    root.className = 'lt-root';
    root.dataset.streamPath = this.path;
    root.dataset.streamSize = String(this.ctx.size || 0);
    this.root = root;

    const toolbar = document.createElement('div');
    toolbar.className = 'lt-toolbar';

    const badge = document.createElement('span');
    badge.className = 'lt-badge';
    badge.textContent = t('canvas.largeTextReadOnly');
    badge.title = t('canvas.largeTextReadOnlyHint');

    const meta = document.createElement('span');
    meta.className = 'lt-meta';
    meta.textContent = `${this.ctx.fileName || this.path.split('/').pop() || this.path} · ${formatBytes(this.ctx.size || 0)}`;
    this.metaEl = meta;

    const search = document.createElement('input');
    search.className = 'lt-search';
    search.type = 'search';
    search.placeholder = t('canvas.largeTextSearchPlaceholder');
    search.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        this.runSearch(search.value);
      }
    });
    this.searchEl = search;

    const prevBtn = document.createElement('button');
    prevBtn.className = 'lt-btn';
    prevBtn.type = 'button';
    prevBtn.textContent = '↑';
    prevBtn.title = t('canvas.largeTextPrevHit');
    prevBtn.addEventListener('click', () => this.stepHit(-1));

    const nextBtn = document.createElement('button');
    nextBtn.className = 'lt-btn';
    nextBtn.type = 'button';
    nextBtn.textContent = '↓';
    nextBtn.title = t('canvas.largeTextNextHit');
    nextBtn.addEventListener('click', () => this.stepHit(1));

    const jumpInput = document.createElement('input');
    jumpInput.className = 'lt-jump';
    jumpInput.type = 'number';
    jumpInput.min = '1';
    jumpInput.placeholder = t('canvas.largeTextGoToLine');
    jumpInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        const n = parseInt(jumpInput.value, 10);
        if (Number.isFinite(n) && n > 0) this.jumpToLine(n);
      }
    });
    this.jumpEl = jumpInput;

    toolbar.append(badge, meta, search, prevBtn, nextBtn, jumpInput);

    const status = document.createElement('div');
    status.className = 'lt-status';
    status.dataset.state = 'loading';
    this.statusEl = status;

    const main = document.createElement('div');
    main.className = 'lt-main';

    const gutter = document.createElement('div');
    gutter.className = 'lt-gutter';
    const gutterInner = document.createElement('div');
    gutterInner.className = 'lt-gutter-inner';
    gutter.appendChild(gutterInner);
    this.gutterEl = gutter;
    this.gutterInner = gutterInner;

    const scroller = document.createElement('div');
    scroller.className = 'lt-scroll';
    const sizer = document.createElement('div');
    sizer.className = 'lt-sizer';
    const rows = document.createElement('div');
    rows.className = 'lt-rows';
    sizer.appendChild(rows);
    scroller.appendChild(sizer);
    this.scroller = scroller;
    this.sizer = sizer;
    this.rows = rows;

    main.append(gutter, scroller);
    root.append(toolbar, status, main);
    pane.appendChild(root);

    scroller.addEventListener('scroll', () => {
      this.syncScrollLayers();
      this.scheduleRender();
      this.schedulePrefetch();
    });

    const ro = new ResizeObserver(() => this.scheduleRender());
    ro.observe(scroller);
    this.resizeObserver = ro;
  }

  // ── data ─────────────────────────────────────────────────────────────────

  /** Absorb a served window: split into lines, compute per-line BYTE offsets, place
    * it by the server's exact `firstLine`. */
  absorb(win, partialFirst) {
    if (this.windows.has(win.startByte)) return this.windows.get(win.startByte);
    // Split on LF only; a CRLF file keeps the CR on the raw segment and it is
    // dropped from the line CONTENT (row text == the line, terminator excluded —
    // same semantics as the direct-state Monaco `getLineContent`). The raw segment
    // still carries the CR's byte, so the per-line BYTE offsets stay exact for
    // CRLF files too (a +1-only stride would drift one byte per line and walk the
    // prefetch anchors backwards).
    const raw = win.text.split('\n');
    if (raw.length > 0 && raw[raw.length - 1] === '') raw.pop();
    const lines = raw.map((s) => (s.endsWith('\r') ? s.slice(0, -1) : s));
    /** @type {number[]} */
    const offsets = [];
    let acc = 0;
    for (let i = 0; i < raw.length; i++) {
      offsets.push(acc);
      acc += byteLen(raw[i]) + 1; // + '\n' (the CR, when present, is inside raw[i])
    }
    const entry = {
      startByte: win.startByte,
      endByte: win.endByte,
      firstLine: win.firstLine,
      lastLine: win.firstLine + Math.max(lines.length - 1, 0),
      lines,
      lineOffsets: offsets,
      eof: !!win.eof,
      partialFirst: !!partialFirst,
    };
    this.windows.set(win.startByte, entry);
    this.knownLines = Math.max(this.knownLines, entry.lastLine);
    this.evictFarWindows();
    return entry;
  }

  evictFarWindows() {
    if (this.windows.size <= MAX_LOADED_WINDOWS) return;
    const visible = this.visibleRange();
    const entries = Array.from(this.windows.values());
    entries.sort((a, b) => {
      const da = Math.min(Math.abs(a.firstLine - visible.first), Math.abs(a.lastLine - visible.last));
      const db = Math.min(Math.abs(b.firstLine - visible.first), Math.abs(b.lastLine - visible.last));
      return da - db;
    });
    while (entries.length > MAX_LOADED_WINDOWS) {
      const drop = entries.pop();
      if (!drop) break;
      this.windows.delete(drop.startByte);
      this.client.windows.delete(drop.startByte);
    }
  }

  /** @param {number} line @returns {any|null} */
  windowForLine(line) {
    for (const win of this.windows.values()) {
      if (line >= win.firstLine && line <= win.lastLine) return win;
    }
    return null;
  }

  /** @returns {{first: number, last: number}} visible 1-based line range */
  visibleRange() {
    const scroller = this.scroller;
    const top = scroller ? scroller.scrollTop : 0;
    const h = scroller && scroller.clientHeight ? scroller.clientHeight : 600;
    const g = this.geometry();
    const first = Math.max(1, Math.min(Math.floor(top / g.pxPerLine) + 1, g.total));
    // `last` spans the scroll-space band the viewport covers. In compressed mode
    // that band holds more lines than the viewport has 20px rows for; the surplus
    // rows hang below the fold and are exactly what keeps the document's tail
    // visible once the row block is clamped at the bottom (see drawRows).
    const last = first + Math.ceil(h / g.pxPerLine) + OVERSCAN;
    return { first, last };
  }

  totalLines() {
    if (this.index) return this.index.totalLines;
    return Math.max(this.knownLines, 1);
  }

  /** Scroll-space geometry for the document as it is currently known.
    * @returns {{total: number, pxPerLine: number, height: number, compressed: boolean}}
    */
  geometry() {
    const total = this.totalLines();
    const honest = total * LINE_HEIGHT;
    if (honest <= MAX_SCROLL_PX) {
      return { total, pxPerLine: LINE_HEIGHT, height: honest, compressed: false };
    }
    // Above the engine's layout ceiling the tall-and-honest scroll space is
    // impossible: the sizer stops at the ceiling and every line takes an equal
    // share of it, so line N stays reachable at `(N-1) × pxPerLine` (verify3 F2).
    return { total, pxPerLine: MAX_SCROLL_PX / total, height: MAX_SCROLL_PX, compressed: true };
  }

  /** @returns {number} current scroll offset (0 before the scroller exists) */
  currentScrollTop() {
    return this.scroller ? this.scroller.scrollTop : 0;
  }

  /** Content-space top of the row block for a draw that starts at `first` and
    * covers `n` rows. The BLOCK is anchored, not each row: rows keep their fixed
    * 20px pitch and the block sits at the top of the scroll-space band it renders,
    * i.e. (first-1) × pxPerLine — identical to (first-1) × 20 while the mapping is
    * uncompressed. The min() keeps the block's overscan rows inside the sizer at
    * the far end of the document: rows poking past the sizer would enlarge the
    * scroller's scrollable area, which is exactly how the document's tail became
    * unreachable before (verify3 F2). Single source for both drawRows and
    * syncScrollLayers — two copies of this arithmetic could silently disagree.
    * @param {number} first @param {number} n @returns {number} */
  blockTopFor(first, n) {
    const g = this.geometry();
    return Math.min((first - 1) * g.pxPerLine, Math.max(0, g.height - n * LINE_HEIGHT));
  }

  // ── loading ──────────────────────────────────────────────────────────────

  async start() {
    this.build();
    this.setStatus('loading', t('canvas.largeTextLoading'));
    // First window and the index are requested IN PARALLEL (card §三.2: the first
    // screen must not wait for the index; the index only enables the scrollbar
    // and jump-to-line).
    const firstWindow = this.client.window(0, WINDOW_BYTES)
      .then((win) => { this.absorb(win, false); this.render(); })
      .catch((err) => this.fail(err));
    const indexLoad = this.client.loadIndex()
      .then((idx) => {
        this.index = idx;
        this.root.dataset.indexReady = '1';
        this.root.dataset.streamTotalLines = String(idx.totalLines);
        this.setStatus('ready', t('canvas.largeTextLines', { count: String(idx.totalLines) }));
        this.render();
      })
      .catch((err) => this.fail(err));
    this.client.on('drift', () => {
      this.windows.clear();
      this.knownLines = 0;
      this.index = null;
      this.root.dataset.indexReady = '0';
      this.setStatus('drift', t('canvas.largeTextDrift'));
      void this.reload();
    });
    this.client.on('error', (msg) => this.fail(new Error(String(msg))));
    await Promise.all([firstWindow, indexLoad]);
  }

  async reload() {
    this.windows.clear();
    this.knownLines = 0;
    const first = this.visibleRange().first;
    try {
      const win = await this.client.window(0, WINDOW_BYTES);
      this.absorb(win, false);
      if (first > 1) await this.ensureLines(first, first + 1);
      this.render();
      if (!this.index) {
        try {
          this.index = await this.client.loadIndex();
          this.root.dataset.indexReady = '1';
          this.root.dataset.streamTotalLines = String(this.index.totalLines);
        } catch (err) { this.fail(err); }
      }
    } catch (err) {
      this.fail(err);
    }
  }

  /** Nearest known line-start byte offset at or before `line`. */
  async anchorForLine(line) {
    if (this.index && this.index.lineStarts.length) {
      const stride = this.index.stride || 1024;
      const i = Math.min(Math.floor((line - 1) / stride), this.index.lineStarts.length - 1);
      return { offset: this.index.lineStarts[Math.max(0, i)], line: Math.max(0, i) * stride + 1 };
    }
    let best = null;
    for (const win of this.windows.values()) {
      if (win.lastLine < line && (!best || win.lastLine > best.lastLine)) best = win;
    }
    if (best) {
      const idx = Math.max(best.lines.length - 1, 0);
      return { offset: best.startByte + best.lineOffsets[idx], line: best.lastLine };
    }
    return { offset: 0, line: 1 };
  }

  /** Load whatever is missing in [first, last]. Bounded walk (MAX_SEEK_ATTEMPTS). */
  async ensureLines(first, last) {
    if (this.closed) return;
    for (let attempt = 0; attempt < MAX_SEEK_ATTEMPTS; attempt++) {
      const missing = this.firstMissingLine(first, last);
      if (missing === null) return;
      const anchor = await this.anchorForLine(missing);
      try {
        const win = await this.client.window(anchor.offset, WINDOW_BYTES);
        // A window served from an anchor is line-aligned only when the anchor was
        // a real line start; the server's `firstLine` is exact either way, so the
        // placement below is safe. Partial head only happens for the backwards
        // prefetch path (see prefetch()).
        // The SERVED frame has no `lines` (shape: startByte/endByte/text/firstLine/
        // lineCount/eof — card §三.4) — only the absorbed entry does. Reading the
        // frame's `lines` here threw on every EOF window and surfaced as a visible
        // error state on scroll/jump (verify3 F1).
        const ent = this.absorb(win, false);
        if (win.eof && ent.lastLine < missing) return; // hit EOF
      } catch (err) {
        this.fail(err);
        return;
      }
    }
  }

  /** @param {number} first @param {number} last @returns {number|null} */
  firstMissingLine(first, last) {
    for (let line = first; line <= last; line++) {
      if (!this.windowForLine(line)) return line;
    }
    return null;
  }

  /** Prefetch: two windows forward (from the last line of the furthest window)
    * and two backwards (a byte range ENDING at a known line start — its first line
    * is then a clipped tail, which is marked as partial rather than shown whole). */
  async prefetch() {
    if (this.closed) return;
    const visible = this.visibleRange();
    const forwardWindows = Array.from(this.windows.values()).sort((a, b) => a.firstLine - b.firstLine);
    if (forwardWindows.length === 0) return;
    let cursor = forwardWindows[forwardWindows.length - 1];
    for (let i = 0; i < PREFETCH_WINDOWS; i++) {
      if (cursor.eof || cursor.lines.length === 0) break;
      const idx = Math.max(cursor.lines.length - 1, 0);
      const offset = cursor.startByte + cursor.lineOffsets[idx];
      if (this.windows.has(offset)) { cursor = this.windows.get(offset); continue; }
      try {
        const win = await this.client.window(offset, WINDOW_BYTES);
        this.absorb(win, false);
        cursor = this.windows.get(offset) || cursor;
      } catch { return; }
    }
    let back = forwardWindows[0];
    for (let i = 0; i < PREFETCH_WINDOWS; i++) {
      if (back.startByte <= 0) break;
      const start = Math.max(0, back.startByte - WINDOW_BYTES);
      if (this.windows.has(start)) { back = this.windows.get(start); continue; }
      try {
        const win = await this.client.window(start, back.startByte - start);
        this.absorb(win, true);
        back = this.windows.get(start) || back;
      } catch { return; }
      if (back.lastLine >= visible.first && back.firstLine <= visible.last) break;
    }
  }

  // ── render ───────────────────────────────────────────────────────────────

  scheduleRender() {
    if (this.rendering) { this.pendingRender = true; return; }
    this.rendering = true;
    const run = () => {
      this.rendering = false;
      this.render();
      if (this.pendingRender) { this.pendingRender = false; this.scheduleRender(); }
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
    else setTimeout(run, 16);
  }

  schedulePrefetch() {
    if (this.prefetching) return;
    this.prefetching = true;
    this.prefetch().finally(() => { this.prefetching = false; });
  }

  render() {
    if (this.closed) return;
    const g = this.geometry();
    this.sizer.style.height = `${g.height}px`;
    // Machine-readable mapping: a "how far can the scroll reach" reading must not
    // assume 20px/line — above the ceiling the two differ by the compression ratio.
    this.root.dataset.streamPxPerLine = g.pxPerLine.toFixed(4);
    this.root.dataset.streamCompressed = g.compressed ? '1' : '0';
    this.root.dataset.streamScrollPx = String(g.height);
    const { first, last } = this.visibleRange();
    void this.ensureLines(first, last).then(() => this.drawRows(first, last));
    this.drawRows(first, last);
  }

  drawRows(first, last) {
    const rows = this.rows;
    const gutter = this.gutterInner;
    rows.textContent = '';
    gutter.textContent = '';
    const g = this.geometry();
    const end = Math.min(last, g.total);
    const n = Math.max(end - first + 1, 0);
    const blockTop = this.blockTopFor(first, n);
    rows.style.top = `${blockTop}px`;
    this.layerState = { first, n, blockTop };
    this.syncScrollLayers();
    for (let line = first; line <= end; line++) {
      // `entry` is the ABSORBED window (it has `lines`); the served frame does not
      // — see the EOF guard in ensureLines.
      const entry = this.windowForLine(line);
      const top = `${(line - first) * LINE_HEIGHT}px`;
      const num = document.createElement('span');
      num.className = 'lt-num';
      num.dataset.line = String(line);
      num.textContent = String(line);
      num.style.top = top;
      gutter.appendChild(num);

      const row = document.createElement('div');
      row.className = 'lt-row';
      row.dataset.line = String(line);
      row.style.top = top;
      if (this.hitLines.has(line)) row.classList.add('lt-hit');
      if (entry) {
        const i = line - entry.firstLine;
        const text = entry.lines[i];
        if (text !== undefined) {
          if (entry.partialFirst && i === 0) {
            row.dataset.partial = '1';
            row.textContent = `… ${text}`;
          } else {
            row.textContent = text;
          }
        } else {
          row.classList.add('lt-pending');
        }
      } else {
        row.classList.add('lt-pending');
      }
      rows.appendChild(row);
    }
  }

  /** Keep the two scroll-linked layers (row block + gutter column) on the same
    * offset. The gutter is a non-scrolling sibling, so it carries the block's
    * position minus the scroll offset; without this the numbers would drift away
    * from their rows as soon as the mapping is compressed. */
  syncScrollLayers() {
    const scrollTop = this.currentScrollTop();
    const st = this.layerState;
    if (!st) {
      if (this.gutterInner) this.gutterInner.style.top = `${-scrollTop}px`;
      return;
    }
    const blockTop = this.blockTopFor(st.first, st.n);
    if (blockTop !== st.blockTop) {
      this.rows.style.top = `${blockTop}px`;
      st.blockTop = blockTop;
    }
    this.gutterInner.style.top = `${blockTop - scrollTop}px`;
  }

  setStatus(state, text) {
    this.status = state;
    this.statusEl.dataset.state = state;
    this.statusEl.textContent = text || '';
    if (state === 'error') {
      const retry = document.createElement('button');
      retry.className = 'lt-btn lt-retry';
      retry.type = 'button';
      retry.textContent = t('canvas.largeTextRetry');
      retry.addEventListener('click', () => {
        this.error = null;
        this.setStatus('loading', t('canvas.largeTextLoading'));
        void this.reload();
      });
      this.statusEl.appendChild(retry);
    }
  }

  fail(err) {
    const message = err && err.message ? err.message : String(err);
    this.error = message;
    console.warn('largeText view error:', message);
    this.setStatus('error', t('canvas.largeTextLoadFailed', { error: message }));
  }

  // ── jump ─────────────────────────────────────────────────────────────────

  async jumpToLine(line) {
    const g = this.geometry();
    const target = Math.max(1, Math.min(line, g.total));
    await this.ensureLines(target, target);
    // The strip for `line` starts at (line-1) × pxPerLine in scroll space — the same
    // mapping the scrollbar uses, and identical to the old (line-1) × 20 whenever the
    // document is not compressed. Beyond the engine ceiling the engine itself clamps
    // scrollTop, so the target is clamped here first and the block clamp in drawRows
    // keeps the tail (which is where a clamped jump lands) in view.
    const maxTop = Math.max(0, g.height - this.scroller.clientHeight);
    this.scroller.scrollTop = Math.min((target - 1) * g.pxPerLine, maxTop);
    this.render();
    this.flash(target);
  }

  flash(line) {
    const row = /** @type {HTMLElement|null} */ (this.rows.querySelector(`.lt-row[data-line="${line}"]`));
    if (!row) return;
    row.classList.add('lt-flash');
    setTimeout(() => row.classList.remove('lt-flash'), 900);
  }

  // ── search ───────────────────────────────────────────────────────────────

  async runSearch(query) {
    const q = (query || '').trim();
    if (!q) return;
    this.searchToken += 1;
    const token = this.searchToken;
    // Every search starts from an EMPTY hit set, the status row's counts included:
    // the previous query's hits must not survive into this one, or a query with no
    // matches reports the stale count and the stale highlights stay on screen
    // (verify3 F4).
    this.resetHits();
    this.setStatus('searching', t('canvas.largeTextSearching'));
    try {
      const res = await this.client.search(q, {
        caseSensitive: false,
        // Hit frames stream in while the server scans, so they are applied as they
        // arrive (a long scan shows its matches before it finishes).
        onHits: (hits) => { if (token === this.searchToken) this.applyHits(hits); },
      });
      if (token !== this.searchToken) return; // superseded by a newer search
      // The terminal frame's `hits` IS the list those hit frames already streamed
      // (textStream.js accumulates one array and resolves with it), so it REPLACES
      // the applied set instead of being appended a second time — appending doubled
      // every count and the match navigation with it (verify3 F3).
      this.setHits(res.hits || []);
      const truncated = !!res.truncated;
      this.statusEl.dataset.truncated = truncated ? '1' : '0';
      const label = truncated
        ? t('canvas.largeTextSearchTruncated', { count: String(this.hits.length) })
        : t('canvas.largeTextSearchHits', { count: String(this.hits.length) });
      this.setStatus(truncated ? 'search-truncated' : 'search-done', label);
      this.searchToken += 1; // keep the token newer than any late hit frame
      this.activeHit = this.hits.length ? 0 : -1;
      if (this.activeHit === 0) void this.gotoHit(0);
    } catch (err) {
      if (token !== this.searchToken) return;
      this.fail(err);
    }
  }

  /** Empty hit state (also clears the status row's mirrors, which assertions read). */
  resetHits() {
    this.hits = [];
    this.hitLines = new Set();
    this.activeHit = -1;
    this.statusEl.dataset.hits = '0';
    this.statusEl.dataset.hitLines = '';
    this.statusEl.dataset.truncated = '0';
    this.render();
  }

  /** Progressive application of the streamed hit frames: append + repaint. */
  applyHits(hits) {
    for (const h of hits) {
      this.hits.push(h);
      this.hitLines.add(h.line);
    }
    this.syncHitDataset();
    this.render();
  }

  /** Authoritative list from the search's terminal frame: REPLACE, never append. */
  setHits(hits) {
    this.hits = Array.isArray(hits) ? hits.slice() : [];
    this.hitLines = new Set(this.hits.map((h) => h.line));
    this.syncHitDataset();
    this.render();
  }

  syncHitDataset() {
    this.statusEl.dataset.hitLines = this.hits.map((h) => h.line).join(',');
    this.statusEl.dataset.hits = String(this.hits.length);
  }

  stepHit(delta) {
    if (this.hits.length === 0) return;
    const n = this.hits.length;
    this.activeHit = ((this.activeHit + delta) % n + n) % n;
    void this.gotoHit(this.activeHit);
  }

  async gotoHit(i) {
    const hit = this.hits[i];
    if (!hit) return;
    await this.jumpToLine(hit.line);
    const row = /** @type {HTMLElement|null} */ (this.rows.querySelector(`.lt-row[data-line="${hit.line}"]`));
    if (row) row.classList.add('lt-hit-active');
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  destroy() {
    if (this.closed) return;
    this.closed = true;
    this.client.cancel();
    if (this.resizeObserver) this.resizeObserver.disconnect();
    this.windows.clear();
  }

  /** Background refresh reported new file metadata: invalidate + reload if changed. */
  acceptMeta(meta) {
    if (this.closed) return false;
    return this.client.acceptMeta(meta);
  }
}

/**
 * Viewer protocol entry (`render(pane, ctx)`); reached through the single-point
 * branch in fileViewers.js when the open-leg descriptor carries
 * `stream:{v:1,kind:"text"}`.
 * @param {HTMLElement} pane @param {any} ctx
 */
async function renderLargeText(pane, ctx) {
  const existing = /** @type {any} */ (pane)._streamClient;
  if (existing && typeof existing.destroy === 'function') existing.destroy();
  const view = new LargeTextView(pane, ctx);
  /** @type {any} */ (pane)._streamClient = view;
  await view.start();
}

export default {
  // `textstream` is not an itemType: the read-only view is selected by the
  // descriptor's `stream` field, not by an extension (the extension table stays
  // owned by the registry, one source only).
  name: 'textstream',
  label: 'Large text (read-only)',
  extensions: [],
  binary: false,
  priority: 0,
  render: renderLargeText,
};
