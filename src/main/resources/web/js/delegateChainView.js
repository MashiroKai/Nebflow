// delegateChainView.js — S10 per-task 链视图 ＋ S11 结构总视图
//（#1228 换源接线批 · projects 标签页就地视图新分支 `delegate-chain`）。
//
// 定位（追认设计稿 §2.1/§2.3/§2.4）：
//   - S10：点进 delegate = **该条链**的 flowmap（per-task 数据源，非 per-project）。
//     复用 projectTab 就地视图骨架（.flowmap-nav-bar 返回条 + 视图体）；卡 = ChainNode
//     （任务＋描述＋状态＋执行器），卡渲染走 **fmNodeCard 单源拆出件**（与主图同一
//     渲染件，禁复制双权威）。数据走 delegateChainStub.getChainByTask 读取口——
//     N1 per-task 读面未落 ⇒ 查无链时 fail-soft 态亮 §16 键 7（行保留可重试）。
//   - S11：结构总视图 = worktree 座（spawn 行 branch/worktree/cwd 投影）＋ FIFO
//     合并泳道（mergeQueueFifo readyAt↑ 单点已钉）＋各 delegate 链卡。头 = §16 键 3、
//     泳道头 = 键 4、座标 = 键 5。前端**零派生第二权威**：排序单点 = stub
//     mergeQueueFifo（视图投影；真源排序在引擎 MergeQueue，REST 到位后 projection
//     改透传）。
//   - 顶栏返回钮 = §16 键 2「返回任务列表」（关 projects 页签 = 回任务列表面板）。
//
// 纪律：文案只消费 §16 七键＋§3.3 复用清单；其余显示面全部数据原文（taskId/branch/
// cwd/kind 等——数据非文案）。行源/链源候 N1：setSource 单缝不动，live provider
// 缺位 = stub/夹具态。flowAnim 零依赖（静态卡列表，无边飞行/轨道驱动）。

import { openTab, getTabPane, closeTab } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { NODE_STATUS_CLS } from './nodeData.js';
import {
  listDelegateRows, getChainByTask, listDelegateChildren, mergeQueueFifo, verdictToStatus,
} from './delegateChainStub.js';
import { nodeHtml } from './fmNodeCard.js';
import { executorIcon } from './executorIcons.js';
import { fetchArchivedDelegates, unarchiveDelegate } from './delegateApi.js';
// file-diff feature: live GUI entry for the node detail layer (archive FAB +
// panel + detail with the changed-file diff section) inside this chain view.
import { attachChainDetailLayer } from './chainDetailEntry.js';

// ── delegate/flowmap 两层架构批（2026-10-09 · 规格 §C/§D/§E）──────────────────
// L1 = delegate 专属流程视图（一期卡列表形态顶位，画布化另批）：根卡 + subagent
// 调用卡（同名重试聚合并 retry 徽标）+ AgentFlow 链卡（下钻 flow-step 层）；L2 =
// 平铺照抄 + 三段分组 + 唯一归档面（FAB → 抽屉：流程图/恢复）+ 终态自动归档到
// 达反馈（徽章脉冲 + toast 带 5s 撤销）。自动记录语义数据源 = G3 children ∪ 链
// nodes ∪ 行投影（前端组装投影——H.1 缺口 G1 的 P0 臂）；缺写面（G3 无角色/executor）
// 如实降级不臆造。

/** 子条目 source 数据词归一（九点②同判据；main.js bgChildKindWord 面板同族映射
 *  ——「subtask」UI 标注更名 delegate 语义，数据原文保留在 title）。 */
function childSourceWord(source) {
  const s = String(source || '');
  if (s === 'subtask' || s === 'delegate') return 'Delegate';
  return s;
}

/**
 * G3 子代理条目 → 聚合卡载荷（§C 自动记录语义：**同名重试聚合同卡**）。徽标数据
 * 全取 G3：calls = 同名条目集（×N 调用数）、retrySum = Σ retryCount（直和，零派生
 * 估读——G5/G6 红线：无审计数据不显数字）。状态聚合 = 展示级归并（failed 优先、
 * 在跑次之、全终态 completed——词面取 subagents.status.* 既有键）。
 * @param {any[]} kids @returns {Array<{name: string, calls: any[], retrySum: number, status: string}>}
 */
function aggregateKids(kids) {
  const byName = new Map();
  const order = [];
  for (const c of (Array.isArray(kids) ? kids : [])) {
    const src = c && typeof c === 'object' ? c : {};
    const name = String(src.agentName || src.taskId || '');
    if (!name) continue;
    if (!byName.has(name)) {
      byName.set(name, { name, calls: [], retrySum: 0 });
      order.push(name);
    }
    const agg = byName.get(name);
    agg.calls.push(src);
    agg.retrySum += typeof src.retryCount === 'number' ? src.retryCount : 0;
  }
  return order.map((name) => {
    const agg = byName.get(name);
    const statuses = new Set(agg.calls.map((c) => String(c.status || '')));
    agg.status = statuses.has('failed') ? 'failed'
      : (statuses.has('running') || statuses.has('restarting')) ? 'running'
        : 'completed';
    return agg;
  });
}

/** 卡内行内徽标（L2 卡 + L1 根卡共用；与 taskList 行内徽标同件同语义——users+N /
 *  flow+N，缺读数不渲染）。 */
const CARD_USERS_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>';
const CARD_FLOW_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="16" y="16" width="6" height="6" rx="1"/><rect x="2" y="16" width="6" height="6" rx="1"/><rect x="9" y="2" width="6" height="6" rx="1"/><path d="M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3"/><path d="M12 12V8"/></svg>';

/** delegate 行 → 卡面 kids/flow 徽标 HTML（head 行内；数据非文案——数字直显）。 */
function cardBadgesHtml(r) {
  if (!r || r.external === true) return '';
  const delegateId = String(r.delegateId || '');
  const kids = delegateId ? listDelegateChildren(delegateId) : [];
  const kidNames = kids.map((c) => String((c && (c.agentName || c.taskId)) || '')).filter(Boolean);
  const kidsBadge = kids.length
    ? `<span class="delegate-card-kids" title="${esc(`${t('delegate.children')} ×${kids.length}${kidNames.length ? ` — ${kidNames.join(', ')}` : ''}`)}">${CARD_USERS_SVG}<span>${kids.length}</span></span>`
    : '';
  const key = String((r.taskNo || delegateId) || '');
  const chain = key ? getChainByTask(key) : null;
  const flowSteps = chain && Array.isArray(chain.nodes)
    ? chain.nodes.filter((n) => n && n.kind === 'flow-step') : [];
  const flowBadge = flowSteps.length
    ? `<span class="delegate-card-flow" title="${esc(t('delegate.chainSteps', { n: String(flowSteps.length) }))}">${CARD_FLOW_SVG}<span>${flowSteps.length}</span></span>`
    : '';
  return kidsBadge + flowBadge;
}

/** 视图体滚动容器选择器（pane 内唯一）。 */
const BODY_SEL = '.delegate-chain-view-body';

/** 当前在图的 delegate-chain pane（镜像事件路由锚；openPane 建架即指向，页签关闭
 *  后 isConnected 守卫兜底——镜像事件路由单点，pane 重建零监听堆积）。 */
let activeChainPane = null;

/** 行状态 → fm-node 卡状态词（复用 FM_NODE_ST_KEY 值域之外的面板词表）。 */
function rowWord(st) {
  const key = {
    pending: 'task.nodePending',
    running: 'task.inProgressShort',
    blocked: 'task.nodeBlocked',
    completed: 'flowmap.done',
    failed: 'flowmap.fail',
    cancelled: 'flowmap.st.cancelled',
    'ready-to-merge': 'delegate.readyToMerge',
  }[String(st || '')];
  return key ? t(key) : '';
}

