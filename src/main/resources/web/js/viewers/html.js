// viewers/html.js — HTML viewer (sandboxed iframe, full browser-grade rendering).
// Migrated verbatim from fileViewers.js (viewHtml + its private iframe scripts).

import { getToken, buildThemeVarsCSS, resolveLocalFiles, addSourceToggle } from './shared.js';

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
    parent.postMessage({ _nfImagePreview: { src: img.currentSrc || img.src, alt: img.alt || '' } }, window.location.origin);
  }, true);
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
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}${graphvizCSS}html,body{margin:0;padding:0;font-size:15px;line-height:1.5;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg,var(--color-surface,white));color:var(--color-text,#1a1a1a);overflow:auto;}*,*:before,*:after{box-sizing:inherit;}svg{max-width:100%;height:auto;}img{max-width:100%;height:auto;}</style></head><body>${html}${svgInlineScript}${imgClickScript}${anchorNavScript}${themePropScript}</body></html>`;

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

export default {
  name: 'html',
  label: 'HTML',
  extensions: ['.html', '.htm'],
  binary: false,
  priority: 0,
  render: viewHtml,
};
