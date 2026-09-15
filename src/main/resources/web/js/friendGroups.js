// friendGroups.js — 群组一期（friendgroups 客户端腿）群域唯一属主：
// 群可用性（fail-closed）/ 群取数归一 / 建群对话框 / 群设置抽屉（成员/邀请/
// 改名/踢人/退群/解散）。会话列表合并与聊天窗渲染归 messages.js（本模块不碰
// 渲染管线）；「新的朋友」式群邀请入口归 contacts.js（本模块只供数据与对话框）。
//
// 契约来源（冻结，逐条给锚点）：
//  · 主卡案1② 接口清单（主卡:249）：POST /api/groups、GET /api/groups、
//    GET /api/groups/{id}/members、POST /api/groups/{id}/invites(+accept|decline)、
//    POST /api/groups/{id}/leave、POST /api/groups/{id}/members/{userId}/kick、
//    PUT /api/groups/{id}/title、DELETE /api/groups/{id}、
//    POST /api/groups/{id}/messages（请求/响应/校验序 = 补充卡 §5.1 逐字）。
//  · 九项裁定：成员上限 50（O①）/ 邀请需对方确认（O②/A-4）/ 退群后历史不可见
//    （O③）/ 群头像首字母占位（O④，**现行有效**）/ owner 禁退群只能解散（O⑨，
//    **现行有效**）/ 拉黑只断单聊、同群照常（O⑩）。
//  · 权限矩阵（🔴 **O⑧ 已被取代**：作者 2026-09-16 06:23 决策卡推翻「admin 只做
//    标识不赋权」；06:31 双卡追加「admin 亦可改名」+ 显式授权任命/撤销管理员路由）。
//    终版（正典 §B.1）= 邀请 owner✅/admin✅/member❌ · 踢人 owner✅（不可踢自己）/
//    admin✅（**仅普通成员**）· member❌ · 改名 owner✅/admin✅/member❌ · 解散
//    **owner 专属** · 退群 admin✅/member✅/owner❌ · 设撤管理员 **owner 专属**。
//    🔴 客户端只做 UX 入口闸，**权威闸在服务端**（禁复制第二套判定）。
//  · fail-closed（主卡 G-2 :204-206 + js/friendsApi.js:77-82 errKind）：旧网关/
//    旧服务端无群路由 ⇒ 404 ⇒ neblinkOff ⇒ 群入口隐藏 + 一次性可见提示
//    （🔴 禁把「群不可用」写成静默无反应）。
//  · 附件帧（分发器 2026-09-15 11:32 A③ 翻案）：agent 群消息可带附件引用；
//    渲染面 = fillBubble 附件卡 + 「由 Agent 发」徽章同帧共存（messages.js
//    既有管线结构性支持，本模块零参与）。

import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { getNeblinkState } from './neblink.js';
import * as api from './friendsApi.js';
import { errKind } from './friendsApi.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：新增输入面必须同批接入 imeGuard，
// 禁手写组字判定（scripts/check-ime-guard.mjs 机械哨兵）。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

// ── 九项裁定常量（唯一落点）────────────────────────────────
// 成员上限 50（O①，root 裁定 ④「成员上限 50」）：权威闸在服务端；客户端只在
// 建群/邀请入口做 UX 预检（超限禁提交 + 可读提示），不复制第二套判定。
const GROUP_MEMBER_CAP = 50;
// 群名长度上限：对齐好友备注 REMARK_MAX=64 的既有口径（显示名不触文件系统）。
const GROUP_TITLE_MAX = 64;

// ── 可用性（fail-closed，主卡 G-2）─────────────────────────
// true = 群路由在（或尚未探测）；false = 网关/服务端 404 ⇒ 群入口隐藏。
// 翻转时广播一次 'fm-groups-changed'（仅翻转那一次，禁循环）。
let groupsOn = true;
let unavailableToasted = false;
// 入站群邀请的 keep-last-known 副本（取数失败 ≠ 空集，与好友域「失败≠空」同口径）。
// 邀请真源 = 契约端点 GET /api/groups/invites（`{incoming:[…]}`）；该腿单独失败
// 时保留上一拍读数，且**不**参与可用性判定（可用性判据仍只看群列表腿）。
let lastInvites = [];

/** 群路由可用性（contacts 面用于隐藏「发起群聊」入口；初值乐观 true）。 */
export function groupsAvailable() {
  return groupsOn;
}

function toastGlobal(text, kind) {
  window.__showToast?.(text, kind || 'info');
}

