// fmChatBridge.js — fm 会话桥（P12 电脑端聊天面板大一统 · 阶段 1 铺垫件）
//
// 目标：把好友/群/设备会话挂进主会话既有的多视图路由面（chatView.js 的
// chatViews 注册表），走通「conv ↔ ChatView」这一跳 —— 模式照搬 bgAgentPopup.js /
// flowAgentPopup.js 的先例（fakeDom 弹窗视图注册进 chatViews，让 ws 路由面能按
// 会话把帧派发进对应视图）。这是大一统的第一步**纯增件**：fm 三窗的渲染
// （fmComposer / fillBubble / 三发送腿）本阶段零触碰 —— 视图只是**接收**帧，
// 渲染接线属阶段 2+（候作者裁定）。
//
// 职责边界（阶段 1）：
//   · syncFmViews(conversations) —— 会话列表变化后对账：每个会话注册一枚
//     `fm-<kind>-<id>` 视图（fakeDom 指向隐藏容器，同 bgAgentPopup 的
//     getHiddenRoot 形态）；列表里消失的会话连视图带容器一并退役。
//   · fmFrameSink(msg) —— ws.js 对 `friend_event` 类型帧的 fan-out 通道
//     （setFmFrameSink 注入，规避 ws→messages 循环 import，与 askFrameSink /
//     flowStepInterceptor 同款）。帧按 conversationId 找到视图后派发给它：
//     写视图上的桥接日志（fmBridge journal）+ 通知订阅者。**非独占**分支：
//     messages.js 既有的 onFriendEvent 数据腿（未读/缓存/开窗 DOM）原样照跑，
//     本通道只做镜像派发，零行为差。
//   · onFmFrame(conversationId, cb) —— 订阅某会话的帧流（阶段 2+ 渲染管线的
//     接线缝；阶段 1 只有桥接 spec 消费）。
//
// 刻意的**不做**（阶段 1 = 行为不变）：
//   · view.sessionId 保持 null：findViewBySessionId()（约 40 个调用面）全部以
//     agent sessionId 为键，帧里也不存在 conversationId 形态的 sessionId ——
//     置 null 保证既有路由面对桥视图**不可达**（零交集可证）；conv↔view 的
//     权威映射在本模块的 fmViews Map（键 = conversationId）。阶段 2+ 若要接
//     sessionId 派发，需连帧契约一起设计，不在本批。
//   · view.visible = false：桥视图不接 DOM 渲染（容器恒隐藏），不参与
//     streamDispatchView 的可见门（fm 帧不走 chat.js 处理器）。
//   · 不 dispatch 到 ws.js 的 handlers 注册表：friend_event 的产品消费只有
//     messages.js 一处（onFriendEvent），重复派发 = 未读/缓存双计 —— 桥通道
//     只投递给本模块的订阅者。

import { h } from '../dom.js';
import { ChatView, chatViews } from '../chatView.js';
import { setFmFrameSink } from '../ws.js';

/** 合法会话档（messages.js conv.kind 的值域；缺席按 friend 读 —— 会话列表
 *  的好友行本来就不带 kind 字段，device/group 行显式携带）。 */
const VALID_KINDS = new Set(['friend', 'group', 'device']);

/**
 * conv → 视图 id（唯一映射点）：`fm-<kind>-<id>`。
 * @param {{ kind?: string, conversationId?: string|null }} conv
 * @returns {string}
 */
export function fmViewIdOf(conv) {
  const kind = conv && VALID_KINDS.has(conv.kind) ? conv.kind : 'friend';
  return `fm-${kind}-${conv ? conv.conversationId : ''}`;
}

/** conversationId → { view, container, listeners }。conv↔view 的权威映射。 */
/** @type {Map<string, {view: ChatView, container: HTMLElement, listeners: Set<(msg: any, view: ChatView) => void>}>} */
const fmViews = new Map();

// ── 隐藏宿主（bgAgentPopup.getHiddenRoot 同款形态）────────────────────────
// 阶段 1 视图不渲染 ⇒ 容器只需要「存在且不可见」。内联 display:none 落在宿主
// 一处（零 CSS 文件依赖、零类名泄漏面）；子容器自身无样式诉求。
let hiddenRoot = null;
function getHiddenRoot() {
  if (!hiddenRoot) {
    hiddenRoot = h('div', { class: 'fm-bridge-hidden', style: 'display: none' });
    document.body.appendChild(hiddenRoot);
  }
  return hiddenRoot;
}

/** fakeDom 形状 = bgAgentPopup.ensureStepView 同款（chat 必备，其余 null：
 *  阶段 2+ 接渲染时再按需补真引用）。 */
function makeFakeDom(container) {
  return {
    chat: container,
    input: null, sendBtn: null, stopBtn: null, attachBtn: null,
    attPreview: null, slashDropdown: null, queueBar: null,
    voiceBtn: null, voiceOverlay: null, voiceText: null,
    bgIndicatorEl: null, bgCountEl: null,
    bgDropdownEl: null, bgDropdownListEl: null,
    bgagentIndicatorEl: null, bgagentDropdownEl: null, bgagentDropdownListEl: null,
    sessionNameEl: null,
  };
}

/**
 * 取（或建）某会话的桥视图。视图 id 按 conv 当前 kind 推导 —— 服务端若修正
 * 会话档（kind 翻转）则就地迁移注册表键（容器与订阅者保留，journal 清零）。
 * @param {{ kind?: string, conversationId: string }} conv
 * @returns {{ view: ChatView, container: HTMLElement, listeners: Set<Function> }}
 */
