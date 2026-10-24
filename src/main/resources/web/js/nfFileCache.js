// nfFileCache.js — 设备侧（浏览器端）已取文件的 blob 缓存账本（imgdoor #542 档2；
// M2 全数据面推广 · 设计 v1 §6）。
//
// 作者令 2026-10-20 逐字：「已点击下载过的文件（存在本机），应该也打开要很快。」
// ⇒ 档2：同一件文件第二次打开（新标签 / 刷新页面后重开）**零取数**直接渲染，
//   速度 = 档1（本机直读）体感。M2 把账本从 nf-file 两条腿推广为全数据面共享
//   （canvas 七 viewer / 设备取件 hash 副键 / 事件失效）。
//
// 形态（M2 起）：
//   · 字节存 Cache Storage（`caches`，同源持久，刷新页面仍在），**字节键 = 内容 id
//     （cid = 整件 sha256 前 16 hex；无 hash 条目回落取数键）**——同一份文件多设备
//     /多取数键只存一份字节（v1 §6.3 多设备去重；索引多键指向同一 Cache 条目，
//     引用计数淘汰：全部键出局才删字节）。`caches` 不可用（非安全上下文 / 老引擎）
//     ⇒ 退化为**会话级内存 Map**（同一刷新周期内仍命中，申报降级面，非静默失败）。
//   · 索引（LRU 序 + meta + 内容表）存 localStorage —— 键值都是小整数/短串；多标签
//     并发写同一索引是**可容忍竞态**（最坏 = 少记一次 LRU touch，字节上限由下次
//     put 重新收敛，v1 §6.4 申报保留）。v1 索引（无 cid/contents）读入即迁移。
//   · 淘汰 = **分桶预算**（v1 §6.2：local 128MiB / device 96MiB / server 32MiB，可配）
//     + 桶内 LRU——防一类大件把别桶全挤出局；**TTL 兜底不变**（默认 30min，与
//     nf-ticket TTL 同族）。**命中不做服务端再验证**（nf-file 无条件 GET，再验证 =
//     全量重下，违背档2 目标）：陈旧窗 ≤ TTL + 事件失效收敛（下条）。
//   · **事件失效**（v1 §6.1）：引擎侧写路径（Write/Edit/编辑器保存/删除/移动）广播
//     `nf-cache-invalidate {path}` ⇒ `nfCacheInvalidatePath` 精确淘汰；列目录的
//     size+mtime 读数 ⇒ `nfCacheReconcile` 顺带比对（version = `size:mtimeMs`），
//     不一致静默淘汰。TTL 是事件丢失时的兜底，语义不变。
//   · 配置（缺席 = 缺省值）：<home>/nebflow.json
//       "nfFileCache": { "enabled": true, "maxBytes": 268435456, "ttlMs": 1800000,
//                        "buckets": { "localBytes": 134217728, "deviceBytes": 100663296, "serverBytes": 33554432 } }
//     经既有 configData 通道 → `state.parsedConfig` 原样到达；改动刷新页面生效。
//     `maxBytes` = 全局硬顶（分桶预算各自再被它夹顶）；清缓存用户入口随 M2 表5
//     落在设置面板「高级」区（占用读数 + 清空键，consumer = sidebar.js）。
//
// 放置/依赖纪律：本模块只 import `state.js` 与 `branding.js`（localStorage 命名
// 空间），**不** import canvas/attachmentPreview/messages/nfTicket（不入任何环，
// scripts/check-circular.mjs 面；nfTicket 用**动态 import** 在取数腿内解——动态边
// 不进静态环）。本模块**不新增取字节路**：`fetchBlobWithProgress` 只是调用方自带
// 的**既有路由 URL**（nf-ticket 票据路 / friendsApi 鉴权路）的流式读出器；
// `nfViewerSource` 同理——URL 仍由 nfTicket 票据链铸造，账本只做「查→取→入账」。

import state from './state.js';
import { key } from './branding.js';

const CACHE_NAME = 'nf-file-cache-v1';
/** 全局硬顶缺省 256 MiB（v1 §2.2 既有值；nebflow.json `nfFileCache.maxBytes` 可改）。 */
const DEFAULT_MAX_BYTES = 256 * 1024 * 1024;
/** 缺省 TTL 30 分钟（与 nf-ticket TTL 同族；`nfFileCache.ttlMs` 可改）。 */
const DEFAULT_TTL_MS = 30 * 60 * 1000;
/** 分桶预算缺省（v1 §6.2 推荐值：local 128 / device 96 / server 32 MiB，可配）。 */
const DEFAULT_BUCKET_BYTES = {
  local: 128 * 1024 * 1024,
  device: 96 * 1024 * 1024,
  server: 32 * 1024 * 1024,
};
/** 配置下限保护：预算至少 1 MiB、TTL 至少 10s（防手误 0/负数把缓存打穿）。 */
const MIN_MAX_BYTES = 1024 * 1024;
const MIN_TTL_MS = 10 * 1000;
/** 索引 localStorage 键（branding 命名空间内）。 */
const INDEX_STORAGE_KEY = 'nfFileCacheV1';
/** Cache Storage 里的哑 URL 前缀（Cache.put 需要 http(s) Request；.invalid 保留 TLD）。 */
const DUMMY_URL_PREFIX = 'https://nf-cache.invalid/';

