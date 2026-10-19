// nfFileCache.js — 设备侧（浏览器端）已取文件的 blob 缓存（imgdoor #542 档2）。
//
// 作者令 2026-10-20 逐字：「已点击下载过的文件（存在本机），应该也打开要很快。」
// ⇒ 档2：同一件文件第二次打开（新标签 / 刷新页面后重开）**零取数**直接渲染，
//   速度 = 档1（本机直读）体感。
//
// 形态：
//   · 字节存 Cache Storage（`caches`，同源持久，刷新页面仍在）；`caches` 不可用
//     （非安全上下文 / 老引擎）⇒ 退化为**会话级内存 Map**（同一刷新周期内仍命中，
//     报告申报的降级面，非静默失败）。
//   · 索引（LRU 序 + 字节数 + 时间戳）存 localStorage —— 键值都是小整数/短串，
//     与 5MB 配额相比可忽略；多标签并发写同一索引是**可容忍竞态**（最坏情形 =
//     少记一次 LRU touch，字节上限由下次 put 重新收敛），报告申报。
//   · 淘汰 = LRU + 字节预算（默认 256 MiB）；过期 = TTL（默认 30min，与 nf-ticket
//     TTL 同族）——TTL 过后视为未命中，重取即刷新。**命中不做服务端再验证**
//     （nf-file 无条件 GET，再验证 = 全量重下，违背档2 目标）：陈旧窗 ≤ TTL，
//     与浏览器 HTTP 缓存同级的陈旧语义，报告申报。
//   · 配置（本批新增键，缺席 = 缺省值）：<home>/nebflow.json
//       "nfFileCache": { "enabled": true, "maxBytes": 268435456, "ttlMs": 1800000 }
//     经既有 configData 通道 → `state.parsedConfig` 原样到达（featureFlags.js 同款
//     机制，零新机制）；改动刷新页面生效。清缓存入口本批**不做**（候作者示）。
//
// 放置/依赖纪律：本模块只 import `state.js`（config 读数）与 `branding.js`
// （localStorage 命名空间），**不** import canvas/attachmentPreview/messages ⇒
// 不入任何环（scripts/check-circular.mjs 面）。
// 本模块**不取数**：`fetchBlobWithProgress` 只是调用方自带的**既有路由 URL**
// （nf-ticket 票据路 / friendsApi 鉴权路）的流式读出器，禁第三条取字节路。

import state from './state.js';
import { key } from './branding.js';

const CACHE_NAME = 'nf-file-cache-v1';
/** 缺省字节预算 256 MiB（报告申报值；nebflow.json `nfFileCache.maxBytes` 可改）。 */
const DEFAULT_MAX_BYTES = 256 * 1024 * 1024;
/** 缺省 TTL 30 分钟（与 nf-ticket TTL 同族；`nfFileCache.ttlMs` 可改）。 */
const DEFAULT_TTL_MS = 30 * 60 * 1000;
/** 配置下限保护：预算至少 1 MiB、TTL 至少 10s（防手误 0/负数把缓存打穿）。 */
const MIN_MAX_BYTES = 1024 * 1024;
const MIN_TTL_MS = 10 * 1000;
/** 索引 localStorage 键（branding 命名空间内）。 */
const INDEX_STORAGE_KEY = 'nfFileCacheV1';
/** Cache Storage 里的哑 URL 前缀（Cache.put 需要 http(s) Request；.invalid 保留 TLD）。 */
const DUMMY_URL_PREFIX = 'https://nf-cache.invalid/';

/** @typedef {{ size: number, at: number, last: number, ver: string }} NfCacheMeta */
/** @typedef {{ v: number, bytes: number, order: string[], meta: Record<string, NfCacheMeta> }} NfCacheIndex */

