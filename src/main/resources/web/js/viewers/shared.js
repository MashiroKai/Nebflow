// viewers/shared.js — utilities shared by multiple built-in file viewers.
// Migrated verbatim from fileViewers.js (behavior unchanged).

export function getToken() {
  return localStorage.getItem('nebflow_token') || '';
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
 *  Skips: http://, https://, data:, #, javascript:, blob:, /api/ (already proxied). */
export function resolveLocalFiles(html, dir, token) {
  const tokParam = token ? `&token=${encodeURIComponent(token)}` : '';
  const toFileUrl = (src) => {
    if (/^(https?:|data:|#|javascript:|blob:|\/api\/|mailto:|tel:)/i.test(src)) return null;
    if (!src || src.trim() === '') return null;
    let resolved;
    if (src.startsWith('/') || /^[A-Za-z]:[\\/]/.test(src) || src.startsWith('~')) {
      resolved = src.replace(/^~/, ''); // backend expands ~
    } else if (dir) {
      resolved = dir + '/' + src.replace(/^\.\//, '');
    } else {
      return null; // can't resolve without dir
    }
    return `/api/nf-file?path=${encodeURIComponent(resolved)}${tokParam}`;
  };
  // src= attributes
  html = html.replace(/\bsrc\s*=\s*(["'])([^"']+)\1/gi, (m, q, src) => {
    const url = toFileUrl(src);
    return url ? `src=${q}${url}${q}` : m;
  });
  // href= attributes (for <link> stylesheets, <a> anchors with local paths)
  html = html.replace(/\bhref\s*=\s*(["'])([^"']+)\1/gi, (m, q, href) => {
    const url = toFileUrl(href);
    return url ? `href=${q}${url}${q}` : m;
  });
  return html;
}

// ── Render/source toggle (markdown & HTML viewers) ─────────────────────

const CODE_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>';
const EYE_ICON_SVG = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';

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
