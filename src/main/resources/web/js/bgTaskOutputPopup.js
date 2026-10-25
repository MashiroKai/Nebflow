// bgTaskOutputPopup.js — 后台任务输出详情卡（2026-09-09 作者需求：
// 「后台任务点进去点击具体任务能不能让可以查看任务的具体输出」）。
//
// 后台任务面板（main.js renderBgDropdown）任务行的 click target 打开本卡片：
// 毛玻璃详情卡实时查看任务的 stdout/stderr 合并输出。
// - 设计语言复用 flow-agent-overlay / flow-agent-modal 族（同款 overlay 不暗化
//   不模糊、面板自身 --glass-bg + blur、顶缘蓝宝石折射线、footer 折射线）；
//   状态点复用 chat.css 的 .bg-task-status.bg-status-*；复制按钮走 glass-control
//   绿玻璃语言（#send-btn 参数）。零新增设计语言。
// - 数据面：REST GET /api/bg-tasks/:taskId/output?offset=N（后端
//   BgTaskOutputStore，字节游标增量），运行中 ~2s 轮询；终态（completed/failed/
//   cancelled）后停轮询——后端留存区（最近 20 条）+ 落盘（bg-task-logs/，
//   2026-10-25 输出查看补全批）保证可回看。
// - kind=remote（远端任务）——2026-10-25 输出查看补全批重做（作者令「远端任务
//   看不到」）：不再降级占位。运行中轮询 /api/bg-tasks/:id/remote-output（引擎
//   按 outputPath 经既有设备通道拉对端日志尾窗，~3s），终态（404）后单拍 /output
//   取灌账留存；无 outputPath / 设备不可达 = 「输出不可用 + 原因」，不再用
//   「暂不支持」搪塞。
// - 滚动跟随：输出增长时若用户在底部附近则自动滚到底；用户上滚即暂停跟随
//   （回看历史），重新滚回底部恢复跟随。
// - XSS 纪律：动态文本一律 textContent（骨架 innerHTML 仅静态结构）。

import { t } from './i18n.js';
import { key } from './branding.js';
import { statusIcon, escapeHtml } from './utils.js';

// ── State（单例卡：同一时刻最多一张详情卡）─────────────────
let overlayEl = null;
// cur.failCount：连续失败计数（网络/HTTP≥400/超时）——超预算终局，禁无限静默等待
// （2026-09-10 作者实测「完成的后台任务会卡在那」修复：旧版 resp!ok 与网络错误
//  都静默 return 无上限重试、完成态行也进 2s 轮询死等——三处全是无终局路径）。
let cur = null; // { taskId, offset, text, pollTimer, closed, gotData, restoreFocus, ... }
let escHandler = null;

// 终态集合（completed/failed/cancelled）——终态行走单次读取模式（不进轮询）。
const isTerminalStatus = (s) => s === 'completed' || s === 'failed' || s === 'cancelled';
// 轮询模式连续失败预算：5 次（~10s 无一成功即终局报错，不无限静默重试）。
const MAX_POLL_FAILS = 5;
// 单次读取模式（终态行）失败预算：3 拍（首拍+2 重试）。
const MAX_ONESHOT_ATTEMPTS = 3;
// 每次 fetch 的挂死兜底：8s 无响应按失败计（AbortController）。
const FETCH_TIMEOUT_MS = 8000;
// 轮询模式总预算：10min 无终态 → 停轮询并明示（persistent/僵死任务防线）。
const MAX_POLL_TOTAL_MS = 10 * 60 * 1000;
// 远端模式（2026-10-25 输出查看补全批）：轮询间隔 3s（设备通道拉尾的礼貌节拍）。
const REMOTE_POLL_MS = 3000;

function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

function fmtBytes(n) {
  if (!Number.isFinite(n) || n < 0) n = 0;
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}
function fmtLines(n) {
  const x = Number(n);
  return Number.isFinite(x) ? x.toLocaleString() : '—';
}

