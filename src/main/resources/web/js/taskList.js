// taskList.js — Collapsible task panel (floats above the input area)
//
// Panel face = flat flowmap node rows over the delegate plane:
//   - One row per visible work unit: delegate roots, their star sub-agent
//     children, and AgentFlow chain nodes (kind 'flow-step'). No inline expand
//     tree — navigation is click-through, the row anatomy and interaction model
//     follow the classic flowmap node list.
//   - Rows are grouped by project with a small uppercase group header, sorted
//     most-recent-activity first inside each group.
//   - Row anatomy: status glyph (orbit ring = running, amber dot = blocked,
//     solid dots = terminal states, box = pending) + executor mark (roots) +
//     label + chain badge (member rows: same-color dot + short chain title) +
//     meta line (description / external progress tail) + muted status word +
//     relative time. Color signal lives on the glyph only; words stay muted.
//   - Interactions: row click opens the per-task chain view (L1) — flow-step
//     rows pass an anchor node; the chain badge clicks through to the same L1
//     view (whole chain). Display-only rows: external executors (they carry the
//     open-native exit instead) and ready-to-merge rows (queue rank chip only).
//   - Data: delegateChainStub.js read port (listDelegateRows / getChainByTask /
//     listDelegateChildren); the live provider mirror is wired once in main.js
//     via setSource. Re-render triggers: the provider's
//     delegate-chain-mirror-updated event + session switch + S16 progress
//     polling. This file has zero WS knowledge and zero derived second
//     authorities.
//   - Header stats = visible row count (key task.statsProgress).
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
 * Single inline entry to the L1 per-task chain view. Row click / chain badge /
 * flow-step anchor all funnel through here (dynamic import keeps the module
 * graph decoupled).
 * @param {string} key @param {any} meta
 * @param {{expandFlow?: boolean, anchorNode?: string}} [opts] */
function openL1(key, meta, opts = {}) {
  import('./delegateChainView.js').then(({ openDelegateChainTask }) => {
    openDelegateChainTask(key, meta, opts);
  }).catch(() => {});
}

// ── Panel session gate ───────────────────────────────────────────────────
/** Only the Nebula unified panel renders rows. */
function sessionShowsNodeEntries(sid) {
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
    container.querySelectorAll('.task-node-time'));
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
const ACTIVE_ROW_STATUSES = new Set(['pending', 'running', 'blocked', 'ready-to-merge']);

/**
 * Running-state glyph: the three-dot orbit (same visual language as the
 * flowmap node running state), 12px row rhythm.
 * @returns {HTMLElement} */
function buildOrbitGlyph() {
  const g = document.createElement('span');
  g.className = 'task-node-orbit';
  g.setAttribute('aria-hidden', 'true');
  for (let i = 0; i < 3; i++) {
    const ring = document.createElement('span');
    ring.className = 'task-node-orbit-ring';
    ring.style.setProperty('--orbit-phase', `${i * 120}deg`);
    const dot = document.createElement('span');
    dot.className = 'task-node-orbit-dot';
    ring.appendChild(dot);
    g.appendChild(ring);
  }
  return g;
}

/**
 * Status glyph, shared row language: running = orbit, blocked = amber dot,
 * terminal = solid color dot, wiring|pending = box, unknown = neutral dot
 * (graceful degradation).
 * @param {string} st @param {string} cls @returns {HTMLElement} */
function buildNodeGlyph(st, cls) {
  const g = document.createElement('span');
  g.setAttribute('aria-hidden', 'true');
  if (st === 'running') {
    g.appendChild(buildOrbitGlyph());
    g.className = 'task-node-glyph-running';
  } else if (st === 'blocked') {
    g.className = 'task-node-dot task-node-dot-blocked';
  } else if (st === 'completed' || st === 'failed' || st === 'cancelled') {
    g.className = `task-node-dot task-node-dot-${cls}`;
  } else if (st === 'wiring' || st === 'pending') {
    g.className = 'task-node-box';
  } else {
    g.className = 'task-node-dot task-node-dot-neutral';
  }
  return g;
}

