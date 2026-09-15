// ctxthresh.js — Nebula 窗口 Header「上下文压缩阈值可调」面板（ctxthresh 批，
// 2026-09-15 方案 A；作者卡答逐字「按方案A实施」＋「上限90%，下限不得小于当前
// 上下文用量而且大于15%。（上一问的小面板设计要参照 git 历史中这个设计，就是
// hover 后拖动小圆环的那个小滑杆）」）。
//
// ── 交互形态来源（考古恢复，逐字可复现；证据见
//    .nebflow/evidence/20260915_ctxthresh/impl/ev_archaeo_old_form.txt）───────
// 旧设计（2026-08-10 `0999b8180` 移除前）在 `main.js` 有两条拖拽腿：
//   `setupRingThresholdDrag`（环上按角度拖阈值刻线）与 `setupBarThresholdDrag`
//   （横向条上拖阈值刻线），落值走 `commitThreshold`（mouseup 提交 + 300ms 防抖）。
//   其视觉语言 = ① 可拖元素 `cursor: ew-resize`；② 拖动时刻线高亮放大
//   （`background: rgba(200,80,80,0.8)`，2px×12px → 2px×16px）；③ 拖动中在其上方
//   浮出小号等宽 `%` 标签（`.ctx-bar-threshold-label`，opacity 0 → 1，9px 等宽）。
// 本模块**照抄这三条视觉/交互语言**，把「小滑杆」搬进面板（旧形态的 28px 环命中区
// 过小、角度几何误触高，故入口改为「点环开面板」，拖拽仍在面板内保留）。
// 🔴 不照搬旧设计的**配置面**（旧版写 `nebflow.json` 的 `compact.bufferRatio`）：
// 本批走会话级 override（设计 §5），旧键三枚不动（§9-O5）。
//
// ── 作用域（口径①/③）─────────────────────────────────────────────────────
// 面板只服务 **主窗口的 root 会话**（`#header-model-info` 只存在于主窗口：
// main.js `updateHeaderModelInfo` 注释逐字「popups pass headerModelInfo: null」）。
// 服务端另有作用域闸（`WebSocketRoutes.isRootScopeSession`：`AgentKind.Root` 才放行），
// 本侧为第一道：**只接受主会话 id 的回显帧**（非主会话帧一律忽略 → U6）。
//
// ── 值域（作者卡答逐字；与后端 `CompactThresholdOverride` 同源）─────────────
//   `15% < r ≤ 90%`，下限另有**动态钳制**：`r ≥ 当前上下文用量比例`
//   （低于即「设完立刻触发压缩」，故禁选/钳回）。后端静态值域是硬闸，本侧动态
//   下限是 UI 闸（滑杆 min + 输入钳回）。
//   🔴 后端 `compactThresholdInfo.minRatio` 是**静态**下限（0.16）；本侧再用
//   当前用量抬高它（`effectiveMinRatio()`）。
//   🔴 **90% 硬顶（root 2026-09-15 逐字）**：阈值 ≤ 90% 是**硬边界**；**当前用量 ≥ 90% 时
//   钳制区间为空**（`[max(15%, 用量), 90%]` 无解）⇒ 面板进**锁定态**：滑杆**真禁用**
//   （不可拖 + 不可提交，含复位腿）、**保持默认值**（不静默改值、不临时放开 >90%）、
//   **零出站**（超限态本身就是要立即压缩的信号）、**零文案**。
//   🔴 常量须与 `src/main/scala/nebflow/agent/protocol.scala` 的
//   `object CompactThresholdOverride` 保持同步（MinRatio / MaxRatio / StepRatio）。
//
// ── 文案面（作者终裁 2026-09-15 逐字，取代本批此前「超限态文案在场」口径）──────────
//   终裁逐字：「**不显示任何文字，只在拉动滑杆的时候，显示%比**」＋显式确认「**不显示任何字**」。
//   ⇒ ① **零常驻文字**：面板标题 / 作用域描述句 / 区间提示句 / 默认值注记**一律不渲染**
//        （元素留作空壳，无任何 textContent）；
//      ② **零常驻读数**：token 数 / 比例 / 数字框显示值**仅在拖动滑杆时**出现（`dragRatio != null`）；
//      ③ 超限态**只以滑杆锁死 / 置灰**表达：**不新增任何文字**（含两条拦截腿的提示句 ——
//         「静默锁死」即终形）。

