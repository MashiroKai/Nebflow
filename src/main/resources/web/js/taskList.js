// taskList.js — Collapsible task panel (floats above the input area)
// 任务面板 = 纯 Flow Map 节点视图（作者 2026-09-05 10:54 裁定，taskpanel-audit
// 批落地；推翻 67e69bf1 的「旧任务路径保留」取舍）：
//   - 旧任务区整体退役：taskListUpdate / teamTaskListUpdate 数据路径（渲染层
//     + main.js handler + state 字段 + ws.js 路由表项）、.task-section-progress
//     区块、旧任务行/三级分组、统计中的旧任务计数，全部移除。
//   - 头部统计 = 仅节点数（键复用 task.statsProgress）。
//   - 节点视图机制（2026-09-02 作者裁定 + 2026-09-05 徽章裁定）保留不动：
//     WS nodeCreated/Updated/Completed/Removed 自包含订阅 + REST 快照对齐
//     （_wsTs 防回滚）+ 八态徽章映射 + 点击行 → Flow Map 跳转聚焦。
//   - 主图同源过滤（2026-09-05 12:59 作者裁定 → 链级抽象 P0 单源收口）：可见性
//     = 后端活动区事实本身——整链归档由后端 sweep 出活动区并广播 nodeRemoved
//     （缓存删除）+ 快照对账消化，前端零链派生零判据（旧 deriveArchivedIds/
//     clusterBatches 时间批镜像已删除，判据唯一存在于后端 FlowMapStore）。
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect } from './ws.js';
import { fetchProjects, fetchFlowMap, NODE_STATUS_CLS } from './nodeData.js';

// ── Flow Map 节点条目（2026-09-02 作者裁定：节点作为条目并入任务列表）──────
// [节点区块 · 独立命名空间] 数据 = WS 节点广播帧（nodeCreated/Updated/Completed/
// Removed，全局广播 {type, project, nodeId, node}，契约 20260901_project-node-contract
// §2）增量维护本地缓存 + 连接建立时 NodeList 全量快照（GET /api/projects/<n>/flow-map）
// 对齐一次；事件驱动，无轮询。渲染/CSS 类（.task-node-*）/判定条件全部节点自有，
// 与上方 team 区块零交叉引用（v2 解耦，2026-09-02 作者反馈③）。终态节点在 5min
// TTL 窗口内仍显示（与 Flow Map 一致），到期由 nodeRemoved 移除。

/** 节点区块自有判定：Nebula 统一面板才显示节点条目。独立命名空间（不复用
 *  已退役的旧任务域判定）——team/旧任务区退役不影响节点区块。 */
function sessionShowsNodeEntries(sid) {
  if (!sid) return false;
  const agent = state.sessionAgentMap ? state.sessionAgentMap[sid] : null;
  return !agent || agent === 'Nebula';
}

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

