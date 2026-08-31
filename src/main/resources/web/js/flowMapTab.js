// flowMapTab.js — Project Flow Map 标签页（#27 方向调整：点击 project → 标签页打开）。
// 复用 flow-run 标签页形态 + solar-card 显示设计；关闭标签页不影响项目数据。
// 数据驱动自 §2.2 NodeList 结构；已完成节点 TTL 倒计时到期前端隐藏（数据保留归档）。
// 点击节点卡片打开结果详情 viewer。

import { openTab, getTabPane } from './canvas.js';
import { FLOW_CSS } from './flowCss.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchFlowMap, NODE_STATUS_CLS } from './nodeData.js';

// 布局常量（复用 flowDag 视觉节奏）
const NODE_W = 150;
const NODE_H = 108;
const V_SPACING = 150;
const H_SPACING = 210;
const PAD = 70;

let ttlTimer = null;
let currentProject = null;
let currentFm = null;

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
function edgesSvg(fm, positions, width, height) {
  const nodeIds = new Set((fm?.nodes || []).map((n) => n.id));
  const paths = (fm?.nodes || []).map((n) => {
    if (!n.out || n.out === 'Nebula' || !nodeIds.has(n.out)) return '';
    const from = positions[n.id];
    const to = positions[n.out];
    if (!from || !to) return '';
    const x1 = from.x, y1 = from.y, x2 = to.x, y2 = to.y;
    const midY = (y1 + y2) / 2;
    const active = n.status === 'completed';
    return `
      <path class="flow-edge${active ? ' active' : ''}" d="M ${x1.toFixed(1)} ${y1.toFixed(1)} C ${x1.toFixed(1)} ${midY.toFixed(1)} ${x2.toFixed(1)} ${midY.toFixed(1)} ${x2.toFixed(1)} ${y2.toFixed(1)}"/>
      <circle class="flow-edge-arrow" cx="${x2.toFixed(1)}" cy="${y2.toFixed(1)}" r="3"/>`;
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
  const nodes = visibleNodes(fm);
  const { positions, width, height } = layoutNodes(fm);
  const nodesHtml = nodes.map((n) => nodeHtml(n, positions[n.id] || { x: 0, y: 0 }, width / 2)).join('');
  container.innerHTML = `
    <div class="flowmap-card-header">
      <div class="flowmap-card-title" title="${esc(projectName)}">${esc(projectName)}</div>
      <div class="flowmap-summary">${summarizeHeader(fm)}</div>
    </div>
    ${nodes.length === 0
      ? `<div class="dag-empty"><div class="hint">${esc(t('flowmap.empty'))}</div></div>`
      : `<div class="solar-card flowmap-card">
          <div class="solar-scroll">
            <div class="solar-canvas" style="width:${width}px;height:${height}px">
              ${edgesSvg(fm, positions, width, height)}
              ${nodesHtml}
            </div>
          </div>
        </div>`}
    `;
  bindFlowMapClicks(container);
  startTtlTicker(container);
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(container));
}

function bindFlowMapClicks(container) {
  container.querySelectorAll('.fm-node').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openNodeDetail(el.getAttribute('data-node-id') || '');
    });
  });
}

// 节点结果详情：复用 flowViewers 的 overlay viewer（clean overlay 语义）。
function openNodeDetail(nodeId) {
  const node = currentFm?.nodes?.find((n) => n.id === nodeId);
  if (!node) return;
  import('./flowViewers.js').then(({ openNodeResultViewer }) => {
    openNodeResultViewer(esc(node.name), node.agent || '', node.status || '', node.worktree || '', node.result, node.id);
  });
}

function startTtlTicker(container) {
  clearInterval(ttlTimer);
  const ttlEls = container.querySelectorAll('[data-ttl-node]');
  if (ttlEls.length === 0) return;
  ttlTimer = setInterval(() => {
    let dirty = false;
    (currentFm?.nodes || []).forEach((n) => {
      if ((n.status === 'completed' || n.status === 'failed' || n.status === 'cancelled') &&
          Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0) {
        n.ttlLeftSec -= 1;
        if (n.ttlLeftSec <= 0) dirty = true;
      }
    });
    const els = container.querySelectorAll('[data-ttl]');
    els.forEach((el) => {
      const id = el.getAttribute('data-ttl-node');
      const n = (currentFm?.nodes || []).find((x) => x.id === id);
      if (n) el.textContent = fmtTtl(n.ttlLeftSec);
    });
    if (dirty && currentProject) renderFlowMap(container, currentFm, currentProject);
  }, 1000);
}

// ── 标签页打开 / 渲染 ────────────────────────────────────

export function openFlowMapTab(projectName) {
  if (!projectName) return;
  currentProject = projectName;
  openTab(`flow-map-${projectName}`, projectName, { type: 'flow-map', closable: true, pinned: true });
  renderFlowMapTab(projectName);
}

function renderFlowMapTab(projectName) {
  const pane = getTabPane(`flow-map-${projectName}`);
  if (!pane) return;
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
  const scroll = ensureScroll(pane, `flow-map-scroll-${projectName}`);
  scroll.innerHTML = `<div class="flowmap-loading">${esc(t('flowmap.loading'))}</div>`;
  fetchFlowMap(projectName).then((fm) => {
    currentFm = fm;
    renderFlowMap(scroll, fm, projectName);
  }).catch(() => {
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

// 标签页关闭时清理 TTL 定时器（不影响项目数据，数据在 service/nodeData mock）。
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  const id = e.detail?.id || '';
  if (typeof id === 'string' && id.startsWith('flow-map-')) {
    clearInterval(ttlTimer);
    ttlTimer = null;
    currentProject = null;
    currentFm = null;
  }
});

let refreshTimer = null;
export function refreshOpenFlowMap() {
  if (!currentProject) return;
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    fetchFlowMap(currentProject).then((fm) => {
      currentFm = fm;
      const pane = getTabPane(`flow-map-${currentProject}`);
      if (pane) {
        const scroll = ensureScroll(pane, `flow-map-scroll-${currentProject}`);
        renderFlowMap(scroll, fm, currentProject);
      }
    });
  }, 200);
}
