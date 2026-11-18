// settingsIdentity.js — 设置页身份卡 + App 内「个人资料」面（件四 · 2026-11-02 作者令）。
//
// 作者原令（逐字）：「设置里，头像在上，下面是用户名和邮箱。点击用户和邮箱的这个
// 胶囊，直接进入自己的 profile 设置页面；可以修改自己的 user name / nickname，
// 修改和查看自己的 GitHub 和谷歌的快捷登陆绑定。这里和官网对齐。现在的设计是跳转
// 到官网，但是我是要在这里直接修改。」
//
// 本模块交付两件，都在 App 内，零 window.open：
//   ① 设置页顶部身份卡 —— 头像在上（`#settings-avatar-entry`，沿用既有选择器链），
//      下面一行胶囊 `#settings-identity-capsule`（用户名 + 邮箱同胶囊），点胶囊 =
//      开 App 内个人资料面；
//   ② 个人资料面 `#settings-profile-overlay` —— 头像 / 用户名 / 昵称 / 邮箱 /
//      GitHub 绑定 / Google 绑定，全部**只显示真实读数**，无一处假可编辑开关。
//
// ══ 数据纪律（本批实测，禁凭 recon 报告照抄）═══════════════════════════════
// 上游/网关探针（本机 :6138，2026-11-02）：
//   · 自身用户名的**唯一**可用权威读路 = `GET /api/users/search?q=<自己的 email>`
//     → 200 `{found:true,user:{userId,username,display_name,avatar},relation_status:'self'}`
//     （自证靠 `relation_status === 'self'`）。经 friendsApi.searchUser 单点（同一契约
//     单一实现，禁第二份取数）。
//   · `PUT /api/users/me/neblink-id` 与 `GET .../neblink-id/available` → 网关 502
//     （上游 `HTTP 403 {"error":"Invalid or expired token"}`）⇒ **上游该端点已不可用**，
//     不得据此造出「改了必失败」的假输入框。
//   · `PUT /api/neblink/device-info` → 200，但白名单只透传 `userDescription` +
//     `capabilities`（NeblinkRoutes.scala:904-912）⇒ **avatarUrl / githubLogin / email /
//     username 一律写不进去**（实测写 avatarUrl 后 status 读数不变）。
//   · `PUT|POST /api/users/me/avatar`、`/api/users/me`、`/api/users/me/bindings`
//     → 404（网关无此路由）。
//   ⇒ 本批**零可写字段**：身份卡与资料面只渲染真实读数；不可读的（GitHub / Google
//     绑定）显示「未知」+ 一句「账号服务暂未提供该接口」，并在交付报告里列卡点。
//     禁 window.open/跳官网伪装「编辑」，禁假开关，禁本地造值回显。
//
// ══ 头像点击语义（作者令「点击头像就是直接换头像」）════════════════════════
// 点头像 ≠ 进 profile 面。已登录 ⇒ 触发现役换头像流程的**同一机械形态**
// （隐藏 `<input type="file" accept="image/*">` + `.click()`，与
// `agentProfile.js` 头像预览盒同款）。账号头像写面不存在 ⇒ 选完图后**不假上传**，
// 只给可见提示 `settings.avatarChangeUnavailable`。未登录 ⇒ 登录卡（既有
// activityBar.activateAccountEntry 腿，本模块不碰：登录弹窗必须在手势栈里同步预留，
// 归属 activityBar，避免本叶子模块回边成环）。
//
// 模块纪律：叶子模块（只 import neblink / friendsApi / utils / i18n / brand /
// mobile/popSheet，均为既有无回边依赖），不 import activityBar / sidebar / navController。

import { getNeblinkState, avatarViewState, paintAvatarSlot, noteAvatarFailure, onNeblinkStatus } from './neblink.js';
import { searchUser } from './friendsApi.js';
import { escapeHtml, createIconsIn } from './utils.js';
import { t } from './i18n.js';
import { brand, getProfileUrl } from './brand.js';
import { mobileSheetBack, isMobileLayer } from './mobile/popSheet.js';
import { buildDeviceList, paintDeviceList, bindDeviceList, ensureDeviceListLive, deviceIdKey } from './profileDevices.js';

