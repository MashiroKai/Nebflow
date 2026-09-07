// cardRegistry.js — Universal HTML card renderer for Nebflow
// Detects ___<AGENT>_HTML___ markers and renders HTML in sandboxed iframes.
// Theme CSS variables are injected into iframes for dark mode support.

import state from './state.js';
import { key } from './branding.js';
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

/** Build the height reporting script to inject into srcdoc.
 *  #nf-wrap uses width:100% so all content (including SVGs with width:100%)
 *  fills the available card width and responds to window resize.
 *  Height is reported via ResizeObserver so the iframe auto-sizes vertically.
 *  Width reporting is no longer needed — the chain width:100% from chat panel
 *  → row → container → wrap → iframe → nf-wrap handles responsive sizing.
 *
 *  Measurement source: body.scrollHeight (NOT documentElement.scrollHeight).
 *  <html> is the viewport scroll container — documentElement.scrollHeight is
 *  max(viewport, content), so short content gets padded up to the iframe's
 *  default 150px viewport (empty band under the card) and the viewport leaks
 *  into every measurement. body has overflow:hidden (BFC), so it shrink-wraps
 *  its content and body.scrollHeight is the natural content height including
 *  trailing margins, independent of the viewport.
 *
 *  Runaway fuse: content whose height genuinely depends on the viewport
 *  (min-height:100vh-class layouts) has no stable auto height — height H
 *  makes the viewport H, which makes the content H+tail, which reports
 *  H+tail, forever ("card scrolls forever, never reaches its bottom").
 *  Detect the signature — consecutive uniform growth steps (constant delta,
 *  small, monotonic) — and trip: stop reporting, clamp the body to the last
 *  height and switch it to internal scrolling so the bottom edge stays
 *  reachable. The parent re-arms the fuse via _nfCardRemeasure when the card
 *  width changes (only the parent knows; any viewport-bound anchor inside
 *  the frame would itself move with the clamp). */
function buildHeightScript(id) {
  return `<script>
(function(){
  var id=${id};
  var last=0,step=0,delta=0,tripped=false;
  var FUSE_TRIPS=8;
  function measure(){
    return document.body.scrollHeight||document.documentElement.scrollHeight;
  }
  function send(){
    if(tripped) return;
    try{
      var h=measure();
      if(last>0&&h>last){
        var d=h-last;
        if(d<200&&delta>0&&Math.abs(d-delta)<=Math.max(1,0.02*delta)){
          step++;
        }else{
          step=1;
        }
        delta=d;
        if(step>=FUSE_TRIPS){
          tripped=true;
          document.body.style.height=h+'px';
          document.body.style.overflow='auto';
          parent.postMessage({_nfCardH:h,id:id},"*");
          parent.postMessage({_nfCardFused:true,id:id},"*");
          return;
        }
      }else{
        step=1;delta=0;
      }
      last=h;
      parent.postMessage({_nfCardH:h,id:id},"*");
    }catch(e){}
  }
  window.addEventListener('message',function(e){
    if(e.data&&e.data._nfCardRemeasure&&e.data.id===id){
      tripped=false;last=0;step=0;delta=0;
      document.body.style.height='';
      document.body.style.overflow='';
      setTimeout(send,30);
    }
    if(e.data&&e.data._nfThemeVars){
      var s=document.getElementById('nf-card-theme');
      if(!s){s=document.createElement('style');s.id='nf-card-theme';document.head.appendChild(s);}
      s.textContent=':root{'+e.data._nfThemeVars+'}';
      setTimeout(send,50);
    }
  });
  new ResizeObserver(send).observe(document.body);
  send();setTimeout(send,100);setTimeout(send,500);setTimeout(send,2000);
})();
</script>`;
}

/** Track which iframes have received their first height measurement. */
const _firstHeightDone = new Set();