import { onMessage, sendWs } from './ws.js';
import { t } from './i18n.js';
import state from './state.js';
import { chatViews } from './chatView.js';

// 🔴 与后端 CompactThresholdOverride 同步的常量（禁只改一侧）
const MIN_RATIO = 0.15;   // 静态下限（开区间：r 必须严格大于它）
const MAX_RATIO = 0.90;   // 静态上限（闭区间）
const STEP_RATIO = 0.01;  // 1 个百分点

const PANEL_ID = 'ctxthresh-panel';
const TRACK_ID = 'ctxthresh-track';
const THUMB_ID = 'ctxthresh-thumb';
const LABEL_ID = 'ctxthresh-thumb-label';
const FILL_ID = 'ctxthresh-fill';
const ABS_ID = 'ctxthresh-abs';
const PCT_ID = 'ctxthresh-pct';
const NUM_ID = 'ctxthresh-number';
const HINT_ID = 'ctxthresh-hint';
const SCOPE_ID = 'ctxthresh-scope';

const CSS = `
<style id="ctxthresh-css">
/* ── 入口：Header 上下文环变为可点（旧设计遗留的拖拽光标语言 → 点击语言）── */
#header-model-info .ctx-ring-wrap { cursor: pointer; }
#header-model-info .ctx-ring-wrap:hover .ctx-ring-threshold {
  stroke: rgba(200,80,80,0.95); stroke-width: 2;
}

/* ── 面板容器（sapphire glass；#header 的**兄弟节点**——base.css:243-256 硬约束：
   #header 自带 backdrop-filter 会形成 backdrop root，浮层放其内部则毛玻璃失效）── */
#${PANEL_ID} {
  position: absolute;
  top: 56px;
  left: 16px;
  width: 300px;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border: 1px solid var(--glass-border);
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.05), 0 8px 36px rgba(0,0,0,0.06);
  z-index: 300;
  visibility: hidden;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s, visibility 0.15s;
  padding: 10px 12px 12px;
  box-sizing: border-box;
}
#${PANEL_ID}.open { visibility: visible; opacity: 1; pointer-events: auto; }
.ctxthresh-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 6px;
}
.ctxthresh-title {
  font-size: 12px; font-weight: 500; color: var(--color-frame-text);
}
.ctxthresh-close-btn {
  background: none; border: none; cursor: pointer; padding: 0;
  width: 20px; height: 20px; line-height: 1; border-radius: 4px;
  color: var(--color-frame-text-muted); font-size: 14px;
}
.ctxthresh-close-btn:hover { background: var(--glass-control-bg-hover); }
.ctxthresh-scope {
  font-size: 10px; color: var(--color-frame-text-muted);
  margin-bottom: 8px; line-height: 1.4;
}
.ctxthresh-readout {
  display: flex; align-items: baseline; gap: 6px; margin-bottom: 8px;
  font-family: ui-monospace, SFMono-Regular, monospace;
}
.ctxthresh-abs { font-size: 16px; font-weight: 600; color: var(--color-frame-text); }
.ctxthresh-pct { font-size: 11px; color: var(--color-frame-text-muted); }
.ctxthresh-default-note {
  font-size: 10px; color: var(--color-frame-text-muted); margin-left: auto;
}

/* ── 小滑杆（旧形态逐字复刻：ew-resize 可拖 + 拖动高亮 + 拖动中浮出 % 标签）── */
.ctxthresh-track {
  position: relative; height: 12px; margin: 10px 0 4px;
  cursor: ew-resize; user-select: none;
}
.ctxthresh-rail {
  position: absolute; top: 5px; left: 0; right: 0; height: 2px;
  background: rgba(128,128,128,0.25); border-radius: 1px;
}
.ctxthresh-fill {
  position: absolute; top: 5px; left: 0; height: 2px;
  background: rgba(128,128,128,0.5); border-radius: 1px;
}
.ctxthresh-thumb {
  position: absolute; top: 0; width: 2px; height: 12px; margin-left: -1px;
  background: rgba(128,128,128,0.5); border-radius: 1px;
  transition: background 0.15s ease, height 0.15s ease, top 0.15s ease;
}
/* 旧 .ctx-bar-threshold:hover / .dragging 逐字等价 */
.ctxthresh-track:hover .ctxthresh-thumb,
.ctxthresh-track.dragging .ctxthresh-thumb {
  background: rgba(200,80,80,0.8); height: 16px; top: -2px;
}
/* 旧 .ctx-bar-threshold-label / .visible 逐字等价 */
.ctxthresh-thumb-label {
  position: absolute; top: -16px; font-size: 9px;
  font-family: ui-monospace, SFMono-Regular, monospace;
  color: rgba(200,80,80,0.9);
  transform: translateX(-50%); white-space: nowrap;
  opacity: 0; transition: opacity 0.12s ease; pointer-events: none;
}
.ctxthresh-thumb-label.visible { opacity: 1; }
/* 🔴 90% 硬顶：超限档（usage ≥ 90%）滑杆无可选区间 ⇒ 真禁用（不可拖 + 不可提交） */
.ctxthresh-track.disabled, .ctxthresh-track.disabled .ctxthresh-thumb { pointer-events: none; cursor: default; }
.ctxthresh-btn:disabled, .ctxthresh-number:disabled { opacity: 0.5; cursor: not-allowed; }

.ctxthresh-row {
  display: flex; align-items: center; gap: 6px; margin-top: 8px;
}
.ctxthresh-number {
  width: 68px; padding: 3px 6px; font-size: 11px;
  border-radius: 6px; border: 1px solid var(--color-border);
  background: var(--glass-control-bg); color: var(--color-frame-text);
  font-family: ui-monospace, SFMono-Regular, monospace;
}
.ctxthresh-actions { margin-left: auto; display: flex; gap: 6px; }
.ctxthresh-btn {
  font-size: 11px; padding: 3px 9px; border-radius: 6px; cursor: pointer;
  border: 1px solid var(--color-border); background: var(--glass-control-bg);
  color: var(--color-frame-text);
}
.ctxthresh-btn:hover { background: var(--glass-control-bg-hover); }
.ctxthresh-btn-primary {
  border-color: transparent; background: var(--color-primary); color: #fff;
}
.ctxthresh-hint {
  font-size: 10px; color: var(--color-frame-text-muted);
  margin-top: 6px; line-height: 1.4;
}
.ctxthresh-msg { font-size: 10px; margin-top: 6px; line-height: 1.4; }
.ctxthresh-msg.error { color: var(--color-error); }
.ctxthresh-msg.ok { color: var(--color-success); }
</style>`;

