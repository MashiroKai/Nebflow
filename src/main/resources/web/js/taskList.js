// taskList.js — Collapsible task panel (floats above the input area)
// 任务面板 = delegate 行主面（#56 残项 · 换源即切，晨裁② 2026-10-07：「并行一窗
// vs 换源即切」已裁＝换源即切、并行一窗不保留）：
//   - 节点区块整体退役：原 Flow Map 节点条目（WS nodeCreated/Updated/Completed/
//     Removed 订阅 + GET /api/projects/<n>/flow-map 快照对账 + 链折叠行/链徽标/
//     链控三钮）随 delegate 行成为面板主面即切摘除——REST/WS 引擎退役面零触碰
//     （时序＝N1 结构帧落地面，#1229 清单面）。
//   - 面板行只剩 S1 delegate 行（数据 = delegateChainStub.js 读取口；N1 刀3
//     setSource 已挂 live provider——REST /api/chains 快照 + WS 触发防抖镜像，
//     main.js 副作用 import 单行）。面板重渲触发 = provider 镜像刷新完成广播
//     （delegate-chain-mirror-updated）＋会话切换＋S16 进度轮询；帧只当触发器
//     纪律不变，面板零 WS 知识、零第二权威。
//   - 头部统计 = delegate 行数（键复用 task.statsProgress；统计口径归换源批——
//     a60af705d 申报面「delegate 行计数的统计面归换源批」）。
//   - 入口键 project.openFlowMap 随晨裁②退役照荐落（§3.3 在案：节点行 title 消费
//     面随节点区块退役；键值保留在册，零文案 diff）。
//   - 旧任务区退役（2026-09-05 10:54 裁定）与相对时间分钟拍机制保留不动。
import { t } from './i18n.js';
import { createIconsIn, onMinuteTick } from './utils.js';
import state from './state.js';
import { NODE_STATUS_CLS } from './nodeData.js';
// Delegate 行（S1 主面）：数据源 = delegateChainStub.js 读取口（listDelegateRows /
// getChainByTask）。N1 刀3 换源已落：main.js 副作用 import 单行把真 provider 挂上
// setSource()（REST /api/chains 快照 + WS 触发的防抖镜像），本渲染面零数据权威、
// 零 provider 直引（架构钉：换源只在 main.js 单缝）；夹具在场 = 恒夹具优先
// （harness 兼容缝）。
import { listDelegateRows, getChainByTask, listDelegateChildren } from './delegateChainStub.js';
import { fetchDelegateOutput, openDelegateNative } from './delegateApi.js';
import { executorIcon } from './executorIcons.js';

// ── delegate/flowmap 两层架构批（2026-10-09 · 规格 §B WF-02 / §E）──────────────
// 子代理显示从弹窗迁入任务列表：行内徽标（users+N / flow 链步数）+ expanded 逐
// 子代理行族 + flow「链 N 步」单行。source 数据词归一（九点②同款；main.js
// bgChildKindWord 为面板同族映射——本件本地镜像同判据）。
function childSourceWord(source) {
  const s = String(source || '');
  if (s === 'subtask' || s === 'delegate') return 'Delegate';
  return s;
}

/** 子条目 store 状态 → 行状态词键值域（subagents.status.*；零新键）。 */
function childStateKey(status) {
  if (status === 'completed') return 'done';
  if (status === 'failed') return 'error';
  return 'running'; // running|restarting|未知 → 保守运行侧
}

/** 子条目时长尾（spawnedAt → completedAt｜在跑 = now；数据非文案：m/h 裸数字）。 */
function childDurationText(c) {
  const spawned = c && typeof c.spawnedAt === 'number' ? c.spawnedAt : 0;
  if (!spawned) return '';
  const end = c && typeof c.completedAt === 'number' && c.completedAt > 0 ? c.completedAt : Date.now();
  const mins = Math.max(0, Math.round((end - spawned) / 60_000));
  if (mins < 60) return `${mins}m`;
  return `${Math.floor(mins / 60)}h`;
}

/** 行内徽标内联 SVG（lucide 同源描边语言；卡内小尺寸 12px）。 */
const BADGE_USERS_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>';
const BADGE_FLOW_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="16" y="16" width="6" height="6" rx="1"/><rect x="2" y="16" width="6" height="6" rx="1"/><rect x="9" y="2" width="6" height="6" rx="1"/><path d="M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3"/><path d="M12 12V8"/></svg>';

/**
 * 行内子代理/flow 徽标（§B collapsed）：users+N（G3 children 镜像计数）与
 * flow+N（chain.nodes flow-step 计数）。**零自造数据**：无读数不渲染（九点同款
 * 降级纪律——children 恒空集/链无 flow-step = 无徽标）；外部行不渲染（children
 * 恒空集守卫）。title = 数据原文（计数+名单）。
 * @param {any} r DelegateRow @param {HTMLElement} row @returns {void} */
function appendInlineBadges(r, row) {
  if (!r || r.external === true) return; // 外部执行器零平台工具 ⇒ children 恒空集
  const delegateId = String(r.delegateId || '');
  const kids = delegateId ? listDelegateChildren(delegateId) : [];
  const kidNames = kids.map((c) => String((c && (c.agentName || c.taskId)) || '')).filter(Boolean);
  if (kids.length) {
    const badge = document.createElement('span');
    badge.className = 'task-kids-badge';
    badge.setAttribute('aria-hidden', 'true');
    badge.innerHTML = `${BADGE_USERS_SVG}<span class="task-kids-badge-n">${kids.length}</span>`;
    badge.title = `${t('delegate.children')} ×${kids.length}${kidNames.length ? ` — ${kidNames.join(', ')}` : ''}`;
    row.appendChild(badge);
  }
  const key = delegateRowKey(r);
  const chain = key ? getChainByTask(key) : null;
  const flowSteps = chain && Array.isArray(chain.nodes)
    ? chain.nodes.filter((n) => n && n.kind === 'flow-step') : [];
  if (flowSteps.length) {
    const badge = document.createElement('span');
    badge.className = 'task-flow-badge';
    badge.setAttribute('aria-hidden', 'true');
    badge.innerHTML = `${BADGE_FLOW_SVG}<span class="task-flow-badge-n">${flowSteps.length}</span>`;
    badge.title = t('delegate.chainSteps', { n: String(flowSteps.length) });
    row.appendChild(badge);
  }
}

