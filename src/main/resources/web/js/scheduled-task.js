// scheduled-task.js — Scheduled Tasks + 待办收件箱 双面 UI（iPhone Reminders style）
// 待办分家批（2026-10-30 作者令）：待办从定时任务面拆出——
//   定时任务（顶栏 #reminder-btn 时钟）= 给 agent 用的计划触发；
//   待办（消息列表入口行 → #todo-page 提醒事项页）= 给人的，等作者处理。
// 待办体系改版批（2026-10-31 作者令）：
//   ① 工具语义分家——Schedule=agent 注入提醒；todo=提醒用户的面（待决策/待批准/
//      计划），AskUser 决策/批准类分流进收件箱，真阻塞才弹问；
//   ② UI = 苹果提醒事项风格独立页——不占侧边栏槽位（#todo-btn/#panel-todo 退役），
//      入口 = 消息列表顶部「待办事项」行（随 #fm-conversations 双端同现）；
//      分组 已到期（浮顶）/待决策/待批准/计划；圆钮打勾销账；
//   ③ 收件箱跨会话聚合（listScheduledTasks scope:'all'），行级 sessionId 寻址。
// 两面数据源分离（互不漏行）：
//   · 提醒面 = tasks（单会话列表，kind≠todo 渲染 + 超时徽标）；
//   · 待办面 = inboxTodos（scope:all 跨会话列表，分组渲染 + 入口行徽标）。
//   · 可行动聚合（actionable.js，messages 徽标消费）= 待办面计数——定时任务是
//     agent 面，不再计入「需要作者行动」的总量。
import state from './state.js';
import { key } from './branding.js';
import { sendWs, onMessage, onReconnect } from './ws.js';
import { t } from './i18n.js';
import { statusIcon } from './utils.js'; // escapeHtml = 本文件既有本地实现（107 行）
import { addNotification } from './notificationBanner.js';
import { createIconsIn } from './utils.js';
import { jumpToAsk } from './askPending.js'; // 待办分家批：ask- 镜像行跳转提问卡
// 认知二期产品批②角标可行动判据（P6）：本面板注册「待销账待办」计数提供者；
// 提醒徽标只报可行动量（待办 + 已超时定时任务），未来时间任务不上徽标。
import { registerActionableProvider, notifyActionableChanged } from './actionable.js';
// chatViews/shouldFollowBottom 随 schedcard 批（2026-10-29）摘除：瞬态 toast 注入
// 腿退役后本文件零聊天流 DOM 注入（卡片唯一呈现 = 注入 user 帧专属卡分支）。
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

// Inline locale getter to avoid caching issues with module imports
function getLocale() {
  return document.documentElement.lang || navigator.language || 'en';
}

// ── State ──────────────────────────────────────────────────────────────
let tasks = [];
// 待办收件箱（改版批 2026-10-31）：scope:'all' 跨会话聚合，与提醒面 tasks 分态
// （all 响应会混入其它会话的定时任务行——后端语义是「全库未完成 todo」，但旧
// 后端/all 之外路径仍按 kind 过滤兜底，故渲染侧一律只取 kind=todo）。
let inboxTodos = [];
let isCreating = false;
let isCreatingTodo = false;
let panelOpen = false;
let todoPageOpen = false;
// 改版批追加令（2026-10-31 23:14 ①）：项目维度筛选。'' = 全部；'__none__' =
// 未分组；其余 = 具体 project 标签（收件箱自由文本，引擎不持注册表权威）。
let todoProjectFilter = '';
// 追加令②：行内编辑态（✎ 进入）——{ id }；保存 ⇒ updateScheduledTask 帧。
let editingTodo = null;
// Recurrence selected in the inline create form ("once" | "hourly" | "daily"
// | "weekly"). Reset whenever the form opens. Wire values match ScheduleTool
// schema — "once" maps to omitting repeat (one-shot).
let currentRepeat = 'once';

// Creates attempted while the WS was down (sendWs silently drops non-OPEN
// sends). Queued instead of optimistically added — the UI must never show a
// task the server never received. Flushed on reconnect / session restore.
let pendingCreates = [];

// ── Persistence ────────────────────────────────────────────────────────
// Cache tasks per-session in localStorage so the panel shows instantly
// on page reload or session switch, before the WS response arrives.
const TASKS_CACHE_PREFIX = key('tasks_');
// 收件箱缓存不随会话（跨会话聚合语义 = 一份全局缓存）。
const INBOX_CACHE_KEY = key('todo_inbox');
const PANEL_OPEN_KEY = key('reminder_panel_open');

function loadCachedTasks(sessionId) {
  if (!sessionId) return [];
  try {
    const raw = localStorage.getItem(TASKS_CACHE_PREFIX + sessionId);
    return raw ? JSON.parse(raw) : [];
  } catch (_) { return []; }
}

function saveCachedTasks(sessionId, taskList) {
  if (!sessionId) return;
  try {
    localStorage.setItem(TASKS_CACHE_PREFIX + sessionId, JSON.stringify(taskList));
  } catch (_) {}
}

function loadPanelOpen() {
  try { return localStorage.getItem(PANEL_OPEN_KEY) === 'true'; }
  catch (_) { return false; }
}

function savePanelOpen(open) {
  try { localStorage.setItem(PANEL_OPEN_KEY, open ? 'true' : 'false'); }
  catch (_) {}
}