// ── 自身身份读数（用户名 / userId）─────────────────────────────────────────
// 缓存键 = 当前账号 email（登录态改变或 email 改变才重探）。结论只有两种：
// ok（拿到 relation_status==='self' 的 username）/ unavailable（读不到 ⇒ 界面显
// 「未知」，**不做重试风暴**，也不拿昵称/邮箱冒充用户名）。
/** @typedef {{email: string, status: 'idle'|'loading'|'ok'|'unavailable', username: string, userId: string}} SelfState */
/** @type {SelfState} */
let selfState = { email: '', status: 'idle', username: '', userId: '' };
let probeInFlight = false;

/** 订阅面：身份卡与资料面共用一拍重绘（状态拍 + 自证探针结果都走这里）。 */
const paintCbs = /** @type {Set<() => void>} */ (new Set());
/** 订阅单飞标记（见 subscribeOnce）。 */
let subscribed = false;

function notify() {
  for (const cb of [...paintCbs]) {
    try { cb(); } catch (e) { console.error('[settingsIdentity] paint failed:', e); }
  }
}

/** 当前账号 email（唯一探针键；空 = 不可探）。 */
function accountEmail() {
  const st = getNeblinkState();
  if (!st.loggedIn) return '';
  return String(st.device?.email || '').trim();
}

/**
 * 自证探针（单飞 + 结论缓存）：`GET /api/users/search?q=<email>` 且
 * `relation_status==='self'` ⇒ username 权威读数。失败一律降级为 unavailable，
 * 不抛、不重试、不冒泡（契约失败不得把设置页打崩）。
 * @param {string} email
 * @returns {Promise<{username: string, userId: string} | null>}
 */
async function probeSelf(email) {
  try {
    const r = await searchUser(email);
    if (r && r.found === true && r.relation_status === 'self') {
      return { username: String(r.username || ''), userId: String(r.userId || '') };
    }
    return null;
  } catch {
    return null;
  }
}

/** 需要时发起探针（幂等；同一 email 已有结论则不再探）。 */
function ensureSelfProbe() {
  const email = accountEmail();
  if (!email) {
    if (selfState.status !== 'idle') { selfState = { email: '', status: 'idle', username: '', userId: '' }; notify(); }
    return;
  }
  if (selfState.email === email && selfState.status !== 'idle') return; // ok / unavailable 都是结论
  if (probeInFlight) return;
  probeInFlight = true;
  selfState = { email, status: 'loading', username: '', userId: '' };
  notify();
  probeSelf(email).then((r) => {
    probeInFlight = false;
    selfState = {
      email,
      status: r ? 'ok' : 'unavailable',
      username: r ? r.username : '',
      userId: r ? r.userId : '',
    };
    notify();
  });
}

/**
 * 官网账号页的**纯文本**读数（profile-multidevice 批：编辑落点在官网，本面只给
 * 指引）。刻意**不**产 `<a href>`、不 `window.open` —— 上一批已裁定「零官网出口」
 * 的理由（跳官网不能替代编辑，只造成「看着能改其实没改」的假面）依然成立；本批
 * 变的是**说清楚去哪儿改**，不是把出口装回来。同一条纪律由既有验收断言
 * `tests/profile-settings-batch-acceptance.spec.mjs:392`（文档内零指向官网账号页的
 * `<a href>`）机械守住。
 * @returns {string} 例 `mystand.space/profile`
 */
function profileUrlText() {
  try {
    const u = new URL(getProfileUrl());
    return `${u.host}${u.pathname}`;
  } catch {
    return String(brand.domain || '');
  }
}

/** 未读到的值一律显「未知」（禁空白禁占位假值）。 */
function orUnknown(v) {
  const s = String(v == null ? '' : v).trim();
  return s || t('settings.identityUnknown');
}

/**
 * 身份读数**单点**（身份卡 / 资料面 / 手机 me 页共用，禁第二份取数链）。
 * 六个能力位与交付报告的能力矩阵一一对应：
 *   username   自证探针（`relation_status==='self'`）——读不到 = 未知
 *   nickname   status.device.displayName（id_token name claim，只读）
 *   email      status.device.email（id_token email claim，只读）
 *   avatarUrl  status.device.avatarUrl（只读；写面无端点）
 *   github     绑定态**无接口** ⇒ 恒 null（禁拿 legacy githubLogin 冒充）
 *   google     绑定态**无接口** ⇒ 恒 null
 * @returns {{loggedIn: boolean, userId: string, username: string, nickname: string,
 *   email: string, avatarUrl: string, github: null, google: null}}
 */
