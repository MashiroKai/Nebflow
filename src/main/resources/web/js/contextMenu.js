// contextMenu.js — generic right-click popup menu (#290 A2A, addendum §1/§3).
// One component, two consumers: message bubbles (转发给 agent) and contacts
// friend rows (删除好友 / 加入黑名单 / 移出黑名单). Visuals reuse the
// .explorer-context-menu rule family (split.css) via the .fm-context-menu
// selector - zero new color tokens, same glass panel look.
//
// Usage:
//   import { showPopupMenu } from './contextMenu.js';
//   row.addEventListener('contextmenu', (e) => {
//     e.preventDefault();
//     showPopupMenu(e.clientX, e.clientY, [
//       { label: t('messages.forwardToAgent'), onClick: () => {} },
//       { label: t('contacts.menuDelete'), danger: true, onClick: () => {} },
//     ]);
//   });

// 弹窗退场批（作者令 2026-10-31②）：手机层右键/长按菜单 = 全屏动作表 + 左上返回。
import { mobileSheetBack } from './mobile/popSheet.js';
// keyboard-first 一期（2026-11-01）：菜单是「层」——打开时落焦进菜单、Tab 在
// 菜单内循环、关闭后把焦点还给触发元素。20+ 个调用方零改动（本文件对它们
// 的契约仍是 showPopupMenu(x, y, items)）。
import { pushLayer } from './focusTrap.js';

let menuEl = null;
/** @type {import('./focusTrap.js').Layer|null} */
let menuLayer = null;

/** Hide the open menu (if any). Safe to call unconditionally. */
export function hidePopupMenu() {
  // 先出栈（回焦到触发元素）再摘 DOM：反序会让回焦目标是一次性的游离节点。
  const l = menuLayer;
  menuLayer = null;
  if (l) l.release();
  if (menuEl) { menuEl.remove(); menuEl = null; }
}

/** Is a menu currently open? (Additive read-only probe, 2026-09-18 msgmenu batch.)
 *  Consumer = `messages.js::escClose`: the menu owns Escape while it is open
 *  (it closes on Escape via the capture-phase listener below), so the chat
 *  modal's own Escape handling must yield that first press instead of
 *  acting on it (which would close the window / exit selection in the same
 *  keystroke). Zero behaviour change for existing consumers. */
export function isPopupMenuOpen() { return !!menuEl; }

/**
 * Show a popup menu at (x, y).
 * @param {number} x clientX
 * @param {number} y clientY
 * @param {Array<{label: string, danger?: boolean, checked?: boolean, onClick?: () => void}>} items
 */
