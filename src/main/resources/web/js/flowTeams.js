// flowTeams.js — Team panel rendering: glass cards with agent tiles + inline flow DAG.
//
// Each mounted team is a glass card with a header (name, rules, mailbox buttons),
// an agent grid, and a Flows section showing flow DAG nodes with real-time status.
// Clicking an agent tile opens a chat popup. Clicking a flow row expands/collapses
// its DAG nodes inline.

import { openStepPopup } from './flowAgentPopup.js';
import { esc, authHeaders, overlayRoot, teamPendingCount } from './flowHelpers.js';
import { orderDagNodes, dagNodeInlineHtml } from './flowDag.js';
import state from './state.js';
import { onMessage } from './ws.js';

// ── Module-level state for flow rows ───────────────────────
const dagCache = new Map();           // flowName → { ordered: [{nodeId, agent}] }
const expandedFlows = new Set();      // "teamName/flowName" keys
const manuallyCollapsed = new Set();  // same key — prevents auto-expand

// ── Model label cache ──────────────────────────────────────
// The model label is stable for the session — resolve it once per agent and
// reuse on every re-render instead of re-fetching /api/agents/:name/model.
const modelCache = new Map();         // agentName → shortName
const modelPending = new Set();       // agentName keys with an in-flight fetch

export function statusOf(agent, agentStatus) {
  const live = agent.sessionId ? agentStatus.get(agent.sessionId) : null;
  const s = (live || agent.status || 'idle').toLowerCase();
  if (s === 'running' || s === 'busy') return 'running';
  return 'idle';
}

// ── DAG data helpers ───────────────────────────────────────

/** Fetch static DAG structure from /api/flow/dag/:flowName and cache it. */
async function fetchDag(flowName) {
  if (dagCache.has(flowName)) return dagCache.get(flowName);
  try {
    const resp = await fetch(`/api/flow/dag/${encodeURIComponent(flowName)}`, { headers: authHeaders() });
    if (!resp.ok) return null;
    const data = await resp.json();
    // Normalize: nodes may be a map { nodeId: { agent, ... } } or an array
    const rawNodes = data.nodes || {};
    const isMap = typeof rawNodes === 'object' && !Array.isArray(rawNodes);
    const nodeArr = isMap
      ? Object.entries(rawNodes).map(([id, n]) => ({ nodeId: id, agent: (n && n.agent) || '' }))
      : (Array.isArray(rawNodes) ? rawNodes.map(n => ({ nodeId: n.nodeId || '', agent: n.agent || '' })) : []);

    // Topological sort — derive edges from onComplete fields
    const edges = [];
    for (const [id, node] of Object.entries(rawNodes)) {
      if (!node) continue;
      const oc = node.onComplete;
      if (typeof oc === 'string') {
        if (oc !== '$return') edges.push({ from: id, to: oc });
      } else if (oc && typeof oc === 'object' && oc.cases) {
        for (const target of Object.values(oc.cases)) {
          if (typeof target === 'string' && target !== '$return') edges.push({ from: id, to: target });
        }
      }
    }
    const entry = data.entry;
    const childrenMap = new Map();
    edges.forEach(e => {
      if (!childrenMap.has(e.from)) childrenMap.set(e.from, []);
      childrenMap.get(e.from).push(e.to);
    });
    const ordered = [];
    const visited = new Set();
    function visit(id) {
      if (visited.has(id)) return;
      visited.add(id);
      const node = nodeArr.find(n => n.nodeId === id);
      if (node) ordered.push(node);
      (childrenMap.get(id) || []).forEach(c => visit(c));
    }
    if (entry) visit(entry);
    nodeArr.forEach(n => { if (!visited.has(n.nodeId)) ordered.push(n); });

    const result = { ordered };
    dagCache.set(flowName, result);
    return result;
  } catch (e) { return null; }
}

/** Get ordered nodes with status for inline rendering.
 *  - Running flow → use real-time node statuses from runningFlows
 *  - Not running → use cached static DAG (all 'idle') */
function getInlineNodes(flowName, runningFlows) {
  const rf = runningFlows?.find(r => r.flowName === flowName);
  if (rf) {
    // Running — use orderDagNodes from flowDag.js (handles edges + entry)
    return orderDagNodes(rf);
  }
  // Static DAG from cache
  const dag = dagCache.get(flowName);
  if (dag) {
    return dag.ordered.map(n => ({ node: { nodeId: n.nodeId, agent: n.agent, status: 'idle' }, edgeLabel: null }));
  }
  return [];
}

// ── Card rendering ─────────────────────────────────────────