/** @typedef {{ size: number, at: number, last: number, ver: string, hash: string, cid: string, bucket: 'local'|'device'|'server', srcPath: string }} NfCacheMeta */
/** @typedef {{ size: number, refs: number }} NfCacheContent */
/** @typedef {{ v: number, bytes: number, order: string[], meta: Record<string, NfCacheMeta>, contents: Record<string, NfCacheContent> }} NfCacheIndex */

/** @type {NfCacheIndex} */
const index = { v: 2, bytes: 0, order: [], meta: {}, contents: {} };
/** 桶占用读数（增量维护；loadIndex 时重建）。 */
const bucketBytesMap = { local: 0, device: 0, server: 0 };
/** @type {Map<string, Blob>} 会话级内存退化面（`caches` 不可用时），键 = cid。 */
const memoryBlobs = new Map();
let indexLoaded = false;
/** @type {Promise<Cache>|null} caches.open 的单飞（不可用 ⇒ 恒 null）。 */
let cacheStorePromise = null;

function configNow() {
  const c = /** @type {any} */ (state.parsedConfig)?.nfFileCache;
  const maxBytesRaw = Number(c && c.maxBytes);
  const ttlRaw = Number(c && c.ttlMs);
  const b = c && c.buckets ? c.buckets : {};
  const clampBucket = (v, dflt) => {
    const n = Number(v);
    return Number.isFinite(n) && n > 0 ? Math.max(MIN_MAX_BYTES, n) : dflt;
  };
  return {
    enabled: !c || c.enabled !== false,
    maxBytes: Number.isFinite(maxBytesRaw) && maxBytesRaw > 0
      ? Math.max(MIN_MAX_BYTES, maxBytesRaw)
      : DEFAULT_MAX_BYTES,
    ttlMs: Number.isFinite(ttlRaw) && ttlRaw > 0
      ? Math.max(MIN_TTL_MS, ttlRaw)
      : DEFAULT_TTL_MS,
    buckets: {
      local: clampBucket(b.localBytes, DEFAULT_BUCKET_BYTES.local),
      device: clampBucket(b.deviceBytes, DEFAULT_BUCKET_BYTES.device),
      server: clampBucket(b.serverBytes, DEFAULT_BUCKET_BYTES.server),
    },
  };
}

/** 取数键 → 分桶（v1 §6.2 locality 分桶；显式桶参数胜，缺省按键前缀判）。 */
export function bucketOfKey(cacheKey, explicit) {
  if (explicit === 'local' || explicit === 'device' || explicit === 'server') return explicit;
  if (typeof cacheKey === 'string' && cacheKey.startsWith('dev:')) return 'device';
  if (typeof cacheKey === 'string' && cacheKey.startsWith('nf:')) return 'local';
  return 'server';
}

/** 索引惰性加载（首次使用时一次读 localStorage；v1 索引读入即迁移 v2）。 */
function loadIndex() {
  if (indexLoaded) return;
  indexLoaded = true;
  try {
    const raw = localStorage.getItem(key(INDEX_STORAGE_KEY));
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || !Array.isArray(parsed.order) || !parsed.meta) return;
    if (parsed.v === 1) {
      // v1 → v2 迁移：每键自持内容（cid = 键），桶按键前缀判。
      index.order = parsed.order;
      index.bytes = 0;
      for (const k of parsed.order) {
        const old = parsed.meta[k];
        if (!old) continue;
        const cid = k;
        const bucket = bucketOfKey(k);
        index.meta[k] = {
          size: old.size, at: old.at, last: old.last, ver: old.ver || '',
          hash: old.hash || '', cid, bucket, srcPath: '',
        };
        index.contents[cid] = { size: old.size, refs: 1 };
        index.bytes += old.size;
      }
      recomputeBucketBytes();
      saveIndex();
      return;
    }
    if (parsed.v === 2 && typeof parsed.bytes === 'number' && parsed.contents) {
      index.bytes = parsed.bytes;
      index.order = parsed.order;
      index.meta = parsed.meta;
      index.contents = parsed.contents;
      recomputeBucketBytes();
    }
  } catch { /* 损坏索引 = 无缓存起步（下次 put 重建） */ }
}

function recomputeBucketBytes() {
  bucketBytesMap.local = 0; bucketBytesMap.device = 0; bucketBytesMap.server = 0;
  for (const k of index.order) {
    const meta = index.meta[k];
    const c = meta && index.contents[meta.cid];
    if (meta && c) bucketBytesMap[meta.bucket] = (bucketBytesMap[meta.bucket] || 0) + c.size;
  }
}

