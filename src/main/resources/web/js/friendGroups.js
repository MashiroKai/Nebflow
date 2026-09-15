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
//    （O③）/ 群头像首字母占位（O④）/ owner 禁退群只能解散（O⑨）/ admin 字段
//    留置行为不开放（O⑧）/ 拉黑只断单聊、同群照常（O⑩）。
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
 * GET /api/groups（群域唯一取数口；messages.js 的 refreshConversations 与
 * contacts.js 的群邀请区共用，禁第二份调用点各写归一）。
 *
 * 返回值三态：
 *  · {groups, pendingInvites} — 成功（groups 已按会话行形状归一）；
 *  · {groups: [], pendingInvites: []} — 群路由 404（fail-closed：调用方按空集
 *    处理 = 群行从会话列表消失，可用性面已翻 false + 一次性提示）；
 *  · null — 鉴权/网络/5xx（keep-last-known，与好友域 refresh 同口径）。
 * @returns {Promise<{groups: any[], pendingInvites: any[]}|null>}
 */
export async function refreshGroups() {
  try {
    const env = await api.getGroups();
    markAvailability(true);
    const groups = (env.groups || []).map(normalizeRow).filter(Boolean);
    return { groups, pendingInvites: env.pendingInvites || [] };
  } catch (err) {
    if (errKind(err) === 'neblinkOff') {
      markAvailability(false);
      return { groups: [], pendingInvites: [] };
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
/** 发起群聊：选好友（可多选）+ 可选群名 → POST /api/groups。成员上限 50 做
 *  UX 预检（含本机 1 人）；权威闸在服务端（超限 422/403 走 groupErrToast）。 */
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

  // 群名（可选，O④ 同族最小面；imeGuard 接入 = 新增输入面纪律）
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
    // 成员上限 50（含本机）UX 预检；权威闸在服务端。
    if (selected.size + 1 > GROUP_MEMBER_CAP) {
      toastGlobal(t('contacts.createGroupCap'), 'error');
      return;
    }
    createBtn.disabled = true;
    try {
      const conv = await api.createGroup(nameInput.value.trim(), [...selected]);
      overlay.remove();
      window.dispatchEvent(new CustomEvent('fm-groups-changed', {
        detail: { openConversationId: conv && conv.conversationId },
      }));
      toastGlobal(t('messages.groupCreated'));
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
 * 构建群设置抽屉内容（成员列表 + 角色面 + 权限内动作）。
 * 权限矩阵（九项裁定 / 主卡 A-3）：owner = 改名/邀请/踢人/解散 + 禁退群提示；
 * member = 退群；admin = 字段留置仅展示（O⑧ 行为不开放）。
 * @param {any} conv 群会话行（kind==='group'）
 * @param {{toast: (s: string) => void, close: () => void, onChanged: () => void}} hooks
 * @returns {HTMLElement}
 */
export function buildGroupSettings(conv, hooks) {
  const root = el('div', 'fm-group-settings');
  const toast = (s) => hooks.toast(s);
  const changed = () => hooks.onChanged();
  const isOwner = conv.myRole === 'owner';

  // ── 群名（owner 可改；行内编辑，imeGuard 接入）
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
  titleInput.disabled = !isOwner;
  bindImeGuard(titleInput);
  titleInput.addEventListener('keydown', (e) => {
    if (isImeComposing(e, titleInput)) return;
    if (e.key === 'Enter') { e.preventDefault(); saveTitle(); }
  });
  titleRow.appendChild(titleInput);
  if (isOwner) {
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

  // ── 成员列表（显示名而非好友备注，主卡 H 节口径）
  const memberSec = el('div', 'fm-gs-section');
  const memberHead = el('div', 'fm-gs-title-row');
  memberHead.appendChild(el('div', 'fm-gs-title', t('messages.groupMembers')));
  memberSec.appendChild(memberHead);
  const memberList = el('div', 'fm-member-list');
  memberList.appendChild(el('div', 'fm-empty', t('messages.loading')));
  memberSec.appendChild(memberList);
  root.appendChild(memberSec);

  // ── 邀请（owner；A-4：对方 accept 后才入群 ⇒ 发出后即 pending 态反馈）
  if (isOwner) {
    const inviteSec = el('div', 'fm-gs-section');
    const inviteBtn = el('button', 'glass-control fm-gs-action', t('messages.groupInviteBtn'));
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
      const sendBtn = el('button', 'glass-control fm-msg-btn', t('messages.groupInviteSend'));
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

  // ── 退群 / 解散（互斥：owner 禁退群只能解散，O⑨）
  const actSec = el('div', 'fm-gs-section fm-gs-actions');
  if (isOwner) {
    root.appendChild(actSec);
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
    root.appendChild(actSec);
  }

  // 成员拉取（async 填充；404/403 分态 = 群已不存在/已解散/非成员 ⇒ 提示并关抽屉）
  api.getGroupMembers(conv.conversationId).then((members) => {
    memberList.innerHTML = '';
    if (!members || members.length === 0) {
      // 退化态（群至少含 owner 一人；空集 = 取数异常的诚实呈现，禁伪装成正常态）
      memberList.appendChild(el('div', 'fm-empty', t('contacts.listError')));
      return;
    }
    if (conv.memberCount !== members.length) conv.memberCount = members.length;
    memberHead.appendChild(el('span', 'fm-gs-count', String(members.length)));
    for (const mem of members) {
      const row = el('div', 'fm-member-row');
      row.appendChild(avatarEl(mem, 28));
      row.appendChild(el('span', 'fm-member-name', mem.name || mem.userId));
      if (mem.role === 'owner') row.appendChild(el('span', 'fm-role-tag', t('messages.roleOwner')));
      else if (mem.role === 'admin') row.appendChild(el('span', 'fm-role-tag', t('messages.roleAdmin')));
      // 踢人（owner only；对 owner 行不挂 —— server 闸之外的客户端预检）
      if (isOwner && mem.role !== 'owner') {
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
