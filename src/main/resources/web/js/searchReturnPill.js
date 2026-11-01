// searchReturnPill.js — 搜索跳转「回到跳转前位置」胶囊（作者令 2026-11-01）。
//
// 现状（弹窗退场批①）：搜索 → 点结果 → 主会话滚动定位 + 闪击高亮。作者要的
// 后半句：跳转落地后出现一颗胶囊，点击平滑滚回跳转前正在读的位置（作者口中
// 的「回到开头」= 回到你刚才读到的地方）。
//
// ── 样式逐字复用 ─────────────────────────────────────────────────────────
// 胶囊 = 现役「↓ N 条新消息」胶囊同一颗家族（utils.js ensureScrollPill 创建的
// `.scroll-new-pill`，chat.css 同名段）——本模块零新 CSS、零新色：直接挂同一
// class，落位公式同 utils.js positionScrollPill（#input-area 高 + 12px），仅
// 多挂 `data-nf-search-return="1"` 作 QA 断言面与「本胶囊 ≠ 新消息胶囊」的
// 查询区分符（↓N 在场时本胶囊再上抬 40px，避免同槽玻璃叠影）。
//
// ── 锚点契约（真锚，不假装）───────────────────────────────────────────────
// 跳转前位置 = 视口顶缘穿越行（msgScrollAnchor.firstVisibleMessage 同语义）中
// 第一条**可再寻址**的行 + 它当时相对聊天容器顶缘的视觉偏移。可寻址身份二档：
//   · 行级 data-msg-id（chat.js 行诞生即挂的引用寻址面）；
//   · 行内 duration-badge 的 data-ts（chatSearch.js#scrollToMessage 同款兜底，
//     ts=0 视为无身份——tool/agent/ask 归一 ts=0 的既有口径）。
// 回位 = 把锚行带回**捕获时的视口相对位置**（rowRect.top − chatRect.top 差值，
// 两侧同坐标系、不依赖 offsetParent 链）。折叠展开 / 翻页 prepend / 补丁就地
// 替换等流重建都会改行高与绝对 scrollTop，视口相对差值是唯一稳定语义。
//
// 锚行在点击时**现场重解析**；解析失败（消息被删 / 流重建丢弃 / 会话已切）⇒
// 诚实降级：胶囊文案换「回到最新」（dataset.state='latest'，QA 断言面），点击
// 滚到底部——绝不滚到一个假装正确的位置。锚行被逐出由 childList 观察器在胶囊
// 可见期盯守，降级文案即时换挡，不做点击后才改口的马后炮。
//
// 跨会话跳转不设胶囊：锚点住在另一条会话的流里，切回即整树重建（resetChat
// 清空 #chat），任何「回去」都是重放不可保真的位置 ⇒ 直接不出现（诚实缺席
// 优于虚假锚点）。会话切换（nebflow-session-change，sidebar.js 派发）⇒ 胶囊
// 即隐。
//
// ── 消失时机（作者令三点）────────────────────────────────────────────────
// ① 点击回位后；② 用户手动滚离（wheel / touchmove / 键盘滚行键，以及 scroll
//    事件——跳转落地滚动本身豁免：scrollend 或数值到位判「程序滚动已终结」，
//    不支持 scrollend 的引擎回落 1.2s 时限；到位后的任何 scroll 都算用户滚离）；
//    ③ 再次新跳转 = 新捕获 + 新落地（armReturnPill 冻结当次锚快照，旧胶囊原地
//    换锚）。
//
// 滚动纪律：胶囊不可见期本模块**零监听**（wheel/touch/scroll 只在可见期挂，
// 隐藏即摘）；不调用 updateScrollSnapped（B4 单探针纪律不受扰），scroll 腿只
// 读一个 scrollTop 数值与 pendingTop 比较，不做第二份贴底判定。

import state from './state.js';
import { t } from './i18n.js';
import { chatViews } from './chatView.js';

/** @typedef {{ msgId: string, ts: string, session: string, offsetFromChatTop: number }} ReturnAnchor */
/** @typedef {ReturnAnchor & { pendingTop: number, shownAt: number, arrived: boolean }} ArmedAnchor */

/** 最近一次捕获的跳转前锚（null = 本次跳转不设胶囊）。 */
let captured = /** @type {ReturnAnchor | null} */ (null);

/** 当前在挂胶囊的冻结快照（armReturnPill 时定格；后续捕获不回写它）。 */
let activeAnchor = /** @type {ArmedAnchor | null} */ (null);

/** @type {HTMLElement | null} 胶囊节点（懒建，驻 #main，跨跳转复用）。 */
let pillEl = null;

/** 可见期监听器的成组摘除句柄（null = 胶囊不可见，零监听）。 */
let detachVisible = null;

const RETURN_ATTR = 'data-nf-search-return';

// ── 锚行寻址 ─────────────────────────────────────────────────────────────

/** 行的再寻址身份：行级 data-msg-id 优先，缺则行内 duration-badge 的 data-ts
 *  （scrollToMessage 同款兜底；'0' 归一为无身份，tool/agent/ask 既有口径）。 */
