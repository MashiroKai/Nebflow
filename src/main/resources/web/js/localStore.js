// localStore.js — 会话面本地优先的**唯一属主本地层**（IndexedDB）。
//
// 规格 = 方案卡 `.nebflow/20260920_184900_sessperf-local-first-card__chain-sessperf.md`
// §4①（本地存储层）/§4②（增量同步）/§4③（头像内容指纹双层缓存）/§4④（预算与
// 六级降级）/§4⑤（与 uxconsist 的在飞接缝）。本文件是**这些设计与数值的唯一落点**：
// 调用方（`messages.js` / `contacts.js` / `avatarRender.js` / `persistence.js` /
// `neblink.js`）只消费，禁第二份取数或落盘实现（现状两份 `avatarEl` 正是反例）。
//
// ── 介质为什么是 IDB（不是 localStorage）───────────────────────────────
// `localStorage` 是同步阻塞 API + ~5 MB 总量 + 字符串 JSON；头像字节走 dataURL
// 膨胀 33%（`avatarCache.js` 的 `MAX_BYTES = 1.5 MB` 单枚闸就是这个天花板的产物）。
// IDB 支持 Blob 直存（零膨胀）、按 key/索引查询、GB 级配额、异步写不阻塞渲染线程。
//
// ── 三条硬纪律（违反即 bug）────────────────────────────────────────────
//  1. **渲染路径零 await 本地层**：读接口（`readMessages` / `readAvatar` /
//     `readRoster` / `readOutbox`）**全部同步**，数据来自内存镜像；异步只发生在
//     **写**与**预热**（卡 §4① 逐字）。首帧因此永远不等本地层。
//  2. **零顶层副作用**：模块求值不得碰 `indexedDB` / `localStorage`
//     —— `scripts/build-web.mjs` 按可达性做 define + DCE，顶层副作用会变成
//     剥离包里的残留死代码（同 `fmMessageCache.js` 第 1 条纪律）。
//  3. **按 acct 分区**：分区键的**唯一来源** = `fmMessageCache.getCacheAccount()`
//     （`deviceId|email`，`neblink.js` 落位）。`openLocalStore(acct)` 只是把**已经
//     落位**的分区读进来；两者不一致 ⇒ 当空缓存（L4），绝不跨账号串数据。
//
// ── 六级降级阶梯（卡 §4④，每条都有确定行为，禁静默）────────────────────
//  L0 正常：IDB 全开。
//  L1 `indexedDB` 不可用（隐私模式 / 配额初始化失败）⇒ `available=false`，
//     本模块整体 no-op（读返回 null、写静默丢弃）⇒ 调用方按
//     `isLocalStoreEnabled()` 回落**现状三层**（`fmMessageCache` +
//     `fmDropboxCache` + `avatarCache`）+ 直拉。
//  L2 写失败（`QuotaExceededError`）⇒ 本池清空 → 重试一次 → 仍失败 ⇒ 该池
//     `degraded`（只读内存镜像，不抛、不阻断 UI）。
//  L3 结构不匹配 / 打开或解析失败 ⇒ **整库丢弃重建**（无迁移）。
//  L4 账号分区不匹配 ⇒ 当空缓存（**绝不跨账号**）。
//  L5 长跑累积超硬上限 ⇒ `evict()` 强制压回软上限；`stats()` 可读。
//
// ── 与 legacy 三层的关系（卡 §4① / §5.4 风险表）──────────────────────
// 首启动 lazy **一次性**导入（写 IDB），导入后读只走 IDB（**禁双写**，否则双读数
// 分歧）；legacy 槽位**零删除**（保留回退面）；IDB 不可用时（L1）legacy 三层照旧
// 是权威层，行为与改前逐字相同。
//
// ── 接口面（卡 §4⑤ 冻结，**不得改名**）────────────────────────────────
//  同步读：readMessages(convId) / readAvatar(key) / readRoster(kind) / readOutbox(clientMsgId)
//  异步写：writeMessages(convId, msgs, {watermark,lastMessageAt}) /
//          writeAvatar(key, {blob,sha256,url}) / writeRoster(kind, payload, {version}) /
//          writeOutbox(entry)
//  运维：  openLocalStore(acct) / evict() / stats() / clear(scope)
// 另加（**加性**，非改名）：`isLocalStoreEnabled()` / `localStoreAcct()` /
//  `ensureAvatar()` / `avatarMeta()` / `signatureOf()` / `reconcile()` /
//  `localStoreSizes()`。`outbox` store 的键位 = `clientMsgId`（与 `friendsApi` 既有
// 幂等键同源）—— uxconsist 的 8 态状态机只加记录，消息层接口零改动（本批只冻结
// 键位与读写接口，不实现发送态）。

import { key } from './branding.js';
import { getCacheAccount, CACHE_KEY as FM_MSG_LEGACY_KEY } from './fmMessageCache.js';

/** IDB 库名（卡 §4① 磁盘 schema）。 */
export const DB_NAME = 'nebflow-local';
/** 结构版本 —— 不匹配即整库丢弃重建（无迁移）。 */
export const SCHEMA_VERSION = 1;

/** store 名（键位与索引逐字对齐卡 §4①）。 */
const S_META = 'meta';
const S_MESSAGES = 'messages';
const S_CONVMETA = 'convmeta';
const S_AVATARS = 'avatars';
const S_ROSTER = 'roster';
const S_OUTBOX = 'outbox';
/** 设备 legacy 消息缓存槽（`fmDropboxCache.js` 的键，一次性导入用）。 */
const FM_DBX_LEGACY_KEY = key('fm_dbx_msg_cache');
/** 本账号头像 legacy 槽（`avatarCache.js` 的键，一次性导入用）。 */
const AVATAR_LEGACY_KEY = key('avatar_cache');

// ── 预算（卡 §4④ 分池表；**本文件唯一落点**，调用方不得自选数值）──────────
const KB = 1024;
const MB = 1024 * KB;
/** 分池上限（软上限，超限即触发本池驱逐）。 */
export const BUDGET = {
  /** 会话数上限 × 每会话条数上限；总字节 3 MB（约 180 B/条）。 */
  messages: { convs: 30, msgsPerConv: 200, bytes: 3 * MB },
  /** 头像条目数 / 字节（Blob 直存，40 px ≈ 4–12 KB/枚）。 */
  avatars: { entries: 300, bytes: 6 * MB },
  /** 名册（会话 / 好友 / 群 / 群名册 / 设备）。 */
  roster: { entries: 800, bytes: 1 * MB },
  /** 未决发送（预留，条目天然极少）。 */
  outbox: { entries: 200, bytes: 256 * KB },
  /** 总闸：IDB 无 5 MB 天花板，仍自设闸以防长跑累积。 */
  totalSoft: 15 * MB,
  totalHard: 25 * MB,
};

/** 条目新鲜度（对齐既有三层的既有数值，不新造口径）。 */
export const MSG_TTL_MS = 5 * 60 * 1000; // 对齐 fmMessageCache.TTL_MS
export const ROSTER_TTL_MS = 10 * 60 * 1000; // 卡 §4④ roster TTL 10 min
export const AVATAR_TTL_MS = 24 * 60 * 60 * 1000; // 对齐 avatarCache.TTL_MS
export const AVATAR_BACKOFF_MS = 60 * 60 * 1000; // 对齐 avatarCache.RETRY_BACKOFF_MS
/** 内存 objectURL 层上限（卡 §5.4 内存估算 = 80 枚头像 ≈ 1.5 MB）。 */
export const OBJECT_URL_CAP = 80;

