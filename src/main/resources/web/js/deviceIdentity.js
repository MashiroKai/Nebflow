// deviceIdentity.js — 网页控制台设备身份（device-provenance 批 2026-10-23；
// 标注全自动批 2026-10-26 作者令：「设置里这里相关的内容去掉，消息来源识别应该是自动的」）。
//
// 设置页「消息设备标记」手动面（命名输入/保存、绑定下拉、说明文案）整区退役；
// 来源识别改为**全自动**，随帧标注机制本身保留。判定三分支（fleet roster 判定，
// 花名册读数复用 deviceRoster.js 单一读取点）：
//   ① 登录会话 → 账号设备名：带凭据的浏览器读花名册 self（/api/neblink/status，
//      会话 cookie / Bearer 皆可达）⇒ 随帧自动带 boundDeviceId=self.id，网关按
//      登记名标注（已登记）；
//   ② 配对设备 → 登记名：无凭据但环回可达（status 读点 bootstrap 闸放行）读得
//      self ⇒ 同一出口，按登记名标注；
//   ③ 匿名 → 默认标注：花名册不可得（未配对/取数失败/非环回匿名）⇒ 帧不带
//      boundDeviceId，网关给可读默认名（「未登记-手机 Chrome」族），恒不冒认
//      已登记。
//
// 本模块是**客户端声明面**：给每个浏览器会话一份稳定身份，随用户消息帧的
// `device` 键上行；网关（WebSocketRoutes.deviceProvenanceOf）对照 NebLink
// 花名册（self + peers）判定已登记/未登记，标记块只进模型面（会话气泡不渲染
// 标记，与社交渠道头同一纪律）。
//
// 稳定性纪律：clientId 在 localStorage 持久、跨会话/跨刷新不变（同一浏览器的
// 多次会话同身份）；平台/浏览器由 UA 辅助判定（仅用于未登记设备的可读默认名，
// 不作身份键——身份键只有 clientId 与自动判定的花名册设备 id）。手动命名/
// 手动绑定（localStorage device_name / device_bound_id 两键随设置区退役）：
// 存量遗留值不再被读（孤儿键无害，无删除面——换新面后无任何消费方）。
//
// 消费方：
//   · input.js        五处用户消息发送点（send / 队列 drain / 离线补发 / 插件卡
//                     注入 / 队列「立即发送」）随帧附 `device: deviceStamp()`；
//   · errorRecovery.js 离线补发重发腿同律；
//   · nearpath.js     近路探测（bound= 自动绑定值上交，enrolled 判定同词表）；
//   · ws.js           onopen 触发 refreshAutoBinding（「何时刷」由 ws.js 管，
//                     与近路探测同拍；登录/登出走 WS 重连 ⇒ 自动跟上）。

import { key } from './branding.js';
import { getDeviceRoster } from './deviceRoster.js';

// 命名空间键（branding.js 派生，与全站存储键同族）。
const CLIENT_ID_KEY = key('device_client_id');

let cachedClientId = null;

/** 稳定客户端 id：首次生成（crypto.randomUUID，退化时间+随机），之后持久复用。 */
export function deviceClientId() {
  if (cachedClientId) return cachedClientId;
  try {
    const existing = localStorage.getItem(CLIENT_ID_KEY);
    if (existing) { cachedClientId = existing; return existing; }
  } catch (e) { /* storage 不可用：退化内存态，绝不断发送帧 */ }
  let id;
  try {
    id = (crypto && crypto.randomUUID) ? crypto.randomUUID()
      : 'dev-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  } catch (e) {
    id = 'dev-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  }
  cachedClientId = id;
  try { localStorage.setItem(CLIENT_ID_KEY, id); } catch (e) { /* 内存态即可 */ }
  return id;
}

/**
 * 平台端别（协议值，网关 DeviceProvenance.platformDisplay 消费）：
 * phone / tablet / mac / windows / linux。UA 辅助判定，不求完备——
 * 判不出的返回 undefined，网关按「未知设备」渲染可读名。
 */
export function detectPlatform() {
  const ua = navigator.userAgent || '';
  const maxTouch = (navigator.maxTouchPoints || 0);
  // iPadOS 13+ 桌面模式伪装 Macintosh：触点数 > 1 揭穿。
  if (/iPhone|iPod/i.test(ua)) return 'phone';
  if (/iPad/i.test(ua) || (/Macintosh/i.test(ua) && maxTouch > 1)) return 'tablet';
  if (/Android/i.test(ua)) return /Mobile/i.test(ua) ? 'phone' : 'tablet';
  if (/Mobile|Windows Phone/i.test(ua)) return 'phone';
  if (/Windows/i.test(ua)) return 'windows';
  if (/Macintosh|Mac OS X/i.test(ua)) return 'mac';
  if (/Linux|X11/i.test(ua)) return 'linux';
  return undefined;
}

/** 浏览器名（仅用于可读默认名尾段；判不出 = undefined，省略尾段）。 */
export function detectBrowser() {
  const ua = navigator.userAgent || '';
  if (/Edg\//i.test(ua)) return 'Edge';
  if (/OPR\//i.test(ua)) return 'Opera';
  if (/Firefox\//i.test(ua)) return 'Firefox';
  if (/Chrome\//i.test(ua)) return 'Chrome';
  if (/Safari\//i.test(ua)) return 'Safari';
  return undefined;
}

// ─────────── 自动设备判定（fleet roster；设置手动面的替代面） ───────────
//
// 绑定读数 = 花名册 self 的设备 id（内存态，空串 = 当前无可判定登记设备）。
// 刷新时机（事件驱动，零定时器）：ws.js onopen（每连）与 nearpath 探测入口；
// deviceRoster.getDeviceRoster 自带 60s 内存缓存，重复刷新零额外网络价。

let autoBoundDeviceId = '';

/**
 * 刷新自动绑定读数：花名册 self 在场（登录会话鉴权读 / 环回匿名读皆可达同一
 * 出口）⇒ 自动绑定 self.id（账号登记设备）；不可得 ⇒ 清空（帧不带
 * boundDeviceId ⇒ 网关默认名兜底）。任何失败静默降级，绝不影响发送路径。
 */
export async function refreshAutoBinding() {
  try {
    const roster = await getDeviceRoster();
    autoBoundDeviceId = (roster.self && roster.self.id) || '';
  } catch (e) {
    autoBoundDeviceId = '';
  }
  return autoBoundDeviceId;
}

/** 当前自动绑定读数（同步面：随帧 stamp 与近路探测取用，不发网络）。 */
export function currentAutoBinding() {
  return autoBoundDeviceId;
}

/**
 * 随帧上行的 `device` 声明（纯数据，网关据此 + 花名册出标记）。
 * 值域即 ClientDevice 解码面：clientId（必出）/ platform / browser /
 * boundDeviceId（自动判定命中才携带——缺席键省略，不发 null/空串；手动命名
 * 键 `name` 已随设置区退役，永不携带）。
 */
export function deviceStamp() {
  const stamp = {
    clientId: deviceClientId(),
    platform: detectPlatform(),
    browser: detectBrowser(),
  };
  const bound = currentAutoBinding();
  if (bound) stamp.boundDeviceId = bound;
  return stamp;
}
