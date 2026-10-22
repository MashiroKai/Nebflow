// wikiGraph.js — Wiki document graph (控制面迁左栏 redesign, 2026-10-21 batch).
//
// The Canvas tab `wiki-graph` renders the merged document graph served by
// GET /api/wiki/graph: one node per markdown document across every project's
// `<workspace>/docs` vault (local projects scanned on disk; peer-device
// projects scanned through the NebLink browse channel), one edge per
// Obsidian wikilink / markdown link / embed.
//
// 作者令 2026-10-21（图谱布局重构 · 控制面迁左栏）：canvas 只显示图谱本体——
// 力导向图 + 悬停 tooltip，控件面全部撤出 canvas；激活图谱 tab 时左栏（原文件
// 浏览器那一栏）内容切换为「图谱控制面」，回到文件浏览态时左栏还原文件浏览器：
//   左栏 #wiki-graph-side（#panel-sessions 内，#explorer-section 的兄弟节）
//     搜索框 · 目录 chips 过滤 · 选中节点详情卡（徽标/摘要/目录/引出/被引/
//     路径/打开文档，替代原浮动预览卡）· 断链清单（固定区块替代浮层）·
//     统计行（N 篇文档 · N 条引用 · N 条未解析）
//   canvas pane（.wiki-graph-pane）
//     <canvas> + 悬停 tooltip；另有 .wg-panebar（仅 body.mobile-layer 显示——
//     手机层没有左栏，紧凑控制条随 pane 收编进移动页，桌面零渲染）
//   双向联动：左栏点目录/词条（chips/ref/断链行）→ canvas 过滤聚焦；
//     canvas 点节点 → 左栏详情卡刷新
//   亮暗双主题：canvas 沿用 --wg-* 石墨双主题表；左栏面板吃侧栏玻璃卡既有的
//     --color-frame-* / --glass-* token（prefers-color-scheme 自动双主题）；
//     窄面板（container query，wgside）压缩内距与徽标
//   展开 semantics（承 2026-10-19 批 R1-R10）：图谱「变为可见」的跃迁才自动
//     展开侧栏并激活 files 面板（showSidePanel 既有持久化 API，零新键）；
//     可见期间手动收起 → 安静；切走再切回 → 再展开；离开绝不自动收起；
//     移动端 ≤768px 不自动开（抽屉互斥）。
//
// Nodes are solid graphite dots (degree → radius 5-11 + ink depth), edges are
// 1px straight muted lines. No glow, no pulse, no accent hue, no persistent
// animation (the force loop stops once the layout settles). Labels: hubs by
// default, the rest fade in with hover and zoom (LABEL_ALL_AT_SCALE ramp).
//
// No new vendor dependencies — the force simulation stays the small
// velocity-Verlet loop sized for vault-scale graphs (hundreds of nodes).

import { t } from './i18n.js';
import { authHeaders } from './flowHelpers.js';

const TAB_ID = 'wiki-graph';
const GRAPH_API = '/api/wiki/graph';

// Label density knobs (代审整改): hubs labeled by default; zoom reveals the
// rest linearly until everything is labeled at LABEL_ALL_AT_SCALE.
const LABEL_HUBS = 12;
const LABEL_ALL_AT_SCALE = 1.5;
const ZOOM_MIN = 0.08; // design §3: 8%–320%
const ZOOM_MAX = 3.2;
const LABEL_MAX_CHARS = 22;   // canvas label truncation (design §4)

const MOBILE_MQ = '(max-width: 768px)';

/** @typedef {{ref:any, id:string, label:string, dir:string, isAttach:boolean,
 *             x:number, y:number, vx:number, vy:number, degree:number, rank:number,
 *             out:Array<any>, inc:Array<any>, dimmed:boolean}} WgNode */

/**
 * Live state of the wiki graph tab (one instance at a time).
 * @typedef {Object} WikiViewState
 * @property {HTMLElement} pane
 * @property {HTMLCanvasElement} canvas
 * @property {CanvasRenderingContext2D} ctx
 * @property {HTMLElement|null} side   — sidebar control face (#wiki-graph-side)
 * @property {HTMLElement|null} panebar — in-pane compact bar (mobile layer only)
 * @property {any} data
 * @property {{nodes: WgNode[], edges: {a:WgNode,b:WgNode}[], alpha: number} | null} sim
 * @property {number} raf
 * @property {string} focusId
 * @property {Map<string, WgNode>} byId
 * @property {WgNode | null} hover
 * @property {WgNode | null} selected
 * @property {number} vx
 * @property {number} vy
 * @property {number} scale
 * @property {WgNode | null} dragging
 * @property {boolean} panning
 * @property {{px: number, py: number} | null} lastPointer
 * @property {ResizeObserver | null} resizeObs
 * @property {number} W
 * @property {number} H
 * @property {{ink: string, muted: string, line: string} | null} colors
 * @property {string} filter
 * @property {string} dirFilter
 * @property {string[]} dirs
 * @property {{from: string, target: string}[]} unresolved
 * @property {{name: string, error: string, stub: boolean}[]} projectErrors
 * @property {MediaQueryList | null} themeMQ
 * @property {(() => void) | null} onThemeChange
 * @property {(() => void) | null} onKey
 * @property {MutationObserver | null} bodyObs
 */

