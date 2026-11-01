// mobile/msgLongPress.js — 手机交互层：消息长按 → 上下文菜单（附件批 2026-10-31，
// 作者令 10-31 09:04「手机端 长按消息要像电脑端右键一样 弹出引用 然后引用要支持
// 你发的文件 图片等」）。
//
// 架构口径（mobileLayer 铁律）：本模块只在 body.mobile-layer 激活期注册（bootstrap.js
// 激活谱系），桌面视口零注册零监听；菜单构建**不新造** —— 单点出口 =
// msgQuote.openQuoteMenuForRow（与桌面右键同一份 items/同壳 showPopupMenu）。
//
// 手势契约（与滚动/滑动不冲突是硬验收）：
//   · touchstart **不 preventDefault** —— 原生滚动/橡皮筋完全保留；
//   · 长按计时 LONG_PRESS_MS，期间 touchmove 位移 > MOVE_CANCEL_PX 即取消
//     （手指在滑动 = 想滚动，不是想弹菜单）；
//   · touchend/touchcancel 先于计时器 ⇒ 取消（普通轻点零打扰）；
//   · 计时到点 ⇒ openQuoteMenuForRow(touch 坐标, {swallowTouchClick:true}) ——
//     contextMenu.js 的吞窗吃掉松手时补发的合成 click（否则菜单弹出一瞬即被
//     全局 click 关闭监听器关掉）；
//   · iOS Safari 长按也会补发 contextmenu —— 行上 attachQuoteMenu 的既有监听
//     会再开一次同款菜单（同坐标重开 = 无害幂等），无需压制。
//
// QA 探针：window.__nfMobileLongPress = { active }（结构读数，不依赖视觉推断）。

import { openQuoteMenuForRow } from '../msgQuote.js';

const LONG_PRESS_MS = 480;
const MOVE_CANCEL_PX = 10;

/** 触摸监听选项：注册/拆除两侧逐字同值（removeEventListener 只认 capture 位，
 *  passive 在拆除侧被忽略——用同一个常量保证不会写出「注册 A、拆除 B」）。 */
const TOUCH_LISTENER_OPTS = /** @type {AddEventListenerOptions} */ ({ passive: true });

/** @type {boolean} */
let active = false;
/** @type {{row: Element, x: number, y: number, timer: number} | null} */
let pending = null;

function rowOf(target) {
  if (!(target instanceof Element)) return null;
  // 引用块/附件卡等行内交互子面长按也归行菜单（微信口径：按在消息上就行）；
  // 行必须挂过引用菜单（__nfQuoteDesc 在场 = 可引用行）。
  const row = target.closest('#chat .row');
  return row && (/** @type {{__nfQuoteDesc?: unknown}} */ (row)).__nfQuoteDesc ? row : null;
}

function cancelPending() {
  if (!pending) return;
  clearTimeout(pending.timer);
  pending = null;
}

/** @returns {() => void} teardown（bootstrap.js 激活谱系数组序拆除）。 */
export function activateMsgLongPress() {
  if (active) return () => {};
  active = true;

  const onTouchStart = (e) => {
    cancelPending();
    if (e.touches.length !== 1) return;
    const row = rowOf(e.target);
    if (!row) return;
    const t = e.touches[0];
    const x = t.clientX;
    const y = t.clientY;
    pending = {
      row,
      x,
      y,
      timer: setTimeout(() => {
        pending = null;
        // 触点坐标弹菜单；吞窗旗标 = 松手合成 click 不关菜单（契约见文件头）。
        openQuoteMenuForRow(row, x, y, { swallowTouchClick: true });
      }, LONG_PRESS_MS),
    };
  };

  const onTouchMove = (e) => {
    if (!pending || e.touches.length !== 1) return;
    const t = e.touches[0];
    if (Math.hypot(t.clientX - pending.x, t.clientY - pending.y) > MOVE_CANCEL_PX) cancelPending();
  };

  const onTouchEnd = () => cancelPending();

  // 委托挂 document、过滤 #chat：手机层各页面互斥 .live，#chat 被 navController
  // 收编进页面体（节点同一，委托不丢）；弹窗/nf-static 副窗不在 #chat 下 ⇒ 手机层
  // 长按语义只落主聊天区（桌面右键口径不变，副窗不翻案）。
  document.addEventListener('touchstart', onTouchStart, TOUCH_LISTENER_OPTS);
  document.addEventListener('touchmove', onTouchMove, TOUCH_LISTENER_OPTS);
  document.addEventListener('touchend', onTouchEnd, TOUCH_LISTENER_OPTS);
  document.addEventListener('touchcancel', onTouchEnd, TOUCH_LISTENER_OPTS);

  (/** @type {any} */ (window)).__nfMobileLongPress = { get active() { return active; } };

  return () => {
    active = false;
    cancelPending();
    document.removeEventListener('touchstart', onTouchStart, TOUCH_LISTENER_OPTS);
    document.removeEventListener('touchmove', onTouchMove, TOUCH_LISTENER_OPTS);
    document.removeEventListener('touchend', onTouchEnd, TOUCH_LISTENER_OPTS);
    document.removeEventListener('touchcancel', onTouchEnd, TOUCH_LISTENER_OPTS);
    delete (/** @type {any} */ (window)).__nfMobileLongPress;
  };
}
