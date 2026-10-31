// mobile/taskPanel.js — 任务面（dock 批改写：浮层 → 「任务」详情页面板）。
//
// dock 批（作者令 2026-10-29「各节目都是独立界面 不再跟其他界面重叠」）：
// v3⑤ 的「圆钮二级液态形变任务浮层」形态退役（morph 腿随 floatController/
// morphPanel 一并退场）；本文件保留**数据面与卡渲染面全部原样**（12 卡六级
// 信息层级、分组序、同一读取口），呈现位 = navController 收编进「任务」详情
// 页（overlayFaceByPage.tasks = #mobile-task-panel，出页原位归还）。
//
// 形态沿革：下拉列中点「任务」→ 六键液态收回 → 圆钮原地形变加宽成任务浮层
//（40→308 / 40→560）→ dock 批改「任务」键（聊天头左槽）push 任务页。
// 活跃过滤批（作者令 2026-10-31 11:49「手机端任务列表不要显示全部历史（含已完成），
// 只显示进行中的」）：已完成组（completed/failed/cancelled 终态行呈现层补组）整组
// 退役——手机端列表面 = collectChains 现役行（pending/running/ready-to-merge/
// interrupted，未归档），与桌面面板现役窗同口径；终态行仍可从桌面结构视图/归档
// 抽屉到达。interrupted（重启打断灰态）保留在运行组内以灰点显形（重启对账批语义
// 不变：尸体系可见、禁借 done 勾绿）。
// 卡信息层级（六级，样张⑤口径）：
//   ① 状态点（运行绿 / 排队灰 / 中断灰）
//   ② 标题（#taskNo + 名）
//   ③ 进度 n/m（tabular-nums，chainProgressMeta 同源）
//   ④ 状态词（DELEGATE_WORD_KEY 同源键）
//   ⑤ 成员预览（仅运行中卡，限 2 行 +「+n」溢出计数）
//   ⑥ 分组序：运行中 → 排队（分组标签 = 站内小写字号语言，无装饰条）。
//
// 数据面（任务书：功能逻辑不重造）：与 taskList.js **同一**读取口
//（collectChains 导出缝——活跃行直接吃 collectChains 现值（与面板行同源））。
// 渲染触发 = delegate-chain-mirror-updated + 会话切换（与 taskList 同族事件），
// Nebula 统一面会话门同源（sessionShowsNodeEntries）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import state from '../state.js';
import {
  collectChains, DELEGATE_WORD_KEY, chainProgressMeta, rowMetaOf, sessionShowsNodeEntries,
} from '../taskList.js';
import { logEvent } from './navController.js';

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

/** 面板壳（惰性一件；挂 body 级 —— fixed 几何自带，无包含块陷阱）。 */
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

  panel.append(head, bd);
  document.body.appendChild(panel);
  if (typeof lucide !== 'undefined') createIconsIn(panel);

  // 与 taskList 同族的重渲触发：镜像刷新 + 会话切换（开着的浮层跟数据走）。
  window.addEventListener('delegate-chain-mirror-updated', () => { if (isTaskPanelOpen()) render(); });
  window.addEventListener('nebflow-session-change', () => { if (isTaskPanelOpen()) render(); });
  return panel;
}

/** 任务键徽标读数 = 在册任务数（与面板 stats 同一数据面，浮层关着也跟新）。 */
export function taskBadgeCount() {
  if (!sessionShowsNodeEntries(state.activeSessionId)) return 0;
  return collectChains().length;
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

function render() {
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