function saveIndex() {
  try { localStorage.setItem(key(INDEX_STORAGE_KEY), JSON.stringify(index)); }
  catch { /* 配额/隐私模式：索引不落盘 ⇒ 本会话内存序仍有效 */ }
}

/** `caches` 是否可用（安全上下文限定；不可用 ⇒ 内存退化面）。 */
function backendAvailable() {
  return typeof caches !== 'undefined' && typeof caches.open === 'function';
}

/** @returns {Promise<Cache>|null} */
function cacheStore() {
  if (!backendAvailable()) return null;
  if (!cacheStorePromise) {
    cacheStorePromise = caches.open(CACHE_NAME).catch(() => null);
  }
  return cacheStorePromise;
}

function dummyUrl(cacheKey) {
  return DUMMY_URL_PREFIX + encodeURIComponent(cacheKey);
}

/** LRU touch + 索引落盘。 */
function touch(cacheKey, now) {
  const i = index.order.indexOf(cacheKey);
  if (i >= 0) index.order.splice(i, 1);
  index.order.push(cacheKey);
  const meta = index.meta[cacheKey];
  if (meta) meta.last = now;
  saveIndex();
}

/**
 * 内容引用减一：refs 归零 ⇒ 删字节（Cache Storage + 内存面）并扣全局字节账。
 * 多键指向同一 cid 时字节不动（v1 §6.3 引用计数淘汰纪律）。返回释放的字节数
 * （0 = 内容仍被其他键引用——桶账不扣，由调用方据此记账）。
 */
async function unrefContent(cid) {
  const c = index.contents[cid];
  if (!c) return 0;
  c.refs -= 1;
  if (c.refs > 0) return 0;
  delete index.contents[cid];
  index.bytes = Math.max(0, index.bytes - c.size);
  const store = await cacheStore();
  if (store) { try { await store.delete(dummyUrl(cid)); } catch { /* 忽略单条删除失败 */ } }
  memoryBlobs.delete(cid);
  return c.size;
}

/** 删一个键（引用计数感知）：摘序 + 摘 meta + 内容引用减一（内容真死才扣桶账）。 */
async function dropEntry(cacheKey) {
  const i = index.order.indexOf(cacheKey);
  if (i >= 0) index.order.splice(i, 1);
  const meta = index.meta[cacheKey];
  delete index.meta[cacheKey];
  if (meta) {
    const freed = await unrefContent(meta.cid);
    if (freed > 0) bucketBytesMap[meta.bucket] = Math.max(0, (bucketBytesMap[meta.bucket] || 0) - freed);
  }
  saveIndex();
}

/**
 * 按分桶预算淘汰（v1 §6.2）：只在**超预算的桶内**选 LRU 受害者（`keep` = 刚写入、
 * 不许淘的键；桶内只剩 keep 时不再自我淘汰，超预算由下一个 put 收敛——同全局版语义）。
 */
async function evictToBudget(keep, bucket) {
  const cfg = configNow();
  const budget = Math.min(cfg.buckets[bucket], cfg.maxBytes);
  for (;;) {
    if ((bucketBytesMap[bucket] || 0) <= budget) break;
    let victim = '';
    for (const k of index.order) {
      const meta = index.meta[k];
      if (!meta || meta.bucket !== bucket) continue;
      if (k === keep) continue;
      victim = k;
      break; // order[0] = 最旧
    }
    if (!victim) break; // 桶内只剩 keep（或空表）：不自我淘汰
    index.order.splice(index.order.indexOf(victim), 1);
    const meta = index.meta[victim];
    delete index.meta[victim];
    if (meta) {
      const freed = await unrefContent(meta.cid);
      if (freed > 0) bucketBytesMap[bucket] = Math.max(0, (bucketBytesMap[bucket] || 0) - freed);
    }
    saveIndex();
  }
}

/**
 * 命中读数：键在场且未过 TTL ⇒ Blob（LRU touch；字节按 cid 取——多键同内容共享
 * 一份）。否则按未命中处理并**顺手清掉**过期/孤儿条目。绝不抛（缓存故障 ⇒ 未命中，
 * 回到档3 取数腿）。
 * @param {string} cacheKey @returns {Promise<Blob|null>}
 */
export async function nfCachedBlob(cacheKey) {
  if (!cacheKey || !configNow().enabled) return null;
  loadIndex();
  const meta = index.meta[cacheKey];
  if (!meta) return null;
  const now = Date.now();
  if (now - meta.at >= configNow().ttlMs) {
    await dropEntry(cacheKey); // 过期：按未命中，重取即刷新
    return null;
  }
  const store = await cacheStore();
  if (store) {
    try {
      const hit = await store.match(dummyUrl(meta.cid));
      const blob = hit ? await hit.blob() : null;
      if (blob) { touch(cacheKey, now); return blob; }
    } catch { /* 读失败 ⇒ 未命中 */ }
  }
  const mem = memoryBlobs.get(meta.cid);
  if (mem) { touch(cacheKey, now); return mem; }
  // 索引在、字节不在（他端清了 Cache / 存储被逐）：清孤儿索引，按未命中。
  await dropEntry(cacheKey);
  return null;
}

