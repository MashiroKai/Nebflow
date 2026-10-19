// mobile/taskPanel.js — v3「二级液态任务浮层」（样张 20261020_214500 v3⑤ 同款）。
//
// 形态：下拉列中点「任务」→ 六键液态收回 → 圆钮原地形变加宽成任务浮层
//（40→308 / 40→560，圆角 20→16，spring 过冲回稳）；X 逆向收回；点空白全收。
// 12 卡信息层级（六级，样张⑤口径）：
//   ① 状态点（运行绿 / 排队灰 / 完成绿 / 失败红）
//   ② 标题（#taskNo + 名）
//   ③ 进度 n/m（tabular-nums，chainProgressMeta 同源）
//   ④ 状态词（DELEGATE_WORD_KEY 同源键）
//   ⑤ 成员预览（仅运行中卡，限 2 行 +「+n」溢出计数）
//   ⑥ 分组序：运行中 → 排队 → 已完成（已完成整卡降透明；分组标签 = 站内
//      小写字号语言，无装饰条）。
//
// 数据面（任务书：功能逻辑不重造）：与 taskList.js **同一**读取口
//（delegateChainStub.js listDelegateRows / getChainByTask）+ collectChains 导出
// 缝 —— 活跃组直接吃 collectChains 现值（与面板行同源），已完成组按同一状态域
// 从 listDelegateRows 终态行补出（桌面面板不显示终态行 = 既有口径，浮层补该组
// 纯为样张分组序；呈现层职责）。渲染触发 = delegate-chain-mirror-updated +
// 会话切换（与 taskList 同族事件），Nebula 统一面会话门同源（sessionShowsNodeEntries）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import state from '../state.js';
import { listDelegateRows, getChainByTask } from '../delegateChainStub.js';
import {
  collectChains, DELEGATE_WORD_KEY, chainProgressMeta, rowMetaOf, sessionShowsNodeEntries,
} from '../taskList.js';
import { morphIn, morphOut } from './morphPanel.js';
import { logEvent } from './navController.js';

const CHECK_SVG = '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.6 6.2l1.7 1.7 3.1-3.6"/></svg>';
const MEMBER_PREVIEW_MAX = 2;

/** @type {HTMLElement|null} */
let panel = null;
/** @type {HTMLElement|null} */
let listEl = null;
/** @type {HTMLElement|null} */
let statsEl = null;
/** @type {HTMLElement|null} */
let originBtn = null;
/** @type {number|null} */
let openTimer = null;

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

/** 状态域三分（样张分组序）：运行中（pending/running/blocked）→ 排队（ready-to-merge）→ 已完成（终态）。 */
function groupOf(status) {
  if (status === 'ready-to-merge') return 'queued';
  if (['completed', 'failed', 'cancelled'].includes(status)) return 'done';
  return 'running';
}

/** 已完成组：与活跃组同一读取口的终态行（未归档，新→旧；呈现层补组）。 */
function collectDone() {
  if (!sessionShowsNodeEntries(state.activeSessionId)) return [];
  const ts0 = (r) => {
    const v = typeof r.createdAt === 'number' ? r.createdAt : Date.parse(String(r.createdAt || ''));
    return Number.isFinite(v) ? v : 0;
  };
  return listDelegateRows()
    .filter((r) => r && !r.archivedAt && ['completed', 'failed', 'cancelled'].includes(String(r.status)))
    .sort((a, b) => ts0(b) - ts0(a));
}

function glyph(cls) {
  const g = document.createElement('span');
  g.className = `tp-glyph tp-g-${cls}`;
  g.setAttribute('aria-hidden', 'true');
  return g;
}

/** 一张任务卡（六级层级见文件头）。block = collectChains 现值或 done 行的轻包。 */
function buildCard(entry) {
  const r = entry.row;
  const st = String(r.status || 'running');
  const grp = groupOf(st);
  const taskNo = String(r.taskNo == null ? '' : r.taskNo);
  const title = String(r.title || r.delegateId || '');
  const wordKey = DELEGATE_WORD_KEY[st] || '';
  const members = entry.members || [];

  const card = document.createElement('div');
  card.className = `tp-card tp-${grp}` + (grp === 'done' ? ' tp-dim' : '');
  card.setAttribute('role', 'button');

  const hd = document.createElement('div');
  hd.className = 'tp-c-hd';
  hd.appendChild(glyph(grp === 'queued' ? 'queue' : grp === 'done' ? (st === 'failed' ? 'fail' : 'done') : 'run'));
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

  // ⑤ 成员预览：仅运行中卡（排队=display-only、已完成=整卡降透明，均不出成员）。
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
  const key = grp === 'running' ? 'taskpanel.grpRunning' : grp === 'queued' ? 'taskpanel.grpQueued' : 'taskpanel.grpDone';
  return `${t(key)} · ${n}`;
}

function render() {
  if (!listEl || !statsEl) return;
  const chains = collectChains();
  const done = collectDone();
  const running = chains.filter((c) => groupOf(String(c.row.status)) === 'running');
  const queued = chains.filter((c) => groupOf(String(c.row.status)) === 'queued');
  const unitCount = chains.reduce((n, c) => n + (c.allCount || 1), 0) + done.length;
  statsEl.textContent = t('taskpanel.stats', { n: String(unitCount), run: String(running.length) });

  listEl.replaceChildren();
  const groups = [
    ['running', running.map((c) => ({
      key: c.key, row: c.row, members: c.view.ordered,
      progressText: c.view.ordered.length > 1 ? chainProgressMeta(c.view.ordered).split(' · ')[0] : '',
    }))],
    ['queued', queued.map((c) => ({ key: c.key, row: c.row, members: [], progressText: '' }))],
    ['done', done.map((r) => ({ key: String(r.taskNo || r.delegateId || ''), row: r, members: [], progressText: '' }))],
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

/** 开浮层：morph 自 origin 圆钮（调用方已把下拉列收回）。 */
export function openTaskPanel(originEl) {
  const p = ensurePanel();
  originBtn = originEl || null;
  render();
  p.hidden = false;
  logEvent('tpanel-open', {});
  morphIn(p, originEl);
}

/** 收浮层；viaScrim（点空白）直接收 —— X 走逆向形变（样张口径）。 */
export function closeTaskPanel(instant = false) {
  if (!isTaskPanelOpen()) return;
  const p = /** @type {HTMLElement} */ (panel);
  logEvent('tpanel-close', { via: instant ? 'scrim' : 'x' });
  if (instant || !originBtn) {
    p.hidden = true;
    return;
  }
  morphOut(p, originBtn, () => { p.hidden = true; });
}