export function getIdentityReadout() {
  const st = getNeblinkState();
  return {
    loggedIn: !!st.loggedIn,
    userId: selfState.status === 'ok' ? selfState.userId : '',
    username: selfState.status === 'ok' ? selfState.username : '',
    nickname: String(st.device?.displayName || '').trim(),
    email: String(st.device?.email || '').trim(),
    avatarUrl: String(st.device?.avatarUrl || '').trim(),
    github: null,
    google: null,
  };
}

// ── ① 身份卡（设置页账号段顶部）──────────────────────────────────────────

/**
 * 身份卡 HTML（每次 renderSettings 重建；头像按钮 id 沿用既有 `#settings-avatar-entry`，
 * 保住 settings-cleanup / 截图脚本等既有选择器链）。
 * @returns {string}
 */
export function settingsIdentityCardHTML() {
  return `
    <div class="settings-identity-card" id="settings-identity-card">
      <button class="settings-avatar-entry" id="settings-avatar-entry" type="button"
        aria-label="${escapeHtml(t('settings.identityChangeAvatar'))}" title="${escapeHtml(t('settings.identityChangeAvatar'))}">
        <span class="settings-avatar-frame">
          <picture>
            <source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">
            <img class="settings-avatar-logo" src="${brand.logoUrl}" alt="">
          </picture>
          <img class="settings-avatar-photo" alt="" hidden>
        </span>
      </button>
      <button class="settings-identity-capsule" id="settings-identity-capsule" type="button"
        aria-label="${escapeHtml(t('settings.identityCardLabel'))}">
        <span class="settings-identity-username"></span>
        <span class="settings-identity-sep" aria-hidden="true">·</span>
        <span class="settings-identity-email"></span>
        <i data-lucide="chevron-right" class="settings-identity-chev"></i>
      </button>
      <div class="settings-identity-notice" id="settings-identity-notice" role="status" hidden></div>
    </div>`;
}

/** 已绑定的胶囊（WeakSet：每次重渲染是新节点，绑定不重复）。 */
const boundCapsules = new WeakSet();

/**
 * 换头像流程的隐藏文件选择器（**单例**，挂 body）。与 `agentProfile.js` 的
 * `fileInput` 同款机械形态（type=file + accept=image/* + hidden，点头像 →
 * `input.click()` → change）；挂 body 而非挂卡片内，是为了让**三个入口**
 * （设置页身份卡 / 资料面 / 手机 me-avatar 子页）共用同一枚选择器，
 * 避免每个入口各自造一份（双真源必然分叉）。重复调用幂等。
 * @returns {HTMLInputElement}
 */
function ensureAvatarInput() {
  const existing = document.getElementById('settings-avatar-file');
  if (existing instanceof HTMLInputElement) return existing;
  const input = document.createElement('input');
  input.type = 'file';
  input.id = 'settings-avatar-file';
  input.accept = 'image/*';
  input.hidden = true;
  input.setAttribute('aria-hidden', 'true');
  input.addEventListener('change', () => {
    const f = input.files && input.files[0];
    input.value = ''; // 允许重复选同一张
    onAvatarFilePicked(f);
  });
  document.body.appendChild(input);
  return input;
}

/**
 * 头像「换头像流程」入口：触发隐藏 file input 的点击。三个入口共用
 * （设置页身份卡 / 资料面 / 手机 me-avatar）。
 * @returns {boolean} 模块未就位时 false（调用方可退回菜单）
 */
export function openSettingsAvatarPicker() {
  if (typeof document === 'undefined' || !document.body) return false;
  ensureAvatarInput().click();
  return true;
}

/** 卡片内可见提示（自动 6s 收起；不计入 DOM 外链面）。 */
function showIdentityNotice(text) {
  const el = document.getElementById('settings-identity-notice');
  if (!(el instanceof HTMLElement)) return;
  el.textContent = text;
  el.hidden = false;
  if (noticeTimer) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { if (el.isConnected) el.hidden = true; }, 6000);
}
/** @type {ReturnType<typeof setTimeout> | null} */
let noticeTimer = null;

