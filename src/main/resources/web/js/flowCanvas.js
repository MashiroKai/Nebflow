// flowCanvas.js — Flow DAG visualization on the canvas panel.
//
// Each agent is a mini solar system: concentric rings with orbiting dots.
// Running agents have dots in motion; done/pending are static.
// All black-and-white, line-based aesthetic.
// Data flows along edges only when a done node feeds a running node.

import { openCanvas, closeCanvas, setCanvasContent, showCanvasHeader } from './canvas.js';
import state from './state.js';

// ── Constants ──────────────────────────────────────────────
const INK = '#333333';
const LINE = '#CCCCCC';
const LINE_ACTIVE = '#999999';
const TXT = '#888888';
const TXT_DIM = '#AAAAAA';

// ── State ──────────────────────────────────────────────────
let canvasEl = null;
let ctx = null;
let rafId = null;
let resizeObs = null;
let W = 0, H = 0;
let time = 0;

let flowData = null;  // { name, steps, edges, verify, loop, iteration, maxIterations, phase }

// ── Solar system node model ────────────────────────────────
function makeRings() {
  return [
    { r: 5,  dots: [{ a: Math.random() * 6.28, s: 0.025 }] },
    { r: 10, dots: [{ a: Math.random() * 6.28, s: 0.018 }, { a: Math.random() * 6.28, s: 0.018 }] },
    { r: 15, dots: [{ a: Math.random() * 6.28, s: 0.012 }] },
  ];
}

// ── Layout: compute node positions from DAG ────────────────
function computeLayout(steps, verifyId) {
  // Compute depth (longest dependency chain) for each step
  const depthMap = {};
  function getDepth(id) {
    if (id in depthMap) return depthMap[id];
    const step = steps.find(s => s.id === id);
    if (!step || step.dependsOn.length === 0) return 0;
    const d = Math.max(...step.dependsOn.map(getDepth)) + 1;
    depthMap[id] = d;
    return d;
  }
  steps.forEach(s => getDepth(s.id));

  // Group by depth level
  const maxDepth = Math.max(...Object.values(depthMap), 0);
  const levels = {};
  steps.forEach(s => {
    const d = depthMap[s.id];
    if (!levels[d]) levels[d] = [];
    levels[d].push(s);
  });

  // Position each level
  const nodes = [];
  const levelCount = maxDepth + 1;
  for (let lv = 0; lv <= maxDepth; lv++) {
    const group = levels[lv] || [];
    const y = (lv + 0.5) / (levelCount + 1.5);  // +1.5 to leave room for verify
    group.forEach((step, i) => {
      const x = group.length === 1 ? 0.5 : (i + 1) / (group.length + 1);
      nodes.push({
        id: step.id,
        agent: step.agent,
        label: step.id,
        x, y,
        status: 'pending',
        rings: makeRings(),
      });
    });
  }

  // Verify node at bottom
  nodes.push({
    id: verifyId,
    agent: 'verify',
    label: 'verify',
    x: 0.5,
    y: (maxDepth + 1.5) / (levelCount + 1.5),
    status: 'pending',
    rings: makeRings(),
  });

  return nodes;
}

// ── Canvas setup ───────────────────────────────────────────
function ensureCanvas() {
  setCanvasContent('<canvas id="flow-dag" style="width:100%;height:100%;display:block;"></canvas>');
  showCanvasHeader(false);
  canvasEl = document.getElementById('flow-dag');
  ctx = canvasEl.getContext('2d');

  function resize() {
    const rect = canvasEl.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvasEl.width = rect.width * dpr;
    canvasEl.height = rect.height * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    W = rect.width;
    H = rect.height;
  }

  resize();
  resizeObs = new ResizeObserver(resize);
  resizeObs.observe(canvasEl);
}

function destroyCanvas() {
  if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
  if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
  canvasEl = null;
  ctx = null;
}

// ── Drawing ────────────────────────────────────────────────
const N = id => flowData?.nodes.find(n => n.id === id);

function edgePath(from, to) {
  const x1 = from.x * W, y1 = from.y * H;
  const x2 = to.x * W, y2 = to.y * H;
  const dx = x2 - x1, dy = y2 - y1;
  const dist = Math.sqrt(dx * dx + dy * dy) || 1;
  const ux = dx / dist, uy = dy / dist;
  const pad = 18; // node radius + margin
  return {
    x1: x1 + ux * pad, y1: y1 + uy * pad,
    x2: x2 - ux * pad, y2: y2 - uy * pad,
    mx: (x1 + x2) / 2, my: (y1 + y2) / 2,
  };
}