function rowIdentity(row) {
  const msgId = row.dataset.msgId || '';
  if (msgId) return { msgId, ts: '' };
  const badge = row.querySelector('[data-ts]');
  const ts = badge instanceof HTMLElement ? badge.dataset.ts || '' : '';
  if (ts && ts !== '0') return { msgId: '', ts };
  return null;
}

/** 锚身份 → 当前行（现场重解析；流重建后仍在 ⇒ 真锚成立）。 */
function resolveRow(chat, rec) {
  if (rec.msgId) {
    const hit = chat.querySelector(`[data-msg-id="${CSS.escape(rec.msgId)}"]`);
    if (hit) return hit;
  }
  if (rec.ts) {
    const badge = chat.querySelector(`[data-ts="${CSS.escape(rec.ts)}"]`);
    const row = badge?.closest('.row, .tool-card');
    if (row) return row;
  }
  return null;
}

/** 锚是否仍然可解析（可见期降级换挡 / 点击回位共用一份判定）。 */
function anchorLive(rec) {
  const chat = chatViews.primary?.dom?.chat;
  if (!chat || state.activeSessionId !== rec.session) return false;
  const row = resolveRow(chat, rec);
  return !!(row && row.isConnected);
}

/**
 * 捕获跳转前锚点（jumpToResult 开场调用，先于任何 DOM / 滚动变更）。
 * 跨会话跳转（res.sessionId ≠ 当前会话）⇒ 主动清锚：另一条会话流里的位置
 * 不设胶囊（见头注「真锚」段）。
 * @param {{ sessionId?: string }} res 命中结果（wrapItem 恒带 sessionId）
 */
export function captureReturnAnchor(res) {
  captured = null;
  if (res.sessionId && res.sessionId !== state.activeSessionId) return;
  const chat = chatViews.primary?.dom?.chat;
  if (!chat) return;
  const chatTop = chat.getBoundingClientRect().top;
  for (const node of chat.children) {
    const row = /** @type {HTMLElement} */ (node);
    if (!(row.classList.contains('row') || row.classList.contains('tool-card'))) continue;
    const rect = row.getBoundingClientRect();
    if (rect.bottom <= chatTop + 1) continue; // 整行在视口顶缘以上，看不见
    const id = rowIdentity(row);
    if (!id) continue;                        // 身份缺位 ⇒ 找下一条（不锚假行）
    captured = {
      msgId: id.msgId,
      ts: id.ts,
      session: /** @type {string} */ (state.activeSessionId),
      offsetFromChatTop: rect.top - chatTop,
    };
    return;
  }
}

// ── 胶囊本体 ─────────────────────────────────────────────────────────────

function ensurePill() {
  if (pillEl && pillEl.isConnected) return pillEl;
  const host = document.getElementById('main');
  if (!host) return null;
  const el = document.createElement('button');
  el.type = 'button';
  el.className = 'scroll-new-pill hidden'; // 现役新消息胶囊同类（chat.css 同名段）
  el.setAttribute(RETURN_ATTR, '1');
  el.setAttribute('aria-live', 'polite');
  el.dataset.state = 'anchor';
  el.addEventListener('click', onPillClick);
  host.appendChild(el);
  pillEl = el;
  return el;
}

/** 落位：#input-area 上缘 + 12px（= utils.js positionScrollPill 同式）；↓N
 *  胶囊在场时再上抬 40px，两颗玻璃胶囊不同槽。 */
function positionPill(el) {
  const msgPillVisible = !!document.querySelector(
    `#main .scroll-new-pill:not(.hidden):not([${RETURN_ATTR}])`);
  const inputArea = document.getElementById('input-area');
  el.style.bottom = `${(inputArea ? inputArea.offsetHeight : 54) + 12 + (msgPillVisible ? 40 : 0)}px`;
}

function setLabel(el) {
  const degraded = el.dataset.state === 'latest';
  const label = degraded ? t('chat.backToLatest') : t('chat.backToPrevPosition');
  if (el.textContent !== label) el.textContent = label;
}

/**
 * 跳转落地（flashRow 定位成功）时唤起：捕获了锚 ⇒ 出胶囊并冻结本次锚快照。
 * @param {number} pendingTop flashRow 平滑滚动的目标 scrollTop（到位豁免用）
 */
export function armReturnPill(pendingTop) {
  hideReturnPill(); // 先摘旧挂载（重复 arm = 换锚重进场，nf-pill-in 重放）
  const snap = captured;
  if (!snap) return;
  const el = ensurePill();
  if (!el) return;
  activeAnchor = { ...snap, pendingTop, shownAt: Date.now(), arrived: false };
  // 现场已解析不到 ⇒ 开场即挂降级文案（不假装能回到旧位置）。
  el.dataset.state = anchorLive(activeAnchor) ? 'anchor' : 'latest';
  // QA 断言面：锚身份上墙（data-msg-id / data-ts）——验收侧可独立验证
  // 「真锚」＝该身份在 #chat 里解析得到一行。
  if (snap.msgId) el.dataset.msgId = snap.msgId; else delete el.dataset.msgId;
  if (snap.ts) el.dataset.ts = snap.ts; else delete el.dataset.ts;
  setLabel(el);
  positionPill(el);
  el.classList.remove('hidden');
  attachWhileVisible(el);
}

