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

let menuEl = null;

/** Hide the open menu (if any). Safe to call unconditionally. */
export function hidePopupMenu() {
  if (menuEl) { menuEl.remove(); menuEl = null; }
}

/** Is a menu currently open? (Additive read-only probe, 2026-09-18 msgmenu batch.)
 *  Consumer = `messages.js::escClose`: the menu owns Escape while it is open
 *  (it closes on Escape via the capture-phase listener below), so the chat
 *  modal's own Escape handling must yield that first press instead of
 *  acting on it (which would close the window / exit selection in the same
 *  keystroke). Zero behaviour change for existing consumers. */
export function isPopupMenuOpen() { return !!menuEl; }

/** 触屏长按批（2026-10-31，mobileLayer 口径）：长按松手时浏览器会补发一个合成
 *  click（落在触点上）——document 捕获期的全局关闭监听会在菜单刚弹出的一瞬间
 *  把它关掉。openQuoteMenuForRow 长按腿以 opts.swallowTouchClick 开窗：窗口内
 *  的合成 click 被忽略（菜单保住），窗口外行为逐字不变。桌面右键路径不传该
 *  旗标 ⇒ 零行为变化。 */
let _swallowClicksUntil = 0;

/**
 * Show a popup menu at (x, y).
 * @param {number} x clientX
 * @param {number} y clientY
 * @param {Array<{label: string, danger?: boolean, onClick?: () => void}>} items
 * @param {{swallowTouchClick?: boolean}} [opts]
 */
export function showPopupMenu(x, y, items, opts) {
  hidePopupMenu();
  if (!items || items.length === 0) return;
  if (opts && opts.swallowTouchClick) _swallowClicksUntil = Date.now() + 500;
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
    btn.textContent = item.label;
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      hidePopupMenu();
      item.onClick?.();
    });
    menuEl.appendChild(btn);
  }
  document.body.appendChild(menuEl);

  // Keep on-screen (same adjustment as the explorer context menu).
  const rect = menuEl.getBoundingClientRect();
  if (rect.right > window.innerWidth) menuEl.style.left = Math.max(0, x - rect.width) + 'px';
  if (rect.bottom > window.innerHeight) menuEl.style.top = Math.max(0, y - rect.height) + 'px';
}

// Global dismiss: any click / scroll / Escape / resize closes the menu.
// Capture phase so the menu closes before the click reaches its target.
// （吞窗：触屏长按腿刚开窗时跳过这一次合成 click —— 见上方 _swallowClicksUntil。）
document.addEventListener('click', (e) => {
  if (Date.now() < _swallowClicksUntil) { e.stopPropagation(); e.preventDefault(); return; }
  hidePopupMenu();
}, true);
document.addEventListener('contextmenu', (e) => {
  // A right-click outside the menu closes it; menu items handle themselves.
  if (menuEl && !(e.target instanceof Element && menuEl.contains(e.target))) hidePopupMenu();
}, true);
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  // 2026-09-18 msgmenu 批（缺陷修复，零行为变化对外）：菜单**开着**时这一下 Esc
  // 归菜单独占 —— 不 stopPropagation 的话，宿主弹窗自己的 Esc 处理器（bubble 阶段）
  // 会在菜单已被本处理器关掉之后**同一击**再跑一次 ⇒ 聊天窗被一起关掉
  // （实测：右键出菜单 → Esc → 菜单与窗同时消失）。既有唯一消费者（联系人行菜单）
  // 不在任何弹窗里 ⇒ 该行对其零影响。
  if (menuEl) e.stopPropagation();
  hidePopupMenu();
}, true);
window.addEventListener('resize', hidePopupMenu);
window.addEventListener('blur', hidePopupMenu);