function loadCachedInbox() {
  try {
    const raw = localStorage.getItem(INBOX_CACHE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch (_) { return []; }
}

function saveCachedInbox(list) {
  try { localStorage.setItem(INBOX_CACHE_KEY, JSON.stringify(list)); }
  catch (_) {}
}

// ── Helpers ────────────────────────────────────────────────────────────

function $(sel) { return document.querySelector(sel); }

function formatTriggerTime(epochMs) {
  const d = new Date(epochMs);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const target = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diffDays = Math.round((target - today) / 86400000);

  const time = d.toLocaleTimeString(getLocale() === 'zh-CN' ? 'zh-CN' : 'en', {
    hour: '2-digit', minute: '2-digit', hour12: false
  });

  if (diffDays === 0) return t('task.today') + ' ' + time;
  if (diffDays === 1) return t('task.tomorrow') + ' ' + time;
  if (diffDays === -1) return t('task.yesterday') + ' ' + time;
  const dateStr = d.toLocaleDateString(getLocale() === 'zh-CN' ? 'zh-CN' : 'en', {
    month: 'short', day: 'numeric'
  });
  return dateStr + ' ' + time;
}

function isOverdue(t) {
  return !t.triggered && t.triggerAt < Date.now();
}

function isTodoTask(t) {
  return t.kind === 'todo';
}

/**
 * 待答镜像行判据（待办分家批 2026-10-30）：AskUserQuestion 挂起时引擎侧
 * AskTodoBridge upsert 的 kind=todo 镜像条目，name = ask-<requestId>。
 * 镜像的唯一销账路 = ask 终态（引擎幂等销账）⇒ 待办面不给打卡圆钮/取消 ×，
 * 整行 = 跳回提问卡作答的入口。非 ask- 前缀（人建待办/周报打卡）⇒ ''。
 */
function askRequestIdOf(r) {
  const n = typeof r.name === 'string' ? r.name : '';
  return n.startsWith('ask-') ? n.slice('ask-'.length) : '';
}

/**
 * 待批准镜像行判据（改版批 2026-10-31）：Permission / McpPermission 卡挂起时
 * AskTodoBridge upsert 的 kind=todo 镜像条目，name = perm-<requestId>。
 * 同 ask- 镜像语义：唯一销账路 = 卡终态；整行 = 跳回批准卡处理的入口
 * （批准卡与提问卡同 .option-box[data-request-id] 定位面，jumpToAsk 复用）。
 */
function permRequestIdOf(r) {
  const n = typeof r.name === 'string' ? r.name : '';
  return n.startsWith('perm-') ? n.slice('perm-'.length) : '';
}

/** 待办分组判据（改版批 2026-10-31）：固定序 overdue → decision → approval →
 *  plan。镜像行按来源分组（不等时）；人建/周按计划组，triggerAt 已过且未触发
 *  ⇒ 已到期组浮顶（红钟 overdue 语言沿用 reminder.css）。 */
function todoGroupOf(r) {
  if (askRequestIdOf(r)) return 'decision';
  if (permRequestIdOf(r)) return 'approval';
  if (!r.triggered && typeof r.triggerAt === 'number' && r.triggerAt < Date.now()) return 'overdue';
  return 'plan';
}

/**
 * 提醒面（定时任务）待处置计数：已到点未触发 = 需要处置；未来时间任务是
 * 「有更新」不是「要动手」，不上徽标。待办已从提醒面拆出（待办分家批），
 * 不计入本徽标。
 */
function reminderPendingCount() {
  return tasks.filter(t => !t.triggered && !isTodoTask(t) && isOverdue(t)).length;
}

/**
 * 待办面待销账计数（改版批 2026-10-31）：跨会话收件箱未触发 kind=todo 条数
 * （含 ask- 待答镜像 / perm- 待批准镜像——等人处理即等人行动）。入口行徽标 +
 * 可行动聚合 + 页头计数三处同源。
 */
function todoPendingCount() {
  return inboxTodos.filter(t => !t.triggered && isTodoTask(t)).length;
}

function defaultTriggerAt() {
  // 1-hour from now, rounded UP to the next minute.
  const d = new Date(Date.now() + 3600000);
  d.setSeconds(0, 0);
  return d;
}

function toLocalDatetimeString(d) {
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/** Localized label for a repeat value ("hourly" | "daily" | "weekly"), or ''
 *  for one-shot — the badge is only rendered for recurring tasks. */
function repeatLabel(r) {
  if (r === 'hourly') return t('task.repeatHourly');
  if (r === 'daily') return t('task.repeatDaily');
  if (r === 'weekly') return t('task.repeatWeekly');
  return '';
}

// ── Badge ──────────────────────────────────────────────────────────────

function updateBadge() {
  // 提醒面徽标（顶栏时钟）：已超时定时任务数（待办已拆出，不计）。
  const btn = $('#reminder-btn');
  if (btn) {
    let badge = btn.querySelector('.reminder-badge');
    const count = reminderPendingCount();
    if (count > 0) {
      if (!badge) {
        badge = document.createElement('span');
        badge.className = 'reminder-badge';
        btn.appendChild(badge);
      }
      badge.textContent = count > 9 ? '9+' : count;
    } else if (badge) {
      badge.remove();
    }
  }
  // 改版批（2026-10-31）：入口行徽标/副行刷新（消息列表行；侧边栏 #todo-btn
  // 已退役）。徽标 = 待销账待办数（含待答/待批准镜像）。
  // 可行动总量变了 ⇒ 通知 messages 徽标等订阅者（P6 判据单一聚合面）。
  notifyActionableChanged();
  refreshTodoEntry();
}

// ── Render ─────────────────────────────────────────────────────────────

/** P3 手机层分组签（mobile-secondary-screens 实施批；样张 v3.5 P3 口径）：
 *  「即将触发 / 已超时 / 已停用」站内小写字号语言（任务浮层 tp-grp 同族）。
 *  只在手机层分组分支使用，桌面扁平序零改动。 */
function buildGroupLabel(labelKey, n) {
  const el = document.createElement('div');
  el.className = 'mh-rd-grp';
  el.textContent = `${t(labelKey)} · ${n}`;
  return el;
}

function renderList() {
  renderReminderPanel();
  renderTodoPage();
}

/** 提醒面（顶栏时钟浮层）：只渲染定时任务（kind≠todo；待办已拆去侧边栏）。 */
function renderReminderPanel() {
  const body = $('#reminder-panel .reminder-panel-body');
  if (!body) return;

  const pending = tasks.filter(t => !t.triggered && !isTodoTask(t));

  // Update header count
  const countEl = $('#reminder-panel .reminder-panel-count');
  if (countEl) countEl.textContent = pending.length > 0 ? pending.length : '';

  // Clear and rebuild
  body.innerHTML = '';

  if (pending.length === 0 && !isCreating) {
    body.appendChild(buildEmptyState());
  } else if (document.body.classList.contains('mobile-layer')) {
    // P3 手机层三分组（样张 v3.5 P3 + 开放点③⑤按设计批推荐）：即将触发 →
    // 已超时（isOverdue 独立分组，红 clock-alert 语义仍由行级携带）→ 已停用
    //（enabled=false 整组垫底，行级降透明走既有 .disabled 类 + 手机层组内
    // 整行降透明）。桌面扁平序（分支外）不变。
    // 待办分家批：打卡（待办）组随拆家移去侧边栏待办面板，本面三分组纯定时任务。
    const upcoming = pending.filter(r => r.enabled !== false && !isOverdue(r));
    const overdue = pending.filter(r => r.enabled !== false && isOverdue(r));
    const disabled = pending.filter(r => r.enabled === false);
    const groups = [
      ['task.grpUpcoming', upcoming],
      ['task.overdue', overdue],
      ['task.grpDisabled', disabled],
    ];
    for (const [key, rows] of groups) {
      if (!rows.length) continue;
      body.appendChild(buildGroupLabel(key, rows.length));
      for (const r of rows) body.appendChild(buildRow(r, false));
    }
  } else {
    for (const r of pending) body.appendChild(buildRow(r, false));
  }

  // Create form (always at bottom)
  if (isCreating) {
    body.appendChild(buildInlineCreate());
  }

  if (typeof lucide !== 'undefined') createIconsIn(body);

  // Focus inline input if creating
  if (isCreating) {
    const input = body.querySelector('#reminder-inline-input');
    if (input) requestAnimationFrame(() => input.focus());
  }
}

/**
 * 待办面（改版批 2026-10-31）：#todo-page 提醒事项页——跨会话收件箱分组渲染。
 * 一级维度 = 项目（追加令 23:14 ①：筛选 chips：全部 / 各项目 / 未分组）；
 * 二级 = 类型分组固定序：已到期（浮顶）→ 待决策（ask- 镜像跳转行）→ 待批准
 * （perm- 镜像跳转行）→ 计划（圆钮打卡 + ✎ 编辑 + 行尾取消 × + 缺卡建议）。
 * 行语言复用 reminder-row 族（reminder.css）；分组/筛选签 = todo-page.css。
 */
function renderTodoPage() {
  const body = $('#todo-page-body');
  if (!body) return;

  const pendingTodos = inboxTodos.filter(t => !t.triggered && isTodoTask(t));

  const countEl = $('#todo-page-count');
  if (countEl) countEl.textContent = pendingTodos.length > 0 ? String(pendingTodos.length) : '';

  body.innerHTML = '';

  // 项目筛选行（追加令①）：chips 由收件箱现状派生；全未分组（零标签）时
  // 不渲染筛选行（克制——无第二维可滤就不摆控件）。
  if (projectFacetsOf(pendingTodos).length > 0) body.appendChild(buildProjectFilterRow(pendingTodos));
  const visible = filterByProject(pendingTodos);

  if (visible.length === 0 && !isCreatingTodo) {
    body.appendChild(buildTodoEmptyState());
  }

  const groups = [
    ['todo.grpOverdue', visible.filter((r) => todoGroupOf(r) === 'overdue')],
    ['todo.grpDecision', visible.filter((r) => todoGroupOf(r) === 'decision')],
    ['todo.grpApproval', visible.filter((r) => todoGroupOf(r) === 'approval')],
    ['todo.grpPlan', visible.filter((r) => todoGroupOf(r) === 'plan')],
  ];
  for (const [labelKey, rows] of groups) {
    if (!rows.length) continue;
    const label = document.createElement('div');
    label.className = 'todo-grp';
    label.dataset.grp = labelKey;
    const name = document.createElement('span');
    name.textContent = t(labelKey);
    const n = document.createElement('span');
    n.className = 'todo-grp-n';
    n.textContent = String(rows.length);
    label.append(name, n);
    body.appendChild(label);
    for (const r of rows) {
      const grp = todoGroupOf(r);
      if (grp === 'decision') body.appendChild(buildAskTodoRow(r));
      else if (grp === 'approval') body.appendChild(buildPermTodoRow(r));
      else body.appendChild(buildRow(r, false));
    }
  }

  // 创建/编辑表单（始终在底部）— 待办 = 纯文本 + 项目标签（追加令①）；
  // 浮出时刻是引擎侧固定策略（下一次 09:00），无时间/周期控件。
  if (isCreatingTodo) {
    body.appendChild(buildTodoInlineCreate());
  }

  if (typeof lucide !== 'undefined') createIconsIn(body);

  if (isCreatingTodo) {
    const input = body.querySelector('#todo-inline-input');
    if (input) requestAnimationFrame(() => input.focus());
  }
}

/** 项目清单派生（排序去重；未分组存在 ⇒ '__none__' 哨兵在末位）。 */
function projectFacetsOf(list) {
  const projects = [...new Set(list.map((r) => (typeof r.project === 'string' ? r.project.trim() : '')).filter(Boolean))].sort();
  const hasNone = list.some((r) => !(typeof r.project === 'string' && r.project.trim()));
  return hasNone ? [...projects, '__none__'] : projects;
}

function filterByProject(list) {
  if (!todoProjectFilter) return list;
  if (todoProjectFilter === '__none__') return list.filter((r) => !(typeof r.project === 'string' && r.project.trim()));
  return list.filter((r) => (typeof r.project === 'string' ? r.project.trim() : '') === todoProjectFilter);
}

/** 项目筛选 chips 行（追加令①）：单选，点选即滤；激活态 = .active。 */
function buildProjectFilterRow(list) {
  const row = document.createElement('div');
  row.className = 'todo-filter-row';
  const facets = ['', ...projectFacetsOf(list)];
  for (const facet of facets) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'todo-filter-chip' + (todoProjectFilter === facet ? ' active' : '');
    chip.dataset.project = facet;
    chip.textContent =
      facet === '' ? t('todo.filterAll') :
      facet === '__none__' ? t('todo.filterUngrouped') :
      facet;
    chip.addEventListener('click', (e) => {
      e.stopPropagation();
      todoProjectFilter = todoProjectFilter === facet ? '' : facet;
      renderTodoPage();
    });
    row.appendChild(chip);
  }
  return row;
}

// ── DOM builders (returns elements, not HTML strings, for reliable event binding) ──

function buildEmptyState() {
  const el = document.createElement('div');
  el.className = 'reminder-empty';
  el.innerHTML = `
    <div class="reminder-empty-text">${t('task.empty')}</div>
    <div class="reminder-empty-hint">${t('task.emptyHint')}</div>`;
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    startInlineCreate();
  });
  return el;
}

