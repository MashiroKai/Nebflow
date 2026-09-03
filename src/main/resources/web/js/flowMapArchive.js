// flowMapArchive.js — Flow Map 整链归档（v3，规格 20260903_flowmap-archive-panel-spec.md）。
//
// 语义（作者 2026-09-03 20:26 五条反馈，规格 §3/§4/§5/§18）：
//   ① 归档单位 = 任务链：同一次任务触发的节点按 createdAt 派发批次窗口（相邻间隔
//      ≤120s）聚簇成链；链内全部成员到达终态（completed/failed/cancelled）→ 整链
//      一起进归档（主图同帧集体淡出由 flowMapTab 的增量管线执行，本模块提供链判定
//      与 newly-completed 检测）；链未齐时终态成员保留主图（终态色卡）。
//   ② 归档面板 = 画布内右上角悬浮钮（元素选择器 .canvas-ref-select 同语言）+ mailbox
//      式链条目（倒序/独占展开/成员行/hover 联动/Esc 分层关闭/焦点归还）。
//   ③ 右侧详情面板（z 70 > 面板 60）：主图点节点与链内点成员两路触发；宽视口与归档
//      面板并排零重叠（dock-left 404px），窄视口同位浮前。
//   ④ 全局空白点击统一收起（click 判定 + >3px 拖拽豁免 + 节点/面板内部豁免）。
//   ⑤ 条目 24h TTL：按链完成时间到期清理、徽章同步减、成员从派生输入移除（防链
//      重判复活）；剩余 <60min 显示「即将过期」。
//
// 数据源（NodePayload.buildNodeJson 单序列化点，规格 §1.4）：createdAt/completedAt/
// status/result 齐备，链判定纯前端派生；载荷无 task 字段 → 链名第①级（task【】
// 前缀）在有 task 时才启用，产品载荷自然落到第②③级（名称公共前缀/链首名）。

import { esc, fmtTime } from './flowHelpers.js';
import { t } from './i18n.js';
import { showToast } from './modal.js';
import { renderMarkdownWithMath } from './utils.js';

// ── 常量（规格 §3.5/§5.9）─────────────────────────────────
/** 终态集合：链齐判定与归档口径（规格 §3.1）。 */
export const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled']);

/** @param {string|undefined} st @returns {boolean} */
export function isTerminalStatus(st) {
  return TERMINAL_STATUSES.has(String(st || ''));
}

/** 派发批次窗口（§3.5）：同批 createdAt 相邻间隔 ≤120s（实测同批 ≤80s、跨批 ≥3min）。 */
const CHAIN_BATCH_MS = 120000;
/** 归档条目 TTL（§5.9）：自链完成时间起保留 24h。 */
export const ARCHIVE_TTL_MS = 86400000;
/** 「即将过期」标签窗口：剩余 <60min。 */
const TTL_TAG_WINDOW = 3600000;
/** TTL 周期检查（§5.9：30s 轮询 + 快照导入触发）。 */
const TTL_SWEEP_MS = 30000;

/** 状态图标 SVG（禁 emoji：勾/叉/横线，规格 §5.3）。 */
const ST_SVG = {
  completed: '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 6.5l2.5 2.5 4.5-5.5"/></svg>',
  failed: '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 3l6 6M9 3l-6 6"/></svg>',
  cancelled: '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 6h6"/></svg>',
};
const CHEV_SVG = '<svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 2l3.5 3-3.5 3"/></svg>';
const ARCHIVE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="5" x="2" y="3" rx="1"/><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"/><path d="M10 12h4"/></svg>';

/** 详情/条目状态色 class（terminal 三态有专属 SVG，活动态挂 sapphire 色类）。 */
function statusClass(st) {
  return TERMINAL_STATUSES.has(st) ? st : 'active';
}

// ══ 每项目链派生 store（§7.2，随 flowMapTab store 生命周期）═══

/**
 * @typedef {Object} ChainMember
 * @property {string} id
 * @property {string=} name
 * @property {string=} agent
 * @property {string=} status
 * @property {string=} result
 * @property {string=} task
 * @property {number=} createdAt
 * @property {number=} completedAt
 * @property {string[]=} in barrier 输入（NodePayload）
 * @property {string[]=} deps 依赖（NodePayload，条件序列化）
 * @property {string=} out 出边目标（NodePayload）
 * @property {any=} blockedFeedback blocked 结构化反馈（NodePayload，blocked 态）
 * @property {number=} blockCount 阻断轮数（NodePayload）
 */

/**
 * @typedef {Object} Chain
 * @property {string} id
 * @property {string} title
 * @property {ChainMember[]} members 完成时间倒序（冻结副本）
 * @property {number} nodeCount
 * @property {string} status 最坏优先 failed > cancelled > completed
 * @property {number} completedAt 成员最晚完成时间
 */