/** 群操作失败的统一 toast 分态：语义码优先（补充卡 §5.3：404 group_not_found /
 *  403 group_disbanded / 403 not_member 是**群终态**，与「登录失效」分态——
 *  403 身上带群语义码时不按 auth 折叠）；无语义码的 401/403 由 req() 的
 *  fm-auth-required 全局链覆盖（friendsApi.js:59-61），本函数跳过；404 无语义码
 *  = 群路由缺失（neblinkOff，fail-closed 面）；其余走 networkError 文案。
 *  返回 true = 已就地提示（调用方无须再弹）。 */
export function groupErrToast(err) {
  const code = err && err.data && (err.data.error || err.data.code);
  if (code === 'group_not_found') { toastGlobal(t('messages.groupNotFound'), 'error'); return true; }
  if (code === 'group_disbanded') { toastGlobal(t('messages.groupDisbanded'), 'error'); return true; }
  if (code === 'not_member') { toastGlobal(t('messages.groupKicked'), 'error'); return true; }
  // 越权专属码（正典 §B.4.1）：**必须可见**、且与 auth 403 分态（req() 已不派
  // 登录链，见 friendsApi.js 同批注释）。群内越权一律走本分支。
  if (code === 'not_group_admin') { toastGlobal(t('messages.groupNotAdmin'), 'error'); return true; }
  // 目标已不是成员 / 角色取值非法 / 群满：逐码可见分态（禁静默、禁报成成功）。
  if (code === 'member_not_found') { toastGlobal(t('messages.groupMemberNotFound'), 'error'); return true; }
  if (code === 'invalid_role') { toastGlobal(t('messages.groupRoleInvalid'), 'error'); return true; }
  if (code === 'owner_cannot_leave') { toastGlobal(t('messages.groupOwnerNoLeave'), 'error'); return true; }
  if (code === 'group_full') { toastGlobal(t('contacts.createGroupCap'), 'error'); return true; }
  if (!err || err.status === undefined) return false; // 网络错：fm-network-error 已覆盖
  const kind = errKind(err);
  if (kind === 'auth') return true; // 登录引导已由全局链弹出
  if (kind === 'neblinkOff') {
    toastGlobal(t('messages.groupsUnavailable'), 'error');
    return true;
  }
  toastGlobal(t('messages.networkError'), 'error');
  return true;
}

function markAvailability(next) {
  if (groupsOn === next) return;
  groupsOn = next;
  if (!next && !unavailableToasted) {
    unavailableToasted = true;
    toastGlobal(t('messages.groupsUnavailable'));
  }
  window.dispatchEvent(new CustomEvent('fm-groups-changed'));
}

// ── 取数归一（群域唯一取数口）─────────────────────────────
/** 群标题回落：服务端 title 缺席/空白 ⇒ i18n 占位（禁渲染空串/undefined）。 */
export function groupTitleOf(conv) {
  const title = conv && typeof conv.title === 'string' ? conv.title.trim() : '';
  return title || t('messages.groupUntitled');
}

/**
 * 群域唯一取数口（messages.js 的 refreshConversations 与 contacts.js 的群邀请区
 * 共用，禁第二份调用点各写归一）：**两条契约腿**并行——
 *  · GET /api/groups → 裸数组 `[GroupSummary]`（行键 `groupId`）→ 会话行形状；
 *  · GET /api/groups/invites → `{incoming:[GroupInviteEntry]}`（**邀请发现真源**；
 *    此前从群列表响应里读加性 `pendingInvites` 的假设已废除——承载件无此键）。
 *
 * 返回值三态：
 *  · {groups, pendingInvites, selfUserId} — 成功（groups 已按会话行形状归一；
 *    selfUserId = 加性契约的 viewer 身份，行内面优先、邀请信封面兜底、都无 ⇒ ''）；
 *  · {groups: [], pendingInvites: [], selfUserId: ''} — 群路由 404（fail-closed：调用方按空集
 *    处理 = 群行从会话列表消失，可用性面已翻 false + 一次性提示）；
 *  · null — 鉴权/网络/5xx（keep-last-known，与好友域 refresh 同口径）。
 *
 * 邀请腿**单独失败可容**（catch ⇒ null ⇒ lastInvites 不变）：可用性判据只认群列表
 * 腿，否则一条加性端点的 404 会把整个群入口误判为「网关/服务端无群路由」。
 * @returns {Promise<{groups: any[], pendingInvites: any[], selfUserId: string}|null>}
 */
