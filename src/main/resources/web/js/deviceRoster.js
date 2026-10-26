// deviceRoster.js — NebLink 设备花名册的单一读取点（ui-device-faces 批 2026-10-10）。
//
// 消费方（本批两处）：
//   · projectTab.js  面 A 创建弹层「存放设备」菜单（self + peers 数据源，设计稿 §1.1）
//                    + 面 C 项目卡 `.dev-badge` 设备显示名 / 离线态（设计稿 §3）；
//   · explorer.js    面 B 头部 `.dev-chip` 设备显示名（设计稿 §2.1）。
//
// 数据源 = `GET /api/neblink/status`（NeblinkRoutes.scala:193-238 既有出口，零新后端面）：
//   device{id,name}（自身，菜单首项恒在）+ peers[{deviceId,deviceName,online,directOnline}]。
//
// 为什么不 import neblink.js 的 fetchNeblinkStatus：那是设置面板的全量状态拍（连带
// localStore/avatarCache 等重链）；projectTab 面板首屏与 explorer 侧栏都只读两个
// 字段——本模块只做「取数 + 60s 内存缓存 + 解读」，零 UI、零状态订阅面。
//
// 失败纪律：取数失败/未登录 = 显式 `null` 读数（调用方回落 deviceId 直显，不猜名字、
// 不猜在线态）；绝不缓存失败读数（下次调用重问）。
//
// 凭据纪律：Bearer 令牌读 `key('token')`（branding.js 命名空间派生，与
// agentManager/ws 等全站同族逐字同源）。重启窗热修批 2026-10-18：此前裸写
// `'nebflow_token'` 字面——品牌迁移（stand-startup-branding 批）把存量令牌迁到
// 现行命名空间（stand_*）后该裸键恒空 ⇒ Bearer 空 ⇒ /api/neblink/status 恒 401 ⇒
// 「存放设备」菜单静默退化仅本机、设备 chip/徽标全盲。

import { key } from './branding.js';

const CACHE_TTL_MS = 60_000;

let cache = null; // { at: number, self: {id,name}|null, peers: [{id,name,online,direct}] }

/** 拉一次花名册（60s 内存缓存；仅内存、不落盘——与面 B 元数据缓存同纪律）。 */
export async function getDeviceRoster() {
  if (cache && Date.now() - cache.at < CACHE_TTL_MS) return cache;
  let data = null;
  try {
    const token = localStorage.getItem(key('token')) || '';
    const r = await fetch('/api/neblink/status', { headers: { Authorization: `Bearer ${token}` } });
    if (r.ok) data = await r.json();
  } catch (_) { /* 显式 null 读数（下方归一） */ }
  const entry = {
    at: Date.now(),
    self: data?.device && typeof data.device.id === 'string'
      ? { id: data.device.id, name: data.device.name || data.device.id }
      : null,
    peers: Array.isArray(data?.peers)
      ? data.peers
          .filter((p) => p && typeof p.deviceId === 'string')
          .map((p) => ({
            id: p.deviceId,
            name: p.deviceName || p.deviceId,
            online: p.online === true,
            direct: p.directOnline === true,
          }))
      : [],
  };
  if (entry.self && entry.self.name) noteSelfName(entry.self.name);
  cache = entry;
  return entry;
}

// ── 本机设备显示名（设备角标本名批 2026-10-27 作者令）────────────────────
//
// 「本机」字面（引擎 `device="local"` 逃生门）在**显示面**不再直出——解析为本机
// 设备名，两源：① 花名册 self（多设备面板同源，getDeviceRoster 命中即登记）；
// ② configData.selfDevice.name（引擎自身身份读数，ws/main 面经
// noteSelfDeviceName 登记；引擎侧无身份文件时已含主机名兜底，恒非「local」）。
// 两源皆缺（老引擎帧 + 花名册不可得）⇒ 空读数，调用方不渲染角标——**绝不回退
// 「local」字样**。
//
// 隔离实例名（NeblinkModel.mintedDeviceName：非默认数据根 = `-iso-<8位指纹>`
// 后缀）是内部消歧标记，不进显示面（作者补充裁决 2026-10-27）：`stripIsoNameSuffix`
// 只打显示读数，full 原值保留给 title/tooltip；身份/配对/日志面保真原值不动。

// 隔离实例名后缀（NeblinkModel.mintedDeviceName/scopeFingerprint：8 位十六进制指纹）。
const ISO_NAME_SUFFIX_RE = /-iso-[0-9a-f]{8}$/;

/**
 * 剥离隔离实例名后缀（`-iso-<hex8>`，**仅显示面**；身份/配对/日志面保原值）。
 * 纯函数：无后缀原样返回，非串归一空串。
 */
export function stripIsoNameSuffix(name) {
  return String(name || '').replace(ISO_NAME_SUFFIX_RE, '');
}

// 本机显示名缓存（同步读数面）：{full: 原值, display: 剥后缀}。渲染是同步路径
// （工具卡 label 拼串），异步取数只发生在刷新点（getDeviceRoster / configData 帧）。
let selfName = { full: '', display: '' };

function noteSelfName(full) {
  const v = String(full || '').trim();
  if (!v) return;
  selfName = { full: v, display: stripIsoNameSuffix(v) || v };
}

/** ws configData 帧登记引擎自身设备名（selfDevice.name 加法字段；空值不覆盖）。 */
export function noteSelfDeviceName(full) {
  noteSelfName(full);
}

/** 当前本机设备显示名（同步读数：{full, display}；两源皆缺 = 全空串）。 */
export function currentSelfDeviceName() {
  return selfName;
}

/** 设备显示名（花名册命中 → 显示名；未命中 → 原 id 直显，不猜）。 */
export async function resolveDeviceName(deviceId) {
  if (!deviceId) return '';
  const roster = await getDeviceRoster();
  if (roster.self && roster.self.id === deviceId) return roster.self.name;
  const peer = roster.peers.find((p) => p.id === deviceId);
  return peer ? peer.name : deviceId;
}

/** 面C 离线判定（peers[].online；花名册不可得 = unknown = 不加点，不猜）。 */
export async function isDeviceOnline(deviceId) {
  if (!deviceId) return null;
  const roster = await getDeviceRoster();
  if (roster.self && roster.self.id === deviceId) return true; // 自身恒在线
  const peer = roster.peers.find((p) => p.id === deviceId);
  return peer ? peer.online : null;
}
