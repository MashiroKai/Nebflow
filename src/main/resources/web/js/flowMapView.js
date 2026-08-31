// flowMapView.js — Project Flow Map 视图（§3.5）。
// 页面内导航（非弹窗非新标签页）：点击 project 进入，左上角返回回列表。
// 复用 flow-run 面板显示设计（.solar-card / .solar-node / .solar-orbit /
// .flow-edge 状态色+边连线），数据驱动自 §2.2 NodeList 结构。
// 已完成节点 5 分钟后不显示（TTL 只影响显示，结果保留归档——前端隐藏≠数据删除）。
// 点击节点卡片查看结果详情（活动/归档读取——阶段 0 mock 先显示 result 摘要）。

import { t } from './i18n.js';
import { esc } from './flowHelpers.js';
import { FLOW_CSS } from './flowCss.js';
import { fetchFlowMap, NODE_STATUS_CLS } from './nodeData.js';

// 布局常量（复用 flowDag 的视觉节奏）
const NODE_W = 150;
const NODE_H = 108;
const V_SPACING = 150;
const H_SPACING = 210;
const PAD = 70;

const view = () => document.getElementById('flowmap-view');
let currentProject = null;
let ttlTimer = null;

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
  // 入口节点（无入边或入边指向不存在节点）→ 深度 0
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
  return { positions, width, height, depth };
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
  // TTL 倒计时（终态节点）：只在 >0 时显示；=0 视为已过 5min 应隐藏（渲染前过滤）
  const ttl = (st === 'completed' || st === 'failed' || st === 'cancelled') && Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec > 0
    ? `<span class="fm-ttl" data-ttl-node="${esc(n.id)}" data-ttl="0">${fmtTtl(n.ttlLeftSec)}</span>` : '';
  const result = n.result
    ? `<div class="fm-result-summary" title="${esc(n.result)}">${esc(n.result.slice(0, 46))}${n.result.length > 46 ? '…' : ''}</div>`
    : (st === 'running' ? '<div class="fm-result-summary running">运行中…</div>' : '');
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
  const s = Math.max(0, Math.ceil(sec));
  return `⏱ ${s}s`;
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

// ── 渲染 ─────────────────────────────────────────────────
export function renderFlowMap(container, fm, projectName) {
  const nodes = visibleNodes(fm);
  const { positions, width, height } = layoutNodes(fm);
  const nodesHtml = nodes.map((n) => nodeHtml(n, positions[n.id] || { x: 0, y: 0 }, width / 2)).join('');
  const summary = summarizeHeader(fm);
  container.innerHTML = `
    <div class="flowmap-header">
      <button id="flowmap-back" class="flowmap-back" aria-label="${esc(t('flowmap.back'))}">
        <i data-lucide="arrow-left"></i>
      </button>
      <div class="flowmap-title-wrap">
        <div class="flowmap-title">${esc(projectName)}</div>
        <div class="flowmap-sub">${esc(t('flowmap.title'))}</div>
      </div>
      <div class="flowmap-summary">${summary}</div>
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
    <div id="flowmap-node-detail" class="flowmap-node-detail" hidden></div>`;
  createIconsIn(container);
  bindFlowMapClicks(container);
  startTtlTicker(container);
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

// ── 交互：进入 / 返回 / 节点详情 / TTL ────────────────────
function bindFlowMapClicks(container) {
  container.querySelector('#flowmap-back')?.addEventListener('click', closeFlowMap);
  container.querySelectorAll('.fm-node').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openNodeDetail(el.getAttribute('data-node-id') || '', el.getAttribute('data-status') || '');
    });
  });
}

