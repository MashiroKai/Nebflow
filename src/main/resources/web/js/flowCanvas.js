// flowCanvas.js — Teams and Flows as independent Canvas tabs.
//
// TEAMS tab: Glass cards with agent tiles. Always accessible via toggle button.
// FLOWS tab: DAG node graph with real-time progress. Auto-opens when flows run.

import { openTab, getTabPane, hasTab, isCanvasOpen, setActiveTab } from './canvas.js';
import { FLOW_CSS } from './flowCss.js';
import { esc, authHeaders, overlayRoot } from './flowHelpers.js';
import { renderTeamsPanel, bindTileClicks, bindCardActions, bindFlowRowClicks, statusOf, populateTileModels } from './flowTeams.js';
import { renderFlowsPanel, bindDagNodeClicks } from './flowDag.js';
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

export function onFlowStarted(msg) {
  const rf = {
    instanceId: msg.instanceId,
    flowName: msg.flowName,
    description: msg.description || '',
    entry: msg.entry,
    status: 'running',
    nodes: msg.nodes || {},
    edges: msg.edges || [],
  };
  const existing = runningFlows.find(f => f.instanceId === msg.instanceId);
  if (!existing) runningFlows.push(rf);
  openFlowRunTab(msg.instanceId, msg.flowName);
}

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

// ── Flows tab ──────────────────────────────────────────────

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
    nodes: Object.fromEntries(
      Object.entries(dag.nodes || {}).map(([id, n]) => [id, { nodeId: id, agent: n.agent, status: 'static' }])
    ),
    edges: Object.entries(dag.nodes || {}).flatMap(([from, n]) => {
      const oc = n.onComplete;
      if (typeof oc === 'string') return [{ from, to: oc, condition: null }];
      if (oc && oc.switch) return Object.entries(oc.cases || {}).map(([cond, to]) => ({ from, to, condition: cond }));
      return [];
    }),
  };
  import('./flowDag.js').then(({ dagCardHtml, bindDagNodeClicks }) => {
    scroll.innerHTML = dagCardHtml(pseudoRf);
    bindDagNodeClicks();
  });
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

/** Open a dedicated tab for one running flow instance.
 *  Placeholder visualization (filtered DAG) — the full runtime view
 *  (恒星系 animation) lands in P5. */
export function openFlowRunTab(instanceId, flowName) {
  if (!instanceId) return;
  openTab(`flow-run-${instanceId}`, flowName || 'Flow run', { type: 'flow-run', closable: true });
  renderFlowRunTab(instanceId);
}

function renderFlowRunTab(instanceId) {
  const pane = getTabPane(`flow-run-${instanceId}`);
  if (!pane) return;
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
  const flow = runningFlows.find(f => f.instanceId === instanceId);
  let scroll = pane.querySelector('.stellar-container');
  if (!scroll) {
    pane.innerHTML = '';
    scroll = document.createElement('div');
    scroll.className = 'stellar-container';
    pane.appendChild(scroll);
  }
  import('./flowDag.js').then(({ renderStellarSystem, bindStellarNodeClicks }) => {
    renderStellarSystem(scroll, flow ? [flow] : []);
    bindStellarNodeClicks();
    if (typeof lucide !== 'undefined') lucide.createIcons();
  });
}

// ── Auto-open Flows tab when running flows appear ──────────

function maybeAutoOpenFlowsTab() {
  if (runningFlows.length === 0) return;
  if (hasTab('flows')) return;
  // Open the Flows tab and switch to it.
  openTab('flows', 'Flows', { type: 'flow', closable: true });
  renderFlowsTab();
}

// ── WS event handlers ──────────────────────────────────────

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
        if (!liveStatus) agentStatus.set(a.sessionId, restStatus);
        else if (liveStatus === 'running' && restStatus === 'idle') agentStatus.set(a.sessionId, 'idle');
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
    openTab('teams', 'Teams', { type: 'teams', closable: false });
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
