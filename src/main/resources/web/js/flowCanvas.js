// flowCanvas.js — Teams and Flows as independent Canvas tabs.
//
// TEAMS tab: Glass cards with agent tiles. Always accessible via toggle button.
// FLOWS tab: DAG node graph with real-time progress. Auto-opens when flows run.

import { openTab, getTabPane, hasTab, isCanvasOpen, setActiveTab } from './canvas.js';
import { FLOW_CSS } from './flowCss.js';
import { esc, authHeaders, overlayRoot } from './flowHelpers.js';
import { renderTeamsPanel, bindTileClicks, bindCardActions, bindFlowRowClicks, statusOf, populateTileModels } from './flowTeams.js';
import { renderFlowRunInto, renderFlowsPanel, bindDagNodeClicks, dagCardHtml, renderStellarSystem, bindStellarNodeClicks } from './flowDag.js';
import { renderFlowList } from './flowList.js';
import { closeViewer, openMailbox, openRules, openDefinition } from './flowViewers.js';
import { onReconnect } from './ws.js';

// ── State ──────────────────────────────────────────────────
let teams = [];           // Team definitions from /api/teams (disk)
let teamsLoaded = false;
const agentStatus = new Map();
const mailFlash = new Map();
let autoRestoreRetryCount = 0;
let runningFlows = [];
let flowsTabAutoOpened = false;  // prevent repeated auto-open
let flowDefs = [];

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
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
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
  bindCardActions(openMailbox, openRules, openDefinition);
  bindFlowRowClicks(runningFlows, () => renderTeamsTab());
  if (typeof lucide !== 'undefined') lucide.createIcons();
  populateTileModels(teams);
}

// ── Flows tab (static flow list, P6) ───────────────────────

function renderFlowsTab() {
  const pane = getTabPane('flows');
  if (!pane) return;
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
  let scroll = pane.querySelector('#flow-scroll-flows');
  if (!scroll) {
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.id = 'flow-scroll-flows';
    scroll.style.flexDirection = 'column';
    scroll.style.alignItems = 'stretch';
    pane.appendChild(scroll);
    // Flow definitions are rendered inline below from the flowDefs state
    // (populated by fetchFlowDefs). The previous renderFlowList() call was
    // removed because its async fetch would race with and overwrite the
    // inline content.
  }
  let defsHtml = '';
  if (flowDefs.length > 0) {
    defsHtml = `<div class="flow-defs-section">
      <div class="flow-defs-header">Flow Definitions</div>
      <div class="flow-defs-grid">
        ${flowDefs.map(fd => `
          <div class="flow-def-card" data-flow-name="${esc(fd.name)}">
            <div class="flow-def-name">${esc(fd.name)}</div>
            ${fd.description ? `<div class="flow-def-desc">${esc(fd.description)}</div>` : ''}
            <div class="flow-def-meta">${fd.nodeCount || 0} nodes</div>
            <button class="flow-def-view-btn" data-flow-name="${esc(fd.name)}">View DAG \u2192</button>
          </div>
        `).join('')}
      </div>
    </div>`;
  }
  let runningHtml = '';
  if (runningFlows.length > 0) {
    runningHtml = `<div class="flow-running-section">
      <div class="flow-defs-header">Running Instances</div>
      ${runningFlows.map(rf => dagCardHtml(rf)).join('')}
    </div>`;
  }
  if (flowDefs.length === 0 && runningFlows.length === 0) {
    scroll.innerHTML = `<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No flows defined</div></div>`;
  } else {
    scroll.innerHTML = defsHtml + runningHtml;
  }
  overlayRoot();
  bindDagNodeClicks();
  scroll.querySelectorAll('.flow-def-view-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const flowName = btn.dataset.flowName;
      openTab(`flow-def-${flowName}`, `DAG: ${flowName}`, { type: 'flow', closable: true });
      const defPane = getTabPane(`flow-def-${flowName}`);
      if (defPane) {
        defPane.innerHTML = '<div class="agent-detail-loading">Loading DAG...</div>';
        fetch(`/api/flow/dag/${encodeURIComponent(flowName)}`, { headers: authHeaders() })
          .then(r => r.json())
          .then(dag => renderStaticDag(defPane, dag));
      }
    });
  });
  if (typeof lucide !== 'undefined') lucide.createIcons();
}

function renderStaticDag(pane, dag) {
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.style.flexDirection = 'column';
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
  if (typeof lucide !== 'undefined') lucide.createIcons();
}