/**
 * **hash 副键命中**（v1 §6.3 多设备同文件去重）：主键未命中时，用取件腿免费带回的
 * 整件 sha256 前 16 hex 查账——另一设备/另一键已缓存的**同内容**字节直接复用（字节
 * 已在浏览器，与它从哪台设备来无关）。命中即 touch 该条（LRU 记账）。
 * @param {string} hash16 @returns {Promise<Blob|null>}
 */
export async function nfCacheLookupByHash(hash16) {
  if (!hash16 || !configNow().enabled) return null;
  loadIndex();
  const now = Date.now();
  // 新→旧扫（LRU 尾部优先），找同内容且未过 TTL 的任何键。
  for (let i = index.order.length - 1; i >= 0; i--) {
    const k = index.order[i];
    const meta = index.meta[k];
    if (!meta) continue;
    if (meta.hash !== hash16 && meta.cid !== hash16) continue;
    if (now - meta.at >= configNow().ttlMs) continue; // 过期条目不作副键源
    const blob = await nfCachedBlob(k);
    if (blob) return blob;
  }
  return null;
}

/**
 * 写入（成功取数后调用；`void`-able，失败只降级为「下次重取」）。
 *
 * M2 内容寻址：`opts.hash`（整件 sha256 前 16 hex，设备分块取件腿免费副产品）在场
 * ⇒ cid = hash——**同内容多键只存一份字节**（再次 put 同 cid 仅加引用，零重复写）；
 * 无 hash ⇒ cid = 键（行为与 v1 等价）。超出**所在桶**预算的单件不缓存（永远淘不进）。
 *
 * @param {string} cacheKey @param {Blob} blob @param {string} [version]
 *   取数时顺手拿到的版本读数（`size:mtimeMs`；仅记录，命中判据 = TTL）
 * @param {{ hash?: string, srcPath?: string, bucket?: 'local'|'device'|'server' }} [opts]
 * @returns {Promise<void>}
 */
export async function nfCachePut(cacheKey, blob, version, opts) {
  if (!cacheKey || !blob || typeof blob.size !== 'number' || !(blob.size > 0)) return;
  if (!configNow().enabled) return;
  loadIndex();
  const bucket = bucketOfKey(cacheKey, opts && opts.bucket);
  const budget = Math.min(configNow().buckets[bucket], configNow().maxBytes);
  if (blob.size > budget) return; // 大于整个桶预算的单件不缓存
  const now = Date.now();
  const prev = index.meta[cacheKey];
  const cid = (opts && opts.hash) || (prev && prev.cid) || cacheKey;
  const existing = index.contents[cid];
  if (!existing) {
    // 新内容：写字节（Cache Storage，失败退化内存面）+ 记账（refs = 本键 1 个引用）。
    const store = await cacheStore();
    if (store) {
      try {
        await store.put(
          new Request(dummyUrl(cid)),
          new Response(blob, { headers: { 'Content-Type': blob.type || 'application/octet-stream' } })
        );
      } catch { /* Cache 写失败 ⇒ 退化到内存面 */ }
    }
    index.contents[cid] = { size: blob.size, refs: 1 };
    index.bytes += blob.size;
    bucketBytesMap[bucket] = (bucketBytesMap[bucket] || 0) + blob.size;
  } else if (!prev || prev.cid !== cid) {
    // 既有内容被一个**新键**链接：引用加一（同键重放同内容 = 已计过，不重复加）。
    index.contents[cid].refs += 1;
  }
  if (prev && prev.cid && prev.cid !== cid) await unrefContent(prev.cid); // 键换内容：旧引用减一
  index.meta[cacheKey] = {
    size: blob.size, at: now, last: now, ver: String(version || ''),
    hash: (opts && opts.hash) || '', cid, bucket, srcPath: (opts && opts.srcPath) || '',
  };
  const i = index.order.indexOf(cacheKey);
  if (i >= 0) index.order.splice(i, 1);
  index.order.push(cacheKey);
  if (!existing) memoryBlobs.set(cid, blob); // 内存面恒有（命中第二读数 + 无 caches 引擎的退化面）
  saveIndex();
  await evictToBudget(cacheKey, bucket);
}

/**
 * 单键淘汰（引用计数感知；410 对账 / 事件失效共用）：字节还有别的键引用 ⇒ 只摘键；
 * 最后一个引用出局 ⇒ 删字节。
 * @param {string} cacheKey @returns {Promise<boolean>} true = 确有一条被摘。
 */