/**
 * @typedef {Object} ArchiveStore
 * @property {Chain[]} chains 已归档链（面板条目，冻结成员；completedAt 倒序）
 * @property {Chain[]} bufferChains 链未齐链（≥1 终态成员；面板不显、徽章不计）
 * @property {Map<string, Chain>} chainOf nodeId → 所属链（含 buffer）
 * @property {Set<string>} archivedIds 已归档链成员 id（主图不可见集）
 * @property {Set<string>} expiredIds TTL 到期链成员 id（派生输入排除，防复活 §18-C10）
 * @property {Map<string, ChainMember>} tombstones nodeRemoved 出库的终态节点（§7.2：
 *           终态事实保留参与链齐判定；快照重现即清除）
 * @property {Map<string, ChainMember>} input 最近一次派生输入（详情/链名数据源）
 * @property {string | null} expandedEntry 面板展开条目 chainId
 */

/** @type {Map<string, ArchiveStore>} */
const stores = new Map();

/** @param {string} project @returns {ArchiveStore} */
function storeOf(project) {
  let s = stores.get(project);
  if (!s) {
    s = {
      chains: [],
      bufferChains: [],
      chainOf: new Map(),
      archivedIds: new Set(),
      expiredIds: new Set(),
      tombstones: new Map(),
      input: new Map(),
      expandedEntry: null,
    };
    stores.set(project, s);
  }
  return s;
}

/** 项目视图关闭时清 store（flowMapTab canvas-tab-closed 调用）。 */
export function dropStore(project) {
  stores.delete(project);
}

/** 白盒访问（供 flowMapTab 判定/测试脚本检查面板基线；勿在渲染路径外改写）。 */
export function getStore(project) {
  return storeOf(project);
}

const byCreated = (/** @type {ChainMember} */ a, /** @type {ChainMember} */ b) =>
  (a.createdAt || 0) - (b.createdAt || 0);
const byCompletedDesc = (/** @type {ChainMember} */ a, /** @type {ChainMember} */ b) =>
  (b.completedAt || 0) - (a.completedAt || 0);

/**
 * 链名推导（§5.3 确定性三级）：① ≥2 成员 task 含【…】且公共前缀 ≥2 字（产品载荷
 * 暂无 task 字段，出现时自动启用）→ ② 成员名去角色前缀后公共前缀 ≥3 字 → ③ 链首
 * （createdAt 最早）节点名。
 * @param {ChainMember[]} members createdAt 升序
 * @returns {string}
 */
function chainTitleOf(members) {
  const brackets = members
    .map((m) => { const mt = String(m.task || '').match(/【([^】]+)】/); return mt ? mt[1] : null; })
    .filter((/** @type {string | null} */ x) => !!x);
  if (brackets.length >= 2) {
    const pre = commonPrefix(/** @type {string[]} */ (brackets)).replace(/[\s·:：\-—/\d]+$/u, '').trim();
    if (pre.length >= 2) return pre;
  }
  const names = members.map((m) => String(m.name || '').replace(/^(诊断|修复|合并|实施|验收|部署|取证)-/, ''));
  const pre = commonPrefix(names).replace(/[\s\-—·]+$/u, '').trim();
  if (pre.length >= 3) return pre;
  return String(members[0]?.name || members[0]?.id || '');
}

/** @param {string[]} ss @returns {string} */
function commonPrefix(ss) {
  if (!ss.length) return '';
  let pre = ss[0];
  for (const s of ss.slice(1)) {
    while (pre && !s.startsWith(pre)) pre = pre.slice(0, -1);
  }
  return pre;
}

/** 链状态 = 成员最坏态（failed > cancelled > completed，§5.3）。 */
function chainStatusOf(members) {
  if (members.some((m) => m.status === 'failed')) return 'failed';
  if (members.some((m) => m.status === 'cancelled')) return 'cancelled';
  return 'completed';
}

/**
 * @param {string} id
 * @param {ChainMember[]} members createdAt 升序的当前输入成员
 * @returns {Chain}
 */
function buildChain(id, members) {
  const byCreatedList = members.slice().sort(byCreated);
  const frozen = byCreatedList.map((m) => ({ ...m }));
  const sorted = frozen.slice().sort(byCompletedDesc);
  return {
    id,
    title: chainTitleOf(byCreatedList),
    members: sorted,
    nodeCount: sorted.length,
    status: chainStatusOf(sorted),
    completedAt: sorted.reduce((mx, m) => Math.max(mx, m.completedAt || 0), 0),
  };
}

/**
 * 链派生 + 归档判定（§7.2 单点函数；P2 triggerId 落地后仅此处换精确口径）。
 * 返回本次新完成（含重建）的链——调用方据此驱动整链同帧退场动画。
 * @param {string} project
 * @param {ChainMember[]=} fmNodes 快照全量节点（活动 + 终态）
 * @returns {Chain[]}
 */
