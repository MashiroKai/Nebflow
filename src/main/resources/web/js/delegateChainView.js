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
  listDelegateRows, getChainByTask, mergeQueueFifo, verdictToStatus,
} from './delegateChainStub.js';
import { nodeHtml } from './fmNodeCard.js';
import { executorIcon } from './executorIcons.js';
// file-diff feature: live GUI entry for the node detail layer (archive FAB +
// panel + detail with the changed-file diff section) inside this chain view.
import { attachChainDetailLayer } from './chainDetailEntry.js';

/** 视图体滚动容器选择器（pane 内唯一）。 */
const BODY_SEL = '.delegate-chain-view-body';

/** 相对时刻（数据座标辅助显示）：与 taskList.formatLastActive 同语义的本地镜像
 *  （该件未导出且属任务面板域——不为此拉模块耦合；词面复用既有键 task.justNow）。 */
function fmtAgo(ms) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

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

/** 执行器 head 徽标（fm-node head 行首；title = 执行器 id 数据）。 */
function executorHeadHtml(executor) {
  const id = String(executor || '');
  if (!id) return '';
  return `<span class="delegate-card-executor" title="${esc(id)}">${executorIcon(id, 12)}</span>`;
}

/** 打开（或复用）projects 页签并铺 delegate-chain 视图骨架（复用 projectTab 就地
 *  视图骨架：.flowmap-nav-bar 返回条 + 视图体）。mode = 'task' | 'structure'。 */
function openPane(mode, taskKey, summary) {
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
    // 列表页 FAB 属列表视图（projectTab.attachProjectsCreateFab 自有视图守卫会摘除；
    // 这里整体重建 pane 内 DOM，无残留）。
    pane.innerHTML = `
      <div class="flowmap-nav-bar">
        <button class="flowmap-back-btn" data-back-to-tasks type="button" aria-label="${esc(t('delegate.backToTasks'))}">
          <i data-lucide="arrow-left"></i><span class="flowmap-back-name">${esc(t('delegate.backToTasks'))}</span>
        </button>
        ${mode === 'task' ? `<button class="flowmap-back-btn delegate-structure-entry" data-open-delegate-structure type="button" title="${esc(t('delegate.structure'))}" aria-label="${esc(t('delegate.structure'))}">
          <i data-lucide="network"></i><span class="flowmap-back-name">${esc(t('delegate.structure'))}</span>
        </button>` : ''}
        <span class="flowmap-summary flowmap-nav-summary" aria-live="polite">${esc(summary || '')}</span>
      </div>
      <div class="${BODY_SEL.slice(1)}"></div>`;
    pane.querySelector('[data-back-to-tasks]').addEventListener('click', (e) => {
      e.stopPropagation();
      closeTab('projects'); // 返回任务列表：关 projects 页签 = 回任务面板视图
    });
    const structureBtn = pane.querySelector('[data-open-delegate-structure]');
    if (structureBtn) {
      structureBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        openDelegateChainStructure();
      });
    }
    createIconsIn(pane);
  } else {
    const summaryEl = pane.querySelector('.flowmap-nav-summary');
    if (summaryEl) summaryEl.textContent = summary || '';
  }
  return pane;
}

// ── S10 per-task 链视图 ──────────────────────────────────────────────────

/**
 * 打开某 delegate 行的 per-task 链视图（S10；taskList 行钮 / S11 链卡入口）。
 * @param {string} taskKey 行键（taskNo ‖ delegateId——getChainByTask 读取口键）
 * @param {{taskNo?: string, delegateId?: string, title?: string, status?: string, executor?: string}} [meta] 行元数据（顶栏摘要用；缺省容忍）
 */
export function openDelegateChainTask(taskKey, meta = {}) {
  const key = String(taskKey == null ? '' : taskKey);
  if (!key) return;
  const m = meta && typeof meta === 'object' ? meta : {};
  const labelBits = [m.taskNo ? `#${m.taskNo}` : '', m.title || m.delegateId || key].filter(Boolean);
  const pane = openPane('task', key, labelBits.join(' '));
  if (!pane) return;
  // 行挂载项目名（file-diff diff 入口的报告/归档拉取上下文；'' = 无项目态，
  // 层照开、远端读取 fail-soft）。rerender 路径经同一 dataset 复用。
  pane.dataset.delegateProject = String(m.project || '');
  renderTaskBody(pane, key);
}

