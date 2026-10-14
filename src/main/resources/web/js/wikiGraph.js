// wikiGraph.js — Wiki document graph (Obsidian-style force-directed map).
//
// The Canvas tab `wiki-graph` renders the merged document graph served by
// GET /api/wiki/graph: one node per markdown document across every project's
// `<workspace>/docs` vault (local projects scanned on disk; peer-device
// projects scanned through the NebLink browse channel), one edge per
// Obsidian wikilink / markdown link / embed.
//
// Interaction model follows the Obsidian graph view: drag to pan, wheel to
// zoom, drag a node to pull the layout, click a node to open the document
// (local files open as Canvas file tabs; remote docs are fetched read-only
// through /api/wiki/remote-doc). Hovering highlights the node's neighborhood;
// the toolbar search dims everything that does not match.
//
// Styling uses the Sapphire Glass design tokens (css/base.css, css/sapphire.css):
// a transparent canvas over the pane, glass toolbar controls, project hues from
// the accent palette. No new vendor dependencies — the force simulation is a
// small velocity-Verlet loop sized for vault-scale graphs (hundreds of nodes).

import { t } from './i18n.js';
import { authHeaders } from './flowHelpers.js';

const TAB_ID = 'wiki-graph';
const GRAPH_API = '/api/wiki/graph';

// Project palette — mirrors the accent ramp used across the design system
// (--color-primary green, sapphire blue, plus warm/cool companions). Values
// are the token defaults so the graph keeps working inside iframes.
const PALETTE = ['#07c160', '#5e6ad2', '#e6a23c', '#d2695e', '#3aa0ff', '#9a6dd7', '#2fbfb0', '#c8567b'];

/**
 * Live state of the wiki graph tab (one instance at a time).
 * @typedef {Object} WikiViewState
 * @property {HTMLElement} pane
 * @property {HTMLCanvasElement} canvas
 * @property {CanvasRenderingContext2D} ctx
 * @property {any} data
 * @property {{nodes: Array<any>, edges: Array<any>, alpha: number} | null} sim
 * @property {number} raf
 * @property {string} focusId
 * @property {string} filter
 * @property {Map<string, any>} byId
 * @property {any} focusNode
 * @property {number} focusPulse
 * @property {number} vx
 * @property {number} vy
 * @property {number} scale
 * @property {any} dragging
 * @property {boolean} panning
 * @property {any} hover
 * @property {{px: number, py: number} | null} lastPointer
 * @property {ResizeObserver | null} resizeObs
 * @property {number} W
 * @property {number} H
 * @property {{text: string, muted: string} | null} colors
 */

/** @type {WikiViewState | null} */
let view = null;

/** Open (or focus) the wiki graph tab and render it. */
export async function openWikiGraphTab(focusNodeId = '') {
  const canvas = await import('./canvas.js');
  const entry = canvas.openTab(TAB_ID, t('wiki.tabTitle'), { type: TAB_ID, closable: true });
  if (!entry) return;
  if (focusNodeId) view ? (view.focusId = focusNodeId) : (pendingFocus = focusNodeId);
  await renderInto(entry.paneEl);
}

/** Pending focus for a restore that races the first render. */
let pendingFocus = '';

// Tab restore (reload/restart: canvas.js rebuilds the pane and dispatches
// canvas-tab-restore for panel tabs — same contract as projectTab.js).
window.addEventListener('canvas-tab-restore', (/** @type {CustomEvent} */ e) => {
  if (e.detail && e.detail.id === TAB_ID) {
    import('./canvas.js').then(async ({ getTabPane }) => {
      const pane = getTabPane(TAB_ID);
      if (pane) await renderInto(pane);
    });
  }
});

// Tab close: stop the animation loop — a detached canvas must not keep a
// requestAnimationFrame cycle (and its CPU) alive.
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  if (e.detail && e.detail.id === TAB_ID) teardown();
});

