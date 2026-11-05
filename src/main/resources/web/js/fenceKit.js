// fenceKit.js — 图表/富内容围栏的共享底座（rich-render 批 2026-11-05；作者令
// 2026-11-05 08:51「支持渲染 mermaid」+ 08:53「多用框图表格等可视化的输入，还有
// 什么可以直接支持的可视化输出？」+ 08:55「可以，试试内联svg」）。
//
// 为什么有这一层：三种围栏（```mermaid / ```svg / 数学占位）此前各自的懒渲染、
// 状态机、失败态会各写一遍 —— 本仓付过代价的正是「失败伪装成空态」（见 batch
// 报告 §6）：一份实现漏了错误分支，另一份不一定会漏。所以状态机与失败态
// 单点在这里，各围栏只提供 `render(el)`。
//
// ── 状态机（单一属性，运行期唯一真源）────────────────────────────────
//   （无属性） → 'pending'   排队（hydrate 同步打标，防 observer 重入）
//   'pending'  → 'loading'   进入视口、开始渲染
//   'loading'  → 'done'      成功（body 内 = 渲染产物）
//   'loading'  → 'error'     失败（body 内 = 错误行 + 原始源码，默认可见）
// 属性名按围栏族取（data-mermaid-state / data-svg-state / data-math-state），
// 既有 spec 与 CSS 依赖它，所以**不合并成一个通用属性**。
//
// ── 懒渲染与全局接线 ────────────────────────────────────────────────
// · 一个 IntersectionObserver（320px 前瞻）管所有围栏 —— 首个围栏进视口才渲染，
//   长文档里视口外的图零成本。
// · 一个 body 级 MutationObserver 管所有 markdown 面：共享管线（utils.js）把围栏
//   换成占位 div，落到任何面（聊天气泡 / Canvas md / wiki / flow 卡 / report 卡 /
//   popup）都自动 hydrate，不需要逐调用点接线。
// · 重入安全：状态属性在**任何 await 之前**同步写入；observer 只匹配
//   `:not([state])`。
//
// ── 落 DOM 纪律 ────────────────────────────────────────────────────
// 本文件零裸 innerHTML（component-contract R5）：错误态与骨架全部经 dom.js 的
// h() 构建。渲染产物（SVG）由各围栏族解析成节点后 appendChild。

import { h } from './dom.js';
import { t } from './i18n.js';

/**
 * @typedef {Object} FenceKind
 * @property {string} name 族名（读数列用）
 * @property {string} selector 占位块的 CSS 选择器
 * @property {string} stateAttr 状态属性名
 * @property {string} srcAttr 源码属性名
 * @property {string} bodyClass 产物容器类名（.nf-<族>-body）
 * @property {string} phClass 骨架类名（.nf-<族>-ph）
 * @property {(el: HTMLElement) => Promise<void>} render 渲染实现（自管 done/error）
 */

/** @type {FenceKind[]} */
const kinds = [];
/** @type {Map<Element, FenceKind>} */
const queued = new Map();
/** @type {IntersectionObserver|null} */
let viewportIO = null;
/** @type {MutationObserver|null} */
let autoMO = null;

/** 注册一个围栏族。重复注册同名族 = 以最后一次为准（幂等，防重复 arm 双跑）。 */
export function registerFence(/** @type {FenceKind} */ kind) {
  const at = kinds.findIndex((k) => k.name === kind.name);
  if (at >= 0) kinds[at] = kind;
  else kinds.push(kind);
}

/** 写/清状态属性（`null` = 清）。 */
export function setFenceState(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind, /** @type {string|null} */ state) {
  if (state === null) delete el.dataset[kind.stateAttr.replace(/^data-/, '')];
  else el.setAttribute(kind.stateAttr, state);
}

/** 围栏源码（占位块上的 data-* 属性，DOM 读回时实体已解码）。 */
export function fenceSource(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind) {
  return el.getAttribute(kind.srcAttr) || '';
}

/** 取 body 容器（各围栏族共用同一占位结构：.nf-<族>-body）。 */
export function fenceBody(/** @type {HTMLElement} */ el, /** @type {string} */ bodyClass) {
  const body = el.querySelector('.' + bodyClass);
  return /** @type {HTMLElement|null} */ (body);
}

/** 骨架占位（重新排队时回到加载形）。 */
export function fenceSkeleton(/** @type {HTMLElement} */ el, /** @type {string} */ bodyClass, /** @type {string} */ phClass) {
  const body = fenceBody(el, bodyClass);
  if (!body) return;
  body.replaceChildren(h('div', { class: phClass }));
}

/**
 * 失败态：**原始源码默认可见** + 一行可读错误。
 * 作者令原话（2026-11-05 08:51 第 6 条）：「解析失败/不支持的图类型时，回退显示
 * 原代码并给出可读的错误状态，不许静默吞成空白」。所以源码不藏在按钮后面 ——
 * 按钮是「收起」，不是「查看」（收起源码后仍可再展开）。
 * @param {HTMLElement} el
 * @param {FenceKind} kind
 * @param {string} bodyClass
 * @param {string} src 原始围栏源码
 * @param {Error|string} err
 */
