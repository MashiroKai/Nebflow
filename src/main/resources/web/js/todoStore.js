// todoStore.js — 待办**真源适配器**（待办体系统一批 · 前端数据面换血，2026-11-04）
//
// 换血前（ReminderIsland 复刻批 2026-11-02）：本文件 = 261 行纯内存 mock 仓，
// 模块加载即 `seed()` 注入 8 条假待办，写口只改数组（刷新即回滚）。
// 换血后（本批）：本文件 = **后端真源适配器**。
//   · 读：WS `scheduledTaskList {scope:'all'}`（跨会话收件箱回执）→ 映射成既有
//     读口形状（id / title(=content) / notes / due(=triggerAt) / priority /
//     completed / createdAt / tagId），读口签名（visibleItems / openCount /
//     overdueCount / tags / tagById / isAllView / activeTagId /
//     selectedPriorities）与常量导出**逐字不变** ⇒ 渲染层（todoFace.js /
//     taskPanel.js）零改动。
//   · 写：createItem / updateItem / completeItem / uncompleteItem / removeItem
//     一律发 WS 帧 + **乐观更新** + 帧回执（`scheduledTask*` 广播）收敛；
//     服务端显式拒绝 ⇒ 回滚 + console.error（**不静默吞**）；另有一次去抖刷新
//     兜底（跨会话行的广播会被 ws.js 的会话过滤器丢掉，靠刷新收口）。
//   · 排序：真源面 = 后端 `ScheduledTask.todoSortKey` 的**复算**
//     （priority 降序 → due(=triggerAt) 升序 → createdAt 升序 → id），
//     前端不引入任何"只有前端知道"的顺序。
//
// 真源契约（帧表 / 行字段 / 兼容策略）：
//   reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/backend/CONTRACT.md
//   代码：src/main/scala/nebflow/{core/scheduler,gateway/WsTasksWorkspaceHandlers}.scala
//
// ── 字段落库面（诚实申报；机器可读副本 = todoStore.persistence）─────────────
//   · **真落库**：title ↔ content、priority ↔ priority、completed ↔ completedAt、
//     tagId ↔ project（后端没有 tag 实体，分组名 = 项目名，见下方「标签面」）。
//   · **只读真源**：due ↔ triggerAt —— 引擎算出的「下次浮出时刻」，客户端不可写
//     （`createScheduledTask(kind:todo)` 由服务端 `nextTodoSurfaceAt` 决定，
//     `updateScheduledTask` 的 patch 面也没有 triggerAt）。本地改 due 只影响当前
//     页面，下一次真源刷新即回归后端值。
//   · **仅本地**：notes（后端模型无 notes 字段）、分组色板与 chips 顺序。
//
// ── 标签面（tag）处置决定（本批明确申报，不许假装落库）─────────────────────
//   后端 `ScheduledTask` 只有 `project: Option[String]`，**没有 tag 实体**
//   （无 tag 表、无颜色、无顺序、无增删帧）。本批取"降级 + 投影"路线：
//     ① 分组 = `project` 字段的**本地投影**：每个出现过的 project 值 ⇒ 一个分组
//        （id = `g:<project>`，名 = project），分组成员即该 project 的行 ——
//        这一层是真源派生，不是假数据；
//     ② 把行挂到分组 / 新建分组下的行 / 解散分组 = 发
//        `updateScheduledTask {project}` / `createScheduledTask {project}` —— 真落库；
//     ③ 分组**颜色与 chips 顺序**纯展示、只在本页有效（后端无对应字段，明确申报）；
//        重命名分组 = 逐行改 project（真落库）+ 本地改名，颜色/顺序不动。
//   老内存态的「删标签连带删条目」不再保留：真源行不该因为分组解散被销毁。
//
// ── 测试缝 ────────────────────────────────────────────────────────────────
//   `todoStore.__seed({tagList, itemList, order})` = **夹具模式**（fixture mode）：
//   照旧把内存摊成给定夹具、退化为纯本地行为（不拉真源、不发写帧），既有
//   shots/几何 spec 的数据注入腿与旧排序语义原样可用。
//   `todoStore.__live()` 退出夹具模式并重新拉真源（本批 spec / 面板节点可用）。

import state from './state.js';
import { key } from './branding.js';
import { sendWs, onMessage, onReconnect } from './ws.js';
import { projectTodoText } from './todoText.js';

/** 标签色板（ReminderIsland ReminderConfig.calendarColorPresets 十色逐值）。
 *  真源无 tag 颜色字段 ⇒ 只用于本地展示（按分组名确定性取色）。 */
export const TAG_COLOR_PRESETS = [
  'rgb(255, 61, 61)',    // red    (1.0, 0.24, 0.24)
  'rgb(255, 148, 0)',    // orange (1.0, 0.58, 0.0)
  'rgb(255, 204, 0)',    // yellow (1.0, 0.8, 0.0)
  'rgb(77, 217, 102)',   // green  (0.3, 0.85, 0.4)
  'rgb(0, 204, 204)',    // cyan   (0.0, 0.8, 0.8)
  'rgb(0, 122, 255)',    // blue   (0.0, 0.48, 1.0)
  'rgb(89, 89, 217)',    // indigo (0.35, 0.35, 0.85)
  'rgb(153, 77, 217)',   // purple (0.6, 0.3, 0.85)
  'rgb(217, 77, 153)',   // pink   (0.85, 0.3, 0.6)
  'rgb(143, 143, 148)',  // gray   (0.56, 0.56, 0.58)
];

/** 重要度四色（ReminderIsland priorityColors：None 白 60% / Low cyan /
 *  Med orange / High red；系统色等价值，亮暗同值——数据色不随主题翻转）。 */