export async function nfCacheDrop(cacheKey) {
  if (!cacheKey) return false;
  loadIndex();
  if (!index.meta[cacheKey]) return false;
  await dropEntry(cacheKey);
  return true;
}

/**
 * 事件失效（v1 §6.1）：引擎广播 `nf-cache-invalidate {path}` ⇒ 精确淘汰——
 * ① 主键恰为 `nf:<path>` 的条目；② `srcPath` 记录了该路径的条目（设备取件等
 * 间接键）。返回被淘汰键数（诊断读数）。
 * @param {string} path @returns {Promise<number>}
 */
export async function nfCacheInvalidatePath(path) {
  if (!path) return 0;
  loadIndex();
  /** @type {string[]} */
  const hits = [];
  const direct = 'nf:' + path;
  if (index.meta[direct]) hits.push(direct);
  for (const k of index.order) {
    const meta = index.meta[k];
    if (meta && meta.srcPath && meta.srcPath === path && !hits.includes(k)) hits.push(k);
  }
  for (const k of hits) await dropEntry(k);
  return hits.length;
}

/**
 * 顺带对账（v1 §6.1「取数时顺带比对」腿）：消费**本来就流动着**的元数据
 * （列目录条目 `[{path,size,mtimeMs}]`），与账本 version（`size:mtimeMs`）不一致 ⇒
 * 静默淘汰该条（下次打开按未命中走）。命中零取数纪律不受影响——本函数只在
 * 「其他原因到达服务端的往返」里被动消费数据，零新增请求。
 * @param {{ path: string, size: number, mtimeMs?: number }[]} entries
 * @returns {Promise<number>} 被淘汰键数（诊断读数）。
 */
export async function nfCacheReconcile(entries) {
  if (!Array.isArray(entries) || entries.length === 0) return 0;
  loadIndex();
  /** @type {string[]} */
  const hits = [];
  for (const e of entries) {
    if (!e || !e.path) continue;
    const k = 'nf:' + e.path;
    const meta = index.meta[k];
    if (!meta || !meta.ver) continue;
    const mtime = Number(e.mtimeMs) || 0;
    if (!mtime) continue; // 无 mtime 读数不比对（保守：宁可放过不可误杀）
    const current = `${Number(e.size) || 0}:${mtime}`;
    if (meta.ver !== current) hits.push(k);
  }
  for (const k of hits) await dropEntry(k);
  return hits.length;
}

/**
 * 读数面（测试/诊断 seam）：引擎面板、spec 断言用。
 * @returns {{ enabled: boolean, backend: 'caches'|'memory', entries: number, bytes: number,
 *   maxBytes: number, ttlMs: number, buckets: {local: number, device: number, server: number},
 *   bucketBudgets: {local: number, device: number, server: number} }}
 */
export function nfCacheStats() {
  loadIndex();
  const cfg = configNow();
  return {
    enabled: cfg.enabled,
    backend: backendAvailable() ? 'caches' : 'memory',
    entries: index.order.length,
    bytes: index.bytes,
    maxBytes: cfg.maxBytes,
    ttlMs: cfg.ttlMs,
    buckets: { local: bucketBytesMap.local || 0, device: bucketBytesMap.device || 0, server: bucketBytesMap.server || 0 },
    bucketBudgets: cfg.buckets,
  };
}

/** 清空索引 + 字节。用户入口 = 设置面板「清空文件缓存」键（M2 表5，v1 §6.4 建议
 *  项落地；consumer 唯 sidebar.js；spec seam 同用）。 */
export async function nfCacheReset() {
  loadIndex();
  const store = await cacheStore();
  for (const cid of Object.keys(index.contents)) {
    if (store) { try { await store.delete(dummyUrl(cid)); } catch { /* 逐条删 */ } }
  }
  index.order = [];
  index.meta = {};
  index.contents = {};
  index.bytes = 0;
  bucketBytesMap.local = 0; bucketBytesMap.device = 0; bucketBytesMap.server = 0;
  memoryBlobs.clear();
  saveIndex();
}

// ── canvas viewer 接账本（M2 · 设计 v1 §7 M1-2 本批落地）────────────────────
// 七个 viewer（image/pdf/docx/xlsx/pptx/epub/video）的既有取数形态 =
// `objectUrl || await ticketUrl(absPath)`：objectUrl 腿（字节已在手的附件/Pop 面）
// 原样；票据腿接账本——`nf:<absPath>` 命中零取数（blob URL 直渲染），未命中走
// **既有票据链**（动态 import nfTicket，URL 铸造不归账本）并 `nfCachePut` 入账。
// 第二次打开（新标签/刷新后重开）= 零取数直渲染。

/** paneEl → blob URL 登记表：tab 关闭（canvas-tab-closed 广播）后 pane 已断连 ⇒
 *  撤 URL（禁泄漏；attachmentPreview.js 同款事件，零 import——document 级监听）。
 *  同 pane 多 URL（重试/重渲）全登记，断连一并撤。 */