/** 隐藏并摘净可见期监听（幂等；本模块的常驻足迹 = 零）。 */
export function hideReturnPill() {
  activeAnchor = null;
  if (detachVisible) { detachVisible(); detachVisible = null; }
  if (pillEl) pillEl.classList.add('hidden');
}

// ── 点击回位 ─────────────────────────────────────────────────────────────

function onPillClick() {
  const rec = activeAnchor;
  hideReturnPill(); // 先隐先摘监听：回位滚动自身不得触发「用户滚离」判定
  if (!rec) return;
  const chat = chatViews.primary?.dom?.chat;
  if (!chat || state.activeSessionId !== rec.session) return;
  const row = resolveRow(chat, rec);
  if (row && row.isConnected) {
    // 把锚行带回捕获时的视口相对位置（两侧同坐标系，见头注「锚点契约」）。
    const chatRect = chat.getBoundingClientRect();
    const rowRect = row.getBoundingClientRect();
    const max = Math.max(0, chat.scrollHeight - chat.clientHeight);
    const target = chat.scrollTop + (rowRect.top - chatRect.top) - rec.offsetFromChatTop;
    chat.scrollTo({ top: Math.max(0, Math.min(max, target)), behavior: 'smooth' });
  } else {
    // 锚已不在流里 ⇒ 诚实降级「回到最新」（文案在观察器腿早已换挡）。
    chat.scrollTo({ top: chat.scrollHeight, behavior: 'smooth' });
  }
}

// ── 可见期监听（消失时机②：用户滚离；含降级换挡观察器）─────────────────

const NAV_KEYS = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' ', 'Spacebar']);

function attachWhileVisible(el) {
  const chat = chatViews.primary?.dom?.chat;
  if (!chat) { hideReturnPill(); return; }

  const onScroll = () => {
    if (!activeAnchor) return;
    // 落地滚动豁免：未到位 ⇒ 不是用户滚离。到位判定 = scrollTop 与 flashRow
    // 目标的纯数值比较（无第二份贴底几何探针）。到位后（scrollend 或数值到位）
    // 的任何 scroll 事件才算用户滚离。
    if (Math.abs(chat.scrollTop - activeAnchor.pendingTop) <= 2) {
      activeAnchor.arrived = true;
      return;
    }
    if (!activeAnchor.arrived && Date.now() - activeAnchor.shownAt < 1200) return;
    hideReturnPill();
  };
  // scrollend = 程序化平滑滚动确定终结（长距离跳转平滑段可超 1.2s —— 只靠
  // 时限会把「还在飞」误判成「用户滚离」，胶囊落地即消失）。不支持 scrollend
  // 的引擎回落到上面的 1.2s 时限 + 数值到位判定。
  const onScrollEnd = () => {
    if (!activeAnchor) return;
    if (Math.abs(chat.scrollTop - activeAnchor.pendingTop) <= 2) activeAnchor.arrived = true;
  };
  const onUserScrollGesture = () => hideReturnPill();
  const onKey = (e) => { if (NAV_KEYS.has(e.key)) hideReturnPill(); };
  const onLocale = () => { if (activeAnchor) setLabel(el); };

  // 降级换挡：流重建逐出锚行 ⇒ 文案当场换「回到最新」（不点击后才改口）。
  let recheckQueued = false;
  const mo = new MutationObserver(() => {
    if (!activeAnchor || recheckQueued) return;
    recheckQueued = true;
    queueMicrotask(() => {
      recheckQueued = false;
      if (!activeAnchor) return;
      el.dataset.state = anchorLive(activeAnchor) ? 'anchor' : 'latest';
      setLabel(el);
    });
  });
  mo.observe(chat, { childList: true });

  chat.addEventListener('scroll', onScroll, { passive: true });
  chat.addEventListener('scrollend', onScrollEnd, { passive: true });
  chat.addEventListener('wheel', onUserScrollGesture, { passive: true });
  chat.addEventListener('touchmove', onUserScrollGesture, { passive: true });
  window.addEventListener('keydown', onKey);
  window.addEventListener('locale-changed', onLocale);

  detachVisible = () => {
    chat.removeEventListener('scroll', onScroll);
    chat.removeEventListener('scrollend', onScrollEnd);
    chat.removeEventListener('wheel', onUserScrollGesture);
    chat.removeEventListener('touchmove', onUserScrollGesture);
    window.removeEventListener('keydown', onKey);
    window.removeEventListener('locale-changed', onLocale);
    mo.disconnect();
  };
}

// 会话切换永久监听（幂等）：切会话 = 流整树重建，锚已失效 ⇒ 胶囊即隐。
window.addEventListener('nebflow-session-change', () => hideReturnPill());