export const PRIORITY_COLORS = {
  0: 'rgba(255, 255, 255, 0.6)',
  1: 'rgb(50, 173, 230)',   // system cyan
  2: 'rgb(255, 149, 0)',    // system orange
  3: 'rgb(255, 59, 48)',    // system red
};

export const PRIORITY_LABEL_KEYS = ['todoface.prioNone', 'todoface.prioLow', 'todoface.prioMed', 'todoface.prioHigh'];

/** 新建分组的默认色。 */
const DEFAULT_TAG_COLOR = TAG_COLOR_PRESETS[5];

/** 服务端帧型（与 WsTasksWorkspaceHandlers 逐字对齐）。 */
const FRAME = {
  list: 'listScheduledTasks',
  create: 'createScheduledTask',
  update: 'updateScheduledTask',
  complete: 'completeTodo',
  uncomplete: 'uncompleteTodo',
  remove: 'deleteScheduledTask',
};

/** 后端 ask/permission 镜像行（`name` = ask-<id> / perm-<id>）不属人待办面：
 *  它们的销账归 ask/permission 流（后端 completeTodo 会把镜像行算进周报），
 *  收件箱只认人建待办（与本仓 `js/scheduled-task.js` 的镜像行零圆钮同义）。 */
const MIRROR_NAME_RE = /^(ask|perm)-/;

/** 写帧去抖刷新间隔（跨会话广播被 ws.js 会话过滤器丢弃时的收敛兜底）。 */
const REFRESH_DEBOUNCE_MS = 400;
/** 会话 id 未就绪时的重试节拍/上限（有界，成功即停）。 */
const SID_RETRY_MS = 250;
const SID_RETRY_MAX = 40;

// ── 状态 ──────────────────────────────────────────────────────────────────

/** @type {Array<{id:string,name:string,color:string,local?:boolean}>} 分组表
 *  （真源面 = project 投影；夹具面 = __seed 给定）。 */
let tags = [];
/** 行形状单点（读口 + 乐观行 + 真源映射三处同形；改字段先改这里）。
 *
 *  两段化（待办结构化批 2026-11-05）：行正文 = **name（短标题，列表行/引用/# 的
 *  键）+ detail（具体内容，折叠区）**。真源两段在后端 `ScheduledTask` 的
 *  name/detail 键；旧行只有 content 一段 ⇒ 由 todoText.projectTodoText 读时
 *  投影（零写、可逆、零丢失）。
 *
 *  `title` / `notes` 是**兼容别名**（枚举 getter/setter，指向 name/detail）：
 *  既有渲染层与 shots/spec 逐字可用，新代码只用 name/detail —— 一份数据两个
 *  名字，不会出现两处不同步。 */
/** @typedef {{id:string,tagId:string|null,name:string,detail:string,due:number|null,
 *   priority:number,completed:boolean,completedAt:number|null,createdAt:number,
 *   sessionId:string,project:string|null,title:string,notes:string}} TodoRow */

/**
 * 造一行（name/detail 为唯一存储面；title/notes 是只读别名视图）。
 * @param {object} fields 行字段
 * @returns {any}
 */
function makeRow(fields) {
  const row = { ...fields };
  Object.defineProperty(row, 'title', {
    get: () => row.name,
    set: (v) => { row.name = String(v == null ? '' : v); },
    enumerable: true,
    configurable: true,
  });
  Object.defineProperty(row, 'notes', {
    get: () => row.detail,
    set: (v) => { row.detail = String(v == null ? '' : v); },
    enumerable: true,
    configurable: true,
  });
  return row;
}
/** @type {Array<TodoRow>} */
let items = [];
/** 分组 chips 行序（本地展示态；后端无顺序字段）。 @type {string[]} */
let tagOrder = [];
/** 当前浏览分组（null = 总条目 All）。 @type {string|null} */
let activeTagId = null;
/** 重要度筛选（空集 = 不过滤）。 @type {Set<number>} */
const selectedPriorities = new Set();

/** 夹具模式（__seed 置位）：纯本地，不拉真源、不发写帧、保留旧排序语义。 */
let fixtureMode = false;
/** 归档面开关（refresh({includeCompleted:true}) 置位）：拉取面含已完成行。 */
let wantCompleted = false;
/** 首拉是否已发出（惰性自动拉：首次 subscribe / 首次读口）。 */
let pullStarted = false;
/** 真源回执序号（诊断读数；每次 list 回执 +1）。 */
let serverSeq = 0;

let uidSeed = 0;
function uid(prefix) { return `${prefix}${++uidSeed}_${Date.now().toString(36)}`; }

const listeners = new Set();
function emit() { for (const fn of listeners) { try { fn(); } catch { /* listener 断链不传染 */ } } }

function clampPriority(p) { return Math.min(3, Math.max(0, (p | 0) || 0)); }
function currentSessionId() { return (state && state.activeSessionId) || ''; }

/** 分组 id（真源面 = `g:<project>`；夹具面沿用 __seed 给定 id）。 */
function groupId(project) { return `g:${project}`; }

/** 分组名 → 确定性色（后端无颜色字段；同名恒同色，刷新不跳色）。 */
function colorForName(name) {
  let h = 0;
  const s = String(name || '');
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 100000;
  return TAG_COLOR_PRESETS[h % TAG_COLOR_PRESETS.length];
}

const warnedFields = new Set();
/** 未落库字段的显式申报（一次/字段）：本地生效但不发帧，绝不假装已落库。 */
function warnUnpersisted(field, why) {
  if (warnedFields.has(field)) return;
  warnedFields.add(field);
  console.warn(`[todoStore] field "${field}" is local-only (not persisted): ${why}`);
}

