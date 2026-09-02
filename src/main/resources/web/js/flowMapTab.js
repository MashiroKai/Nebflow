// flowMapTab.js — Project Flow Map 渲染（#27 方向调整 + 导航调整：点击 project 不再
// 新开标签页，由 projectTab 在 projects 标签页内就地渲染，本模块提供 renderFlowMapInto
// 渲染管线 + TTL ticker + WS 增量更新；独立 flow-map-* 标签页降级为 legacy 路径，
// 仅供旧标签页恢复）。复用 flow-run 标签页形态 + solar-card 显示设计；
// 关闭标签页不影响项目数据。数据驱动自 §2.2 NodeList 结构；
// 已完成节点 TTL 倒计时到期前端隐藏（数据保留归档）。点击节点卡片打开结果详情 viewer。
//
// 实时更新 + 变化动画（Flow Map 实时性）：WS nodeCreated/Updated/Completed/Removed 的
// payload 是与 NodeList 同构的节点 JSON（后端 NodePayload.buildNodeJson 单序列化点），
// 事件到达时直接并入本地快照 fmByProject，走增量 diff 渲染——新节点淡入、消失节点
// 淡出、连线生长 / 端点跟随重绘、状态色平滑过渡；全程不做整页 innerHTML，已有节点的
// 轨道动画与点击监听保持存活。视图打开（legacy 标签页或 projects 就地视图）状态下
// 收到事件即更新，不切换标签页。fetch 全量快照降级为对账兜底：增量应用后仍安排一次
// 防抖拉取，diff 渲染保证对账只在真实漂移时碰 DOM（对账无跳变）。

import { openTab, getTabPane } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchFlowMap, NODE_STATUS_CLS } from './nodeData.js';

// 布局常量（复用 flowDag 视觉节奏）
// V_SPACING 150 → 180：节点卡片改为按内容伸展后（带 result 摘要的卡片可达 ~134px），
// 150 的层间距只剩 16px 空隙，上下卡片几乎贴住；180 恢复呼吸感。
const NODE_W = 150;
const NODE_H = 108;
const V_SPACING = 180;
const H_SPACING = 210;
const PAD = 70;

// 增量动画节奏：节点位移走 CSS left/top 过渡、边端点跟随走 rAF 插值，两者同曲线
// （easeInOutCubic ≙ cubic-bezier(0.645,0.045,0.355,1)）同时长，视觉上同步滑动。
const MOVE_MS = 400;
const easeInOutCubic = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);

function prefersReducedMotion() {
  return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}

// 每项目一份状态（原来是单例 currentProject/currentFm：开第二个 flow-map 标签页会
// 互相顶掉，刷新恢复后 currentProject 为 null → WS 事件驱动的刷新整个失效）。
/** @type {Map<string, any>} project → 最近一次 NodeList 快照（WS 增量应用的目标） */
const fmByProject = new Map();
/** @type {Map<string, number>} project → 渲染代（丢弃过期 fetch 响应，fetch 对 fetch） */
const seqByProject = new Map();
/** @type {Map<string, number>} project → 快照代：任何缓存写入（fetch 接受 / WS 增量
 *  应用）都 +1。fetch 在途期间代前进 → 该响应相对缓存已旧，丢弃并重新对账——否则
 *  旧快照会把增量应用进来的节点回滚掉（节点闪没了又出现）。 */
const genByProject = new Map();
/** @type {WeakMap<HTMLElement, any>} 渲染容器 → 最近一次渲染的快照（增量 diff 基线） */
const renderedFmByContainer = new WeakMap();
/** @type {Map<string, number>} project → 对账拉取定时器 id */
const reconcileTimers = new Map();
let ttlTimer = null;

function bumpGen(project) {
  genByProject.set(project, (genByProject.get(project) || 0) + 1);
}

/** 当前打开的所有 flow-map 视图 → [{project, pane}]（DOM 即真相，恢复后同样成立）。
 *  包含两类：legacy 独立标签页（data-tab-id^="flow-map-"）与 projects 标签页内
 *  的就地视图（pane.dataset.projectsView === 'flow-map'，由 projectTab 切换）。 */
function openFlowMapPanes() {
  const panes = /** @type {NodeListOf<HTMLElement>} */ (
    document.querySelectorAll('.canvas-tab-pane[data-tab-id^="flow-map-"]')
  );
  const list = Array.from(panes)
    .map((pane) => ({ project: (pane.dataset.tabId || '').slice('flow-map-'.length), pane }))
    .filter((x) => x.project);
  const pp = getTabPane('projects');
  if (pp && pp.dataset.projectsView === 'flow-map' && pp.dataset.flowMapProject) {
    list.push({ project: pp.dataset.flowMapProject, pane: pp });
  }
  return list;
}

