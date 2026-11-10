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
// 🔴 「本地缓存」= 语义真源在上游、上游**尚无**该字段 ⇒ 本面在字段旁显式标注
// 跨设备收敛状态（profile-sync 徽标批 2026-11-06 作者令 3(a)）：徽标不再是写死的
// 「本地缓存·未同步」，改读 `GET /api/profile/sync-status`（`ProfileSync.status`
// 真数据源）三态 = 已同步 / 待同步 / 仅本地，开面 / 保存后 / `profileDescriptionsChanged`
// 帧三拍刷新。诚实优先：无外推成功证据绝不画「已同步」（判据在后端单点，
// 前端禁二次判读）。
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
import { h } from './dom.js';
import * as api from './friendsApi.js';
import { personLabel, conversationSnapshot } from './messages.js';
import { onMessage } from './ws.js';
import { avatarNodeFor } from './avatarRender.js';
import { bindImeGuard, isImeComposing } from './imeGuard.js';
import { mobileSheetBack, isMobileLayer } from './mobile/popSheet.js';
import { createToggle, setToggleState, setToggleBusy } from './toggle.js';
// 🔴 搜索面板（`js/chatSearch.js`）**不静态引**：它是全仓最重的一张面（chat / card
// registry / 媒体索引…），且本仓静态环闸要求 Profile 面不得新增静态边 ⇒ 点击时
// 动态 import（与 `mobile/navController.js` 开搜索页、`messages.js` 开本面同一条纪律）。
// 复用判据 = 调用的仍是**同一个** `openSearchModal()`（主对话搜索钮 / 手机搜索页
// 用的同一个导出函数），不是第二套实现。

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
    // 会话偏好（三点入口批 2026-11-05）：真值 = 好友行的 `muted` / `pinned` 键
    // （后端 `FriendService.applyFriendPrefs` 单一出口；关闭 = 键为 null）。
    if (field === 'muted') return hit.muted === true ? 'true' : 'false';
    if (field === 'pinned') return hit.pinned === true ? 'true' : 'false';
    return field === 'bio' ? (hit.bio || '') : (hit.remark || '');
  }
  const env = await api.getGroups();
  const g = (env.groups || []).find((x) => String(x.conversationId) === String(current.id));
  if (!g) return '';
  return field === 'description' ? (g.description || '') : (g.remark || '');
}

/**
 * 单字段编辑行（**扁平行**，三点入口批 2026-11-05 重排）：
 * head = label [+ 跨设备同步徽标]，行体 = 输入框 + 保存键（同一行，不套卡片盒）。
 * @param {{
 *   field: string, labelKey: string, value: string, max: number,
 *   placeholderKey: string, syncField?: string, onSave: (v: string) => Promise<any>,
 * }} spec `syncField` = ProfileSync 键类（`friendBio` / `groupDescription`）——
 *    在场 ⇒ head 挂三态徽标（`refreshSyncBadges` 按 `GET /api/profile/sync-status`
 *    真值回填；写死的 `hintKey` 静态文案已随 3(a) 令退役）。
 */
function fieldRow(spec) {
  const row = el('div', 'pf-field');
  row.dataset.pfField = spec.field;
  const head = el('div', 'pf-field-head');
  head.appendChild(el('span', 'pf-label', t(spec.labelKey)));
  if (spec.syncField) {
    const badge = el('span', 'pf-cache-hint pf-sync-badge', '');
    badge.dataset.pfSyncBadge = spec.field;
    badge.dataset.syncField = spec.syncField;
    badge.dataset.syncState = '';
    head.appendChild(badge);
  }
  row.appendChild(head);

  const line = el('div', 'pf-field-line');
  const input = document.createElement('input');
  input.className = 'cfg-input pf-input';
  input.type = 'text';
  input.maxLength = spec.max;
  input.value = spec.value || '';
  input.placeholder = t(spec.placeholderKey);
  input.autocomplete = 'off';
  input.dataset.pfInput = spec.field;
  bindImeGuard(input);

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
      // 3(a)：写后外推已在后端起跑 ⇒ 徽标真值变了，立即重读（同步态→待同步/已同步）。
      void refreshSyncBadges();
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
  line.appendChild(input);
  line.appendChild(save);
  row.appendChild(line);
  return row;
}

