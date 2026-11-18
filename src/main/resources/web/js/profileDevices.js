// profileDevices.js — 个人资料页的设备清单 + 逐设备独立退出
// （profile-multidevice 批，作者令：单页资料面 / 只读身份 / 设备清单含逐设备退出）。
//
// 定位：**唯一**的设备清单读数点与退出动作点。两个消费面共用本模块，禁第二份：
//   · 桌面 = `#settings-profile-overlay`（settingsIdentity.js 的个人资料面）
//   · 手机 = `me` 个人主页（mobile/meFace.js，层级子页本批退役后剩下的唯一页）
//
// ══ 数据纪律 ══════════════════════════════════════════════════════════════
// 读数源 = `neblink.js` 的 `getNeblinkState()`（其上游 = `GET /api/neblink/status`，
// 已在状态拍里落位 ⇒ 本模块**零新取数链、零新端点**）：
//   · 本机 = `state.device`：`deviceId` / `deviceName` / `platform`
//   · 对端 = `state.peers[]`（原样保留的上游对象）：`deviceId` / `deviceName` /
//     `platform` / `lastSeen`(ms) / `online`(新鲜度判据，服务端算好) / `directOnline`
//   · 在线态、最后活动时间 = **上游读数直显**，本模块不推算、不补值（读不到 = 「未知」）。
// 平台图标与文案 = `neblink.js` 的 `platformDisplay()`（同一实现，禁第二份平台判定）；
// 令牌 = `neblink.js` 的 `getAuthToken()`（branding.js 命名空间派生，与全站同源）。
//
// ══ 逐设备独立退出（本批语义，与 openspec/changes/profile-multidevice 对齐）════
// 「独立」= 每行退出的**作用域就是这一行那台设备**，绝不顺带动别的设备：
//   · 本机行 → 既有**唯一**登出链 `neblink.js openEndSessionHandoff(false)`
//     （本地凭据拆除 + provider RP 登出；清的就是本机那一份凭据，对端凭据在各自
//     设备本地，本客户端无从触及 ⇒ 天然设备域）。
//   · 对端行 → `POST /api/neblink/devices/{deviceId}/sign-out`，路径段 = **该行自己的**
//     deviceId。网关侧对该 id 做花名册校验（未知 ⇒ 404），已配对设备由网关返回
//     显式的「无此能力」读数（本批非目标：跨设备远端凭据拆除，见 proposal 的
//     non-goals）—— 失败禁静默：行内可见提示，绝不假装已退出。
//
// 模块纪律：叶子模块（只 import neblink / i18n / utils），不 import
// settingsIdentity / activityBar / navController，无回边成环。

import { getNeblinkState, getAuthToken, onNeblinkStatus, openEndSessionHandoff, platformDisplay } from './neblink.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';

/** @typedef {{id: string, name: string, platform: string, lastSeen: number|null,
 *   online: boolean|null, current: boolean}} ProfileDevice */

/** 读数缺失的统一降级文案（禁空白、禁占位假值）。 */
function unknown() {
  return t('settings.identityUnknown');
}

/**
 * 最近活动文案（纯函数；`lastSeen` = 上游 ms 时间戳）。
 * 读不到 / 非正数 ⇒ 「未知」（**不做**「刚刚」之类的兜底猜测）。
 * @param {number|null|undefined} lastSeen @returns {string}
 */
export function lastSeenText(lastSeen) {
  const ms = Number(lastSeen);
  if (!Number.isFinite(ms) || ms <= 0) return unknown();
  const deltaSec = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (deltaSec < 60) return t('profile.deviceSeenNow');
  const min = Math.round(deltaSec / 60);
  if (min < 60) return t('profile.deviceSeenMinutes', { n: String(min) });
  const hr = Math.round(min / 60);
  if (hr < 24) return t('profile.deviceSeenHours', { n: String(hr) });
  return t('profile.deviceSeenDays', { n: String(Math.round(hr / 24)) });
}

/** 状态文案（`online` 三态：true / false / null=未知，禁把未知画成离线）。 */
function statusText(online) {
  if (online === true) return t('profile.deviceOnline');
  if (online === false) return t('profile.deviceOffline');
  return unknown();
}

/**
 * 当前设备清单读数（同步；源 = 状态拍，禁第二份取数）。
 * 本机恒在首位（`current:true`），其后为对端（上游已做本机自过滤）。
 * @returns {{current: ProfileDevice|null, peers: ProfileDevice[]}}
 */
