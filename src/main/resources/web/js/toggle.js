// toggle.js — Shared switch (toggle) component.
//
// 2026-09-05 插件面板体验批：先前存在两套互不相通的开关实现——
//   • .toggle（sidebar.js 设置页渲染区 / orbSettingsUI.js）：div + 手造色值
//     （#22c55e 绿 / #fff 旋钮），双主题不自适应；
//   • .cfg-toggle（sidebar.js MCP 卡，settings-cleanup 支删除该用点）：button，
//     var(--color-primary) 单色。
// 本组件是唯一后续形态：插件面板（即时接入）与设置页（下游
// sidebar-restructure 节点接入，届时替换上面两套旧实现）同 class 同样式。
//
// Exported contract (settings page MUST adopt the exact same shape):
//   toggleHTML(options) → string
//     options: { on = false, label = '', title = '', id = '', disabled = false,
//                attrs = '' }
//     → `<button type="button" class="nb-toggle[ on][ disabled]" role="switch"
//        aria-checked="true|false" aria-label=label title=title [attrs]>`
//     `attrs` is a pre-escaped string of extra attributes for caller hooks
//     (e.g. `data-plugin-switch="name"`), keeping the class contract intact.
//     Knob is drawn by CSS (::after) — no child elements, one class.
//   bindToggle(root, onChange) → HTMLElement[]
//     Binds every .nb-toggle inside root. Fires on click (real <button>, so
//     Space and Enter both produce native click — keyboard operable by
//     construction, no duplicate keydown wiring). onChange(el, on) receives
//     the element and the NEW state AFTER the visual flip (optimistic); the
//     caller owns rollback via setToggleState.
//   setToggleState(el, on) → void
//     Programmatic state set (class + aria-checked). Used for rollback /
//     backend-truth convergence without re-rendering.
//
// Style: css/sidebar.css «nb-toggle — shared switch component» section.
// Sizes/animation/colors are ALL global tokens (--glass-control-* material,
// rgb(var(--sapphire) / α) accent, 0.15s control-transition convention) —
// zero hand-made color values, both themes auto-adapt (sapphire.css).

import { escapeHtml } from './utils.js';

/**
 * Render the shared switch markup.
 * @param {{ on?: boolean, label?: string, title?: string, id?: string,
 *            disabled?: boolean, attrs?: string }} [options]
 * @returns {string} HTML for one `<button class="nb-toggle" role="switch">`.
 */
export function toggleHTML(options = {}) {
  const { on = false, label = '', title = '', id = '', disabled = false, attrs = '' } = options;
  const cls = `nb-toggle${on ? ' on' : ''}${disabled ? ' disabled' : ''}`;
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
