// flowCanvas.js — Teams and Flows as independent Canvas tabs.
//
// TEAMS tab: Glass cards with agent tiles. Always accessible via toggle button.
// FLOWS tab: DAG node graph with real-time progress. Auto-opens when flows run.

import { openTab, getTabPane, hasTab, isCanvasOpen, setActiveTab, closeTab, openCanvas, registerCanvasPanelButton } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
// Side-effect import: rAF orbit driver for .solar-node dots (seamless loop +
// coast-to-endpoint + fade on completion). Watches the DOM itself; no per-tab
// wiring needed — any pane that renders solar nodes gets animated.
import './flowAnim.js';
import { esc, authHeaders, overlayRoot, setMailPending } from './flowHelpers.js';
import { renderTeamsPanel, bindTileClicks, bindCardActions, bindFlowRowClicks, statusOf, populateTileModels } from './flowTeams.js';
import { renderFlowRunInto, renderFlowsPanel, bindDagNodeClicks, dagCardHtml, renderStellarSystem, bindStellarNodeClicks } from './flowDag.js';
import { renderFlowList } from './flowList.js';
import { closeViewer, openMailbox, openRules, openDefinition, refreshMailboxPending } from './flowViewers.js';
import { onReconnect } from './ws.js';
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';

// ── State ──────────────────────────────────────────────────
let teams = [];           // Team definitions from /api/teams (disk)
let teamsLoaded = false;
const agentStatus = new Map();
const mailFlash = new Map();
let autoRestoreRetryCount = 0;
let runningFlows = [];
// flow v4 (20260825_flow-redesign-research.md §4.10): flow-run is the sole flow
// UI. Once the user closes a flow-run tab (running or terminal) don't auto-reopen
// it on later progress — 「被关闭后不复活」. Track dismissed instanceIds.
// 2026-08-26 (flow-complement §5.3): the set is persisted to sessionStorage so a
// page refresh doesn't auto-reopen a tab the user explicitly closed, while the
// running-flows indicator still offers a manual re-open path.
const dismissedFlowRuns = new Set();
const FLOWS_DISMISS_KEY = 'nebflow.dismissedFlowRuns';

/** Snapshot the dismissed set to sessionStorage. */
function persistDismissedFlowRuns() {
  try {
    sessionStorage.setItem(FLOWS_DISMISS_KEY, JSON.stringify([...dismissedFlowRuns]));
  } catch (e) { /* non-critical */ }
}

/** Restore the dismissed set from sessionStorage (page reload). */
function loadDismissedFlowRuns() {
  try {
    const raw = sessionStorage.getItem(FLOWS_DISMISS_KEY);
    if (!raw) return;
    const arr = JSON.parse(raw);
    if (Array.isArray(arr)) for (const id of arr) if (typeof id === 'string') dismissedFlowRuns.add(id);
  } catch (e) { /* non-critical */ }
}

let flowsTabAutoOpened = false;  // prevent repeated auto-open
let flowDefs = [];

// Record a dismissed flow-run tab when its canvas tab closes (Canvas dispatches
// 'canvas-tab-closed' with { id }). The tab's own Close button routes through
// closeTab, so both the X and the terminal Close button land here.
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  const id = e.detail && e.detail.id;
  if (typeof id === 'string' && id.startsWith('flow-run-')) {
    dismissedFlowRuns.add(id.slice('flow-run-'.length));
    persistDismissedFlowRuns();
  }
});

async function fetchFlowDefs() {
  try {
    const resp = await fetch('/api/flows/list', { headers: authHeaders() });
    if (!resp.ok) return;
    const data = await resp.json();
    flowDefs = data.flows || [];
  } catch (e) { /* non-critical */ }
}

// ── Teams tab ──────────────────────────────────────────────

function renderTeamsTab() {
  const pane = getTabPane('teams');
  if (!pane) return;
  ensureFlowCss();
  let scroll = pane.querySelector('#team-scroll');
  if (!scroll) {
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.id = 'team-scroll';
    pane.appendChild(scroll);
  }
  renderTeamsPanel(scroll, teams, agentStatus, mailFlash, runningFlows);
  overlayRoot();
  bindTileClicks();
  bindCardActions((fn) => openMailbox(fn, teams.find(f => f.name === fn) || null), openRules, openDefinition);
  bindFlowRowClicks(runningFlows, () => renderTeamsTab());
  if (typeof lucide !== 'undefined') createIconsIn(scroll);
  populateTileModels(teams);
}

