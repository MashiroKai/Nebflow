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

/** Build the auto-scale + height-reporting script to inject into srcdoc.
 *  #nf-wrap uses width:fit-content so the card shrinks to content width.
 *  Reports both width and height; parent adjusts accordingly.
 *  Also listens for _nfThemeVars messages from parent to live-update theme
 *  when the system switches between light and dark mode. */
function buildHeightScript(id) {
  return `<script>
(function(){
  var id=${id};
  function send(){
    try{
      var d=document.documentElement,b=document.body,w=document.getElementById('nf-wrap');
      if(!w)return;
      var vw=d.clientWidth;
      var cw=w.scrollWidth;
      var s=cw>vw?vw/cw:1;
      w.style.transform=s<1?'scale('+s+')':'';
      var reportW=s<1?vw:w.offsetWidth;
      // body.scrollHeight includes all content + margins (getBoundingClientRect misses child margins)
      var reportH=Math.ceil(document.body.scrollHeight*s);
      parent.postMessage({_nfCardW:reportW,_nfCardH:reportH,id:id},"*");
    }catch(e){}
  }
  new ResizeObserver(send).observe(document.body);
  send();setTimeout(send,100);setTimeout(send,500);
  // Listen for live theme updates from parent window
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
    iframe.style.height = newHeight;
    // On first measurement: set card width and reveal
    if (isFirst) {
      if (e.data._nfCardW) {
        const wrap = iframe.closest('.html-card-wrap');
        if (wrap) wrap.style.width = e.data._nfCardW + 'px';
      }
      iframe.style.opacity = '1';
    }
    // If the card grew taller, force scroll to bottom if we were near the bottom.
    if (oldHeight && oldHeight !== newHeight) {
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
    } else {
      smartScroll();
    }
  }
});

/** Render HTML content inside a sandboxed iframe. */
function renderHtmlCard(container, html, title) {
  container.innerHTML = '';

  const wrap = document.createElement('div');
  wrap.className = 'html-card-wrap';

  const id = ++_iframeId;
  const themeCSS = buildThemeVarsCSS();
  const heightScript = buildHeightScript(id);

  // Wrap user HTML in #nf-wrap for auto-scaling.
  // The wrapper carries transform-origin so transform:scale() can shrink content
  // to fit the iframe viewport when it's wider than available space.
  // Note: do NOT put overflow:hidden on body — it breaks flexbox min-width:auto.
  // scrolling="no" on iframe prevents scrollbars; iframe clips beyond its viewport.
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}html,body{margin:0;padding:0;width:100%;font-size:13px;line-height:1.45;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg);color:var(--color-text);}*,*:before,*:after{box-sizing:inherit;}img,svg,video{max-width:100%;height:auto;}</style></head><body><div id="nf-wrap" style="transform-origin:top left;width:fit-content;max-width:100%">${html}</div>${heightScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.className = 'html-card-iframe';
  iframe.setAttribute('sandbox', 'allow-scripts');
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
