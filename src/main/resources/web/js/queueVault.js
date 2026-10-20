// queueVault.js — 消息队列附件**载荷库**（IndexedDB）。
//
// 链路定性（qattachvault 修复批，2026-10-21）：选图 → pendingAttachments{data,preview}
// → WS 未开/LLM 忙入队（input.js queueMessage，内存项带全量字节）→ persistQueue 把
// image 附件剥成 {type,name,mimeType,hash:'',size:0,stripped:true}（localStorage 装不
// 下数百 KB 的 base64 ⇒ data/preview 从不落任何介质）→ 页面刷新 restoreQueue 用骨架
// **替换**内存态 ⇒ 字节永久丢失 → 重连派发帧 `data: undefined` ⇒ JSON 丢键 ⇒ 服务端
// （WebSocketRoutes.scala:1621 image 落盘需 data.nonEmpty）判「无数据」⇒ **零字节落盘**
// ⇒ ui.json 落 path:null ⇒ 接收面永远「媒体加载失败」。
//
// 本模块 = 缺的那块介质：入队时把附件 base64 写进 IDB（键 = sid:itemId:attIdx，队列项
// 元数据仍走 localStorage persistQueue 轨），刷新后水合（input.js hydrateQueueFromVault）
// 把字节灌回内存项 ⇒ 派发帧带全量 data ⇒ 服务端落 uploads ⇒ path 真值。水合不中
// （库被清 / 写失败 / IDB 不可用）⇒ 派发闸拒绝出线（显式失败态可重传，禁静默、禁空心帧）。
//
// 纪律（沿 localStore.js 三条硬纪律的同款口径）：
//   1. **零顶层副作用**：模块求值不碰 indexedDB —— 打开是**惰性**的（首次操作才开），
//      构建管线 define+DCE 后不残留死代码；
//   2. **叶子模块**：只 import branding.js（存储键命名空间，与 message_queue 同律），
//      不 import 任何业务件 ⇒ 不进 check-circular 的 SCC；
//   3. **降级有形**：IDB 不可用（隐私模式/配额）= L1 ⇒ 写返回 false、读返回 null，
//      绝不抛错打断入队/派发链；后果由派发闸的**显式失败态**承担（可见，不静默）。

import { key } from './branding.js';

/** @type {Promise<IDBDatabase|null>|null} 惰性单连接（失败/被 blocking ⇒ null）。 */
let dbPromise = null;

/** DB 名（brand 命名空间，与 localStorage 的 message_queue 键同律）。 */
function dbName() {
  return key('queue_vault');
}

function openDb() {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  if (!dbPromise) {
    dbPromise = new Promise((resolve) => {
      let req;
      try {
        req = indexedDB.open(dbName(), 1);
      } catch { resolve(null); return; }
      req.onupgradeneeded = () => {
        try {
          const db = req.result;
          if (!db.objectStoreNames.contains('payloads')) db.createObjectStore('payloads');
        } catch { /* 升级失败 ⇒ 后续操作按 L1 降级 */ }
      };
      req.onsuccess = () => {
        const db = req.result;
        db.onversionchange = () => { try { db.close(); } catch { /* 已关 */ } dbPromise = null; };
        resolve(db);
      };
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    });
  }
  return dbPromise;
}

/** IDB 载荷库是否可用（L1 探针；调用方只用于清扫/测试分支，不用于拦截入队）。 */
export function available() {
  return typeof indexedDB !== 'undefined';
}

/** 队列载荷键（唯一拼键点）：`<sid>:<itemId>:<attIdx>`；sid 缺省钉成 'null'
 *  （与 localStorage Object.entries 对 null 键的字符串化一致，读写两侧必然对上）。 */
export function queueVaultKey(sid, itemId, attIdx) {
  return `${(sid === undefined || sid === null) ? 'null' : String(sid)}:${String(itemId)}:${String(attIdx)}`;
}

function tx(db, mode) {
  return db.transaction('payloads', mode).objectStore('payloads');
}

/** 写入一条 base64 载荷。成功 true；任何失败 false（L1/L2 降级，不抛）。 */
export function put(k, b64) {
  if (typeof b64 !== 'string' || b64.length === 0) return Promise.resolve(false);
  return openDb().then((db) => new Promise((resolve) => {
    if (!db) { resolve(false); return; }
    try {
      const req = tx(db, 'readwrite').put(b64, k);
      req.onsuccess = () => resolve(true);
      req.onerror = () => resolve(false);
    } catch { resolve(false); }
  }));
}

/** 读取一条载荷；不命中/失败 ⇒ null（不抛）。 */
export function get(k) {
  return openDb().then((db) => new Promise((resolve) => {
    if (!db) { resolve(null); return; }
    try {
      const req = tx(db, 'readonly').get(k);
      req.onsuccess = () => resolve(typeof req.result === 'string' ? req.result : null);
      req.onerror = () => resolve(null);
    } catch { resolve(null); }
  }));
}

/** 删除一条载荷（派发成功/项离队后的清账；失败静默）。 */
export function del(k) {
  return openDb().then((db) => new Promise((resolve) => {
    if (!db) { resolve(false); return; }
    try {
      const req = tx(db, 'readwrite').delete(k);
      req.onsuccess = () => resolve(true);
      req.onerror = () => resolve(false);
    } catch { resolve(false); }
  }));
}

/** 全量键列表（孤儿清扫用；失败 ⇒ []）。 */
export function keys() {
  return openDb().then((db) => new Promise((resolve) => {
    if (!db) { resolve([]); return; }
    try {
      const req = tx(db, 'readonly').getAllKeys();
      req.onsuccess = () => resolve(Array.isArray(req.result) ? req.result.map(String) : []);
      req.onerror = () => resolve([]);
    } catch { resolve([]); }
  }));
}

/** 清空载荷库（维护/测试面：水合前模拟「字节彻底丢失」的失败可见腿）。 */
export function wipe() {
  return openDb().then((db) => new Promise((resolve) => {
    if (!db) { resolve(false); return; }
    try {
      const req = tx(db, 'readwrite').clear();
      req.onsuccess = () => resolve(true);
      req.onerror = () => resolve(false);
    } catch { resolve(false); }
  }));
}
