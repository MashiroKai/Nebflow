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
// Styling is the Glass Aurora skin (draftB port, 2026-10-16): aurora wash on
// the pane, glass command bar + facet pills + right sidebar + zoom cluster
// (css/split.css), curved sapphire edges, bead tooltip. Nodes are GRAYSCALE by
// author ruling (2026-10-16): a low-saturation gray family layered by degree
// (hubs darker → the map's structure reads at a glance), node TYPE carried by
// shape/stroke (filled circle doc / stroked rounded-square attachment / dashed
// hollow stub / ringed resolved doc), never by flashy hue. Sapphire stays the
// single interaction accent (hover edge + hover/focus rings). No new vendor
// dependencies — the force simulation is a small velocity-Verlet loop sized
// for vault-scale graphs (hundreds of nodes).

import { t } from './i18n.js';
import { authHeaders } from './flowHelpers.js';

const TAB_ID = 'wiki-graph';
const GRAPH_API = '/api/wiki/graph';

// Aurora-gray node family, one ramp per theme (dark inverts lightness so the
// nodes keep reading against the aurora wash). Degree maps onto the leaf→hub
// lightness step; attachments take the mid step + a stroke; stubs stay hollow.
const GRAY_RAMP = {
  light: { hub: '#3f444c', mid: '#5d626b', leaf: '#8b919a', attach: '#6e7076',
           stroke: '#2a2e34', stub: '#9aa0a8', doneRing: '#3f444c',
           label: '#1c1e22', labelMuted: 'rgba(58,62,68,0.78)' },
  dark:  { hub: '#bcc2cc', mid: '#949aa4', leaf: '#6a6f78', attach: '#7e848d',
           stroke: '#e8ebef', stub: '#7a8089', doneRing: '#d4d9e0',
           label: '#e8ebef', labelMuted: 'rgba(198,203,210,0.72)' },
};

// Sidebar project dots: fixed gray steps — project identity stays visible in
// the panel without re-introducing hue on the canvas.
const PROJECT_DOTS = ['#3f444c', '#71767e', '#9aa0a8', '#c2c6cc'];

// Sapphire edge colors (canvas has no CSS var access) — matches the draftB
// colorRules mapping (hover edge → strong sapphire; normal → fine sapphire
// line; filtered-out → near-invisible).
const EDGE = { hover: 'rgba(91,127,191,0.9)', normal: 'rgba(91,127,191,0.28)', dim: 'rgba(91,127,191,0.07)' };
const EDGE_BEND = 0.10;          // quadratic bow (draftB `bend`)
const GLOW = { hover: 30, idle: 9, attachHover: 26, attachIdle: 7 }; // draftB glowBoost ×2.4

// Frontmatter statuses that mark RESOLVED work (research: solved/concluded;
// engineering: accepted/done; learning: mastered) — ring on the node.
const DONE_STATUSES = /^(solved|resolved|done|concluded|accepted|mastered|published|closed)$/i;