// ── delegate-ui 修订批（用户九点②③④⑧ · 2026-10-09）────────────────────────
// ② logo 去重：内部执行器（nebflow）的 executor 徽标与卡面自带的轨道/状态视觉
//    同形（nebflow logo = 轨道圆点），S10/S11 卡面双 logo 叠放——内部执行器不再
//    重复挂牌，外部执行器徽标保留（异构执行器身份仍有信息量）。
/** 执行器 head 徽标（fm-node head 行首；title = 执行器 id 数据）。内部执行器
 *  （nebflow/空）= '' ——卡面轨道视觉已表意，去重（九点②）。 */
function executorHeadHtml(executor) {
  const id = String(executor || '');
  if (!id || id === 'nebflow') return '';
  return `<span class="delegate-card-executor" title="${esc(id)}">${executorIcon(id, 12)}</span>`;
}

/** 待合并排队名次 chip（九点②④⑧：合并态只显名次）：head 行内「待合并」旁的
 *  `#N`。位次 = 引擎 G5 mergeQueuePos.position 原样（透传臂）；回落臂（行无该键、
 *  链行投影 FIFO 序）由调用方给 rankOf 回落值。缺位次 = ''（🔴 禁派生数字）。 */
function queueRankHtml(rank) {
  const pos = Number(rank || 0);
  if (!Number.isInteger(pos) || pos < 1) return '';
  return `<span class="delegate-card-rank" title="#${pos}">#${pos}</span>`;
}

/** ChainNode/行 → fm-node 卡载荷（fmNodeCard.nodeHtml 消费形）。执行器图标进
 *  head 行首（headPrefix）；subText = kind/project 数据（'' = 副行省略）。 */
function delegateCardPayload(n) {
  const src = n && typeof n === 'object' ? n : {};
  return {
    id: String(src.id || ''),
    name: String(src.label || src.name || src.id || ''),
    status: String(src.status || 'pending'),
    description: String(src.description || ''),
    deps: Array.isArray(src.deps) ? src.deps : [],
    in: [],
    executor: String(src.executor || ''),
    kind: String(src.kind || ''),
  };
}

/** Spawn-row plugins/allocation segments (jev-plugins additive payload keys).
 *  plugins array joins ', ' per the existing plugins list style (flowMapArchive
 *  detail card); allocation is a scalar mode word (jev / llm-fallback / fail-open).
 *  Prefixes are raw payload key names — data, not copy (no new locale keys;
 *  view copy discipline: §16 seven keys + data原文 only). Empty → segment absent. */
function spawnAllocBits(w) {
  const src = w && typeof w === 'object' ? w : {};
  const plugins = Array.isArray(src.plugins) ? src.plugins.filter(Boolean) : [];
  return [
    plugins.length ? `plugins: ${plugins.join(', ')}` : '',
    src.allocation ? `allocation: ${src.allocation}` : '',
  ];
}

/** 打开（或复用）projects 页签并铺 delegate-chain 视图骨架（复用 projectTab 就地
 *  视图骨架：.flowmap-nav-bar 返回条 + 视图体）。mode = 'task' | 'structure'。
 *  两层架构批（§E）：L1 摘要标题 = 「#N · 委托流程图」；文件夹树批（2026-10-09
 *  R7）：L1 顶栏「项目流程图」结构入口钮**退役**（行内入口被树交互取代——L2 入口
 *  迁项目面板「项目流程图」圆钮 + 项目卡标题点按；归档历史态 backTo='structure'
 *  的返回路径保留——那是导航历史不是入口钮）。
 *  opts.backTo='structure' = 归档抽屉「流程图 →」来的历史态 L1——返回钮回抽屉
 *  所在 L2（规格 §E「返回回抽屉所在 L2」），其余层返回 = 关页签回任务列表。 */
function openPane(mode, taskKey, summary, opts = {}) {
  openTab('projects', t('project.title'), { type: 'projects', closable: true });
  const pane = getTabPane('projects');
  if (!pane) return null;
  const sameView = pane.dataset.projectsView === 'delegate-chain'
    && pane.dataset.delegateMode === mode
    && (mode !== 'task' || pane.dataset.delegateKey === String(taskKey || ''));
  ensureFlowCss();
  if (!sameView) {
    pane.dataset.projectsView = 'delegate-chain';
    pane.dataset.delegateMode = mode;
    pane.dataset.delegateKey = String(taskKey || '');
    pane.dataset.delegateFrom = String((opts && opts.backTo) || '');
    // 列表页 FAB 属列表视图（projectTab.attachProjectsCreateFab / attachProjectsFlowmapFab
    // 自有视图守卫会摘除；这里整体重建 pane 内 DOM，无残留）。
    const backLabel = opts.backTo === 'structure' ? t('delegate.projectflow.title') : t('delegate.backToTasks');
    const backIcon = opts.backTo === 'structure' ? '<i data-lucide="network"></i>' : '<i data-lucide="arrow-left"></i>';
    pane.innerHTML = `
      <div class="flowmap-nav-bar">
        <button class="flowmap-back-btn" data-back-to-tasks type="button" aria-label="${esc(backLabel)}">
          ${backIcon}<span class="flowmap-back-name">${esc(backLabel)}</span>
        </button>
        <span class="flowmap-summary flowmap-nav-summary" aria-live="polite">${esc(summary || '')}</span>
      </div>
      <div class="${BODY_SEL.slice(1)} delegate-mode-${mode === 'task' ? 'task' : 'structure'}"></div>`;
    pane.querySelector('[data-back-to-tasks]').addEventListener('click', (e) => {
      e.stopPropagation();
      if (opts.backTo === 'structure') openDelegateChainStructure(); // 历史态 L1 → 回抽屉所在 L2
      else closeTab('projects'); // 返回任务列表：关 projects 页签 = 回任务面板视图
    });
    // 镜像新鲜度（两层架构批）：fail-soft 态的 L1 在镜像补拉到达后自动重渲
    // （归档历史键 lazy 拉取同缝）；L2 平铺面随镜像刷新重渲（自动归档淡出/回归）。
    // 路由锚 = activeChainPane（window 级单监听，见模块尾接线——pane 重建零监听堆积）。
    activeChainPane = pane;
    createIconsIn(pane);
  } else {
    activeChainPane = pane; // 同视图复用分支同锚（pane 可能被 canvas 重建过）
    const summaryEl = pane.querySelector('.flowmap-nav-summary');
    if (summaryEl) summaryEl.textContent = summary || '';
  }
  return pane;
}

// ── S10 per-task 链视图 ──────────────────────────────────────────────────

/**
 * 打开某 delegate 行的 per-task 链视图（L1 委托流程图；taskList 行钮 / L2 链卡 /
 * 归档抽屉「流程图 →」入口）。
 * @param {string} taskKey 行键（taskNo ‖ delegateId——getChainByTask 读取口键）
 * @param {{taskNo?: string, delegateId?: string, title?: string, status?: string, executor?: string, project?: string}} [meta] 行元数据（顶栏摘要用 + file-diff 项目上下文；缺省容忍）
 * @param {{expandFlow?: boolean, anchorNode?: string, from?: string}} [opts] expandFlow =
 *        任务列表 flow 行下钻锚点（打开即展开 flow-step 层）；anchorNode = 任务列表
 *        flow 第三层「点具体 Node」锚（文件夹树批：展开 flow-step 层并定位该节点卡，
 *        sapphire 描边短闪一次性）；from='archive' = 归档抽屉来——返回钮回 L2。
 */
