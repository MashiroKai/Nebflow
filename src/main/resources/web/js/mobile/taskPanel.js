// mobile/taskPanel.js — 任务面（dock 批改写：浮层 → 「任务」详情页面板）。
//
// ⛔ 【No longer mounted by navigation】Apple todo face port (2026-11-10): the
//   'tasks' page body is now mobile/todoAppleFace.js, and navController no
//   longer imports this module — ensureTaskPanel/closeTaskPanel are unreached
//   from navigation. The file stays: taskBadgeCount is a live read seam for
//   the chrome badge and for specs that import this module directly.
//
// ✅ 【本模块恢复现役】作者令 2026-11-05 09:28（逐字）：「手机端的待办…跟电脑端
//   的区分开，电脑端是一个胶囊，手机端还是需要是之前那个左上角的一个悬浮的待办
//   按钮」⇒ 入口圆钮（js/mobile/chatChrome.js 的 [data-chrome='tasks']）、导航页
//   'tasks' 与其 overlay 收编/对账（js/mobile/navController.js）、徽标读数
//   taskBadgeCount 的调用方（chatChrome 的 syncTaskBadge）本批**全部恢复**，
//   本模块重新有 import 方。
//   沿革（入口收敛批 todo-unify-entry-collapse 2026-11-04 曾整模块退役：当时
//   口径 = 待办入口收敛到顶带胶囊一处，手机端胶囊双端同现；本批按端区分反转）：
//     · 手机端 = 左上任务圆钮 → pushPage('tasks') → 本面板（页面即展开态）；
//     · 桌面端 = 顶带胶囊 #task-list（不受 body.mobile-layer 作用域影响，零改动）。
//   历史留档（退役期的承载说明，仍准确）：待办数读数在手机页面态由面板头
//   `.tp-stats` 承载，胶囊/面板双态的计数节点是 `.tfl-cap-faces`。
//
// ★ 任务面换血（ReminderIsland 复刻批 · 作者令 2026-11-02 20:02）：本面板体 =
//   待办列表（todoFace.mountTodoFaceInto——手机端入口维持本文件接线的「任务」
//   左侧悬浮圆钮不变，内容换血为 ReminderIsland 复刻待办列表）。旧 delegate 卡
//   渲染核（buildCard/renderDelegateCards 等）**代码零删除**保留于本文件（别面
//   数据缝 collectChains/DELEGATE_WORD_KEY/chainProgressMeta 照旧导出），仅本面
//   不再触发——render() 收敛为徽标数刷新。
//
// dock 批（作者令 2026-10-29「各节目都是独立界面 不再跟其他界面重叠」）：
// v3⑤ 的「圆钮二级液态形变任务浮层」形态退役（morph 腿随 floatController/
// morphPanel 一并退场）；本文件保留**数据面与卡渲染面全部原样**（12 卡六级
// 信息层级、分组序、同一读取口），呈现位 = navController 收编进「任务」详情
// 页（overlayFaceByPage.tasks = #mobile-task-panel，出页原位归还）。
//
// 形态沿革：下拉列中点「任务」→ 六键液态收回 → 圆钮原地形变加宽成任务浮层
//（40→308 / 40→560）→ dock 批改「任务」键（聊天头左槽）push 任务页。
// 活跃过滤批（作者令 2026-10-31 11:49）与 interrupted 灰态语义随旧渲染核保留
// 在 renderDelegateCards（代码存档）；现役面 = 待办列表（内存态 mock，
// 接自有待办工具为下一步）。
//
// 数据面：待办 = todoStore（todoStore.js 内存仓）；旧 delegate 读取口 imports
// 保留（renderDelegateCards 存档依赖 + 别面导出缝）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import state from '../state.js';
import {
  collectChains, DELEGATE_WORD_KEY, chainProgressMeta, rowMetaOf, sessionShowsNodeEntries,
} from '../taskList.js';
import { mountTodoFaceInto } from '../todoFace.js';
import { todoStore } from '../todoStore.js';
// 层事件流水（循环门禁修复批 2026-11-04）：打事件取叶子 navEvents.js，不再静态
// import navController.js —— 后者 import 本模块的 ensureTaskPanel/closeTaskPanel
// （页面收编），两条边互为反向即 SCC[2]（scripts/check-circular.mjs）。
import { logEvent } from './navEvents.js';

const CHECK_SVG = '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.6 6.2l1.7 1.7 3.1-3.6"/></svg>';
const MEMBER_PREVIEW_MAX = 2;

/** @type {HTMLElement|null} */
let panel = null;
/** @type {HTMLElement|null} */
let listEl = null;
/** @type {HTMLElement|null} */
let statsEl = null;