export function refreshChains(project, fmNodes) {
  const s = storeOf(project);
  const input = new Map();
  for (const n of fmNodes || []) {
    input.set(n.id, n);
    s.tombstones.delete(n.id); // 快照重现 → 出库墓碑清除
  }
  for (const [id, tn] of s.tombstones) {
    if (!input.has(id)) input.set(id, tn);
  }
  s.input = input;

  // 批次聚簇（§3.5）：全量节点按 createdAt 升序、相邻间隔 ≤120s 归一批
  const sorted = Array.from(input.values())
    .filter((n) => !s.expiredIds.has(n.id))
    .sort(byCreated);
  /** @type {{id: string, members: ChainMember[]}[]} */
  const batches = [];
  let cur = null;
  let lastT = -Infinity;
  for (const n of sorted) {
    const ct = n.createdAt || 0;
    if (!cur || ct - lastT > CHAIN_BATCH_MS) {
      cur = { id: 'chain-' + n.id, members: [] };
      batches.push(cur);
    }
    cur.members.push(n);
    lastT = ct;
  }

  const known = new Map(s.chains.map((c) => [c.id, c]));
  /** @type {Chain[]} */
  const buffers = [];
  /** @type {Chain[]} */
  const newChains = [];
  s.chainOf = new Map();
  for (const b of batches) {
    const allTerm = b.members.every((m) => TERMINAL_STATUSES.has(String(m.status || '')));
    if (!allTerm) {
      if (b.members.some((m) => TERMINAL_STATUSES.has(String(m.status || '')))) {
        buffers.push(buildChain(b.id, b.members)); // 链未齐（buffer，活数据派生）
      }
      continue;
    }
    const prev = known.get(b.id);
    // 成员集不变（id 集相等，与序无关）→ 幂等跳过（§7.2）：冻结条目按完成时间
    // 倒序、当前批按创建升序，逐位对比会把每条已归档链误判为重建（每次刷新重复
    // toast + 已归档成员被退场动画复活回主图）。成员集变化才算重建（迟到成员并入）。
    const prevIds = prev ? new Set(prev.members.map((m) => m.id)) : null;
    const identical = !!prevIds && prevIds.size === b.members.length
      && b.members.every((m) => prevIds.has(m.id));
    if (identical) continue; // 冻结条目原样保留（成员被服务端 TTL 出库由墓碑补位）
    const chain = buildChain(b.id, b.members);
    if (prev) Object.assign(prev, chain); // 迟到成员并入已归档链（重建条目，不重复）
    else s.chains.push(chain);
    newChains.push(chain);
  }
  for (const c of s.chains) {
    for (const m of c.members) {
      if (!s.chainOf.has(m.id)) s.chainOf.set(m.id, c);
    }
  }
  for (const c of buffers) {
    for (const m of c.members) s.chainOf.set(m.id, c);
  }
  s.bufferChains = buffers;
  s.archivedIds = new Set();
  for (const c of s.chains) {
    for (const m of c.members) s.archivedIds.add(m.id);
  }
  s.chains.sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0) || a.title.localeCompare(b.title));
  return newChains;
}

/**
 * TTL 清理（§5.9）：到期链从面板移除 + 徽章同步减 + 成员从派生输入排除（§18-C10）。
 * @param {string} project
 * @param {number=} now
 * @returns {number} 移除条数
 */
export function purgeExpired(project, now = Date.now()) {
  const s = storeOf(project);
  const keep = s.chains.filter((c) => now - (c.completedAt || 0) <= ARCHIVE_TTL_MS);
  const removed = s.chains.length - keep.length;
  if (!removed) return 0;
  for (const c of s.chains) {
    if (keep.indexOf(c) !== -1) continue;
    for (const m of c.members) {
      s.expiredIds.add(m.id);
      s.tombstones.delete(m.id);
    }
    if (s.expandedEntry === c.id) s.expandedEntry = null;
  }
  s.chains = keep;
  return removed;
}

/**
 * nodeRemoved 出库墓碑（§7.2）：终态节点出库保留终态事实参与链齐判定；
 * 非终态出库直接消失。
 */
export function recordNodeRemoved(project, node) {
  const s = storeOf(project);
  if (node && isTerminalStatus(node.status)) s.tombstones.set(node.id, node);
}

/** 主图可见判定（§3.1）：可见 = 非已归档链成员 且 非 TTL 到期链成员。 */
export function isVisibleNode(project, node) {
  const s = stores.get(project);
  if (!s) return true;
  return !s.archivedIds.has(node.id) && !s.expiredIds.has(node.id);
}

/** nodeId → 所属链（含链未齐链；详情 meta 用）。 */
export function chainOfNode(project, nodeId) {
  const s = stores.get(project);
  return s ? (s.chainOf.get(nodeId) || null) : null;
}

// ══ 悬浮层 UI（悬浮钮 + 归档面板 + 右侧详情，§4/§5）═══

/**
 * @typedef {Object} LayerCtx
 * @property {string} project
 * @property {HTMLElement} container 渲染容器（卡片宿主，hover 联动定位用）
 * @property {HTMLElement} layer
 * @property {HTMLElement} fab
 * @property {HTMLElement} badge
 * @property {HTMLElement} panel
 * @property {HTMLElement} panelBody
 * @property {HTMLElement} detail
 * @property {HTMLElement} detailTitle
 * @property {HTMLElement} detailBody
 * @property {boolean} panelOpen
 * @property {number=} closeTimer
 * @property {number=} detailCloseTimer
 */