// ── 标签本地叠加层（作者报障批 2026-11-07）─────────────────────────────────
//
// 为什么需要：真源 `ScheduledTask` 只有 `project`，**没有 tag 实体**（无颜色、
// 无顺序、无增删帧）⇒ 分组表原先是纯内存投影，刷新后 ① 本地新建但还没有行的
// 分组直接消失、② 用户选的色回落到 `colorForName(名)` 的哈希色。作者报障的
// 「添加标签的窗口」正是选色的第一现场，两者都会被读成「保存了没生效」。
//
// 落点与边界：本机 localStorage（键走 branding 命名空间，`key()` 单源），与真源
// 派生组**合并**而不覆盖 —— 存在性的权威仍是真源（有行 = 有组），本层只补
// 颜色 / 顺序 / 空分组这三件后端没有的展示事实。夹具模式（`__seed`）整层不参与，
// 既有 shots/spec 的注入面逐字不变。
const TAG_OVERLAY_KEY = key('todo_tags_local');

/** @typedef {{v:number, colors:Object<string,string>, empties:Array<{name:string,color:string}>, order:string[]}} TagOverlay */
/** @type {TagOverlay|null} */
let tagOverlay = null;

/** 读叠加层（坏 JSON / 隐私模式一律退回空层，绝不抛）。 */
function readTagOverlay() {
  if (tagOverlay) return tagOverlay;
  tagOverlay = { v: 1, colors: {}, empties: [], order: [] };
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(TAG_OVERLAY_KEY) : null;
    if (raw) {
      const p = JSON.parse(raw);
      if (p && typeof p === 'object') {
        if (p.colors && typeof p.colors === 'object' && !Array.isArray(p.colors)) tagOverlay.colors = p.colors;
        if (Array.isArray(p.empties)) {
          tagOverlay.empties = p.empties
            .filter((x) => x && typeof x.name === 'string' && x.name)
            .map((x) => ({ name: x.name, color: typeof x.color === 'string' ? x.color : '' }));
        }
        if (Array.isArray(p.order)) tagOverlay.order = p.order.filter((x) => typeof x === 'string' && x);
      }
    }
  } catch { /* localStorage 不可用 ⇒ 纯内存（行为退回本批之前） */ }
  return tagOverlay;
}

/** 写叠加层（尽力而为；写不进去不影响本次会话）。 */
function writeTagOverlay() {
  if (!tagOverlay) return;
  try {
    localStorage.setItem(TAG_OVERLAY_KEY, JSON.stringify(tagOverlay));
  } catch { /* quota / 隐私模式：忽略 */ }
}

/** 叠加层生效：颜色与顺序按名对齐到当前分组表（未知名自然忽略）。 */
function applyTagOverlay() {
  const ov = readTagOverlay();
  for (const t of tags) {
    const c = ov.colors[t.name];
    if (c) t.color = c;
  }
  if (!ov.order.length) return;
  const rank = new Map(ov.order.map((n, i) => [n, i]));
  const known = tagOrder.filter((id) => {
    const t = tags.find((x) => x.id === id);
    return t && rank.has(t.name);
  });
  const rest = tagOrder.filter((id) => !known.includes(id));
  known.sort((a, b) => {
    const ta = tags.find((x) => x.id === a);
    const tb = tags.find((x) => x.id === b);
    return (rank.get(ta.name) || 0) - (rank.get(tb.name) || 0);
  });
  tagOrder = [...known, ...rest];
}

/** 叠加层登记：空分组（本地建、尚无行）。 */
function overlayRememberEmpty(name, color) {
  const ov = readTagOverlay();
  ov.empties = ov.empties.filter((e) => e.name !== name);
  ov.empties.push({ name, color: color || '' });
  if (color) ov.colors[name] = color;
  writeTagOverlay();
}

/** 叠加层登记：改名（颜色/空分组/顺序三条同迁）。 */
function overlayRenameTag(oldName, newName) {
  if (oldName === newName) return;
  const ov = readTagOverlay();
  if (ov.colors[oldName]) { ov.colors[newName] = ov.colors[oldName]; delete ov.colors[oldName]; }
  const e = ov.empties.find((x) => x.name === oldName);
  if (e) e.name = newName;
  ov.order = ov.order.map((n) => (n === oldName ? newName : n));
  writeTagOverlay();
}

/** 叠加层登记：删除分组。 */
function overlayForgetTag(name) {
  const ov = readTagOverlay();
  delete ov.colors[name];
  ov.empties = ov.empties.filter((e) => e.name !== name);
  ov.order = ov.order.filter((n) => n !== name);
  writeTagOverlay();
}

/** 叠加层登记：chips 顺序（按名存，跨真源/本地两种 id 语义都成立）。 */
function overlayRememberOrder(orderIds) {
  const ov = readTagOverlay();
  const names = orderIds.map((id) => {
    const t = tags.find((x) => x.id === id);
    return t ? t.name : null;
  }).filter(Boolean);
  if (!names.length) return;
  ov.order = names;
  writeTagOverlay();
}


// ── 真源读：行映射 ────────────────────────────────────────────────────────

/** 后端行（listTaskJson 形状）是否进人待办收件箱面。 */
function isTodoRow(row) {
  if (!row || typeof row.id !== 'string' || !row.id) return false;
  const kind = row.kind == null ? null : String(row.kind);
  if (kind != null && kind !== 'todo') return false;
  return !MIRROR_NAME_RE.test(String(row.name || ''));
}

