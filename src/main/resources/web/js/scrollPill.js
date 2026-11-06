// scrollPill.js — 会话面浮条胶囊的**唯一实现**（作者令 2026-11-06 07:39 逐字：
// 「我们消息框的回到底部的按钮不要自己造 要跟 新消息通知 搜索聊天记录跳转 使用同一套」）。
//
// ── 一族三消费（同一 DOM 结构 / 同一类名 `.scroll-new-pill` / 同一形态声明
//    css/chat.css / 同一令牌 / 同一动效 / 同一出现-消失语义）──────────────
//   ① 新消息通知  js/utils.js  `syncScrollPill`（主窗 + 每个 agent 弹窗，view 级计数）
//   ② 回到底部    js/utils.js 同上（无未读、只是距底 ≥ SHOW_AFTER_PX 时的同一枚）；
//                 好友/群/设备 js/messages.js `fmSyncFollowPill`（同一工厂）
//   ③ 搜索回跳    js/searchReturnPill.js（锚点/降级语义自带，形态与落位走本组件）
//
// **基准判定**（为什么以 `.scroll-new-pill` 为基准，而不是以「回到底部」为准）：
//   · 它先存在（新消息通知 2026-09 起、搜索回跳 2026-11-01 起，两者本就共用这一类名
//     与 chat.css 同名段；「回到底部」2026-11-05 才新造）；
//   · 它改动面更小：收编 = 删掉新造的那一套，基准侧零改动；
//   · 它被作者见过更多次（每轮对话都会出现 ↓N，搜索跳转天天用）。
//   反向路径（把通知/搜索改成新造那条）不在本批范围 —— 作者原话是「不要自己造」。
//
// ── 唯一形态声明 ────────────────────────────────────────────────────────
// 形态（玻璃材质 / 28px 高 / --ds-radius-pill / --ds-fs-hint / 横居中 / hover 与
// 按下的底色阶 / nf-pill-in 进场 / reduced-motion 关动效）**只在 css/chat.css 的
// `.scroll-new-pill` 段**；本文件不写任何内联外观，只写落位（bottom）一个几何量。
// 落位公式三消费同一式：视口矩形反算（手机层收编窗的 offsetParent 与桌面不同，
// offsetTop 算术会错位），`bottom = 宿主底缘 − 参照件顶缘 + 净空`。
//
// ── 出现/消失语义（计数型消费统一两条腿）───────────────────────────────
//   · 腿 A（未读）：离开底部期间到达了**真实新消息** ⇒ 「↓ N 条新消息」；
//   · 腿 B（拐点）：无未读、但距底 ≥ SHOW_AFTER_PX ⇒ 「回到底部」；
//   · 回到贴底（任何原因：本机发送 / 到达跟随 / 点击）⇒ 两腿同时收敛为隐藏。
//   搜索回跳消费不走 sync（它自己管出现时机：跳转落地才出、用户滚离即隐），
//   但仍复用同一套**落位 / 进场动画 / 点击原语**。

import { t } from './i18n.js';

/**
 * 拐点阈值（px，距底距离）。取值依据 = 主会话**实测**的单行消息行距
 * （`utils.js` NEAR_BOTTOM_PX 头注的实测表：单行气泡 44.47、行高 65.47、
 * **行距 75.47px**）。320 ≈ 4.2 行 ⇒「往上翻了四行以上」才给拐点键：
 *   · 低于 2 行（≤160）⇒ 手指一扫就弹，等于常驻，违背「不许常驻」；
 *   · 高于一屏（812/900）⇒ 用户已经彻底丢了上下文，键出现得太晚；
 *   · 320 也大于 AT_BOTTOM_PX（44）一个量级 ⇒ 与「仍在贴底」判定零重叠。
 */
export const SHOW_AFTER_PX = 320;

/** 「仍算贴底」的容差（px）—— 与 utils.js NEAR_BOTTOM_PX 同档；本组件自持一份
 *  只作 sync 的缺省判定（调用方可显式传 atBottom 复用调用方的单次探测）。 */
export const AT_BOTTOM_PX = 44;

/** 同宿主两枚胶囊（↓N 与搜索回跳同时在场）的堆叠净距（px）。 */
export const STACK_STEP = 40;

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

/** 同宿主已在场的「非堆叠位」胶囊数 → 堆叠位胶囊上抬量。
 *  只有挂 `stack:'above'` 的消费（搜索回跳）上抬；↓N/回到底部永不上抬 ——
 *  两侧同一个函数，不存在「谁先出现谁说了算」的不确定几何。 */