// ── 布局：按 out 边算深度层 ────────────────────────────────
function layoutNodes(fm) {
  const nodes = fm?.nodes || [];
  const nodeIds = new Set(nodes.map((n) => n.id));
  const childrenMap = new Map();
  nodes.forEach((n) => {
    if (n.out && n.out !== 'Nebula' && nodeIds.has(n.out)) {
      if (!childrenMap.has(n.id)) childrenMap.set(n.id, []);
      childrenMap.get(n.id).push(n.out);
    }
  });
  const depth = {};
  nodes.forEach((n) => { depth[n.id] = 0; });
  const visited = new Set();
  function visit(id, d) {
    depth[id] = Math.max(depth[id] || 0, d);
    if (visited.has(id)) return;
    visited.add(id);
    (childrenMap.get(id) || []).forEach((to) => visit(to, depth[id] + 1));
  }
  nodes.forEach((n) => {
    const hasIn = (n.in || []).some((x) => nodeIds.has(x));
    if (!hasIn) visit(n.id, 0);
  });
  nodes.forEach((n) => visit(n.id, depth[n.id] || 0));
  const atDepth = {};
  nodes.forEach((n) => {
    const d = depth[n.id] || 0;
    (atDepth[d] = atDepth[d] || []).push(n.id);
  });
  const positions = {};
  const maxAt = Object.values(atDepth).reduce((m, ids) => Math.max(m, ids.length), 0);
  Object.entries(atDepth).forEach(([d, ids]) => {
    const y = Number(d) * V_SPACING;
    const total = (ids.length - 1) * H_SPACING;
    ids.forEach((id, i) => { positions[id] = { x: i * H_SPACING - total / 2, y }; });
  });
  const maxDepth = Math.max(0, ...Object.keys(atDepth).map(Number));
  const width = Math.max((maxAt - 1) * H_SPACING + NODE_W + PAD * 2, 360);
  const height = maxDepth * V_SPACING + PAD * 2;
  return { positions, width, height };
}

// ── 节点卡片（复用 solar 视觉）────────────────────────────
function nodeHtml(n, pos, originX) {
  const st = n.status || 'pending';
  const cls = NODE_STATUS_CLS[st] || 'pending';
  const left = pos.x - NODE_W / 2 + originX;
  const top = pos.y - NODE_H / 2 + PAD;
  const statusIcon = st === 'completed' ? '<span class="solar-node-status ok">✓</span>'
    : st === 'failed' ? '<span class="solar-node-status err">✗</span>'
    : st === 'cancelled' ? '<span class="solar-node-status cancelled">—</span>' : '';
  const worktreeBadge = n.hasWorktree || n.worktree
    ? `<span class="fm-worktree-badge" title="${esc(n.worktree || '')}">wt</span>` : '';
  const ttl = (st === 'completed' || st === 'failed' || st === 'cancelled') && Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0
    ? `<span class="fm-ttl" data-ttl-node="${esc(n.id)}" data-ttl="0">${fmtTtl(n.ttlLeftSec)}</span>` : '';
  const result = n.result
    ? `<div class="fm-result-summary" title="${esc(n.result)}">${esc(n.result.slice(0, 46))}${n.result.length > 46 ? '…' : ''}</div>`
    : (st === 'running' ? `<div class="fm-result-summary running">${esc(t('flowmap.cardRunning'))}</div>` : '');
  return `
    <div class="solar-node fm-node ${cls}" data-node-id="${esc(n.id)}" data-agent="${esc(n.agent)}"
         data-status="${esc(st)}" style="left:${left.toFixed(1)}px;top:${top.toFixed(1)}px">
      <div class="solar-orbit">
        <div class="solar-ring ring-1"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
        <div class="solar-ring ring-2"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
        <div class="solar-ring ring-3"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
      </div>
      <div class="fm-node-head">${worktreeBadge}${statusIcon}</div>
      <div class="solar-node-label" title="${esc(n.name)}">${esc(n.name)}</div>
      <div class="solar-node-sub">${esc(n.agent)}${ttl ? ' ' + ttl : ''}</div>
      ${st === 'pending' && (n.in || []).length > 1 ? `<div class="fm-barrier-hint">barrier ×${(n.in || []).length}</div>` : ''}
      ${result}
    </div>`;
}

function fmtTtl(sec) {
  return `⏱ ${Math.max(0, Math.ceil(sec))}s`;
}

// ── 边（SVG，复用 .flow-edge）─────────────────────────────
// 边的语义按 §2.7 的投递状态分三档，而不是 flow-run 的"正在流动"动画：
//   delivered  上游 completed → 结果已沿这条边投递（实线、加重）
//   inflight   上游 running   → 结果尚未产生（虚线行军蚁 = 等这条线出结果）
//   idle       其余（wiring/pending 上游）→ 静止细线
function edgeStateOf(n) {
  if (n.status === 'completed') return 'delivered';
  if (n.status === 'running') return 'inflight';
  return 'idle';
}

/** 边只画在「可见」节点之间：终态到期被前端隐藏的节点不再拖出指向空位的幽灵边
 *  （旧版对全量节点画边，隐藏节点的边仍画向其占位坐标）。 */
function collectEdges(fm, positions) {
  const vis = visibleNodes(fm);
  const ids = new Set(vis.map((n) => n.id));
  const edges = new Map();
  for (const n of vis) {
    if (!n.out || n.out === 'Nebula' || !ids.has(n.out)) continue;
    const from = positions[n.id];
    const to = positions[n.out];
    if (!from || !to) continue;
    edges.set(`${n.id}=>${n.out}`, {
      x1: from.x, y1: from.y, x2: to.x, y2: to.y, state: edgeStateOf(n),
    });
  }
  return edges;
}

