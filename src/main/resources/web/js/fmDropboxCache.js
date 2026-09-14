// fmDropboxCache.js — ⑩ L2 Dropbox 消息持久缓存（localStorage）。
//
// 作者 2026-09-14 17:21 令：「加载好友消息，dropbox 消息，点进去的加载速度还是比较
// 慢，才会显示。……消息都是本地上，打开应该能够直接显示，而不是空白。」
//
// 事实（考古位 2026-09-14 实测，报告 §4）：好友面已有 L2（`fmMessageCache.js`）⇒
// 开窗即渲缓存；Dropbox 面此前**零本地层** —— `dropbox.js` 的 `dropboxMessages`
// 是模块级内存对象（页面重载即失），首帧恒等挂在 `dropbox-history` 回包上：
// 逐 run Δ(首节点插入 − 回包) = 0.0–0.1 ms（13/13 全等），回包静默时**永不显示**
// （4 s 内 0 节点）。本模块就是补的那一层。
//
// 三条纪律（与 `fmMessageCache.js` 逐条同向，违反即 bug）：
//  1. **零顶层副作用**：模块求值不得碰 localStorage —— `scripts/build-web.mjs`
//     按可达性做 define + DCE，顶层副作用会变成剥离包里的残留死代码。
//  2. **键走 `key()` 品牌命名空间**（`branding.js`）。
//  3. **按 acct 分区**：分区键的**唯一来源** = `fmMessageCache.getCacheAccount()`
//     （`deviceId|email`，由 `neblink.js` 落位）。本模块**不自设** `setCacheAccount`
//     —— 第二份分区状态必然与第一份漂移，跨账号串数据是最高危的失配。
//
// 与好友面缓存的**有意差异**（逐条给理由，不是疏漏）：
//  - **无 keyset 水位**：Dropbox 历史是 `msgId` 键的全量帧（网关
//    `DropboxService.getHistory` 即内存直读全量），协议上没有 `after=` 游标
//    ⇒ 缓存既不消费也不该造一个水位字段。
//  - **无 TTL**：条目存在的意义就是「先显示」；新鲜度由**每次开窗必发**的
//    `dropbox-get-history` 核对承担 —— 那正是 stale-while-revalidate 的
//    revalidate 腿，TTL 只会把「已过期但仍可用」误判成不可用。
//  - **不落瞬时进度**（`bytesReceived` / `totalBytes` / `downloadedBytes`）：
//    落盘会在重开时画出一条**永不再动**的进度条 —— 属「看起来对的错」。
//  - **无 `savedPath`**：那是本机落盘路径，跨会话重放会指到一个可能已被用户
//    移走的路径上；`status` 本身已能表达「已完成」。

import { key } from './branding.js';
import { getCacheAccount } from './fmMessageCache.js';

/** localStorage 槽位（品牌命名空间；`nebflow_fm_dbx_msg_cache` today）。 */
export const CACHE_KEY = key('fm_dbx_msg_cache');

/** 结构版本 —— 不匹配即整体丢弃重建（Dropbox 域此前无消息缓存，无迁移）。 */
export const SCHEMA_VERSION = 1;

// ── ⑩ 上限（与好友面同值同口径；本文件是这些数值的唯一落点）──────
/** 每设备保留的消息条数（= 好友面 MSGS_PER_CONV：都是「一屏多一点」的尾巴窗）。 */
export const MSGS_PER_DEV = 50;
/** 设备数上限（LRU：先淘汰最久未写入的设备条目）。 */
export const MAX_DEVS = 30;
/** 总字节上限。计量口径 = `JSON.stringify(...).length`
 *  （与 `fmMessageCache.MAX_BYTES` / `persistence.js` 的 2 MB 判据同一口径）。 */
export const MAX_BYTES = 512 * 1024;

// ── 内部状态 ─────────────────────────────────────────────
/** 当前分区的已解码副本（避免每次读都 JSON.parse 全量）。 */
let mem = null;
/** 内存副本对应的分区 —— 与 `getCacheAccount()` 不等 ⇒ 副本作废重读。 */
let memAcct = null;
/** localStorage 不可用 / 配额写失败标记（只读降级：不抛、不阻断 UI）。 */
let degraded = false;

/** 写入白名单：只落渲染真正消费的字段，绝不整对象透传。
 *  瞬时的传输进度字段（bytesReceived/totalBytes/downloadedBytes）**刻意不落**，
 *  理由见文件头「有意差异」第 3 条。 */
function slim(m) {
  if (!m || typeof m !== 'object') return null;
  const id = m.msgId;
  if (id === undefined || id === null || id === '') return null; // 无稳定键 ⇒ 无法增量合并
  /** @type {Record<string, unknown>} */
  const out = { msgId: String(id), kind: m.kind, direction: m.direction, ts: m.ts };
  if (m.text !== undefined) out.text = m.text;
  if (m.status !== undefined) out.status = m.status;
  if (m.transferId !== undefined) out.transferId = m.transferId;
  if (m.fileName !== undefined) out.fileName = m.fileName;
  if (m.fileSize !== undefined) out.fileSize = m.fileSize;
  return out;
}

