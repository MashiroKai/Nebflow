// svgFence.js — ```svg 围栏 → 白名单重建后的内联 SVG（rich-render 批，作者令
// 2026-11-05 08:55 逐字「可以，试试内联svg」）。
//
// ── 为什么不是「正则黑名单扫一遍就挂上去」─────────────────────────────
// 黑名单在 XML 里天然是漏的：大小写变体（`<ScRiPt>`）、命名实体、命名空间前缀
// （`<svg:script>`）、`<style>` 里的 `@import`、`xlink:href` 与 `href` 双写通道、
// 大小写混写的 `JaVaScRiPt:` —— 逐条补正则是个无底洞。所以本模块的形态是
// **先解析成 DOM，再按白名单重建一棵新树**：原树只当不可信输入读，产物树上每个
// 元素/属性都是我们自己写进去的。没在白名单里的东西不存在于产物里（不是被
// 「过滤掉了」，是从来没被复制）。
//
// ── 审查矩阵（作者 2026-11-05 08:55 逐条；机械断言见 tests/rich-render.spec.mjs）──
//   ① `<script>` / `</script>` 片段        → 不在白名单 ⇒ 整棵子树不复制
//   ② 所有 `on*` 事件属性（通配）           → 属性白名单外 + 值策略双闸
//   ③ `<foreignObject>` 整棵子树            → 不在白名单 ⇒ 整棵子树不复制
//   ④ 外链资源：`<image href="http…">` / `<use href="http…">` / 外部
//      `xlink:href` / `<style>@import` / `url(http…)` → href 只认 `#片段` 或内联
//      位图 data:；style 元素与 style 属性整类不复制；`url(` 只认 `#`
//   ⑤ `<a href>` 去链接保留文字             → `a` 解包（子节点上提一层）
//   ⑥ `<iframe>/<object>/<embed>/<animate*>/<set>` → 不在白名单 ⇒ 不复制
// 审查后整份不可用 / 重建后无可画内容 ⇒ 回退成代码块 + 可见错误态
// （fenceKit.fenceError，原始源码默认可见）。**无任何外部请求**：产物里只可能有
// 片段引用与内联 data: 位图。
//
// ── 主题 ──────────────────────────────────────────────────────────────
// 外来 SVG 自带配色，暗色下深墨会糊在深底上。挂载前做一次**墨色归一**：把每个
// 元素的有效 `fill`/`stroke` 解析成计算色值，与面板底色算 WCAG 相对亮度比；
// 低于 3:1 的换成当前主题的正文墨色 token。没有显式 fill 的图形/文字补上主题
// 墨色（XML 默认 fill 恒黑，暗色下等于隐形）。读数可机械断言：断言取的就是
// **计算值**（getComputedStyle），不是肉眼。
//
// 依赖：零（浏览器原生 DOMParser）——本围栏不新增任何第三方依赖。

import { armFenceAutoHydrate, fenceDone, fenceError, fenceNotice, fenceSource, hydrateFencesIn, registerFence } from './fenceKit.js';
import { t } from './i18n.js';
import { normalizeAttrInk } from './svgInk.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const XLINK_NS = 'http://www.w3.org/1999/xlink';

/** 允许复制的元素（几何 / 文字 / 定义 / 渐变 / 裁剪 / 标记 / 滤镜基元）。
 *  刻意**不含**：script、foreignObject、iframe、object、embed、animate、
 *  animateTransform、animateMotion、set、handler、listener、a、style。 */
const ALLOWED_ELEMENTS = new Set([
  'svg', 'g', 'defs', 'symbol', 'switch', 'title', 'desc',
  'path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon',
  'text', 'tspan', 'textPath',
  'use', 'image', 'marker',
  'linearGradient', 'radialGradient', 'stop',
  'clipPath', 'mask', 'pattern',
  'filter', 'feGaussianBlur', 'feOffset', 'feBlend', 'feColorMatrix',
  'feComposite', 'feFlood', 'feMerge', 'feMergeNode', 'feMorphology',
  'feDropShadow', 'feTurbulence', 'feDisplacementMap',
]);

/** 允许复制的属性（几何 / 表现 / 引用 / 无障碍）。名字按小写比对；
 *  `data-*` / `aria-*` 单独放行（无行为面）。 */
