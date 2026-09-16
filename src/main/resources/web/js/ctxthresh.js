// ctxthresh.js — Nebula 窗口 Header 上下文压缩阈值 = **环上拖动**（ctxring 批，
// 2026-09-17 恢复；入口 = 环本身，**唯一**）。
//
// ── 口径来源（逐字可复现）─────────────────────────────────────────────────
// 作者 2026-09-16 17:08 三题裁定：
//   P1 = **替换（删面板）**：小面板入口整路径删除；环上拖动成为唯一入口。
//   P2 = **起手即写值**：按下即按指针角度取值（无位移闸，连点一下也写值）；
//        移动跟手更新、松开一次性提交。交互模型照设计卡（atan2 角度投影、
//        径向忽略、几何不脱环）。
//   P3 = **原径 28px + 隐形命中面 32×44**（`::before { inset:-8px -2px }`）。
// 设计卡（作者已阅并据此拍板）：
//   `~/.nebflow/docs/Nebflow/20260916_170435_ctxring-restore-design__chain-n-b9a95e1a.md`
//   §1 交互模型 / §2 语义对接 / §3 视觉 / §4 真渲染 / §5 影响面与 L1–L10 / §6 备选。
// 归因（作者 2026-09-17 更正）：08-10 整体删除该交互的**真因 = 上下文窗口被固定下来**
//   （阈值无可调对象，控件连带删除），**不是交互质量被否** —— 逐字「这个交互其实挺好的」。
//
// ── 不脱环 = 几何构造保证（不是夹取出来的）──────────────────────────────────
// 把手 = 既有阈值刻线 `.ctx-ring-threshold`（SVG `<line>`，`main.js` 环模板）。
// 拖动中**只写一个属性**：`transform = rotate(<pct × 3.6> 18 18)`。
// `<line>` 的 `x1/y1/x2/y2` 全程恒为 `18/1.5/18/5`（半径 16.5→13，viewBox 单位）
// ⇒ **径向自由度为零**：没有 clip-path、没有极坐标反算回环、没有径向夹取 ——
// 代码里不存在能把把手推离环的路径。
// 环心 `(18,18)` 写死在 viewBox 里（**不**从 getBoundingClientRect 反算）；
// 缩放 s = 28/36 ⇒ 把手半径中点 11.47px、刻线内/外端 10.11 / 12.83px。
// 1 个百分点 = **3.6°**（= 360 × StepRatio），与 `main.js` 的 `thresholdPct * 3.6`
// 同源 ⇒ 阈值刻线与用量弧**同一把尺子**。方案原文（含实测读数）见设计卡 §1.1/§4.2。
//
// ── 取值（设计卡 §1.2）────────────────────────────────────────────────────
//   `deg = atan2(dx, -dy)`（12 点 = 0%、顺时针；dy 取负）⇒ `pct = deg / 3.6`。
//   **径向距离完全忽略**（指针离环心多远都只取角度，拖到屏外也照算）；
//   **抓取偏移忽略**（起手即取当前角度，不是相对位移累积）；
//   量化收敛到 **1% 网格**（与后端 `StepRatio = 0.01` 同格）；
//   退化保护：指针压在环心（半径 < 1px）⇒ 本次读数作废（`atan2(0,-0)` 会解出
//   180° = 50% 的假值）；环心屏幕坐标**起手取一次快照**，拖动中不复算
//   （页面滚动/布局变动期间基准漂移 = 与旧实现同源的已知项，本批不额外缓解）。
//
// ── 提交 / 取消（设计卡 §1.3）──────────────────────────────────────────────
//   `pointerup` **一次性提交**：WS `setCompactThreshold`，载荷
//   `{ type, sessionId, ratio }`，`ratio` = 1% 量化值（`ratio: null` = 恢复默认）。
//   取消腿（**古早实现缺失，本批补上，它是缺失不是过度缓解**）：
//   `pointercancel` / `Escape` / `lostpointercapture` ⇒ 清预览 + 回弹权威值 + **零出站**。
//
// ── 与现阈值语义对接（设计卡 §2，🔴 禁凭记忆）────────────────────────────────
//   值域 `15% < r ≤ 90%`（`protocol.scala` `CompactThresholdOverride`：MinRatio /
//   MaxRatio / StepRatio —— 本文件常量与它**必须同步**，禁只改一侧）；
//   动态下限 `max(16%, 当前用量)`（`minRatioFor` ↔ `effectiveMinRatio()`）；
//   钳制 `clampRatio`（`< lo ⇒ lo`、`> MaxRatio ⇒ MaxRatio`；`lo` 自身钳到 90 防倒挂出站）；
//   🔴 **90% 硬顶**（root 2026-09-15 逐字）：`当前用量 ≥ 90%` ⇒ 可选区间
//   `[max(15%, 用量), 90%]` **空集** ⇒ **不可拖 + 零出站 + 零文案**（静默锁死即终形）。
//   权威值源 = WS 回显帧 `compactThresholdInfo`（`ratio / contextWindow /
//   effectiveThreshold / effectiveRatio / defaultThreshold / defaultRatio /
//   minRatio / maxRatio`）——UI **不回算、不自造值**；`effectiveRatio` 语义
//   「**无覆盖 ⇒ = 默认值比例**」逐字沿用，未被本批改写。
//   生效路径 = 会话级 override → 一次性回显帧 → 写
//   `state.sessionModelInfo[sid].compactThreshold` → `state.updateHeaderModelInfo()`
//   → 环刻线自动跟到新角度。**无重载 / 无重连 / 无刷新**。
//   持久化 = `SessionMeta.compactThresholdRatio` → `<dataRoot>/sessions/_index.json`。
//   🔴 旧写值路径（`nebflow.json` 的 `compact.bufferRatio`）**已死且不使用**。
//
// ── 拖动期预览钩子（设计卡 S1；`main.js` 内 ≤3 行）────────────────────────────
//   拖动/暂留期间把当前值写进 `#header-model-info` 的 `data-ring-drag-preview-pct`，
//   `main.js` 的三处就地补丁优先取它 —— 否则拖动中若有 `usageUpdate` 帧到达，
//   把手/读数会被权威值抢回（与「跟手」直接冲突）。
//   暂留语义：提交后到权威帧到达前**保持**提交值（防「松手回跳」），
//   回显帧 / 取消 / `HOLD_MS` 兜底任一到达即交还权威值。
//
// ── 回帧腿的拖动期守卫（F-1 整改，2026-09-17）──────────────────────────────
//   `compactThresholdInfo` 到达时若 `dragging === true` ⇒ **只**更新权威状态
//   （`info` + `state.sessionModelInfo[sid].compactThreshold`），**不调用** `clearPreview()`。
//   否则回帧内含的 `endDrag()` 会把手势自己掐死：冷页（`info` 未初始化）或换会话后的
//   **第一次**拖动起手即补拉 ⇒ 回帧在拖动中到达 ⇒ 预览消失、`pointerup` 变 no-op、
//   松手零出站（＝作者 P2「起手即写值」在该路径上不成立）。交还权威值一律发生在
//   手势结束之后（`onPointerUp` 的 `commit()` / `clearPreview()` 或取消腿）。
//
// ── 面板删除边界（作者裁定 P1 = 替换；**本批删除仅限此界**）──────────────────
//   删除：面板的创建 / 打开 / 关闭 / 保存 / 复位按钮 / 面板滑杆拖动 / 数字框 /
//   键盘（面板期）/ 事件绑定 / 运行期注入样式（`.ctxthresh-*` 面板段）/
//   面板悬空 i18n 键 / `chat.css` 的 `.ctx-bar-*` 孤儿样式。
//   🔴 **不动**：钳制 / 回显 / 持久化 / 锁定态四条腿行为不变；后端零改动。
//
// ── 文案面（作者终裁 2026-09-15 逐字，仍适用）──────────────────────────────
//   逐字：「**不显示任何文字，只在拉动滑杆的时候，显示%比**」＋显式确认
//   「**不显示任何字**」⇒ ① 零常驻文字；② 环心读数只在**拖动中**切到阈值 %
//   （复用既有 `.ctx-ring-pct`，松手自动交还用量 %）；③ 锁定态只以「不可拖」表达，
//   **不新增任何文字**（静默锁死即终形）。

