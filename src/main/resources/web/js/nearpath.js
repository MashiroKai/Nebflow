// nearpath.js — 设备识别快速通道 · 主动探测（console-fastpath 批 2026-10-23，作者令腿①）。
//
// 原理与「消息标记设备」逐字同源（device-provenance 批）：浏览端持 device stamp
// （clientId + 设置页绑定到设备列表的 boundDeviceId），网关对照花名册
// （/api/neblink/nearpath 的 enrolled 标记，判定词表与 WebSocketRoutes 的
// 消息设备登记判定一致）——**是 → 快速通道候选（LAN 地址清单），不是 → relay
// 兜底**（作者批复原文；LAN 拓扑面服务端强制执行，前端拿不到未登记的清单）。
//
// 探测时机（主动，不只等用户点）：ws.js 在每次 onopen（首连 + 重连，经
// onReconnect 注册）调 probeNearPath()——会话打开/重连即自动探测。环回候选
// 不走本端点：ws.js 既有近路短路链自带 deviceId 锚（本地加速批），同机识别
// 天然成立；本模块负责的是**LAN 候选 + 记录结论到会话**（探测成功即把
// {base, kind} 记进 sessionStorage + 模块态，nfTicket/nfFileCache 的大文件
// LAN 直传腿消费它）。
//
// 失败纪律：每一环失败都静默停留 relay 现状（author 令「不是就走 relay」），
// 绝不影响 WS 连接；探测并发一拍（Promise.all），单候选 2.5s 超时。
//
// 结论记录（记到会话）：sessionStorage `stand_ws_nearpath_result` =
//   { at, verified: {base, kind} | null, enrolled, reason }——重连后重探，
//   30s 防摆闸（与 ws.js remint 同纪律）。

import { key } from './branding.js';
import { deviceStamp, getDeviceBinding } from './deviceIdentity.js';

const RESULT_KEY = key('ws_nearpath_result');
const PROBE_TIMEOUT_MS = 2500;
const REFRACTORY_MS = 30000;

/** @type {{ base: string, kind: 'loopback'|'lan', at: number } | null} */
let active = null;

function readStored() {
  try {
    const raw = sessionStorage.getItem(RESULT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') return parsed;
  } catch (_) { /* 损坏 = 无记录 */ }
  return null;
}

function writeStored(rec) {
  try { sessionStorage.setItem(RESULT_KEY, JSON.stringify(rec)); } catch (_) { /* 内存态即可 */ }
}

/**
 * 本会话已验证的近路（nfTicket/nfFileCache 大文件 LAN 直传腿的消费面）。
 * @returns {{ base: string, kind: 'loopback'|'lan' } | null}
 */
export function getNearPath() {
  if (active && Date.now() - active.at < REFRACTORY_MS * 4) return active;
  const stored = readStored();
  if (stored && stored.verified && typeof stored.verified.base === 'string') {
    return { base: stored.verified.base, kind: stored.verified.kind === 'lan' ? 'lan' : 'loopback' };
  }
  return null;
}

/** 环回基址判据：探测目标必须是 http 环回（https 环回无证书，必败）。 */
function loopbackBases(port) {
  return [`http://127.0.0.1:${port}`, `http://localhost:${port}`];
}

/** 单候选探测：nf-reach 204 + 品牌自证头 ⇒ 「一个 stand 网关」可达。 */
async function probeBase(base) {
  try {
    const r = await fetch(`${base}/api/nf-reach`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
    });
    if (!r.ok && r.status !== 204) return false;
    // 品牌自证头：与 nf-reach 服务面同族（x-<brand>-gateway）。
    const brand = /** @type {Window & { __BRAND__?: { lowerName?: string } }} */ (window).__BRAND__;
    return r.headers.get(`x-${(brand && brand.lowerName) || 'stand'}-gateway`) === '1';
  } catch (_) {
    return false;
  }
}

/**
 * 主动探测（ws.js onReconnect 调用；幂等带 30s 防摆闸）。
 * 链：①近路面（带 stamp）拿 enrolled + lanAddresses + port；
 *     ②enrolled ⇒ 候选 = 环回 ∪ LAN（环回不受 enrolled 限制——同机识别
 *       由 ws.js 短路链负责，这里只给取件面兜底）；
 *     ③并发 nf-reach 探测，首个命中记会话；全败 ⇒ 记录负结论（不重试
 *       至闸开）。
 * @returns {Promise<{ base: string, kind: 'loopback'|'lan' } | null>}
 */
export async function probeNearPath() {
  const stored = readStored();
  if (stored && Date.now() - (stored.at || 0) < REFRACTORY_MS) {
    return stored.verified || null;
  }
  try {
    const stamp = deviceStamp();
    const bound = getDeviceBinding();
    const token = (() => { try { return localStorage.getItem(key('token')) || ''; } catch (_) { return ''; } })();
    const q = new URLSearchParams({ clientId: stamp.clientId || '' });
    if (bound) q.set('bound', bound);
    const r = await fetch(`/api/neblink/nearpath?${q.toString()}`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!r.ok) {
      writeStored({ at: Date.now(), verified: null, enrolled: false, reason: `http_${r.status}` });
      return null;
    }
    const data = await r.json();
    const port = Number(data && data.port);
    if (!Number.isInteger(port) || port <= 0 || port > 65535) {
      writeStored({ at: Date.now(), verified: null, enrolled: false, reason: 'no_port' });
      return null;
    }
    // 候选拼装：环回恒在（取件兜底）；LAN 仅 enrolled（服务端清单已按
    // 作者令过滤，这里再闸一道防前端漂移）。
    /** @type {Array<{ base: string, kind: 'loopback'|'lan' }>} */
    const candidates = [];
    for (const b of loopbackBases(port)) candidates.push({ base: b, kind: 'loopback' });
    if (data.enrolled === true && Array.isArray(data.lanAddresses)) {
      for (const ip of data.lanAddresses) {
        if (typeof ip === 'string' && /^(\d{1,3}\.){3}\d{1,3}$/.test(ip)) {
          candidates.push({ base: `http://${ip}:${port}`, kind: 'lan' });
        }
      }
    }
    const results = await Promise.all(candidates.map((c) => probeBase(c.base).then((ok) => (ok ? c : null))));
    const hit = results.find(Boolean) || null;
    active = hit ? { base: hit.base, kind: hit.kind, at: Date.now() } : null;
    writeStored({
      at: Date.now(),
      verified: hit ? { base: hit.base, kind: hit.kind } : null,
      enrolled: data.enrolled === true,
      reason: hit ? 'ok' : 'unreachable',
    });
    return active;
  } catch (_) {
    writeStored({ at: Date.now(), verified: null, enrolled: false, reason: 'probe_error' });
    return null;
  }
}