// ── CSS（flow-agent-overlay 族同款；模块加载时注入一次）─────
const POPUP_CSS = `<style id="bgt-output-popup-css">
/* Overlay：fixed 全屏透明点击层——无暗化无模糊（视觉铁律：弹窗禁背景遮罩），
   面板自身毛玻璃。同 .flow-agent-overlay.fullscreen 定位。 */
.bgt-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--overlay-bg);
  z-index: 1000;
  animation: bgt-fade-in 0.2s ease;
}
@keyframes bgt-fade-in { from { opacity: 0; } to { opacity: 1; } }

/* Modal：--glass-bg + blur + 蓝宝石玻璃边（同 .flow-agent-modal 材质与阴影）。 */
.bgt-modal {
  position: relative;
  width: min(720px, calc(100vw - 48px));
  height: min(560px, calc(100vh - 96px));
  display: flex; flex-direction: column;
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border: 1px solid var(--glass-border);
  border-radius: 20px;
  overflow: hidden;
  box-shadow:
    inset 0 1px 0 0 rgba(255,255,255,0.25),
    0px 2px 8px rgba(0,0,0,0.04),
    0px 8px 32px rgba(0,0,0,0.10);
}
@media (prefers-color-scheme: dark) {
  .bgt-modal {
    box-shadow:
      inset 0 1px 0 0 rgba(255,255,255,0.04),
      0px 2px 8px rgba(0,0,0,0.20),
      0px 8px 32px rgba(0,0,0,0.35);
  }
}

/* Header：同 .flow-agent-header（顶缘折射线 + 底部 hairline）。 */
.bgt-header {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--glass-border);
  flex-shrink: 0;
  position: relative;
  user-select: none;
}
.bgt-header::before {
  content: '';
  position: absolute;
  top: 0; left: 10%; right: 10%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 10%,
    var(--sapphire-refraction, rgba(99,179,237,0.25)) 50%,
    transparent 90%);
  pointer-events: none;
  z-index: 1;
}
.bgt-title-wrap { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 1px; }
.bgt-title {
  font: 600 13px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 100%;
}
.bgt-subtitle {
  font: 400 11px -apple-system, sans-serif;
  color: var(--color-text-muted);
  font-family: ui-monospace, SFMono-Regular, monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 100%;
}
.bgt-state-chip {
  display: inline-flex; align-items: center; gap: 5px;
  flex-shrink: 0;
  font: 400 11px -apple-system, sans-serif;
  color: var(--color-frame-text-muted);
}
/* Unified panel spec §3.1：状态词 400 让位主名；终态分色同行面板
   （done/cancelled/cancelling → dim，failed → error，chat.css:432-435 同源）。
   ui-status-redesign（盘点 #15，S2）：failed 词转中性字，红由词前 circle-x
   图标（.st-ic token）承载。 */
.bgt-state-label--done { color: var(--color-frame-text-dim); }
.bgt-state-label--failed { color: var(--status-word); }
.bgt-state-chip .st-ic { width: 11px; height: 11px; }
.bgt-close {
  cursor: pointer; font-size: 16px; line-height: 1;
  opacity: 0.7; transition: opacity 0.15s;
  padding: 2px 4px; color: var(--color-text);
  border-radius: 4px;
  flex-shrink: 0;
  background: none; border: none;
}
.bgt-close:hover { opacity: 1; background: var(--color-frame-hover); }
.bgt-close:focus { outline: none; }
/* 焦点环约定（projectPanel.css/nav.css 同款）：只认键盘路径的 :focus-visible 细环，
   程序化 .focus()（打开卡片时的焦点管理）不出环。 */
.bgt-close:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--glass-control-border);
}

/* 失败原因行（failed 任务终态回看）：ui-status-redesign（S9 同族）——正文转
   中性字，红由左侧 2px 描边（图案）＋行首 circle-alert 图标承载。 */
.bgt-error {
  flex-shrink: 0;
  display: flex; align-items: flex-start; gap: 6px;
  padding: 6px 16px;
  font: 400 11px -apple-system, sans-serif;
  color: var(--status-word);
  border-bottom: 1px solid var(--glass-border);
  border-left: 2px solid var(--status-error-icon);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.bgt-error .st-ic { width: 12px; height: 12px; margin-top: 1px; flex-shrink: 0; }

/* 输出区（2026-09-10 作者指令：终端化内芯）：后台任务输出是终端任务——
   内芯 = 跟随主题的深/浅终端底（light #f5f5f5 / dark #111419，与代码块
   .bubble.ai pre 同源值）+ 等宽栈 + pre-wrap 原始换行；外层 .bgt-modal
   仍守毛玻璃、overlay 禁暗化红线不变（面板外壳毛玻璃统一 + 内芯终端化）。
   复制：.bgt-body 复用代码块 .code-block-wrap 容器 class——.code-copy-btn
   的 hover 浮现（chat.css）与玻璃主题（sapphire.css Pattern B / dark 档）
   零复制生效；padding-top 抬高给右上角浮层按钮留位（28px = 按钮高+余量）。 */
.bgt-body { flex: 1; min-height: 0; display: flex; position: relative; padding: 0 12px 10px; }
/* 复制按钮落进输出框内（2026-09-12 作者令：「复制按钮需要在输出框内」）。
   公共定位（chat.css:1043-1046 .code-copy-btn）为 absolute top:4px/right:4px，
   定位祖先是最近的 position:relative 容器 .bgt-body（padding: 0 12px 10px）
   而非 pre ⇒ 按钮右缘落在 pre.bgt-output 右边界外 8px（12px 横向 padding − 4px）。
   本面板就地覆盖：right:18px = 12px(.bgt-body padding-right) + 6px（pre 边内缩
   6px，清开 .bgt-output 的 4px 细滚动条档，见下方 ::-webkit-scrollbar）；
   top:4px 与 pre 顶边对齐（.bgt-body padding-top 为 0、pre margin 为 0）——
   按钮四边因此全部落在 .bgt-output 矩形内（几何读数见本批 evidence）。
   选择器限 .bgt-body 直接子级（0,2,0 > chat.css 的 .code-copy-btn 0,1,0）
   ⇒ 公共 .code-copy-btn / chat.css 零改动，气泡代码块等其它场景不受影响。 */
.bgt-body > .code-copy-btn { top: 4px; right: 18px; }
.bgt-output {
  flex: 1;
  margin: 0;
  padding: 28px 14px 12px;
  overflow: auto;
  background: #f5f5f5;
  border: 1px solid var(--color-frame-border);
  border-radius: 8px;
  font: 400 12px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--color-text);
  white-space: pre-wrap;
  word-break: break-word;
  overscroll-behavior: contain;
  scrollbar-color: var(--color-frame-border) transparent;
}
@media (prefers-color-scheme: dark) {
  .bgt-output { background: #111419; scrollbar-color: rgba(255,255,255,0.15) transparent; }
}
/* 代码/终端面 4px 细滚动条档（base.css 全局 6px，tool-card body 先例同款覆宽）。 */
.bgt-output::-webkit-scrollbar { width: 4px; height: 4px; }
.bgt-placeholder {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  padding: 16px;
  font: 400 12px -apple-system, sans-serif;
  color: var(--color-text-muted);
  text-align: center;
}
.bgt-placeholder.bgt-hidden { display: none; }
.bgt-exit-label.bgt-hidden, .bgt-truncated.bgt-hidden, .bgt-error.bgt-hidden { display: none; }

/* Footer：同 .flow-agent-footer（顶缘折射线 + hairline）；meta 左、截断注记右。
   （2026-09-10 作者指令：显式复制按钮区移除——复制改为 hover 右上角浮现，
   与代码块同款交互同款样式，见 .bgt-body 的 code-block-wrap 复用。） */
.bgt-footer {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 8px;
  padding: 8px 16px;
  border-top: 1px solid var(--glass-border);
  position: relative;
}
.bgt-footer::before {
  content: '';
  position: absolute;
  top: 0; left: 10%; right: 10%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 10%,
    var(--sapphire-refraction, rgba(99,179,237,0.25)) 50%,
    transparent 90%);
  pointer-events: none;
  z-index: 1;
}
.bgt-meta {
  font: 400 11px ui-monospace, SFMono-Regular, Menlo, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-muted);
  min-width: 0;
  display: inline-flex; align-items: center; gap: 6px;
}
.bgt-meta-text {
  min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 终端语言的状态标识（2026-09-10 作者指令③；2026-09-12 作者令：「绿色光标也
   要放在输出框内」）：运行态 = pre 文本流末尾的终端光标块（.tool-stream-body
   pre .cursor 同源视觉：--color-primary 5×12 blink）——渲染在 .bgt-output
   内（空输出时即其内容区左上角），随输出一起滚动、永不覆盖文本；伪元素不落
   pre DOM ⇒ pre.textContent（window.copyCode 的读取面）零污染。
   终态 = footer 的美元符 prompt 前缀 + exit 码（如 "$ exit 0 · 3 行 · 27 B"）。 */
.bgt-output.bgt-running::after {
  content: '';
  display: inline-block;
  width: 5px; height: 12px;
  margin-left: 1px;
  background: var(--color-primary);
  vertical-align: text-bottom;
  animation: blink 1s steps(2) infinite;
}
.bgt-prompt { opacity: 0.7; flex-shrink: 0; }
.bgt-truncated {
  font: 500 11px -apple-system, sans-serif;
  color: #d4a030;
  flex-shrink: 0;
}
@media (prefers-reduced-motion: reduce) {
  .bgt-overlay { animation: none; }
}
</style>`;