/** 贝塞尔路径（g 局部坐标）：端点插值动画与全量渲染共用同一形状函数。 */
function edgePathD(p) {
  const midY = (p.y1 + p.y2) / 2;
  return `M ${p.x1.toFixed(1)} ${p.y1.toFixed(1)} C ${p.x1.toFixed(1)} ${midY.toFixed(1)} ${p.x2.toFixed(1)} ${midY.toFixed(1)} ${p.x2.toFixed(1)} ${p.y2.toFixed(1)}`;
}

function edgesSvg(fm, positions, width, height) {
  const paths = Array.from(collectEdges(fm, positions)).map(([id, e]) => `
      <path class="flow-edge fm-edge ${e.state}" data-edge-id="${esc(id)}" d="${edgePathD(e)}"/>
      <circle class="flow-edge-arrow fm-edge-arrow ${e.state}" data-edge-id="${esc(id)}" cx="${e.x2.toFixed(1)}" cy="${e.y2.toFixed(1)}" r="3"/>`).join('');
  return `<svg class="solar-edges" width="${width}" height="${height}"><g transform="translate(${(width / 2).toFixed(1)},${PAD})">${paths}</g></svg>`;
}

// ── 过滤：终态节点 ttlLeftSec<=0 即到期，前端隐藏（数据保留）──
function visibleNodes(fm) {
  return (fm?.nodes || []).filter((n) => {
    const terminal = n.status === 'completed' || n.status === 'failed' || n.status === 'cancelled';
    if (!terminal) return true;
    return !(Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec <= 0);
  });
}

// ── header 摘要：HTML 转义版（innerHTML）与纯文本版（textContent 增量更新）共用 ──
function summaryParts(fm) {
  const nodes = fm?.nodes || [];
  const running = nodes.filter((n) => n.status === 'running').length;
  const failed = nodes.filter((n) => n.status === 'failed').length;
  const pending = nodes.filter((n) => n.status === 'pending').length;
  const completed = nodes.filter((n) => n.status === 'completed').length;
  const parts = [];
  if (running) parts.push(`${running} ${t('flowmap.run')}`);
  if (pending) parts.push(`${pending} ${t('flowmap.wait')}`);
  if (failed) parts.push(`${failed} ${t('flowmap.fail')}`);
  if (completed) parts.push(`${completed} ${t('flowmap.done')}`);
  return parts;
}

function summarizeHeader(fm) {
  const parts = summaryParts(fm);
  return parts.length ? parts.map(esc).join(' · ') : esc(t('flowmap.idle'));
}

function summaryText(fm) {
  const parts = summaryParts(fm);
  return parts.length ? parts.join(' · ') : t('flowmap.idle');
}

// ══ 增量动画（rAF 插值）══════════════════════════════════
// 边端点跟随 + svg g 平移用同一 rAF 泵驱动；节点位移由 CSS left/top 过渡承担。

/** 进行中的边端点插值：edgeId → {path, circle, from, to, start}（重入时从当前插值
 *  位置续跑，快速连发事件不跳变）。 */
const edgeFlights = new Map();
/** 进行中的 g 平移（画布宽度变化 → translate(width/2) 跟随）：{g, from, to, start} */
const gFlights = new Set();
let flightRaf = 0;

function lerpPts(a, b, k) {
  return {
    x1: a.x1 + (b.x1 - a.x1) * k,
    y1: a.y1 + (b.y1 - a.y1) * k,
    x2: a.x2 + (b.x2 - a.x2) * k,
    y2: a.y2 + (b.y2 - a.y2) * k,
  };
}

function edgeFlightNow(f, now) {
  const t = Math.min(1, (now - f.start) / MOVE_MS);
  return lerpPts(f.from, f.to, easeInOutCubic(t));
}

function pumpFlights(now) {
  flightRaf = 0;
  let live = false;
  for (const [id, f] of Array.from(edgeFlights)) {
    if (!f.path.isConnected) { edgeFlights.delete(id); continue; }
    const t = Math.min(1, (now - f.start) / MOVE_MS);
    const p = lerpPts(f.from, f.to, easeInOutCubic(t));
    f.path.setAttribute('d', edgePathD(p));
    if (f.circle) {
      f.circle.setAttribute('cx', p.x2.toFixed(1));
      f.circle.setAttribute('cy', p.y2.toFixed(1));
    }
    if (t >= 1) edgeFlights.delete(id); else live = true;
  }
  for (const f of Array.from(gFlights)) {
    if (!f.g.isConnected) { gFlights.delete(f); continue; }
    const t = Math.min(1, (now - f.start) / MOVE_MS);
    const tx = f.from + (f.to - f.from) * easeInOutCubic(t);
    f.g.setAttribute('transform', `translate(${tx.toFixed(1)},${PAD})`);
    if (t >= 1) gFlights.delete(f); else live = true;
  }
  if (live) flightRaf = requestAnimationFrame(pumpFlights);
}

function ensureFlightPump() {
  if (!flightRaf && (edgeFlights.size + gFlights.size) > 0) {
    flightRaf = requestAnimationFrame(pumpFlights);
  }
}

