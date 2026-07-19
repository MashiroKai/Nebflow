// flowCanvas.js — Flow architecture visualization on the canvas panel.
//
// Shows ALL mounted pipelines simultaneously as a connected architecture.
// Each pipeline is a vertical "tree" with its steps, branching from a
// shared Main Agent node at the top.
// Lines show message flow direction with arrowheads.
// Active lines have flowing dash animation to show messages in transit.
// Theme-aware via CSS variables. Session-bound.

import { openCanvas, closeCanvas, setCanvasContent, showCanvasHeader } from './canvas.js';

// ── State ──────────────────────────────────────────────────
let pipelines = new Map();
let resizeObs = null;

// ── CSS ────────────────────────────────────────────────────
const FLOW_CSS = `
<style>
.flow-root {
  width: 100%; height: 100%; position: relative; overflow: auto;
}
.flow-inner {
  position: relative; min-width: 100%; min-height: 100%;
}
.flow-svg {
  position: absolute; top: 0; left: 0; width: 100%; height: 100%;
  pointer-events: none; z-index: 1;
}
.flow-svg path { fill: none; stroke: var(--color-border); stroke-width: 1.5; }
.flow-svg path.active {
  stroke: var(--color-primary, #6366f1);
  stroke-width: 2;
  stroke-dasharray: 6 3;
  animation: flow-dash 1s linear infinite;
}
.flow-svg path.return {
  stroke: var(--color-success, #30a46c);
  stroke-width: 1.5;
  stroke-dasharray: 4 4;
  opacity: 0.6;
}
@keyframes flow-dash { to { stroke-dashoffset: -9; } }

/* Node card */
.flow-node {
  position: absolute; transform: translate(-50%, -50%);
  z-index: 2;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  padding: 12px 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06), 0 4px 16px rgba(0,0,0,0.04);
  text-align: center;
  min-width: 80px;
  transition: opacity 0.4s ease;
}
.flow-node.root {
  min-width: 110px;
  border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08), 0 6px 20px rgba(0,0,0,0.06);
}

/* Solar system orbit */
.flow-orbit {
  position: relative; width: 36px; height: 36px; margin: 0 auto 6px;
}
.flow-ring {
  position: absolute; top: 50%; left: 50%;
  border: 1px solid var(--color-border);
  border-radius: 50%; transform: translate(-50%, -50%);
}
.flow-ring-1 { width: 8px; height: 8px; }
.flow-ring-2 { width: 18px; height: 18px; }
.flow-ring-3 { width: 28px; height: 28px; }
.flow-dot-wrap { position: absolute; top: 50%; left: 50%; width: 0; height: 0; }
.flow-dot {
  position: absolute; width: 3px; height: 3px;
  background: var(--color-text); border-radius: 50%; top: -1.5px;
}
.flow-ring-1 .flow-dot { left: 3px; }
.flow-ring-2 .flow-dot { left: 8px; }
.flow-ring-3 .flow-dot { left: 13px; }
.flow-ring-1 .flow-dot-wrap { transform: rotate(0deg); }
.flow-ring-2 .flow-dot-wrap { transform: rotate(120deg); }
.flow-ring-3 .flow-dot-wrap { transform: rotate(240deg); }
.flow-node.running .flow-ring-1 .flow-dot-wrap { animation: flow-spin 3s linear infinite; }
.flow-node.running .flow-ring-2 .flow-dot-wrap { animation: flow-spin 4.5s linear infinite reverse; animation-delay: -3s; }
.flow-node.running .flow-ring-3 .flow-dot-wrap { animation: flow-spin 6s linear infinite; animation-delay: -4s; }
@keyframes flow-spin { to { transform: rotate(360deg); } }
.flow-node.pending .flow-ring { border-style: dashed; opacity: 0.4; }
.flow-node.pending .flow-dot { opacity: 0.3; }
.flow-node.pending .flow-label { opacity: 0.4; }
.flow-node.done .flow-dot-wrap { animation: none; }
.flow-node.done .flow-label { opacity: 0.6; }
.flow-node.failed .flow-ring { border-color: var(--color-error, #e5484d); opacity: 0.5; }

/* Pipeline header */
.flow-node.pipeline-header {
  min-width: 90px;
  border-radius: 12px;
  border: 1px solid var(--color-primary, #6366f1);
}
.flow-node.pipeline-header.idle { opacity: 0.5; }
.flow-node.pipeline-header.running {
  border-color: var(--color-primary, #6366f1);
  box-shadow: 0 0 12px rgba(99, 102, 241, 0.15);
}

/* Labels */
.flow-label {
  font: 500 10px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text); white-space: nowrap;
}
.flow-agent {
  font: 500 8px -apple-system, sans-serif;
  color: var(--color-primary, #6366f1);
  margin-top: 1px; white-space: nowrap;
}
.flow-status {
  font: 400 7px -apple-system, sans-serif;
  color: var(--color-text-muted);
  text-transform: uppercase; letter-spacing: 0.5px; margin-top: 2px;
}

/* Info bar */
.flow-info {
  position: sticky; top: 0; left: 0; z-index: 3;
  font: 600 12px -apple-system, sans-serif;
  color: var(--color-text-muted);
  padding: 10px 16px;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur));
  backdrop-filter: blur(var(--glass-blur));
  border-bottom: 1px solid var(--glass-border);
}
.flow-empty {
  display: flex; align-items: center; justify-content: center;
  height: 100%; flex-direction: column; gap: 8px;
}
.flow-empty .hint {
  font: 400 11px -apple-system, sans-serif;
  color: var(--color-text-muted); opacity: 0.5;
}
</style>
`;