/**
 * L1 委托流程图唯一行内入口（两层架构批 · 规格 §C/E；文件夹树批 2026-10-09 起
 * **调用方 = 树子行**：行尾 workflow 钮已退役——根行点击 = 展开/收起结构树，
 * 子节点行（subagent/flow/链 Node）点击 = 跳 L1）。taskList 仅存这一条动态 import
 * （legacy-ui-retire 静态钉：单一 openDelegateChainTask 引线，零结构视图引用）。
 * @param {string} key @param {any} meta
 * @param {{expandFlow?: boolean, anchorNode?: string}} [opts] anchorNode = flow
 *        第三层「点具体 Node」锚（L1 打开即展开 flow-step 层并定位该节点卡）。 */
function openL1(key, meta, opts = {}) {
  import('./delegateChainView.js').then(({ openDelegateChainTask }) => {
    openDelegateChainTask(key, meta, opts);
  }).catch(() => {});
}

// ── 面板会话门（沿用原节点区块自有判定；delegate 行主面同门）────────────────
/** Nebula 统一面板才渲染面板行。独立命名空间（不复用已退役的旧任务域判定）——
 *  team/旧任务区退役不影响本门。 */
function sessionShowsNodeEntries(sid) {
  if (!sid) return false;
  const agent = state.sessionAgentMap ? state.sessionAgentMap[sid] : null;
  return !agent || agent === 'Nebula';
}

/** 最近一次渲染面板的会话：镜像事件到达时按它判断是否重渲（Nebula 统一面板）。 */
let lastPanelSessionId = null;

function rerenderWithNodes() {
  const sid = lastPanelSessionId;
  if (!sid || !sessionShowsNodeEntries(sid)) return;
  renderTaskList([], undefined, sid); // 首参占位——旧任务入参已退役（delegate 行主面）
}

// 换源即切接线（#56 残项 · 晨裁②）：provider 镜像刷新完成（boot 首刷 / WS 触发
// 防抖刷 / 重连对账）即广播 window 事件——本面板监听重渲。数据权威恒在 provider
// 镜像（REST），事件零载荷；本文件零 WS 订阅、零帧解读。
const DELEGATE_MIRROR_EVENT = 'delegate-chain-mirror-updated';
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener(DELEGATE_MIRROR_EVENT, () => { rerenderWithNodes(); });
}