function startEdgeFlights(list) {
  if (prefersReducedMotion()) {
    for (const it of list) {
      it.path.setAttribute('d', edgePathD(it.to));
      if (it.circle) {
        it.circle.setAttribute('cx', it.to.x2.toFixed(1));
        it.circle.setAttribute('cy', it.to.y2.toFixed(1));
      }
    }
    return;
  }
  const now = performance.now();
  for (const it of list) {
    const cur = edgeFlights.get(it.id);
    const from = cur ? edgeFlightNow(cur, now) : it.from;
    edgeFlights.set(it.id, { path: it.path, circle: it.circle, from, to: it.to, start: now });
  }
  ensureFlightPump();
}

function animateGTranslate(g, fromTx, toTx) {
  const setFinal = () => g.setAttribute('transform', `translate(${toTx.toFixed(1)},${PAD})`);
  if (prefersReducedMotion() || Math.abs(fromTx - toTx) < 0.5) { setFinal(); return; }
  const now = performance.now();
  const prev = Array.from(gFlights).find((f) => f.g === g);
  let from = fromTx;
  if (prev) {
    const t = Math.min(1, (now - prev.start) / MOVE_MS);
    from = prev.from + (prev.to - prev.from) * easeInOutCubic(t); // 续跑：不回到起点
    gFlights.delete(prev);
  }
  gFlights.add({ g, from, to: toTx, start: now });
  ensureFlightPump();
}

/** 新边生长动画：dashoffset 从路径长度过渡到 0（画线生长），结束后清掉内联
 *  dash/animation 让状态档位（inflight 行军蚁等）的类样式接管。 */
function animateEdgeEnter(path, circle) {
  if (prefersReducedMotion()) return;
  let len = 0;
  try { len = path.getTotalLength(); } catch (_) { /* 未渲染（隐藏 pane）时量不到 */ }
  if (!Number.isFinite(len) || len <= 0) return;
  path.style.animation = 'none'; // 压掉 inflight 的 stroke-dashoffset 动画（同属性冲突）
  path.style.strokeDasharray = String(len);
  path.style.strokeDashoffset = String(len);
  path.getBoundingClientRect(); // 强制样式生效，过渡从这里起步
  path.style.transition = 'stroke-dashoffset 0.45s cubic-bezier(0.33, 0, 0.2, 1)';
  path.style.strokeDashoffset = '0';
  if (circle) {
    circle.style.transition = 'opacity 0.4s ease';
    circle.style.opacity = '0';
    requestAnimationFrame(() => { circle.style.opacity = ''; });
  }
  setTimeout(() => {
    path.style.animation = '';
    path.style.strokeDasharray = '';
    path.style.strokeDashoffset = '';
    path.style.transition = '';
    if (circle) circle.style.transition = '';
  }, 520);
}

function animateEdgeExit(path, circle) {
  path.classList.add('fm-edge-exit'); // 标记：后续 diff 不再把它当作可复用元素
  if (prefersReducedMotion()) {
    path.remove();
    if (circle) circle.remove();
    return;
  }
  path.style.transition = 'opacity 0.3s ease';
  path.style.opacity = '0';
  if (circle) {
    circle.style.transition = 'opacity 0.3s ease';
    circle.style.opacity = '0';
  }
  setTimeout(() => {
    path.remove();
    if (circle) circle.remove();
  }, 340);
}

function animateNodeEnter(el) {
  if (prefersReducedMotion()) return;
  el.classList.add('fm-enter');
  el.getBoundingClientRect(); // 强制布局，确保过渡从入场态起步
  requestAnimationFrame(() => el.classList.remove('fm-enter'));
}

function animateNodeExit(el) {
  if (prefersReducedMotion()) { el.remove(); return; }
  el.classList.add('fm-exit');
  setTimeout(() => el.remove(), 380);
}

// ══ 增量 diff 渲染 ═══════════════════════════════════════

/** 节点卡片「内容」签名：参与 nodeHtml 渲染且增量期间会变化的字段。TTL 的数值
 *  刻意不入键——ticker 每秒原地改写文本，重建反而会闪。 */
function nodeContentKey(n) {
  if (!n) return '∅';
  const st = n.status || 'pending';
  const terminal = st === 'completed' || st === 'failed' || st === 'cancelled';
  return [
    st,
    n.name || '',
    n.agent || '',
    n.hasWorktree || n.worktree ? 1 : 0,
    n.worktree || '',
    terminal && Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0 ? 1 : 0,
    st === 'pending' && (n.in || []).length > 1 ? (n.in || []).length : 0,
    n.result || '',
  ].join('|');
}

/** 就地更新节点卡片内容，但保留 .solar-orbit——轨道旋转由 flowAnim.js 的 rAF
 *  以 inline transform 逐帧驱动（状态按 pane|nodeId 键控续接）。移植 orbit 以外
 *  子节点使 .solar-dot-wrap 元素及其 inline transform 原地保留：rAF 的键控状态
 *  st.el === el 继续成立，增量更新零打断、零重挂载（全量重建路径由 flowAnim 的
 *  keyed re-attach 兜底续角度）。根 class/状态属性同步替换，rAF reconcile 据此
 *  感知 running→终态并执行滑行淡出。 */
function transplantNodeContent(el, n, pos, originX) {
  const holder = document.createElement('div');
  holder.innerHTML = nodeHtml(n, pos, originX);
  const fresh = holder.firstElementChild;
  if (!fresh) return;
  const orbit = el.querySelector('.solar-orbit');
  Array.from(el.children).forEach((child) => { if (child !== orbit) child.remove(); });
  Array.from(fresh.children).forEach((child) => {
    if (child.classList && child.classList.contains('solar-orbit')) return;
    el.appendChild(child);
  });
  el.className = fresh.className;
  if (fresh.dataset.status !== undefined) el.dataset.status = fresh.dataset.status;
  if (fresh.dataset.agent !== undefined) el.dataset.agent = fresh.dataset.agent;
}