// ── Layout ─────────────────────────────────────────────────

function computePipelineLayout(steps, colStart, colWidth) {
  const depthMap = {};
  function getDepth(id) {
    if (id in depthMap) return depthMap[id];
    const step = steps.find(s => s.id === id);
    if (!step || !step.dependsOn || step.dependsOn.length === 0) { depthMap[id] = 0; return 0; }
    const d = Math.max(...step.dependsOn.map(getDepth)) + 1;
    depthMap[id] = d;
    return d;
  }
  steps.forEach(s => getDepth(s.id));
  const maxDepth = Math.max(0, ...Object.values(depthMap));
  const levels = {};
  steps.forEach(s => {
    const d = depthMap[s.id];
    if (!levels[d]) levels[d] = [];
    levels[d].push(s);
  });

  const colCenter = colStart + colWidth / 2;
  const positions = {};

  // Pipeline header
  positions['__header__'] = { x: colCenter, y: 0.12 };

  // Work steps — spread within the column
  const totalRows = maxDepth + 2;
  for (let lv = 0; lv <= maxDepth; lv++) {
    const group = levels[lv] || [];
    const y = 0.22 + (lv / (totalRows + 1)) * 0.6;
    group.forEach((step, i) => {
      const x = group.length === 1 ? colCenter
        : colStart + colWidth * 0.15 + colWidth * 0.7 * (i / (group.length - 1));
      positions[step.id] = { x, y };
    });
  }
  // Verify at bottom
  positions['__verify__'] = { x: colCenter, y: 0.88 };

  return { positions, maxDepth };
}