/** 待办面空态（待办分家批）：文案走 todo.* 键，点击空白创建待办。 */
function buildTodoEmptyState() {
  const el = document.createElement('div');
  el.className = 'reminder-empty';
  el.innerHTML = `
    <div class="reminder-empty-text">${t('todo.empty')}</div>
    <div class="reminder-empty-hint">${t('todo.emptyHint')}</div>`;
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    startTodoInlineCreate();
  });
  return el;
}

/**
 * 待答镜像行（AskTodoBridge 产物，name=ask-<requestId>）：整行 = 跳回提问卡作答
 * 的入口——无打卡圆钮 / 取消 ×（镜像的唯一销账路 = ask 终态，引擎侧幂等销账；
 * 人在这里打卡会造成「待办已销、提问仍挂」的分叉）。行首待答 chip 标注语义。
 */
function buildAskTodoRow(r) {
  const row = document.createElement('div');
  row.className = 'reminder-row todo-ask-row';
  row.dataset.id = r.id;
  row.setAttribute('role', 'button');
  row.title = t('todo.askJump');

  const chip = document.createElement('span');
  chip.className = 'todo-ask-chip';
  chip.textContent = t('todo.askChip');

  const content = document.createElement('div');
  content.className = 'reminder-row-content';
  const titleRow = document.createElement('div');
  titleRow.className = 'reminder-row-title';
  const textSpan = document.createElement('span');
  textSpan.className = 'reminder-row-text';
  textSpan.textContent = r.content;
  titleRow.appendChild(textSpan);
  content.appendChild(titleRow);

  row.appendChild(chip);
  row.appendChild(content);

  const askId = askRequestIdOf(r);
  row.addEventListener('click', (e) => {
    e.stopPropagation();
    // 镜像挂在提问所在的 root 会话；askPending.jumpToAsk 负责切会话 + 定位卡片。
    jumpToAsk({ requestId: askId, sessionId: r.sessionId || state.activeSessionId });
  });
  return row;
}

/**
 * 待批准镜像行（改版批 2026-10-31，AskTodoBridge perm- 产物，name=perm-<requestId>）：
 * 与 ask- 镜像同语义——整行 = 跳回批准卡处理的入口，无打卡圆钮 / 取消 ×
 * （镜像唯一销账路 = 卡终态，引擎侧幂等销账）。批准卡与提问卡同
 * .option-box[data-request-id] 定位面 ⇒ jumpToAsk 直接复用。
 */
function buildPermTodoRow(r) {
  const row = document.createElement('div');
  row.className = 'reminder-row todo-ask-row todo-perm-row';
  row.dataset.id = r.id;
  row.setAttribute('role', 'button');
  row.title = t('todo.permJump');

  const chip = document.createElement('span');
  chip.className = 'todo-perm-chip';
  chip.textContent = t('todo.permChip');

  const content = document.createElement('div');
  content.className = 'reminder-row-content';
  const titleRow = document.createElement('div');
  titleRow.className = 'reminder-row-title';
  const textSpan = document.createElement('span');
  textSpan.className = 'reminder-row-text';
  textSpan.textContent = r.content;
  titleRow.appendChild(textSpan);
  content.appendChild(titleRow);
  row.appendChild(chip);
  row.appendChild(content);

  const permId = permRequestIdOf(r);
  row.addEventListener('click', (e) => {
    e.stopPropagation();
    jumpToAsk({ requestId: permId, sessionId: r.sessionId || state.activeSessionId });
  });
  return row;
}