import { onMessage, sendWs } from './ws.js';
import { t } from './i18n.js';
import state from './state.js';
import { chatViews } from './chatView.js';

// 🔴 与后端 CompactThresholdOverride 同步的常量（禁只改一侧）
const MIN_RATIO = 0.15;   // 静态下限（开区间：r 必须严格大于它）
const MAX_RATIO = 0.90;   // 静态上限（闭区间）
const STEP_RATIO = 0.01;  // 1 个百分点

/** 1 个百分点 = 3.6°（= 360 × StepRatio）——与 `main.js` 的 `thresholdPct * 3.6` 同源。 */
const DEG_PER_PCT = 360 * STEP_RATIO;
/** viewBox 环心（与 `main.js` 环模板同源；🔴 不从 getBoundingClientRect 反算）。 */
const VB_CENTER = 18;
/** 角度导引线的终点半径（= 把手半径中点 14.75，viewBox 单位）——落在环内。 */
const GUIDE_RADIUS = 14.75;
/** 提交后到权威帧到达之前的暂留上限（兜底防「永不交还」）。 */
const HOLD_MS = 1500;
/** 环宿主元素 id（`main.js` 的 `updateHeaderModelInfo` 只重写其 innerHTML，宿主持久）。 */
const HOST_ID = 'header-model-info';