function computeGlobalLayout() {
  const names = [...pipelines.keys()];
  const n = names.length;
  if (n === 0) return null;

  // Give each pipeline more room — use min width and allow horizontal scroll
  const minColWidth = 0.35; // minimum 35% of container per pipeline
  const colWidth = Math.max(1.0 / n, minColWidth);
  const totalWidth = colWidth * n;

  const rootPos = { x: 0.5, y: 0.05 };
  const allPaths = [];
  const allNodes = [
    { id: '__root__', label: 'Main Agent', status: 'done', isRoot: true,
      position: rootPos, pipeline: null }
  ];

  names.forEach((name, idx) => {
    const p = pipelines.get(name);
    const colStart = idx * colWidth;
    const { positions } = computePipelineLayout(p.steps, colStart, colWidth);
    p.positions = positions;

    // Header node
    allNodes.push({
      id: `pipe-${name}`, label: name, status: p.phase.toLowerCase(),
      isPipelineHeader: true, agent: '', pipeline: name,
      position: positions['__header__'],
    });

    // Step nodes
    p.steps.forEach(s => {
      const pos = positions[s.id];
      if (pos) allNodes.push({
        id: `${name}/${s.id}`, label: s.id, status: (s.status || 'pending').toLowerCase(),
        agent: s.agent || '', pipeline: name, stepId: s.id, position: pos,
      });
    });

    // Verify node
    allNodes.push({
      id: `${name}/__verify__`, label: 'verify', status: p.verifyResult ? 'done' : 'pending',
      agent: p.verifyAgent || 'Explorer', pipeline: name, isVerify: true,
      position: positions['__verify__'],
    });

    // ── Paths with direction (message flow) ──

    // 1. Main Agent → Pipeline header (trigger)
    allPaths.push({
      from: rootPos, to: positions['__header__'],
      active: p.phase === 'Running',
      direction: 'down',
    });

    // 2. Header → first-level steps (spawn)
    p.steps.filter(s => !s.dependsOn || s.dependsOn.length === 0).forEach(s => {
      allPaths.push({
        from: positions['__header__'], to: positions[s.id],
        active: s.status === 'Done' || s.status === 'Running',
        direction: 'down',
      });
    });

    // 3. Step → dependent step (data passing: ${stepId} template substitution)
    p.steps.forEach(s => {
      (s.dependsOn || []).forEach(d => {
        const from = positions[d], to = positions[s.id];
        if (from && to) allPaths.push({
          from, to,
          active: s.status === 'Done' || s.status === 'Running',
          direction: 'down',
        });
      });
    });

    // 4. Last steps → verify (all done → trigger verify)
    const lastSteps = p.steps.filter(s => {
      const hasDependers = p.steps.some(o => (o.dependsOn || []).includes(s.id));
      return !hasDependers;
    });
    // If only one step, connect it to verify
    if (lastSteps.length === 0 && p.steps.length > 0) lastSteps.push(p.steps[0]);
    lastSteps.forEach(s => {
      const from = positions[s.id], to = positions['__verify__'];
      if (from && to) allPaths.push({
        from, to,
        active: p.verifyResult !== null,
        direction: 'down',
      });
    });

    // 5. Verify → Main Agent (return result — curved回流 line)
    allPaths.push({
      from: positions['__verify__'], to: rootPos,
      active: p.phase === 'Completed' || p.phase === 'Failed',
      direction: 'return',
    });
  });

  return { allNodes, allPaths, count: n, totalWidth };
}

// ── Rendering ──────────────────────────────────────────────

