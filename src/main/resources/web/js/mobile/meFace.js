// mobile/meFace.js — 个人主页层级面（手机顶栏批 2026-10-31；件四改写 2026-11-02）。
//
// 定位：工作台顶头像点击后的「个人主页」+ 苹果层级式配置子页（用户名/头像/
// 绑定）——点进铺满屏子页、左上「<」返回 = 既有手机详情页壳（navController
// pushPage/popPage），本模块只产页面体内容，导航语义零新建。
//
// ══ 件四改写（作者令 2026-11-02「设置里…直接修改；这里和官网对齐」）══════════
// 本模块**四处官网出口全部退役**（修前 `openPortal()` / `portalButton()` 在 me /
// me-user / me-avatar / me-bind 各出现一次，且 me-user 的文案里还有一句「前往官网
// 账号中心」）：现在四个页面**零外链、零 window.open、零可编辑控件**，只展示
// `getIdentityReadout()` 的真实读数（与设置页身份卡 / App 内个人资料面同一单点），
// 并给一条明确的「账号服务暂未提供该接口」提示。修不修、改不改由上游接口决定，
// 不由本模块自造 —— 见 settingsIdentity.js 头注的实测清单。
//
// 数据纪律：全部字段 = `settingsIdentity.js` 的 `getIdentityReadout()`（其上游 =
// `/api/neblink/status` 的 id_token claims + `GET /api/users/search` 自证探针），
// 禁第二份取数、禁新端点。头像绘制与设置页/Activity Bar 同池（avatarViewState
// 双态 + paintAvatarSlot 就绪门，永不空环；失败 URL 进共享 latch）。

import { getNeblinkState, avatarViewState, paintAvatarSlot, noteAvatarFailure, onNeblinkStatus, accountCardHTML, bindNeblinkEvents, fetchNeblinkStatus } from '../neblink.js';
import { brand } from '../brand.js';
import { createIconsIn } from '../utils.js';
import { showLoginModal } from '../activityBar.js';
import { getIdentityReadout, openProfileFace, openSettingsAvatarPicker } from '../settingsIdentity.js';
import { t } from '../i18n.js';
import { h } from '../dom.js';

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** 状态拍 → 全部个人主页面重绘（登录/登出/头像变更；页不在 DOM 时零成本跳过）。 */
const paintCbs = new Set();
const accountTeardowns = new WeakMap();
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

/** 卡内入口行（图标 + 标签 + 右值 + chevron；点 = 执行动作，不再跳官网）。 */
function navRow(card, icon, label, onClick, pageId, valueText = '') {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'mf-hub-row';
  row.dataset.mePage = pageId; // QA 断言面：层级行机械定位
  row.append(h('i', { 'data-lucide': icon }), h('span', { class: 'mf-hub-row-label' }));
  if (valueText) row.append(h('span', { class: 'mf-hub-row-val' }));
  row.append(h('i', { 'data-lucide': 'chevron-right', class: 'mf-hub-chev' }));
  /** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-label')).textContent = label;
  if (valueText) /** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-val')).textContent = valueText;
  row.addEventListener('click', (e) => { e.stopPropagation(); onClick(); });
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

/** 个人主页（me）：资料卡 + 账号分组行 + 「编辑资料」入口（App 内，零外链）。 */
function buildMeHome(body, push) {
  const id = getIdentityReadout();
  // 资料卡：大头像 + 名 + 邮箱（未登录态给登录键，与顶栏批登录面同源）。
  const card = el(body, 'mf-me-card sp-identity');
  const frame = el(card, 'mf-me-avatar-frame huge');
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
    login.textContent = tt('登录账号', 'Sign in');
    login.addEventListener('click', (e) => { e.stopPropagation(); showLoginModal(); });
    card.appendChild(login);
  }
  // Reuse the entire settings account card and its existing logout flow.
  const account = el(body, 'mf-account-card');
  let releaseAccount = () => {};
  const paintAccount = () => {
    if (!account.isConnected) return;
    releaseAccount();
    account.innerHTML = accountCardHTML({ mobile: true });
    releaseAccount = bindNeblinkEvents(paintAccount, account);
  };
  accountTeardowns.set(body, () => releaseAccount());
  paintAccount();
  paintCbs.add(paintAccount);
  // 账号分组（苹果层级入口；全部为 App 内页面）。
  el(body, 'mf-hub-label mf-me-grouplabel').textContent = tt('账号', 'Account');
  const rows = el(body, 'mf-hub-rows');
  // 右值禁拿昵称/邮箱冒充用户名（与身份卡同一口径：读不到 = 未知）。
  navRow(rows, 'user-round', tt('用户名', 'Username'), () => push('me-user'), 'me-user',
    id.username || unknownText());
  navRow(rows, 'image', tt('头像', 'Avatar'), () => push('me-avatar'), 'me-avatar');
  navRow(rows, 'link', tt('绑定', 'Bindings'), () => push('me-bind'), 'me-bind',
    id.email || unknownText());
  // 编辑资料 = App 内个人资料面（与设置页胶囊同一面；修前的官网出口在此退役）。
  const edit = document.createElement('button');
  edit.className = 'mf-me-edit glass-control';
  edit.type = 'button';
  edit.id = 'me-edit-profile';
  edit.textContent = tt('编辑资料', 'Edit profile');
  edit.addEventListener('click', (e) => { e.stopPropagation(); openProfileFace(); });
  body.appendChild(edit);
  el(body, 'mf-me-hint').textContent =
    tt('资料与绑定只展示账号服务当前提供的读数；账号服务暂未提供该接口（编辑）。',
      'Profile and bindings show only what the account service currently provides; it does not offer edit endpoints yet.');
  paintCbs.add(() => {
    const s = getIdentityReadout();
    nameEl.textContent = s.loggedIn
      ? (s.nickname || s.username || s.email || tt('已登录', 'Signed in'))
      : tt('未登录', 'Not signed in');
    subEl.textContent = s.loggedIn ? (s.email || '') : '';
    paintAvatarFrame(frame);
  });
}

