// profileFace.js — 微信式「联系人/群 Profile」面（件一，作者令 2026-11-02）。
//
// 作者原话：「联系人和群是没有 Profile 的，类似于微信，我们应该要有 profile。profile 里，
// 有两个描述，一是用户给自己写的描述，二是我们给好友备注的描述，这样你查询的时候，可以
// 根据描述来做判断。」
//
// 本模块 = **Profile 面的唯一属主**（好友 / 群两种主体的建面 + 读写 + 回读），并明确
// 导出可被「右上三点」入口调用的独立函数 `openFriendProfile` / `openGroupProfile`
// （另一节点只需 import 这两个函数即可挂入口，禁第二套面）。
//
// 双描述语义矩阵（与后端 `ProfileDescriptions` / `friendsApi` 同名注释一一对应）：
//
// | 主体 | 字段           | 语义             | 真源           | 落盘（本仓）                | 可编辑 |
// |------|----------------|------------------|----------------|-----------------------------|--------|
// | 好友 | `bio`          | 对方自我介绍     | 上游（当前无） | profile-descriptions.json   | ✅（本地缓存） |
// | 好友 | `remark`       | 我给对方的备注   | 本仓本地       | friend-remarks.json（既有） | ✅ |
// | 群   | `description`  | 群自己的介绍     | 上游（当前无） | profile-descriptions.json   | ✅（本地缓存） |
// | 群   | `remark`       | 我给群起的备注   | 本仓本地       | profile-descriptions.json   | ✅ |
//
// 🔴 「本地缓存」= 语义真源在上游、上游**尚无**该字段 ⇒ 本面在字段旁**显式**标
// `profile.localCacheHint`（本地缓存·未同步）。绝不把本地写读画成「已同步给对方/群」。
//
// 形态纪律（沿用本仓既有先例，零新弹层家族）：
//  · 壳 = `.cfg-modal-overlay` + `.cfg-modal`（`settingsIdentity.js` / `friendGroups.js`
//    同族）；手机层铺平由 `mobileLayer.css` 的 A 族既有规则覆盖，返回钮用
//    `popSheet.mobileSheetBack`（同款「左上 <」）。
//  · 输入件 = `.cfg-input`（`modal.css:522`），提交键 = `.glass-control`。
//  · 零裸 hex：全部走 `var(--color-*)` / `var(--glass-*)`（CSS 在 friends.css「Profile 面」段）。
//  · imeGuard：本面是新增输入面 ⇒ 同批接入 `bindImeGuard` / `isImeComposing`
//    （作者裁定 2026-09-12：新输入面必须接入，禁手写组字判定）。

import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import * as api from './friendsApi.js';
import { personLabel } from './messages.js';
import { avatarNodeFor } from './avatarRender.js';
import { bindImeGuard, isImeComposing } from './imeGuard.js';
import { mobileSheetBack } from './mobile/popSheet.js';

/** 描述长度上限（与好友备注 REMARK_MAX=64 同档：显示名/介绍不触文件系统）。 */
export const BIO_MAX = 64;

/** 当前打开的面（单例；同一时刻只有一面）。 @type {HTMLElement|null} */
let overlay = null;
/** @type {{kind: 'friend'|'group', id: string, conv: any}|null} */
let current = null;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

/** 头像节点（复用 `avatarRender.avatarNodeFor` 唯一实现；无头像 ⇒ 首字母）。 */
function avatarNode(person, size) {
  const wrap = el('span', `fm-avatar fm-avatar-${size}`);
  const node = person ? avatarNodeFor(person) : null;
  if (node) wrap.appendChild(node);
  else wrap.textContent = ((person && (person.name || person.neblinkId)) || '?').trim().charAt(0).toUpperCase();
  wrap.setAttribute('aria-hidden', 'true');
  return wrap;
}

/** 主体显示名（好友 = 备注 > 显示名 > NL 号；群 = 群名，同列表/窗头口径）。 */
function subjectTitle() {
  if (!current) return '';
  if (current.kind === 'group') {
    const title = typeof current.conv?.title === 'string' ? current.conv.title.trim() : '';
    return title || t('messages.groupUntitled');
  }
  return personLabel(current.conv) || '';
}

/**
 * 字段回读（**唯一读路**）：好友 bio/remark 从好友快照读，群 description/remark
 * 从群列表读，全部经 `friendsApi` 的契约读点（禁在此处读内部缓存结构）。
 * @param {string} field
 * @returns {Promise<string>}
 */
