// cardRegistry.js — Universal HTML card renderer for Nebflow
// Detects ___<AGENT>_HTML___ markers and renders HTML in sandboxed iframes.
// Theme CSS variables are injected into iframes for dark mode support.

import state from './state.js';
import { smartScroll } from './utils.js';

let _iframeId = 0;

/** Inject theme CSS variables from the parent document into the iframe via srcdoc.
 *  Automatically collects all --color-* custom properties so new variables
 *  added to base.css are always available inside card iframes. */
function buildThemeVarsCSS() {
  const root = getComputedStyle(document.documentElement);
  // Collect all --color-* custom properties from :root
  const pairs = [];
  for (const sheet of document.styleSheets) {
    try {
      for (const rule of sheet.cssRules) {
        if (rule.selectorText === ':root') {
          for (const prop of rule.style) {
            if (prop.startsWith('--color-')) {
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

/** Extract just the CSS variable declarations from a theme CSS string.
 *  Input: ":root{--color-bg:#fff;--color-text:#000}"
 *  Output: "--color-bg:#fff;--color-text:#000" */
function extractThemeVars(themeCSS) {
  return themeCSS.replace(':root{', '').replace('}', '').trim();
}

/** Build the height + width reporting script to inject into srcdoc.
 *  #nf-wrap uses width:fit-content so narrow cards stay compact.
 *  No transform:scale() — it causes SVG flowchart lines to misalign due to
 *  sub-pixel rounding. Wide content is constrained by max-width:100% on
 *  img/svg/video and overflow:hidden on body.
 *  Reports content width (so parent can size the wrap) and height. */
function buildHeightScript(id) {
  return `<script>
(function(){
  var id=${id};
  function send(){
    try{
      var w=document.getElementById('nf-wrap');
      if(!w)return;
      var cw=w.offsetWidth;
      var h=document.documentElement.scrollHeight||document.body.scrollHeight;
      parent.postMessage({_nfCardW:cw,_nfCardH:h,id:id},"*");
    }catch(e){}
  }
  new ResizeObserver(send).observe(document.body);
  send();setTimeout(send,100);setTimeout(send,500);
  window.addEventListener('message',function(e){
    if(e.data&&e.data._nfThemeVars){
      var s=document.getElementById('nf-card-theme');
      if(!s){s=document.createElement('style');s.id='nf-card-theme';document.head.appendChild(s);}
      s.textContent=':root{'+e.data._nfThemeVars+'}';
      setTimeout(send,50);
    }
  });
})();
</script>`;
}

/** Track which iframes have received their first height measurement. */
const _firstHeightDone = new Set();

/** Listen for height messages from card iframes. */
window.addEventListener('message', (e) => {
  if (!e.data || !e.data._nfCardH) return;
  const iframe = document.querySelector(`iframe[data-nf-card-id="${e.data.id}"]`);
  if (iframe) {
    const isFirst = !_firstHeightDone.has(e.data.id);
    if (isFirst) {
      _firstHeightDone.add(e.data.id);
    }
    const oldHeight = iframe.style.height;
    const newHeight = e.data._nfCardH + 'px';
    // Track whether height actually changed (first measurement always counts as "changed")
    const heightChanged = !oldHeight || oldHeight !== newHeight;
    iframe.style.height = newHeight;
    // On first measurement: set card width and reveal
    if (isFirst) {
      if (e.data._nfCardW) {
        const wrap = iframe.closest('.html-card-wrap');
        if (wrap) wrap.style.width = e.data._nfCardW + 'px';
      }
      iframe.style.opacity = '1';
    }
    // If height changed, ensure scroll position still shows the card bottom.
    // Works for first measurement (oldHeight == '' → heightChanged = true) and subsequent resizes.
    if (heightChanged) {
      const chat = state.dom.chat;
      const nearBottom = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 100;
      if (nearBottom) {
        requestAnimationFrame(() => {
          chat.scrollTop = chat.scrollHeight;
          state.scrollSnapped = true;
        });
      } else {
        smartScroll();
      }
    }
  }
});

/** Read the nebflow auth token from cookie (set by ws.js on connect). */
function getNfToken() {
  const match = document.cookie.match(/(?:^|;\s*)nebflow_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/** Inject auth token into /api/nf-file URLs so the sandboxed iframe can fetch them.
 *  The iframe uses allow-same-origin, but srcdoc iframes may not send cookies
 *  reliably — token in the query string ensures the request is authenticated. */
function injectFileTokens(html) {
  const token = getNfToken();
  if (!token) return html;
  return html.replace(/(\/api\/nf-file\?path=[^"'\s]+)/g, (url) => {
    return url + '&token=' + encodeURIComponent(token);
  });
}

/** Render HTML content inside a sandboxed iframe.
 *  Creates the iframe immediately (no lazy loading) — the browser handles srcdoc
 *  parsing asynchronously, so this is safe even with many cards. */
function renderHtmlCard(container, html, title) {
  container.innerHTML = '';

  const wrap = document.createElement('div');
  wrap.className = 'html-card-wrap';

  const id = ++_iframeId;
  const themeCSS = buildThemeVarsCSS();
  const heightScript = buildHeightScript(id);

  // Inject auth tokens into /api/nf-file URLs (sandboxed iframe can't use cookies)
  const processedHtml = injectFileTokens(html);

  // #nf-wrap: width:100% fills the full chat width so content is never tiny.
  // SVG defaults to width:100%; min font-size 14px prevents unreadable text.
  // No transform:scale() — it causes SVG flowchart lines to misalign.
  // img default: width:100% ensures plots/photos fill the card; images with explicit
  // inline width/height are respected via [style] attribute selector. max-width prevents
  // any image from overflowing.
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}html,body{margin:0;padding:0;width:100%;font-size:15px;line-height:1.5;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg);color:var(--color-text);overflow:hidden;}*,*:before,*:after{box-sizing:inherit;}svg{width:100%;height:auto;}svg text{font-size:min(max(14px,100%),5vw);}img{max-width:100%;height:auto;}img:not([style*="width"]){width:100%;}</style></head><body><div id="nf-wrap" style="width:100%">${processedHtml}</div>${heightScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.className = 'html-card-iframe';
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
  iframe.setAttribute('scrolling', 'no');
  iframe.setAttribute('srcdoc', srcdoc);
  iframe.dataset.nfCardId = id;
  wrap.appendChild(iframe);

  container.appendChild(wrap);
}

/**
 * Try to render a tool card using the universal HTML renderer.
 * Accepts:
 *  - A string with ___<AGENT>_HTML___ or ___<AGENT>_JSON___ markers (legacy)
 *  - An object {html, title} for direct rendering (preferred)
 *  - An object with .content string containing markers (from persistence)
 */
export function renderWithRegistry(container, text, toolName) {
  // Direct {html, title} object — used by Card tool (no marker needed)
  if (text && typeof text === 'object' && !Array.isArray(text) && text.html) {
    renderHtmlCard(container, text.html, text.title || '');
    return true;
  }

  // Extract string from wrapped object (persistence format: {content, summary, ...})
  if (text && typeof text === 'object' && !Array.isArray(text)) {
    text = text.content || text.summary || '';
  }
  if (typeof text !== 'string') text = '';

  const htmlMatch = text && text.match(/^___\w+_HTML___/);
  if (htmlMatch) {
    try {
      const json = text.substring(htmlMatch[0].length);
      const data = JSON.parse(json);
      if (data.html) {
        renderHtmlCard(container, data.html, data.title || '');
        return true;
      }
    } catch (e) {}
  }

  // Legacy ___<AGENT>_JSON___ marker (backward compat)
  const jsonMatch = text && text.match(/^___\w+_JSON___/);
  if (jsonMatch) {
    try {
      const json = text.substring(jsonMatch[0].length);
      const data = JSON.parse(json);
      const inner = data.data || data;
      if (inner.html) {
        renderHtmlCard(container, inner.html, inner.title || '');
        return true;
      }
    } catch (e) {}
  }

  return false;
}

/** No-op for backward compatibility. */
export function registerCardRenderer() {}

/** No-op for backward compatibility. */
export function clearRenderers() {}

/**
 * Watch for system theme changes (light/dark) and propagate the new CSS
 * custom properties to all rendered card iframes so they update live.
 */
(function initThemeWatcher() {
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const handler = () => {
    const css = buildThemeVarsCSS();
    if (!css) return;
    const vars = extractThemeVars(css);
    document.querySelectorAll('iframe[data-nf-card-id]').forEach(iframe => {
      try {
        if (iframe.contentWindow) {
          iframe.contentWindow.postMessage({ _nfThemeVars: vars }, '*');
        }
      } catch (e) { /* cross-origin iframe, skip */ }
    });
  };
  mq.addEventListener('change', handler);
})();