/**
 * 选定文件后的处理：**账号头像写面不存在**（见头注实测）⇒ 不读字节、不降采样、
 * 不写任何地方、不 window.open；只给一句可见提示。宁可「看得到改不了」，
 * 也不做「看着改了其实没改」的假上传。
 */
function onAvatarFilePicked(file) {
  if (!file) return;
  if (!String(file.type || '').startsWith('image/')) return;
  showIdentityNotice(t('settings.avatarChangeUnavailable'));
}

/**
 * 绑定卡片内交互（幂等）。头像按钮本身**不在**这里绑：已登录 = 换头像流程、
 * 未登录 = 登录卡，两条腿都由 activityBar 的既有 document 级委托统一承担
 * （`bindSettingsAccountEntry`），避免两个监听器抢同一击。
 */
function ensureBound() {
  const capsule = document.getElementById('settings-identity-capsule');
  if (capsule instanceof HTMLElement && !boundCapsules.has(capsule)) {
    boundCapsules.add(capsule);
    capsule.addEventListener('click', (e) => { e.stopPropagation(); openProfileFace(); });
  }
}

/**
 * 绘制身份卡（每次 renderSettings 之后 + 每次状态拍）。元素不在场零成本。
 * 同时确保状态订阅与自证探针就位。
 */
export function paintSettingsIdentity() {
  const root = document.getElementById('settings-identity-card');
  if (!root) return;
  ensureBound();
  ensureAvatarInput(); // 换头像流程的选择器随身份卡一起就位（卡片在场即可用）
  const id = getIdentityReadout();

  // 头像双态 —— 与活动条/工作台同一个判定（avatarViewState）与同一个就绪门
  // （paintAvatarSlot：照片字节可绘制前由 logo 占槽，槽位永不空）。
  const { url: validAvatarUrl, showPhoto } = avatarViewState();
  const logoEl = root.querySelector('.settings-avatar-logo');
  const photoEl = root.querySelector('.settings-avatar-photo');
  if (photoEl instanceof HTMLImageElement) {
    paintAvatarSlot(photoEl, logoEl instanceof HTMLElement ? logoEl : null, validAvatarUrl, showPhoto, () => {
      noteAvatarFailure(validAvatarUrl);
      const view = avatarViewState();
      photoEl.hidden = true;
      if (logoEl instanceof HTMLElement) logoEl.hidden = view.showPhoto;
    });
  }

  // 胶囊 = 用户名 + 邮箱（同一枚按钮内，作者令「用户名和邮箱的这个胶囊」）。
  const unEl = root.querySelector('.settings-identity-username');
  const emEl = root.querySelector('.settings-identity-email');
  if (unEl instanceof HTMLElement) {
    unEl.textContent = id.loggedIn ? (id.username || t('settings.identityUnknown')) : t('settings.identityNotSignedIn');
  }
  if (emEl instanceof HTMLElement) {
    emEl.textContent = id.loggedIn ? orUnknown(id.email) : '';
  }
  const sep = root.querySelector('.settings-identity-sep');
  if (sep instanceof HTMLElement) sep.hidden = !id.loggedIn;

  subscribeOnce();
  ensureSelfProbe();
}

/** 状态订阅 + 两个绘制器注册（模块级单飞；**禁**在每次绘制里 add 新闭包，
 *  否则订阅集合会随绘制次数无界增长）。 */
function subscribeOnce() {
  if (subscribed) return;
  subscribed = true;
  onNeblinkStatus(() => notify());
  paintCbs.add(() => paintSettingsIdentity());
  paintCbs.add(() => paintProfileFace());
}

// ── ② App 内个人资料面（`#settings-profile-overlay`）──────────────────────
//
// 形态 = 既有 `.cfg-modal-overlay` + `.cfg-modal` 覆层（dropbox / friendGroups 同族，
// 零新弹层形态）；手机层由 mobileLayer.css 既有的 A 族铺平规则覆盖（overlay/box
// 两个类名都在该族选择器里）+ `mobileSheetBack` 注入左上「<」返回（桌面不注入）。
// 整个面**零可编辑控件**：没有 input/select/switch，只有读数行 + 一句接口缺失提示。