export function isTaskPanelOpen() {
  return !!(panel && !panel.hidden);
}

/** 面板壳（惰性一件；挂 body 级 —— fixed 几何自带，无包含块陷阱）。
 *  ⛔ 【退役】todo-unify-entry-collapse 2026-11-04：本面板 = 已退役任务页的载体
 *  （#mobile-task-panel），导航页 'tasks' 撤除后无任何路径调用本函数；代码保留。 */
function ensurePanel() {
  if (panel) return panel;
  panel = document.createElement('div');
  panel.id = 'mobile-task-panel';
  panel.className = 'mh-tpanel';
  panel.hidden = true;
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', t('taskpanel.title'));

  const head = document.createElement('div');
  head.className = 'tp-hd';
  const icon = document.createElement('i');
  icon.setAttribute('data-lucide', 'list-todo');
  statsEl = document.createElement('span');
  statsEl.className = 'tp-stats';
  const x = document.createElement('button');
  x.type = 'button';
  x.className = 'tp-x glass-control';
  x.setAttribute('aria-label', t('taskpanel.collapse'));
  x.innerHTML = '<i data-lucide="x"></i>';
  x.addEventListener('click', () => closeTaskPanel());
  head.append(icon, statsEl, x);

  const bd = document.createElement('div');
  bd.className = 'tp-bd';
  listEl = bd;
  // 换血批：面板体 = 待办列表（页面即展开态；face 自订阅 todoStore 自重渲）。
  mountTodoFaceInto(bd);

  panel.append(head, bd);
  document.body.appendChild(panel);
  if (typeof lucide !== 'undefined') createIconsIn(panel);

  // 与 taskList 同族的重渲触发（换血批：镜像/会话事件只刷徽标数——列表自订阅）。
  window.addEventListener('delegate-chain-mirror-updated', () => { if (isTaskPanelOpen()) render(); });
  window.addEventListener('nebflow-session-change', () => { if (isTaskPanelOpen()) render(); });
  return panel;
}

/** 任务键徽标读数 = 待办数（换血批：todoStore 单源；浮层关着也跟新）。 */
export function taskBadgeCount() {
  if (!sessionShowsNodeEntries(state.activeSessionId)) return 0;
  return todoStore.openCount();
}

/** 状态域二分（活跃过滤批）：ready-to-merge → 排队；其余现役行（pending/running/
 *  interrupted）→ 运行中。终态行经 collectChains 现役闸天然缺席，无需再判。 */
function groupOf(status) {
  return status === 'ready-to-merge' ? 'queued' : 'running';
}

function glyph(cls) {
  const g = document.createElement('span');
  g.className = `tp-glyph tp-g-${cls}`;
  g.setAttribute('aria-hidden', 'true');
  return g;
}

/** 一张任务卡（六级层级见文件头）。block = collectChains 现值。 */
function buildCard(entry) {
  const r = entry.row;
  const st = String(r.status || 'running');
  const grp = groupOf(st);
  const taskNo = String(r.taskNo == null ? '' : r.taskNo);
  const title = String(r.title || r.delegateId || '');
  const wordKey = DELEGATE_WORD_KEY[st] || '';
  const members = entry.members || [];

  const card = document.createElement('div');
  card.className = `tp-card tp-${grp}`;
  card.setAttribute('role', 'button');

  const hd = document.createElement('div');
  hd.className = 'tp-c-hd';
  // 重启对账批 2026-10-22：interrupted = 灰点（stop），永不借 done 勾语言。
  hd.appendChild(glyph(grp === 'queued' ? 'queue' : st === 'interrupted' ? 'stop' : 'run'));
  const ttl = document.createElement('span');
  ttl.className = 'tp-ttl';
  ttl.textContent = taskNo ? `#${taskNo} ${title}` : title;
  hd.appendChild(ttl);
  const meta = document.createElement('span');
  meta.className = 'tp-meta';
  meta.textContent = entry.progressText || '';
  if (meta.textContent) hd.appendChild(meta);
  const word = document.createElement('span');
  word.className = 'tp-word';
  word.textContent = wordKey ? t(wordKey) : '';
  if (word.textContent) hd.appendChild(word);
  card.appendChild(hd);

  // ⑤ 成员预览：仅运行中卡（排队=display-only，均不出成员）。
  if (grp === 'running' && members.length > 1) {
    const box = document.createElement('div');
    box.className = 'tp-members';
    const rows = members.slice(1); // 首位 = 根桩（即卡头行本身）
    rows.slice(0, MEMBER_PREVIEW_MAX).forEach((m) => {
      const line = document.createElement('div');
      line.className = 'tp-mem';
      const mst = String(m.status || 'pending');
      if (mst === 'running') {
        const spin = document.createElement('span');
        spin.className = 'tp-mem-spin';
        line.appendChild(spin);
      } else if (['completed', 'failed'].includes(mst)) {
        const mk = document.createElement('span');
        mk.className = `tp-mem-mark${mst === 'failed' ? ' tp-mem-mark-fail' : ''}`;
        mk.innerHTML = CHECK_SVG;
        line.appendChild(mk);
      } else {
        const hollow = document.createElement('span');
        hollow.className = 'tp-mem-hollow';
        line.appendChild(hollow);
      }
      const nm = document.createElement('span');
      nm.textContent = m.name || String(m.id || '');
      line.appendChild(nm);
      box.appendChild(line);
    });
    if (rows.length > MEMBER_PREVIEW_MAX) {
      const more = document.createElement('div');
      more.className = 'tp-mem-more';
      more.textContent = `+${rows.length - MEMBER_PREVIEW_MAX}`;
      box.appendChild(more);
    }
    card.appendChild(box);
  }

  // 点击 = 与面板行同语义的 L1 跳转（可开链；外接/排队行 display-only）。
  const memberCount = Math.max(0, members.length - 1);
  const openable = r.external !== true && grp !== 'queued' && memberCount > 0;
  if (openable) {
    card.addEventListener('click', () => {
      logEvent('tp-card-open-l1', { key: entry.key });
      const meta0 = rowMetaOf(r);
      import('../delegateChainView.js').then(({ openDelegateChainTask }) => {
        openDelegateChainTask(entry.key, meta0);
      }).catch(() => {});
    });
  } else {
    card.classList.add('tp-static');
  }
  return card;
}

