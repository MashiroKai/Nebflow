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

  return `
    <div class="dag-card">
      <div class="dag-card-header">
        <div class="dag-card-title">${esc(rf.flowName)}</div>
        <div class="dag-card-status ${statusCls}"><span class="dot"></span>${statusText}</div>
      </div>
      ${rf.description ? `<div class="dag-card-desc">${esc(rf.description)}</div>` : ''}
      <div class="dag-nodes">${nodesHtml}</div>
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
}