function hexToRgb(hex) {
  const v = parseInt(hex.slice(1), 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}

/** Mix two ramp grays by t∈[0,1] → layered node color (degree shading). */
function mixGray(aHex, bHex, t) {
  const a = hexToRgb(aHex), b = hexToRgb(bHex);
  return `rgb(${Math.round(a[0] + (b[0] - a[0]) * t)},${Math.round(a[1] + (b[1] - a[1]) * t)},${Math.round(a[2] + (b[2] - a[2]) * t)})`;
}

/** The theme ramp the canvas currently draws with (prefers-color-scheme). */
function themeRamp() {
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    ? GRAY_RAMP.dark : GRAY_RAMP.light;
}

function roundedRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * Live state of the wiki graph tab (one instance at a time).
 * @typedef {Object} WikiViewState
 * @property {HTMLElement} pane
 * @property {HTMLCanvasElement} canvas
 * @property {CanvasRenderingContext2D} ctx
 * @property {any} data
 * @property {{nodes: Array<any>, edges: Array<any>, degree: Map<string, number>, alpha: number} | null} sim
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
 * @property {string} facetKind
 * @property {string} facetType
 * @property {string} facetStatus
 * @property {MediaQueryList | null} themeMQ
 * @property {(() => void) | null} onThemeChange
 * @property {{hub: string, mid: string, leaf: string, attach: string, stroke: string, stub: string, doneRing: string, label: string, labelMuted: string} | null} ramp
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
// requestAnimationFrame cycle (and its CPU) alive — and hand the explorer
// panel space back to the file tree.
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  if (e.detail && e.detail.id === TAB_ID) { teardown(); unmountGraphExplorerList(); }
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
  pane.classList.add('wiki-graph-pane', 'wx-B');

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
  // Scenario facets (wiki-graph v2): kind + frontmatter type/status filters —
  // the research "open problems / conclusions" and learning "progress" faces.
  // Options are (re)derived from the loaded data in rebuildSim.
  const facetRow = document.createElement('div');
  facetRow.className = 'wiki-facets';
  const mkFacet = (cls) => {
    const sel = document.createElement('select');
    sel.className = 'wiki-facet glass-control ' + cls;
    sel.addEventListener('change', () => {
      if (!view) return;
      if (cls === 'wiki-facet-kind') view.facetKind = sel.value;
      else if (cls === 'wiki-facet-type') view.facetType = sel.value;
      else view.facetStatus = sel.value;
      applyFilter();
    });
    return sel;
  };
  const kindSel = mkFacet('wiki-facet-kind');
  const typeSel = mkFacet('wiki-facet-type');
  const statusSel = mkFacet('wiki-facet-status');
  facetRow.append(kindSel, typeSel, statusSel);
  pane.appendChild(facetRow);
  pane.appendChild(bar);

  const canvasEl = document.createElement('canvas');
  canvasEl.className = 'wiki-canvas';
  pane.appendChild(canvasEl);

  // The graph's file list is NOT built here anymore (graph-view interaction
  // batch, 2026-10-18): while this tab is the visible Canvas content the list
  // is hosted by the SAME panel space the file explorer uses
  // (syncGraphExplorerList / renderGraphFileList below), so the pane stays
  // pure graph + toolbar. renderSidebar() fills that host once the data lands
  // in rebuildSim; its filter rows still drive the EXISTING toolbar/facet
  // controls through real events, so list and toolbar state can never diverge.

  // ── Zoom cluster (draftB): in/out reuse the wheel zoom math; ⟲ re-lands the
  // layout (rebuildSim resets scale + pan and re-seeds positions).
  const zoom = document.createElement('div');
  zoom.className = 'wx-zoom';
  const mkZoomBtn = (z, label, title) => {
    const b = document.createElement('button');
    b.type = 'button'; b.dataset.z = z; b.textContent = label; b.title = title;
    return b;
  };
  zoom.append(
    mkZoomBtn('in', '+', t('wiki.zoomInTitle')),
    mkZoomBtn('out', '−', t('wiki.zoomOutTitle')),
    mkZoomBtn('fit', '⟲', t('wiki.zoomResetTitle')),
  );
  zoom.addEventListener('click', (e) => {
    const b = (/** @type {Element} */ (e.target)).closest('button');
    if (!b || !view) return;
    if (b.dataset.z === 'in') zoomCanvas(1.12);
    else if (b.dataset.z === 'out') zoomCanvas(1 / 1.12);
    else rebuildSim();
  });
  pane.appendChild(zoom);

  view = { pane, canvas: canvasEl, ctx: canvasEl.getContext('2d'), data: null, sim: null,
           raf: 0, focusId: pendingFocus || '', filter: '', byId: new Map(),
           focusNode: null, focusPulse: 0, vx: 0, vy: 0, scale: 1,
           dragging: null, panning: false, hover: null,
           lastPointer: null, resizeObs: null, colors: null, W: 0, H: 0,
           facetKind: '', facetType: '', facetStatus: '',
           themeMQ: null, onThemeChange: null, ramp: null };
  pendingFocus = '';

  bindCanvas(canvasEl);
  if (window.ResizeObserver) {
    view.resizeObs = new ResizeObserver(() => resizeCanvas());
    view.resizeObs.observe(canvasEl);
  }
  // Theme flip (system light/dark) mid-session: repaint the gray ramp + label
  // colors on the next frame. CSS surfaces follow the media query themselves.
  view.themeMQ = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  view.onThemeChange = () => {
    if (!view || !view.sim) return;
    paintNodes();
    const g = themeRamp();
    view.ramp = g;
    view.colors = { text: g.label, muted: g.labelMuted };
    wake(0.1);
  };
  if (view.themeMQ && view.themeMQ.addEventListener) view.themeMQ.addEventListener('change', view.onThemeChange);

  await loadData(false);
  rebuildSim();
}

