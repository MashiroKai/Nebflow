// mermaidRenderer.js — ```mermaid fenced blocks → lazy, themed, sandboxed SVG.
// (mermaid-md batch, 2026-10-23; author order 「让我们的markdown支持mermaid渲染」)
//
// Pipeline split (same discipline as the katex/math pass in utils.js):
//   · utils.js (string land) swaps ```mermaid fences for `.nf-mermaid`
//     placeholder divs carrying the raw source in `data-mermaid-src` — every
//     face sharing renderMarkdownWithMath (canvas md viewer, wiki docs, chat
//     bubbles, flow/report cards, popups) gets the placeholder for free;
//   · this module (DOM land) hydrates those placeholders:
//       lazy viewport render (IntersectionObserver, 320px lead),
//       on-demand library load (pdf.js single-flight script-inject precedent —
//         the 5.5MB vendored mermaid never taxes documents without diagrams),
//       theme following (matchMedia prefers-color-scheme, monaco precedent —
//         mermaid theme variables read the sapphire token as the accent),
//       securityLevel 'strict' + a defensive svg scan before DOM attach,
//       error state that keeps the original source inspectable (不空白不吞).
//
// Wiring: ONE global MutationObserver (armMermaidAutoHydrate, armed in
// main.js) covers every markdown face with zero per-callsite edits;
// viewers/markdown.js additionally calls hydrateMermaidIn explicitly at the
// primary document face. Hydration mutations are re-entrant safe: the state
// attribute is set synchronously before any await, and the observer only
// matches `.nf-mermaid:not([data-mermaid-state])`.
//
// Library PIN: vendored mermaid 12.2.0 (vendor/mermaid/README.md ledger,
// sha256 a8a6a6f805b074a8261a2825c86f99c8054acb3b35f50bb7e24a4f9f2c3c3b9f).

import { t } from './i18n.js';

const MERMAID_SCRIPT = '/vendor/mermaid/mermaid.min.js';
// Same stack as base.css body — diagrams must not introduce a foreign face.
const MERMAID_FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif";
// Fail-closed svg gate (belt over mermaid strict): a sanitized svg never
// carries script elements, inline handlers or javascript: URLs.
const UNSAFE_SVG_RE = /<script[\s>]|javascript:|\son[a-z]+\s*=/i;
const SVG_CACHE_CAP = 24;

/** @type {Promise<any>|null} single-flight library load (pdf.js shape) */
let mermaidReady = null;
/** @type {IntersectionObserver|null} */
let lazyIO = null;
/** @type {MutationObserver|null} */
let autoMO = null;
let renderUid = 0;

/** Rendered-svg LRU keyed by `${themeKey}|${source}` — streaming faces
 *  re-mount placeholders per frame; a cache hit re-attaches synchronously. */
const svgCache = new Map();

const darkMQ = /** @type {MediaQueryList} */ (
  typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : { matches: false, addEventListener() {} });
const themeKey = () => (darkMQ.matches ? 'dark' : 'light');

/** Sapphire accent read straight from the design-system token (single source:
 *  the value is an `r g b` triplet in both themes). */
function sapphireRgb() {
  const triplet = getComputedStyle(document.documentElement).getPropertyValue('--sapphire').trim();
  return triplet || '91 127 191';
}

/** Mermaid init config for the CURRENT system theme. Light runs `neutral`
 *  (quiet greys fit the 低調 reading face) tinted sapphire; dark runs `dark`
 *  with transparent background over the app panels. */