// ── 状态 ────────────────────────────────────────────────────────────────
/** 服务端最近一次回显的权威值（唯一数据源；UI 不回算、不自造）。 */
let info = null;
/** 拖动中的临时比例（未提交）。 */
let dragRatio = null;
let open = false;

function primarySessionId() {
  return chatViews.primary?.sessionId || state.activeSessionId || null;
}

function fmtTokens(n) {
  if (n == null || !isFinite(n)) return '';
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return Math.round(n / 1000) + 'k';
  return String(n);
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

/** 🔴 90% 硬顶（root 2026-09-15 逐字）：**当前用量 ≥ 90% ⇒ 滑杆无可选区间**
  * （`[max(15%, 用量), 90%]` 空集）⇒ 面板进**锁定态**（真禁用 + 保持默认值 + 零出站 + 零文案）。 */
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

// ── DOM ─────────────────────────────────────────────────────────────────
function ensurePanel() {
  if (document.getElementById(PANEL_ID)) return document.getElementById(PANEL_ID);
  const header = document.getElementById('header');
  if (!header) return null;
  // 🔴 必须是 #header 的**兄弟节点**（同在 #main 内）——base.css:243-256 的
  // backdrop-filter 硬约束（同 .header-dropdown-menu / #reminder-panel / #daemon-panel）。
  // 🔴 终裁文案面：标题 / 作用域句 / 读数位（abs·pct）/ 默认值注记 / 提示句（`${HINT_ID}`）
  //    一律**空壳**（无 textContent），读数只在拖动中回填（见 `render()`）。
  header.insertAdjacentHTML('afterend', `
    <div id="${PANEL_ID}" role="dialog" aria-label="${t('ctxthresh.title')}">
      <div class="ctxthresh-header">
        <span class="ctxthresh-title"></span>
        <button class="ctxthresh-close-btn" id="ctxthresh-close-btn" title="${t('ctxthresh.close')}">×</button>
      </div>
      <div class="ctxthresh-scope" id="${SCOPE_ID}"></div>
      <div class="ctxthresh-readout">
        <span class="ctxthresh-abs" id="${ABS_ID}"></span>
        <span class="ctxthresh-pct" id="${PCT_ID}"></span>
        <span class="ctxthresh-default-note" id="ctxthresh-default-note"></span>
      </div>
      <div class="ctxthresh-track" id="${TRACK_ID}">
        <div class="ctxthresh-rail"></div>
        <div class="ctxthresh-fill" id="${FILL_ID}"></div>
        <div class="ctxthresh-thumb" id="${THUMB_ID}"></div>
        <div class="ctxthresh-thumb-label" id="${LABEL_ID}"></div>
      </div>
      <div class="ctxthresh-row">
        <input class="ctxthresh-number" id="${NUM_ID}" type="number" step="1" min="16" max="90"
               inputmode="numeric" autocomplete="off" aria-label="${t('ctxthresh.title')}">
        <span class="ctxthresh-pct">%</span>
        <div class="ctxthresh-actions">
          <button class="ctxthresh-btn ctxthresh-btn-primary" id="ctxthresh-save-btn">${t('ctxthresh.save')}</button>
          <button class="ctxthresh-btn" id="ctxthresh-reset-btn">${t('ctxthresh.reset')}</button>
        </div>
      </div>
      <div class="ctxthresh-hint" id="${HINT_ID}"></div>
      <div class="ctxthresh-msg" id="ctxthresh-msg"></div>
    </div>
  `);
  const panel = document.getElementById(PANEL_ID);
  bindPanelEvents(panel);
  return panel;
}

function setMsg(text, kind) {
  const el = document.getElementById('ctxthresh-msg');
  if (!el) return;
  el.textContent = text || '';
  el.className = 'ctxthresh-msg' + (kind ? ' ' + kind : '');
}

/** 渲染（值来源 = 服务端回显 `info` + 拖动中的 `dragRatio`）。
  * 🔴 终裁（作者 2026-09-15 逐字）：「不显示任何文字，只在拉动滑杆的时候，显示 %比」
  * ⇒ **零常驻读数**：token 数 / 比例 / 数字框值只在**拖动中**（`dragRatio != null`）回填，
  * 静止态一律清空；**零常驻文字**：提示句 / 默认值注记不再渲染。 */
function render() {
  const panel = document.getElementById(PANEL_ID);
  if (!panel) return;
  const window = info?.contextWindow || state.sessionModelInfo?.[primarySessionId()]?.contextWindow || 0;
  const dragging = dragRatio != null;   // 🔴 终裁：读数仅在拖动中出现
  const ratio = dragging ? dragRatio : (info?.effectiveRatio ?? 0);
  const tokens = window ? Math.round(window * ratio) : null;

  const abs = document.getElementById(ABS_ID);
  if (abs) abs.textContent = dragging && tokens != null ? fmtTokens(tokens) : '';
  const pct = document.getElementById(PCT_ID);
  if (pct) pct.textContent = dragging ? (ratio * 100).toFixed(1) + '%' : '';

  const lo = effectiveMinRatio();
  // 🔴 90% 硬顶：超限档区间为空（分母 MAX-lo ≤ 0）⇒ 不给倒挂位置，thumb 固定在硬顶端
  const over = overLimit();
  const pos = ((ratio - lo) / (MAX_RATIO - lo)) * 100;
  const clampedPos = over ? 100 : Math.max(0, Math.min(100, pos));
  const fill = document.getElementById(FILL_ID);
  if (fill) fill.style.width = clampedPos + '%';
  const thumb = document.getElementById(THUMB_ID);
  if (thumb) {
    thumb.style.left = clampedPos + '%';
    // 超限档：thumb 真禁用（pointer-events:none + aria-disabled），不是仅拦提交
    thumb.style.pointerEvents = over ? 'none' : '';
    thumb.setAttribute('aria-disabled', over ? 'true' : 'false');
  }
  const label = document.getElementById(LABEL_ID);
  if (label) {
    label.style.left = clampedPos + '%';
    // 🔴 终裁：拖动读数只在拖动中出现（静止态连标签内容也清空 ⇒ 零常驻读数）
    label.textContent = dragging ? Math.round(ratio * 100) + '%' : '';
  }
  const num = /** @type {HTMLInputElement|null} */ (document.getElementById(NUM_ID));
  if (num) {
    // 动态下限（作者卡答）：滑杆/数字框的 min 抬到「当前用量」或 16% 的较大者。
    // 超限档下限越过 90% ⇒ 无可选区间：min 与 max 同取 90（不出倒挂区间 min>max）。
    num.min = String(Math.ceil((over ? MAX_RATIO : lo) * 100));
    num.max = String(Math.round(MAX_RATIO * 100));
    num.disabled = over;   // 超限档：数字框真禁用（无可选区间）
    // 用户未在编辑时才回写显示值（防拖动时打断输入）。🔴 终裁：静止态清空 ⇒ 零常驻读数。
    if (document.activeElement !== num) num.value = dragging ? String(Math.round(ratio * 100)) : '';
  }
  // 🔴 90% 硬顶：超限档 ⇒ 面板锁定态——滑杆/数字框/保存/复位全部锁定（真禁用；🔴 零文案）
  const track = document.getElementById(TRACK_ID);
  if (track) {
    track.classList.toggle('disabled', over);
    track.setAttribute('aria-disabled', over ? 'true' : 'false');
    track.style.pointerEvents = over ? 'none' : '';
  }
  const saveBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('ctxthresh-save-btn'));
  const resetBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('ctxthresh-reset-btn'));
  if (saveBtn) saveBtn.disabled = over;
  if (resetBtn) resetBtn.disabled = over;
  // 🔴 终裁文案面：提示句（`${HINT_ID}` / `.ctxthresh-hint`）**不再渲染** —— 超限态只以
  // 锁死/置灰表达、非超限档也不留区间句（原 `over` 三目分支 + `over-limit` 样式钩子一并撤除）。
}