function teardown() {
  if (!view) return;
  if (view.raf) cancelAnimationFrame(view.raf);
  if (view.resizeObs) view.resizeObs.disconnect();
  if (view.themeMQ && view.onThemeChange && view.themeMQ.removeEventListener) {
    view.themeMQ.removeEventListener('change', view.onThemeChange);
  }
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

/** Assign the aurora-gray node colors from link degree (hubs darker). */
function paintNodes() {
  if (!view || !view.sim) return;
  const g = themeRamp();
  const deg = view.sim.degree;
  for (const n of view.sim.nodes) {
    if (n.ref.stub) { n.color = g.stub; continue; }
    if (n.ref.kind === 'attachment') { n.color = g.attach; continue; }
    // t: leaf (degree 0) → hub (degree 6+ saturates) — the layering read.
    const t = Math.min(1, (deg.get(n.id) || 0) / 6);
    n.color = mixGray(g.leaf, g.hub, t);
  }
}

function rebuildSim() {
  if (!view || !view.data) return;
  const data = view.data;
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
    color: '#8b919a',
    remote: n.device && n.device !== 'self',
    project: n.project,
    radius: n.stub ? 5 : (n.kind === 'attachment' ? 5.5 : 6 + Math.min(6, (n.headings || []).length * 0.8)),
    fixed: false,
  }));
  const byId = new Map(nodes.map(n => [n.id, n]));
  const edges = (data.edges || [])
    .map(e => ({ from: byId.get(e.from), to: byId.get(e.to), anchor: e.anchor || '' }))
    .filter(e => e.from && e.to && e.from !== e.to);
  // Link degree per node — drives the grayscale layering (paintNodes).
  const degree = new Map();
  edges.forEach(e => {
    degree.set(e.from.id, (degree.get(e.from.id) || 0) + 1);
    degree.set(e.to.id, (degree.get(e.to.id) || 0) + 1);
  });
  view.sim = { nodes, edges, degree, alpha: 1 };
  view.byId = byId;
  paintNodes();
  view.scale = 1;
  view.vx = 0; view.vy = 0;
  // Canvas label colors follow the same theme ramp as the nodes (2D context
  // cannot read CSS variables). Cached per rebuild + theme flip (draw reads
  // view.ramp — no per-frame matchMedia).
  const g = themeRamp();
  view.ramp = g;
  view.colors = { text: g.label, muted: g.labelMuted };
  // Scenario facet options from the loaded data (stable sorted, capped).
  const typeSel = /** @type {HTMLSelectElement|null} */ (view.pane.querySelector('.wiki-facet-type'));
  const statusSel = /** @type {HTMLSelectElement|null} */ (view.pane.querySelector('.wiki-facet-status'));
  const kindSel = /** @type {HTMLSelectElement|null} */ (view.pane.querySelector('.wiki-facet-kind'));
  if (kindSel) {
    kindSel.innerHTML = '';
    [['', t('wiki.facetAll')], ['doc', t('wiki.facetDocs')], ['attachment', t('wiki.facetAttachments')]].forEach(([v, label]) => {
      const o = document.createElement('option'); o.value = v; o.textContent = label; kindSel.appendChild(o);
    });
    kindSel.value = view.facetKind;
  }
  const fillFacet = (sel, values, allLabel) => {
    if (!sel) return;
    sel.innerHTML = '';
    const all = document.createElement('option'); all.value = ''; all.textContent = allLabel; sel.appendChild(all);
    values.forEach((v) => { const o = document.createElement('option'); o.value = v; o.textContent = v; sel.appendChild(o); });
    sel.value = view.facetStatus !== undefined && sel === statusSel ? view.facetStatus : (sel === typeSel ? view.facetType : '');
  };
  const types = [...new Set((data.nodes || []).map(n => n.type).filter(Boolean))].sort().slice(0, 12);
  const statuses = [...new Set((data.nodes || []).map(n => n.status).filter(Boolean))].sort().slice(0, 12);
  fillFacet(typeSel, types, t('wiki.facetAllTypes'));
  fillFacet(statusSel, statuses, t('wiki.facetAllStatuses'));
  renderSidebar();
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
    let visible = true;
    if (q) visible = (n.label || '').toLowerCase().includes(q) || (n.ref.rel || '').toLowerCase().includes(q) || (n.project || '').toLowerCase().includes(q);
    if (visible && view.facetKind) visible = (n.ref.kind || 'doc') === view.facetKind;
    if (visible && view.facetType) visible = n.ref.type === view.facetType;
    if (visible && view.facetStatus) visible = n.ref.status === view.facetStatus;
    n.dimmed = !visible;
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

  // Edges — fine sapphire lines with the draftB aurora bow (quadratic bend);
  // the hovered node's links highlight strong, filtered links fall to near-0.
  for (const e of sim.edges) {
    const hl = neighbors && (e.from === hover || e.to === hover);
    const dimmed = (e.from.dimmed || e.to.dimmed);
    ctx.strokeStyle = hl ? EDGE.hover : (dimmed ? EDGE.dim : EDGE.normal);
    ctx.lineWidth = hl ? 2 : 1.25;
    const dx = e.to.x - e.from.x, dy = e.to.y - e.from.y;
    const mx = (e.from.x + e.to.x) / 2 - dy * EDGE_BEND;
    const my = (e.from.y + e.to.y) / 2 + dx * EDGE_BEND;
    ctx.beginPath();
    ctx.moveTo(e.from.x, e.from.y);
    ctx.quadraticCurveTo(mx, my, e.to.x, e.to.y);
    ctx.stroke();
  }
  // Nodes — grayscale family: layering = degree (paintNodes), type = shape.
  const g = view.ramp || themeRamp();
  for (const n of sim.nodes) {
    const r = n.radius;
    const isHover = n === hover;
    const connected = !neighbors || n === hover || neighbors.has(n);
    const alpha = n.dimmed ? 0.12 : (connected ? 1 : 0.25);
    ctx.globalAlpha = alpha;
    if (n === view.focusNode && view.focusPulse > 0) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 10 * view.focusPulse, 0, Math.PI * 2);
      ctx.strokeStyle = EDGE.hover;
      ctx.lineWidth = 1.9;
      ctx.globalAlpha = view.focusPulse * 0.8;
      ctx.stroke();
      ctx.globalAlpha = alpha;
    }
    if (n.ref.stub) {
      // Unresolved: hollow dashed circle — shape carries the type, no fill.
      ctx.setLineDash([2.5, 2.5]);
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.strokeStyle = n.color;
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.setLineDash([]);
    } else if (n.ref.kind === 'attachment') {
      // Attachments (literature PDFs, figures) draw as stroked rounded squares
      // so the document/attachment split reads at a glance (shape + stroke,
      // per the grayscale ruling — never hue).
      const s = r * 1.7;
      roundedRect(ctx, n.x - s / 2, n.y - s / 2, s, s, 3);
      ctx.fillStyle = n.color;
      ctx.shadowColor = n.color;
      ctx.shadowBlur = isHover ? GLOW.attachHover : GLOW.attachIdle;
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.strokeStyle = g.stroke;
      ctx.lineWidth = 1;
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = n.color;
      ctx.shadowColor = n.color;
      ctx.shadowBlur = isHover ? GLOW.hover : GLOW.idle;
      ctx.fill();
      ctx.shadowBlur = 0;
      // Status glyph: resolved work carries a ring — graphite stroke keeps the
      // glyph inside the gray family (was brand green before the ruling).
      if (n.ref.status && DONE_STATUSES.test(n.ref.status)) {
        ctx.beginPath();
        ctx.arc(n.x, n.y, r + 3.5, 0, Math.PI * 2);
        ctx.strokeStyle = g.doneRing;
        ctx.lineWidth = 1.6;
        ctx.stroke();
      }
      // Hover ring — the clear "this is the one" state (sapphire, the single
      // interaction accent).
      if (isHover) {
        ctx.beginPath();
        ctx.arc(n.x, n.y, r + 2.5, 0, Math.PI * 2);
        ctx.strokeStyle = EDGE.hover;
        ctx.lineWidth = 2;
        ctx.stroke();
      }
    }
    // Selected (focused from a document's graph button): persistent ring so the
    // selection survives the landing pulse.
    if (n === view.focusNode && !n.ref.stub && view.focusPulse <= 0) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 4.5, 0, Math.PI * 2);
      ctx.strokeStyle = EDGE.hover;
      ctx.lineWidth = 1.6;
      ctx.stroke();
    }
    // Label — only when readable (zoomed in, hovered, or few docs).
    const labelOn = isHover || view.scale > 1.6 || sim.nodes.length <= 40 || (neighbors && neighbors.has(n) && view.scale > 0.9);
    if (labelOn && !n.dimmed) {
      ctx.font = (isHover ? '600 ' : '') + '11px -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif';
      ctx.fillStyle = isHover ? (view.colors ? view.colors.text : '#1c1e22') : (view.colors ? view.colors.muted : 'rgba(128,132,140,0.95)');
      ctx.textAlign = 'center';
      let label = n.label.length > 28 ? n.label.slice(0, 27) + '…' : n.label;
      if (n.ref.kind === 'attachment') label = '⎘ ' + label;
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
    const rect = canvasEl.getBoundingClientRect();
    zoomAt(e.clientX - rect.left, e.clientY - rect.top, e.deltaY < 0 ? 1.12 : 1 / 1.12);
  }, { passive: false });
}