/** 后端行 → 读口形状（映射单点；渲染层只认这里产出的键）。 */
function mapRow(row) {
  const project = row.project == null || row.project === '' ? null : String(row.project);
  // 两段投影单点（todoText.projectTodoText）：新行直接用后端的 name/detail，
  // 旧行（无 detail 键）按「首句作 name、其余留 detail」无损投影。
  const parts = projectTodoText(row);
  return makeRow({
    id: String(row.id),
    tagId: project ? groupId(project) : null,
    project,
    name: parts.name,
    detail: parts.detail,
    // due ↔ triggerAt：引擎算出的下次浮出时刻（只读真源）。
    due: Number.isFinite(row.triggerAt) ? row.triggerAt : null,
    priority: clampPriority(row.priority),
    completed: row.completed === true || (row.completedAt != null),
    completedAt: Number.isFinite(row.completedAt) ? row.completedAt : null,
    createdAt: Number.isFinite(row.createdAt) ? row.createdAt : Date.now(),
    // 跨会话行级寻址面（写帧必须带**该行**的 sessionId）。
    sessionId: String(row.sessionId || currentSessionId() || ''),
  });
}

/** 分组表 ↔ 真源 project 对账：出现即建组、无行即撤组（夹具模式跳过）。 */
function syncGroupRegistry() {
  if (!fixtureMode) {
    // ① 本机叠加层先落「空分组」（本地新建、尚无行）—— 真源没有 tag 实体，
    //    不叠这一层的话新建分组刷新即消失（见上方「标签本地叠加层」）。
    const ov = readTagOverlay();
    for (const e of ov.empties) {
      const id = groupId(e.name);
      if (!tags.some((t) => t.id === id)) {
        tags.push({ id, name: e.name, color: ov.colors[e.name] || e.color || colorForName(e.name), local: true });
        tagOrder.push(id);
      }
    }
  }
  const live = new Set();
  for (const it of items) {
    if (!it.project) continue;
    live.add(it.project);
    const id = groupId(it.project);
    if (!tags.some((t) => t.id === id)) {
      tags.push({ id, name: it.project, color: colorForName(it.project), local: false });
      tagOrder.push(id);
    }
  }
  const keep = tags.filter((t) => t.local || !t.name || live.has(t.name) || fixtureMode);
  if (keep.length !== tags.length) {
    tags = keep;
    const ids = new Set(tags.map((t) => t.id));
    tagOrder = tagOrder.filter((id) => ids.has(id));
    if (activeTagId != null && !ids.has(activeTagId)) activeTagId = null;
  }
  if (!fixtureMode) applyTagOverlay();
}

// ── 真源读：拉取 ──────────────────────────────────────────────────────────

let sidRetryTimer = null;
let sidRetries = 0;
let refreshTimer = null;

/**
 * 请求跨会话收件箱（`scope:'all'`，含默认排除已完成）。
 * 会话 id 未就绪时**有界重试**（不在无 sessionId 时发帧：ws.js 会把无
 * sessionId 的非 GLOBAL 回执当会话内事件并把视图切到 primary）。
 */
function requestInbox() {
  if (fixtureMode) return;
  const sid = currentSessionId();
  if (!sid) {
    if (sidRetryTimer || sidRetries >= SID_RETRY_MAX) return;
    sidRetryTimer = setTimeout(() => {
      sidRetryTimer = null;
      sidRetries++;
      requestInbox();
    }, SID_RETRY_MS);
    return;
  }
  sidRetries = 0;
  const frame = { type: FRAME.list, sessionId: sid, scope: 'all' };
  // opt-in 归档面（panel 节点的"已完成/撤销"视图）：默认路径**不带**该键，
  // 后端默认不返回已完成行（CONTRACT.md §2）。
  if (wantCompleted) frame.includeCompleted = true;
  sendWs(frame);
}

/** 惰性首拉（首次 subscribe / 首次读口触发；渲染层不必改）。 */
function maybePull() {
  if (fixtureMode || pullStarted) return;
  pullStarted = true;
  requestInbox();
}

/** 主动刷新（渲染层挂载点/面板节点可显式调用）。
 *  `refresh({includeCompleted:true})` = 连已完成行一起拉（归档/撤销视图用）。 */
function refresh(opts = {}) {
  if (fixtureMode) return;
  if (opts && typeof opts.includeCompleted === 'boolean') wantCompleted = opts.includeCompleted;
  pullStarted = true;
  requestInbox();
}

/** 写后收敛：去抖刷新（覆盖被会话过滤器丢弃的跨会话广播）。 */
function scheduleRefresh() {
  if (fixtureMode || refreshTimer) return;
  refreshTimer = setTimeout(() => { refreshTimer = null; requestInbox(); }, REFRESH_DEBOUNCE_MS);
}

// ── 真源读：帧回执 → 内存摊（收敛单点）──────────────────────────────────

function replaceRow(row) {
  if (!isTodoRow(row)) return false;
  const mapped = mapRow(row);
  const i = items.findIndex((x) => x.id === mapped.id);
  if (i >= 0) items[i] = mapped;
  else items.push(mapped);
  syncGroupRegistry();
  emit();
  return true;
}

function listHandler(msg) {
  if (fixtureMode) return;
  // 只认跨会话收件箱面（单会话回执归提醒面板 / scheduled-task.js，不是本仓数据源）。
  if (msg.scope !== 'all') return;
  const rows = Array.isArray(msg.tasks) ? msg.tasks : [];
  const live = rows.filter(isTodoRow).map(mapRow);
  // 已完成行 = **归档影子**：默认读口（includeCompleted:false）不再下发它们，但
  // 本会话内刚完成的行要保留寻址能力（uncompleteItem / 归档视图），否则
  // 「完成 → 刷新 → 撤销」这条回退链会在下一次刷新处断掉。
  // 影子不进可见面、不计 openCount（visibleItems / openCount 都过滤 completed）。
  const shadow = items.filter((x) => x.completed && !live.some((y) => y.id === x.id));
  items = live.concat(shadow);
  serverSeq++;
  syncGroupRegistry();
  emit();
}