function ratioFromEvent(ev, track) {
  const rect = track.getBoundingClientRect();
  if (rect.width <= 0) return effectiveMinRatio();
  const lo = effectiveMinRatio();
  const frac = (ev.clientX - rect.left) / rect.width;
  const raw = lo + frac * (MAX_RATIO - lo);
  return Math.round(clampRatio(raw) / STEP_RATIO) * STEP_RATIO;
}

function bindPanelEvents(panel) {
  panel.querySelector('.ctxthresh-close-btn')?.addEventListener('click', () => setOpen(false));

  const track = document.getElementById(TRACK_ID);
  if (track) {
    // 旧形态逐字：mousedown 起拖 → document 上 mousemove → mouseup 提交。
    track.addEventListener('mousedown', (e) => {
      if (overLimit()) return;   // 🔴 超限档真禁用：不可拖动（程序化 mousedown 同样拦）
      e.preventDefault();
      track.classList.add('dragging');
      const label = document.getElementById(LABEL_ID);
      if (label) label.classList.add('visible');
      dragRatio = ratioFromEvent(e, track);
      render();
      const onMove = (ev) => { dragRatio = ratioFromEvent(ev, track); render(); };
      const onUp = () => {
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', onUp);
        track.classList.remove('dragging');
        if (label) label.classList.remove('visible');
        const committed = dragRatio;
        dragRatio = null;
        if (committed != null) submitRatio(committed);
        render();
      };
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
    });
  }

  const num = /** @type {HTMLInputElement|null} */ (document.getElementById(NUM_ID));
  const save = document.getElementById('ctxthresh-save-btn');
  const commitNumber = () => {
    if (!num) return;
    // 🔴 终裁后数字框静止态为空值 ⇒ 空输入**不得**当作 0%（否则会被钳成下限 = 静默改值）
    const raw = num.value.trim();
    const pctVal = Number(raw);
    if (raw === '' || !isFinite(pctVal)) { render(); return; }
    submitRatio(clampRatio(pctVal / 100));
  };
  save?.addEventListener('click', commitNumber);
  num?.addEventListener('change', commitNumber);

  document.getElementById('ctxthresh-reset-btn')?.addEventListener('click', () => {
    // 🔴 90% 硬顶：复位腿入闸——超限档「恢复默认」会把阈值落到默认值（25.6% < 当前用量）
    // ⇒ 出站未受闸值 = 静默改值，故与提交腿同闸（保持默认值 / 零出站）。
    // 🔴 终裁：拦截腿**零文案**（「静默锁死」即终形，不再给提示句）。
    if (overLimit()) return;
    setMsg('');
    sendWs({ type: 'setCompactThreshold', sessionId: primarySessionId(), ratio: null });
  });
}

