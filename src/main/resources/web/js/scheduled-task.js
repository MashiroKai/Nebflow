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
// name/description 两段投影（schedule-todo-unify 批 2026-11-06）：**引用**待办面
// 已立的同一条规则（todoText.js，零复制 —— 单实现原则）。
import { splitTodoText } from './todoText.js';
// 液态形变（Liquid Morph）展开/收起腿（单源 = js/motion/liquidMorph.js；本文件只
// 引用 motion/collapse.js 这一层「属性子集 + 一次性过渡」，不写第二份时长/曲线）。
import { wrapCollapseDetail, setCollapse, resetCollapse } from './motion/collapse.js';
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
// 时间滚轮选择器（time-wheel-picker 实施批 2026-11-03，作者令 22:16/22:20）：
// 定时任务的触发时刻从裸 `<input type="datetime-local">` 收编为同款点选面
// （日期列 + 时/分滚轮）。交互单源 = ./timeWheel.js（禁第二份实现）。
import { pointTriggerHTML, paintPointTrigger, attachTimeTriggers, msToLocalInput } from './timeWheel.js';
// 液态形变动效语言单源（create-bottom-restore 批 · 2026-11-06）：新建抽屉开合的
// 时长/曲线/属性白名单只吃这一条（与待办页 js/todoFace.js 的 setSheetOpen 同源
// 同档；本文件不另写毫秒数、不另造曲线）。
import { morphTransition, reducedMotion as lmReducedMotion } from './motion/liquidMorph.js';

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
/** Draft round: bumped whenever a create/edit draft is started. A refresh reuses the open form of the current
 *  round; a reopened or retargeted form belongs to a new round and is built fresh, never mistaken for the old one. */
let draftRound = 0;
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
  const diffDays = Math.round((target.getTime() - today.getTime()) / 86400000);

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
  // Legacy surface cannot safely edit the decision-card JSON payload.
  return t.kind === 'todo' && t.project !== '__decision_card_mvp__';
}

/**
 * name / description（description = **分隔描述**）两段读取（schedule-todo-unify 批
 * 2026-11-06 作者令「定时任务也要按 name description 来添加 点击展开可以查看细节」）。
 *
 * 数据面：后端 `detail` = description（第二段，真源字段）；`content` 仍是注入文本
 * （首行名，无 detail 的存量行用它投影）。**存量行零迁移**——读时投影、不写回：
 *   · 有 detail ⇒ name = content 首行，description = detail；
 *   · 无 detail ⇒ 套 todoText.splitTodoText（首句/24 字窗口，全站同一条投影规则）。
 * 同一条规则已在 `todoText.js` 落地（待办面两段化批 2026-11-05）——本文件只**引用**
 * 不复制（单实现原则）。
 */
function nameOf(t) {
  const split = splitTodoText(nameSourceOf(t));
  return split.name;
}

function descriptionOf(t) {
  if (typeof t.detail === 'string' && t.detail.trim()) return t.detail.trim();
  return splitTodoText(nameSourceOf(t)).detail;
}

/** 首行之外的 content（有 detail 时 description 只读 detail，content 余下不再展示）。 */
function nameSourceOf(t) {
  const raw = String(t.content || '');
  const nl = raw.indexOf('\n');
  return (nl === -1 ? raw : raw.slice(0, nl)).trim();
}

/** 由两段拼回 content（提交/乐观行唯一写口；空 description = 只写 name）。 */
function composeContent(name, description) {
  const n = String(name || '').trim();
  const d = String(description || '').trim();
  return d ? `${n}\n${d}` : n;
}

/**
 * 组好的两行 content ⇒ description（提交/乐观行的读口；与 composeContent 成对 ——
 * descriptionOf/`nameSourceOf` 是**任务对象**的投影（有 detail 读 detail、无
 * detail 才投影），不能用来反解刚拼好的两行串（那会把描述裁丢）。
 */
function descriptionFromComposed(content) {
  const raw = String(content || '');
  const nl = raw.indexOf('\n');
  return nl === -1 ? '' : raw.slice(nl + 1).trim();
}

/** 当前展开的那条（一次一个；点另一条即换位，点自己即收起）。 */
let expandedTaskId = null;

/** 编辑态的定时任务本体（null = 新建）。编辑入口 = 行尾 ✎（待办面已有同形入口）。 */
let editingTask = null;