function applyNodeDiff(canvas, prevFm, fm, positions, width, projectName) {
  const prevById = new Map(visibleNodes(prevFm).map((n) => [n.id, n]));
  const vis = visibleNodes(fm);
  const originX = width / 2;
  const existing = new Map();
  canvas.querySelectorAll('.fm-node').forEach((el) => {
    existing.set(el.getAttribute('data-node-id'), el);
  });
  const seen = new Set();
  for (const n of vis) {
    seen.add(n.id);
    const pos = positions[n.id] || { x: 0, y: 0 };
    const left = `${(pos.x - NODE_W / 2 + originX).toFixed(1)}px`;
    const top = `${(pos.y - NODE_H / 2 + PAD).toFixed(1)}px`;
    let el = existing.get(n.id);
    if (el && el.classList.contains('fm-exit')) {
      el.remove(); // 快速删后又重建：不复用正在退场的元素（退场定时器随后空移除）
      el = null;
    }
    if (!el) {
      const holder = document.createElement('div');
      holder.innerHTML = nodeHtml(n, pos, originX);
      el = holder.firstElementChild;
      if (!el) continue;
      el.addEventListener('click', (e) => {
        e.stopPropagation();
        openNodeDetail(projectName, n.id);
      });
      canvas.appendChild(el);
      animateNodeEnter(el);
      continue;
    }
    // 位移：CSS left/top 过渡平滑滑动（同曲线同时长的边端点插值由 rAF 负责）
    if (el.style.left !== left) el.style.left = left;
    if (el.style.top !== top) el.style.top = top;
    if (nodeContentKey(prevById.get(n.id)) !== nodeContentKey(n)) {
      transplantNodeContent(el, n, pos, originX);
    }
  }
  for (const [id, el] of existing) {
    if (!seen.has(id)) animateNodeExit(el);
  }
}

function applyEdgeDiff(g, prevEdges, edges) {
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const paths = new Map();
  const circles = new Map();
  g.querySelectorAll('path[data-edge-id]').forEach((p) => {
    if (!p.classList.contains('fm-edge-exit')) paths.set(p.getAttribute('data-edge-id'), p);
  });
  g.querySelectorAll('circle[data-edge-id]').forEach((c) => {
    if (!c.classList.contains('fm-edge-exit')) circles.set(c.getAttribute('data-edge-id'), c);
  });
  const flights = [];
  for (const [id, e] of edges) {
    let p = paths.get(id);
    let c = circles.get(id) || null;
    if (!p) {
      p = document.createElementNS(SVG_NS, 'path');
      p.setAttribute('class', `flow-edge fm-edge ${e.state}`);
      p.setAttribute('data-edge-id', id);
      p.setAttribute('d', edgePathD(e));
      g.appendChild(p);
      if (!c) {
        c = document.createElementNS(SVG_NS, 'circle');
        c.setAttribute('class', `flow-edge-arrow fm-edge-arrow ${e.state}`);
        c.setAttribute('data-edge-id', id);
        c.setAttribute('cx', e.x2.toFixed(1));
        c.setAttribute('cy', e.y2.toFixed(1));
        c.setAttribute('r', '3');
        g.appendChild(c);
      }
      animateEdgeEnter(p, c);
      continue;
    }
    const prevE = prevEdges.get(id);
    if (prevE && prevE.state !== e.state) {
      // 状态档位变化：颜色/线型交由 CSS transition 平滑（flowMap.css .fm-edge 过渡）
      p.setAttribute('class', `flow-edge fm-edge ${e.state}`);
      if (c) c.setAttribute('class', `flow-edge-arrow fm-edge-arrow ${e.state}`);
    }
    const moved = !prevE || prevE.x1 !== e.x1 || prevE.y1 !== e.y1
      || prevE.x2 !== e.x2 || prevE.y2 !== e.y2;
    if (!moved) continue;
    if (prevE) {
      flights.push({ id, path: p, circle: c, from: prevE, to: e });
    } else {
      p.setAttribute('d', edgePathD(e));
      if (c) {
        c.setAttribute('cx', e.x2.toFixed(1));
        c.setAttribute('cy', e.y2.toFixed(1));
      }
    }
  }
  for (const [id, p] of paths) {
    if (!edges.has(id)) animateEdgeExit(p, circles.get(id) || null);
  }
  if (flights.length) startEdgeFlights(flights);
}

/** 增量更新已渲染的图（不做 innerHTML 替换）。baseline 是上一帧的快照——通常取
 *  renderedFmByContainer；TTL 到期路径传「回拨 1 秒」的克隆（ticker 原地改写缓存，
 *  基线与新快照同引用时 diff 无从对比，见 tickTtl）。 */