function submitRatio(ratio) {
  const sid = primarySessionId();
  if (!sid) return;
  // 🔴 90% 硬顶（root 2026-09-15 逐字）：当前用量 ≥ 90% ⇒ 滑杆无可选区间 ⇒ 拒绝提交
  // （保持默认值 / 不静默改值 / 不临时放开 >90%）；不改成钳到「越过 90% 或低于当前用量」的值。
  // 🔴 终裁：拦截**零文案**（不再写提示句），只回拉权威值让面板回到真实状态。
  if (overLimit()) { sendWs({ type: 'getCompactThreshold', sessionId: sid }); return; }
  setMsg('');
  // 🔴 90% 硬顶：出站值一律过 clampRatio（lo 已钳到 90%）⇒ 任何腿都不可能出站 > 90%
  sendWs({ type: 'setCompactThreshold', sessionId: sid, ratio: Math.round(clampRatio(ratio) * 100) / 100 });
}

function setOpen(next) {
  const panel = ensurePanel();
  if (!panel) return;
  open = next;
  panel.classList.toggle('open', open);
  if (open) {
    setMsg('');
    const sid = primarySessionId();
    if (sid) sendWs({ type: 'getCompactThreshold', sessionId: sid });
    render();
  } else {
    dragRatio = null;
  }
}