const CSS = `
<style id="ctxring-css">
/* ── 入口：环本身 = 唯一交互面（按下即取值、拖动跟手、松开提交）─────────────
   🔴 旧「点环开小面板」入口整路径随作者 2026-09-17 裁定 P1 删除。 */
#header-model-info {
  cursor: grab;
  user-select: none;
  touch-action: none;   /* pointer 拖动手势（含触屏）不被滚动/平移抢占 */
}
#header-model-info.ctx-ring-dragging { cursor: grabbing; }
#header-model-info:focus-visible {
  outline: 2px solid rgba(200,80,80,0.65);
  outline-offset: 2px;
  border-radius: 4px;
}

/* ── P3 命中面（作者裁定：原径 28px + 隐形命中面 32×44）─────────────────────
   走 **CSS 伪元素**：规则常驻 <head> ⇒ main.js 重建环 innerHTML 后命中面自动存活
   —— 零 DOM 保活、零观察器、零新增 header 叶子控件、几何与布局零位移。
   28+2×2 = 32 宽、28+2×8 = 44 高 ⇒ 纵向 44px 达 WCAG 2.5.8 目标尺寸。 */
#header-model-info .ctx-ring-wrap::before {
  content: '';
  position: absolute;
  inset: -8px -2px;
}

/* ── 拖动反馈层①：把手高亮（沿用既有红族，零新色值）────────────────────── */
#header-model-info.ctx-ring-dragging .ctx-ring-threshold {
  stroke: rgba(200,80,80,0.95);
  stroke-width: 2;
}
/* ── 拖动反馈层②：环心读数切阈值 %，字号 9px → 12px（设计系统「可读文本 ≥12px」
   地板；root 自决 ⑴ —— 设计位实测缺陷，顺手修入范围）─────────────────────
   亮/暗双档对比度 ≥ AA 4.5:1（实测读数见报告；改前 3.96 / 3.61 不达标）。
   色值仍属既有红族的色相 0° 族：亮档更深、暗档更亮。
   🔴 只在**拖动中**生效 ⇒ 静止态与既有环心读数逐字不变。 */
#header-model-info.ctx-ring-dragging .ctx-ring-pct {
  font-size: 12px;
  color: rgb(170,50,50);            /* 亮档：比 rgba(200,80,80,·) 更深 */
}
@media (prefers-color-scheme: dark) {
  #header-model-info.ctx-ring-dragging .ctx-ring-pct {
    color: rgb(230,120,120);        /* 暗档：比 rgba(200,80,80,·) 更亮 */
  }
}
</style>`;