function buildRow(r, isTriggered) {
  const row = document.createElement('div');
  row.className = 'reminder-row' + (isTriggered ? ' triggered' : '');
  if (!isTriggered && isOverdue(r)) row.classList.add('overdue');
  if (r.enabled === false) row.classList.add('disabled');
  row.dataset.id = r.id;

  // Circle button
  const circle = document.createElement('button');
  circle.className = 'reminder-circle';
  if (isTriggered) {
    circle.classList.add('completed');
    circle.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';
    circle.disabled = true;
  } else if (isTodoTask(r)) {
    // 认知二期产品批③打卡：待办圆钮 = 打卡销账（completeTodo——留 done 痕，
    // 周报数据源）；取消走行尾 ×（deleteScheduledTask，不留痕）。
    circle.dataset.action = 'checkin';
    circle.dataset.id = r.id;
    circle.title = t('task.checkin');
    circle.addEventListener('click', (e) => {
      e.stopPropagation();
      completeCheckin(r.id);
    });
  } else {
    circle.dataset.action = 'delete';
    circle.dataset.id = r.id;
    circle.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteTask(r.id);
    });
  }

  // Content area
  const content = document.createElement('div');
  content.className = 'reminder-row-content';

  // Title line: task text (ellipsis) + repeat badge for recurring tasks.
  // One-shot tasks get no badge (restraint; the repeat field is the signal).
  const titleRow = document.createElement('div');
  titleRow.className = 'reminder-row-title';
  const textSpan = document.createElement('span');
  textSpan.className = 'reminder-row-text';
  textSpan.textContent = r.content;
  titleRow.appendChild(textSpan);
  // 项目标签（追加令①）：行内淡色标签（与 repeat badge 同位语言）。
  if (r.project) {
    const proj = document.createElement('span');
    proj.className = 'reminder-proj-tag';
    proj.textContent = String(r.project);
    titleRow.appendChild(proj);
  }
  const badgeLabel = repeatLabel(r.repeat);
  if (badgeLabel) {
    const badge = document.createElement('span');
    badge.className = 'reminder-repeat-badge';
    badge.dataset.repeat = r.repeat;
    badge.textContent = badgeLabel;
    titleRow.appendChild(badge);
  }
  content.appendChild(titleRow);

  // Time info: show next trigger + last triggered when available
  const timeSpan = document.createElement('span');
  timeSpan.className = 'reminder-row-time';
  if (isTriggered) {
    timeSpan.textContent = t('task.triggered') + ' ' + formatTriggerTime(r.triggerAt);
  } else {
    const nextTime = r.nextTrigger || r.triggerAt;
    const overdue = isOverdue({ triggered: false, triggerAt: nextTime });
    // ui-status-redesign（S12 / 盘点 #39）：逾期行 = 行首 clock-alert 红图标 +
    // 中性等宽时间；「逾期」tag 转图案（红边淡红底）+ 中性字。
    timeSpan.innerHTML = (overdue ? statusIcon('clock-alert', 11, 'st-err') : '')
      + '<span>' + escapeHtml(formatTriggerTime(nextTime)) + '</span>';
    if (overdue) {
      const tag = document.createElement('span');
      tag.className = 'reminder-overdue-tag';
      tag.innerHTML = statusIcon('clock-alert', 9, 'st-err') + '<span>' + escapeHtml(t('task.overdue')) + '</span>';
      timeSpan.appendChild(tag);
    }
  }

  content.appendChild(timeSpan);

  // Last triggered sub-text (if available)
  if (r.lastTriggered) {
    const lastEl = document.createElement('div');
    lastEl.className = 'reminder-row-last';
    lastEl.textContent = t('task.lastTriggered') + ' ' + formatTriggerTime(r.lastTriggered);
    content.appendChild(lastEl);
  }

  // 认知二期产品批③缺卡调整流：连续浮出未销账达阈值的待办，行内附调整建议
  // （不指责，只给默认建议呈作者确认——注意力伦理，不擅自改计划）。
  if (!isTriggered && isTodoTask(r) && (r.missCount || 0) >= 3) {
    const missEl = document.createElement('div');
    missEl.className = 'reminder-miss-suggest';
    missEl.textContent = t('task.missSuggest', { n: r.missCount });
    content.appendChild(missEl);
  }

  row.appendChild(circle);
  row.appendChild(content);

  // 待办的取消钮（×）——与打卡圆钮并存：打卡 = 留痕销账，取消 = 无痕删除。
  // 追加令②（2026-10-31 23:14）：✎ 编辑钮（改内容/改项目分组）。
  if (!isTriggered && isTodoTask(r)) {
    const editBtn = document.createElement('button');
    editBtn.className = 'reminder-cancel reminder-edit';
    editBtn.type = 'button';
    editBtn.title = t('todo.edit');
    editBtn.dataset.action = 'edit';
    editBtn.dataset.id = r.id;
    editBtn.innerHTML = '<i data-lucide="pencil"></i>';
    editBtn.setAttribute('aria-label', t('todo.edit'));
    editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      startTodoEdit(r);
    });
    row.appendChild(editBtn);
    const cancel = document.createElement('button');
    cancel.className = 'reminder-cancel';
    cancel.type = 'button';
    cancel.title = t('task.cancel');
    cancel.textContent = '×';
    cancel.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteTask(r.id);
    });
    row.appendChild(cancel);
  }

  return row;
}


function buildInlineCreate() {
  const wrap = document.createElement('div');
  wrap.className = 'reminder-inline-create';
  wrap.id = 'reminder-inline-create';

  const dtStr = toLocalDatetimeString(defaultTriggerAt());
  const minStr = toLocalDatetimeString(new Date());

  wrap.innerHTML = `
    <div class="reminder-inline-main">
      <span class="reminder-circle ghost"></span>
      <input type="text" class="reminder-inline-input" id="reminder-inline-input"
        placeholder="${t('task.inputPlaceholder')}" autocomplete="off">
    </div>
    <div class="reminder-inline-detail">
      <div class="reminder-time-row">
        <i data-lucide="clock"></i>
        <input type="datetime-local" id="reminder-time-input" value="${dtStr}" min="${minStr}">
      </div>
      <div class="reminder-repeat-row">
        <i data-lucide="repeat"></i>
        <div class="reminder-repeat-seg" id="reminder-repeat-seg" role="radiogroup" aria-label="${t('task.repeat')}">
          <button type="button" data-repeat="once" class="active" role="radio" aria-checked="true">${t('task.repeatOnce')}</button>
          <button type="button" data-repeat="hourly" role="radio" aria-checked="false">${t('task.repeatHourly')}</button>
          <button type="button" data-repeat="daily" role="radio" aria-checked="false">${t('task.repeatDaily')}</button>
          <button type="button" data-repeat="weekly" role="radio" aria-checked="false">${t('task.repeatWeekly')}</button>
        </div>
      </div>
    </div>`;

  // Bind events
  const input = wrap.querySelector('#reminder-inline-input');
  const timeInput = wrap.querySelector('#reminder-time-input');
  const repeatSeg = wrap.querySelector('#reminder-repeat-seg');

  // Repeat segmented control — the active pill is currentRepeat; values match
  // ScheduleTool schema ("hourly"|"daily"|"weekly"; "once" omits repeat).
  if (repeatSeg) {
    repeatSeg.addEventListener('click', (e) => {
      if (!(e.target instanceof Element)) return;
      const btn = /** @type {HTMLElement | null} */ (e.target.closest('button[data-repeat]'));
      if (!btn) return;
      repeatSeg.querySelectorAll('button').forEach(b => {
        const active = b === btn;
        b.classList.toggle('active', active);
        b.setAttribute('aria-checked', active ? 'true' : 'false');
      });
      currentRepeat = btn.dataset.repeat;
    });
  }

  if (input) {
    // ⑤ 组字期间 Enter/Esc 交还输入法（非组字态行为逐键不变）。
    bindImeGuard(input);
    input.addEventListener('keydown', (e) => {
      if (isImeComposing(e, input)) return;
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        saveInlineTask();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        cancelInlineCreate();
      }
    });
  }

  // Update min when time input is focused (prevents stale past-time validation)
  if (timeInput) {
    timeInput.addEventListener('focus', () => {
      timeInput.min = toLocalDatetimeString(new Date());
    });
  }

  return wrap;
}