/** 用户名子页（真实读数：username / nickname / email；零官网出口）。 */
function buildMeUser(body) {
  const id = getIdentityReadout();
  const card = el(body, 'mf-hub-rows mf-me-fields');
  // 用户名 = 自证探针读数；读不到就是「未知」—— **禁**拿昵称/邮箱冒充（那是假读数）。
  const vName = fieldRow(card, tt('用户名', 'Username'), id.username || unknownText());
  const vNick = fieldRow(card, tt('昵称', 'Nickname'), id.nickname || unknownText());
  const vEmail = fieldRow(card, tt('邮箱', 'Email'), id.email || unknownText());
  el(body, 'mf-me-hint').textContent =
    tt('用户名与昵称由账号服务提供，客户端当前没有修改接口。',
      'The username and nickname come from the account service; the client has no edit endpoint yet.');
  paintCbs.add(() => {
    const s = getIdentityReadout();
    vName.textContent = s.username || unknownText();
    vNick.textContent = s.nickname || unknownText();
    vEmail.textContent = s.email || unknownText();
  });
}

/** 头像子页（大头像读数 + 就地换头像入口；零官网出口）。 */
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
  // 点「更换头像」= 换头像流程（与设置页身份卡同一个隐藏文件选择器；修前的官网出口在此退役）。
  const pick = document.createElement('button');
  pick.className = 'mf-me-edit glass-control';
  pick.type = 'button';
  pick.id = 'me-avatar-pick';
  pick.textContent = tt('更换头像', 'Change avatar');
  pick.addEventListener('click', (e) => { e.stopPropagation(); openSettingsAvatarPicker(); });
  body.appendChild(pick);
  el(body, 'mf-me-hint').textContent =
    tt('账号头像上传接口暂未提供，选定图片后不会上传。',
      'The account avatar upload endpoint is not available yet — a picked image will not be uploaded.');
  paintCbs.add(() => paintAvatarFrame(frame.querySelector('.mf-me-avatar-frame')));
}

/** 绑定子页（邮箱/设备/登录态 + GitHub / Google 绑定读数；零官网出口）。 */
function buildMeBind(body) {
  const id = getIdentityReadout();
  const st = getNeblinkState();
  const card = el(body, 'mf-hub-rows mf-me-fields');
  const vEmail = fieldRow(card, tt('邮箱账号', 'Email account'), id.email || unknownText());
  // GitHub / Google 绑定态：上游无接口 ⇒ 恒「未知」（禁拿 legacy githubLogin 冒充，
  // neblink.js 明确该字段不映射——它只承载 legacy enroll handle）。
  const vGh = fieldRow(card, tt('GitHub 绑定', 'GitHub binding'), unknownText());
  const vGg = fieldRow(card, tt('Google 绑定', 'Google binding'), unknownText());
  const peers = Array.isArray(st.peers) ? st.peers : [];
  const vDev = fieldRow(card, tt('设备绑定', 'Devices'),
    tt(`本机 + ${peers.length} 台`, `This device + ${peers.length}`));
  const vState = fieldRow(card, tt('登录状态', 'Sign-in'),
    st.loggedIn ? tt('已登录', 'Signed in') : tt('未登录', 'Not signed in'));
  el(body, 'mf-me-hint').textContent =
    tt('绑定关系由账号服务提供，当前没有查询或修改接口；设备管理在联系人页。',
      'Bindings come from the account service, which offers no read or edit endpoint yet; manage devices on the Contacts page.');
  paintCbs.add(() => {
    const s = getIdentityReadout();
    const s2 = getNeblinkState();
    vEmail.textContent = s.email || unknownText();
    const n = Array.isArray(s2.peers) ? s2.peers.length : 0;
    vDev.textContent = tt(`本机 + ${n} 台`, `This device + ${n}`);
    vState.textContent = s2.loggedIn ? tt('已登录', 'Signed in') : tt('未登录', 'Not signed in');
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
  const existingCbs = new Set(paintCbs);
  if (pageId === 'me') build(body, nav.push);
  else build(body);
  const ownCbs = [...paintCbs].filter(cb => !existingCbs.has(cb));
  for (const cb of ownCbs) cb();
  if (pageId === 'me') void fetchNeblinkStatus();
  if (typeof lucide !== 'undefined') createIconsIn(body);
  // 状态订阅（模块级单飞）：一拍重绘全部四个页面在场元素。
  if (!unsubscribeStatus) {
    unsubscribeStatus = onNeblinkStatus(() => { for (const cb of [...paintCbs]) { try { cb(); } catch { /* One face must not block the others. */ } } });
  }
  return () => {
    accountTeardowns.get(body)?.();
    accountTeardowns.delete(body);
    for (const cb of ownCbs) paintCbs.delete(cb);
    if (!paintCbs.size) { unsubscribeStatus?.(); unsubscribeStatus = null; }
  };
}