/** Zoom anchored at pane-local (px,py) — shared by the wheel and the cluster. */
function zoomAt(px, py, factor) {
  if (!view) return;
  const W = view.W / 2, H = view.H / 2;
  const ox = px - W - view.vx, oy = py - H - view.vy;
  view.vx -= ox * (factor - 1);
  view.vy -= oy * (factor - 1);
  view.scale = Math.min(4, Math.max(0.25, view.scale * factor));
}

/** Zoom-cluster leg: wheel math anchored at the pane center. */
function zoomCanvas(factor) {
  if (!view) return;
  wake(0.15);
  zoomAt(view.W / 2, view.H / 2, factor);
}

// ── File list hosted in the explorer panel space (graph-view interaction
// batch, 2026-10-18) ────────────────────────────────────────────
// While the wiki-graph tab is the visible Canvas content, its file list —
// projects with their documents, the facet chips and the legend — is hosted
// by the SAME panel space the file explorer uses (#panel-sessions, beside
// #explorer-section, which hides for the duration). The pane itself builds no
// list block. Mount/unmount ride the tab lifecycle: every activation funnels
// through canvas.js setActiveTab → 'canvas-tab-switched'; closing rides
// 'canvas-tab-closed'; the Canvas panel's collapse has no event — it toggles
// body.canvas-open, mirrored with a MutationObserver. Filter wiring goes
// through the real toolbar/facet controls (set value + dispatch), so the
// list, the selects and the canvas share one filter state.

