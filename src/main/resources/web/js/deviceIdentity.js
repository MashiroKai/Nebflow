// deviceIdentity.js — 网页控制台设备身份（device-provenance 批，2026-10-23 作者令）。
//
// 作者令：消息来源标记从渠道级（微信/飞书）细化到设备级——每条入站消息带
// ①来源设备（手机/Mac/Windows，可读设备名）②该设备是否在平台设备列表里。
//
// 本模块是**客户端声明面**：给每个浏览器会话一份稳定身份，随用户消息帧的
// `device` 键上行；网关（WebSocketRoutes.deviceProvenanceOf）对照 NebLink
// 花名册（/api/neblink/status 的 self + peers）判定已登记/未登记，标记块只进
// 模型面（会话气泡不渲染标记，与社交渠道头同一纪律）。
//
// 稳定性纪律：clientId 在 localStorage 持久、跨会话/跨刷新不变（同一浏览器的
// 多次会话同身份）；平台/浏览器由 UA 辅助判定（仅用于未登记设备的可读默认名，
// 不作身份键——身份键只有 clientId 与绑定的花名册设备 id）。
//
// 消费方：
//   · input.js   五处用户消息发送点（send / 队列 drain / 离线补发 / 插件卡注入 /
//                队列「立即发送」）随帧附 `device: deviceStamp()`；
//   · sidebar.js 设置页「消息设备标记」行（命名 + 绑定到设备列表）。

import { key } from './branding.js';

// 命名空间键（branding.js 派生，与全站存储键同族）。
const CLIENT_ID_KEY = key('device_client_id');
const NAME_KEY = key('device_name');
const BOUND_KEY = key('device_bound_id');

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

/** 用户命名（设置页写入；空串 = 清除，回默认名）。 */
export function setDeviceName(name) {
  try {
    const trimmed = (name || '').trim();
    if (trimmed) localStorage.setItem(NAME_KEY, trimmed);
    else localStorage.removeItem(NAME_KEY);
  } catch (e) { /* 存储不可用：本次命名不生效，不抛 */ }
}

export function getDeviceName() {
  try { return (localStorage.getItem(NAME_KEY) || '').trim(); } catch (e) { return ''; }
}

/** 绑定到设备列表（settings 的花名册设备 id；null/'' = 解除绑定）。 */
export function setDeviceBinding(rosterDeviceId) {
  try {
    const id = (rosterDeviceId || '').trim();
    if (id) localStorage.setItem(BOUND_KEY, id);
    else localStorage.removeItem(BOUND_KEY);
  } catch (e) { /* 同上 */ }
}

export function getDeviceBinding() {
  try { return (localStorage.getItem(BOUND_KEY) || '').trim(); } catch (e) { return ''; }
}

/**
 * 随帧上行的 `device` 声明（纯数据，网关据此 + 花名册出标记）。
 * 值域即 ClientDevice 解码面：clientId（必出）/ name / platform / browser /
 * boundDeviceId（缺席键省略，不发 null）。
 */
export function deviceStamp() {
  const stamp = {
    clientId: deviceClientId(),
    platform: detectPlatform(),
    browser: detectBrowser(),
  };
  const name = getDeviceName();
  if (name) stamp.name = name;
  const bound = getDeviceBinding();
  if (bound) stamp.boundDeviceId = bound;
  return stamp;
}

/**
 * 未登记默认名的本端拼装（与网关 ClientDevice.defaultName 同一词表，仅供设置页
 * 预览显示；登记态的真名以网关解析为准——这里不拉花名册，不猜已登记名）。
 */
export function defaultDeviceLabel() {
  const platformMap = { phone: '手机', tablet: '平板', mac: 'Mac', windows: 'Windows', linux: 'Linux' };
  const base = platformMap[detectPlatform()] || '未知设备';
  const browser = detectBrowser();
  return '未登记-' + base + (browser ? ' ' + browser : '');
}

// ─────────── 设置页「消息设备标记」区（命名 + 绑定） ───────────
//
// 复用既有 settings-row / cfg-input / cfg-select / cfg-btn 族样式（零新 CSS）；
// 动态文本一律 textContent（XSS 纪律）。花名册读数走 deviceRoster.getDeviceRoster
// （60s 内存缓存既有面，零新后端出口）。

/**
 * 往 slot 挂载标记设置区（幂等：每次全量重渲 slot）。由 sidebar.renderSettings
 * 懒挂载调用；roster 不可得（未登录/离线）⇒ 绑定下拉只有「不绑定」+ 现值回显，
 * 绝不伪造设备行。
 */
export async function mountDeviceStampSettings(slot) {
  if (!slot) return;
  const { t } = await import('./i18n.js');
  const { getDeviceRoster, resolveDeviceName } = await import('./deviceRoster.js');

  const bound = getDeviceBinding();
  const roster = await getDeviceRoster();

  // 现状读数：绑定命中 → 花名册登记名（已登记）；否则 用户命名 / 默认名（未登记）。
  let currentLabel;
  if (bound) {
    const rosterName = await resolveDeviceName(bound);
    currentLabel = rosterName + '（' + t('settings.deviceStampEnrolled') + '）';
  } else {
    const name = getDeviceName();
    currentLabel = (name || defaultDeviceLabel()) + '（' + t('settings.deviceStampUnenrolled') + '）';
  }

  // 绑定选项：不绑定 + self + peers（roster 不可得 = 只有不绑定）。
  const options = [{ id: '', name: t('settings.deviceStampNone') }];
  if (roster.self) options.push({ id: roster.self.id, name: roster.self.name });
  for (const p of roster.peers) options.push({ id: p.id, name: p.name });
  const known = bound && options.some((o) => o.id === bound);
  if (bound && !known) options.push({ id: bound, name: bound }); // 花名册里已消失的绑定值如实回显，不静默丢弃

  slot.innerHTML = `
    <div class="settings-row">
      <span class="settings-label">${t('settings.deviceStamp')}</span>
      <span id="device-stamp-current" class="cfg-hint" style="margin:0;text-align:right"></span>
    </div>
    <div class="settings-row">
      <span class="settings-label">${t('settings.deviceStampName')}</span>
      <input class="cfg-input" id="cfg-device-stamp-name" style="width:auto" spellcheck="false">
      <button type="button" class="cfg-btn cfg-btn-sm" id="btn-device-stamp-name-save">${t('settings.deviceStampSave')}</button>
    </div>
    <div class="settings-row">
      <span class="settings-label">${t('settings.deviceStampBind')}</span>
      <select class="cfg-select" id="cfg-device-stamp-bind" style="width:auto"></select>
    </div>
    <div class="cfg-hint">${t('settings.deviceStampHint')}</div>`;

  // 动态值走 textContent / 属性赋值（不用模板插值携带用户数据）。
  slot.querySelector('#device-stamp-current').textContent = currentLabel;
  const nameInput = slot.querySelector('#cfg-device-stamp-name');
  nameInput.value = getDeviceName();
  nameInput.placeholder = defaultDeviceLabel();
  const bindSel = slot.querySelector('#cfg-device-stamp-bind');
  for (const o of options) {
    const opt = document.createElement('option');
    opt.value = o.id;
    opt.textContent = o.name;
    if (o.id === bound) opt.selected = true;
    bindSel.appendChild(opt);
  }

  const rerender = () => mountDeviceStampSettings(slot);
  slot.querySelector('#btn-device-stamp-name-save').addEventListener('click', () => {
    setDeviceName(nameInput.value);
    rerender();
  });
  bindSel.addEventListener('change', () => {
    setDeviceBinding(bindSel.value);
    rerender();
  });
}