// Render whichever tab(s) are open.
function renderOpenTabs() {
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
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    pane.innerHTML = '';
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.style.display = 'block';
    pane.appendChild(scroll);
  }
  const flow = runningFlows.find(f => f.instanceId === instanceId);
  renderFlowRunInto(scroll, flow);
  overlayRoot();
  bindDagNodeClicks();
  if (typeof lucide !== 'undefined') lucide.createIcons();
}

// ── Auto-open flow-run tabs when flows start (P5) ──────────

function maybeAutoOpenFlowsTab() {
  if (runningFlows.length === 0) return;
  for (const f of runningFlows) {
    if (f.status === 'running' && !hasTab(`flow-run-${f.instanceId}`)) {
      openFlowRunTab(f.instanceId, f.flowName);
    }
  }
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
  if (isCanvasOpen()) renderOpenTabs();
}

export function onFlowCompleted(msg) {
  const rf = runningFlows.find(f => f.instanceId === (msg.instanceId || ''));
  if (rf) rf.status = msg.success ? 'completed' : 'failed';
  fetchRunningFlows().then(() => { if (isCanvasOpen()) renderOpenTabs(); });
}

// ── Data fetch ─────────────────────────────────────────────

export async function fetchRunningFlows() {
  try {
    const resp = await fetch('/api/running-flows', { headers: authHeaders() });
    if (!resp.ok) return;
    const data = await resp.json();
    runningFlows = data.flows || [];
  } catch (e) { /* non-critical */ }
}

export async function autoRestore() {
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
        // server's /api/teams/mounted status is only a fallback. Never let a
        // stale "idle" from the server demote a live "running" — the backend
        // busyMap (FlowTreeActor.markBusy/markIdle) has no callers and always
        // reports idle, so a demote here would erase the running state the
        // moment the Teams tab is re-opened.
        if (!liveStatus) agentStatus.set(a.sessionId, restStatus);
        else if (restStatus === 'running' && liveStatus !== 'running') agentStatus.set(a.sessionId, 'running');
      }
    }
    teamsLoaded = true;
    autoRestoreRetryCount = 0;
    if (isCanvasOpen()) renderOpenTabs();
  } catch (e) {
    console.warn('[flowCanvas] autoRestore failed:', e.message);
    if (autoRestoreRetryCount < 3 && isCanvasOpen()) {
      autoRestoreRetryCount++;
      const delays = [1000, 2000, 4000];
      setTimeout(() => autoRestore(), delays[autoRestoreRetryCount - 1]);
    }
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

export async function openTeams() {
  const btn = document.getElementById('teams-btn');
  btn?.classList.add('active');
  if (hasTab('teams')) {
    setActiveTab('teams');
  } else {
    openTab('teams', 'Teams', { type: 'teams', closable: true });
  }
  renderTeamsTab();
  autoRestore().then(() => { if (teams.length > 0) renderTeamsTab(); });
  fetchRunningFlows().then(() => {
    if (runningFlows.length > 0) maybeAutoOpenFlowsTab();
    if (hasTab('teams')) renderTeamsTab();
  });
}

export async function openFlows() {
  const btn = document.getElementById('flows-btn');
  btn?.classList.add('active');
  if (hasTab('flows')) {
    setActiveTab('flows');
  } else {
    openTab('flows', 'Flows', { type: 'flow', closable: true });
  }
  await fetchFlowDefs();
  renderFlowsTab();
}

// Test hook
if (typeof window !== 'undefined') {
  window.__testFlow = { openMailbox, openDefinition, openTeams, openFlowRunTab, _teams: () => teams };
}

document.addEventListener('canvas-tab-closed', (e) => {
  if (e.detail?.id === 'teams') {
    closeViewer();
    document.getElementById('teams-btn')?.classList.remove('active');
  }
  else if (e.detail?.id === 'flows') {
    closeViewer();
    document.getElementById('flows-btn')?.classList.remove('active');
  }
});

// Re-render panel tabs restored from localStorage on page load.
// canvas.js re-creates the tab panes synchronously, then dispatches this
// event so we can fill them with live state.
window.addEventListener('canvas-tab-restore', (e) => {
  const { id } = e.detail || {};
  if (id === 'teams') {
    document.getElementById('teams-btn')?.classList.add('active');
    renderTeamsTab();
  }
  else if (id === 'flows') {
    document.getElementById('flows-btn')?.classList.add('active');
    renderFlowsTab();
  }
});

// Re-fetch teams on WS reconnect — covers the race condition where
// treeBranchMounted fires before the initial WS connection is established.
onReconnect(() => { autoRestore(); });
