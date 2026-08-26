// flowDag.js — Solar-system DAG visualization for running flow instances.
//
// Each flow instance renders as a "solar system": DAG nodes laid out by
// dependency depth, each node drawn as concentric orbit rings whose dots
// spin while the node is running. SVG edges connect nodes with condition
// labels. Clicking a node opens the agent chat popup.
//
// Kept exports (used elsewhere):
//   orderDagNodes(rf)          — topological order (flowTeams.js)
//   dagNodeInlineHtml(item, flowName) — compact pill (flowTeams.js)
//   renderFlowsPanel(scroll, runningFlows) — list of solar cards (flowCanvas.js)
//   bindDagNodeClicks()        — bind node popups + cancel buttons (flowCanvas.js)

import { openStepPopup, resolveFlowNodeSession } from './flowAgentPopup.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';

/** Shared DAG-node click: resolve the node's REAL dag-* session before
 *  opening the popup — without it the popup keyed on a synthetic "flow/node"
 *  id and showed an empty window (372-1). Nodes that never ran get a toast. */
export async function openFlowNodePopup(flowName, agentName, nodeId) {
  const sid = await resolveFlowNodeSession(flowName, nodeId);
  if (!sid) {
    window.__showToast?.(t('flow.noSession'), 'info');
    return;
  }
  openStepPopup(`${flowName}/${nodeId}`, nodeId, agentName, flowName, sid, 'Flow');
}

const NODE_W = 124;   // glass card width (372-3: +14 so nodeId labels fit inside the card)
const NODE_H = 88;    // glass card height (372-3: +8 — sub line no longer clipped)
const V_SPACING = 120;
const H_SPACING = 172;
const PAD = 60;       // canvas padding around nodes

/** Topological sort of DAG nodes following edges from entry.
 *  Returns ordered array of { node, edgeLabel }. Exported for reuse. */
export function orderDagNodes(rf) {
  const nodes = rf.nodes || [];
  const edges = rf.edges || [];
  const nodeMap = new Map(nodes.map(n => [n.nodeId, n]));

  const childrenMap = new Map();
  edges.forEach(e => {
    if (!childrenMap.has(e.from)) childrenMap.set(e.from, []);
    childrenMap.get(e.from).push({ to: e.to, condition: e.condition });
  });

  const ordered = [];
  const visited = new Set();
  function visit(nodeId, edgeLabel) {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    ordered.push({ node: nodeMap.get(nodeId), edgeLabel });
    const children = childrenMap.get(nodeId) || [];
    children.forEach(c => visit(c.to, c.condition));
  }
  if (rf.entry) visit(rf.entry, null);
  // Append any nodes not reachable from entry
  nodes.forEach(n => { if (!visited.has(n.nodeId)) ordered.push({ node: n, edgeLabel: null }); });
  return ordered;
}

/** Lightweight single-line pill for inline DAG display inside team cards. */
export function dagNodeInlineHtml(item, flowName) {
  const n = item.node || item;
  const nodeId = n.nodeId || '';
  const agent = n.agent || '';
  const st = n.status || 'idle';
  return `\
      <div class="dag-inline-node ${st}" data-flow="${esc(flowName)}" data-agent="${esc(agent)}" data-node="${esc(nodeId)}">
        <div class="dag-inline-dot"></div>
        <div class="dag-inline-id">${esc(nodeId)}</div>
        ${agent && agent !== nodeId ? `<div class="dag-inline-agent">${esc(agent)}</div>` : ''}
      </div>`;
}

// ── Layout ─────────────────────────────────────────────────

