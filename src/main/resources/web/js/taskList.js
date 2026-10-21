// taskList.js — Collapsible task panel (floats above the input area)
//
// Panel face = chain blocks over the delegate plane (the classic flowmap-era
// chain-structure list, re-sourced from the delegate read port):
//   - One CHAIN BLOCK per live delegate: a header row (the chain's root node)
//     carrying the status glyph + external-executor badge (九点② dedup:
//     internal executors carry none) + #N + title + n/N progress meta + muted
//     status word + relative time, and a fold twisty when the chain has
//     members. Member rows (sub-agent children and
//     AgentFlow flow-steps) render nested under the header in topological
//     order (depth ↑, createdAt ↑, id ↑) with a hierarchical sequence label
//     carrying depth past the CSS indent cap.
//   - Fold discipline: chains start collapsed; header click toggles (render-
//     layer memory Set, refresh resets — same as the classic design). The
//     dotmap/meta zone clicks through to the chain's L1 flowmap (the chain-
//     level jump, badge-click analogue); member rows click through to L1 with
//     the node anchor (camera focus + flash). Leaf roots (no members) click
//     straight to L1. External executors and ready-to-merge rows stay
//     display-only (open-native exit / queue rank chip instead).
//   - Motion: member rows carry the classic transition marks — running spin,
//     completed check draws itself, failed cross — only on a real frame-over-
//     frame status change (first paint and same-state frames stay silent);
//     the chain block pops on first appearance; the status word rolls in on
//     transition. prefers-reduced-motion lands everything static.
//   - Data: delegateChainStub.js read port (listDelegateRows / getChainByTask /
//     listDelegateChildren); the live provider mirror is wired in main.js.
//     Re-render triggers: delegate-chain-mirror-updated + session switch + the
//     S16 progress poll. Zero WS knowledge, zero derived second authorities
//     (topology edges are rebuilt from per-node declared fields only).
//   - Header stats = total unit count (roots + members), fold-independent.
import { t } from './i18n.js';
import { createIconsIn, onMinuteTick } from './utils.js';
import state from './state.js';
import { NODE_STATUS_CLS } from './nodeData.js';
// Delegate rows: data source = delegateChainStub.js read port (listDelegateRows /
// getChainByTask / listDelegateChildren). The live provider is attached in
// main.js (single seam); fixtures always win when present (harness seam).
import { listDelegateRows, getChainByTask, listDelegateChildren } from './delegateChainStub.js';
import { fetchDelegateOutput, openDelegateNative } from './delegateApi.js';
import { executorIcon } from './executorIcons.js';

/**
 * Single inline entry to the L1 per-task chain view (the delegate flowmap).
 * Row click / dotmap chain jump / member anchor all funnel through here
 * (dynamic import keeps the module graph decoupled).
 * @param {string} key @param {any} meta
 * @param {{anchorNode?: string}} [opts] */
function openL1(key, meta, opts = {}) {
  import('./delegateChainView.js').then(({ openDelegateChainTask }) => {
    openDelegateChainTask(key, meta, opts);
  }).catch(() => {});
}

// ── Panel session gate ───────────────────────────────────────────────────
/** Only the Nebula unified panel renders rows. */
/** 导出 = 手机层 v3 任务面板的同源面板会话门（mobile/taskPanel.js）。 */
export function sessionShowsNodeEntries(sid) {
  if (!sid) return false;
  const agent = state.sessionAgentMap ? state.sessionAgentMap[sid] : null;
  return !agent || agent === 'Nebula';
}

/** Session the panel was last rendered for: mirror events re-render against it. */
let lastPanelSessionId = null;

function rerenderWithNodes() {
  const sid = lastPanelSessionId;
  if (!sid || !sessionShowsNodeEntries(sid)) return;
  renderTaskList([], undefined, sid); // first arg is a retired placeholder
}

// Provider mirror wiring: the provider broadcasts this event after every
// refresh (boot / WS-triggered debounce / reconnect reconcile). Payload-free;
// REST remains the sole authority.
const DELEGATE_MIRROR_EVENT = 'delegate-chain-mirror-updated';
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener(DELEGATE_MIRROR_EVENT, () => { rerenderWithNodes(); });
}

// ── §15.7 relative time ──────────────────────────────────────────────────
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

/** §15.7: minute-grid tick, only while the panel has visible rows; textContent
 *  direct update, no animation. */
let lastActiveTimer = null;
function refreshLastActive() {
  const container = document.getElementById('task-list');
  if (!container) return;
  const els = /** @type {NodeListOf<HTMLElement>} */ (
    container.querySelectorAll('.task-node-time, .task-chain-member-time'));
  els.forEach((el) => {
    const ts = el.dataset.ts;
    if (ts) el.textContent = formatLastActive(ts);
  });
}
function ensureLastActiveTimer(running) {
  if (running && !lastActiveTimer) {
    // perf-481 A5: relative timestamps only need minute granularity, so this
    // shares the page-wide wall-clock minute grid (utils.onMinuteTick).
    lastActiveTimer = onMinuteTick(() => {
      if (document.hidden) return;
      refreshLastActive();
    });
  } else if (!running && lastActiveTimer) {
    lastActiveTimer();
    lastActiveTimer = null;
  }
}

const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

// Only live delegates stay listed: terminal rows leave the panel as they
// complete (still reachable in the structure view and the archive drawer).
// ready-to-merge stays — queued merges are still active work.
// #566 修复批：blocked 从 delegate 行值域退役（作者令「blocked 是给 agentflow
// 用的」）——delegate 根行永不 blocked；chain 成员（agentflow 节点）仍可 blocked。
const ACTIVE_ROW_STATUSES = new Set(['pending', 'running', 'ready-to-merge']);