// ── Flows tab (static flow list, P6) ───────────────────────

function renderFlowsTab() {
  const pane = getTabPane('flows');
  if (!pane) return;
  ensureFlowCss();
  let scroll = pane.querySelector('#flow-scroll-flows');
  if (!scroll) {
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.id = 'flow-scroll-flows';
    scroll.style.flexDirection = 'column';
    // Center the content column horizontally; sections cap at max-width
    // (see .flow-defs-section in flowCss.js) so cards sit centered.
    // nowrap is required: in a wrapping column flex container, each line's
    // cross size shrinks to its widest item, so align-items would center
    // within a content-sized line — visually still left-aligned.
    scroll.style.alignItems = 'center';
    scroll.style.flexWrap = 'nowrap';
    pane.appendChild(scroll);
    // Flow definitions are rendered inline below from the flowDefs state
    // (populated by fetchFlowDefs). The previous renderFlowList() call was
    // removed because its async fetch would race with and overwrite the
    // inline content.
  }
  let defsHtml = '';
  if (flowDefs.length > 0) {
    defsHtml = `<div class="flow-defs-section">
      <div class="flow-defs-header">${t('flows.definitions')}</div>
      <div class="flow-defs-grid">
        ${flowDefs.map(fd => `
          <div class="flow-def-card" data-flow-name="${esc(fd.name)}">
            <div class="flow-def-name">${esc(fd.name)}</div>
            ${fd.description ? `<div class="flow-def-desc">${esc(fd.description)}</div>` : ''}
            <div class="flow-def-meta">${t('flows.nodesCount', { count: fd.nodeCount || 0 })}</div>
            <button class="flow-def-view-btn" data-flow-name="${esc(fd.name)}">${t('flows.viewDag')} \u2192</button>
          </div>
        `).join('')}
      </div>
    </div>`;
  }
  // 372-2: Running Instances block removed from the panel — a running flow
  // already auto-opens a dedicated flow-run tab (maybeAutoOpenFlowsTab), so
  // listing it here duplicated the same content in two places. Watch a live
  // run in its tab instead.
  if (flowDefs.length === 0 && runningFlows.length === 0) {
    scroll.innerHTML = `<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">${t('agentManager.noFlows')}</div></div>`;
  } else {
    scroll.innerHTML = defsHtml || (runningFlows.length > 0
      ? `<div class="dag-empty"><div style="font:600 13px -apple-system;color:var(--color-text-muted)">${esc(t('flow.runningInTab'))}</div></div>`
      : '');
  }
  overlayRoot();
  bindDagNodeClicks();
  scroll.querySelectorAll('.flow-def-view-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const flowName = btn.dataset.flowName;
      openTab(`flow-def-${flowName}`, `DAG: ${flowName}`, { type: 'flow', closable: true });
      const defPane = getTabPane(`flow-def-${flowName}`);
      if (defPane) {
        defPane.innerHTML = '<div class="agent-detail-loading">' + t('flows.loadingDag') + '</div>';
        fetch(`/api/flow/dag/${encodeURIComponent(flowName)}`, { headers: authHeaders() })
          .then(r => r.json())
          .then(dag => renderStaticDag(defPane, dag));
      }
    });
  });
  if (typeof lucide !== 'undefined') createIconsIn(scroll);
}

function renderStaticDag(pane, dag) {
  ensureFlowCss();
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.style.flexDirection = 'column';
    // See renderFlowsTab: nowrap keeps align-items centering effective.
    scroll.style.alignItems = 'center';
    scroll.style.flexWrap = 'nowrap';
    pane.appendChild(scroll);
  }
  const pseudoRf = {
    flowName: dag.name,
    description: dag.description,
    entry: dag.entry,
    status: 'static',
    nodes: Object.entries(dag.nodes || {}).map(([id, n]) => ({
      nodeId: id,
      agent: (n && (n.agent || n.agentName)) || '',
      status: 'static',
    })),
    edges: Object.entries(dag.nodes || {}).flatMap(([from, n]) => {
      const oc = n && n.onComplete;
      if (!oc) return [];
      if (typeof oc === 'string') return [{ from, to: oc, condition: null }];
      if (oc.goto) return [{ from, to: oc.goto, condition: null }];
      if (oc.return) return [{ from, to: '$return', condition: null }];
      if (oc.switch && oc.cases) return Object.entries(oc.cases).map(([cond, to]) => ({ from, to, condition: cond }));
      return [];
    }),
  };
  scroll.innerHTML = dagCardHtml(pseudoRf);
  bindDagNodeClicks();
  if (typeof lucide !== 'undefined') createIconsIn(scroll);
}