/** S10 视图体渲染：数据走 getChainByTask；查无链 → fail-soft 态（§16 键 7，点击重试）。 */
function renderTaskBody(pane, key) {
  const body = pane.querySelector(BODY_SEL);
  if (!body) return;
  body.innerHTML = '';
  const chain = getChainByTask(key);
  if (!chain || !Array.isArray(chain.nodes) || chain.nodes.length === 0) {
    // fail-soft（FSM loaderr 终态）：行保留（面板不受影响）+ 本态可重试。读口
    // catch→null 同纪律；N1 per-task 读面落位前，stub/夹具态即此。
    const fail = document.createElement('button');
    fail.type = 'button';
    fail.className = 'delegate-chain-failsoft';
    fail.textContent = t('delegate.chainUnavailable'); // §16 键 7
    fail.addEventListener('click', () => {
      renderTaskBody(pane, key); // 重试 = 重走读取口（provider 可能已换/夹具已装）
    });
    body.appendChild(fail);
    return;
  }
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
      chain.worktree.baseBranch ? `← ${chain.worktree.baseBranch}` : ''].filter(Boolean);
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
  // 链节点卡（ChainNode：任务＋描述＋状态＋执行器）——fmNodeCard 单源渲染件，
  // plain 流式布局（列表非画布）。卡面即 FSM「node 卡片」态：任务＋描述＋状态＋
  // 执行器全显（呈审稿 §2.1 S10「卡片点开可见 node 任务与描述」= 卡面字段，
  // 零新增弹层）。
  const list = document.createElement('div');
  list.className = 'delegate-chain-list';
  for (const n of chain.nodes) {
    const payload = delegateCardPayload(n);
    const holder = document.createElement('div');
    holder.className = 'delegate-chain-card';
    holder.innerHTML = nodeHtml(payload, { x: 0, y: 0, h: 0 }, 0, null, {
      plain: true,
      headPrefix: executorHeadHtml(n.executor),
      subText: String(n.kind || ''),
    });
    const card = holder.firstElementChild;
    if (card) list.appendChild(card);
  }
  wrap.appendChild(list);
  // 候定集/未知帧透传列表（只透传不解读：kind + at 原样——N1 定稿前禁造第二权威）。
  const frames = Array.isArray(chain.frames) ? chain.frames : [];
  if (frames.length) {
    const fx = document.createElement('div');
    fx.className = 'delegate-chain-frames';
    for (const f of frames) {
      const el = document.createElement('div');
      el.className = 'delegate-chain-frame';
      el.dataset.frameKind = String(f.kind || '');
      const bits = [String(f.kind || ''), fmtAgo(f.at)].filter(Boolean);
      el.textContent = bits.join(' · ');
      fx.appendChild(el);
    }
    wrap.appendChild(fx);
  }
  body.appendChild(wrap);
  // file-diff feature (rework): the chain view body hosts the single-source
  // node detail layer — node cards open .fm-detail, and completed nodes mount
  // their changed-file diff section inside it (404/no project = the section
  // silently stays absent). Scoped-chain projections carry real node ids; the
  // fallback single-delegate node degrades honestly (no report to fetch).
  attachChainDetailLayer(body, String(pane.dataset.delegateProject || ''), chain.nodes);
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

