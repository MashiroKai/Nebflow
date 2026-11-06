// toggle.js — 全站唯一开关（switch）组件。
//
// 2026-09-05 插件面板体验批：先前存在两套互不相通的开关实现——
//   • .toggle（sidebar.js 设置页渲染区 / orbSettingsUI.js）：div + 手造色值
//     （#22c55e 绿 / #fff 旋钮），双主题不自适应；
//   • .cfg-toggle（sidebar.js MCP 卡，settings-cleanup 支删除该用点）：button，
//     var(--color-primary) 单色。
//
// 2026-11-05 toggle-unify 批（作者裁定 19:55「A 胶囊滑块」）：本文件是**全站唯一**
// 开关的行为实现，收编了最后三族自画开关——todo 面 `.tfl-switch`、守护进程
// `.daemon-autostart`（input[checkbox]+track+thumb）、感知面板 `.pp-toggle`。
// 三族自有标记 / 自有 CSS / 自有 click 逻辑已删除，改为消费本文件导出；
// 门禁 `scripts/check-flat-buttons.mjs` 已把 toggle/switch 补进按钮面判据段，
// 再自画一套开关会因厚度道具 / 渐变 / 按压位移被判红。
//
// Exported contract (every switch on the site MUST adopt the exact same shape):
//   toggleHTML(options) → string
//     options: { on = false, label = '', title = '', id = '', disabled = false,
//                attrs = '' }
//     → `<button type="button" class="nb-toggle[ on][ disabled]" role="switch"
//        aria-checked="true|false" aria-label=label title=title [attrs]>`
//     `attrs` is a pre-escaped string of extra attributes for caller hooks
//     (e.g. `data-plugin-dispatch="name"`), keeping the class contract intact.
//     Knob is drawn by CSS (::after) — no child elements, one class.
//   createToggle(options) → HTMLButtonElement
//     DOM twin of toggleHTML with the SAME attribute table (one class builder,
//     `toggleClass()`), for call sites under the component-contract R5 gate
//     (bare innerHTML is forbidden).
//   bindToggle(root, onChange) → HTMLElement[]
//     Binds every .nb-toggle inside root. Fires on click (real <button>, so
//     Space and Enter both produce native click — keyboard operable by
//     construction, no duplicate keydown wiring). onChange(el, on) receives
//     the element and the NEW state AFTER the visual flip (optimistic); the
//     caller owns rollback via setToggleState.
//   setToggleState(el, on) → void
//     Programmatic state set (class + aria-checked). Used for rollback /
//     backend-truth convergence without re-rendering.
//   setToggleBusy(el, busy) → void
//     「不确定 / 进行中」态（aria-busy）：写操作在途时用。**不用**
//     aria-checked="mixed"——role=switch 的 aria-checked 只有真/假两档，mixed 会被
//     读屏读成 false（等于对用户宣称「已关」，而我们其实不知道）。aria-checked
//     保留最后一次已知值，视觉由 CSS 的虚线描边 + 圆钮降不透明表达。
//
// Style: css/sidebar.css «nb-toggle — shared switch component» section（唯一造型点）;
// tokens: css/stand-tokens.css «--nf-toggle-*» section（唯一数值出生地）。
// Geometry (作者裁定 A 组, 2026-11-05；居中改推导 2026-11-06 toggle-center 批):
// border-box track 40×22 (手机档 body.mobile-layer 51×31), radius 999px, knob 16×16
// (手机 27)。圆钮落位 = --nf-toggle-offset = 缝(3) − 描边(1) = 2px（padding box
// 坐标；缝 = (轨高 − 钮径)/2 由令牌算出），ON translateX(--nf-toggle-travel = 18px，
// 手机 20px) ⇒ **外缘四向缝恒相等**（桌面 3/3/3/3，手机 2/2/2/2）。
// 手填「内衬」是本批消灭的错误类别：absolute 伪元素的 top/left 相对 padding box，
// 手填值会把 1px 描边重复记账（手机档旧写法的 3/1 偏心即由此而来）。
// 机检：scripts/check-toggle-geometry.mjs（像素级：|上−下| ≤ 0.5、|左(关)−右(开)| ≤ 0.5，
// 桌面 + 手机 × 亮/暗）；令牌形态：scripts/check-toggle-unify.mjs T3。
// Motion: 180ms cubic-bezier(0.2,0,0,1) — 单调不过冲（无回弹段）；
// prefers-reduced-motion: reduce ⇒ 0.01ms 瞬间落位。

import { escapeHtml } from './utils.js';