// Render whichever tab(s) are open.
// Debounced — high-frequency WS events (agentStart/agentDone/flowMail/
// flowProgress/treeBranch*) collapse into a single render instead of
// rebuilding the DOM per event (which replays animations and resets scroll).
let renderTimer = null;
function renderOpenTabs() {
  clearTimeout(renderTimer);
  renderTimer = setTimeout(renderOpenTabsNow, 150);
}

function renderOpenTabsNow() {
  if (hasTab('teams')) renderTeamsTab();
  if (hasTab('flows')) renderFlowsTab();
  for (const f of runningFlows) {
    if (hasTab(`flow-run-${f.instanceId}`)) renderFlowRunTab(f.instanceId);
  }
}

// ── Flow run tab (per-instance runtime view) ───────────────

/** Open a dedicated tab for one running flow instance — solar-system view (P5). */
export function openFlowRunTab(instanceId, flowName) {
  if (!instanceId) return;
  openTab(`flow-run-${instanceId}`, flowName || 'Flow run', { type: 'flow-run', closable: true, pinned: true });
  renderFlowRunTab(instanceId);
}

function renderFlowRunTab(instanceId) {
  const pane = getTabPane(`flow-run-${instanceId}`);
  if (!pane) return;
  ensureFlowCss();
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    pane.innerHTML = '';
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.style.display = 'block';
    pane.appendChild(scroll);
  }
  const flow = runningFlows.find(f => f.instanceId === instanceId);
  renderFlowRunInto(scroll, flow, { onClose: () => closeTab(`flow-run-${instanceId}`) });
  // Terminal state Close button (see dagCardHtml §4.10 "停留结束态可手动关闭").
  pane.querySelectorAll('.dag-card-close').forEach(btn => {
    btn.addEventListener('click', () => closeTab(`flow-run-${instanceId}`));
  });
  overlayRoot();
  bindDagNodeClicks();
  if (typeof lucide !== 'undefined') createIconsIn(scroll);
}

// ── Auto-open flow-run tabs when flows start (P5) ──────────

function maybeAutoOpenFlowsTab(manual) {
  if (runningFlows.length === 0) return;
  for (const f of runningFlows) {
    if (f.status !== 'running' || hasTab(`flow-run-${f.instanceId}`)) continue;
    if (dismissedFlowRuns.has(f.instanceId)) {
      // Manual sidebar click = explicit user intent: it overrides the
      // dismissed suppression (#412 sessionStorage semantics stay for the
      // automatic paths - reload / fetch events never re-pop a dismissed tab).
      if (!manual) continue;
      dismissedFlowRuns.delete(f.instanceId);
      persistDismissedFlowRuns();
    }
    openFlowRunTab(f.instanceId, f.flowName);
  }
}

// ── Running flows indicator (2026-08-26 flow-complement-design §5.3) ──
// Once a user closes a flow-run tab there is no way to re-open the running
// flow's progress (the core 「进展重入」 gap). This indicator — badge count +
// dropdown list in the chat header, mirroring the Sub-Agents pattern — is the
// re-entry affordance. Badge count = running flows; clicking a row re-opens
// the flow-run tab and clears its dismiss mark so live progress renders.

function flowsIndicatorEl() { return document.getElementById('flows-indicator'); }
function flowsDropdownEl() { return document.getElementById('flows-dropdown'); }
function flowsDropdownListEl() {
  const dd = flowsDropdownEl();
  return /** @type {HTMLElement|null} */ (dd ? dd.querySelector('.flows-dropdown-list') : null);
}

function fmtDuration(ms) {
  if (ms == null || ms < 0) return '';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm';
  const h = Math.floor(m / 60);
  return h + 'h ' + (m % 60) + 'm';
}