/** @type {Map<Element, Set<string>>} */
const viewerUrls = new Map();
let viewerRevokeBound = false;

/** 会话内已知的路径→整件 hash 读数（设备取件腿 fileContent 帧免费带回；viewer
 *  取数入账时作 cid——同内容跨设备/跨键只存一份字节，v1 §6.3）。 */
/** @type {Map<string, string>} 键 = 'nf:<absPath>' → hash16 */
const knownHashes = new Map();

/**
 * 记一条路径的整件 hash（explorer.js `fileContent` 成功腿调用；会话级，无持久化）。
 * @param {string} absPath @param {string} hash16
 */
export function nfCacheNoteHash(absPath, hash16) {
  if (absPath && hash16 && typeof hash16 === 'string') knownHashes.set('nf:' + absPath, hash16.slice(0, 16));
}

function trackViewerUrl(pane, url) {
  let set = viewerUrls.get(pane);
  if (!set) { set = new Set(); viewerUrls.set(pane, set); }
  set.add(url);
  if (!viewerRevokeBound) {
    viewerRevokeBound = true;
    document.addEventListener('canvas-tab-closed', () => {
      for (const [paneEl, urls] of [...viewerUrls]) {
        if (!paneEl.isConnected) {
          viewerUrls.delete(paneEl);
          for (const u of urls) setTimeout(() => URL.revokeObjectURL(u), 1000);
        }
      }
    });
  }
  return url;
}

/**
 * viewer 取数单点（**字节路由零新增**：票据腿仍走 `/api/nf-file?path&ticket`）。
 * @param {{ absPath: ?string, objectUrl: ?string, onProgress?: ((loaded: number, total: number) => void)|null, pane?: Element }} p
 * @returns {Promise<{ url: string, cached: boolean }>} `url` = objectUrl（腿A 原样 /
 *   账本命中新建 / 票据腿取回新建）；`cached` = 本次是否走了账本字节（诊断读数）。
 */
export async function nfViewerSource(p) {
  const { absPath, objectUrl, onProgress, pane } = p || {};
  if (objectUrl) return { url: objectUrl, cached: false };
  if (!absPath) return { url: '', cached: false };
  const cacheKey = 'nf:' + absPath;
  const hit = await nfCachedBlob(cacheKey);
  if (hit) {
    const url = trackViewerUrl(pane || document.body, URL.createObjectURL(hit));
    return { url, cached: true };
  }
  // 未命中：票据链（动态 import——静态边会成环：nfTicket ← viewers/shared ← …）。
  // 大文件 LAN 直传腿（console-fastpath 批·腿③）：≥4MiB 且近路在册 ⇒ 绝对
  // URL 跨源直取（凭据仍 ticket，服务端 CORS/PNA 已放）；否则原票据腿。
  const nfTicket = await import('./nfTicket.js');
  let url = (await nfTicket.largeFileDirectUrl(absPath)) || (await nfTicket.ticketUrl(absPath));
  // ── 票链自愈 + 升级路径（Pop 图片 403 批 2026-10-24）────────────────────
  // 修前形态：取数腿 401/403 直抛「HTTP 403」进各 viewer 终态错误面板——无重试
  // 无提示升级路径（<img> onerror 腿的 reMint 自愈对本腿不可达：失败是 fetch
  // 抛出，不是元素 error 事件）。两个 403 源分治：
  //   · 网关形状（X-Nf-Reason 在场：invalid-ticket/expired/path-mismatch/
  //     credential-*）⇒ 重铸一票重试恰一次（T3 语义同 image.js）；确定性拒
  //     （credential-*）二次同败即终态，不成环。
  //   · relay 门形状（relayGate：中继平面凭据死）⇒ 重铸无意义（铸票同门被
  //     拒）⇒ 交 sessionGate 统一重认证伞（可见横幅 + 自愈 reload——两条
  //     已设计落点），不再静默死亡。
  let res = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      res = await fetchBlobWithProgress(url, onProgress || null);
      break;
    } catch (e) {
      const status = e && /** @type {any} */ (e).status;
      if (attempt === 0 && (status === 401 || status === 403)) {
        if (/** @type {any} */ (e).relayGate) {
          try {
            const sg = await import('./sessionGate.js');
            sg.noteAuthRejected('nf-file');
          } catch (e2) { /* 伞缺席：仍落下方错误面 */ }
          throw e;
        }
        const fresh = await nfTicket.reMint(absPath);
        if (fresh && fresh !== url && /[?&]ticket=/.test(fresh)) {
          url = fresh;
          continue;
        }
      }
      throw e;
    }
  }
  if (!res) throw new Error('nfViewerSource: fetch failed');
  // 入账带 hash（设备取件腿已知时）+ srcPath（事件失效/对账的路径锚）。
  void nfCachePut(cacheKey, res.blob, res.version, {
    hash: knownHashes.get(cacheKey) || '',
    srcPath: absPath,
  });
  const url2 = trackViewerUrl(pane || document.body, URL.createObjectURL(res.blob));
  return { url: url2, cached: false };
}