/** @type {Set<LayerCtx>} 全部存活悬浮层（document 级管线遍历用）。 */
const layerCtxs = new Set();
/** @type {WeakMap<HTMLElement, HTMLElement>} host → layer（全量渲染 innerHTML 抹除后重建） */
const layerByHost = new WeakMap();
/** @type {WeakMap<HTMLElement, LayerCtx>} layer → ctx（避免在 DOM 元素上挂自定义属性） */
const ctxByLayer = new WeakMap();
/** 面板唯一 id 序号（多视图并存时 aria-controls 不串）。 */
let layerSeq = 0;
/** 悬浮层 DOM 模板（class 挂 fm- 前缀；testid 对齐原型回归选择器）。 */
const LAYER_HTML = `
  <div class="fm-float-layer">
    <button class="fm-fab" type="button" data-testid="archive-toggle" aria-expanded="false">
      ${ARCHIVE_ICON}
      <span class="fm-archive-badge hidden" data-testid="archive-badge"></span>
    </button>
    <div class="fm-archive-panel" role="region" aria-label="${esc(t('flowmap.archive.title'))}" data-testid="archive-panel" hidden>
      <div class="fm-panel-head">
        <span class="fm-panel-title" title="${esc(t('flowmap.archive.ttlTitle'))}">${esc(t('flowmap.archive.title'))}</span>
        <span class="fm-panel-count"></span>
        <button class="fm-panel-close" type="button" aria-label="${esc(t('flowmap.archive.close'))}">✕</button>
      </div>
      <div class="fm-panel-body"></div>
    </div>
    <div class="fm-detail" role="region" aria-label="${esc(t('flowmap.archive.detailLabel'))}" data-testid="detail-panel" hidden>
      <div class="fm-detail-head">
        <span class="fm-detail-st"></span>
        <span class="fm-detail-title"></span>
        <button class="fm-detail-close" type="button" aria-label="${esc(t('flowmap.archive.detailClose'))}">✕</button>
      </div>
      <div class="fm-detail-body"></div>
    </div>
  </div>`;

function prefersReducedMotion() {
  return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}

/**
 * 确保 host 上存在悬浮层并绑定项目上下文（renderFlowMap 全量/增量路径后调用）。
 * host = 就地视图 .flowmap-view-body（自身定位上下文）或 legacy .canvas-tab-pane。
 * @param {HTMLElement} host position:relative 定位上下文
 * @param {HTMLElement} container 渲染容器（.team-scroll / .flowmap-view-body）
 * @param {string} project
 * @returns {LayerCtx}
 */
export function ensureArchiveLayer(host, container, project) {
  let layer = layerByHost.get(host);
  if (!layer || !layer.isConnected || layer.parentElement !== host) {
    if (layer) layer.remove();
    const holder = document.createElement('div');
    holder.innerHTML = LAYER_HTML;
    layer = /** @type {HTMLElement} */ (holder.firstElementChild);
    // aria-controls 指向本层面板（多视图并存 id 唯一）
    const panelId = `fm-archive-panel-${++layerSeq}`;
    const panelEl = layer.querySelector('.fm-archive-panel');
    if (panelEl) panelEl.id = panelId;
    layer.querySelector('.fm-fab')?.setAttribute('aria-controls', panelId);
    host.appendChild(layer);
    layerByHost.set(host, layer);
  }
  let ctx = ctxByLayer.get(layer);
  if (!ctx) {
    ctx = {
      project,
      container,
      layer,
      fab: /** @type {HTMLElement} */ (layer.querySelector('.fm-fab')),
      badge: /** @type {HTMLElement} */ (layer.querySelector('.fm-archive-badge')),
      panel: /** @type {HTMLElement} */ (layer.querySelector('.fm-archive-panel')),
      panelBody: /** @type {HTMLElement} */ (layer.querySelector('.fm-panel-body')),
      detail: /** @type {HTMLElement} */ (layer.querySelector('.fm-detail')),
      detailTitle: /** @type {HTMLElement} */ (layer.querySelector('.fm-detail-title')),
      detailBody: /** @type {HTMLElement} */ (layer.querySelector('.fm-detail-body')),
      panelOpen: false,
    };
    ctxByLayer.set(layer, ctx);
    bindLayerEvents(ctx);
    layerCtxs.add(ctx);
  }
  ctx.project = project;
  ctx.container = container;
  renderArchiveUi(ctx);
  return ctx;
}

/** 按 (容器, 项目) 找到（必要时创建）该视图的悬浮层上下文。 */
function ctxFor(container, project) {
  const host = container.classList.contains('flowmap-view-body')
    ? container
    : (container.closest('.canvas-tab-pane') || container.parentElement || container);
  return ensureArchiveLayer(/** @type {HTMLElement} */ (host), container, project);
}

/** flowMapTab 渲染管线统一入口：渲染后同步悬浮层（徽章/面板/详情计数）。 */
export function syncArchiveUi(container, project, opts = {}) {
  const ctx = ctxFor(container, project);
  renderArchiveUi(ctx, opts);
  return ctx;
}

/** 外部开详情（主图节点点击；§5.8 触发①）。 */
export function openDetailFor(container, project, nodeId) {
  openDetail(ctxFor(container, project), nodeId);
}