/** textarea 随内容长高（1–4 行封顶；再长就内部滚 —— 不把抽屉顶爆）。 */
function updateDescAutosize(el) {
  el.style.height = 'auto';
  const line = parseFloat(getComputedStyle(el).lineHeight) || 18;
  el.style.height = Math.min(el.scrollHeight, line * 4 + 8) + 'px';
}
function bindDescAutosize(el) {
  const fit = () => updateDescAutosize(el);
  el.addEventListener('input', fit);
  // 组字期间不重排（中文输入法组合态下 scrollHeight 会抖）。
  el.addEventListener('compositionupdate', fit);
  requestAnimationFrame(fit);
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
  // 【入口收敛批 todo-unify-entry-collapse 2026-11-04】入口行已退役（消息列表
  // 顶部「待办事项」行撤除，唯一入口 = 顶带待办胶囊）⇒ 行上漆/就地刷新腿随之
  // 停跑；函数体（refreshTodoEntry / paintTodoEntry / buildTodoEntryRow）保留，
  // 注释退役。待销账计数仍由 notifyActionableChanged 的聚合面外发（胶囊订阅
  // todoStore 自刷，两路同源 scope:'all' kind=todo）。
  // refreshTodoEntry();
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

  // A refresh keeps the open desktop draft (value, focus, caret). A draft belongs to one round: a form built for
  // an earlier create/edit (another target, or the same one reopened) is rebuilt, never carried over.
  const round = String(draftRound);
  const liveForm = isCreating && !mobileLayerOn() ? /** @type {HTMLElement|null} */ (body.querySelector('.reminder-inline-create')) : null;
  const draftForm = liveForm && liveForm.dataset.draft === round ? liveForm : null;
  const focused = /** @type {HTMLInputElement|HTMLTextAreaElement|null} */ (draftForm && draftForm.contains(document.activeElement) ? document.activeElement : null);
  const selection = focused && 'selectionStart' in focused ? [focused.selectionStart, focused.selectionEnd] : null;
  let formBuilt = false;
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

  // Create form：手机页面态 = 底部抽屉（铺满的列表不再被常驻表单占位，作者令
  // 2026-11-05 17:33）；桌面腿 = 列表尾部流内一行（原样）。
  if (mobileLayerOn()) {
    const sheet = ensureMobileChrome(body.parentElement);
    // 抽屉标题随形态（新建 / 编辑）——同一抽屉不建第二个（main
    // schedule-todo-unify 批 2026-11-06 的既有面；纯 textContent 写，零位移）。
    if (mDrawerTitle) mDrawerTitle.textContent = editingTask ? t('task.editTitle') : t('task.sheetTitle');
    // 首拍面板尚未收编时抽屉只能挂面板内（页面壳还没找到）；面板进页面壳后
    // 用同一锚函数再搬一次 —— 之后恒为页面壳（与待办页同包含块）。
    if (sheet && !mDrawerHosted) {
      const host = drawerAnchor(sheet.parentElement);
      if (host !== sheet.parentElement && host !== document.body) {
        host.append(sheet);
        mDrawerHosted = true;
      }
    }
    // create 开合**不再整面重渲行区**（零位移硬验收，S4 实测驱动 2026-11-06）：
    // 旧写法每次 renderReminderPanel 都 replaceChildren 抽屉体 + setDrawerOpen，
    // 开抽屉的第一次渲染会抽掉行区一段内容 ⇒ 页面壳滚动量收钳 + 标题带收高
    // 级联，整页内容位移（首行 rect 128→78）。现在行区 DOM 在本分支**原样保留**
    // （数据刷新路径照常走上面的行重建），抽屉体只在开新单时填一次、关闭收场
    // 时清（写口在 setDrawerOpen 的收尾计时器）—— 表单还在输入中时到达的
    // listScheduledTasks 刷新帧不再抹掉输入值（旧写法会抹，顺手修掉的暗坑）。
    const wantOpen = !!(isCreating && mDrawerBody);
    const drawerForm = mDrawerBody ? /** @type {HTMLElement|null} */ (mDrawerBody.querySelector('.reminder-inline-create')) : null;
    if (sheet && mDrawerBody && wantOpen && (!drawerForm || drawerForm.dataset.draft !== round)) {
      mDrawerBody.replaceChildren(buildInlineCreate());
      formBuilt = true;
    }
    setDrawerOpen(wantOpen);
  } else if (isCreating) {
    formBuilt = !draftForm;
    body.appendChild(draftForm || buildInlineCreate());
    if (focused) {
      focused.focus({ preventScroll: true });
      if (selection && selection[0] != null && selection[1] != null) focused.setSelectionRange(selection[0], selection[1]);
    }
  }

  if (typeof lucide !== 'undefined') {
    createIconsIn(body);
    if (mDrawerBody) createIconsIn(mDrawerBody);
    if (mFab) createIconsIn(mFab);
  }

  // Focus inline input if creating：表单在两处宿主之一（手机 = 抽屉体，桌面 = 列表
  // 尾部），所以按**当前宿主**取输入件（类选择器，不再走 #reminder-* id 直查 ——
  // 组件契约 R2 计数只许降不许升）。
  if (isCreating && formBuilt) {
    const host = mobileLayerOn() ? mDrawerBody : body;
    const input = host ? /** @type {HTMLInputElement|null} */ (host.querySelector('.reminder-inline-input')) : null;
    // buildInlineCreate fills an editing draft once; a refresh neither rebuilds it nor moves its focus.
    // preventScroll（S4 实测驱动 2026-11-06）：开抽屉的首拍抽屉盒还在
    // translateY(100%) 场外（is-open 下一帧才翻类），此刻 focus 会触发浏览器的
    // focus-scroll 去页面壳上把场外输入件滚进可视区 —— 页面壳 scrollTop 被写、
    // 标题带收高级联、整页内容位移（首行 rect 128→78 实测）。表单落位后本就
    // 在抽屉可视区内，滚不滚都不需要：preventScroll 直接掐掉这条位移源
    // （与待办页 todoFace.js:1941 的手机腿同口径）。
    if (input) requestAnimationFrame(() => input.focus({ preventScroll: true }));
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

/**
 * 列表行（schedule-todo-unify 批 2026-11-06 作者令「定时任务也要按 name description
 * 来添加 点击展开可以查看细节 展开要用液态形变」）：
 *   · **默认行只显 name**（单行省略：文字 trunkate，右侧徽标族 = 项目标签 / 周期 /
 *     时间**贴在行右缘**，同一水平线 —— CSS `.reminder-row-badges`）；
 *   · 点行（或行尾的展开键）= 液态形变展开：宿主同框加内距 + 详情体（description /
 *     触发时间 / 上次触发 / 周期 / 缺卡建议）从宽度 0 缩放进场；再点收起。
 *   打卡圆钮 / ✎ 编辑 / × 取消 的点击各自 stopPropagation，不会误触展开。
 */
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

  // ── 首行：name（唯一常显文本）+ 右缘徽标族（项目 / 周期 / 时间 / 逾期）────────
  const content = document.createElement('div');
  content.className = 'reminder-row-content';

  const titleRow = document.createElement('div');
  titleRow.className = 'reminder-row-title';
  const textSpan = document.createElement('span');
  textSpan.className = 'reminder-row-text';
  textSpan.textContent = nameOf(r) || t('task.detailEmpty');
  titleRow.appendChild(textSpan);

  const badges = document.createElement('div');
  badges.className = 'reminder-row-badges';
  // 项目标签（追加令①）：行内淡色标签（与 repeat badge 同位语言）。
  if (r.project) {
    const proj = document.createElement('span');
    proj.className = 'reminder-proj-tag';
    proj.textContent = String(r.project);
    badges.appendChild(proj);
  }
  // 周期徽标（循环任务才渲染；一次性 = 无徽标 = 克制）。
  const badgeLabel = repeatLabel(r.repeat);
  if (badgeLabel) {
    const badge = document.createElement('span');
    badge.className = 'reminder-repeat-badge';
    badge.dataset.repeat = r.repeat;
    badge.textContent = badgeLabel;
    badges.appendChild(badge);
  }
  // 触发时间（右缘小字；逾期行换红 clock-alert 图案 + 中性等宽时间）。
  const timeSpan = document.createElement('span');
  timeSpan.className = 'reminder-row-time';
  if (isTriggered) {
    timeSpan.textContent = t('task.triggered') + ' ' + formatTriggerTime(r.triggerAt);
  } else {
    const nextTime = r.nextTrigger || r.triggerAt;
    const overdue = isOverdue({ triggered: false, triggerAt: nextTime });
    timeSpan.innerHTML = (overdue ? statusIcon('clock-alert', 11, 'st-err') : '')
      + '<span>' + escapeHtml(formatTriggerTime(nextTime)) + '</span>';
    if (overdue) {
      const tag = document.createElement('span');
      tag.className = 'reminder-overdue-tag';
      tag.innerHTML = statusIcon('clock-alert', 9, 'st-err') + '<span>' + escapeHtml(t('task.overdue')) + '</span>';
      timeSpan.appendChild(tag);
    }
  }
  badges.appendChild(timeSpan);
  titleRow.appendChild(badges);
  content.appendChild(titleRow);

  // ── 详情体（收起态不占行；展开态 = 液态形变缩放进场）─────────────────────────
  const detail = document.createElement('div');
  detail.className = 'reminder-row-detail';
  const desc = descriptionOf(r);
  const descEl = document.createElement('div');
  descEl.className = 'reminder-row-desc';
  descEl.textContent = desc;
  detail.appendChild(descEl);

  const facts = document.createElement('div');
  facts.className = 'reminder-row-facts';
  const addFact = (iconName, label, value) => {
    const f = document.createElement('div');
    f.className = 'reminder-row-fact';
    f.appendChild(lucideIconEl(iconName));
    const lab = document.createElement('span');
    lab.className = 'reminder-row-fact-label';
    lab.textContent = label;
    const val = document.createElement('span');
    val.className = 'reminder-row-fact-value';
    val.textContent = value;
    f.append(lab, val);
    facts.appendChild(f);
  };
  if (isTriggered) addFact('check-check', t('task.triggered'), formatTriggerTime(r.triggerAt));
  else addFact('clock', t('task.triggerTime'), formatTriggerTime(r.nextTrigger || r.triggerAt));
  if (r.lastTriggered) addFact('history', t('task.lastTriggered'), formatTriggerTime(r.lastTriggered));
  if (badgeLabel) addFact('repeat', t('task.repeat'), badgeLabel);
  detail.appendChild(facts);

  // 认知二期产品批③缺卡调整流：连续浮出未销账达阈值的待办，行内附调整建议
  // （不指责，只给默认建议呈作者确认——注意力伦理，不擅自改计划）。
  if (!isTriggered && isTodoTask(r) && (r.missCount || 0) >= 3) {
    const missEl = document.createElement('div');
    missEl.className = 'reminder-miss-suggest';
    missEl.textContent = t('task.missSuggest', { n: r.missCount });
    detail.appendChild(missEl);
  }
  content.appendChild(detail);   // 先入父节点（wrapCollapseDetail 就地包一层缩放遮罩）
  const clip = wrapCollapseDetail(detail);

  // 展开键（行尾；圆钮档=44 视觉 / 命中盒 --ds-hit-box-min）：键盘/读屏可达的
  // 显式开关；鼠标/触屏点整行同样开合。
  const expandBtn = document.createElement('button');
  expandBtn.type = 'button';
  expandBtn.className = 'reminder-expand-btn';
  expandBtn.setAttribute('aria-label', t('task.expand'));
  expandBtn.title = t('task.expand');
  expandBtn.appendChild(lucideIconEl('chevron-down'));

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
    editBtn.appendChild(lucideIconEl('pencil'));
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
  // 定时任务行尾 ✎（schedule-todo-unify 批：「按 name description 来添加」的另一半
  // = 能改；与待办面 ✎ 同一位置同一形态，零第二套编辑入口）。
  if (!isTriggered && !isTodoTask(r)) {
    const editBtn = document.createElement('button');
    editBtn.className = 'reminder-cancel reminder-edit';
    editBtn.type = 'button';
    editBtn.title = t('todo.edit');
    editBtn.dataset.action = 'edit';
    editBtn.dataset.id = r.id;
    editBtn.appendChild(lucideIconEl('pencil'));
    editBtn.setAttribute('aria-label', t('todo.edit'));
    editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      startTaskEdit(r);
    });
    row.appendChild(editBtn);
  }
  row.appendChild(expandBtn);

  // ── 开合接线（单源 = motion/collapse.js；本处只喂元素与闭合值）───────────────
  const open = expandedTaskId === r.id;
  const toggle = () => {
    expandedTaskId = expandedTaskId === r.id ? null : r.id;
    // 面板内同时只有一条展开：先收起其余已展开行（逐行 setCollapse 同一段过渡）。
    for (const other of document.querySelectorAll('.reminder-row.is-expanded')) {
      if (other === row) continue;
      const otherClip = other.querySelector('.lm-collapse-clip');
      if (otherClip) setCollapse({ host: /** @type {HTMLElement} */ (other), clip: /** @type {HTMLElement} */ (otherClip), open: false });
    }
    setCollapse({ host: row, clip, open: expandedTaskId === r.id });
    expandBtn.setAttribute('aria-label', expandedTaskId === r.id ? t('task.collapse') : t('task.expand'));
    expandBtn.title = expandedTaskId === r.id ? t('task.collapse') : t('task.expand');
  };
  expandBtn.addEventListener('click', (e) => { e.stopPropagation(); toggle(); });
  row.addEventListener('click', (e) => {
    // 行内按钮/输入件的点击不触发展开（它们各自 stopPropagation，这里再兜一层）。
    if (/** @type {HTMLElement} */ (e.target).closest('button, input, textarea, a, .tw-trigger, .lm-collapse-clip')) return;
    toggle();
  });
  row.style.cursor = 'pointer';
  row.setAttribute('role', 'button');
  row.tabIndex = 0;
  row.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    if (/** @type {HTMLElement} */ (e.target) !== row) return;
    e.preventDefault();
    toggle();
  });
  if (open) {
    // 重渲后保持展开（例如创建/删除别条后 renderList 重建）：直接落终态，不重播。
    expandedTaskId = r.id;
    setCollapse({ host: row, clip, open: true });
    resetCollapse({ host: row, clip });
  } else {
    resetCollapse({ host: row, clip });
  }

  return row;
}