function renderAll() {
  const layout = computeGlobalLayout();
  if (!layout) {
    setCanvasContent(`${FLOW_CSS}
      <div class="flow-root">
        <div class="flow-empty">
          <div style="font: 600 14px -apple-system; color: var(--color-text-muted)">No active flows</div>
          <div class="hint">Mount a flow to see the architecture</div>
        </div>
      </div>`);
    showCanvasHeader(false);
    openCanvas('');
    return;
  }

  const { allNodes, allPaths, count, totalWidth } = layout;
  const runningCount = allNodes.filter(n => n.status === 'running').length;
  const doneCount = allNodes.filter(n => n.status === 'done' || n.status === 'idle').length;

  const nodesHtml = allNodes.map(n => {
    if (n.isRoot) return nodeHtml(n.id, 'Main Agent', 'done', true);
    if (n.isPipelineHeader) return pipelineHeaderHtml(n.id, n.label, n.status);
    return nodeHtml(n.id, n.label, n.status, false, n.agent || '');
  }).join('');

  const infoHtml = `
    <div class="flow-info">
      ${count} pipeline${count > 1 ? 's' : ''} · ${runningCount} running · ${doneCount} done
    </div>`;

  setCanvasContent(`${FLOW_CSS}
    <div class="flow-root">
      ${infoHtml}
      <div class="flow-inner" style="width:${Math.max(100, totalWidth * 100)}%">
        <svg class="flow-svg" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <marker id="arrow" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
              <path d="M0,0 L6,3 L0,6 Z" fill="var(--color-text-muted)" />
            </marker>
            <marker id="arrow-active" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
              <path d="M0,0 L6,3 L0,6 Z" fill="var(--color-primary, #6366f1)" />
            </marker>
            <marker id="arrow-return" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
              <path d="M0,0 L6,3 L0,6 Z" fill="var(--color-success, #30a46c)" />
            </marker>
          </defs>
        </svg>
        ${nodesHtml}
      </div>
    </div>`);
  showCanvasHeader(false);
  openCanvas('');
  document.getElementById('flow-toggle-btn')?.classList.add('active');

  // Position nodes
  const container = document.querySelector('.flow-inner');
  if (container) {
    const w = container.clientWidth;
    const h = Math.max(container.clientHeight, 500);

    allNodes.forEach(n => {
      const el = container.querySelector(`[data-step-id="${n.id}"]`);
      if (el && n.position) {
        el.style.left = (n.position.x * w) + 'px';
        el.style.top = (n.position.y * h) + 'px';
      }
    });

    // Draw SVG paths
    const svg = container.querySelector('.flow-svg');
    if (svg) {
      svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
      svg.style.width = w + 'px';
      svg.style.height = h + 'px';

      const pathsHtml = allPaths.map(p => {
        const x1 = p.from.x * w, y1 = p.from.y * h;
        const x2 = p.to.x * w, y2 = p.to.y * h;
        const dy = y2 - y1;

        let cls = '';
        let marker = 'arrow';

        if (p.direction === 'return') {
          // Return line: curve outward (to the side) and back up
          const midX = (x1 + x2) / 2 + (x1 > x2 ? 60 : -60);
          const cp1x = x1 + (midX - x1) * 0.5;
          const cp2x = x2 + (midX - x2) * 0.5;
          cls = p.active ? 'return' : '';
          marker = 'arrow-return';
          return `<path d="M ${x1},${y1} Q ${midX},${(y1+y2)/2} ${x2},${y2}" class="${cls}" marker-end="url(#${marker})" />`;
        }

        // Normal downward line with cubic bezier
        if (p.active) { cls = 'active'; marker = 'arrow-active'; }
        const cp1y = y1 + dy * 0.5;
        const cp2y = y2 - dy * 0.5;
        return `<path d="M ${x1},${y1} C ${x1},${cp1y} ${x2},${cp2y} ${x2},${y2}" class="${cls}" marker-end="url(#${marker})" />`;
      }).join('');

      // Keep defs, replace paths
      const defs = svg.querySelector('defs');
      svg.innerHTML = '';
      if (defs) svg.appendChild(defs);
      svg.insertAdjacentHTML('beforeend', pathsHtml);
    }
  }

  // Resize observer
  if (container && !resizeObs) {
    resizeObs = new ResizeObserver(() => renderAll());
    resizeObs.observe(container);
  }
}

function nodeHtml(id, label, status, isRoot = false, agent = '') {
  const cls = `flow-node ${status}${isRoot ? ' root' : ''}`;
  const displayLabel = isRoot ? 'Main Agent' : label;
  const statusText = isRoot ? 'orchestrator' : status;
  const agentHtml = (!isRoot && agent) ? `<div class="flow-agent">${agent}</div>` : '';
  return `
    <div class="${cls}" data-step-id="${id}">
      <div class="flow-orbit">
        <div class="flow-ring flow-ring-1"><div class="flow-dot-wrap"><div class="flow-dot"></div></div></div>
        <div class="flow-ring flow-ring-2"><div class="flow-dot-wrap"><div class="flow-dot"></div></div></div>
        <div class="flow-ring flow-ring-3"><div class="flow-dot-wrap"><div class="flow-dot"></div></div></div>
      </div>
      <div class="flow-label">${displayLabel}</div>
      ${agentHtml}
      <div class="flow-status">${statusText}</div>
    </div>`;
}