function updateBadge(/** @type {LayerCtx} */ ctx, /** @type {boolean} */ pulse) {
  const s = storeOf(ctx.project);
  const n = s.chains.length; // 徽章口径 = 面板链条目数（§4.3；TTL 清理同步减）
  ctx.badge.textContent = n > 99 ? '99+' : String(n);
  ctx.badge.classList.toggle('hidden', n === 0);
  const label = t('flowmap.archive.button', { n: String(n) });
  ctx.fab.setAttribute('aria-label', label);
  ctx.fab.title = label;
  if (pulse && !prefersReducedMotion()) {
    ctx.badge.classList.remove('pulse');
    void ctx.badge.getBoundingClientRect();
    ctx.badge.classList.add('pulse');
  }
}

/** @param {ChainMember} m @returns {string} 成员行（§5.5：状态/名称/agent/时间/›） */
function memberHtml(m) {
  const st = String(m.status || '');
  const preview = String(m.result || '').slice(0, 60);
  return `
    <div class="fm-member" role="button" tabindex="0" data-node="${esc(m.id)}" title="${esc(String(m.name || ''))} · ${esc(t('flowmap.archive.openMemberHint'))}">
      <div class="fm-member-row">
        <span class="fm-entry-st ${statusClass(st)}">${ST_SVG[/** @type {'completed'} */ (st)] || ''}</span>
        <span class="fm-member-name">${esc(String(m.name || m.id))}</span>
        <span class="fm-member-agent">${esc(String(m.agent || ''))}</span>
        <span class="fm-member-time">${esc(fmtTime(m.completedAt))}</span>
        <span class="fm-member-go">${CHEV_SVG}</span>
      </div>
      ${preview ? `<div class="fm-member-preview">${esc(preview)}</div>` : ''}
    </div>`;
}

/** @param {Chain} c @returns {string} 链条目（§5.3：徽章/链名/N 节点/时间/▾ + 摘要 + 成员区） */
function entryHtml(c) {
  const last = c.members[0];
  const preview = String(last?.result || '').slice(0, 60);
  const remain = (c.completedAt || 0) + ARCHIVE_TTL_MS - Date.now();
  const ttlTag = remain > 0 && remain < TTL_TAG_WINDOW
    ? `<span class="fm-entry-ttl" title="${esc(t('flowmap.archive.ttlTitle'))}">${esc(t('flowmap.archive.expiringSoon'))}</span>`
    : '';
  return `
  <div class="fm-entry" role="button" tabindex="0" data-chain-id="${esc(c.id)}" data-ts="${c.completedAt || 0}"
       aria-expanded="false" data-testid="archive-entry" title="${esc(c.title)}">
    <div class="fm-entry-row1">
      <span class="fm-entry-st ${statusClass(c.status)}">${ST_SVG[/** @type {'completed'} */ (c.status)] || ''}</span>
      <span class="fm-entry-name">${esc(c.title)}</span>
      <span class="fm-entry-nodes">${esc(t('flowmap.archive.nodes', { n: String(c.nodeCount) }))}</span>
      <span class="fm-entry-time">${esc(fmtTime(c.completedAt))}</span>
      ${ttlTag}
      <span class="fm-entry-chev">${CHEV_SVG}</span>
    </div>
    ${preview ? `<div class="fm-entry-preview">${esc(preview)}</div>` : ''}
    <div class="fm-entry-members">${c.members.map(memberHtml).join('')}</div>
  </div>`;
}