// ── §15.7 relative time（面板行相对时刻）────────────────────────────────
function formatLastActive(updatedAt) {
  if (!updatedAt) return '';
  const ms = Date.parse(updatedAt);
  if (isNaN(ms)) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/** §15.7: 60s interval, only while the panel has visible rows; textContent
 *  direct update, no animation. */
let lastActiveTimer = null;
function refreshLastActive() {
  const container = document.getElementById('task-list');
  if (!container) return;
  const els = /** @type {NodeListOf<HTMLElement>} */ (
    // delegate 行时间列沿用 .task-node-time 视觉槽（行族同源设计语言）
    container.querySelectorAll('.task-node-time'));
  els.forEach((el) => {
    const ts = el.dataset.ts;
    if (ts) el.textContent = formatLastActive(ts);
  });
}
function ensureLastActiveTimer(running) {
  if (running && !lastActiveTimer) {
    // perf-481 A5: was a private `setInterval(60_000)`. Relative timestamps
    // ("3m ago") only need minute granularity, so this refresh shares the
    // page-wide wall-clock minute grid (utils.onMinuteTick) instead of owning a
    // second 1-minute timer whose phase drifts from the project-tab fallback.
    lastActiveTimer = onMinuteTick(() => {
      if (document.hidden) return;
      refreshLastActive();
    });
  } else if (!running && lastActiveTimer) {
    lastActiveTimer();
    lastActiveTimer = null;
  }
}

const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

// ── delegate-ui 修订批（用户九点①④⑥ · 2026-10-09）─────────────────────────
// ① 任务列表只显示在跑的 delegate：非活性行（completed/failed/cancelled 终态）
//    随完成移出列表——过滤单点在此（渲染入口），读取口/镜像保持全量在册
//    （数据面零改写；终态行仍在结构总视图 S11 与归档窗可达）。
//    活性值域 = pending/running/blocked/ready-to-merge（排队合并仍是活跃工作——
//    ④ 待合并行带名次留列，终态不留）。
const ACTIVE_ROW_STATUSES = new Set(['pending', 'running', 'blocked', 'ready-to-merge']);

/**
 * 运行态 glyph（用户九点⑥ · 2026-10-09）：delegate 运行态统一对齐 flowmap 节点
 * 现行的「三点轨道旋转」视觉（.solar-orbit 同语言：3 环 3 点、120° 相位、整体
 * 旋转），替代旧单环 task-node-spin。12px 行内节奏不变（glyph 槽尺寸沿用）。
 * @returns {HTMLElement} */
function buildOrbitGlyph() {
  const g = document.createElement('span');
  g.className = 'task-node-orbit';
  g.setAttribute('aria-hidden', 'true');
  for (let i = 0; i < 3; i++) {
    const ring = document.createElement('span');
    ring.className = 'task-node-orbit-ring';
    ring.style.setProperty('--orbit-phase', `${i * 120}deg`);
    const dot = document.createElement('span');
    dot.className = 'task-node-orbit-dot';
    ring.appendChild(dot);
    g.appendChild(ring);
  }
  return g;
}

// ── 状态 glyph（面板行共用设计语言：delegate 行沿用节点行 glyph 族）────────

/**
 * 状态 glyph：running = 品牌绿转动圆环 / blocked = 琥珀实心点 / 终态 = 色点 /
 * wiring|pending = 小方框 / 未知 = 中性半透明点（优雅降级不崩）。
 * 视觉规范承节点行 v2（2026-09-02/09-05 裁定）：glyph 统一 12px 节奏，颜色信号
 * 由 glyph 独占、状态词全 muted。
 * @param {string} st @param {string} cls @returns {HTMLElement} */
function buildNodeGlyph(st, cls) {
  const g = document.createElement('span');
  g.setAttribute('aria-hidden', 'true');
  if (st === 'running') {
    // 用户九点⑥：delegate 运行态 = 三点轨道旋转（flowmap 节点同款视觉）。
    g.appendChild(buildOrbitGlyph());
    g.className = 'task-node-glyph-running';
  } else if (st === 'blocked') {
    // blocked（阻塞）：琥珀实心点（警示态，区别于 failed 终结红）
    g.className = 'task-node-dot task-node-dot-blocked';
  } else if (st === 'completed' || st === 'failed' || st === 'cancelled') {
    g.className = `task-node-dot task-node-dot-${cls}`;
  } else if (st === 'wiring' || st === 'pending') {
    g.className = 'task-node-box';
  } else {
    g.className = 'task-node-dot task-node-dot-neutral'; // 未知状态中性降级
  }
  return g;
}

// ── Delegate 行骨架（#1228 重划版提前开工批 · 可落地段最小面）─────────────
// S1 行 = task#N＋标题＋执行器图标＋状态。数据源 = delegateChainStub.js 读取口
// （live provider 缺位且无夹具 ⇒ 空 ⇒ 本区块不渲染，面板收起）。**零新增 locale
// 键**：状态词只映射既有键可表达的态。展开结构帧（S1 delegate 链帧）/点进
// per-task 链视图（S10）/外部粗粒度行接线（S16）＝换源接线批已落面。

/** 委托行状态词映射（🔴 既有键复用单源 + §16 键 1；值 = 键名）。
 *  键域对齐 delegateChainStub.ROW_STATUS。ready-to-merge＝§16 过目键 1
 *  「待合并/Ready to merge」（#1228 两裁①落库；#1224 判词语义＝有分支产物、
 *  入 FIFO 候合并）。条件三键（merging/外部行进度词/区块头）不落。 */
const DELEGATE_WORD_KEY = {
  pending: 'task.nodePending',
  running: 'task.inProgressShort',
  blocked: 'task.nodeBlocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
  'ready-to-merge': 'delegate.readyToMerge',
};

// ── S1 展开记忆（FSM：collapsed ⇄ expanded；折叠记忆跨重渲保留）──────────
// 键 = 行键（taskNo ‖ delegateId，ChainStore.keyFor 同口径）。行内 Set（渲染层
// 内存，不入 DOM/storage——刷新归零）。
const expandedDelegates = new Set();

/** 行键（delegate 行的链读取口键）：台账号优先，delegateId 回落。 */
function delegateRowKey(r) {
  return String((r && (r.taskNo || r.delegateId)) || '');
}

// ── 任务列表「文件夹树」批（2026-10-09 · 对账报告 §一 R2/R3/R5 定稿）──────────
// 根 delegate = root；subagent 子代理行与 flow 链行 = root 下同级子节点；flow 子行
// 可再展开第三层（链 Node 1/2/3…——用户手绘新增，数据源 = chain.nodes 链数据）。
// 全行热区零侧边按钮：内部行行尾 workflow 钮退役（根行点击 = 展开/收起；子行点击
// = 跳 L1）；外部行 openNative 钮保留（S16 唯一出口边界，不在「侧边按钮」禁令内）。
// 解释帧（worktree/verdict/raw）退场（R3：L1 已有同源投影，树 = 纯结构）。

/** flow 第三层展开记忆（渲染层内存，不入 DOM/storage——与 expandedDelegates 同纪律；
 *  键 = 行键）。收起 root 不清除（重渲后展开态保留）。 */
const expandedFlowTrees = new Set();

/** 行首 disclosure chevron（12px 示能；▸，展开后 CSS 随 aria-expanded 旋转成 ▾）。
 *  叶行（外部行/待合并行/空树行）= 不可见占位（Finder 对齐纪律：保证全列文本对齐
 *  ——chevron 是示能不是按钮，热区仍全行）。 */
const TREE_CHEV_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>';
function buildTreeChevron(leaf) {
  const c = document.createElement('span');
  c.className = 'task-tree-chev' + (leaf ? ' task-tree-chev-leaf' : '');
  c.setAttribute('aria-hidden', 'true');
  c.innerHTML = TREE_CHEV_SVG;
  return c;
}

/** 树展开守卫（FSM；替代旧 delegateChainRenderable——R3 解释帧退场后 worktree/
 *  verdict/frames 不再构成展开面）：树非空即「有 children ∪ 有 flow-step」。
 *  空树 root = 叶行（无 chevron 示能、无展开面、点击零动作——零空态区块纪律延伸）。 */
function delegateTreeOpenable(r) {
  if (!r || typeof r !== 'object') return false;
  const delegateId = String(r.delegateId || '');
  if (delegateId && listDelegateChildren(delegateId).length) return true;
  const chain = getChainByTask(delegateRowKey(r));
  return !!(chain && Array.isArray(chain.nodes) &&
    chain.nodes.some((n) => n && n.kind === 'flow-step'));
}

/**
 * Delegate 行（S1 collapsed 态，文件夹树 root）：`[chevron][执行器图标] #N 标题
 * [状态词] [相对时间]`。视觉复用 .task-node 行族（零新增 CSS；设计语言与面板既有
 * 行同源），外加 .task-delegate 命名空间锚与 data-delegate-* 数据锚。
 * 交互（树批定稿 · 全行热区零侧边按钮）：
 *   - 内部可展开行（树非空）：行首 12px chevron = 展开示能；**点行任意处 = 展开/
 *     收起结构树**（aria-expanded/FSM 照旧；行尾 workflow 钮退役——L1 入口移交子行）。
 *   - 叶行（外部行 S16 守卫终态 / 待合并行九点④名次终态 / 空树行）：chevron 只留
 *     不可见占位，点行零动作、无展开面。
 *   - 外部行行尾 openNative 钮保留（S16 唯一出口；title/aria = §16 键 6）＋进度
 *     meta 行（offset 增量轮询）。
 * @param {any} r DelegateRow（delegateChainStub 规范 schema）
 * @returns {HTMLElement} */
function buildDelegateRow(r) {
  const row0 = r && typeof r === 'object' ? r : {};
  const taskNo = String(row0.taskNo == null ? '' : row0.taskNo);
  const delegateId = String(row0.delegateId || '');
  const title = String(row0.title || '');
  const st = String(row0.status || 'running');
  const wordKey = DELEGATE_WORD_KEY[st] || '';
  const external = row0.external === true;
  // 用户九点④：待合并行只显示排队名次——不显示详细信息、不可展开（行本体不是
  // 展开钮、点击零动作；链帧信息面不进 S1 expanded，名次 = 引擎 G5 位次透传）。
  const queued = st === 'ready-to-merge';
  // 文件夹树：内部非排队行 ∧ 树非空才可展开（空树 = 叶行）。
  const openable = !external && !queued && delegateTreeOpenable(row0);

  const row = document.createElement('div');
  row.className = 'task-node task-delegate';
  row.dataset.delegateKey = taskNo || delegateId;
  row.dataset.taskNo = taskNo;
  row.dataset.delegateId = delegateId;
  row.dataset.delegateStatus = st;
  if (external) row.dataset.external = '1';
  // 行首 disclosure chevron（第一视觉槽；叶行 = 不可见占位保证全列文本对齐）。
  row.appendChild(buildTreeChevron(!openable));
  if (openable) {
    // 可展开行 = S1 展开钮（role/aria-expanded 只属可展开行；叶行行本体不是按钮）。
    row.setAttribute('role', 'button');
    row.setAttribute('aria-expanded', expandedDelegates.has(taskNo || delegateId) ? 'true' : 'false');
  }
  const labelBits = [taskNo ? `#${taskNo}` : '', title || delegateId].filter(Boolean);
  row.setAttribute('aria-label',
    `${labelBits.join(' ')}${wordKey ? ` — ${t(wordKey)}` : ''}`);

  // 状态 glyph（复用节点行 glyph 语言：running=品牌绿转圈 / blocked=琥珀点 /
  // 终态=色点 / 未知=中性点——零新增 CSS；ready-to-merge 走中性点降级，词已落
  // §16 键 1，色信号候 N1 结构帧值域定稿再议）。色信号由 glyph 独占（同面板既有口径）。
  row.appendChild(buildNodeGlyph(st, NODE_STATUS_CLS[st] || ''));

  // 执行器图标（S1：executorIcons 留项消费；未知 executor → 图标自带中性降级）。
  const icon = document.createElement('span');
  icon.className = 'task-delegate-icon';
  icon.setAttribute('aria-hidden', 'true');
  icon.innerHTML = executorIcon(String(row0.executor || ''), 14);
  row.appendChild(icon);

  const text = document.createElement('div');
  text.className = 'task-node-text';
  const labelRow = document.createElement('span');
  labelRow.className = 'task-node-label-row';
  if (taskNo) {
    const no = document.createElement('span');
    no.className = 'task-delegate-no';
    no.textContent = `#${taskNo}`;
    labelRow.appendChild(no);
  }
  const label = document.createElement('span');
  label.className = 'task-node-label';
  label.textContent = title || delegateId; // 无标题回落 delegate id（数据，非文案）
  labelRow.appendChild(label);
  text.appendChild(labelRow);

  // S16 外部行进度 meta（粗粒度「进度」腿）：offset 增量轮询的输出尾行（数据）。
  // 复用既有 .task-node-meta（零新 CSS）；无缓冲/无读数 → 优雅缺席（零空行）。
  if (external) {
    const tail = progressTailText(delegateProgress.get(delegateId));
    if (tail) {
      const meta = document.createElement('span');
      meta.className = 'task-node-meta';
      meta.setAttribute('aria-hidden', 'true');
      meta.textContent = tail;
      meta.title = tail; // 截断 → 悬停全文
      text.appendChild(meta);
    }
  }
  row.appendChild(text);

  if (wordKey) {
    const wordEl = document.createElement('span');
    wordEl.className = 'task-node-word';
    wordEl.setAttribute('aria-hidden', 'true');
    wordEl.textContent = t(wordKey);
    row.appendChild(wordEl);
  }

  // 用户九点②④：合并态不显示 FIFO——行侧唯一可见面 = 「待合并」旁的排队名次
  // （#N，引擎 G5 mergeQueuePos.position 原样透传；未排队/缺键 = 无名次，禁派生）。
  if (queued && row0.mergeQueuePos && typeof row0.mergeQueuePos.position === 'number' &&
      row0.mergeQueuePos.position > 0) {
    const qp = row0.mergeQueuePos;
    const rankEl = document.createElement('span');
    rankEl.className = 'task-delegate-rank';
    rankEl.setAttribute('aria-hidden', 'true');
    rankEl.textContent = `#${qp.position}`;
    const titleBits = [`#${qp.position}`, qp.total ? `/ ${qp.total}` : '',
      qp.branch || ''].filter(Boolean);
    rankEl.title = titleBits.join(' '); // 数据原文（位次/分支），非文案
    row.appendChild(rankEl);
  }

  // 两层架构批（§B WF-02）：行内子代理/flow 徽标（users+N / flow+N）——插在状态
  // 词（含名次 chip）与时间之间；缺读数不渲染（零自造数据）；queued 行只有徽标
  // 无展开面（九点④共处）。
  appendInlineBadges(row0, row);

  const createdAt = typeof row0.createdAt === 'number' ? row0.createdAt : Date.parse(String(row0.createdAt || ''));
  if (Number.isFinite(createdAt) && createdAt > 0) {
    const time = document.createElement('span');
    time.className = 'task-node-time';
    time.setAttribute('aria-hidden', 'true');
    time.dataset.ts = new Date(createdAt).toISOString();
    time.textContent = formatLastActive(time.dataset.ts);
    row.appendChild(time);
  }

  // 行尾动作区（树批 R2/R8）：内部行整段退役（L1 入口 = 子行点击，全行热区零侧边
  // 按钮）；仅外部行保留 openNative 出口（S16 唯一出口边界，不在禁令内）。
  if (external) {
    const actions = document.createElement('span');
    actions.className = 'task-delegate-actions';
    const nativeBtn = document.createElement('button');
    nativeBtn.type = 'button';
    nativeBtn.className = 'task-delegate-btn';
    nativeBtn.title = t('delegate.openNative'); // §16 键 6（title/aria 同值）
    nativeBtn.setAttribute('aria-label', t('delegate.openNative'));
    nativeBtn.innerHTML = '<i data-lucide="external-link"></i>';
    nativeBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!delegateId || nativeBtn.disabled) return;
      nativeBtn.disabled = true; // 飞行中防重入（fire-and-forget 端点会真开终端）
      const res = await openDelegateNative(delegateId);
      nativeBtn.disabled = false;
      if (res.state !== 'ok') {
        console.warn(`[tasklist] open-native refused (${res.state}${res.state === 'http' ? ` ${res.status}` : ''})`);
      }
    });
    actions.appendChild(nativeBtn);
    row.appendChild(actions);
  }

  // 行点击 = S1 展开/收回（仅树非空的内部非排队行；叶行守卫终态零动作——九点④：
  // 待合并不可展开，空树行无展开面）。
  if (openable) {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const key = taskNo || delegateId;
      if (expandedDelegates.has(key)) {
        expandedDelegates.delete(key);
        rerenderWithNodes();
        return;
      }
      expandedDelegates.add(key);
      rerenderWithNodes();
    });
  }
  return row;
}