function createdHandler(msg) {
  if (fixtureMode) return;
  const row = msg && msg.task;
  if (!row || !isTodoRow(row)) return;
  const mapped = mapRow(row);
  // 乐观行对账：同 content 的在飞临时行就地换 id（保留本地 notes/due 展示位）。
  const pendingIdx = items.findIndex((x) => String(x.id).startsWith('tmp') && x.title === mapped.title);
  if (pendingIdx >= 0) items.splice(pendingIdx, 1);
  if (!items.some((x) => x.id === mapped.id)) items.push(mapped);
  syncGroupRegistry();
  emit();
}

function updatedHandler(msg) {
  if (fixtureMode) return;
  const row = msg && msg.task;
  if (row) { replaceRow(row); return; }
}

function completedHandler(msg) {
  if (fixtureMode) return;
  const row = msg && msg.task;
  if (row && row.id) {
    const mapped = mapRow({ ...row, completed: true, completedAt: msg.completedAt != null ? msg.completedAt : row.completedAt });
    const i = items.findIndex((x) => x.id === mapped.id);
    if (i >= 0) items[i] = { ...items[i], ...mapped, completed: true };
    else items.push(mapped);
    emit();
    return;
  }
  const it = items.find((x) => x.id === msg.id);
  if (it) { it.completed = true; emit(); }
}

function deletedHandler(msg) {
  if (fixtureMode) return;
  const id = msg && msg.id;
  if (!id) return;
  const i = items.findIndex((x) => x.id === id);
  if (i < 0) return;
  // 完成兼容双发：后端 completeTodo 同时发 scheduledTaskCompleted + 老帧
  // scheduledTaskDeleted。老帧对「本地已判完成的行」是影子帧 ⇒ 不摘行
  // （摘了就让 uncompleteItem / 归档视图失去寻址对象）；真删由去抖刷新收口。
  if (items[i].completed) return;
  items.splice(i, 1);
  syncGroupRegistry();
  emit();
}

/** 服务端显式拒绝（带 msgType 的 error 帧）⇒ 回滚 + 报错，不静默吞。 */
function errorHandler(msg) {
  if (!msg || !msg.msgType) return;
  const ourFrames = [FRAME.create, FRAME.update, FRAME.complete, FRAME.uncomplete, FRAME.remove];
  if (!ourFrames.includes(msg.msgType)) return;
  console.error(`[todoStore] server rejected ${msg.msgType}: ${msg.message || 'unknown reason'}`);
  // 创建被拒：撤掉在飞乐观行（旧口径 temp- 前缀，本文件用 tmp 前缀）。
  if (msg.msgType === FRAME.create) {
    const i = items.findIndex((x) => String(x.id).startsWith('tmp'));
    if (i >= 0) { items.splice(i, 1); emit(); }
  }
  // 其余：拉一次真源把本地乐观态纠回服务端态（回滚的权威版本）。
  refresh();
}

let wired = false;
function wire() {
  if (wired) return;
  wired = true;
  onMessage('scheduledTaskList', listHandler);
  onMessage('scheduledTaskCreated', createdHandler);
  onMessage('scheduledTaskUpdated', updatedHandler);
  onMessage('scheduledTaskCompleted', completedHandler);
  onMessage('scheduledTaskDeleted', deletedHandler);
  onMessage('error', errorHandler);
  // 重连后重拉（断线期间的写操作/浮出全部补齐）。
  onReconnect(() => { if (!fixtureMode) requestInbox(); });
}
wire();

// ── 读口（签名不变）──────────────────────────────────────────────────────

function tagIndexOf(tagId) {
  const i = tagOrder.indexOf(tagId);
  return i >= 0 ? i : Number.MAX_SAFE_INTEGER;
}

/** 真源排序 = 后端 `todoSortKey` 的复算：(priority 降序, due 升序, createdAt 升序)。
 *  已完成已在上游过滤；id 仅作末位确定性 tiebreak。 */
function sortByBackendFields(list) {
  return [...list].sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    const ad = a.due, bd = b.due;
    if (ad != null && bd != null) { if (ad !== bd) return ad - bd; }
    else if (ad == null && bd != null) return 1;
    else if (ad != null && bd == null) return -1;
    if (a.createdAt !== b.createdAt) return a.createdAt - b.createdAt;
    return a.id < b.id ? -1 : (a.id > b.id ? 1 : 0);
  });
}

/** 夹具模式排序（旧内存态语义逐字保留：重要度降序 →（All 视图）分组序 → 截止）。
 *  🔴 仅 __seed 夹具面走这里；真源面绝不按 chips 行序排（那是只有前端知道的顺序）。 */
function sortFixture(list, allView) {
  return [...list].sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    if (allView) {
      const ta = tagIndexOf(a.tagId), tb = tagIndexOf(b.tagId);
      if (ta !== tb) return ta - tb;
    }
    if (a.due != null && b.due != null) return a.due - b.due;
    if (a.due == null && b.due == null) return a.createdAt - b.createdAt;
    return a.due == null ? 1 : -1;
  });
}

/** 可见条目（过滤 + 排序；真源面顺序 = 后端字段复算）。 */
function visibleItems() {
  maybePull();
  const base = items.filter((it) => !it.completed)
    .filter((it) => activeTagId == null || it.tagId === activeTagId)
    .filter((it) => selectedPriorities.size === 0 || selectedPriorities.has(it.priority));
  return fixtureMode ? sortFixture(base, activeTagId == null) : sortByBackendFields(base);
}

function orderedTags() {
  return [...tags].sort((a, b) => tagIndexOf(a.id) - tagIndexOf(b.id));
}

/**
 * 全部未完成行（**不套**当前分组/重要度筛选）—— `#` 引用补全的候选面。
 * 引用天然跨分组：用户在某个分组视图里也应能引到别组的待办，所以这里刻意绕开
 * visibleItems 的筛选，只保留「未完成」这一条与补全相关的门。
 * @returns {Array<TodoRow>}
 */