// ── 状态 ────────────────────────────────────────────────────────────────
/** 服务端最近一次回显的权威值（唯一数据源；UI 不回算、不自造）。 */
let info = null;
/** 拖动 / 提交暂留中的预览值（**百分点整数**，1% 网格）；`null` = 无预览。 */
let previewPct = null;
/** 拖动会话进行中（指针已按下且未结束）。 */
let dragging = false;
/** 上一次已出站、尚未回显的值 —— 键盘 ←/→ 的连击基准。 */
let pendingPct = null;
/** 起手时的环心屏幕坐标快照（拖动中不复算 —— 与古早实现同）。 */
let center = null;
/** 指针 capture 的 pointerId。 */
let pointerId = null;
/** 暂留兜底定时器。 */
let holdTimer = null;
/** 监听只绑一次（`initCtxThresh` 可被 e2e 反复调用）。 */
let bound = false;

function primarySessionId() {
  return chatViews.primary?.sessionId || state.activeSessionId || null;
}

function hostEl() {
  return document.getElementById(HOST_ID);
}

/** 当前会话的已用比例（0..1；无用量读数 ⇒ 0）。 */
function usageRatio() {
  const sid = primarySessionId();
  const mi = sid ? state.sessionModelInfo?.[sid] : null;
  if (!mi || !mi.contextWindow || mi.inputTokens == null) return 0;
  return Math.max(0, Math.min(1, mi.inputTokens / mi.contextWindow));
}

/** 动态下限（作者卡答：不得小于当前用量，且 > 15%）——与后端
  * `CompactThresholdOverride.minRatioFor` 同式。 */
function effectiveMinRatio() {
  return Math.max(MIN_RATIO + STEP_RATIO, usageRatio());
}

/** 🔴 90% 硬顶（root 2026-09-15 逐字）：当前用量 ≥ 90% ⇒ 可选区间空集
  * ⇒ **不可拖 + 零出站 + 零文案**（静默锁死即终形）。 */
function overLimit() {
  return usageRatio() >= MAX_RATIO;
}

function clampRatio(r) {
  // 🔴 90% 硬顶：lo 自身也钳到 90%——超限档 lo > 90% 时旧式会返回 **> 90%** 的值（出站越界）
  const lo = Math.min(effectiveMinRatio(), MAX_RATIO);
  if (!isFinite(r)) return lo;
  if (r < lo) return lo;
  if (r > MAX_RATIO) return MAX_RATIO;
  return r;
}

/** 百分点 ⇒ 过钳制的百分点（1% 网格）。 */
function clampPct(pct) {
  return Math.round(clampRatio(pct / 100) * 100);
}

/** 百分点 ⇒ 出站比例值（与旧面板腿同式：先钳制、再 1% 量化）。 */
function ratioFromPct(pct) {
  return Math.round(clampRatio(pct / 100) * 100) / 100;
}

/** 权威值（百分点整数）；无权威读数 ⇒ `null`（**不用用量/默认值充数**）。 */
function authoritativePct() {
  const sid = primarySessionId();
  if (!sid) return null;
  if (info && info.sessionId === sid && typeof info.effectiveRatio === 'number' && isFinite(info.effectiveRatio)) {
    return Math.round(info.effectiveRatio * 100);
  }
  const mi = state.sessionModelInfo?.[sid];
  if (mi && typeof mi.compactThreshold === 'number' && isFinite(mi.compactThreshold)) {
    return Math.round(mi.compactThreshold * 100);
  }
  return null;
}

/** 键盘 ←/→ 的基准：优先「已出站未回显」的值（连击不被回显延迟吞掉），否则取权威值。 */
function stepBasePct() {
  return pendingPct != null ? pendingPct : authoritativePct();
}