function ensureCss() {
  if (!document.getElementById('bgt-output-popup-css')) {
    const holder = document.createElement('div');
    holder.innerHTML = POPUP_CSS;
    document.head.appendChild(holder.firstElementChild);
  }
}

// ── Status mapping（复用行面板的状态点/文字约定）───────────
// state 仅作样式钩子（chip 终态分色，见 POPUP_CSS .bgt-state-label--*），状态→文案/点的映射本身不动。
function statusView(status) {
  if (status === 'completed') return { label: t('bg.completed'), dot: 'bg-task-status bg-status-done', state: 'done' };
  if (status === 'failed') return { label: t('bg.failed'), dot: 'bg-task-status bg-status-error', state: 'failed' };
  if (status === 'cancelled') return { label: t('bg.cancelled'), dot: 'bg-task-status bg-status-done', state: 'done' };
  if (status === 'cancelling') return { label: t('bg.cancelling'), dot: 'bg-task-status bg-status-done', state: 'done' };
  return { label: t('bg.running'), dot: 'bg-task-status bg-status-active', state: '' };
}

function renderStatus(status, exitCode) {
  if (!cur || cur.closed) return;
  cur.lastStatus = status;
  const chip = overlayEl.querySelector('.bgt-state-chip');
  const dotEl = chip.querySelector('.bg-task-status');
  const labelEl = chip.querySelector('.bgt-state-label');
  const v = statusView(status);
  dotEl.className = v.dot + ' bgt-dot';
  labelEl.className = 'bgt-state-label' + (v.state ? ' bgt-state-label--' + v.state : '');
  // ui-status-redesign（盘点 #15）：failed 词前 circle-x 红图标，词本体中性。
  labelEl.innerHTML = (v.state === 'failed' ? statusIcon('circle-x', 11, 'st-err') : '') + escapeHtml(v.label);
  // 非零退出码补注（completed 但 exit!=0 也如实展示）；outputPath 有值时补在
  // subtitle（输出查看补全批：本地=落盘路径 / remote=对端日志路径——title 带
  // 全文，视觉截断交给 CSS ellipsis）。
  const sub = overlayEl.querySelector('.bgt-subtitle');
  const exitNote = (exitCode !== undefined && exitCode !== null && exitCode !== 0)
    ? ' · ' + t('bg.detail.exit', { code: exitCode }) : '';
  const pathNote = cur.outputPath ? ' · ' + cur.outputPath : '';
  const origin = cur.originText || '';
  sub.textContent = cur.taskId + (origin ? ' · ' + origin : '') + exitNote + pathNote;
  sub.title = cur.outputPath || '';
}