/** 面板节点条目数据：全部项目的活跃节点，最近活动在前。主图同源可见性
 *  （2026-09-05 12:59 作者裁定，P0 单源收口）= 后端活动区事实本身：节点留在
 *  缓存即显示（含已终态、等待后端 sweep 的成员），后端 sweep 整链出库广播
 *  nodeRemoved → 缓存删除即隐藏，快照对账兜底收敛——前端零链派生零判据
 *  （旧 deriveArchivedIds 链资格镜像已删，归档资格判定唯一存在于后端）。
 *  过滤后无节点 = 主图空 = 面板收起（既有空态兜底）。 */
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
  if (!sid || !sessionShowsNodeEntries(sid)) return;
  renderTaskList([], undefined, sid); // 首参占位——旧任务入参已退役（纯节点视图）
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
  // 项目删除收敛（D5，mem-diag 20260907）：快照项目清单来自 fetchProjects()
  // 全集——不在清单里的项目 = 已删除（后端无 projectRemoved 帧），其缓存条目
  // 随本次对账移除（此前只增不减，删除的项目永久滞留内存）。
  const seenProjects = new Set(results.map(([name]) => name));
  for (const name of Array.from(nodeCache.keys())) {
    if (!seenProjects.has(name)) nodeCache.delete(name);
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

/** §15.7: 60s interval, only while the panel has visible rows; textContent
 *  direct update, no animation. */
let lastActiveTimer = null;
function refreshLastActive() {
  const container = document.getElementById('task-list');
  if (!container) return;
  const els = /** @type {NodeListOf<HTMLElement>} */ (
    // 纯节点视图（2026-09-05 裁定）：面板行只剩节点行（.task-node-time）
    container.querySelectorAll('.task-node-time'));
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

// ── Row builders ────────────────────────────────────────────────────────
// 旧任务行构建器（buildCheck/buildRow：四态 glyph + subject/desc/meta + 状态词 +
// 相对时间）随旧任务区退役整体移除（2026-09-05 10:54 裁定）——面板行只剩
// 节点行 buildNodeRow。

// ── Flow Map 节点行（2026-09-02）：复用任务行设计语言 ────────────────────

// 状态词映射（2026-09-05 作者裁定：wiring/pending→待处理、running→进行中、
// blocked→阻塞、completed→已完成、failed→失败、cancelled→已取消）。
// 复用既有键 running→task.inProgressShort（进行中）、completed→flowmap.done、
// failed→flowmap.fail、cancelled→flows.status.cancelled；新增两键（zh/en 成对）：
// task.nodePending / task.nodeBlocked。
const NODE_WORD_KEY = {
  wiring: 'task.nodePending',
  pending: 'task.nodePending',
  running: 'task.inProgressShort',
  blocked: 'task.nodeBlocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flows.status.cancelled',
};

/**
 * 节点条目行（v2 简约化，2026-09-02 作者反馈）：节点名 + 状态 glyph + 状态词 +
 * 相对时间 + agent 标注。设计规范：
 *   - glyph 统一 12px 节奏（对齐任务行 pending 小方框，宁小勿大）；
 *     running = 品牌绿（--color-primary）转动圆环，与 completed 静态实心绿点
 *     （--color-success）靠形态+色相双区分。
 *   - 状态词全部 muted（颜色信号由 glyph 独占——减装饰）。
 *   - meta 只标 agent（project 已由分组头承载——信息减法）。
 *   - 未知状态（后续 blocked 等新状态）优雅降级：中性半透明点、无状态词，不崩。
 * 点击行 → 打开该项目 Flow Map 就地视图并高亮节点（动态 import projectTab，
 * 避免模块图静态耦合）。
 */
function buildNodeGlyph(st, cls) {
  const g = document.createElement('span');
  g.setAttribute('aria-hidden', 'true');
  if (st === 'running') {
    g.className = 'task-node-spin';
  } else if (st === 'blocked') {
    // blocked（阻塞）：琥珀实心点（警示态，区别于 failed 终结红——与
    // flowmap.css .fm-node.blocked 的 --amber 描边语义一致）
    g.className = 'task-node-dot task-node-dot-blocked';
  } else if (st === 'completed' || st === 'failed' || st === 'cancelled') {
    g.className = `task-node-dot task-node-dot-${cls}`;
  } else if (st === 'wiring' || st === 'pending') {
    g.className = 'task-node-box';
  } else {
    g.className = 'task-node-dot task-node-dot-neutral'; // 未知状态中性降级
  }
  return g;
}

function buildNodeRow(node, project) {
  const st = String(node.status || 'pending');
  const cls = NODE_STATUS_CLS[st] || ''; // wiring → pending（等待色）；未知 → ''
  const wordKey = NODE_WORD_KEY[st];

  const row = document.createElement('div');
  row.className = 'task-node' + (cls ? ` task-node-${cls}` : '');
  row.dataset.nodeKey = `${project}:${node.id}`;
  row.dataset.project = project;
  row.dataset.nodeId = node.id;
  row.setAttribute('role', 'button');
  row.setAttribute('aria-label',
    `${node.name || node.id}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  row.title = t('project.openFlowMap', { name: project });

  row.appendChild(buildNodeGlyph(st, cls));

  const text = document.createElement('div');
  text.className = 'task-node-text';
  const label = document.createElement('span');
  label.className = 'task-node-label';
  label.textContent = node.name || node.id;
  text.appendChild(label);
  // project 已由分组头承载，meta 只标节点自身信息：description 优先（agent 字段已
  // 退役——新建节点恒 "general" 零信息量，节点行副行显示它等于空信息）；缺失
  // （存量节点）→ 回落 agent 名；两者皆无 → 不渲染（行更矮更净，零空行）。
  // 数据源 = 默认载荷 description（ProjectTypes.scala NodePayload 基础字段，随
  // fetchFlowMap 快照与 WS 节点帧同构到达，零新增取数）。窄栏由 CSS 省略号截断，
  // 悬停全文挂 metaEl.title——row.title 的「打开 Flow Map」语义与 aria-label 不动。
  const desc = typeof node.description === 'string' ? node.description.trim() : '';
  const agent = typeof node.agent === 'string' ? node.agent : '';
  const metaText = desc || agent;
  if (metaText) {
    const metaEl = document.createElement('span');
    metaEl.className = 'task-node-meta';
    metaEl.setAttribute('aria-hidden', 'true');
    metaEl.textContent = metaText;
    if (desc) metaEl.title = desc; // 截断（CSS text-overflow: ellipsis）→ 悬停全文
    text.appendChild(metaEl);
  }
  row.appendChild(text);

  if (wordKey) {
    const wordEl = document.createElement('span');
    wordEl.className = 'task-node-word';
    wordEl.setAttribute('aria-hidden', 'true');
    wordEl.textContent = t(wordKey);
    row.appendChild(wordEl);
  }

  const ms = nodeTs(node);
  if (ms > 0) {
    const time = document.createElement('span');
    time.className = 'task-node-time';
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

/** 节点区块分区装配（独立于 redraw 的 team 分组逻辑）：按项目分组直排。
 *  2026-09-06 显示优化批：「Flow Map」分区标题元素整体移除（作者 00:35 裁定；
 *  flowmap.title i18n 键保留——Flow Map 标签页域共用）。无节点返回 null。 */
function buildNodeSection(nodes) {
  if (!nodes || nodes.length === 0) return null;
  const section = document.createElement('div');
  section.className = 'task-section task-section-nodes';
  const byProject = new Map();
  for (const it of nodes) {
    if (!byProject.has(it.project)) byProject.set(it.project, []);
    byProject.get(it.project).push(it);
  }
  for (const [project, items] of byProject) {
    const group = document.createElement('div');
    group.className = 'task-node-group';
    const h = document.createElement('div');
    h.className = 'task-node-group-header';
    h.textContent = project;
    group.appendChild(h);
    for (const { node } of items) group.appendChild(buildNodeRow(node, project));
    section.appendChild(group);
  }
  return section;
}

/** Empty-state builder（兜底保留，2026-09-05 10:54 裁定「空态兜底保留」）：
 *  主路径不接——无节点时面板整体收起（.has-tasks 才展开，与 67e69bf1 行为
 *  一致）。键 task.progressEmpty 保留（zh/en 成对），供后续区块复用。 */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render（纯节点视图，2026-09-05 10:54 裁定）─────────────────────

/**
 * 渲染任务面板 = 纯 Flow Map 节点视图。首参为旧任务入参占位（_tasks 忽略，
 * 调用方兼容——旧任务数据路径已退役，无消费方再传真实旧任务）。
 * @param {Array} [_tasks] 旧任务入参——2026-09-05 裁定退役，忽略
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — gates node visibility
 */
export function renderTaskList(_tasks, container, sessionId) {
  if (!container) container = document.getElementById('task-list');
  if (!container) return;

  lastPanelSessionId = sessionId || null;
  // 2026-09-02: Nebula 统一面板显示 Flow Map 节点条目（成员会话面板不显示）。
  // 首渲全量对齐一次快照（此后事件驱动），补齐页面打开前已在跑的节点。
  const showNodes = sessionShowsNodeEntries(sessionId);
  if (showNodes && !nodeSnapshotLoaded) {
    nodeSnapshotLoaded = true;
    refreshNodeSnapshot();
  }
  const nodes = showNodes ? collectNodes() : [];

  // 纯节点视图：统计/行全节点口径。无节点 → 面板收起（.has-tasks 才展开）。
  container.classList.toggle('has-tasks', nodes.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(nodes.length > 0);
  if (nodes.length === 0) {
    container.innerHTML = '';
    return;
  }

  redraw(container, nodes);
}

/** Map nodeKey → 存在性 from the current DOM (entry-animation comparison:
 *  已存在的行不重播入场动画；纯节点视图单区，zone-diff 概念随旧任务区退役)。 */
function collectRowKeys(container) {
  const keys = new Set();
  container.querySelectorAll('.task-node[data-node-key]').forEach((el) => {
    keys.add(el.dataset.nodeKey);
  });
  return keys;
}

function redraw(container, nodes) {
  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats（统计 = 仅节点数，2026-09-05 10:54 裁定；
  //  键复用 task.statsProgress「{progress} 任务」——语义仍成立）──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: nodes.length });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: 节点区块（面板唯一内容区）──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  // 节点区块装配在 buildNodeSection 内全程使用 .task-node-* 命名空间。
  const nodesSection = buildNodeSection(nodes);
  if (nodesSection) inner.appendChild(nodesSection);

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

  // Entry animation for brand-new node rows（旧任务行 task-entering 随退役
  // 移除——节点行动画名独立 task-node-entering）。
  if (!REDUCED_MOTION) {
    const existingKeys = collectRowKeys(container);
    const items = /** @type {NodeListOf<HTMLElement>} */ (
      container.querySelectorAll('.task-node'));
    items.forEach((el) => {
      if (existingKeys.has(el.dataset.nodeKey)) return;
      el.classList.add('task-node-entering');
      el.addEventListener('animationend', () => el.classList.remove('task-node-entering'), { once: true });
    });
  }
}