/** S11 视图体渲染。 */
function renderStructureBody(pane) {
  const body = pane.querySelector(BODY_SEL);
  if (!body) return;
  body.innerHTML = '';
  const rows = listDelegateRows();

  const head = document.createElement('div');
  head.className = 'delegate-structure-head';
  head.textContent = t('delegate.structure'); // §16 键 3
  body.appendChild(head);

  if (!rows.length) {
    const fail = document.createElement('button');
    fail.type = 'button';
    fail.className = 'delegate-chain-failsoft';
    fail.textContent = t('delegate.chainUnavailable');
    fail.addEventListener('click', () => renderStructureBody(pane));
    body.appendChild(fail);
    return;
  }

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
      const icon = document.createElement('span');
      icon.className = 'delegate-seat-executor';
      icon.setAttribute('aria-hidden', 'true');
      icon.innerHTML = executorIcon(String(p.row.executor || ''), 12);
      el.appendChild(icon);
      const val = document.createElement('span');
      val.className = 'delegate-chain-mono';
      const w = p.chain.worktree;
      const bits = [p.row.taskNo ? `#${p.row.taskNo}` : '', w.branch,
        w.worktree || w.cwd, w.baseBranch ? `← ${w.baseBranch}` : ''].filter(Boolean);
      val.textContent = bits.join(' · ');
      val.title = bits.join(' · ');
      el.appendChild(val);
      section.appendChild(el);
    }
    body.appendChild(section);
  }

  // ── FIFO 合并泳道（merge-queue 全量收集 → mergeQueueFifo 单点排序；§16 键 4）──
  // 🔴 零派生第二权威：排序键单点 = stub mergeQueueFifo（readyAt ↑ → at ↑ →
  // delegateId ↑；S11 排序键改义 = 完成时间非创建时间）。真源排序在引擎
  // MergeQueue——REST 到位后本 projection 改透传（呈审稿 §2.4 在案）。
  const queue = [];
  for (const p of projections) {
    if (p.chain && Array.isArray(p.chain.mergeQueue)) queue.push(...p.chain.mergeQueue);
  }
  if (queue.length) {
    const section = document.createElement('div');
    section.className = 'delegate-structure-section';
    const laneHead = document.createElement('div');
    laneHead.className = 'delegate-structure-label';
    laneHead.textContent = t('delegate.mergeQueue'); // §16 键 4
    section.appendChild(laneHead);
    const lane = document.createElement('div');
    lane.className = 'delegate-structure-lane';
    mergeQueueFifo(queue).forEach((q, i) => {
      const el = document.createElement('div');
      el.className = 'delegate-structure-lane-item' + (i === 0 ? ' is-head' : '');
      el.dataset.readyAt = String(q.readyAt || 0);
      const pos = document.createElement('span');
      pos.className = 'delegate-lane-pos';
      pos.textContent = String(i + 1);
      el.appendChild(pos);
      const val = document.createElement('span');
      val.className = 'delegate-chain-mono';
      const bits = [q.branch || q.delegateId, fmtAgo(q.readyAt)].filter(Boolean);
      val.textContent = bits.join(' · ');
      val.title = bits.join(' · ');
      el.appendChild(val);
      lane.appendChild(el);
    });
    section.appendChild(lane);
    body.appendChild(section);
  }

  // ── 各 delegate 链卡（fmNodeCard 单源；点击 → S10 该链视图）──
  const list = document.createElement('div');
  list.className = 'delegate-chain-list';
  for (const p of projections) {
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
      headPrefix: executorHeadHtml(r.executor),
      subText: String(r.project || ''),
    });
    const card = holder.firstElementChild;
    if (card) {
      card.setAttribute('role', 'button');
      card.addEventListener('click', () => {
        openDelegateChainTask(p.key, {
          taskNo: r.taskNo, delegateId: r.delegateId, title: r.title,
          status: r.status, executor: r.executor,
        });
      });
      list.appendChild(card);
    }
  }
  if (list.children.length) body.appendChild(list);
}

/** projectTab.rerenderProjectsTab 视图分流挂钩：按 pane 记忆的模式重渲当前视图
 *  （数据面 = stub 读取口现读；WS/live 增量属 N1 结构帧落地后的 provider 面）。 */
export function rerenderDelegateChainPane(pane) {
  if (!pane || pane.dataset.projectsView !== 'delegate-chain') return;
  if (pane.dataset.delegateMode === 'task') renderTaskBody(pane, String(pane.dataset.delegateKey || ''));
  else if (pane.dataset.delegateMode === 'structure') renderStructureBody(pane);
}