function renderMeta(data) {
  if (!cur || cur.closed) return;
  const metaTextEl = overlayEl.querySelector('.bgt-meta-text');
  if (!metaTextEl) return;
  // #753 运行态活性：meta 段补「已运行」时长（等待型任务整段无 stdout 时，
  // 行数/字节恒 0——时长是「它还在跑」的唯一可见信号）。startedAt 来自行数据。
  const running = data.status === 'running' || data.status === 'cancelling';
  const upPart = running && cur.startedAtMs
    ? ' · ' + t('bg.detail.uptime', { dur: fmtDuration(Date.now() - cur.startedAtMs) })
    : '';
  metaTextEl.textContent = t('bg.detail.meta', { lines: fmtLines(data.totalLines), bytes: fmtBytes(data.totalBytes) }) + upPart;
}

/** 时长（bg.detail.uptime 陪伴面；轻实现——秒/分/时三档）。 */
function fmtDuration(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
}

// 终端语言 meta（2026-09-10 作者指令③）：终态 → `$` prompt 前缀 + exit 码
// （复用 bg.detail.exit / 既有终态 label，零新 key）；运行态 → 绿光标块。
// 2026-09-12 作者令（「绿色光标也要放在输出框内」）：光标不再落 footer 的
// meta-marker，改为 pre 上的 bgt-running 状态类（POPUP_CSS 的
// .bgt-output.bgt-running::after 伪元素）——光标进输出框、随输出滚动，伪元素
// 不进 pre DOM ⇒ pre.textContent（复制面）零污染；运行态 marker 因此留空。
// 只动 pre 状态类 / marker / exit-label 三处——meta-text（行数/字节）由
// renderMeta 独占。
function renderTerminalState(status, exitCode) {
  if (!cur || cur.closed) return;
  const pre = overlayEl.querySelector('.bgt-output');
  const marker = overlayEl.querySelector('.bgt-meta-marker');
  const exitEl = overlayEl.querySelector('.bgt-exit-label');
  if (!pre || !marker || !exitEl) return;
  if (status && status !== 'running' && status !== 'cancelling') {
    pre.classList.remove('bgt-running');
    marker.innerHTML = '<span class="bgt-prompt">$</span>';
    const label = (exitCode !== undefined && exitCode !== null)
      ? t('bg.detail.exit', { code: exitCode })
      : statusView(status).label;
    exitEl.textContent = label;
    exitEl.classList.remove('bgt-hidden');
  } else {
    pre.classList.add('bgt-running');
    marker.textContent = '';
    exitEl.textContent = '';
    exitEl.classList.add('bgt-hidden');
  }
}

function showTruncatedNote() {
  if (!cur || cur.closed) return;
  const note = overlayEl.querySelector('.bgt-truncated');
  note.textContent = t('bg.detail.truncated');
  note.classList.remove('bgt-hidden');
}

function showErrorHint(text) {
  if (!cur || cur.closed || !text) return;
  const el = overlayEl.querySelector('.bgt-error');
  // 取消与失败分开措辞（cancelled 的 hint 是取消原因，不是失败原因）
  const key = cur.lastStatus === 'cancelled' ? 'bg.detail.cancelHint' : 'bg.detail.failedHint';
  // ui-status-redesign（S9 同族）：行首 circle-alert 红图标 + 中性正文（红由
  // 左侧 2px 描边承载，见 .bgt-error）。text = 后端原始错误串，必须 escape。
  el.innerHTML = statusIcon('circle-alert', 12, 'st-err') + '<span>' + escapeHtml(t(key, { error: text })) + '</span>';
  el.classList.remove('bgt-hidden');
}

function showPlaceholder(text) {
  if (!cur || cur.closed) return;
  const ph = overlayEl.querySelector('.bgt-placeholder');
  ph.textContent = text;
  ph.classList.remove('bgt-hidden');
}
function hidePlaceholder() {
  if (!cur || cur.closed) return;
  overlayEl.querySelector('.bgt-placeholder').classList.add('bgt-hidden');
}

