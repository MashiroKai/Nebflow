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
  const SRC_RE = /\bsrc\s*=\s*(["'])([^"']+)\1/gi;
  const HREF_RE = /\bhref\s*=\s*(["'])([^"']+)\1/gi;
  const candidates = new Set();
  const scan = (re) => {
    html.replace(re, (m, _q, url) => {
      const p = toPath(url);
      if (p) candidates.add(p);
      return m;
    });
  };
  scan(SRC_RE);
  scan(HREF_RE);
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
  // src= attributes
  html = html.replace(SRC_RE, (m, q, src) => {
    const p = toPath(src);
    return p ? `src=${q}${toUrl(p)}${q}` : m;
  });
  // href= attributes (for <link> stylesheets, <a> anchors with local paths)
  html = html.replace(HREF_RE, (m, q, href) => {
    const p = toPath(href);
    return p ? `href=${q}${toUrl(p)}${q}` : m;
  });
  // Already-proxied URLs (pass 2b) — credential injected, path untouched.
  html = injectTickets(html, tickets);
  return html;
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