function renderFlowMapDiff(container, baseline, fm, projectName) {
  const canvas = container.querySelector('.solar-canvas');
  if (!canvas) return false;
  const svg = canvas.querySelector('svg.solar-edges');
  const g = svg ? svg.querySelector('g') : null;
  if (!svg || !g) return false;
  if (visibleNodes(fm).length === 0 || visibleNodes(baseline).length === 0) return false; // 图⇄空态走全量
  const prevLayout = layoutNodes(baseline);
  const { positions, width, height } = layoutNodes(fm);

  // 画布尺寸：节点增删导致重排时宽度连续过渡（.flowmap-card .solar-canvas transition），
  // 居中 margin-auto 的偏移随之连续，配合节点 left 过渡整图不跳。
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  animateGTranslate(g, prevLayout.width / 2, width / 2);

  applyNodeDiff(canvas, baseline, fm, positions, width, projectName);
  applyEdgeDiff(g, collectEdges(baseline, prevLayout.positions), collectEdges(fm, positions));

  const summary = container.querySelector('.flowmap-summary');
  const txt = summaryText(fm);
  if (summary && summary.textContent !== txt) summary.textContent = txt;
  container.dataset.fmState = 'nodes';
  container.dataset.fmVisible = String(visibleNodes(fm).length);
  container.dataset.fmTotal = String((fm?.nodes || []).length);
  return true;
}

/** 全量渲染后让整图「长出来」（图⇄空态切换走全量路径时的入场动画）。 */
function animateAllIn(container) {
  if (prefersReducedMotion()) return;
  container.querySelectorAll('.fm-node').forEach((el) => animateNodeEnter(el));
  container.querySelectorAll('path[data-edge-id]').forEach((p) => {
    const next = p.nextElementSibling;
    animateEdgeEnter(p, next && next.tagName.toLowerCase() === 'circle' ? next : null);
  });
}

/** 渲染 Flow Map 内容（header + solar 图 / 空态）进给定容器。导出供 projectTab
 *  在 projects 标签页内就地渲染（同标签页切换视图）。
 *
 *  两条路径：容器里已有渲染好的图（且新旧都有可见节点）→ 增量 diff（带过渡动画，
 *  不重建 DOM）；否则全量 innerHTML（首渲 / 图⇄空态切换）。opts.animateAll 让
 *  全量路径也带入场动画（WS 事件把图从空态唤起时用）。 */
export function renderFlowMap(container, fm, projectName, opts = {}) {
  const total = (fm?.nodes || []).length;
  const nodes = visibleNodes(fm);
  const prev = renderedFmByContainer.get(container);
  if (prev && nodes.length > 0 && visibleNodes(prev).length > 0
      && renderFlowMapDiff(container, prev, fm, projectName)) {
    renderedFmByContainer.set(container, fm);
    if (nodes.some((n) => Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0)) startTtlTicker();
    return;
  }
  const { positions, width, height } = layoutNodes(fm);
  const nodesHtml = nodes.map((n) => nodeHtml(n, positions[n.id] || { x: 0, y: 0 }, width / 2)).join('');
  // 空态三分（旧版一律"暂无节点，项目空闲"，把「未挂载」「TTL 已归档」两种
  // 有数据的情况说成没数据 —— qa 取证「后端有 3 节点、视图显示暂无节点」即此）。
  const emptyMsg = fm?.notMounted ? t('flowmap.notMounted')
    : total > 0 ? t('flowmap.allArchived', { n: total })
    : t('flowmap.empty');
  const state = fm?.notMounted ? 'not-mounted' : nodes.length > 0 ? 'nodes' : total > 0 ? 'archived' : 'empty';
  container.dataset.fmState = state;
  container.dataset.fmVisible = String(nodes.length);
  container.dataset.fmTotal = String(total);
  container.innerHTML = `
    <div class="flowmap-card-header">
      <div class="flowmap-card-title" title="${esc(projectName)}">${esc(projectName)}</div>
      <div class="flowmap-summary">${summarizeHeader(fm)}</div>
    </div>
    ${nodes.length === 0
      ? `<div class="dag-empty"><div class="hint">${esc(emptyMsg)}</div></div>`
      : `<div class="solar-card flowmap-card">
          <div class="solar-scroll">
            <div class="solar-canvas" style="width:${width}px;height:${height}px">
              ${edgesSvg(fm, positions, width, height)}
              ${nodesHtml}
            </div>
          </div>
        </div>`}
    `;
  renderedFmByContainer.set(container, fm);
  bindFlowMapClicks(container, projectName);
  if (opts.animateAll) animateAllIn(container);
  if (nodes.some((n) => Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0)) startTtlTicker();
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(container));
}

function bindFlowMapClicks(container, projectName) {
  container.querySelectorAll('.fm-node').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openNodeDetail(projectName, el.getAttribute('data-node-id') || '');
    });
  });
}

// 节点结果详情：复用 flowViewers 的 overlay viewer（clean overlay 语义）。
function openNodeDetail(projectName, nodeId) {
  const node = fmByProject.get(projectName)?.nodes?.find((n) => n.id === nodeId);
  if (!node) return;
  import('./flowViewers.js').then(({ openNodeResultViewer }) => {
    openNodeResultViewer(esc(node.name), node.agent || '', node.status || '', node.worktree || '', node.result, node.id);
  });
}

/** TTL 倒计时：一个全局 ticker 驱动所有打开的 flow-map 标签页
 *  （旧版 interval 绑定单个 container，第二个标签页会把第一个的 ticker 顶掉）。 */