const ALLOWED_ATTRS = new Set([
  'xmlns', 'xmlns:xlink', 'version', 'viewbox', 'preserveaspectratio', 'id', 'class',
  'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry', 'fx', 'fy', 'fr',
  'width', 'height', 'd', 'points', 'pathlength', 'transform', 'transform-origin',
  'dx', 'dy', 'rotate', 'textlength', 'lengthadjust', 'startoffset', 'method', 'spacing',
  'fill', 'fill-opacity', 'fill-rule', 'stroke', 'stroke-width', 'stroke-opacity',
  'stroke-linecap', 'stroke-linejoin', 'stroke-dasharray', 'stroke-dashoffset',
  'stroke-miterlimit', 'opacity', 'color', 'display', 'visibility', 'overflow',
  'paint-order', 'vector-effect', 'shape-rendering', 'color-interpolation',
  'color-interpolation-filters',
  'font-family', 'font-size', 'font-weight', 'font-style', 'font-variant',
  'letter-spacing', 'word-spacing', 'text-anchor', 'text-decoration',
  'dominant-baseline', 'alignment-baseline', 'baseline-shift', 'writing-mode',
  'direction', 'unicode-bidi', 'white-space',
  'clip-path', 'clip-rule', 'mask', 'filter', 'marker-start', 'marker-mid',
  'marker-end', 'markerwidth', 'markerheight', 'markerunits', 'refx', 'refy',
  'orient', 'gradientunits', 'gradienttransform', 'spreadmethod', 'offset',
  'stop-color', 'stop-opacity', 'patternunits', 'patterncontentunits',
  'patterntransform', 'clippathunits', 'maskunits', 'maskcontentunits',
  'filterunits', 'primitiveunits', 'stddeviation', 'in', 'in2', 'result', 'mode',
  'operator', 'values', 'type', 'tablevalues', 'slope', 'intercept', 'amplitude',
  'exponent', 'basefrequency', 'numoctaves', 'seed', 'radius', 'flood-color',
  'flood-opacity', 'scale', 'xchannelselector', 'ychannelselector',
  'edgemode', 'preservealpha', 'k1', 'k2', 'k3', 'k4', 'targetx', 'targety',
  'role', 'aria-label', 'aria-hidden', 'focusable',
]);

/** 内联位图 data: 白名单（无脚本面、无请求面）。 */
const DATA_IMAGE_RE = /^data:image\/(png|jpeg|jpg|gif|webp);base64,[a-z0-9+/=\s]+$/i;

/** @typedef {import('./fenceKit.js').FenceKind} FenceKind */

/** @type {FenceKind} */
export const SVG_KIND = {
  name: 'svg',
  selector: '.nf-svg',
  stateAttr: 'data-svg-state',
  srcAttr: 'data-svg-src',
  bodyClass: 'nf-svg-body',
  phClass: 'nf-svg-ph',
  render: renderBlock,
};

/**
 * 白名单重建。原树只读，产物树全新建。
 * @param {string} src 围栏源码（可信度 = 不可信输入）
 * @returns {{ root: Element, strikes: number }} strikes = 被剥离/降级的项数
 */