/** Lay out DAG nodes by dependency depth. Returns { positions, width, height }. */
export function layoutDagNodes(rf) {
  const nodes = rf.nodes || [];
  const edges = rf.edges || [];

  const childrenMap = new Map();
  edges.forEach(e => {
    if (!childrenMap.has(e.from)) childrenMap.set(e.from, []);
    childrenMap.get(e.from).push(e.to);
  });

  // Longest-path depth from entry (handles fan-out / join)
  const depth = {};
  nodes.forEach(n => { depth[n.nodeId] = 0; });
  orderDagNodes(rf).forEach(({ node }) => {
    if (!node) return;
    const d = depth[node.nodeId] || 0;
    (childrenMap.get(node.nodeId) || []).forEach(to => {
      if (to === '$return') return;
      depth[to] = Math.max(depth[to] || 0, d + 1);
    });
  });

  const nodesAtDepth = {};
  nodes.forEach(n => {
    const d = depth[n.nodeId] || 0;
    (nodesAtDepth[d] = nodesAtDepth[d] || []).push(n.nodeId);
  });

  const positions = {};
  const maxAtDepth = Object.values(nodesAtDepth).reduce((m, ids) => Math.max(m, ids.length), 0);
  Object.entries(nodesAtDepth).forEach(([d, ids]) => {
    const y = Number(d) * V_SPACING;
    const total = (ids.length - 1) * H_SPACING;
    ids.forEach((id, i) => {
      positions[id] = { x: i * H_SPACING - total / 2, y };
    });
  });

  const maxDepth = Math.max(0, ...Object.keys(nodesAtDepth).map(Number));
  const width = Math.max((maxAtDepth - 1) * H_SPACING + NODE_W + PAD * 2, 320);
  // Vertical: node row centers sit at y = depth*V_SPACING; with top offset
  // PAD the first row's top edge is PAD - NODE_H/2 from the canvas top.
  // height = lastRowCenter + PAD keeps the bottom margin identical (no extra
  // NODE_H term — that used to make the bottom margin 100px vs 20px on top).
  const height = maxDepth * V_SPACING + PAD * 2;

  return { positions, width, height };
}

// ── Solar-system node ──────────────────────────────────────

/** Node positions from layoutDagNodes are ROW-CENTERED on x=0 (can be
 *  negative, e.g. a 3-node row is x=-160/0/160). Render with the canvas
 *  center as the horizontal origin (originX = width/2) — using PAD as the
 *  origin pushed negative-x nodes off the left edge (clipped) and made the
 *  whole graph lean left instead of centering. */
function solarNodeHtml(n, flowName, pos, statusOf, originX) {
  const nodeId = n.nodeId || '';
  const agent = n.agent || '';
  const st = statusOf(n) || 'pending';
  const left = pos.x - NODE_W / 2 + originX;
  const top = pos.y - NODE_H / 2 + PAD;
  const statusIcon = st === 'completed' ? '<span class="solar-node-status ok">✓</span>'
    : st === 'failed' ? '<span class="solar-node-status err">✗</span>' : '';
  return `
    <div class="solar-node ${st}" data-flow="${esc(flowName)}" data-agent="${esc(agent)}" data-node="${esc(nodeId)}"
         style="left:${left.toFixed(1)}px;top:${top.toFixed(1)}px">
      <div class="solar-orbit">
        <div class="solar-ring ring-1"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
        <div class="solar-ring ring-2"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
        <div class="solar-ring ring-3"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
      </div>
      <div class="solar-node-label" title="${esc(agent)}">${esc(nodeId)}</div>
      ${agent && agent !== nodeId ? `<div class="solar-node-sub">${esc(agent)}${statusIcon}</div>` : (statusIcon ? `<div class="solar-node-sub">${statusIcon}</div>` : '')}
    </div>`;
}

// ── Edges (SVG) ────────────────────────────────────────────

function solarEdgesSvg(rf, positions, statusOf) {
  const edges = (rf.edges || []).filter(e => e.to && e.to !== '$return');
  if (edges.length === 0) return '';
  const paths = edges.map(e => {
    const from = positions[e.from];
    const to = positions[e.to];
    if (!from || !to) return '';
    const x1 = from.x, y1 = from.y;
    const x2 = to.x, y2 = to.y;
    const midX = (x1 + x2) / 2, midY = (y1 + y2) / 2;
    // Active = data flowing: from-node completed and to-node running
    const active = statusOf(rf.nodes?.find(n => n.nodeId === e.from)) === 'completed' &&
                   statusOf(rf.nodes?.find(n => n.nodeId === e.to)) === 'running';
    const cond = e.condition
      ? `<text class="solar-edge-label" x="${midX.toFixed(1)}" y="${(midY - 8).toFixed(1)}">${esc(e.condition)}</text>`
      : '';
    return `
      <path class="flow-edge${active ? ' active' : ''}" d="M ${x1.toFixed(1)} ${y1.toFixed(1)} C ${x1.toFixed(1)} ${midY.toFixed(1)} ${x2.toFixed(1)} ${midY.toFixed(1)} ${x2.toFixed(1)} ${y2.toFixed(1)}"/>
      <circle class="flow-edge-arrow" cx="${x2.toFixed(1)}" cy="${y2.toFixed(1)}" r="3"/>
      ${cond}`;
  }).join('');
  return `<svg class="solar-edges" width="${positions._w}" height="${positions._h}"><g transform="translate(${(positions._w / 2).toFixed(1)},${PAD})">${paths}</g></svg>`;
}

