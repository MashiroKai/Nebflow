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

import { openStepPopup } from './flowAgentPopup.js';
import { esc } from './flowHelpers.js';

const NODE_W = 130;   // orbit square width
const NODE_H = 150;   // node card height (orbit + label)
const V_SPACING = 175;
const H_SPACING = 195;
const PAD = 80;       // canvas padding around nodes

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
        <div class="dag-inline-agent">${esc(agent)}</div>
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
  const height = maxDepth * V_SPACING + NODE_H + PAD * 2;

  return { positions, width, height };
}

// ── Solar-system node ──────────────────────────────────────

/** Scatter angles for orbit dots (per ring index) so pending dots don't stack. */
const DOT_ANGLES = {
  outer: [0, 120, 240],
  middle: [45, 165, 285],
  inner: [90, 210, 330],
};
const RING_RADII = { outer: 48, middle: 35, inner: 23 };

function ringHtml(ringName, status) {
  const radius = RING_RADII[ringName];
  const angles = DOT_ANGLES[ringName];
  // Pending/completed: dots stay static at scattered angles (no spin).
  const spinClass = status === 'running' ? ` spin-${ringName}` : '';
  const dots = angles.map((deg, i) =>
    `<span class="flow-dot" style="transform: rotate(${deg + i * 7}deg) translateY(-${radius}px)"></span>`
  ).join('');
  return `<div class="flow-ring ${ringName}${spinClass}">${dots}</div>`;
}

function solarNodeHtml(n, flowName, pos, statusOf) {
  const nodeId = n.nodeId || '';
  const agent = n.agent || '';
  const st = statusOf(n) || 'pending';
  const left = pos.x - NODE_W / 2 + PAD;
  const top = pos.y - NODE_H / 2 + PAD;
  const statusIcon = st === 'completed' ? '<span class="solar-node-status ok">✓</span>'
    : st === 'failed' ? '<span class="solar-node-status err">✗</span>' : '';
  return `
    <div class="solar-node ${st}" data-flow="${esc(flowName)}" data-agent="${esc(agent)}" data-node="${esc(nodeId)}"
         style="left:${left.toFixed(1)}px;top:${top.toFixed(1)}px">
      <div class="solar-rings">
        ${ringHtml('outer', st)}
        ${ringHtml('middle', st)}
        ${ringHtml('inner', st)}
        ${statusIcon}
      </div>
      <div class="solar-node-label" title="${esc(agent)}">${esc(agent)}</div>
      <div class="solar-node-sub">${esc(nodeId)}</div>
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
      <path class="flow-edge${active ? ' active' : ''}" d="M ${x1.toFixed(1)} ${y1.toFixed(1)} L ${x2.toFixed(1)} ${y2.toFixed(1)}"/>
      <circle class="flow-edge-arrow" cx="${x2.toFixed(1)}" cy="${y2.toFixed(1)}" r="3"/>
      ${cond}`;
  }).join('');
  return `<svg class="solar-edges" width="${positions._w}" height="${positions._h}"><g transform="translate(${PAD},${PAD})">${paths}</g></svg>`;
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
    return solarNodeHtml(n, rf.flowName, pos, statusOf);
  }).join('');

  const isActive = statusCls === 'running';
  const cancelBtn = isActive ? `<button class="dag-card-cancel" data-instance-id="${esc(rf.instanceId || '')}">Cancel Flow</button>` : '';

  return `
    <div class="solar-card ${statusCls}">
      <div class="solar-card-header">
        <div class="solar-card-title">${esc(rf.flowName)}</div>
        <div class="solar-card-status ${statusCls}"><span class="dot"></span>${statusText}</div>
      </div>
      ${rf.description ? `<div class="solar-card-desc">${esc(rf.description)}</div>` : ''}
      <div class="solar-scroll">
        <div class="solar-canvas" style="width:${width}px;height:${height}px">
          ${solarEdgesSvg(rf, edgePositions, statusOf)}
          ${nodesHtml}
        </div>
      </div>
      <div class="solar-card-footer">${cancelBtn}</div>
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

/** Render a single flow instance into an existing container (flow-run tab). */
export function renderFlowRunInto(container, rf) {
  container.innerHTML = rf ? dagCardHtml(rf) : `<div class="dag-empty"><div class="hint">Flow instance not found</div></div>`;
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
      const flowName = el.getAttribute('data-flow') || '';
      const agentName = el.getAttribute('data-agent') || '';
      const nodeId = el.getAttribute('data-node') || '';
      openStepPopup(`${flowName}/${nodeId}`, nodeId, agentName, flowName, null);
    });
  });
  // Legacy .dag-node binding (kept for compatibility)
  document.querySelectorAll('.dag-node').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      const flowName = el.getAttribute('data-flow') || '';
      const agentName = el.getAttribute('data-agent') || '';
      const nodeId = el.getAttribute('data-node') || '';
      openStepPopup(`${flowName}/${nodeId}`, nodeId, agentName, flowName, null);
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
    (childrenMap.get(nodeId) || []).forEach(child => visit(child, depth + 1));
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
  const positions = {};
  Object.entries(nodesAtDepth).forEach(([depth, nodeIds]) => {
    const y = Number(depth) * V_SPACING + 80;
    nodeIds.forEach((nodeId) => {
      positions[nodeId] = { x: 0, y };
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
    const cond = e.condition ? `<text x="60" y="${(from.y + to.y) / 2}" class="flow-edge-label" text-anchor="middle">${esc(e.condition)}</text>` : '';
    const midY = (from.y + to.y) / 2;
    svgPaths.push(`<path d="M 0 ${from.y + 55} C 0 ${midY}, 0 ${midY}, 0 ${to.y - 55}" class="flow-edge" fill="none"/>${cond}`);
  });
  return svgPaths.join('');
}

function orbitNodeHtml(node, pos) {
  const st = node.status || 'pending';
  const agent = node.agent || '';
  const nodeId = node.nodeId || '';
  const icon = st === 'completed' ? '✓' : st === 'failed' ? '✗' : '';
  return `<div class="flow-orbit-node ${st}" style="left:50%; top:${pos.y}px;" data-agent="${esc(agent)}" data-node="${esc(nodeId)}">
    <div class="flow-ring outer"><div class="flow-dot"></div></div>
    <div class="flow-ring middle"><div class="flow-dot"></div></div>
    <div class="flow-ring inner"><div class="flow-dot"></div></div>
    <div class="flow-orbit-content">
      <div class="flow-orbit-agent">${esc(agent)}</div>
      <div class="flow-orbit-nodeid">${esc(nodeId)}</div>
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
    const nodes = Object.entries(rf.nodes || {}).map(([id, n]) => orbitNodeHtml(n, positions[id] || { x: 0, y: 0 }));
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
      const agentName = el.getAttribute('data-agent') || '';
      const nodeId = el.getAttribute('data-node') || '';
      const instanceId = card?.dataset.instance || '';
      openStepPopup(`${flowName}/${nodeId}`, nodeId, agentName, flowName, instanceId);
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
