// mobile/meFace.js — 个人主页层级面（手机顶栏批 2026-10-31 作者令④）。
//
// 定位：工作台顶头像点击后的「个人主页」+ 苹果层级式配置子页（用户名/头像/
// 绑定）——点进行铺满屏子页、左上「<」返回 = 既有手机详情页壳（navController
// pushPage/popPage），本模块只产页面体内容，导航语义零新建。
//
// 数据纪律（作者令「复用官网个人页已有字段与接口，不新造配置面」）：
//   · 全部字段 = /api/neblink/status 的既有账号读数（displayName/email/avatarUrl/
//     peers/loggedIn），经 neblink.js 单点访问器取 —— 禁第二份取数、禁新端点；
//   · 修改入口一律 defer 到官网个人主页（brand.js getProfileUrl，既有接口 +
//     from=client 契约）——客户端不出现任何账号编辑控件（2026-09-05 裁定口径：
//     Username 统一经官网账号中心设置，客户端不提供修改入口）；
//   · 设备绑定读数只作展示，管理入口 = 联系人页既有设备段（不重复造设备面）。
//
// 头像绘制与设置页/Activity Bar 同池：avatarViewState 双态 + paintAvatarSlot
// 就绪门（永不空环），失败 URL 进共享 latch（noteAvatarFailure）。

import { getNeblinkState, avatarViewState, paintAvatarSlot, noteAvatarFailure, onNeblinkStatus } from '../neblink.js';
import { getProfileUrl, brand } from '../brand.js';
import { createIconsIn } from '../utils.js';
import { showLoginModal } from '../activityBar.js';

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** 状态拍 → 全部个人主页面重绘（登录/登出/头像变更；页不在 DOM 时零成本跳过）。 */
const paintCbs = new Set();
let statusSubscribed = false;

/** @param {HTMLElement} body */
function el(body, cls, tag = 'div') {
  const d = document.createElement(tag);
  d.className = cls;
  body.appendChild(d);
  return d;
}

/** 官网跳转（既有接口：品牌契约 profileUrl + from=client；noopener 防 opener）。 */
function openPortal() {
  window.open(getProfileUrl(), '_blank', 'noopener');
}

/** 玻璃行动键（官网个人主页入口；登录键同款形态不同语义色）。 */
function portalButton(body, label) {
  const btn = document.createElement('button');
  btn.className = 'mf-me-portal glass-control';
  btn.type = 'button';
  body.appendChild(btn);
  btn.textContent = label;
  btn.addEventListener('click', (e) => { e.stopPropagation(); openPortal(); });
  return btn;
}

/** 字段行（label + 只读 value；层级子页的信息排布单元）。 */
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