/** 面板渲染（§5.2/§5.3/§5.4/§7.3；展开态跨渲染保留 C13）。 */
function renderArchiveUi(/** @type {LayerCtx} */ ctx, opts = {}) {
  const s = storeOf(ctx.project);
  const totalNodes = s.chains.reduce((m, c) => m + c.nodeCount, 0);
  ctx.panelBody.innerHTML = s.chains.length
    ? s.chains.map(entryHtml).join('')
    : `<div class="fm-panel-empty">${esc(t('flowmap.archive.empty'))}</div>`;
  if (s.expandedEntry) {
    const el = ctx.panelBody.querySelector(`.fm-entry[data-chain-id="${CSS.escape(s.expandedEntry)}"]`);
    if (el) {
      el.classList.add('expanded');
      el.setAttribute('aria-expanded', 'true');
    }
  }
  if (opts.flashChainIds) {
    for (const cid of /** @type {string[]} */ (opts.flashChainIds)) {
      const el = ctx.panelBody.querySelector(`.fm-entry[data-chain-id="${CSS.escape(cid)}"]`);
      if (el) {
        el.classList.add('flash');
        el.scrollIntoView({ block: 'nearest', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
      }
    }
  }
  const countEl = ctx.panel.querySelector('.fm-panel-count');
  if (countEl) countEl.textContent = t('flowmap.archive.count', { n: String(s.chains.length), m: String(totalNodes) });
  updateBadge(ctx, !!opts.pulse);
}

// ── 开合（§5.7/§6/§9.2）──────────────────────────────────
function openPanel(/** @type {LayerCtx} */ ctx) {
  if (ctx.closeTimer) { clearTimeout(ctx.closeTimer); ctx.closeTimer = 0; }
  ctx.panel.hidden = false;
  ctx.panel.classList.remove('closing');
  void ctx.panel.getBoundingClientRect(); // 强制起始态生效
  ctx.panel.classList.add('open');
  ctx.fab.classList.add('active');
  ctx.fab.setAttribute('aria-expanded', 'true');
  ctx.panelOpen = true;
  updateDetailDock(ctx); // §5.8b：面板开 → 详情左靠并排
}

function closePanel(/** @type {LayerCtx} */ ctx, opts = {}) {
  if (!ctx.panelOpen && ctx.panel.hidden) return;
  ctx.panelOpen = false;
  ctx.panel.classList.remove('open');
  ctx.fab.classList.remove('active');
  ctx.fab.setAttribute('aria-expanded', 'false');
  updateDetailDock(ctx);
  if (prefersReducedMotion()) {
    ctx.panel.hidden = true;
    ctx.panel.classList.remove('closing');
  } else {
    ctx.panel.classList.add('closing');
    if (ctx.closeTimer) clearTimeout(ctx.closeTimer);
    ctx.closeTimer = setTimeout(() => {
      ctx.panel.hidden = true;
      ctx.panel.classList.remove('closing');
    }, 175);
  }
  if (!opts.keepFocus) ctx.fab.focus({ preventScroll: true }); // §5.7 焦点归还悬浮钮
}

function togglePanel(/** @type {LayerCtx} */ ctx) {
  if (ctx.panelOpen) closePanel(ctx, { keepFocus: true });
  else openPanel(ctx);
}

// ── 右侧详情（§5.8/§5.8b）────────────────────────────────
const STATUS_KEY = {
  wiring: 'flowmap.st.wiring',
  pending: 'flowmap.st.pending',
  running: 'flowmap.run',
  blocked: 'flowmap.blocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
};

function statusLabel(st) {
  const key = STATUS_KEY[st];
  return key ? t(key) : String(st || '');
}

function updateDetailDock(/** @type {LayerCtx} */ ctx) {
  ctx.detail.classList.toggle('dock-left', ctx.panelOpen); // §5.8b：面板同开 → dock-left 404px
}

function openDetail(/** @type {LayerCtx} */ ctx, /** @type {string} */ nodeId) {
  const s = storeOf(ctx.project);
  const n = s.input.get(nodeId);
  if (!n) return;
  renderDetail(ctx, n);
  updateDetailDock(ctx);
  if (!ctx.detail.hidden && ctx.detail.classList.contains('open')) return; // 已开 → 原位换内容
  if (ctx.detailCloseTimer) { clearTimeout(ctx.detailCloseTimer); ctx.detailCloseTimer = 0; }
  ctx.detail.hidden = false;
  ctx.detail.classList.remove('closing');
  void ctx.detail.getBoundingClientRect();
  ctx.detail.classList.add('open');
}

function closeDetail(/** @type {LayerCtx} */ ctx) {
  if (ctx.detail.hidden) return;
  ctx.detail.classList.remove('open');
  if (prefersReducedMotion()) {
    ctx.detail.hidden = true;
    ctx.detail.classList.remove('closing');
  } else {
    ctx.detail.classList.add('closing');
    if (ctx.detailCloseTimer) clearTimeout(ctx.detailCloseTimer);
    ctx.detailCloseTimer = setTimeout(() => {
      ctx.detail.hidden = true;
      ctx.detail.classList.remove('closing');
    }, 175);
  }
}

function renderDetail(/** @type {LayerCtx} */ ctx, /** @type {ChainMember} */ n) {
  const st = String(n.status || '');
  const stEl = ctx.detail.querySelector('.fm-detail-st');
  if (stEl) {
    stEl.className = 'fm-detail-st ' + statusClass(st);
    stEl.innerHTML = ST_SVG[/** @type {'completed'} */ (st)] || '';
  }
  ctx.detailTitle.textContent = String(n.name || n.id);
  const chain = chainOfNode(ctx.project, n.id);
  const chainLine = chain
    ? `${esc(t('flowmap.archive.chain'))}：${esc(chain.title)}（${esc(t('flowmap.archive.nodes', { n: String(chain.nodeCount) }))} · ${esc(chain.members.every((m) => TERMINAL_STATUSES.has(String(m.status || ''))) ? t('flowmap.archive.chainArchived') : t('flowmap.archive.chainRetained'))}）`
    : '';
  const metaRows = [
    `<b>${esc(String(n.agent || ''))}</b> · ${esc(statusLabel(st))} · ${esc(n.id)}`,
    `${esc(t('flowmap.archive.created'))} ${esc(fmtTime(n.createdAt))}${n.completedAt ? ` · ${esc(t('flowmap.archive.completed'))} ${esc(fmtTime(n.completedAt))}` : ''}`,
    chainLine,
  ].filter(Boolean).join('<br>');
  // blocked 结构化反馈（20260902 设计 §4.3 既有载荷；面板化后保留，回归不回退）
  const fb = st === 'blocked' && n.blockedFeedback ? /** @type {any} */ (n.blockedFeedback) : null;
  const blockedPanel = fb ? `
    <div class="flow-blocked-panel">
      <div class="flow-blocked-head">
        <span class="flow-blocked-badge">⚑ ${esc(t('flowmap.blockedTitle'))}</span>
        ${fb.category ? `<span class="flow-blocked-category">${esc(String(fb.category))}</span>` : ''}
        <span class="flow-blocked-count">${esc(t('flowmap.blockedRounds', { n: String(Number(n.blockCount) || 0) }))}</span>
      </div>
      ${fb.detail ? `<div class="flow-blocked-row"><span class="flow-blocked-label">${esc(t('flowmap.blockedDetail'))}</span><div class="flow-blocked-text">${esc(String(fb.detail))}</div></div>` : ''}
      ${fb.suggestion ? `<div class="flow-blocked-row"><span class="flow-blocked-label">${esc(t('flowmap.blockedSuggestion'))}</span><div class="flow-blocked-text">${esc(String(fb.suggestion))}</div></div>` : ''}
    </div>` : '';
  const resultText = String(n.result || '');
  let resultHtml;
  if (!resultText.trim()) {
    resultHtml = `<span class="md-empty">${esc(t('flowmap.archive.noResult'))}</span>`;
  } else if (fb) {
    resultHtml = `<details class="flow-blocked-raw"><summary>${esc(t('flowmap.blockedRawTitle'))}</summary><div class="flow-agent-block-readonly">${esc(resultText)}</div></details>`;
  } else {
    try {
      resultHtml = renderMarkdownWithMath(resultText) || `<span class="md-empty">${esc(t('flowmap.archive.noResult'))}</span>`;
    } catch (_) {
      resultHtml = `<pre>${esc(resultText)}</pre>`; // 渲染异常纯文本兜底（§5.8）
    }
  }
  const taskText = String(n.task || '');
  ctx.detailBody.innerHTML = `
    <div class="fm-detail-meta">${metaRows}</div>
    ${blockedPanel}
    ${taskText ? `<div class="fm-detail-sec">${esc(t('flowmap.archive.taskLabel'))}</div><div class="fm-detail-task">${esc(taskText)}</div>` : ''}
    <div class="fm-detail-sec">${esc(t('flowmap.archive.resultLabel'))}</div>
    <div class="fm-detail-result fm-md">${resultHtml}</div>`;
}

// ── hover 联动（§5.6：条目 → 全员可见下游；成员行 → 该员下游）─────────
function clearAdjHighlight(/** @type {LayerCtx} */ ctx) {
  ctx.container.querySelectorAll('.fm-node.fm-adj-by-entry').forEach((x) => {
    x.classList.remove('fm-adj', 'fm-adj-by-entry');
  });
}

function highlightDownstream(/** @type {LayerCtx} */ ctx, /** @type {string | null} */ nodeId, /** @type {boolean} */ on) {
  clearAdjHighlight(ctx);
  if (!on || !nodeId) return;
  const s = storeOf(ctx.project);
  for (const n of s.input.values()) {
    if (!isVisibleNode(ctx.project, n)) continue;
    const hit = (Array.isArray(n.in) && n.in.includes(nodeId))
      || (Array.isArray(n.deps) && n.deps.includes(nodeId))
      || n.out === nodeId;
    if (!hit) continue;
    const card = ctx.container.querySelector(`.fm-node[data-node-id="${CSS.escape(n.id)}"]`);
    if (card) card.classList.add('fm-adj', 'fm-adj-by-entry');
  }
}

function highlightChainDownstream(/** @type {LayerCtx} */ ctx, /** @type {string} */ chainId, /** @type {boolean} */ on) {
  if (!on) { highlightDownstream(ctx, null, false); return; }
  const s = storeOf(ctx.project);
  const chain = s.chains.find((c) => c.id === chainId) || s.bufferChains.find((c) => c.id === chainId);
  if (!chain) return;
  clearAdjHighlight(ctx);
  const memberIds = new Set(chain.members.map((m) => m.id));
  for (const n of s.input.values()) {
    if (!isVisibleNode(ctx.project, n)) continue;
    const refs = [...(Array.isArray(n.in) ? n.in : []), ...(Array.isArray(n.deps) ? n.deps : [])];
    if (!refs.some((x) => memberIds.has(x))) continue;
    const card = ctx.container.querySelector(`.fm-node[data-node-id="${CSS.escape(n.id)}"]`);
    if (card) card.classList.add('fm-adj', 'fm-adj-by-entry');
  }
}

// ── 条目交互（§5.5：独占式展开；成员点击 → 详情）─────────────
function toggleEntry(/** @type {LayerCtx} */ ctx, /** @type {Element} */ el) {
  const s = storeOf(ctx.project);
  const id = el.getAttribute('data-chain-id') || '';
  const wasOpen = el.classList.contains('expanded');
  ctx.panelBody.querySelectorAll('.fm-entry.expanded').forEach((x) => {
    x.classList.remove('expanded');
    x.setAttribute('aria-expanded', 'false');
  });
  if (!wasOpen) {
    el.classList.add('expanded');
    el.setAttribute('aria-expanded', 'true');
    s.expandedEntry = id;
  } else {
    s.expandedEntry = null;
  }
}

function bindLayerEvents(/** @type {LayerCtx} */ ctx) {
  ctx.fab.addEventListener('click', (e) => {
    e.stopPropagation();
    togglePanel(ctx);
  });
  const panelClose = ctx.panel.querySelector('.fm-panel-close');
  if (panelClose) panelClose.addEventListener('click', () => closePanel(ctx));
  const detailClose = ctx.detail.querySelector('.fm-detail-close');
  if (detailClose) detailClose.addEventListener('click', () => closeDetail(ctx));

  // 面板内代理事件（条目重建无需重绑）
  ctx.panelBody.addEventListener('click', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    const member = target.closest('.fm-member');
    if (member) {
      e.stopPropagation();
      openDetail(ctx, member.getAttribute('data-node') || '');
      return;
    }
    const entry = target.closest('.fm-entry');
    if (entry) toggleEntry(ctx, entry);
  });
  ctx.panelBody.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const target = /** @type {HTMLElement} */ (e.target);
    const member = target.closest('.fm-member');
    if (member) {
      e.preventDefault();
      e.stopPropagation();
      openDetail(ctx, member.getAttribute('data-node') || '');
      return;
    }
    const entry = target.closest('.fm-entry');
    if (entry) {
      e.preventDefault();
      toggleEntry(ctx, entry);
    }
  });
  ctx.panelBody.addEventListener('mouseover', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    const member = target.closest('.fm-member');
    if (member) {
      highlightDownstream(ctx, member.getAttribute('data-node') || '', true);
      return;
    }
    const entry = target.closest('.fm-entry');
    if (entry) highlightChainDownstream(ctx, entry.getAttribute('data-chain-id') || '', true);
  });
  ctx.panelBody.addEventListener('mouseout', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    if (target.closest('.fm-member')) highlightDownstream(ctx, null, false);
    else if (target.closest('.fm-entry')) highlightChainDownstream(ctx, '', false);
  });
}

