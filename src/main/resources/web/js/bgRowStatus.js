// bgRowStatus.js — 后台面板行「状态视觉」单点（UI 批 · 后台智能体/后台任务三行结构，
// 作者令 2026-11-05 17:53 逐字「第一行 name · 状态（用图标显示）· time」）。
//
// ── 为什么独立成模块 ───────────────────────────────────────────────────────
// 本批之前，行状态有 **四条各自为政的呈现链**：左侧 6px 圆点（.bg-task-status 的
// .bg-status-*）、行内中文状态词（.bg-task-state 的 bg-state-*）、零散的词前图标
// （stuck 的 timer-off / error 的 circle-x / chain 的 workflow，发射点各写一次）、
// 以及冻结族的 snowflake。作者报障的形态（「进行中 / 候合并 / 自动恢复中」这类中文
// 词挤在名字旁边）就是这条多链叠行的结果。本批把「状态 → 图标 / 色调 / 无障碍文案」
// 收成**一张表**（[[BG_STATUS_VISUAL]]）＋**一个发射点**（bgStatusHtml）：
//   · 新增状态 = 表里加一行；渲染点一处都不用改；
//   · 图标不许散落 switch（本文件之外零 icon 名）；
//   · 色调只给 CSS 类名（.bg-row-status[data-tone] 见 chat.css 单块），JS 里零色值 —
//     设计令牌门禁（check-design-tokens）对 JS 颜色字面量只许减不许增。
//
// ── 无障碍口径 ─────────────────────────────────────────────────────────────
// 状态**只剩图标**上屏 ⇒ 信息不能只靠颜色/形状：每个状态位恒带
//   ① role="img" + aria-label（读屏播报状态词）；
//   ② title（鼠标悬停拿同词 + 细节）；
//   ③ 行内 sr-only 文本（.bg-row-sr，`clip-path: inset(50%)`）——它是视觉隐藏的
//      **真文本**，既有回归断言（.bg-task-state 的 textContent = 「进行中」）与
//      读屏播报读到的都是它，不是装饰性 aria 声明。
//
// 依赖方向：只依赖 i18n / utils（零环）。

import { t } from './i18n.js';
import { escapeHtml, statusIcon } from './utils.js';

/**
 * 状态 → { 图标（lucide 名）, 色调（CSS data-tone）, 文案键 }。
 * 色调词表**同时**是既有 .bg-status-* 类名的后缀词表（compat：行集回归锚
 * `.bg-task-status.bg-status-active` 仍成立）；新增色调 = 表里加行 + CSS 加一条色。
 * @type {Record<string, {icon: string, tone: string, labelKey: string}>}
 */
export const BG_STATUS_VISUAL = {
  active: { icon: 'loader', tone: 'active', labelKey: 'subagents.status.running' },
  idle: { icon: 'clock', tone: 'idle', labelKey: 'subagents.status.idle' },
  waiting: { icon: 'hourglass', tone: 'waiting', labelKey: 'subagents.status.waiting' },
  frozen: { icon: 'snowflake', tone: 'frozen', labelKey: 'subagents.status.frozen' },
  stuck: { icon: 'timer-off', tone: 'stuck', labelKey: 'subagents.status.stuck' },
  error: { icon: 'circle-x', tone: 'error', labelKey: 'subagents.status.error' },
  done: { icon: 'circle-check', tone: 'done', labelKey: 'subagents.status.done' },
  cancelling: { icon: 'circle-slash', tone: 'cancelling', labelKey: 'bg.cancelling' },
  chain: { icon: 'workflow', tone: 'chain', labelKey: 'subagents.chainRunning' },
  // 模型粒度批（作者令 2026-11-06 20:36）：重启/崩溃中断行（后端
  // SubAgentStartupRecovery 标 `pending-recovery`，零通知 + 不自动恢复）——
  // 等你的恢复动作，不是错误也不是进行中；色调复用 waiting（不新增 CSS 色）。
  'pending-recovery': { icon: 'hourglass', tone: 'waiting', labelKey: 'delegate.pendingRecovery' },
};

/** 未知状态回落档（脏数据/旧后端）：不崩、不空、最保守 = 空闲中性色。 */
const FALLBACK = 'idle';

/**
 * 状态视觉读点（图标名 + 色调）。渲染面只读这两个字段——别处不许再写 icon 名。
 * @param {string} state 状态词（BG_STATUS_VISUAL 的键）
 * @returns {{icon: string, tone: string, labelKey: string}}
 */
export function bgStatusVisual(state) {
  return BG_STATUS_VISUAL[String(state || '')] || BG_STATUS_VISUAL[FALLBACK];
}

/**
 * 状态位 HTML（图标 + sr-only 文本 + aria-label/title）——**唯一发射点**。
 *
 * 类名族：
 *   `.bg-row-status`  —— 本批几何（行内图标格，chat.css）；
 *   `.bg-task-status` —— 既有行集锚（保留：`[data-tone]` 之外仍带 .bg-status-<tone>）；
 *   `.bg-task-state`  —— 既有状态词锚（read 到的是 sr-only 文本）；
 *   `.bg-status-<tone>` —— 既有色调类（CSS 单块给色）。
 *
 * @param {string} state BG_STATUS_VISUAL 的键（active/idle/waiting/…）
 * @param {{label?: string, title?: string, extraClass?: string, tone?: string}} [opts]
 *   label = 显式文案（后台任务行有自己的词表：已完成/已取消…）；缺席走表里的键；
 *   title = 悬停补充（合并队列位次 / 冻结原因 / 设备离线等细节），完整词仍在；
 *   tone  = 色调覆写（冻结态误报批：自动恢复族用 waiting 色而非 frozen 色——形态
 *           仍是雪花图标，只换色阶；唯一调用点是 bgAgents 冻结族那一条）。
 * @returns {string} HTML
 */
export function bgStatusHtml(state, opts = {}) {
  const v = bgStatusVisual(state);
  const tone = String(opts.tone || v.tone);
  const label = String(opts.label || t(v.labelKey));
  const title = opts.title ? label + ' · ' + String(opts.title) : label;
  const extra = opts.extraClass ? ' ' + opts.extraClass : '';
  // 兼容锚（Flow 会话可见性批的 `.bg-task-state.bg-task-chain` 回归取点）：链态额外
  // 带 `bg-task-chain`——旧类名是回归/读数的既有锚，本批不因换图标而改名。
  const chainCls = tone === 'chain' ? ' bg-task-chain' : '';
  return '<span class="bg-row-status bg-task-status bg-task-state bg-status-' + tone + chainCls + extra + '"'
    + ' role="img" data-tone="' + tone + '"'
    + ' aria-label="' + escapeHtml(title) + '" title="' + escapeHtml(title) + '">'
    + statusIcon(v.icon, 13)
    + '<span class="bg-row-sr">' + escapeHtml(label) + '</span>'
    + '</span>';
}