// ── 预览绘制（拖动反馈三层，全为零新增 DOM 叶子）────────────────────────────
/** 角度导引线（反馈层③）：1px 虚线、从环心指向把手方向。
  * 🔴 **必须追加为 SVG 的最后一个子节点** —— `main.js` 的就地补丁用
  * `circle:nth-child(2)` 定位用量弧，插在末位不改既有子节点序号。 */
function ensureGuide(svg) {
  let g = svg.querySelector('.ctx-ring-guide');
  if (!g) {
    g = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    g.setAttribute('class', 'ctx-ring-guide');
    g.setAttribute('x1', String(VB_CENTER));
    g.setAttribute('y1', String(VB_CENTER));
    g.setAttribute('x2', String(VB_CENTER));
    g.setAttribute('y2', String(VB_CENTER - GUIDE_RADIUS));
    g.setAttribute('stroke', 'rgba(200,80,80,0.55)');   // 既有红族，零新色值
    g.setAttribute('stroke-width', '1');
    g.setAttribute('stroke-dasharray', '2 2');
    svg.appendChild(g);
  }
  return g;
}

function syncPreviewDataset() {
  const host = hostEl();
  if (!host) return;
  if (previewPct != null) host.dataset.ringDragPreviewPct = String(previewPct);
  else delete host.dataset.ringDragPreviewPct;
}

/** 绘制当前预览（把手角度 + 环心读数 + 导引线）。`previewPct == null` ⇒ no-op。 */
function paintPreview() {
  const host = hostEl();
  if (!host || previewPct == null) return;
  const line = host.querySelector('.ctx-ring-threshold');
  // 🔴 唯一更新语句：只改 transform —— `x1/y1/x2/y2` 全程不被触碰（几何不脱环）。
  if (line) line.setAttribute('transform', `rotate(${previewPct * DEG_PER_PCT} 18 18)`);
  const pctEl = host.querySelector('.ctx-ring-pct');
  if (pctEl) pctEl.textContent = String(previewPct);
  const svg = host.querySelector('.ctx-ring-svg');
  if (dragging && svg) {
    const g = ensureGuide(svg);
    const rad = (previewPct * DEG_PER_PCT) * Math.PI / 180;
    g.setAttribute('x2', String(VB_CENTER + GUIDE_RADIUS * Math.sin(rad)));
    g.setAttribute('y2', String(VB_CENTER - GUIDE_RADIUS * Math.cos(rad)));
  }
  syncPreviewDataset();
  syncAria();
}

/** 清预览 + 回弹权威值（取消腿 / 回显交还 / 暂留兜底三处共用）。**零出站**。 */
function clearPreview() {
  if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; }
  previewPct = null;
  pendingPct = null;
  endDrag();
  const host = hostEl();
  if (host) {
    host.classList.remove('ctx-ring-dragging');
    delete host.dataset.ringDragPreviewPct;
  }
  // 交还权威值：`updateHeaderModelInfo` 会按 state 里的 compactThreshold 重画把手与读数。
  if (typeof state.updateHeaderModelInfo === 'function') state.updateHeaderModelInfo();
  syncAria();
}

/** 暂留：提交后到权威帧到达前保持提交值（防「松手回跳」）+ 超时兜底。 */
function hold(ratio) {
  previewPct = Math.round(clampRatio(ratio) * 100);
  pendingPct = previewPct;
  paintPreview();
  if (holdTimer) clearTimeout(holdTimer);
  holdTimer = setTimeout(() => { holdTimer = null; clearPreview(); }, HOLD_MS);
}