function sizeOf(s) {
  try { return JSON.stringify(s).length; } catch { return 0; }
}

function emptyStore(acct) {
  return { v: SCHEMA_VERSION, acct, devs: /** @type {Record<string, any>} */ ({}) };
}

function readRaw() {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

/** 当前分区的存储视图（账号未知 / 结构不匹配 / 分区不匹配 ⇒ 当空缓存，不迁移）。 */
function store() {
  const acct = getCacheAccount();
  if (mem && memAcct === acct) return mem;
  memAcct = acct;
  if (!acct) { mem = emptyStore(''); return mem; }
  const raw = readRaw();
  if (!raw || raw.v !== SCHEMA_VERSION || raw.acct !== acct || !raw.devs || typeof raw.devs !== 'object') {
    mem = emptyStore(acct);
    return mem;
  }
  mem = { v: SCHEMA_VERSION, acct, devs: raw.devs };
  return mem;
}

function write(s) {
  if (degraded) return;
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(s));
  } catch {
    // 配额不足：本模块是纯缓存 —— 先丢自己，绝不让 Dropbox 缓存挤掉会话缓存
    // （与好友面同一条淘汰纪律，见 `persistence.js` 的淘汰顺序）。
    degraded = true;
    mem = emptyStore(s.acct);
    memAcct = s.acct;
    try { localStorage.removeItem(CACHE_KEY); } catch { /* non-critical */ }
  }
}

/** 淘汰：先按设备数（LRU：最久未写入者先走），再按总字节。
 *  `keepId` = 本次刚写入的设备，永不被本次淘汰删除（它才是当前工作集）。 */
function enforceCaps(s, keepId) {
  const keys = () => Object.keys(s.devs).filter(id => id !== keepId);
  if (Object.keys(s.devs).length > MAX_DEVS) {
    for (const id of keys()) {
      if (Object.keys(s.devs).length <= MAX_DEVS) break;
      delete s.devs[id];
    }
  }
  if (sizeOf(s) <= MAX_BYTES) return;
  for (const id of keys()) {
    delete s.devs[id];
    if (sizeOf(s) <= MAX_BYTES) return;
  }
  // 单条目自身超上限 —— 仍以封顶优先。
  if (sizeOf(s) > MAX_BYTES) delete s.devs[keepId];
}

// ── 生命周期 ─────────────────────────────────────────────

/** 登出清除（`neblink.js` 登出链挂靠点，与好友面缓存同轮）：整槽删除。 */
export function clearDeviceMessageCache() {
  mem = null;
  memAcct = null;
  degraded = false;
  try { localStorage.removeItem(CACHE_KEY); } catch { /* non-critical */ }
}

/** 淘汰顺序挂靠点（`persistence.js`）：本层与好友消息缓存同为纯缓存，
 *  在会话缓存需要清理时一并先丢。返回是否删掉了东西。 */
export function dropDeviceMessageCache() {
  let had = false;
  try { had = localStorage.getItem(CACHE_KEY) !== null; } catch { /* non-critical */ }
  mem = null;
  memAcct = null;
  if (had) { try { localStorage.removeItem(CACHE_KEY); } catch { /* non-critical */ } }
  return had;
}

// ── 读写 ─────────────────────────────────────────────────

/**
 * 读一个设备的缓存消息（**同步**，开窗首帧的唯一数据来源）。
 * @param {string} deviceId
 * @returns {any[]|null} null = 无可用缓存（账号未知 / 无条目 / 空条目）
 */
export function loadDeviceMessages(deviceId) {
  if (!deviceId || degraded) return null;
  if (!getCacheAccount()) return null;
  const e = store().devs[String(deviceId)];
  if (!e || !Array.isArray(e.msgs) || e.msgs.length === 0) return null;
  // 键 = `msgId` ⇒ 读侧按稳定键收敛一次（保留首条，保序）—— 与好友面
  // `loadConversation` 的读侧归一同一条纪律：数据层归一，不改渲染层语义。
  const seen = new Set();
  return e.msgs.filter((m) => {
    const k = String(m && m.msgId);
    if (!k || k === 'undefined' || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/**
 * 写一个设备的缓存消息（按每设备上限截尾）。
 * @param {string} deviceId
 * @param {any[]} msgs 已渲染消息（时序即界面序）
 * @returns {number} 实际落盘的条数（0 = 未落盘）
 */
export function saveDeviceMessages(deviceId, msgs) {
  if (!deviceId || !Array.isArray(msgs) || degraded) return 0;
  if (!getCacheAccount()) return 0;
  const kept = [];
  for (const m of msgs) {
    const s = slim(m);
    if (s) kept.push(s);
  }
  const trimmed = kept.slice(-MSGS_PER_DEV);
  if (trimmed.length === 0) return 0;
  const s = store();
  s.devs[String(deviceId)] = { msgs: trimmed, savedAt: Date.now() };
  enforceCaps(s, String(deviceId));
  write(s);
  return trimmed.length;
}

/** 诊断/测试读数：当前分区的设备数与序列化字节数。 */
export function deviceCacheStats() {
  const s = store();
  return { acct: s.acct, devs: Object.keys(s.devs).length, bytes: sizeOf(s) };
}