export function openDelegateChainTask(taskKey, meta = {}, opts = {}) {
  const key = String(taskKey == null ? '' : taskKey);
  if (!key) return;
  const m = meta && typeof meta === 'object' ? meta : {};
  const no = m.taskNo ? `#${m.taskNo}` : '';
  const summary = [no, t('delegate.flowmap.title')].filter(Boolean).join(' · ');
  const pane = openPane('task', key, summary, { backTo: opts && opts.from === 'archive' ? 'structure' : '' });
  if (!pane) return;
  // 行挂载项目名（file-diff diff 入口的报告/归档拉取上下文；'' = 无项目态，
  // 层照开、远端读取 fail-soft）。rerender 路径经同一 dataset 复用。
  pane.dataset.delegateProject = String(m.project || '');
  renderTaskBody(pane, key, {
    expandFlow: !!(opts && opts.expandFlow),
    anchorNode: String((opts && opts.anchorNode) || ''),
  });
}

/** L1 视图体渲染（两层架构批 · 规格 §C，一期 = 卡列表形态顶位）：根卡 + subagent
 *  调用卡（同名重试聚合 + retry 徽标）+ AgentFlow 链卡（下钻 flow-step 层）。
 *  数据走 getChainByTask + G3 children 读取口（§C 前端组装投影——G1 P0 臂）；
 *  查无链 → fail-soft 态（§16 键 7，点击重试；镜像补拉到达后自动重渲）。 */