/** 卡内入口行（图标 + 标签 + 右值 + chevron；点 = push 子页）。 */
function navRow(card, icon, label, push, pageId, valueText = '') {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'mf-hub-row';
  row.dataset.mePage = pageId; // QA 断言面：层级行机械定位
  row.innerHTML = `<i data-lucide="${icon}"></i><span class="mf-hub-row-label"></span>` +
    (valueText ? '<span class="mf-hub-row-val"></span>' : '') +
    '<i data-lucide="chevron-right" class="mf-hub-chev"></i>';
  /** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-label')).textContent = label;
  if (valueText) /** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-val')).textContent = valueText;
  row.addEventListener('click', (e) => { e.stopPropagation(); push(pageId); });
  card.appendChild(row);
  return row;
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

/** 个人主页（me）：资料卡 + 账号分组行 + 官网入口。 */
function buildMeHome(body, push) {
  const st = getNeblinkState();
  // 资料卡：大头像 + 名 + 邮箱（未登录态给登录键，与顶栏批登录面同源）。
  const card = el(body, 'mf-me-card');
  const frame = el(card, 'mf-me-avatar-frame big');
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
  if (!st.loggedIn) {
    const login = document.createElement('button');
    login.className = 'mf-me-login glass-control';
    login.type = 'button';
    login.textContent = tt('登录账号', 'Sign in');
    login.addEventListener('click', (e) => { e.stopPropagation(); showLoginModal(); });
    card.appendChild(login);
  }
  // 账号分组（苹果层级入口）。
  el(body, 'mf-hub-label mf-me-grouplabel').textContent = tt('账号', 'Account');
  const rows = el(body, 'mf-hub-rows');
  navRow(rows, 'user-round', tt('用户名', 'Username'), push, 'me-user',
    st.device?.displayName || st.device?.email || '—');
  navRow(rows, 'image', tt('头像', 'Avatar'), push, 'me-avatar');
  navRow(rows, 'link', tt('绑定', 'Bindings'), push, 'me-bind',
    st.device?.email || '—');
  // 配置修改统一在官网（既有接口，不新造配置面）。
  const hint = el(body, 'mf-me-hint');
  hint.textContent = tt('用户名、头像、绑定等配置在官网个人主页统一管理。',
    'Username, avatar and bindings are managed on the website profile.');
  portalButton(body, tt('前往官网个人主页', 'Open website profile'));
  paintCbs.add(() => {
    const s = getNeblinkState();
    nameEl.textContent = s.loggedIn
      ? (s.device?.displayName || s.device?.email || tt('已登录', 'Signed in'))
      : tt('未登录', 'Not signed in');
    subEl.textContent = s.loggedIn ? (s.device?.email || '') : '';
    paintAvatarFrame(frame);
  });
}

/** 用户名子页（只读读数 + 官网账号中心出口）。 */
function buildMeUser(body) {
  const st = getNeblinkState();
  const card = el(body, 'mf-hub-rows mf-me-fields');
  const vName = fieldRow(card, tt('用户名', 'Username'),
    st.device?.displayName || st.device?.email || '—');
  const vEmail = fieldRow(card, tt('邮箱', 'Email'), st.device?.email || '—');
  el(body, 'mf-me-hint').textContent =
    tt('用户名在官网账号中心统一管理，客户端不提供修改入口。',
      'The username is managed on the website account center; the client has no edit entry.');
  portalButton(body, tt('前往官网账号中心', 'Open website account center'));
  paintCbs.add(() => {
    const s = getNeblinkState();
    vName.textContent = s.device?.displayName || s.device?.email || '—';
    vEmail.textContent = s.device?.email || '—';
  });
}

/** 头像子页（大头像读数 + 官网个人主页出口）。 */
function buildMeAvatar(body) {
  const frame = el(body, 'mf-me-hero');
  frame.innerHTML = `
    <span class="mf-me-avatar-frame huge">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">
        <img class="mf-me-avatar-logo" src="${brand.logoUrl}" alt="">
      </picture>
      <img class="mf-me-avatar-photo" alt="" hidden>
    </span>`;
  paintAvatarFrame(frame.querySelector('.mf-me-avatar-frame'));
  el(body, 'mf-me-hint').textContent =
    tt('头像在官网个人主页统一更换。', 'The avatar is changed on the website profile.');
  portalButton(body, tt('前往官网个人主页', 'Open website profile'));
  paintCbs.add(() => paintAvatarFrame(frame.querySelector('.mf-me-avatar-frame')));
}

/** 绑定子页（邮箱/设备/登录态只读读数）。 */
function buildMeBind(body) {
  const st = getNeblinkState();
  const card = el(body, 'mf-hub-rows mf-me-fields');
  const vEmail = fieldRow(card, tt('邮箱账号', 'Email account'), st.device?.email || '—');
  const peers = Array.isArray(st.peers) ? st.peers : [];
  const vDev = fieldRow(card, tt('设备绑定', 'Devices'),
    tt(`本机 + ${peers.length} 台`, `This device + ${peers.length}`));
  const vState = fieldRow(card, tt('登录状态', 'Sign-in'), st.loggedIn ? tt('已登录', 'Signed in') : tt('未登录', 'Not signed in'));
  el(body, 'mf-me-hint').textContent =
    tt('设备管理在联系人页。', 'Manage devices on the Contacts page.');
  paintCbs.add(() => {
    const s = getNeblinkState();
    vEmail.textContent = s.device?.email || '—';
    const n = Array.isArray(s.peers) ? s.peers.length : 0;
    vDev.textContent = tt(`本机 + ${n} 台`, `This device + ${n}`);
    vState.textContent = s.loggedIn ? tt('已登录', 'Signed in') : tt('未登录', 'Not signed in');
  });
}

const BUILDERS = {
  me: buildMeHome,
  'me-user': buildMeUser,
  'me-avatar': buildMeAvatar,
  'me-bind': buildMeBind,
};

/**
 * 构建个人主页层级页的页面体（navController buildSection 分派；幂等——每页
 * 只建一次，重绘走状态订阅）。返回拆除函数（nav 层注销时调用）。
 * @param {string} pageId 'me' | 'me-user' | 'me-avatar' | 'me-bind'
 * @param {HTMLElement} body 页面体容器
 * @param {{ push: (pageId: string) => void }} nav 导航回调（navController pushPage 注入，避免本模块回边成环）
 * @returns {() => void} teardown
 */
export function buildMeFace(pageId, body, nav) {
  const build = BUILDERS[pageId];
  if (!build) return () => {};
  if (pageId === 'me') build(body, nav.push);
  else build(body);
  if (typeof lucide !== 'undefined') createIconsIn(body);
  // 状态订阅（模块级单飞）：一拍重绘全部四个页面在场元素。
  if (!statusSubscribed) {
    statusSubscribed = true;
    onNeblinkStatus(() => { for (const cb of [...paintCbs]) { try { cb(); } catch { /* 单页重绘异常不阻断其余 */ } } });
  }
  return () => { /* 元素随页面壳销毁；paintCbs 回调对缺席元素零成本 */ };
}
