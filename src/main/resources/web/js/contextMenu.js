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

/**
 * Show a popup menu at (x, y).
 * @param {number} x clientX
 * @param {number} y clientY
 * @param {Array<{label: string, danger?: boolean, onClick?: () => void}>} items
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
document.addEventListener('click', () => hidePopupMenu(), true);
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