/** Badge count = running flows; hide (and collapse dropdown) when zero. */
export function updateFlowsIndicator() {
  const el = flowsIndicatorEl();
  if (!el) return;
  const count = runningFlows.filter(f => f.status === 'running').length;
  if (count > 0) {
    el.classList.remove('hidden');
    const c = el.querySelector('.flows-count');
    if (c) c.textContent = String(count);
  } else {
    el.classList.add('hidden');
    el.setAttribute('aria-expanded', 'false');
    const dd = flowsDropdownEl();
    if (dd) dd.classList.add('hidden');
  }
}

function setFlowsDropdownOpen(open) {
  const dd = flowsDropdownEl();
  const ind = flowsIndicatorEl();
  if (!dd) return;
  if (open) {
    renderFlowsDropdown();
    dd.classList.remove('hidden');
    if (ind) ind.setAttribute('aria-expanded', 'true');
  } else {
    dd.classList.add('hidden');
    if (ind) ind.setAttribute('aria-expanded', 'false');
  }
}

/** Render the flows dropdown rows (running flows only) + wire row/cancel. */
export function renderFlowsDropdown() {
  const listEl = flowsDropdownListEl();
  if (!listEl) return;
  const running = runningFlows.filter(f => f.status === 'running');
  const headerEl = flowsDropdownEl()?.querySelector('.bg-dropdown-header');
  if (headerEl) headerEl.textContent = t('flows.running', { count: running.length });
  if (running.length === 0) {
    listEl.innerHTML = '<div class="bg-dropdown-empty">' + esc(t('flows.none')) + '</div>';
    return;
  }
  listEl.innerHTML = running.map(f => {
    const nodes = f.nodes || [];
    const done = nodes.filter(n => n.status === 'done').length;
    const total = nodes.length;
    const elapsed = f.startedAt ? fmtDuration(Date.now() - f.startedAt) : '';
    const statusCls = f.status === 'failed' ? 'failed' : 'running';
    return '<div class="flows-row" role="listitem" tabindex="0" data-flow-instance="' + esc(f.instanceId || '') + '">' +
      '<span class="flows-status ' + statusCls + '" aria-hidden="true"></span>' +
      '<div class="flows-info">' +
        '<div class="flows-line">' +
          '<span class="flows-name" title="' + esc(f.flowName || '') + '">' + esc(f.flowName || '') + '</span>' +
          '<span class="flows-progress">' + esc(t('flows.progress', { done, total })) + '</span>' +
          '<span class="flows-elapsed">' + esc(elapsed) + '</span>' +
        '</div>' +
      '</div>' +
      '<button class="flows-cancel" data-instance-id="' + esc(f.instanceId || '') + '">' + esc(t('flows.cancel')) + '</button>' +
    '</div>';
  }).join('');

  // Row click → re-open the flow-run tab and clear its dismiss mark (manual
  // re-open = explicit intent — later flowProgress must render the tab again).
  listEl.querySelectorAll('[data-flow-instance]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      if (/** @type {Element|null} */(e.target) && /** @type {Element} */(e.target).closest && /** @type {Element} */(e.target).closest('.flows-cancel')) return;
      const id = row.getAttribute('data-flow-instance');
      if (!id) return;
      const rf = runningFlows.find(f => f.instanceId === id);
      openFlowRunTab(id, rf ? rf.flowName : 'Flow run');
      dismissedFlowRuns.delete(id);
      persistDismissedFlowRuns();
      setFlowsDropdownOpen(false);
    });
  });
  listEl.querySelectorAll('.flows-cancel').forEach(btn => {
    const cancelBtn = /** @type {HTMLButtonElement} */(btn);
    cancelBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const id = cancelBtn.getAttribute('data-instance-id') || '';
      if (!id) return;
      cancelBtn.disabled = true;
      cancelBtn.textContent = '...';
      import('./ws.js').then(({ sendWs }) => { sendWs({ type: 'cancelFlow', instanceId: id }); });
    });
  });
  // Keyboard (APG listbox-lite): Enter/Space opens the row.
  listEl.onkeydown = (e) => {
    const tgt = /** @type {Element} */(e.target);
    const row = tgt && tgt.closest ? /** @type {HTMLElement} */(tgt.closest('.flows-row')) : null;
    if (!row) return;
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      row.click();
    }
  };
}

