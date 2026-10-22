// turnActivity.js — 会话级「回合活动流」条（对话层活性批 2026-10-22）。
//
// 症状（作者实测澄清）：长回合（尤其委派/后台批并跑的回合）期间，对话面完全
// 静默——根会话自己的工具行有 workline 转动，但**子代理/后台任务的活动**只在
// Sub-Agents 下拉与弹出窗里有面；用户不开弹窗时，主对话从头到尾一动不动，只有
// 最终文本落地面才动，体感等同死机。
//
// 诊断结论（ws 事件面不缺、缺的是对话层消费）：委派/节点事件
// （agentStart / agentToolStart / agentToolEnd / agentDone / delegateWaiting /
// backgroundTaskUpdate）后端已全部经 wsHub.broadcast 推到前端（带 rootSessionId
// 归属），但前端唯一消费者是 Sub-Agents 指示器（agentStart/agentToolStart 两处）
// ——收下了状态，没有给对话面任何一行。
//
// 本模块 = 那个缺失的**对话层面**：一条轻量活动条（#activity-strip，挂在
// #input-area 顶部、#queue-bar 之上），把回合内活动实时流式上屏——
//   · 批派发 / 批完成（agentStart / agentDone）
//   · 子代理工具调用（agentToolStart 的 label = 后端 summarizeToolCall 摘要）
//   · 待合并（delegateWaiting）
//   · 后台任务状态变化（backgroundTaskUpdate）
// 可折叠、不刷屏：默认收起 = 一行滚动摘要（最新一条 + 计数），点击展开最近
// N 条列表；同文连发 1.5s 窗内聚合计数。回合终态（done）清空——终态摘要由
// turn-header 徽标承担，活动条只管「回合进行中看得见在动」。
//
// 纪律：
//  · 会话级数据 = state.sessionActivity[sessionId]（全局，非激活会话的事件照收，
//    切回该会话时重放）——与 sessionBgAgents 同律。
//  · 叶子模块：只 import state / i18n，不 import chatView / chat（零环）。
//    渲染宿主 = document.getElementById('activity-strip')（主窗独有元素，弹窗无）。
//  · 纯 CSS 动画（无 rAF / 无 WAAPI），遵守 turn-* specs 对脚本动画的禁令。
//  · 无 emoji；图标 = 内联 SVG（workline.js 同款自绘惯例，不依赖 lucide 装载时序）。

import state from '../state.js';
import { t } from '../i18n.js';

/** state.sessionActivity[sid] 的容量上限（内存面，超出丢最旧）。 */
const ACTIVITY_CAP = 40;
/** 展开列表最多渲染的条数（更旧的仍在内存计数里，不渲染）。 */
const VISIBLE_ITEMS = 12;
/** 同文连发聚合窗：同 kind+text+who 在此窗口内重复到达只涨计数不新增行。 */
const COALESCE_MS = 1500;

/** 内联 SVG 图标（stroke 线稿，workline.js 的 CHECK_SVG 同族）。 */
const KIND_ICONS = {
  dispatch: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4z"/></svg>',
  tool: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>',
  done: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
  waiting: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>',
  bgtask: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
};

/** 活动条宿主（主窗唯一实例）。会话归属写在 data-sid 上。 */
function stripEl() {
  return document.getElementById('activity-strip');
}

/** 单条活动的展示文本：who（子代理短名）在场时前缀，例：
 *  「实现批 · Read src/main/scala/…」。 */
function lineText(entry) {
  return entry.who ? entry.who + ' · ' + entry.text : entry.text;
}

/** 记一条活动（ws 处理器唯一入口）。同文聚合 + 容量截断 + 在场即渲染。
 *  @param {string} sid  归属会话（事件信封的 rootSessionId——委派嵌套也归到
 *                       用户正在看的顶层会话，与 agentStart 的落桶口径同律）。
 *  @param {{kind?: string, text: string, who?: string, ts?: number}} entry
 */
export function noteActivity(sid, entry) {
  if (!sid || !entry || !entry.text) return;
  const list = state.sessionActivity[sid] || (state.sessionActivity[sid] = []);
  const ts = entry.ts || Date.now();
  const last = list[list.length - 1];
  if (last && last.kind === (entry.kind || 'tool') && last.text === entry.text &&
      last.who === (entry.who || '') && ts - last.ts < COALESCE_MS) {
    // 连发聚合：只涨计数、推进时间戳（摘要行带 ×N，不刷屏）。
    last.count = (last.count || 1) + 1;
    last.ts = ts;
  } else {
    list.push({
      kind: entry.kind || 'tool',
      text: entry.text,
      who: entry.who || '',
      ts,
      count: 1,
    });
  }
  if (list.length > ACTIVITY_CAP) list.splice(0, list.length - ACTIVITY_CAP);
  renderActivityStrip(sid, false);
}

