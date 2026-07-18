// flowCanvas.js — Flow DAG visualization on the canvas panel.
//
// Tree-branch layout with glassmorphism node cards.
// Each node is a mini solar system with CSS-animated orbiting dots.
// Theme-aware via CSS variables. Session-bound.

import { openCanvas, closeCanvas, setCanvasContent, showCanvasHeader } from './canvas.js';

// ── State ──────────────────────────────────────────────────
let flowData = null;
let resizeObs = null;

// ── CSS (injected once into canvas-content) ────────────────
const FLOW_CSS = `
<style>
.flow-root {
  width: 100%; height: 100%; position: relative; overflow: hidden;
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
  min-width: 90px;
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
/* Pending: dots at scattered angles (not all on the same side) */
.flow-ring-1 .flow-dot { left: 3px; transform: rotate(0deg) translateY(-1.5px); }
.flow-ring-2 .flow-dot { left: 8px; transform-origin: -4px 1.5px; transform: rotate(120deg); }
.flow-ring-3 .flow-dot { left: 13px; transform-origin: -11px 1.5px; transform: rotate(240deg); }

/* Running: orbit animation — negative delays scatter starting angles */
.flow-node.running .flow-dot-wrap {
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

/* Pending: dashed rings, dimmed */
.flow-node.pending .flow-ring { border-style: dashed; opacity: 0.4; }
.flow-node.pending .flow-dot { opacity: 0.3; }
.flow-node.pending .flow-label { opacity: 0.4; }

/* Done: stable, slightly dimmed */
.flow-node.done .flow-dot-wrap { animation: none; }
.flow-node.done .flow-label { opacity: 0.6; }

/* Failed */
.flow-node.failed .flow-ring { border-color: var(--color-error, #e5484d); opacity: 0.5; }

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
  position: absolute; top: 16px; left: 20px; z-index: 3;
  font: 600 12px -apple-system, sans-serif;
  color: var(--color-text-muted);
}
.flow-info .sub {
  font: 400 9px -apple-system, sans-serif;
  color: var(--color-text-muted); opacity: 0.6;
  margin-top: 3px;
}
</style>
`;