export function getDeviceList() {
  const st = getNeblinkState();
  const d = st && st.device;
  /** @type {ProfileDevice|null} */
  const current = d && d.deviceId
    ? {
        id: String(d.deviceId),
        name: String(d.deviceName || d.deviceId),
        platform: String(d.platform || ''),
        lastSeen: null,
        online: st.loggedIn === true ? true : null, // 本机在线 = 自己的登录态；未登录 ⇒ 未知
        current: true,
      }
    : null;
  const peers = (Array.isArray(st && st.peers) ? st.peers : [])
    .filter((p) => p && typeof p.deviceId === 'string' && p.deviceId)
    .map((p) => ({
      id: String(p.deviceId),
      name: String(p.deviceName || p.deviceId),
      platform: String(p.platform || ''),
      lastSeen: Number.isFinite(Number(p.lastSeen)) ? Number(p.lastSeen) : null,
      online: typeof p.online === 'boolean' ? p.online : null,
      current: false,
    }));
  return { current, peers };
}

/** 单行副行文案：`平台 · 状态 · 最近活动 <时间>`（逐段都能缺；全缺 = 未知）。 */
export function deviceMetaText(dev) {
  const parts = [platformDisplay(dev.platform).text, statusText(dev.online)];
  if (!dev.current) parts.push(t('profile.deviceLastSeen', { when: lastSeenText(dev.lastSeen) }));
  return parts.filter((s) => s && s !== unknown()).join(' · ') || unknown();
}

/**
 * 设备清单 HTML（共享标记，两个面逐字同一份 ⇒ 不存在两套列表）。
 * 行 = `data-device-id` + `data-device-current`，退出键 = `[data-device-signout]`。
 * @returns {string}
 */
export function deviceListHTML() {
  const { current, peers } = getDeviceList();
  const all = current ? [current, ...peers] : peers;
  if (!all.length) {
    return `<div class="pd-devices" data-device-list="1">
      <p class="pd-device-empty" role="note">${escapeHtml(t('profile.deviceNone'))}</p>
    </div>`;
  }
  const rows = all.map((dev) => {
    const { icon } = platformDisplay(dev.platform);
    const badge = dev.current
      ? `<span class="pd-device-badge">${escapeHtml(t('profile.deviceCurrent'))}</span>`
      : '';
    return `
      <div class="pd-device-row" data-device-id="${escapeHtml(dev.id)}" data-device-current="${dev.current ? '1' : '0'}">
        <span class="pd-device-icon" aria-hidden="true">${icon}</span>
        <span class="pd-device-main">
          <span class="pd-device-name"><span class="pd-device-name-text"></span>${badge}</span>
          <span class="pd-device-meta"></span>
        </span>
        <button class="pd-device-signout glass-control" type="button"
          data-device-signout="${escapeHtml(dev.id)}"
          aria-label="${escapeHtml(t(dev.current ? 'profile.deviceSignOutCurrent' : 'profile.deviceSignOut'))}">${escapeHtml(t('profile.deviceSignOut'))}</button>
      </div>`;
  }).join('');
  return `<div class="pd-devices" data-device-list="1">${rows}
      <p class="pd-device-notice" role="status" hidden></p>
    </div>`;
}

/**
 * 绘制清单文本（建表与重绘共用；元素不在场零成本）。
 * 只改文本与状态位，**不重建行**（重建会吃掉正在进行的退出动作的焦点/禁用态）。
 * @param {ParentNode} root
 */
export function paintDeviceList(root) {
  const list = root.querySelector('[data-device-list="1"]');
  if (!(list instanceof HTMLElement)) return;
  const { current, peers } = getDeviceList();
  /** @type {Map<string, ProfileDevice>} */
  const byId = new Map();
  if (current) byId.set(current.id, current);
  for (const p of peers) byId.set(p.id, p);
  for (const row of list.querySelectorAll('.pd-device-row')) {
    if (!(row instanceof HTMLElement)) continue;
    const dev = byId.get(row.dataset.deviceId || '');
    if (!dev) continue;
    const nameEl = row.querySelector('.pd-device-name-text');
    if (nameEl instanceof HTMLElement) nameEl.textContent = dev.name;
    const metaEl = row.querySelector('.pd-device-meta');
    if (metaEl instanceof HTMLElement) metaEl.textContent = deviceMetaText(dev);
    row.dataset.deviceOnline = dev.online === true ? 'online' : dev.online === false ? 'offline' : 'unknown';
  }
}

