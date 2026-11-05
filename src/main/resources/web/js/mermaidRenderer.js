// mermaidRenderer.js — ```mermaid 围栏 → 懒加载、跟随主题、消毒后的 SVG。
// 入仓批次：mermaid-md（2026-10-23 作者令「让我们的markdown支持mermaid渲染」）；
// rich-render 批（2026-11-05 作者令 08:51 第 1/3/4/5/6 条）在此硬化：
//   · 安全：securityLevel 'strict' + **htmlLabels 显式 false**（关掉 foreignObject
//     这唯一的 HTML 注入面），产物落 DOM 前再过一道节点级扫描（不是只扫字符串）；
//   · 落 DOM：DOMParser 解析 → 逐节点校验 → importNode/replaceChildren，
//     本文件**零裸 innerHTML**（component-contract R5）；
//   · 失败可见：错误态由 fenceKit 单点提供（原始源码默认可见）；
//   · 流式：占位只由「消息已完成」的渲染产生（判据在 utils.js，见该文件
//     `_trailingOpenFence` + opts.stream），本文件不猜流式状态。
//
// 管线分工（与 utils.js 的 katex/human 面同一纪律）：
//   utils.js（字符串面）把 ```mermaid 围栏换成 `.nf-mermaid` 占位（源码在
//   data-mermaid-src）→ 本文件（DOM 面）水合：
//     视口懒渲染（IntersectionObserver，见 fenceKit）、
//     库按需装载（vendored 5.5MB IIFE，单飞 Promise，对齐 pdf.js「不进开屏包」）、
//     主题跟随（prefers-color-scheme）、
//     失败态（不空白不吞）。
//
// 接线：main.js 一次 `armMermaidAutoHydrate()`；viewers/markdown.js 额外显式
// `hydrateMermaidIn`（主文档面）。两者皆幂等。
//
// 库 PIN：vendored mermaid 12.2.0（vendor/mermaid/README.md 台账，
// sha256 a8a6a6f805b074a8261a2825c86f99c8054acb3b35f50bb7e24a4f9f2c3c3b9f）。

import { armFenceAutoHydrate, fenceDone, fenceError, fenceBody, fenceSource, hydrateFencesIn, registerFence } from './fenceKit.js';
import { normalizeComputedInk } from './svgInk.js';

const MERMAID_SCRIPT = '/vendor/mermaid/mermaid.min.js';
// Same stack as base.css body — diagrams must not introduce a foreign face.
const MERMAID_FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif";
const SVG_CACHE_CAP = 24;
const SVG_NS = 'http://www.w3.org/2000/svg';

/** @typedef {import('./fenceKit.js').FenceKind} FenceKind */

/** @type {FenceKind} */
const MERMAID_KIND = {
  name: 'mermaid',
  selector: '.nf-mermaid',
  stateAttr: 'data-mermaid-state',
  srcAttr: 'data-mermaid-src',
  bodyClass: 'nf-mermaid-body',
  phClass: 'nf-mermaid-ph',
  render: renderBlock,
};

/** @type {Promise<any>|null} single-flight library load (pdf.js shape) */
let mermaidReady = null;
let renderUid = 0;

/** Rendered-svg LRU keyed by `${themeKey}|${source}` — streaming faces
 *  re-mount placeholders per frame; a cache hit re-attaches synchronously. */
/** @type {Map<string, Node>} */
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

/** Mermaid init config for the CURRENT theme. Light runs `neutral` (quiet greys
 *  fit the 低調 reading face) tinted sapphire; dark runs `dark` with transparent
 *  background over the app panels. `htmlLabels: false` is a security setting,
 *  not taste: html labels are <foreignObject> HTML islands inside the SVG and
 *  the one channel mermaid's sanitizer could be bypassed through. */
function themeConfig() {
  const sp = sapphireRgb();
  const fontFamily = MERMAID_FONT;
  const base = {
    fontFamily,
    securityLevel: 'strict',
    startOnLoad: false,
    // 库自带失败面 = 往 <body> 塞一个 "Syntax error in text" 炸弹元素：页面污染
    // 且在我们的错误态之外。错误归 fenceKit.fenceError（原始源码默认可见）。
    suppressErrorRendering: true,
    htmlLabels: false,
    flowchart: { htmlLabels: false, useMaxWidth: true },
    class: { htmlLabels: false },
  };
  if (darkMQ.matches) {
    return {
      ...base,
      theme: 'dark',
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
    ...base,
    theme: 'neutral',
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
      w.mermaid.initialize(themeConfig());
      resolve(w.mermaid);
    };
    s.onerror = () => { mermaidReady = null; reject(new Error('mermaid load failed')); };
    document.head.appendChild(s);
  });
  return mermaidReady;
}