function bezierPt(p, t) {
  return {
    x: (1-t)*(1-t)*p.x1 + 2*(1-t)*t*p.mx + t*t*p.x2,
    y: (1-t)*(1-t)*p.y1 + 2*(1-t)*t*p.my + t*t*p.y2,
  };
}

function drawEdges() {
  if (!flowData) return;
  const { nodes, edges, flows } = flowData;

  // Static lines
  edges.forEach(e => {
    const f = N(e.from), t = N(e.to);
    if (!f || !t) return;
    const p = edgePath(f, t);
    const isActive = f.status === 'done' || f.status === 'running';
    ctx.beginPath();
    ctx.moveTo(p.x1, p.y1);
    ctx.quadraticCurveTo(p.mx, p.my, p.x2, p.y2);
    ctx.strokeStyle = isActive ? LINE_ACTIVE : LINE;
    ctx.lineWidth = 0.8;
    ctx.stroke();
  });

  // Flowing dots (done → running only)
  flows.forEach(f => {
    f.t += f.speed;
    if (f.t > 1) f.t = 0;
    const from = N(f.fromId), to = N(f.toId);
    if (!from || !to) return;
    const p = edgePath(from, to);
    const pt = bezierPt(p, f.t);
    ctx.fillStyle = INK;
    ctx.beginPath();
    ctx.arc(pt.x, pt.y, 1.5, 0, 6.28);
    ctx.fill();
  });
}

function drawNode(n) {
  const x = n.x * W, y = n.y * H;
  const running = n.status === 'running';
  const done = n.status === 'done';
  const failed = n.status === 'failed';
  const pending = n.status === 'pending';

  // Consistent opacity — user requested uniform visibility
  const color = INK;
  const lineColor = INK;

  // Concentric rings + orbiting dots
  n.rings.forEach(ring => {
    // Ring circle
    ctx.beginPath();
    ctx.arc(x, y, ring.r, 0, 6.28);
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 0.7;
    if (pending) ctx.setLineDash([1.5, 1.5]);
    ctx.stroke();
    ctx.setLineDash([]);

    // Dots on ring
    ring.dots.forEach(d => {
      if (running) d.a += d.s;
      const dx = x + ring.r * Math.cos(d.a);
      const dy = y + ring.r * Math.sin(d.a);
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(dx, dy, 1.2, 0, 6.28);
      ctx.fill();
    });
  });

  // Center point
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(x, y, 1.2, 0, 6.28);
  ctx.fill();

  // Failed marker
  if (failed) {
    ctx.strokeStyle = INK;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(x - 18, y - 18);
    ctx.lineTo(x + 18, y + 18);
    ctx.moveTo(x + 18, y - 18);
    ctx.lineTo(x - 18, y + 18);
    ctx.stroke();
  }

  // Label
  ctx.font = '500 9px -apple-system, sans-serif';
  ctx.fillStyle = TXT;
  ctx.textAlign = 'center';
  ctx.fillText(n.label, x, y + 24);

  // Status subtitle
  const sub = running ? 'running' : done ? 'done' : pending ? 'pending' : 'failed';
  ctx.font = '400 7px -apple-system, sans-serif';
  ctx.fillStyle = TXT_DIM;
  ctx.fillText(sub, x, y + 34);
}

function drawHeader() {
  if (!flowData) return;
  const { name, iteration, maxIterations, completedSteps, totalSteps, phase } = flowData;

  ctx.font = '600 11px -apple-system, sans-serif';
  ctx.fillStyle = TXT;
  ctx.textAlign = 'left';
  ctx.fillText(name, 24, 28);

  ctx.font = '400 9px -apple-system, sans-serif';
  ctx.fillStyle = TXT_DIM;
  const running = flowData.nodes.filter(n => n.status === 'running').length;
  let info = `${running} running · ${completedSteps}/${totalSteps} steps`;
  if (iteration > 0) info += ` · iter ${iteration}/${maxIterations}`;
  ctx.fillText(info, 24, 42);
}

