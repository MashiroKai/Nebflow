// viewers/html.js — HTML viewer (sandboxed iframe, full browser-grade rendering).
// Migrated verbatim from fileViewers.js (viewHtml + its private iframe scripts).
// Zoom (2026-09-04): the whole iframe is transformed translate+scale via the
// shared viewers/zoom.js engine (glass toolbar, ⌘±/0, clamped 0.3–3.0);
// ctrl/cmd+wheel and ⌘/ctrl key presses over the iframe reach the parent
// through zoomBridgeScript postMessage (cross-frame events don't bubble).

import { getToken, buildThemeVarsCSS, resolveLocalFiles, addSourceToggle, addElementRefToggle } from './shared.js';
import { makeReference } from '../reference.js';
import { t } from '../i18n.js';
import { enableViewerZoom } from './zoom.js';

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

/** Script injected into the iframe to forward image clicks to the parent
 *  for lightbox preview. Uses the _nfImagePreview message prefix. */
const imgClickScript = `<script>
(function(){
  document.addEventListener('click', function(e){
    var img = e.target.closest ? e.target.closest('img') : null;
    if (!img) return;
    e.preventDefault();
    parent.postMessage({ _nfImagePreview: { src: img.currentSrc || img.src, alt: img.alt || '' } }, '*');
  }, true);
})();
<\/script>`;

/** #303 B6: dormant element-select script, embedded in the srcdoc at assembly
 *  (same pattern as imgClickScript/anchorNavScript). Sleeps until the parent
 *  posts `_nfRefMode {on:true}`; then hover-highlight + click-to-pick +
 *  Esc-to-exit, reporting the pick back via `_nfRefPick`. targetOrigin is '*'
 *  (sandbox srcdoc origin is opaque "null" - a literal origin throws). */
const refSelectScript = `<script>
(function(){
  var active=false, styleEl=null, hoverEl=null, clearTimer=null, DOC=document;
  var RM = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  var CONFIRM_MS = RM ? 10 : 200;
  function ensureStyle(){
    if(styleEl) return;
    styleEl=DOC.createElement('style');
    styleEl.id='nf-ref-select-style';
    DOC.head.appendChild(styleEl);
  }
  function applyStyle(){
    ensureStyle();
    styleEl.textContent=
      'body.nf-ref-selecting,body.nf-ref-selecting *{cursor:crosshair !important;}'+
      '[data-nf-ref-hover]{outline:2px solid var(--color-primary,#07c160) !important;outline-offset:1px !important;background:color-mix(in srgb,var(--color-primary,#07c160) 8%,transparent) !important;transition:outline-width .14s ease,background-color .14s ease;}'+
      '[data-nf-ref-picked]{outline:3px solid var(--color-primary,#07c160) !important;outline-offset:1px !important;background:color-mix(in srgb,var(--color-primary,#07c160) 8%,transparent) !important;transition:outline-width .14s ease,background-color .14s ease;}';
  }
  function clearStyle(){ if(styleEl){ styleEl.remove(); styleEl=null; } }
  function clearHover(){ if(hoverEl){ hoverEl.removeAttribute('data-nf-ref-hover'); hoverEl=null; } }
  function escId(s){ return (window.CSS&&CSS.escape)?CSS.escape(s):s; }
  function nthOfType(el){
    var p=el.parentElement; if(!p) return el.tagName.toLowerCase();
    var sibs=Array.prototype.filter.call(p.children,function(c){return c.tagName===el.tagName;});
    return el.tagName.toLowerCase()+':nth-of-type('+(sibs.indexOf(el)+1)+')';
  }
  function buildSelector(el){
    if(el.id) return '#'+escId(el.id);
    var parts=[],cur=el,depth=0;
    while(cur && cur!==DOC.body && depth<8){
      parts.unshift(nthOfType(cur));
      var sel='body > '+parts.join(' > ');
      try{ if(DOC.querySelectorAll(sel).length===1) return sel; }catch(e){}
      cur=cur.parentElement; depth++;
    }
    var full=[],c=el;
    while(c && c!==DOC.documentElement){
      if(c===DOC.body){ full.unshift('body'); break; }
      full.unshift(nthOfType(c)); c=c.parentElement;
    }
    return full.join(' > ');
  }
  function extractText(el){
    var tag=el.tagName.toLowerCase();
    if(tag==='img'||tag==='canvas'||tag==='svg'||tag==='iframe'||tag==='video'||tag==='audio'||tag==='picture'){
      return el.getAttribute('alt')||el.getAttribute('aria-label')||el.getAttribute('title')||'';
    }
    return (el.innerText||'').replace(/\\s+/g,' ').trim().slice(0,4096);
  }
  function onMove(e){
    var t=e.target;
    if(t===hoverEl) return;
    clearHover();
    if(t && t.nodeType===1){ hoverEl=t; t.setAttribute('data-nf-ref-hover',''); }
  }
  function onClick(e){
    e.preventDefault(); e.stopPropagation();
    // Win over the other document-level capture listeners (img lightbox,
    // anchor scroll) registered by sibling scripts - all page interaction is
    // suspended while select mode is active.
    if(e.stopImmediatePropagation) e.stopImmediatePropagation();
    var el=e.target;
    if(!el || el.nodeType!==1) el=DOC.body;
    clearHover();
    el.setAttribute('data-nf-ref-picked','');
    var picked=el;
    setTimeout(function(){
      var r=picked.getBoundingClientRect();
      parent.postMessage({_nfRefPick:{
        selector:buildSelector(picked),
        tag:picked.tagName.toLowerCase(),
        text:extractText(picked),
        rect:{width:Math.round(r.width),height:Math.round(r.height)}
      }},'*');
      deactivate(false);
    },CONFIRM_MS);
  }
  function onKey(e){ if(e.key==='Escape') deactivate(true); }
  function activate(){
    if(active) return; active=true;
    if(clearTimer){ clearTimeout(clearTimer); clearTimer=null; }
    applyStyle();
    if(DOC.body) DOC.body.classList.add('nf-ref-selecting');
    DOC.addEventListener('mousemove',onMove,true);
    DOC.addEventListener('click',onClick,true);
    DOC.addEventListener('keydown',onKey,true);
  }
  function deactivate(notify){
    if(!active) return; active=false;
    DOC.removeEventListener('mousemove',onMove,true);
    DOC.removeEventListener('click',onClick,true);
    DOC.removeEventListener('keydown',onKey,true);
    clearHover();
    if(DOC.body) DOC.body.classList.remove('nf-ref-selecting');
    var pe=DOC.querySelector('[data-nf-ref-picked]'); if(pe) pe.removeAttribute('data-nf-ref-picked');
    clearTimer=setTimeout(clearStyle,180);
    if(notify) parent.postMessage({_nfRefExit:true},'*');
  }
  window.addEventListener('message',function(e){
    var d=e.data;
    if(d&&d._nfRefMode){ if(d._nfRefMode.on) activate(); else deactivate(false); }
  });
})();
<\/script>`;