// ── Card ───────────────────────────────────────────────────

/** Render one flow instance as a solar-system card. */
export function dagCardHtml(rf, opts = {}) {
  const statusCls = opts.statusCls || rf.status || 'running';
  const statusText = opts.statusText || statusCls;
  const statusOf = opts.statusOf || (n => (n ? n.status || 'pending' : 'pending'));

  const { positions, width, height } = layoutDagNodes(rf);
  const nodes = rf.nodes || [];

  // Collect all node positions (skip $return pseudo-targets)
  const edgePositions = { ...positions, _w: width, _h: height };

  const nodesHtml = nodes.map(n => {
    const pos = positions[n.nodeId];
    if (!pos) return '';
    return solarNodeHtml(n, rf.flowName, pos, statusOf, width / 2);
  }).join('');

  const isActive = statusCls === 'running';
  // flow v4 (20260825_flow-redesign-research.md §4.10): flow-run is the sole flow
  // UI. When the run reaches a terminal state and a close affordance is wired
  // (opts.onClose supplied by the flow-run tab), show a terminal banner + a manual
  // Close button so the user can review the result and close at their own pace
  // (§4.10 "关闭或停留结束态"). Non-flow-run callers (flows panel, flow-def) pass no
  // onClose, so they render exactly as before.
  const terminal = opts.onClose && (statusCls === 'completed' || statusCls === 'failed');
  const footerBtn = terminal
    ? `<button class="dag-card-close" data-close-instance="${esc(rf.instanceId || '')}">Close</button>`
    : (isActive ? `<button class="dag-card-cancel" data-instance-id="${esc(rf.instanceId || '')}">Cancel Flow</button>` : '');
  const terminalBanner = terminal
    ? `<div class="solar-terminal-banner ${statusCls === 'failed' ? 'failed' : 'ok'}">
        <span class="solar-terminal-icon">${statusCls === 'failed' ? '\u2717' : '\u2713'}</span>
        <span class="solar-terminal-text">${statusCls === 'failed' ? 'Flow failed' : 'Flow completed'}</span>
      </div>`
    : '';

  return `
    <div class="solar-card ${statusCls}">
      <div class="solar-card-header">
        <div class="solar-card-title">${esc(rf.flowName)}</div>
        <div class="solar-card-status ${statusCls}"><span class="dot"></span>${statusText}</div>
      </div>
      ${rf.description ? `<div class="solar-card-desc">${esc(rf.description)}</div>` : ''}
      ${terminalBanner}
      <div class="solar-scroll">
        <div class="solar-canvas" style="width:${width}px;height:${height}px">
          ${solarEdgesSvg(rf, edgePositions, statusOf)}
          ${nodesHtml}
        </div>
      </div>
      <div class="solar-card-footer">${footerBtn}</div>
    </div>`;
}

// ── Panels ─────────────────────────────────────────────────

export function renderFlowsPanel(scroll, runningFlows) {
  if (runningFlows.length === 0) {
    scroll.innerHTML = `<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No running flows</div><div class="hint">Trigger a flow via Delegate to see it here</div></div>`;
    return;
  }
  scroll.innerHTML = runningFlows.map(rf => dagCardHtml(rf)).join('');
}

/** Render a single flow instance into an existing container (flow-run tab).
 *  @param {object} [opts] - passed to dagCardHtml (e.g. { onClose }) for the
 *    terminal close affordance. */