/** Status word keys (existing locale keys only; domain = stub ROW_STATUS). */
const DELEGATE_WORD_KEY = {
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

// ── Chain badge (member rows) ────────────────────────────────────────────
// Capsule = same-chain color dot + short chain title. The color is a stable
// hash of the chain key into a fixed palette cycle (same chain always the
// same color; no order semantics). Click = open the whole chain in L1;
// does not bubble into the row's own click.
const CHAIN_COLOR_CYCLE = 5; // pairs with .task-node-chain.c0..c4 in taskList.css
const CHAIN_NAME_MAX = 12;   // CSS max-width + ellipsis is the final backstop

/** @param {string} chainKey @returns {number} 0..CHAIN_COLOR_CYCLE-1 (deterministic hash) */
function chainColorIndex(chainKey) {
  const s = String(chainKey);
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h % CHAIN_COLOR_CYCLE;
}

/** @param {string} title @returns {string} chain short name (truncated with ellipsis) */
function chainShortName(title) {
  const s = String(title || '');
  return s.length > CHAIN_NAME_MAX ? `${s.slice(0, CHAIN_NAME_MAX)}…` : s;
}

/**
 * Row-level chain badge for member rows (sub-agent children, flow-step nodes).
 * The chain title is the delegate's own title — raw data, not copy. Absent
 * title → no badge (graceful absence, no empty capsule).
 * @param {string} chainKey @param {string} chainTitle
 * @param {any} meta L1 open meta of the owning delegate
 * @returns {HTMLElement|null} */
function buildChainBadge(chainKey, chainTitle, meta) {
  const name = String(chainTitle || '');
  if (!name) return null;
  const badge = document.createElement('span');
  badge.className = `task-node-chain c${chainColorIndex(chainKey)}`;
  badge.dataset.chainId = chainKey;
  const hint = t('flowmap.chain.badgeHint', { chain: name });
  badge.title = hint;
  badge.setAttribute('role', 'button');
  badge.setAttribute('aria-label', hint);
  const dot = document.createElement('span');
  dot.className = 'task-node-chain-dot';
  dot.setAttribute('aria-hidden', 'true');
  badge.appendChild(dot);
  const nameEl = document.createElement('span');
  nameEl.className = 'task-node-chain-name';
  nameEl.textContent = chainShortName(name);
  badge.appendChild(nameEl);
  badge.addEventListener('click', (e) => {
    e.stopPropagation(); // badge = locate the chain (the row click locates the unit)
    openL1(chainKey, meta);
  });
  return badge;
}

// ── Row builders ─────────────────────────────────────────────────────────

/** Shared meta carrier handed to the L1 view (raw row fields, no derivation). */
function rowMetaOf(r) {
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

/** Executor mark slot (roots) / fixed-width placeholder (members) — keeps the
 *  text column aligned across row kinds. */
function buildIconSlot(html) {
  const icon = document.createElement('span');
  icon.className = html ? 'task-delegate-icon' : 'task-delegate-icon task-delegate-icon-empty';
  icon.setAttribute('aria-hidden', 'true');
  if (html) icon.innerHTML = html;
  return icon;
}

/**
 * Delegate root row: `[glyph][executor] #N title [word] [rank] [time]`.
 * Click = open the delegate's L1 chain view; display-only for external
 * executors (open-native exit + progress tail instead) and ready-to-merge
 * rows (queue rank chip only).
 * @param {any} r DelegateRow @returns {HTMLElement} */
function buildRootRow(r) {
  const row0 = r && typeof r === 'object' ? r : {};
  const taskNo = String(row0.taskNo == null ? '' : row0.taskNo);
  const delegateId = String(row0.delegateId || '');
  const title = String(row0.title || '');
  const st = String(row0.status || 'running');
  const wordKey = DELEGATE_WORD_KEY[st] || '';
  const external = row0.external === true;
  const queued = st === 'ready-to-merge';
  const clickable = !external && !queued;

  const row = document.createElement('div');
  row.className = 'task-node task-delegate';
  row.dataset.delegateKey = taskNo || delegateId;
  row.dataset.taskNo = taskNo;
  row.dataset.delegateId = delegateId;
  row.dataset.delegateStatus = st;
  if (external) row.dataset.external = '1';
  const labelBits = [taskNo ? `#${taskNo}` : '', title || delegateId].filter(Boolean);
  row.setAttribute('aria-label', `${labelBits.join(' ')}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  if (clickable) {
    row.setAttribute('role', 'button');
    row.title = t('delegate.flowmap.title');
  }

  row.appendChild(buildNodeGlyph(st, NODE_STATUS_CLS[st] || ''));
  row.appendChild(buildIconSlot(executorIcon(String(row0.executor || ''), 14)));

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

  appendWordSlot(row, wordKey);

  // Ready-to-merge rows show only the queue rank (engine G5 position verbatim;
  // absent key = no chip, no derivation).
  if (queued && row0.mergeQueuePos && typeof row0.mergeQueuePos.position === 'number' &&
      row0.mergeQueuePos.position > 0) {
    const qp = row0.mergeQueuePos;
    const rankEl = document.createElement('span');
    rankEl.className = 'task-delegate-rank';
    rankEl.setAttribute('aria-hidden', 'true');
    rankEl.textContent = `#${qp.position}`;
    const titleBits = [`#${qp.position}`, qp.total ? `/ ${qp.total}` : '',
      qp.branch || ''].filter(Boolean);
    rankEl.title = titleBits.join(' '); // raw data (rank / branch), not copy
    row.appendChild(rankEl);
  }

  const createdAt = typeof row0.createdAt === 'number' ? row0.createdAt : Date.parse(String(row0.createdAt || ''));
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
    const meta = rowMetaOf(row0);
    const key = delegateRowKey(row0);
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      openL1(key, meta);
    });
  }
  return row;
}

