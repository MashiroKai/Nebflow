// mobile/meFace.js — 个人主页（单页；profile-multidevice 批，多设备改版）。
//
// 定位：工作台顶头像点击后的「个人主页」。**本批起是单页** —— 层级子页
// （me-user / me-avatar / me-bind）与「编辑资料」空动作一并退役（作者令：
// 单页资料 / 只读身份 / 设备清单含逐设备退出 / 无空动作、无子页）。
//
// ══ 页面成员（自上而下）══════════════════════════════════════════════════
//   ① 资料卡：头像 + 名 + 邮箱（未登录态给登录键）—— 结构 `mf-me-card sp-identity`
//      + `mf-me-avatar-frame huge` 逐字保留（`scripts/check-panel-uniform.mjs`
//      的登记判据，共享居中身份结构）。
//   ② 只读身份读数：username / nickname / email / 绑定 —— 全部
//      `settingsIdentity.js getIdentityReadout()` 单点，禁第二份取数。
//      profile-complete 批（2026-11-18 作者令「个人主页信息很多都没获取全」）：
//      · 用户名 —— **修前恒「未知」**：本页从不触发自证探针（探针只在设置页身份卡
//        / 桌面资料面打开时发起），`selfState` 停在 idle ⇒ username 空。本批建面即
//        调 `settingsIdentity.ensureIdentityLive()`（探针 + 状态订阅的单点入口）。
//      · GitHub 绑定 —— 此前写死「未知」；真源一直在
//        `status.device.githubLogin`（服务端 `github_username`，登录时下发、本机
//        `device.json` 持久化），本批映射并渲染真实值。
//      · Google 绑定 —— 本机无任何可读来源（登录 id_token 无 provider/identities
//        claim；社交 provider 身份在 OIDC 往返后不落地；upstream 用户档案契约只有
//        username/display_name/avatar）⇒ 仍「未知」，但**附一句缺口说明**
//        （`bindingGapNote` 单点，与桌面资料面同一份文案）—— 不留无解释的未知。
//   ③ 头像跳官网：点资料卡大头像 → 新标签页开 `https://mystand.space`
//      （`<a target="_blank" rel="noopener noreferrer">`；作者令 2026-11-18
//      「在个人主页再次点击头像要能跳转官网」）。修前该头像**零行为**（无点击监听、
//      无放大/展开），故不是覆盖既有行为，是给空位挂上新行为。
//   ④ 官网编辑指引：编辑落点在官网账号页（**纯文本**读数，零 `<a href>`、零
//      window.open —— 上一批「零官网出口」的裁定理由不变，见下）。
//   ⑤ 设备清单：`profileDevices.js` 单点（本机 + 对端；名称/平台/最近活动/状态/
//      本机标记 + 逐设备独立退出）。与桌面资料面**同一份标记与同一份动作实现**。
//   （原 ⑤「账号卡」= 底部「登录账号」栏，整件退役：作者令 2026-11-18 16:45
//     「底部的账户栏 功能重复 信息重复」——与上方读数组 + 设备行身份完全重复。）
//
// ══ 为什么子页退役（不是「少了一个入口」，是那条入口本身就是假面）════════
// 修前 me-user / me-avatar / me-bind 三页只读同一个读数、各带一句「接口暂无」；
// 「编辑资料」键点了也只开一个同样只读的面。三个层级页 + 一个空动作 = 四条通往
// 同一张纸的路。本批收成单页：读数一次说完，编辑指引直说官网。
//
// 数据纪律：全部字段 = `settingsIdentity.js getIdentityReadout()` + `profileDevices.js`；
// 头像绘制与设置页/Activity Bar 同池（avatarViewState 双态 + paintAvatarSlot 就绪门）。
//
// 模块纪律：叶子模块（只 import neblink / brand / utils / activityBar /
// settingsIdentity / profileDevices / i18n / dom）。不 import navController。

import { avatarViewState, paintAvatarSlot, noteAvatarFailure, onNeblinkStatus, fetchNeblinkStatus } from '../neblink.js';
import { brand, getProfileUrl, getHomeUrl } from '../brand.js';
import { createIconsIn } from '../utils.js';
import { showLoginModal } from '../activityBar.js';
import { getIdentityReadout, ensureIdentityLive, bindingGapNote } from '../settingsIdentity.js';
import { buildDeviceList, paintDeviceList, bindDeviceList, ensureDeviceListLive, deviceIdKey } from '../profileDevices.js';
import { t } from '../i18n.js';