function buildInlineCreate() {
  const wrap = document.createElement('div');
  wrap.className = 'reminder-inline-create';
  wrap.id = 'reminder-inline-create';
  wrap.dataset.target = editingTask ? String(editingTask.id) : '';
  wrap.dataset.draft = String(draftRound);

  const dtStr = toLocalDatetimeString(defaultTriggerAt());
  const [dtDate, dtTime] = dtStr.split('T');

  // 两段表单（schedule-todo-unify 批 2026-11-06）：name（标题，必填）+ description
  // （描述，可空，textarea 一行起）；确认类按钮（取消 / 保存）统一右对齐（见文末
  // .reminder-form-actions）。编辑态预填两段。
  wrap.innerHTML = `
    <div class="reminder-inline-main">
      <span class="reminder-circle ghost"></span>
      <input type="text" class="reminder-inline-input" id="reminder-inline-input"
        placeholder="${t('task.inputPlaceholder')}" autocomplete="off">
    </div>
    <div class="reminder-inline-desc-row">
      <i data-lucide="align-left"></i>
      <textarea class="reminder-inline-desc" rows="1"
        placeholder="${t('task.descPlaceholder')}"></textarea>
    </div>
    <div class="reminder-inline-detail">
      <div class="reminder-time-row">
        <i data-lucide="clock"></i>
        ${pointTriggerHTML({ date: dtDate, time: dtTime, label: t('task.triggerTime') })}
        <input type="hidden" id="reminder-time-input" value="${dtStr}">
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
    </div>
    <div class="reminder-form-actions">
      <button type="button" class="reminder-form-btn" data-role="cancel">${t('task.sheetCancel')}</button>
      <button type="button" class="reminder-form-btn reminder-form-btn-primary" data-role="confirm">${editingTask ? t('task.save') : t('task.sheetDone')}</button>
    </div>`;

  // Bind events
  const input = /** @type {HTMLInputElement|null} */ (wrap.querySelector('#reminder-inline-input'));
  // 描述框用**类选择器**取（组件契约 R2 只许降：不再新开一个 #reminder- id 直查）。
  // 编辑态的 description 预填由 updateDescAutosize() 在填入后调一次（同一元素同一
  // id 两处口径会分叉 —— 预填与自动长高合成一个出口）。
  const descInput = /** @type {HTMLTextAreaElement|null} */ (wrap.querySelector('.reminder-inline-desc'));
  const timeInput = /** @type {HTMLInputElement|null} */ (wrap.querySelector('#reminder-time-input'));
  if (input && editingTask) input.value = nameOf(editingTask);
  if (descInput && editingTask) descInput.value = descriptionOf(editingTask);
  if (descInput) bindDescAutosize(descInput);
  wrap.querySelector('[data-role="cancel"]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    cancelInlineCreate();
  });
  wrap.querySelector('[data-role="confirm"]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    saveInlineTask();
  });
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

  // 触发时刻 = 时间滚轮点选面（time-wheel-picker 批 2026-11-03）：面板确认后
  // 把 date/time 回写成 `#reminder-time-input` 的 datetime-local 串，下游
  // saveInlineTask 的 `new Date(value).getTime()` 腿保持不变（单一写入点）。
  // 旧的 `focus → 刷 min` 现已无意义（min 约束随原生控件一起退役；过期时刻由
  // saveInlineTask 的 ≤now+60s 自动顺延兜底，语义不变）。
  if (wrap.querySelector('[data-tw-point]')) {
    attachTimeTriggers(wrap, {
      onPointChange: (btn) => {
        if (!timeInput) return;
        const ms = new Date(`${btn.dataset.date}T${btn.dataset.time}`).getTime();
        if (!Number.isNaN(ms)) timeInput.value = msToLocalInput(ms);
      },
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
    </div>
    <div class="reminder-form-actions">
      <button type="button" class="reminder-form-btn" data-role="cancel">${t('task.sheetCancel')}</button>
      <button type="button" class="reminder-form-btn reminder-form-btn-primary" data-role="confirm">${editing ? t('task.save') : t('task.sheetDone')}</button>
    </div>`;

  // 确认类按钮统一右对齐（schedule-todo-unify 批：与定时任务面同一组按钮规格）。
  wrap.querySelector('[data-role="cancel"]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    cancelTodoInlineCreate();
  });
  wrap.querySelector('[data-role="confirm"]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    editing ? saveTodoEdit() : saveTodoInline();
  });
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
 *
 * ⛔ 【整面退役】todo-unify-entry-collapse 2026-11-04 —— #todo-page 处置 = (a)
 *   整面退役（DOM 保留、不可达），理由：
 *   ① 入口收敛批把两个入口都撤了（消息列表入口行 / 手机 pushPage('todo')），
 *      本页已经没有任何触发器；
 *   ② 同一个「待办面」已有**更新的完整实现** = 顶带胶囊 + 展开面板
 *      （js/todoFace.js：列表/行内新建/完成三段动画/删除/重要度四签/分组/
 *      逾期红面/计数翻牌），且它是待办体系统一批（2026-11-04）的真源适配面
 *      （todoStore.js ↔ ScheduledTaskStore）；本页渲染核仍是 scheduled-task.js
 *      的旧收件箱行渲染（inboxTodos + 独立缓存），两套并存必然分叉 →
 *      与其留一个可达但只覆盖旧语义的面，不如整面退到不可达；
 *   ③ 选 (b)「收编为胶囊展开面板的全屏形态」会让同一份 DOM 拥有两套状态机
 *      （scheduled-task 的 todoPageOpen / todoFace 的 expanded），且是对②已有
 *      完整面的重复建设。
 *   DOM（index.html #todo-page）、CSS（css/todo-page.css）与本文件渲染核
 *   **原样保留**（本仓退役惯例：不物理删成半成品），导航注册见
 *   js/mobile/navController.js 的同批退役注释。
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
  draftRound += 1;
  isCreating = true;
  editingTask = null;       // 新建 = 清编辑态（否则抽屉标题/预填会带着上一条）
  currentRepeat = 'once';   // fresh form → one-shot by default
  renderList();
  revealInlineCreate();
}

/**
 * 桌面腿：行内创建表单落在**列表尾部**（流内一行）。列表非空时它在滚动体可见区
 * 之外 —— 开单后一次「即时」滚动把它带到位（不播动画：面板体的
 * scroll-behavior:smooth 只对用户发起的滚动友好，开单这一下必须是即刻的终态）。
 * 手机层是底部抽屉皮，表单本就在抽屉可视区内 ⇒ 本函数在手机层直接返回
 *（抽屉批的「新建零位移」硬验收不受影响）。
 */
function revealInlineCreate() {
  if (mobileLayerOn()) return;
  const body = $('#reminder-panel .reminder-panel-body');
  if (!body || !body.querySelector('.reminder-inline-create')) return;
  // 滚到列表尾部 = 表单整块（含「取消 / 创建」）进可视区；体不可滚时是无操作。
  body.scrollTo({ top: body.scrollHeight, behavior: 'instant' });
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
  editingTask = null;
  renderList();
}

/** 行尾 ✎：编辑定时任务的 name/description（表单复用 buildInlineCreate，预填两段）。 */
function startTaskEdit(r) {
  draftRound += 1;
  editingTask = r;
  isCreating = true;
  currentRepeat = r.repeat || 'once';
  renderList();
  revealInlineCreate();
}

/* ── 手机页面态：右下悬浮加号 + 新建抽屉（todo-fullbleed 批 · 作者令 2026-11-05
      17:33「手机端的这个待办和定时任务，要像 apple 的这个提醒事项一样，铺满整个
      内容，加号按钮使用我们 canvas 同款的按钮风格放在右下角悬浮」）────────────
   两个页面共用同一套规范（与 js/todoFace.js 的 buildFab/buildCreateSheet 逐条对齐，
   类名同一族 `.mf-drawer*` / `.g2-fab`）：
     · 加号 = `.g2-fab` 单源材质/尺寸/六态（css/glass.css 圆钮级浮件档，与 Canvas
       工具钮同档 48px）+ `.reminder-fab-add` 只声明落位（右下角）；
     · 新建表单从「列表尾部常驻一行」改为「点加号弹底部抽屉」，保存后回到铺满列表；
     · 桌面腿零改动（#reminder-panel 在桌面是 360px 顶栏下拉，入口仍是头 + 钮；
       本组节点的可见性由 css/mobileLayer.css 的 body.mobile-layer 规则兜底）。 */
/** 手机交互层在场判定（断点真值 = body.mobile-layer，与全站同一判据）。 */
function mobileLayerOn() {
  return document.body.classList.contains('mobile-layer');
}

/** @type {HTMLElement|null} */
let mDrawer = null;
/** @type {HTMLElement|null} */
let mDrawerBody = null;
/** @type {HTMLElement|null} */
let mFab = null;
/** @type {HTMLElement|null} */
let mDrawerBox = null;
let mDrawerTitle = null;
/** 抽屉是否已挂到页面壳（首拍面板还没收编时退回面板内，收编后再搬一次）。 */
let mDrawerHosted = false;
/** 抽屉开合定时器（收尾 hidden 用；单一）。 */
let mDrawerTimer = 0;

/** 新建抽屉开合（唯一显隐写口）。`hidden` 不能参与过渡（display:none）⇒ 先摘
 *  hidden、下一帧再翻 `.is-open`；起点/终点几何全在 css/mobileLayer.css
 *  （`.mf-drawer-box`：translateY(100%) → 0）；过渡串由液态形变单源生成
 *  （属性白名单 transform/opacity、slow 档 = 大位移腿、reduced ⇒ 1ms）。
 *  create-bottom-restore 批（作者裁定 2026-11-06 09:44）：与待办页
 *  js/todoFace.js 的 setSheetOpen 同形 —— 位移只吃 transform ⇒ 开合零 reflow，
 *  列表/页体滚动读数不动。 */
function setDrawerOpen(open) {
  const sheet = mDrawer;
  const box = mDrawerBox;
  if (!sheet || !box) return;
  clearTimeout(mDrawerTimer);
  const reduced = lmReducedMotion();
  box.style.transition = morphTransition(['transform', 'opacity'], { duration: 'slow', reduced });
  if (open) {
    // 钉住可视框（S1 实测驱动，2026-11-06）：页面壳 `.mobile-page` 是
    // overflow:hidden 的**程序滚容器**（大标题收高级联 / 聚焦滚动会给它留
    // scrollTop，实测过 206），而抽屉 inset:0 锚在**内容坐标** —— 滚动非零时
    // 整块抽屉随内容上提（盒底 722 ≠ 页壳底 750）。开启瞬间以
    // translateY(scrollTop) 把抽屉平移回可视框；壳 overflow:hidden 锁死用户
    // 滚动 ⇒ 开启期间位移源恒定，钉一次即全程贴合。只写 transform ⇒ 零 reflow
    // （与下方 box 位移腿同一条白名单）。关闭时复位（关闭后抽屉不可见，余量
    // 无所谓）。与待办页 js/todoFace.js 的 setSheetOpen 同形同注。
    const page = sheet.parentElement && sheet.parentElement.closest
      ? sheet.parentElement.closest('.mobile-page') : null;
    const st = page ? page.scrollTop : 0;
    sheet.style.transform = st > 0 ? `translateY(${st}px)` : '';
    const wasHidden = sheet.hidden;
    sheet.hidden = false;
    if (wasHidden || !box.classList.contains('is-open')) {
      box.classList.remove('is-open');
      void box.offsetHeight; // 强制回流：display 翻转后的起始帧必须被采纳
      requestAnimationFrame(() => { if (isCreating) box.classList.add('is-open'); });
    }
    return;
  }
  sheet.style.transform = '';
  if (sheet.hidden) return;
  box.classList.remove('is-open');
  mDrawerTimer = setTimeout(() => {
    sheet.hidden = true;
    // 收场清体（下开重建）：表单值随抽屉退场丢弃（与底部抽屉交互语义一致：
    // 「取消」即弃稿；save 路径在触发 close 前已同步取走值）。
    if (mDrawerBody) mDrawerBody.replaceChildren();
  }, reduced ? 8 : 420);
}


/** `<i data-lucide>` 图标件（与站内 lucide 装配口径一致；纯建节点，不渲）。 */
function lucideIconEl(name) {
  const i = document.createElement('i');
  i.setAttribute('data-lucide', name);
  return i;
}

/** 新建抽屉的挂载点 = 面板所在的**页面壳** `.mobile-page`（手机腿它是
 *  `position:absolute` + `overflow:hidden` 的页面容器，与待办页 `.mf-drawer`
 *  的包含块逐值相同）。页面壳还没被收编（面板仍在原 overlay 位）时退回面板本身
 *  —— 此时抽屉不可见，下一次渲染面板已进页面壳，自然落到同一锚上。
 *  为什么不挂 `body`：`body` 上带 `canvas-overlay` 一族浮层类，按视口铺底会跨到
 *  页面栈之外（2026-11-06 实测盒底 812 ≠ 页壳底 750）。 */
function drawerAnchor(panel) {
  if (panel && panel.closest) {
    const page = panel.closest('.mobile-page');
    if (page) return page;
  }
  return panel || document.body;
}

/** 惰性建「悬浮加号 + 底部抽屉」一组（面板节点在，就只建一次）。
 *  `panel` 由调用点**以节点传入**（renderReminderPanel 手里已有面板体/其父），
 *  本函数不再拿 id 反查 —— 组件契约 R2「禁跨面 DOM 直查」的计数只许降不许升。 */
function ensureMobileChrome(panel) {
  if (!panel) return null;
  if (mDrawer && mDrawer.isConnected) return mDrawer;

  const fab = document.createElement('button');
  fab.type = 'button';
  fab.className = 'g2-fab reminder-fab-add';
  fab.title = t('task.sheetTitle');
  fab.setAttribute('aria-label', t('task.sheetTitle'));
  fab.appendChild(lucideIconEl('plus'));
  fab.addEventListener('click', (e) => {
    e.stopPropagation();
    if (isCreating) saveInlineTask();
    else startInlineCreate();
  });

  const sheet = document.createElement('div');
  sheet.className = 'mf-drawer';
  sheet.hidden = true;
  sheet.setAttribute('role', 'dialog');
  sheet.setAttribute('aria-modal', 'true');
  sheet.setAttribute('aria-label', t('task.sheetTitle'));

  const scrim = document.createElement('div');
  scrim.className = 'mf-drawer-scrim';
  scrim.addEventListener('click', () => cancelInlineCreate());

  const box = document.createElement('div');
  box.className = 'mf-drawer-box';
  const head = document.createElement('div');
  head.className = 'mf-drawer-head';
  const name = document.createElement('span');
  name.className = 'mf-drawer-title';
  // 标题随形态（新建 / 编辑）——同一个抽屉，不加第二个抽屉。
  name.textContent = t('task.sheetTitle');
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'mf-drawer-cancel-btn';
  cancel.textContent = t('task.sheetCancel');
  cancel.addEventListener('click', () => cancelInlineCreate());
  const done = document.createElement('button');
  done.type = 'button';
  done.className = 'mf-drawer-done-btn';
  done.textContent = t('task.sheetDone');
  done.addEventListener('click', () => saveInlineTask());
  head.append(name, cancel, done);
  mDrawerTitle = name;

  mDrawerBody = document.createElement('div');
  mDrawerBody.className = 'mf-drawer-body';
  box.append(head, mDrawerBody);
  sheet.append(scrim, box);
  // 加号留在面板里（落位吃页面壳）；抽屉挂到页面壳上、而不是面板里 ——
  // 面板在桌面腿是 `position:absolute`（css/reminder.css:55-78），抽屉若挂在
  // 面板内会被它抢走包含块：桌面样式带过来的 top/right/width 把整块抽屉钉在
  // 360px 的小盒里（2026-11-06 spec S1 实测盒底 722 ≠ 页壳底 750）。
  // 挂到页面壳 = 与待办页的包含块逐值相同（js/todoFace.js 的 .mf-drawer 同源）。
  panel.append(fab);
  const host = drawerAnchor(panel);
  host.append(sheet);
  if (host !== panel) mDrawerHosted = true;   // 已落在页面壳上：后续不再搬
  mDrawer = sheet;
  mDrawerBox = box;
  mFab = fab;
  return sheet;
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
  const description = descriptionOf({ content });
  if (wsReady()) {
    sendWs({
      type: 'updateScheduledTask',
      sessionId: sid,
      id: target.id,
      content,
      // 两段化（schedule-todo-unify 批）：description 走真源字段 detail；
      // project "" = 清空归属（未分组）——帧处理器字段级 patch。
      detail: description,
      project
    });
  } else {
    addNotification('task', t('task.queuedOffline'), { dismissAfter: 15000 });
  }
  // 乐观收敛（WS 未就绪也改本地——回执帧/重连 re-list 会纠正）。
  target.content = content;
  target.detail = description || null;
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
  // 定时任务面两段化（schedule-todo-unify 批 2026-11-06）：description → 真源
  // detail 字段；content 仍是「首行名 + 描述」的注入文本（agent 面读同一句）。
  // 面语义由调用点的 kind 决定：kind=todo → 本来就是两段面（旧口径）；其余
  //（定时任务面 / 离线重放）一律带 detail（两段面已是本仓默认）。kind 缺省 = 面分支。
  {
    const desc = descriptionFromComposed(content);
    if (desc) payload.detail = desc;
  }
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
    detail: descriptionFromComposed(content) || null,
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
  const input = /** @type {HTMLInputElement|null} */ (document.querySelector('#reminder-inline-input'));
  // 描述框取当前宿主里的那一件（表单挂在两处宿主之一：桌面 = 列表尾部、手机 =
  // 抽屉体；按可见的那件取值 —— 与上面 name 输入同一读面，不跨面直查 id）。
  const descInput = /** @type {HTMLTextAreaElement|null} */ (
    input?.closest('.reminder-inline-create')?.querySelector('.reminder-inline-desc') || null
  );
  const timeInput = /** @type {HTMLInputElement|null} */ (document.querySelector('#reminder-time-input'));

  const name = input ? input.value.trim() : '';
  const description = descInput ? descInput.value.trim() : '';
  if (!name) {
    // 名称必填（description 可空）：就地提示 + 焦点回名称框，不关表单（不丢用户输入）。
    addNotification('task', t('task.nameRequired'), { dismissAfter: 4000 });
    input?.focus();
    return;
  }
  const content = composeContent(name, description);

  // 编辑态（行尾 ✎ 进入）：只改 name/description 两段（updateScheduledTask 字段级
  // patch：content = 首行名 + detail = description）。时间/周期不在此面改。
  if (editingTask) {
    const target = editingTask;
    if (wsReady()) {
      sendWs({
        type: 'updateScheduledTask',
        sessionId: target.sessionId || state.activeSessionId,
        id: target.id,
        content,
        detail: description,
      });
    } else {
      addNotification('task', t('task.queuedOffline'), { dismissAfter: 15000 });
    }
    target.content = content;
    target.detail = description || null;
    editingTask = null;
    isCreating = false;
    renderList();
    return;
  }

  if (!timeInput) return;
  const triggerAt = new Date(timeInput.value).getTime();
  if (!triggerAt || isNaN(triggerAt)) {
    // 触发时刻已由滚轮面维护（值恒合法）；兜底把焦点交给那颗触发钮，
    // hidden 输入不可聚焦。
    /** @type {HTMLElement|null} */ (document.querySelector('#reminder-inline-create [data-tw-point]'))?.focus();
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
    pendingCreates.push({ content: content, description: description, triggerAt: effectiveTriggerAt, repeat: currentRepeat });
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
    tasks = (msg.tasks || []).filter(t => t.project !== '__decision_card_mvp__');
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
      if (e.target.closest('button, input, textarea, a, .reminder-row')) return;
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
    const hit = /** @type {Element} */ (e.target);
    // `.mf-drawer` 并入「面内」口径（S5 实测驱动 2026-11-06）：手机页抽屉挂在
    // **页面壳**上（drawerAnchor：面板在桌面腿是 absolute，抽屉挂面板内会被抢
    // 包含块钉进 360px 小盒）——面板 contains() 对它恒 false，点「取消/完成」
    // 会被这里误读成面外点击 ⇒ closePanel 摘 .open ⇒ navController CLOSE_WATCH
    // 对账弹页（reminder 页整页退场）。抽屉与面板同属提醒面 ⇒ 与 #reminder-btn
    // 同一条豁免；桌面浮层腿抽屉也在面板内（contains 命中），本豁免对桌面无感。
    if (panel && panelOpen && !panel.contains(hit) && !hit.closest('#reminder-btn')
        && !hit.closest('.mf-drawer')) {
      closePanel();
    }
    // 改版批：待办页同款 outside-close（手机层页被收编进页面体且满铺——
    // 页内点击恒 contains，不会误关；栈返回由 navController 管）。
    const todoPage = $('#todo-page');
    if (todoPage && todoPageOpen && !todoPage.contains(hit) && !hit.closest('.fm-todo-entry')) {
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
 *
 * ⛔ 【退役】入口收敛批 todo-unify-entry-collapse 2026-11-04（作者口径：
 *   待办入口收敛到胶囊一处）。本函数**已无任何调用方**（messages.js
 *   renderListInner 的 prepend 已注释、import 已撤），代码按本仓惯例保留
 *   不物理删除，仅注释退役。承载信息去向：
 *   · 待销账待办数徽标 ⇒ 胶囊计数节点 `.tfl-cap-faces`（css/todo-face.css
 *     「L2 标题计数」块；展开时同一节点搬进面板头 `.tfl-header-count`）；
 *   · 最早一条待办摘录 ⇒ 胶囊展开面板列表首行（同源 scope:'all' 收件箱）；
 *   · 点击开 #todo-page 的入口 ⇒ 面已整面退役（见 openTodoPage 注记）。
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

/** 入口行副行/徽标就地刷新（updateBadge 同源计数；行未挂树 = no-op）。
 *  ⛔ 【退役】todo-unify-entry-collapse 2026-11-04：入口行撤除 ⇒ 调用方已注释
 *  （updateBadge）。行不存在的查询恒 null，本函数如今恒 no-op；保留存档。 */
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