function animate() {
  time += 0.016;
  if (!ctx) return;
  ctx.clearRect(0, 0, W, H);
  drawHeader();
  drawEdges();
  flowData?.nodes.forEach(drawNode);
  rafId = requestAnimationFrame(animate);
}

// ── Public API ─────────────────────────────────────────────

/** Start a new flow visualization. Call on 'flowStarted' event. */
export function startFlow(msg) {
  const steps = msg.steps || [];
  const verifyId = '__verify__';

  // Build layout
  const nodes = computeLayout(steps.map(s => ({
    id: s.id || s.stepId,
    agent: s.agent || s.agentName || '',
    dependsOn: s.dependsOn || [],
  })), verifyId);

  // Build edges from dependencies
  const edges = [];
  steps.forEach(s => {
    const sid = s.id || s.stepId;
    const deps = s.dependsOn || [];
    deps.forEach(d => edges.push({ from: d, to: sid }));
    // Each step → verify (verify depends on all)
    edges.push({ from: sid, to: verifyId });
  });

  flowData = {
    name: msg.flowName || msg.name || 'flow',
    nodes,
    edges,
    flows: [],     // active flowing dots
    iteration: 0,
    maxIterations: msg.maxIterations || 3,
    completedSteps: 0,
    totalSteps: steps.length,
    phase: 'working',
  };

  ensureCanvas();
  openCanvas('');
  if (!rafId) animate();
}

/** Update a step's status. Call on flowStepStarted/Completed/Failed. */
export function updateStep(msg) {
  if (!flowData) return;
  const node = flowData.nodes.find(n => n.id === msg.stepId);
  if (!node) return;

  const oldStatus = node.status;
  const newStatus = msg.status || (msg.type === 'flowStepStarted' ? 'running'
    : msg.type === 'flowStepCompleted' ? 'done'
    : msg.type === 'flowStepFailed' ? 'failed' : node.status);
  node.status = newStatus;

  // Recompute completed count
  flowData.completedSteps = flowData.nodes.filter(n =>
    n.status === 'done' || n.status === 'failed').length;

  // Manage flowing dots: add when from=done → to=running
  refreshFlows();
}

/** Update verify result. Call on flowVerifyResult. */
export function updateVerify(msg) {
  if (!flowData) return;
  const node = flowData.nodes.find(n => n.id === '__verify__');
  if (!node) return;
  if (msg.pass) {
    node.status = 'done';
  } else {
    node.status = 'failed';
  }
  refreshFlows();
}

/** Update loop iteration. Call on flowLoopIteration. */
export function updateLoop(msg) {
  if (!flowData) return;
  flowData.iteration = msg.iteration || flowData.iteration + 1;
  flowData.maxIterations = msg.maxIterations || flowData.maxIterations;

  // Reset verify to pending for next iteration
  const verify = flowData.nodes.find(n => n.id === '__verify__');
  if (verify) verify.status = 'pending';

  // Reset fix node if exists
  const fix = flowData.nodes.find(n => n.id === '__fix__');
  if (fix) fix.status = 'running';
}

/** Finalize flow. Call on flowCompleted. */
export function completeFlow(msg) {
  if (!flowData) return;
  flowData.phase = msg.pass ? 'completed' : 'failed';

  // Mark verify node
  const verify = flowData.nodes.find(n => n.id === '__verify__');
  if (verify) verify.status = msg.pass ? 'done' : 'failed';

  // Stop all animations
  flowData.flows = [];

  // Close after delay
  setTimeout(() => {
    destroyCanvas();
    closeCanvas();
    flowData = null;
  }, 5000);
}

/** Force-close the flow visualization. */
export function closeFlow() {
  destroyCanvas();
  closeCanvas();
  flowData = null;
}

// ── Internal: recompute flowing dots ───────────────────────
function refreshFlows() {
  if (!flowData) return;
  flowData.flows = [];
  flowData.edges.forEach(e => {
    const f = flowData.nodes.find(n => n.id === e.from);
    const t = flowData.nodes.find(n => n.id === e.to);
    if (f && t && f.status === 'done' && t.status === 'running') {
      flowData.flows.push({
        fromId: e.from,
        toId: e.to,
        t: Math.random(),
        speed: 0.004 + Math.random() * 0.002,
      });
    }
  });
}