function pipelineHeaderHtml(id, name, status) {
  return `
    <div class="flow-node pipeline-header ${status}" data-step-id="${id}">
      <div class="flow-label">${name}</div>
      <div class="flow-status">${status}</div>
    </div>`;
}

// ── Event handlers ─────────────────────────────────────────

export function startFlow(msg) {
  const name = msg.flowName || msg.branchName || msg.name || 'unknown';
  console.log('[flowCanvas] flowStarted:', name, 'steps:', msg.steps?.length);

  const steps = (msg.steps || []).map(s => ({
    id: s.id || s.stepId,
    agent: s.agent || s.agentName || '',
    dependsOn: s.dependsOn || [],
    status: 'Pending',
  }));

  pipelines.set(name, {
    name,
    flowName: msg.flowName || name,
    phase: 'Running',
    steps,
    iteration: 0,
    maxIterations: msg.maxIterations || 3,
    verifyResult: null,
    verifyAgent: msg.verifyAgent || 'Explorer',
    positions: {},
  });

  renderAll();
}

export function updateStep(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;

  const step = p.steps.find(s => s.id === msg.stepId);
  if (!step) return;

  step.status = msg.status === 'running' ? 'Running'
    : msg.status === 'done' ? 'Done'
    : msg.status === 'failed' ? 'Failed'
    : step.status;

  renderAll();
}

export function updateVerify(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;
  p.verifyResult = msg.pass ? 'passed' : 'failed';
  renderAll();
}

export function updateLoop(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;
  p.iteration = msg.iteration || p.iteration + 1;
  p.verifyResult = null;
  renderAll();
}

export function completeFlow(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;
  p.phase = msg.pass ? 'Completed' : 'Failed';
  if (p.verifyResult === null) p.verifyResult = msg.pass ? 'passed' : 'failed';
  renderAll();
}

// ── Canvas control ─────────────────────────────────────────

export function closeFlow() {
  if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
  closeCanvas();
  document.getElementById('flow-toggle-btn')?.classList.remove('active');
}

export async function toggleCanvas() {
  const isOpen = document.body.classList.contains('canvas-open');
  const btn = document.getElementById('flow-toggle-btn');
  if (isOpen) { closeCanvas(); btn?.classList.remove('active'); return; }
  renderAll();
}

export function onSessionChange(activeSessionId) {
  // Reload pipelines for the new session
  autoRestore(activeSessionId);
}

// ── Backend restore ────────────────────────────────────────

export async function autoRestore(sessionIdArg) {
  try {
    const sessionId = sessionIdArg || window.Nebflow?.activeSessionId || '';
    if (!sessionId) return;
    const resp = await fetch(`/api/flow/status/${sessionId}`);
    if (!resp.ok) return;
    const data = await resp.json();
    const pipeStates = data.pipelines || [];

    pipelines.clear();
    if (pipeStates.length === 0) {
      renderAll(); // show empty state
      return;
    }

    for (const p of pipeStates) {
      pipelines.set(p.name, {
        name: p.name,
        flowName: p.flowName,
        phase: p.phase,
        steps: (p.steps || []).map(s => ({
          id: s.id,
          agent: s.agent || '',
          dependsOn: s.dependsOn || [],
          status: s.status || 'Pending',
        })),
        iteration: p.iteration || 0,
        maxIterations: 3,
        verifyResult: p.verifyResult || null,
        verifyAgent: 'Explorer',
        positions: {},
      });
    }
    console.log('[flowCanvas] Restored', pipelines.size, 'pipeline(s) for session', sessionId);
    renderAll();
  } catch (e) {
    console.warn('[flowCanvas] Restore failed:', e);
  }
}
