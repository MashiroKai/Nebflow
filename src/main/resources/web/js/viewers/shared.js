// viewers/shared.js — utilities shared by multiple built-in file viewers.
// Migrated verbatim from fileViewers.js (behavior unchanged).

import { key } from '../branding.js';
import { t } from '../i18n.js';
import { mintTickets, nfFilePathsIn, injectTickets } from '../nfTicket.js';
export function getToken() {
  return localStorage.getItem(key('token')) || '';
}

export function escapeHtml(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

export function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// ── HTML viewer utilities (shared with cardRegistry pattern) ──────────

/** Collect all --color-* CSS custom properties from the parent document's :root.
 *  Injected into the iframe so dark mode works without media queries. */
export function buildThemeVarsCSS() {
  const root = getComputedStyle(document.documentElement);
  const pairs = [];
  for (const sheet of document.styleSheets) {
    try {
      for (const rule of sheet.cssRules) {
        if (rule.selectorText === ':root') {
          for (const prop of rule.style) {
            if (prop.startsWith('--color-') || prop.startsWith('--glass') || prop.startsWith('--sapphire')) {
              const val = root.getPropertyValue(prop).trim();
              if (val) pairs.push(`${prop}:${val}`);
            }
          }
        }
      }
    } catch (e) { /* cross-origin stylesheet, skip */ }
  }
  return pairs.length > 0 ? `:root{${pairs.join(';')}}` : '';
}

// ── Tag-context-aware attribute walking ────────────────────────────────────
// The pre-2026-09-12 rewriter matched `\bsrc\s*=` / `\bhref\s*=` ANYWHERE in the
// document. `\b` matches after `-` and after a quote, so it rewrote things that
// are not the attribute at all — measured on a real deliverable: an
// `onclick="location.href='/zzz'"` became `location.href='/api/nf-file?path=…'`
// (clicking it really navigated the frame), a `data-src="/tmp/…png"` lost its
// own value, and body text that merely *contained* `href='/x'` was rewritten —
// user-visible content corruption, on every page this viewer renders.
// Attribute rewriting now happens inside a tag, and only on the real attribute
// name (a `^|whitespace` guard excludes `data-href=` / `data-src=`).

/** Element tags whose children are character data: the walker copies their
 *  bodies verbatim, so an `<a href=…>` written inside a <script> string stays
 *  data instead of being read as markup. */
const RAW_TEXT_TAGS = new Set(['script', 'style', 'textarea', 'title']);

/** Walk the element tags of an HTML string, letting `onTag` rewrite each tag's
 *  attribute text. Returning the attribute text unchanged keeps the tag
 *  byte-identical — the document is never re-serialized.
 *  @param {string} html
 *  @param {(tag: string, attrs: string) => string} onTag
 *  @returns {string} */
function mapTags(html, onTag) {
  const re = /<([a-zA-Z][a-zA-Z0-9:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  const lower = html.toLowerCase();
  let out = '';
  let last = 0;
  /** @type {RegExpExecArray|null} */
  let m;
  while ((m = re.exec(html)) !== null) {
    const tag = m[1].toLowerCase();
    const attrs = m[2];
    out += html.slice(last, m.index);
    const mapped = onTag(tag, attrs);
    out += mapped === attrs ? m[0] : '<' + m[1] + mapped + '>';
    last = re.lastIndex;
    if (RAW_TEXT_TAGS.has(tag) && !/\/\s*$/.test(attrs)) {
      const close = lower.indexOf('</' + tag, last);
      const gt = close === -1 ? -1 : html.indexOf('>', close);
      if (gt !== -1) {
        out += html.slice(last, gt + 1);
        last = gt + 1;
        re.lastIndex = last;
      }
    }
  }
  return out + html.slice(last);
}

/** Map the QUOTED attributes inside one tag's attribute text.
 *  `onAttr(name, value)` returns the replacement value, or null to keep it.
 *  Unquoted values are not rewritten — unchanged gap, the old regexes required
 *  quotes too.
 *  @param {string} attrs
 *  @param {(name: string, value: string) => string|null} onAttr
 *  @returns {string} */
function mapAttrs(attrs, onAttr) {
  return attrs.replace(/(^|\s)([a-zA-Z_:][-a-zA-Z0-9_:.]*)(\s*=\s*)(["'])([^"']*)\4/g,
    (full, lead, name, eq, quote, value) => {
      const next = onAttr(name.toLowerCase(), value);
      return next === null ? full : lead + name + eq + quote + next + quote;
    });
}

/** Escape an attribute value we produce ourselves. */
function escapeAttrValue(v) {
  return String(v).replace(/&/g, '&amp;').replace(/"/g, '&quot;');
}

/** Convert local file paths in src= and href= attributes to /api/nf-file URLs.
 *  Handles: absolute paths (/tmp/..., ~/...), relative paths (relative to HTML dir).
 *  Skips: http://, https://, data:, #, javascript:, blob:, /api/ (already proxied).
 *
 *  2026-09-11 (C batch): async, and the credential leg is a per-path ticket
 *  instead of the global token. The scan runs FIRST and mints one batched
 *  ticket request for every distinct candidate (an HTML deliverable commonly
 *  references a dozen companions — one request, not a dozen); the rewrite runs
 *  second, synchronously. A failed mint is not fatal: the URL is emitted
 *  without a ticket, the endpoint answers 401, and the viewer's existing
 *  failure path shows it (§4.3 F2).
 *
 *  2026-09-12 (canvas-preview nav parity): `<a href>` is NO LONGER turned into
 *  a proxy URL. A proxied anchor is an in-frame same-origin HTTP navigation,
 *  and that is exactly what the viewer's navigation guard used to answer by
 *  destroying the pane ("Rendering was stopped…"), while `target="_blank"`
 *  could not work either (html/md are outside the extension whitelist and
 *  data-root `docs/**` is refused, so the popup opened a 401; the chat card
 *  sandbox has no allow-popups at all). An anchor's resolved absolute path now
 *  rides in `data-nf-local-link` and the href itself is left byte-identical
 *  (relative semantics, status-bar preview and CSS untouched); the click is
 *  routed to a Canvas tab by `localLinkNavScript` + `bindLocalLinkBridge`
 *  below. Every RESOURCE-shaped src=/href= (`<img>`, `<link>`, `<script>`,
 *  `<video>`, `<audio>`, `<source>`, `<iframe>`, …) keeps the proxy + ticket
 *  form: those bytes must be reachable from inside the frame, which is the
 *  browser semantics this feature is supposed to preserve.
 *
 *  The `token` parameter is gone: no credential is ever built into these URLs
 *  any more (T2 — a credential must not reach a serializable text face).
 *
 *  CSS `url()` / `@import` returns are NOT rewritten here — a known, unchanged
 *  gap on this path (shared.js:60-72 only ever handled src=/href=). The Card
 *  path DOES rewrite them (CardTool side); see cardRegistry.js for its own
 *  injection, which covers the unquoted `url()` form too. */
export async function resolveLocalFiles(html, dir) {
  const toPath = (src) => {
    if (/^(https?:|data:|#|javascript:|blob:|\/api\/|mailto:|tel:)/i.test(src)) return null;
    if (!src || src.trim() === '') return null;
    if (src.startsWith('/') || /^[A-Za-z]:[\\/]/.test(src) || src.startsWith('~')) {
      // Keep `~` intact — the /api/nf-file endpoint expands it to the user
      // home. The old implementation stripped the `~` prefix (contradicting
      // this comment's claim) and sent `/x.png` — an absolute path at the
      // filesystem root — so every `~/…` src/href in an HTML file 404'd.
      return src;
    }
    if (dir) return dir + '/' + src.replace(/^\.\//, '');
    return null; // can't resolve without dir
  };
  /** The local file an `<a href>` points at, '' when it is not a local file.
   *  An `/api/nf-file?path=…` anchor (a replayed card, or hand-written markup)
   *  is decoded back to its path so the click stays on the local-file route
   *  instead of navigating the frame to the proxy URL. */
  const anchorPath = (href) => {
    const h = href || '';
    const proxied = /^\/api\/nf-file\?(?:[^"'#]*&)?path=([^&"']*)/i.exec(h);
    if (proxied) {
      try { return decodeURIComponent(proxied[1]); } catch (_) { return ''; }
    }
    return toPath(h) || '';
  };
  const candidates = new Set();
  mapTags(html, (tag, attrs) => {
    mapAttrs(attrs, (name, value) => {
      // `<a href>` needs no ticket any more (see the header): it is routed to
      // the Canvas tab leg, which reads the file over the existing WS leg.
      if (name === 'src' || (name === 'href' && tag !== 'a')) {
        const p = toPath(value);
        if (p) candidates.add(p);
      }
      return null;
    });
    return attrs;
  });
  // Already-proxied `/api/nf-file?path=…` URLs are NOT left alone any more.
  // They used to be skipped as "already resolved" — true while the credential
  // was the global token, which such a URL did not carry either. Now a
  // credential-free nf-file URL is a guaranteed 401, and these URLs do occur:
  // a persisted/replayed card, or hand-written markup, arrives with the
  // proxy URL already in it (and with a ticket that has long expired).
  // Authentication is therefore applied to them in place.
  for (const p of nfFilePathsIn(html)) candidates.add(p);
  // F1: zero candidates → zero mint requests (mintTickets early-returns).
  const tickets = await mintTickets([...candidates]);
  const toUrl = (p) => {
    const t = tickets.get(p);
    return `/api/nf-file?path=${encodeURIComponent(p)}${t ? '&ticket=' + encodeURIComponent(t) : ''}`;
  };
  html = mapTags(html, (tag, attrs) => {
    const isAnchor = tag === 'a';
    let out = mapAttrs(attrs, (name, value) => {
      if (name !== 'src' && name !== 'href') return null;
      if (isAnchor && name === 'href') return null;   // marker attribute below, href kept as authored
      const p = toPath(value);
      return p ? toUrl(p) : null;
    });
    if (isAnchor) {
      const m = /(^|\s)href\s*=\s*(["'])([^"']*)\2/i.exec(attrs);
      const p = m ? anchorPath(m[3]) : '';
      if (p) {
        // Idempotent: a document that already carries the marker gets exactly
        // one, with the path resolved for THIS document's directory.
        out = out.replace(/(^|\s)data-nf-local-link\s*=\s*(["'])[^"']*\2/gi, '');
        out += ` data-nf-local-link="${escapeAttrValue(p)}"`;
      }
    }
    return out;
  });
  // Already-proxied URLs (pass 2b) — credential injected, path untouched.
  html = injectTickets(html, tickets);
  return html;
}

// ── Local-link routing: frame side + parent side (2026-09-12) ─────────────
// Two halves of one channel, shared by BOTH surfaces that render agent HTML —
// the Canvas HTML viewer (viewers/html.js) and the chat card
// (cardRegistry.js) — so the two can never drift into two behaviours (the same
// "one implementation" rule nfTicket.js follows for the proxy-URL regex).

/** Dormant script injected into an HTML frame: local-file and external link
 *  clicks are routed to the parent instead of letting the frame navigate.
 *  Registered in the capture phase on `document` (the pattern html.js's
 *  `imgClickScript` uses), so page-level handlers cannot outrun it; covers
 *  left-click, middle-click (`auxclick`) and keyboard activation (a synthetic
 *  click with detail 0). `#` anchors are left to the viewer's own in-frame
 *  scroll script, and a local link that cannot be resolved to a path gets a
 *  VISIBLE inline note rather than a silent dead click.
 *  @returns {string} */
export function localLinkNavScript() {
  return `<script>
(function(){
  var CRED = /^\\/api\\/nf-file\\?(?:[^"'#]*&)?path=([^&"']*)/i;
  var NOT_LOCAL = /^(https?:|data:|#|javascript:|blob:|mailto:|tel:|file:)/i;
  function decode(u){ try { return decodeURIComponent(u); } catch (e) { return ''; } }
  function pathOf(a){
    var marked = a.getAttribute('data-nf-local-link');
    if (marked) return marked;
    var href = a.getAttribute('href') || '';
    var m = CRED.exec(href);
    if (m) return decode(m[1]);
    if (!href || NOT_LOCAL.test(href)) return '';
    if (href.charAt(0) === '/' || href.charAt(0) === '~' || /^[A-Za-z]:[\\\\/]/.test(href)) return href;
    return '';
  }
  function unsolved(a){
    if (a.getAttribute('data-nf-link-unsolved')) return;
    a.setAttribute('data-nf-link-unsolved', '1');
    var s = document.createElement('span');
    s.setAttribute('data-nf-link-unsolved-note', '1');
    s.textContent = ' (local link: no path to open from this preview)';
    s.style.cssText = 'font-size:11px;color:var(--color-text-muted,#8b8e96);';
    if (a.parentNode) a.parentNode.insertBefore(s, a.nextSibling);
  }
  function onClick(e){
    if (e.defaultPrevented) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (!href || href.charAt(0) === '#') return;
    if (/^https?:/i.test(href)) {
      e.preventDefault();
      parent.postMessage({ _nfOpenExternalUrl: { url: href } }, '*');
      return;
    }
    if (NOT_LOCAL.test(href)) return;
    var path = pathOf(a);
    e.preventDefault();
    if (!path) { unsolved(a); return; }
    parent.postMessage({ _nfOpenLocalFile: {
      path: path,
      newTab: !!(e.metaKey || e.ctrlKey || e.shiftKey || e.type === 'auxclick')
    } }, '*');
  }
  document.addEventListener('click', onClick, true);
  document.addEventListener('auxclick', onClick, true);
})();
<\/script>`;
}

let _localLinkBridgeBound = false;

/** Parent side of `localLinkNavScript`. One global listener for the whole app
 *  (idempotent); the sender must be a frame this app renders, the same
 *  sender-verification discipline viewers/html.js uses for `_nfZoomWheel` /
 *  `_nfRefPick`.
 *
 *  The local-file leg is the one that predates this batch: `workspace-open-item`
 *  → canvas.js `openWorkspaceItem` → `pop.readFile` → FileTypeRegistry picks a
 *  viewer → the target opens as a Canvas tab (`.html` → HTML viewer, `.md` →
 *  markdown, `.png` → image). No `/api/nf-file` request is made, and the read
 *  face keeps the C batch's tightening untouched — a "browser-grade" link must
 *  not become an argument for widening it. */
export function bindLocalLinkBridge() {
  if (_localLinkBridgeBound) return;
  _localLinkBridgeBound = true;
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (!d || (!d._nfOpenLocalFile && !d._nfOpenExternalUrl)) return;
    if (!isRenderedFrame(/** @type {Window|null} */ (e.source))) return;
    const local = d._nfOpenLocalFile;
    if (local) openLocalFileTab(String(local.path || ''));
    else openExternalUrlTab(String(d._nfOpenExternalUrl.url || ''));
  });
}

/** True when `src` is the contentWindow of a frame this app renders (Canvas
 *  HTML viewer or chat card).
 *  @param {Window|null} src @returns {boolean} */
function isRenderedFrame(src) {
  if (!src) return false;
  const frames = document.querySelectorAll('iframe[data-nf-canvas-html], iframe.html-card-iframe');
  for (const f of frames) {
    if (/** @type {HTMLIFrameElement} */ (f).contentWindow === src) return true;
  }
  return false;
}

/** Open a local file as a Canvas tab.
 *  `newTab` from the frame is intentionally NOT turned into a second tab for an
 *  already-open path: the tab store dedupes by absPath (canvas.js:898-902) and
 *  the author's requirement is that a link behaves the same whether or not the
 *  markup writes `target` — form equivalence is the point, not tab bookkeeping.
 *  @param {string} path */
function openLocalFileTab(path) {
  if (!path) return;
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + path, itemType: '', content: '', absPath: path },
  }));
  revealCanvas();
}

/** Open an external http(s) link as a Canvas URL tab (canvas.js renderUrlPane,
 *  whose sandbox already carries allow-popups), so following a link stays
 *  inside the Canvas — the browser-grade equivalent, with no detour through the
 *  real browser.
 *  @param {string} url */
function openExternalUrlTab(url) {
  if (!/^https?:\/\//i.test(url)) return;
  let title = url;
  try { title = new URL(url).hostname || url; } catch (_) { /* keep the raw url */ }
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'url:' + url, itemType: 'url', title, url },
  }));
  revealCanvas();
}

/** A click inside a chat card must be VISIBLE: with the Canvas panel closed the
 *  tab it just opened would be invisible — indistinguishable from the dead link
 *  this batch exists to fix. Dynamic import on purpose: canvas.js statically
 *  imports the viewer registry, which imports this module. */
function revealCanvas() {
  import('../canvas.js')
    .then((m) => { if (!m.isCanvasOpen()) m.openCanvas(); })
    .catch(() => { /* canvas module unavailable — the event still opened the tab */ });
}

// ── Render/source toggle (markdown & HTML viewers) ─────────────────────

const CODE_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>';
const EYE_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
// #303 B6: crosshair icon for the "select element to reference" mode toggle.
const CROSSHAIR_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><line x1="21" y1="12" x2="17" y2="12"/><line x1="7" y1="12" x2="3" y2="12"/><line x1="12" y1="7" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="17"/></svg>';

/** Add a floating rendered↔source toggle button to a viewer pane.
 *  Rendered → source: clear the render, mount a Monaco editor with the source.
 *  Source → rendered: pull the latest content from the Monaco model (survives
 *  edits), dispose the editor, re-run the viewer — which re-attaches this
 *  toggle with the updated ctx.
 *  @param {HTMLElement} pane — canvas tab pane
 *  @param {Function} renderFn — viewer function (viewMarkdown / viewHtml)
 *  @param {Object} ctx — { content, absPath, fileName } */
export function addSourceToggle(pane, renderFn, ctx) {
  pane.querySelector('.canvas-source-toggle')?.remove();
  // The button is absolutely positioned — the pane must be a positioning
  // context. (.canvas-tab-pane already is; defensive fallback only.)
  if (getComputedStyle(pane).position === 'static') pane.style.position = 'relative';

  const btn = document.createElement('button');
  btn.className = 'canvas-source-toggle';
  btn.innerHTML = CODE_ICON_SVG;
  btn.title = 'View source';

  let busy = false;  // guard against clicks during async Monaco load
  btn.addEventListener('click', async () => {
    if (busy) return;
    busy = true;
    try {
      const inSource = pane.dataset.sourceMode === '1';
      if (!inSource) {
        // → Source mode
        pane.dataset.sourceMode = '1';
        btn.innerHTML = EYE_ICON_SVG;
        btn.title = 'View rendered';
        if (pane._editorHandle) { pane._editorHandle.dispose(); pane._editorHandle = null; }
        pane.innerHTML = '';
        pane.classList.remove('scrollable');

        const container = document.createElement('div');
        container.className = 'canvas-monaco-container';
        container.style.width = '100%';
        container.style.height = '100%';
        if (!window.monaco) {
          container.innerHTML = '<div class="canvas-loading"><div class="canvas-loading-spinner"></div></div>';
        }
        pane.appendChild(container);
        pane.appendChild(btn);  // innerHTML='' above detached it

        const { createEditor, setActiveEditor } = await import('../monacoEditor.js');
        const handle = await createEditor(container, {
          path: ctx.absPath || ctx.fileName,
          content: ctx.content || '',
          fileName: ctx.fileName,
        });
        container.querySelector('.canvas-loading')?.remove();
        pane._editorHandle = handle;

        // Same wiring as viewMonaco: dirty indicator + global Ctrl+S routing
        handle.onDirty((dirty) => {
          pane._dirty = dirty;
          pane.dispatchEvent(new CustomEvent('editor-dirty-change', { detail: { dirty } }));
        });
        pane.addEventListener('canvas-tab-activated', () => {
          if (pane._editorHandle !== handle) return;  // stale editor from an old toggle cycle
          setActiveEditor(handle);
          handle.focus();
        });
        if (pane.classList.contains('active')) setActiveEditor(handle);
      } else {
        // → Rendered mode. Take the latest content from the Monaco model so
        // unsaved edits survive the round-trip (createEditor reuses the cached
        // model — passing stale ctx.content would wipe them).
        let latest = ctx.content;
        if (pane._editorHandle) {
          latest = pane._editorHandle.model.getValue();
          pane._editorHandle.dispose();
          pane._editorHandle = null;
        }
        delete pane.dataset.sourceMode;
        // renderFn clears the pane and re-attaches the toggle via its own
        // addSourceToggle call, carrying the updated content forward.
        await renderFn(pane, { ...ctx, content: latest });
      }
    } finally {
      busy = false;
    }
  });
  pane.appendChild(btn);
}

/** Add a floating "select element to reference" toggle button to a viewer pane
 *  (#303 B6). Same floating-glass style and same corner as the source toggle
 *  (`.canvas-source-toggle`), seated to its left. Element-selection is only
 *  offered where there is a DOM to select — the HTML viewer (srcdoc/same-origin
 *  iframe). Other viewers (PDF/image/cross-origin URL) show no toggle: they
 *  were the 'nodom'/'crossorigin' capability-matrix modes, removed so the
 *  affordance is zero-trace outside HTML tabs (20260830 author ruling).
 *  @param {HTMLElement} pane — canvas tab pane
 *  @param {Object} opts — { onToggle } ; onToggle(active) toggles select mode
 *  @returns {HTMLButtonElement} */
export function addElementRefToggle(pane, opts) {
  const { onToggle } = opts || {};
  pane.querySelector('.canvas-ref-select')?.remove();
  if (getComputedStyle(pane).position === 'static') pane.style.position = 'relative';

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'canvas-ref-select';
  btn.innerHTML = CROSSHAIR_ICON_SVG;

  btn.title = t('ref.selectElement');
  btn.setAttribute('aria-pressed', 'false');
  // State is DOM-class-driven (not a closure flag) so an external exit
  // (Esc / tab switch via the viewer's state machine) stays in sync with
  // the next click.
  btn.addEventListener('click', () => {
    const active = !btn.classList.contains('active');
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-pressed', String(active));
    btn.title = active ? t('ref.exitSelect') : t('ref.selectElement');
    if (typeof onToggle === 'function') onToggle(active);
  });
  pane.appendChild(btn);
  return btn;
}

// ── ZIP utilities (EPUB viewer) ────────────────────────────────────────

/** Parse ZIP central directory → map of filename → { compMethod, data (Uint8Array) } */
export function parseZipEntries(buffer) {
  const view = new DataView(buffer);
  const u8 = new Uint8Array(buffer);
  // Find End of Central Directory record (search from end)
  let eocd = -1;
  for (let i = buffer.byteLength - 22; i >= Math.max(0, buffer.byteLength - 65536); i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd === -1) throw new Error('Invalid ZIP: EOCD not found');
  const cdOffset = view.getUint32(eocd + 16, true);
  const cdCount = view.getUint16(eocd + 10, true);
  const entries = {};
  let off = cdOffset;
  for (let i = 0; i < cdCount; i++) {
    if (view.getUint32(off, true) !== 0x02014b50) break;
    const compMethod = view.getUint16(off + 10, true);
    const compSize = view.getUint32(off + 20, true);
    const nameLen = view.getUint16(off + 28, true);
    const extraLen = view.getUint16(off + 30, true);
    const commentLen = view.getUint16(off + 32, true);
    const localOff = view.getUint32(off + 42, true);
    const name = new TextDecoder().decode(u8.subarray(off + 46, off + 46 + nameLen));
    // Read local header for actual data offset
    const lNameLen = view.getUint16(localOff + 26, true);
    const lExtraLen = view.getUint16(localOff + 28, true);
    const dataOff = localOff + 30 + lNameLen + lExtraLen;
    entries[name] = { compMethod, data: u8.subarray(dataOff, dataOff + compSize) };
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

/** Decompress a ZIP entry to text. Method 0 = stored, 8 = deflate. */
export async function decompressZipEntry(entry) {
  if (!entry) return '';
  if (entry.compMethod === 0) return new TextDecoder().decode(entry.data);
  if (entry.compMethod === 8) {
    const ds = new DecompressionStream('deflate-raw');
    const stream = new Blob([entry.data]).stream().pipeThrough(ds);
    return await new Response(stream).text();
  }
  throw new Error('Unsupported ZIP compression: ' + entry.compMethod);
}