/**
 * Render the shared switch markup.
 * @param {{ on?: boolean, label?: string, title?: string, id?: string,
 *            disabled?: boolean, attrs?: string }} [options]
 * @returns {string} HTML for one `<button class="nb-toggle" role="switch">`.
 */
export function toggleHTML(options = {}) {
  const { on = false, label = '', title = '', id = '', disabled = false, attrs = '' } = options;
  const cls = toggleClass(on, disabled);
  return `<button type="button" class="${cls}"${id ? ` id="${escapeHtml(id)}"` : ''}`
    + ` role="switch" aria-checked="${on ? 'true' : 'false'}"`
    + ` aria-label="${escapeHtml(label)}"`
    + (title ? ` title="${escapeHtml(title)}"` : '')
    + `${disabled ? ' disabled' : ''}`
    + `${attrs ? ` ${attrs}` : ''}></button>`;
}

/** The visual state flip shared by click handling and programmatic updates. */
export function setToggleState(el, on) {
  if (!el) return;
  el.classList.toggle('on', !!on);
  el.setAttribute('aria-checked', on ? 'true' : 'false');
}

/**
 * 「不确定 / 进行中」态开关（写操作在途时用）。
 *
 * 语义选择见文件头：用 `aria-busy` 而不是 `aria-checked="mixed"`。视觉面（虚线
 * 描边 + 圆钮 0.55 不透明）由 `.nb-toggle[aria-busy="true"]` 单点定义，
 * 本函数只写属性，不碰样式（防两处各画一套「忙碌态」）。
 *
 * @param {Element|null|undefined} el 目标开关
 * @param {boolean} busy true = 进入不确定态；false = 退出（清掉属性）
 */
export function setToggleBusy(el, busy) {
  if (!el) return;
  if (busy) el.setAttribute('aria-busy', 'true');
  else el.removeAttribute('aria-busy');
}

/**
 * DOM 形态的同一组件（`toggleHTML` 的孪生，**同一属主仍是本文件**）。
 *
 * 为什么需要第二个形态（三点入口批 2026-11-05）：组件契约门禁 R5 禁「裸 innerHTML」
 * ——调用方若只有字符串形态，就只能把字符串直接赋给 innerHTML（= 新增违规）。
 * 本函数的产出与 `toggleHTML` **逐属性同形**（同一 `nb-toggle` 类、同一 role/aria
 * 契约、旋钮仍由 CSS `::after` 画），只是走建 DOM 的路；两形态共用一个
 * `toggleClass()` 拼装点，禁各写一张属性表（防漂移）。
 *
 * @param {{ on?: boolean, label?: string, title?: string, id?: string,
 *            disabled?: boolean }} [options]
 * @returns {HTMLButtonElement}
 */
export function createToggle(options = {}) {
  const { on = false, label = '', title = '', id = '', disabled = false } = options;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = toggleClass(on, disabled);
  if (id) btn.id = id;
  btn.setAttribute('role', 'switch');
  btn.setAttribute('aria-checked', on ? 'true' : 'false');
  btn.setAttribute('aria-label', label);
  if (title) btn.title = title;
  if (disabled) btn.disabled = true;
  return btn;
}

/** 两形态共用的类名拼装点（唯一）。 */
function toggleClass(on, disabled) {
  return `nb-toggle${on ? ' on' : ''}${disabled ? ' disabled' : ''}`;
}

/** Bound-switch registry — WeakSet instead of expando properties so the
 *  file stays clean under the checkJs zero-new-errors gate. */
const boundSwitches = new WeakSet();

/**
 * Bind click handling for every .nb-toggle inside root.
 * @param {ParentNode} root — container to query within (e.g. a panel content el).
 * @param {(el: HTMLButtonElement, on: boolean) => void} [onChange]
 *        Called AFTER the optimistic visual flip; `on` is the NEW state.
 * @returns {HTMLElement[]} the bound switches.
 */
export function bindToggle(root, onChange) {
  if (!root) return [];
  // The component only ever renders <button> — assert it for checkJs so the
  // file stays under the zero-new-errors gate (TS won't narrow a compound
  // selector on its own).
  const switches = /** @type {HTMLButtonElement[]} */ ([...root.querySelectorAll('.nb-toggle')]);
  for (const el of switches) {
    if (boundSwitches.has(el)) continue; // idempotent — safe to re-bind after partial updates
    boundSwitches.add(el);
    el.addEventListener('click', () => {
      if (el.disabled) return;
      const on = !el.classList.contains('on');
      setToggleState(el, on); // optimistic flip — caller rolls back on failure
      onChange?.(el, on);
    });
  }
  return switches;
}