function openNodeDetail(nodeId, status) {
  const fm = currentFm;
  const node = fm?.nodes?.find((n) => n.id === nodeId);
  if (!node) return;
  const panel = document.getElementById('flowmap-node-detail');
  if (!panel) return;
  const titleText = node.result ? t('flowmap.resultTitle') : t('flowmap.noResult');
  const body = node.result
    ? esc(node.result)
    : (status === 'running' ? esc(t('flowmap.runningDetail')) : esc(t('flowmap.noResultDetail')));
  panel.innerHTML = `
    <div class="fm-detail-card">
      <div class="fm-detail-head">
        <div class="fm-detail-title">${esc(node.name)}</div>
        <button class="fm-detail-close" aria-label="${esc(t('flowmap.close'))}"><i data-lucide="x"></i></button>
      </div>
      <div class="fm-detail-meta">${esc(node.agent)} · ${esc(status)}${node.worktree ? ' · ' + esc(node.worktree) : ''}</div>
      <div class="fm-detail-body">${body}</div>
    </div>`;
  panel.hidden = false;
  createIconsIn(panel);
  panel.querySelector('.fm-detail-close')?.addEventListener('click', () => { panel.hidden = true; });
}

function startTtlTicker(container) {
  clearInterval(ttlTimer);
  const ttlEls = container.querySelectorAll('[data-ttl-node]');
  if (ttlEls.length === 0) return;
  ttlTimer = setInterval(() => {
    let dirty = false;
    // 服务端未驱动时本地递减演示 TTL；契约后由 nodeUpdated/nodeCompleted 驱动
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
    // 若有节点到期 → 重渲（隐藏超出 TTL 的终态节点；数据保留）
    if (dirty && currentProject) {
      renderFlowMap(container, currentFm, currentProject);
      // 重渲后 reload 图标（lucide）
      createIconsIn(container);
    }
  }, 1000);
}

// ── 进入 / 返回 ───────────────────────────────────────────
export function openFlowMap(projectName) {
  const el = view();
  if (!el) return;
  currentProject = projectName;
  el.hidden = false;
  document.body.classList.add('flowmap-open');
  const content = el.querySelector('.flowmap-content') || el;
  content.innerHTML = `<div class="flowmap-loading">${esc(t('flowmap.loading'))}</div>`;
  fetchFlowMap(projectName).then((fm) => {
    currentFm = fm;
    renderFlowMap(content, fm, projectName);
  }).catch(() => {
    content.innerHTML = `<div class="dag-empty"><div class="hint">${esc(t('flowmap.loadFail'))}</div></div>`;
  });
}

export function closeFlowMap() {
  const el = view();
  if (!el) return;
  el.hidden = true;
  document.body.classList.remove('flowmap-open');
  currentProject = null;
  currentFm = null;
  clearInterval(ttlTimer);
  ttlTimer = null;
}

let currentFm = null;

export function initFlowMapView() {
  // 注入 flow-run 的 DAG 视觉样式（.solar-card/.solar-node/.flow-edge 等），
  // 让 Flow Map 复用与 flow-run 一致的显示设计。注入 head（带 guard），因为
  // renderFlowMap 会整体覆写视图内容，注入视图内的 style 会被抹掉。
  if (!document.getElementById('flowmap-flow-css')) {
    const style = document.createElement('style');
    style.id = 'flowmap-flow-css';
    style.textContent = FLOW_CSS;
    document.head.appendChild(style);
  }
  window.addEventListener('flowmap-open', (/** @type {CustomEvent} */ e) => {
    const project = e.detail?.project;
    if (project) openFlowMap(project);
  });
  // WS 事件（契约后）：nodeCreated/nodeUpdated/nodeCompleted/nodeRemoved 驱动状态刷新
  import('./ws.js').then(({ onMessage }) => {
    onMessage('nodeCreated', () => refreshIfOpen());
    onMessage('nodeUpdated', () => refreshIfOpen());
    onMessage('nodeCompleted', () => refreshIfOpen());
    onMessage('nodeRemoved', () => refreshIfOpen());
  });
}

let refreshTimer = null;
function refreshIfOpen() {
  if (!currentProject) return;
  // 防抖：多个节点事件合一次刷新
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    fetchFlowMap(currentProject).then((fm) => {
      currentFm = fm;
      const content = view()?.querySelector('.flowmap-content') || view();
      if (content) renderFlowMap(content, fm, currentProject);
    });
  }, 200);
}

// lazy import utils for createIconsIn
function createIconsIn(root) {
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(root));
}