function groupLabel(grp, n) {
  const key = grp === 'running' ? 'taskpanel.grpRunning' : 'taskpanel.grpQueued';
  return `${t(key)} · ${n}`;
}

/** 现役 render（换血批）：面板体已由 todoFace 自管，这里只刷面板头徽标数。 */
function render() {
  if (!statsEl) return;
  statsEl.textContent = t('todoface.capsuleCount', { n: String(todoStore.openCount()) });
}

// ── 旧 delegate 卡渲染核（换血批存档：代码零删除、本面零触发）──────────────
// 以下 buildCard/groupOf/glyph/renderDelegateCards 为 dock 批的 delegate 卡渲染
// 链（数据面 = taskList.collectChains 同源缝，别面照旧消费其导出）。任务列表面
// 换血为待办列表后本链不再被任何路径调用——保留作「旧渲染路径」对照与回切件。

function renderDelegateCards() {
  if (!listEl || !statsEl) return;
  const chains = collectChains();
  const running = chains.filter((c) => groupOf(String(c.row.status)) === 'running');
  const queued = chains.filter((c) => groupOf(String(c.row.status)) === 'queued');
  const unitCount = chains.reduce((n, c) => n + (c.allCount || 1), 0);
  statsEl.textContent = t('taskpanel.stats', { n: String(unitCount), run: String(running.length) });

  listEl.replaceChildren();
  const groups = [
    ['running', running.map((c) => ({
      key: c.key, row: c.row, members: c.view.ordered,
      progressText: c.view.ordered.length > 1 ? chainProgressMeta(c.view.ordered).split(' · ')[0] : '',
    }))],
    ['queued', queued.map((c) => ({ key: c.key, row: c.row, members: [], progressText: '' }))],
  ];
  for (const [grp, entries] of groups) {
    if (!entries.length) continue;
    const label = document.createElement('div');
    label.className = 'tp-grp';
    label.textContent = groupLabel(grp, entries.length);
    listEl.appendChild(label);
    for (const e of entries) listEl.appendChild(buildCard(e));
  }
  if (!listEl.children.length) {
    const empty = document.createElement('div');
    empty.className = 'tp-empty';
    empty.textContent = t('taskpanel.empty');
    listEl.appendChild(empty);
  }
}

/** 页面开臂（navController.ensurePanelContent 分派）：惰性建板 + 重渲 + 显出。
 *  收编由 navController 的 overlayFaceByPage.tasks 完成（本函数只管态）。 */
export function ensureTaskPanel() {
  const p = ensurePanel();
  render();
  p.hidden = false;
  logEvent('tpanel-open', { via: 'page' });
}

/** 关面板（页面出栈腿 closePageFace / 面板头 X 共用；instant = 无动画直隐）。 */
export function closeTaskPanel(instant = false) {
  if (!isTaskPanelOpen()) return;
  const p = /** @type {HTMLElement} */ (panel);
  logEvent('tpanel-close', { via: instant ? 'page' : 'x' });
  p.hidden = true;
}