function startTtlTicker() {
  if (ttlTimer) return;
  ttlTimer = setInterval(tickTtl, 1000);
}

function tickTtl() {
  const panes = openFlowMapPanes();
  if (panes.length === 0) { clearInterval(ttlTimer); ttlTimer = null; return; }
  let anyTtl = false;
  for (const { project, pane } of panes) {
    const fm = fmByProject.get(project);
    // legacy 标签页的滚动体是 .team-scroll；projects 就地视图的滚动体是 .flowmap-view-body。
    const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
    if (!fm || !scroll) continue;
    let dirty = false;
    (fm.nodes || []).forEach((n) => {
      if ((n.status === 'completed' || n.status === 'failed' || n.status === 'cancelled') &&
          Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0) {
        n.ttlLeftSec -= 1;
        if (n.ttlLeftSec <= 0) dirty = true; else anyTtl = true;
      }
    });
    if (dirty) {
      // ticker 原地改写缓存（引用同体），diff 基线取「回拨 1 秒」的克隆才能看出
      // 「刚到期消失」这个变化 → 到期节点走淡出而不是瞬间蒸发。
      const baseline = {
        ...fm,
        nodes: fm.nodes.map((n) => (n.ttlLeftSec === 0 ? { ...n, ttlLeftSec: 1 } : n)),
      };
      if (!renderFlowMapDiff(scroll, baseline, fm, project)) {
        renderFlowMap(scroll, fm, project); // 最后一个可见节点到期 → 空态全量
      } else {
        renderedFmByContainer.set(scroll, fm);
      }
      continue;
    }
    scroll.querySelectorAll('[data-ttl-node]').forEach((el) => {
      const n = (fm.nodes || []).find((x) => x.id === el.getAttribute('data-ttl-node'));
      if (n) el.textContent = fmtTtl(n.ttlLeftSec);
    });
  }
  // 没有任何倒计时中的节点就停表——空转的 1s 定时器没有意义。
  if (!anyTtl) { clearInterval(ttlTimer); ttlTimer = null; }
}

// ── 标签页打开 / 渲染 ────────────────────────────────────

/** 独立标签页打开（legacy 路径：canvas-tab-restore 恢复的旧 flow-map 标签页仍走这里）。
 *  项目面板点击项目不再调此函数——改为 projects 标签页内就地视图（见 projectTab.js）。 */
export function openFlowMapTab(projectName) {
  if (!projectName) return;
  openTab(`flow-map-${projectName}`, projectName, { type: 'flow-map', closable: true, pinned: true });
  renderFlowMapTab(projectName);
}

/** 把某项目的 Flow Map 渲染进任意容器（projects 标签页就地视图与 legacy 标签页共用）。
 *  双代防陈旧：seq 管 fetch 对 fetch 的先后；gen 管「fetch 在途时 WS 增量已写入缓存」
 *  ——此时这份响应相对缓存是旧的，丢弃并重新对账，否则旧快照会回滚增量状态。 */
export function renderFlowMapInto(container, projectName) {
  if (!container) return;
  const seq = (seqByProject.get(projectName) || 0) + 1;
  seqByProject.set(projectName, seq);
  const genAtStart = genByProject.get(projectName) || 0;
  if (!container.querySelector('.flowmap-card-header')) {
    container.dataset.fmState = 'loading';
    container.innerHTML = `<div class="flowmap-loading">${esc(t('flowmap.loading'))}</div>`;
  }
  fetchFlowMap(projectName).then((fm) => {
    if (seqByProject.get(projectName) !== seq || !container.isConnected) return; // 过期响应丢弃
    if ((genByProject.get(projectName) || 0) !== genAtStart) {
      scheduleReconcile(projectName); // 在途期间有增量落进缓存 → 这份旧了，重拉收敛
      return;
    }
    bumpGen(projectName);
    fmByProject.set(projectName, fm);
    renderFlowMap(container, fm, projectName);
  }).catch(() => {
    if (seqByProject.get(projectName) !== seq || !container.isConnected) return;
    container.dataset.fmState = 'error';
    container.innerHTML = `<div class="dag-empty"><div class="hint">${esc(t('flowmap.loadFail'))}</div></div>`;
  });
}

function renderFlowMapTab(projectName) {
  const pane = getTabPane(`flow-map-${projectName}`);
  if (!pane) return;
  ensureFlowCss();
  const scroll = ensureScroll(pane, `flow-map-scroll-${projectName}`);
  renderFlowMapInto(scroll, projectName);
}

function ensureScroll(pane, id) {
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    pane.innerHTML = '';
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.id = id;
    pane.appendChild(scroll);
  }
  return scroll;
}

// 标签页关闭时清理该项目的状态（不影响项目数据，数据在服务端 flow-map.json）。
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  const id = e.detail?.id || '';
  if (typeof id === 'string' && id.startsWith('flow-map-')) {
    const project = id.slice('flow-map-'.length);
    fmByProject.delete(project);
    seqByProject.delete(project);
    genByProject.delete(project);
    const timer = reconcileTimers.get(project);
    if (timer) { clearTimeout(timer); reconcileTimers.delete(project); }
    // 事件在 pane 移除之前派发，故要把正在关的这个排除掉再判断是否还有活的标签页。
    if (openFlowMapPanes().filter((x) => x.project !== project).length === 0) {
      clearInterval(ttlTimer);
      ttlTimer = null;
    }
  }
});