export function fenceError(el, kind, bodyClass, src, err) {
  // 状态先落（body 缺席也必须离开 'loading' —— 悬挂的加载态就是「失败伪装成
  // 空白」的另一张脸）。
  el.classList.remove('nf-fence-done');
  el.classList.add('nf-fence-error');
  setFenceState(el, kind, 'error');
  el.setAttribute('data-fence-error', kind.name);
  const body = fenceBody(el, bodyClass);
  if (!body) return;
  const detail = String((err && /** @type {any} */ (err).message) || err || '').slice(0, 200);
  const pre = h('pre', { class: 'nf-fence-src' }, h('code', { text: src }));
  const btn = h('button', {
    type: 'button',
    class: 'nf-fence-srcbtn',
    text: t('fence.hideSource'),
    'on:click': () => {
      const hidden = pre.hasAttribute('hidden');
      if (hidden) pre.removeAttribute('hidden');
      else pre.setAttribute('hidden', '');
      btn.textContent = hidden ? t('fence.hideSource') : t('fence.showSource');
    },
  });
  body.replaceChildren(
    h('div', { class: 'nf-fence-errrow' }, [
      h('span', { class: 'nf-fence-errlabel', text: t('fence.renderFailed') }),
      h('span', { class: 'nf-fence-errdetail', text: detail }),
      btn,
    ]),
    pre,
  );
}

/** 成功态收尾：挂产物 + 落状态。 */
export function fenceDone(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind, /** @type {Node} */ node) {
  const body = fenceBody(el, kind.bodyClass);
  if (body) body.replaceChildren(node);
  el.classList.remove('nf-fence-error');
  el.classList.add('nf-fence-done');
  setFenceState(el, kind, 'done');
}

/**
 * 安全审查读数：剥离/降级发生过时在图上留一行**状态事实**（不是解释句）。
 * 作者令 2026-11-05 08:55：「审查不通过 ⇒ 回退成代码块 + 可见错误态，不许吞成
 * 空白」——净化通过但确有剥离时，同样不许静默。
 */
export function fenceNotice(/** @type {HTMLElement} */ el, /** @type {string} */ bodyClass, /** @type {string} */ text) {
  const body = fenceBody(el, bodyClass);
  if (!body) return;
  body.appendChild(h('div', { class: 'nf-fence-notice', text }));
}

/** 把元素排进视口队列（已排队/已渲染的不重复排）。 */
export function queueForViewport(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind) {
  if (queued.has(el)) return;
  queued.set(el, kind);
  setFenceState(el, kind, 'pending');
  if (!viewportIO) {
    viewportIO = new IntersectionObserver((entries) => {
      for (const en of entries) {
        if (!en.isIntersecting) continue;
        const target = /** @type {HTMLElement} */ (en.target);
        viewportIO?.unobserve(target);
        const k = queued.get(target);
        if (!k) continue;
        queued.delete(target);
        setFenceState(target, k, 'loading');
        Promise.resolve()
          .then(() => k.render(target))
          .catch((e) => fenceError(target, k, k.bodyClass, fenceSource(target, k), e));
      }
    }, { rootMargin: '320px 0px' });
  }
  viewportIO.observe(el);
}

/**
 * 在 `root`（含自身）里找出所有未排队的围栏并排队。
 * @param {ParentNode|null} root
 * @param {string} [only] 只处理指定族（省略 = 全部族）
 */
export function hydrateFencesIn(root, only) {
  if (!root) return;
  const scope = /** @type {Element} */ (root);
  for (const kind of kinds) {
    if (only && kind.name !== only) continue;
    const sel = kind.selector + ':not([' + kind.stateAttr + '])';
    if (scope.nodeType === 1 && scope.matches(sel)) queueForViewport(/** @type {HTMLElement} */ (scope), kind);
    scope.querySelectorAll(sel).forEach((el) => queueForViewport(/** @type {HTMLElement} */ (el), kind));
  }
}

/** ONE wiring point：body 级 childList observer，覆盖所有 markdown 面。 */
export function armFenceAutoHydrate() {
  if (autoMO || !window.MutationObserver) return;
  autoMO = new MutationObserver((records) => {
    for (const rec of records) {
      for (const node of rec.addedNodes) {
        if (node.nodeType !== 1) continue;
        hydrateFencesIn(/** @type {Element} */ (node));
      }
    }
  });
  autoMO.observe(document.body, { childList: true, subtree: true });
  // 已在文档里的围栏（首屏直接内联的 markdown）同样纳入。
  hydrateFencesIn(document.body);
}

/** 主题翻转：把所有**已落定**的围栏退回骨架重新排队（渲染器读新主题）。
 *  各围栏族自管 in-flight 代号（gen），旧代写回被丢弃。 */
export function requeueSettledFences() {
  for (const kind of kinds) {
    const sel = kind.selector + '[' + kind.stateAttr + '="done"], ' + kind.selector + '[' + kind.stateAttr + '="error"]';
    document.querySelectorAll(sel).forEach((el) => {
      const target = /** @type {HTMLElement} */ (el);
      target.classList.remove('nf-fence-done', 'nf-fence-error');
      target.removeAttribute('data-fence-error');
      setFenceState(target, kind, null);
      queued.delete(target);
      fenceSkeleton(target, kind.bodyClass, kind.phClass);
      hydrateFencesIn(target, kind.name);
    });
  }
}