/**
 * 渲染某会话的活动条。
 * @param {string|null} sid 目标会话
 * @param {boolean} force  true = 强制重画为该会话（会话切换腿）；false = 仅当
 *                         条当前正显示该会话才重画（事件腿——条在显示别的会话
 *                         时不动它，数据已入 state.sessionActivity，切回时重放）。
 */
export function renderActivityStrip(sid, force) {
  const el = stripEl();
  if (!el) return;
  if (!sid) { hideStrip(el); return; }
  if (!force && el.dataset.sid !== sid) return;
  el.dataset.sid = sid;
  const list = state.sessionActivity[sid] || [];
  if (list.length === 0) { hideStrip(el); return; }

  const latest = list[list.length - 1];
  const total = list.reduce((n, e) => n + (e.count || 1), 0);

  el.classList.add('visible');
  el.innerHTML = '';

  // 头行（= 收起态全文）：图标 + 「活动」+ 计数 + 最新一条摘要。点击整条切换展开。
  const head = document.createElement('button');
  head.type = 'button';
  head.className = 'activity-strip-head';
  head.setAttribute('aria-expanded', el.classList.contains('expanded') ? 'true' : 'false');
  const icon = document.createElement('span');
  icon.className = 'activity-strip-glyph';
  icon.innerHTML = KIND_ICONS[latest.kind] || KIND_ICONS.tool;
  head.appendChild(icon);
  const title = document.createElement('span');
  title.className = 'activity-strip-title';
  title.textContent = t('activity.title');
  head.appendChild(title);
  const count = document.createElement('span');
  count.className = 'activity-strip-count';
  count.textContent = t('activity.count', { n: total });
  head.appendChild(count);
  const summary = document.createElement('span');
  summary.className = 'activity-strip-summary';
  summary.textContent = lineText(latest) + (latest.count > 1 ? ' ×' + latest.count : '');
  summary.title = summary.textContent;
  head.appendChild(summary);
  const chevron = document.createElement('span');
  chevron.className = 'activity-strip-chevron';
  chevron.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
  head.appendChild(chevron);
  head.addEventListener('click', () => {
    el.classList.toggle('expanded');
    head.setAttribute('aria-expanded', el.classList.contains('expanded') ? 'true' : 'false');
  });
  el.appendChild(head);

  // 展开列表：最近 VISIBLE_ITEMS 条（新在上）。
  const body = document.createElement('div');
  body.className = 'activity-strip-body';
  const view = list.slice(-VISIBLE_ITEMS).reverse();
  for (const entry of view) {
    const rowEl = document.createElement('div');
    rowEl.className = 'activity-strip-item kind-' + entry.kind;
    const g = document.createElement('span');
    g.className = 'activity-item-glyph';
    g.innerHTML = KIND_ICONS[entry.kind] || KIND_ICONS.tool;
    rowEl.appendChild(g);
    const txt = document.createElement('span');
    txt.className = 'activity-item-text';
    txt.textContent = lineText(entry) + (entry.count > 1 ? ' ×' + entry.count : '');
    txt.title = txt.textContent;
    rowEl.appendChild(txt);
    const when = document.createElement('span');
    when.className = 'activity-item-time';
    when.textContent = fmtClock(entry.ts);
    rowEl.appendChild(when);
    body.appendChild(rowEl);
  }
  el.appendChild(body);
}

function hideStrip(el) {
  el.classList.remove('visible');
  el.classList.remove('expanded');
  el.innerHTML = '';
}

/** 清空某会话的活动流（回合终态收口）。数据删除 + 条隐藏。 */
export function clearActivity(sid) {
  if (!sid) return;
  delete state.sessionActivity[sid];
  const el = stripEl();
  if (el && (!el.dataset.sid || el.dataset.sid === sid)) hideStrip(el);
}

/** 会话切换腿：既有 queuebar-refresh 事件（chatView.setSession 派发，带新
 *  sessionId）驱动强制重画 + 收回展开态（默认一行摘要，别刷屏）。 */
window.addEventListener('queuebar-refresh', (e) => {
  const detail = e && /** @type {CustomEvent} */ (e).detail;
  const sid = detail ? detail.sessionId : null;
  const el = stripEl();
  if (el) el.classList.remove('expanded');
  renderActivityStrip(sid, true);
});

/** HH:MM 时钟（列表行右侧；紧凑、无日期）。 */
function fmtClock(ts) {
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes());
}