// ── 内部状态 ─────────────────────────────────────────────
/** 当前分区（'' = 未打开/未登录 ⇒ 读写全部关闭，绝不落盘）。 */
let acct = '';
/** @type {IDBDatabase|null} */
let db = null;
/** IDB 是否可用（L1 判据；false ⇒ 本模块整体 no-op）。 */
let available = false;
/** 打开/初始化是否已经在飞（幂等；换账号时重置）。 */
let opening = /** @type {Promise<boolean>|null} */ (null);
/** 写失败降级的池（L2；只影响对应池）。 */
const degraded = new Set();
/** legacy 一次性导入是否已尝试（内存 latch，meta 里另有持久标记）。 */
let legacyImported = false;

/** 内存镜像（渲染路径的唯一来源；同步读）。 */
const mirror = {
  /** @type {Map<string, {msgs: any[], watermark: number, fetchedAt: number, lastMessageAt: number}>} */
  messages: new Map(),
  /** @type {Map<string, {payload: any, fetchedAt: number, version: number}>} */
  roster: new Map(),
  /** @type {Map<string, {objectUrl: string, url: string, sha256: string, fetchedAt: number, lastAccessAt: number, failedAt: number}>} */
  avatars: new Map(),
  /** @type {Map<string, any>} */
  outbox: new Map(),
};
/** 字节记账（LRU 驱逐的判据面；写路径增量维护，`evict()` 时全量重算）。 */
const sizes = {
  /** @type {Map<string, number>} */
  messages: new Map(),
  /** @type {Map<string, number>} */
  roster: new Map(),
  /** @type {Map<string, number>} */
  avatars: new Map(),
  /** @type {Map<string, number>} */
  outbox: new Map(),
};
/** objectURL LRU 序（末尾最新）；吊销归驱逐点（卡 §4③）。 */
const objectUrlOrder = [];
/** `ensureAvatar` 的在飞去重（`key` → Promise）。 */
const inflightAvatars = new Map();

function idbFactory() {
  try {
    return typeof indexedDB !== 'undefined' && indexedDB ? indexedDB : null;
  } catch {
    return null; // 隐私模式 / 被策略拒绝 —— 一律按不可用（L1）
  }
}

function bytesOf(value) {
  try {
    if (!value || typeof value !== 'object') return 0;
    let n = JSON.stringify(value).length;
    const blob = value.blob;
    if (blob && typeof blob.size === 'number') n += blob.size; // Blob 直存，不计 JSON 里的占位
    return n;
  } catch {
    return 0;
  }
}

/** 纯对象（写 IDB 用；去掉函数等不可结构化克隆的成员）。 */
function plain(value) {
  try {
    return JSON.parse(JSON.stringify(value));
  } catch {
    return null;
  }
}

// ── 库打开 / 结构（L3 的落点）──────────────────────────────