/** @type {WikiViewState | null} */
let view = null;

/** Previous "graph is the visible canvas content" value — auto-expand fires
 *  on the FALSE→TRUE transition only (可见期间手动收起 → 安静，R5/R6/R9). */
let sideWasVisible = false;

/** Open (or focus) the wiki graph tab and render it. */
export async function openWikiGraphTab(focusNodeId = '') {
  const canvas = await import('./canvas.js');
  const entry = canvas.openTab(TAB_ID, t('wiki.tabTitle'), { type: TAB_ID, closable: true });
  if (!entry) return;
  if (focusNodeId) {
    // Tab already live: select + center + detail directly (the renderInto
    // idempotence guard would otherwise swallow the focus request).
    if (view && view.pane === entry.paneEl && view.canvas && view.canvas.isConnected) {
      const n = view.byId.get(focusNodeId);
      if (n) { select(n, true); return; }
      view.focusId = focusNodeId; // not in the loaded data — retry after refetch
      rebuildSim();
      return;
    }
    pendingFocus = focusNodeId; // fresh/restore render applies it in rebuildSim
  }
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

// Restore dispatch/load race (R4 hardening): canvas.js openPanelTab dispatches
// canvas-tab-restore synchronously during boot, while this module may still be
// loading (main.js imports it dynamically) — a fast server-archive fetch can
// win that race and the dispatch fires with no listener yet. Reconcile once at
// eval: an already-restored ACTIVE wiki pane renders exactly as the missed
// event would have. renderInto is idempotent, so the listener path stays safe
// when the event order is the other way around.
import('./canvas.js').then(({ getTabPane }) => {
  const pane = getTabPane(TAB_ID);
  if (pane && pane.classList.contains('active')) renderInto(pane);
}).catch(() => { /* canvas unavailable (embedded/test) — skip */ });

// Tab close: stop the animation loop, drop every document-level listener and
// hand the left column back to the file explorer — a closed tab must not keep
// a rAF cycle (and its CPU) alive, nor the control face.
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  if (e.detail && e.detail.id === TAB_ID) teardown();
});

/** Tab switching: repaint + side-face sync on activation changes (the hidden
 *  canvas backing store is dropped while the tab is inactive). */
document.addEventListener('canvas-tab-switched', (/** @type {CustomEvent} */ e) => {
  if (!e.detail) return;
  if (e.detail.id === TAB_ID) repaintOnActivate();
  if (view) syncSidePanel(e.detail.id === TAB_ID);
});

// Canvas panel open/close rides the body class (closeCanvas keeps tabs) —
// observe it so closing the canvas hands the left column back to the explorer
// even though activeTabId still points at the graph.
function startBodyObserver() {
  if (!view || view.bodyObs || !window.MutationObserver) return;
  view.bodyObs = new MutationObserver(() => {
    if (!view) return;
    syncSidePanel(view.pane.classList.contains('active') &&
      document.body.classList.contains('canvas-open'));
  });
  view.bodyObs.observe(document.body, { attributes: true, attributeFilter: ['class'] });
}

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
    if (pendingFocus) { view.focusId = pendingFocus; pendingFocus = ''; rebuildSim(); }
    return;
  }
  teardown();
  pane.innerHTML = '';
  pane.classList.add('wiki-graph-pane');

  // ── Canvas face: graph body + hover tooltip. Nothing else (作者令). ──
  const canvasEl = document.createElement('canvas');
  canvasEl.className = 'wiki-canvas';

  // Hover tooltip — pane-local (position:absolute in a relative+hidden pane),
  // so it can never surface over the conversation UI.
  const tip = document.createElement('div');
  tip.className = 'wg-tip';
  tip.setAttribute('role', 'tooltip');
  tip.innerHTML = '<div class="tt"></div><div class="ts"></div><div class="td"></div>';

  // Compact in-pane bar for the MOBILE layer only (no left column on phones;
  // CSS keeps it display:none everywhere else — desktop canvas stays pure).
  const panebar = buildPaneBar();

  pane.append(canvasEl, tip, panebar);

  view = { pane, canvas: canvasEl, ctx: canvasEl.getContext('2d'),
           side: ensureSidePanel(), panebar,
           data: null, sim: null,
           raf: 0, focusId: pendingFocus || '', byId: new Map(),
           hover: null, selected: null,
           vx: 0, vy: 0, scale: 1,
           dragging: null, panning: false, lastPointer: null,
           resizeObs: null, W: 0, H: 0, colors: null,
           filter: '', dirFilter: '', dirs: [], unresolved: [], projectErrors: [],
           themeMQ: null, onThemeChange: null, onKey: null, bodyObs: null };
  pendingFocus = '';

  bindCanvas(canvasEl);
  if (window.ResizeObserver) {
    view.resizeObs = new ResizeObserver(() => resizeCanvas());
    view.resizeObs.observe(canvasEl);
  }
  // Theme flip (system light/dark) mid-session: re-read the graphite palette
  // from the pane's CSS variables and repaint on the next frame.
  view.themeMQ = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  view.onThemeChange = () => { if (view) { readColors(); draw(); } };
  if (view.themeMQ && view.themeMQ.addEventListener) view.themeMQ.addEventListener('change', view.onThemeChange);

  // Esc — layer-by-layer exit (selection → search filter → directory filter).
  view.onKey = (/** @type {KeyboardEvent} */ e) => {
    if (!view || e.key !== 'Escape') return;
    if (view.selected) { deselect(); return; }
    if (view.filter) {
      view.filter = '';
      for (const input of searchInputs()) input.value = '';
      applyFilter(); draw();
      return;
    }
    if (view.dirFilter) { view.dirFilter = ''; renderChips(); applyFilter(); draw(); }
  };
  document.addEventListener('keydown', view.onKey);
  startBodyObserver();

  await loadData(false);
  rebuildSim();

  // Fresh render lands with the pane active (open) or not (background restore)
  // — publish the side-face state either way; the transition guard inside
  // keeps a manually collapsed sidebar quiet.
  syncSidePanel(view.pane.classList.contains('active') &&
    document.body.classList.contains('canvas-open'));
}