function renderTaskBody(pane, key, opts = {}) {
  const body = pane.querySelector(BODY_SEL);
  if (!body) return;
  body.innerHTML = '';
  const chain = getChainByTask(key);
  if (!chain || !Array.isArray(chain.nodes) || chain.nodes.length === 0) {
    // fail-soft（FSM loaderr 终态）：行保留（面板不受影响）+ 本态可重试。读口
    // catch→null 同纪律；镜像补拉（provider lazy 拉取——含归档历史键）到达即重渲。
    pane.dataset.delegateTaskSoft = '1';
    const fail = document.createElement('button');
    fail.type = 'button';
    fail.className = 'delegate-chain-failsoft';
    fail.textContent = t('delegate.chainUnavailable'); // §16 键 7
    fail.addEventListener('click', () => {
      renderTaskBody(pane, key, opts); // 重试 = 重走读取口（provider 可能已换/夹具已装）
    });
    body.appendChild(fail);
    return;
  }
  delete pane.dataset.delegateTaskSoft;
  const wrap = document.createElement('div');
  wrap.className = 'delegate-chain-taskview';
  // worktree 座行（spawn 投影；数据原文）。
  if (chain.worktree) {
    const wt = document.createElement('div');
    wt.className = 'delegate-chain-worktree';
    const label = document.createElement('span');
    label.className = 'delegate-structure-label';
    label.textContent = t('delegate.worktree'); // §16 键 5
    const val = document.createElement('span');
    val.className = 'delegate-chain-mono';
    const bits = [chain.worktree.branch, chain.worktree.worktree || chain.worktree.cwd,
      chain.worktree.baseBranch ? `← ${chain.worktree.baseBranch}` : '',
      ...spawnAllocBits(chain.worktree)].filter(Boolean);
    val.textContent = bits.join(' · ');
    val.title = bits.join(' · ');
    wt.appendChild(label);
    wt.appendChild(val);
    wrap.appendChild(wt);
  }
  // 判词行（terminal-verdict 投影：状态词 + reason 数据）。
  if (chain.verdict) {
    const v = document.createElement('div');
    v.className = 'delegate-chain-verdict';
    const word = rowWord(verdictToStatus(chain.verdict.verdict)) || String(chain.verdict.verdict || '');
    const bits = [word, chain.verdict.reason || ''].filter(Boolean);
    v.textContent = bits.join(' — ');
    wrap.appendChild(v);
  }
  const nodes = Array.isArray(chain.nodes) ? chain.nodes : [];
  // §C 组装投影：链节点三源 = 链 nodes（flow-step 整组/回退 delegate 节点）∪ G3
  // children ∪ 行投影。根卡优先吃链内 delegate 节点（保留链投影状态/标题权威），
  // 缺席（作用域图在=nodes 全 flow-step）→ 行投影合成（数据同源，零臆造）。
  const row = listDelegateRows().find((r) => r && String(r.taskNo || r.delegateId || '') === key) || null;
  const delegateId = String(chain.delegateId || (row && row.delegateId) || key);
  const rootFromChain = nodes.find((n) => n && n.kind === 'delegate') || null;
  const kids = delegateId ? listDelegateChildren(delegateId) : [];
  const aggKids = aggregateKids(kids);
  const flowSteps = nodes.filter((n) => n && n.kind === 'flow-step');
  const rootLabel = rootFromChain
    ? String(rootFromChain.label || rootFromChain.id || '')
    : [[row && row.taskNo ? `#${row.taskNo}` : ''], String((row && (row.title || row.delegateId)) || delegateId)]
        .flat().filter(Boolean).join(' ');
  const rootStatus = rootFromChain
    ? String(rootFromChain.status || 'running')
    : (chain.verdict ? verdictToStatus(chain.verdict.verdict) : String((row && row.status) || 'running'));
  const rootExecutor = rootFromChain && rootFromChain.executor
    ? String(rootFromChain.executor)
    : String((row && row.executor) || '');
  const kidNames = kids.map((c) => String((c && (c.agentName || c.taskId)) || '')).filter(Boolean);
  const rootHead = executorHeadHtml(rootExecutor) + cardBadgesHtml({
    taskNo: String((row && row.taskNo) || ''), delegateId, external: false,
  });
  const rootPayload = delegateCardPayload({
    id: String((rootFromChain && rootFromChain.id) || delegateId),
    label: rootLabel,
    status: rootStatus,
    description: '',
    executor: rootExecutor,
    kind: 'delegate',
  });
  rootPayload.worktree = chain.worktree ? String(chain.worktree.worktree || chain.worktree.cwd || '') : '';
  const rootHolder = document.createElement('div');
  rootHolder.className = 'delegate-chain-card delegate-chain-root';
  rootHolder.innerHTML = nodeHtml(rootPayload, { x: 0, y: 0, h: 0 }, 0, null, {
    plain: true,
    headPrefix: rootHead,
    subText: 'Delegate', // 数据词不上键（H.4）
    noDesc: true,
  });
  const rootCard = /** @type {HTMLElement} */ (rootHolder.firstElementChild);
  if (rootCard) {
    rootCard.dataset.flowPopupCard = '1'; // 点击 = 对话窗（brief/结果全文——九点⑦承载）
    rootCard.setAttribute('role', 'button');
    rootCard.title = [rootLabel, kidNames.length ? `${t('delegate.children')} ×${kids.length}` : '']
      .filter(Boolean).join(' — ');
    rootCard.addEventListener('click', () => {
      import('./bgAgentPopup.js').then(({ openStepPopup }) => {
        openStepPopup(delegateId, rootLabel, String((row && row.title) || ''));
      }).catch(() => {});
    });
    wrap.appendChild(rootCard);
  }
  // subagent 调用卡（§C）：同名重试聚合同卡 + retry 徽标（Σ retryCount 数据直和）；
  // 点击 = 最新调用会话的（净化后）对话窗。角色五色点（impl/verify）随 G3 缺角色
  // 写面如实缺席（不臆造——P2 画布化批随引擎节点台账评估）。
  for (const agg of aggKids) {
    const first = agg.calls[0] || {};
    const latest = agg.calls[agg.calls.length - 1] || first;
    const callCount = agg.calls.length;
    const retryChip = agg.retrySum > 0
      ? `<span class="delegate-kid-retry" title="${esc(`${t('manage.retry')} ×${agg.retrySum}`)}">retry ×${agg.retrySum}</span>` : '';
    const callChip = callCount > 1
      ? `<span class="delegate-kid-calls" title="${esc(`×${callCount}`)}">×${callCount}</span>` : '';
    const srcWord = childSourceWord(String(first.source || ''));
    const payload = delegateCardPayload({
      id: String(first.taskId || ''),
      label: agg.name,
      status: agg.status,
      description: '',
      executor: '',
      kind: 'subagent',
    });
    const holder = document.createElement('div');
    holder.className = 'delegate-chain-card delegate-chain-kid';
    holder.innerHTML = nodeHtml(payload, { x: 0, y: 0, h: 0 }, 0, null, {
      plain: true,
      headPrefix: retryChip + callChip,
      subText: srcWord || 'Subagent', // source 数据词（九点②归一口径）
      noDesc: true,
    });
    const card = /** @type {HTMLElement} */ (holder.firstElementChild);
    if (card) {
      card.dataset.flowPopupCard = '1'; // 对话窗承载——file-diff 详情层跳过
      card.setAttribute('role', 'button');
      const desc = String(latest.description || latest.prompt || '');
      if (desc) card.title = desc;
      card.addEventListener('click', () => {
        import('./bgAgentPopup.js').then(({ openStepPopup }) => {
          openStepPopup(String(latest.taskId || ''), agg.name, desc);
        }).catch(() => {});
      });
      wrap.appendChild(card);
    }
  }
  // AgentFlow 链卡（§C）：链小点 + 「链 N 步」同一行（九点③语言）——点开下钻
  // flow-step 卡层（deps 面板行语言）。单层卡列表形态的下钻 = 就地展开（画布化另批）。
  // 文件夹树批锚点臂：expandFlow 锚或 anchorNode 命中链内 flow-step ⇒ 打开即展开。
  const anchorId = String(opts.anchorNode || '');
  if (flowSteps.length) {
    const flowOpen = !!opts.expandFlow ||
      (!!anchorId && flowSteps.some((n) => String((n && n.id) || '') === anchorId));
    const flowCard = document.createElement('div');
    flowCard.className = 'delegate-flow-chain';
    flowCard.dataset.flowChain = '1';
    const flowBtn = document.createElement('button');
    flowBtn.type = 'button';
    flowBtn.className = 'delegate-flow-chain-toggle';
    flowBtn.setAttribute('aria-expanded', flowOpen ? 'true' : 'false');
    const chip = document.createElement('span');
    chip.className = 'delegate-flow-chain-chip';
    chip.textContent = 'AgentFlow'; // 数据词（面板 flow 行同款，不上键）
    flowBtn.appendChild(chip);
    const meta = document.createElement('span');
    meta.className = 'delegate-chain-meta';
    meta.dataset.chainMeta = '1';
    for (const n of flowSteps) {
      const dot = document.createElement('span');
      dot.className = `delegate-chain-dot st-${String((n && n.status) || 'pending')}`;
      dot.title = String((n && (n.label || n.id)) || '');
      meta.appendChild(dot);
    }
    const count = document.createElement('span');
    count.className = 'delegate-chain-steps';
    count.textContent = t('delegate.chainSteps', { n: String(flowSteps.length) });
    meta.appendChild(count);
    flowBtn.appendChild(meta);
    const chev = document.createElement('span');
    chev.className = 'delegate-flow-chain-chev';
    chev.setAttribute('aria-hidden', 'true');
    chev.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>';
    flowBtn.appendChild(chev);
    flowCard.appendChild(flowBtn);
    const stepsWrap = document.createElement('div');
    stepsWrap.className = 'delegate-flow-steps';
    stepsWrap.hidden = !flowOpen; // 下钻层（flow 行锚点/节点锚/点开才展）
    for (const n of flowSteps) {
      const payload = delegateCardPayload(n);
      const holder = document.createElement('div');
      holder.className = 'delegate-chain-card';
      holder.innerHTML = nodeHtml(payload, { x: 0, y: 0, h: 0 }, 0, null, {
        plain: true,
        headPrefix: executorHeadHtml(n.executor),
        subText: String(n.kind || ''),
        noDesc: true,
      });
      const card = holder.firstElementChild;
      if (card) stepsWrap.appendChild(card);
    }
    flowCard.appendChild(stepsWrap);
    flowBtn.addEventListener('click', () => {
      const open = stepsWrap.hidden;
      stepsWrap.hidden = !open;
      flowBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    wrap.appendChild(flowCard);
  } else if (!aggKids.length && rootCard) {
    // §C ⑤ 空态延伸：单节点 L1 = 根卡 + 一行提示（不画空态插图——零自造数据）。
    const hint = document.createElement('div');
    hint.className = 'delegate-flow-empty';
    hint.textContent = t('delegate.kids.empty');
    wrap.appendChild(hint);
  }
  // 候定集/未知帧透传列表随九点③撤销：「节点下方的文字解释全部删掉」——kind+时刻
  // 说明行不再入视图（审计面在引擎 ChainStore，读取口 frames 投影保持原样零改动）。
  body.appendChild(wrap);
  // file-diff feature (rework): the chain view body hosts the single-source
  // node detail layer — flow-step cards open .fm-detail (completed nodes mount
  // their changed-file diff section inside it; 404/no project = the section
  // silently stays absent). Root/subagent cards carry data-flow-popup-card="1"
  // (conversation popup is their detail carrier) and are skipped by the layer.
  attachChainDetailLayer(body, String(pane.dataset.delegateProject || ''), chain.nodes);
  // 节点锚点（文件夹树批：任务列表 flow 第三层「点具体 Node」跳转锚）——目标卡
  // 滚入视线 + sapphire 描边短闪（一次性；重渲路径不带锚自然失效）。锚不在链内
  // （链已换/夹具未装）= 静默缺席，层照开。
  if (anchorId) {
    const target = body.querySelector(
      `.fm-node[data-node-id="${(window.CSS && CSS.escape ? CSS.escape(anchorId) : anchorId)}"]`);
    if (target) {
      target.scrollIntoView({ block: 'center' });
      target.classList.add('delegate-node-anchor');
      setTimeout(() => target.classList.remove('delegate-node-anchor'), 1800);
    }
  }
}

// ── S11 结构总视图 ───────────────────────────────────────────────────────

/**
 * 打开 delegate 结构总视图（S11 改义面）：worktree 座 ＋ FIFO 合并泳道 ＋ 各
 * delegate 链卡。数据 = listDelegateRows × getChainByTask 投影（stub 读取口）。
 */
export function openDelegateChainStructure() {
  const pane = openPane('structure', '', '');
  if (!pane) return;
  renderStructureBody(pane);
}

/** S11 视图体渲染。
 *  用户九点⑤（2026-10-09）：空态自动收起——零行不渲染空态区块（视图体留白，
 *  面板返回条仍在；重试面只留给「有行但读取失败」的真实故障态）。 */
function renderStructureBody(pane) {
  const body = pane.querySelector(BODY_SEL);
  if (!body) return;
  body.innerHTML = '';
  const rows = listDelegateRows();
  if (!rows.length) {
    // 用户九点⑤空态自动收起的 §F⑤ 延伸：L2 空 = 留白 + 抽屉引用（归档积累后
    // 历史仍可达——只挂归档 FAB，零空态插图）。
    attachStructureArchive(body);
    return;
  }

  const head = document.createElement('div');
  head.className = 'delegate-structure-head';
  // 两层架构批（§E 两层视觉签名）：L2 标题 = 「项目名 · 项目流程图」（全行同挂载
  // 项目时带项目名，多项目/无挂载 = 裸「项目流程图」——数据面降级，零臆造）。
  const projects = [...new Set(rows.map((r) => String((r && r.project) || '')).filter(Boolean))];
  head.textContent = projects.length === 1
    ? `${projects[0]} · ${t('delegate.projectflow.title')}`
    : t('delegate.projectflow.title');
  body.appendChild(head);

  // 各行链投影（行键 → ChainView|null）。
  const projections = rows.map((r) => {
    const key = String((r && (r.taskNo || r.delegateId)) || '');
    return { row: r, key, chain: key ? getChainByTask(key) : null };
  });

  // ── worktree 座（spawn 行 branch/worktree/cwd 投影；§16 键 5 座标）──
  const seats = projections.filter((p) => p.chain && p.chain.worktree);
  if (seats.length) {
    const section = document.createElement('div');
    section.className = 'delegate-structure-section';
    const seatHead = document.createElement('div');
    seatHead.className = 'delegate-structure-label';
    seatHead.textContent = t('delegate.worktree'); // §16 键 5
    section.appendChild(seatHead);
    for (const p of seats) {
      const el = document.createElement('div');
      el.className = 'delegate-structure-seat';
      // 九点② logo 去重：内部执行器（nebflow）徽标留空（与卡面同口径）。
      const execId = String(p.row.executor || '');
      if (execId && execId !== 'nebflow') {
        const icon = document.createElement('span');
        icon.className = 'delegate-seat-executor';
        icon.setAttribute('aria-hidden', 'true');
        icon.innerHTML = executorIcon(execId, 12);
        el.appendChild(icon);
      }
      const val = document.createElement('span');
      val.className = 'delegate-chain-mono';
      const w = p.chain.worktree;
      const bits = [p.row.taskNo ? `#${p.row.taskNo}` : '', w.branch,
        w.worktree || w.cwd, w.baseBranch ? `← ${w.baseBranch}` : '',
        ...spawnAllocBits(w)].filter(Boolean);
      val.textContent = bits.join(' · ');
      val.title = bits.join(' · ');
      el.appendChild(val);
      section.appendChild(el);
    }
    body.appendChild(section);
  }

  // ── 合并态（用户九点②④⑧ · 2026-10-09）：**FIFO 泳道撤销、不连线**——合并
  //  结构不再整段陈列（无泳道/无连线/无 branch·readyAt 明细），唯一可见面 =
  //  待合并链卡旁的排队名次 chip（#N）。位次取值两臂（与旧泳道同源，零新判据）：
  //    G5 透传臂：行内 mergeQueuePos.position（引擎闸单点判据，🔴 前端零派生）；
  //    回落臂：行无该键（旧引擎/夹具）→ 既有链行投影 mergeQueueFifo（readyAt ↑）
  //    的序位（旧泳道回落臂同款 `i+1`，语义不变）。
  const rankByDelegate = new Map(); // delegateId → position
  const queuedRows = rows.filter((r) => r && r.mergeQueuePos);
  for (const r of queuedRows) {
    const id = String(r.delegateId || '');
    const pos = typeof r.mergeQueuePos.position === 'number' ? r.mergeQueuePos.position : 0;
    if (id && pos > 0) rankByDelegate.set(id, pos);
  }
  if (rankByDelegate.size === 0) {
    // 回落臂 = 既有链行投影全量合并后 mergeQueueFifo 单点（readyAt ↑，与旧泳道同
    // 序——跨链全局序位，非逐链局部序）。
    const merged = [];
    for (const p of projections) {
      if (p.chain && Array.isArray(p.chain.mergeQueue)) merged.push(...p.chain.mergeQueue);
    }
    mergeQueueFifo(merged).forEach((q, i) => {
      const id = String(q.delegateId || '');
      if (id && !rankByDelegate.has(id)) rankByDelegate.set(id, i + 1);
    });
  }

  // ── 各 delegate 链卡（fmNodeCard 单源；点击 → L1 该 delegate 流程视图）──
  // 两层架构批（§D WF-05）：**三段分组**取代过渡期 project 参数分组——组序 = 生命
  // 周期（在跑 pending/running/blocked → 待合并 ready-to-merge → 已完成·未归档暂驻
  // completed/failed/cancelled），组标签 = .delegate-structure-label 现语言（词面全
  // 复用既有键，零新键）；段内序 = 行序首现（零前端派生）。卡面新增子代理/flow 徽标
  // （与 §B 行内徽标同件同语义）；名次 chip（九点④口径）只属待合并段。
  const SEGMENTS = [
    { key: 'running', wordKey: 'task.inProgressShort', test: (st) => st !== 'ready-to-merge' && st !== 'completed' && st !== 'failed' && st !== 'cancelled' },
    { key: 'merge', wordKey: 'delegate.readyToMerge', test: (st) => st === 'ready-to-merge' },
    { key: 'done', wordKey: 'flowmap.done', test: (st) => st === 'completed' || st === 'failed' || st === 'cancelled' },
  ];
  const list = document.createElement('div');
  list.className = 'delegate-chain-list delegate-structure-tiles';
  for (const seg of SEGMENTS) {
    const arr = projections.filter((p) => seg.test(String((p.row && p.row.status) || '')));
    if (!arr.length) continue; // 空段不渲染组头（零空段）
    const gh = document.createElement('div');
    gh.className = 'delegate-structure-label delegate-structure-group delegate-structure-seg';
    gh.dataset.segment = seg.key;
    gh.textContent = t(seg.wordKey);
    list.appendChild(gh);
    for (const p of arr) {
      const r = /** @type {any} */ (p.row || {});
      const payload = delegateCardPayload({
        id: p.key,
        label: [r.taskNo ? `#${r.taskNo}` : '', r.title || r.delegateId || p.key].filter(Boolean).join(' '),
        status: r.status,
        description: '',
        executor: r.executor,
        kind: 'delegate',
      });
      const holder = document.createElement('div');
      holder.className = 'delegate-chain-card';
      holder.innerHTML = nodeHtml(payload, { x: 0, y: 0, h: 0 }, 0, null, {
        plain: true,
        // head 行首 = 执行器徽标（内部执行器去重留空，九点②）＋ 待合并名次 chip
        // （九点②④⑧：合并态只显名次，位次 = rankByDelegate 两臂）＋ 子代理/flow
        // 徽标（§D：与 §B 行内徽标同件同语义）。
        headPrefix: executorHeadHtml(r.executor) +
          (r.status === 'ready-to-merge' ? queueRankHtml(rankByDelegate.get(String(r.delegateId || ''))) : '') +
          cardBadgesHtml(r),
        subText: String(r.project || ''),
      });
      const card = holder.firstElementChild;
      if (card) {
        const external = r.external === true;
        if (!external) {
          card.setAttribute('role', 'button');
          card.addEventListener('click', () => {
            openDelegateChainTask(p.key, {
              taskNo: r.taskNo, delegateId: r.delegateId, title: r.title,
              status: r.status, executor: r.executor, project: r.project,
            });
          });
        } else {
          // G7：外部行无子结构下钻——本体＋合并结构已在上文座/泳道，链卡零入口。
          card.setAttribute('data-external-card', '1');
        }
        list.appendChild(card);
      }
    }
  }
  if (list.children.length) body.appendChild(list);
  // 两层架构批（§D）：右上角归档件升级为唯一归档面 = FAB → 抽屉（流程图/恢复两栏；
  // 终态自动归档后卡淡出、徽章脉冲、toast 带 5s 撤销——见 attachStructureArchive）。
  attachStructureArchive(body);
}

/** projectTab.rerenderProjectsTab 视图分流挂钩：按 pane 记忆的模式重渲当前视图
 *  （数据面 = stub 读取口现读；WS/live 增量属 N1 结构帧落地后的 provider 面）。 */
export function rerenderDelegateChainPane(pane) {
  if (!pane || pane.dataset.projectsView !== 'delegate-chain') return;
  if (pane.dataset.delegateMode === 'task') renderTaskBody(pane, String(pane.dataset.delegateKey || ''));
  else if (pane.dataset.delegateMode === 'structure') renderStructureBody(pane);
}

// ── L2 唯一归档面（两层架构批 · 2026-10-09 §D WF-07）：FAB → 右侧抽屉 ──────────
// 视觉 = flowMapArchive .fm-fab 同语言（玻璃圆钮 + 归档 icon + 计数徽章，ARCHIVE_ICON
// 同一 path——零复制双权威：icon 常量 import 单源）。点开 = 右侧抽屉：归档 delegate
// 清单（GET /api/chains?archived=1 历史窗），行动作 ×2 = 「流程图 →」（打开该
// delegate 的 L1 只读历史快照——返回回抽屉所在 L2）+「恢复」（清除 archivedAt 回
// 平铺面，G4-2 反归档引擎缝）。终态自动归档到达 = 徽章脉冲 + toast（5s 撤销）。
// 手动归档件全量退役（弹窗头小图标/S11 详情条钮已撤）——归档动作全部后台化，用户
// 唯一主动作 = 抽屉「恢复」。
// 样式自注入（本视图无独立 CSS 载体；flowMap.css 的 .fm-fab 定位在 .fm-float-layer
// 宿主语境，本视图 body 直接宿主——不挪用其类名，避免层叠面耦合）。
const STRUCT_ARCHIVE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="20" height="5" x="2" y="3" rx="1"/><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"/><path d="M10 12h4"/></svg>';
const STRUCT_ARCHIVE_CSS_ID = 'delegate-structure-archive-css';
const STRUCT_ARCHIVE_CSS = `
.delegate-chain-view-body { position: relative; }
/* 两层视觉签名（§E）：L1 = 画布式深底 + 径向蓝晕；L2 = 平铺文档流（无底色）。 */
.delegate-chain-view-body.delegate-mode-task {
  background:
    radial-gradient(120% 90% at 50% -10%, rgb(var(--sapphire, 88 166 255) / 0.09), transparent 60%),
    var(--color-surface, transparent);
  border-radius: 12px;
  padding: 14px;
}
.delegate-chain-view-body.delegate-mode-structure { padding: 2px 2px 8px; }
.delegate-archive-fab {
  position: absolute; top: 12px; right: 14px;
  width: 34px; height: 34px; border-radius: 50%;
  border: 1px solid var(--glass-control-border, rgba(128,128,128,0.35));
  background: var(--glass-control-bg, transparent);
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; z-index: 45; padding: 0;
  color: var(--color-text); opacity: 0.6; transition: all 0.2s ease;
}
.delegate-archive-fab svg { width: 16px; height: 16px; display: block; }
.delegate-archive-fab:hover { background: var(--glass-control-bg-hover, rgba(128,128,128,0.12)); opacity: 1; transform: scale(1.05); }
.delegate-archive-fab.active { opacity: 1; color: rgb(var(--sapphire)); border-color: rgb(var(--sapphire) / 0.35); background: rgb(var(--sapphire) / 0.12); transform: none; }
.delegate-archive-badge {
  position: absolute; top: -5px; right: -5px; min-width: 15px; height: 15px; padding: 0 4px;
  border-radius: 8px; background: rgb(var(--sapphire)); color: #fff;
  font: 500 9px/15px -apple-system, BlinkMacSystemFont, sans-serif; text-align: center;
  font-variant-numeric: tabular-nums; pointer-events: none;
}
/* 自动归档到达反馈（§D：fm-fab 徽章脉冲——flowMapArchive 到达反馈同款语言）。 */
@keyframes delegate-archive-badge-pulse {
  0% { transform: scale(1); box-shadow: 0 0 0 0 rgb(var(--sapphire) / 0.55); }
  60% { transform: scale(1.35); box-shadow: 0 0 0 8px rgb(var(--sapphire) / 0); }
  100% { transform: scale(1); box-shadow: 0 0 0 0 rgb(var(--sapphire) / 0); }
}
.delegate-archive-badge.pulse { animation: delegate-archive-badge-pulse 1.1s ease-out 2; }
/* 归档抽屉（唯一归档视图）：右侧滑出面板，清单 + 行动作两栏（流程图/恢复）。 */
.delegate-archive-drawer {
  position: absolute; top: 0; right: 0; bottom: 0; width: 340px; max-width: 86%;
  z-index: 46; overflow-y: auto; padding: 12px 12px 16px;
  background: var(--glass-bg, var(--color-surface));
  border-left: 1px solid var(--color-border);
  box-shadow: -12px 0 36px rgba(0, 0, 0, 0.28);
  display: flex; flex-direction: column; gap: 2px;
  animation: delegate-drawer-in 0.18s ease;
}
@keyframes delegate-drawer-in { from { transform: translateX(24px); opacity: 0; } to { transform: none; opacity: 1; } }
.delegate-archive-drawer-head {
  display: flex; align-items: center; gap: 8px; min-width: 0;
  font: 600 12px/1.6 -apple-system, BlinkMacSystemFont, sans-serif; letter-spacing: 0.02em;
  color: var(--color-text); padding: 0 4px 8px;
}
.delegate-archive-drawer-close {
  margin-left: auto; flex-shrink: 0; width: 22px; height: 22px;
  display: inline-flex; align-items: center; justify-content: center;
  border: none; border-radius: 6px; background: transparent; color: var(--color-text-muted);
  cursor: pointer;
}
.delegate-archive-drawer-close:hover { background: var(--color-frame-hover, rgba(128,128,128,0.12)); color: var(--color-text); }
.delegate-archive-drawer-close svg { width: 12px; height: 12px; }
/* hidden 属性守卫：author display:flex 会压过 UA 的 [hidden]{display:none}。 */
.delegate-archive-drawer[hidden] { display: none; }
.delegate-archive-row {
  display: flex; align-items: center; gap: 8px; min-width: 0;
  padding: 7px 6px; border-radius: 8px; border: none;
  background: transparent; color: inherit; width: 100%;
}
.delegate-archive-row:nth-child(odd) { background: var(--color-frame-hover, rgba(128,128,128,0.06)); }
.delegate-archive-row-main { min-width: 0; flex: 1 1 auto; display: flex; flex-direction: column; gap: 1px; }
.delegate-archive-row-title {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font: 400 12px/1.5 -apple-system, BlinkMacSystemFont, sans-serif; color: var(--color-text);
}
.delegate-archive-row-meta {
  font: 500 10px/1.5 ui-monospace, SFMono-Regular, monospace;
  color: var(--color-text-muted);
}
.delegate-archive-row-actions { flex-shrink: 0; display: inline-flex; gap: 4px; }
.delegate-archive-act {
  flex-shrink: 0; padding: 2px 8px; border-radius: 7px; cursor: pointer;
  font: 500 10.5px/1.6 -apple-system, BlinkMacSystemFont, sans-serif;
  background: var(--glass-control-bg, transparent);
  border: 1px solid var(--glass-control-border, rgba(128,128,128,0.35));
  color: var(--color-frame-text-muted, inherit);
}
.delegate-archive-act:hover:not(:disabled) { background: var(--color-frame-hover, rgba(128,128,128,0.12)); color: rgb(var(--sapphire)); border-color: rgb(var(--sapphire) / 0.4); }
.delegate-archive-act:disabled { opacity: 0.5; cursor: default; }
.delegate-archive-empty { padding: 10px 6px; font: 400 12px/1.5 -apple-system, BlinkMacSystemFont, sans-serif; color: var(--color-text-muted); }
/* 自动归档到达 toast（§D：fm-exit 到达反馈——「#N 已完成并归档」+ 5s 撤销）。 */
.delegate-archive-toast {
  position: absolute; left: 50%; bottom: 18px; transform: translateX(-50%);
  z-index: 47; display: flex; align-items: center; gap: 10px; min-width: 0;
  padding: 8px 14px; border-radius: 12px;
  background: var(--glass-bg, var(--color-surface));
  border: 1px solid var(--color-border);
  box-shadow: 0 10px 36px rgba(0, 0, 0, 0.35);
  font: 400 12px/1.5 -apple-system, BlinkMacSystemFont, sans-serif; color: var(--color-text);
  animation: delegate-drawer-in 0.18s ease;
}
.delegate-archive-toast[hidden] { display: none; }
.delegate-archive-toast-undo {
  flex-shrink: 0; padding: 1px 10px; border-radius: 8px; cursor: pointer;
  font: 500 11px/1.7 -apple-system, BlinkMacSystemFont, sans-serif;
  background: rgb(var(--sapphire) / 0.12); color: rgb(var(--sapphire));
  border: 1px solid rgb(var(--sapphire) / 0.35);
}
.delegate-archive-toast-undo:hover { background: rgb(var(--sapphire) / 0.2); }
/* L1 subagent 调用卡徽标（§C：retry ×N / ×N calls——G3 数据直和/条目数）。 */
.delegate-kid-retry, .delegate-kid-calls {
  flex-shrink: 0; padding: 0 5px; border-radius: 6px;
  background: var(--color-frame-hover, rgba(128,128,128,0.12));
  font: 600 9px/15px ui-monospace, SFMono-Regular, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-muted);
}
.delegate-kid-retry { color: rgb(var(--amber, 212 160 48) / 1); }
/* L1 节点锚点短闪（文件夹树批：任务列表 flow 第三层「点 Node」跳转锚——目标卡
   sapphire 描边 ~1.8s，零新色值）。 */
.fm-node.delegate-node-anchor {
  outline: 1.5px solid rgb(var(--sapphire, 88 166 255) / 0.55);
  outline-offset: 2px;
}
/* L1 AgentFlow 链卡（九点③语言：链小点+「链 N 步」同行）+ 下钻 flow-step 层。 */
.delegate-flow-chain {
  align-self: stretch;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 8px 10px;
  display: flex; flex-direction: column; gap: 8px;
  background: var(--color-frame-hover, rgba(128,128,128,0.05));
}
.delegate-flow-chain-toggle {
  display: flex; align-items: center; gap: 10px; min-width: 0; width: 100%;
  padding: 0; border: none; background: transparent; color: inherit;
  font: inherit; text-align: left; cursor: pointer;
}
.delegate-flow-chain-chip {
  flex-shrink: 0; padding: 0 7px; border-radius: 7px;
  background: var(--color-frame-hover, rgba(128,128,128,0.12));
  font: 600 10px/18px ui-monospace, SFMono-Regular, monospace;
  color: var(--color-text-muted);
}
.delegate-flow-chain .delegate-chain-meta { flex: 1 1 auto; min-width: 0; }
.delegate-flow-chain-chev { flex-shrink: 0; width: 12px; height: 12px; display: inline-flex; color: var(--color-text-muted); transition: transform 0.15s ease; }
.delegate-flow-chain-chev svg { width: 12px; height: 12px; display: block; }
.delegate-flow-chain-toggle[aria-expanded="true"] .delegate-flow-chain-chev { transform: rotate(180deg); }
.delegate-flow-steps {
  display: flex; flex-wrap: wrap; align-items: flex-start; gap: 12px;
  padding: 4px 2px 2px; border-top: 1px dashed var(--color-border);
}
.delegate-flow-steps[hidden] { display: none; }
.delegate-flow-empty {
  font: 400 11px/1.5 -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text-muted); opacity: 0.8;
}
/* L2 卡内子代理/flow 徽标（§D：与 §B 行内徽标同件同语言）。 */
.delegate-card-kids, .delegate-card-flow {
  flex-shrink: 0; display: inline-flex; align-items: center; gap: 2px;
  padding: 0 4px; border-radius: 6px;
  background: var(--color-frame-hover, rgba(128,128,128,0.12));
  color: var(--color-text-muted);
}
.delegate-card-kids svg, .delegate-card-flow svg { width: 9px; height: 9px; display: block; }
.delegate-card-kids span, .delegate-card-flow span {
  font: 600 9px/13px ui-monospace, SFMono-Regular, monospace;
  font-variant-numeric: tabular-nums;
}
`;

/** 归档态样式注入（模块级单点；幂等）。 */
function ensureArchiveCss() {
  if (typeof document !== 'undefined' && !document.getElementById(STRUCT_ARCHIVE_CSS_ID)) {
    document.head.insertAdjacentHTML('beforeend', `<style id="${STRUCT_ARCHIVE_CSS_ID}">${STRUCT_ARCHIVE_CSS}</style>`);
  }
}

/** 相对时刻（归档行元数据；与 taskList.formatLastActive 同语义的本地小镜像——
 *  该件未导出；只吃 epoch ms，零 locale 键）。 */
function fmtAgoShort(ms) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/**
 * 挂 L2 右上角归档 FAB + 抽屉（两层架构批 §D WF-07；幂等——每次重渲重建 body，
 * FAB 随建）。徽章计数 = 首拉回执（fail-soft：拉取失败 = 徽章缺席，FAB 仍可点开
 * 重试）；自动归档到达（delegate-archived 事件）= 徽章脉冲 + toast 带 5s 撤销。
 * @param {HTMLElement} body L2 视图体（.delegate-chain-view-body，relative 宿主）
 */
function attachStructureArchive(body) {
  ensureArchiveCss();
  const pane = body.closest('.canvas-tab-pane') || body.parentElement;
  const fab = document.createElement('button');
  fab.type = 'button';
  fab.className = 'delegate-archive-fab';
  fab.dataset.testid = 'delegate-structure-archive';
  fab.setAttribute('aria-expanded', 'false');
  fab.title = t('delegate.archive.drawerTitle');
  fab.setAttribute('aria-label', fab.title);
  fab.innerHTML = `${STRUCT_ARCHIVE_ICON}<span class="delegate-archive-badge" hidden></span>`;
  body.appendChild(fab);

  const drawer = document.createElement('div');
  drawer.className = 'delegate-archive-drawer';
  drawer.hidden = true;
  body.appendChild(drawer);

  /** 自动归档到达 toast（§D：单一在飞实例；5s 自动退场，撤销 = 反归档回平铺面）。 */
  let toastEl = null;
  let toastTimer = null;
  const hideToast = () => {
    if (toastTimer) { clearTimeout(toastTimer); toastTimer = null; }
    if (toastEl) { toastEl.remove(); toastEl = null; }
  };
  const showToast = (detail) => {
    if (!pane) return;
    hideToast();
    const label = detail.taskNo ? `#${detail.taskNo}` : String(detail.title || detail.delegateId || '');
    toastEl = document.createElement('div');
    toastEl.className = 'delegate-archive-toast';
    toastEl.dataset.archiveToast = String(detail.delegateId || '');
    const text = document.createElement('span');
    text.textContent = t('delegate.archive.toast', { n: label });
    toastEl.appendChild(text);
    const undo = document.createElement('button');
    undo.type = 'button';
    undo.className = 'delegate-archive-toast-undo';
    undo.textContent = t('delegate.archive.undo');
    undo.addEventListener('click', async () => {
      const id = String((detail && detail.delegateId) || '');
      hideToast();
      if (!id) return;
      undo.disabled = true;
      const res = await unarchiveDelegate(id).catch(() => ({ state: 'network' }));
      if (res.state !== 'ok') showToastError(t('delegate.archiveFailed'));
      // 反归档成功 → 镜像即时对账（行回在册窗 = 平铺面回归；fail-soft 静默）。
      import('./delegateChainProvider.js').then((m) => m.refreshMirror && m.refreshMirror()).catch(() => {});
    });
    toastEl.appendChild(undo);
    pane.appendChild(toastEl);
    toastTimer = setTimeout(hideToast, 5000);
  };
  const showToastError = (msg) => {
    if (!pane) return;
    hideToast();
    toastEl = document.createElement('div');
    toastEl.className = 'delegate-archive-toast';
    const text = document.createElement('span');
    text.textContent = msg;
    toastEl.appendChild(text);
    pane.appendChild(toastEl);
    toastTimer = setTimeout(hideToast, 5000);
  };

  /** 拉归档历史窗进抽屉（fail-soft：null = 静默缺席，空态文案兜底）。行动作 ×2 =
   *  流程图 →（历史态 L1，返回回本抽屉所在 L2）/ 恢复（反归档 → 平铺面回归）。 */
  const loadRows = async () => {
    drawer.innerHTML = `<div class="delegate-archive-empty">…</div>`;
    const rows = await fetchArchivedDelegates();
    drawer.innerHTML = '';
    const head = document.createElement('div');
    head.className = 'delegate-archive-drawer-head';
    const headText = document.createElement('span');
    headText.textContent = t('delegate.archive.drawerTitle');
    head.appendChild(headText);
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'delegate-archive-drawer-close';
    close.setAttribute('aria-label', t('delegate.archive.drawerTitle'));
    close.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>';
    close.addEventListener('click', () => {
      drawer.hidden = true;
      fab.classList.remove('active');
      fab.setAttribute('aria-expanded', 'false');
    });
    head.appendChild(close);
    drawer.appendChild(head);
    // 空态行（含 fail-soft 行）走 appendChild——🔴 禁 innerHTML +=：重解析会替换
    // 既有子节点（head/close 连同监听器一并剥除——关闭钮失灵实测根因）。
    const emptyRow = (wordKey) => {
      const el = document.createElement('div');
      el.className = 'delegate-archive-empty';
      el.textContent = t(wordKey);
      drawer.appendChild(el);
    };
    if (!rows) {
      emptyRow('delegate.chainUnavailable');
      return null;
    }
    if (!rows.length) {
      emptyRow('flowmap.archive.empty');
      return rows;
    }
    for (const r of rows) {
      const row = document.createElement('div');
      row.className = 'delegate-archive-row';
      const main = document.createElement('div');
      main.className = 'delegate-archive-row-main';
      const title = document.createElement('span');
      title.className = 'delegate-archive-row-title';
      title.textContent = [r.taskNo ? `#${r.taskNo}` : '', r.title || r.delegateId || ''].filter(Boolean).join(' ');
      title.title = title.textContent;
      main.appendChild(title);
      const meta = document.createElement('span');
      meta.className = 'delegate-archive-row-meta';
      const bits = [r.archivedAt ? fmtAgoShort(r.archivedAt) : '', rowWord(r.status || '')].filter(Boolean);
      meta.textContent = bits.join(' · ');
      main.appendChild(meta);
      row.appendChild(main);
      const actions = document.createElement('div');
      actions.className = 'delegate-archive-row-actions';
      const openFlow = document.createElement('button');
      openFlow.type = 'button';
      openFlow.className = 'delegate-archive-act';
      openFlow.textContent = t('delegate.archive.openFlow');
      openFlow.addEventListener('click', () => {
        openDelegateChainTask(
          String(r.taskNo || r.delegateId || ''),
          { taskNo: r.taskNo, delegateId: r.delegateId, title: r.title, status: r.status, executor: r.executor, project: r.project },
          { from: 'archive' }, // 历史态 L1：返回钮回本抽屉所在 L2（§E）
        );
      });
      actions.appendChild(openFlow);
      const restore = document.createElement('button');
      restore.type = 'button';
      restore.className = 'delegate-archive-act';
      restore.dataset.testid = 'delegate-restore';
      restore.textContent = t('delegate.archive.restore');
      restore.addEventListener('click', async () => {
        const id = String(r.delegateId || '');
        if (!id || restore.disabled) return;
        restore.disabled = true;
        const res = await unarchiveDelegate(id).catch(() => ({ state: 'network' }));
        if (res.state === 'ok') {
          // 镜像即时对账：行回在册窗（平铺面经 mirror 事件重渲回归）。
          import('./delegateChainProvider.js').then((m) => m.refreshMirror && m.refreshMirror()).catch(() => {});
        } else {
          restore.disabled = false;
          showToastError(t('delegate.archiveFailed'));
        }
      });
      actions.appendChild(restore);
      row.appendChild(actions);
      drawer.appendChild(row);
    }
    return rows;
  };

  const badge = /** @type {HTMLElement} */ (fab.querySelector('.delegate-archive-badge'));
  /** 徽章刷新（首拉/到达反馈共用；fail-soft 静默）。 */
  const refreshBadge = async () => {
    const rows = await fetchArchivedDelegates().catch(() => null);
    if (!rows || !badge) return;
    badge.textContent = rows.length > 99 ? '99+' : String(rows.length);
    badge.hidden = rows.length === 0;
    return rows;
  };
  // 首拉一次（徽章计数；fire-and-forget fail-soft）。
  refreshBadge().catch(() => {});

  fab.addEventListener('click', async (e) => {
    e.stopPropagation();
    const opening = drawer.hidden;
    fab.classList.toggle('active', opening);
    fab.setAttribute('aria-expanded', String(opening));
    drawer.hidden = !opening;
    if (opening) await loadRows().catch(() => {});
  });
  // 外点收起（视图体内点击不关——面板/卡片交互优先）。
  body.addEventListener('click', (e) => {
    if (drawer.hidden) return;
    const t = /** @type {Element} */ (e.target);
    if (t && (t.closest('.delegate-archive-drawer') || t.closest('.delegate-archive-fab'))) return;
    drawer.hidden = true;
    fab.classList.remove('active');
    fab.setAttribute('aria-expanded', 'false');
  });

  // 自动归档到达（§D）：provider 侦测行移历史窗 → delegate-archived 事件 →
  // 徽章脉冲 +1 + toast「#N 已完成并归档」（5s 撤销）。仅 L2 在图时呈现。
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('delegate-archived', (/** @type {CustomEvent} */ ev) => {
      if (!pane || !pane.isConnected || (/** @type {HTMLElement} */ (pane)).dataset.delegateMode !== 'structure') return;
      const detail = (ev && ev.detail) || {};
      if (badge) {
        refreshBadge().catch(() => {});
        badge.classList.remove('pulse');
        // 强制重启动画（同帧移除再加类）。
        void badge.offsetWidth;
        badge.classList.add('pulse');
      }
      showToast(detail);
    });
  }
}

// ── 镜像事件路由（两层架构批单点接线）─────────────────────────────────────
// delegate-chain-mirror-updated（provider 刷新完成广播；帧只当触发器）→ 当前在图
// pane 分流：L1 = fail-soft 态自动重渲（归档历史键 lazy 拉取到达同缝）；L2 = 平铺面
// 随镜像重渲（终态自动归档卡淡出 / 抽屉「恢复」行回归）。activeChainPane 由
// openPane 建架时指向（pane 重建零监听堆积；isConnected 守卫兜底）。
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('delegate-chain-mirror-updated', () => {
    const pane = activeChainPane;
    if (!pane || !pane.isConnected || pane.dataset.projectsView !== 'delegate-chain') return;
    if (pane.dataset.delegateMode === 'task') {
      if (pane.dataset.delegateTaskSoft === '1') renderTaskBody(pane, String(pane.dataset.delegateKey || ''));
    } else if (pane.dataset.delegateMode === 'structure') {
      renderStructureBody(pane);
    }
  });
}