/**
 * 待办面内联创建/编辑表单（待办分家批 + 追加令② 2026-10-31 23:14）：
 * 纯文本 + 项目标签（追加令①——预填当前项目筛选；编辑态预填原行内容/归属，
 * 保存 ⇒ updateScheduledTask 帧）。待办无固定触发时间（下一次 09:00 浮出是
 * 引擎侧固定策略），无周期语义，故不渲染时间/重复控件。
 */
function buildTodoInlineCreate() {
  const editing = editingTodo ? inboxTodos.find((r) => r.id === editingTodo.id) : null;
  const wrap = document.createElement('div');
  wrap.className = 'reminder-inline-create todo-inline';

  const projectPrefill =
    editing ? String(editing.project || '')
    : (todoProjectFilter && todoProjectFilter !== '__none__' ? todoProjectFilter : '');

  wrap.innerHTML = `
    <div class="reminder-inline-main">
      <span class="reminder-circle ghost"></span>
      <input type="text" class="reminder-inline-input" id="todo-inline-input"
        placeholder="${t('todo.inputPlaceholder')}" autocomplete="off"
        value="${escapeHtml(editing ? String(editing.content || '') : '')}">
    </div>
    <div class="reminder-inline-project">
      <i data-lucide="folder"></i>
      <input type="text" id="todo-inline-project"
        placeholder="${t('todo.projectPlaceholder')}" autocomplete="off"
        value="${escapeHtml(projectPrefill)}">
    </div>`;

  const input = /** @type {HTMLInputElement | null} */ (wrap.querySelector('#todo-inline-input'));
  const projectInput = /** @type {HTMLInputElement | null} */ (wrap.querySelector('#todo-inline-project'));
  if (input) {
    // ⑤ 组字期间 Enter/Esc 交还输入法（非组字态行为逐键不变）。
    bindImeGuard(input);
    input.addEventListener('keydown', (e) => {
      if (isImeComposing(e, input)) return;
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        editing ? saveTodoEdit() : saveTodoInline();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        cancelTodoInlineCreate();
      }
    });
  }
  if (projectInput) {
    bindImeGuard(projectInput);
    projectInput.addEventListener('keydown', (e) => {
      if (isImeComposing(e, projectInput)) return;
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        input?.focus();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        cancelTodoInlineCreate();
      }
    });
  }

  return wrap;
}

/** 表单 project 读取（trim；空 = 未分组）。 */
function inlineProjectValue() {
  const el = /** @type {HTMLInputElement | null} */ (document.querySelector('#todo-inline-project'));
  return el ? el.value.trim() : '';
}

// ── Actions ────────────────────────────────────────────────────────────

function openPanel() {
  const panel = $('#reminder-panel');
  if (!panel) return;
  panelOpen = true;
  isCreating = false;
  panel.classList.add('open');
  savePanelOpen(true);

  if (typeof lucide !== 'undefined') createIconsIn(panel);

  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  }
}

function closePanel() {
  const panel = $('#reminder-panel');
  if (!panel) return;
  panelOpen = false;
  isCreating = false;
  panel.classList.remove('open');
  savePanelOpen(false);
}

function togglePanel() {
  if (panelOpen) closePanel();
  else openPanel();
}

/** 请求跨会话待办收件箱（scope:all；会话 id 仅作兜底，后端忽略之）。 */
function requestTodoInbox() {
  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId, scope: 'all' });
  }
}

/**
 * 待办事项页开合（改版批 2026-10-31）。桌面 = 入口行点击直开；手机层由
 * navController pushPage('todo') 后经 ensurePanelContent 调到这里（面被收编
 * 进页面体，.open 语义同 reminder-panel 先例）。打开即刷新收件箱。
 */
export function openTodoPage() {
  const page = $('#todo-page');
  if (!page) return;
  todoPageOpen = true;
  isCreatingTodo = false;
  page.classList.add('open');
  if (typeof lucide !== 'undefined') createIconsIn(page);
  requestTodoInbox();
}

function closeTodoPage() {
  const page = $('#todo-page');
  if (!page) return;
  todoPageOpen = false;
  isCreatingTodo = false;
  page.classList.remove('open');
}

function toggleTodoPage() {
  if (todoPageOpen) closeTodoPage();
  else openTodoPage();
}

function startInlineCreate() {
  if (isCreating) return;
  isCreating = true;
  currentRepeat = 'once';   // fresh form → one-shot by default
  renderList();
}

/** 待办面内联创建开/关（待办分家批，与提醒面 isCreating 互不干扰）。 */
function startTodoInlineCreate() {
  if (isCreatingTodo) return;
  editingTodo = null;
  isCreatingTodo = true;
  renderList();
}

/** 追加令②：进入行内编辑（✎；行转表单，保存 ⇒ updateScheduledTask 帧）。 */
function startTodoEdit(r) {
  editingTodo = { id: r.id };
  isCreatingTodo = true;
  renderList();
}

function cancelInlineCreate() {
  isCreating = false;
  renderList();
}

function cancelTodoInlineCreate() {
  isCreatingTodo = false;
  editingTodo = null;
  renderList();
}

/**
 * 追加令②：保存编辑（updateScheduledTask 帧，字段级 patch——content/project）。
 * 就地乐观收敛：本地直接改行；回执 scheduledTaskUpdated 幂等再对账。
 */
function saveTodoEdit() {
  const input = /** @type {HTMLInputElement | null} */ (document.querySelector('#todo-inline-input'));
  const target = editingTodo ? (inboxTodos.find((r) => r.id === editingTodo.id) || tasks.find((r) => r.id === editingTodo.id)) : null;
  if (!input || !target) { cancelTodoInlineCreate(); return; }
  const content = input.value.trim();
  const project = inlineProjectValue();
  if (!content) { cancelTodoInlineCreate(); return; }

  const sid = target.sessionId || state.activeSessionId;
  if (wsReady()) {
    sendWs({
      type: 'updateScheduledTask',
      sessionId: sid,
      id: target.id,
      content,
      project // "" = 清空归属（未分组）；帧处理器字段级 patch
    });
  } else {
    addNotification('task', t('task.queuedOffline'), { dismissAfter: 15000 });
  }
  // 乐观收敛（WS 未就绪也改本地——回执帧/重连 re-list 会纠正）。
  target.content = content;
  target.project = project || undefined;
  saveCachedInbox(inboxTodos);
  editingTodo = null;
  isCreatingTodo = false;
  renderList();
  updateBadge();
}

