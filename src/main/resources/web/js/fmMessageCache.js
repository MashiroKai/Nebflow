// fmMessageCache.js — ⑨ L2 好友消息持久缓存（localStorage）。
//
// 作者 2026-09-12 07:40 落盘口径：消息**持久化落盘、像微信一样** —— v1 介质取
// `localStorage`（不取 sessionStorage、不取不落盘）。本模块是该层的唯一属主，
// 只被 `messages.js`（好友消息面）与 `persistence.js`（淘汰顺序）消费。
//
// 为什么是「版本号核对」而不是「内容哈希」：消息只追加、无删除/撤回
// （方案 §2.3 根因），此时哈希核对正确退化形式就是服务端水位核对 —— 会话列表
// 自带的 `lastMessage.id` 与消息的 `id` 就是免费的服务端水位，`after=<水位>`
// 的 keyset 游标（`store.rs list_messages: id > after ORDER BY id ASC`）即可
// 增量补齐。
//
// 三条纪律（违反即 bug）：
//  1. **零顶层副作用**：模块求值不得碰 localStorage —— release 产物
//     （`scripts/build-web.mjs`，esbuild define + DCE）会把好友激活路径整条剥除；
//     顶层键/副作用会变成剥离包里的残留死代码。
//  2. **键走 `key()` 品牌命名空间**（`branding.js`），与 ⑨-6 的裸键迁移同源。
//  3. **按 acct 分区**：账号换了 ⇒ 分区不匹配 ⇒ 一律当空缓存（绝不跨账号串数据）。
//
// 上限为**保守估值 · 待生产数据复核**（方案 §7.1 U-⑨4，作者 2026-09-12 预授权
// 逐条口径），本文件是这些数值的唯一落点，调用方不得自选新数值。

import { key } from './branding.js';

/** localStorage 槽位（品牌命名空间；`nebflow_fm_msg_cache` today）。 */
export const CACHE_KEY = key('fm_msg_cache');

/** 结构版本 —— 不匹配即整体丢弃重建（无迁移，好友域此前无消息缓存）。 */
export const SCHEMA_VERSION = 1;

// ── ⑨-4 上限（保守估值 · 待生产数据复核；本文件唯一落点）────────────
/** 每会话保留的消息条数。 */
export const MSGS_PER_CONV = 50;
/** 总会话数上限（LRU by `lastMessage.createdAt` 淘汰）。 */
export const MAX_CONVS = 30;
/** 总字节上限。计量口径 = `JSON.stringify(...).length`（与
 *  `persistence.js emergencyCacheCleanup` 的 2MB 判据同一口径）。 */
export const MAX_BYTES = 512 * 1024;
/** 条目新鲜度 TTL：超时 ⇒ 条目仍可作首屏渲染（持久缓存语义），但标记
 *  `fresh:false`，调用方据此走「可疑 ⇒ 多补一页」的校验分支。 */
export const TTL_MS = 5 * 60 * 1000;

/** 增量同步单页条数 = 每会话上限（不新造数值）。 */
export const SYNC_PAGE = MSGS_PER_CONV;
/** 增量同步最多翻页数：4 页 × 50 = 200 = 既有尾窗带宽（messages.js
 *  HISTORY_WINDOW=200）—— 即「增量补齐的带宽上限不超过今天一次尾窗」。 */
export const MAX_SYNC_PAGES = 4;

// ── 内部状态 ─────────────────────────────────────────────
/** 当前账号分区（'' = 未登录/账号未知 ⇒ 读写全部关闭，绝不落盘）。 */
let acct = '';
/** 当前分区的已解码副本（内存缓存，避免每次读都 JSON.parse 全量）。 */
let mem = null;
/** localStorage 不可用 / 配额写失败标记（只读降级：不抛、不阻断 UI）。 */
let degraded = false;

/** 写入白名单：只落渲染/转发真正消费的字段，绝不整对象透传。 */
function slim(m) {
  if (!m || typeof m !== 'object') return null;
  /** @type {Record<string, unknown>} */
  const out = { id: m.id, senderId: m.senderId, kind: m.kind, body: m.body || '', createdAt: m.createdAt };
  if (m.origin !== undefined) out.origin = m.origin;
  if (m.agentSent !== undefined) out.agentSent = m.agentSent;
  return out;
}

function idOf(m) {
  const n = Number(m && m.id);
  return Number.isFinite(n) ? n : 0;
}

function sizeOf(s) {
  try { return JSON.stringify(s).length; } catch { return 0; }
}

function emptyStore() {
  return { v: SCHEMA_VERSION, acct, convs: /** @type {Record<string, any>} */ ({}) };
}

function readRaw() {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

/** 当前分区的存储视图（结构/分区不匹配 ⇒ 当空缓存，不迁移）。 */
function store() {
  if (mem) return mem;
  const raw = readRaw();
  if (!raw || raw.v !== SCHEMA_VERSION || raw.acct !== acct || !raw.convs || typeof raw.convs !== 'object') {
    mem = emptyStore();
    return mem;
  }
  mem = { v: SCHEMA_VERSION, acct, convs: raw.convs };
  return mem;
}

function write(s) {
  if (degraded) return;
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(s));
  } catch {
    // 配额不足：本模块是纯缓存 —— 先丢自己，绝不让好友缓存挤掉会话缓存
    // （方案 §2.3「淘汰顺序」：pruneAndRetrySetSessions 剪的是会话缓存，
    // 主聊天被伤不可接受）。
    degraded = true;
    mem = emptyStore();
    try { localStorage.removeItem(CACHE_KEY); } catch { /* non-critical */ }
  }
}

/** 淘汰：先按会话数（LRU by `lastMessage.createdAt`），再按总字节。
 *  `keepId` = 本次刚写入的会话，永不被本次淘汰删除（它才是当前工作集）。 */