// ── 滚动跟随：近底部才跟随，用户上滚即暂停 ────────────────
function appendOutput(text) {
  if (!cur || cur.closed || !text) return;
  const pre = overlayEl.querySelector('.bgt-output');
  const nearBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 32;
  // 增量块行对齐（后端按整行切）——与已有内容间补行分隔，避免首行粘连
  const sep = cur.text ? '\n' : '';
  cur.text += sep + text;
  pre.textContent += sep + text;
  if (nearBottom) pre.scrollTop = pre.scrollHeight;
}

// ── 轮询（字节游标增量；2026-09-10 修复：预算+终局态）──────
function stopPoll() {
  if (cur && cur.pollTimer) { clearInterval(cur.pollTimer); cur.pollTimer = null; }
}

// 终局收口：停一切定时器与在途请求。所有终局路径（终态到达/404/失败预算/
// 总预算）必须走这里——禁任何无限静默等待。
function stopAll() {
  if (!cur) return;
  stopPoll();
  if (cur.totalTimer) { clearTimeout(cur.totalTimer); cur.totalTimer = null; }
  if (cur.retryTimer) { clearTimeout(cur.retryTimer); cur.retryTimer = null; }
  if (cur.abortCtl) { try { cur.abortCtl.abort(); } catch { /* */ } cur.abortCtl = null; }
}

// 读取失败终局（预算耗尽）：明确错误提示 + 停止一切等待，面板可关闭。
// remote 模式专措（输出查看补全批）：设备通道拉不到 ≠ 本地网络故障——
// 「设备可能离线」语义，不用本地措辞搪塞。
function showReadFailure() {
  if (!cur || cur.closed) return;
  stopAll();
  const dotEl = overlayEl.querySelector('.bgt-dot');
  if (dotEl) dotEl.className = 'bg-task-status bg-status-error bgt-dot';
  showPlaceholder(t(cur.remoteMode ? 'bg.detail.remoteOffline' : 'bg.detail.readError'));
}

// 带 8s 挂死兜底的 fetch（AbortController；响应到达即拆定时器）。
async function fetchWithTimeout(url, opts) {
  const ctl = new AbortController();
  cur.abortCtl = ctl;
  const timer = setTimeout(() => ctl.abort(), FETCH_TIMEOUT_MS);
  try {
    return await fetch(url, { ...opts, signal: ctl.signal });
  } finally {
    clearTimeout(timer);
    if (cur && cur.abortCtl === ctl) cur.abortCtl = null;
  }
}

async function pollOnce() {
  if (!cur || cur.closed) return;
  let resp = null;
  try {
    resp = await fetchWithTimeout(
      `/api/bg-tasks/${encodeURIComponent(cur.taskId)}/output?offset=${cur.offset}`,
      { headers: authHeaders() }
    );
  } catch {
    // 网络/超时失败：计入预算（旧版静默 return 无上限——卡死主根因之一）。
    onReadFailure();
    return;
  }
  if (!cur || cur.closed) return;
  if (resp.status === 404) {
    // 未知任务：remote 任务从未缓冲 / 留存区已淘汰。仅首拍降级提示，
    // 已有数据时（极端：轮询中被淘汰）保留现有内容只停轮询。
    // 状态 chip 不谎报（2026-09-10 修复：旧版写死 cancelled——已完成任务
    // 被留存淘汰后显示「已取消」，与面板行「已完成」矛盾）。
    if (!cur.gotData) showPlaceholder(t('bg.detail.notFound'));
    const dotEl = overlayEl.querySelector('.bgt-dot');
    if (dotEl) dotEl.className = 'bg-task-status bg-status-done bgt-dot';
    stopAll();
    return;
  }
  if (!resp.ok) { onReadFailure(); return; } // HTTP 错误——计入预算，不再静默
  let data = null;
  try {
    data = await resp.json();
  } catch {
    onReadFailure(); // 响应体损坏同预算处理
    return;
  }
  if (!cur || cur.closed) return;
  cur.failCount = 0;
  cur.gotData = true;
  if (data.outputPath) cur.outputPath = data.outputPath;
  hidePlaceholder();
  if (data.output) appendOutput(data.output);
  else if (!cur.text) {
    // #753「点进去永远是空」语义面：运行中且暂无输出 ≠ 空数据——明示「还在跑、
    // 没产出」（等待型/轮询任务整段无 stdout 是常态）。终态空输出仍走既有空词。
    const runningEmpty = !data.status || data.status === 'running';
    showPlaceholder(t(runningEmpty ? 'bg.detail.waitingLive' : 'bg.detail.empty'));
  }
  if (typeof data.nextOffset === 'number') cur.offset = data.nextOffset;
  renderMeta(data);
  renderStatus(data.status, data.exitCode);
  renderTerminalState(data.status, data.exitCode);
  if (data.truncated) showTruncatedNote();
  if (data.errorHint) showErrorHint(data.errorHint);
  if (data.status && data.status !== 'running') { stopAll(); return; }
  // 单次读取模式（终态行）但 store 竟返回 running（行状态过时/竞态）→
  // 就地转轮询模式直到终态（预算照常生效）。
  if (cur.oneShot) {
    cur.oneShot = false;
    armTotalBudget();
    cur.pollTimer = setInterval(pollOnce, 2000);
  }
}