/** Debounced refetch when the tab is re-activated (canvas.js panelTabRefresh). */
let refetchTimer = 0;
export function rerenderWikiGraph() {
  clearTimeout(refetchTimer);
  refetchTimer = setTimeout(() => {
    if (!view || !view.pane || !view.pane.isConnected) return;
    loadData(true).then(() => rebuildSim());
  }, 200);
}

async function renderInto(pane) {
  if (view && view.pane === pane && view.canvas && view.canvas.isConnected) {
    if (pendingFocus) { view.focusId = pendingFocus; pendingFocus = ''; }
    return;
  }
  teardown();
  pane.innerHTML = '';
  pane.classList.add('wiki-graph-pane');

  const bar = document.createElement('div');
  bar.className = 'wiki-toolbar';

  const search = document.createElement('input');
  search.type = 'text';
  search.className = 'wiki-toolbar-search glass-control';
  search.placeholder = t('wiki.searchPlaceholder');
  search.addEventListener('input', () => {
    if (!view) return;
    view.filter = search.value.trim().toLowerCase();
    applyFilter();
  });

  const refreshBtn = document.createElement('button');
  refreshBtn.className = 'glass-control wiki-toolbar-btn';
  refreshBtn.textContent = t('wiki.refresh');
  refreshBtn.title = t('wiki.refreshTitle');
  refreshBtn.addEventListener('click', async () => {
    refreshBtn.classList.add('busy');
    await loadData(true);
    rebuildSim();
    refreshBtn.classList.remove('busy');
  });

  const status = document.createElement('span');
  status.className = 'wiki-toolbar-status';

  bar.append(search, refreshBtn, status);
  pane.appendChild(bar);

  const canvasEl = document.createElement('canvas');
  canvasEl.className = 'wiki-canvas';
  pane.appendChild(canvasEl);

  view = { pane, canvas: canvasEl, ctx: canvasEl.getContext('2d'), data: null, sim: null,
           raf: 0, focusId: pendingFocus || '', filter: '', byId: new Map(),
           focusNode: null, focusPulse: 0, vx: 0, vy: 0, scale: 1,
           dragging: null, panning: false, hover: null,
           lastPointer: null, resizeObs: null, colors: null, W: 0, H: 0 };
  pendingFocus = '';

  bindCanvas(canvasEl);
  if (window.ResizeObserver) {
    view.resizeObs = new ResizeObserver(() => resizeCanvas());
    view.resizeObs.observe(canvasEl);
  }

  await loadData(false);
  rebuildSim();
}

function teardown() {
  if (!view) return;
  if (view.raf) cancelAnimationFrame(view.raf);
  if (view.resizeObs) view.resizeObs.disconnect();
  view = null;
}

async function loadData(force) {
  if (!view) return;
  try {
    const res = await fetch(GRAPH_API + (force ? '?force=1' : ''), { headers: { ...authHeaders() } });
    view.data = res.ok ? await res.json() : { projects: [], nodes: [], edges: [] };
  } catch (e) {
    view.data = { projects: [], nodes: [], edges: [], error: String(e && e.message || e) };
  }
  if (view.data.error) window.__showToast?.(String(view.data.error), 'error');
}

// ── Simulation model ───────────────────────────────────────────

function projectHue(projects) {
  const map = new Map();
  (projects || []).forEach((p, i) => map.set(p.name, PALETTE[i % PALETTE.length]));
  return map;
}