const EXPLORER_HOST_ID = 'wiki-graph-explorer-section';

/** The explorer panel space this module may host the list in — null when the
 *  panel is absent (embedded/test contexts): the pane then simply stays pure
 *  graph. @returns {{panel: HTMLElement, explorer: HTMLElement} | null} */
function explorerPanelSpace() {
  const panel = document.getElementById('panel-sessions');
  const explorer = document.getElementById('explorer-section');
  return panel && explorer ? { panel, explorer } : null;
}

/** True when the wiki-graph tab is the VISIBLE Canvas content (its pane is
 *  the active one ∧ the Canvas panel is open — the same predicate
 *  canvas.js syncCanvasPanelButtons mirrors onto the activity-bar button). */
function graphTabVisible() {
  const pane = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-content .canvas-tab-pane.active'));
  return !!pane && pane.dataset.tabId === TAB_ID && document.body.classList.contains('canvas-open');
}

/** Mount the file list into the explorer panel space — or restore the
 *  explorer when the graph tab is not the visible content. Idempotent. */
function syncGraphExplorerList() {
  const space = explorerPanelSpace();
  if (!space) return;
  if (!graphTabVisible()) { unmountGraphExplorerList(); return; }
  let host = document.getElementById(EXPLORER_HOST_ID);
  if (!host) {
    host = document.createElement('div');
    host.id = EXPLORER_HOST_ID;
    space.panel.insertBefore(host, space.explorer.nextSibling);
  }
  space.explorer.style.display = 'none';
  renderGraphFileList(host);
}

/** Restore the file explorer (the graph list is no longer hosted). */
function unmountGraphExplorerList() {
  document.getElementById(EXPLORER_HOST_ID)?.remove();
  const explorer = document.getElementById('explorer-section');
  if (explorer) explorer.style.display = '';
}

/** The file list face: head (title + clear), projects with their documents,
 *  facet chips, legend. A document row opens through the SAME entry the
 *  canvas node clicks use (openDoc → openLocalFileTab / remote-doc channel). */
