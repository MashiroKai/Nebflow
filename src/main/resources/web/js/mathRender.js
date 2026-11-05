// mathRender.js — 数学公式围栏的按需装载腿（rich-render 批，作者令 2026-11-05
// 08:53：「KaTeX 数学公式 $$…$$ 块级 + $…$ 行内……同样本地 vendored、禁 CDN，
// 同样按需动态 import，不进首屏关键路径。安全：KaTeX 走 trust: false」）。
//
// 分工（与 mermaid/svg 同一纪律，单一管线不另起一套）：
//   utils.js（字符串面）在 `katex` 全局**缺席**时把每段公式换成
//   `<span class="nf-math" data-math-src="…" data-math-display="0|1">原始源码</span>`
//   —— 占位里直接躺着可读的原始 LaTeX，因此「水合没跑到」时的形态是**回退到源码**
//   而不是空白（失败可见的前置条件）；本文件（DOM 面）水合：
//     首次见公式才注入 vendor/katex.min.{css,js}（单飞 Promise，275KB 不进开屏包），
//     渲染成功 → 换成 KaTeX 产物；解析失败 → 保留原始源码 + 可见错误态。
//
// 安全：`trust: false`（作者令）——同时封闭 `\href` / `\url` / `\htmlClass` /
// `\includegraphics` 一类可注入/可外链面；产物落 DOM 前再逐节点剥 `on*` 与
// `href`（belt，见 `sanitizeKatexNodes`）。
//
// 库 PIN：vendored KaTeX（vendor/katex.min.js 275KB / vendor/katex.min.css 23KB，
// 均为上游 dist 原样件）——见 package.json `vendor.assets` 台账与批报告。

import { queueForViewport, registerFence, setFenceState } from './fenceKit.js';
import { t } from './i18n.js';

const KATEX_SCRIPT = '/vendor/katex.min.js';
const KATEX_CSS = '/vendor/katex.min.css';

/** @typedef {import('./fenceKit.js').FenceKind} FenceKind */

/** @type {FenceKind} */
const MATH_KIND = {
  name: 'math',
  selector: '.nf-math',
  stateAttr: 'data-math-state',
  srcAttr: 'data-math-src',
  bodyClass: 'nf-math',
  phClass: 'nf-math-ph',
  render: renderMath,
};

/** @type {Promise<any>|null} */
let katexReady = null;

/** 注入 vendor KaTeX 样式（一次）。 */
function ensureKatexCss() {
  if (document.querySelector('link[data-nf-katex]')) return;
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = KATEX_CSS;
  link.setAttribute('data-nf-katex', '');
  document.head.appendChild(link);
}

/** 按需装载 vendor KaTeX（单飞）。
 *
 *  ⚠ 这里**不能**用 `<script src>` 注入：页面上 monaco 的 AMD loader 定义了
 *  `window.define.amd`，katex 的 UMD 头会走 `define([], factory)` 分支 —— 模块
 *  值进了 loader，`window.katex` 永远不出现（实测 data-math-error =
 *  "katex global missing after load"，且是否命中取决于 monaco 装载时序，属竞态）。
 *  所以改为：取源码 → 在一个**有界函数作用域**里求值，并把 UMD 的三个探测点
 *  （define / module / exports）显式遮成 undefined ⇒ UMD 自动落到
 *  `self.katex = factory()` 分支。不碰全局 define，不与 monaco 抢，也不依赖时序。
 */
function loadKatex() {
  if (katexReady) return katexReady;
  katexReady = (async () => {
    const w = /** @type {any} */ (window);
    if (w.katex) return w.katex;
    ensureKatexCss();
    const res = await fetch(KATEX_SCRIPT);
    if (!res.ok) throw new Error('katex fetch failed: ' + res.status);
    const src = await res.text();
    // 作用域内 define/module/exports 皆为形参（= undefined）⇒ UMD 走全局赋值分支。
    const run = new Function('define', 'module', 'exports', src + '\n;return self.katex;');
    const api = run(undefined, undefined, undefined);
    if (!api || typeof api.renderToString !== 'function') throw new Error('katex global missing after eval');
    return api;
  })().catch((e) => {
    katexReady = null;
    throw e;
  });
  return katexReady;
}