/** Listen for height messages from card iframes. */
window.addEventListener('message', (e) => {
  // Runaway-fuse trip notice (see buildHeightScript): the frame froze its own
  // height and switched to internal scrolling. Mark the iframe so the wrap's
  // width observer knows to re-arm it when the card re-flows.
  if (e.data && e.data._nfCardFused) {
    const fused = document.querySelector(`iframe[data-nf-card-id="${e.data.id}"]`);
    if (fused) (/** @type {{_nfFused?: boolean}} */ (fused))._nfFused = true;
    return;
  }
  if (!e.data || !e.data._nfCardH) return;
  const iframe = document.querySelector(`iframe[data-nf-card-id="${e.data.id}"]`);
  if (iframe) {
    const isFirst = !_firstHeightDone.has(e.data.id);
    if (isFirst) {
      _firstHeightDone.add(e.data.id);
    }
    const oldHeight = iframe.style.height;
    const newHeight = e.data._nfCardH + 'px';
    // Height collapse protection: if the iframe already has a reasonable
    // height, ignore suspiciously small reports that would clip all content.
    // These can occur from transient layout changes during font/image loading
    // or browser resource management discarding and partially restoring the
    // iframe's browsing context.
    const oldHeightNum = parseInt(oldHeight) || 0;
    if (oldHeightNum > 50 && e.data._nfCardH < 20) return;
    // Track whether height actually changed (first measurement always counts as "changed")
    const heightChanged = !oldHeight || oldHeight !== newHeight;
    iframe.style.height = newHeight;
    // On first measurement: reveal
    if (isFirst) {
      iframe.style.opacity = '1';
    }
    // If height changed, ensure scroll position still shows the card bottom.
    // Works for first measurement (oldHeight == '' → heightChanged = true) and subsequent resizes.
    if (heightChanged) {
      const view = state.getActiveView ? state.getActiveView() : null;
      const chat = view && view.dom.chat;
      if (!chat) return;
      const nearBottom = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 100;
      if (nearBottom) {
        requestAnimationFrame(() => {
          chat.scrollTop = chat.scrollHeight;
          const v = state.getActiveView ? state.getActiveView() : null;
          if (v) v.stream.scrollSnapped = true;
        });
      } else {
        smartScroll();
      }
    }
  }
});

// ===== Card interaction: accumulate + submit =====
const _cardAccumulator = {};
// Last-activity timestamp per origin — drives the TTL prune below (D5,
// mem-diag 20260907: entries were previously only cleared on submit, so
// abandoned interactions accumulated forever).
const CARD_ACCUM_TTL_MS = 10 * 60 * 1000;
const _cardAccumTs = new Map();

function pruneCardAccumulator(now = Date.now()) {
  for (const [origin, ts] of _cardAccumTs) {
    if (now - ts > CARD_ACCUM_TTL_MS) {
      _cardAccumTs.delete(origin);
      delete _cardAccumulator[origin];
    }
  }
}

/** Drop all in-progress card interaction accumulations. Card iframes are
 *  destroyed on session switch/delete, so their pending selections are
 *  unreachable orphans past that point (D5, mem-diag 20260907). */
