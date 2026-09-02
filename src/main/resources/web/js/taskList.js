// taskList.js — Collapsible task panel (floats above the input area)
// 任务工具重做 (2026-08-30 作者规格) + #15 补齐 (2026-08-30): 纯进展可视化
// 四态状态机 pending → in_progress → completed / failed。needs_confirmation /
// dismissed / cancelled / 待办 全部退役：#15 作者三条强调「不再有待办——纯 team
// 任务进展可视化」——待办区 (.task-section-todo) 与 human pending CIRCLE
// (completeTask 用户确认通道) 移除，无用户确认/打回/取消环节。
//   - 任务区 (.task-section-progress): agent pending/in_progress — read-only
//     glyphs (pending=静态小方框 / in_progress=独立 spinner), status word +
//     relative time + team/member meta (谁在执行)
// 裁定②: team 域任务统一并入 Nebula 会话的本面板（Teams 标签页任务区已删）；
// 裁定④ + #15: 三级分组 team → member → tasks（member=assignee 契约字段，
// 每任务标注 Team+成员名）。
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect } from './ws.js';
import { fetchProjects, fetchFlowMap, NODE_STATUS_CLS } from './nodeData.js';

/** 任务工具重做: visible set = pending + in_progress ONLY（「列表只显
 *  pending+in_progress」——completed/failed 完成即消失，6h/2d TTL 管磁盘
 *  清理不管可见性）。legacy 状态由后端解码映射（needs_confirmation→
 *  completed 等），到不了这里。 */
function isVisible(tk) {
  return !!tk && (tk.status === 'pending' || tk.status === 'in_progress');
}

/** C1: missing taskKind (pre-upgrade server) defaults to 'agent'. */
function kindOf(tk) {
  return tk && tk.taskKind === 'human' ? 'human' : 'agent';
}

// ── Zone partitioning ────────────────────────────────────────────────────
// #15 不再有待办——面板纯任务进展可视化，单一任务区（progress），todo/progress
// 双区块划分退役。

/** Sort key: updatedAt refreshed on every mutation and always present;
 *  defensive fallback to createdAt. */