/**
 * KaTeX 产物节点消毒（belt over trust:false）：剥 `on*` 事件属性、剥一切
 * `href`（公式不是链接面 —— `\href` 的可见文字保留，可点性去掉）、剥
 * `<script>` / `<iframe>` / `<object>` / `<embed>`。
 * @param {ParentNode} root
 */
function sanitizeKatexNodes(root) {
  root.querySelectorAll('script, iframe, object, embed').forEach((n) => n.remove());
  root.querySelectorAll('*').forEach((el) => {
    for (const at of Array.from(el.attributes)) {
      const name = at.name.toLowerCase();
      if (name.startsWith('on') || name === 'href' || name.endsWith(':href')) el.removeAttribute(at.name);
    }
  });
}

/** HTML 字符串 → 已消毒的节点列表（DOMParser 解析，零 innerHTML）。 */
function htmlToNodes(/** @type {string} */ html) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  sanitizeKatexNodes(doc.body);
  return Array.from(doc.body.childNodes).map((n) => document.importNode(n, true));
}

/** 失败态：原始源码保持可见（占位里本来就是它）+ 可读错误读数。 */
function mathError(/** @type {HTMLElement} */ el, /** @type {string} */ detail) {
  el.classList.add('nf-math-error');
  el.setAttribute('data-math-error', detail.slice(0, 160));
  el.setAttribute('title', t('fence.mathFailed') + ' · ' + detail.slice(0, 120));
  setFenceState(el, MATH_KIND, 'error');
}

/** 渲染一段 `.nf-math`。 @param {HTMLElement} el */
async function renderMath(el) {
  const src = el.getAttribute('data-math-src') || '';
  const display = el.getAttribute('data-math-display') === '1';
  try {
    const katex = await loadKatex();
    const html = katex.renderToString(src, {
      displayMode: display,
      throwOnError: false,
      strict: 'ignore',
      // 作者令 2026-11-05 08:53：trust 恒 false（禁 \href/\htmlClass 注入面）。
      trust: false,
    });
    el.replaceChildren(...htmlToNodes(html));
    // KaTeX 的失败面有两条形态（实测 0.16.11）：① 未定义的命令 = 红字内联
    // （`mathcolor="#cc0000"`，**不带** .katex-error 类）；② 结构性解析错 =
    // `<span class="katex-error">`。两条都算「可见错误态」，都要落状态读数 ——
    // 只看 .katex-error 会把 ① 误判成成功（本批首轮实证）。
    const errored = /katex-error|mathcolor="#cc0000"|color:#cc0000/i.test(html);
    if (errored) {
      el.classList.add('nf-math-error');
      el.setAttribute('data-math-error', t('fence.mathFailed'));
      setFenceState(el, MATH_KIND, 'error');
      return;
    }
    el.classList.remove('nf-math-error');
    setFenceState(el, MATH_KIND, 'done');
  } catch (e) {
    mathError(el, String((e && /** @type {any} */ (e).message) || e));
  }
}

/**
 * 显式水合（幂等）：把未排队的公式排进 fenceKit 的同一个 IntersectionObserver
 * （queueForViewport 单点），装载仍发生在首次渲染时。
 * @param {Element|Document} root
 */
export function hydrateMathIn(root) {
  const scope = /** @type {ParentNode} */ (root);
  scope.querySelectorAll('.nf-math:not([data-math-state])').forEach((el) => {
    queueForViewport(/** @type {HTMLElement} */ (el), MATH_KIND);
  });
}

/** 全局注册（fenceKit 的 body 级 observer 会把新落 DOM 的公式排进队列）。 */
export function armMathHydrate() {
  registerFence(MATH_KIND);
}

/** 主题翻转：清掉错误着色读数（KaTeX 产物本身跟 CSS 变量走，无需重渲）。 */
export function bumpMathGeneration() {
  document.querySelectorAll('.nf-math[data-math-state="error"]').forEach((el) => {
    el.classList.remove('nf-math-error');
  });
}