// ── 文档级管线：空白点击统一收起（§5.10）+ Esc 分层（§5.7）─────────
/** 指针按下位置（>3px 位移的拖拽豁免 §5.10②）。 */
let pressPoint = null;
document.addEventListener('pointerdown', (e) => {
  pressPoint = { x: e.clientX, y: e.clientY };
});
document.addEventListener('pointerup', () => {
  pressPoint = null;
});

document.addEventListener('click', (e) => {
  const target = /** @type {HTMLElement} */ (e.target);
  if (!target) return;
  // 拖拽豁免：位移 >3px 的按下-抬起不是「空白点击」
  if (pressPoint && Math.hypot(e.clientX - pressPoint.x, e.clientY - pressPoint.y) > 3) return;
  // 面板/悬浮钮/详情内部豁免
  if (target.closest('.fm-float-layer')) return;
  // 节点卡豁免（点节点 = 选择开详情；卡片自身已 stopPropagation，此处兜底）
  if (target.closest('.fm-node')) return;
  let closed = false;
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { layerCtxs.delete(ctx); continue; }
    if (ctx.panelOpen || !ctx.detail.hidden) closed = true;
    closeDetail(ctx);
    closePanel(ctx, { keepFocus: true });
  }
  return closed;
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  let detailClosed = false;
  let panelCtx = null;
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { layerCtxs.delete(ctx); continue; }
    if (!ctx.detail.hidden) { closeDetail(ctx); detailClosed = true; } // Esc 先关详情（z 70 顶层）
    else if (ctx.panelOpen) panelCtx = ctx;
  }
  if (detailClosed) {
    e.stopPropagation();
    return;
  }
  if (panelCtx) {
    e.stopPropagation();
    closePanel(panelCtx); // 焦点归还悬浮钮
  }
});