/** Re-render the dropdown only while it's open (live progress on node events). */
function refreshFlowsDropdownIfOpen() {
  const dd = flowsDropdownEl();
  if (dd && !dd.classList.contains('hidden')) renderFlowsDropdown();
}

/** Wire the flows badge/dropdown toggle + outside-click close (module init). */
function initFlowsIndicatorBindings() {
  const ind = flowsIndicatorEl();
  const dd = flowsDropdownEl();
  if (!ind || !dd) return;
  const toggle = (e) => {
    e.stopPropagation();
    setFlowsDropdownOpen(dd.classList.contains('hidden'));
  };
  ind.addEventListener('click', toggle);
  ind.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(e); }
  });
  document.addEventListener('click', (e) => {
    if (dd.classList.contains('hidden')) return;
    if (!dd.contains(/** @type {Node} */(e.target)) && !ind.contains(/** @type {Node} */(e.target))) {
      setFlowsDropdownOpen(false);
    }
  });
}

// ── WS event handlers ──────────────────────────────────────

export function onFlowStarted(msg) {
  // Full DAG structure arrives with flowStarted — render immediately.
  const existing = runningFlows.find(f => f.instanceId === (msg.instanceId || ''));
  if (!existing) {
    runningFlows.push({
      instanceId: msg.instanceId,
      flowName: msg.flowName,
      description: msg.description || '',
      entry: msg.entry,
      status: 'running',
      startedAt: Date.now(),
      nodes: (msg.nodes || []).map(n => ({ ...n, status: n.status || 'pending' })),
      edges: msg.edges || [],
    });
  }
  maybeAutoOpenFlowsTab();
  updateFlowsIndicator();
  if (isCanvasOpen()) renderOpenTabs();
}

/** Dynamically-expanded nodes from a flow's fan-out (FlowExecute ParallelDynamic).
 *  flowNodesAdded carries the new runtime node instances + their downstream
 *  join edges; append them to the running flow so the flow-run DAG grows live. */
export function onFlowNodesAdded(msg) {
  const rf = runningFlows.find(f => f.instanceId === (msg.instanceId || ''));
  if (!rf) return;
  for (const n of (msg.nodes || [])) {
    const existing = rf.nodes.find(x => x.nodeId === n.nodeId);
    if (!existing) rf.nodes.push({ ...n, status: n.status || 'pending' });
  }
  const edges = rf.edges || (rf.edges = []);
  for (const e of (msg.edges || [])) {
    if (!edges.some(x => x.from === e.from && x.to === e.to)) edges.push(e);
  }
  if (isCanvasOpen()) renderOpenTabs();
}

export function onFlowMail(msg) {
  const flow = teams.find(f => f.name === (msg.flowName || ''));
  const agent = flow?.agents?.find(a => a.name === (msg.to || ''));
  if (agent?.sessionId) {
    if (mailFlash.has(agent.sessionId)) clearTimeout(mailFlash.get(agent.sessionId));
    mailFlash.set(agent.sessionId, setTimeout(() => { mailFlash.delete(agent.sessionId); if (isCanvasOpen()) renderOpenTabs(); }, 1200));
  }
  if (isCanvasOpen()) renderOpenTabs();
}

// ── Mail queue (pending) events ────────────────────────────
// Both events carry the target agent's sessionId and the resulting
// pendingCount for that session. Update the shared count map (team-card
// badge) and live-refresh the mailbox viewer if open.
export function onMailQueued(msg) {
  setMailPending(msg.sessionId || '', typeof msg.pendingCount === 'number' ? msg.pendingCount : 0);
  refreshMailboxPending();
  if (isCanvasOpen()) renderOpenTabs();
}

export function onMailDequeued(msg) {
  setMailPending(msg.sessionId || '', typeof msg.pendingCount === 'number' ? msg.pendingCount : 0);
  refreshMailboxPending();
  if (isCanvasOpen()) renderOpenTabs();
}

export function onAgentStart(sessionId) {
  if (!sessionId) return;
  agentStatus.set(sessionId, 'running');
  if (isCanvasOpen()) renderOpenTabs();
}

export function onAgentDone(sessionId) {
  if (!sessionId) return;
  agentStatus.set(sessionId, 'idle');
  if (isCanvasOpen()) renderOpenTabs();
}