function renderGraphFileList(host) {
  if (!view || !view.data) return;
  const d = view.data;
  const nodes = d.nodes || [];
  const docCount = nodes.filter(n => !n.stub).length;
  const linkCount = (d.edges || []).length;
  const pane = view.pane;
  const search = /** @type {HTMLInputElement|null} */ (pane ? pane.querySelector('.wiki-toolbar-search') : null);

  host.innerHTML = '';
  const head = document.createElement('div');
  head.className = 'wx-sb-head';
  const title = document.createElement('span');
  title.className = 'wx-sb-head-title';
  title.textContent = t('wiki.panelTitle');
  const clearBtn = document.createElement('button');
  clearBtn.type = 'button';
  clearBtn.className = 'wx-sb-clear';
  clearBtn.textContent = t('wiki.clearAll');
  clearBtn.title = t('wiki.clearAllTitle');
  clearBtn.addEventListener('click', () => {
    if (!view || !view.pane) return;
    const search2 = /** @type {HTMLInputElement|null} */ (view.pane.querySelector('.wiki-toolbar-search'));
    const kindSel = /** @type {HTMLSelectElement|null} */ (view.pane.querySelector('.wiki-facet-kind'));
    const typeSel = /** @type {HTMLSelectElement|null} */ (view.pane.querySelector('.wiki-facet-type'));
    const statusSel = /** @type {HTMLSelectElement|null} */ (view.pane.querySelector('.wiki-facet-status'));
    if (search2) { search2.value = ''; search2.dispatchEvent(new Event('input', { bubbles: true })); }
    [kindSel, typeSel, statusSel].forEach((s) => { if (s) { s.value = ''; s.dispatchEvent(new Event('change', { bubbles: true })); } });
    host.querySelectorAll('.active').forEach((el) => el.classList.remove('active'));
  });
  head.append(title, clearBtn);

  const body = document.createElement('div');
  body.className = 'wx-sb-body';

  // Projects + their documents — the file list itself. Project rows keep the
  // old sidebar behaviour (click = toolbar search filter); document rows open
  // the file in a Canvas tab through openDoc.
  const projSec = document.createElement('div');
  projSec.className = 'wx-sb-sec';
  const projTitle = document.createElement('div');
  projTitle.className = 'wx-sb-title wx-sb-proj-title';
  projTitle.textContent = t('wiki.sbProjectsCount').replace('{docs}', String(docCount)).replace('{links}', String(linkCount));
  const projList = document.createElement('div');
  projList.className = 'wx-sb-list wx-projects';
  const byProject = new Map();
  nodes.forEach((n) => {
    if (!byProject.has(n.project)) byProject.set(n.project, []);
    byProject.get(n.project).push(n);
  });
  (d.projects || []).forEach((p, i) => {
    const row = document.createElement('div');
    row.className = 'wx-sb-item wx-proj';
    if (view.filter === p.name.toLowerCase()) row.classList.add('active');
    const dot = document.createElement('span');
    dot.className = 'wx-dot';
    dot.style.background = PROJECT_DOTS[i % PROJECT_DOTS.length];
    const name = document.createElement('span');
    name.className = 'wx-proj-name';
    name.textContent = p.name + (p.stub ? t('wiki.remoteProject') : '');
    const count = document.createElement('span');
    count.className = 'wx-count';
    count.textContent = String(p.docCount ?? 0);
    row.append(dot, name, count);
    row.addEventListener('click', () => {
      const active = row.classList.toggle('active');
      projList.querySelectorAll('.wx-proj').forEach((r) => { if (r !== row) r.classList.remove('active'); });
      if (search) { search.value = active ? p.name : ''; search.dispatchEvent(new Event('input', { bubbles: true })); }
    });
    projList.appendChild(row);
    // Document rows — real files only (stubs are unresolved links, not files;
    // clicking a stub node on the canvas explains that via its own toast).
    for (const n of byProject.get(p.name) || []) {
      if (n.stub) continue;
      const doc = document.createElement('div');
      doc.className = 'wx-sb-item wx-doc';
      const docName = document.createElement('span');
      docName.className = 'wx-doc-name';
      docName.textContent = (n.kind === 'attachment' ? '⎘ ' : '') + (n.title || n.rel.split('/').pop());
      doc.title = n.rel;
      doc.append(docName);
      doc.addEventListener('click', () => { void openDoc(n); });
      projList.appendChild(doc);
    }
  });
  projSec.append(projTitle, projList);

  // Type / status chips — click = facet select. Counts capped like the selects.
  const chipInto = (hostSel, selCls, counts) => {
    const sec = document.createElement('div');
    sec.className = 'wx-sb-sec';
    const secTitle = document.createElement('div');
    secTitle.className = 'wx-sb-title';
    secTitle.textContent = hostSel === '.wx-types' ? t('wiki.sbTypes') : t('wiki.sbStatuses');
    const chips = document.createElement('div');
    chips.className = 'wx-sb-chips';
    const sel = pane ? pane.querySelector(selCls) : null;
    counts.forEach(([v, c]) => {
      const el = document.createElement('span');
      el.className = 'wx-chip';
      if (sel && /** @type {HTMLSelectElement} */ (sel).value === v) el.classList.add('active');
      el.textContent = `${v} ${c}`;
      el.addEventListener('click', () => {
        const on = !el.classList.contains('active');
        chips.querySelectorAll('.wx-chip').forEach((c2) => c2.classList.remove('active'));
        if (on) el.classList.add('active');
        if (sel) { /** @type {HTMLSelectElement} */ (sel).value = on ? v : ''; sel.dispatchEvent(new Event('change', { bubbles: true })); }
      });
      chips.appendChild(el);
    });
    sec.append(secTitle, chips);
    return sec;
  };
  const topCounts = (key) => {
    const m = new Map();
    nodes.forEach((n) => { if (n[key]) m.set(n[key], (m.get(n[key]) || 0) + 1); });
    return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
  };

  // Legend — node shapes (moved with the list; the pane stays pure graph).
  const legendSec = document.createElement('div');
  legendSec.className = 'wx-sb-sec';
  const legendTitle = document.createElement('div');
  legendTitle.className = 'wx-sb-title';
  legendTitle.textContent = t('wiki.sbLegend');
  const legend = document.createElement('div');
  legend.className = 'wx-sb-legend';
  legend.innerHTML =
    `<div><b>●</b> <span class="wx-lg-docs"></span> &nbsp;<b>▢</b> <span class="wx-lg-attach"></span> &nbsp;<b>◌</b> <span class="wx-lg-stub"></span></div>` +
    `<div><b>◎</b> <span class="wx-lg-done"></span> &nbsp;<b>—</b> <span class="wx-lg-links"></span></div>`;
  legend.querySelector('.wx-lg-docs').textContent = t('wiki.legendDocs');
  legend.querySelector('.wx-lg-attach').textContent = t('wiki.legendAttachments');
  legend.querySelector('.wx-lg-stub').textContent = t('wiki.legendUnresolved');
  legend.querySelector('.wx-lg-done').textContent = t('wiki.legendDone');
  legend.querySelector('.wx-lg-links').textContent = t('wiki.legendLinks');
  legendSec.append(legendTitle, legend);

  body.append(projSec, chipInto('.wx-types', '.wiki-facet-type', topCounts('type')),
    chipInto('.wx-status', '.wiki-facet-status', topCounts('status')), legendSec);
  host.append(head, body);
}