export function flowCardHtml(flow, agentStatus, mailFlash, runningFlows) {
  const agents = flow.agents || [];
  const running = agents.filter(a => statusOf(a, agentStatus) === 'running').length;
  const summaryText = running > 0 ? `${running} running` : `${agents.length} idle`;
  const summaryCls = running > 0 ? 'running' : '';

  // Pending mail-queue badge — sum of per-agent pending counts, hidden at 0.
  const pending = teamPendingCount(agents);
  const mailBadge = pending > 0 ? `<span class="team-mail-badge">${pending > 99 ? '99+' : pending}</span>` : '';

  const tilesHtml = agents.map(a => {
    const st = statusOf(a, agentStatus);
    const isManager = !!a.manager;
    const flash = a.sessionId && mailFlash.has(a.sessionId) ? ' mail-flash' : '';
    const role = isManager ? 'manager' : st;
    return `
      <div class="team-tile ${st}${isManager ? ' manager' : ''}${flash}"
           data-flow="${esc(flow.name)}"
           data-agent="${esc(a.name)}"
           data-sid="${esc(a.sessionId || '')}"
           data-status="${esc(st)}"
           title="${esc(a.duty || a.description || a.name)}">
        <div class="team-tile-dot"></div>
        <div class="team-tile-name">${esc(a.name)}</div>
        <div class="team-tile-role">${role}</div>
      </div>`;
  }).join('');

  // ── Flow rows with inline DAG ──
  const flowNames = flow.flows || [];
  let flowsSectionHtml = '';
  if (flowNames.length > 0) {
    const rowsHtml = flowNames.map(fn => {
      const isRunning = (runningFlows || []).some(rf => rf.flowName === fn);
      const key = `${flow.name}/${fn}`;
      const isExpanded = expandedFlows.has(key);

      // Render inline DAG nodes if expanded
      let dagHtml = '';
      if (isExpanded) {
        const nodes = getInlineNodes(fn, runningFlows);
        dagHtml = nodes.map(item => dagNodeInlineHtml(item, fn)).join('');
      }

      return `
        <div class="team-flow-row${isRunning ? ' running' : ''}${isExpanded ? ' expanded' : ''}"
             data-flow-name="${esc(fn)}" data-team="${esc(flow.name)}">
          <div class="team-flow-hex"></div>
          <div class="team-flow-label">${esc(fn)}</div>
          <div class="team-flow-status"></div>
          <div class="team-flow-chevron">\u25B6</div>
        </div>
        ${isExpanded ? `<div class="team-flow-dag">${dagHtml}</div>` : ''}`;
    }).join('');

    flowsSectionHtml = `
      <div class="team-flows-divider"></div>
      <div class="team-flows-section">
        <div class="team-flows-header">Flows</div>
        ${rowsHtml}
      </div>`;
  }

  return `
    <div class="team-card">
      <div class="team-card-header">
        <div class="team-card-title" data-flow="${esc(flow.name)}" data-act="def" title="View team definition">${esc(flow.name)}</div>
        <div class="team-card-actions">
          <button class="team-act-btn" data-act="rules" data-flow="${esc(flow.name)}" title="Team rules"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="15" y2="17"/></svg></button>
          <button class="team-act-btn" data-act="mailbox" data-flow="${esc(flow.name)}" title="Inbox"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>${mailBadge}</button>
        </div>
        <div class="team-card-summary ${summaryCls}"><span class="dot"></span>${summaryText}</div>
      </div>
      <div class="team-agents">${tilesHtml}</div>
      ${flowsSectionHtml}
    </div>`;
}

export function renderTeamsPanel(scroll, flows, agentStatus, mailFlash, runningFlows) {
  if (flows.length === 0) {
    scroll.innerHTML = `<div class="team-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No active teams</div><div class="hint">Mount a team to see its agents</div></div>`;
    return;
  }

  // Auto-expand running flows (unless manually collapsed)
  for (const team of flows) {
    for (const fn of (team.flows || [])) {
      const key = `${team.name}/${fn}`;
      const isRunning = (runningFlows || []).some(rf => rf.flowName === fn);
      if (isRunning && !manuallyCollapsed.has(key)) {
        expandedFlows.add(key);
      }
    }
  }

  scroll.innerHTML = flows.map(flow => flowCardHtml(flow, agentStatus, mailFlash, runningFlows)).join('');
}

export function bindTileClicks() {
  document.querySelectorAll('.team-tile').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      const flowName = el.getAttribute('data-flow') || '';
      const agentName = el.getAttribute('data-agent') || '';
      const sid = el.getAttribute('data-sid') || null;
      openStepPopup(`${flowName}/${agentName}`, agentName, agentName, flowName, sid || null);
    });
  });
}