/** @type {NfCacheIndex} */
const index = { v: 1, bytes: 0, order: [], meta: {} };
/** @type {Map<string, Blob>} 会话级内存退化面（`caches` 不可用时）。 */
const memoryBlobs = new Map();
let indexLoaded = false;
/** @type {Promise<Cache>|null} caches.open 的单飞（不可用 ⇒ 恒 null）。 */
let cacheStorePromise = null;

function configNow() {
  const c = /** @type {any} */ (state.parsedConfig)?.nfFileCache;
  const maxBytesRaw = Number(c && c.maxBytes);
  const ttlRaw = Number(c && c.ttlMs);
  return {
    enabled: !c || c.enabled !== false,
    maxBytes: Number.isFinite(maxBytesRaw) && maxBytesRaw > 0
      ? Math.max(MIN_MAX_BYTES, maxBytesRaw)
      : DEFAULT_MAX_BYTES,
    ttlMs: Number.isFinite(ttlRaw) && ttlRaw > 0
      ? Math.max(MIN_TTL_MS, ttlRaw)
      : DEFAULT_TTL_MS,
  };
}

/** 索引惰性加载（首次使用时一次读 localStorage）。 */
function loadIndex() {
  if (indexLoaded) return;
  indexLoaded = true;
  try {
    const raw = localStorage.getItem(key(INDEX_STORAGE_KEY));
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (parsed && parsed.v === 1 && typeof parsed.bytes === 'number' && Array.isArray(parsed.order) && parsed.meta) {
      index.bytes = parsed.bytes;
      index.order = parsed.order;
      index.meta = parsed.meta;
    }
  } catch { /* 损坏索引 = 无缓存起步（下次 put 重建） */ }
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

/** 按 LRU 淘汰到预算内（`keep` = 刚写入、不许淘的键）。 */
async function evictToBudget(keep) {
  const budget = configNow().maxBytes;
  while (index.bytes > budget && index.order.length > 0) {
    const victim = index.order[0];
    if (victim === keep && index.order.length > 1) {
      // 唯一幸存者也超预算：不自我淘汰（超预算由下一个 put 收敛）。
      index.order.push(index.order.shift());
      if (index.order[0] !== victim) continue;
      break;
    }
    index.order.shift();
    const meta = index.meta[victim];
    if (meta) index.bytes = Math.max(0, index.bytes - meta.size);
    delete index.meta[victim];
    const store = await cacheStore();
    if (store) { try { await store.delete(dummyUrl(victim)); } catch { /* 忽略单条删除失败 */ } }
    memoryBlobs.delete(victim);
    saveIndex();
  }
}

async function dropEntry(cacheKey) {
  const i = index.order.indexOf(cacheKey);
  if (i >= 0) index.order.splice(i, 1);
  const meta = index.meta[cacheKey];
  if (meta) index.bytes = Math.max(0, index.bytes - meta.size);
  delete index.meta[cacheKey];
  const store = await cacheStore();
  if (store) { try { await store.delete(dummyUrl(cacheKey)); } catch { /* 同上 */ } }
  memoryBlobs.delete(cacheKey);
  saveIndex();
}

/**
 * 命中读数：键在场且未过 TTL ⇒ Blob（LRU touch）；否则按未命中处理并**顺手清掉**
 * 过期/孤儿条目。绝不抛（缓存故障 ⇒ 未命中，回到档3 取数腿）。
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
      const hit = await store.match(dummyUrl(cacheKey));
      const blob = hit ? await hit.blob() : null;
      if (blob) { touch(cacheKey, now); return blob; }
    } catch { /* 读失败 ⇒ 未命中 */ }
  }
  const mem = memoryBlobs.get(cacheKey);
  if (mem) { touch(cacheKey, now); return mem; }
  // 索引在、字节不在（他端清了 Cache / 存储被逐）：清孤儿索引，按未命中。
  await dropEntry(cacheKey);
  return null;
}