export function resetCardAccumulator() {
  for (const k of Object.keys(_cardAccumulator)) delete _cardAccumulator[k];
  _cardAccumTs.clear();
}

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
    pruneCardAccumulator();
    // Merge into accumulator (per card iframe origin)
    const key = e.origin;
    if (!_cardAccumulator[key]) _cardAccumulator[key] = {};
    Object.assign(_cardAccumulator[key], data);
    _cardAccumTs.set(key, Date.now());
  } else if (action === 'submit') {
    // Merge any accumulated data with the submit payload
    const key = e.origin;
    _cardAccumTs.delete(key);
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
  return localStorage.getItem(key('token')) || '';
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

  // Add preload="none" to <audio>/<video> elements that don't already have it.
  // Without this, a session with 200+ audio elements causes the browser to
  // simultaneously fetch and decode all files on load, freezing the page.
  processedHtml = processedHtml.replace(/(<audio\b(?![^>]*\bpreload=)[^>]*)(\s*\/?>)/gi, '$1 preload="none"$2');
  processedHtml = processedHtml.replace(/(<video\b(?![^>]*\bpreload=)[^>]*)(\s*\/?>)/gi, '$1 preload="none"$2');

  // #nf-wrap: width:100% fills the available card width.
  // SVGs with width:100% scale proportionally via viewBox + height:auto.
  // No fit-content deadlock — the width chain is: chat panel → row → container → wrap → iframe → nf-wrap, all 100%.
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}html,body{margin:0;padding:0;font-size:15px;line-height:1.5;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;background:var(--color-bg);color:var(--color-text);overflow:hidden;}*,*:before,*:after{box-sizing:inherit;}svg{max-width:100%;height:auto;}svg text{font-size:min(max(14px,100%),5vw);}img{max-width:100%;height:auto;}</style></head><body><div id="nf-wrap" style="width:100%">${processedHtml}</div>${heightScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.className = 'html-card-iframe';
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
  iframe.setAttribute('scrolling', 'no');
  iframe.dataset.nfCardId = id;
  wrap.appendChild(iframe);

  container.appendChild(wrap);

  // Re-arm a fused (viewport-dependent content, see buildHeightScript) card
  // when its width changes: only the parent knows about re-flows — any
  // anchor inside the frame would itself move with the fuse clamp. Normal
  // (non-fused) cards don't need this: their in-frame ResizeObserver picks
  // up width-driven height changes on its own.
  let lastWrapW = 0;
  const widthObserver = new ResizeObserver(() => {
    const w = Math.round(wrap.getBoundingClientRect().width);
    if (Math.abs(w - lastWrapW) <= 2) return;
    lastWrapW = w;
    if (/** @type {{_nfFused?: boolean}} */ (iframe)._nfFused) {
      try { iframe.contentWindow.postMessage({ _nfCardRemeasure: true, id }, '*'); } catch (err) { /* dead frame */ }
    }
  });
  widthObserver.observe(wrap);

  // Lazy loading: defer srcdoc until the iframe is near the viewport.
  // This prevents all card iframes in a session from being created and
  // parsed at once — only visible (+ margin) cards are instantiated.
  let srcdocSet = false;
  let lastReloadCheck = 0;

  const applySrcdoc = () => {
    if (srcdocSet) return;
    srcdocSet = true;
    // Set the reload throttle baseline so we don't attempt a reload check
    // during the initial load (the iframe's size change from 0→150px can
    // re-trigger the IntersectionObserver while content is still parsing).
    lastReloadCheck = Date.now();
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

  // Check if the iframe's content has been lost (browser may discard iframe
  // browsing contexts under memory pressure when they scroll far out of view)
  // and reload from srcdoc if needed. Called when the iframe re-enters the
  // viewport via IntersectionObserver.
  const reloadIfBlank = () => {
    if (!srcdocSet) return;
    // Throttle: at most one check per 2s per card
    const now = Date.now();
    if (now - lastReloadCheck < 2000) return;
    lastReloadCheck = now;

    let needsReload = false;
    try {
      const doc = iframe.contentDocument;
      if (!doc || !doc.body) {
        // Browsing context was destroyed by the browser
        needsReload = true;
      } else {
        const innerWrap = doc.body.querySelector('#nf-wrap');
        if (!innerWrap || (innerWrap.children.length === 0 && !innerWrap.textContent.trim())) {
          // Content rendered but is now empty
          needsReload = true;
        }
      }
    } catch (e) {
      // Cross-origin — can't inspect, skip
      return;
    }

    if (needsReload) {
      // Force reload by re-applying srcdoc. Removing and re-adding the
      // attribute in the same frame doesn't always trigger a navigation,
      // so we use requestAnimationFrame to ensure the browser processes
      // the removal before re-adding.
      iframe.removeAttribute('srcdoc');
      requestAnimationFrame(() => {
        iframe.setAttribute('srcdoc', srcdoc);
      });
    }
  };

  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      if (entries.some(e => e.isIntersecting)) {
        if (!srcdocSet) {
          applySrcdoc();
        } else {
          reloadIfBlank();
        }
        // Don't disconnect — keep observing so we can detect and reload
        // blank cards (e.g. browser-discarded iframes) when they scroll
        // back into view.
      }
    }, { rootMargin: '300px' });
    io.observe(iframe);
    // Register cleanup so mass DOM removal (session switch, history
    // reload) can release the observer + iframe browsing context instead
    // of leaking them (each leaked iframe keeps its own DOM+JS engine).
    iframe._nfCleanup = () => {
      io.disconnect();
      widthObserver.disconnect();
      _firstHeightDone.delete(id);
      iframe.removeAttribute('srcdoc');
    };
    // Safety timeout: load after 3s even if observer never fires
    // (e.g. display:none ancestor, or already in viewport before observer attaches).
    setTimeout(applySrcdoc, 3000);
  } else {
    applySrcdoc();
  }
}

/** Release all card iframes under root: disconnect their IntersectionObservers,
 *  clear first-height tracking, and drop srcdoc so the browsing contexts can
 *  be GC'd. MUST be called before mass-removing chat DOM (innerHTML = '') —
 *  otherwise observers keep the iframes (and their independent browsing
 *  contexts, 2-10MB each) alive forever. */
export function cleanupCardIframes(root) {
  if (!root || !root.querySelectorAll) return;
  root.querySelectorAll('iframe[data-nf-card-id]').forEach(f => {
    try { if (f._nfCleanup) f._nfCleanup(); } catch (e) { /* non-critical */ }
  });
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