export function renderFlowRunInto(container, rf, opts) {
  // flowProgress / agentStart / agentDone debounce into renderOpenTabsNow, which
  // rebuilds this card via innerHTML on every update. That destroys .solar-scroll
  // and resets its scrollLeft/scrollTop to 0 - so during a running WIDE fan-out
  // (e.g. 16 parallel nodes -> ~2800px canvas) the horizontal scrollbar snaps back
  // on every progress tick and the off-screen nodes are effectively unreachable.
  // Capture the scroll position here and restore it after the rebuild; only a
  // brand-new render (no prior .solar-scroll) centers horizontally on the canvas
  // middle, where the entry / join nodes sit, instead of the leftmost leaf slice.
  const prev = container.querySelector('.solar-scroll');
  const prevLeft = prev ? prev.scrollLeft : null;
  const prevTop = prev ? prev.scrollTop : null;
  container.innerHTML = rf ? dagCardHtml(rf, opts) : `<div class="dag-empty"><div class="hint">Flow instance not found</div></div>`;
  const next = container.querySelector('.solar-scroll');
  if (next && next.clientWidth > 0) {
    if (prevLeft == null) {
      next.scrollLeft = Math.max(0, (next.scrollWidth - next.clientWidth) / 2);
    } else {
      next.scrollLeft = prevLeft;
      next.scrollTop = prevTop;
    }
  }
}

/** Render a static flow definition preview (all nodes pending) — P6. */
export function renderFlowDefStatic(container, flowDef) {
  if (!flowDef) {
    container.innerHTML = `<div class="dag-empty"><div class="hint">No flow data</div></div>`;
    return;
  }
  const rf = {
    instanceId: `def-${flowDef.name}`,
    flowName: flowDef.name,
    description: flowDef.description,
    entry: flowDef.entry,
    status: 'completed',
    nodes: (flowDef.nodes || []).map(n => ({ ...n, status: n.status || 'pending' })),
    edges: flowDef.edges || [],
  };
  container.innerHTML = dagCardHtml(rf, { statusCls: 'completed', statusText: 'definition' });
}

// ── Click bindings ─────────────────────────────────────────

export function bindDagNodeClicks() {
  document.querySelectorAll('.solar-node').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openFlowNodePopup(el.getAttribute('data-flow') || '', el.getAttribute('data-agent') || '', el.getAttribute('data-node') || '');
    });
  });
  // Legacy .dag-node binding (kept for compatibility)
  document.querySelectorAll('.dag-node').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openFlowNodePopup(el.getAttribute('data-flow') || '', el.getAttribute('data-agent') || '', el.getAttribute('data-node') || '');
    });
  });
  // Bind cancel buttons on active DAG flow cards
  document.querySelectorAll('.dag-card-cancel').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const instanceId = btn.getAttribute('data-instance-id') || '';
      if (!instanceId) return;
      btn.disabled = true;
      btn.textContent = '...';
      import('./ws.js').then(({ sendWs }) => {
        sendWs({ type: 'cancelFlow', instanceId });
      });
    });
  });
}


// ── Stellar system visualization (P5) ───────────────────────

function computeDepths(rf) {
  const depths = {};
  const edges = rf.edges || [];
  const childrenMap = new Map();
  edges.forEach(e => {
    if (!childrenMap.has(e.from)) childrenMap.set(e.from, []);
    childrenMap.get(e.from).push(e.to);
  });
  function visit(nodeId, depth) {
    if (depths[nodeId] !== undefined && depths[nodeId] >= depth) return;
    depths[nodeId] = depth;
    (childrenMap.get(nodeId) || []).forEach(child => {
      if (child === '$return') return; // pseudo-target, not a real node
      visit(child, depth + 1);
    });
  }
  if (rf.entry) visit(rf.entry, 0);
  return depths;
}

function layoutStellarNodes(rf) {
  const depths = computeDepths(rf);
  const nodesAtDepth = {};
  Object.entries(depths).forEach(([nodeId, depth]) => {
    if (!nodesAtDepth[depth]) nodesAtDepth[depth] = [];
    nodesAtDepth[depth].push(nodeId);
  });
  const V_SPACING = 160;
  const H_SPACING = 200; // horizontal spread for same-depth (parallel branch) nodes
  const positions = {};
  Object.entries(nodesAtDepth).forEach(([depth, nodeIds]) => {
    const y = Number(depth) * V_SPACING + 80;
    // Center each depth row around x=0 so parallel siblings don't stack.
    const total = (nodeIds.length - 1) * H_SPACING;
    nodeIds.forEach((nodeId, i) => {
      positions[nodeId] = { x: i * H_SPACING - total / 2, y };
    });
  });
  return positions;
}