// ── a11y（设计卡 §5.4 L8：面板的 aria 面整体迁到环）──────────────────────────
function syncAria() {
  const host = hostEl();
  if (!host) return;
  const shown = previewPct != null ? previewPct : authoritativePct();
  const lo = Math.round(Math.min(effectiveMinRatio(), MAX_RATIO) * 100);
  const hi = Math.round(MAX_RATIO * 100);
  host.setAttribute('role', 'slider');
  host.setAttribute('tabindex', '0');
  host.setAttribute('aria-label', t('ctxthresh.title'));
  host.setAttribute('aria-valuemin', String(lo));
  host.setAttribute('aria-valuemax', String(hi));
  if (shown != null) host.setAttribute('aria-valuenow', String(shown));
  else host.removeAttribute('aria-valuenow');
  host.setAttribute('aria-valuetext', shown != null ? shown + '%' : '');
  if (overLimit()) host.setAttribute('aria-disabled', 'true');
  else host.removeAttribute('aria-disabled');
}

// ── 取值 ────────────────────────────────────────────────────────────────
/** 指针位置 ⇒ 百分点（1% 网格 + 钳制）；退化（半径 < 1px）⇒ `null`。 */
function pctFromPointer(ev) {
  if (!center) return null;
  const dx = ev.clientX - center.cx;
  const dy = ev.clientY - center.cy;
  // 退化保护：指针压在环心 ⇒ 本次读数作废（`atan2(0, -0)` 会解出 180° = 50% 的假值）
  if (Math.hypot(dx, dy) < 1) return null;
  // 12 点 = 0%、顺时针（dy 取负）；**径向距离完全忽略** —— 拖到屏外也照算角度。
  let deg = Math.atan2(dx, -dy) * 180 / Math.PI;
  if (deg < 0) deg += 360;
  return clampPct(deg / DEG_PER_PCT);
}

// ── 提交 ────────────────────────────────────────────────────────────────
function pull() {
  const sid = primarySessionId();
  if (!sid) return;
  sendWs({ type: 'getCompactThreshold', sessionId: sid });
}

/** 提交一个百分点值（拖动松手 / 键盘两腿共用）。 */
function commit(pct) {
  const sid = primarySessionId();
  if (!sid) return;
  // 🔴 90% 硬顶：当前用量 ≥ 90% ⇒ 无可选区间 ⇒ 拒绝提交（保持默认值 / 零出站 / 零文案）
  if (overLimit()) return;
  const ratio = ratioFromPct(pct);
  sendWs({ type: 'setCompactThreshold', sessionId: sid, ratio });
  hold(ratio);
}

/** 恢复默认（双击环 / 环聚焦后 Delete）——出站 `ratio: null`。 */
function restoreDefault() {
  const sid = primarySessionId();
  if (!sid) return;
  // 🔴 90% 硬顶：复位腿入闸——超限档「恢复默认」会把阈值落到默认值（< 当前用量）
  // ⇒ 出站未受闸值 = 静默改值，故与提交腿同闸（保持默认值 / 零出站 / 零文案）。
  if (overLimit()) return;
  pendingPct = null;
  sendWs({ type: 'setCompactThreshold', sessionId: sid, ratio: null });
  // 暂留到**权威默认值**：`info.defaultRatio` 来自回显帧（非自造值），
  // 让把手立刻落到默认角度、无回跳。
  if (info && info.sessionId === sid && typeof info.defaultRatio === 'number' && isFinite(info.defaultRatio)) {
    hold(info.defaultRatio);
  } else {
    clearPreview();
  }
}

// ── 指针腿（P2：起手即写值 / 移动跟手 / 松开提交）─────────────────────────────
function onPointerDown(ev) {
  const sid = primarySessionId();
  if (!sid) return;
  if (overLimit()) return;                                     // 锁定档：不可拖 + 零出站 + 零文案
  if (ev.pointerType === 'mouse' && ev.button !== 0) return;    // 仅主键
  const host = hostEl();
  const svg = host && host.querySelector('.ctx-ring-svg');
  if (!host || !svg) return;
  const box = svg.getBoundingClientRect();
  if (box.width <= 0) return;
  // 环心屏幕坐标：起手取一次快照（拖动中不复算 —— 与古早实现同）
  center = { cx: box.left + box.width / 2, cy: box.top + box.height / 2 };
  dragging = true;
  pointerId = ev.pointerId;
  try { host.setPointerCapture(ev.pointerId); } catch { /* capture 不可用时 document 腿兜底 */ }
  host.classList.add('ctx-ring-dragging');
  // 🔴 起手即写值（无位移闸）：按下即按指针角度取值 —— 连点一下也写值。
  const pct = pctFromPointer(ev);
  if (pct != null) { previewPct = pct; paintPreview(); } else { syncAria(); }
  // 权威值缺失 / 换会话 ⇒ 顺手拉一次（面板删除后 `getCompactThreshold` 的拉取时机）
  if (!info || info.sessionId !== sid) pull();
}

