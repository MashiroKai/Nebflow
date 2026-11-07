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

/** 已注册的围栏族名（接线读数：验收据此断言「水合/主题换图腿确实armed」）。 */
export function fenceKindNames() {
  return kinds.map((k) => k.name);
}

/** 写/清状态属性（`null` = 清）。
 *  ⚠ 清必须走 removeAttribute —— `delete el.dataset['mermaid-state']` 是**空操作**：
 *  DOMStringMap 的删除器只认「受支持的属性名」（camelCase，`dataset.mermaidState`），
 *  带连字符的下标访问读得到、写得到，删不掉。mermaid-viewer-stability 批实测：
 *  旧写法让 `requeueSettledFences` 清不掉状态 ⇒ 重排队循环一个元素都没排上 ⇒
 *  主题翻转后图仍是旧配色、`hydrateFencesIn` 的 `:not([state])` 选择器永远不命中。 */
export function setFenceState(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind, /** @type {string|null} */ state) {
  if (state === null) el.removeAttribute(kind.stateAttr);
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
  syncFigureButton(el, false);
  el.style.removeProperty('min-height');   // 重排队期间的槽高钉子随内容换代一并撤
  const body = fenceBody(el, bodyClass);
  if (body) body.style.removeProperty('min-height');
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

/* ── 图槽稳定（mermaid-viewer-stability 批 2026-11-06）───────────────────
   作者报障逐字：「canvas渲染的框图…渲染的时候有可能会导致canvas窗口异常 突然变大
   变小」。实测（tests/mermaid-viewer-stability.spec.mjs 的 before 读数）：
     · 外层（#canvas-panel / #main / #chat / .canvas-tab-pane / 文档滚动器）在图
       渲染前后**零变化** —— 跳变不在外壳；
     · 跳变在图槽本身：`.canvas-md-viewer` 高度 40 → 406.5 → 1608.5 px 在 2 帧内
       连跳三档；图槽此前无任何高度上界，一张纵向长图可以把文档/卡片撑到数千 px。
   所以稳定面 = **图槽**：① 槽高加硬上界（CSS 单点，见 mermaid.css 的
   --nf-figure-max-h，允许槽内滚动 —— 不裁图）；② 已知纵横比时把槽高提前钉住
   （aspect-ratio），重渲染/主题切换**零高度变化**；③ 重排队不再塌回骨架（在
   原地换图），消除「先变小再变大」的第二段跳变。 */

/** 从渲染产物里读内禀宽高（viewBox 优先，其次 width/height 属性）。
 *  只读数值，不做任何布局测量 —— 因此没有「量了又写、写了又被量到」的回环面。
 *  @param {Node|null} node
 *  @returns {{w: number, h: number}|null} */
export function figureSize(node) {
  const el = /** @type {Element|null} */ (node && /** @type {any} */ (node).nodeType === 1 ? node : null);
  if (!el || !el.getAttribute) return null;
  const vb = (el.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
  if (vb.length === 4 && vb[2] > 0 && vb[3] > 0) return { w: vb[2], h: vb[3] };
  const w = parseFloat(el.getAttribute('width') || '');
  const h = parseFloat(el.getAttribute('height') || '');
  if (w > 0 && h > 0) return { w, h };
  return null;
}

/** 图槽“可点开看大图”的角标（唯一创建点，全部围栏族共用）。
 *  只在成功态出现；错误态/重排队时摘掉（错误面本身已经是可读内容，不该再挂大图钮）。
 *  真钮（button）⇒ 键盘可 Tab/Enter，不靠 SVG 上的伪交互。 */
function syncFigureButton(/** @type {HTMLElement} */ el, /** @type {boolean} */ on) {
  const existing = /** @type {HTMLButtonElement|null} */ (el.querySelector(':scope > .nf-figure-open'));
  if (!on) { if (existing) existing.remove(); return; }
  if (existing) return;
  const btn = h('button', {
    type: 'button',
    class: 'nf-figure-open',
    'aria-label': t('fence.openViewer'),
    title: t('fence.openViewer'),
  });
  // 图标走 CSS mask（见 mermaid.css `.nf-figure-open::before`），**不在块里多放
  // 一枚 <svg>**：图槽「恰好一个 svg = 那一张图」是既有验收（rich-render ③、
  // mermaid-md R1 按索引取 SVG）依赖的不变量 —— 内联 SVG 图标会把 els[1] 变成
  // 24x24 的图标，实测让 sequenceDiagram 的尺寸读数变成 13x13。
  btn.appendChild(h('span', { class: 'nf-figure-open-ico', 'aria-hidden': 'true' }));
  el.appendChild(btn);
}

/**
 * 成功态收尾：挂产物 + 落状态。
 * @param {HTMLElement} el
 * @param {FenceKind} kind
 * @param {Node} node
 * @param {{aspect?: string|null}} [opts] aspect = 已算出的 `w / h` 字符串，只作
 *   **读数**（`--nf-fig-ar`）落盘，供验收与后续批次消费。
 */
export function fenceDone(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind, /** @type {Node} */ node, opts) {
  if (opts && opts.aspect) el.style.setProperty('--nf-fig-ar', opts.aspect);
  const body = fenceBody(el, kind.bodyClass);
  if (body) body.replaceChildren(node);
  el.classList.remove('nf-fence-error');
  el.classList.add('nf-fence-done');
  // 重排队期间钉的槽高钉子在此撤销：新内容已经就位，钉子留着会把块撑在旧高上。
  el.style.removeProperty('min-height');
  setFenceState(el, kind, 'done');
  syncFigureButton(el, true);
}

/**
 * 重排队（主题翻转 / 重新水合）：**原地换图**，不塌回骨架。
 *
 * 旧实现（`fenceSkeleton` 覆写 body）会把已渲染的图换成一个 56px 骨架 —— 读者看到
 * 的是「图先缩没、再重新长出来」，即报障里「突然变大变小」的第二段。现在改为：
 *   ① 把当前槽高钉成块的 min-height（换图期间外层高度恒定）；
 *   ② body 里的旧图原样留着（新图落定后被 replaceChildren 原子替换）；
 *   ③ 只清状态属性让 observer 重新排队。
 * 新旧图比例一致时（同源同主题键以外的情况）槽高全程零变化；比例不同时也只有
 * 一次落定，不会出现「先塌后长」。
 * @param {HTMLElement} el
 * @param {FenceKind} kind
 */
export function fenceRequeueInPlace(/** @type {HTMLElement} */ el, /** @type {FenceKind} */ kind) {
  const body = fenceBody(el, kind.bodyClass);
  if (body) {
    const h0 = body.getBoundingClientRect().height;
    if (h0 > 0) el.style.minHeight = Math.round(h0) + 'px';
  }
  el.classList.remove('nf-fence-done', 'nf-fence-error');
  el.removeAttribute('data-fence-error');
  setFenceState(el, kind, null);
  queued.delete(el);
  hydrateFencesIn(el, kind.name);
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

/** 主题翻转：把所有**已落定**的围栏在原位重新排队（渲染器读新主题）。
 *  各围栏族自管 in-flight 代号（gen），旧代写回被丢弃。
 *  原位 = 不塌回骨架（见 fenceRequeueInPlace 头注：塌回骨架才是「突然变小」的来源）。
 *  @param {string[]} [only] 限定的围栏族名（省略 = 全部族）。主题换图只对
 *    自带配色的族有意义（mermaid / svg）；数学公式与主题无关，别跟着白重排一遍。 */
export function requeueSettledFences(only) {
  for (const kind of kinds) {
    if (only && !only.includes(kind.name)) continue;
    const sel = kind.selector + '[' + kind.stateAttr + '="done"], ' + kind.selector + '[' + kind.stateAttr + '="error"]';
    document.querySelectorAll(sel).forEach((el) => {
      fenceRequeueInPlace(/** @type {HTMLElement} */ (el), kind);
    });
  }
}

/* ── 主题换图接线（mermaid-viewer-stability 批）──────────────────────────
   实测缺口：`requeueSettledFences` / `bumpMermaidGeneration` / `bumpSvgGeneration`
   此前是**零调用点的死代码** —— 系统主题翻转后，已落定的图仍留着旧主题产物
   （暗档里坐着亮色纸面图）。本函数是它唯一的接线点：翻转时先让各族丢弃在飞渲染
   （bumpers），再原位重排队。
   幂等：重复 arm 只补一个 fencer，不叠监听。 */
const themeBumpers = new Set();
let themeArmed = false;
let themeRequeues = 0;
/** 主题换图腿的实际触发次数（验收读数：证明接线在位，而不是「看起来没坏」）。 */
export function themeRequeueCount() { return themeRequeues; }
/**
 * @param {() => void} [bump] 该围栏族的「丢弃在飞渲染」钩子（幂等注册）
 */
export function armThemeRequeue(bump) {
  if (bump) themeBumpers.add(bump);
  if (themeArmed) return;
  if (typeof window === 'undefined' || !window.matchMedia) return;
  themeArmed = true;
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const onChange = () => {
    themeRequeues += 1;
    for (const b of themeBumpers) { try { b(); } catch (e) { /* 族自管 */ } }
    requeueSettledFences(['mermaid', 'svg']);
  };
  if (mq.addEventListener) mq.addEventListener('change', onChange);
  else if (mq.addListener) mq.addListener(onChange);
}