/**
 * 节点级消毒扫描（belt over mermaid strict）：svg 落 DOM 前逐节点查
 * script / foreignObject / on* 属性 / 非片段 href。正则扫字符串挡不住实体与
 * 大小写变体，节点扫描挡得住。
 * @param {Element} root
 * @returns {string} 干净 = 空串；脏 = 原因
 */
function svgScan(root) {
  if (root.namespaceURI !== SVG_NS) return 'root is not svg';
  const bad = root.querySelector('script, foreignObject');
  if (bad) return 'unsafe element: ' + bad.tagName;
  const all = [root, ...Array.from(root.querySelectorAll('*'))];
  for (const el of all) {
    for (const at of Array.from(el.attributes)) {
      const name = at.name.toLowerCase();
      if (name.startsWith('on')) return 'unsafe attribute: ' + at.name;
      if ((name === 'href' || name.endsWith(':href')) && !at.value.trim().startsWith('#')) return 'external href';
      if (/javascript:|data:text\/html/i.test(at.value)) return 'unsafe url';
    }
  }
  return '';
}

/** svg 字符串 → 已扫描的 DOM 节点。 */
function svgToNode(/** @type {string} */ svg) {
  const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
  if (doc.querySelector('parsererror')) throw new Error('svg parse failed');
  const root = doc.documentElement;
  const why = svgScan(root);
  if (why) throw new Error(why);
  return /** @type {Node} */ (document.importNode(root, true));
}

/** Render one `.nf-mermaid` block. Generation-guarded: a theme flip bumps
 *  `_mermaidGen` and drops in-flight writes from the previous theme. */
async function renderBlock(/** @type {HTMLElement} */ el) {
  const src = fenceSource(el, MERMAID_KIND);
  const anyEl = /** @type {any} */ (el);
  const gen = (anyEl._mermaidGen = (anyEl._mermaidGen || 0) + 1);
  try {
    const mermaid = await loadMermaid();
    const key = themeKey() + '|' + src;
    let node = svgCache.get(key);
    if (node === undefined) {
      // Re-initialize per render batch so a theme flip after library load is
      // picked up (initialize merges into the live config).
      mermaid.initialize(themeConfig());
      const out = await mermaid.render('nf-mermaid-svg-' + (++renderUid), src);
      node = svgToNode(String(out.svg || ''));
      svgCache.set(key, node);
      if (svgCache.size > SVG_CACHE_CAP) svgCache.delete(svgCache.keys().next().value);
    }
    if (anyEl._mermaidGen !== gen) return; // superseded mid-render
    // 缓存节点可能已被上一处挂载消费 ⇒ 克隆再挂（replaceChildren 会移动原节点）。
    fenceDone(el, MERMAID_KIND, /** @type {Node} */ (node).cloneNode(true));
    // 墨色归一必须**挂载之后**做：mermaid 把自己的配色写在 svg 内部的 `<style>`
    // 里，只有 getComputedStyle 才读得到；暗档实测有文本仍是纯黑（对比度
    // 1.16:1，肉眼不可读），这条 belt 把它换成主题正文墨色（行内 style 压过内部
    // 样式表）。读数落到 data-nf-ink 供机械断言。
    const body = fenceBody(el, MERMAID_KIND.bodyClass);
    const svg = body ? body.querySelector('svg') : null;
    if (svg) el.setAttribute('data-nf-ink', String(normalizeComputedInk(svg)));
  } catch (e) {
    if (anyEl._mermaidGen !== gen) return; // superseded mid-render
    fenceError(el, MERMAID_KIND, MERMAID_KIND.bodyClass, src, /** @type {Error} */ (e));
  }
}

/** 显式水合（主文档面用；幂等，无围栏时是空操作）。 */
export function hydrateMermaidIn(/** @type {Element} */ root) {
  hydrateFencesIn(root, 'mermaid');
}

/** 全局接线：`mermaid` 族注册 + 一个 body 级 observer（fenceKit 单点）。 */
export function armMermaidAutoHydrate() {
  registerFence(MERMAID_KIND);
  armFenceAutoHydrate();
}

/** 主题翻转时丢弃在飞渲染（换图由 fenceKit.requeueSettledFences 驱动）。 */
export function bumpMermaidGeneration() {
  document.querySelectorAll('.nf-mermaid').forEach((el) => {
    const anyEl = /** @type {any} */ (el);
    anyEl._mermaidGen = (anyEl._mermaidGen || 0) + 1;
  });
}