function teardown() {
  if (!view) return;
  if (view.raf) cancelAnimationFrame(view.raf);
  if (view.resizeObs) view.resizeObs.disconnect();
  if (view.themeMQ && view.onThemeChange && view.themeMQ.removeEventListener) {
    view.themeMQ.removeEventListener('change', view.onThemeChange);
  }
  if (view.onKey) document.removeEventListener('keydown', view.onKey);
  if (view.bodyObs) { view.bodyObs.disconnect(); view.bodyObs = null; }
  // Hand the left column back to the file explorer.
  document.body.classList.remove('wg-side-active');
  const side = document.getElementById('wiki-graph-side');
  if (side) side.remove();
  sideWasVisible = false;
  view = null;
}

// ── 左栏控制面（#wiki-graph-side — #explorer-section 的兄弟节）───────────

/** Build (once) the graph control face inside the files sidebar panel.
 *  Returns the section element, or null when the panel is unavailable
 *  (embedded/test contexts). */
function ensureSidePanel() {
  const panel = document.getElementById('panel-sessions');
  if (!panel) return null;
  let side = document.getElementById('wiki-graph-side');
  if (side) return side;
  side = document.createElement('section');
  side.id = 'wiki-graph-side';
  side.className = 'wg-side';
  side.setAttribute('aria-label', t('wiki.tabTitle'));

  const head = document.createElement('div');
  head.className = 'wg-side-head';
  const title = document.createElement('span');
  title.className = 'wg-side-title';
  title.textContent = t('wiki.tabTitle');
  head.appendChild(title);

  const detail = document.createElement('div');
  detail.className = 'wg-detail';
  detail.appendChild(buildDetailSkeleton());

  const broken = document.createElement('div');
  broken.className = 'wg-broken';
  broken.innerHTML = '<h3></h3><div class="rows"></div>';
  broken.querySelector('h3').textContent = t('wiki.brokenTitle');

  const stats = document.createElement('div');
  stats.className = 'wg-stats';

  side.append(head, buildSearchBox(), buildChipsGroup(), detail, broken, stats);
  panel.appendChild(side);
  return side;
}

/** Search box (shared builder — side face and mobile panebar). */
function buildSearchBox() {
  const wrap = document.createElement('div');
  wrap.className = 'wg-search';
  wrap.innerHTML =
    '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>';
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'wg-search-input';
  input.placeholder = t('wiki.searchPlaceholder');
  input.setAttribute('aria-label', t('wiki.searchPlaceholder'));
  input.addEventListener('input', () => {
    if (!view) return;
    view.filter = input.value.trim().toLowerCase();
    for (const other of searchInputs()) { if (other !== input) other.value = input.value; }
    applyFilter();
    draw();
  });
  wrap.appendChild(input);
  return wrap;
}

/** Directory chips group (shared builder). One-click single-select filter. */
function buildChipsGroup() {
  const chips = document.createElement('div');
  chips.className = 'wg-chips';
  chips.setAttribute('role', 'group');
  return chips;
}

/** Detail card skeleton (徽标/摘要/目录/引出/被引/路径/打开文档) — the sidebar
 *  replacement of the old floating preview card. */
function buildDetailSkeleton() {
  const frag = document.createElement('div');
  frag.className = 'wg-detail-card';
  const empty = document.createElement('div');
  empty.className = 'wg-detail-empty';
  empty.textContent = t('wiki.detailEmpty');
  const body = document.createElement('div');
  body.className = 'wg-detail-body';
  body.hidden = true;
  body.innerHTML =
    '<header><h2></h2>' +
    '<button class="close" type="button" aria-label="' + t('wiki.closePreview') + '">×</button></header>' +
    '<div class="badges"></div><p class="summary"></p>' +
    '<h3></h3><ul class="toc"></ul>' +
    '<h3></h3><ul class="out"></ul>' +
    '<h3></h3><ul class="in"></ul>' +
    '<div class="path"></div>' +
    '<button class="openbtn" type="button"></button>';
  const h3s = body.querySelectorAll('h3');
  h3s[0].textContent = t('wiki.secToc');
  h3s[1].textContent = t('wiki.secOut');
  h3s[2].textContent = t('wiki.secIn');
  body.querySelector('.openbtn').textContent = t('wiki.openDoc');
  body.querySelector('.close').addEventListener('click', () => deselect());
  body.querySelector('.openbtn').addEventListener('click', () => {
    if (view && view.selected) void openDoc(view.selected.ref);
  });
  frag.append(empty, body);
  return frag;
}