function rebuildSim() {
  if (!view || !view.data) return;
  const data = view.data;
  const hues = projectHue(data.projects);
  const W = view.W || view.canvas.clientWidth || 800;
  const H = view.H || view.canvas.clientHeight || 600;
  const nodes = (data.nodes || []).map((n, i) => ({
    ref: n,
    id: n.id,
    label: n.title || (n.rel || '').split('/').pop(),
    x: W / 2 + Math.cos((i / Math.max(1, data.nodes.length)) * Math.PI * 2) * (0.25 * Math.min(W, H)),
    y: H / 2 + Math.sin((i / Math.max(1, data.nodes.length)) * Math.PI * 2) * (0.25 * Math.min(W, H)),
    px: 0, py: 0,
    vx: 0, vy: 0,
    color: n.stub ? 'rgba(139,142,150,0.55)' : (hues.get(n.project) || PALETTE[0]),
    remote: n.device && n.device !== 'self',
    project: n.project,
    radius: n.stub ? 5 : 6 + Math.min(6, (n.headings || []).length * 0.8),
    fixed: false,
  }));
  const byId = new Map(nodes.map(n => [n.id, n]));
  const edges = (data.edges || [])
    .map(e => ({ from: byId.get(e.from), to: byId.get(e.to), anchor: e.anchor || '' }))
    .filter(e => e.from && e.to && e.from !== e.to);
  view.sim = { nodes, edges, alpha: 1 };
  view.byId = byId;
  view.scale = 1;
  view.vx = 0; view.vy = 0;
  // Design-token colors for canvas (2D context cannot read CSS variables).
  const cs = getComputedStyle(document.documentElement);
  view.colors = {
    text: (cs.getPropertyValue('--color-text') || '#1c1e22').trim() || '#1c1e22',
    muted: 'rgba(128,132,140,0.95)',
  };
  // Center on focus node after two ticks (positions need to spread first).
  updateStatus();
  applyFilter();
  view.focusNode = null;
  view.focusPulse = 0;
  if (view.focusId && byId.has(view.focusId)) {
    const n = byId.get(view.focusId);
    view.vx = W / 2 - n.x; view.vy = H / 2 - n.y;
    view.focusNode = n;
    view.focusPulse = 1;
    view.sim.alpha = 1.2;
  }
  startLoop();
}

function updateStatus() {
  if (!view || !view.pane) return;
  const status = view.pane.querySelector('.wiki-toolbar-status');
  if (!status) return;
  const d = view.data || {};
  const docs = (d.nodes || []).filter(n => !n.stub).length;
  const links = (d.edges || []).length;
  const projects = (d.projects || []).filter(p => !p.stub).length;
  status.textContent = t('wiki.status').replace('{docs}', String(docs)).replace('{links}', String(links)).replace('{projects}', String(projects));
}

function applyFilter() {
  if (!view || !view.sim) return;
  const q = view.filter;
  view.sim.nodes.forEach(n => {
    n.dimmed = !!q && !(n.label || '').toLowerCase().includes(q) && !(n.ref.rel || '').toLowerCase().includes(q) && !(n.project || '').toLowerCase().includes(q);
  });
}

function tick() {
  const sim = view && view.sim;
  if (!sim) return;
  const nodes = sim.nodes, edges = sim.edges;
  const W = view.W || 800, H = view.H || 600;
  const kAlpha = Math.min(1, sim.alpha);
  // Repulsion (n² — fine for vault-scale graphs).
  for (let i = 0; i < nodes.length; i++) {
    const a = nodes[i];
    for (let j = i + 1; j < nodes.length; j++) {
      const b = nodes[j];
      let dx = a.x - b.x, dy = a.y - b.y;
      let d2 = dx * dx + dy * dy;
      if (d2 < 1) { dx = (Math.random() - 0.5); dy = (Math.random() - 0.5); d2 = 1; }
      if (d2 > 160000) continue; // cut off beyond 400px
      const f = 1800 * kAlpha / d2;
      const d = Math.sqrt(d2);
      const fx = dx / d * f, fy = dy / d * f;
      a.vx += fx; a.vy += fy;
      b.vx -= fx; b.vy -= fy;
    }
  }
  // Springs.
  for (const e of edges) {
    const dx = e.to.x - e.from.x, dy = e.to.y - e.from.y;
    const d = Math.max(1, Math.hypot(dx, dy));
    const target = 110;
    const f = (d - target) * 0.015 * kAlpha;
    const fx = dx / d * f, fy = dy / d * f;
    e.from.vx += fx; e.from.vy += fy;
    e.to.vx -= fx; e.to.vy -= fy;
  }
  // Gravity toward center + integrate.
  const drag = 0.85;
  for (const n of nodes) {
    n.vx += (W / 2 - n.x) * 0.0015 * kAlpha;
    n.vy += (H / 2 - n.y) * 0.0015 * kAlpha;
    if (n.dragging) { n.vx = 0; n.vy = 0; continue; }
    n.vx *= drag; n.vy *= drag;
    n.x += n.vx; n.y += n.vy;
  }
  if (view.focusPulse > 0) view.focusPulse = Math.max(0, view.focusPulse - 0.008);
  sim.alpha *= 0.995;
}