// 标签页恢复：canvas.js 重建 pane 后派发 canvas-tab-restore——没有这条监听，
// 恢复出来的 flow-map 是个空壳（无样式、无内容），且 WS 事件也刷不出来。
window.addEventListener('canvas-tab-restore', (/** @type {CustomEvent} */ e) => {
  const id = e.detail?.id || '';
  if (typeof id === 'string' && id.startsWith('flow-map-')) {
    renderFlowMapTab(id.slice('flow-map-'.length));
  }
});

let refreshTimer = null;
/** WS 兜底全量刷新：只刷 legacy 独立 flow-map 标签页（服务端快照为权威）。
 *  projects 就地视图的兜底在 refreshFlowMapViews——增量路径的渲染由
 *  handleNodeWsEvent 直接 diff 两类视图。 */
export function refreshOpenFlowMap(project) {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    openFlowMapPanes()
      .filter(({ project: p, pane }) =>
        (!project || p === project) && (pane.dataset.tabId || '').startsWith('flow-map-'))
      .forEach(({ project: p }) => renderFlowMapTab(p));
  }, 200);
}

/** 刷新某项目（缺省=全部）所有打开的 Flow Map 视图：legacy 标签页 + projects 就地视图。
 *  断线重连后事件有缺口，全量拉权威快照；renderFlowMap 的 diff 保证快照与现渲染
 *  一致时不碰 DOM（无跳变）。 */
function refreshFlowMapViews(project) {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    openFlowMapPanes()
      .filter(({ project: p, pane }) =>
        (!project || p === project) && (pane.dataset.tabId || '').startsWith('flow-map-'))
      .forEach(({ project: p }) => renderFlowMapTab(p));
    const pp = getTabPane('projects');
    const ppProject = pp && pp.dataset ? pp.dataset.flowMapProject : '';
    if (pp && pp.dataset.projectsView === 'flow-map' && ppProject && (!project || ppProject === project)) {
      const body = pp.querySelector('.flowmap-view-body');
      if (body) renderFlowMapInto(body, ppProject);
    }
  }, 200);
}

// ── WS 事件 → 增量渲染（实时更新核心）─────────────────────

/** 对账拉取：增量应用后安排一次防抖全量拉取。服务端快照权威，事件丢失（重连缺口、
 *  广播时序）或 payload 漂移都在这里收敛；renderFlowMap 的 diff 保证无漂移时零 DOM
 *  变更（对账不可见）。 */
function scheduleReconcile(project) {
  const prev = reconcileTimers.get(project);
  if (prev) clearTimeout(prev);
  reconcileTimers.set(project, setTimeout(() => {
    reconcileTimers.delete(project);
    renderFlowMapTabIfNeeded(project);
  }, 600));
}

function renderFlowMapTabIfNeeded(project) {
  for (const { project: p, pane } of openFlowMapPanes()) {
    if (p !== project) continue;
    const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
    if (scroll) renderFlowMapInto(scroll, project);
  }
}

/** 节点 WS 事件（{type, project, nodeId, node}）处理：
 *  1) 缓存可用（有快照、已挂载、payload 带节点身份）→ 并入快照 + 增量 diff 渲染
 *     （动画），再安排对账拉取兜底；
 *  2) 否则（无缓存 / 未挂载刚激活 / 视图处于非图状态 / 旧帧缺字段）→ 全量拉快照。 */
function handleNodeWsEvent(msg) {
  const type = String(msg?.type || '');
  const project = msg?.project;
  if (!project) { refreshOpenFlowMap(); return; } // 旧帧无 project → 全量兜底
  const panes = openFlowMapPanes().filter((x) => x.project === project);
  if (panes.length === 0) return; // 该项目的图没开着：无事可做（打开时会重新拉取）
  const fm = fmByProject.get(project);
  const node = msg?.node;
  if (fm && !fm.notMounted && node && node.id) {
    const nodes = (fm.nodes || []).filter((n) => n.id !== node.id);
    if (type !== 'nodeRemoved') nodes.push(node);
    const next = { ...fm, nodes, meta: { ...(fm.meta || {}), updatedAt: Date.now() } };
    bumpGen(project);
    fmByProject.set(project, next);
    let applied = true;
    for (const { pane } of panes) {
      const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
      if (!scroll) { applied = false; break; }
      // 视图还没渲染出图（空态/加载态）→ 全量渲染并让新图入场；已在图态 → 纯增量。
      renderFlowMap(scroll, next, project, { animateAll: !scroll.querySelector('.solar-canvas') });
    }
    if (applied) { scheduleReconcile(project); return; }
  }
  refreshFlowMapViews(project);
}

// WS 事件驱动（契约 §2）：四类节点事件全部走增量管线。之前是「任何事件 → 全量
// 重拉 + innerHTML 整页替换」：没有动画、轨道旋转被打断、与其他渲染方互相覆盖。
import { onMessage, onReconnect } from './ws.js';
for (const evt of ['nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved']) {
  onMessage(evt, handleNodeWsEvent);
}
// 断线期间的事件有缺口：重连后对所有打开的 Flow Map 视图拉权威快照（diff 保证
// 与现渲染一致时零 DOM 变更）。
onReconnect(() => refreshFlowMapViews());