// Send a create frame and add the optimistic temp row. Only call this when
// the WS is OPEN and a session is active — otherwise the row would be a lie
// (sendWs silently drops non-OPEN sends).
// kind: undefined = 定时任务（现状）；'todo' = 待办（待办分家批——triggerAt 客户端
// 占位、服务端 kind=todo 分支重算为下一次 09:00 浮出时刻；repeat 无待办语义）。
function submitCreate(content, triggerAt, repeat, kind, project) {
  const payload = {
    type: 'createScheduledTask',
    sessionId: state.activeSessionId,
    content: content,
    triggerAt: triggerAt,
    referencePath: undefined
  };
  // "once" = one-shot = omit repeat (ScheduleTool schema: optional field);
  // hourly/daily/weekly are sent verbatim.
  if (repeat && repeat !== 'once') payload.repeat = repeat;
  if (kind === 'todo') {
    payload.kind = 'todo';
    // 服务端 kind=todo 分支不校验客户端时间（重算浮出时刻）；占位一个未来值仅供
    // 乐观行渲染，回执到达后 re-list 以服务端值为准。
    if (!payload.triggerAt || payload.triggerAt <= Date.now()) payload.triggerAt = Date.now() + 60000;
    // 项目标签（追加令①）：空串不落键（未分组）。
    if (project) payload.project = project;
  }
  sendWs(payload);

  // Optimistic add — append to end（改版批：kind=todo 乐观行进收件箱面，
  // 提醒面零混入；回执到达后 re-list 以服务端值为准）。
  const optimistic = {
    id: 'temp-' + Date.now(),
    content: content,
    triggerAt: payload.triggerAt,
    createdAt: Date.now(),
    triggered: false,
    triggeredAt: null,
    referencePath: null,
    repeat: (kind === 'todo') ? undefined : (repeat && repeat !== 'once' ? repeat : undefined),
    kind: kind === 'todo' ? 'todo' : undefined,
    sessionId: state.activeSessionId,
    project: (kind === 'todo' && project) ? project : undefined
  };
  if (kind === 'todo') inboxTodos.push(optimistic);
  else tasks.push(optimistic);
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  if (kind === 'todo') saveCachedInbox(inboxTodos);
}

function wsReady() {
  return !!(state.ws && state.ws.readyState === WebSocket.OPEN && state.activeSessionId);
}

function flushPendingCreates() {
  if (pendingCreates.length === 0 || !wsReady()) return;
  const queued = pendingCreates;
  pendingCreates = [];
  for (const q of queued) {
    if (q.kind === 'todo') {
      // 待办：无客户端时间语义，直接补发（服务端算浮出时刻）；项目标签随行。
      submitCreate(q.content, 0, 'once', 'todo', q.project);
      continue;
    }
    // Same auto-bump semantics as saveInlineTask: a trigger time that expired
    // while queued becomes 2 min from now.
    const triggerAt = q.triggerAt <= Date.now() ? Date.now() + 120000 : q.triggerAt;
    submitCreate(q.content, triggerAt, q.repeat);
  }
  renderList();
  updateBadge();
  requestTodoInbox();
}

function saveInlineTask() {
  const input = document.querySelector('#reminder-inline-input');
  const timeInput = document.querySelector('#reminder-time-input');
  if (!input || !timeInput) return;

  const content = input.value.trim();
  if (!content) {
    cancelInlineCreate();
    return;
  }

  const triggerAt = new Date(timeInput.value).getTime();
  if (!triggerAt || isNaN(triggerAt)) {
    timeInput.focus();
    return;
  }

  // If time is within 1 minute of now, auto-bump to 2 min from now.
  // This prevents race conditions where a near-future time expires by the
  // time the WS message round-trips to the server.
  const now = Date.now();
  const effectiveTriggerAt = triggerAt <= now + 60000
    ? now + 120000
    : triggerAt;

  if (!wsReady()) {
    // WS down (reconnect backoff, half-open socket, server restart) or no
    // active session yet — queue instead of lying with an optimistic row.
    pendingCreates.push({ content: content, triggerAt: effectiveTriggerAt, repeat: currentRepeat });
    addNotification('task', t('task.queuedOffline'), { dismissAfter: 15000 });
    isCreating = false;
    renderList();
    return;
  }

  submitCreate(content, effectiveTriggerAt, currentRepeat);
  isCreating = false;
  renderList();
  updateBadge();
}

/** 待办面保存（待办分家批）：纯文本 + 项目标签（追加令①），kind='todo' 帧——
 *  服务端算下一次 09:00 浮出时刻。WS 未就绪时排队（重连补发），不造乐观行谎报。 */
function saveTodoInline() {
  const input = /** @type {HTMLInputElement | null} */ (document.querySelector('#todo-inline-input'));
  if (!input) return;

  const content = input.value.trim();
  const project = inlineProjectValue();
  if (!content) {
    cancelTodoInlineCreate();
    return;
  }

  if (!wsReady()) {
    pendingCreates.push({ content: content, triggerAt: 0, repeat: 'once', kind: 'todo', project });
    addNotification('task', t('task.queuedOffline'), { dismissAfter: 15000 });
    isCreatingTodo = false;
    renderList();
    return;
  }

  submitCreate(content, 0, 'once', 'todo', project);
  isCreatingTodo = false;
  renderList();
  updateBadge();
}

/**
 * 行级会话归属解析（改版批 2026-10-31）：跨会话收件箱行的 completeTodo /
 * deleteScheduledTask 帧必须带行自身 sessionId（后端 completeTodo 按 id 查库
 * 取真值、帧内值兜底；delete 按帧内值寻址）。兜底顺序：收件箱 → 提醒面 →
 * 当前会话。
 */
function sessionIdOfTask(id) {
  const hit = inboxTodos.find(t => t.id === id) || tasks.find(t => t.id === id);
  return (hit && hit.sessionId) || state.activeSessionId;
}

function deleteTask(id) {
  const sid = sessionIdOfTask(id);
  if (!sid) return;

  const row = document.querySelector(`.reminder-row[data-id="${id}"]`);
  if (row) {
    const circle = row.querySelector('.reminder-circle');
    if (circle) circle.classList.add('completing');
    row.classList.add('removing');
    setTimeout(() => {
      sendWs({ type: 'deleteScheduledTask', sessionId: sid, id: id });
      tasks = tasks.filter(t => t.id !== id);
      inboxTodos = inboxTodos.filter(t => t.id !== id);
      if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
      saveCachedInbox(inboxTodos);
      renderList();
      updateBadge();
    }, 350);
  } else {
    sendWs({ type: 'deleteScheduledTask', sessionId: sid, id: id });
    tasks = tasks.filter(t => t.id !== id);
    inboxTodos = inboxTodos.filter(t => t.id !== id);
    if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
    saveCachedInbox(inboxTodos);
    renderList();
    updateBadge();
  }
}

/**
 * 打卡销账（认知二期产品批③）：completeTodo 帧——引擎留 done 痕（周报数据
 * 源）后删行；广播回执沿用 scheduledTaskDeleted 帧型，本地乐观收敛同 delete。
 * 改版批：帧带行自身 sessionId（跨会话收件箱寻址）。
 */