// 失败预算判定：超限 → 明确错误终局（禁无限静默等待）；未超限 → 安排下一拍
// （oneShot 的「首拍+2 重试」由 retryTimer 驱动；轮询模式由 interval 驱动）。
function onReadFailure() {
  if (!cur || cur.closed) return;
  cur.failCount = (cur.failCount || 0) + 1;
  if (cur.oneShot) {
    if (cur.failCount >= MAX_ONESHOT_ATTEMPTS) { showReadFailure(); return; }
    if (!cur.retryTimer) cur.retryTimer = setTimeout(() => { if (cur && !cur.closed) { cur.retryTimer = null; pollOnce(); } }, 1200);
    return;
  }
  if (cur.failCount >= MAX_POLL_FAILS) showReadFailure();
}

// 轮询模式总预算：10min 无终态 → 停轮询并明示（persistent/僵死任务防线）。
function armTotalBudget() {
  if (!cur || cur.totalTimer) return;
  cur.totalTimer = setTimeout(() => {
    if (!cur || cur.closed) return;
    stopAll();
    const dotEl = overlayEl.querySelector('.bgt-dot');
    if (dotEl) dotEl.className = 'bg-task-status bg-status-done bgt-dot';
    showPlaceholder(t('bg.detail.stopped'));
  }, MAX_POLL_TOTAL_MS);
}

function startPoll() {
  stopPoll();
  // 完成态行走单次读取模式（2026-09-10 验收①：从持久层一次读取到位，不依赖
  // 活流/活订阅）——首拍失败允许 2 次重试，仍失败即终局报错；绝无轮询死等。
  // （oneShot 在 pollOnce 内发现 store 仍 running 时就地解除并转入轮询。）
  cur.oneShot = isTerminalStatus(cur.initialStatus);
  cur.failCount = 0;
  pollOnce();
  if (!cur.oneShot) {
    cur.pollTimer = setInterval(pollOnce, 2000);
    armTotalBudget();
  }
}

// ── 远端模式（2026-10-25 输出查看补全批，作者令「远端任务看不到」）──
// 运行中：轮询 /bg-tasks/:id/remote-output——引擎按 job 记录的 outputPath 经
// 既有设备通道拉对端日志尾窗（~3s 节拍，整窗替换渲染，非增量游标）。404 =
// 任务已终态（引擎完成即注销 + 灌账留存）→ 单拍 /output 收口。失败预算与本地
// 同族（MAX_POLL_FAILS 连败终局 / 总预算），措辞分叉（remoteOffline）。
// 「输出不可用」不再用「暂不支持」搪塞：无 outputPath / executor 缺席 = 永久
// 态（停轮询明示原因）；设备拉取失败 = 暂态（占位原因 + 继续重试至预算）。

/** 整窗替换渲染（远端尾窗语义：每拍拿的是完整尾窗快照，不是增量块）。 */
function setOutput(text) {
  if (!cur || cur.closed) return;
  const pre = overlayEl.querySelector('.bgt-output');
  const nearBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 32;
  cur.text = text || '';
  pre.textContent = cur.text;
  if (nearBottom) pre.scrollTop = pre.scrollHeight;
}

/** 远端 meta：对端日志路径（title 全文）+ 运行时长——行数/字节对尾窗无意义。 */
function renderRemoteMeta() {
  if (!cur || cur.closed) return;
  const metaTextEl = overlayEl.querySelector('.bgt-meta-text');
  if (!metaTextEl) return;
  const running = cur.lastStatus === 'running' || cur.lastStatus === 'cancelling';
  const upPart = running && cur.startedAtMs
    ? ' · ' + t('bg.detail.uptime', { dur: fmtDuration(Date.now() - cur.startedAtMs) })
    : '';
  metaTextEl.textContent = (cur.outputPath || '') + upPart;
  metaTextEl.title = cur.outputPath || '';
}

/** 终态收口：单拍 /output 取引擎灌账留存（fillRemote 先于 WS completed 帧 ⇒
 *  命中是常态）；仍 404 = 无留存（引擎重启后旧任务）→ 明示，不虚报状态。 */
async function fetchRemoteFinal() {
  let resp = null;
  try {
    resp = await fetchWithTimeout(
      `/api/bg-tasks/${encodeURIComponent(cur.taskId)}/output?offset=0`,
      { headers: authHeaders() }
    );
  } catch {
    showReadFailure();
    return;
  }
  if (!cur || cur.closed) return;
  if (!resp.ok) {
    const dotEl = overlayEl.querySelector('.bgt-dot');
    if (dotEl) dotEl.className = 'bg-task-status bg-status-done bgt-dot';
    showPlaceholder(t('bg.detail.remoteLost'));
    stopAll();
    return;
  }
  let data = null;
  try { data = await resp.json(); } catch { showReadFailure(); return; }
  if (!cur || cur.closed) return;
  if (data.outputPath) cur.outputPath = data.outputPath;
  if (data.output) setOutput(data.output);
  else showPlaceholder(t('bg.detail.empty'));
  renderMeta(data);
  renderStatus(data.status || cur.initialStatus, data.exitCode);
  renderTerminalState(data.status || cur.initialStatus, data.exitCode);
  if (data.output && data.truncated) showTruncatedNote();
  if (data.errorHint) showErrorHint(data.errorHint);
  stopAll();
}