function onPointerMove(ev) {
  if (!dragging) return;
  if (pointerId != null && ev.pointerId !== pointerId) return;
  const pct = pctFromPointer(ev);
  if (pct == null) return;                                     // 退化保护：忽略本次 move
  previewPct = pct;
  paintPreview();
}

function endDrag() {
  const host = hostEl();
  dragging = false;                                            // 🔴 先落标志，再 release
  if (pointerId != null) {
    try { host?.releasePointerCapture(pointerId); } catch { /* 已释放 */ }
    pointerId = null;
  }
  if (!host) return;
  host.classList.remove('ctx-ring-dragging');
  const svg = host.querySelector('.ctx-ring-svg');
  const g = svg && svg.querySelector('.ctx-ring-guide');
  if (g) g.remove();                                           // 导引线随拖动创建/销毁
}

function onPointerUp(ev) {
  if (!dragging) return;
  if (pointerId != null && ev.pointerId !== pointerId) return;
  const committed = previewPct;
  endDrag();
  if (committed != null) commit(committed);
  else clearPreview();
}

/** 取消腿（卡 §1.3）：`pointercancel` / 外力夺走 capture ⇒ 清预览 + 回弹 + **零出站**。 */
function cancelDrag() {
  endDrag();
  clearPreview();
}

function onPointerCancel() {
  if (!dragging) return;
  cancelDrag();
}

function onLostPointerCapture() {
  // 正常松开路径已由 `pointerup` 收尾（`dragging` 已 false ⇒ 此处 no-op）；
  // 仅当 capture 在拖动中被外力夺走时按取消腿处理。
  if (!dragging) return;
  cancelDrag();
}

// ── 键鼠腿（a11y）：←/→ ±1% · Home = 动态下限 · End = 90% · Delete = 恢复默认 ──
function onKeyDown(ev) {
  if (ev.key === 'Escape') {
    // 取消腿：拖动中或无暂留 ⇒ no-op；否则清预览 + 回弹 + 零出站。
    if (!dragging && previewPct == null) return;
    ev.preventDefault();
    cancelDrag();
    return;
  }
  const host = hostEl();
  if (!host || document.activeElement !== host) return;         // 键盘腿只在环聚焦时生效
  if (overLimit()) return;                                      // 锁定档：零出站 + 零文案
  if (ev.key === 'Delete') { ev.preventDefault(); restoreDefault(); return; }
  let pct = null;
  if (ev.key === 'ArrowRight' || ev.key === 'ArrowUp') {
    const base = stepBasePct();
    if (base == null) { pull(); return; }
    pct = clampPct(base + 1);
  } else if (ev.key === 'ArrowLeft' || ev.key === 'ArrowDown') {
    const base = stepBasePct();
    if (base == null) { pull(); return; }
    pct = clampPct(base - 1);
  } else if (ev.key === 'Home') {
    pct = clampPct(0);                                          // 钳到动态下限
  } else if (ev.key === 'End') {
    pct = Math.round(MAX_RATIO * 100);                          // 90% 硬顶
  } else {
    return;
  }
  if (pct == null) return;
  ev.preventDefault();
  commit(pct);
}

function onDblClick(ev) {
  ev.preventDefault();
  if (overLimit()) return;                                      // 锁定档：零出站
  restoreDefault();
}