export function onFlowProgress(msg) {
  const rf = runningFlows.find(f => f.instanceId === (msg.instanceId || ''));
  if (rf) {
    const node = (rf.nodes || []).find(n => n.nodeId === (msg.nodeId || ''));
    if (node) { node.status = msg.status || ''; if (msg.output) node.output = msg.output; if (msg.error) node.error = msg.error; }
  }
  maybeAutoOpenFlowsTab();
  refreshFlowsDropdownIfOpen();
  if (isCanvasOpen()) renderOpenTabs();
}

export function onFlowCompleted(msg) {
  const rf = runningFlows.find(f => f.instanceId === (msg.instanceId || ''));
  if (rf) rf.status = msg.success ? 'completed' : 'failed';
  updateFlowsIndicator();
  fetchRunningFlows().then(() => { if (isCanvasOpen()) renderOpenTabs(); });
}

// ── Data fetch ─────────────────────────────────────────────

export async function fetchRunningFlows() {
  try {
    const resp = await fetch('/api/running-flows', { headers: authHeaders() });
    if (!resp.ok) return;
    const data = await resp.json();
    runningFlows = data.flows || [];
    updateFlowsIndicator();
    refreshFlowsDropdownIfOpen();
  } catch (e) { /* non-critical */ }
}

// Single-flight + short-window coalescing (W1-d): boot fans out several
// autoRestore() callers (openTeams / onSessionChange / reconnect callbacks),
// which fired up to 6 identical GET /api/teams/mounted — and they arrive in
// WAVES, not one concurrent burst, so pure in-flight dedup still leaves ~3.
// Rules: concurrent callers join the in-flight promise; a call within
// COALESCE_MS of a successful fetch reuses that data (a status snapshot
// 1.5s stale is fresh enough); anything later fetches fresh. refresh() has
// no force semantics — every caller is WS-event/boot-driven (teamList,
// treeBranchMounted/Unmounted/Updated), so there is no user-gesture caller
// that would need to bypass the coalesce window.
let autoRestoreInFlight = null;
let autoRestoreOkAt = 0;
const COALESCE_MS = 1500;

export function autoRestore() {
  if (autoRestoreInFlight) return autoRestoreInFlight;
  if (Date.now() - autoRestoreOkAt < COALESCE_MS) return Promise.resolve();
  autoRestoreInFlight = autoRestoreFetch()
    .then((ok) => { if (ok) autoRestoreOkAt = Date.now(); })
    .finally(() => { autoRestoreInFlight = null; });
  return autoRestoreInFlight;
}

async function autoRestoreFetch() {
  try {
    const resp = await fetch('/api/teams/mounted', { headers: authHeaders() });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    teams = data.teams || [];
    for (const f of teams) {
      for (const a of (f.agents || [])) {
        if (!a.sessionId) continue;
        const restStatus = (a.status || 'idle').toLowerCase() === 'running' ? 'running' : 'idle';
        const liveStatus = agentStatus.get(a.sessionId);
        // Live agentStart/agentDone events are the source of truth; the
        // server's /api/teams/mounted status (backed by the backend busyMap —
        // AgentActor markTeamBusy/markIdle, verified with 5 call sites) is
        // only a fallback. Never let a stale "idle" REST snapshot demote a
        // live "running" — a demote here would erase the running state the
        // moment the Teams tab is re-opened.
        if (!liveStatus) agentStatus.set(a.sessionId, restStatus);
        else if (restStatus === 'running' && liveStatus !== 'running') agentStatus.set(a.sessionId, 'running');
      }
    }
    teamsLoaded = true;
    autoRestoreRetryCount = 0;
    if (isCanvasOpen()) renderOpenTabs();
    return true;
  } catch (e) {
    console.warn('[flowCanvas] autoRestore failed:', e.message);
    if (autoRestoreRetryCount < 3 && isCanvasOpen()) {
      autoRestoreRetryCount++;
      const delays = [1000, 2000, 4000];
      setTimeout(() => autoRestore(), delays[autoRestoreRetryCount - 1]);
    }
    return false;
  }
}

export async function refresh() { await autoRestore(); }