function buildStellarEdges(rf, positions) {
  const svgPaths = [];
  (rf.edges || []).forEach(e => {
    const from = positions[e.from];
    const to = positions[e.to];
    if (!from || !to) return;
    const midX = (from.x + to.x) / 2;
    const midY = (from.y + to.y) / 2;
    const cond = e.condition ? `<text x="${midX}" y="${midY}" class="flow-edge-label" text-anchor="middle">${esc(e.condition)}</text>` : '';
    svgPaths.push(`<path d="M ${from.x} ${from.y + 55} C ${from.x} ${midY}, ${to.x} ${midY}, ${to.x} ${to.y - 55}" class="flow-edge" fill="none"/>${cond}`);
  });
  return svgPaths.join('');
}

function orbitNodeHtml(node, pos) {
  const st = node.status || 'pending';
  const agent = node.agent || '';
  const nodeId = node.nodeId || '';
  const icon = st === 'completed' ? '✓' : st === 'failed' ? '✗' : '';
  return `<div class="flow-orbit-node ${st}" style="left:calc(50% + ${pos.x}px); top:${pos.y}px;" data-agent="${esc(agent)}" data-node="${esc(nodeId)}">
    <div class="flow-ring outer"><div class="flow-dot"></div></div>
    <div class="flow-ring middle"><div class="flow-dot"></div></div>
    <div class="flow-ring inner"><div class="flow-dot"></div></div>
    <div class="flow-orbit-content">
      <div class="flow-orbit-nodeid" title="${esc(agent)}">${esc(nodeId)}</div>
      ${agent && agent !== nodeId ? `<div class="flow-orbit-agent">${esc(agent)}</div>` : ''}
      ${icon ? `<div class="flow-orbit-status">${icon}</div>` : ''}
    </div>
  </div>`;
}

export function renderStellarSystem(container, runningFlows) {
  if (!runningFlows || runningFlows.length === 0) {
    container.innerHTML = `<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No running flows</div></div>`;
    return;
  }
  container.innerHTML = runningFlows.map(rf => {
    const positions = layoutStellarNodes(rf);
    // rf.nodes is an array (WS/REST data contract) — key positions by nodeId.
    const nodes = (rf.nodes || []).map(n => orbitNodeHtml(n, positions[n.nodeId] || { x: 0, y: 0 }));
    const edgesSvg = buildStellarEdges(rf, positions);
    const maxDepth = Math.max(0, ...Object.values(computeDepths(rf)));
    const svgHeight = (maxDepth + 1) * 160 + 100;
    const isActive = rf.status === 'running';
    const cancelBtn = isActive ? `<button class="dag-card-cancel" data-instance-id="${esc(rf.instanceId || '')}">Cancel</button>` : '';
    return `<div class="stellar-card" data-instance="${esc(rf.instanceId)}">
      <div class="stellar-header">
        <div class="stellar-title">${esc(rf.flowName)}</div>
        <div class="stellar-status ${rf.status || 'running'}">${rf.status || 'running'}</div>
      </div>
      ${rf.description ? `<div class="stellar-desc">${esc(rf.description)}</div>` : ''}
      <div class="stellar-stage" style="min-height:${svgHeight}px;">
        <svg class="stellar-svg" style="position:absolute;top:0;left:50%;width:1px;height:${svgHeight}px;overflow:visible;pointer-events:none;">${edgesSvg}</svg>
        ${nodes.join('')}
      </div>
      ${cancelBtn}
    </div>`;
  }).join('');
}

export function bindStellarNodeClicks() {
  document.querySelectorAll('.flow-orbit-node').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      const card = el.closest('.stellar-card');
      const flowName = card?.querySelector('.stellar-title')?.textContent || '';
      // 372-1: previously passed the INSTANCE id as the node session id —
      // the popup then loaded no history. Resolve the real dag-* session.
      openFlowNodePopup(flowName, el.getAttribute('data-agent') || '', el.getAttribute('data-node') || '');
    });
  });
  document.querySelectorAll('.stellar-card .dag-card-cancel').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const instanceId = btn.getAttribute('data-instance-id') || '';
      if (!instanceId) return;
      btn.disabled = true;
      btn.textContent = '...';
      import('./ws.js').then(({ sendWs }) => sendWs({ type: 'cancelFlow', instanceId }));
    });
  });
}
