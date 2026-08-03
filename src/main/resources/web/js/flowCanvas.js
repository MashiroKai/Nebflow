// flowCanvas.js — Teams and Flows as independent Canvas tabs.
//
// TEAMS tab: Glass cards with agent tiles. Always accessible via toggle button.
// FLOWS tab: DAG node graph with real-time progress. Auto-opens when flows run.

import { openTab, closeCanvas, getTabPane, hasTab, isCanvasOpen } from './canvas.js';
import { FLOW_CSS } from './flowCss.js';
import { esc, authHeaders, overlayRoot } from './flowHelpers.js';
import { renderTeamsPanel, bindTileClicks, bindCardActions, statusOf } from './flowTeams.js';
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
    scroll.className = 'flow-scroll';
    scroll.id = 'team-scroll';
    pane.appendChild(scroll);
  }
  renderTeamsPanel(scroll, teams, agentStatus, mailFlash);
  overlayRoot();
  bindTileClicks();
  bindCardActions(openMailbox, openRules, openDefinition);
  if (typeof lucide !== 'undefined') lucide.createIcons();
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
    scroll.className = 'flow-scroll';
    scroll.id = 'flow-scroll-flows';
    pane.appendChild(scroll);
  }
  renderFlowsPanel(scroll, runningFlows);
  overlayRoot();
  bindDagNodeClicks();
  if (typeof lucide !== 'undefined') lucide.createIcons();
}

// Render whichever tab(s) are open.
function renderOpenTabs() {
  if (hasTab('teams')) renderTeamsTab();
  if (hasTab('flows')) renderFlowsTab();
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
  if (isCanvasOpen() && hasTab('flows')) renderFlowsTab();
}

export function onFlowCompleted(msg) {
  const rf = runningFlows.find(f => f.instanceId === (msg.instanceId || ''));
  if (rf) rf.status = msg.success ? 'completed' : 'failed';
  fetchRunningFlows().then(() => { if (isCanvasOpen() && hasTab('flows')) renderFlowsTab(); });
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

export async function toggleCanvas() {
  const btn = document.getElementById('team-toggle-btn');
  if (hasTab('teams') && !hasTab('flows')) {
    // Teams tab is open and no flows tab — close everything.
    btn?.classList.remove('active');
    closeCanvas();
    return;
  }
  btn?.classList.add('active');
  openTab('teams', 'Teams', { type: 'flow', closable: false });
  renderTeamsTab();
  // Always fetch fresh data — don't rely on potentially stale cache.
  // If the initial load returned empty (e.g. teams not mounted yet),
  // this ensures we retry on every panel open.
  autoRestore().then(() => { if (teams.length > 0) renderTeamsTab(); });
  fetchRunningFlows().then(() => {
    if (runningFlows.length > 0) maybeAutoOpenFlowsTab();
  });
}

// Test hook
if (typeof window !== 'undefined') {
  window.__testFlow = { openMailbox, openDefinition, toggleCanvas, _teams: () => teams };
}

document.addEventListener('canvas-tab-closed', (e) => {
  if (e.detail?.id === 'teams') {
    closeViewer();
    document.getElementById('team-toggle-btn')?.classList.remove('active');
  }
  if (e.detail?.id === 'flows') {
    closeViewer();
  }
});

// Re-fetch teams on WS reconnect — covers the race condition where
// treeBranchMounted fires before the initial WS connection is established.
onReconnect(() => { autoRestore(); });