/**
 * S1 expanded 面板 = 文件夹树（行下方就地展开 · 树批 R3/R4/R5 重组）：root 下同级
 * 子节点 = subagent 子代理行族 + flow 链行（缩进导轨+叉枝见 CSS）；解释帧
 * （worktree/verdict/raw）退场——同源投影 L1 已有（delegateChainView），树 = 纯结构。
 * 交互：子代理/flow 子行点击 = 跳该 delegate 的 L1 委托流程图（openL1 单缝；flow
 * 子行带 expandFlow 锚点，对话窗入口无损平移到 L1 根卡/子卡）；flow 子行 chevron =
 * 第三层展开/收起（链 Node 1/2/3…，节点名+状态点）；点具体 Node = 跳 L1 并锚到
 * 该节点。数据 = stub 读取口（children/chain＝provider 镜像），零 WS 直连。
 * @param {any} r DelegateRow @returns {HTMLElement|null} */
function buildDelegateDetail(r) {
  const key = delegateRowKey(r);
  const chain = getChainByTask(key);
  const box = document.createElement('div');
  box.className = 'task-delegate-detail';
  box.dataset.delegateDetail = key;
  const rowMeta = r && typeof r === 'object' ? r : {};
  const meta = {
    taskNo: String(rowMeta.taskNo || ''),
    delegateId: String(rowMeta.delegateId || ''),
    title: String(rowMeta.title || ''),
    status: String(rowMeta.status || ''),
    executor: String(rowMeta.executor || ''),
    project: String(rowMeta.project || ''),
  };
  // 子代理行族（§B 逐行形态保留；点击语义树批改 = 跳 L1——R4「树层管导航、L1 管
  // 详情」两层分工；净化后对话窗入口无损平移到 L1 根卡/子卡点击）。
  const kids = listDelegateChildren(String(rowMeta.delegateId || ''));
  if (kids.length) {
    const list = document.createElement('div');
    list.className = 'task-kids-list';
    for (const c of kids) {
      const src = c && typeof c === 'object' ? c : {};
      const sid = String(src.taskId || '');
      if (!sid) continue;
      const state = childStateKey(String(src.status || ''));
      const childRow = document.createElement('button');
      childRow.type = 'button';
      childRow.className = 'bgagent-child-row';
      childRow.dataset.childSession = sid;
      childRow.setAttribute('aria-label', t('delegate.flowmap.title')); // 子行跳 L1（§五.6）
      const dot = document.createElement('span');
      dot.className = 'bgagent-child-dot bg-state-' + state;
      childRow.appendChild(dot);
      const name = document.createElement('span');
      name.className = 'bgagent-child-name';
      name.textContent = String(src.agentName || '') || sid;
      childRow.appendChild(name);
      const st = document.createElement('span');
      st.className = 'bgagent-child-status';
      st.textContent = t('subagents.status.' + state);
      childRow.appendChild(st);
      const source = childSourceWord(String(src.source || ''));
      if (source) {
        const sw = document.createElement('span');
        sw.className = 'bgagent-child-source';
        sw.textContent = source; // 显示词（九点②：subtask→Delegate；数据原文在 title）
        sw.title = String(src.source || '');
        childRow.appendChild(sw);
      }
      const retries = typeof src.retryCount === 'number' && src.retryCount > 0 ? src.retryCount : 0;
      if (retries) {
        const rt = document.createElement('span');
        rt.className = 'bgagent-child-retries';
        rt.textContent = `×${retries}`; // G3 retryCount 数据（重试徽标；0 = 无噪声不渲染）
        rt.title = `${t('manage.retry')} ×${retries}`;
        childRow.appendChild(rt);
      }
      const dur = childDurationText(src);
      if (dur) {
        const durEl = document.createElement('span');
        durEl.className = 'bgagent-child-time';
        durEl.textContent = dur;
        childRow.appendChild(durEl);
      }
      const desc = String(src.description || src.prompt || '');
      childRow.title = desc || t('delegate.flowmap.title');
      childRow.addEventListener('click', (e) => {
        e.stopPropagation();
        openL1(key, meta); // 子行点击 = 跳该 delegate 的 L1 委托流程图（用户令）
      });
      list.appendChild(childRow);
    }
    if (list.children.length) box.appendChild(list);
  }
  // flow 子行（§B 单行保留：flow chip + 链小点图 + 「链 N 步」，九点③语言）：行
  // 点击 = L1 flow 下钻锚点（expandFlow——L1 打开即展开 flow-step 层，R5 语义不变
  // 原位升级为树子节点）；行首 chevron = 第三层展开/收起（链 Node 1/2/3…）。
  const flowSteps = chain && Array.isArray(chain.nodes)
    ? chain.nodes.filter((n) => n && n.kind === 'flow-step')
    : [];
  if (flowSteps.length) {
    const flowRow = document.createElement('button');
    flowRow.type = 'button';
    flowRow.className = 'task-flow-row';
    flowRow.setAttribute('aria-label', t('delegate.flowmap.title')); // 行点击 = 跳 L1
    flowRow.title = t('delegate.flowmap.title'); // L1 入口（aria 语义位）
    const chev = buildTreeChevron(false);
    chev.addEventListener('click', (e) => {
      // chevron = 第三层展开/收起热区（其余全行 = 跳 L1——flow 子行双语义分工）。
      e.stopPropagation();
      if (expandedFlowTrees.has(key)) expandedFlowTrees.delete(key);
      else expandedFlowTrees.add(key);
      rerenderWithNodes();
    });
    flowRow.appendChild(chev);
    flowRow.setAttribute('aria-expanded', expandedFlowTrees.has(key) ? 'true' : 'false');
    const chip = document.createElement('span');
    chip.className = 'task-flow-chip';
    chip.textContent = 'flow'; // 数据词不上键（H.4：Subagent/Delegate/flow 保持数据词）
    flowRow.appendChild(chip);
    const metaEl = document.createElement('span');
    metaEl.className = 'delegate-chain-meta';
    for (const n of flowSteps) {
      const dot = document.createElement('span');
      dot.className = `delegate-chain-dot st-${String((n && n.status) || 'pending')}`;
      dot.title = String((n && (n.label || n.id)) || '');
      metaEl.appendChild(dot);
    }
    const steps = document.createElement('span');
    steps.className = 'delegate-chain-steps';
    steps.textContent = t('delegate.chainSteps', { n: String(flowSteps.length) });
    metaEl.appendChild(steps);
    flowRow.appendChild(metaEl);
    flowRow.addEventListener('click', (e) => {
      e.stopPropagation();
      openL1(key, meta, { expandFlow: true });
    });
    box.appendChild(flowRow);
    // flow 第三层（用户手绘新增）：flow 子行展开后列出链上 Node 1/2/3…（节点名+
    // 状态点，数据源 = chain.nodes 链数据——沿用 L2 链数据）；点具体 Node = 跳 L1
    // 并锚到对应节点（anchorNode）。默认收起（零空态区块纪律延伸到树第三层）。
    if (expandedFlowTrees.has(key)) {
      const nodesBox = document.createElement('div');
      nodesBox.className = 'task-flow-nodes';
      for (const n of flowSteps) {
        const nodeId = String((n && n.id) || '');
        if (!nodeId) continue;
        const nodeLabel = String((n && (n.label || n.id)) || '');
        const nodeRow = document.createElement('button');
        nodeRow.type = 'button';
        nodeRow.className = 'task-flow-node-row';
        nodeRow.dataset.flowNodeId = nodeId;
        nodeRow.title = nodeLabel; // 数据原文（悬停全文）
        const dot = document.createElement('span');
        dot.className = `delegate-chain-dot st-${String((n && n.status) || 'pending')}`;
        dot.title = nodeLabel;
        nodeRow.appendChild(dot);
        const nameEl = document.createElement('span');
        nameEl.className = 'task-flow-node-name';
        nameEl.textContent = nodeLabel;
        nodeRow.appendChild(nameEl);
        nodeRow.addEventListener('click', (e) => {
          e.stopPropagation();
          openL1(key, meta, { expandFlow: true, anchorNode: nodeId });
        });
        nodesBox.appendChild(nodeRow);
      }
      if (nodesBox.children.length) box.appendChild(nodesBox);
    }
  }
  return box.children.length ? box : null;
}