function completeCheckin(id) {
  const sid = sessionIdOfTask(id);
  if (!sid) return;
  const row = document.querySelector(`.reminder-row[data-id="${id}"]`);
  if (row) {
    const circle = row.querySelector('.reminder-circle');
    if (circle) circle.classList.add('completing');
    row.classList.add('removing');
  }
  sendWs({ type: 'completeTodo', sessionId: sid, id: id });
  tasks = tasks.filter(t => t.id !== id);
  inboxTodos = inboxTodos.filter(t => t.id !== id);
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  saveCachedInbox(inboxTodos);
  renderList();
  updateBadge();
}

// ── WS Message Handlers ───────────────────────────────────────────────

onMessage('scheduledTaskList', (msg) => {
  // 改版批（2026-10-31）：scope:'all' 响应 ⇒ 跨会话收件箱面；否则 = 单会话
  // 提醒面。两面分态，互不漏行（all 响应不含可靠 kind 过滤义务 ⇒ 收件箱只取
  // kind=todo；提醒面只取 kind≠todo）。
  if (msg.scope === 'all') {
    inboxTodos = (msg.tasks || []).filter(isTodoTask);
    saveCachedInbox(inboxTodos);
  } else {
    tasks = msg.tasks || [];
    if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  }
  renderList();
  updateBadge();
});

onMessage('scheduledTaskCreated', (msg) => {
  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  }
  // 改版批：收件箱跨会话聚合——新待办可能落在其它会话，同步刷新一面。
  requestTodoInbox();
  updateBadge();
});

// Server-side rejection of a create (invalid fields, etc.). The backend tags
// these with msgType: 'createScheduledTask'. Roll back the optimistic temp
// row so the list never shows a task the server refused to persist.
onMessage('error', (msg) => {
  if (msg.msgType !== 'createScheduledTask') return;
  // Remove the most recent optimistic temp row (the one this error answers).
  // 改版批：待办乐观行在收件箱面，提醒面行在任务面——两面都回滚。
  for (let i = inboxTodos.length - 1; i >= 0; i--) {
    if (typeof inboxTodos[i].id === 'string' && inboxTodos[i].id.startsWith('temp-')) {
      inboxTodos.splice(i, 1);
      break;
    }
  }
  for (let i = tasks.length - 1; i >= 0; i--) {
    if (typeof tasks[i].id === 'string' && tasks[i].id.startsWith('temp-')) {
      tasks.splice(i, 1);
      break;
    }
  }
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  saveCachedInbox(inboxTodos);
  renderList();
  updateBadge();
  addNotification('task', msg.message || t('task.createFailed'), { dismissAfter: 15000 });
});

onMessage('scheduledTaskDeleted', (msg) => {
  tasks = tasks.filter(t => t.id !== msg.id);
  inboxTodos = inboxTodos.filter(t => t.id !== msg.id);
  saveCachedInbox(inboxTodos);
  renderList();
  updateBadge();
});

// 追加令②（2026-10-31 23:14）：编辑回执——就地替换行（幂等对账；含 Schedule
// 工具 edit 动作的广播，同一帧型双路收敛）。
onMessage('scheduledTaskUpdated', (msg) => {
  const updated = msg.task;
  if (!updated || !updated.id) return;
  const patch = (list) => list.map((t) => (t.id === updated.id ? { ...t, ...updated } : t));
  const hit = inboxTodos.some((t) => t.id === updated.id) || tasks.some((t) => t.id === updated.id);
  if (!hit) return;
  inboxTodos = patch(inboxTodos);
  tasks = patch(tasks);
  saveCachedInbox(inboxTodos);
  renderList();
  updateBadge();
});

onMessage('scheduledTaskToggled', (msg) => {
  // Server confirmed toggle — update local state
  tasks = tasks.map(t => t.id === msg.id ? { ...t, enabled: msg.enabled } : t);
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  renderList();
  updateBadge();
});

onMessage('weeklyReportReady', (msg) => {
  // 认知二期收尾回填（设计稿 §6.4 接合面 + §9-12/§9-13 授权）：周报浮出面从
  // 通知横幅改为待办收件箱浮出——引擎侧已 upsert kind=todo 条目（name=
  // weekly-report-<weekKey>，聚合节奏接每日 09:00 todo digest 面，done 销账 =
  // P2 闭合回路）。本帧保留为「就绪」同步信号：刷新收件箱列表（条目落打卡组
  // 置顶 + 徽标 +1），横幅退役（P6：聚合一次浮出，不逐节点打扰）。
  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  }
  // 改版批：周报条目落收件箱，同步刷新跨会话一面。
  requestTodoInbox();
});

// 改版批（2026-10-31）：todoSurfaced 帧此前前端零消费——到点浮出（digest 注入
// agent）后刷新收件箱与徽标（触发不删行：待办浮出后仍在册等销账，仅重排分组）。
onMessage('todoSurfaced', () => {
  requestTodoInbox();
  renderList();
  updateBadge();
});

onMessage('scheduledTaskTriggered', (msg) => {
  // Show persistent notification regardless of active session, so it survives session switches
  if (msg.task && msg.task.content) {
    addNotification('task', msg.task.content, { dismissAfter: 60000 });
  }

  if (msg.sessionId === state.activeSessionId) {
    // Remove the triggered task from the list immediately
    tasks = tasks.filter(t => t.id !== (msg.task && msg.task.id));
    if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
    renderList();
    updateBadge();

    // schedcard 批（2026-10-29）：本帧原先还往聊天流手拼一张瞬态 toast
    // （reminder.css 已退役的 triggered-toast 样式，刷新即丢）——与引擎落盘/广播
    // 的注入 user 帧双显。现在聊天的卡片呈现**唯一**由注入帧管
    // （main.js onMessage('user') → buildInjectedRow 的 source==='schedule'
    // 专属卡分支，持久 + 刷新存活），这里只剩列表/徽章/通知职责，零 DOM 注入。
  } else {
    // Non-active session: still update local task state so badge is correct
    tasks = tasks.map(t =>
      t.id === (msg.task && msg.task.id)
        ? { ...t, triggered: true, triggeredAt: Date.now() }
        : t
    );
    updateBadge();
  }
});

// ── Public Init ────────────────────────────────────────────────────────