/** 行内可见提示（自动 6s 收起；失败禁静默）。 */
let noticeTimer = null;
/** @param {ParentNode} root @param {string} text */
function showNotice(root, text) {
  const el = root.querySelector('.pd-device-notice');
  if (!(el instanceof HTMLElement)) return;
  el.textContent = text;
  el.hidden = false;
  if (noticeTimer) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { if (el.isConnected) el.hidden = true; }, 6000);
}

/**
 * 逐设备退出（**唯一**动作实现，两个面共用）。
 * 本机 = 既有唯一登出链（SSO 完整）；对端 = 设备域端点，路径段 = 该行自己的 id。
 * @param {string} deviceId
 * @returns {Promise<{ok: boolean, scope: 'current'|'remote', code?: string}>}
 */
export async function signOutDevice(deviceId) {
  const id = String(deviceId || '');
  if (!id) return { ok: false, scope: 'remote', code: 'missing-device-id' };
  const { current } = getDeviceList();
  if (current && current.id === id) {
    // 复用既有唯一登出链（本地凭据拆除 + provider RP 登出）；本批**零新增**登出实现。
    openEndSessionHandoff(false);
    return { ok: true, scope: 'current' };
  }
  let code = '';
  try {
    const token = getAuthToken();
    const r = await fetch(`/api/neblink/devices/${encodeURIComponent(id)}/sign-out`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: '{}',
    });
    if (r.ok) return { ok: true, scope: 'remote' };
    const data = await r.json().catch(() => null);
    code = (data && typeof data.code === 'string' && data.code) || `http-${r.status}`;
  } catch (e) {
    code = 'network-error';
  }
  return { ok: false, scope: 'remote', code };
}

/** 退出失败 / 无能力的**可见**文案（禁静默、禁假装已退出）。 */
function signOutFailureText(code) {
  if (code === 'remote-sign-out-unsupported') return t('profile.deviceSignOutRemoteUnsupported');
  if (code === 'missing-device-id') return t('profile.deviceSignOutFailed');
  return t('profile.deviceSignOutFailed');
}

/** 已绑定的清单容器（WeakSet：重绘不重复绑定）。 */
const boundLists = new WeakSet();

/**
 * 绑定清单交互（幂等；两个面在各自建面后调用一次）。
 * @param {HTMLElement} root 清单所在容器（含 `[data-device-list]`）
 * @param {() => void} [rerender] 本机退出成功后的重绘回调
 */
export function bindDeviceList(root, rerender) {
  const list = root.querySelector('[data-device-list="1"]');
  if (!(list instanceof HTMLElement) || boundLists.has(list)) return;
  boundLists.add(list);
  list.addEventListener('click', async (e) => {
    const btn = e.target instanceof Element ? e.target.closest('[data-device-signout]') : null;
    if (!(btn instanceof HTMLElement)) return;
    e.stopPropagation();
    const id = btn.dataset.deviceSignout || '';
    const row = btn.closest('.pd-device-row');
    const isCurrent = row instanceof HTMLElement && row.dataset.deviceCurrent === '1';
    btn.disabled = true;
    const res = await signOutDevice(id);
    if (!res.ok) {
      if (btn.isConnected) btn.disabled = false;
      showNotice(list, signOutFailureText(res.code));
      return;
    }
    if (isCurrent) {
      // 本机退出 = 既有链整段重绘（元素可能已 detach ⇒ 只在仍在树上时重绘）。
      if (list.isConnected && typeof rerender === 'function') rerender();
      return;
    }
    if (btn.isConnected) btn.disabled = false;
    showNotice(list, t('profile.deviceSignOutQueued'));
  });
}

/** 状态拍订阅（清单在场时重绘；单飞，禁每次绘制加新闭包）。 */
let subscribed = false;
/** @param {ParentNode} root */
export function ensureDeviceListLive(root) {
  if (subscribed) return;
  subscribed = true;
  onNeblinkStatus(() => {
    const list = document.querySelector('[data-device-list="1"]');
    if (list) paintDeviceList(document);
  });
  void root;
}