// ── 三态同步徽标（profile-sync 徽标批 2026-11-06 作者令 3(a)）────────────────
// 真数据源 = `GET /api/profile/sync-status`（`ProfileSync.status`，判据后端单点）；
// 本面只做「读 → 画」，禁二次判读。三态词汇：`synced`（已同步）/ `syncing`
// （待同步）/ `local-only`（仅本地）。消费字段 = 今天挂徽标的两行：好友 `bio`
// 与群介绍（`description`）；群备注/我的备注行不挂（面上没有这枚徽标，不是无状态）。
const SYNC_KIND_OF_FIELD = { bio: 'friendBio', description: 'groupDescription' };

/** wire 态 → 文案键尾（唯一拼写桥：`local-only` → `localOnly`）。 */
function syncStateKey(state) {
  return state === 'local-only' ? 'localOnly' : state;
}

/**
 * 徽标真值回填（幂等）：一次往返读全量状态，逐徽标按 kind 落三态。
 * 读失败 ⇒ 徽标退空档（`data-sync-state=""`，CSS 折叠）——「读不到」不冒充
 * 任何一态，下一拍刷新重画。竞态守卫 = seq（连拍时旧回包不覆新画）。
 */
let syncBadgeSeq = 0;
async function refreshSyncBadges() {
  if (!overlay || overlay.hidden) return;
  const badges = [...overlay.querySelectorAll('[data-pf-sync-badge]')];
  if (!badges.length) return;
  const seq = ++syncBadgeSeq;
  let fields = null;
  try {
    const st = await api.getProfileSyncStatus();
    fields = st && st.fields && typeof st.fields === 'object' ? st.fields : null;
  } catch { /* 读失败 = 空档（不猜） */ }
  if (seq !== syncBadgeSeq) return; // 已被更新的刷新取代
  for (const badge of badges) {
    if (!(badge instanceof HTMLElement)) continue;
    const kind = SYNC_KIND_OF_FIELD[badge.dataset.pfSyncBadge || ''] || '';
    const f = fields ? fields[kind] : null;
    const state = f && typeof f.state === 'string' ? f.state : '';
    badge.dataset.syncState = state;
    badge.textContent = state ? t(`profile.sync.${syncStateKey(state)}`) : '';
  }
}

// 三拍刷新之②③：远端收敛帧（描述被对端改写 ⇒ 状态变）+ 语言切换（就地改文案）。
// ①开面在 `afterMount`。模块级注册一次；面未开 ⇒ 幂等空转。
onMessage('profileDescriptionsChanged', () => { void refreshSyncBadges(); });
window.addEventListener('locale-changed', () => {
  if (!overlay || overlay.hidden) return;
  for (const badge of overlay.querySelectorAll('[data-pf-sync-badge]')) {
    if (!(badge instanceof HTMLElement)) continue;
    const state = badge.dataset.syncState || '';
    if (state) badge.textContent = t(`profile.sync.${syncStateKey(state)}`);
  }
});

/**
 * 导航行（扁平）：label（可带副行）+ 右箭头。行本体是 `<button>`（键盘可达）。
 * 本批唯一消费点 = 「搜索聊天记录」⇒ 复用主对话的同一张搜索面板。
 * @param {{ labelKey: string, subKey?: string, onClick: () => void, node?: string }} spec
 */
function navRow(spec) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'pf-nav';
  row.dataset.pfNav = spec.node || '';
  row.appendChild(el('span', 'pf-nav-label', t(spec.labelKey)));
  if (spec.subKey) row.appendChild(el('span', 'pf-nav-sub', t(spec.subKey)));
  const chev = h('span', { class: 'pf-nav-chev' }, h('i', { 'data-lucide': 'chevron-right' }));
  row.appendChild(chev);
  row.addEventListener('click', spec.onClick);
  return row;
}

/**
 * 开关行（扁平）：label + 共享开关组件 `nb-toggle`（`js/toggle.js`，禁自画）。
 *
 * 写序纪律（与 remark/bio 的回读口径同族）：点击即**乐观翻面**，随后写后端；
 * 后端是唯一真值 ⇒ 写成功后再回读一次好友行收敛（失败则回滚 + 可见提示，
 * 不留「界面说开了、其实没存」的假开关）。
 * @param {{
 *   field: 'muted'|'pinned', labelKey: string,
 *   on: boolean, onSave: (v: boolean) => Promise<any>,
 * }} spec
 */