/** Compact in-pane control bar — MOBILE LAYER ONLY (display:none elsewhere).
 *  Search + chips + stats; the phone shell hosts the pane full-page and has
 *  no left column, so the bar rides inside the pane. */
function buildPaneBar() {
  const bar = document.createElement('div');
  bar.className = 'wg-panebar';
  const stats = document.createElement('div');
  stats.className = 'wg-stats';
  bar.append(buildSearchBox(), buildChipsGroup(), stats);
  return bar;
}

/** Every live search input (side face + panebar). @returns {HTMLInputElement[]} */
function searchInputs() {
  const out = /** @type {HTMLInputElement[]} */ ([]);
  if (view && view.side) out.push(/** @type {HTMLInputElement|null} */ (view.side.querySelector('.wg-search-input')));
  if (view && view.panebar) out.push(/** @type {HTMLInputElement|null} */ (view.panebar.querySelector('.wg-search-input')));
  return out.filter(Boolean);
}

/** Every live chips container. */
function chipsContainers() {
  const out = [];
  if (view && view.side) out.push(view.side.querySelector('.wg-chips'));
  if (view && view.panebar) out.push(view.panebar.querySelector('.wg-chips'));
  return out.filter(Boolean);
}

/** Every live detail-card root (currently the side face only). */
function detailRoots() {
  const out = [];
  if (view && view.side) out.push(view.side.querySelector('.wg-detail'));
  return out.filter(Boolean);
}

/** Every live stats line. */
function statsContainers() {
  const out = [];
  if (view && view.side) out.push(view.side.querySelector('.wg-stats'));
  if (view && view.panebar) out.push(view.panebar.querySelector('.wg-stats'));
  return out.filter(Boolean);
}

// ── Side-face ↔ canvas visibility sync ────────────────────────────────────

/** Publish "the wiki graph IS the visible canvas content" onto the left
 *  column: body.wg-side-active swaps the explorer for the control face.
 *  Auto-expand fires on the FALSE→TRUE transition only (R5/R6/R9 semantics —
 *  see module header); expanding walks the persisted showSidePanel API so no
 *  new localStorage key appears. Mobile viewport never auto-expands (drawer
 *  exclusivity — whoever expands second wins). */
function syncSidePanel(visible) {
  const now = !!visible && !!view &&
    document.body.classList.contains('canvas-open') &&
    view.pane.isConnected;
  document.body.classList.toggle('wg-side-active', now);
  if (now && !sideWasVisible && !isMobileViewport()) {
    import('./activityBar.js').then((m) => m.showSidePanel('files'))
      .catch(() => { /* activity bar unavailable — face still swaps */ });
  }
  sideWasVisible = now;
}

function isMobileViewport() {
  return !!(window.matchMedia && window.matchMedia(MOBILE_MQ).matches);
}

/** Selection from the control face must land on a visible canvas (mobile
 *  drawer case: expanding the sidebar closed the canvas — reopen it). */
function ensureCanvasVisible() {
  if (document.body.classList.contains('canvas-open')) return;
  import('./canvas.js').then((m) => m.openCanvas()).catch(() => { /* noop */ });
}

// ── Data ─────────────────────────────────────────────────────────────────

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

// ── Simulation model (design §7: the force kernel survives the redesign) ──

/** Deterministic PRNG — the dir-cluster seed must not jitter between refreshes. */
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Directory facet of a node — the rel's first segment ('.' at the vault
 *  root). Client-side derivation, per design §5 (no backend change). */
function dirOfRel(rel) {
  const i = rel.indexOf('/');
  return i < 0 ? '.' : rel.slice(0, i);
}

function radiusOf(n) {
  if (n.isAttach) return 3.5;
  return 5 + Math.min(6, Math.sqrt(n.degree) * 1.9); // design §4: 5–11px
}