async function readFieldValue(field) {
  if (!current) return '';
  if (current.kind === 'friend') {
    const list = await api.getFriends();
    const hit = (list.friends || []).find((f) => String(f.userId) === String(current.id));
    if (!hit) return '';
    return field === 'bio' ? (hit.bio || '') : (hit.remark || '');
  }
  const env = await api.getGroups();
  const g = (env.groups || []).find((x) => String(x.conversationId) === String(current.id));
  if (!g) return '';
  return field === 'description' ? (g.description || '') : (g.remark || '');
}

/**
 * 单字段编辑行：label [+ 缓存提示] + 输入框 + 保存键。
 * @param {{
 *   field: string, labelKey: string, value: string, max: number,
 *   placeholderKey: string, hintKey?: string, onSave: (v: string) => Promise<any>,
 * }} spec
 */
function fieldRow(spec) {
  const row = el('div', 'pf-field');
  row.dataset.pfField = spec.field;
  const head = el('div', 'pf-field-head');
  head.appendChild(el('span', 'pf-label', t(spec.labelKey)));
  if (spec.hintKey) {
    const hint = el('span', 'pf-cache-hint', t(spec.hintKey));
    hint.dataset.pfCacheHint = spec.field;
    head.appendChild(hint);
  }
  row.appendChild(head);

  const input = document.createElement('input');
  input.className = 'cfg-input pf-input';
  input.type = 'text';
  input.maxLength = spec.max;
  input.value = spec.value || '';
  input.placeholder = t(spec.placeholderKey);
  input.autocomplete = 'off';
  input.dataset.pfInput = spec.field;
  bindImeGuard(input);

  const foot = el('div', 'pf-field-foot');
  const save = el('button', 'glass-control pf-save', t('profile.save'));
  save.type = 'button';
  save.dataset.pfSave = spec.field;

  const commit = async () => {
    const v = input.value.trim().slice(0, spec.max);
    save.disabled = true;
    try {
      await spec.onSave(v);
      // 回读一致：保存后**以真值回读**为准（不做乐观写）
      const fresh = await readFieldValue(spec.field);
      input.value = fresh || '';
      // 列表面的**唯一刷新信号**（禁本模块自己去摸 contacts/messages 的 DOM）：
      // 好友域 ⇒ `fm-friends-changed`（contacts 重取 + messages 重取会话），
      // 群域 ⇒ `fm-groups-changed`。这正是「描述进检索」成立的前提——过滤面读的是
      // 各属主自己的快照，不重取就还拿着改前值。
      window.dispatchEvent(new CustomEvent(
        current && current.kind === 'friend' ? 'fm-friends-changed' : 'fm-groups-changed'
      ));
      window.__showToast?.(t('profile.saved'), 'info');
    } catch (err) {
      // 失败**不清空输入**（同设备描述面板口径：写失败不收起）
      window.__showToast?.(t('messages.networkError'), 'error');
      console.debug('[profileFace] save failed', spec.field, err && err.message);
    } finally {
      save.disabled = false;
    }
  };
  save.addEventListener('click', commit);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
    if (e.key === 'Enter') { e.preventDefault(); commit(); }
  });
  foot.appendChild(save);
  row.appendChild(input);
  row.appendChild(foot);
  return row;
}

/** 建壳（幂等；壳常驻，内容每次 open 重建）。 */
function ensureShell() {
  if (overlay && overlay.isConnected) return overlay;
  const ov = document.createElement('div');
  ov.id = 'profile-face-overlay';
  ov.className = 'cfg-modal-overlay';
  ov.hidden = true;
  document.body.appendChild(ov);
  ov.addEventListener('click', (e) => { if (e.target === ov) closeProfile(); });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && overlay && !overlay.hidden) closeProfile();
  });
  overlay = ov;
  return ov;
}

/** 内容重建（每次 open 都重建：主体可换，禁跨主体复用旧输入框值）。 */
function renderBody() {
  const ov = ensureShell();
  const label = current?.kind === 'group' ? t('profile.groupTitle') : t('profile.friendTitle');
  const modal = el('div', 'cfg-modal pf-modal');
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-modal', 'true');
  modal.setAttribute('aria-label', label);
  modal.dataset.pfKind = current?.kind || '';

  const titleBar = el('div', 'cfg-modal-title');
  titleBar.appendChild(el('span', null, label));
  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'pf-close';
  closeBtn.id = 'profile-face-close';
  closeBtn.setAttribute('aria-label', t('profile.close'));
  closeBtn.innerHTML = '<i data-lucide="x"></i>';
  closeBtn.addEventListener('click', () => closeProfile());
  titleBar.appendChild(closeBtn);
  modal.appendChild(titleBar);

  const hero = el('div', 'pf-hero');
  if (current?.kind === 'group') {
    hero.appendChild(avatarNode({ name: current.conv?.title || '' }, 56));
    hero.appendChild(el('div', 'pf-hero-name', subjectTitle()));
    hero.appendChild(el('div', 'pf-hero-sub', t('messages.memberCount', { n: Number(current.conv?.memberCount) || 0 })));
  } else {
    const f = current?.conv || {};
    hero.appendChild(avatarNode({ name: f.name, neblinkId: f.neblinkId, avatarUrl: f.avatarUrl }, 56));
    hero.appendChild(el('div', 'pf-hero-name', subjectTitle()));
    hero.appendChild(el('div', 'pf-hero-sub', f.neblinkId || ''));
  }
  modal.appendChild(hero);

  const body = el('div', 'pf-fields');
  modal.appendChild(body);
  ov.replaceChildren(modal);
  return { ov, body };
}