/** Data-landing hook (rebuildSim): refresh the hosted list in place. */
function renderSidebar() {
  const host = document.getElementById(EXPLORER_HOST_ID);
  if (host) renderGraphFileList(host);
}

// Tab switching drives the explorer-space hosting: any activation re-evaluates
// (syncGraphExplorerList self-guards on WHICH pane is active — mount for this
// tab, restore the explorer for any other).
document.addEventListener('canvas-tab-switched', () => { syncGraphExplorerList(); });

// Canvas panel collapse/expand dispatches no event (body.canvas-open is the
// state mirror canvas.js itself reads) — track it so the explorer comes back
// the moment the graph stops being visible.
new MutationObserver(() => { syncGraphExplorerList(); })
  .observe(document.body, { attributes: true, attributeFilter: ['class'] });

function showTooltip(node, px, py) {
  let tip = document.getElementById('wiki-graph-tip');
  if (!tip) {
    tip = document.createElement('div');
    tip.id = 'wiki-graph-tip';
    tip.className = 'wiki-tip';
    // Mounted INSIDE the graph pane, not on document.body: the pane is
    // position:relative + overflow:hidden, so the tip (position:absolute) is
    // structurally confined to the graph view and can never surface over the
    // conversation UI. A stale tip also dies with the pane's innerHTML reset.
    view.pane.appendChild(tip);
  }
  const d = node.ref;
  const deviceLine = node.remote ? `<div class="wiki-tip-device">${t('wiki.remoteDevice').replace('{device}', d.device)}</div>` : '';
  // Scenario metadata: kind + frontmatter type/status (research/learning packs).
  const meta = [];
  if (d.kind === 'attachment') meta.push(t('wiki.kindAttachment'));
  if (d.type) meta.push(d.type);
  if (d.status) meta.push(d.status);
  const metaLine = meta.length ? `<div class="wiki-tip-meta"></div>` : '';
  tip.innerHTML = `<div class="wiki-tip-title"></div><div class="wiki-tip-path"></div>${metaLine}${deviceLine}`;
  tip.querySelector('.wiki-tip-title').textContent = d.title || d.rel;
  tip.querySelector('.wiki-tip-path').textContent = d.rel || '';
  if (meta.length) tip.querySelector('.wiki-tip-meta').textContent = meta.join(' · ');
  // (px,py) are canvas-local pointer coords; the canvas fills the pane
  // (inset:0), so they are pane-local too — exactly the tip's positioning
  // space now. Clamp against the MEASURED tip box so it stays fully inside
  // the pane even near the right/bottom edges (the old clamp mixed canvas
  // coords with window.innerWidth — the misplacement bug this fixes).
  tip.style.left = Math.max(4, Math.min(view.pane.clientWidth - tip.offsetWidth - 4, px + 14)) + 'px';
  tip.style.top = Math.max(4, Math.min(view.pane.clientHeight - tip.offsetHeight - 4, py + 14)) + 'px';
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
      const proj = (view?.data.projects || []).find(p => p.name === d.project);
      window.__showToast?.(
        proj && proj.error ? t('wiki.stubError').replace('{error}', proj.error)
          : t('wiki.stubProject').replace('{project}', d.project),
        'info');
    } else {
      window.__showToast?.(t('wiki.unresolved'), 'info');
    }
    return;
  }
  await openDoc(d);
}