export async function refreshGroups() {
  try {
    const [env, invites] = await Promise.all([
      api.getGroups(),
      api.getGroupInvites().catch(() => null),
    ]);
    markAvailability(true);
    const groups = (env.groups || []).map(normalizeRow).filter(Boolean);
    if (invites) lastInvites = invites;
    // viewer 身份（加性契约字段）：优先取群行**行内** `selfUserId`，行面读不到
    // （零群退化）时取邀请**信封** `selfUserId`（§1.1 #2/#3）。两处都缺席 ⇒ ''
    // ⇒ 消费方回落 send-correlation 自证（禁造值）。
    const selfId = (groups.find(g => g && g.selfUserId) || {}).selfUserId
      || (invites && invites.selfUserId) || '';
    return { groups, pendingInvites: lastInvites, selfUserId: selfId };
  } catch (err) {
    if (errKind(err) === 'neblinkOff') {
      markAvailability(false);
      lastInvites = [];
      return { groups: [], pendingInvites: [], selfUserId: '' };
    }
    return null; // auth（全局链已提示）/ 网络 / 5xx ⇒ keep-last-known
  }
}

function normalizeRow(row) {
  // 与 friendsApi.normalizeGroupRow 同一消费集；此处不再二次归一（getGroups
  // 出口已归一），只过滤 null 行。
  return row && row.conversationId ? row : null;
}

// ── 本地 DOM 小件（contacts.js 同款 file-local 先例：el/avatarEl 各文件一份）─
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function avatarEl(person, size) {
  const a = el('span', `fm-avatar fm-avatar-${size}`);
  if (person && person.avatarUrl) {
    const img = document.createElement('img');
    img.src = person.avatarUrl;
    img.alt = '';
    a.appendChild(img);
  } else {
    // O④：群头像 = 标题首字母占位（无自定义群头像）；成员头像同既有首字母兜底。
    a.textContent = ((person && (person.name || person.neblinkId)) || '?').trim().charAt(0).toUpperCase();
  }
  a.setAttribute('aria-hidden', 'true');
  return a;
}

/** 危险操作确认（window.__showConfirm 既有先例，contacts.js:423 同款兜底）。 */
function confirmRun(title, text, run) {
  if (typeof window.__showConfirm === 'function') window.__showConfirm(title, text, run);
  else run();
}

// ── 群成员头像九宫格（批 1 · 正典 §A 字段 + 方案 §3.2.1 几何）───────────────
/** 行模式表（微信式；方案 §3.2.1，逐格已渲染验证）：格子数 n ⇒ 每行格数。
 *  n=1 [1] · 2 [2] · 3 [1,2] · 4 [2,2] · 5 [2,3] · 6 [3,3] · 7 [1,3,3] · 8 [2,3,3] · 9 [3,3,3] */
const AVATAR_GRID_ROWS = [
  [1], [2], [1, 2], [2, 2], [2, 3], [3, 3], [1, 3, 3], [2, 3, 3], [3, 3, 3],
];

/** 格内首字母兜底（**逐格**，复用既有口径：无 avatarUrl 就取名字首字符大写）。
 *  显示名优先、其次 `userId`（会话列表行只有 userId ⇒ 退化为 userId 首字符）。 */
function gridCellInitial(cell) {
  const src = (cell && (cell.name || cell.userId)) || '?';
  return String(src).trim().charAt(0).toUpperCase() || '?';
}

/**
 * 群成员头像九宫格（**唯一实现**；三处落点共用：会话列表群行 / 群会话窗头 /
 * 群信息面板头部）。样式全部落在 `friends.css` 的 `.fm-avgrid*`（零内联色值、
 * 零内联像素算术）。
 *
 * 几何（**G-A 方形圆角**，方案 §3.2.1 / 决策卡 A）：
 *   · 容器 = `size × size` 方形、`border-radius:5px`、格间缝 `1px`、容器底
 *     `var(--color-surface)`；格内 `border-radius:1px`
 *   · **行高 = 容器高 ÷ 行数**、**某行格宽 = 容器宽 ÷ 该行格数**（两条规则即定义
 *     全部 9 种模式 ⇒ flex 等高行 + 等分格，禁内联算像素）
 *   · 每格 `img { object-fit: cover }`（复用既有 `friends.css` 口径）
 *
 * 降级（方案 §3.2.3）：
 *   · `cells` 为空 ⇒ **返回 null**（调用方回退现状那枚「标题首字母」头像 = 降级态，
 *     非被删态；n=0 / 名册未到 / 取数失败三态同形）
 *   · 1..9 ⇒ 铺满；逐格无 `avatarUrl` ⇒ 该格首字母兜底（**逐格**，非整图回退）
 *   · `total > 9` ⇒ 右下角 `+N` 角标（N = total − 9）
 *
 * @param {Array<{userId?: string, avatarUrl?: string, name?: string}>} cells
 *        已归一格子（**禁**传 wire 行；只消费 userId/avatarUrl/name 三个内部键）
 * @param {number} size 容器边长（px；本批落点档 = 40）
 * @param {number} [total] 成员总数（>9 时出角标；缺省 = cells.length）
 * @returns {HTMLElement|null}
 */