export function rebuildSvg(src) {
  const doc = new DOMParser().parseFromString(src, 'image/svg+xml');
  if (doc.querySelector('parsererror')) throw new Error(t('fence.svgParseFailed'));
  const srcRoot = doc.documentElement;
  if (!srcRoot || srcRoot.localName !== 'svg' || srcRoot.namespaceURI !== SVG_NS) {
    throw new Error(t('fence.svgRootRequired'));
  }
  let strikes = 0;
  const out = document.createElementNS(SVG_NS, 'svg');

  /** @param {Element} from @param {Element} to */
  const copyAttrs = (from, to) => {
    for (const at of Array.from(from.attributes)) {
      const ns = at.namespaceURI;
      const lname = at.localName.toLowerCase();
      const full = at.name.toLowerCase();
      // ② 事件属性：通配剥离（白名单外 + 显式判据，双闸）。
      if (lname.startsWith('on') || full.startsWith('on')) { strikes++; continue; }
      if (ns === XLINK_NS || lname === 'href' || full.endsWith(':href')) {
        // ④ 引用只认片段（#id）与内联位图 data:，其余一律剥。
        const v = at.value.trim();
        if (v.startsWith('#') || DATA_IMAGE_RE.test(v)) to.setAttribute('href', v);
        else strikes++;
        continue;
      }
      if (ns && ns !== SVG_NS) { strikes++; continue; }
      if (!ALLOWED_ATTRS.has(lname) && !lname.startsWith('data-') && !lname.startsWith('aria-')) {
        strikes++;
        continue;
      }
      const value = at.value;
      // ④ 值策略：只允许片段 url(#…)，禁 @import / expression / javascript:。
      if (/@import|expression\(|javascript:/i.test(value)) { strikes++; continue; }
      if (/url\(\s*['"]?(?!#)/i.test(value)) { strikes++; continue; }
      to.setAttribute(lname, value);
    }
  };

  /** @param {Node} from @param {Element} to */
  const copyChildren = (from, to) => {
    for (const child of Array.from(from.childNodes)) {
      if (child.nodeType === 3 || child.nodeType === 4) {
        const text = /** @type {string} */ (child.nodeValue || '');
        if (text.trim()) to.appendChild(doc.createTextNode(text));
        continue;
      }
      if (child.nodeType !== 1) continue;
      const el = /** @type {Element} */ (child);
      const name = el.localName;
      // ①③⑥ 脚本 / HTML 岛 / 可挂脚本容器 / style：整棵子树不复制。
      if (name === 'script' || name === 'foreignObject' || name === 'iframe' ||
          name === 'object' || name === 'embed' || name === 'handler' ||
          name === 'listener' || name === 'style') {
        strikes++;
        continue;
      }
      // ⑥ 动画形态（含 set）整体剥离。
      if (/^animate/i.test(name) || name === 'set') { strikes++; continue; }
      // ⑤ `<a href>` 去链接保留文字（子节点上提一层）。
      if (name === 'a') { strikes++; copyChildren(el, to); continue; }
      if (!ALLOWED_ELEMENTS.has(name) || el.namespaceURI !== SVG_NS) { strikes++; continue; }
      // ④ 外链位图：`image` 只接受内联 data: 位图。
      if (name === 'image') {
        const href = (el.getAttribute('href') || el.getAttribute('xlink:href') || '').trim();
        if (!DATA_IMAGE_RE.test(href)) { strikes++; continue; }
      }
      const clone = doc.createElementNS(SVG_NS, name);
      copyAttrs(el, clone);
      copyChildren(el, clone);
      to.appendChild(clone);
    }
  };

  copyAttrs(srcRoot, out);
  copyChildren(srcRoot, out);
  return { root: out, strikes };
}

/** 重建后的树里有没有「可画的东西」。 @param {Element} root @returns {boolean} */
function hasDrawable(root) {
  const DRAWN = ['path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'text', 'image', 'use'];
  return DRAWN.some((tag) => root.querySelector(tag) !== null);
}

/** 渲染一个 `.nf-svg` 占位块。 @param {HTMLElement} el */
async function renderBlock(el) {
  const src = fenceSource(el, SVG_KIND);
  const anyEl = /** @type {any} */ (el);
  const gen = (anyEl._svgGen = (anyEl._svgGen || 0) + 1);
  try {
    const { root, strikes } = rebuildSvg(src);
    if (!hasDrawable(root)) throw new Error(t('fence.svgNothingLeft'));
    normalizeAttrInk(root);
    if (anyEl._svgGen !== gen) return;
    fenceDone(el, SVG_KIND, /** @type {Node} */ (document.importNode(root, true)));
    if (strikes > 0) fenceNotice(el, SVG_KIND.bodyClass, t('fence.sanitized', { n: String(strikes) }));
  } catch (e) {
    if (anyEl._svgGen !== gen) return;
    fenceError(el, SVG_KIND, SVG_KIND.bodyClass, src, /** @type {Error} */ (e));
  }
}

/** 显式水合（幂等）。 @param {Element} root */
export function hydrateSvgIn(root) {
  hydrateFencesIn(root, 'svg');
}

/** 全局接线：`svg` 族注册 + body 级 observer（fenceKit 单点）。 */
export function armSvgFenceHydrate() {
  registerFence(SVG_KIND);
  armFenceAutoHydrate();
}

/** 主题翻转：丢弃在飞渲染。 */
export function bumpSvgGeneration() {
  document.querySelectorAll('.nf-svg').forEach((el) => {
    const anyEl = /** @type {any} */ (el);
    anyEl._svgGen = (anyEl._svgGen || 0) + 1;
  });
}