function openItems() {
  maybePull();
  return items.filter((it) => !it.completed);
}

function openCount() {
  maybePull();
  return items.filter((it) => !it.completed).length;
}

function overdueCount() {
  maybePull();
  const now = Date.now();
  return items.filter((it) => !it.completed && it.due != null && it.due < now).length;
}

/** 今日到期（未完成、due 落在本地日历日之内 = 今天 00:00 ≤ due < 明天 00:00）。 */
function dueTodayCount() {
  maybePull();
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const from = start.getTime();
  const to = from + 24 * 3600_000;
  return items.filter((it) => !it.completed && it.due != null && it.due >= from && it.due < to).length;
}

function completedCount() {
  maybePull();
  return items.filter((it) => it.completed).length;
}

/** 某标签下未完成条目数（列表卡行右侧计数）。 */
function openCountOf(tagId) {
  maybePull();
  return items.filter((it) => !it.completed && it.tagId === tagId).length;
}

/** 全量条目（含已完成；只读拷贝）——已完成矩阵与冒烟探针用。 */
function allItems() {
  maybePull();
  return [...items];
}

// ── 写口（乐观 + 帧 + 回执/刷新收敛；失败回滚并 console.error）──────────

/** 分组 id → 后端 project 名（"" = 清空归属）。 */
function projectNameOf(tagId) {
  if (!tagId) return '';
  const tag = tags.find((t) => t.id === tagId);
  return tag ? tag.name : '';
}

/**
 * 新建待办：`createScheduledTask {kind:'todo', sessionId, content, name?, detail?, priority, project?}`。
 * 注意后端对 kind=todo **不采信客户端 triggerAt**（由 `nextTodoSurfaceAt` 决定），
 * 因此 due 传什么都不会落库（申报为只读真源）。
 *
 * 两段化入参（待办结构化批 2026-11-05）：`{name, detail}` 是正式面；`{title, notes}`
 * 作为旧调用面继续可用（title → name、notes → detail），二者同时给时 name/detail
 * 优先。`content`（后端必填、注入/摘要用）= detail || name，所以**只传旧字段的
 * 调用方行为逐字不变**。
 *
 * @param {{tagId?: string|null, name?: string, detail?: string, title?: string, notes?: string,
 *   due?: number|null, priority?: number}} [input]
 * @returns {string | null} 本地乐观行 id
 */
function createItem({ tagId = null, name = '', detail = '', title = '', notes = '', due = null, priority = 0 } = {}) {
  const resolvedName = String(name || title || '').trim();
  const resolvedDetail = String(detail || notes || '').trim();
  const content = resolvedDetail || resolvedName;
  const resolvedTagId = tagId || (tags[0] ? tags[0].id : null);
  const optimistic = makeRow({
    id: uid('tmp'),
    tagId: resolvedTagId,
    project: projectNameOf(resolvedTagId) || null,
    name: resolvedName || content,
    detail: resolvedDetail,
    due: Number.isFinite(due) ? due : null,
    priority: clampPriority(priority),
    completed: false,
    completedAt: null,
    createdAt: Date.now(),
    sessionId: currentSessionId(),
  });
  items.push(optimistic);
  if (Number.isFinite(due)) warnUnpersisted('due', 'triggerAt is engine-owned (nextTodoSurfaceAt)');
  if (!fixtureMode) {
    const sid = currentSessionId();
    if (!sid) {
      // 无会话无从落库：撤掉乐观行 + 报错（不静默吞）。
      items = items.filter((x) => x.id !== optimistic.id);
      console.error('[todoStore] createItem failed: no active session');
      emit();
      return null;
    }
    /** @type {Record<string, any>} 帧面（键集随批次增减，样式面走宽松字典） */
    const frame = { type: FRAME.create, kind: 'todo', sessionId: sid, content, priority: optimistic.priority };
    if (resolvedName) frame.name = resolvedName;
    if (resolvedDetail) frame.detail = resolvedDetail;
    if (optimistic.project) frame.project = optimistic.project;
    sendWs(frame);
    scheduleRefresh();
  }
  emit();
  return optimistic.id;
}

function applyLocalPatch(it, patch) {
  // 两段化：name/detail 是正式面，title/notes 是旧调用面（同义）。
  if (typeof patch.name === 'string') it.name = patch.name.trim();
  if (typeof patch.detail === 'string') it.detail = patch.detail;
  if (typeof patch.title === 'string') it.name = patch.title.trim();
  if (typeof patch.notes === 'string') it.detail = patch.notes;
  if (typeof patch.due === 'number') it.due = patch.due;
  if ('hasDue' in patch && !patch.hasDue) it.due = null;
  if (typeof patch.priority === 'number') it.priority = clampPriority(patch.priority);
  if (patch.tagId === null) { it.tagId = null; it.project = null; }
  else if (typeof patch.tagId === 'string' && tags.some((tg) => tg.id === patch.tagId)) {
    it.tagId = patch.tagId;
    it.project = projectNameOf(patch.tagId) || null;
  }
}

/**
 * 改字段：一个 `updateScheduledTask` 帧只带变化的 patch 字段
 * （content / name / detail / priority / project 是后端认的 patch 面；due 只本地）。
 */