/**
 * Delegate 区块（S1 主面）：行清单直排（保持源侧顺序，零前端派生）。展开行
 * （S1 expanded）行下就地挂链帧面板。空数据 → null（面板零变化）。
 * @param {any[]} rows @returns {HTMLElement|null} */
function buildDelegateSection(rows) {
  const list = Array.isArray(rows) ? rows : [];
  if (list.length === 0) return null;
  const section = document.createElement('div');
  section.className = 'task-section task-section-delegates';
  for (const r of list) {
    const rowKey = delegateRowKey(r);
    section.appendChild(buildDelegateRow(r));
    // 外部行（S16 守卫终态）/待合并行（九点④名次终态：不显示详细信息、不可展开）
    // 均无展开面。
    if (!r || r.external === true || r.status === 'ready-to-merge') continue;
    if (!expandedDelegates.has(rowKey)) continue;
    const detail = buildDelegateDetail(r);
    if (detail) section.appendChild(detail);
  }
  return section;
}

// ── S16 外部行进度面（offset 增量轮询；#1225 落库端点 · delegateApi 绑定）──
// 粗粒度「进度」腿：ExternalOutputStore 缓冲的输出尾行（数据文本，非文案）。
// 纪律：只对「面板上可见的外部 running 行」轮询；offset 增量读（nextOffset 守持，
// 不重拉全量）；终态（exitCode 到位）/无缓冲（404）即停即缺席；行绝不因单次
// 拉取失败崩/清空（fail-soft 同 R11 口径）。终态词面 = 行状态列既有键，不另造。
/** delegateId → 进度读数（渲染层内存：{offset, tail, at, noBuffer, done}）。 */
const delegateProgress = new Map();
/** 输出尾行滚动缓冲上限（字符）——粗粒度行只显示最后一行，缓冲只为拼接增量。 */
const DELEGATE_PROGRESS_TAIL_MAX = 2000;
/** 常规轮询间隔（ms）。404 无缓冲的重试退避（记录面存在、缓冲未建/已淘汰）。 */
const DELEGATE_PROGRESS_TICK_MS = 3000;
const DELEGATE_PROGRESS_NOBUFFER_BACKOFF_MS = 15000;
let delegateProgressTimer = null;