function startLoop() {
  if (!view) return;
  cancelAnimationFrame(view.raf);
  const loop = () => {
    if (!view || !view.sim) return;
    tick();
    draw();
    // Idle stop: the layout settled and nobody is interacting — save the
    // battery. Any pointer/zoom/filter interaction restarts the loop.
    if (view.sim.alpha < 0.02 && view.focusPulse <= 0 && !view.hover && !view.dragging && !view.panning) {
      view.raf = 0;
      return;
    }
    view.raf = requestAnimationFrame(loop);
  };
  view.raf = requestAnimationFrame(loop);
}

/** Wake the simulation/loop after user interaction (see startLoop idle stop). */
function wake(minAlpha = 0.3) {
  if (!view || !view.sim) return;
  view.sim.alpha = Math.max(view.sim.alpha, minAlpha);
  if (!view.raf) startLoop();
}

// ── Rendering ─────────────────────────────────────────────────

function resizeCanvas() {
  if (!view || !view.canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = view.canvas.clientWidth, h = view.canvas.clientHeight;
  view.canvas.width = Math.max(1, Math.floor(w * dpr));
  view.canvas.height = Math.max(1, Math.floor(h * dpr));
  view.W = w; view.H = h;
  view.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function draw() {
  if (!view || !view.ctx) return;
  const { ctx, sim } = view;
  if (view.canvas.width === 1) resizeCanvas();
  const W = view.W, H = view.H;
  ctx.clearRect(0, 0, W, H);
  if (!sim) return;
  ctx.save();
  ctx.translate(W / 2 + view.vx, H / 2 + view.vy);
  ctx.scale(view.scale, view.scale);
  ctx.translate(-W / 2, -H / 2);

  const hover = view.hover;
  const neighbors = hover ? neighborhood(hover) : null;

  // Edges.
  for (const e of sim.edges) {
    const hl = neighbors && (e.from === hover || e.to === hover);
    const dimmed = (e.from.dimmed || e.to.dimmed);
    ctx.strokeStyle = hl
      ? 'rgba(94,106,210,0.9)'
      : (dimmed ? 'rgba(128,132,140,0.06)' : 'rgba(128,132,140,0.22)');
    ctx.lineWidth = hl ? 1.6 : 1;
    ctx.beginPath();
    ctx.moveTo(e.from.x, e.from.y);
    ctx.lineTo(e.to.x, e.to.y);
    ctx.stroke();
  }
  // Nodes.
  for (const n of sim.nodes) {
    const r = n.radius;
    const isHover = n === hover;
    const connected = !neighbors || n === hover || neighbors.has(n);
    const alpha = n.dimmed ? 0.12 : (connected ? 1 : 0.25);
    ctx.globalAlpha = alpha;
    if (n === view.focusNode && view.focusPulse > 0) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 10 * view.focusPulse, 0, Math.PI * 2);
      ctx.strokeStyle = n.color;
      ctx.lineWidth = 1.5;
      ctx.globalAlpha = view.focusPulse * 0.8;
      ctx.stroke();
      ctx.globalAlpha = alpha;
    }
    if (n.ref.stub) {
      ctx.setLineDash([2.5, 2.5]);
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.strokeStyle = n.color;
      ctx.lineWidth = 1.2;
      ctx.stroke();
      ctx.setLineDash([]);
    } else {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = n.color;
      ctx.shadowColor = n.color;
      ctx.shadowBlur = isHover ? 14 : 4;
      ctx.fill();
      ctx.shadowBlur = 0;
    }
    // Label — only when readable (zoomed in, hovered, or few docs).
    const labelOn = isHover || view.scale > 1.6 || sim.nodes.length <= 40 || (neighbors && neighbors.has(n) && view.scale > 0.9);
    if (labelOn && !n.dimmed) {
      ctx.font = (isHover ? '600 ' : '') + '11px -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif';
      ctx.fillStyle = isHover ? (view.colors ? view.colors.text : '#1c1e22') : (view.colors ? view.colors.muted : 'rgba(128,132,140,0.95)');
      ctx.textAlign = 'center';
      const label = n.label.length > 28 ? n.label.slice(0, 27) + '…' : n.label;
      ctx.fillText(label, n.x, n.y + r + 13);
    }
    ctx.globalAlpha = 1;
  }
  ctx.restore();
}