function themeConfig() {
  const sp = sapphireRgb();
  const fontFamily = MERMAID_FONT;
  if (darkMQ.matches) {
    return {
      theme: 'dark',
      fontFamily,
      themeVariables: {
        fontFamily,
        background: 'transparent',
        lineColor: '#93a6c3',
        primaryColor: `rgb(${sp} / 0.32)`,
        primaryTextColor: '#e6edf5',
        actorBkg: `rgb(${sp} / 0.32)`,
        actorTextColor: '#e6edf5',
        signalTextColor: '#c9d5e5',
        noteBkgColor: `rgb(${sp} / 0.22)`,
      },
    };
  }
  return {
    theme: 'neutral',
    fontFamily,
    themeVariables: {
      fontFamily,
      background: 'transparent',
      lineColor: '#5b7fbf',
      primaryColor: `rgb(${sp} / 0.14)`,
      primaryTextColor: '#25324a',
      actorBkg: `rgb(${sp} / 0.14)`,
      actorTextColor: '#25324a',
      signalTextColor: '#3c4a63',
      noteBkgColor: `rgb(${sp} / 0.10)`,
    },
  };
}

/** Load the vendored mermaid IIFE once; initialize with the current theme. */
function loadMermaid() {
  if (mermaidReady) return mermaidReady;
  mermaidReady = new Promise((resolve, reject) => {
    const w = /** @type {any} */ (window);
    if (w.mermaid) { resolve(w.mermaid); return; }
    const s = document.createElement('script');
    s.src = MERMAID_SCRIPT;
    s.onload = () => {
      if (!w.mermaid) { mermaidReady = null; reject(new Error('mermaid global missing after load')); return; }
      // suppressErrorRendering: mermaid's default failure face is a "Syntax
      // error in text" bomb element appended to <body> OUTSIDE our block —
      // page-polluting and outside the error state's styling. Error handling
      // belongs to showError() (in-block message + inspectable source).
      w.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, ...themeConfig() });
      resolve(w.mermaid);
    };
    s.onerror = () => { mermaidReady = null; reject(new Error('mermaid load failed')); };
    document.head.appendChild(s);
  });
  return mermaidReady;
}

/** Fresh placeholder body content (skeleton slot) for a queued block. */
function placeholderBody(/** @type {HTMLElement} */ body) {
  body.innerHTML = '';
  const ph = document.createElement('div');
  ph.className = 'nf-mermaid-ph';
  body.appendChild(ph);
}

/** Render one `.nf-mermaid` block. Generation-guarded: a theme flip bumps
 *  `_mermaidGen` and drops in-flight writes from the previous theme. */
async function renderBlock(/** @type {HTMLElement} */ el) {
  const src = el.dataset.mermaidSrc || '';
  const body = el.querySelector('.nf-mermaid-body');
  if (!body) return;
  el.dataset.mermaidState = 'loading';
  const gen = (/** @type {any} */ (el)._mermaidGen = ((/** @type {any} */ (el)._mermaidGen || 0) + 1));
  try {
    const mermaid = await loadMermaid();
    const key = themeKey() + '|' + src;
    let svg = svgCache.get(key);
    if (svg === undefined) {
      // Re-initialize per render batch so a theme flip after library load is
      // picked up (initialize merges into the live config).
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, ...themeConfig() });
      ({ svg } = await mermaid.render('nf-mermaid-svg-' + (++renderUid), src));
      if (UNSAFE_SVG_RE.test(svg)) throw new Error('svg rejected by defensive scan');
      svgCache.set(key, svg);
      if (svgCache.size > SVG_CACHE_CAP) svgCache.delete(svgCache.keys().next().value);
    }
    if ((/** @type {any} */ (el)._mermaidGen) !== gen) return; // superseded mid-render
    body.innerHTML = svg;
    el.classList.add('nf-mermaid-done');
    el.dataset.mermaidState = 'done';
  } catch (e) {
    if ((/** @type {any} */ (el)._mermaidGen) !== gen) return; // superseded mid-render
    showError(el, src, /** @type {Error} */ (e));
  }
}

/** Error state: message row + source toggle. The original fenced source stays
 *  inspectable (textContent-only) — never blanked, never swallowed. */