function switchRow(spec) {
  const row = el('div', 'pf-switch');
  row.dataset.pfSwitch = spec.field;
  const label = el('span', 'pf-switch-label', t(spec.labelKey));
  label.id = `pf-switch-label-${spec.field}`;
  row.appendChild(label);
  const holder = el('span', 'pf-switch-ctl');
  // 共享开关组件的 **DOM 形态**（`toggle.js` 的 createToggle：与 toggleHTML 逐属性
  // 同形、同一属主模块）——禁本面自画开关，也禁裸 innerHTML（组件契约 R5）。
  const btn = createToggle({ on: spec.on, label: t(spec.labelKey) });
  holder.appendChild(btn);
  btn.dataset.pfToggle = spec.field;
  btn.setAttribute('aria-labelledby', label.id);
  btn.addEventListener('click', async () => {
    const next = !btn.classList.contains('on');
    setToggleState(btn, next); // 乐观：组件自有翻转（与 bindToggle 同一套视觉态）
    // 在途用 aria-busy（不确定态）而不是 disabled —— 禁用一个刚被键盘按下的开关会
    // 立刻丢焦点，用户「Space 之后再 Enter」就没反应（toggle-unify 批实测）。
    setToggleBusy(btn, true);
    try {
      await spec.onSave(next);
      // 回读收敛：以服务端真值为准（与 remark/bio 的「不做乐观写」同一条纪律）。
      const truth = await readFieldValue(spec.field);
      setToggleState(btn, truth === 'true');
      window.dispatchEvent(new CustomEvent(
        current && current.kind === 'friend' ? 'fm-friends-changed' : 'fm-groups-changed'
      ));
    } catch (err) {
      setToggleState(btn, !next); // 回滚：写失败绝不留在假态
      window.__showToast?.(t('messages.networkError'), 'error');
      console.debug('[profileFace] toggle failed', spec.field, err && err.message);
    } finally {
      setToggleBusy(btn, false);
    }
  });
  row.appendChild(holder);
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
  // 3(d) 按钮去重（panel-language 批 2026-11-06）：手机层返回键在（afterMount 的
  // mobileSheetBack 注入 .mfs-back）⇒ X 键**不建**（作者令逐字「已经有返回键了，
  // 就不要再放叉号键（X）」）；桌面层无返回键 ⇒ X 键保留（Esc / 点外关闭路径不变）。
  // renderBody 每次 open 重建 ⇒ 守卫逐次评估，视口跨档重开也对账。
  // 🔴 守卫与创建必须相邻（机检 P2 = scripts/check-panel-unify.mjs，锚行 3 行窗内
  //    必须见 isMobileLayer 守卫；判定单源 = popSheet.isMobileLayer，禁自写）。
  if (!isMobileLayer()) {
    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'pf-close';
    closeBtn.id = 'profile-face-close';
    closeBtn.setAttribute('aria-label', t('profile.close'));
    closeBtn.appendChild(h('i', { 'data-lucide': 'x' }));
    closeBtn.addEventListener('click', () => closeProfile());
    titleBar.appendChild(closeBtn);
  }
  modal.appendChild(titleBar);

  // 英雄区（三点入口批 2026-11-05 扁平化）：居中列 → **左对齐单行**（头像 + 名字/
  // 副行），头像档位从无规则的 `fm-avatar-56`（尺寸随源图漂）改为既有令牌档
  // `fm-avatar-40`（40×40，friends.css 既有规则，零新尺寸字面量）。
  const hero = el('div', 'pf-hero');
  if (current?.kind === 'group') {
    hero.appendChild(avatarNode({ name: current.conv?.title || '' }, 40));
    const idText = el('div', 'pf-hero-id');
    idText.appendChild(el('div', 'pf-hero-name', subjectTitle()));
    idText.appendChild(el('div', 'pf-hero-sub', t('messages.memberCount', { n: Number(current.conv?.memberCount) || 0 })));
    hero.appendChild(idText);
  } else {
    const f = current?.conv || {};
    hero.appendChild(avatarNode({ name: f.name, neblinkId: f.neblinkId, avatarUrl: f.avatarUrl }, 40));
    const idText = el('div', 'pf-hero-id');
    idText.appendChild(el('div', 'pf-hero-name', subjectTitle()));
    idText.appendChild(el('div', 'pf-hero-sub', f.neblinkId || ''));
    hero.appendChild(idText);
  }
  modal.appendChild(hero);

  const body = el('div', 'pf-fields');
  modal.appendChild(body);
  ov.replaceChildren(modal);
  return { ov, body };
}