/** @type {HTMLElement | null} */
let profileOverlay = null;

const PROFILE_FIELDS = [
  // [field id, i18n label key, 取值来源]  value 由 paintProfileFace 单点填充
  ['username', 'settings.profileUsername'],
  ['nickname', 'settings.profileNickname'],
  ['email', 'settings.profileEmail'],
  ['github', 'settings.profileGithub'],
  ['google', 'settings.profileGoogle'],
];

/** 建面（幂等；只建一次，之后走重绘）。 */
function ensureProfileFace() {
  const existing = document.getElementById('settings-profile-overlay');
  if (existing instanceof HTMLElement) { profileOverlay = existing; return existing; }

  const overlay = document.createElement('div');
  overlay.id = 'settings-profile-overlay';
  overlay.className = 'cfg-modal-overlay';
  overlay.hidden = true;
  overlay.innerHTML = `
    <div class="cfg-modal settings-profile-modal" role="dialog" aria-modal="true"
      aria-label="${escapeHtml(t('settings.profileSelf'))}">
      <div class="cfg-modal-title">
        <span>${escapeHtml(t('settings.profileSelf'))}</span>
        ${isMobileLayer() ? '' : `<button class="settings-profile-close" id="settings-profile-close" type="button"
          aria-label="${escapeHtml(t('settings.profileClose'))}"><i data-lucide="x"></i></button>`}
      </div>
      <div class="sp-hero">
        <span class="sp-avatar-frame">
          <picture>
            <source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">
            <img class="sp-avatar-logo" src="${brand.logoUrl}" alt="">
          </picture>
          <img class="sp-avatar-photo" alt="" hidden>
        </span>
        <span class="sp-hero-name"></span>
        <span class="sp-hero-sub"></span>
      </div>
      <div class="sp-fields">
        ${PROFILE_FIELDS.map(([id, labelKey]) => `
        <div class="sp-field" data-sp-field="${id}">
          <span class="sp-label">${escapeHtml(t(labelKey))}</span>
          <span class="sp-value"></span>
        </div>`).join('')}
      </div>
      <p class="sp-note" role="note">${escapeHtml(t('settings.profileReadonlyNote'))}</p>
      <p class="sp-note" role="note">${escapeHtml(t('profile.editOnWebsite', { where: profileUrlText() }))}</p>
      <p class="sp-note sp-note-warn" role="note">${escapeHtml(t('settings.accountServiceUnavailable'))}</p>
      <div class="pd-section-label" role="heading" aria-level="3">${escapeHtml(t('profile.deviceSection'))}</div>
      <div class="sp-fields" id="settings-profile-devices"></div>
    </div>`;
  document.body.appendChild(overlay);

  const close = () => closeProfileFace();
  const closeBtn = overlay.querySelector('#settings-profile-close');
  if (closeBtn instanceof HTMLElement) closeBtn.addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !overlay.hidden) close();
  });
  mobileSheetBack(overlay, close);
  // 设备清单挂载（标记单点 = profileDevices.buildDeviceList()；本面只挂，零第二套
  // HTML 串 —— 清单节点本身不参与上面那条 overlay 模板串）。
  const devHost = overlay.querySelector('#settings-profile-devices');
  if (devHost instanceof HTMLElement) {
    devHost.replaceChildren(buildDeviceList());
    devHost.dataset.pdIds = deviceIdKey();
  }
  bindDeviceList(overlay, paintProfileFace); // 逐设备退出（本机行 = 既有唯一登出链）
  ensureDeviceListLive(overlay);
  if (typeof lucide !== 'undefined') createIconsIn(overlay);
  profileOverlay = overlay;
  return overlay;
}