/**
 * 写入（成功取数后调用；`void`-able，失败只降级为「下次重取」）。超出预算按
 * LRU 淘汰旧条目（刚写入的键本身不淘）。
 * @param {string} cacheKey @param {Blob} blob @param {string} [version] 取数时
 *   顺手拿到的版本读数（size:lastModified 或调用方自定义；仅记录，命中判据 = TTL）
 * @returns {Promise<void>}
 */
export async function nfCachePut(cacheKey, blob, version) {
  if (!cacheKey || !blob || typeof blob.size !== 'number' || !(blob.size > 0)) return;
  if (!configNow().enabled) return;
  loadIndex();
  // 大于整个预算的单件不缓存（永远淘不进预算，只会反复触发淘汰）。
  if (blob.size > configNow().maxBytes) return;
  const now = Date.now();
  const prev = index.meta[cacheKey];
  index.meta[cacheKey] = { size: blob.size, at: now, last: now, ver: String(version || '') };
  index.bytes = Math.max(0, index.bytes + blob.size - (prev ? prev.size : 0));
  const i = index.order.indexOf(cacheKey);
  if (i >= 0) index.order.splice(i, 1);
  index.order.push(cacheKey);
  const store = await cacheStore();
  if (store) {
    try {
      await store.put(
        new Request(dummyUrl(cacheKey)),
        new Response(blob, { headers: { 'Content-Type': blob.type || 'application/octet-stream' } })
      );
    } catch { /* Cache 写失败 ⇒ 退化到内存面 */ }
  }
  memoryBlobs.set(cacheKey, blob); // 内存面恒有（命中第二读数 + 无 caches 引擎的退化面）
  saveIndex();
  await evictToBudget(cacheKey);
}

/**
 * 读数面（测试/诊断 seam）：引擎面板、spec 断言用。
 * @returns {{ enabled: boolean, backend: 'caches'|'memory', entries: number, bytes: number, maxBytes: number, ttlMs: number }}
 */
export function nfCacheStats() {
  loadIndex();
  return {
    enabled: configNow().enabled,
    backend: backendAvailable() ? 'caches' : 'memory',
    entries: index.order.length,
    bytes: index.bytes,
    maxBytes: configNow().maxBytes,
    ttlMs: configNow().ttlMs,
  };
}

/** 测试 seam：清空索引 + 字节（清缓存**用户入口**本批不做，候作者示）。 */
export async function nfCacheReset() {
  loadIndex();
  const store = await cacheStore();
  if (store) { try { await store.delete(dummyUrl('**')); } catch { /* 逐条删 */ } }
  for (const k of [...index.order]) {
    if (store) { try { await store.delete(dummyUrl(k)); } catch { /* 同上 */ } }
  }
  index.order = [];
  index.meta = {};
  index.bytes = 0;
  memoryBlobs.clear();
  saveIndex();
}

/**
 * 流式取数器（**字节路由由调用方给定**——既有票据路/鉴权路 URL，禁第三条路）：
 * 边收边报进度（`onProgress(loaded, total)`；`total` 来自 Content-Length，
 * 缺席 = 0 ⇒ 调用方转不定态转圈）。失败抛错（status 挂在 error.status 上），
 * 调用方出**可见**失败态（档3 可见面，禁静默）。
 *
 * 返回 `version` = `size:lastModified`（响应头可得时）——档2 条目的版本读数
 * （记录用；命中判据见上「TTL」）。
 * @param {string} url
 * @param {((loaded: number, total: number) => void)|null} [onProgress]
 * @returns {Promise<{blob: Blob, total: number, lastModified: string, version: string}>}
 */
export async function fetchBlobWithProgress(url, onProgress) {
  const resp = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
  if (!resp.ok) {
    const err = /** @type {Error & {status?: number}} */ (new Error('HTTP ' + resp.status));
    err.status = resp.status;
    throw err;
  }
  const lastModified = resp.headers.get('Last-Modified') || '';
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
  return { blob, total: finalTotal, lastModified, version: finalTotal + ':' + lastModified };
}