// ── Layout ─────────────────────────────────────────────────
function computeLayout(steps) {
  const depthMap = {};
  function getDepth(id) {
    if (id in depthMap) return depthMap[id];
    const step = steps.find(s => s.id === id);
    if (!step || !step.dependsOn || step.dependsOn.length === 0) {
      depthMap[id] = 0;
      return 0;
    }
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
  // Position: root at top, steps by level, verify at bottom
  const totalLevels = maxDepth + 2; // +1 for root, +1 for verify
  const positions = {};
  // Root (main agent)
  positions['__root__'] = { x: 0.5, y: 0.08 };
  // Work steps
  for (let lv = 0; lv <= maxDepth; lv++) {
    const group = levels[lv] || [];
    const y = (lv + 1) / (totalLevels + 0.5);
    group.forEach((step, i) => {
      const x = group.length === 1 ? 0.5 : (i + 1) / (group.length + 1);
      positions[step.id] = { x, y };
    });
  }
  // Verify at bottom
  positions['__verify__'] = { x: 0.5, y: (maxDepth + 1.5) / (totalLevels + 0.5) };
  return { positions, maxDepth, levels };
}

// ── Node HTML ──────────────────────────────────────────────
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

// ── SVG paths ──────────────────────────────────────────────
function buildSvgPaths(positions, steps, containerW, containerH) {
  const verifyId = '__verify__';
  const rootId = '__root__';
  const paths = [];

  function pos(id) {
    const p = positions[id];
    return p ? { x: p.x * containerW, y: p.y * containerH } : null;
  }

  // Root → first level steps (or directly to verify if no steps)
  steps.forEach(s => {
    if (!s.dependsOn || s.dependsOn.length === 0) {
      const from = pos(rootId), to = pos(s.id);
      if (from && to) paths.push({ from, to, fromId: rootId, toId: s.id });
    }
    // Step → dependencies
    if (s.dependsOn) {
      s.dependsOn.forEach(d => {
        const from = pos(d), to = pos(s.id);
        if (from && to) paths.push({ from, to, fromId: d, toId: s.id });
      });
    }
    // Step → verify
    const from = pos(s.id), to = pos(verifyId);
    if (from && to) paths.push({ from, to, fromId: s.id, toId: verifyId });
  });

  // Build path strings with cubic bezier (organic branch curve)
  return paths.map(p => {
    const dy = p.to.y - p.from.y;
    const cp1y = p.from.y + dy * 0.5;
    const cp2y = p.to.y - dy * 0.5;
    const d = `M ${p.from.x},${p.from.y} C ${p.from.x},${cp1y} ${p.to.x},${cp2y} ${p.to.x},${p.to.y}`;
    return { d, fromId: p.fromId, toId: p.toId };
  });
}

function renderSvg(paths, activeFromIds) {
  const svg = document.querySelector('.flow-svg');
  if (!svg) return;
  svg.innerHTML = paths.map(p => {
    const isActive = activeFromIds.includes(p.fromId);
    return `<path d="${p.d}" class="${isActive ? 'active' : ''}"/>`;
  }).join('');
}

// ── Refresh line positions after layout ────────────────────
function refreshLines() {
  if (!flowData) return;
  const container = document.querySelector('.flow-root');
  if (!container) return;
  const w = container.clientWidth;
  const h = container.clientHeight;
  const paths = buildSvgPaths(flowData.positions, flowData.steps, w, h);
  const activeFromIds = flowData.nodes
    .filter(n => n.status === 'done' || n.status === 'running')
    .map(n => n.id);
  renderSvg(paths, activeFromIds);
}

// ── Public API ─────────────────────────────────────────────

export function startFlow(msg) {
  console.log('[flowCanvas] flowStarted:', msg.flowName, 'steps:', msg.steps?.length);

  const steps = (msg.steps || []).map(s => ({
    id: s.id || s.stepId,
    agent: s.agent || s.agentName || '',
    dependsOn: s.dependsOn || [],
  }));

  const { positions } = computeLayout(steps);

  const nodes = [
    { id: '__root__', label: 'Main Agent', status: 'done' },
    ...steps.map(s => ({ id: s.id, label: s.id, agent: s.agent, status: 'pending' })),
    { id: '__verify__', label: 'verify', agent: msg.verifyAgent || 'Explorer', status: 'pending' },
  ];

  flowData = {
    name: msg.flowName || msg.name || 'flow',
    sessionId: msg.sessionId || null,
    steps,
    positions,
    nodes,
    iteration: 0,
    maxIterations: msg.maxIterations || 3,
    completedSteps: 0,
    totalSteps: steps.length,
  };

  // Build HTML
  const nodesHtml = nodes.map(n =>
    nodeHtml(n.id, n.label, n.status, n.id === '__root__', n.agent || '')
  ).join('');

  const infoHtml = `
    <div class="flow-info">
      <div>${flowData.name}</div>
      <div class="sub" id="flow-info-sub"></div>
    </div>`;

  setCanvasContent(`${FLOW_CSS}
    <div class="flow-root">
      ${infoHtml}
      <svg class="flow-svg" xmlns="http://www.w3.org/2000/svg"></svg>
      ${nodesHtml}
    </div>`);
  showCanvasHeader(false);
  openCanvas('');
  document.getElementById('flow-toggle-btn')?.classList.remove('hidden');
  document.getElementById('flow-toggle-btn')?.classList.add('active');

  // Position nodes
  positionNodes();

  // Draw lines after layout
  requestAnimationFrame(() => {
    refreshLines();
    updateInfo();
  });

  // Watch for resize
  const container = document.querySelector('.flow-root');
  if (container && !resizeObs) {
    resizeObs = new ResizeObserver(() => {
      positionNodes();
      refreshLines();
    });
    resizeObs.observe(container);
  }
}

function positionNodes() {
  if (!flowData) return;
  const container = document.querySelector('.flow-root');
  if (!container) return;
  const w = container.clientWidth;
  const h = container.clientHeight;
  flowData.nodes.forEach(n => {
    const p = flowData.positions[n.id];
    if (!p) return;
    const el = container.querySelector(`[data-step-id="${n.id}"]`);
    if (el) {
      el.style.left = (p.x * w) + 'px';
      el.style.top = (p.y * h) + 'px';
    }
  });
}

export function updateStep(msg) {
  if (!flowData) return;
  const node = flowData.nodes.find(n => n.id === msg.stepId);
  if (!node) return;
  const newStatus = msg.status || (msg.type === 'flowStepStarted' ? 'running'
    : msg.type === 'flowStepCompleted' ? 'done'
    : msg.type === 'flowStepFailed' ? 'failed' : node.status);
  node.status = newStatus;
  // Update DOM
  const el = document.querySelector(`[data-step-id="${msg.stepId}"]`);
  if (el) {
    el.className = `flow-node ${newStatus}${node.id === '__root__' ? ' root' : ''}`;
    const statusEl = el.querySelector('.flow-status');
    if (statusEl) statusEl.textContent = newStatus;
  }
  flowData.completedSteps = flowData.nodes.filter(n =>
    n.status === 'done' || n.status === 'failed').length;
  refreshLines();
  updateInfo();
}

export function updateVerify(msg) {
  if (!flowData) return;
  const node = flowData.nodes.find(n => n.id === '__verify__');
  if (!node) return;
  node.status = msg.pass ? 'done' : 'failed';
  const el = document.querySelector('[data-step-id="__verify__"]');
  if (el) {
    el.className = `flow-node ${node.status}`;
    const s = el.querySelector('.flow-status');
    if (s) s.textContent = msg.pass ? 'passed' : 'failed';
  }
  refreshLines();
  updateInfo();
}

export function updateLoop(msg) {
  if (!flowData) return;
  flowData.iteration = msg.iteration || flowData.iteration + 1;
  flowData.maxIterations = msg.maxIterations || flowData.maxIterations;
  const v = flowData.nodes.find(n => n.id === '__verify__');
  if (v) { v.status = 'pending'; updateNodeDom('__verify__', 'pending'); }
  updateInfo();
}

export function completeFlow(msg) {
  if (!flowData) return;
  const v = flowData.nodes.find(n => n.id === '__verify__');
  if (v) { v.status = msg.pass ? 'done' : 'failed'; updateNodeDom('__verify__', v.status); }
  refreshLines();
  setTimeout(() => closeFlow(), 5000);
}

export function closeFlow() {
  if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
  closeCanvas();
  flowData = null;
  document.getElementById('flow-toggle-btn')?.classList.add('hidden');
  document.getElementById('flow-toggle-btn')?.classList.remove('active');
}

export function toggleCanvas() {
  if (!flowData) return;
  const isOpen = document.body.classList.contains('canvas-open');
  const btn = document.getElementById('flow-toggle-btn');
  if (isOpen) {
    closeCanvas();
    btn?.classList.remove('active');
  } else {
    // Re-render content and open
    const nodesHtml = flowData.nodes.map(n =>
      nodeHtml(n.id, n.label, n.status, n.id === '__root__', n.agent || '')
    ).join('');
    const infoHtml = `<div class="flow-info"><div>${flowData.name}</div><div class="sub" id="flow-info-sub"></div></div>`;
    setCanvasContent(`${FLOW_CSS}<div class="flow-root">${infoHtml}<svg class="flow-svg" xmlns="http://www.w3.org/2000/svg"></svg>${nodesHtml}</div>`);
    showCanvasHeader(false);
    openCanvas('');
    positionNodes();
    requestAnimationFrame(() => { refreshLines(); updateInfo(); });
    btn?.classList.add('active');
  }
}

export function onSessionChange(activeSessionId) {
  if (flowData && flowData.sessionId && flowData.sessionId !== activeSessionId) {
    closeFlow();
  }
}

// ── Internal helpers ───────────────────────────────────────
function updateNodeDom(id, status) {
  const el = document.querySelector(`[data-step-id="${id}"]`);
  if (el) {
    const isRoot = id === '__root__';
    el.className = `flow-node ${status}${isRoot ? ' root' : ''}`;
    const s = el.querySelector('.flow-status');
    if (s) s.textContent = status;
  }
}

function updateInfo() {
  if (!flowData) return;
  const sub = document.getElementById('flow-info-sub');
  if (!sub) return;
  const running = flowData.nodes.filter(n => n.status === 'running').length;
  let info = `${running} running · ${flowData.completedSteps}/${flowData.totalSteps} steps`;
  if (flowData.iteration > 0) info += ` · iter ${flowData.iteration}/${flowData.maxIterations}`;
  sub.textContent = info;
}
