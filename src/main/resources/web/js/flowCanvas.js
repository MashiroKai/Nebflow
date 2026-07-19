// flowCanvas.js — Flow architecture visualization on the canvas panel.
//
// Shows ALL mounted pipelines simultaneously as a connected architecture.
// Each pipeline is a vertical "tree" with its steps, branching from a
// shared Main Agent node at the top.
// Theme-aware via CSS variables. Session-bound.

import { openCanvas, closeCanvas, setCanvasContent, showCanvasHeader } from './canvas.js';

// ── State ──────────────────────────────────────────────────
// Map<pipelineName, { name, flowName, phase, steps, positions, nodes, iteration, ... }>
let pipelines = new Map();
let resizeObs = null;
let canvasOpen = false;

// ── CSS (injected once into canvas-content) ────────────────
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
.flow-svg path { fill: none; stroke: var(--color-border); stroke-width: 1; }
.flow-svg path.active { stroke: var(--color-text-muted); }

/* Node card — glassmorphism */
.flow-node {
  position: absolute; transform: translate(-50%, -50%);
  z-index: 2;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  padding: 10px 14px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06), 0 4px 16px rgba(0,0,0,0.04);
  text-align: center;
  min-width: 70px;
  transition: opacity 0.4s ease;
}
.flow-node.root {
  min-width: 100px;
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

.flow-dot-wrap {
  position: absolute; top: 50%; left: 50%; width: 0; height: 0;
}
.flow-dot {
  position: absolute; width: 3px; height: 3px;
  background: var(--color-text); border-radius: 50%;
  top: -1.5px;
}
.flow-ring-1 .flow-dot { left: 3px; }
.flow-ring-2 .flow-dot { left: 8px; }
.flow-ring-3 .flow-dot { left: 13px; }

.flow-ring-1 .flow-dot-wrap { transform: rotate(0deg); }
.flow-ring-2 .flow-dot-wrap { transform: rotate(120deg); }
.flow-ring-3 .flow-dot-wrap { transform: rotate(240deg); }

.flow-node.running .flow-ring-1 .flow-dot-wrap {
  animation: flow-spin 3s linear infinite;
}
.flow-node.running .flow-ring-2 .flow-dot-wrap {
  animation: flow-spin 4.5s linear infinite reverse;
  animation-delay: -3s;
}
.flow-node.running .flow-ring-3 .flow-dot-wrap {
  animation: flow-spin 6s linear infinite;
  animation-delay: -4s;
}
@keyframes flow-spin { to { transform: rotate(360deg); } }

.flow-node.pending .flow-ring { border-style: dashed; opacity: 0.4; }
.flow-node.pending .flow-dot { opacity: 0.3; }
.flow-node.pending .flow-label { opacity: 0.4; }

.flow-node.done .flow-dot-wrap { animation: none; }
.flow-node.done .flow-label { opacity: 0.6; }

.flow-node.failed .flow-ring { border-color: var(--color-error, #e5484d); opacity: 0.5; }

/* Pipeline header node — shows pipeline name */
.flow-node.pipeline-header {
  min-width: 80px;
  border-radius: 12px;
  border: 1px solid var(--color-primary, #6366f1);
  opacity: 0.5;
}
.flow-node.pipeline-header.running {
  opacity: 1;
  border-color: var(--color-primary, #6366f1);
  box-shadow: 0 0 12px rgba(99, 102, 241, 0.15);
}

/* Labels */
.flow-label {
  font: 500 10px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text);
  white-space: nowrap;
}
.flow-agent {
  font: 500 8px -apple-system, sans-serif;
  color: var(--color-primary, #6366f1);
  margin-top: 1px;
  white-space: nowrap;
}
.flow-status {
  font: 400 7px -apple-system, sans-serif;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-top: 2px;
}

/* Header */
.flow-info {
  position: sticky; top: 0; left: 0; z-index: 3;
  font: 600 12px -apple-system, sans-serif;
  color: var(--color-text-muted);
  padding: 12px 16px;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur));
  backdrop-filter: blur(var(--glass-blur));
  border-bottom: 1px solid var(--glass-border);
}
.flow-info .sub {
  font: 400 9px -apple-system, sans-serif;
  color: var(--color-text-muted); opacity: 0.6;
  margin-top: 3px;
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
  // Compute dependency depth for each step
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

  // Positions within this pipeline's column (x is absolute within full canvas)
  const colCenter = colStart + colWidth / 2;
  const positions = {};

  // Pipeline header node (between root and first step)
  positions['__header__'] = { x: colCenter, y: 0.15 };

  // Work steps
  const totalRows = maxDepth + 2; // +1 header, +1 verify
  for (let lv = 0; lv <= maxDepth; lv++) {
    const group = levels[lv] || [];
    const y = (lv + 2) / (totalRows + 2.5);
    group.forEach((step, i) => {
      const x = group.length === 1 ? colCenter : colStart + colWidth * ((i + 1) / (group.length + 1));
      positions[step.id] = { x, y };
    });
  }
  // Verify at bottom
  positions['__verify__'] = { x: colCenter, y: (maxDepth + 2.5) / (totalRows + 2.5) };

  return { positions, maxDepth };
}

function computeGlobalLayout() {
  const names = [...pipelines.keys()];
  const n = names.length;
  if (n === 0) return null;

  const rootPos = { x: 0.5, y: 0.05 };
  const allPaths = [];
  const allNodes = [
    { id: '__root__', label: 'Main Agent', status: 'done', isRoot: true,
      position: rootPos, pipeline: null }
  ];

  names.forEach((name, idx) => {
    const p = pipelines.get(name);
    const colWidth = 1.0 / n;
    const colStart = idx * colWidth;

    const { positions } = computePipelineLayout(p.steps, colStart, colWidth);

    // Store positions back into pipeline
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

    // Paths: root → header
    allPaths.push({ from: rootPos, to: positions['__header__'], active: true });
    // Paths: header → first-level steps
    p.steps.filter(s => !s.dependsOn || s.dependsOn.length === 0).forEach(s => {
      allPaths.push({ from: positions['__header__'], to: positions[s.id], active: s.status === 'Done' || s.status === 'Running' });
    });
    // Paths: step → dependent step
    p.steps.forEach(s => {
      (s.dependsOn || []).forEach(d => {
        const from = positions[d], to = positions[s.id];
        if (from && to) allPaths.push({ from, to, active: s.status === 'Done' });
      });
    });
  });

  return { allNodes, allPaths, count: n };
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

  const { allNodes, allPaths, count } = layout;
  const runningCount = allNodes.filter(n => n.status === 'running').length;
  const doneCount = allNodes.filter(n => n.status === 'done' || n.status === 'idle').length;

  // Build node HTML
  const nodesHtml = allNodes.map(n => {
    if (n.isRoot) return nodeHtml(n.id, 'Main Agent', 'done', true);
    if (n.isPipelineHeader) return pipelineHeaderHtml(n.id, n.label, n.status);
    return nodeHtml(n.id, n.label, n.status, false, n.agent || '');
  }).join('');

  const infoHtml = `
    <div class="flow-info">
      <div>${count} pipeline${count > 1 ? 's' : ''} · ${runningCount} running · ${doneCount} done</div>
    </div>`;

  setCanvasContent(`${FLOW_CSS}
    <div class="flow-root">
      ${infoHtml}
      <div class="flow-inner">
        <svg class="flow-svg" xmlns="http://www.w3.org/2000/svg"></svg>
        ${nodesHtml}
      </div>
    </div>`);
  showCanvasHeader(false);
  openCanvas('');
  document.getElementById('flow-toggle-btn')?.classList.add('active');

  // Position nodes and draw lines
  const container = document.querySelector('.flow-inner');
  if (container) {
    const w = container.clientWidth;
    const h = Math.max(container.clientHeight, 400);
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
      svg.innerHTML = allPaths.map(p => {
        const dy = p.to.y * h - p.from.y * h;
        const cp1y = p.from.y * h + dy * 0.5;
        const cp2y = p.to.y * h - dy * 0.5;
        return `<path d="M ${p.from.x * w},${p.from.y * h} C ${p.from.x * w},${cp1y} ${p.to.x * w},${cp2y} ${p.to.x * w},${p.to.y * h}" class="${p.active ? 'active' : ''}"/>`;
      }).join('');
    }
  }

  // Watch for resize
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

// ── Public API — Event handlers ────────────────────────────

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
  p.verifyResult = null; // reset verify for re-check
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

// ── Public API — Canvas control ────────────────────────────

export function closeFlow() {
  if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
  closeCanvas();
  document.getElementById('flow-toggle-btn')?.classList.remove('active');
}

export async function toggleCanvas() {
  const isOpen = document.body.classList.contains('canvas-open');
  const btn = document.getElementById('flow-toggle-btn');
  if (isOpen) { closeCanvas(); btn?.classList.remove('active'); return; }

  // Always re-render (shows current state of all pipelines)
  renderAll();
}

export function onSessionChange(activeSessionId) {
  // Could filter by session, but for now keep all visible
}

// ── Backend restore ────────────────────────────────────────

export async function autoRestore() {
  try {
    const sessionId = window.Nebflow?.activeSessionId || '';
    if (!sessionId) return;
    const resp = await fetch(`/flow/status/${sessionId}`);
    if (!resp.ok) return;
    const data = await resp.json();
    const pipeStates = data.pipelines || [];
    if (pipeStates.length === 0) return;

    pipelines.clear();
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
    console.log('[flowCanvas] Restored', pipelines.size, 'pipeline(s) from backend');
    renderAll();
  } catch (e) {
    console.warn('[flowCanvas] Restore failed:', e);
  }
}
