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
 *  Reports content width (so parent can size the wrap) and height.
 *  Width is only reported after all images finish loading — otherwise the
 *  first measurement arrives before images load, reports a tiny width,
 *  the parent shrinks the wrap, and the feedback loop deadlocks the card
 *  at that tiny size. */
function buildHeightScript(id) {
  return `<script>
(function(){
  var id=${id};
  function allImagesLoaded(){
    var imgs=document.getElementById('nf-wrap');
    if(!imgs)return true;
    imgs=imgs.querySelectorAll('img');
    for(var i=0;i<imgs.length;i++){
      if(!imgs[i].complete)return false;
    }
    return true;
  }
  function send(){
    try{
      var w=document.getElementById('nf-wrap');
      if(!w)return;
      var h=document.documentElement.scrollHeight||document.body.scrollHeight;
      var cw=null;
      if(allImagesLoaded()){
        cw=w.offsetWidth;
      }
      parent.postMessage({_nfCardW:cw,_nfCardH:h,id:id},"*");
    }catch(e){}
  }
  new ResizeObserver(send).observe(document.body);
  send();setTimeout(send,100);setTimeout(send,500);setTimeout(send,2000);
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
    // Sync wrap width: grow-only to avoid fit-content feedback loop.
    // This handles late-loading images that expand after first measurement.
    if (e.data._nfCardW) {
      const wrap = iframe.closest('.html-card-wrap');
      if (wrap) {
        const currentWrapW = parseInt(wrap.style.width) || 0;
        if (e.data._nfCardW > currentWrapW) {
          wrap.style.width = e.data._nfCardW + 'px';
        }
      }
    }
    // On first measurement: reveal
    if (isFirst) {
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

// ===== Card interaction: accumulate + submit =====
const _cardAccumulator = {};

/**
 * Listen for card interaction messages.
 * Protocol:
 *   { _nfCardAction: 'accumulate', data: {...} }  — store data, no LLM trigger
 *   { _nfCardAction: 'submit', data: {...} }      — send accumulated data to LLM
 * Cards can send accumulate on each user action (select, click, etc.),
 * then submit when the user clicks a confirm button.
 */
window.addEventListener('message', (e) => {
  if (!e.data || !e.data._nfCardAction) return;
  const action = e.data._nfCardAction;
  const data = e.data.data || {};

  if (action === 'accumulate') {
    // Merge into accumulator (per card iframe origin)
    const key = e.origin;
    if (!_cardAccumulator[key]) _cardAccumulator[key] = {};
    Object.assign(_cardAccumulator[key], data);
  } else if (action === 'submit') {
    // Merge any accumulated data with the submit payload
    const key = e.origin;
    const accumulated = _cardAccumulator[key] || {};
    const merged = { ...accumulated, ...data };
    delete _cardAccumulator[key];

    // Send to LLM as injected user message
    const text = `[Card Interaction] ${JSON.stringify(merged)}`;
    import('./input.js').then(({ injectUserMessage }) => {
      injectUserMessage(text, { silent: false });
    });
  }
});
/** Read the nebflow auth token from localStorage (set by ws.js on connect). */
function getNfToken() {
  return localStorage.getItem('nebflow_token') || '';
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
 *  Uses lazy loading: the iframe's srcdoc is not set until it scrolls near the
 *  viewport (IntersectionObserver). This prevents dozens of iframe browsing
 *  contexts — each with its own DOM, JS engine, and network requests — from
 *  being created simultaneously when a session with many cards is opened.
 *  Additionally, <audio>/<video> elements get preload="none" so the browser
 *  never auto-fetches media files; the user must click play. */
function renderHtmlCard(container, html, title) {
  container.innerHTML = '';

  const wrap = document.createElement('div');
  wrap.className = 'html-card-wrap';

  const id = ++_iframeId;
  const themeCSS = buildThemeVarsCSS();
  const heightScript = buildHeightScript(id);

  // Inject auth tokens into /api/nf-file URLs (sandboxed iframe can't use cookies)
  let processedHtml = injectFileTokens(html);

  // Fix width:100% on img/svg — fit-content + width:100% deadlocks (collapses to zero).
  // For img: strip width:100%, let natural size + max-width:100% handle overflow.
  // For svg: replace width:100% with viewBox width (3rd number in "minX minY width height").
  processedHtml = processedHtml.replace(/(<img\b[^>]*\bstyle=["'][^"']*)width:\s*100%\s*;?/gi, '$1');
  processedHtml = processedHtml.replace(/<svg\b[^>]*viewBox=["']([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)["'][^>]*\bstyle=["']([^"']*)width:\s*100%\s*;?([^"']*["'])/gi, (match, minX, minY, vbW, vbH, styleBefore, styleAfter) => {
    return match.replace(/width:\s*100%\s*;?/i, `width:${vbW}px;`);
  });
  // SVGs without viewBox: strip width:100%
  processedHtml = processedHtml.replace(/(<svg\b(?![^>]*viewBox=)[^>]*\bstyle=["'][^"']*)width:\s*100%\s*;?([^"']*["'])/gi, '$1$2');

  // Add preload="none" to <audio>/<video> elements that don't already have it.
  // Without this, a session with 200+ audio elements causes the browser to
  // simultaneously fetch and decode all files on load, freezing the page.
  processedHtml = processedHtml.replace(/(<audio\b(?![^>]*\bpreload=)[^>]*)(\s*\/?>)/gi, '$1 preload="none"$2');
  processedHtml = processedHtml.replace(/(<video\b(?![^>]*\bpreload=)[^>]*)(\s*\/?>)/gi, '$1 preload="none"$2');

  // #nf-wrap: fit-content shrinks the bubble to match content size.
  // max-width:100% prevents overflow.
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}html,body{margin:0;padding:0;font-size:15px;line-height:1.5;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg);color:var(--color-text);overflow:hidden;}*,*:before,*:after{box-sizing:inherit;}svg{max-width:100%;height:auto;}svg text{font-size:min(max(14px,100%),5vw);}img{max-width:100%;height:auto;}</style></head><body><div id="nf-wrap" style="width:fit-content;max-width:100%">${processedHtml}</div>${heightScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.className = 'html-card-iframe';
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
  iframe.setAttribute('scrolling', 'no');
  iframe.dataset.nfCardId = id;
  wrap.appendChild(iframe);

  container.appendChild(wrap);

  // Lazy loading: defer srcdoc until the iframe is near the viewport.
  // This prevents all card iframes in a session from being created and
  // parsed at once — only visible (+ margin) cards are instantiated.
  let srcdocSet = false;
  const applySrcdoc = () => {
    if (srcdocSet) return;
    srcdocSet = true;
    iframe.setAttribute('srcdoc', srcdoc);
    // Fallback: force iframe visible after 800ms even if the height postMessage
    // hasn't arrived yet. During active streaming the browser event loop may be
    // busy processing WebSocket messages, delaying postMessage handling and
    // leaving the card stuck at opacity:0 (blank bubble).
    setTimeout(() => {
      if (iframe.style.opacity !== '1') {
        iframe.style.opacity = '1';
        iframe.style.minHeight = '20px';
      }
    }, 800);
  };

  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      if (entries.some(e => e.isIntersecting)) {
        applySrcdoc();
        io.disconnect();
      }
    }, { rootMargin: '300px' });
    io.observe(iframe);
    // Safety timeout: load after 3s even if observer never fires
    // (e.g. display:none ancestor, or already in viewport before observer attaches).
    setTimeout(applySrcdoc, 3000);
  } else {
    applySrcdoc();
  }
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
