// avatarCache.js — local-first avatar cache (dataURL in localStorage).
//
// 2026-09-05 sidebar-restructure 批。根因：头像渲染路径（Activity Bar +
// 设置页账号区，共用 neblink.js avatarViewState）每次都把远端 avatarUrl 塞给
// <img src>——「登录了没头像」闪失：慢网/离线时头像区长时间空白或回落 logo。
//
// 策略（节点裁量，报告 §头像缓存机制 有完整推理）：
//   1. 缓存优先：localStorage 存单条目 { url, dataUrl, fetchedAt, loggedIn }。
//      渲染路径同步读缓存（memo 化，零 async）——同 URL 命中 → 直接给
//      dataURL，<img> 零网络请求，离线/慢网也即时显示。
//   2. 源变化标记 = avatarUrl 本身（远端头像 URL 随账户/登录态演进，URL 变
//      即源变；<img>/fetch 均拿不到 ETag）。URL 不匹配 → 旧缓存作废，后台
//      回源新 URL 一次并重建缓存。
//   3. TTL 后台刷新：命中但超过 TTL → 仍先用缓存渲染（不闪失），后台静默
//      fetch 更新，下次轮询渲染自然生效。禁止「每次加载都请求远端」由此保证：
//      渲染路径永不发请求，唯一请求点是 TTL 过期的后台刷新 + 首见 URL 的
//      缓存建立（各带 in-flight 去重与失败退避）。
//   4. 渐进增强：远端无 CORS 头时 fetch 失败 → 静默放弃缓存，行为退化到
//      「<img> 直拉」现状，无回归。blob > 上限不入缓存（localStorage 配额）。
//   5. 登录态快照（loggedIn + dataUrl）随每次在线状态更新——本会话拿不到
//      status（启动即离线）时 avatarViewState 回落该快照，跨刷新不丢头像。
//      登出时 neblink.js 调 forgetAvatarProfile() 清除。
//
// Storage key 走 branding 命名空间（key('avatar_cache')）。全部读写 try/catch
// 包裹：隐私模式/配额满 → 静默退化为直拉现状。

import { key } from './branding.js';

const LS_KEY = key('avatar_cache');
/** Cache entries older than this get a silent background refresh (rendering
 *  keeps using the cached dataURL — never blocks, never flickers). */
const TTL_MS = 24 * 60 * 60 * 1000;
/** Refuse to cache avatars bigger than this (localStorage ~5MB total). */
const MAX_BYTES = 1.5 * 1024 * 1024;
/** After a failed fetch, don't retry for this long (the 10s status poll must
 *  not hammer an unreachable remote). */
const RETRY_BACKOFF_MS = 60 * 60 * 1000;

/** @typedef {{ url: string, dataUrl: string, fetchedAt: number, loggedIn: boolean, failedAt?: number }} AvatarEntry */
let memo = null;        // parsed entry (localStorage is sync — cache the parse)
let memoLoaded = false;
let inflight = '';      // sourceUrl currently being fetched (dedup)

function loadEntry() {
  if (memoLoaded) return memo;
  memoLoaded = true;
  memo = null;
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) memo = JSON.parse(raw);
  } catch { /* corrupted or unavailable — behave as no-cache */ }
  return memo;
}

function saveEntry(entry) {
  memo = entry;
  memoLoaded = true;
  try { localStorage.setItem(LS_KEY, JSON.stringify(entry)); } catch { /* quota — cache is best-effort */ }
}

/** blob → dataURL (FileReader), rejecting non-images / oversized blobs. */
function blobToDataUrl(blob) {
  return new Promise((resolve) => {
    if (!blob || !blob.type.startsWith('image/') || blob.size > MAX_BYTES) { resolve(null); return; }
    const fr = new FileReader();
    fr.onload = () => resolve(typeof fr.result === 'string' ? fr.result : null);
    fr.onerror = () => resolve(null);
    fr.readAsDataURL(blob);
  });
}

/** Fetch the remote avatar and (re)build the cache entry. Fire-and-forget:
 *  failures are silent (rendering falls back to the remote URL / logo). */
async function refreshDataUrl(sourceUrl) {
  if (inflight === sourceUrl) return;
  const entry = loadEntry();
  if (entry && entry.failedAt && Date.now() - entry.failedAt < RETRY_BACKOFF_MS && entry.url === sourceUrl) return;
  inflight = sourceUrl;
  try {
    const resp = await fetch(sourceUrl, { mode: 'cors', cache: 'no-store' });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const dataUrl = await blobToDataUrl(await resp.blob());
    if (dataUrl) {
      // Re-read the entry: rememberAvatarProfile may have recorded the login
      // state while this fetch was in flight — never overwrite it with the
      // stale pre-fetch snapshot.
      const latest = loadEntry();
      saveEntry({ url: sourceUrl, dataUrl, fetchedAt: Date.now(), loggedIn: latest ? latest.loggedIn : false });
    } else {
      throw new Error('unreadable avatar blob');
    }
  } catch {
    // Backoff: keep the old dataUrl (if any) but stop retrying for a while.
    const cur = loadEntry();
    saveEntry(cur && cur.url === sourceUrl
      ? { ...cur, failedAt: Date.now() }
      : { url: sourceUrl, dataUrl: '', fetchedAt: 0, loggedIn: cur ? cur.loggedIn : false, failedAt: Date.now() });
  } finally {
    inflight = '';
  }
}

/**
 * Synchronous cache lookup for a render pass.
 * @param {string} sourceUrl the valid remote avatar URL
 * @returns {string | null} cached dataURL, or null on miss (caller uses the
 *          remote URL and we build the cache in the background).
 */
export function lookupAvatar(sourceUrl) {
  const entry = loadEntry();
  if (!entry || entry.url !== sourceUrl || !entry.dataUrl) {
    refreshDataUrl(sourceUrl); // first sighting — build the cache, don't wait
    return null;
  }
  if (Date.now() - entry.fetchedAt > TTL_MS) {
    refreshDataUrl(sourceUrl); // stale — render cached, refresh in background
  }
  return entry.dataUrl;
}

/**
 * Record the last-known login state (called from avatarViewState whenever the
 * live status is available) so an offline boot can still show the avatar.
 * Value-change short-circuit: the 10s poll must not rewrite storage.
 */
export function rememberAvatarProfile(loggedIn, sourceUrl) {
  const entry = loadEntry();
  if (entry && entry.loggedIn === loggedIn && (entry.url === sourceUrl || !sourceUrl)) return;
  saveEntry({
    url: sourceUrl || (entry ? entry.url : ''),
    dataUrl: entry ? entry.dataUrl : '',
    fetchedAt: entry ? entry.fetchedAt : 0,
    loggedIn,
  });
}

/** Last-known profile snapshot for offline boots: loggedIn + a usable dataUrl.
 *  @returns {{ loggedIn: boolean, dataUrl: string } | null} */
export function lastKnownAvatarProfile() {
  const entry = loadEntry();
  if (entry && entry.loggedIn && entry.dataUrl) return { loggedIn: true, dataUrl: entry.dataUrl };
  return null;
}

/** Logout path: drop the snapshot so an offline refresh right after logout
 *  cannot resurrect the old avatar. */
export function forgetAvatarProfile() {
  memo = null;
  memoLoaded = true;
  try { localStorage.removeItem(LS_KEY); } catch { /* non-critical */ }
}