function openDb() {
  const factory = idbFactory();
  if (!factory) return Promise.resolve(null);
  return new Promise((resolve) => {
    let req;
    try {
      req = factory.open(DB_NAME, SCHEMA_VERSION);
    } catch {
      resolve(null);
      return;
    }
    req.onupgradeneeded = () => {
      const d = req.result;
      if (!d.objectStoreNames.contains(S_META)) d.createObjectStore(S_META, { keyPath: 'k' });
      if (!d.objectStoreNames.contains(S_MESSAGES)) {
        const s = d.createObjectStore(S_MESSAGES, { keyPath: ['convId', 'id'] });
        s.createIndex('byConv', 'convId', { unique: false });
        s.createIndex('byAt', ['convId', 'createdAt'], { unique: false });
      }
      if (!d.objectStoreNames.contains(S_CONVMETA)) d.createObjectStore(S_CONVMETA, { keyPath: 'convId' });
      if (!d.objectStoreNames.contains(S_AVATARS)) d.createObjectStore(S_AVATARS, { keyPath: 'key' });
      if (!d.objectStoreNames.contains(S_ROSTER)) d.createObjectStore(S_ROSTER, { keyPath: 'k' });
      if (!d.objectStoreNames.contains(S_OUTBOX)) d.createObjectStore(S_OUTBOX, { keyPath: 'clientMsgId' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => resolve(null);
    req.onblocked = () => resolve(null);
  });
}

/** 整库丢弃重建（L3 / L4 的机械落点；无迁移，照 `fmMessageCache` 既有纪律）。 */
function dropDb() {
  const factory = idbFactory();
  const close = () => {
    try {
      if (db) db.close();
    } catch { /* non-critical */ }
    db = null;
  };
  close();
  return new Promise((resolve) => {
    if (!factory) {
      resolve(false);
      return;
    }
    let req;
    try {
      req = factory.deleteDatabase(DB_NAME);
    } catch {
      resolve(false);
      return;
    }
    req.onsuccess = () => resolve(true);
    req.onerror = () => resolve(false);
    req.onblocked = () => resolve(false);
  });
}

/**
 * 单条 IDB 读（Promise 化）。
 * @param {string} store
 * @param {IDBValidKey | IDBKeyRange} key
 * @returns {Promise<any>}
 */
function idbGet(store, key) {
  return new Promise((resolve) => {
    if (!db) {
      resolve(null);
      return;
    }
    try {
      const req = db.transaction(store, 'readonly').objectStore(store).get(key);
      req.onsuccess = () => resolve(req.result === undefined ? null : req.result);
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

/**
 * 全量读（预热 / 记账 / 驱逐）。
 * @param {string} store
 * @returns {Promise<any[]>}
 */
function idbGetAll(store) {
  return new Promise((resolve) => {
    if (!db) {
      resolve([]);
      return;
    }
    try {
      const req = db.transaction(store, 'readonly').objectStore(store).getAll();
      req.onsuccess = () => resolve(Array.isArray(req.result) ? req.result : []);
      req.onerror = () => resolve([]);
    } catch {
      resolve([]);
    }
  });
}

/** 索引全量读（`byConv` —— 只取一个会话的条目，预热用）。 */
function idbGetAllByIndex(store, index, key) {
  return new Promise((resolve) => {
    if (!db) {
      resolve([]);
      return;
    }
    try {
      const req = db.transaction(store, 'readonly').objectStore(store).index(index).getAll(key);
      req.onsuccess = () => resolve(Array.isArray(req.result) ? req.result : []);
      req.onerror = () => resolve([]);
    } catch {
      resolve([]);
    }
  });
}

/**
 * 单条写。
 * @returns {Promise<boolean>} false = 写失败（调用方按 L2 处理；配额错在 `name` 里）
 */
function idbPut(store, value) {
  return new Promise((resolve) => {
    if (!db) {
      resolve(false);
      return;
    }
    try {
      const tx = db.transaction(store, 'readwrite');
      tx.objectStore(store).put(value);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
      tx.onabort = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}

/** @returns {Promise<boolean>} */
function idbPuts(store, values) {
  if (!values.length) return Promise.resolve(true);
  return new Promise((resolve) => {
    if (!db) {
      resolve(false);
      return;
    }
    try {
      const tx = db.transaction(store, 'readwrite');
      const os = tx.objectStore(store);
      for (const v of values) os.put(v);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
      tx.onabort = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}

/** @returns {Promise<boolean>} */
function idbDelete(store, key) {
  return new Promise((resolve) => {
    if (!db) {
      resolve(false);
      return;
    }
    try {
      const tx = db.transaction(store, 'readwrite');
      tx.objectStore(store).delete(key);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
      tx.onabort = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}

/** @returns {Promise<boolean>} */
function idbClear(store) {
  return new Promise((resolve) => {
    if (!db) {
      resolve(false);
      return;
    }
    try {
      const tx = db.transaction(store, 'readwrite');
      tx.objectStore(store).clear();
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
      tx.onabort = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}

// ── 生命周期 ─────────────────────────────────────────────

/**
 * 打开/切换本地层分区（幂等；同分区重复调用只保证「已就绪」）。
 *
 * 时序：① 打开库（失败 ⇒ L1）；② 分区核对（不匹配 ⇒ 整库丢弃重建 ⇒ L4 当空）；
 * ③ legacy 一次性导入（首启动）；④ 预热内存镜像（异步，**永不阻塞渲染路径**）。
 *
 * @param {string} nextAcct 分区键（`deviceId|email`，来自 `neblink.js`；'' = 未登录）
 * @returns {Promise<boolean>} 本地层是否可用（= 是否该由本层当权威）
 */
export function openLocalStore(nextAcct) {
  const want = String(nextAcct || '');
  if (want !== acct) {
    acct = want;
    db = null;
    available = false;
    opening = null;
    degraded.clear();
    legacyImported = false;
    resetMirrors();
  }
  if (!want) {
    available = false; // 账号未知 ⇒ 读写全关（绝不落盘/跨账号）
    return Promise.resolve(false);
  }
  if (opening) return opening;
  opening = (async () => {
    let d = await openDb();
    if (!d) {
      available = false; // L1：IDB 不可用 ⇒ 本层整体 no-op
      return false;
    }
    db = d;
    available = true;
    // 分区核对（L4）：`meta.acct` 不是本账号 ⇒ 整库丢弃重建（无跨账号读数窗口）
    const metaAcct = await idbGet(S_META, 'acct');
    const stored = metaAcct && typeof metaAcct === 'object' ? String(metaAcct.v || '') : '';
    if (stored && stored !== acct) {
      await dropDb(); // L4 + L3：一条实现（丢弃重建 = 无迁移）
      d = await openDb();
      if (!d) {
        available = false;
        db = null;
        return false;
      }
      db = d;
      resetMirrors();
    }
    const schemaEntry = await idbGet(S_META, 'schema');
    const schema = schemaEntry && typeof schemaEntry === 'object' ? Number(schemaEntry.v) : 0;
    if (schema !== SCHEMA_VERSION) {
      // L3：结构不匹配 ⇒ 整库丢弃重建（导入腿随后重建内容）
      await dropDb();
      d = await openDb();
      if (!d) {
        available = false;
        db = null;
        return false;
      }
      db = d;
      resetMirrors();
    }
    await idbPut(S_META, { k: 'acct', v: acct });
    await idbPut(S_META, { k: 'schema', v: SCHEMA_VERSION });
    if (!legacyImported) {
      legacyImported = true;
      await importLegacy();
    }
    void warmMirror(); // 预热永不阻塞首帧（卡 §4① 三段式第 ① 步）
    return true;
  })();
  return opening;
}

function resetMirrors() {
  for (const m of objectUrlOrder) revokeObjectUrl(m);
  objectUrlOrder.length = 0;
  mirror.messages.clear();
  mirror.roster.clear();
  mirror.avatars.clear();
  mirror.outbox.clear();
  sizes.messages.clear();
  sizes.roster.clear();
  sizes.avatars.clear();
  sizes.outbox.clear();
}

function revokeObjectUrl(url) {
  try {
    if (url) URL.revokeObjectURL(url);
  } catch { /* non-critical */ }
}

/** 本层此刻是否当权威（false ⇒ 调用方走 legacy 三层 + 直拉，即 L1 现状行为）。 */
export function isLocalStoreEnabled() {
  return available && !!acct && getCacheAccount() === acct;
}

/** 本层当前分区（诊断/测试读数）。 */
export function localStoreAcct() {
  return acct;
}

// ── legacy 一次性导入（卡 §4①「首启动 lazy 导入一次」，导入后禁双写）──────

function readLegacyJson(lsKey) {
  try {
    const raw = localStorage.getItem(lsKey);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/** 好友/群消息槽 → `messages` + `convmeta`（形状逐字对齐 `fmMessageCache`）。 */
async function importLegacyMessages() {
  const raw = readLegacyJson(FM_MSG_LEGACY_KEY);
  const convs = raw && raw.convs && typeof raw.convs === 'object' ? raw.convs : null;
  if (!convs) return;
  const byConv = [];
  for (const convId of Object.keys(convs)) {
    const e = convs[convId];
    if (!e || !Array.isArray(e.msgs) || !e.msgs.length) continue;
    const msgs = [];
    for (const m of e.msgs) {
      const v = fmEntry(convId, m);
      if (v) msgs.push(v);
    }
    if (!msgs.length) continue;
    byConv.push({
      convId: String(convId),
      msgs,
      watermark: Number(e.watermark) || maxNumericId(msgs),
      fetchedAt: Number(e.fetchedAt) || Date.now(),
      lastMessageAt: Number(e.lastMessageAt) || 0,
    });
  }
  for (const e of byConv) {
    await idbPuts(S_MESSAGES, e.msgs);
    await idbPut(S_CONVMETA, { convId: e.convId, watermark: e.watermark, fetchedAt: e.fetchedAt, lastMessageAt: e.lastMessageAt, msgs: e.msgs.length });
    sizes.messages.set(e.convId, e.msgs.reduce((n, v) => n + bytesOf(v), 0));
  }
}

/** 设备 legacy 槽 → `messages`（`dev:<deviceId>` 键；消费点仍是 dropbox.js 的既有层）。 */
async function importLegacyDeviceMessages() {
  const raw = readLegacyJson(FM_DBX_LEGACY_KEY);
  const devs = raw && raw.devs && typeof raw.devs === 'object' ? raw.devs : null;
  if (!devs) return;
  for (const deviceId of Object.keys(devs)) {
    const e = devs[deviceId];
    if (!e || !Array.isArray(e.msgs) || !e.msgs.length) continue;
    const convId = 'dev:' + String(deviceId);
    const msgs = [];
    for (const m of e.msgs) {
      const v = devEntry(convId, m);
      if (v) msgs.push(v);
    }
    if (!msgs.length) continue;
    await idbPuts(S_MESSAGES, msgs);
    await idbPut(S_CONVMETA, {
      convId,
      watermark: Number(e.savedAt) || 0, // legacy 面无游标（不必造一个水位 —— 卡 §4② 第 2 行）
      fetchedAt: Number(e.savedAt) || Date.now(),
      lastMessageAt: Number(e.savedAt) || 0,
      msgs: msgs.length,
    });
    sizes.messages.set(convId, msgs.reduce((n, v) => n + bytesOf(v), 0));
  }
}

/** 本账号头像 legacy 槽（dataURL）→ `avatars`（键 = `self`；消费点待自查）。 */
async function importLegacyAvatar() {
  const raw = readLegacyJson(AVATAR_LEGACY_KEY);
  const dataUrl = raw && typeof raw.dataUrl === 'string' ? raw.dataUrl : '';
  if (!dataUrl.startsWith('data:')) return;
  try {
    const blob = await (await fetch(dataUrl)).blob();
    if (!blob || !blob.size) return;
    const sha = await sha256Hex(await blob.arrayBuffer());
    await idbPut(S_AVATARS, {
      key: 'self',
      userId: '',
      url: String(raw.url || ''),
      sha256: sha,
      blob,
      fetchedAt: Number(raw.fetchedAt) || Date.now(),
      lastAccessAt: Date.now(),
    });
    sizes.avatars.set('self', bytesOf({ blob }));
  } catch { /* 导入是尽力而为：失败即当没有旧头像（零回归） */ }
}

async function importLegacy() {
  try {
    await importLegacyMessages();
    await importLegacyDeviceMessages();
    await importLegacyAvatar();
    await idbPut(S_META, { k: 'legacy', v: 1 });
  } catch { /* 导入失败不影响本层可用性（render 面走网络腿，零回归） */ }
}

// ── 内存镜像预热（异步、分帧；首帧只依赖已在镜像里的会话）────────────────

function warmMirror() {
  const run = async () => {
    if (!isLocalStoreEnabled()) return;
    try {
      const metas = await idbGetAll(S_CONVMETA);
      metas.sort((a, b) => (Number(b.lastMessageAt) || 0) - (Number(a.lastMessageAt) || 0));
      const keep = metas.slice(0, BUDGET.messages.convs);
      for (const meta of keep) {
        const convId = String(meta.convId);
        const rows = await idbGetAllByIndex(S_MESSAGES, 'byConv', convId);
        rows.sort((a, b) => compareIds(a.id, b.id));
        mirror.messages.set(convId, {
          msgs: rows,
          watermark: Number(meta.watermark) || 0,
          fetchedAt: Number(meta.fetchedAt) || 0,
          lastMessageAt: Number(meta.lastMessageAt) || 0,
        });
      }
      const rosters = await idbGetAll(S_ROSTER);
      for (const r of rosters) {
        mirror.roster.set(String(r.k), {
          payload: r.payload,
          fetchedAt: Number(r.fetchedAt) || 0,
          version: Number(r.version) || 0,
        });
        sizes.roster.set(String(r.k), bytesOf(r));
      }
      const avatars = await idbGetAll(S_AVATARS);
      avatars.sort((a, b) => (Number(b.lastAccessAt) || 0) - (Number(a.lastAccessAt) || 0));
      for (const a of avatars.slice(0, OBJECT_URL_CAP)) hydrateAvatarMirror(a);
      for (const a of avatars) sizes.avatars.set(String(a.key), bytesOf(a));
      const box = await idbGetAll(S_OUTBOX);
      for (const o of box) {
        mirror.outbox.set(String(o.clientMsgId), o);
        sizes.outbox.set(String(o.clientMsgId), bytesOf(o));
      }
      accountSizes();
      // L5：冷启动即超硬上限 ⇒ 立刻压回软上限（长跑累积的收口点）
      if (totalBytes() > BUDGET.totalHard) void evict();
    } catch { /* 预热失败不影响本层（读返回空 ⇒ 走网络腿，零回归） */ }
  };
  if (typeof requestIdleCallback === 'function') requestIdleCallback(() => void run(), { timeout: 2000 });
  else setTimeout(() => void run(), 0);
}

/** 给一条头像记录建内存 objectURL（卡 §4③ 内存层）。 */
function hydrateAvatarMirror(rec) {
  const k = String(rec.key);
  const blob = rec.blob;
  let objectUrl = '';
  if (blob && typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
    try {
      objectUrl = URL.createObjectURL(blob);
    } catch {
      objectUrl = '';
    }
  }
  if (objectUrl) touchObjectUrl(k);
  mirror.avatars.set(k, {
    objectUrl,
    url: String(rec.url || ''),
    sha256: String(rec.sha256 || ''),
    fetchedAt: Number(rec.fetchedAt) || 0,
    lastAccessAt: Number(rec.lastAccessAt) || 0,
    failedAt: Number(rec.failedAt) || 0,
  });
}

// ── id/排序小件 ──────────────────────────────────────────

function compareIds(a, b) {
  const na = Number(a);
  const nb = Number(b);
  if (Number.isFinite(na) && Number.isFinite(nb)) return na - nb;
  return String(a).localeCompare(String(b));
}

function maxNumericId(values) {
  let max = 0;
  for (const v of values) {
    const n = Number(v && v.id);
    if (Number.isFinite(n) && n > max) max = n;
  }
  return max;
}

// ── 写入白名单（卡 §4① 的 messages 值形状）────────────────────────────

/** 附件白名单键 —— 逐字对齐线上 `AttachmentSummary`（**不增不减**，见
 *  `fmMessageCache.ATTACHMENT_KEYS` 的取证注释：`name`/`sha256` 缺一会误报）。 */
const ATTACHMENT_KEYS = ['id', 'name', 'size', 'mime', 'sha256', 'state'];

function slimAttachments(list) {
  if (!Array.isArray(list) || list.length === 0) return [];
  const out = [];
  for (const a of list) {
    if (!a || typeof a !== 'object') continue;
    /** @type {Record<string, unknown>} */
    const one = {};
    for (const k of ATTACHMENT_KEYS) if (a[k] !== undefined) one[k] = a[k];
    if (Object.keys(one).length) out.push(one);
  }
  return out;
}

/** 好友/群面一条消息 → 落盘条目（键 `id` 归一为字符串：`1` 与 `"1"` 不得成两条）。 */
function fmEntry(convId, m) {
  if (!m || typeof m !== 'object') return null;
  const raw = m.id;
  if (raw === undefined || raw === null || raw === '') return null;
  const id = String(raw);
  if (id.startsWith('fm-tmp-')) return null; // 乐观临时 id 不落盘（同 fmMessageCache 纪律）
  /** @type {Record<string, unknown>} */
  const out = { convId, id, shape: 'fm', senderId: m.senderId, kind: m.kind, body: m.body || '', createdAt: m.createdAt };
  if (m.origin !== undefined) out.origin = m.origin;
  if (m.agentSent !== undefined) out.agentSent = m.agentSent;
  const atts = slimAttachments(m.attachments);
  if (atts.length) out.attachments = atts;
  return out;
}

/** 设备**服务端腿**一条 wire 行 → 落盘条目（方向不落盘：读回时按 `adaptDeviceMessage`
 *  同一单点重算 —— 冻结方向会把换机/换账号后的判据钉死）。 */
function devEntry(convId, m) {
  if (!m || typeof m !== 'object') return null;
  const raw = m.id !== undefined && m.id !== null ? m.id : m.msgId;
  if (raw === undefined || raw === null || raw === '') return null;
  /** @type {Record<string, unknown>} */
  const out = { convId, id: String(raw), shape: 'dev', senderDeviceId: m.senderDeviceId, body: m.body, createdAtMs: m.createdAtMs, kind: m.kind };
  const atts = slimAttachments(m.attachments);
  if (atts.length) out.attachments = atts;
  if (m.ts !== undefined) out.ts = m.ts;
  return out;
}

/** legacy 设备条目（`fmDropboxCache` 形状，带 `msgId`）→ 落盘条目。 */
function devLegacyEntry(convId, m) {
  if (!m || typeof m !== 'object') return null;
  const id = m.msgId;
  if (id === undefined || id === null || id === '') return null;
  /** @type {Record<string, unknown>} */
  const out = { convId, id: String(id), shape: 'dev', legacy: 1, direction: m.direction, ts: m.ts, kind: m.kind };
  if (m.text !== undefined) out.body = m.text;
  if (m.status !== undefined) out.status = m.status;
  if (m.transferId !== undefined) out.transferId = m.transferId;
  if (m.fileName !== undefined) out.fileName = m.fileName;
  if (m.fileSize !== undefined) out.fileSize = m.fileSize;
  return out;
}

// ── 同步读（渲染路径；**零 await、零网络**）────────────────────────────

/**
 * 读一个会话的本地条目（同步）。
 * @param {string} convId
 * @returns {{msgs: any[], watermark: number, fetchedAt: number, fresh: boolean}|null}
 *   null = 无可用条目（L1 不可用 / 未打开 / 无条目 / 空条目 / 无可信水位）。
 */
export function readMessages(convId) {
  if (!isLocalStoreEnabled() || !convId) return null;
  const e = mirror.messages.get(String(convId));
  if (!e || !Array.isArray(e.msgs) || e.msgs.length === 0) return null;
  // 无可信 keyset 水位 ⇒ 当无条目（`after=0` 在服务端是「从最早开始」，拿它当
  // 增量锚会拉回最旧窗口 —— 与 `fmMessageCache.loadConversation` 同一条纪律）。
  if (e.watermark <= 0) return null;
  const seen = new Set();
  const msgs = e.msgs.filter((m) => {
    const k = String(m && m.id);
    if (!k || k === 'undefined' || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  if (!msgs.length) return null;
  return {
    msgs,
    watermark: e.watermark,
    fetchedAt: e.fetchedAt,
    fresh: Date.now() - e.fetchedAt <= MSG_TTL_MS,
  };
}

/**
 * 名册读取（同步）。
 * @param {string} kind `'conversations'|'friends'|'groups'|'groupMembers:<id>'|'devices'`
 * @returns {{payload: any, fetchedAt: number, version: number, fresh: boolean}|null}
 */
export function readRoster(kind) {
  if (!isLocalStoreEnabled() || !kind) return null;
  const e = mirror.roster.get(String(kind));
  if (!e || e.payload === undefined || e.payload === null) return null;
  return { payload: e.payload, fetchedAt: e.fetchedAt, version: e.version, fresh: Date.now() - e.fetchedAt <= ROSTER_TTL_MS };
}

/**
 * 头像读取（同步，卡 §4③ 「三条零成本路径」第 1 条）。
 * @param {string} avatarKey `u:<userId>` / `group:<groupId>:<sig>` / `self`
 * @returns {string|null} objectURL（磁盘命中且已建内存层）；null = 未命中（调用方
 *   回落现状 `avatarImgNode(url)` 直拉 + 解码池 —— 渐进增强、零回归）
 */
export function readAvatar(avatarKey) {
  if (!isLocalStoreEnabled() || !avatarKey) return null;
  const k = String(avatarKey);
  const e = mirror.avatars.get(k);
  if (!e || !e.objectUrl) return null;
  // LRU touch（内存态）。🔴 **不回写 IDB**：读路径回写会把 blob 字段写没
  // （只读路径不该产生写放大；`lastAccessAt` 的下一次落盘由写路径/对账拍承担）。
  touchObjectUrl(k);
  e.lastAccessAt = Date.now();
  return e.objectUrl;
}

/**
 * 头像元数据（不建 objectURL；TTL/退避判据用）。
 * @param {string} avatarKey
 * @returns {{url: string, sha256: string, fetchedAt: number, lastAccessAt: number, failedAt: number}|null}
 */
export function avatarMeta(avatarKey) {
  if (!isLocalStoreEnabled() || !avatarKey) return null;
  const e = mirror.avatars.get(String(avatarKey));
  if (!e) return null;
  return { url: e.url, sha256: e.sha256, fetchedAt: e.fetchedAt, lastAccessAt: e.lastAccessAt, failedAt: e.failedAt || 0 };
}

/**
 * 未决发送读取（同步；`outbox` 键位 = `clientMsgId`，uxconsist 接缝）。
 * @param {string} clientMsgId
 */
export function readOutbox(clientMsgId) {
  if (!isLocalStoreEnabled() || !clientMsgId) return null;
  return mirror.outbox.get(String(clientMsgId)) || null;
}

// ── 异步写（首帧之后；本地层的唯一写路径）─────────────────────────────

/**
 * 写一个会话的消息窗口。
 * @param {string} convId
 * @param {any[]} msgs 已加载/已取回的消息（升序；乐观临时 id 会被过滤）
 * @param {{watermark?: number, lastMessageAt?: number, shape?: 'fm'|'dev'}} [opts]
 *   `watermark` = 服务端 keyset 水位（含被截掉的旧条目）；
 *   `lastMessageAt` = 会话 LRU 键；`shape` = 条目形状（默认 `'fm'`；设备服务端腿
 *   传 `'dev'` —— 加性可选键，非改名）。
 */
export function writeMessages(convId, msgs, opts = {}) {
  if (!isLocalStoreEnabled() || !convId || !Array.isArray(msgs)) return Promise.resolve(false);
  const id = String(convId);
  const shape = opts.shape === 'dev' ? 'dev' : 'fm';
  const kept = [];
  for (const m of msgs) {
    const v = shape === 'dev' ? devEntry(id, m) : fmEntry(id, m);
    if (v) kept.push(v);
  }
  if (!kept.length) return Promise.resolve(false);
  const trimmed = kept.slice(-BUDGET.messages.msgsPerConv);
  return putConversation(id, trimmed, {
    watermark: Math.max(Number(opts.watermark) || 0, maxNumericId(trimmed)),
    lastMessageAt: Number(opts.lastMessageAt) || 0,
    shape,
  });
}

function putConversation(convId, trimmed, meta) {
  const fetchedAt = Date.now();
  mirror.messages.set(convId, { msgs: trimmed, watermark: meta.watermark, fetchedAt, lastMessageAt: meta.lastMessageAt || 0 });
  sizes.messages.set(convId, trimmed.reduce((n, v) => n + bytesOf(v), 0));
  const writes = async () => {
    // 先删该会话旧窗口（窗口会滑动：只 put 不 delete 会把被截掉的旧条目留在库里）
    await idbClearConv(convId);
    const ok = await idbPuts(S_MESSAGES, trimmed);
    await idbPut(S_CONVMETA, {
      convId,
      watermark: meta.watermark,
      fetchedAt,
      lastMessageAt: meta.lastMessageAt || 0,
      msgs: trimmed.length,
    });
    accountSizes();
    return ok;
  };
  return writes()
    .then((ok) => (ok ? true : retryAfterQuota('messages', writes)))
    .then(() => {
      if (totalBytes() > BUDGET.totalSoft) return evict();
      return undefined;
    })
    .catch(() => false);
}

/** 该会话的历史条目清空（索引删除；窗口滑动的正确性前提）。 */
function idbClearConv(convId) {
  return new Promise((resolve) => {
    if (!db) {
      resolve(false);
      return;
    }
    try {
      const tx = db.transaction(S_MESSAGES, 'readwrite');
      const idx = tx.objectStore(S_MESSAGES).index('byConv');
      const req = idx.openKeyCursor(IDBKeyRange.only(convId));
      req.onsuccess = () => {
        const cursor = req.result;
        if (cursor) {
          tx.objectStore(S_MESSAGES).delete(cursor.primaryKey);
          cursor.continue();
        }
      };
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => resolve(false);
      tx.onabort = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}

/**
 * 写头像磁盘层（Blob 直存 + 内容 sha256）。
 * @param {string} avatarKey `u:<userId>` / `self` / `group:<groupId>:<sig>`
 * @param {{blob?: Blob|null, sha256?: string, url?: string, userId?: string}} entry
 */
export function writeAvatar(avatarKey, entry) {
  if (!isLocalStoreEnabled() || !avatarKey) return Promise.resolve(false);
  const k = String(avatarKey);
  const prev = mirror.avatars.get(k);
  const blob = entry && entry.blob ? entry.blob : null;
  const sha256 = String((entry && entry.sha256) || (prev && prev.sha256) || '');
  const url = String((entry && entry.url) || (prev && prev.url) || '');
  const record = {
    key: k,
    userId: String((entry && entry.userId) || ''),
    url,
    sha256,
    blob,
    fetchedAt: Date.now(),
    lastAccessAt: Date.now(),
  };
  const objectUrl = blob ? createObjectUrl(blob) : (prev && prev.objectUrl) || '';
  if (objectUrl) touchObjectUrl(k);
  mirror.avatars.set(k, {
    objectUrl,
    url,
    sha256,
    fetchedAt: record.fetchedAt,
    lastAccessAt: record.lastAccessAt,
    failedAt: 0,
  });
  sizes.avatars.set(k, bytesOf(record));
  const write = () => idbPut(S_AVATARS, record);
  return write()
    .then((ok) => (ok ? true : retryAfterQuota('avatars', write)))
    .then(() => {
      if (sizes.avatars.size > BUDGET.avatars.entries || totalBytes() > BUDGET.totalSoft) return evict();
      return undefined;
    })
    .catch(() => false);
}

function createObjectUrl(blob) {
  try {
    if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') return URL.createObjectURL(blob);
  } catch { /* non-critical */ }
  return '';
}

/** objectURL LRU 记位；超上限即在同一处吊销最久未用者（卡 §4③ 驱逐点）。 */
function touchObjectUrl(k) {
  const i = objectUrlOrder.indexOf(k);
  if (i >= 0) objectUrlOrder.splice(i, 1);
  objectUrlOrder.push(k);
  while (objectUrlOrder.length > OBJECT_URL_CAP) {
    const drop = objectUrlOrder.shift();
    if (!drop) break;
    const e = mirror.avatars.get(drop);
    if (e && e.objectUrl) {
      revokeObjectUrl(e.objectUrl); // 吊销归 LRU 驱逐点（卡 §4③）
      e.objectUrl = '';
    }
  }
}

/**
 * 写名册（会话 / 好友 / 群 / 群名册 / 设备列表）。
 * @param {string} kind 见 `readRoster`
 * @param {any} payload 服务端载荷原样（内容签名由调用方算，见 `signatureOf`）
 * @param {{version?: number}} [opts]
 */
export function writeRoster(kind, payload, opts = {}) {
  if (!isLocalStoreEnabled() || !kind) return Promise.resolve(false);
  const k = String(kind);
  const record = {
    k,
    acct,
    payload: plain(payload),
    fetchedAt: Date.now(),
    version: Number(opts.version) || 0,
  };
  mirror.roster.set(k, { payload: record.payload, fetchedAt: record.fetchedAt, version: record.version });
  sizes.roster.set(k, bytesOf(record));
  const write = () => idbPut(S_ROSTER, record);
  return write()
    .then((ok) => (ok ? true : retryAfterQuota('roster', write)))
    .then(() => {
      if (sizes.roster.size > BUDGET.roster.entries || totalBytes() > BUDGET.totalSoft) return evict();
      return undefined;
    })
    .catch(() => false);
}

/**
 * 写未决发送条目（**键位冻结** = `clientMsgId`；uxconsist 的发送态接缝）。
 * @param {{clientMsgId: string}} entry
 */
export function writeOutbox(entry) {
  if (!isLocalStoreEnabled() || !entry || !entry.clientMsgId) return Promise.resolve(false);
  const k = String(entry.clientMsgId);
  const record = { ...plain(entry) };
  mirror.outbox.set(k, record);
  sizes.outbox.set(k, bytesOf(record));
  const write = () => idbPut(S_OUTBOX, record);
  return write()
    .then((ok) => (ok ? true : retryAfterQuota('outbox', write)))
    .catch(() => false);
}

// ── L2：配额失败 ⇒ 本池清空 → 重试一次 → 仍失败则降级 ────────────────────

function retryAfterQuota(pool, retry) {
  return (async () => {
    if (degraded.has(pool)) return false;
    await clearPool(pool);
    const ok = await retry();
    if (!ok) degraded.add(pool); // 只读内存镜像：不抛、不阻断 UI
    return ok;
  })();
}

async function clearPool(pool) {
  if (pool === 'messages') {
    mirror.messages.clear();
    sizes.messages.clear();
    await idbClear(S_MESSAGES);
    await idbClear(S_CONVMETA);
  } else if (pool === 'avatars') {
    for (const [, e] of mirror.avatars) revokeObjectUrl(e.objectUrl);
    mirror.avatars.clear();
    sizes.avatars.clear();
    objectUrlOrder.length = 0;
    await idbClear(S_AVATARS);
  } else if (pool === 'roster') {
    mirror.roster.clear();
    sizes.roster.clear();
    await idbClear(S_ROSTER);
  } else if (pool === 'outbox') {
    mirror.outbox.clear();
    sizes.outbox.clear();
    await idbClear(S_OUTBOX);
  }
}

// ── L5：字节记账 / LRU 驱逐 ───────────────────────────────

function poolBytes(map) {
  let n = 0;
  for (const v of map.values()) n += Number(v) || 0;
  return n;
}

function totalBytes() {
  return poolBytes(sizes.messages) + poolBytes(sizes.avatars) + poolBytes(sizes.roster) + poolBytes(sizes.outbox);
}

/** 记账全量重算（预热后 / `evict()` 前置；一次 getAll，异步）。 */
function accountSizes() {
  const run = async () => {
    const [msgs, avatars, rosters, box, metas] = await Promise.all([
      idbGetAll(S_MESSAGES),
      idbGetAll(S_AVATARS),
      idbGetAll(S_ROSTER),
      idbGetAll(S_OUTBOX),
      idbGetAll(S_CONVMETA),
    ]);
    sizes.messages.clear();
    sizes.avatars.clear();
    sizes.roster.clear();
    sizes.outbox.clear();
    const perConv = new Map();
    for (const m of msgs) {
      const k = String(m.convId);
      perConv.set(k, (perConv.get(k) || 0) + bytesOf(m));
    }
    for (const [k, v] of perConv) sizes.messages.set(k, v);
    for (const a of avatars) sizes.avatars.set(String(a.key), bytesOf(a));
    for (const r of rosters) sizes.roster.set(String(r.k), bytesOf(r));
    for (const o of box) sizes.outbox.set(String(o.clientMsgId), bytesOf(o));
    void metas;
  };
  return run();
}

/**
 * 驱逐到软上限（L5 的强制收口 + 写路径的自动触发）。
 * 顺序照卡 §4④：`roster` → `avatars` → `messages` → **`outbox` 最后**。
 * @returns {Promise<{evicted: number, bytes: number}>}
 */
export async function evict() {
  if (!isLocalStoreEnabled()) return { evicted: 0, bytes: 0 };
  let evicted = 0;
  // roster（按 fetchedAt LRU，保留刚写入的一条）
  if (sizes.roster.size > BUDGET.roster.entries || poolBytes(sizes.roster) > BUDGET.roster.bytes) {
    evicted += await evictLru(S_ROSTER, 'k', sizes.roster, BUDGET.roster.entries, BUDGET.roster.bytes, mirror.roster);
  }
  // avatars（按 lastAccessAt LRU；吊销 objectURL）
  if (sizes.avatars.size > BUDGET.avatars.entries || poolBytes(sizes.avatars) > BUDGET.avatars.bytes) {
    const order = await avatarLruOrder();
    let bytes = poolBytes(sizes.avatars);
    for (const k of order) {
      if (sizes.avatars.size <= BUDGET.avatars.entries && bytes <= BUDGET.avatars.bytes) break;
      const e = mirror.avatars.get(k);
      if (e) revokeObjectUrl(e.objectUrl);
      mirror.avatars.delete(k);
      const i = objectUrlOrder.indexOf(k);
      if (i >= 0) objectUrlOrder.splice(i, 1);
      bytes -= sizes.avatars.get(k) || 0;
      sizes.avatars.delete(k);
      await idbDelete(S_AVATARS, k);
      evicted++;
    }
  }
  // messages（按 lastMessageAt LRU：先剪会话数，再按字节）
  if (sizes.messages.size > BUDGET.messages.convs || poolBytes(sizes.messages) > BUDGET.messages.bytes) {
    const order = await convLruOrder();
    let bytes = poolBytes(sizes.messages);
    for (const k of order) {
      if (sizes.messages.size <= BUDGET.messages.convs && bytes <= BUDGET.messages.bytes) break;
      mirror.messages.delete(k);
      bytes -= sizes.messages.get(k) || 0;
      sizes.messages.delete(k);
      await idbClearConv(k);
      await idbDelete(S_CONVMETA, k);
      evicted++;
    }
  }
  // outbox 最后（未决发送绝不先丢）
  if (sizes.outbox.size > BUDGET.outbox.entries || poolBytes(sizes.outbox) > BUDGET.outbox.bytes) {
    const order = await outboxOrder();
    let bytes = poolBytes(sizes.outbox);
    let n = sizes.outbox.size;
    for (const k of order) {
      if (n <= BUDGET.outbox.entries && bytes <= BUDGET.outbox.bytes) break;
      mirror.outbox.delete(k);
      bytes -= sizes.outbox.get(k) || 0;
      sizes.outbox.delete(k);
      await idbDelete(S_OUTBOX, k);
      n--;
      evicted++;
    }
  }
  // 总闸：硬上限 ⇒ 再压一轮（顺序同上，outbox 仍最后）
  if (totalBytes() > BUDGET.totalSoft) {
    const order = await convLruOrder();
    for (const k of order) {
      if (totalBytes() <= BUDGET.totalSoft) break;
      mirror.messages.delete(k);
      sizes.messages.delete(k);
      await idbClearConv(k);
      await idbDelete(S_CONVMETA, k);
      evicted++;
    }
  }
  return { evicted, bytes: totalBytes() };
}

async function avatarLruOrder() {
  const rows = await idbGetAll(S_AVATARS);
  rows.sort((a, b) => (Number(a.lastAccessAt) || 0) - (Number(b.lastAccessAt) || 0));
  return rows.map((r) => String(r.key));
}

async function convLruOrder() {
  const metas = await idbGetAll(S_CONVMETA);
  metas.sort((a, b) => (Number(a.lastMessageAt) || 0) - (Number(b.lastMessageAt) || 0));
  return metas.map((m) => String(m.convId));
}

async function outboxOrder() {
  const rows = await idbGetAll(S_OUTBOX);
  rows.sort((a, b) => (Number(a.createdAt) || 0) - (Number(b.createdAt) || 0));
  return rows.map((r) => String(r.clientMsgId));
}

/**
 * 通用 LRU 驱逐（名册 / 单键 store）。
 * @param {string} store
 * @param {string} keyPath
 * @param {Map<string, number>} sizeMap
 * @param {number} entryCap
 * @param {number} byteCap
 * @param {Map<string, any>} memMap
 */
async function evictLru(store, keyPath, sizeMap, entryCap, byteCap, memMap) {
  const rows = await idbGetAll(store);
  rows.sort((a, b) => (Number(a.fetchedAt) || 0) - (Number(b.fetchedAt) || 0));
  let evicted = 0;
  let bytes = poolBytes(sizeMap);
  for (const r of rows) {
    if (sizeMap.size <= entryCap && bytes <= byteCap) break;
    const k = String(r[keyPath]);
    memMap.delete(k);
    bytes -= sizeMap.get(k) || 0;
    sizeMap.delete(k);
    await idbDelete(store, r[keyPath]);
    evicted++;
  }
  return evicted;
}

// ── 运维读数 / 清场 ──────────────────────────────────────

/**
 * 诊断读数（L5 审计面；同步，来自内存记账）。
 * @returns {{available: boolean, acct: string, degraded: string[], bytes: {messages: number, avatars: number, roster: number, outbox: number, total: number}, entries: {messages: number, avatars: number, roster: number, outbox: number}, objectUrls: number, budget: typeof BUDGET}}
 */
export function stats() {
  return {
    available,
    acct,
    degraded: [...degraded],
    bytes: {
      messages: poolBytes(sizes.messages),
      avatars: poolBytes(sizes.avatars),
      roster: poolBytes(sizes.roster),
      outbox: poolBytes(sizes.outbox),
      total: totalBytes(),
    },
    entries: {
      messages: sizes.messages.size,
      avatars: sizes.avatars.size,
      roster: sizes.roster.size,
      outbox: sizes.outbox.size,
    },
    objectUrls: mirror.avatars.size ? objectUrlOrder.length : 0,
    budget: BUDGET,
  };
}

/** 分池字节（测试/诊断用；同步）。 */
export function localStoreSizes() {
  return {
    messages: poolBytes(sizes.messages),
    avatars: poolBytes(sizes.avatars),
    roster: poolBytes(sizes.roster),
    outbox: poolBytes(sizes.outbox),
  };
}

/**
 * 清场（登出 / 会话失效 / 测试）。
 * @param {'all'|'messages'|'avatars'|'roster'|'outbox'} scope
 */
export function clear(scope = 'all') {
  const run = async () => {
    if (scope === 'all') {
      resetMirrors();
      if (db) {
        await Promise.all([idbClear(S_MESSAGES), idbClear(S_CONVMETA), idbClear(S_AVATARS), idbClear(S_ROSTER), idbClear(S_OUTBOX)]);
      }
      await idbPut(S_META, { k: 'acct', v: acct });
      await idbPut(S_META, { k: 'schema', v: SCHEMA_VERSION });
      return true;
    }
    await clearPool(scope);
    return true;
  };
  return run().catch(() => false);
}

// ── 内容签名（卡 §4② 兜底判据的单点实现）────────────────────────────────

/**
 * 内容签名（有序拼接 → 短哈希）。用途 = 「服务端无版本号」的数据面是否变过的
 * 判据（会话列表 / 好友列表 / 群名册；沿用 `avatarRender.avatarCellsSignature`
 * 的既有先例：**签名相同 ⇒ 零 DOM 操作**）。
 * @param {Array<string|number|null|undefined>} parts 有序字段
 * @returns {string}
 */
export function signatureOf(parts) {
  const s = (Array.isArray(parts) ? parts : []).map((p) => (p === null || p === undefined ? '' : String(p))).join('\u0001');
  // FNV-1a 32bit —— 足够判「变没变」，且零依赖、零加密成本（不是安全判据）。
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = (h * 0x01000193) >>> 0;
  }
  return `${s.length.toString(36)}-${h.toString(36)}`;
}

// ── 头像：内容指纹双层缓存（卡 §4③）──────────────────────

/** 头像稳定键（人 = `u:<userId>`；本账号 = `self`）。 */
export function avatarKey(userId) {
  const v = String(userId || '');
  return v ? `u:${v}` : 'self';
}

/** 群组合头像键（内容签名键：成员集合或任一成员头像变 ⇒ 键变 ⇒ 自然失效）。 */
export function groupAvatarKey(groupId, signature) {
  return `group:${String(groupId || '')}:${String(signature || '')}`;
}

function toHex(buf) {
  const bytes = new Uint8Array(buf);
  let out = '';
  for (const b of bytes) out += b.toString(16).padStart(2, '0');
  return out;
}

/**
 * 内容 sha256（卡 §4③ 「内容指纹」；服务端头优先、无则本地算）。
 * @param {ArrayBuffer|Uint8Array} bytes
 * @returns {Promise<string>}
 */
export async function sha256Hex(bytes) {
  try {
    if (typeof crypto !== 'undefined' && crypto.subtle && crypto.subtle.digest) {
      const buf = await crypto.subtle.digest('SHA-256', bytes);
      return toHex(buf);
    }
  } catch { /* 非安全上下文（http 非 localhost）⇒ 退回下面的轻量指纹 */ }
  // 无 crypto.subtle（老环境/非安全上下文）⇒ 用签名函数兜底：**只作「变没变」判据**
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let h = 0x811c9dc5;
  for (let i = 0; i < view.length; i++) {
    h ^= view[i];
    h = (h * 0x01000193) >>> 0;
  }
  return `fnv:${view.length.toString(36)}-${h.toString(36)}`;
}

function authHeaders() {
  try {
    const token = localStorage.getItem(key('token')) || '';
    return token ? { 'Authorization': `Bearer ${token}` } : {};
  } catch {
    return {};
  }
}

/** 单个人头像的同源取字节路由（卡 §3 前置项；本批新增，出生即包鉴权闸）。 */
function peerAvatarUrl(userId) {
  return `/api/avatars/${encodeURIComponent(String(userId))}`;
}

/**
 * 头像「磁盘未命中」时的建缓存腿（卡 §4③ 第 2/3 条）：
 *  · 源 URL 变化 / 无条目 ⇒ 取一次字节（同源路由，带 `If-None-Match`）；
 *  · 304 或 sha 未变 ⇒ **复用旧 blob 不重落盘**，只更元数据；
 *  · 失败 ⇒ 退避 1 h（10 s 状态拍不得反复打不可达的远端）。
 *
 * 🔴 渲染路径**不 await** 本函数（fire-and-forget）；它只负责让下一拍命中磁盘层。
 * @param {string} avatarKey
 * @param {{userId?: string, url?: string}} source
 * @returns {Promise<boolean>}
 */
export function ensureAvatar(avatarKey, source = {}) {
  if (!isLocalStoreEnabled() || !avatarKey) return Promise.resolve(false);
  const k = String(avatarKey);
  const userId = String(source.userId || '');
  const url = String(source.url || '');
  const prev = mirror.avatars.get(k);
  const now = Date.now();
  // 新鲜且源未变 ⇒ 零重取（卡 §4③ 第 1 条）
  if (prev && now - (prev.fetchedAt || 0) <= AVATAR_TTL_MS && (!url || prev.url === url)) return Promise.resolve(true);
  if (prev && prev.failedAt && now - prev.failedAt < AVATAR_BACKOFF_MS) return Promise.resolve(false);
  const inflight = inflightAvatars.get(k);
  if (inflight) return inflight;
  const run = (async () => {
    try {
      if (!userId) return false; // 无稳定身份键 ⇒ 不猜路由（禁造第二份取数面）
      const headers = authHeaders();
      if (prev && prev.sha256) headers['If-None-Match'] = `"${prev.sha256}"`;
      const resp = await fetch(peerAvatarUrl(userId), { headers, cache: 'no-store' });
      if (resp.status === 304) {
        // 内容未变 ⇒ 零重落盘，只更元数据（卡 §4③ 第 2 条的正落点）。
        // 🔴 只 merge 元数据字段：直接 put 一份不含 blob 的记录会把字节写没。
        await touchAvatarRecord(k, { fetchedAt: now, failedAt: 0 });
        return true;
      }
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const blob = await resp.blob();
      if (!blob || !blob.size) throw new Error('empty avatar blob');
      const sha = (resp.headers.get('X-Avatar-Sha256') || '') || (await sha256Hex(await blob.arrayBuffer()));
      const cur = mirror.avatars.get(k);
      if (cur && cur.sha256 && cur.sha256 === sha && cur.objectUrl) {
        // hash 相同 ⇒ 复用同一 objectURL 的旧 blob：**不重落盘**，只更元数据
        await touchAvatarRecord(k, { url: url || cur.url, fetchedAt: now, failedAt: 0 });
        return true;
      }
      await writeAvatar(k, { blob, sha256: sha, url, userId });
      return true;
    } catch {
      const cur = mirror.avatars.get(k);
      if (cur) cur.failedAt = Date.now();
      else {
        mirror.avatars.set(k, { objectUrl: '', url, sha256: '', fetchedAt: 0, lastAccessAt: 0, failedAt: Date.now() });
      }
      return false;
    } finally {
      inflightAvatars.delete(k);
    }
  })();
  inflightAvatars.set(k, run);
  return run;
}

/**
 * 头像记录的**元数据局部更新**（读改写：保留既有 `blob` 不动 —— 见 `ensureAvatar`
 * 两条「零重落盘」路径）。
 * @param {string} k
 * @param {{url?: string, fetchedAt?: number, failedAt?: number, lastAccessAt?: number}} patch
 */
async function touchAvatarRecord(k, patch) {
  const cur = mirror.avatars.get(k);
  if (cur) {
    if (patch.url !== undefined) cur.url = patch.url;
    if (patch.fetchedAt !== undefined) cur.fetchedAt = patch.fetchedAt;
    if (patch.failedAt !== undefined) cur.failedAt = patch.failedAt;
    if (patch.lastAccessAt !== undefined) cur.lastAccessAt = patch.lastAccessAt;
  }
  const row = await idbGet(S_AVATARS, k);
  if (!row) return false;
  return idbPut(S_AVATARS, { ...row, ...patch });
}

// ── 头像字节层的**有界延迟建缓存**（渲染路径零阻塞 / 低频）────────────────

/** key → {userId, url}；由渲染路径登记，空闲拍有界消费。 */
const pendingAvatarBuilds = new Map();
let drainScheduled = false;
/** 每次空闲拍最多取几枚（有界：首屏 200 行头像不得变 200 个请求）。 */
const AVATAR_BUILD_PER_DRAIN = 4;

/**
 * 渲染路径的**登记口**（零 await；本地层命中时直接返回，不发生任何请求）。
 * 同 key 只登记一次；已有新鲜字节或处于失败退避期 ⇒ 直接跳过。
 * @param {string} avatarKey
 * @param {{userId?: string, url?: string}} source
 */
export function buildAvatarLater(avatarKey, source = {}) {
  if (!isLocalStoreEnabled() || !avatarKey) return;
  const userId = String(source.userId || '');
  if (!userId) return; // 无稳定身份键 ⇒ 不猜路由
  const k = String(avatarKey);
  const e = mirror.avatars.get(k);
  const now = Date.now();
  if (e && e.objectUrl && now - (e.fetchedAt || 0) <= AVATAR_TTL_MS) return;
  if (e && e.failedAt && now - e.failedAt < AVATAR_BACKOFF_MS) return;
  pendingAvatarBuilds.set(k, { userId, url: String(source.url || '') });
  if (drainScheduled) return;
  drainScheduled = true;
  const run = () => {
    drainScheduled = false;
    void drainAvatarBuilds();
  };
  if (typeof requestIdleCallback === 'function') requestIdleCallback(run, { timeout: 3000 });
  else setTimeout(run, 1500);
}

async function drainAvatarBuilds() {
  const items = [...pendingAvatarBuilds.entries()].slice(0, AVATAR_BUILD_PER_DRAIN);
  for (const [k, src] of items) {
    pendingAvatarBuilds.delete(k);
    await ensureAvatar(k, src);
  }
  if (pendingAvatarBuilds.size && !drainScheduled) {
    drainScheduled = true;
    const run = () => {
      drainScheduled = false;
      void drainAvatarBuilds();
    };
    if (typeof requestIdleCallback === 'function') requestIdleCallback(run, { timeout: 3000 });
    else setTimeout(run, 1500);
  }
}

// ── 后台对账（卡 §4②；挂靠**既有** 10 s beacon，禁新增定时器）────────────

/**
 * 后台对账拍：① 预算收口（L5）；② 头像 TTL 兜底（过期只后台刷新，不阻塞渲染）。
 * 调用点 = `messages.js` 的 `backfillTick`（既有 `onStatusTick` beacon），
 * 由调用方做节流 —— 本函数自身不持定时器、不做长循环。
 * @returns {Promise<void>}
 */
export async function reconcile() {
  if (!isLocalStoreEnabled()) return;
  try {
    if (totalBytes() > BUDGET.totalSoft) await evict();
    const now = Date.now();
    /** @type {Array<{key: string, userId: string, url: string}>} */
    const stale = [];
    for (const [k, e] of mirror.avatars) {
      if (!e.url || e.failedAt) continue;
      if (now - (e.fetchedAt || 0) <= AVATAR_TTL_MS) continue;
      stale.push({ key: k, userId: k.startsWith('u:') ? k.slice(2) : '', url: e.url });
      if (stale.length >= 3) break; // 每拍最多 3 枚（低频、有界）
    }
    for (const s of stale) await ensureAvatar(s.key, s);
  } catch { /* 对账失败不得冒泡（下一次 beacon 再试） */ }
}