export function groupAvatarGrid(cells, size, total) {
  const list = Array.isArray(cells) ? cells.filter(Boolean).slice(0, 9) : [];
  if (list.length === 0) return null;
  const grid = el('div', `fm-avgrid fm-avgrid-${size}`);
  const pattern = AVATAR_GRID_ROWS[list.length - 1];
  let i = 0;
  for (const count of pattern) {
    const row = el('div', 'fm-avgrid-row');
    for (let k = 0; k < count && i < list.length; k += 1, i += 1) {
      const cell = list[i];
      const c = el('span', 'fm-avgrid-cell');
      if (cell.avatarUrl) {
        const img = document.createElement('img');
        img.src = cell.avatarUrl;
        img.alt = '';
        c.appendChild(img);
      } else {
        c.textContent = gridCellInitial(cell);
      }
      row.appendChild(c);
    }
    grid.appendChild(row);
  }
  const n = Number(total) || list.length;
  if (n > 9) grid.appendChild(el('span', 'fm-avgrid-more', `+${n - 9}`));
  // 装饰性组合（群名已是同义的可见文本）⇒ 与既有 avatarEl 同口径 aria-hidden。
  grid.setAttribute('aria-hidden', 'true');
  return grid;
}

// ── 好友多选器（建群 / owner 邀请 共用一份，禁两份选择面）───────────────
// 拉黑好友不进候选（O⑩ 拉黑只断单聊的保守面：不主动把拉黑对象拉进群；已在同
// 群的拉黑对象照常可见 —— 那是服务端成员闸 + 渲染面的事，与本选择器无关）。
/** @param {{excludeIds?: Set<string>, selected?: Set<string>}} [opts] */
function buildFriendPicker(opts) {
  const exclude = opts && opts.excludeIds;
  const selected = (opts && opts.selected) || new Set();
  const wrap = el('div', 'fm-pick-list');
  wrap.setAttribute('role', 'listbox');
  wrap.setAttribute('aria-multiselectable', 'true');
  api.getFriends().then((data) => {
    wrap.innerHTML = '';
    const list = (data && data.friends || []).filter(f => f && f.userId && !f.blocked
      && !(exclude && exclude.has(String(f.userId))));
    if (list.length === 0) {
      wrap.appendChild(el('div', 'fm-empty', t('contacts.empty')));
      return;
    }
    for (const f of list) {
      const row = el('div', 'fm-pick-row' + (selected.has(String(f.userId)) ? ' on' : ''));
      row.setAttribute('role', 'option');
      row.setAttribute('aria-selected', String(selected.has(String(f.userId))));
      row.dataset.userId = String(f.userId);
      row.appendChild(avatarEl(f, 36));
      const meta = el('div', 'fm-row-meta');
      meta.appendChild(el('div', 'fm-row-name', f.remark || f.name || f.neblinkId));
      meta.appendChild(el('div', 'fm-row-sub', f.neblinkId || ''));
      row.appendChild(meta);
      const tick = el('span', 'fm-pick-tick');
      tick.innerHTML = '<i data-lucide="check"></i>';
      row.appendChild(tick);
      row.addEventListener('click', () => {
        const id = String(f.userId);
        if (selected.has(id)) selected.delete(id); else selected.add(id);
        row.classList.toggle('on', selected.has(id));
        row.setAttribute('aria-selected', String(selected.has(id)));
      });
      wrap.appendChild(row);
    }
    createIconsIn(wrap);
  }).catch(() => {
    wrap.innerHTML = '';
    wrap.appendChild(el('div', 'fm-empty', t('contacts.listError')));
  });
  return wrap;
}

// ── 建群对话框（contacts 面入口；成功后经 fm-groups-changed 交 messages 开窗）─
/** 发起群聊：选好友（可多选）+ 群名 → POST /api/groups `{title}`（**契约只有
 *  title 一个字段**：GroupCreateBody，model.rs:828-830）——建群成功后对选中好友
 *  **逐个 POST /api/groups/{id}/invites `{userId}`**（契约里成员只能经
 *  invite+accept 入群，groups.rs:229-297；承载件无 memberIds ⇒ 发 memberIds 会被
 *  服务端静默丢弃，等于选了白选）。
 *  标题**必填**（trim 后非空）：服务端 valid_group_title 空串 ⇒ 422 invalid_title
 *  （groups.rs:96-106,161-162），客户端做同判据 UX 预检（权威闸仍在服务端）。
 *  成员上限 50 同样做 UX 预检（含本机 1 人）；权威闸在服务端（超限 422/403 走
 *  groupErrToast）。 */