function rebuildSim() {
  if (!view || !view.data) return;
  const data = view.data;
  const W = view.W || view.canvas.clientWidth || 800;
  const H = view.H || view.canvas.clientHeight || 600;
  const allById = new Map((data.nodes || []).map(n => [n.id, n]));

  // Broken references never become nodes (design §3): stubs are dropped and
  // their edges are counted into the stats line instead.
  const nodes = /** @type {WgNode[]} */ ((data.nodes || [])
    .filter(n => !n.stub)
    .map(n => ({
      ref: n,
      id: n.id,
      label: n.title || (n.rel || '').split('/').pop(),
      dir: dirOfRel(n.rel || ''),
      isAttach: n.kind === 'attachment',
      x: 0, y: 0, vx: 0, vy: 0,
      degree: 0, rank: 0, out: [], inc: [], dimmed: false,
    })));
  const byId = new Map(nodes.map(n => [n.id, n]));

  // Deduplicated reference edges between real nodes.
  const seen = new Set();
  const edges = [];
  for (const e of data.edges || []) {
    const a = byId.get(e.from), b = byId.get(e.to);
    if (!a || !b || a === b) continue;
    const key = e.from + '|' + e.to;
    if (seen.has(key)) continue;
    seen.add(key);
    edges.push({ a, b });
  }
  for (const e of edges) { e.a.out.push(e.b); e.b.inc.push(e.a); }
  for (const n of nodes) n.degree = n.out.length + n.inc.length;

  // Unresolved references (edge targets that are stubs) — counted, listed in
  // the broken section, never drawn. Project-level stubs surface as vault
  // errors in the same section.
  const unresolvedSeen = new Set();
  const unresolved = [];
  for (const e of data.edges || []) {
    const from = allById.get(e.from), to = allById.get(e.to);
    if (!from || !to || !to.stub) continue;
    const key = e.from + '|' + e.to;
    if (unresolvedSeen.has(key)) continue;
    unresolvedSeen.add(key);
    unresolved.push({ from: from.rel || from.title || e.from, target: to.rel || to.title || e.to });
  }
  view.unresolved = unresolved;
  view.projectErrors = (data.projects || [])
    .filter(p => p.stub || p.error)
    .map(p => ({ name: p.name, error: p.error || '', stub: !!p.stub }));

  // Hub ranks (代审整改): degree-descending order drives the default label set.
  [...nodes].sort((a, b) => b.degree - a.degree).forEach((n, i) => { n.rank = i; });

  view.sim = { nodes, edges, alpha: 1 };
  view.byId = byId;
  view.dirs = [...new Set(nodes.map(n => n.dir))].sort();

  // Dir-cluster seed (sample layout): same-directory nodes start near the
  // same angle, so clusters read immediately while the forces settle.
  const rnd = mulberry32(42);
  const dirIdx = new Map(view.dirs.map((d, i) => [d, i]));
  const R = 0.25 * Math.min(W, H);
  for (const n of nodes) {
    const ang = ((dirIdx.get(n.dir) + 0.5) / Math.max(1, view.dirs.length)) * Math.PI * 2;
    n.x = W / 2 + Math.cos(ang) * R + (rnd() - 0.5) * 90;
    n.y = H / 2 + Math.sin(ang) * R + (rnd() - 0.5) * 90;
  }

  readColors();
  view.scale = 1;
  view.vx = 0; view.vy = 0;
  view.hover = null;
  hideTip();

  // Preserve selection across refetches by id (the old node objects die here).
  const selId = view.selected ? view.selected.id : '';
  view.selected = (selId && byId.get(selId)) || null;

  renderChips();
  renderStats();
  renderBroken();
  applyFilter();
  renderDetailAll();

  if (view.focusId && byId.has(view.focusId)) {
    const n = byId.get(view.focusId);
    view.focusId = ''; // one-shot: a data refresh must not re-center/re-open
    select(n, true);
  }
  startLoop();
}

function readColors() {
  if (!view) return;
  let cs = null;
  try { cs = getComputedStyle(view.pane); } catch (_) { /* detached pane */ }
  const v = (name, fb) => ((cs && cs.getPropertyValue(name)) || '').trim() || fb;
  view.colors = {
    ink: v('--wg-foreground', '#141416'),
    muted: v('--wg-muted', '#6f727b'),
    line: v('--wg-border', '#e8e9ec'),
  };
}

function renderChips() {
  if (!view) return;
  for (const chips of chipsContainers()) {
    chips.textContent = '';
    const mk = (label, value) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'wg-chip' + (view.dirFilter === value ? ' on' : '');
      b.textContent = label;
      b.addEventListener('click', () => {
        if (!view) return;
        view.dirFilter = view.dirFilter === value ? '' : value;
        renderChips();
        applyFilter();
        draw();
      });
      return b;
    };
    chips.appendChild(mk(t('wiki.chipAll'), ''));
    for (const d of view.dirs) chips.appendChild(mk(d === '.' ? t('wiki.chipRoot') : d + '/', d));
  }
}

/** Stats line (bottom of the control face): N 篇文档 · N 条引用 · N 条未解析. */
function renderStats() {
  if (!view) return;
  const d = view.data || {};
  const text = t('wiki.infoDocs')
    .replace('{docs}', String((d.nodes || []).filter(n => !n.stub).length))
    .replace('{links}', String(view.sim ? view.sim.edges.length : 0)) +
    t('wiki.infoUnresolved').replace('{n}', String(view.unresolved.length));
  for (const el of statsContainers()) el.textContent = text;
}

/** Broken-reference section (fixed block, not an overlay): unreadable vaults
 *  first, then the unresolved reference list. A row re-selects its SOURCE
 *  document in the canvas. Hidden entirely when there is nothing broken. */
function renderBroken() {
  if (!view || !view.side) return;
  const broken = view.side.querySelector('.wg-broken');
  if (!broken) return;
  const rows = broken.querySelector('.rows');
  rows.textContent = '';
  for (const p of view.projectErrors.slice(0, 8)) {
    const row = document.createElement('div');
    row.className = 'row';
    row.textContent = (p.stub && !p.error)
      ? t('wiki.brokenVault').replace('{project}', p.name)
      : t('wiki.brokenProject').replace('{project}', p.name).replace('{error}', p.error);
    rows.appendChild(row);
  }
  view.unresolved.slice(0, 60).forEach(({ from, target }, i) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'row';
    const b = document.createElement('b');
    b.textContent = from + ' → ';
    row.appendChild(b);
    row.appendChild(document.createTextNode(target));
    row.addEventListener('click', () => {
      const srcNode = [...view.byId.values()].find(nn => (nn.ref.rel || '') === from);
      if (srcNode) select(srcNode, true);
    });
    rows.appendChild(row);
    if (i === 59) {
      const more = document.createElement('div');
      more.className = 'row more';
      more.textContent = '…';
      rows.appendChild(more);
    }
  });
  broken.classList.toggle('none', !rows.childElementCount);
}