/** Zoom bridge, injected into the srcdoc (same dormant-script pattern as the
 *  sibling scripts above): forwards ctrl/cmd+wheel — preventDefault, that
 *  combo is the viewer's zoom, plain wheel is left to the page's own
 *  scrolling — and ⌘/ctrl +/−/0 key presses to the parent as
 *  _nfZoomWheel/_nfZoomKey. Coordinates are iframe-document CSS px; the
 *  parent maps them through the current transform (zoom-to-cursor). */
const zoomBridgeScript = `<script>
(function(){
  function post(msg){ try{ parent.postMessage(msg,'*'); }catch(_){} }
  document.addEventListener('wheel',function(e){
    if(!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    post({_nfZoomWheel:{deltaY:e.deltaY,x:e.clientX,y:e.clientY}});
  },{passive:false});
  window.addEventListener('keydown',function(e){
    if(!e.ctrlKey && !e.metaKey) return;
    if(e.key!=='='&&e.key!=='+'&&e.key!=='-'&&e.key!=='0') return;
    e.preventDefault();
    post({_nfZoomKey:{key:e.key}});
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

// ── #303 B6: element-select mode state machine (tab-level, single active) ──
// One tab is in select mode at a time (mutual exclusion). The iframe-side
// dormant script does hover/pick; this parent side owns the toggle button,
// the _nfRefMode/_nfRefPick/_nfRefExit postMessage channel (payload-validated
// V1-V4), Esc, and tab switch/close cleanup.
/** @type {{ iframe: HTMLIFrameElement|null, pane: HTMLElement|null, btn: HTMLButtonElement|null, source: {url?:string,title?:string,path?:string}|null }} */
const htmlRefSel = { iframe: null, pane: null, btn: null, source: null };
let _refGlobalBound = false;

function bindRefGlobalListeners() {
  if (_refGlobalBound) return;
  _refGlobalBound = true;
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (d && d._nfRefPick) {
      // V1: accept only from the iframe currently in select mode.
      if (!htmlRefSel.iframe || e.source !== htmlRefSel.iframe.contentWindow) return;
      handleRefPick(d._nfRefPick);
    } else if (d && d._nfRefExit) {
      if (htmlRefSel.iframe && e.source === htmlRefSel.iframe.contentWindow) exitSelectMode();
    }
  });
  // Esc at the parent level also exits select mode (capture so it wins).
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && htmlRefSel.iframe) { e.stopPropagation(); exitSelectMode(); }
  }, true);
  // Tab switch / close -> clean S1->S0 exit.
  document.addEventListener('canvas-tab-switched', () => {
    if (htmlRefSel.pane && !htmlRefSel.pane.classList.contains('active')) exitSelectMode();
  });
  document.addEventListener('canvas-tab-closed', () => {
    if (htmlRefSel.pane && !htmlRefSel.pane.isConnected) exitSelectMode();
  });
}

function enterSelectMode(pane, iframe, source, btn) {
  exitSelectMode();  // mutual exclusion - exit any prior select mode first
  htmlRefSel.iframe = iframe;
  htmlRefSel.pane = pane;
  htmlRefSel.source = source;
  htmlRefSel.btn = btn;
  try { iframe.contentWindow.postMessage({ _nfRefMode: { on: true } }, '*'); } catch (_) { /* dead frame */ }
}

function exitSelectMode() {
  const iframe = htmlRefSel.iframe;
  if (iframe) {
    try { iframe.contentWindow.postMessage({ _nfRefMode: { on: false } }, '*'); } catch (_) { /* dead frame */ }
  }
  if (htmlRefSel.btn) {
    htmlRefSel.btn.classList.remove('active');
    htmlRefSel.btn.setAttribute('aria-pressed', 'false');
    htmlRefSel.btn.title = t('ref.selectElement');
  }
  htmlRefSel.iframe = null;
  htmlRefSel.pane = null;
  htmlRefSel.btn = null;
  htmlRefSel.source = null;
}

function handleRefPick(p) {
  const iframe = htmlRefSel.iframe;
  // V2: selector must be a string, <=512 chars, only legal selector chars.
  const sel = p && p.selector;
  if (typeof sel !== 'string' || sel.length === 0 || sel.length > 512) { console.warn('[nf-ref] pick dropped: bad selector'); return; }
  if (!/^[A-Za-z0-9_#.\s>:()+~=[\]'",*-]+$/.test(sel)) { console.warn('[nf-ref] pick dropped: illegal selector chars'); return; }
  // V3: text truncated to 4KB. tag normalized. (V4: any failure above drops silently.)
  const text = typeof p.text === 'string' ? p.text.slice(0, 4096) : '';
  const tag = typeof p.tag === 'string' ? p.tag.toLowerCase() : '';
  // Uniqueness re-check (B6-A4) against the live iframe DOM (srcdoc same-origin).
  try {
    const doc = iframe && iframe.contentDocument;
    if (doc && doc.querySelectorAll(sel).length !== 1) { console.warn('[nf-ref] selector not unique at pick time'); }
  } catch (_) { /* cross-origin guard */ }
  const src = htmlRefSel.source || {};
  const ref = makeReference({
    refType: 'html-element',
    source: { kind: 'web', url: src.url || '', title: src.title || '', path: src.path || '' },
    anchor: { kind: 'element', selector: sel, text, tag, rect: p.rect },
  });
  exitSelectMode();
  if (!ref) return;
  // Dynamic import to avoid an html.js -> input.js static edge (cycle safety,
  // same as canvas.js showCanvasRefMenu).
  import('../input.js').then(({ appendRefToActiveView }) => appendRefToActiveView(ref));
}

/** Wire the element-select toggle onto an HTML viewer pane (srcdoc channel). */
function setupElementRefSelect(pane, iframe, ctx) {
  bindRefGlobalListeners();
  const source = {
    path: ctx.absPath || '',
    title: ctx.fileName || (ctx.absPath ? ctx.absPath.split('/').pop() : ''),
    url: '',
  };
  const btn = addElementRefToggle(pane, {
    mode: 'available',
    onToggle: (active) => {
      if (active) enterSelectMode(pane, iframe, source, btn);
      else exitSelectMode();
    },
  });
}

// ── Zoom bridge (parent side of zoomBridgeScript) ──────────────────────────
// iframe-internal wheel/keydown never bubbles to the parent document, so the
// in-frame bridge forwards ctrl/cmd+wheel and ⌘/ctrl +/−/0 via postMessage.
// One parent listener total; the sender is verified against every live HTML
// viewer iframe (same discipline as the _nfRefPick channel), then the hit
// pane's zoom controls (stashed by viewHtml on the pane) perform the zoom.
let _zoomBridgeBound = false;
function bindZoomBridge() {
  if (_zoomBridgeBound) return;
  _zoomBridgeBound = true;
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (!d || (!d._nfZoomWheel && !d._nfZoomKey)) return;
    const frames = document.querySelectorAll('iframe[data-nf-canvas-html]');
    for (const iframe of frames) {
      if (!iframe.contentWindow || iframe.contentWindow !== e.source) continue;
      const pane = iframe.closest('.canvas-tab-pane');
      const ctl = pane && /** @type {any} */ (pane)._nfHtmlZoom;
      if (!ctl) break;
      const w = d._nfZoomWheel;
      if (w) {
        if (typeof w.deltaY === 'number' && isFinite(w.x) && isFinite(w.y)) {
          const p = ctl.panePoint(w.x, w.y);
          ctl.zoomBy(Math.pow(1.0015, -w.deltaY), p);
        }
      } else if (d._nfZoomKey) {
        const k = d._nfZoomKey.key;
        if (k === '0') ctl.reset();
        else if (k === '-') ctl.zoomBy(1 / 1.25, null);
        else if (k === '=' || k === '+') ctl.zoomBy(1.25, null);
      }
      break;
    }
  });
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
  bindZoomBridge();

  // Content-unchanged guard (mirrors markdown.js viewMarkdown). Focus/visibility
  // refreshes re-fetch the same file and re-render every open tab; rebuilding
  // the srcdoc iframe resets its internal scrollTop and throws the reader back
  // to the top (bug: Canvas HTML reading position lost after image preview /
  // on app switch). Same "unchanged never remounts" rule as the Monaco path in
  // canvas.js openWorkspaceItem. The guard also requires a live iframe: a
  // source-mode round trip wipes innerHTML before calling back into this
  // function, so iframe is null there and the re-render must proceed.
  const prevIframe = pane.querySelector('iframe[data-nf-canvas-html]');
  if (prevIframe && /** @type {any} */ (pane)._renderedHtmlContent === content) return;

  // Capture before rebuild: when content actually changed (external edit),
  // restore the reader's position afterwards so the refresh at worst drifts
  // but never jumps to the top. The iframe scrolls its OWN document (the
  // srcdoc html/body has overflow:auto) — read both axes from contentDocument
  // and re-apply them on the new frame's load below.
  let prevScrollTop = 0;
  let prevScrollLeft = 0;
  if (prevIframe) {
    try {
      const pd = prevIframe.contentDocument;
      if (pd) {
        prevScrollTop = pd.documentElement.scrollTop || pd.body.scrollTop || 0;
        prevScrollLeft = pd.documentElement.scrollLeft || pd.body.scrollLeft || 0;
      }
    } catch (_) { /* cross-origin guard */ }
  }

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
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}${graphvizCSS}html,body{margin:0;padding:0;font-size:15px;line-height:1.5;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg,var(--color-surface,white));color:var(--color-text,#1a1a1a);overflow:auto;}*,*:before,*:after{box-sizing:inherit;}svg{max-width:100%;height:auto;}img{max-width:100%;height:auto;}</style></head><body>${html}${refSelectScript}${zoomBridgeScript}${svgInlineScript}${imgClickScript}${anchorNavScript}${themePropScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.style.width = '100%';
  iframe.style.height = '100%';
  iframe.style.border = 'none';
  iframe.style.transformOrigin = '0 0';  // zoom math assumes top-left origin (default 50% 50% would offset by (1−s)·center)
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups');
  iframe.setAttribute('scrolling', 'auto');
  iframe.dataset.nfCanvasHtml = '1';
  iframe.srcdoc = srcdoc;
  pane.appendChild(iframe);

  // ── Zoom (shared engine; transform the whole iframe, never its DOM) ───────
  // translate+scale with origin 0 0 (same math as the image viewer): the inner
  // document keeps its own layout and native scrolling; zoom pivots are
  // pane-px points and keep the content under the cursor stationary.
  /** @type {any} */ (pane)._nfZoomDestroy?.();  // source-mode round trip: the pane element persists — drop the previous engine's pane listeners first
  const ZOOM_MIN = 0.3;
  const ZOOM_MAX = 3.0;
  let zs = 1, zx = 0, zy = 0;   // scale + translate (pane px)
  let zoomApi = null;
  const applyZoom = () => { iframe.style.transform = `translate(${zx}px, ${zy}px) scale(${zs})`; };
  const zoomApply = (next, pivot) => {
    const clamped = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, next));
    if (clamped === zs) return;
    const old = zs;
    zs = clamped;
    if (pivot) {
      // Zoom-to-cursor: keep the content point at the pivot stationary.
      const k = zs / old;
      zx = pivot.x - (pivot.x - zx) * k;
      zy = pivot.y - (pivot.y - zy) * k;
    }
    applyZoom();
    if (zoomApi) zoomApi.setPct(zs);
  };
  const zoomReset = () => { zs = 1; zx = 0; zy = 0; applyZoom(); if (zoomApi) zoomApi.setPct(zs); };
  zoomApi = enableViewerZoom(pane, {
    min: ZOOM_MIN, max: ZOOM_MAX,
    getScale: () => zs,
    applyScale: zoomApply,
    reset: zoomReset,
  });
  /** @type {any} */ (pane)._nfZoomDestroy = zoomApi.destroy;
  // Bridge-facing controls: panePoint maps iframe-document CSS px (as posted
  // by zoomBridgeScript) into pane px through the current transform.
  /** @type {any} */ (pane)._nfHtmlZoom = {
    panePoint: (x, y) => ({ x: x * zs + zx, y: y * zs + zy }),
    zoomBy: (factor, pivot) => zoomApply(zs * factor, pivot),
    reset: zoomReset,
  };

  // Restore the reader's position after a content-changed rebuild. The srcdoc
  // loads asynchronously — the scroll only exists once the new document has
  // loaded, so restore on load. Attached before the navigation-away check so a
  // legitimate preview keeps its position; a frame that navigated to the app
  // URL is replaced by the notice below regardless.
  if (prevScrollTop > 0 || prevScrollLeft > 0) {
    iframe.addEventListener('load', () => {
      try {
        const nd = iframe.contentDocument;
        if (!nd) return;
        const de = nd.documentElement;
        if (prevScrollTop > 0) { de.scrollTop = prevScrollTop; if (nd.body) nd.body.scrollTop = prevScrollTop; }
        if (prevScrollLeft > 0) { de.scrollLeft = prevScrollLeft; if (nd.body) nd.body.scrollLeft = prevScrollLeft; }
      } catch (_) { /* cross-origin guard */ }
    }, { once: true });
  }

  // Navigation-away fallback: srcdoc documents inherit the app's base URL, so
  // a client-side router (slidev etc.) may navigate the frame onto the app
  // itself — the embedded-boot guard in index.html stops the recursion, and
  // this replaces the pane with a clear notice instead of a nested app shell.
  iframe.addEventListener('load', () => {
    let href = null;
    try { href = iframe.contentWindow?.location?.href; } catch { /* cross-origin */ }
    if (!href || href === 'about:srcdoc') return;
    /** @type {any} */ (pane)._nfZoomDestroy?.();  // frame navigated away — retire the zoom engine with it
    pane.innerHTML = '';
    const note = document.createElement('div');
    note.style.cssText = 'padding:32px;text-align:center;color:var(--color-text-muted);font-size:13px;line-height:1.6';
    note.textContent = 'This HTML page navigated its preview frame to the application URL (client-side router). Rendering was stopped to prevent recursive nesting.';
    pane.appendChild(note);
  });

  addSourceToggle(pane, viewHtml, { content, absPath, fileName });
  // #303 B6: element-select toggle, seated left of the source toggle.
  setupElementRefSelect(pane, iframe, { absPath, fileName });
  // Cache the rendered content for the unchanged-guard at the top. Set after
  // the pane is fully assembled; the guard also requires a live iframe, so a
  // stale marker after a navigation-away replacement is harmless (no iframe →
  // the re-render proceeds).
  /** @type {any} */ (pane)._renderedHtmlContent = content;
}

export default {
  name: 'html',
  label: 'HTML',
  extensions: ['.html', '.htm'],
  binary: false,
  priority: 0,
  render: viewHtml,
};
