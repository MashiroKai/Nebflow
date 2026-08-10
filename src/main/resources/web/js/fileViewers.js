// fileViewers.js — Pluggable file viewer registry for Canvas tabs.
//
// Each viewer is a function: (paneEl, ctx) => void
// ctx = { content, absPath, fileName, size, itemType }
//
// The registry maps itemType → viewer function.

function getToken() {
  return localStorage.getItem('nebflow_token') || '';
}

function escapeHtml(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// ── HTML viewer utilities (shared with cardRegistry pattern) ──────────

/** Collect all --color-* CSS custom properties from the parent document's :root.
 *  Injected into the iframe so dark mode works without media queries. */
function buildThemeVarsCSS() {
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
function resolveLocalFiles(html, dir, token) {
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

/** Script injected into the iframe to listen for theme changes from the parent.
 *  When the parent switches light/dark, it posts the new CSS vars to all iframes. */
const themePropScript = `<script>
(function(){
  window.addEventListener('message',function(e){
    if(e.data&&e.data._nfThemeVars){
      var s=document.getElementById('nf-canvas-theme');
      if(!s){s=document.createElement('style');s.id='nf-canvas-theme';document.head.appendChild(s);}
      s.textContent=':root{'+e.data._nfThemeVars+'}';
    }
  });
})();
<\/script>`;

/** Script injected into the iframe to intercept # anchor clicks and scroll
 *  within the iframe instead of navigating to the parent URL. */
const anchorNavScript = `<script>
(function(){
  document.addEventListener('click', function(e) {
    var link = e.target.closest('a[href^="#"]');
    if (!link) return;
    var href = link.getAttribute('href');
    if (!href || href === '#') { e.preventDefault(); window.scrollTo(0, 0); return; }
    e.preventDefault();
    var id = href.slice(1);
    var target = document.getElementById(id);
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
})();
<\/script>`;

/** Watch for system theme changes and propagate CSS vars to all Canvas HTML iframes.
 *  Initialized once on first viewHtml call. */
let _canvasThemeWatcherInit = false;
function initCanvasThemeWatcher() {
  if (_canvasThemeWatcherInit) return;
  _canvasThemeWatcherInit = true;
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const handler = () => {
    const css = buildThemeVarsCSS();
    if (!css) return;
    const vars = css.replace(':root{', '').replace('}', '').trim();
    document.querySelectorAll('.canvas-tab-pane iframe[data-nf-canvas-html]').forEach(iframe => {
      try {
        if (iframe.contentWindow) iframe.contentWindow.postMessage({ _nfThemeVars: vars }, '*');
      } catch (e) { /* cross-origin */ }
    });
  };
  mq.addEventListener('change', handler);
}

// ── Built-in viewers ───────────────────────────────────────────────────

/** Monaco editor viewer — replaces read-only code viewer with full editing.
 *  Used for all text-based file types (code, markdown, json, csv, etc.) */
async function viewMonaco(pane, ctx) {
  // Show loading indicator while Monaco loads (first time only).
  pane.innerHTML = '<div class="canvas-loading"><div class="canvas-loading-spinner"></div></div>';

  const { createEditor } = await import('./monacoEditor.js');
  // Create a container for Monaco
  const container = document.createElement('div');
  container.className = 'canvas-monaco-container';
  container.style.width = '100%';
  container.style.height = '100%';
  pane.innerHTML = '';
  pane.appendChild(container);

  const handle = await createEditor(container, {
    path: ctx.path || ctx.fileName,
    content: ctx.content || '',
    fileName: ctx.fileName,
    rootPath: ctx.rootPath || null,
  });

  // Store handle on the pane for canvas.js to access
  pane._editorHandle = handle;

  // Forward dirty state to the pane (for tab indicator)
  handle.onDirty((dirty) => {
    pane._dirty = dirty;
    pane.dispatchEvent(new CustomEvent('editor-dirty-change', { detail: { dirty } }));
  });

  // Notify canvas of the active editor
  const { setActiveEditor } = await import('./monacoEditor.js');
  pane.addEventListener('canvas-tab-activated', () => {
    setActiveEditor(handle);
    handle.focus();
  });

  // If this pane is already active (just opened), set as active editor
  if (pane.classList.contains('active')) {
    setActiveEditor(handle);
  }
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
function addSourceToggle(pane, renderFn, ctx) {
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

        const { createEditor, setActiveEditor } = await import('./monacoEditor.js');
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

/** Markdown viewer — render formatted markdown (read-only preview) */
async function viewMarkdown(pane, { content, absPath, fileName }) {
  const { renderMarkdownWithMath } = await import('./utils.js');
  let html = renderMarkdownWithMath(content || '', false);

  // Resolve relative image paths against the markdown file's directory.
  if (absPath) {
    const sep = absPath.includes('\\') ? '\\' : '/';
    const dir = absPath.substring(0, absPath.lastIndexOf(sep));
    const tok = getToken();
    html = html.replace(/(<img\s+[^>]*src=")([^"]+)(")/g, (m, prefix, src, suffix) => {
      // Skip remote, data, and already-resolved URLs
      if (/^(https?:|data:|\/api\/)/.test(src)) return m;
      // Resolve relative or absolute path
      let resolved;
      if (src.startsWith('/') || /^[A-Za-z]:[\\/]/.test(src)) {
        resolved = src; // already absolute
      } else {
        resolved = dir ? dir + '/' + src.replace(/^\.\//, '') : src;
      }
      return `${prefix}/api/nf-file?path=${encodeURIComponent(resolved)}&token=${encodeURIComponent(tok)}${suffix}`;
    });
    // Add onerror handler for debugging broken images
    html = html.replace(/<img\b/g, '<img onerror="this.style.opacity=0.3;this.title=\'Failed: \'+this.src"');
  }

  pane.classList.remove('scrollable');
  pane.innerHTML = `<div class="canvas-md-scroll"><div class="canvas-md-viewer">${html}</div></div>`;

  // marked v5+ dropped the `headerIds` option, so add slug ids manually
  // for TOC anchor navigation. Keeps word chars, spaces, CJK, hyphens;
  // strips other punctuation, lowercases, spaces→hyphens.
  const mdViewer = pane.querySelector('.canvas-md-viewer');
  if (mdViewer) {
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s\u4e00-\u9fff-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(h => {
      if (h.id) return;
      let slug = slugify(h.textContent);
      if (!slug) slug = 'heading';
      if (slug in slugCounts) { slugCounts[slug]++; slug = `${slug}-${slugCounts[slug]}`; }
      else slugCounts[slug] = 0;
      h.id = slug;
    });
    // Handle TOC anchor clicks — scroll within the pane, not the window.
    // marked v12 URL-encodes CJK chars in href="#..." anchors, but heading
    // IDs use raw characters. Try decoded first, fall back to raw.
    mdViewer.addEventListener('click', (e) => {
      const link = e.target.closest('a[href^="#"]');
      if (!link) return;
      const href = link.getAttribute('href');
      if (!href || href === '#') return;
      e.preventDefault();
      const raw = href.slice(1);
      let decoded;
      try { decoded = decodeURIComponent(raw); } catch { decoded = raw; }
      const target = mdViewer.querySelector(`[id="${decoded}"]`) ||
                     mdViewer.querySelector(`[id="${raw}"]`);
      if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }

  addSourceToggle(pane, viewMarkdown, { content, absPath, fileName });
}

/** YAML viewer — structured view with syntax highlighting */
function viewYaml(pane, { content, fileName }) {
  const escaped = escapeHtml(content || '');
  // Simple YAML syntax highlighting: keys, comments, strings, numbers
  const highlighted = escaped
    .split('\n')
    .map(line => {
      // Comments
      if (line.trimStart().startsWith('#'))
        return `<span style="color:var(--color-text-muted)">${line}</span>`;
      // Key: value
      const kvMatch = line.match(/^(\s*)([\w.-]+)(:)(.*)$/);
      if (kvMatch) {
        const [, indent, key, colon, rest] = kvMatch;
        const valStyled = rest.trimStart() 
          ? `<span style="color:var(--color-text)">${rest}</span>`
          : '';
        return `${indent}<span style="color:var(--color-primary)">${key}</span><span style="color:var(--color-text-muted)">${colon}</span>${valStyled}`;
      }
      // List items
      if (line.match(/^\s*-\s/))
        return `<span style="color:var(--color-text-muted)">${line}</span>`;
      return line;
    })
    .join('\n');

  pane.innerHTML = `
    <div class="canvas-yaml-viewer">
      <pre><code>${highlighted}</code></pre>
    </div>`;
  pane.classList.add('scrollable');
}

/** HTML viewer — full browser-grade rendering in sandboxed iframe.
 *  Mirrors Card's rendering pipeline: theme variables injected from parent,
 *  local file paths converted to /api/nf-file URLs, auth tokens added,
 *  audio/video preloading disabled, theme propagation for live dark mode.
 *
 *  Unlike Card (auto-height in chat), Canvas fills the panel height and
 *  scrolls internally — like a browser viewport. */
function viewHtml(pane, { content, absPath, fileName }) {
  initCanvasThemeWatcher();
  pane.innerHTML = '';

  // Directory of the HTML file, for resolving relative paths
  const dir = absPath ? absPath.substring(0, absPath.lastIndexOf('/')) : '';
  const token = getToken();

  let html = content || '<!DOCTYPE html><html><body><p style="color:#999;padding:20px">Empty HTML file</p></body></html>';

  // 1. Convert local file paths in src/href to /api/nf-file URLs
  html = resolveLocalFiles(html, dir, token);

  // 2. Add preload="none" to audio/video (prevent mass-fetch on load)
  html = html.replace(/(<audio\b(?![^>]*\bpreload=)[^>]*)(\s*\/?>)/gi, '$1 preload="none"$2');
  html = html.replace(/(<video\b(?![^>]*\bpreload=)[^>]*)(\s*\/?>)/gi, '$1 preload="none"$2');

  // 3. Build theme CSS from parent document
  const themeCSS = buildThemeVarsCSS();

  // 4. CSS overrides for graphviz SVGs: edges/labels use theme vars, not hardcoded black
  const graphvizCSS = `
  svg .edge path { stroke: var(--color-text-muted, #6b7280) !important; fill: none !important; }
  svg .edge polygon { fill: var(--color-text-muted, #6b7280) !important; stroke: none !important; }
  svg .edge text { fill: var(--color-text-muted, #6b7280) !important; }
  svg > g > polygon { fill: transparent !important; }
  `;

  // 5. Script to inline <img src="*.svg"> so CSS can override hardcoded colors
  const svgInlineScript = `<script>
  (function(){
    document.querySelectorAll('img').forEach(function(img){
      var src = img.getAttribute('src') || '';
      if (!/\\.svg(\\?|$)/i.test(src)) return;
      fetch(src).then(function(r){ return r.text(); }).then(function(svgText){
        var parser = new DOMParser();
        var doc = parser.parseFromString(svgText, 'image/svg+xml');
        var svg = doc.documentElement;
        // Strip xml declarations already handled by parser
        svg.removeAttribute('width');
        svg.setAttribute('width', '100%');
        svg.removeAttribute('height');
        svg.style.maxWidth = '100%';
        svg.style.height = 'auto';
        // Copy class/size from img
        var w = img.getAttribute('width') || img.style.width;
        var h = img.getAttribute('height') || img.style.height;
        if (w) svg.setAttribute('width', w);
        if (h) svg.style.height = h;
        img.parentNode.replaceChild(svg, img);
      }).catch(function(){ /* leave img as fallback */ });
    });
  })();
  <\/script>`;

  // 6. Assemble srcdoc with base styles (transparent bg, theme-aware, scrollable)
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}${graphvizCSS}html,body{margin:0;padding:0;font-size:15px;line-height:1.5;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg,var(--color-surface,white));color:var(--color-text,#1a1a1a);overflow:auto;}*,*:before,*:after{box-sizing:inherit;}svg{max-width:100%;height:auto;}img{max-width:100%;height:auto;}</style></head><body>${html}${svgInlineScript}${anchorNavScript}${themePropScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.style.width = '100%';
  iframe.style.height = '100%';
  iframe.style.border = 'none';
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups');
  iframe.setAttribute('scrolling', 'auto');
  iframe.dataset.nfCanvasHtml = '1';
  iframe.srcdoc = srcdoc;
  pane.appendChild(iframe);

  addSourceToggle(pane, viewHtml, { content, absPath, fileName });
}

/** Image viewer — <img> served via /api/nf-file */
function viewImage(pane, { absPath, fileName, size }) {
  if (!absPath) {
    pane.innerHTML = '<div class="canvas-error">Cannot display image: no absolute path available.</div>';
    return;
  }
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  const img = new Image();
  img.alt = fileName || 'image';
  img.onerror = () => {
    let reason = 'File may be corrupted or not a valid image format.';
    if (size && size < 500) reason += ` (File is only ${size} bytes — likely an error page or placeholder, not a real image.)`;
    pane.innerHTML = `<div class="canvas-error">${reason}<br>Path: ${escapeHtml(absPath)}</div>`;
  };
  img.onload = () => {
    pane.innerHTML = '';
    const wrap = document.createElement('div');
    wrap.className = 'canvas-image-viewer';
    const info = document.createElement('div');
    info.className = 'image-info';
    info.textContent = `${fileName || ''} — ${formatSize(size || 0)}`;
    wrap.appendChild(info);
    wrap.appendChild(img);
    pane.appendChild(wrap);
    pane.classList.add('scrollable');
  };
  img.src = url;
}

/** PDF viewer — <iframe> served via /api/nf-file */
function viewPdf(pane, { absPath, fileName }) {
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  pane.innerHTML = `
    <div class="canvas-pdf-viewer">
      <iframe src="${url}" title="${escapeHtml(fileName || 'PDF')}" style="width:100%;height:100%;border:none;"></iframe>
    </div>`;
}

/** DOCX viewer — fetch binary, convert to HTML via mammoth.js */
async function viewDocx(pane, { absPath, fileName }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading document...</div>`;
  const tok = getToken();
  if (!absPath) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for DOCX viewer.</div>`;
    return;
  }
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    const arrayBuffer = await resp.arrayBuffer();
    // Load mammoth lazily.
    // mammoth.min.js is a UMD bundle: if Monaco's AMD loader (window.define)
    // is active, the UMD wrapper registers there instead of setting window.mammoth.
    // Fetch as text and eval via new Function with define/module/exports shadowed
    // so the UMD wrapper falls through to the window global branch.
    if (typeof window.mammoth === 'undefined') {
      try {
        const scriptResp = await fetch('vendor/mammoth.min.js');
        const code = await scriptResp.text();
        // Shadow AMD/CommonJS globals so UMD sets window.mammoth
        const exec = new Function('define', 'module', 'exports', code);
        exec(undefined, undefined, undefined);
      } catch (e) {
        throw new Error('Failed to load mammoth.js: ' + e.message);
      }
      if (typeof window.mammoth === 'undefined') {
        throw new Error('mammoth.js loaded but global not initialized');
      }
    }
    const result = await window.mammoth.convertToHtml(
      { arrayBuffer },
      {
        convertImage: window.mammoth.images.imgElement(function(image) {
          return image.read("base64").then(function(imageBuffer) {
            const supported = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif',
              'image/svg+xml', 'image/webp', 'image/bmp', 'image/x-icon'];
            if (supported.includes(image.contentType)) {
              return { src: "data:" + image.contentType + ";base64," + imageBuffer };
            }
            // Unsupported format (EMF/WMF/TIFF from Windows Word)
            return {
              src: 'data:image/svg+xml;utf8,' + encodeURIComponent(
                `<svg xmlns="http://www.w3.org/2000/svg" width="200" height="60"><rect width="100%" height="100%" fill="%23f0f0f0" rx="8"/><text x="100" y="35" text-anchor="middle" font-size="12" fill="%23999">${image.contentType} — browser preview not supported</text></svg>`
              )
            };
          });
        })
      }
    );
    const html = result.value || '<p style="color:var(--color-text-muted)">Document is empty.</p>';
    pane.innerHTML = `<div class="canvas-md-viewer">${html}</div>`;
    pane.classList.add('scrollable');
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render DOCX: ${escapeHtml(err.message)}</div>`;
  }
}

/** XLSX viewer — fetch binary, parse with SheetJS, render first sheet as table */
async function viewXlsx(pane, { absPath, fileName }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading spreadsheet...</div>`;
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    const arrayBuffer = await resp.arrayBuffer();
    // Load SheetJS lazily (non-module script, attaches to window.XLSX)
    if (typeof window.XLSX === 'undefined') {
      await new Promise((resolve, reject) => {
        const s = document.createElement('script');
        s.src = 'vendor/xlsx.full.min.js';
        s.onload = resolve;
        s.onerror = () => reject(new Error('Failed to load SheetJS'));
        document.head.appendChild(s);
      });
    }
    const wb = window.XLSX.read(arrayBuffer, { type: 'array' });
    const sheetName = wb.SheetNames[0];
    if (!sheetName) {
      pane.innerHTML = `<div class="canvas-error">Spreadsheet has no sheets.</div>`;
      return;
    }
    const sheet = wb.Sheets[sheetName];
    const html = window.XLSX.utils.sheet_to_html(sheet, { editable: false });
    const sheetTabs = wb.SheetNames.map((name, i) =>
      `<button class="xlsx-sheet-tab ${i === 0 ? 'active' : ''}" data-sheet="${escapeHtml(name)}">${escapeHtml(name)}</button>`
    ).join('');
    pane.innerHTML = `
      <div class="canvas-xlsx-viewer">
        <div class="xlsx-sheet-bar">${sheetTabs}</div>
        <div class="xlsx-sheet-content">${html}</div>
      </div>`;
    pane.classList.add('scrollable');
    // Wire sheet tabs
    pane.querySelectorAll('.xlsx-sheet-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        const name = btn.dataset.sheet;
        const s2 = wb.Sheets[name];
        const html2 = window.XLSX.utils.sheet_to_html(s2, { editable: false });
        pane.querySelector('.xlsx-sheet-content').innerHTML = html2;
        pane.querySelectorAll('.xlsx-sheet-tab').forEach(b => b.classList.toggle('active', b === btn));
      });
    });
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render XLSX: ${escapeHtml(err.message)}</div>`;
  }
}

/** PPTX viewer — no reliable client-side library, show info + download link */
function viewPptx(pane, { absPath, fileName, size }) {
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  pane.innerHTML = `
    <div class="canvas-md-viewer" style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:var(--color-text-muted);">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" opacity="0.4">
        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
      </svg>
      <div style="text-align:center;">
        <div style="font-weight:600;color:var(--color-text);margin-bottom:4px">${escapeHtml(fileName || 'PowerPoint')}</div>
        <div style="font-size:12px;opacity:0.6">${formatSize(size || 0)}</div>
      </div>
      <a href="${url}" download="${escapeHtml(fileName || 'presentation.pptx')}" style="font-size:13px;color:var(--color-primary);text-decoration:none;padding:6px 16px;border:1px solid var(--color-primary);border-radius:8px;">Download to view</a>
    </div>`;
}

/** EPUB viewer — paginated reader with chapter navigation.
 *  Parses ZIP structure, extracts XHTML chapters, renders with CSS column pagination.
 *  Dispatches 'epub-page-change' events for tracking what user is reading. */
async function viewEpub(pane, { absPath, fileName, size }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading ebook...</div>`;
  const tok = getToken();
  if (!absPath) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for EPUB viewer.</div>`;
    return;
  }
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    const buffer = await resp.arrayBuffer();

    const entries = parseZipEntries(buffer);
    if (!entries['META-INF/container.xml']) throw new Error('Not a valid EPUB file');

    // Parse container.xml → OPF path
    const containerRaw = await decompressZipEntry(entries['META-INF/container.xml']);
    const containerDoc = new DOMParser().parseFromString(containerRaw, 'application/xml');
    const rootfileEl = containerDoc.querySelector('rootfile');
    if (!rootfileEl) throw new Error('EPUB: container.xml missing rootfile');
    const opfPath = rootfileEl.getAttribute('full-path');
    const opfDir = opfPath.includes('/') ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : '';

    // Parse OPF — manifest + spine
    const opfRaw = await decompressZipEntry(entries[opfPath]);
    const opfDoc = new DOMParser().parseFromString(opfRaw, 'application/xml');
    const manifest = {};
    opfDoc.querySelectorAll('manifest > item').forEach(item => {
      manifest[item.getAttribute('id')] = {
        href: item.getAttribute('href'),
        mediaType: item.getAttribute('media-type') || '',
      };
    });
    const spineIds = [];
    opfDoc.querySelectorAll('spine > itemref').forEach(itemref => {
      spineIds.push(itemref.getAttribute('idref'));
    });

    // Pre-load all chapter contents
    const chapters = [];
    for (const id of spineIds) {
      const item = manifest[id];
      if (!item) continue;
      const itemPath = (opfDir + item.href).replace(/[^\/]+\/\.\.\//g, '');
      const entry = entries[itemPath];
      if (!entry) continue;
      const html = await decompressZipEntry(entry);
      const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
      const bodyContent = bodyMatch ? bodyMatch[1] : html;
      const clean = bodyContent.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '');
      const titleMatch = clean.match(/<h[1-6][^>]*>(.*?)<\/h[1-6]>/i);
      const title = titleMatch ? titleMatch[1].replace(/<[^>]+>/g, '').trim() : `Chapter ${chapters.length + 1}`;
      chapters.push({ title, content: clean });
    }

    if (chapters.length === 0) throw new Error('EPUB has no readable chapters');

    renderEpubReader(pane, chapters, fileName || 'EPUB');
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render EPUB: ${escapeHtml(err.message)}</div>`;
  }
}

/** Render paginated EPUB reader UI — scroll-based pagination. */
function renderEpubReader(pane, chapters, bookTitle) {
  let chapterIdx = 0;

  pane.innerHTML = `
    <div class="epub-reader">
      <div class="epub-reader-header">
        <span class="epub-chapter-title"></span>
      </div>
      <div class="epub-reader-viewport">
        <div class="epub-reader-content"></div>
      </div>
      <div class="epub-reader-nav">
        <button class="epub-nav-btn epub-prev" title="Previous">‹</button>
        <span class="epub-nav-info"></span>
        <button class="epub-nav-btn epub-next" title="Next">›</button>
      </div>
    </div>`;

  const viewport = pane.querySelector('.epub-reader-viewport');
  const content = pane.querySelector('.epub-reader-content');
  const chapterTitleEl = pane.querySelector('.epub-chapter-title');
  const infoEl = pane.querySelector('.epub-nav-info');
  const prevBtn = pane.querySelector('.epub-prev');
  const nextBtn = pane.querySelector('.epub-next');

  function getTotalPages() {
    return Math.max(1, Math.ceil(viewport.scrollHeight / viewport.clientHeight));
  }

  function getCurrentPage() {
    return Math.round(viewport.scrollTop / viewport.clientHeight);
  }

  function emitTracking() {
    const total = getTotalPages();
    const page = getCurrentPage();
    pane.dispatchEvent(new CustomEvent('epub-page-change', {
      detail: {
        bookTitle,
        chapter: chapterIdx,
        chapterTitle: chapters[chapterIdx].title,
        page,
        totalPages: total,
        totalChapters: chapters.length,
        progress: chapterIdx / chapters.length + (page + 1) / total / chapters.length,
      }
    }));
  }

  function updateUI() {
    const page = getCurrentPage() + 1;
    const total = getTotalPages();
    chapterTitleEl.textContent = chapters[chapterIdx].title;
    infoEl.textContent = `Ch ${chapterIdx + 1}/${chapters.length} · Pg ${page}/${total}`;
    prevBtn.disabled = chapterIdx === 0 && viewport.scrollTop <= 0;
    nextBtn.disabled = chapterIdx === chapters.length - 1 && viewport.scrollTop >= viewport.scrollHeight - viewport.clientHeight - 1;
    emitTracking();
  }

  function loadChapter(idx, scrollPos) {
    chapterIdx = idx;
    content.innerHTML = chapters[idx].content;
    requestAnimationFrame(() => {
      viewport.scrollTop = scrollPos || 0;
      updateUI();
    });
  }

  function nextPage() {
    const bottom = viewport.scrollHeight - viewport.clientHeight;
    if (viewport.scrollTop < bottom - 1) {
      viewport.scrollTo({ top: viewport.scrollTop + viewport.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx < chapters.length - 1) {
      loadChapter(chapterIdx + 1, 0);
    }
  }

  function prevPage() {
    if (viewport.scrollTop > 1) {
      viewport.scrollTo({ top: viewport.scrollTop - viewport.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx > 0) {
      loadChapter(chapterIdx - 1, 999999); // bottom of previous chapter
    }
  }

  prevBtn.addEventListener('click', (e) => { e.stopPropagation(); prevPage(); });
  nextBtn.addEventListener('click', (e) => { e.stopPropagation(); nextPage(); });

  // Click left/right halves to navigate (but not when selecting text)
  viewport.addEventListener('click', (e) => {
    const sel = window.getSelection();
    if (sel && sel.toString().trim().length > 0) return;
    const rect = viewport.getBoundingClientRect();
    if (e.clientX < rect.left + rect.width / 2) prevPage();
    else nextPage();
  });

  // Keyboard navigation
  pane.tabIndex = 0;
  pane.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); prevPage(); }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); nextPage(); }
  });

  // Update indicator on scroll
  let scrollTimer = null;
  viewport.addEventListener('scroll', () => {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => updateUI(), 100);
  });

  // Recalculate on resize
  new ResizeObserver(() => updateUI()).observe(viewport);

  // Initial load
  loadChapter(0, 0);
}