function neighborhood(node) {
  if (!view || !view.sim) return null;
  const set = new Set();
  for (const e of view.sim.edges) {
    if (e.from === node) set.add(e.to);
    else if (e.to === node) set.add(e.from);
  }
  return set;
}

// ── Pointer interaction ───────────────────────────────────────

function toWorld(px, py) {
  const W = view.W, H = view.H;
  return {
    x: (px - W / 2 - view.vx) / view.scale + W / 2,
    y: (py - H / 2 - view.vy) / view.scale + H / 2,
  };
}

function pick(px, py) {
  if (!view || !view.sim) return null;
  const p = toWorld(px, py);
  let best = null, bestD = Infinity;
  for (const n of view.sim.nodes) {
    const d = Math.hypot(n.x - p.x, n.y - p.y);
    if (d < n.radius + 8 && d < bestD) { best = n; bestD = d; }
  }
  return best;
}

function bindCanvas(canvasEl) {
  let moved = false;
  canvasEl.addEventListener('pointerdown', (e) => {
    if (!view) return;
    canvasEl.setPointerCapture(e.pointerId);
    const rect = canvasEl.getBoundingClientRect();
    const px = e.clientX - rect.left, py = e.clientY - rect.top;
    const hit = pick(px, py);
    moved = false;
    wake(hit ? 0.6 : 0.25);
    if (hit) { hit.dragging = true; view.dragging = hit; }
    else { view.panning = true; }
    view.lastPointer = { px, py };
  });
  canvasEl.addEventListener('pointermove', (e) => {
    if (!view) return;
    const rect = canvasEl.getBoundingClientRect();
    const px = e.clientX - rect.left, py = e.clientY - rect.top;
    const last = view.lastPointer || { px, py };
    const dx = px - last.px, dy = py - last.py;
    if (Math.abs(dx) + Math.abs(dy) > 2) moved = true;
    if (view.dragging) {
      const w = toWorld(px, py);
      view.dragging.x = w.x; view.dragging.y = w.y;
      wake(0.35);
    } else if (view.panning) {
      view.vx += dx; view.vy += dy;
    } else {
      const hit = pick(px, py);
      if (hit !== view.hover) {
        view.hover = hit;
        canvasEl.style.cursor = hit ? 'pointer' : 'grab';
      }
      if (hit) showTooltip(hit, px, py);
      else hideTooltip();
    }
    view.lastPointer = { px, py };
  });
  const endDrag = (e) => {
    if (!view) return;
    if (view.dragging) view.dragging.dragging = false;
    if (!moved && view.dragging) openNode(view.dragging);
    view.dragging = null;
    view.panning = false;
    view.lastPointer = null;
    hideTooltip();
  };
  canvasEl.addEventListener('pointerup', endDrag);
  canvasEl.addEventListener('pointercancel', endDrag);
  canvasEl.addEventListener('pointerleave', () => { if (view) { view.hover = null; hideTooltip(); } });
  canvasEl.addEventListener('wheel', (e) => {
    if (!view) return;
    e.preventDefault();
    wake(0.15);
    const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
    const rect = canvasEl.getBoundingClientRect();
    const px = e.clientX - rect.left, py = e.clientY - rect.top;
    const W = view.W / 2, H = view.H / 2;
    // Zoom toward the pointer.
    const ox = px - W - view.vx, oy = py - H - view.vy;
    view.vx -= ox * (factor - 1);
    view.vy -= oy * (factor - 1);
    view.scale = Math.min(4, Math.max(0.25, view.scale * factor));
  }, { passive: false });
}

