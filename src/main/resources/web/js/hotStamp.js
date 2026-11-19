// hotStamp.js — 前端热更新地基：asset stamp 轮询自愈（台账 #919，2026-10-27）。
//
// 设计件：docs/design/20261027_hot-reload-foundation-spec.zh-CN.md §1.2。
// 服务面（StaticRoutes 磁盘 dist 逐请求读 + 内容寻址 URL，4df51ddcd 批）已经
// 零重启可换字节；缺的最后一环 = 已打开的页面不知道服务端换了字节。本模块
// 轮询 GET /api/nf-asset-stamp（stamp = 引擎实际伺服的 index.html 字节指纹，
// 与 indexResponse 同源单点），stamp 变化 ⇒ 页面**空闲时**自动 reload。
//
// 纪律（与 swReg.js 同族：降级与逃生门）：
//  · 任何失败（端点 404/网络拒绝/解析失败）= 静默空转零影响——本文件可以先行
//    合入，自动面在未部署 stamp 端点的引擎上恒为 no-op；
//  · 忙时让位：state.busySessionIds 非空（有在跑回合）不 reload，挂 pending，
//    空闲拍（下一个轮询/focus/visibility 回来）补 reload；
//  · 防环：reload 前写 sessionStorage `<lower>_hotstamp_reload_at`，20s 内不二次
//    reload（stub 异常不会把页面打进 reload 循环）；
//  · 后台标签页零轮询（visibility 门）；回前台 focus 补一拍（≥10s 节流）；
//  · 诊断/测试缝：window.__nfHotStamp + _hotStampTestHooks()
//    （checkNow 手动驱动同一判路 / applyRemote 模拟远端读数 / state 读数）。

import state from './state.js';

const POLL_MS = 45_000;
const MIN_GAP_MS = 10_000;   // focus/visibility 补拍的节流
const RELOAD_GUARD_MS = 20_000;

let bootStamp = null;        // 本页面启动时伺服的字节指纹
let lastRemote = null;       // 最近一次远端读数（诊断面）
let lastFetchAt = 0;
let pendingReload = false;   // 检出变化但忙 ⇒ 挂起，待空闲拍
let timer = null;

function brandLower() {
  return (/** @type {Window & { __BRAND__?: { lowerName?: string } }} */ (window).__BRAND__?.lowerName) || 'stand';
}

function reloadAllowed() {
  try {
    const key = brandLower() + '_hotstamp_reload_at';
    const last = Number(sessionStorage.getItem(key) || '0');
    if (last && Date.now() - last < RELOAD_GUARD_MS) return false;
    sessionStorage.setItem(key, String(Date.now()));
    return true;
  } catch {
    return true; // sessionStorage 不可用 = 放行（防环闸尽力而为）
  }
}

function pageBusy() {
  try {
    return !!(state && state.busySessionIds && state.busySessionIds.size > 0);
  } catch {
    return false; // 状态面不可读 = 视为空闲（判路主体绝不因诊断面抛错而停摆）
  }
}

async function fetchStamp() {
  const r = await fetch('/api/nf-asset-stamp', { cache: 'no-store' });
  if (!r.ok) return null;
  const data = await r.json();
  return typeof data?.stamp === 'string' ? data.stamp : null;
}

/** 一次判路（轮询拍 / focus 补拍 / 测试缝共用同一入口）：
 *  远端读数 → 变化判定 → 忙让位 / 防环闸 → reload。 */
async function checkNow() {
  if (bootStamp === null) return 'no-boot';           // 尚未捕获基线（首拍前）
  if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return 'hidden';
  const remote = await fetchStamp();
  if (remote === null) return 'unavailable';
  lastRemote = remote;
  lastFetchAt = Date.now();
  if (remote === bootStamp) { pendingReload = false; return 'same'; }
  if (pageBusy()) { pendingReload = true; return 'busy-deferred'; }
  pendingReload = false;
  if (!reloadAllowed()) return 'guard-skipped';
  location.reload();
  return 'reloading';
}

/** 空闲补查拍：忙挂起态在每个轮询拍/focus 重查（busy 清零 ⇒ 落 reload）。 */
function armPolling() {
  if (timer !== null) return;
  timer = setInterval(() => { void checkNow(); }, POLL_MS);
}

export function initHotStampWatch() {
  try {
    if (location.protocol !== 'http:' && location.protocol !== 'https:') return;
    // 首拍捕获基线（fire-and-forget；失败 = 本模块静默空转）
    void (async () => {
      const s = await fetchStamp();
      if (typeof s === 'string' && s) bootStamp = s;
    })();
    armPolling();
    const kick = () => {
      if (Date.now() - lastFetchAt < MIN_GAP_MS) return;
      void checkNow();
    };
    window.addEventListener('focus', kick);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') kick();
    });
  } catch {
    /* 任何意外都不能影响主功能（swReg 同款兜底） */
  }
}

/** 诊断/测试缝（ws.js hbStats 同款出口纪律）。 */
export function _hotStampTestHooks() {
  return {
    /** 手动驱动一次判路（等价于一个轮询拍，不受 MIN_GAP 节流）。 */
    checkNow,
    /** 测试注桩：直接设置基线/远端读数（免 fetch 依赖）。 */
    setBoot(stamp) { bootStamp = stamp; },
    /** 读数面。 */
    state() {
      return { bootStamp, lastRemote, pendingReload, pollMs: POLL_MS, guardMs: RELOAD_GUARD_MS, busy: pageBusy() };
    },
  };
}
