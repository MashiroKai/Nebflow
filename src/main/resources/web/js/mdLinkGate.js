// mdLinkGate.js — 外部消息 markdown 外链的**唯一**二次确认处置点
// （fm-md-social 批，2026-11-02 19:30 作者令「好友/群聊/设备里的聊天也要支持渲染
//  markdown」；A5 判据 = 点击行为实测）。
//
// 为什么单独一个模块：三类会话面（好友/群聊/设备）与主会话不一样——这三个面里
// 的正文来自**外部**（对端好友 / 群里任何人 / 另一台设备），链接点一下直接跳走
// 是作者既有偏好里明确要慎重的「外跳动作」。渲染器（utils.js safe 档）已经把
// href 收敛到 http/https/mailto 白名单，本模块只管**导航前的确认**：
//   · `window.__showConfirm`（modal.js 既有确认框，全站单一确认语言）在场 ⇒
//     确认 → `window.open(..., 'noopener,noreferrer')`；取消 ⇒ 什么都不做；
//   · 确认框缺席 ⇒ **不导航**（fail closed：绝不无确认直跳外部站点）。
//
// 单点纪律：事件委托挂在气泡容器上（`.fm-msg-md`），不逐锚绑 listener ——
// 气泡内容由 innerHTML 整体重建，逐锚绑定会掉；容器本身不重建。
// 消费点：messages.js `bodyEl`（好友/群/设备三面同一处）。

import { t } from './i18n.js';

/**
 * 处置一次 md 外链点击。非 md-ext 锚 ⇒ 不做任何事（交回浏览器/上层）。
 * @param {MouseEvent} e
 * @returns {boolean} true = 本模块已接管（调用方无需再处理）
 */
export function handleMdExtClick(e) {
  const hit = e.target instanceof Element ? e.target.closest('a.md-ext') : null;
  if (!hit) return false;
  // 只有左键主点击与中键参与；右键（contextmenu）与修饰键组合不拦（浏览器
  // 自身的「新标签打开」语义不构成一次无确认导航：用户已显式选择新窗口）。
  e.preventDefault();
  const href = hit.getAttribute('href') || '';
  if (!/^https?:\/\//i.test(href) && !/^mailto:/i.test(href)) return true; // 纵深防御：协议再过一遍
  const open = () => {
    try { window.open(href, '_blank', 'noopener,noreferrer'); } catch { /* popup blocked */ }
  };
  if (typeof window.__showConfirm !== 'function') return true; // fail closed
  const host = (() => { try { return new URL(href).host; } catch { return href.slice(0, 60); } })();
  window.__showConfirm(t('messages.mdLinkTitle'), t('messages.mdLinkConfirm', { host }), open, {
    tone: 'neutral',
    confirmLabel: t('messages.mdLinkOpen'),
  });
  return true;
}

/** 把上面那条委托绑到一个气泡容器上（幂等：同容器重复调用只绑一次）。 */
export function bindMdExtGate(container) {
  if (!container || container.dataset.mdGateBound === '1') return;
  container.dataset.mdGateBound = '1';
  const h = (/** @type {MouseEvent} */ e) => { handleMdExtClick(e); };
  container.addEventListener('click', h);
  // 中键（auxclick）同样不许绕过确认；右键菜单不在本闸范围。
  container.addEventListener('auxclick', h);
}
