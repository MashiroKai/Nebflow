// bgAgentWorkStrip.js — delegate 详情弹窗的「会话在做什么」条（#752 面板进展可见化）。
//
// 作者实证：「这种 delegate 显示空闲状态不理人是怎么回事，也看不到进展」——
// 弹窗内此前只有聊天流与摘要条，该会话名下的后台任务与其最近动作无处可看。
// 本条挂载在 bgAgentPopup 弹窗头与聊天体之间，两段：
//   · 最近动作 —— 该会话最后一次工具调用（agentToolStart 实时帧的 label）+
//     agent 侧活动距今（快照 lastActivityMs）；Processing 行 = 在飞工具名。
//   · 后台任务 —— state.sessionBgTasks 里 sessionId 归属本会话的任务行
//     （描述/状态/行数/时长），点击行 = openBgTaskOutput 输出详情卡（既有
//     承载复用，零新弹层语义）。
//
// 数据面零新协议：全部读前端既有 state（bgAgents 快照合并 + backgroundTaskUpdate
// 分桶），开弹窗时 fetchActiveAgentsOnOpen 已有 1s 冷却快照回填。刷新 = 弹窗
// 打开期间 15s 一拍（时长/活动戳滚动），关闭即停。XSS 纪律：动态文本一律
// textContent；样式自注入，颜色/尺寸全走既有 CSS 变量（零裸 hex）。

import { t } from './i18n.js';
import state from './state.js';
import { statusIcon } from './utils.js';
import { openBgTaskOutput } from './bgTaskOutputPopup.js';

const STRIP_CSS_ID = 'bgagent-work-strip-css';
const REFRESH_MS = 15000;

const STRIP_CSS = `<style id="${STRIP_CSS_ID}">
/* 「会话在做什么」条：弹窗头下、聊天体上的窄条（玻璃语言，零新设计词）。 */
.bgws {
  border-bottom: 1px solid var(--glass-border);
  padding: 6px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex-shrink: 0;
  font: 400 11px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text-muted);
  max-height: 108px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.bgws-row { display: flex; align-items: center; gap: 6px; min-width: 0; }
.bgws-label {
  flex-shrink: 0;
  font-weight: 500;
  color: var(--color-frame-text-dim, var(--color-text-muted));
}
.bgws-value { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bgws-value .st-ic { width: 11px; height: 11px; margin-right: 3px; vertical-align: -1px; }
.bgws-idle { color: var(--status-warn-icon); font-weight: 500; }
.bgws-task {
  cursor: pointer;
  border-radius: 6px;
  padding: 1px 4px;
  margin-left: -4px;
}
.bgws-task:hover { background: var(--color-frame-hover); }
.bgws-task .bgws-task-meta {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--color-frame-text-muted);
  flex-shrink: 0;
}
.bgws-task.running .bgws-task-name { color: var(--color-text); }
.bgws-empty { color: var(--color-frame-text-dim, var(--color-text-muted)); }
</style>`;

function ensureCss() {
  if (!document.getElementById(STRIP_CSS_ID)) {
    const holder = document.createElement('div');
    holder.innerHTML = STRIP_CSS;
    document.head.appendChild(holder.firstElementChild);
  }
}

function fmtDur(ms) {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  const h = Math.floor(m / 60);
  return h + 'h ' + (m % 60) + 'm';
}

/** 弹窗会话的行数据：subagent 面板桶里 sessionId 匹配本会话的 entry。 */
function findAgentInfo(nodeSessionId) {
  for (const bucket of Object.values(state.sessionBgAgents || {})) {
    for (const info of Object.values(bucket || {})) {
      if (info && (info.sessionId || '').replace(/^team-/, '') === nodeSessionId) return info;
    }
  }
  return null;
}

/** 本会话名下的后台任务（task.sessionId === 会话 id），新任务在前。 */
function sessionBgTasks(nodeSessionId) {
  const out = [];
  for (const bucket of Object.values(state.sessionBgTasks || {})) {
    for (const task of bucket || []) {
      if (task && task.sessionId === nodeSessionId) out.push(task);
    }
  }
  return out.sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
}

function taskStateWord(task) {
  if (task.status === 'completed') return t('bg.completed');
  if (task.status === 'failed') return t('bg.failed');
  if (task.status === 'cancelled') return t('bg.cancelled');
  if (task.status === 'cancelling') return t('bg.cancelling');
  return t('bg.running');
}

function taskLines(task) {
  const hb = task.heartbeat;
  if (hb && typeof hb.outputLines === 'number') return hb.outputLines;
  return typeof task.outputLines === 'number' ? task.outputLines : 0;
}