/** 同步文案（建面时语言已定；单页四组标签共用）。 */
function ttSync(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** 状态拍 → 页面重绘（登录/登出/头像变更/设备增减；页不在 DOM 时零成本跳过）。 */
const paintCbs = new Set();
let unsubscribeStatus = null;

/** @param {HTMLElement} body */
function el(body, cls, tag = 'div') {
  const d = document.createElement(tag);
  d.className = cls;
  body.appendChild(d);
  return d;
}

/** 读数缺失的统一降级文案（禁空白、禁占位假值）。 */
function unknownText() {
  return t('settings.identityUnknown');
}

/** 字段行（label + 只读 value；本页信息排布单元）。 */
function fieldRow(body, label, valueText) {
  const row = el(body, 'mf-me-field');
  const l = document.createElement('span');
  l.className = 'mf-me-field-label';
  l.textContent = label;
  const v = document.createElement('span');
  v.className = 'mf-me-field-value';
  v.textContent = valueText;
  row.append(l, v);
  return v;
}

/** 头像双态绘制（logo 占槽 → 照片字节就绪翻面；与设置页同池同纪律）。 */
function paintAvatarFrame(frame) {
  if (!(frame instanceof HTMLElement)) return;
  const { url: validAvatarUrl, showPhoto } = avatarViewState();
  const photoEl = frame.querySelector('.mf-me-avatar-photo');
  const logoEl = frame.querySelector('.mf-me-avatar-logo');
  if (photoEl instanceof HTMLImageElement) {
    paintAvatarSlot(photoEl, logoEl instanceof HTMLImageElement ? logoEl : null,
      validAvatarUrl, showPhoto, () => {
        noteAvatarFailure(validAvatarUrl);
        const view = avatarViewState();
        photoEl.hidden = true;
        if (logoEl instanceof HTMLImageElement) logoEl.hidden = view.showPhoto;
      });
  }
}

/** 官网账号页读数（纯文本；零 `<a href>`、零 window.open —— 见文件头注）。 */
function websiteText() {
  try {
    const u = new URL(getProfileUrl());
    return `${u.host}${u.pathname}`;
  } catch {
    return String(brand.domain || '');
  }
}

/** 资料卡 + 只读身份 + 官网指引 + 设备清单（单页全部内容；底部账号卡已随 #4 退役）。 */
function buildMeHome(body) {
  const id = getIdentityReadout();
  // ① 资料卡：大头像 + 名 + 邮箱（未登录态给登录键，与顶栏批登录面同源）。
  //    大头像 = 官网出口（profile-complete 批，作者令「再次点击头像跳转官网」）：
  //    `<a>` 直落官网首页、新标签页打开（`rel="noopener noreferrer"`）。几何与
  //    绘制全走既有 `.mf-me-avatar-frame` 规则（标签从 div 换 a，类名与结构逐字不变）。
  const card = el(body, 'mf-me-card sp-identity');
  const frame = /** @type {HTMLAnchorElement} */ (el(card, 'mf-me-avatar-frame huge', 'a'));
  frame.href = getHomeUrl();
  frame.target = '_blank';
  frame.rel = 'noopener noreferrer';
  frame.setAttribute('aria-label', ttSync('打开官网', 'Open website'));
  frame.innerHTML = `
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">
      <img class="mf-me-avatar-logo" src="${brand.logoUrl}" alt="">
    </picture>
    <img class="mf-me-avatar-photo" alt="" hidden>`;
  paintAvatarFrame(frame);
  const names = el(card, 'mf-me-names');
  const nameEl = el(names, 'mf-me-name');
  const subEl = el(names, 'mf-me-sub');
  if (!id.loggedIn) {
    const login = document.createElement('button');
    login.className = 'mf-me-login glass-control';
    login.type = 'button';
    login.textContent = ttSync('登录账号', 'Sign in');
    login.addEventListener('click', (e) => { e.stopPropagation(); showLoginModal(); });
    card.appendChild(login);
  }

  // ② 只读身份读数（全部真实读数；读不到 = 「未知」，禁冒充）。
  el(body, 'mf-hub-label mf-me-grouplabel').textContent = ttSync('账号', 'Account');
  const fields = el(body, 'mf-hub-rows mf-me-fields');
  const vName = fieldRow(fields, ttSync('用户名', 'Username'), id.username || unknownText());
  const vNick = fieldRow(fields, ttSync('昵称', 'Nickname'), id.nickname || unknownText());
  const vEmail = fieldRow(fields, ttSync('邮箱', 'Email'), id.email || unknownText());
  const vGh = fieldRow(fields, ttSync('GitHub 绑定', 'GitHub binding'), id.github || unknownText());
  const vGg = fieldRow(fields, ttSync('Google 绑定', 'Google binding'), unknownText());
  // ③ 绑定缺口说明（单点 = settingsIdentity.bindingGapNote，与桌面资料面同一份文案）：
  //    只对**确实读不到**的字段出句 —— 作者令「不许留『未知』而不解释」。两位都读到
  //    时整句收起（hidden）。
  const gapNote = el(body, 'mf-me-gapnote');
  gapNote.hidden = true;
  // ④ 编辑指引：落点在官网（纯文本，非链接）。
  el(body, 'mf-me-hint').textContent =
    t('profile.editOnWebsite', { where: websiteText() });

  // ④ 设备清单（与桌面资料面同一份标记 + 同一份动作实现；标记由
  //    profileDevices.buildDeviceList() 单点产出，本面只做挂载 —— 零第二套 HTML 串）。
  el(body, 'mf-hub-label mf-me-grouplabel').textContent = t('profile.deviceSection');
  const devHost = el(body, 'mf-hub-rows mf-me-fields');
  devHost.replaceChildren(buildDeviceList());
  bindDeviceList(devHost, () => { for (const cb of [...paintCbs]) { try { cb(); } catch { /* 一个面不得阻断其它面 */ } } });
  ensureDeviceListLive(devHost);
  paintDeviceList(devHost);

  // ⑤ 底部「登录账号」账户卡**整件退役**（作者令 2026-11-18 16:45「底部的账户栏
  //    功能重复 信息重复 …不要有重复的功能和信息」）：它与上方「账号」读数组
  //    （用户名/昵称/邮箱/GitHub/Google）**完全重复**，也与设备行身份信息重复。
  //    本页身份/头像的权威展示 = 资料卡 + 只读读数组；逐设备登出 = 设备清单
  //    （`profileDevices.js`）——账户卡移除后本页零新增、零残留。

  const paintIdentity = () => {
    const s = getIdentityReadout();
    nameEl.textContent = s.loggedIn
      ? (s.nickname || s.username || s.email || t('profile.signedIn'))
      : t('settings.identityNotSignedIn');
    subEl.textContent = s.loggedIn ? (s.email || '') : '';
    vName.textContent = s.username || unknownText();
    vNick.textContent = s.nickname || unknownText();
    vEmail.textContent = s.email || unknownText();
    vGh.textContent = s.github || unknownText();
    const gap = bindingGapNote(s);
    gapNote.textContent = gap;
    gapNote.hidden = !gap;
    paintAvatarFrame(frame);
    // 身份读数保活（探针 + 状态订阅；幂等单飞）—— 见文件头②：本页不调它 = 用户名恒「未知」。
    ensureIdentityLive();
  };
  paintCbs.add(paintIdentity);
  paintCbs.add(() => {
    // 设备集合变了才重建行（重建会吃掉正在进行的退出动作的禁用态）。
    const wanted = deviceIdKey();
    if (devHost.dataset.pdIds !== wanted) {
      devHost.replaceChildren(buildDeviceList());
      devHost.dataset.pdIds = wanted;
      bindDeviceList(devHost, () => { for (const cb of [...paintCbs]) { try { cb(); } catch { /* 同上 */ } } });
    }
    paintDeviceList(devHost);
  });
}

const BUILDERS = {
  me: buildMeHome,
};

/**
 * 构建个人主页页面体（navController buildSection 分派；幂等——只建一次，
 * 重绘走状态订阅）。返回拆除函数（nav 层注销时调用）。
 * @param {string} pageId 'me'
 * @param {HTMLElement} body 页面体容器
 * @param {{ push: (pageId: string) => void }} nav 导航回调（本批已无子页；参数保留
 *   以维持 navController 的单点调用签名，**不**新增层级入口）
 * @returns {() => void} teardown
 */
export function buildMeFace(pageId, body, nav) {
  const build = BUILDERS[pageId];
  if (!build) return () => {};
  const existingCbs = new Set(paintCbs);
  build(body);
  void nav;
  const ownCbs = [...paintCbs].filter(cb => !existingCbs.has(cb));
  for (const cb of ownCbs) cb();
  if (pageId === 'me') { ensureIdentityLive(); void fetchNeblinkStatus(); }
  if (typeof lucide !== 'undefined') createIconsIn(body);
  // 状态订阅（模块级单飞）：一拍重绘本页在场元素。
  if (!unsubscribeStatus) {
    unsubscribeStatus = onNeblinkStatus(() => { for (const cb of [...paintCbs]) { try { cb(); } catch { /* One face must not block the others. */ } } });
  }
  return () => {
    for (const cb of ownCbs) paintCbs.delete(cb);
    if (!paintCbs.size) { unsubscribeStatus?.(); unsubscribeStatus = null; }
  };
}