/**
 * Status glyph, shared row language: running = orbit, terminal = solid color
 * dot, wiring|pending = box, unknown = neutral dot (graceful degradation).
 * (#566: the blocked branch retired from the delegate header — flow-step
 * member rows render their own marks.)
 * @param {string} st @param {string} cls @returns {HTMLElement} */
function buildNodeGlyph(st, cls) {
  const g = document.createElement('span');
  g.setAttribute('aria-hidden', 'true');
  if (st === 'running') {
    // Classic list running face: the single brand-green spin ring (reads as
    // "in progress" at 12px — the three-dot orbit collapsed into a cryptic
    // clover at this scale; the orbit language stays on the map cards).
    g.className = 'task-node-spin';
  } else if (st === 'completed' || st === 'failed' || st === 'cancelled') {
    g.className = `task-node-dot task-node-dot-${cls}`;
  } else if (st === 'wiring' || st === 'pending') {
    g.className = 'task-node-box';
  } else {
    g.className = 'task-node-dot task-node-dot-neutral';
  }
  return g;
}

/** Status word keys (existing locale keys only; domain = stub ROW_STATUS).
 *  blocked 键保留：agentflow flow-step 成员行合法态（#566：delegate 根行不再产生）。
 *  导出 = 手机层 v3 任务面板的同源消费缝（mobile/taskPanel.js 只换呈现不换数据面）。 */
export const DELEGATE_WORD_KEY = {
  pending: 'task.nodePending',
  running: 'task.inProgressShort',
  blocked: 'task.nodeBlocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
  'ready-to-merge': 'delegate.readyToMerge',
};

/** Star-child store status → row status domain (conservative: unknown = running). */
function childRowStatus(status) {
  if (status === 'completed') return 'completed';
  if (status === 'failed') return 'failed';
  return 'running'; // running | restarting | unknown
}

/** Row key of a delegate row (chain read-port key): taskNo preferred, delegateId fallback. */
function delegateRowKey(r) {
  return String((r && (r.taskNo || r.delegateId)) || '');
}

// ── Chain topology (ported from the classic chain view) ──────────────────
// Edges are rebuilt from the members' OWN declared fields (out ∪ deps ∪ in) —
// this rebuilds per-node declarations, never membership (that authority stays
// in the engine/read port). Target resolution: id hit, then node-name
// fallback; unresolvable ⇒ dropped.