async function pollRemoteOnce() {
  if (!cur || cur.closed) return;
  // 已收口（fetchRemoteFinal 渲染过终态）→ 不再打设备通道（interval 由 stopAll
  // 清掉，本拍是竞态尾巴，静默返回即可）。
  if (isTerminalStatus(cur.lastStatus || '')) return;
  let resp = null;
  try {
    resp = await fetchWithTimeout(
      `/api/bg-tasks/${encodeURIComponent(cur.taskId)}/remote-output`,
      { headers: authHeaders() }
    );
  } catch {
    onReadFailure();
    return;
  }
  if (!cur || cur.closed) return;
  if (resp.status === 404) { stopPoll(); await fetchRemoteFinal(); return; }
  if (!resp.ok) { onReadFailure(); return; }
  let data = null;
  try { data = await resp.json(); } catch { onReadFailure(); return; }
  if (!cur || cur.closed) return;
  cur.failCount = 0;
  if (data.outputPath) cur.outputPath = data.outputPath;
  if (data.available === false) {
    const kind = data.reasonKind || 'pullFailed';
    if (kind === 'noOutputPath' || kind === 'noExecutor') {
      // 永久态：轮询无意义 → 停，占位 = 真实内容 + 明确原因（不再「暂不支持」）。
      stopAll();
      showPlaceholder(t(kind === 'noOutputPath' ? 'bg.detail.remoteNoPath' : 'bg.detail.remoteNoExecutor'));
    } else {
      // 暂态（设备离线/拉取失败）：占位原因，继续重试至预算。
      showPlaceholder(t('bg.detail.remoteUnavailable', { reason: data.reason || '' }));
    }
    return;
  }
  hidePlaceholder();
  setOutput(data.output || '');
  renderStatus('running');
  renderTerminalState('running');
  renderRemoteMeta();
  if (data.truncated) showTruncatedNote();
}

function startRemotePoll() {
  cur.remoteMode = true;
  cur.failCount = 0;
  renderStatus(cur.initialStatus);
  renderTerminalState(cur.initialStatus);
  renderRemoteMeta();
  // 终态行（completed 行回看）→ 不进轮询，直取灌账留存。
  if (isTerminalStatus(cur.initialStatus)) { fetchRemoteFinal(); return; }
  showPlaceholder(t('bg.detail.remotePulling', { device: cur.deviceName || '' }));
  pollRemoteOnce();
  cur.pollTimer = setInterval(pollRemoteOnce, REMOTE_POLL_MS);
  armTotalBudget();
}

// ── 复制（hover 右上角浮现，2026-09-10 作者指令：与代码块同款）──
// 点击处理直接复用代码块全局 window.copyCode（utils.js）：读取
// .code-block-wrap 内 pre 的当前 textContent（运行中=已加载部分，规格允许）、
// chat.copied 反馈 2s 还原、降级选中文本——交互/反馈与代码块行为完全一致。
// 防御性 fallback：window.copyCode 缺席时用本地最小实现（同款反馈语义）。
function wireCopyButton(btn) {
  if (typeof window.copyCode === 'function') {
    btn.addEventListener('click', () => window.copyCode(btn));
    return;
  }
  btn.addEventListener('click', async () => {
    const pre = overlayEl && overlayEl.querySelector('.bgt-output');
    const text = pre ? pre.textContent : '';
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      const span = btn.querySelector('span');
      span.textContent = t('chat.copied');
      btn.classList.add('copied');
      setTimeout(() => {
        span.textContent = t('chat.copy');
        btn.classList.remove('copied');
      }, 2000);
    } catch { /* 与代码块同语义：静默失败 */ }
  });
}

// 与 utils.js renderMarkdown 注入的代码块复制按钮同构（svg + span 双语 title）。
function copyButtonHtml() {
  return '<button class="code-copy-btn" type="button" title="' + t('chat.copy') + '">' +
    '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>' +
    '<span>' + t('chat.copy') + '</span></button>';
}

// ── Open / close ──────────────────────────────────────────