export function showPopupMenu(x, y, items) {
  hidePopupMenu();
  if (!items || items.length === 0) return;
  menuEl = document.createElement('div');
  menuEl.className = 'fm-context-menu';
  menuEl.setAttribute('role', 'menu');
  menuEl.style.left = x + 'px';
  menuEl.style.top = y + 'px';
  for (const item of items) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.setAttribute('role', 'menuitem');
    if (item.danger) btn.className = 'danger';
    // 值语义档（todo-mobile 批 2026-11-18）：列表选择器的**选中态**。作者令
    // 「标签必须有列表来选择」（条目多、要能一眼看出当前值）⇒ 当前项带钩 +
    // 无障碍态（menuitemradio + aria-checked）。既有调用方一律不传 checked
    // ⇒ 按钮的文字与属性逐字节不变（零回归）。
    if (item.checked) {
      btn.setAttribute('role', 'menuitemradio');
      btn.setAttribute('aria-checked', 'true');
      btn.textContent = `✓ ${item.label}`;
    } else {
      btn.textContent = item.label;
    }
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      hidePopupMenu();
      item.onClick?.();
    });
    menuEl.appendChild(btn);
  }
  document.body.appendChild(menuEl);
  // 弹窗退场批②：仅手机层注入左上返回钮（桌面 DOM 逐字节不变——friend-remark
  // 等桌面 spec 按 button 数/textContent 精确断言）。
  if (document.body.classList.contains('mobile-layer')) {
    mobileSheetBack(menuEl, hidePopupMenu);
  }

  // Keep on-screen (same adjustment as the explorer context menu).
  const rect = menuEl.getBoundingClientRect();
  if (rect.right > window.innerWidth) menuEl.style.left = Math.max(0, x - rect.width) + 'px';
  if (rect.bottom > window.innerHeight) menuEl.style.top = Math.max(0, y - rect.height) + 'px';

  // keyboard-first 一期：整张菜单入层栈。落焦 → 首项；Tab/Shift+Tab 在菜单内
  // 首尾相接；任意关闭路径（点外 / Esc / 选项点击 / resize / blur）都会经
  // hidePopupMenu → release 把焦点送回触发元素。
  // Esc 归层栈（keyboard-focus-audit 批 G1，2026-11-21 作者令「Esc 收进
  // focusTrap 层栈 onEscape」）：focusTrap 的 capture 处理器对栈顶声明了 onEscape
  // 的层先 preventDefault + stopPropagation 再调用 —— 与下方既有 capture 监听
  // 「菜单开着时这一击不传导给宿主弹窗」逐条等价（2026-09-18 msgmenu 批的语义
  // 由层栈承载，不再由本文件手写）。层栈尚未安装时无法入栈的退化路径见下方
  // keydown 兜底。
  menuLayer = pushLayer(menuEl, {
    initial: /** @type {HTMLElement|null} */ (menuEl.querySelector('button')),
    contain: true,
    name: 'context-menu',
    onEscape: () => hidePopupMenu(),
  });
}

// Global dismiss: any click / scroll / Escape / resize closes the menu.
// Capture phase so the menu closes before the click reaches its target.
document.addEventListener('click', () => hidePopupMenu(), true);
document.addEventListener('contextmenu', (e) => {
  // A right-click outside the menu closes it; menu items handle themselves.
  if (menuEl && !(e.target instanceof Element && menuEl.contains(e.target))) hidePopupMenu();
}, true);
// ── 行导航（G1，keyboard-focus-audit 批 2026-11-21）────────────────────────
// 口径逐条照抄既有下拉（dropdown.js 的行导航：↑↓ 循环、Home/End 首末、
// 当前项不在菜单内时按方向取首/末）。菜单开着时这组键归菜单：capture 阶段
// preventDefault + stopPropagation，避免宿主面（文件树 / 叠卡 / 列表）同时响应。
const MENU_ITEM_SEL = '[role="menuitem"], [role="menuitemradio"]';
function menuItems() {
  if (!menuEl) return [];
  return [...menuEl.querySelectorAll(MENU_ITEM_SEL)]
    .filter((n) => n instanceof HTMLElement && !n.hasAttribute('disabled') && !(n instanceof HTMLButtonElement && n.disabled));
}
document.addEventListener('keydown', (e) => {
  if (!menuEl) return;
  const k = e.key;
  if (k !== 'ArrowDown' && k !== 'ArrowUp' && k !== 'Home' && k !== 'End') return;
  const items = menuItems();
  if (!items.length) return;
  e.preventDefault();
  e.stopPropagation();
  const idx = items.indexOf(/** @type {HTMLElement} */ (document.activeElement));
  let next;
  if (k === 'Home') next = 0;
  else if (k === 'End') next = items.length - 1;
  else if (idx < 0) next = k === 'ArrowUp' ? items.length - 1 : 0;
  else next = k === 'ArrowUp' ? (idx - 1 + items.length) % items.length : (idx + 1) % items.length;
  items[next].focus();
}, true);
// Esc：唯一所有者 = focusTrap 层栈（见 showPopupMenu 的 onEscape）。语义与
// 2026-09-18 msgmenu 批等价：菜单开着时这一击不传导给宿主弹窗（focusTrap 对
// 栈顶 onEscape 先 preventDefault + stopPropagation 再调用）。
window.addEventListener('resize', hidePopupMenu);
window.addEventListener('blur', hidePopupMenu);