/** 渲染（就地重画 strip 内容；DOM 全 textContent，无 innerHTML 插值）。 */
function renderStrip(stripEl, nodeSessionId) {
  if (!stripEl || !stripEl.isConnected) return;
  stripEl.textContent = '';
  const info = findAgentInfo(nodeSessionId);
  const tasks = sessionBgTasks(nodeSessionId);

  // ── 最近动作行 ──
  const actRow = document.createElement('div');
  actRow.className = 'bgws-row';
  const actLabel = document.createElement('span');
  actLabel.className = 'bgws-label';
  actLabel.textContent = t('bgws.lastAction');
  const actValue = document.createElement('span');
  actValue.className = 'bgws-value';
  const status = String((info && info.status) || '');
  const tool = String((info && (info.currentTool || '')) || '');
  const activityAgo = info && Number(info.lastActivityMs) > 0
    ? t('bgws.activityAgo', { dur: fmtDur(Date.now() - Number(info.lastActivityMs)) })
    : '';
  if (status === 'Processing') {
    // 在飞：工具名（或思考中）+ 运行态点。
    actValue.className = 'bgws-value';
    actValue.textContent = tool ? tool : t('bgws.thinking');
  } else if (status === 'Waiting') {
    // statusIcon 返回的是 lucide 生成的 SVG 字符串（库内模板，非用户数据）——
    // 与 bgAgents.js 行渲染同一注入姿势：innerHTML 承载图标，动态文本走 textContent。
    actValue.innerHTML = statusIcon('pause', 11);
    actValue.appendChild(document.createTextNode(t('subagents.status.waitingMerge')));
  } else if (status === 'Idle' || status === 'WaitingForUser' || status === 'idle') {
    // 空闲细分：等什么（bgWaiting / 回执）或长空闲徽标——与面板行同判据。
    const bgWaiting = Array.isArray(info?.bgWaiting) ? info.bgWaiting : [];
    const receipts = (Number(info?.heldReceipts) || 0) + (Number(info?.unreadReceipts) || 0)
      + (Number(info?.outstandingSubagents) || 0);
    if (bgWaiting.length > 0) {
      const w = bgWaiting[0];
      const dur = w.startedAt ? fmtDur(Date.now() - w.startedAt) : '';
      actValue.textContent = t('subagents.waitingBg', { count: bgWaiting.length, desc: String(w.description || w.taskId || ''), dur });
    } else if (receipts > 0) {
      actValue.textContent = t('subagents.waitingReceipts', { count: receipts });
    } else {
      const idleMs = Number(info?.lastActivityMs) > 0 ? Date.now() - Number(info.lastActivityMs) : 0;
      if (idleMs > 10 * 60 * 1000) {
        actValue.className = 'bgws-value bgws-idle';
        actValue.innerHTML = statusIcon('timer-off', 11, 'st-warn');
        actValue.appendChild(document.createTextNode(t('subagents.longIdle', { mins: Math.floor(idleMs / 60000) })));
      } else {
        actValue.textContent = t('subagents.status.idle') + (activityAgo ? ' · ' + activityAgo : '');
      }
    }
  } else if (status) {
    actValue.textContent = status + (activityAgo ? ' · ' + activityAgo : '');
  } else {
    actValue.className = 'bgws-value bgws-empty';
    actValue.textContent = t('bgws.noData');
  }
  actRow.append(actLabel, actValue);
  stripEl.appendChild(actRow);

  // ── 后台任务行（每任务一行，点击 = 输出卡；无任务 = 一行空注记）──
  const maxRows = 4;
  const shown = tasks.slice(0, maxRows);
  for (const task of shown) {
    const row = document.createElement('div');
    const running = task.status === 'running' || task.status === 'cancelling';
    row.className = 'bgws-row bgws-task' + (running ? ' running' : '');
    row.setAttribute('role', 'button');
    row.setAttribute('tabindex', '0');
    const name = document.createElement('span');
    name.className = 'bgws-value bgws-task-name';
    name.textContent = String(task.description || task.taskId || '');
    name.title = String(task.description || task.taskId || '');
    const meta = document.createElement('span');
    meta.className = 'bgws-task-meta';
    meta.textContent = [
      taskStateWord(task),
      t('bg.lines', { count: taskLines(task) }),
      task.startedAt ? fmtDur(Date.now() - task.startedAt) : '',
    ].filter(Boolean).join(' · ');
    row.append(name, meta);
    const open = () => openBgTaskOutput(task);
    row.addEventListener('click', open);
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    });
    stripEl.appendChild(row);
  }
  if (tasks.length > maxRows) {
    const more = document.createElement('div');
    more.className = 'bgws-row';
    const moreVal = document.createElement('span');
    moreVal.className = 'bgws-value bgws-empty';
    moreVal.textContent = t('bgws.moreTasks', { count: tasks.length - maxRows });
    more.appendChild(moreVal);
    stripEl.appendChild(more);
  } else if (tasks.length === 0) {
    const none = document.createElement('div');
    none.className = 'bgws-row';
    const noneVal = document.createElement('span');
    noneVal.className = 'bgws-value bgws-empty';
    noneVal.textContent = t('bgws.noBgTasks');
    none.appendChild(noneVal);
    stripEl.appendChild(none);
  }
}

// 打开中的 strip 账本：{ stripEl, timer, nodeSessionId }（单例——同一时刻一个弹窗）。
let mounted = null;

/**
 * 挂载「会话在做什么」条到 delegate 弹窗（bgAgentPopup.openStepPopup 调用）。
 * @param {string} nodeSessionId 弹窗会话 id（delegate- / subtask- 前缀族）
 * @param {HTMLElement} modal 弹窗 modal 元素（strip 插到聊天体之前）
 * @param {HTMLElement} chatContainer 聊天体容器（插入锚点）
 */
export function mountSessionWorkStrip(nodeSessionId, modal, chatContainer) {
  unmountSessionWorkStrip();
  ensureCss();
  const stripEl = document.createElement('div');
  stripEl.className = 'bgws';
  stripEl.setAttribute('aria-label', t('bgws.aria'));
  modal.insertBefore(stripEl, chatContainer);
  renderStrip(stripEl, nodeSessionId);
  const timer = setInterval(() => renderStrip(stripEl, nodeSessionId), REFRESH_MS);
  mounted = { stripEl, timer, nodeSessionId };
}

/** 卸载（closeStepPopup 调用）：停刷新拍、摘元素。幂等。 */
export function unmountSessionWorkStrip() {
  if (!mounted) return;
  if (mounted.timer) clearInterval(mounted.timer);
  if (mounted.stripEl && mounted.stripEl.isConnected) mounted.stripEl.remove();
  mounted = null;
}
