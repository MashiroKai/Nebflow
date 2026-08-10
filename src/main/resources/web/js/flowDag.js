// flowDag.js — DAG flow panel: vertical node graph with real-time progress.
//
// Each running flow instance is a card with nodes laid out top-to-bottom
// following the DAG edges. Node color reflects status (pending/running/
// completed/failed). Clicking a node opens the agent chat popup.

import { openStepPopup } from './flowAgentPopup.js';
import { esc } from './flowHelpers.js';

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

export function dagCardHtml(rf) {
  const statusCls = rf.status || 'running';
  const statusText = statusCls;
  const ordered = orderDagNodes(rf);
  const isActive = statusCls === 'running';

  let nodesHtml = '';
  ordered.forEach((item, i) => {
    if (i > 0) {
      const label = item.edgeLabel ? `<span class="dag-edge-label">${esc(item.edgeLabel)}</span>` : '';
      nodesHtml += `<div class="dag-edge">${label}</div>`;
    }
    const n = item.node;
    if (!n) return;
    const st = n.status || 'pending';
    const outputPreview = n.output ? `<div class="dag-node-output">${esc(n.output)}</div>` : '';
    const errPreview = n.error ? `<div class="dag-node-output" style="color:#f44336">${esc(n.error)}</div>` : '';
    nodesHtml += `
      <div class="dag-node ${st}" data-flow="${esc(rf.flowName)}" data-agent="${esc(n.agent)}" data-node="${esc(n.nodeId)}">
        <div class="dag-node-dot"></div>
        <div class="dag-node-info">
          <div class="dag-node-id">${esc(n.nodeId)}</div>
          <div class="dag-node-agent">${esc(n.agent)}</div>
          ${outputPreview}${errPreview}
        </div>
      </div>`;
  });

  const cancelBtn = isActive ? `<button class="dag-card-cancel" data-instance-id="${esc(rf.instanceId || '')}">Cancel</button>` : '';

  return `
    <div class="dag-card">
      <div class="dag-card-header">
        <div class="dag-card-title">${esc(rf.flowName)}</div>
        <div class="dag-card-status ${statusCls}"><span class="dot"></span>${statusText}</div>
      </div>
      ${rf.description ? `<div class="dag-card-desc">${esc(rf.description)}</div>` : ''}
      <div class="dag-nodes">${nodesHtml}</div>
      ${cancelBtn}
    </div>`;
}

export function renderFlowsPanel(scroll, runningFlows) {
  if (runningFlows.length === 0) {
    scroll.innerHTML = `<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No running flows</div><div class="hint">Trigger a flow via Mail to see it here</div></div>`;
    return;
  }
  scroll.innerHTML = runningFlows.map(rf => dagCardHtml(rf)).join('');
}

export function bindDagNodeClicks() {
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