function toggle() { setOpen(!open); }

// ── WS 回显（唯一数据源）────────────────────────────────────────────────
onMessage('compactThresholdInfo', (msg) => {
  const sid = primarySessionId();
  // 🔴 作用域第一道闸（U6）：只认主会话（root）的回显帧——非主会话帧一律忽略，
  // 绝不把非 root 会话的阈值显示进 Nebula 窗口的环/面板。
  if (!sid || msg.sessionId !== sid) return;
  info = msg;
  if (typeof state.updateHeaderModelInfo === 'function') {
    if (!state.sessionModelInfo[sid]) return;
    // 「判定面 ≡ 上报面」：环的阈值线/文案取生效比例（有覆盖 = 覆盖值，
    // 无覆盖 = 现值比例）——与后端 Done/UsageUpdate 的 compactThreshold 同源。
    state.sessionModelInfo[sid].compactThreshold = msg.effectiveRatio;
    state.updateHeaderModelInfo();
  }
  if (open) render();
});

onMessage('compactThresholdError', (msg) => {
  const sid = primarySessionId();
  if (!sid || msg.sessionId !== sid) return;
  setMsg(msg.message || t('ctxthresh.failed'), 'error');
  // 拒绝 ⇒ 重新拉权威值，面板回到真实状态（不留假的乐观值）
  sendWs({ type: 'getCompactThreshold', sessionId: sid });
});

// ── 入口绑定 + 关闭行为 ─────────────────────────────────────────────────
function init() {
  if (typeof document === 'undefined') return;
  if (!document.getElementById('ctxthresh-css')) {
    document.head.insertAdjacentHTML('beforeend', CSS);
  }
  const panel = ensurePanel();
  if (!panel) return;

  // 入口 = 点既有上下文环（#header-model-info）。🔴 **零新增 header 叶子控件**
  // （header-button-collision 教训）：环本身是既有元素，CHAIN（main.js:3484-3495）
  // 无需增项。环的 innerHTML 会被 main.js 重绘，但宿主 span 持久 ⇒ 监听其冒泡。
  document.getElementById('header-model-info')?.addEventListener('click', (e) => {
    e.stopPropagation();
    toggle();
  });
  // 关闭行为：**按下**面板/环以外处即关（用 mousedown 而非 click —— 拖动滑杆时
  // 松手点可能落在面板外，click 会把「拖到最左」误判成「点外面」而中途收面板）。
  document.addEventListener('mousedown', (e) => {
    if (!open) return;
    const panelEl = document.getElementById(PANEL_ID);
    if (panelEl && e.target instanceof Node && panelEl.contains(e.target)) return;
    const ringEl = document.getElementById('header-model-info');
    if (ringEl && e.target instanceof Node && ringEl.contains(e.target)) return;
    setOpen(false);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && open) setOpen(false);
  });
  // 会话切换无需专门监听：面板每次打开都重新 `getCompactThreshold`（以当前主会话
  // id 为准），且回显帧的作用域闸按当前主会话 id 过滤 ⇒ 换会话后旧值自然作废。
}

/** 供 e2e / 调试：显式刷新（幂等）。 */
export function initCtxThresh() { init(); }

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