/**
 * viewer URL 简形（七个 viewer 的 `objectUrl || await ticketUrl(absPath)` 直换形态；
 * 语义 = [[nfViewerSource]] 只取 url）。`maxBytes` 在场且声明尺寸超限 ⇒ 不走账本
 * （保持票据 URL 流式/Range 原状——视频 seek 依赖 Range，大件整收是 UX 回归；
 * 阈值 64 MiB 与 96MiB device 桶/128MiB local 桶留距，超预算本也不可缓存）。
 * @param {{ absPath: ?string, objectUrl: ?string, pane?: Element, size?: number, maxBytes?: number }} p
 * @returns {Promise<string>}
 */
export async function nfViewerUrl(p) {
  const sizeOk = !p.size || !p.maxBytes || p.size <= p.maxBytes;
  const src = sizeOk
    ? await nfViewerSource(p)
    : { url: p.objectUrl || await (await import('./nfTicket.js')).ticketUrl(/** @type {string} */ (p.absPath)), cached: false };
  return src.url;
}

// ── 历史页指纹账（M2 · 设计 v2 §3.1 ② 键族扩展）────────────────────────────
// `hist:<sessionId>` → 最近一次 historyPage 帧的页指纹 `{total, offset, pageLen,
// last, lastAt, at}`。**只存键不存渲染**（载荷小——文本页不入 256MiB 字节预算，决策
// 记录在案防过度设计，v2 §3.1 ② 原注）；「本地先行渲染」的渲染语义 = 设计件开放
// 问题 Q18（候作者裁），本账只提供重连/翻页「同页否」的对账基础。LRU 上限 40 会话。

const HIST_FP_KEY = 'nfHistoryFingerprintsV1';
const HIST_FP_CAP = 40;

/** @returns {{ fps: Record<string, any>, order: string[] }} */
function loadHistFps() {
  try {
    const raw = localStorage.getItem(key(HIST_FP_KEY));
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && parsed.fps && Array.isArray(parsed.order)) return parsed;
    }
  } catch { /* 损坏 = 空账起步 */ }
  return { fps: {}, order: [] };
}

/**
 * 记一帧页指纹（main.js `historyPage` 订阅点调用；失败不响——读数面纪律）。
 * @param {string} sessionId @param {any} fp
 */
export function noteHistoryFingerprint(sessionId, fp) {
  if (!sessionId || !fp || typeof fp !== 'object') return;
  const { fps, order } = loadHistFps();
  const i = order.indexOf(sessionId);
  if (i >= 0) order.splice(i, 1);
  order.push(sessionId);
  while (order.length > HIST_FP_CAP) {
    const evict = order.shift();
    if (evict) delete fps[evict];
  }
  fps[sessionId] = { total: fp.total || 0, offset: fp.offset || 0, pageLen: fp.pageLen || 0, last: fp.last ?? -1, lastAt: fp.lastAt || 0, at: Date.now() };
  try { localStorage.setItem(key(HIST_FP_KEY), JSON.stringify({ fps, order })); } catch { /* 配额：账不落盘，会话内仍有效 */ }
}

/**
 * 读一帧页指纹（重连/翻页对账用；本批消费方 = spec + 后续 Q18 裁定后的本地先行腿）。
 * @param {string} sessionId @returns {any|null}
 */
export function getHistoryFingerprint(sessionId) {
  const { fps } = loadHistFps();
  return fps[sessionId] || null;
}

// ── 过载退避重试（#750 · nf-file 媒体面高载 503 硬失败批）─────────────────────
// 作者实测（2026-10-22 18:52，五 JVM 并行构建压载）：文档里点图报
// 「Failed to render: HTTP 503」——网关文件腿过载时上游回裸 503（同源旁证
// 18:42 relay uplink inbox full），本函数直抛 `HTTP 503` 进 viewer 终态错误，
// 用户唯一出路是刷新整页。本批给取数腿补**过载退避语义**（服务端对偶腿 =
// NfServeGate 的 429 + Retry-After）：
//   · 429/503 = 可重试的过载信号（非确定性失败）⇒ 按 Retry-After（缺席/非法 =
//     缺省 1s）短退避后**恰一次**重试；
//   · 第二败即定局（照旧抛 `HTTP <status>` 进各 viewer 既有失败面）；
//   · 其余状态码照旧直抛（4xx = 确定性失败，重试无意义）。
// 🔴 红线（零超时设计）：Retry-After 是服务端建议的等待时长（恒定钳在
// [0.5s, 5s]），不是任何 deadline/超时判据；重试恰一次，不成环不成闸。
const OVERLOAD_RETRY_STATUSES = new Set([429, 503]);
const OVERLOAD_RETRY_DEFAULT_MS = 1000;
const OVERLOAD_RETRY_MIN_MS = 500;
const OVERLOAD_RETRY_MAX_MS = 5000;