function taskTs(tk) {
  const raw = tk.updatedAt || tk.createdAt;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

function sortByActiveDesc(a, b) {
  return taskTs(b) - taskTs(a);
}

/** 任务区: updatedAt desc (visible set is active-only — no failed sinking). */
function sortProgress(a, b) {
  return sortByActiveDesc(a, b);
}

// ── Team grouping (第七件 裁定④, 2026-08-30 + #15 补齐) ───────────────────
// 任务区 groups team → member → tasks. ACCESSOR ISOLATION: the Backend data
// contract hooks up HERE and nowhere else. 契约字段（TaskModel.scala）：
// team 归属 = `teamId`（scope=='team' 的 team 域任务携带；session 域无此键）；
// member 归属 = `assignee`（成员责任归属——#15 修正：此前 taskMember 误读
// `member` 字段（不存在）致三级分组的 member 层永不填充）。
function taskTeam(tk) { return (tk && typeof tk.teamId === 'string' && tk.teamId) || null; }
function taskMember(tk) { return (tk && typeof tk.assignee === 'string' && tk.assignee) || null; }

/** 裁定②: team 域任务（teamTaskListUpdate, 无 sessionId）统一并入 Nebula
 *  会话的任务列表面板。Only the Nebula session shows the unified list —
 *  member/agent sessions keep showing only their own session tasks. */
export function sessionShowsTeamTasks(sid) {
  if (!sid) return false;
  const agent = state.sessionAgentMap ? state.sessionAgentMap[sid] : null;
  return !agent || agent === 'Nebula';
}

/** Merge team-domain tasks (state.teamTasks, keyed by team) into the panel
 *  list for Nebula sessions. Stamped copies carry teamId (grouping hook) and
 *  a namespaced display id — team tasks live in their own store directory
 *  with their own id space, so a bare id could collide with a session task
 *  (row dataset/zone-diff keyed by id). Team tasks are read-only here, so the
 *  stamped id never goes back on the wire. */
function mergeTeamTasks(tasks, sessionId) {
  const base = Array.isArray(tasks) ? tasks : [];
  if (!sessionShowsTeamTasks(sessionId)) return base;
  const teamMap = state.teamTasks || {};
  const stamped = [];
  for (const [team, arr] of Object.entries(teamMap)) {
    if (!Array.isArray(arr)) continue;
    for (const tk of arr) {
      if (!tk) continue;
      stamped.push({ ...tk, id: `team:${team}:${tk.id}`, teamId: tk.teamId || team });
    }
  }
  return stamped.length ? base.concat(stamped) : base;
}

// ── Flow Map 节点条目（2026-09-02 作者裁定：节点作为条目并入任务列表）──────
// 纯前端、零后端改动：数据 = WS 节点广播帧（nodeCreated/Updated/Completed/Removed，
// 全局广播 {type, project, nodeId, node}，契约 20260901_project-node-contract §2）
// 增量维护本地缓存 + 连接建立时 NodeList 全量快照（GET /api/projects/<n>/flow-map）
// 对齐一次；事件驱动，无轮询。与裁定② team 任务同一并入逻辑：只在 Nebula 统一
// 面板（sessionShowsTeamTasks）渲染。终态节点在 5min TTL 窗口内仍显示（与 Flow Map
// 一致），到期由 nodeRemoved 移除。

/** project → Map(nodeId → node)。node 附带 _wsTs（本帧落地时刻，防快照回滚）。 */
const nodeCache = new Map();
let nodeSnapshotLoaded = false;
let nodeSnapshotSeq = 0;
/** 最近一次渲染面板的会话：WS 事件到达时按它判断是否重渲（Nebula 统一面板）。 */
let lastPanelSessionId = null;

/** 节点时间戳：契约 §4 是 epoch ms 数字；容忍 ISO 字符串（旧帧兼容）。 */
function nodeTs(n) {
  const raw = n && (n.completedAt || n.startedAt || n.createdAt);
  if (typeof raw === 'number') return raw;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

/** 终态且 ttlLeftSec 已到期（≤0）的节点不再显示——与 flowMapTab.visibleNodes 同规则。 */
function nodeIsLive(n) {
  const terminal = n.status === 'completed' || n.status === 'failed' || n.status === 'cancelled';
  if (terminal && Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec <= 0) return false;
  return true;
}

/** 面板节点条目数据：全部项目的活跃节点，最近活动在前。 */
function collectNodes() {
  const out = [];
  for (const [project, byId] of nodeCache) {
    for (const n of byId.values()) {
      if (n && n.id && nodeIsLive(n)) out.push({ node: n, project });
    }
  }
  out.sort((a, b) => nodeTs(b.node) - nodeTs(a.node));
  return out;
}

/** WS 节点事件 → 缓存增量 + 面板重渲。导出供测试直接驱动（与 ws.js 分发等价）。 */
export function applyNodeWsEvent(msg) {
  const project = msg && msg.project;
  if (!project) return;
  const type = String(msg.type || '');
  let byId = nodeCache.get(project);
  if (!byId) { byId = new Map(); nodeCache.set(project, byId); }
  const node = msg.node;
  if (type === 'nodeRemoved') {
    byId.delete(String(msg.nodeId || (node && node.id) || ''));
  } else if (node && node.id) {
    byId.set(node.id, { ...node, _wsTs: Date.now() });
  }
  if (byId.size === 0) nodeCache.delete(project);
  rerenderWithNodes();
}

function rerenderWithNodes() {
  const sid = lastPanelSessionId;
  if (!sid || !sessionShowsTeamTasks(sid)) return;
  renderTaskList(state.sessionTasks[sid] || [], undefined, sid);
}

/** NodeList 全量快照对齐（连接建立首渲一次 + 断线重连收敛事件缺口）。逐项目容错：
 *  单项目拉取失败不连累其他项目。防回滚：快照在途期间落地的 WS 增量（_wsTs 晚于
 *  本次刷新起点）比快照新，保留缓存版本——否则旧快照会把已完成的节点滚回 running。 */
async function refreshNodeSnapshot() {
  const seq = ++nodeSnapshotSeq;
  const fetchStart = Date.now();
  let projects;
  try {
    projects = await fetchProjects();
  } catch (_) { return; }
  const results = await Promise.all((projects || []).map(async (p) => {
    try { return [p.name, await fetchFlowMap(p.name)]; }
    catch (_) { return [p.name, null]; }
  }));
  if (seq !== nodeSnapshotSeq) return; // 已有更新的刷新，丢弃旧结果
  for (const [name, fm] of results) {
    if (!fm) continue; // 网络失败保留旧缓存（对账无依据时不破坏现状）
    const byId = nodeCache.get(name) || new Map();
    const next = new Map();
    for (const n of (fm.nodes || [])) {
      if (!n || !n.id) continue;
      const cached = byId.get(n.id);
      next.set(n.id, (cached && (cached._wsTs || 0) > fetchStart) ? cached : { ...n });
    }
    if (next.size) nodeCache.set(name, next); else nodeCache.delete(name);
  }
  rerenderWithNodes();
}

for (const evt of ['nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved']) {
  onMessage(evt, applyNodeWsEvent);
}
onReconnect(() => { if (nodeSnapshotLoaded) refreshNodeSnapshot(); });

// ── §15.7 relative time (任务区) ─────────────────────────────────────────
function formatLastActive(updatedAt) {
  if (!updatedAt) return '';
  const ms = Date.parse(updatedAt);
  if (isNaN(ms)) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/** §15.7: 60s interval, only while the panel has visible tasks; textContent
 *  direct update, no animation. */
let lastActiveTimer = null;
function refreshLastActive() {
  const container = document.getElementById('task-list');
  if (!container) return;
  const els = /** @type {NodeListOf<HTMLElement>} */ (container.querySelectorAll('.task-last-active'));
  els.forEach((el) => {
    const ts = el.dataset.ts;
    if (ts) el.textContent = formatLastActive(ts);
  });
}
function ensureLastActiveTimer(running) {
  if (running && !lastActiveTimer) {
    lastActiveTimer = setInterval(refreshLastActive, 60_000);
  } else if (!running && lastActiveTimer) {
    clearInterval(lastActiveTimer);
    lastActiveTimer = null;
  }
}

const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

// ── Row builders (§15.5/§15.6) ──────────────────────────────────────────

/**
 * Zone-leading glyph per status (纯进展可视化 — single progress zone):
 *  pending  = 静态小方框 (.task-check-box, #15 更小 —— 观感「排队/等待执行」)
 *  in_progress = 独立 spinner 圆环本体 (裁定⑤; spinner 在方框中转禁).
 *  All read-only (aria-hidden); row aria-label carries the status.
 */
function buildCheck(task, row) {
  const check = document.createElement('span');
  check.className = 'task-check';

  // 任务区 — read-only glyph (§15.6: no hover feedback, no tab stop).
  // in_progress -> standalone spinner (base 20px); pending -> small static box.
  if (task.status === 'in_progress') {
    // 第七件 裁定⑤ (2026-08-30): the spinner IS the glyph — a standalone
    // ring, no surrounding box ("spinner 在方框中转" combo is forbidden).
    // No .task-check-box so it keeps the base 20px slot (not the small box).
    check.classList.add('task-check-spin');
    check.setAttribute('aria-hidden', 'true');
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.inProgressShort')}`.trim());
  } else {
    // agent pending — decorative hollow small square (#15 更小)
    check.classList.add('task-check-box');
    check.setAttribute('aria-hidden', 'true');
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.pendingShort')}`.trim());
  }
  return check;
}

function buildRow(task, sessionId) {
  const row = document.createElement('div');
  row.className = 'task-item' +
    (task.status === 'in_progress' ? ' task-active' : '');
  row.dataset.taskId = task.id;
  if (sessionId) row.dataset.sessionId = sessionId;

  row.appendChild(buildCheck(task, row));

  const text = document.createElement('div');
  text.className = 'task-item-text';
  const label = document.createElement('span');
  label.className = 'task-label';
  label.textContent = task.subject || '';
  text.appendChild(label);
  // #37: render the description under the subject so tasks with similar
  // subjects stay distinguishable. Truncated to 2 lines; full text in title.
  const descText = (task.description || '').trim();
  if (descText) {
    row.classList.add('task-has-desc');
    const desc = document.createElement('span');
    desc.className = 'task-desc';
    desc.textContent = descText;
    desc.title = descText;
    text.appendChild(desc);
  }
  // #15 三级分组: annotate every task with its Team · member (谁在执行). Quiet
  // meta line under the subject/desc; grouped tasks read clearly. 无归属的全局
  // 任务在 Nebula 统一面板标注「Nebula（全局）」来源（#16，Nebula 拍板 08-30）——
  // 成员会话面板的无归属任务不标注（那是成员自己的任务）。
  const team = taskTeam(task);
  const member = taskMember(task);
  let meta = [team, member].filter(Boolean).join(' · ');
  // #16 全局任务来源标注: 无 team 无 assignee 的全局任务在 Nebula 统一面板
  // （sessionShowsTeamTasks true）标注来源「Nebula（全局）」；成员会话面板不标。
  if (!meta && sessionShowsTeamTasks(sessionId)) meta = t('task.globalSource');
  if (meta) {
    row.classList.add('task-has-meta');
    const metaEl = document.createElement('span');
    metaEl.className = 'task-meta';
    metaEl.setAttribute('aria-hidden', 'true');
    metaEl.textContent = meta;
    text.appendChild(metaEl);
  }
  row.appendChild(text);

  // 任务区 (§15.6): status word + relative time (D6). Both aria-hidden —
  // the row's aria-label carries the status for AT users (§15.9).
  const word = document.createElement('span');
  word.className = 'task-status-word';
  word.setAttribute('aria-hidden', 'true');
  word.textContent = t(task.status === 'in_progress'
    ? 'task.inProgressShort' : 'task.pendingShort');

  const time = document.createElement('span');
  time.className = 'task-last-active';
  time.setAttribute('aria-hidden', 'true');
  const ts = task.updatedAt || task.createdAt || '';
  if (ts) {
    time.dataset.ts = ts;
    time.textContent = formatLastActive(ts);
  }

  row.appendChild(word);
  if (ts) row.appendChild(time);
  return row;
}

// ── Flow Map 节点行（2026-09-02）：复用任务行设计语言 ────────────────────

// 状态词全部复用现有 i18n key（零新增）：wiring→等待 / pending→排队中 /
// running→运行中 / completed→已完成 / failed→失败 / cancelled→已取消。
const NODE_WORD_KEY = {
  wiring: 'flowmap.wait',
  pending: 'task.pendingShort',
  running: 'flowmap.run',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flows.status.cancelled',
};

/**
 * 节点条目行：节点名 + 状态徽章 + 相对时间 + project · agent 标注。
 * 状态徽章映射（复用任务行既有 glyph 语义 + 应用既有状态色）：
 *   running     → .task-check-spin（与任务 in_progress 同一 spinner）
 *   wiring/pending → .task-check-box（与任务 pending 同一静态小方框，等待色=muted）
 *   completed/failed/cancelled → .task-node-dot-{cls} 状态色圆点（success/error/warning）
 * 状态词按同语义着色（wiring/pending 保持 muted）。点击行 → 打开该项目 Flow Map
 * 就地视图并高亮节点（动态 import projectTab，避免模块图静态耦合）。
 */
function buildNodeRow(node, project) {
  const st = node.status || 'pending';
  const cls = NODE_STATUS_CLS[st] || 'pending'; // wiring → pending（等待色）
  const word = t(NODE_WORD_KEY[st] || 'task.pendingShort');

  const row = document.createElement('div');
  row.className = `task-item task-node task-node-${cls}`;
  row.dataset.nodeKey = `${project}:${node.id}`;
  row.dataset.project = project;
  row.dataset.nodeId = node.id;
  row.setAttribute('role', 'button');
  row.setAttribute('aria-label', `${node.name || node.id} — ${word}`);
  row.title = t('project.openFlowMap', { name: project });

  const check = document.createElement('span');
  check.className = 'task-check';
  check.setAttribute('aria-hidden', 'true');
  if (st === 'running') {
    check.classList.add('task-check-spin');
  } else if (cls === 'pending') {
    check.classList.add('task-check-box');
  } else {
    check.classList.add('task-node-dot', `task-node-dot-${cls}`);
  }
  row.appendChild(check);

  const text = document.createElement('div');
  text.className = 'task-item-text';
  const label = document.createElement('span');
  label.className = 'task-label';
  label.textContent = node.name || node.id;
  text.appendChild(label);
  const agent = typeof node.agent === 'string' ? node.agent : '';
  row.classList.add('task-has-meta');
  const metaEl = document.createElement('span');
  metaEl.className = 'task-meta';
  metaEl.setAttribute('aria-hidden', 'true');
  metaEl.textContent = agent ? `${project} · ${agent}` : project;
  text.appendChild(metaEl);
  row.appendChild(text);

  const wordEl = document.createElement('span');
  wordEl.className = `task-status-word task-node-word-${cls}`;
  wordEl.setAttribute('aria-hidden', 'true');
  wordEl.textContent = word;
  row.appendChild(wordEl);

  const ms = nodeTs(node);
  if (ms > 0) {
    const time = document.createElement('span');
    time.className = 'task-last-active';
    time.setAttribute('aria-hidden', 'true');
    // ISO 字符串进 dataset.ts——60s ticker 复用 formatLastActive（Date.parse 可解）
    time.dataset.ts = new Date(ms).toISOString();
    time.textContent = formatLastActive(time.dataset.ts);
    row.appendChild(time);
  }

  row.addEventListener('click', (e) => {
    e.stopPropagation();
    import('./projectTab.js').then(({ openProjectFlowMapAt }) => {
      openProjectFlowMapAt(project, node.id);
    }).catch(() => {});
  });
  return row;
}

function buildGroupHeader(label, section) {
  const h = document.createElement('div');
  // Dual class: .task-group-header is the implementation class (CSS hooks),
  // .task-section-title is the frozen spec assertion selector (§10 A6).
  // data-section carries the zone ('progress') — D1 assertion hook.
  h.className = 'task-group-header task-section-title';
  h.dataset.section = section;
  h.textContent = label;
  return h;
}

/** Grouping headers (裁定④): level-1 team / level-2 member. Visual grouping
 *  aids only — each row keeps carrying its own status via aria-label. */
function buildSubgroupHeader(label, level) {
  const h = document.createElement('div');
  h.className = 'task-subgroup-header task-subgroup-' + level;
  h.textContent = label;
  return h;
}

/** Empty-state row (§15.5/§15.6, C27): rendered only when the panel is visible
 *  with no visible tasks; non-interactive, aria-hidden (§15.9). */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render (v3 single-zone: pure progress) ─────────────────────────

/**
 * @param {Array} tasks
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — stamped onto rows
 */
export function renderTaskList(tasks, container, sessionId) {
  if (!container) container = document.getElementById('task-list');
  if (!container) return;

  lastPanelSessionId = sessionId || null;
  // 2026-09-02: Nebula 统一面板并入 Flow Map 节点条目（成员会话面板不显示）。
  // 首渲全量对齐一次快照（此后事件驱动），补齐页面打开前已在跑的节点。
  const showNodes = sessionShowsTeamTasks(sessionId);
  if (showNodes && !nodeSnapshotLoaded) {
    nodeSnapshotLoaded = true;
    refreshNodeSnapshot();
  }
  const nodes = showNodes ? collectNodes() : [];

  // 裁定②: Nebula 会话的任务列表 = 统一视图——session 域任务 + team 域任务
  // （state.teamTasks，teamTaskListUpdate 帧维护）。非 Nebula 会话不变。
  const visible = mergeTeamTasks(tasks, sessionId).filter(isVisible);
  container.classList.toggle('has-tasks', visible.length > 0 || nodes.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(visible.length > 0 || nodes.length > 0);
  if (visible.length === 0 && nodes.length === 0) {
    container.innerHTML = '';
    return;
  }

  // 裁定 3 (跨区移动) 已退役（#15 单一 progress 区，无跨区）——普通更新直接
  // 重绘，新行带 v1 task-entering（裁定 2 状态过渡）。
  const oldById = collectZoneById(container);
  redraw(visible, container, sessionId, oldById, nodes);
}

/** Map taskId/nodeKey → zone ('progress') from the current DOM (entry-animation
 *  comparison only — a #15 single-zone panel always resolves 'progress'). */
function collectZoneById(container) {
  const m = new Map();
  container.querySelectorAll('.task-item[data-task-id], .task-item[data-node-key]').forEach((el) => {
    m.set(el.dataset.taskId || el.dataset.nodeKey, 'progress');
  });
  return m;
}

function redraw(visible, container, sessionId, oldById, nodes) {
  // #15: 单一 progress 区，四态进展展示（无 todo 子块、无 failed 沉底，visible
  // 集本就只含 pending+in_progress）: agent active (updatedAt desc), 裁定④
  // 三级分组 team → member → tasks 在排序之后。
  const progress = visible.filter(tk => kindOf(tk) === 'agent').sort(sortProgress);

  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats (C26 — #15 progress-only count) ──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: progress.length });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: single progress zone ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const progressSection = document.createElement('div');
  progressSection.className = 'task-section task-section-progress';
  progressSection.appendChild(buildGroupHeader(t('task.sectionProgress'), 'progress'));
  if (progress.length === 0) {
    progressSection.appendChild(buildEmpty(t('task.progressEmpty')));
  } else {
    // 裁定④ + #15 grouping: ungrouped (session-local) tasks flat first, then
    // team → member → tasks. First-appearance order preserves sortProgress.
    const ungrouped = [];
    const teamOrder = [];
    const byTeam = new Map(); // team → Map(member → tasks)
    for (const tk of progress) {
      const team = taskTeam(tk);
      if (!team) { ungrouped.push(tk); continue; }
      let g = byTeam.get(team);
      if (!g) { g = new Map(); byTeam.set(team, g); teamOrder.push(team); }
      const member = taskMember(tk) || '';
      if (!g.has(member)) g.set(member, []);
      g.get(member).push(tk);
    }
    ungrouped.forEach(tk => progressSection.appendChild(buildRow(tk, sessionId)));
    for (const team of teamOrder) {
      const teamEl = document.createElement('div');
      teamEl.className = 'task-subgroup';
      teamEl.appendChild(buildSubgroupHeader(team, 'team'));
      for (const [member, memberTasks] of byTeam.get(team)) {
        const memEl = document.createElement('div');
        memEl.className = 'task-member-group';
        if (member) memEl.appendChild(buildSubgroupHeader(member, 'member'));
        memberTasks.forEach(tk => memEl.appendChild(buildRow(tk, sessionId)));
        teamEl.appendChild(memEl);
      }
      progressSection.appendChild(teamEl);
    }
  }

  inner.appendChild(progressSection);

  // ── Flow Map 节点条目区（2026-09-02）：按项目分组的节点行，仅在有节点时渲染。
  // 分组头沿用 team 分组的 .task-subgroup-header 设计语言（project 之于节点 ≙
  // team 之于任务）；空任务 + 有节点时面板仍打开，progress 区照常显示空态行。 ──
  if (nodes && nodes.length > 0) {
    const nodesSection = document.createElement('div');
    nodesSection.className = 'task-section task-section-nodes';
    nodesSection.appendChild(buildGroupHeader(t('flowmap.title'), 'nodes'));
    const byProject = new Map();
    for (const it of nodes) {
      if (!byProject.has(it.project)) byProject.set(it.project, []);
      byProject.get(it.project).push(it);
    }
    for (const [project, items] of byProject) {
      const group = document.createElement('div');
      group.className = 'task-subgroup';
      group.appendChild(buildSubgroupHeader(project, 'team'));
      for (const { node } of items) group.appendChild(buildNodeRow(node, project));
      nodesSection.appendChild(group);
    }
    inner.appendChild(nodesSection);
  }

  body.appendChild(inner);
  card.appendChild(header);
  card.appendChild(body);
  container.appendChild(card);

  createIconsIn(container);

  toggle.addEventListener('click', () => {
    const nowCollapsed = card.classList.toggle('collapsed');
    container.dataset.collapsed = nowCollapsed ? '1' : '';
    toggle.innerHTML = `<i data-lucide="${nowCollapsed ? 'chevron-down' : 'chevron-up'}"></i>`;
    toggle.title = nowCollapsed ? t('task.expand') : t('task.collapse');
    createIconsIn(toggle);
  });

  // 裁定 2: entry animation for brand-new rows.
  if (!REDUCED_MOTION) {
    const items = /** @type {NodeListOf<HTMLElement>} */ (container.querySelectorAll('.task-item'));
    items.forEach((el) => {
      if (!oldById.has(el.dataset.taskId)) {
        el.classList.add('task-entering');
        el.addEventListener('animationend', () => el.classList.remove('task-entering'), { once: true });
      }
    });
  }
}