/** Epoch ms for a member-ish record (createdAt/spawnedAt; ISO tolerated). */
function memberTs(m) {
  const raw = m && (m.createdAt ?? m.spawnedAt);
  if (typeof raw === 'number') return raw;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

/** @param {any[]} members @returns {{ids: Set<string>, adj: Map<string, Set<string>>}} */
function chainEdgesOf(members) {
  const list = Array.isArray(members) ? members : [];
  const ids = new Set(list.map((m) => String(m.id)));
  const byName = new Map();
  for (const m of list) {
    const nm = String(m.name || '');
    if (nm && !byName.has(nm)) byName.set(nm, String(m.id));
  }
  /** @type {Map<string, Set<string>>} */
  const adj = new Map();
  for (const id of ids) adj.set(id, new Set());
  const resolve = (raw) => {
    const s = String(raw == null ? '' : raw).replace(/^chain:/, '');
    if (!s) return null;
    if (ids.has(s)) return s;
    return byName.get(s) || null;
  };
  const link = (from, to) => {
    if (from == null || to == null || from === to) return;
    const set = adj.get(from);
    if (set && !set.has(to)) set.add(to);
  };
  for (const m of list) {
    const self = String(m.id);
    for (const e of (Array.isArray(m.out) ? m.out : [])) {
      if (!e || typeof e !== 'object') continue;
      if (String(e.mode || '') === 'loop' || String(e.to || '') === 'Nebula') continue;
      link(self, resolve(e.to));
    }
    for (const raw of (Array.isArray(m.in) ? m.in : [])) link(resolve(raw), self);
    for (const raw of (Array.isArray(m.deps) ? m.deps : [])) link(resolve(raw), self);
  }
  return { ids, adj };
}

/** Longest-path depth per member (Kahn sweep). Cycle survivors fall back to 0. */
function chainDepthsOf(edges) {
  const indeg = new Map();
  for (const id of edges.ids) indeg.set(id, 0);
  for (const [, set] of edges.adj) for (const to of set) indeg.set(to, (indeg.get(to) || 0) + 1);
  const depth = new Map();
  const queue = [];
  for (const id of edges.ids) {
    if ((indeg.get(id) || 0) === 0) { depth.set(id, 0); queue.push(id); }
  }
  for (let i = 0; i < queue.length; i++) {
    const from = queue[i];
    for (const to of (edges.adj.get(from) || [])) {
      depth.set(to, Math.max(depth.get(to) || 0, (depth.get(from) || 0) + 1));
      indeg.set(to, (indeg.get(to) || 0) - 1);
      if ((indeg.get(to) || 0) === 0) queue.push(to);
    }
  }
  for (const id of edges.ids) if (!depth.has(id)) depth.set(id, 0);
  return depth;
}

/** Topological order — the deterministic three-key sort (depth ↑, ts ↑, id ↑). */
function chainOrderOf(members, depths) {
  const list = Array.isArray(members) ? members : [];
  return [...list].sort((a, b) => {
    const da = depths.get(String(a.id)) || 0;
    const db = depths.get(String(b.id)) || 0;
    if (da !== db) return da - db;
    const ta = memberTs(a);
    const tb = memberTs(b);
    if (ta !== tb) return ta - tb;
    const ia = String(a.id);
    const ib = String(b.id);
    return ia < ib ? -1 : (ia > ib ? 1 : 0);
  });
}

/** Hierarchical sequence labels ("1", "1.1", "1.1.2") in topological order. */
function chainSeqLabels(ordered, depths) {
  const counters = [];
  const labels = new Map();
  for (const m of ordered) {
    const d = depths.get(String(m.id)) || 0;
    counters.length = d + 1;
    for (let i = 0; i <= d; i++) if (!counters[i]) counters[i] = 0;
    counters[d] += 1;
    labels.set(String(m.id), counters.slice(0, d + 1).join('.'));
  }
  return labels;
}

/** Chain view model for one block (pure; derived once per render). */
function chainViewOf(members) {
  const edges = chainEdgesOf(members);
  const depths = chainDepthsOf(edges);
  const ordered = chainOrderOf(members, depths);
  return { ordered, edges, depths, seqs: chainSeqLabels(ordered, depths) };
}

// ── Chain collection (delegate rows → chain blocks) ──────────────────────

/**
 * Members of one delegate chain as topology-shaped records: star children carry
 * a spawn edge from the root (`in: [rootId]`); flow-steps keep their in-chain
 * structural deps and entry steps (no in-chain dep) hang off the root — the
 * same projection the L1 map draws (single mental model, two faces).
 * The root participates in the topology pass (depth 0) so members layer at 1+.
 */
function chainMembersOf(r, chain) {
  const delegateId = String(r.delegateId || '');
  const rootId = delegateId || delegateRowKey(r);
  const rootStub = {
    id: rootId, name: String(r.title || r.delegateId || ''), status: String(r.status || 'running'),
    createdAt: r.createdAt, in: [], deps: [], out: [],
  };
  const members = [rootStub];
  const flowIds = new Set();
  const flowSteps = chain && Array.isArray(chain.nodes)
    ? chain.nodes.filter((n) => n && n.kind === 'flow-step') : [];
  for (const n of flowSteps) flowIds.add(String(n.id));
  for (const c of listDelegateChildren(delegateId)) {
    const c0 = c && typeof c === 'object' ? c : {};
    const id = String(c0.taskId || '');
    if (!id || id === rootId) continue;
    members.push({
      id, kind: 'child',
      name: String(c0.agentName || '') || id,
      status: childRowStatus(c0.status),
      description: String(c0.description || c0.prompt || ''),
      createdAt: typeof c0.spawnedAt === 'number' ? c0.spawnedAt : 0,
      completedAt: typeof c0.completedAt === 'number' && c0.completedAt > 0 ? c0.completedAt : 0,
      in: [rootId], deps: [], out: [],
    });
  }
  for (const n of flowSteps) {
    const id = String(n.id || '');
    if (!id || id === rootId) continue;
    const deps = (Array.isArray(n.deps) ? n.deps : [])
      .map((d) => String(d || '')).filter((d) => flowIds.has(d) && d !== id);
    members.push({
      id, kind: 'flow',
      name: String(n.label || n.id || ''),
      status: String(n.status || 'pending'),
      description: String(n.description || ''),
      createdAt: 0, // the chain projection carries no per-node timestamps
      in: [], deps: deps.length ? deps : [rootId], out: [],
    });
  }
  return members;
}

/**
 * Collect the chain blocks from the delegate read port: every live delegate
 * root plus its members. Order = most-recent root activity first (roots sort
 * by createdAt; members keep topological order inside their block).
 * @returns {Array<{key: string, project: string, row: any, members: any[],
 *          allCount: number,
 *          view: {ordered: any[], edges: any, depths: Map<string, number>, seqs: Map<string, string>}}>} */
/** 导出 = 手机层 v3 任务面板的同源数据面（mobile/taskPanel.js 只换呈现不换数据面）。
 *  返回 collectChains 现值（active rows only，activity 倒序）；字段与面板行渲染
 *  完全同源 —— 零第二数据权威。 */
export function collectChains() {
  const rows = listDelegateRows()
    .filter((r) => r && ACTIVE_ROW_STATUSES.has(String(r.status)) && r.archivedAt == null);
  const ts0 = (r) => {
    const v = typeof r.createdAt === 'number' ? r.createdAt : Date.parse(String(r.createdAt || ''));
    return Number.isFinite(v) ? v : 0;
  };
  rows.sort((a, b) => ts0(b) - ts0(a));
  return rows.map((r) => {
    const key = delegateRowKey(r);
    // External executors carry no platform children/chains — the block is the
    // root stub alone (leaf header row; stats still count it as one unit).
    const chain = r.external === true ? null : getChainByTask(key);
    const members = chainMembersOf(r, chain);
    // ready-to-merge rows stay display-only (queue rank only): members hidden.
    const visibleMembers = String(r.status) === 'ready-to-merge'
      ? members.slice(0, 1)
      : members;
    return {
      key, project: String(r.project || ''), row: r,
      members: visibleMembers,
      view: chainViewOf(visibleMembers),
      allCount: members.length,
    };
  }).filter((c) => c.key);
}

// ── Fold state + frame-diff animation memory (render-layer, refresh resets) ─
/** Collapsed chain keys (taskNo ‖ delegateId). Default = collapsed (classic
 *  chain-block semantics); the Set holds no entry for expanded chains… folded
 *  by default means membership in nothing — so this Set tracks EXPANDED keys. */
const expandedChains = new Set();
/** Previous-frame member status / chain presence (transition marks fire only
 *  on a real status change; first paint and same-state frames stay silent). */
const memberStatusPrev = new Map();
const memberStatusNext = new Map();
const chainSeenPrev = new Set();
const chainSeenNext = new Set();

// ── Row builders ─────────────────────────────────────────────────────────

/** Shared meta carrier handed to the L1 view (raw row fields, no derivation). */
/** 导出 = 手机层 v3 任务卡的 L1 跳转 meta（mobile/taskPanel.js 同源复用）。 */
export function rowMetaOf(r) {
  const row0 = r && typeof r === 'object' ? r : {};
  return {
    taskNo: String(row0.taskNo || ''),
    delegateId: String(row0.delegateId || ''),
    title: String(row0.title || ''),
    status: String(row0.status || ''),
    executor: String(row0.executor || ''),
    project: String(row0.project || ''),
  };
}

/** Relative-time slot (shared by all row kinds). ts = epoch ms; 0/NaN = no element. */
function appendTimeSlot(row, ms) {
  if (!Number.isFinite(ms) || ms <= 0) return;
  const time = document.createElement('span');
  time.className = 'task-node-time';
  time.setAttribute('aria-hidden', 'true');
  time.dataset.ts = new Date(ms).toISOString();
  time.textContent = formatLastActive(time.dataset.ts);
  row.appendChild(time);
}

/** Muted status word slot (key lookup is tolerant: unknown status = no word). */
function appendWordSlot(row, wordKey) {
  if (!wordKey) return;
  const wordEl = document.createElement('span');
  wordEl.className = 'task-node-word';
  wordEl.setAttribute('aria-hidden', 'true');
  wordEl.textContent = t(wordKey);
  row.appendChild(wordEl);
}

/** Meta line slot (description / progress tail; absent data = absent element). */
function appendMetaSlot(text, metaText) {
  const s = String(metaText || '').trim();
  if (!s) return;
  const metaEl = document.createElement('span');
  metaEl.className = 'task-node-meta';
  metaEl.setAttribute('aria-hidden', 'true');
  metaEl.textContent = s;
  metaEl.title = s; // truncated by CSS ellipsis → hover carries the full text
  text.appendChild(metaEl);
}

/** Fold twisty (12px affordance; rotates open via CSS when the block is
 *  expanded). Leaf roots get an invisible placeholder so every row's text
 *  column stays aligned (Finder discipline). */
function buildTwisty(leaf) {
  const c = document.createElement('span');
  c.className = 'task-chain-twisty' + (leaf ? ' task-chain-twisty-leaf' : '');
  c.setAttribute('aria-hidden', 'true');
  c.innerHTML = '<i data-lucide="chevron-right"></i>';
  return c;
}

/** Chain progress meta ("done/total" + working count + elapsed) — the classic
 *  collapsed-row reading. Members include the root stub, so total counts the
 *  root too (the chain as a whole). */
/** 导出 = 手机层 v3 任务卡的进度槽（mobile/taskPanel.js 同源复用）。 */
export function chainProgressMeta(members) {
  const list = Array.isArray(members) ? members : [];
  const done = list.filter((m) => ['completed', 'failed', 'cancelled'].includes(String(m.status))).length;
  const working = list.filter((m) => String(m.status) === 'running').length;
  const stamps = list.map(memberTs).filter((v) => v > 0);
  const bits = [`${done}/${list.length}`];
  if (working >= 2) bits.push(t('task.chain.activeCount', { n: String(working) }));
  if (stamps.length) {
    const mins = Math.max(0, Math.floor((Date.now() - Math.min(...stamps)) / 60_000));
    bits.push(mins < 1 ? t('task.justNow') : `${mins}m`);
  }
  return bits.join(' · ');
}

/**
 * Chain header row (the root node): `[twisty][glyph][executor badge — external
 * executors only, 九点② dedup] #N title [dotmap+meta] [word] [rank] [time]
 * [actions]`.
 * Click = fold toggle (membered chains) / open L1 (leaf roots); the dotmap+meta
 * zone = chain-level jump to the L1 map (stopPropagation). External executors
 * (open-native exit) and ready-to-merge rows (rank chip) stay display-only.
 * @param {any} block collectChains entry @returns {HTMLElement} */
function buildChainHeaderRow(block) {
  const r = block.row;
  const taskNo = String(r.taskNo == null ? '' : r.taskNo);
  const delegateId = String(r.delegateId || '');
  const title = String(r.title || '');
  const st = String(r.status || 'running');
  const wordKey = DELEGATE_WORD_KEY[st] || '';
  const external = r.external === true;
  const queued = st === 'ready-to-merge';
  const memberCount = block.view.ordered.length - 1; // minus the root stub
  const openable = !external && !queued && memberCount > 0;
  const clickable = openable || (!external && !queued); // openable folds; leaf opens L1

  const row = document.createElement('div');
  row.className = 'task-node task-delegate task-chain-head';
  row.dataset.delegateKey = block.key;
  row.dataset.taskNo = taskNo;
  row.dataset.delegateId = delegateId;
  row.dataset.delegateStatus = st;
  if (external) row.dataset.external = '1';
  const labelBits = [taskNo ? `#${taskNo}` : '', title || delegateId].filter(Boolean);
  row.setAttribute('aria-label', `${labelBits.join(' ')}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  if (clickable) {
    row.setAttribute('role', 'button');
    if (openable) row.setAttribute('aria-expanded', expandedChains.has(block.key) ? 'true' : 'false');
    row.title = openable ? t('task.chain.rowHint', { chain: title || delegateId, n: String(memberCount) })
      : t('delegate.flowmap.title');
  }

  row.appendChild(buildTwisty(!openable));
  row.appendChild(buildNodeGlyph(st, NODE_STATUS_CLS[st] || ''));
  // Executor badge — 九点② dedup discipline (the L1 card face's rule,
  // delegate-ui 2026-10-09, now on the panel row too): internal executors
  // (nebflow/empty) carry NO badge — the status glyph is the row's identity
  // visual (the old unfiltered slot rendered a nebflow logo / neutral gear-dot
  // ghost next to the glyph); external executors keep theirs, 12px in the
  // glyph-slot rhythm (tasklist-display-fixes 2026-10-19: the old 14px
  // unaligned mark crowded the title line).
  const executorId = String(r.executor || '');
  if (executorId && executorId !== 'nebflow') {
    const icon = document.createElement('span');
    icon.className = 'task-delegate-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.innerHTML = executorIcon(executorId, 12);
    row.appendChild(icon);
  }

  const text = document.createElement('div');
  text.className = 'task-node-text';
  const labelRow = document.createElement('span');
  labelRow.className = 'task-node-label-row';
  if (taskNo) {
    const no = document.createElement('span');
    no.className = 'task-delegate-no';
    no.textContent = `#${taskNo}`;
    labelRow.appendChild(no);
  }
  const label = document.createElement('span');
  label.className = 'task-node-label';
  label.textContent = title || delegateId; // raw data fallback, not copy
  labelRow.appendChild(label);
  text.appendChild(labelRow);
  if (external) {
    // S16 coarse progress leg: buffered output tail line (raw data).
    appendMetaSlot(text, progressTailText(delegateProgress.get(delegateId)));
  }
  row.appendChild(text);

  // Progress meta (membered chains only) doubles as the chain-level L1 jump
  // target (classic badge-click analogue: whole-chain fit view). Minimal face:
  // text only — the dotmap thumbnail was dropped (noisy at panel scale).
  if (memberCount > 0 && !queued) {
    const jump = document.createElement('span');
    jump.className = 'task-chain-jump';
    jump.setAttribute('role', 'button');
    jump.title = t('flowmap.chain.badgeHint', { chain: title || delegateId });
    jump.setAttribute('aria-label', jump.title);
    const meta = document.createElement('span');
    meta.className = 'task-chain-meta';
    meta.textContent = chainProgressMeta(block.view.ordered);
    jump.appendChild(meta);
    const meta0 = rowMetaOf(r);
    jump.addEventListener('click', (e) => {
      e.stopPropagation(); // chain-level jump ≠ row fold
      openL1(block.key, meta0);
    });
    row.appendChild(jump);
  }

  appendWordSlot(row, wordKey);

  // Ready-to-merge rows show only the queue rank (engine G5 position verbatim).
  if (queued && r.mergeQueuePos && typeof r.mergeQueuePos.position === 'number' &&
      r.mergeQueuePos.position > 0) {
    const qp = r.mergeQueuePos;
    const rankEl = document.createElement('span');
    rankEl.className = 'task-delegate-rank';
    rankEl.setAttribute('aria-hidden', 'true');
    rankEl.textContent = `#${qp.position}`;
    const titleBits = [`#${qp.position}`, qp.total ? `/ ${qp.total}` : '',
      qp.branch || ''].filter(Boolean);
    rankEl.title = titleBits.join(' '); // raw data (rank / branch), not copy
    row.appendChild(rankEl);
  }

  const createdAt = typeof r.createdAt === 'number' ? r.createdAt : Date.parse(String(r.createdAt || ''));
  appendTimeSlot(row, createdAt);

  // Trailing action zone: external rows only (S16 open-native exit).
  if (external) {
    const actions = document.createElement('span');
    actions.className = 'task-delegate-actions';
    const nativeBtn = document.createElement('button');
    nativeBtn.type = 'button';
    nativeBtn.className = 'task-delegate-btn';
    nativeBtn.title = t('delegate.openNative');
    nativeBtn.setAttribute('aria-label', t('delegate.openNative'));
    nativeBtn.innerHTML = '<i data-lucide="external-link"></i>';
    nativeBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!delegateId || nativeBtn.disabled) return;
      nativeBtn.disabled = true; // in-flight reentry guard (the endpoint really opens a terminal)
      const res = await openDelegateNative(delegateId);
      nativeBtn.disabled = false;
      if (res.state !== 'ok') {
        console.warn(`[tasklist] open-native refused (${res.state}${res.state === 'http' ? ` ${res.status}` : ''})`);
      }
    });
    actions.appendChild(nativeBtn);
    row.appendChild(actions);
  }

  if (clickable) {
    const meta = rowMetaOf(r);
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      if (openable) {
        // Fold toggle (classic chain-row semantics).
        if (expandedChains.has(block.key)) expandedChains.delete(block.key);
        else expandedChains.add(block.key);
        rerenderWithNodes();
      } else {
        // 视图重设计实施批（2026-10-21 · 入口统一 作者令⑤）：任务列表点 delegate 行
        // = 全景视图（同一组件同一数据面，仅入参不同——项目过滤 + 行落位高亮）；
        // 链级跳转（上方 chain-jump）与成员锚点仍走 L1 流程图。
        import('./delegatePanoramaView.js')
          .then(({ openDelegatePanorama }) =>
            openDelegatePanorama(meta.project || '', { focus: meta.delegateId || '' }))
          .catch(() => {});
      }
    });
  }
  return row;
}