export function openBgTaskOutput(task) {
  if (!task || !task.taskId) return;
  closeBgTaskOutput();
  ensureCss();

  const restoreFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  cur = {
    taskId: task.taskId,
    offset: 0,
    text: '',
    pollTimer: null,
    totalTimer: null,
    retryTimer: null,
    abortCtl: null,
    failCount: 0,
    oneShot: false,
    closed: false,
    gotData: false,
    restoreFocus,
    // 行面板乐观态——单次读取模式的判定基准 + 404 时 chip 不谎报的依据
    initialStatus: task.status || 'running',
    // #753 运行态 meta 的「已运行」时长来源（行数据自带 startedAt；缺省 0 = 不显示）。
    startedAtMs: Number(task.startedAt) || 0,
    originText: [task.originLabel, task.kind === 'remote' ? t('bg.kind.remote') : (task.kind ? t('bg.kind.local') : '')]
      .filter(Boolean).join(' · '),
    // 远端模式面（输出查看补全批）：remoteMode = 轮询 /remote-output 的整窗渲染
    // 分叉；outputPath = 对端日志路径（引擎逐拍回带）；deviceName = 设备名
    // （origin chip 的 label，拉取中占位文案用）。
    remoteMode: false,
    outputPath: '',
    deviceName: task.originLabel || ''
  };

  overlayEl = document.createElement('div');
  overlayEl.className = 'bgt-overlay';
  // 行身份（R2，2026-09-10）：main.js 的后台任务面板靠它判断「这一行的输出卡正开着」
  // ⇒ 该行到期倒计时暂停（卡片关闭后重新计时，不是关闭即到期）。
  overlayEl.dataset.taskId = task.taskId;
  overlayEl.setAttribute('role', 'dialog');
  overlayEl.setAttribute('aria-label', t('bg.detail.aria'));
  overlayEl.innerHTML =
    '<div class="bgt-modal">' +
      '<div class="bgt-header">' +
        '<span class="bgt-state-chip"><span class="bg-task-status bg-status-active bgt-dot" aria-hidden="true"></span>' +
        '<span class="bgt-state-label"></span></span>' +
        '<div class="bgt-title-wrap">' +
          '<div class="bgt-title"></div>' +
          '<div class="bgt-subtitle"></div>' +
        '</div>' +
        '<button class="bgt-close" type="button" aria-label="Close">×</button>' +
      '</div>' +
      '<div class="bgt-error bgt-hidden"></div>' +
      '<div class="bgt-body code-block-wrap">' +
        copyButtonHtml() +
        '<pre class="bgt-output"></pre>' +
        '<div class="bgt-placeholder bgt-hidden"></div>' +
      '</div>' +
      '<div class="bgt-footer">' +
        '<span class="bgt-meta"><span class="bgt-meta-marker"></span><span class="bgt-exit-label bgt-hidden"></span><span class="bgt-meta-text"></span></span>' +
        '<span class="bgt-truncated bgt-hidden"></span>' +
      '</div>' +
    '</div>';

  // 动态文本一律 textContent（XSS 纪律）
  overlayEl.querySelector('.bgt-title').textContent = task.description || task.taskId;
  const copyBtn = overlayEl.querySelector('.code-copy-btn');
  wireCopyButton(copyBtn);

  document.body.appendChild(overlayEl);

  // 关闭交互：× 按钮 / 点 overlay 空白 / Esc（capture——避免被其他全局 Esc 抢）
  overlayEl.querySelector('.bgt-close').addEventListener('click', closeBgTaskOutput);
  overlayEl.addEventListener('click', (e) => {
    if (e.target === overlayEl) closeBgTaskOutput();
  });
  escHandler = (e) => {
    if (e.key === 'Escape') { e.stopPropagation(); closeBgTaskOutput(); }
  };
  // window 捕获级（capture 顺序 window → document）：main.js initGlobalEscHandler
  // 在 document 捕获级关 #bg-dropdown——卡片在最顶层，Esc 两段式先关卡片，
  // stopPropagation 挡住后续层（再按一次 Esc 才轮到下拉，既有行为不变）。
  window.addEventListener('keydown', escHandler, true);

  // 初始状态（行面板乐观态）→ 首拍立刻校正。remote 任务显示行传入的真实
  // 状态（2026-09-10 修复：旧版写死 running——完成的 remote 任务在卡内永远
  // 转圈，即作者实测「卡在那」的形态之一）。
  renderStatus(cur.initialStatus);
  renderMeta({ totalLines: 0, totalBytes: 0 });
  renderTerminalState(cur.initialStatus);

  // 焦点管理：聚焦关闭按钮，关闭时归还触发行
  // guard：querySelector 失配（null）/ 非 HTMLElement 时无害 no-op——旧写法在该
  // 情形抛 TypeError。instanceof 为运行期窄化守卫（本仓焦点行既有写法，见
  // chatSearch.js:368），非类型断言逃逸。命中处无变化。
  const closeBtn = overlayEl.querySelector('.bgt-close');
  if (closeBtn instanceof HTMLElement) closeBtn.focus();

  if (task.kind === 'remote') {
    // 远端任务（输出查看补全批 2026-10-25 重做）：不再降级占位——轮询引擎的
    // remote-output 面（对端日志尾窗按需拉取），终态收口取灌账留存；无路径 /
    // 设备不可达 = 「输出不可用 + 原因」真实内容。复制按钮照常 hover 浮现
    // （空输出点击 no-op——window.copyCode 空文本 guard）。
    startRemotePoll();
    return;
  }
  startPoll();
}

export function closeBgTaskOutput() {
  if (!overlayEl) { cur = null; return; }
  stopAll();
  if (cur) cur.closed = true;
  const restore = cur && cur.restoreFocus;
  escHandler && window.removeEventListener('keydown', escHandler, true);
  escHandler = null;
  overlayEl.remove();
  overlayEl = null;
  cur = null;
  if (restore && document.contains(restore)) restore.focus();
}

export function isBgTaskOutputOpen() {
  return !!overlayEl;
}