/** Retry-After 头 → 等待毫秒（只认非负整数秒；缺席/非法 = 缺省 1s；钳位见上）。 */
function overloadRetryDelayMs(resp) {
  const raw = Number(resp.headers.get('Retry-After'));
  const ms = Number.isFinite(raw) && raw >= 0 ? raw * 1000 : OVERLOAD_RETRY_DEFAULT_MS;
  return Math.min(Math.max(ms, OVERLOAD_RETRY_MIN_MS), OVERLOAD_RETRY_MAX_MS);
}

/**
 * 流式取数器（**字节路由由调用方给定**——既有票据路/鉴权路 URL，禁第三条路）：
 * 边收边报进度（`onProgress(loaded, total)`；`total` 来自 Content-Length，
 * 缺席 = 0 ⇒ 调用方转不定态转圈）。失败抛错（status 挂在 error.status 上），
 * 调用方出**可见**失败态（档3 可见面，禁静默）。过载面例外：429/503 在抛出前
 * 恰一次短退避重试（见上方块注释）。
 *
 * 返回 `version` = `size:mtimeMs`（Last-Modified 头可得时；不可得 = `size:0`）——
 * 档2 条目的版本读数（`nfCacheReconcile` 对账用；命中判据见上「TTL」）。
 * @param {string} url
 * @param {((loaded: number, total: number) => void)|null} [onProgress]
 * @returns {Promise<{blob: Blob, total: number, lastModified: string, version: string}>}
 */
export async function fetchBlobWithProgress(url, onProgress) {
  const send = () => fetch(url, { credentials: 'same-origin', cache: 'no-store' });
  let resp = await send();
  // #750：过载腿（429/503）退避后恰一次重试。失败 body 先 cancel 归还连接，
  // 再按 Retry-After 等一拍——等待时长是服务端建议，不是本端测时。
  if (!resp.ok && OVERLOAD_RETRY_STATUSES.has(resp.status)) {
    const delayMs = overloadRetryDelayMs(resp);
    try { if (resp.body) await resp.body.cancel(); } catch { /* 已断的 body 不必收拾 */ }
    await new Promise((r) => setTimeout(r, delayMs));
    resp = await send();
  }
  if (!resp.ok) {
    const err = /** @type {Error & {status?: number, nfReason?: string, relayGate?: boolean}} */ (new Error('HTTP ' + resp.status));
    err.status = resp.status;
    let reason = '';
    let jsonish = false;
    try { reason = resp.headers.get('X-Nf-Reason') || ''; } catch (e) { /* 无头面：只留裸 status */ }
    try { jsonish = /json/i.test(resp.headers.get('Content-Type') || ''); } catch (e) { /* 同上 */ }
    err.nfReason = reason;
    // relay ingress 拒答形状（link-server require_user，Pop 图片 403 批 2026-10-24）：
    // 403/401 + JSON 体 + 无 X-Nf-Reason。网关全出向 401/403 一律 text/plain 且
    // verdict/票拒带 X-Nf-Reason——两种形状机械可分：relayGate = 中继平面凭据死
    // （重铸票无意义，铸票过同一道门），调用面据此升级可见（sessionGate 伞），
    // 而不是落成「Failed to render: HTTP 403」死文案。
    err.relayGate = (resp.status === 401 || resp.status === 403) && !reason && jsonish;
    if (reason) err.message = 'HTTP ' + resp.status + ' (' + reason + ')';
    throw err;
  }
  const lastModified = resp.headers.get('Last-Modified') || '';
  const lmMs = Date.parse(lastModified) || 0; // HTTP-date → epoch ms（对账口径与列目录 mtime 同轴）
  const lenHeader = Number(resp.headers.get('Content-Length'));
  const total = Number.isFinite(lenHeader) && lenHeader > 0 ? lenHeader : 0;
  /** @type {Blob} */
  let blob;
  if (resp.body && typeof resp.body.getReader === 'function') {
    const reader = resp.body.getReader();
    /** @type {Uint8Array[]} */
    const chunks = [];
    let loaded = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value && value.length) { chunks.push(value); loaded += value.length; }
      if (onProgress) onProgress(loaded, total);
    }
    blob = new Blob(/** @type {BlobPart[]} */ (chunks));
  } else {
    blob = await resp.blob(); // 无流式 body 的引擎：整收（进度退化为一次 100%）
    if (onProgress) onProgress(blob.size, blob.size || total);
  }
  const finalTotal = total || blob.size;
  return { blob, total: finalTotal, lastModified, version: finalTotal + ':' + lmMs };
}