function showTooltip(node, px, py) {
  let tip = document.getElementById('wiki-graph-tip');
  if (!tip) {
    tip = document.createElement('div');
    tip.id = 'wiki-graph-tip';
    tip.className = 'wiki-tip';
    document.body.appendChild(tip);
  }
  const d = node.ref;
  const deviceLine = node.remote ? `<div class="wiki-tip-device">${t('wiki.remoteDevice').replace('{device}', d.device)}</div>` : '';
  tip.innerHTML = `<div class="wiki-tip-title"></div><div class="wiki-tip-path"></div>${deviceLine}`;
  tip.querySelector('.wiki-tip-title').textContent = d.title || d.rel;
  tip.querySelector('.wiki-tip-path').textContent = d.rel || '';
  tip.style.left = Math.min(window.innerWidth - 260, px + 14) + 'px';
  tip.style.top = (py + 14) + 'px';
  tip.classList.add('show');
}

function hideTooltip() {
  document.getElementById('wiki-graph-tip')?.classList.remove('show');
}

async function openNode(node) {
  const d = node.ref;
  if (d.stub) {
    // A project-level stub or an unresolved link — explain honestly.
    if (d.rel.endsWith('/')) {
      const proj = (view.data.projects || []).find(p => p.name === d.project);
      window.__showToast?.(
        proj && proj.error ? t('wiki.stubError').replace('{error}', proj.error)
          : t('wiki.stubProject').replace('{project}', d.project),
        'info');
    } else {
      window.__showToast?.(t('wiki.unresolved'), 'info');
    }
    return;
  }
  if (!node.remote) {
    // Local: the graph carries the project vault — rebuild the absolute path.
    const proj = (view.data.projects || []).find(p => p.name === d.project);
    if (!proj) return;
    const sep = proj.vault.includes('\\') ? '\\' : '/';
    const abs = proj.vault.replace(/[\\/]+$/, '') + sep + d.rel.split('/').join(sep);
    const { openLocalFileTab } = await import('./viewers/shared.js');
    openLocalFileTab(abs);
    return;
  }
  // Remote: fetch read-only content through the peer channel.
  try {
    const res = await fetch(`/api/wiki/remote-doc?device=${encodeURIComponent(d.device)}&path=${encodeURIComponent((view.data.projects.find(p => p.name === d.project)?.vault || '') + '/' + d.rel)}`, { headers: { ...authHeaders() } });
    if (!res.ok) { window.__showToast?.(t('wiki.remoteFetchFailed'), 'error'); return; }
    const body = await res.json();
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'wiki-remote:' + d.id,
        itemType: 'markdown',
        title: (d.title || d.rel) + ' · ' + d.device,
        content: body.content || '',
        readOnly: true,
      },
    }));
  } catch (e) {
    window.__showToast?.(t('wiki.remoteFetchFailed'), 'error');
  }
}

/** Focus a node (used by the md-viewer graph button): open the tab centered
 *  on that document, identified by project + rel path. */
export function focusWikiNode(project, rel) {
  const id = `${project}:${rel.replace(/\\/g, '/')}`;
  openWikiGraphTab(id);
}