function showError(/** @type {HTMLElement} */ el, /** @type {string} */ src, /** @type {Error} */ err) {
  el.classList.remove('nf-mermaid-done');
  el.classList.add('nf-mermaid-error');
  el.dataset.mermaidState = 'error';
  const body = el.querySelector('.nf-mermaid-body');
  if (!body) return;
  body.innerHTML = '';
  const row = document.createElement('div');
  row.className = 'nf-mermaid-errrow';
  const label = document.createElement('span');
  label.className = 'nf-mermaid-errlabel';
  label.textContent = t('mermaid.renderFailed');
  const detail = document.createElement('span');
  detail.className = 'nf-mermaid-errdetail';
  detail.textContent = String((err && err.message) || err).slice(0, 200);
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'nf-mermaid-srcbtn';
  btn.textContent = t('mermaid.showSource');
  const pre = document.createElement('pre');
  pre.className = 'nf-mermaid-src';
  pre.hidden = true;
  const code = document.createElement('code');
  code.textContent = src;
  pre.appendChild(code);
  btn.addEventListener('click', () => {
    pre.hidden = !pre.hidden;
    btn.textContent = pre.hidden ? t('mermaid.showSource') : t('mermaid.hideSource');
  });
  row.appendChild(label);
  row.appendChild(detail);
  row.appendChild(btn);
  body.appendChild(row);
  body.appendChild(pre);
}

/** Hydrate every pending `.nf-mermaid` under `root` (or `root` itself):
 *  mark queued synchronously, then let the viewport observer trigger renders. */
export function hydrateMermaidIn(/** @type {Element} */ root) {
  if (!root) return;
  if (root.matches('.nf-mermaid:not([data-mermaid-state])')) queueForViewport(/** @type {HTMLElement} */ (root));
  root.querySelectorAll('.nf-mermaid:not([data-mermaid-state])').forEach((el) => queueForViewport(/** @type {HTMLElement} */ (el)));
}

function queueForViewport(/** @type {HTMLElement} */ el) {
  el.dataset.mermaidState = 'pending';
  if (!lazyIO) {
    lazyIO = new IntersectionObserver((entries) => {
      for (const en of entries) {
        if (!en.isIntersecting) continue;
        lazyIO?.unobserve(en.target);
        renderBlock(/** @type {HTMLElement} */ (en.target));
      }
    }, { rootMargin: '320px 0px' });
  }
  lazyIO.observe(el);
}

/** ONE wiring point for every markdown face: a body-wide childList observer
 *  hydrates mermaid placeholders wherever the shared renderer emitted them
 *  (chat streams re-mount per frame; hydration is idempotent via the state
 *  attribute, and cache hits make remounts synchronous). */
export function armMermaidAutoHydrate() {
  if (autoMO || !window.MutationObserver) return;
  autoMO = new MutationObserver((records) => {
    for (const rec of records) {
      for (const node of rec.addedNodes) {
        if (!(node instanceof Element)) continue;
        if (node.matches('.nf-mermaid:not([data-mermaid-state])')) queueForViewport(/** @type {HTMLElement} */ (node));
        else if (node.querySelector('.nf-mermaid:not([data-mermaid-state])')) hydrateMermaidIn(node);
      }
    }
  });
  autoMO.observe(document.body, { childList: true, subtree: true });
}

// Theme flip: re-queue every settled block; renders read the fresh theme
// config (and per-theme cache), generation guards drop stale in-flight writes.
darkMQ.addEventListener?.('change', () => {
  document.querySelectorAll('.nf-mermaid[data-mermaid-state="done"], .nf-mermaid[data-mermaid-state="error"]').forEach((el) => {
    /** @type {HTMLElement} */ (el).classList.remove('nf-mermaid-done', 'nf-mermaid-error');
    delete /** @type {HTMLElement} */ (el).dataset.mermaidState;
    const body = /** @type {HTMLElement|null} */ (el.querySelector('.nf-mermaid-body'));
    if (body) placeholderBody(body);
    const anyEl = /** @type {any} */ (el);
    anyEl._mermaidGen = (anyEl._mermaidGen || 0) + 1;
    queueForViewport(/** @type {HTMLElement} */ (el));
  });
});