export function openCreateGroupDialog() {
  if (!getNeblinkState().loggedIn) return;
  const selected = new Set();
  const overlay = el('div', 'cfg-modal-overlay');
  overlay.id = 'fm-group-create-overlay';
  const modal = el('div', 'cfg-modal fm-modal fm-pick-modal');
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-label', t('contacts.createGroupTitle'));

  const header = el('div', 'fm-modal-header');
  const title = el('div', 'fm-modal-title');
  title.appendChild(el('span', 'fm-modal-name', t('contacts.createGroupTitle')));
  header.appendChild(title);
  const closeBtn = el('span', 'fm-modal-close');
  closeBtn.textContent = '×';
  closeBtn.setAttribute('role', 'button');
  closeBtn.setAttribute('tabindex', '0');
  const close = () => overlay.remove();
  closeBtn.addEventListener('click', close);
  closeBtn.addEventListener('keydown', (e) => { if (e.key === 'Enter') close(); });
  header.appendChild(closeBtn);
  modal.appendChild(header);

  // 群名（**必填** —— 契约 GroupCreateBody.title 无缺省；O④ 同族最小面；
  // imeGuard 接入 = 新增输入面纪律）
  const nameRow = el('div', 'fm-gs-rename');
  const nameInput = document.createElement('input');
  nameInput.className = 'cfg-input fm-gs-rename-input';
  nameInput.type = 'text';
  nameInput.maxLength = GROUP_TITLE_MAX;
  nameInput.placeholder = t('contacts.createGroupName');
  nameInput.autocomplete = 'off';
  bindImeGuard(nameInput);
  nameInput.addEventListener('keydown', (e) => { if (isImeComposing(e, nameInput)) e.stopPropagation(); });
  nameRow.appendChild(nameInput);
  modal.appendChild(nameRow);

  const counter = el('div', 'fm-pick-count', t('contacts.createGroupSelected', { n: 0 }));
  modal.appendChild(counter);
  const picker = buildFriendPicker({ selected });
  picker.addEventListener('click', () => {
    // 点击后同步计数（选择集在本闭包内，读 size 即可）
    counter.textContent = t('contacts.createGroupSelected', { n: selected.size });
  });
  modal.appendChild(picker);

  const foot = el('div', 'fm-pick-foot');
  const createBtn = el('button', 'glass-control fm-msg-btn', t('contacts.createGroupSubmit'));
  createBtn.addEventListener('click', async () => {
    if (createBtn.disabled) return;
    // 群名必填（契约 GroupCreateBody.title 无缺省；空串 ⇒ 422 invalid_title）。
    const groupName = nameInput.value.trim();
    if (!groupName) {
      toastGlobal(t('contacts.createGroupNameRequired'), 'error');
      return;
    }
    // 成员上限 50（含本机）UX 预检；权威闸在服务端。
    if (selected.size + 1 > GROUP_MEMBER_CAP) {
      toastGlobal(t('contacts.createGroupCap'), 'error');
      return;
    }
    createBtn.disabled = true;
    try {
      // ① 建群（契约：`{title}` 单字段 —— 不带 memberIds）
      const conv = await api.createGroup(groupName);
      // ② 选中成员逐个发邀请（契约的成员入口只有 invite+accept）
      let failed = 0;
      for (const uid of [...selected]) {
        try { await api.inviteToGroup(conv && conv.conversationId, uid); } catch { failed++; }
      }
      overlay.remove();
      window.dispatchEvent(new CustomEvent('fm-groups-changed', {
        detail: { openConversationId: conv && conv.conversationId },
      }));
      if (failed > 0) toastGlobal(t('messages.groupInviteFailed', { n: failed }), 'error');
      else toastGlobal(t('messages.groupCreated'));
    } catch (err) {
      createBtn.disabled = false;
      groupErrToast(err);
    }
  });
  foot.appendChild(createBtn);
  modal.appendChild(foot);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.appendChild(modal);
  document.body.appendChild(overlay);
  createIconsIn(modal);
}