function ensureFmView(conv) {
  const key = String(conv.conversationId);
  const wantId = fmViewIdOf(conv);
  const existing = fmViews.get(key);
  if (existing) {
    if (existing.view.id !== wantId) {
      // kind 翻转（病态但廉价防御）：换一枚新视图顶上，旧注册键不留尸。
      delete chatViews[existing.view.id];
      const migrated = buildEntry(conv, key, existing.container, existing.listeners);
      fmViews.set(key, migrated);
      return migrated;
    }
    return existing;
  }
  const container = h('div', { class: 'fm-bridge-chat', 'data-fm-conv': key });
  getHiddenRoot().appendChild(container);
  const entry = buildEntry(conv, key, container, new Set());
  fmViews.set(key, entry);
  return entry;
}

/** 建桥视图 + 注册进 chatViews（bgAgentPopup:434-450 同款次序）。 */
function buildEntry(conv, key, container, listeners) {
  const view = new ChatView(fmViewIdOf(conv), makeFakeDom(container));
  view.mounted = true;
  view.sessionId = null;   // 刻意留空 —— 见文件头「刻意的不做」（findViewBySessionId 零交集）
  view.visible = false;    // 阶段 1 纯管道：容器恒隐藏、不进可见门
  // 桥接 journal（读模型）：帧级派发的可观测面（桥接 spec 与阶段 2+ 消费）。
  (/** @type {{fmBridge?: {frameCount: number, lastFrame: any, lastFrameAt: number}}} */ (view)).fmBridge =
    { frameCount: 0, lastFrame: null, lastFrameAt: 0 };
  chatViews[view.id] = view;
  return { view, container, listeners };
}

/**
 * 会话列表对账（refreshConversations 出口调用）：出现 ⇒ 建视图；消失 ⇒ 退役
 * （注册表键 + 隐藏容器 + 订阅者一并清）。conversationId 缺席的行（如
 * openChatWithFriend 的 `__pending__` 临时行）跳过 —— 没有稳定键就不注册。
 * @param {Array<{ kind?: string, conversationId?: string|null }>} convs
 * @returns {void}
 */
export function syncFmViews(convs) {
  if (!Array.isArray(convs)) return;
  const seen = new Set();
  for (const conv of convs) {
    if (!conv || conv.conversationId === undefined || conv.conversationId === null || conv.conversationId === '') continue;
    const key = String(conv.conversationId);
    seen.add(key);
    // 上面四连守卫后 conversationId 必为非空串（checkJs 收窄不到，显式断言）。
    ensureFmView(/** @type {{ kind?: string, conversationId: string }} */ (conv));
  }
  for (const [key, entry] of [...fmViews]) {
    if (seen.has(key)) continue;
    delete chatViews[entry.view.id];
    entry.container.remove();
    entry.listeners.clear();
    fmViews.delete(key);
  }
}

/** 派发一帧进桥视图：journal 打点 + 通知订阅者（订阅者异常互不传染）。 */
function dispatchFrame(entry, msg) {
  const journal = entry.view.fmBridge;
  if (journal) {
    journal.frameCount += 1;
    journal.lastFrame = msg;
    journal.lastFrameAt = Date.now();
  }
  for (const cb of [...entry.listeners]) {
    try { cb(msg, entry.view); }
    catch (e) { console.error('[fm-bridge] listener error:', e && e.message); }
  }
}

/**
 * ws.js `friend_event` 帧 fan-out 通道（setFmFrameSink 注入）。
 * 按 conversationId 找桥视图：无视图（会话未注册）⇒ 静默返回 false，数据腿
 * 照旧独走；有视图 ⇒ journal + 订阅者派发，返回 true。**不碰 activeView、
 * 不进 handlers 注册表**（见文件头）——泛化按「帧带 conversationId 即投」，
 * 事件档（message_new / message_new_self / 后续新档）不作白名单。
 * @param {any} msg friend_event 帧（payload 已展平到帧顶层）
 * @returns {boolean} 是否有桥视图接了这一帧
 */
export function fmFrameSink(msg) {
  const key = msg && msg.conversationId !== undefined && msg.conversationId !== null
    ? String(msg.conversationId) : null;
  if (!key) return false;
  const entry = fmViews.get(key);
  if (!entry) return false;
  dispatchFrame(entry, msg);
  return true;
}

/**
 * 订阅某会话的帧流（阶段 2+ 渲染管线的接线缝）。
 * @param {string} conversationId
 * @param {(msg: any, view: ChatView) => void} cb
 * @returns {() => boolean} 注销函数（幂等）
 */
export function onFmFrame(conversationId, cb) {
  const entry = fmViews.get(String(conversationId));
  if (!entry) return () => false;
  entry.listeners.add(cb);
  return () => entry.listeners.delete(cb);
}

/** 读访问器：某会话的桥视图（无注册 ⇒ null）。桥接 spec / 阶段 2+ 消费。 */
export function fmViewFor(conversationId) {
  const entry = fmViews.get(String(conversationId));
  return entry ? entry.view : null;
}

// 模块装载即挂通道（messages.js import 本模块 ⇒ boot 期一次性生效；
// setter 注入形态与 askFrameSink / flowStepInterceptor 同律）。
setFmFrameSink(fmFrameSink);