// ── 链事件通知（flowMapTab 整链退场/链未齐保留时调用）─────────────
/** 链未齐提示 toast（§6.3/§14-8 拍板 A：说明终态卡为何保留主图）。 */
export function notifyChainRetained(project, chain, container) {
  const done = chain.members.filter((m) => TERMINAL_STATUSES.has(String(m.status || ''))).length;
  const ctx = findCtx(container, project);
  if (!ctx) return;
  showToast(t('flowmap.chain.retainedToast', {
    chain: chain.title, done: String(done), total: String(chain.nodeCount),
  }), 'info');
}

/** 整链归档提示 toast（§3.4 增量过渡的口播伴随）。 */
export function notifyChainArchived(project, chains, container) {
  const ctx = findCtx(container, project);
  if (!ctx) return;
  for (const c of chains) {
    showToast(t('flowmap.chain.archivedToast', { chain: c.title, n: String(c.nodeCount) }), 'info');
  }
}

function findCtx(container, project) {
  for (const ctx of layerCtxs) {
    if (ctx.project === project && ctx.container === container && ctx.layer.isConnected) return ctx;
  }
  return container && container.isConnected ? ctxFor(container, project) : null;
}

// ── TTL 周期检查（§5.9：30s 轮询；快照导入触发在 flowMapTab 播种路径）─────
setInterval(() => {
  const projects = new Set();
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { layerCtxs.delete(ctx); continue; }
    projects.add(ctx.project);
  }
  for (const project of projects) {
    const removed = purgeExpired(project);
    if (!removed) continue;
    for (const ctx of layerCtxs) {
      if (ctx.project !== project || !ctx.layer.isConnected) continue;
      renderArchiveUi(ctx);
      showToast(t('flowmap.archive.ttlPurged', { n: String(removed) }), 'info');
    }
  }
}, TTL_SWEEP_MS);