// ── 群设置抽屉（聊天窗内；renderChatModal 的群分支挂载）─────────────────
/**
 * 构建群设置抽屉内容（群信息头 + 群名 + 添加成员 + 成员列表 + 角色/权限动作）。
 *
 * 权限矩阵（**正典 §B.1 终版**；O⑧ 已被作者 2026-09-16 06:23 决策卡取代、
 * 06:31 双卡更正改名为 admin ✅）：
 *  · owner = 改名 / 邀请 / 踢人（全权，不可踢自己）/ 解散（**专属**）/ 设撤管理员（**专属**）
 *  · admin = 改名 / 邀请 / 踢人（**仅普通成员**：不可踢 owner、不可踢其他 admin、不可踢自己）
 *  · member = 退群（并可见/可用 = 无管理动作）
 *  · 退群：owner ❌（`owner_cannot_leave`，只给解散）；admin / member ✅
 *  🔴 本函数只做 **UX 入口闸**（不出现 = 不撞墙）；**权威闸在服务端**
 *  （禁复制第二套判定、禁据本地猜测放宽权限闸）。
 * @param {any} conv 群会话行（kind==='group'）
 * @param {{toast: (s: string) => void, close: () => void, onChanged: () => void}} hooks
 * @returns {HTMLElement}
 */