/** 尾行提取：缓冲里最后一个非空行（数据原样，不加工措辞）。 */
function progressTailText(entry) {
  if (!entry || !entry.tail) return '';
  const lines = String(entry.tail).split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const s = lines[i].trim();
    if (s) return s;
  }
  return '';
}

function stopDelegateProgressTimer() {
  if (delegateProgressTimer) {
    clearInterval(delegateProgressTimer);
    delegateProgressTimer = null;
  }
}

/**
 * 一轮进度拉取（定时器与测试钩子共用；渲染路径经 ensureDelegateProgressLoop）。
 * @param {boolean} [force] 越过新鲜度门（harness 专用；产品路径恒缺省）
 * 返回是否有读数变化（变化 → 重渲面板）。
 * @returns {Promise<boolean>} */
async function pollDelegateProgress(force) {
  const container = document.getElementById('task-list');
  if (!container || !container.classList.contains('has-tasks')) {
    stopDelegateProgressTimer();
    return false;
  }
  const ids = [];
  container.querySelectorAll('.task-delegate[data-external="1"][data-delegate-status="running"]')
    .forEach((el) => {
      const id = /** @type {HTMLElement} */ (el).dataset && /** @type {HTMLElement} */ (el).dataset.delegateId;
      if (id && !ids.includes(id)) ids.push(id);
    });
  if (ids.length === 0) {
    stopDelegateProgressTimer();
    return false;
  }
  let changed = false;
  await Promise.all(ids.map(async (id) => {
    const cur = delegateProgress.get(id) || { offset: 0, tail: '', at: 0, noBuffer: false, done: false };
    if (cur.done) return; // 终态读数已定格（终态词面由行状态列承载）
    const backoff = cur.noBuffer ? DELEGATE_PROGRESS_NOBUFFER_BACKOFF_MS : DELEGATE_PROGRESS_TICK_MS;
    if (!force && Date.now() - (cur.at || 0) < backoff - 50) return; // 新鲜读数不重拉
    cur.at = Date.now();
    const data = await fetchDelegateOutput(id, cur.offset || 0);
    if (!data) {
      // 404（无缓冲——进程未在本实例跑过/留存淘汰）→ 静默缺席 + 退避重试；
      // 网络/HTTP 失败同理保留上次读数（fail-soft，不清空行）。
      cur.noBuffer = cur.offset === 0 ? true : cur.noBuffer;
      delegateProgress.set(id, cur);
      return;
    }
    cur.noBuffer = false;
    const out = typeof data.output === 'string' ? data.output : '';
    if (out) {
      cur.tail = (cur.tail + out).slice(-DELEGATE_PROGRESS_TAIL_MAX);
      changed = true;
    }
    if (typeof data.nextOffset === 'number') cur.offset = data.nextOffset;
    if (data.exitCode !== null && data.exitCode !== undefined) {
      cur.done = true; // 终态：offset 增量停止（终态词面 = 行状态列既有键）
      changed = true;
    }
    delegateProgress.set(id, cur);
  }));
  // 读数变化 → 重渲面板（单点：定时器/harness 强制轮共用同一渲染出口）。
  if (changed) rerenderWithNodes();
  return changed;
}