/** Async-fetch model labels for all agent tiles and inject them. */
export async function populateTileModels(teams) {
  const tileSel = (flowName, agentName) =>
    `.team-tile[data-agent="${esc(agentName)}"][data-flow="${esc(flowName)}"]`;
  // #308 actual model: the session's last ACTUALLY-used model is
  // authoritative; REST preferred/current is only a fallback when the agent
  // has never run a LLM round (no sessionModelInfo entry).
  const liveModelShort = (sid) => {
    if (!sid) return null;
    const model = state.sessionModelInfo[sid]?.model;
    if (!model) return null;
    const i = model.lastIndexOf('/');
    return i >= 0 ? model.slice(i + 1) : model;
  };
  for (const flow of teams) {
    for (const a of (flow.agents || [])) {
      const tile = document.querySelector(tileSel(flow.name, a.name));
      if (!tile) continue;
      // role comes from the fixed data-status attribute — NOT from
      // roleEl.textContent — so re-invoking this function (async fetch
      // racing with WS-triggered re-renders) never appends the model
      // name twice.
      const writeLabel = (el, shortName) => {
        if (!el.isConnected) return; // panel was rebuilt while we awaited
        const roleEl = el.querySelector('.team-tile-role');
        if (!roleEl) return;
        const role = !!a.manager ? 'manager' : (el.getAttribute('data-status') || 'idle');
        roleEl.textContent = `${role} · ${shortName}`;
      };
      const live = liveModelShort(a.sessionId);
      // Cache hit — write synchronously, no fetch. Live overrides cache.
      const cached = modelCache.get(a.name);
      if (cached) { writeLabel(tile, live || cached); continue; }
      // One in-flight fetch per agent — concurrent re-renders share it.
      if (modelPending.has(a.name)) continue;
      modelPending.add(a.name);
      try {
        const resp = await fetch(`/api/agents/${encodeURIComponent(a.name)}/model`, { headers: authHeaders() });
        if (!resp.ok) continue;
        const cfg = await resp.json();
        // #308: live actual model first, then health-resolved current, then
        // configured preferred — never show "preferred" as if it were live.
        const current = live || cfg.current || cfg.preferred || cfg.default || '';
        if (!current) continue;
        const slashIdx = current.lastIndexOf('/');
        const shortName = slashIdx >= 0 ? current.slice(slashIdx + 1) : current;
        modelCache.set(a.name, shortName);
        // Re-query the tile — the panel may have been rebuilt while we awaited,
        // leaving our original `tile` reference detached.
        const liveTile = document.querySelector(tileSel(flow.name, a.name));
        if (liveTile) writeLabel(liveTile, shortName);
      } catch (e) { /* skip */ }
      finally { modelPending.delete(a.name); }
    }
  }
}

// #308 live refresh: when a LLM round reports the actual model for a team
// agent session, update its tile label immediately (direct DOM write — no
// cache invalidation needed; the fetch path prefers live anyway).
function refreshTileModelLive(sid) {
  if (!sid) return;
  const info = state.sessionModelInfo[sid];
  if (!info?.model) return;
  const i = info.model.lastIndexOf('/');
  const short = i >= 0 ? info.model.slice(i + 1) : info.model;
  document.querySelectorAll(`.team-tile[data-sid="${CSS.escape(sid)}"]`).forEach((tile) => {
    const roleEl = tile.querySelector('.team-tile-role');
    if (!roleEl) return;
    const role = tile.classList.contains('manager') ? 'manager' : (tile.getAttribute('data-status') || 'idle');
    roleEl.textContent = `${role} · ${short}`;
  });
}
onMessage('usageUpdate', (msg) => refreshTileModelLive(msg.sessionId));
onMessage('done', (msg) => refreshTileModelLive(msg.sessionId));

export function bindCardActions(openMailbox, openRules, openDefinition) {
  document.querySelectorAll('.team-act-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const act = btn.getAttribute('data-act');
      const flowName = btn.getAttribute('data-flow') || '';
      if (act === 'mailbox') openMailbox(flowName);
      else if (act === 'rules') openRules(flowName);
    });
  });
  document.querySelectorAll('.team-card-title[data-act="def"]').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openDefinition(el.getAttribute('data-flow') || '');
    });
  });
}

/** Bind expand/collapse clicks for flow rows + node clicks for inline DAG nodes. */
export function bindFlowRowClicks(runningFlows, reRender) {
  document.querySelectorAll('.team-flow-row').forEach(el => {
    el.addEventListener('click', async (e) => {
      e.stopPropagation();
      const flowName = el.getAttribute('data-flow-name') || '';
      const teamName = el.getAttribute('data-team') || '';
      const key = `${teamName}/${flowName}`;
      const isRunning = (runningFlows || []).some(rf => rf.flowName === flowName);

      if (expandedFlows.has(key)) {
        expandedFlows.delete(key);
        if (isRunning) manuallyCollapsed.add(key);
      } else {
        expandedFlows.add(key);
        manuallyCollapsed.delete(key);
        // Fetch static DAG if not running and not cached
        if (!isRunning && !dagCache.has(flowName)) {
          await fetchDag(flowName);
        }
      }
      reRender();
    });
  });

  // Inline DAG node clicks — open agent popup
  document.querySelectorAll('.dag-inline-node').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      const flowName = el.getAttribute('data-flow') || '';
      const agentName = el.getAttribute('data-agent') || '';
      const nodeId = el.getAttribute('data-node') || '';
      openStepPopup(`${flowName}/${nodeId}`, nodeId, agentName, flowName, null);
    });
  });
}