export function getRunningFlows() {
  return teams.filter(f => (f.agents || []).some(a => { const live = a.sessionId ? agentStatus.get(a.sessionId) : null; return (live || a.status) === 'running'; })).map(f => {
    const agents = f.agents || [];
    return { name: f.name, flowName: f.name, running: agents.filter(a => { const live = a.sessionId ? agentStatus.get(a.sessionId) : null; return (live || a.status) === 'running'; }).length, done: agents.filter(a => { const live = a.sessionId ? agentStatus.get(a.sessionId) : null; return (live || a.status) === 'idle'; }).length, total: agents.length };
  });
}

// ── Canvas control ─────────────────────────────────────────

export function onSessionChange() { autoRestore(); }

export async function openTeams(opts) {
  /* Button pressed state is owned by canvas.js registerCanvasPanelButton —
     it strictly mirrors "this tab is the visible Canvas content". */
  /* Canvas closed + tab already exists: setActiveTab alone is invisible to
     the user (bug 2026-08-29) - expand the panel first. Canvas already open:
     unchanged behavior. */
  if (!isCanvasOpen()) openCanvas();
  if (hasTab('teams')) {
    setActiveTab('teams');
  } else {
    openTab('teams', t('activity.teams'), { type: 'teams', closable: true });
  }
  renderTeamsTab();
  autoRestore().then(() => { if (teams.length > 0) renderTeamsTab(); });
  fetchRunningFlows().then(() => {
    if (runningFlows.length > 0) maybeAutoOpenFlowsTab(!!(opts && opts.manual));
    /* Manual click intent wins: a flow-run tab auto-opened by the line above
       must not steal focus from the Teams view the user explicitly asked for
       (also keeps the button highlight consistent with the visible tab).
       Auto paths (boot) keep the legacy focus behavior. */
    if (opts && opts.manual && hasTab('teams')) setActiveTab('teams');
    if (hasTab('teams')) renderTeamsTab();
  });
}

export async function openFlows() {
  /* Same fix as openTeams: expand a closed canvas before activating the tab. */
  if (!isCanvasOpen()) openCanvas();
  if (hasTab('flows')) {
    setActiveTab('flows');
  } else {
    openTab('flows', t('activity.flows'), { type: 'flow', closable: true });
  }
  await fetchFlowDefs();
  renderFlowsTab();
}

// Test hook
if (typeof window !== 'undefined') {
  window.__testFlow = {
    openMailbox, openDefinition, openTeams, openFlowRunTab, _teams: () => teams,
    // flows indicator (flow-complement §5.3) — exposed for harness assertions.
    updateFlowsIndicator, renderFlowsDropdown,
    _runningFlows: () => runningFlows,
    _dismissed: () => [...dismissedFlowRuns],
    _persistDismissed: persistDismissedFlowRuns,
  };
}

// Activity Bar toggle registration (author 2026-08-30 4-state machine):
// pressed state strictly follows "this tab is the visible Canvas content".
registerCanvasPanelButton('teams', 'teams-btn', () => openTeams({ manual: true }));
registerCanvasPanelButton('flows', 'flows-btn', () => openFlows());

document.addEventListener('canvas-tab-closed', (e) => {
  if (e.detail?.id === 'teams') {
    closeViewer();
  }
  else if (e.detail?.id === 'flows') {
    closeViewer();
  }
});

// Re-render panel tabs restored from localStorage on page load.
// canvas.js re-creates the tab panes synchronously, then dispatches this
// event so we can fill them with live state. (Pressed state is synced
// centrally by canvas.js — restore must not light every restored button.)
window.addEventListener('canvas-tab-restore', (e) => {
  const { id } = e.detail || {};
  if (id === 'teams') {
    renderTeamsTab();
  }
  else if (id === 'flows') {
    renderFlowsTab();
  }
});

// Re-fetch teams on WS reconnect — covers the race condition where
// treeBranchMounted fires before the initial WS connection is established.
onReconnect(() => { autoRestore(); });

// Mailbox viewer's Cancel action updates pending counts directly — re-render
// the team cards so the badge stays in sync.
document.addEventListener('mail-pending-changed', () => { if (isCanvasOpen()) renderOpenTabs(); });

// ── Running flows indicator init (2026-08-26 flow-complement §5.3) ──
// Restore the persisted dismiss set, wire the badge/dropdown, then populate the
// indicator from the backend snapshot so a running flow is visible on page load
// even before the Teams/Flows panel is opened.
loadDismissedFlowRuns();
initFlowsIndicatorBindings();
fetchRunningFlows();