function applyFilter() {
  if (!view || !view.sim) return;
  const q = view.filter;
  const pass = filterPass();
  view.sim.nodes.forEach(n => { n.dimmed = !pass(n); });
}

function filterPass() {
  const q = view ? view.filter : '';
  const dir = view ? view.dirFilter : '';
  return (/** @type {WgNode} */ n) => {
    if (dir && n.dir !== dir) return false;
    if (q) {
      const rel = (n.ref.rel || '').toLowerCase();
      const proj = (n.ref.project || '').toLowerCase();
      if (!(n.label.toLowerCase().includes(q) || rel.includes(q) || proj.includes(q))) return false;
    }
    return true;
  };
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
    const dx = e.b.x - e.a.x, dy = e.b.y - e.a.y;
    const d = Math.max(1, Math.hypot(dx, dy));
    const target = 110;
    const f = (d - target) * 0.015 * kAlpha;
    const fx = dx / d * f, fy = dy / d * f;
    e.a.vx += fx; e.a.vy += fy;
    e.b.vx -= fx; e.b.vy -= fy;
  }
  // Gravity toward center + integrate.
  const drag = 0.85;
  for (const n of nodes) {
    n.vx += (W / 2 - n.x) * 0.0015 * kAlpha;
    n.vy += (H / 2 - n.y) * 0.0015 * kAlpha;
    if (n === view.dragging) { n.vx = 0; n.vy = 0; continue; }
    n.vx *= drag; n.vy *= drag;
    n.x += n.vx; n.y += n.vy;
  }
  sim.alpha *= 0.95; // settle ≈1.1s (design §4: convergence <1.2s, then stop)
}