/**
 * Member row (sub-agent child or flow-step chain node): same row anatomy as a
 * root row minus the executor mark (fixed-width placeholder keeps the text
 * column aligned), plus the chain badge naming the owning delegate chain.
 * Click = open L1; flow-step rows anchor to their node card.
 * @param {{kind: 'child'|'flow', key: string, chainTitle: string, meta: any,
 *          child?: any, node?: any}} item @returns {HTMLElement} */
function buildMemberRow(item) {
  const isFlow = item.kind === 'flow';
  const src = isFlow ? (item.node || {}) : (item.child || {});
  const rawStatus = String(src.status || 'pending');
  const st = isFlow ? rawStatus : childRowStatus(rawStatus);
  const wordKey = DELEGATE_WORD_KEY[st] || '';
  const labelText = isFlow
    ? String(src.label || src.id || '')
    : (String(src.agentName || '') || String(src.taskId || ''));

  const row = document.createElement('div');
  row.className = 'task-node task-member';
  row.dataset.nodeKey = isFlow
    ? `${item.key}‖flow‖${String(src.id || '')}`
    : `${item.key}‖${String(src.taskId || '')}`;
  row.setAttribute('role', 'button');
  row.setAttribute('aria-label', `${labelText}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  row.title = t('delegate.flowmap.title');

  row.appendChild(buildNodeGlyph(st, NODE_STATUS_CLS[st] || ''));
  row.appendChild(buildIconSlot(''));

  const text = document.createElement('div');
  text.className = 'task-node-text';
  const labelRow = document.createElement('span');
  labelRow.className = 'task-node-label-row';
  const label = document.createElement('span');
  label.className = 'task-node-label';
  label.textContent = labelText;
  labelRow.appendChild(label);
  const badge = buildChainBadge(item.key, item.chainTitle, item.meta);
  if (badge) labelRow.appendChild(badge);
  text.appendChild(labelRow);
  appendMetaSlot(text, isFlow ? src.description : (src.description || src.prompt));
  row.appendChild(text);

  appendWordSlot(row, wordKey);

  if (!isFlow) {
    const ts = typeof src.completedAt === 'number' && src.completedAt > 0
      ? src.completedAt
      : (typeof src.spawnedAt === 'number' ? src.spawnedAt : 0);
    appendTimeSlot(row, ts);
  }

  row.addEventListener('click', (e) => {
    e.stopPropagation();
    if (isFlow) openL1(item.key, item.meta, { expandFlow: true, anchorNode: String(src.id || '') });
    else openL1(item.key, item.meta);
  });
  return row;
}

/**
 * Collect the flat panel item list from the delegate read port: every live
 * delegate root plus its star children and flow-step chain nodes. Sorted
 * most-recent-activity first (stable — equal timestamps keep source order).
 * Timestamp policy (no fabrication): roots carry their record's createdAt,
 * children their completion/spawn time; flow-step nodes carry no timestamp in
 * the chain projection, so they sort on their chain root's activity time
 * (keeps a chain's rows adjacent) and render no time slot of their own.
 * @returns {Array<{kind: string, key: string, project: string, ts: number,
 *          row?: any, child?: any, node?: any, chainTitle: string, meta: any}>} */
function collectPanelItems() {
  const rows = listDelegateRows()
    .filter((r) => r && ACTIVE_ROW_STATUSES.has(String(r.status)) && r.archivedAt == null);
  /** @type {any[]} */
  const items = [];
  for (const r of rows) {
    const key = delegateRowKey(r);
    if (!key) continue;
    const project = String(r.project || '');
    const meta = rowMetaOf(r);
    const rootTsRaw = typeof r.createdAt === 'number' ? r.createdAt : Date.parse(String(r.createdAt || ''));
    const rootTs = Number.isFinite(rootTsRaw) ? rootTsRaw : 0;
    const chainTitle = String(r.title || r.delegateId || '');
    items.push({ kind: 'root', key, project, meta, row: r, ts: rootTs, chainTitle });
    if (r.external === true) continue; // external executors: no platform children / chains
    for (const c of listDelegateChildren(String(r.delegateId || ''))) {
      const c0 = c && typeof c === 'object' ? c : {};
      const ts = typeof c0.completedAt === 'number' && c0.completedAt > 0
        ? c0.completedAt
        : (typeof c0.spawnedAt === 'number' ? c0.spawnedAt : 0);
      items.push({ kind: 'child', key, project, meta, child: c0, ts, chainTitle });
    }
    const chain = getChainByTask(key);
    const flowSteps = chain && Array.isArray(chain.nodes)
      ? chain.nodes.filter((n) => n && n.kind === 'flow-step') : [];
    for (const n of flowSteps) {
      items.push({ kind: 'flow', key, project, meta, node: n, ts: rootTs, chainTitle });
    }
  }
  items.sort((a, b) => b.ts - a.ts);
  return items;
}

/**
 * Section assembly: group the flat item list by project (small uppercase
 * header per group; the headerless group hosts rows whose record carries no
 * project). Order inside a group follows the global activity sort.
 * @param {any[]} items @returns {HTMLElement|null} */
function buildNodeSection(items) {
  if (!items || items.length === 0) return null;
  const section = document.createElement('div');
  section.className = 'task-section task-section-nodes';
  const byProject = new Map();
  for (const it of items) {
    if (!byProject.has(it.project)) byProject.set(it.project, []);
    byProject.get(it.project).push(it);
  }
  for (const [project, groupItems] of byProject) {
    const group = document.createElement('div');
    group.className = 'task-node-group';
    if (project) {
      const h = document.createElement('div');
      h.className = 'task-node-group-header';
      h.textContent = project;
      group.appendChild(h);
    }
    for (const it of groupItems) {
      group.appendChild(it.kind === 'root' ? buildRootRow(it.row) : buildMemberRow(it));
    }
    section.appendChild(group);
  }
  return section;
}

// ── S16 external-row progress face (offset-incremental poll) ─────────────
// Coarse progress leg: the buffered output tail line (raw data, not copy).
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

/** Tail extraction: the last non-empty line of the buffer (verbatim). */
function progressTailText(entry) {
  if (!entry || !entry.tail) return '';
  const lines = String(entry.tail).split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const s = lines[i].trim();
    if (s) return s;
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
 * Render the task panel = flat flowmap node rows over the delegate plane.
 * The first arg is a retired placeholder (ignored; caller compatibility).
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
  const items = showRows ? collectPanelItems() : [];

  container.classList.toggle('has-tasks', items.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(items.length > 0);
  if (items.length === 0) {
    container.innerHTML = '';
    stopDelegateProgressTimer(); // panel collapsed ⇒ the S16 poll stops too
    return;
  }

  redraw(container, items);
}

/** Row keys present in the CURRENT DOM (entry-animation comparison: rows that
 *  already exist do not replay the animation; keys come from data-node-key
 *  (member rows) and data-delegate-key (roots) — one shared comparison set). */
function collectRowKeys(container) {
  const keys = new Set();
  container.querySelectorAll('.task-node[data-node-key], .task-delegate[data-delegate-key]')
    .forEach((el) => {
      const k = /** @type {HTMLElement} */ (el).dataset;
      keys.add(String(k.nodeKey || k.delegateKey || ''));
    });
  return keys;
}

/** @param {any[]} items flat panel items (collectPanelItems output) */
function redraw(container, items) {
  // Capture before clearing: entry animation compares the incoming rows
  // against the rows the previous render actually showed.
  const priorKeys = collectRowKeys(container);
  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats (stats = visible row count; key reused) ──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: items.length });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: the node section (sole content zone) ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const nodesSection = buildNodeSection(items);
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
    const items2 = /** @type {NodeListOf<HTMLElement>} */ (
      container.querySelectorAll('.task-node'));
    items2.forEach((el) => {
      const k = /** @type {HTMLElement} */ (el).dataset;
      if (priorKeys.has(String(k.nodeKey || k.delegateKey || ''))) return;
      el.classList.add('task-node-entering');
      el.addEventListener('animationend', () => el.classList.remove('task-node-entering'), { once: true });
    });
  }
}