/** 资料面重绘（读数全部走单点：状态拍 + 自证探针）。 */
function paintProfileFace() {
  const overlay = profileOverlay && profileOverlay.isConnected
    ? profileOverlay
    : document.getElementById('settings-profile-overlay');
  if (!(overlay instanceof HTMLElement) || overlay.hidden) return;
  const id = getIdentityReadout();

  // 头像大头像（同池同就绪门）。
  const frame = overlay.querySelector('.sp-avatar-frame');
  const photoEl = overlay.querySelector('.sp-avatar-photo');
  const logoEl = overlay.querySelector('.sp-avatar-logo');
  if (photoEl instanceof HTMLImageElement) {
    const { url: validAvatarUrl, showPhoto } = avatarViewState();
    paintAvatarSlot(photoEl, logoEl instanceof HTMLElement ? logoEl : null, validAvatarUrl, showPhoto, () => {
      noteAvatarFailure(validAvatarUrl);
      photoEl.hidden = true;
      if (logoEl instanceof HTMLElement) logoEl.hidden = avatarViewState().showPhoto;
    });
  }
  if (frame instanceof HTMLElement) frame.dataset.loggedIn = id.loggedIn ? '1' : '0';

  const heroName = overlay.querySelector('.sp-hero-name');
  if (heroName instanceof HTMLElement) {
    heroName.textContent = id.loggedIn
      ? orUnknown(id.nickname || id.username || id.email)
      : t('settings.identityNotSignedIn');
  }
  const heroSub = overlay.querySelector('.sp-hero-sub');
  if (heroSub instanceof HTMLElement) heroSub.textContent = id.loggedIn ? orUnknown(id.email) : '';

  /** @param {string} fieldId @param {string} value @param {boolean} [unavailable] */
  const setField = (fieldId, value, unavailable = false) => {
    const row = overlay.querySelector(`.sp-field[data-sp-field="${fieldId}"]`);
    if (!(row instanceof HTMLElement)) return;
    const v = row.querySelector('.sp-value');
    if (v instanceof HTMLElement) v.textContent = value;
    row.dataset.spState = unavailable ? 'unavailable' : 'read';
  };

  // 取值面（逐字段，全部真实读数）：
  //   username → 自证探针（relation_status==='self'）；读不到 = 未知（不冒充）
  //   nickname → status.device.displayName（id_token name claim，只读）
  //   email    → status.device.email（id_token email claim，只读）
  //   github / google 绑定 → **无接口**：恒「未知」（禁拿 legacy githubLogin 冒充，
  //              neblink.js 明确该字段不映射）
  setField('username', id.username || t('settings.identityUnknown'), !id.username);
  setField('nickname', orUnknown(id.nickname));
  setField('email', orUnknown(id.email));
  setField('github', t('settings.identityUnknown'), true);
  setField('google', t('settings.identityUnknown'), true);

  // 设备清单（profile-multidevice 批）：设备集合变了才重建行（重建会吃掉正在进行的
  // 退出动作的禁用态），文本读数每次拍都刷。
  const devHost = overlay.querySelector('#settings-profile-devices');
  if (devHost instanceof HTMLElement) {
    const wanted = deviceIdKey();
    if (devHost.dataset.pdIds !== wanted) {
      devHost.replaceChildren(buildDeviceList());
      devHost.dataset.pdIds = wanted;
      bindDeviceList(devHost, paintProfileFace);
    }
    paintDeviceList(devHost);
  }
}

/** 打开 App 内个人资料面（唯一入口：身份卡胶囊 / activityBar 账号菜单 / 手机 me 页）。 */
export function openProfileFace() {
  const overlay = ensureProfileFace();
  overlay.hidden = false;
  // 3(d) 开面对账（panel-language 批 2026-11-06）：壳只建一次（ensureProfileFace 幂等），
  // 视口跨档（桌面建壳 → 缩进手机层重开）时 X 会残留 ⇒ 每次打开对账一次：
  // 手机层返回键在（mobileSheetBack 注入 .mfs-back）⇒ X 摘除（判定与建壳 ternary 同源
  // = popSheet.isMobileLayer）。同一行守卫+选择器相邻（机检 P2 = check-panel-unify.mjs）。
  if (isMobileLayer()) overlay.querySelector('#settings-profile-close')?.remove();
  paintProfileFace();
  subscribeOnce();
  ensureSelfProbe();
  return overlay;
}

/** 关闭个人资料面（幂等）。 */
export function closeProfileFace() {
  const overlay = document.getElementById('settings-profile-overlay');
  if (overlay instanceof HTMLElement) overlay.hidden = true;
}