// ── Member rows (expanded blocks): the classic 22px chain-member language ──

const CHECK_SVG = '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="6" cy="6" r="5" class="ring"/><path d="M3.6 6.2l1.7 1.7 3.1-3.6" class="tick"/></svg>';
const CROSS_SVG = '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="6" cy="6" r="5" class="ring"/><path d="M4.2 4.2l3.6 3.6M7.8 4.2L4.2 7.8" class="tick"/></svg>';

/** Transition motion mark: running = orbit mini, completed = self-drawing
 *  check, failed = cross; anything else = absent. The draw animates only on a
 *  real frame-over-frame status change (transition=true). */
function buildMemberMark(st, transition) {
  if (st === 'running') {
    // Classic member-mark running face: the single spin ring (same language
    // as the header glyph; the old design used exactly this slot form).
    const spin = document.createElement('span');
    spin.className = 'task-node-spin task-chain-mark';
    spin.setAttribute('aria-hidden', 'true');
    return spin;
  }
  if (st !== 'completed' && st !== 'failed') return null;
  const mark = document.createElement('span');
  mark.className = 'task-chain-mark' + (st === 'failed' ? ' task-chain-mark-failed' : ' task-chain-mark-done');
  mark.setAttribute('aria-hidden', 'true');
  mark.innerHTML = st === 'failed' ? CROSS_SVG : CHECK_SVG;
  if (REDUCED_MOTION || !transition) mark.classList.add('task-chain-mark-static');
  return mark;
}

