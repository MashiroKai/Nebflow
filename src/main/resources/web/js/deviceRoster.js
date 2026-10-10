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

const CACHE_TTL_MS = 60_000;

let cache = null; // { at: number, self: {id,name}|null, peers: [{id,name,online,direct}] }

/** 拉一次花名册（60s 内存缓存；仅内存、不落盘——与面 B 元数据缓存同纪律）。 */
export async function getDeviceRoster() {
  if (cache && Date.now() - cache.at < CACHE_TTL_MS) return cache;
  let data = null;
  try {
    const token = localStorage.getItem('nebflow_token') || '';
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
  cache = entry;
  return entry;
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