// ── WS 回显（唯一数据源）────────────────────────────────────────────────
onMessage('compactThresholdInfo', (msg) => {
  const sid = primarySessionId();
  // 🔴 作用域第一道闸：只认主会话（root）的回显帧——非主会话帧一律忽略，
  // 绝不把非 root 会话的阈值显示进 Nebula 窗口的环。
  if (!sid || msg.sessionId !== sid) return;
  info = msg;
  // 权威帧到达 ⇒ 结束暂留、交还权威值（把手/读数回权威角度，`_index.json` 语义同源）。
  // 🔴 **拖动期守卫（F-1）**：手势进行中**不得**交还 —— `clearPreview()` 内含 `endDrag()`
  // ⇒ 起手补拉（`onPointerDown` 的 `pull()`：冷页首手势 / 换会话后首手势）触发的回帧会在
  // 拖动中把手势自己掐死（`dragging = false`、预览与拖动类被清、`pointerup` 直接 no-op ⇒
  // 松手零出站），与作者 P2「起手即写值」直接冲突。权威值照旧写入 `info` 与
  // `state.sessionModelInfo[sid]`（状态不丢），交还推迟到手势结束（`onPointerUp` 的
  // `commit()`/`clearPreview()` 或取消腿）。`main.js` 的 S1 钩子在拖动期优先取
  // `data-ring-drag-preview-pct` ⇒ 随后的 `updateHeaderModelInfo()` 不会抢回把手/读数。
  if (!dragging) clearPreview();
  if (typeof state.updateHeaderModelInfo === 'function') {
    if (!state.sessionModelInfo[sid]) return;
    // 「判定面 ≡ 上报面」：环的阈值线取生效比例（有覆盖 = 覆盖值，
    // 无覆盖 = 现值比例）——与后端 Done/UsageUpdate 的 compactThreshold 同源。
    state.sessionModelInfo[sid].compactThreshold = msg.effectiveRatio;
    state.updateHeaderModelInfo();
  }
});

/** 用量帧 ⇒ 动态下限 / 90% 锁定态随之变化 ⇒ a11y 面（aria-valuemin / aria-disabled /
  * aria-valuenow）必须同步。**推到下一个宏任务**：本模块的 `usageUpdate` 订阅先于
  * `main.js`（模块导入序）注册，同一帧派发时它读到的 `state.sessionModelInfo` 还是旧值。
  * 此处只读 state、不改任何阈值语义。 */
function scheduleAriaSync() { setTimeout(syncAria, 0); }
onMessage('usageUpdate', scheduleAriaSync);
onMessage('done', scheduleAriaSync);

onMessage('compactThresholdError', (msg) => {
  const sid = primarySessionId();
  if (!sid || msg.sessionId !== sid) return;
  // 🔴 终裁文案面：拦截腿**零文案**（不再渲染任何提示句）。
  // 拒绝 ⇒ 重新拉权威值，环回到真实状态（不留假的乐观值）。
  pull();
});

// ── 入口绑定 ────────────────────────────────────────────────────────────
function init() {
  if (typeof document === 'undefined') return;
  if (!document.getElementById('ctxring-css')) {
    document.head.insertAdjacentHTML('beforeend', CSS);
  }
  const host = hostEl();
  if (!host) return;
  if (bound) return;
  bound = true;
  // 环的 innerHTML 会被 `main.js` 重绘，但宿主 `#header-model-info` 持久
  // ⇒ 监听其冒泡，零重绑、零观察器。
  host.addEventListener('pointerdown', onPointerDown);
  host.addEventListener('pointermove', onPointerMove);
  host.addEventListener('pointerup', onPointerUp);
  host.addEventListener('pointercancel', onPointerCancel);
  host.addEventListener('lostpointercapture', onLostPointerCapture);
  host.addEventListener('dblclick', onDblClick);
  document.addEventListener('keydown', onKeyDown);
  syncAria();
  pull();
}

/** 供 e2e / 调试：显式刷新（幂等）。 */
export function initCtxThresh() { init(); }

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