function stackLift(el, stack) {
  if (!stack || !el.parentElement) return 0;
  let n = 0;
  for (const sib of el.parentElement.querySelectorAll('.scroll-new-pill')) {
    if (sib === el || sib.classList.contains('hidden')) continue;
    if (sib.dataset.nfStack === 'above') continue;
    n += 1;
  }
  return n * STACK_STEP;
}

/** 在册胶囊（同宿主兄弟显隐变化 ⇒ 逐个重算落位）。
 *  为什么需要：堆叠位胶囊 park 时只能看见**当下**在场的兄弟；若另一枚是之后
 *  才出现的（滚离触发 ↓N/回到底部），堆叠位就停在旧 `bottom` 上与它重合 ——
 *  两枚同槽玻璃叠成一枚，点击命中也落到上面那枚。显隐两侧各触发一次重算，
 *  几何对「谁先出现」不敏感（顺序无关的同一结果）。 */
const livePills = new Set();
function reparkHost(host) {
  for (const p of livePills) if (p.el.parentElement === host) p.refresh();
}

/**
 * 装配一枚会话面胶囊（三消费共用）。
 *
 * @param {{
 *   host: HTMLElement,                            // 定位宿主（胶囊的落位参照面）
 *   scrollEl: HTMLElement,                        // 被滚动的消息流
 *   anchor?: () => (Element|null),                // 底部参照件（输入区/输入条/页脚）；其顶缘之上落位
 *   gap?: number,                                 // 与参照件顶缘的净空（缺省 12）
 *   onReturn?: () => void,                        // 「回到贴底」后置动作（宿主清未读计数）
 *   onActivate?: () => void,                      // 自定义点击行为（缺省 = 平滑贴底）
 *   labelFor?: (o: {count: number, state: string}) => string,  // 自定义文案
 *   stack?: 'above'|false,                        // 同宿主有另一枚时上抬一格
 *   attrs?: Record<string, string>,               // 额外属性（QA 断言面/语义标记）
 *   ariaLive?: 'polite'|'off',
 * }} opts
 * @returns {{el: HTMLElement, sync: (o: {count?: number, atBottom?: boolean}) => void,
 *            show: () => void, hide: () => void, refresh: () => void, label: () => void,
 *            scrollToBottom: (o?: {smooth?: boolean}) => void,
 *            count: () => number, destroy: () => void}}
 */
export function createScrollPill(opts) {
  const {
    host, scrollEl, anchor = null, gap = 12, onReturn = null,
    onActivate = null, labelFor = null, stack = false, attrs = null, ariaLive = 'polite',
  } = opts;
  if (!host || !scrollEl) throw new Error('scrollPill: host / scrollEl required');

  const el = document.createElement('button');
  el.type = 'button';
  el.className = 'scroll-new-pill hidden';
  el.setAttribute('aria-live', ariaLive);
  if (attrs) for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (stack === 'above') el.dataset.nfStack = 'above';
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
    el.style.bottom = `${Math.max(0, Math.round(box.bottom - top + gap + stackLift(el, stack)))}px`;
  }

  function paint() {
    const label = labelFor
      ? labelFor({ count, state: el.dataset.state || '' })
      : (count > 0 ? t('chat.newMessages', { n: count }) : t('chat.backToBottom'));
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
    reparkHost(host);   // 兄弟（堆叠位）跟着重排，两枚永不叠成一枚
  }

  function hide() {
    count = 0;
    if (!visible) return;
    visible = false;
    el.classList.add('hidden');
    reparkHost(host);
  }

  /**
   * 唯一同步点（滚动 / 到达计数 / 回底三路都汇到这里；幂等、自愈）。
   * @param {{count?: number, atBottom?: boolean}} o
   */
  function sync(o = {}) {
    if (typeof o.count === 'number') count = Math.max(0, o.count);
    const atBottom = o.atBottom === undefined ? bottomDistance(scrollEl) < AT_BOTTOM_PX : !!o.atBottom;
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

  // 点击腿：自定义行为优先（搜索回跳），否则 = 平滑贴底（回到底部 / ↓N）。
  el.addEventListener('click', () => { if (onActivate) onActivate(); else scrollToBottom(); });

  const api = {
    el,
    sync,
    show,
    hide,
    /** 重新落位（宿主布局变化：多行输入长高、键盘弹起、窗口改尺寸）。 */
    refresh() { if (visible) park(); },
    /** 重画文案（locale 切换 / 降级换挡）。 */
    label() { if (visible) paint(); },
    scrollToBottom,
    count: () => count,
    destroy() { el.remove(); visible = false; count = 0; livePills.delete(api); },
  };
  livePills.add(api);
  return api;
}