export function buildGroupSettings(conv, hooks) {
  const root = el('div', 'fm-group-settings');
  const toast = (s) => hooks.toast(s);
  const changed = () => hooks.onChanged();
  const isOwner = conv.myRole === 'owner';
  const isAdmin = conv.myRole === 'admin';
  const canRename = isOwner || isAdmin; // 与 canInvite 同判据（正典 §B.1 两行同值）
  const canInvite = isOwner || isAdmin;
  /** 踢人 UX 闸（逐目标）：owner 全权（对 owner 行不挂 = 不可踢自己）；admin 仅普通成员。 */
  const canKick = (mem) => (isOwner ? mem.role !== 'owner' : (isAdmin ? mem.role === 'member' : false));

  // ── 群信息头（组合头像 + 群名 + 成员数；方案 §3.1 P1 + H2 同族面）
  // 头像 = 成员头像九宫格（**唯一实现** groupAvatarGrid）；名册未到 ⇒ null ⇒ 回退
  // 现状那枚标题首字母头像（降级态）。成员数据面 = 下方同一次 getGroupMembers 拉取。
  const headSec = el('div', 'fm-gs-head');
  const headAvatar = el('div', 'fm-gs-head-avatar');
  headSec.appendChild(headAvatar);
  const headMeta = el('div', 'fm-gs-head-meta');
  headMeta.appendChild(el('div', 'fm-gs-head-name', groupTitleOf(conv)));
  const headCount = el('div', 'fm-gs-head-sub', conv.memberCount > 0
    ? t('messages.memberCount', { n: conv.memberCount }) : '');
  headMeta.appendChild(headCount);
  headSec.appendChild(headMeta);
  root.appendChild(headSec);

  /** 群信息头头像重打：九宫格可用 ⇒ 组合头像；不可用 ⇒ 标题首字母（现状形态）。 */
  function paintHeadAvatar(cells) {
    headAvatar.innerHTML = '';
    const grid = groupAvatarGrid(cells, 40, conv.memberCount);
    headAvatar.appendChild(grid || avatarEl({ name: groupTitleOf(conv) }, 40));
  }
  paintHeadAvatar([]);

  // ── 群名（owner / admin 可改；行内编辑，imeGuard 接入）
  const titleSec = el('div', 'fm-gs-section');
  titleSec.appendChild(el('div', 'fm-gs-title', t('messages.groupTitleLabel')));
  const titleRow = el('div', 'fm-gs-rename');
  const titleInput = document.createElement('input');
  titleInput.className = 'cfg-input fm-gs-rename-input';
  titleInput.type = 'text';
  titleInput.maxLength = GROUP_TITLE_MAX;
  titleInput.value = groupTitleOf(conv);
  titleInput.placeholder = t('contacts.createGroupName');
  titleInput.autocomplete = 'off';
  titleInput.disabled = !canRename;
  bindImeGuard(titleInput);
  titleInput.addEventListener('keydown', (e) => {
    if (isImeComposing(e, titleInput)) return;
    if (e.key === 'Enter') { e.preventDefault(); saveTitle(); }
  });
  titleRow.appendChild(titleInput);
  if (canRename) {
    const saveBtn = el('button', 'glass-control fm-gs-save', t('messages.groupTitleSave'));
    saveBtn.addEventListener('click', saveTitle);
    titleRow.appendChild(saveBtn);
  }
  titleSec.appendChild(titleRow);
  root.appendChild(titleSec);

  async function saveTitle() {
    const next = titleInput.value.trim();
    if (!next || next === (conv.title || '').trim()) return;
    try {
      await api.renameGroup(conv.conversationId, next);
      conv.title = next;
      toast(t('messages.groupRenamed'));
      window.dispatchEvent(new CustomEvent('fm-groups-changed'));
      changed();
    } catch (err) { groupErrToast(err); }
  }

  // ── 添加成员（**移到成员列表之前**，决策卡 C/D/G/H；owner/admin 可用）
  // 入口文案 = 「添加成员」（语义 = 动作本身；候选源仍是好友，复用 buildFriendPicker
  // 那一份，禁第二套选择面）。上限 50 只做 UX 预检（权威闸在服务端）。
  if (canInvite) {
    const inviteSec = el('div', 'fm-gs-section');
    const inviteBtn = el('button', 'glass-control fm-gs-action fm-gs-addmember', t('messages.groupAddMember'));
    let pickerHost = null;
    const invitedIds = new Set();
    inviteBtn.addEventListener('click', () => {
      if (pickerHost) { pickerHost.remove(); pickerHost = null; return; }
      if ((conv.memberCount || 0) >= GROUP_MEMBER_CAP) {
        toast(t('contacts.createGroupCap'));
        return;
      }
      pickerHost = el('div', 'fm-gs-picker');
      // 选择集由本闭包持有（buildFriendPicker 直接读写同一 Set ⇒ 无 DOM 反查）。
      const picked = new Set();
      const picker = buildFriendPicker({
        excludeIds: invitedIds,
        selected: picked,
      });
      // ③ 发送键族统一批：本键是**发送键**，加 `fm-send-chip` 专指发送语义
      // （`.fm-msg-btn` 是共用类名 —— 好友面板「发消息」键 `contacts.js:744` 同用，
      // 那是动作键不是发送键，不得同染）；`cfg-btn-primary` 只为承接既有主操作墨色。
      // 🔴 只动按钮本体与状态色，群聊布局/结构零改动。
      const sendBtn = el('button', 'glass-control cfg-btn-primary fm-msg-btn fm-send-chip', t('messages.groupInviteSend'));
      sendBtn.addEventListener('click', async () => {
        if (sendBtn.disabled) return;
        const ids = [...picked];
        if (ids.length === 0) return;
        if ((conv.memberCount || 0) + ids.length > GROUP_MEMBER_CAP) {
          toast(t('contacts.createGroupCap'));
          return;
        }
        sendBtn.disabled = true;
        let failed = 0;
        for (const uid of ids) {
          try {
            await api.inviteToGroup(conv.conversationId, uid);
            invitedIds.add(String(uid));
          } catch { failed++; }
        }
        sendBtn.disabled = false;
        pickerHost.remove();
        pickerHost = null;
        if (failed > 0) toastGlobal(t('messages.groupInviteFailed', { n: failed }), 'error');
        else toast(t('messages.groupInvitedPending'));
      });
      pickerHost.appendChild(picker);
      pickerHost.appendChild(sendBtn);
      inviteSec.appendChild(pickerHost);
      createIconsIn(pickerHost);
    });
    inviteSec.appendChild(inviteBtn);
    root.appendChild(inviteSec);
  }

  // ── 成员列表（显示名而非好友备注，主卡 H 节口径）
  const memberSec = el('div', 'fm-gs-section');
  const memberHead = el('div', 'fm-gs-title-row');
  memberHead.appendChild(el('div', 'fm-gs-title', t('messages.groupMembers')));
  memberSec.appendChild(memberHead);
  const memberList = el('div', 'fm-member-list');
  memberList.appendChild(el('div', 'fm-empty', t('messages.loading')));
  memberSec.appendChild(memberList);
  root.appendChild(memberSec);

  // ── 退群 / 解散（互斥：owner 禁退群只能解散，O⑨ **现行有效**）
  const actSec = el('div', 'fm-gs-section fm-gs-actions');
  if (isOwner) {
    const hint = el('div', 'fm-gs-hint', t('messages.groupOwnerNoLeave'));
    actSec.appendChild(hint);
    const dissolveBtn = el('button', 'glass-control fm-gs-action fm-gs-danger', t('messages.groupDissolve'));
    dissolveBtn.addEventListener('click', () => {
      confirmRun(t('messages.groupDissolve'), t('messages.confirmDissolve'), async () => {
        try {
          await api.dissolveGroup(conv.conversationId);
          hooks.close();
          window.dispatchEvent(new CustomEvent('fm-groups-changed'));
          toastGlobal(t('messages.groupDisbanded'));
        } catch (err) { groupErrToast(err); }
      });
    });
    actSec.appendChild(dissolveBtn);
  } else {
    const leaveBtn = el('button', 'glass-control fm-gs-action fm-gs-danger', t('messages.groupLeave'));
    leaveBtn.addEventListener('click', () => {
      confirmRun(t('messages.groupLeave'), t('messages.confirmLeave'), async () => {
        try {
          await api.leaveGroup(conv.conversationId);
          hooks.close();
          window.dispatchEvent(new CustomEvent('fm-groups-changed'));
          toastGlobal(t('messages.groupLeft'));
        } catch (err) { groupErrToast(err); }
      });
    });
    actSec.appendChild(leaveBtn);
  }
  root.appendChild(actSec);

  // ── 设 / 撤管理员（**owner 专属**；正典 §B.2 唯一授权新路由）
  // 幂等语义落到按钮态：成功后 changed() ⇒ 抽屉重建（pill 与按钮文案同步翻面，
  // 不会出现第二态）；提交期间禁重复点击。错误路径一律 groupErrToast（可见）。
  async function setRole(mem, nextRole, btn) {
    if (btn.disabled) return;
    btn.disabled = true;
    try {
      await api.setGroupMemberRole(conv.conversationId, mem.userId, nextRole);
      toast(nextRole === 'admin' ? t('messages.groupAdminSet') : t('messages.groupAdminUnset'));
      window.dispatchEvent(new CustomEvent('fm-groups-changed'));
      changed();
    } catch (err) {
      btn.disabled = false;
      groupErrToast(err);
    }
  }

  // 成员拉取（async 填充；404/403 分态 = 群已不存在/已解散/非成员 ⇒ 提示并关抽屉）
  api.getGroupMembers(conv.conversationId).then((members) => {
    memberList.innerHTML = '';
    if (!members || members.length === 0) {
      // 退化态（群至少含 owner 一人；空集 = 取数异常的诚实呈现，禁伪装成正常态）
      memberList.appendChild(el('div', 'fm-empty', t('contacts.listError')));
      return;
    }
    if (conv.memberCount !== members.length) {
      conv.memberCount = members.length;
      headCount.textContent = t('messages.memberCount', { n: members.length });
    }
    memberHead.appendChild(el('span', 'fm-gs-count', String(members.length)));
    // 群信息头组合头像：**同一份名册**（身份键 userId 与成员面同空间；顺序纯透传，
    // 禁把索引 0 当群主）——成员头像缺失 ⇒ 逐格首字母兜底。
    paintHeadAvatar(members.map((mem) => ({ userId: mem.userId, name: mem.name, avatarUrl: mem.avatarUrl })));
    for (const mem of members) {
      const row = el('div', 'fm-member-row');
      row.appendChild(avatarEl(mem, 28));
      row.appendChild(el('span', 'fm-member-name', mem.name || mem.userId));
      if (mem.role === 'owner') row.appendChild(el('span', 'fm-role-tag', t('messages.roleOwner')));
      else if (mem.role === 'admin') row.appendChild(el('span', 'fm-role-tag', t('messages.roleAdmin')));
      // 设 / 撤管理员（**owner 专属**；与「移出群聊」同族位置 = 成员行操作位；
      // 对 owner 行不挂 —— 群主角色不可变更，服务端同判据）
      if (isOwner && mem.role !== 'owner') {
        const toAdmin = mem.role !== 'admin';
        const roleBtn = el('button', 'glass-control fm-gs-role', toAdmin ? t('messages.groupSetAdmin') : t('messages.groupRevokeAdmin'));
        roleBtn.addEventListener('click', () => setRole(mem, toAdmin ? 'admin' : 'member', roleBtn));
        row.appendChild(roleBtn);
      }
      // 踢人（按 canKick 逐目标判定；服务端为权威闸，客户端只做入口闸）
      if (canKick(mem)) {
        const kickBtn = el('button', 'glass-control fm-gs-kick', t('messages.groupKick'));
        kickBtn.addEventListener('click', () => {
          confirmRun(t('messages.groupKick'), t('messages.confirmKick', { name: mem.name || mem.userId }), async () => {
            try {
              await api.kickGroupMember(conv.conversationId, mem.userId);
              toast(t('messages.groupKickedDone'));
              window.dispatchEvent(new CustomEvent('fm-groups-changed'));
              changed();
            } catch (err) { groupErrToast(err); }
          });
        });
        row.appendChild(kickBtn);
      }
      memberList.appendChild(row);
    }
  }).catch((err) => {
    memberList.innerHTML = '';
    const code = err && err.data && (err.data.error || err.data.code);
    const text = errKind(err) === 'neblinkOff' && code === 'group_disbanded' ? t('messages.groupDisbanded')
      : errKind(err) === 'auth' ? t('messages.networkError')
      : t('messages.networkError');
    memberList.appendChild(el('div', 'fm-empty', text));
  });

  createIconsIn(root);
  return root;
}
