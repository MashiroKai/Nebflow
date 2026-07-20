// flowCanvas.js — Flow architecture visualization on an infinite canvas.
//
// Shows ALL mounted pipelines simultaneously as a connected architecture.
// Infinite pan/zoom canvas with CAD-like controls.
// Nodes positioned by hierarchy depth from Main Agent.
// Gray lines show data flow direction.

import { openCanvas, closeCanvas, setCanvasContent, showCanvasHeader } from './canvas.js';

// ── View state (pan/zoom) ──────────────────────────────────
let viewX = 0, viewY = 0, viewScale = 1;
let isPanning = false, panStartX = 0, panStartY = 0, panOrigX = 0, panOrigY = 0;
let pipelines = new Map();
let resizeObs = null;
let panZoomReady = false;

// ── CSS ────────────────────────────────────────────────────
const FLOW_CSS = `
<style>
.flow-root {
  width: 100%; height: 100%; position: relative; overflow: hidden;
  cursor: grab;
  background: var(--color-bg);
}
.flow-root.panning { cursor: grabbing; }
.flow-viewport {
  position: absolute; top: 0; left: 0;
  transform-origin: 0 0;
  will-change: transform;
}
.flow-svg {
  position: absolute; top: 0; left: 0;
  pointer-events: none; z-index: 1;
  overflow: visible;
}
.flow-svg path { fill: none; stroke: var(--color-border); stroke-width: 1; }
.flow-svg path.active { stroke: var(--color-text-muted); }

/* Node card */
.flow-node {
  position: absolute; transform: translate(-50%, -50%);
  z-index: 2;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  padding: 14px 20px;
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
.flow-orbit { position: relative; width: 44px; height: 44px; margin: 0 auto 8px; }
.flow-ring {
  position: absolute; top: 50%; left: 50%;
  border: 1px solid var(--color-border);
  border-radius: 50%; transform: translate(-50%, -50%);
}
.flow-ring-1 { width: 10px; height: 10px; }
.flow-ring-2 { width: 22px; height: 22px; }
.flow-ring-3 { width: 34px; height: 34px; }
.flow-dot-wrap { position: absolute; top: 50%; left: 50%; width: 0; height: 0; }
.flow-dot {
  position: absolute; width: 4px; height: 4px;
  background: var(--color-text); border-radius: 50%; top: -2px;
}
.flow-ring-1 .flow-dot { left: 3px; }
.flow-ring-2 .flow-dot { left: 9px; }
.flow-ring-3 .flow-dot { left: 15px; }
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

/* Labels */
.flow-label {
  font: 500 11px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text); white-space: nowrap;
}
.flow-agent {
  font: 500 9px -apple-system, sans-serif;
  color: var(--color-primary, #6366f1);
  margin-top: 2px; white-space: nowrap;
}
.flow-status {
  font: 400 8px -apple-system, sans-serif;
  color: var(--color-text-muted);
  text-transform: uppercase; letter-spacing: 0.5px; margin-top: 3px;
}

/* Toolbar */
.flow-toolbar {
  position: absolute; bottom: 16px; right: 16px; z-index: 10;
  display: flex; gap: 6px;
}
.flow-btn {
  width: 32px; height: 32px; border-radius: 8px;
  border: 1px solid var(--glass-border);
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur));
  backdrop-filter: blur(var(--glass-blur));
  font: 600 14px -apple-system, sans-serif;
  color: var(--color-text);
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: opacity 0.2s;
}
.flow-btn:hover { opacity: 0.8; }
.flow-btn:active { opacity: 0.6; }

/* Info bar */
.flow-info {
  position: absolute; top: 0; left: 0; right: 0; z-index: 5;
  font: 600 11px -apple-system, sans-serif;
  color: var(--color-text-muted);
  padding: 8px 16px;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur));
  backdrop-filter: blur(var(--glass-blur));
  border-bottom: 1px solid var(--glass-border);
  pointer-events: none;
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

// ── Layout: hierarchy by report depth ──────────────────────
// Layer 0: Main Agent
// Layer 1: Verify (reports directly to Main Agent)
// Layer 2: Last steps (feed into verify)
// Layer 3+: Earlier steps by reverse depth

function computeLayout() {
  const names = [...pipelines.keys()];
  if (names.length === 0) return null;

  const colWidth = 280;
  const colSpacing = 120;
  const rowHeight = 140;
  const originX = 0, originY = 0;

  const allNodes = [];
  const allEdges = [];
  const allPaths = [];

  // Root at top center
  const totalWidth = names.length * colWidth + (names.length - 1) * colSpacing;
  const rootX = totalWidth / 2;
  const rootY = 0;
  allNodes.push({ id: '__root__', label: 'Main Agent', status: 'done', isRoot: true, x: rootX, y: rootY });

  names.forEach((name, idx) => {
    const p = pipelines.get(name);
    const colX = idx * (colWidth + colSpacing);

    // Compute step depths (forward dependency depth)
    const depthMap = {};
    function getDepth(id) {
      if (id in depthMap) return depthMap[id];
      const step = p.steps.find(s => s.id === id);
      if (!step || !step.dependsOn || step.dependsOn.length === 0) { depthMap[id] = 0; return 0; }
      const d = Math.max(...step.dependsOn.map(getDepth)) + 1;
      depthMap[id] = d;
      return d;
    }
    p.steps.forEach(s => getDepth(s.id));
    const maxDepth = Math.max(0, ...Object.values(depthMap));

    // Layer assignment (Y position by hierarchy from Main Agent):
    // Y1: verify (closest to Main Agent — reports to it)
    // Y2: leaf steps (last in chain, feed verify)
    // Y3+: earlier steps by reverse depth
    const colCenter = colX + colWidth / 2;

    // Verify at Y=rowHeight*1
    const verifyY = rowHeight * 1;
    allNodes.push({
      id: `${name}/__verify__`, label: 'verify', status: p.verifyStatus || (p.verifyResult ? 'done' : 'pending'),
      agent: p.verifyAgent || 'Explorer', pipeline: name, isVerify: true,
      x: colCenter, y: verifyY,
    });

    // Leaf steps (no dependers) at Y=rowHeight*2
    const leafSteps = p.steps.filter(s => !p.steps.some(o => (o.dependsOn || []).includes(s.id)));
    if (leafSteps.length === 0 && p.steps.length > 0) leafSteps.push(...p.steps);

    // Assign Y by reverse depth: deeper dependency chain = lower Y
    // Step at maxDepth = closest to verify (Y=2), step at depth 0 = furthest
    p.steps.forEach(s => {
      const d = depthMap[s.id];
      const stepsFromLeaf = maxDepth - d; // 0 for leaf, increases for earlier steps
      const y = rowHeight * (2 + stepsFromLeaf);
      allNodes.push({
        id: `${name}/${s.id}`, label: s.id, status: (s.status || 'pending').toLowerCase(),
        agent: s.agent || '', pipeline: name, stepId: s.id,
        x: colCenter, y,
      });
    });

    // ── Edge definitions (store now, draw after spreading) ──

    // Main Agent → verify (verify reports to main)
    allEdges.push({ from: '__root__', to: `${name}/__verify__`, active: p.phase === 'Completed' || p.phase === 'Failed' });

    // Leaf steps → verify
    leafSteps.forEach(s => {
      allEdges.push({ from: `${name}/${s.id}`, to: `${name}/__verify__`, active: p.verifyResult !== null });
    });

    // Step dependency edges: dependency → dependent
    // Two sources: explicit dependsOn + implicit serial
    const hasExplicitEdge = (fromId, toId) =>
      p.steps.some(s => s.id === toId && (s.dependsOn || []).includes(fromId));

    p.steps.forEach((s, i) => {
      // Explicit edges
      (s.dependsOn || []).forEach(d => {
        allEdges.push({ from: `${name}/${d}`, to: `${name}/${s.id}`, active: s.status === 'Done' || s.status === 'Running' });
      });
      // Implicit serial edge: previous step → this step (if not already connected)
      if (i > 0 && !(s.dependsOn || []).includes(p.steps[i - 1].id) && !hasExplicitEdge(p.steps[i - 1].id, s.id)) {
        allEdges.push({ from: `${name}/${p.steps[i - 1].id}`, to: `${name}/${s.id}`, active: s.status === 'Done' || s.status === 'Running' });
      }
    });

    // Spread nodes horizontally within same Y level
    const yGroups = {};
    allNodes.filter(n => n.pipeline === name && !n.isVerify && !n.isRoot).forEach(n => {
      const yKey = n.y;
      if (!yGroups[yKey]) yGroups[yKey] = [];
      yGroups[yKey].push(n);
    });
    Object.values(yGroups).forEach(group => {
      const count = group.length;
      if (count > 1) {
        group.forEach((n, i) => {
          n.x = colX + colWidth * ((i + 1) / (count + 1));
        });
      }
    });
  });

  // ── Generate path coordinates from final node positions ──
  const nodeMap = {};
  allNodes.forEach(n => { nodeMap[n.id] = n; });
  allEdges.forEach(e => {
    const from = nodeMap[e.from], to = nodeMap[e.to];
    if (from && to) allPaths.push({
      from: { x: from.x, y: from.y },
      to: { x: to.x, y: to.y },
      active: e.active,
    });
  });

  // Compute bounding box
  const xs = allNodes.map(n => n.x);
  const ys = allNodes.map(n => n.y);
  const minX = Math.min(...xs) - 60;
  const maxX = Math.max(...xs) + 60;
  const minY = Math.min(...ys) - 60;
  const maxY = Math.max(...ys) + 60;

  return { allNodes, allPaths, count: names.length, minX, minY, maxX, maxY };
}

// ── Rendering ──────────────────────────────────────────────

function renderAll() {
  const layout = computeLayout();
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

  const nodesHtml = allNodes.map(n => nodeHtml(n.id, n.label, n.status, !!n.isRoot, n.agent || '')).join('');

  const infoHtml = `<div class="flow-info">${count} pipeline${count > 1 ? 's' : ''} · ${runningCount} running</div>`;
  const toolbarHtml = `
    <div class="flow-toolbar">
      <button class="flow-btn" id="flow-zoom-in" title="Zoom in">+</button>
      <button class="flow-btn" id="flow-zoom-out" title="Zoom out">−</button>
      <button class="flow-btn" id="flow-fit" title="Fit all">⊡</button>
    </div>`;

  setCanvasContent(`${FLOW_CSS}
    <div class="flow-root" id="flow-root">
      ${infoHtml}
      <div class="flow-viewport" id="flow-viewport">
        <svg class="flow-svg" id="flow-svg" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <marker id="flow-arrow" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
              <path d="M0,0 L6,3 L0,6 Z" fill="context-stroke" />
            </marker>
          </defs>
        </svg>
        ${nodesHtml}
      </div>
      ${toolbarHtml}
    </div>`);
  showCanvasHeader(false);
  openCanvas('');
  document.getElementById('flow-toggle-btn')?.classList.add('active');

  // Position nodes absolutely in viewport
  const viewport = document.getElementById('flow-viewport');
  if (viewport) {
    allNodes.forEach(n => {
      const el = viewport.querySelector(`[data-step-id="${n.id}"]`);
      if (el) {
        el.style.left = n.x + 'px';
        el.style.top = n.y + 'px';
      }
    });

    // Draw SVG paths
    const svg = document.getElementById('flow-svg');
    if (svg) {
      const pathsHtml = allPaths.map(p => {
        return `<path d="M ${p.from.x},${p.from.y} C ${p.from.x},${(p.from.y + p.to.y) / 2} ${p.to.x},${(p.from.y + p.to.y) / 2} ${p.to.x},${p.to.y}" class="${p.active ? 'active' : ''}" marker-end="url(#flow-arrow)" />`;
      }).join('');

      const defs = svg.querySelector('defs');
      svg.innerHTML = '';
      if (defs) svg.appendChild(defs);
      svg.insertAdjacentHTML('beforeend', pathsHtml);
    }
  }

  setupPanZoom(layout);
  bindToolbar(layout); // always re-bind — DOM is rebuilt each render
  fitView(layout);
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

// ── Pan / Zoom ─────────────────────────────────────────────

function applyTransform() {
  const vp = document.getElementById('flow-viewport');
  if (vp) vp.style.transform = `translate(${viewX}px, ${viewY}px) scale(${viewScale})`;
}

function fitView(layout) {
  if (!layout) return;
  const root = document.getElementById('flow-root');
  if (!root) return;
  const cw = root.clientWidth;
  const ch = root.clientHeight;
  const lw = layout.maxX - layout.minX;
  const lh = layout.maxY - layout.minY;
  const padding = 80;
  const scaleX = (cw - padding * 2) / lw;
  const scaleY = (ch - padding * 2) / lh;
  viewScale = Math.min(scaleX, scaleY, 1.5); // don't zoom in too much
  viewX = (cw - lw * viewScale) / 2 - layout.minX * viewScale;
  viewY = padding - layout.minY * viewScale + 20; // offset for info bar
  applyTransform();
}

// Window-level listeners for pan drag — attached ONCE.
// They just check the isPanning flag set by element-level mousedown.
function setupWindowListeners() {
  if (panZoomReady) return;
  window.addEventListener('mousemove', (e) => {
    if (!isPanning) return;
    viewX = panOrigX + (e.clientX - panStartX);
    viewY = panOrigY + (e.clientY - panStartY);
    applyTransform();
  });
  window.addEventListener('mouseup', () => {
    if (!isPanning) return;
    isPanning = false;
    const r = document.getElementById('flow-root');
    r?.classList.remove('panning');
  });
  panZoomReady = true;
}

// Element-level listeners — called EVERY render since .flow-root is recreated.
function setupPanZoom(layout) {
  const root = document.getElementById('flow-root');
  if (!root) return;

  root.addEventListener('mousedown', (e) => {
    if (e.target.closest('.flow-btn')) return;
    isPanning = true;
    panStartX = e.clientX;
    panStartY = e.clientY;
    panOrigX = viewX;
    panOrigY = viewY;
    root.classList.add('panning');
  });

  root.addEventListener('wheel', (e) => {
    e.preventDefault();
    const rect = root.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    const delta = e.deltaY > 0 ? 0.9 : 1.1;
    const newScale = Math.max(0.1, Math.min(5, viewScale * delta));
    viewX = mouseX - (mouseX - viewX) * (newScale / viewScale);
    viewY = mouseY - (mouseY - viewY) * (newScale / viewScale);
    viewScale = newScale;
    applyTransform();
  }, { passive: false });

  setupWindowListeners(); // idempotent — only attaches once
}

function bindToolbar(layout) {
  document.getElementById('flow-zoom-in')?.addEventListener('click', () => {
    viewScale = Math.min(5, viewScale * 1.2);
    applyTransform();
  });
  document.getElementById('flow-zoom-out')?.addEventListener('click', () => {
    viewScale = Math.max(0.1, viewScale * 0.8);
    applyTransform();
  });
  document.getElementById('flow-fit')?.addEventListener('click', () => {
    fitView(layout);
  });
}

// ── Event handlers ─────────────────────────────────────────

export function startFlow(msg) {
  const name = msg.flowName || msg.branchName || msg.name || 'unknown';
  const steps = (msg.steps || []).map(s => ({
    id: s.id || s.stepId,
    agent: s.agent || s.agentName || '',
    dependsOn: s.dependsOn || [],
    status: 'Pending',
  }));
  pipelines.set(name, {
    name, flowName: msg.flowName || name, phase: 'Running',
    steps, iteration: 0, maxIterations: msg.maxIterations || 3,
    verifyResult: null, verifyAgent: msg.verifyAgent || 'Explorer',
    verifyStatus: 'pending',
  });
  renderAll();
}

export function updateStep(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;
  // Handle verify step (not in p.steps — tracked separately)
  if (msg.stepId === '__verify__') {
    p.verifyStatus = msg.status === 'running' ? 'running'
      : msg.status === 'done' ? 'done'
      : msg.status === 'failed' ? 'failed' : p.verifyStatus;
    renderAll();
    return;
  }
  const step = p.steps.find(s => s.id === msg.stepId);
  if (!step) return;
  step.status = msg.status === 'running' ? 'Running'
    : msg.status === 'done' ? 'Done'
    : msg.status === 'failed' ? 'Failed' : step.status;
  renderAll();
}

export function updateVerify(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;
  p.verifyResult = msg.pass ? 'passed' : 'failed';
  p.verifyStatus = msg.pass ? 'done' : 'failed';
  renderAll();
}

export function updateLoop(msg) {
  const pipeName = msg.branchName || msg.flowName;
  const p = pipelines.get(pipeName);
  if (!p) return;
  p.iteration = msg.iteration || p.iteration + 1;
  p.verifyResult = null;
  p.verifyStatus = 'pending';
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
    if (pipeStates.length === 0) { renderAll(); return; }

    for (const p of pipeStates) {
      const verifyStatus = p.phase === 'Verifying' ? 'running'
        : p.verifyResult === true ? 'done'
        : p.verifyResult === false ? 'failed'
        : 'pending';
      pipelines.set(p.name, {
        name: p.name, flowName: p.flowName, phase: p.phase,
        steps: (p.steps || []).map(s => ({
          id: s.id, agent: s.agent || '',
          dependsOn: s.dependsOn || [], status: s.status || 'Pending',
        })),
        iteration: p.iteration || 0, maxIterations: 3,
        verifyResult: p.verifyResult ?? null, verifyAgent: 'Explorer',
        verifyStatus,
      });
    }
    renderAll();
  } catch (e) {
    console.warn('[flowCanvas] Restore failed:', e);
  }
}
