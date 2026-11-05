// scrollBackPill.js — 「回到底部」胶囊：**四种会话面共用的唯一实现**
// （作者令 2026-11-05 18:11「然后主会话/群/设备/好友的消息 往上放太多了之后，应该
//  有一个胶囊按钮 点击可回到底部。正好我们这批要做的就是他们三者的统一」）。
//
// 消费面（四处，全部走本模块，禁第二份实现）：
//   ① 主会话  js/utils.js  syncScrollPill（view 级：主窗 + 每个 agent 弹窗各一枚）
//   ② 好友    js/messages.js renderChatModal（`.fm-modal` 内）
//   ③ 群      同上（同一窗骨架）
//   ④ 设备    同上（同一窗骨架）
//
// 与既有 `.scroll-new-pill`（chat.css，搜索返回胶囊 searchReturnPill.js 仍在用）的
// 关系（**有意分家，申报**）：那条胶囊的语义是「搜索跳转后返回原锚点」，横居中、
// 只在搜索态出现；本组件是「离开底部 ⇒ 回底」的通用拐点键，落右下角。两者几何锚点
// 不同（居中 vs 右下）⇒ 共类名会让搜索胶囊跟着横跳，故本组件自带类
// `.scroll-back-pill`（css/chat.css 同名段），不复用搜索胶囊的类。
//
// 显隐口径（两条腿，缺一不显）：
//   · 腿 A（未读）：离开底部期间到达了**真实新消息** ⇒ 显示「↓ N 条新消息」；
//   · 腿 B（拐点）：无新消息、但距底 ≥ SHOW_AFTER_PX ⇒ 显示「回到底部」。
// 回到底部（任何原因：本机发送 / 到达跟随 / 点击胶囊）⇒ 两腿同时收敛为隐藏。

import { t } from './i18n.js';

/**
 * 出现阈值（px，距底距离）。取值依据 = 主会话**实测**的单行消息行距
 * （`utils.js` NEAR_BOTTOM_PX 头注的实测表：单行气泡 44.47、行高 65.47、**行距
 * 75.47px**）。320 ≈ 4.2 行 ⇒「往上翻了四行以上」才给拐点键：
 *   · 低于 2 行（≤160）⇒ 手指一扫就弹，等于常驻，违背「不许常驻」；
 *   · 高于一屏（812/900）⇒ 用户已经彻底丢了上下文，键出现得太晚；
 *   · 320 也大于 NEAR_BOTTOM_PX（44）一个量级 ⇒ 与「仍在贴底」判定零重叠。
 */
export const SHOW_AFTER_PX = 320;

/** 距底距离（px）；`el` 非法 ⇒ Infinity（视为离底最远，永远显）。 */
export function bottomDistance(el) {
  if (!el) return Infinity;
  return el.scrollHeight - el.scrollTop - el.clientHeight;
}

/** 是否「已离开底部够远」（腿 B 的唯一判据）。 */
export function scrolledUpBeyond(el, threshold = SHOW_AFTER_PX) {
  return bottomDistance(el) >= threshold;
}

/** prefers-reduced-motion 读点（每次点击读一次：用户可运行中切换）。 */
function reduceMotion() {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; }
}

/**
 * 装配一枚回底胶囊。
 *
 * @param {{
 *   host: HTMLElement,                        // 定位宿主（pill 的落位参照面）
 *   scrollEl: HTMLElement,                    // 被滚动的消息流
 *   anchor?: () => (Element|null),            // 底部参照件（输入区/输入条）；其顶缘之上落位
 *   gap?: number,                             // 与参照件顶缘的净空（缺省 12）
 *   onReturn?: () => void,                    // 「回到贴底」后置动作（宿主清未读计数）
 *   ariaLive?: 'polite'|'off',
 * }} opts
 * @returns {{el: HTMLElement, sync: (o: {count?: number, atBottom?: boolean}) => void,
 *            hide: () => void, refresh: () => void,
 *            scrollToBottom: (o?: {smooth?: boolean}) => void,
 *            destroy: () => void, count: () => number}}
 */
export function createScrollBackPill(opts) {
  const {
    host, scrollEl, anchor = null, gap = 12, onReturn = null, ariaLive = 'polite',
  } = opts;
  if (!host || !scrollEl) throw new Error('scrollBackPill: host / scrollEl required');

  const el = document.createElement('button');
  el.type = 'button';
  el.className = 'scroll-back-pill hidden';
  el.setAttribute('aria-live', ariaLive);
  host.appendChild(el);

  let count = 0;
  let visible = false;

  /** 落位：参照件顶缘之上；无参照件 ⇒ 宿主底缘之上。
   *  ⚠ 不用 offsetTop 算术：手机层会话窗会被 navController 收编进 `.mobile-page`
   *  （offsetParent 与桌面不同），几何一律按**视口矩形**反算，两侧同一公式。 */
  function park() {
    const box = (el.offsetParent || host).getBoundingClientRect();
    const refEl = anchor ? anchor() : null;
    const top = refEl ? refEl.getBoundingClientRect().top : box.bottom;
    el.style.bottom = `${Math.max(0, Math.round(box.bottom - top + gap))}px`;
  }

  function paint() {
    const label = count > 0
      ? t('chat.newMessages', { n: count })
      : t('chat.backToBottom');
    if (el.textContent !== label) el.textContent = label;
    el.setAttribute('aria-label', label);
    el.dataset.unread = String(count);
  }

  function show() {
    if (visible) return;
    visible = true;
    paint();
    park();
    el.classList.remove('hidden');
  }

  function hide() {
    count = 0;
    if (!visible) return;
    visible = false;
    el.classList.add('hidden');
  }

  /**
   * 唯一同步点（滚动 / 到达计数 / 回底三路都汇到这里；幂等、自愈）。
   * @param {{count?: number, atBottom?: boolean}} o
   */
  function sync(o = {}) {
    if (typeof o.count === 'number') count = Math.max(0, o.count);
    const atBottom = o.atBottom === undefined ? bottomDistance(scrollEl) < 44 : !!o.atBottom;
    if (atBottom) { count = 0; hide(); return; }
    if (count > 0 || scrolledUpBeyond(scrollEl)) show();
    else hide();
    if (visible) paint();
  }

  /** 贴底（点击胶囊 / 本机发送共用）。`smooth` 缺省 = 尊重 reduced-motion。 */
  function scrollToBottom(o = {}) {
    const smooth = o.smooth === undefined ? !reduceMotion() : !!o.smooth;
    count = 0;
    visible = false;
    el.classList.add('hidden');
    if (smooth && typeof scrollEl.scrollTo === 'function') {
      scrollEl.scrollTo({ top: scrollEl.scrollHeight, behavior: 'smooth' });
    } else {
      scrollEl.scrollTop = scrollEl.scrollHeight;
    }
    if (onReturn) onReturn();
  }

  el.addEventListener('click', () => scrollToBottom());

  return {
    el,
    sync,
    hide,
    /** 重新落位（宿主布局变化：多行输入长高、键盘弹起、窗口改尺寸）。 */
    refresh() { if (visible) park(); },
    scrollToBottom,
    count: () => count,
    destroy() { el.remove(); visible = false; count = 0; },
  };
}