/** Parse ZIP central directory → map of filename → { compMethod, data (Uint8Array) } */
function parseZipEntries(buffer) {
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
async function decompressZipEntry(entry) {
  if (!entry) return '';
  if (entry.compMethod === 0) return new TextDecoder().decode(entry.data);
  if (entry.compMethod === 8) {
    const ds = new DecompressionStream('deflate-raw');
    const stream = new Blob([entry.data]).stream().pipeThrough(ds);
    return await new Response(stream).text();
  }
  throw new Error('Unsupported ZIP compression: ' + entry.compMethod);
}

// ── Registry ───────────────────────────────────────────────────────────

const viewers = {
  code: viewMonaco,
  markdown: viewMarkdown,
  json: viewMonaco,
  csv: viewMonaco,
  yaml: viewYaml,
  html: viewHtml,
  image: viewImage,
  pdf: viewPdf,
  docx: viewDocx,
  xlsx: viewXlsx,
  pptx: viewPptx,
  epub: viewEpub,
};

/**
 * Render file content into a Canvas tab pane using the appropriate viewer.
 * Falls back to plain text if no viewer matches.
 * @param {HTMLElement} pane — the tab content pane
 * @param {Object} ctx — { itemType, content, absPath, fileName, size }
 */
export async function renderFile(pane, ctx) {
  const viewer = viewers[ctx.itemType] || viewers.code;
  try {
    await viewer(pane, ctx);
  } catch (err) {
    console.warn('Viewer error:', err);
    pane.innerHTML = `<div class="canvas-error">Failed to render: ${escapeHtml(err.message)}</div>`;
  }
}