/** 面开好之后的共同收尾（手机层返回钮 + 图标）。 */
function afterMount(ov) {
  ov.hidden = false;
  if (document.body.classList.contains('mobile-layer')) mobileSheetBack(ov, () => closeProfile());
  if (typeof lucide !== 'undefined') createIconsIn(ov);
  return ov;
}

/**
 * 打开好友 Profile（**独立函数，供三点入口/行右键调用**）。
 * @param {any} friend 好友行（内部 wire 形态 {userId,neblinkId,name,avatarUrl,remark,bio}）
 * @returns {Promise<HTMLElement|null>}
 */
export async function openFriendProfile(friend) {
  if (!friend || !friend.userId) return null;
  current = { kind: 'friend', id: String(friend.userId), conv: friend };
  const { ov, body } = renderBody();

  let fresh = friend;
  try {
    const list = await api.getFriends();
    fresh = (list.friends || []).find((f) => String(f.userId) === String(friend.userId)) || friend;
    current.conv = fresh;
  } catch { /* 读失败 = 用传入行（禁因刷不到就建不出面） */ }

  body.appendChild(fieldRow({
    field: 'remark',
    labelKey: 'profile.friendRemarkLabel',
    value: fresh.remark || '',
    max: BIO_MAX,
    placeholderKey: 'profile.friendRemarkPlaceholder',
    onSave: (v) => api.setFriendRemark(current.id, v),
  }));
  body.appendChild(fieldRow({
    field: 'bio',
    labelKey: 'profile.friendBioLabel',
    value: fresh.bio || '',
    max: BIO_MAX,
    placeholderKey: 'profile.friendBioPlaceholder',
    // 「本地缓存·未同步」= 该字段真源在上游、上游当前无接口（禁假装已同步）
    hintKey: 'profile.localCacheHint',
    onSave: (v) => api.setFriendBio(current.id, v),
  }));
  body.appendChild(el('p', 'pf-note', t('profile.friendNote')));

  return afterMount(ov);
}

/**
 * 打开群 Profile（**独立函数，供三点入口/群设置抽屉调用**）：群介绍 + 我的备注。
 * @param {any} conv 群会话行（内部形状，conversationId = groupId）
 * @returns {Promise<HTMLElement|null>}
 */
export async function openGroupProfile(conv) {
  if (!conv || !(conv.conversationId || conv.groupId)) return null;
  const groupId = String(conv.conversationId || conv.groupId);
  current = { kind: 'group', id: groupId, conv };
  const { ov, body } = renderBody();

  let fresh = conv;
  try {
    const env = await api.getGroups();
    fresh = (env.groups || []).find((g) => String(g.conversationId) === groupId) || conv;
    current.conv = fresh;
  } catch { /* 同上：读不到就用传入行 */ }

  body.appendChild(fieldRow({
    field: 'remark',
    labelKey: 'profile.groupRemarkLabel',
    value: fresh.remark || '',
    max: BIO_MAX,
    placeholderKey: 'profile.groupRemarkPlaceholder',
    onSave: (v) => api.setGroupRemark(groupId, v),
  }));
  body.appendChild(fieldRow({
    field: 'description',
    labelKey: 'profile.groupDescriptionLabel',
    value: fresh.description || '',
    max: BIO_MAX,
    placeholderKey: 'profile.groupDescriptionPlaceholder',
    hintKey: 'profile.localCacheHint',
    onSave: (v) => api.setGroupDescription(groupId, v),
  }));
  body.appendChild(el('p', 'pf-note', t('profile.groupNote')));

  return afterMount(ov);
}

/** 关闭 Profile 面（幂等）。 */
export function closeProfile() {
  if (overlay) {
    overlay.hidden = true;
    overlay.replaceChildren();
  }
  current = null;
}

/** 当前面是否开着（断言面）。 */
export function isProfileOpen() {
  return !!overlay && !overlay.hidden;
}