/** Open one graph document through the SAME channel every other file open in
 *  the product uses (workspace-open-item → canvas.js openWorkspaceItem →
 *  Canvas tab): local files ride openLocalFileTab — the entry the markdown
 *  viewer's links use — so tab dedupe/switching behave identically; peer-device
 *  docs are fetched read-only through /api/wiki/remote-doc. Shared by canvas
 *  node clicks AND the explorer-panel file list rows (graph-view interaction
 *  batch, 2026-10-18). @param {any} d graph node ref */
async function openDoc(d) {
  const remote = d.device && d.device !== 'self';
  if (!remote) {
    // Local: the graph carries the project vault — rebuild the absolute path.
    // Attachments open through the SAME channel: the viewer registry picks the
    // pdf/image/office viewer by extension (literature one click away).
    const proj = (view?.data.projects || []).find(p => p.name === d.project);
    if (!proj) return;
    const sep = proj.vault.includes('\\') ? '\\' : '/';
    const abs = proj.vault.replace(/[\\/]+$/, '') + sep + d.rel.split('/').join(sep);
    const { openLocalFileTab } = await import('./viewers/shared.js');
    openLocalFileTab(abs);
    return;
  }
  // Remote: fetch read-only content through the peer channel. Binary viewer
  // files (literature PDFs, figures) arrive base64 and open from a blob URL.
  try {
    const res = await fetch(`/api/wiki/remote-doc?device=${encodeURIComponent(d.device)}&path=${encodeURIComponent((view?.data.projects.find(p => p.name === d.project)?.vault || '') + '/' + d.rel)}`, { headers: { ...authHeaders() } });
    if (!res.ok) { window.__showToast?.(t('wiki.remoteFetchFailed'), 'error'); return; }
    const body = await res.json();
    const detail = {
      id: 'wiki-remote:' + d.id,
      title: (d.title || d.rel) + ' · ' + d.device,
      readOnly: true,
    };
    if (body.binary && body.contentBase64) {
      const bin = atob(body.contentBase64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      const mime = REMOTE_MIME[d.rel.toLowerCase().match(/\.([a-z0-9]+)$/)?.[1] || ''] || 'application/octet-stream';
      detail.itemType = REMOTE_ITEM_TYPE[d.rel.toLowerCase().match(/\.([a-z0-9]+)$/)?.[1] || ''] || 'image';
      detail.objectUrl = URL.createObjectURL(new Blob([bytes], { type: mime }));
      detail.fileName = d.title || d.rel;
    } else {
      detail.itemType = 'markdown';
      detail.content = body.content || '';
    }
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail }));
  } catch (e) {
    window.__showToast?.(t('wiki.remoteFetchFailed'), 'error');
  }
}

/** Extension → blob mime / canvas itemType for remote attachment opens. */
const REMOTE_MIME = { pdf: 'application/pdf', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml' };
const REMOTE_ITEM_TYPE = { pdf: 'pdf', docx: 'docx', pptx: 'pptx', xlsx: 'xlsx', epub: 'epub', png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', svg: 'image' };

/** Focus a node (used by the md-viewer graph button): open the tab centered
 *  on that document, identified by project + rel path. */
export function focusWikiNode(project, rel) {
  const id = `${project}:${rel.replace(/\\/g, '/')}`;
  openWikiGraphTab(id);
}