/** 渲染路径挂钩：面板上有外部 running 行 → 保证轮询在跑并即刻补一轮（新鲜度门内
 *  自动跳过，突发重渲不放大请求数）；无 → 停表。 */
function ensureDelegateProgressLoop() {
  const container = document.getElementById('task-list');
  if (!container || !container.querySelector('.task-delegate[data-external="1"][data-delegate-status="running"]')) {
    stopDelegateProgressTimer();
    return;
  }
  if (!delegateProgressTimer) {
    delegateProgressTimer = setInterval(() => {
      pollDelegateProgress().catch(() => {});
    }, DELEGATE_PROGRESS_TICK_MS);
  }
  pollDelegateProgress().catch(() => {});
}

/** Harness 注入口（测试专用；产品路径零调用）：即刻强制一轮进度拉取。
 *  @param {boolean} [force] true = 越过新鲜度门（offset 增量断言用）。 */
export function __pollDelegateProgress(force) {
  return pollDelegateProgress(force === true);
}

/** Empty-state builder（兜底保留，2026-09-05 10:54 裁定「空态兜底保留」）：
 *  主路径不接——无行时面板整体收起（.has-tasks 才展开，与 67e69bf1 行为
 *  一致）。键 task.progressEmpty 保留（zh/en 成对），供后续区块复用。 */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render（delegate 行主面，换源即切）─────────────────────────────