/**
 * Expanded member row (22px): sequence label + kind dot + name + status word +
 * motion mark + relative time (children carry spawn/completion times; flow-step
 * rows render no time — the chain projection has no per-node timestamps).
 * Click = the L1 map anchored at this node (camera focus + flash).
 * @param {any} m member record @param {any} block owning chain block
 * @returns {HTMLElement} */
function buildChainMemberRow(m, block) {
  const st = String(m.status || 'pending');
  const cls = NODE_STATUS_CLS[st] || '';
  const id = String(m.id);
  const depth = block.view.depths.get(id) || 0;
  const memKey = `${block.key}:${id}`;
  const prevStatus = memberStatusPrev.get(memKey);
  const transition = !REDUCED_MOTION && prevStatus !== undefined && prevStatus !== st;
  if (!REDUCED_MOTION) memberStatusNext.set(memKey, st);

  const row = document.createElement('div');
  row.className = 'task-chain-member' + (cls ? ` task-node-${cls}` : '');
  row.dataset.nodeKey = memKey;
  row.dataset.delegateId = String(block.row.delegateId || '');
  row.style.setProperty('--nest', String(depth));
  row.setAttribute('role', 'button');
  const wordKey = DELEGATE_WORD_KEY[st];
  row.setAttribute('aria-label', `${m.name || id}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  row.title = t('delegate.flowmap.title');

  const seq = document.createElement('span');
  seq.className = 'task-chain-depth';
  seq.setAttribute('aria-hidden', 'true');
  seq.textContent = block.view.seqs.get(id) || String(depth + 1);
  row.appendChild(seq);

  const dot = document.createElement('span');
  dot.className = `task-chain-dot t-${m.kind === 'flow' ? 'design' : 'impl'}`;
  dot.setAttribute('aria-hidden', 'true');
  row.appendChild(dot);

  const label = document.createElement('span');
  label.className = 'task-chain-member-name';
  label.textContent = m.name || id;
  if (m.description) row.title = String(m.description); // truncated by CSS; hover carries the full text
  row.appendChild(label);

  if (wordKey) {
    const word = document.createElement('span');
    word.className = 'task-chain-member-word' + (transition ? ' task-chain-roll-in' : '');
    word.setAttribute('aria-hidden', 'true');
    word.textContent = t(wordKey);
    row.appendChild(word);
  }

  const mark = buildMemberMark(st, transition);
  if (mark) row.appendChild(mark);

  const ms = (typeof m.completedAt === 'number' && m.completedAt > 0) ? m.completedAt : memberTs(m);
  if (ms > 0) {
    const time = document.createElement('span');
    time.className = 'task-chain-member-time';
    time.setAttribute('aria-hidden', 'true');
    time.dataset.ts = new Date(ms).toISOString();
    time.textContent = formatLastActive(time.dataset.ts);
    row.appendChild(time);
  }

  const meta = rowMetaOf(block.row);
  row.addEventListener('click', (e) => {
    e.stopPropagation();
    openL1(block.key, meta, { anchorNode: id });
  });
  return row;
}

/**
 * One chain block: header row + (when expanded) member rows in topological
 * order. First appearance pops (frame-diff memory; repaints stay silent).
 * @param {any} block @returns {HTMLElement} */
function buildChainBlock(block) {
  const el = document.createElement('div');
  el.className = 'task-chain-block';
  const seenKey = `chain:${block.key}`;
  if (!chainSeenPrev.has(seenKey) && !REDUCED_MOTION) el.classList.add('task-chain-enter');
  chainSeenNext.add(seenKey);
  el.appendChild(buildChainHeaderRow(block));
  if (expandedChains.has(block.key)) {
    const body = document.createElement('div');
    body.className = 'task-chain-body';
    block.view.ordered.forEach((m, i) => {
      if (i === 0) return; // the root stub is the header row, not a member row
      body.appendChild(buildChainMemberRow(m, block));
    });
    el.appendChild(body);
  }
  return el;
}

/**
 * Section assembly: group the chain blocks by project (small uppercase header
 * per group; the headerless group hosts project-less rows). Group order =
 * first appearance in the activity-sorted root order.
 * @param {any[]} chains @returns {HTMLElement|null} */
function buildNodeSection(chains) {
  if (!chains || chains.length === 0) return null;
  const section = document.createElement('div');
  section.className = 'task-section task-section-nodes';
  const byProject = new Map();
  for (const c of chains) {
    if (!byProject.has(c.project)) byProject.set(c.project, []);
    byProject.get(c.project).push(c);
  }
  for (const [project, groupChains] of byProject) {
    const group = document.createElement('div');
    group.className = 'task-node-group';
    if (project) {
      const h = document.createElement('div');
      h.className = 'task-node-group-header';
      h.textContent = project;
      group.appendChild(h);
    }
    for (const c of groupChains) group.appendChild(buildChainBlock(c));
    section.appendChild(group);
  }
  return section;
}

// ── S16 external-row progress face (offset-incremental poll) ─────────────
// Coarse progress leg: the buffered output tail line (raw data, not copy —
// but guarded: stderr/control/fragment lines never reach the row, see
// progressTailText; 2026-10-19 作者令「列表行零泄漏」).
// Only visible external running rows are polled; offset-incremental reads
// (nextOffset carried); terminal (exitCode arrived) / no-buffer (404) stops
// the slot; a row never breaks or clears on a single failed fetch.
/** delegateId → progress reading (render-layer memory: {offset, tail, at, noBuffer, done}). */
const delegateProgress = new Map();
/** Rolling tail buffer cap (chars) — the coarse row shows the last line only. */
const DELEGATE_PROGRESS_TAIL_MAX = 2000;
/** Regular tick (ms); no-buffer backoff (record exists, buffer not built yet). */
const DELEGATE_PROGRESS_TICK_MS = 3000;
const DELEGATE_PROGRESS_NOBUFFER_BACKOFF_MS = 15000;
let delegateProgressTimer = null;

// Row-tail leak guards (display layer only — the raw buffer and the detail
// view keep everything; the guard lives at the row-render seam):
//   - Control lines: the engine appends stderr into the SAME output buffer
//     with a `[stderr] ` prefix (ExternalSessionRunner stderr thread) plus its
//     own `[read-loop crashed]` / `[launch-failed]` markers — 2026-10-19 作者令：
//     stderr 只进详情视图，列表行零泄漏 ⇒ these never render on the row.
//   - Fragment lines: a punctuation-only line (`}`, `]}` … JSON dump tails)
//     reads as a leak, not as progress — skipped like an empty line.
//   - Hard cap: CSS ellipsis truncates visually; this bounds the DOM node and
//     the hover title too (defensive truncation leg beside the other two).
const ROW_TAIL_CONTROL_RE = /^\[(?:stderr|read-loop crashed|launch-failed)\]/;
const ROW_TAIL_FRAGMENT_RE = /^[\s{}[\]",:;]*$/;
const DELEGATE_TAIL_LINE_MAX = 200;

/** Tail extraction: the last renderable line of the buffer (guarded above). */
function progressTailText(entry) {
  if (!entry || !entry.tail) return '';
  const lines = String(entry.tail).split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const s = lines[i].trim();
    if (!s) continue;
    if (ROW_TAIL_CONTROL_RE.test(s)) continue;
    if (ROW_TAIL_FRAGMENT_RE.test(s)) continue;
    return s.length > DELEGATE_TAIL_LINE_MAX
      ? `${s.slice(0, DELEGATE_TAIL_LINE_MAX)}…` : s;
  }
  return '';
}

function stopDelegateProgressTimer() {
  if (delegateProgressTimer) {
    clearInterval(delegateProgressTimer);
    delegateProgressTimer = null;
  }
}

/**
 * One progress round (shared by the timer and the test hook; the render path
 * goes through ensureDelegateProgressLoop).
 * @param {boolean} [force] bypass the freshness gate (harness only)
 * @returns {Promise<boolean>} whether any reading changed (→ re-render)
 */
async function pollDelegateProgress(force) {
  const container = document.getElementById('task-list');
  if (!container || !container.classList.contains('has-tasks')) {
    stopDelegateProgressTimer();
    return false;
  }
  const ids = [];
  container.querySelectorAll('.task-delegate[data-external="1"][data-delegate-status="running"]')
    .forEach((el) => {
      const id = /** @type {HTMLElement} */ (el).dataset && /** @type {HTMLElement} */ (el).dataset.delegateId;
      if (id && !ids.includes(id)) ids.push(id);
    });
  if (ids.length === 0) {
    stopDelegateProgressTimer();
    return false;
  }
  let changed = false;
  await Promise.all(ids.map(async (id) => {
    const cur = delegateProgress.get(id) || { offset: 0, tail: '', at: 0, noBuffer: false, done: false };
    if (cur.done) return; // terminal reading is frozen (the word slot carries the terminal word)
    const backoff = cur.noBuffer ? DELEGATE_PROGRESS_NOBUFFER_BACKOFF_MS : DELEGATE_PROGRESS_TICK_MS;
    if (!force && Date.now() - (cur.at || 0) < backoff - 50) return; // fresh readings are not re-pulled
    cur.at = Date.now();
    const data = await fetchDelegateOutput(id, cur.offset || 0);
    if (!data) {
      // 404 (no buffer) → silent absence + backoff; network/HTTP failures keep
      // the last reading too (fail-soft, the row never clears).
      cur.noBuffer = cur.offset === 0 ? true : cur.noBuffer;
      delegateProgress.set(id, cur);
      return;
    }
    cur.noBuffer = false;
    const out = typeof data.output === 'string' ? data.output : '';
    if (out) {
      cur.tail = (cur.tail + out).slice(-DELEGATE_PROGRESS_TAIL_MAX);
      changed = true;
    }
    if (typeof data.nextOffset === 'number') cur.offset = data.nextOffset;
    if (data.exitCode !== null && data.exitCode !== undefined) {
      cur.done = true;
      changed = true;
    }
    delegateProgress.set(id, cur);
  }));
  if (changed) rerenderWithNodes();
  return changed;
}

/** Render-path hook: visible external running rows ⇒ the poll loop runs (and
 *  fires one round now — the freshness gate prevents request amplification);
 *  none ⇒ the timer stops. */
function ensureDelegateProgressLoop() {
  const container = document.getElementById('task-list');
  if (!container || !container.querySelector('.task-delegate[data-external="1"][data-delegate-status="running"]')) {
    stopDelegateProgressTimer();
    return;
  }
  if (!delegateProgressTimer) {
    delegateProgressTimer = setInterval(() => {
      pollDelegateProgress().catch(() => {});
    }, DELEGATE_PROGRESS_TICK_MS);
  }
  pollDelegateProgress().catch(() => {});
}

/** Harness injection seam (tests only; zero product-path calls): force one round. */
export function __pollDelegateProgress(force) {
  return pollDelegateProgress(force === true);
}

/** Empty-state builder (fallback retained): the main path never wires it — no
 *  rows ⇒ the whole panel collapses (.has-tasks gate). */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render ──────────────────────────────────────────────────────────

/**
 * Render the task panel = chain blocks over the delegate plane. The first arg
 * is a retired placeholder (ignored; caller compatibility).
 * @param {Array} [_tasks]
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — gates panel visibility
 */
export function renderTaskList(_tasks, container, sessionId) {
  if (!container) container = document.getElementById('task-list');
  if (!container) return;

  lastPanelSessionId = sessionId || null;
  // Nebula unified panel only; the live provider mirror (REST snapshot fed,
  // WS-trigger-debounced) drives re-renders via delegate-chain-mirror-updated.
  const showRows = sessionShowsNodeEntries(sessionId);
  const chains = showRows ? collectChains() : [];

  // Stats = total unit count (roots + members, fold-independent — the classic
  // node-count semantics).
  const unitCount = chains.reduce((n, c) => n + c.allCount, 0);

  container.classList.toggle('has-tasks', chains.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(chains.length > 0);
  if (chains.length === 0) {
    container.innerHTML = '';
    stopDelegateProgressTimer(); // panel collapsed ⇒ the S16 poll stops too
    return;
  }

  redraw(container, chains, unitCount);
  // Frame-diff memory swap (transition marks / first-appearance pop).
  memberStatusPrev.clear();
  for (const [k, v] of memberStatusNext) memberStatusPrev.set(k, v);
  memberStatusNext.clear();
  chainSeenPrev.clear();
  for (const k of chainSeenNext) chainSeenPrev.add(k);
  chainSeenNext.clear();
}

/** Row keys present in the CURRENT DOM (entry-animation comparison: rows that
 *  already exist do not replay the animation; keys come from data-node-key
 *  (member rows) and data-delegate-key (header rows) — one shared set). */
function collectRowKeys(container) {
  const keys = new Set();
  container.querySelectorAll('.task-chain-member[data-node-key], .task-delegate[data-delegate-key]')
    .forEach((el) => {
      const k = /** @type {HTMLElement} */ (el).dataset;
      keys.add(String(k.nodeKey || k.delegateKey || ''));
    });
  return keys;
}

/** @param {any[]} chains collectChains output @param {number} unitCount */
function redraw(container, chains, unitCount) {
  // Capture before clearing: entry animation compares the incoming rows
  // against the rows the previous render actually showed.
  const priorKeys = collectRowKeys(container);
  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats (stats = total unit count; key reused) ──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: unitCount });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: the chain section (sole content zone) ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const nodesSection = buildNodeSection(chains);
  if (nodesSection) inner.appendChild(nodesSection);

  body.appendChild(inner);
  card.appendChild(header);
  card.appendChild(body);
  container.appendChild(card);

  createIconsIn(container);
  // S16 external progress poll hook (runs only while external running rows show).
  ensureDelegateProgressLoop();

  toggle.addEventListener('click', () => {
    const nowCollapsed = card.classList.toggle('collapsed');
    container.dataset.collapsed = nowCollapsed ? '1' : '';
    toggle.innerHTML = `<i data-lucide="${nowCollapsed ? 'chevron-down' : 'chevron-up'}"></i>`;
    toggle.title = nowCollapsed ? t('task.expand') : t('task.collapse');
    createIconsIn(toggle);
  });

  // Entry animation for brand-new rows only (comparison against the prior DOM).
  if (!REDUCED_MOTION) {
    const items = /** @type {NodeListOf<HTMLElement>} */ (
      container.querySelectorAll('.task-delegate, .task-chain-member'));
    items.forEach((el) => {
      const k = /** @type {HTMLElement} */ (el).dataset;
      if (priorKeys.has(String(k.nodeKey || k.delegateKey || ''))) return;
      el.classList.add('task-node-entering');
      el.addEventListener('animationend', () => el.classList.remove('task-node-entering'), { once: true });
    });
  }
}
