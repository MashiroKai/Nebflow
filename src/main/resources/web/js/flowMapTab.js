// flowMapTab.js — Project Flow Map 标签页（#27 方向调整：点击 project → 标签页打开）。
// 复用 flow-run 标签页形态 + solar-card 显示设计；关闭标签页不影响项目数据。
// 数据驱动自 §2.2 NodeList 结构；已完成节点 TTL 倒计时到期前端隐藏（数据保留归档）。
// 点击节点卡片打开结果详情 viewer。

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

// 每项目一份状态（原来是单例 currentProject/currentFm：开第二个 flow-map 标签页会
// 互相顶掉，刷新恢复后 currentProject 为 null → WS 事件驱动的刷新整个失效）。
/** @type {Map<string, any>} project → 最近一次 NodeList 快照 */
const fmByProject = new Map();
/** @type {Map<string, number>} project → 渲染代（丢弃过期响应） */
const seqByProject = new Map();
let ttlTimer = null;

/** 当前打开的所有 flow-map 标签页 → [{project, pane}]（DOM 即真相，恢复后同样成立）。 */
function openFlowMapPanes() {
  const panes = /** @type {NodeListOf<HTMLElement>} */ (
    document.querySelectorAll('.canvas-tab-pane[data-tab-id^="flow-map-"]')
  );
  return Array.from(panes)
    .map((pane) => ({ project: (pane.dataset.tabId || '').slice('flow-map-'.length), pane }))
    .filter((x) => x.project);
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
// 配置徽标（skill/mcp/preset）：卡片上紧凑展示，完整值放 title，点击卡片在详情里看全。
function cfgBadgesHtml(n) {
  return [['skill', n.skill], ['mcp', n.mcp], ['preset', n.preset]]
    .filter(([, v]) => typeof v === 'string' && v)
    .map(([k, v]) =>
      `<span class="fm-cfg-badge fm-cfg-${k}" title="${esc(k)}: ${esc(v)}"><span class="fm-cfg-key">${esc(k)}</span>${esc(v)}</span>`
    ).join('');
}

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
  const cfg = cfgBadgesHtml(n);
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
      ${cfg ? `<div class="fm-cfg-row">${cfg}</div>` : ''}
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

function edgesSvg(fm, positions, width, height) {
  const nodeIds = new Set((fm?.nodes || []).map((n) => n.id));
  const paths = (fm?.nodes || []).map((n) => {
    if (!n.out || n.out === 'Nebula' || !nodeIds.has(n.out)) return '';
    const from = positions[n.id];
    const to = positions[n.out];
    if (!from || !to) return '';
    const x1 = from.x, y1 = from.y, x2 = to.x, y2 = to.y;
    const midY = (y1 + y2) / 2;
    const state = edgeStateOf(n);
    return `
      <path class="flow-edge fm-edge ${state}" data-edge-state="${state}" d="M ${x1.toFixed(1)} ${y1.toFixed(1)} C ${x1.toFixed(1)} ${midY.toFixed(1)} ${x2.toFixed(1)} ${midY.toFixed(1)} ${x2.toFixed(1)} ${y2.toFixed(1)}"/>
      <circle class="flow-edge-arrow fm-edge-arrow ${state}" cx="${x2.toFixed(1)}" cy="${y2.toFixed(1)}" r="3"/>`;
  }).join('');
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

function summarizeHeader(fm) {
  const nodes = fm?.nodes || [];
  const running = nodes.filter((n) => n.status === 'running').length;
  const failed = nodes.filter((n) => n.status === 'failed').length;
  const pending = nodes.filter((n) => n.status === 'pending').length;
  const completed = nodes.filter((n) => n.status === 'completed').length;
  const parts = [];
  if (running) parts.push(`${running} ${esc(t('flowmap.run'))}`);
  if (pending) parts.push(`${pending} ${esc(t('flowmap.wait'))}`);
  if (failed) parts.push(`${failed} ${esc(t('flowmap.fail'))}`);
  if (completed) parts.push(`${completed} ${esc(t('flowmap.done'))}`);
  return parts.length ? parts.join(' · ') : esc(t('flowmap.idle'));
}

function renderFlowMap(container, fm, projectName) {
  const total = (fm?.nodes || []).length;
  const nodes = visibleNodes(fm);
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
  bindFlowMapClicks(container, projectName);
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
    openNodeResultViewer(esc(node.name), node.agent || '', node.status || '', node.worktree || '', node.result, node.id,
      { skill: node.skill || '', mcp: node.mcp || '', preset: node.preset || '' });
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
    const scroll = pane.querySelector('.team-scroll');
    if (!fm || !scroll) continue;
    let dirty = false;
    (fm.nodes || []).forEach((n) => {
      if ((n.status === 'completed' || n.status === 'failed' || n.status === 'cancelled') &&
          Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0) {
        n.ttlLeftSec -= 1;
        if (n.ttlLeftSec <= 0) dirty = true; else anyTtl = true;
      }
    });
    if (dirty) { renderFlowMap(scroll, fm, project); continue; }
    scroll.querySelectorAll('[data-ttl-node]').forEach((el) => {
      const n = (fm.nodes || []).find((x) => x.id === el.getAttribute('data-ttl-node'));
      if (n) el.textContent = fmtTtl(n.ttlLeftSec);
    });
  }
  // 没有任何倒计时中的节点就停表——空转的 1s 定时器没有意义。
  if (!anyTtl) { clearInterval(ttlTimer); ttlTimer = null; }
}

// ── 标签页打开 / 渲染 ────────────────────────────────────

export function openFlowMapTab(projectName) {
  if (!projectName) return;
  openTab(`flow-map-${projectName}`, projectName, { type: 'flow-map', closable: true, pinned: true });
  renderFlowMapTab(projectName);
}

function renderFlowMapTab(projectName) {
  const pane = getTabPane(`flow-map-${projectName}`);
  if (!pane) return;
  ensureFlowCss();
  const scroll = ensureScroll(pane, `flow-map-scroll-${projectName}`);
  const seq = (seqByProject.get(projectName) || 0) + 1;
  seqByProject.set(projectName, seq);
  if (!scroll.querySelector('.flowmap-card-header')) {
    scroll.dataset.fmState = 'loading';
    scroll.innerHTML = `<div class="flowmap-loading">${esc(t('flowmap.loading'))}</div>`;
  }
  fetchFlowMap(projectName).then((fm) => {
    if (seqByProject.get(projectName) !== seq || !scroll.isConnected) return; // 过期响应丢弃
    fmByProject.set(projectName, fm);
    renderFlowMap(scroll, fm, projectName);
  }).catch(() => {
    if (seqByProject.get(projectName) !== seq || !scroll.isConnected) return;
    scroll.dataset.fmState = 'error';
    scroll.innerHTML = `<div class="dag-empty"><div class="hint">${esc(t('flowmap.loadFail'))}</div></div>`;
  });
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
/** WS 事件驱动刷新：刷新所有打开的 flow-map 标签页（服务端快照为权威）。 */
export function refreshOpenFlowMap() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    openFlowMapPanes().forEach(({ project }) => renderFlowMapTab(project));
  }, 200);
}

// WS 事件驱动（契约 §2）：节点任何变更都刷新已打开的 flow-map 标签页（服务端权威快照）。
import { onMessage } from './ws.js';
onMessage('nodeCreated', () => refreshOpenFlowMap());
onMessage('nodeUpdated', () => refreshOpenFlowMap());
onMessage('nodeCompleted', () => refreshOpenFlowMap());
onMessage('nodeRemoved', () => refreshOpenFlowMap());