function updateItem(id, patch = {}) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  const before = { ...it };
  applyLocalPatch(it, patch);
  if (typeof patch.due === 'number' || ('hasDue' in patch && !patch.hasDue)) {
    warnUnpersisted('due', 'triggerAt is engine-owned (nextTodoSurfaceAt)');
  }
  if (!fixtureMode) {
    /** @type {Record<string, any>} 帧面（键集随批次增减，样式面走宽松字典） */
    const frame = { type: FRAME.update, id: it.id, sessionId: it.sessionId || currentSessionId() };
    let has = false;
    // content 是必填的注入面：两段任一变化都重算（detail 优先，缺则 name）。
    if ((it.name !== before.name || it.detail !== before.detail) && (it.detail || it.name)) {
      frame.content = it.detail || it.name;
      frame.name = it.name;
      frame.detail = it.detail;
      has = true;
    }
    if (it.priority !== before.priority) { frame.priority = it.priority; has = true; }
    if (it.project !== before.project) { frame.project = it.project || ''; has = true; }
    if (has) {
      if (!frame.sessionId) {
        items = items.map((x) => (x.id === id ? before : x));
        console.error(`[todoStore] updateItem(${id}) failed: no active session`);
        emit();
        return;
      }
      sendWs(frame);
      scheduleRefresh();
    }
  }
  emit();
}

/** 完成（软删：后端置 completedAt；行从可见面退场，可 uncomplete 回退）。 */
function completeItem(id) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  const before = { completed: it.completed, completedAt: it.completedAt };
  it.completed = true;
  it.completedAt = Date.now();
  if (!fixtureMode) {
    const sid = it.sessionId || currentSessionId();
    if (!sid) {
      it.completed = before.completed;
      it.completedAt = before.completedAt;
      console.error(`[todoStore] completeItem(${id}) failed: no addressable session`);
      emit();
      return;
    }
    sendWs({ type: FRAME.complete, sessionId: sid, id: it.id });
    scheduleRefresh();
  }
  emit();
}

/** 取消完成（`uncompleteTodo`；后端清 completedAt + 追加 DoneRecord(via:"undo")）。 */
function uncompleteItem(id) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  const before = { completed: it.completed, completedAt: it.completedAt };
  it.completed = false;
  it.completedAt = null;
  if (!fixtureMode) {
    const sid = it.sessionId || currentSessionId();
    if (!sid) {
      it.completed = before.completed;
      it.completedAt = before.completedAt;
      console.error(`[todoStore] uncompleteItem(${id}) failed: no addressable session`);
      emit();
      return;
    }
    sendWs({ type: FRAME.uncomplete, sessionId: sid, id: it.id });
    scheduleRefresh();
  }
  emit();
}

/** 物理删除（`deleteScheduledTask`；不可逆，与「完成」区分）。 */
function removeItem(id) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  const idx = items.indexOf(it);
  items = items.filter((x) => x.id !== id);
  if (!fixtureMode) {
    const sid = it.sessionId || currentSessionId();
    if (!sid) {
      items.splice(idx, 0, it);
      console.error(`[todoStore] removeItem(${id}) failed: no addressable session`);
      emit();
      return;
    }
    sendWs({ type: FRAME.remove, sessionId: sid, id: it.id });
    scheduleRefresh();
  }
  syncGroupRegistry();
  emit();
}

// ── 分组面（tag = 后端 project 的本地投影；见文件头「标签面」）────────────

/** 新建分组：本地登记（后端无 tag 实体）；该组下第一条行随 project 落库。 */
function createTag(name, color) {
  const trimmed = String(name || '').trim();
  if (!trimmed) return null;
  const id = groupId(trimmed);
  if (!tags.some((t) => t.id === id)) {
    tags.push({ id, name: trimmed, color: color || colorForName(trimmed), local: true });
    tagOrder = [...tagOrder.filter((tid) => tags.some((tg) => tg.id === tid)), id];
    if (!fixtureMode) overlayRememberEmpty(trimmed, color);
  }
  emit();
  return id;
}

/** 改分组：颜色纯本地；改名 = 逐行改 project（真落库）+ 本地改名/改 id。 */
function updateTag(id, patch = {}) {
  const tag = tags.find((x) => x.id === id);
  if (!tag) return;
  if (typeof patch.color === 'string') {
    tag.color = patch.color;
    if (!fixtureMode) { readTagOverlay().colors[tag.name] = patch.color; writeTagOverlay(); }
  }
  const renamed = typeof patch.name === 'string' && patch.name.trim() ? patch.name.trim() : '';
  if (renamed && renamed !== tag.name) {
    const oldName = tag.name;
    tag.name = renamed;
    if (!fixtureMode) {
      const newId = groupId(renamed);
      for (const it of items) {
        if (it.project !== oldName) continue;
        it.project = renamed;
        it.tagId = newId;
        const sid = it.sessionId || currentSessionId();
        if (!sid) { console.error(`[todoStore] rename group: row ${it.id} has no addressable session`); continue; }
        // 分组名 = 后端 project ⇒ 改名必须落到每一行（真落库，不是本地幻觉）。
        sendWs({ type: FRAME.update, id: it.id, sessionId: sid, project: renamed });
      }
      tag.id = newId;
      tag.local = true;
      tagOrder = tagOrder.map((tid) => (tid === id ? newId : tid));
      if (activeTagId === id) activeTagId = newId;
      overlayRenameTag(oldName, renamed);
      scheduleRefresh();
    }
  }
  emit();
}

/** 解散分组：把组内行的 project 清空（真落库），**不删行**。
 *  与旧内存态「删标签连带删条目」不同：真源行不因分组解散被销毁（本批申报）。 */
function deleteTag(id) {
  const tag = tags.find((x) => x.id === id);
  tags = tags.filter((x) => x.id !== id);
  tagOrder = tagOrder.filter((x) => x !== id);
  if (tag && !fixtureMode) {
    overlayForgetTag(tag.name);
    for (const it of items) {
      if (it.project !== tag.name) continue;
      it.tagId = null;
      it.project = null;
      const sid = it.sessionId || currentSessionId();
      if (!sid) { console.error(`[todoStore] dissolve group: row ${it.id} has no addressable session`); continue; }
      sendWs({ type: FRAME.update, id: it.id, sessionId: sid, project: '' });
    }
    scheduleRefresh();
  } else if (tag && fixtureMode) {
    // 夹具模式保留旧语义（本地级联摘行）——既有 shots 数据注入腿依赖。
    items = items.filter((it) => it.tagId !== id);
  }
  if (activeTagId === id) activeTagId = null;
  emit();
}