/** 面开好之后的共同收尾（手机层返回钮 + 图标 + 三态徽标首拍回填）。 */
function afterMount(ov) {
  ov.hidden = false;
  if (document.body.classList.contains('mobile-layer')) mobileSheetBack(ov, () => closeProfile());
  if (typeof lucide !== 'undefined') createIconsIn(ov);
  void refreshSyncBadges(); // 3(a)：开面即读真值（三拍刷新之一）
  return ov;
}

/**
 * 当前主体的**会话身份**（3(b) 会话内独立搜索批 2026-11-06）：群 = groupId 本身
 * 就是 conversationId；好友 = 会话快照按 userId 解析（`conversationSnapshot` 与
 * 消息列表同一份 `conversations`）。解析不到（好友会话尚未建立的边界态）⇒ null
 * ——调用方退回全局搜索（与改前同行为），不猜。
 * @returns {{convId: string, convName: string}|null}
 */
function convScopeForCurrent() {
  if (!current) return null;
  if (current.kind === 'group') {
    return { convId: String(current.id), convName: subjectTitle() };
  }
  const uid = String(current.id);
  const hit = conversationSnapshot().find(
    (c) => c && c.friend && String(c.friend.userId) === uid
  );
  if (!hit || !hit.conversationId) return null;
  return { convId: String(hit.conversationId), convName: personLabel(current.conv) || '' };
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
    // 三态同步徽标（3(a)）：真源 = GET /api/profile/sync-status 的 friendBio 档，
    // 已同步/待同步/仅本地随真值变化（写死的「本地缓存·未同步」已退役）。
    syncField: 'friendBio',
    onSave: (v) => api.setFriendBio(current.id, v),
  }));

  // ── 三点入口批（2026-11-05，作者原话）：「里面的内容除了目前的设置备注和描述，
  //    还要有搜索聊天记录……然后就是消息免打扰的 toggle，然后就是顶置的 toggle」
  //    ⇒ 顺序 = 备注 / 描述 / 搜索聊天记录 / 消息免打扰 / 顶置。
  //
  //    ① 搜索聊天记录 = **复用**主对话那张搜索面板：调的是 `chatSearch.openSearchModal`
  //       同一个导出函数（手机搜索页 / 顶栏搜索钮同一条链），零第二套实现。
  //       开前先收本面（面板是页级浮层，两面叠着会抢焦点/滚动容器）。
  //       3(b) 会话内独立搜索批（2026-11-06 作者令）：会话窗内搜索**只搜当前会话**
  //       （scope 隔离，不串全局）⇒ 开面板时带上本主体的会话身份（convScope）。
  body.appendChild(navRow({
    labelKey: 'profile.searchMessages',
    node: 'search',
    onClick: () => {
      const scope = convScopeForCurrent();
      closeProfile();
      // 手机层走搜索**页**（C5 实证批 2026-11-06）：body 级 overlay 在页面世界是
      // pointer-events:none 的状态载体（mobileLayer.css 遮罩/指针全退族），直开 =
      // 面可见但点不可交互 ⇒ stageConvScope + pushPage('search')（overlay 收编进
      // 页体后可交互；页栈顺序 = 搜索页盖会话页；scope 由页面激活的
      // openSearchModal() 无参一次性消费）。桌面 = 既有直开路径零变化。
      if (isMobileLayer()) {
        void import('./chatSearch.js')
          .then((m) => { m.stageConvScope(scope); return import('./mobile/navController.js'); })
          .then((nav) => { nav.pushPage('search'); })
          .catch((e) => console.debug('[profileFace] open search failed', e && e.message));
        return;
      }
      void import('./chatSearch.js')
        .then((m) => m.openSearchModal(scope || undefined))
        .catch((e) => console.debug('[profileFace] open search failed', e && e.message));
    },
  }));

  //    ② ③ 两个本地偏好（后端 = `profile-descriptions.json` 的 friendMuted/friendPinned，
  //      真值经 `GET /api/friends` 的 `muted`/`pinned` 键回读 ⇒ 关掉重进仍在）。
  body.appendChild(switchRow({
    field: 'muted',
    labelKey: 'profile.muteLabel',
    on: !!fresh.muted,
    onSave: (v) => api.setFriendMuted(current.id, v),
  }));
  body.appendChild(switchRow({
    field: 'pinned',
    labelKey: 'profile.pinLabel',
    on: !!fresh.pinned,
    onSave: (v) => api.setFriendPinned(current.id, v),
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
    // 三态同步徽标（3(a)）：真源 = GET /api/profile/sync-status 的
    // groupDescription 档（同好友 bio 一套实现）。
    syncField: 'groupDescription',
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