function enforceCaps(s, keepId) {
  const lsOf = (id) => Number(s.convs[id] && s.convs[id].lastMessageAt) || 0;
  const lruOrder = () => Object.keys(s.convs).filter(id => id !== keepId).sort((a, b) => lsOf(a) - lsOf(b));

  // (a) 会话数上限
  if (Object.keys(s.convs).length > MAX_CONVS) {
    for (const id of lruOrder()) {
      if (Object.keys(s.convs).length <= MAX_CONVS) break;
      delete s.convs[id];
    }
  }
  // (b) 字节上限
  if (sizeOf(s) <= MAX_BYTES) return;
  for (const id of lruOrder()) {
    delete s.convs[id];
    if (sizeOf(s) <= MAX_BYTES) return;
  }
  // 单条目自身超上限（理论上不可能：50 × body 上限 2000 字符）——仍以封顶优先。
  if (sizeOf(s) > MAX_BYTES) delete s.convs[keepId];
}

// ── 分区 / 生命周期 ──────────────────────────────────────

/** 设置账号分区。跨分区调用 = 分区边界：内存副本立即丢弃，绝不串账号。
 *  `''` = 账号未知（未登录）⇒ 读写全部关闭。
 *  account 键形态由调用方决定（neblink.js 用 `deviceId|email` 复合键：
 *  deviceId 或 email 任一变化都视为换账号）。 */
export function setCacheAccount(next) {
  const v = String(next || '');
  if (v === acct) return;
  acct = v;
  mem = null;
  degraded = false;
}

export function getCacheAccount() { return acct; }

/** 登出清除（neblink.js 登出链挂靠点）：整槽删除 + 分区归零。 */
export function clearMessageCache() {
  acct = '';
  mem = null;
  degraded = false;
  try { localStorage.removeItem(CACHE_KEY); } catch { /* non-critical */ }
}

/** 只删条目、保留分区（会话被删/拉黑等失效场景）。 */
export function dropConversation(conversationId) {
  if (!acct || !conversationId) return;
  const s = store();
  if (!s.convs[String(conversationId)]) return;
  delete s.convs[String(conversationId)];
  write(s);
}

/** 淘汰顺序挂靠点（`persistence.js`）：好友消息缓存是纯缓存且已封顶 512 KB，
 *  在会话缓存需要清理时**先丢它**。返回是否删掉了东西。 */
export function dropFriendMessageCache() {
  let had = false;
  try { had = localStorage.getItem(CACHE_KEY) !== null; } catch { /* non-critical */ }
  mem = null;
  if (had) { try { localStorage.removeItem(CACHE_KEY); } catch { /* non-critical */ } }
  return had;
}

// ── 读写 ─────────────────────────────────────────────────

/**
 * 读一个会话的缓存条目。
 * @param {string} conversationId
 * @returns {{msgs: any[], watermark: number, fetchedAt: number, fresh: boolean}|null}
 *   null = 无可用缓存（未登录 / 无条目 / 空条目）。
 */
export function loadConversation(conversationId) {
  if (!acct || !conversationId || degraded) return null;
  const e = store().convs[String(conversationId)];
  if (!e || !Array.isArray(e.msgs) || e.msgs.length === 0) return null;
  const fetchedAt = Number(e.fetchedAt) || 0;
  const watermark = Number(e.watermark) || 0;
  // 无可信 keyset 水位（数值 id 一条都没见到）⇒ 当无缓存：`after=0` 在服务端
  // 语义是「从最早开始」而不是「从最新开始」，拿它当增量锚会拉回最旧的窗口。
  if (watermark <= 0) return null;
  return {
    msgs: e.msgs.slice(),
    watermark,
    fetchedAt,
    fresh: (Date.now() - fetchedAt) <= TTL_MS,
  };
}

/**
 * 写一个会话的缓存条目（按每会话上限截尾）。
 * @param {string} conversationId
 * @param {any[]} msgs 已加载消息（升序；含乐观发送的临时 id 会被过滤掉）
 * @param {{watermark?: number, lastMessageAt?: number}} [opts]
 *   watermark = 服务端 keyset 水位（已见到过的最大 id，含被截掉的旧条目）；
 *   lastMessageAt = LRU 键（`lastMessage.createdAt` 的毫秒值）。
 */
export function saveConversation(conversationId, msgs, opts = {}) {
  if (!acct || !conversationId || !Array.isArray(msgs) || degraded) return;
  const kept = [];
  for (const m of msgs) {
    const id = m && m.id;
    // 只落服务端已确认的消息：无 id / 乐观临时 id（`fm-tmp-*`）不落盘。
    // id 值域不设类型约束（服务端为数值，mock 面可能为字符串），但水位
    // watermark 只认数值 —— keyset 游标 `after=<id>` 本就是数值协议。
    if (id === undefined || id === null || id === '' || String(id).startsWith('fm-tmp-')) continue;
    const s = slim(m);
    if (!s) continue;
    kept.push(s);
  }
  const trimmed = kept.slice(-MSGS_PER_CONV);
  if (trimmed.length === 0) return;
  const s = store();
  s.convs[String(conversationId)] = {
    msgs: trimmed,
    watermark: Math.max(Number(opts.watermark) || 0, ...trimmed.map(idOf)),
    lastMessageAt: Number(opts.lastMessageAt) || 0,
    fetchedAt: Date.now(),
  };
  enforceCaps(s, String(conversationId));
  write(s);
}

/** 诊断/测试读数：当前分区的会话数与序列化字节数。 */
export function cacheStats() {
  const s = store();
  return { acct, convs: Object.keys(s.convs).length, bytes: sizeOf(s) };
}