export function initScheduledTask() {
  // Header clock button — toggle panel
  const reminderBtn = $('#reminder-btn');
  if (reminderBtn) {
    reminderBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePanel();
    });
  }

  // "+" button in panel header
  const createBtn = $('#reminder-create-btn');
  if (createBtn) {
    createBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (isCreating) {
        saveInlineTask();
      } else {
        startInlineCreate();
      }
    });
  }

  // 待办分家批：侧边栏待办面板的 + 钮（保存/开表单 双态，与提醒面同节奏）。
  const todoCreateBtn = $('#todo-create-btn');
  if (todoCreateBtn) {
    todoCreateBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (isCreatingTodo) {
        saveTodoInline();
      } else {
        startTodoInlineCreate();
      }
    });
  }

  // 待办页体点击：点空白创建待办（与提醒面同交互语言）。
  const todoBody = $('#todo-page-body');
  if (todoBody) {
    todoBody.addEventListener('click', (e) => {
      if (e.target.closest('button, input, a, .reminder-row')) return;
      if (isCreatingTodo) saveTodoInline();
      else startTodoInlineCreate();
    });
  }

  // 待办页关闭钮（桌面 overlay 语义；手机层走页面栈返回）。
  const todoCloseBtn = $('#todo-close-btn');
  if (todoCloseBtn) {
    todoCloseBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeTodoPage();
    });
  }

  // Panel body click: click empty area to create/save
  const body = $('#reminder-panel .reminder-panel-body');
  if (body) {
    body.addEventListener('click', (e) => {
      if (e.target.closest('button, input, a, .reminder-row')) return;
      if (isCreating) saveInlineTask();
      else startInlineCreate();
    });
  }

  // Outside click: close panel
  document.addEventListener('click', (e) => {
    const panel = $('#reminder-panel');
    // isConnected 守卫：面内点击若在冒泡途中触发面板自重渲（body.innerHTML
    // 重建，点击目标被摘除），contains() 对已摘除节点返 false 会被误读成
    // 「面外点击」而误关面板（dock 批 2026-10-29 p3 T5 实测：创建态点分组签
    // 取消 → 重渲 → 面板被误关）。真·面外点击目标恒在树内，守卫不影响。
    if (!(e.target instanceof Node) || !e.target.isConnected) return;
    if (panel && panelOpen && !panel.contains(e.target) && !e.target.closest('#reminder-btn')) {
      closePanel();
    }
    // 改版批：待办页同款 outside-close（手机层页被收编进页面体且满铺——
    // 页内点击恒 contains，不会误关；栈返回由 navController 管）。
    const todoPage = $('#todo-page');
    if (todoPage && todoPageOpen && !todoPage.contains(e.target) && !e.target.closest('.fm-todo-entry')) {
      closeTodoPage();
    }
  });

  // Restore panel open state from last session
  if (loadPanelOpen()) {
    const panel = $('#reminder-panel');
    if (panel) {
      panelOpen = true;
      panel.classList.add('open');
    }
  }

  // Flush any creates queued while the WS was down once the connection is
  // (re)established. Runs on every open; the queue is normally empty.
  onReconnect(flushPendingCreates);

  // 认知二期产品批② + 待办分家批（2026-10-30）语义理清：可行动聚合 = 待办面
  // 计数（待办是「等人处理」的面，含 AskUser 待答镜像）；定时任务是 agent 面
  // （计划触发，到点自动叫醒模型处置），不再计入「需要作者行动」的总量。
  registerActionableProvider(todoPendingCount);
  notifyActionableChanged();

  // Auto-load tasks for active session
  inboxTodos = loadCachedInbox();
  if (state.activeSessionId) {
    // Show cached tasks instantly, then refresh from server
    tasks = loadCachedTasks(state.activeSessionId);
    renderList();
    updateBadge();
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
    requestTodoInbox();
  } else {
    // No active session yet — still render cached tasks for when session arrives
    tasks = loadCachedTasks(state.activeSessionId) || [];
    renderList();
    updateBadge();
  }
}

/**
 * 消息列表「待办事项」入口行（改版批 2026-10-31）——fm-row 会话行家族：
 * 圆形图标头像 + 行名 + 副行（最早一条待办摘录）+ 右侧计数徽标。渲染核在
 * messages.js renderList（prepend，随 #fm-conversations 双端同现——手机端
 * 整容器收编进消息页）。点击：桌面 = 直开 #todo-page；手机层 = 事件环推页。
 */
export function buildTodoEntryRow() {
  // 注意：不挂 .fm-conv-row——那是真会话行的定位面（messages.js 行集查询/
  // listbox 导航按它计数，入口行混入会污染键盘导航与行集断言）。
  const row = document.createElement('div');
  row.className = 'fm-row fm-todo-entry';
  row.setAttribute('role', 'button');
  row.setAttribute('tabindex', '0');
  row.dataset.todoEntry = '1';

  const icon = document.createElement('span');
  // 圆框统一批（2026-11-01，作者实拍指认）：入口行圆框 = `.fm-avatar` 家族
  // （与设备行 `fm-avatar-device` / 好友行同规格：40px + glass-etched 底描边），
  // 本类（.fm-todo-icon）只管字形着色与落点尺寸——禁自带第二份圆框几何。
  icon.className = 'fm-avatar fm-avatar-40 fm-todo-icon';
  icon.innerHTML = '<i data-lucide="list-checks"></i>';
  row.appendChild(icon);

  const meta = document.createElement('div');
  meta.className = 'fm-row-meta';
  const top = document.createElement('div');
  top.className = 'fm-conv-top';
  const name = document.createElement('span');
  name.className = 'fm-row-name';
  name.textContent = t('todo.entryTitle');
  const badge = document.createElement('span');
  badge.className = 'fm-row-badge';
  badge.hidden = true;
  top.appendChild(name);
  top.appendChild(badge);
  const bottom = document.createElement('div');
  bottom.className = 'fm-conv-bottom';
  const summary = document.createElement('span');
  summary.className = 'fm-conv-summary todo-entry-summary';
  bottom.appendChild(summary);
  meta.appendChild(top);
  meta.appendChild(bottom);
  row.appendChild(meta);

  const open = (e) => {
    e.stopPropagation();
    if (document.body.classList.contains('mobile-layer')) {
      // 手机层：事件环外发（避免循环 import）——栈层 push todo 详情页。
      document.dispatchEvent(new CustomEvent('nf:mobile-todo-entry'));
    } else {
      toggleTodoPage();
    }
  };
  row.addEventListener('click', open);
  row.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(e); }
  });
  // 建行即上漆（messages renderList 会整行重建——DOM 查询式刷新对新建行是
  // no-op，状态必须直接从 state 初始化；行在树后的就地刷新走 refreshTodoEntry）。
  paintTodoEntry(row);
  if (typeof lucide !== 'undefined') createIconsIn(row);
  return row;
}

/** 入口行徽标/副行上漆（建行与就地刷新共用；数据 = 收件箱未触发 kind=todo）。 */
function paintTodoEntry(row) {
  const pending = inboxTodos.filter(t => !t.triggered && isTodoTask(t));
  const summary = row.querySelector('.todo-entry-summary');
  if (summary) {
    const first = [...pending].sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0))[0];
    const text = first ? String(first.content || '') : '';
    summary.textContent = text.length > 24 ? text.slice(0, 24) + '…' : (text || t('todo.entryEmpty'));
  }
  const badge = row.querySelector('.fm-row-badge');
  if (badge) {
    const n = pending.length;
    badge.hidden = n <= 0;
    badge.textContent = n > 9 ? '9+' : String(n);
  }
}

/** 入口行副行/徽标就地刷新（updateBadge 同源计数；行未挂树 = no-op）。 */
function refreshTodoEntry() {
  const row = document.querySelector('.fm-todo-entry');
  if (!row) return;
  paintTodoEntry(row);
}

/** Called when session switches — refresh task list */
export function refreshScheduledTasks(sessionId) {
  isCreating = false;
  isCreatingTodo = false;
  editingTodo = null;
  // Session just became available — retry any creates queued while there was
  // no active session (flush no-ops when the queue is empty or WS is down).
  flushPendingCreates();
  if (sessionId) {
    // Show cached tasks instantly, then refresh from server
    tasks = loadCachedTasks(sessionId);
    renderList();
    updateBadge();
    sendWs({ type: 'listScheduledTasks', sessionId: sessionId });
    // 改版批：收件箱跨会话——会话切换同样刷新（聚合内容可能已变）。
    requestTodoInbox();
  } else {
    tasks = [];
    renderList();
    updateBadge();
  }
}