function startLoop() {
  if (!view) return;
  cancelAnimationFrame(view.raf);
  const loop = () => {
    if (!view || !view.sim) return;
    tick();
    draw();
    // Idle stop: the layout settled and nobody is interacting — zero steady-
    // state CPU (design §4). Any pointer/zoom/filter interaction restarts it.
    if (view.sim.alpha < 0.02 && !view.hover && !view.dragging && !view.panning) {
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

/** One-frame repaint after the wiki-graph tab is re-activated
 *  (canvas-tab-switched). Scheduled on the NEXT animation frame so the
 *  ResizeObserver has already restored the bitmap size — observer callbacks
 *  run before rAF callbacks within a frame, so a synchronous draw here would
 *  be wiped by the observer's resize. Skipped when the loop is already
 *  running (it repaints every frame anyway). */
function repaintOnActivate() {
  if (!view || !view.canvas || !view.canvas.isConnected) return;
  view.hover = null;
  hideTip();
  requestAnimationFrame(() => {
    if (!view || !view.canvas || !view.canvas.isConnected || view.raf) return;
    resizeCanvas();
    draw();
  });
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

function neighborhood(node) {
  const set = new Set([node]);
  for (const m of node.out) set.add(m);
  for (const m of node.inc) set.add(m);
  return set;
}

/** How many nodes carry a default (non-hover) label at this zoom — the
 *  代审整改 ramp: LABEL_HUBS at ≤100%, everything at LABEL_ALL_AT_SCALE. */
function labelCountAt(scale) {
  if (!view || !view.sim) return LABEL_HUBS;
  const total = view.sim.nodes.length;
  if (scale >= LABEL_ALL_AT_SCALE || total <= LABEL_HUBS) return total;
  const frac = Math.max(0, (scale - 1) / (LABEL_ALL_AT_SCALE - 1));
  return Math.min(total, Math.round(LABEL_HUBS + frac * (total - LABEL_HUBS)));
}

function draw() {
  if (!view || !view.ctx) return;
  const { ctx, sim } = view;
  if (view.canvas.width === 1) resizeCanvas();
  if (!sim) return;
  const W = view.W, H = view.H;
  if (!view.colors) readColors();
  const C = view.colors;
  ctx.clearRect(0, 0, W, H);
  ctx.save();
  ctx.translate(W / 2 + view.vx, H / 2 + view.vy);
  ctx.scale(view.scale, view.scale);
  ctx.translate(-W / 2, -H / 2);

  const focus = view.hover || view.selected;
  const near = focus ? neighborhood(focus) : null;
  const pass = filterPass();
  const labelCount = labelCountAt(view.scale);

  // Edges — 1px straight muted lines (design §4: no bows, no accent hue);
  // the focused neighborhood stays lit, everything else recedes.
  for (const e of sim.edges) {
    const hl = near && (e.a === focus || e.b === focus);
    const dim = !pass(e.a) || !pass(e.b) || (near && !(near.has(e.a) && near.has(e.b)));
    ctx.strokeStyle = C.muted;
    ctx.globalAlpha = hl ? 0.85 : (dim ? 0.05 : 0.22);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(e.a.x, e.a.y);
    ctx.lineTo(e.b.x, e.b.y);
    ctx.stroke();
  }

  // Nodes — solid graphite dots; degree → radius and ink depth (design §4).
  for (const n of sim.nodes) {
    const r = radiusOf(n);
    const dim = n.dimmed || (near && !near.has(n));
    ctx.globalAlpha = dim ? 0.12 : 1;
    const shade = n.isAttach ? 0 : Math.min(1, n.degree / 8);
    ctx.fillStyle = shade > 0.45 ? C.ink : C.muted;
    ctx.beginPath();
    ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
    ctx.fill();
    if (n === view.selected || n === view.hover) {
      ctx.strokeStyle = C.ink;
      ctx.globalAlpha = dim ? 0.2 : 0.9;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 3.5, 0, Math.PI * 2);
      ctx.stroke();
      ctx.globalAlpha = dim ? 0.12 : 1;
    }
    // Label — hub-density rule (module header): hovered/selected always,
    // their neighborhood when focused, all matches while a filter is active,
    // otherwise only the top-`labelCount` hubs. Attachments label on
    // hover/selection only.
    let labelAlpha = 0;
    if (!dim) {
      const isFocusNode = n === view.hover || n === view.selected;
      if (isFocusNode) labelAlpha = 1;
      else if (near && near.has(n)) labelAlpha = 0.62;
      else if (view.filter || view.dirFilter) labelAlpha = 0.62;
      else if (!n.isAttach && n.rank < labelCount) labelAlpha = 0.62;
    }
    if (labelAlpha > 0) {
      ctx.globalAlpha = labelAlpha;
      ctx.fillStyle = C.ink;
      ctx.font = (labelAlpha === 1 ? '500 ' : '400 ') + '11px -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif';
      ctx.textAlign = 'center';
      const label = n.label.length > LABEL_MAX_CHARS ? n.label.slice(0, LABEL_MAX_CHARS - 1) + '…' : n.label;
      ctx.fillText(label, n.x, n.y + r + 13);
    }
  }
  ctx.globalAlpha = 1;
  ctx.restore();
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
    if (d < radiusOf(n) + 5 && d < bestD) { best = n; bestD = d; }
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
    if (hit) { view.dragging = hit; canvasEl.classList.add('grabbing'); }
    else { view.panning = true; canvasEl.classList.add('grabbing'); }
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
      if (!view.raf) draw();
    } else {
      const hit = pick(px, py);
      if (hit !== view.hover) {
        view.hover = hit;
        canvasEl.classList.toggle('hovering', !!hit);
        if (!view.raf) draw();
      }
      if (hit) showTip(hit, px, py);
      else hideTip();
    }
    view.lastPointer = { px, py };
  });
  const endDrag = (e) => {
    if (!view) return;
    const wasDragging = view.dragging;
    const wasPanning = view.panning;
    view.dragging = null;
    view.panning = false;
    view.lastPointer = null;
    canvasEl.classList.remove('grabbing');
    hideTip();
    if (moved) return;
    if (wasDragging) { select(wasDragging, false); return; }
    if (!wasPanning) return;
    const rect = canvasEl.getBoundingClientRect();
    const hit = pick(e.clientX - rect.left, e.clientY - rect.top);
    if (hit) select(hit, false);
    else deselect();
  };
  canvasEl.addEventListener('pointerup', endDrag);
  canvasEl.addEventListener('pointercancel', endDrag);
  canvasEl.addEventListener('pointerleave', () => {
    if (!view) return;
    view.hover = null;
    hideTip();
    if (!view.raf) draw();
  });
  canvasEl.addEventListener('wheel', (e) => {
    if (!view) return;
    e.preventDefault();
    wake(0.15);
    const rect = canvasEl.getBoundingClientRect();
    zoomAt(e.clientX - rect.left, e.clientY - rect.top, e.deltaY < 0 ? 1.12 : 1 / 1.12);
    draw();
  }, { passive: false });
}

/** Zoom anchored at pane-local (px,py), cursor-anchored (design §3). */
function zoomAt(px, py, factor) {
  if (!view) return;
  const next = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, view.scale * factor));
  const k = next / view.scale;
  view.vx = px - (px - view.vx) * k;
  view.vy = py - (py - view.vy) * k;
  view.scale = next;
}

// ── Tooltip / detail card ─────────────────────────────────────

function dirLabel(dir) { return dir === '.' ? t('wiki.chipRoot') : dir; }

function showTip(node, px, py) {
  if (!view || !view.pane) return;
  const tip = /** @type {HTMLElement|null} */ (view.pane.querySelector('.wg-tip'));
  if (!tip) return;
  const d = node.ref;
  tip.querySelector('.tt').textContent = d.title || d.rel || '';
  tip.querySelector('.ts').textContent = d.summary
    || (node.isAttach ? (d.rel || '') : t('wiki.noSummary'));
  tip.querySelector('.td').textContent = dirLabel(node.dir) + ' · ' + t('wiki.tipDegree')
    .replace('{in}', String(node.inc.length))
    .replace('{out}', String(node.out.length));
  tip.style.display = 'block';
  // Clamp against the MEASURED tip box so it stays fully inside the pane.
  const maxW = 330;
  let x = px + 14, y = py + 14;
  if (x + maxW > view.pane.clientWidth) x = Math.max(4, px - maxW - 8);
  if (y + 110 > view.pane.clientHeight) y = Math.max(4, py - 110);
  tip.style.left = x + 'px';
  tip.style.top = y + 'px';
}