/**
 * 渲染任务面板 = delegate 行主面（S1）。首参为旧任务入参占位（_tasks 忽略，
 * 调用方兼容）。数据 = listDelegateRows() 读取口（live provider / 夹具）。
 * @param {Array} [_tasks] 旧任务入参——2026-09-05 裁定退役，忽略
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — gates panel visibility
 */
export function renderTaskList(_tasks, container, sessionId) {
  if (!container) container = document.getElementById('task-list');
  if (!container) return;

  lastPanelSessionId = sessionId || null;
  // 2026-09-02: Nebula 统一面板显示面板行（成员会话面板不显示）——delegate 行
  // 主面沿用同门。数据面 = setSource 换源后的 live provider（REST /api/chains
  // 快照镜像）；镜像刷新（boot/WS 触发/重连）经 delegate-chain-mirror-updated
  // 事件驱动重渲，本函数零 WS 知识。
  const showRows = sessionShowsNodeEntries(sessionId);
  // 用户九点①（2026-10-09）：任务列表只显示在跑的 delegate——终态行（completed/
  // failed/cancelled）随完成移出列表；归档行（archivedAt 在场）不入列。过滤单点 =
  // 本渲染入口（读取口/镜像保持全量在册——S11 结构视图与归档窗不受影响）。
  const delegateRows = (showRows ? listDelegateRows() : [])
    .filter((r) => r && ACTIVE_ROW_STATUSES.has(String(r.status)) && r.archivedAt == null);

  // delegate 行主面：统计/行全 delegate 口径（统计口径归换源批——a60af705d 申报面）。
  // 无行 → 面板收起（.has-tasks 才展开，既有空态兜底）。
  container.classList.toggle('has-tasks', delegateRows.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(delegateRows.length > 0);
  if (delegateRows.length === 0) {
    container.innerHTML = '';
    stopDelegateProgressTimer();   // 面板空态收起 ⇒ S16 进度轮询一并停表
    return;
  }

  redraw(container, delegateRows);
}

/** Map row key → 存在性 from the current DOM (entry-animation comparison:
 *  已存在的行不重播入场动画；键 = 节点行 data-node-key ‖ delegate 行
 *  data-delegate-key——行族同源设计语言下两类键共用一个比较集）。 */
function collectRowKeys(container) {
  const keys = new Set();
  container.querySelectorAll('.task-node[data-node-key], .task-delegate[data-delegate-key]')
    .forEach((el) => {
      const k = /** @type {HTMLElement} */ (el).dataset;
      keys.add(String(k.nodeKey || k.delegateKey || ''));
    });
  return keys;
}

/** @param {any[]} delegateRows S1 delegate 行（delegateChainStub 读取口产出） */
function redraw(container, delegateRows) {
  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats（统计 = delegate 行数，换源即切口径；
  //  键复用 task.statsProgress「{progress} 任务」——语义仍成立）──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: delegateRows.length });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: delegate 区块（S1 主面；并行一窗已随晨裁②摘除，节点区块零渲染）──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const delegateSection = buildDelegateSection(delegateRows);
  if (delegateSection) inner.appendChild(delegateSection);

  body.appendChild(inner);
  card.appendChild(header);
  card.appendChild(body);
  container.appendChild(card);

  createIconsIn(container);
  // S16 外部行进度轮询挂钩（有外部 running 行才在跑；新鲜度门内不放大请求）。
  ensureDelegateProgressLoop();

  toggle.addEventListener('click', () => {
    const nowCollapsed = card.classList.toggle('collapsed');
    container.dataset.collapsed = nowCollapsed ? '1' : '';
    toggle.innerHTML = `<i data-lucide="${nowCollapsed ? 'chevron-down' : 'chevron-up'}"></i>`;
    toggle.title = nowCollapsed ? t('task.expand') : t('task.collapse');
    createIconsIn(toggle);
  });

  // Entry animation for brand-new rows（delegate 行沿用节点行 task-node-entering
  // 动画槽——行族同源；比较键含 data-delegate-key，重渲不重播）。
  if (!REDUCED_MOTION) {
    const existingKeys = collectRowKeys(container);
    const items = /** @type {NodeListOf<HTMLElement>} */ (
      container.querySelectorAll('.task-node'));
    items.forEach((el) => {
      const k = /** @type {HTMLElement} */ (el).dataset;
      if (existingKeys.has(String(k.nodeKey || k.delegateKey || ''))) return;
      el.classList.add('task-node-entering');
      el.addEventListener('animationend', () => el.classList.remove('task-node-entering'), { once: true });
    });
  }
}