/** chips 拖拽重排（纯本地展示顺序；后端无顺序字段，明确申报）。 */
function reorderTag(dragId, targetId) {
  if (dragId === targetId) return;
  const from = tagOrder.indexOf(dragId);
  const to = tagOrder.indexOf(targetId);
  if (from < 0 || to < 0) return;
  const next = [...tagOrder];
  next.splice(from, 1);
  next.splice(to, 0, dragId);
  tagOrder = next;
  if (!fixtureMode) overlayRememberOrder(next);
  emit();
}

function setActiveTag(id) {
  activeTagId = id == null ? null : (tags.some((tg) => tg.id === id) ? id : null);
  emit();
}

function togglePriority(p) {
  if (selectedPriorities.has(p)) selectedPriorities.delete(p);
  else selectedPriorities.add(p);
  emit();
}

function subscribe(fn) {
  listeners.add(fn);
  maybePull();
  return () => listeners.delete(fn);
}

// ── 测试缝（__ 前缀 = 非产品路径）────────────────────────────────────────

/** 夹具模式：既有 shots/几何 spec 的数据注入腿（语义与换血前逐字一致）。
 *  @param {{tagList?: Array<any>, itemList?: Array<any>, order?: string[]}} [seed] 三个面
 *    全部可选（缺省 = 该面不动）；整体省略 = 空夹具（未知键忽略，与换血前一致）。 */
function seed({ tagList, itemList, order } = {}) {
  fixtureMode = true;
  if (sidRetryTimer) { clearTimeout(sidRetryTimer); sidRetryTimer = null; }
  if (refreshTimer) { clearTimeout(refreshTimer); refreshTimer = null; }
  if (Array.isArray(tagList)) {
    tags = tagList.map((x) => ({ id: String(x.id), name: String(x.name), color: String(x.color), local: true }));
    tagOrder = tags.map((x) => x.id);
  }
  if (Array.isArray(order) && order.every((id) => tags.some((x) => x.id === id))) tagOrder = [...order];
  if (Array.isArray(itemList)) {
    items = itemList.map((x) => makeRow({
      id: String(x.id), tagId: x.tagId == null ? null : String(x.tagId),
      project: null,
      // 夹具面接受两段名（name/detail）与旧名（title/notes），二者同义。
      name: String(x.name || x.title || ''),
      detail: String(x.detail || x.notes || ''),
      due: Number.isFinite(x.due) ? x.due : null,
      priority: clampPriority(x.priority),
      completed: !!x.completed,
      completedAt: x.completed ? Date.now() : null,
      createdAt: Number.isFinite(x.createdAt) ? x.createdAt : Date.now(),
      sessionId: String(x.sessionId || currentSessionId() || ''),
    }));
  }
  activeTagId = null;
  selectedPriorities.clear();
  emit();
}

/** 退出夹具模式并重拉真源（本批 spec / 面板节点用）。 */
function goLive() {
  fixtureMode = false;
  pullStarted = true;
  requestInbox();
}

export const todoStore = {
  /** 只读投影（渲染层不持有可变引用）。 */
  get tags() { maybePull(); return orderedTags(); },
  get activeTagId() { return activeTagId; },
  get selectedPriorities() { return new Set(selectedPriorities); },
  /** 真源面机器可读申报（panel 节点/验收据以判定哪些字段落库）。 */
  get persistence() {
    return {
      // 两段化（待办结构化批 2026-11-05）：name ↔ 后端 name（短标题，同时是 # 引用与
      // 同名 upsert 键）；detail ↔ 后端 detail（长内容）。旧行（只有 content）由
      // todoText.projectTodoText 读时投影，零写零丢失。
      name: 'name', detail: 'detail', priority: 'priority', completed: 'completedAt',
      tagId: 'project', due: 'read-only:triggerAt',
      // 标签色/顺序/空分组：后端无 tag 实体 ⇒ 落**本机**叠加层（localStorage，
      // 键 tagOverlayKey；作者报障批 2026-11-07 前是纯内存，刷新即丢）。
      tagColor: 'local-storage', tagOrder: 'local-storage',
    };
  },
  get dataSource() { return fixtureMode ? 'fixture' : 'backend'; },
  get serverSeq() { return serverSeq; },
  visibleItems, openCount, overdueCount, dueTodayCount, completedCount, openCountOf, allItems,
  /** 全部未完成行（不带分组/重要度筛选；`#` 引用补全的候选面）。 */
  openItems,
  /** 归档面（已完成行；按完成时刻降序）。需先 refresh({includeCompleted:true}) 才完整。 */
  completedItems: () => items.filter((it) => it.completed)
    .sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0)),
  tagById: (id) => tags.find((x) => x.id === id) || null,
  isAllView: () => activeTagId == null,
  setActiveTag, togglePriority,
  /** 显式拉取/刷新（渲染层挂载点可调用；不调用也已惰性自动首拉）。 */
  init: maybePull,
  refresh,
  createItem, updateItem, completeItem, uncompleteItem, removeItem,
  createTag, updateTag, deleteTag, reorderTag,
  subscribe,
  /** 测试缝：确定性夹具（shots 几何 spec 用；__ 前缀 = 非产品路径）。 */
  __seed: seed,
  /** 测试缝：退出夹具模式回真源。 */
  __live: goLive,
};