function hideTip() {
  if (!view || !view.pane) return;
  const tip = /** @type {HTMLElement|null} */ (view.pane.querySelector('.wg-tip'));
  if (tip) tip.style.display = 'none';
}

/** Fill one detail card for a node (徽标/摘要/目录 ≤5/引出 ≤5/被引 ≤5/路径). */
function fillDetailCard(root, n) {
  const emptyEl = root.querySelector('.wg-detail-empty');
  const card = root.querySelector('.wg-detail-body');
  if (!n) {
    if (emptyEl) emptyEl.hidden = false;
    if (card) card.hidden = true;
    return;
  }
  if (emptyEl) emptyEl.hidden = true;
  if (card) card.hidden = false;
  const d = n.ref;
  card.querySelector('h2').textContent = n.label;
  const badges = card.querySelector('.badges');
  badges.textContent = '';
  const badgeTexts = [
    dirLabel(n.dir) + (n.dir === '.' ? '' : '/'),
    d.type || '',
    d.status || '',
    n.isAttach ? t('wiki.kindAttachment') : '',
    (d.device && d.device !== 'self') ? t('wiki.badgeDevice').replace('{device}', d.device) : '',
  ];
  for (const text of badgeTexts) {
    if (!text) continue;
    const s = document.createElement('span');
    s.className = 'badge';
    s.textContent = text;
    badges.appendChild(s);
  }
  card.querySelector('.summary').textContent = d.summary || t('wiki.noSummary');
  const toc = card.querySelector('.toc');
  toc.textContent = '';
  for (const h of (d.headings || []).slice(0, 5)) {
    const li = document.createElement('li');
    li.textContent = h.t || h;
    toc.appendChild(li);
  }
  const tocHead = /** @type {HTMLElement|null} */ (toc.previousElementSibling);
  if (tocHead) tocHead.style.display = toc.children.length ? '' : 'none';
  const refItem = (m) => {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'ref';
    b.textContent = m.label;
    b.addEventListener('click', () => select(m, true));
    li.appendChild(b);
    return li;
  };
  const ghostItem = (text) => {
    const li = document.createElement('li');
    li.className = 'ghost';
    li.textContent = text;
    return li;
  };
  const outL = card.querySelector('.out');
  outL.textContent = '';
  n.out.slice(0, 5).forEach(m => outL.appendChild(refItem(m)));
  if (!n.out.length) outL.appendChild(ghostItem(t('wiki.emptyOut')));
  const inL = card.querySelector('.in');
  inL.textContent = '';
  n.inc.slice(0, 5).forEach(m => inL.appendChild(refItem(m)));
  if (!n.inc.length) inL.appendChild(ghostItem(t('wiki.emptyIn')));
  card.querySelector('.path').textContent = (d.project ? d.project + ' · ' : '') + 'docs/' + (d.rel || '');
}

/** Refresh every detail card from view.selected (canvas click → 左栏详情). */
function renderDetailAll() {
  if (!view) return;
  for (const root of detailRoots()) fillDetailCard(root, view.selected);
}

/** Select a node: refresh the sidebar detail card, optionally center the
 *  canvas on it (左栏词条 → canvas 聚焦). */
function select(n, scroll) {
  if (!view) return;
  view.selected = n;
  renderDetailAll();
  ensureCanvasVisible();
  if (scroll) {
    // Center the node exactly (the control face lives outside the canvas
    // pane — no half-width yield anymore).
    view.vx = view.W / 2 - n.x * view.scale;
    view.vy = view.H / 2 - n.y * view.scale;
  }
  wake(0.15);
  draw();
}

function deselect() {
  if (!view) return;
  view.selected = null;
  renderDetailAll();
  draw();
}

// ── Document open (unchanged channel: every other file open in the product
//    uses the same entries — workspace-open-item / openLocalFileTab) ──

/** Open one graph document through the SAME channel every other file open in
 *  the product uses (workspace-open-item → canvas.js openWorkspaceItem →
 *  Canvas tab): local files ride openLocalFileTab — the entry the markdown
 *  viewer's links use — so tab dedupe/switching behave identically; peer-device
 *  docs are fetched read-only through /api/wiki/remote-doc. Fired by the
 *  detail card's 打开文档 button. @param {any} d graph node ref */
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

// ── Harness probe (read-only) ─────────────────────────────────
// The canvas is ONE bitmap — DOM reads cannot see nodes, so the e2e harness
// reads laid-out coordinates here to drive real pointer events. Exposes
// layout state only; no mutation path.
/** @type {any} */ (window).__wikiGraphProbe = () => view && view.sim ? {
  alpha: view.sim.alpha,
  scale: view.scale,
  vx: view.vx, vy: view.vy, W: view.W, H: view.H,
  nodes: view.sim.nodes.map(n => ({ id: n.id, x: n.x, y: n.y, degree: n.degree, rank: n.rank, dir: n.dir })),
  unresolved: view.unresolved.length,
  filter: view.filter, dirFilter: view.dirFilter,
  selected: view.selected ? view.selected.id : null,
  sideMounted: !!(view.side && view.side.isConnected),
} : null;
