// askEditReview.js — canvas 原文档内联 diff 审阅面（canvas-inline-diff-review
// 批，2026-11-16；调研结论见 delivery/canvas-inline-diff-review/research-inline-diff.md）。
//
// 模型 = Cursor 式「先应用、后审阅」（作者原话「agent编辑都是直接成功的。
// 它可以每条同意 也可以一起统一」）：ask_edit/ask_write 直接落盘，本模块把
// 聚合审阅态（baseText=审阅基线 / currentText=盘上现文 / hunks=可审块表，
// 服务端 AskEditReviews 单源下发）渲染成 canvas 上的「审阅页签」——原文档
// 全文就地绿增红删，长未变段折叠。
//
// 动作（WS 域，WsAskHandlers）：
//   逐块：askEditAccept/askEditReject {sessionId, hunkId}（接受=标记已审零写
//   盘；拒绝=回写回退该块）；
//   全量：缺省 hunkId = 全部接受（清账）/全部拒绝（整体回退）；
//   快捷键：审阅页签激活时 ⌘/Ctrl+Enter 全部接受、⌘/Ctrl+Backspace 全部拒绝
//   （对齐 VS Code Copilot 习惯，见调研 §三）。
//
// 过期/并发：askEditResolved outcome:error（服务端盘上校验不等 = 审阅期间文件
// 被改）→ 显式冲突条（绝不静默覆盖）：[重试] 重发同动作、[保留现状并关闭]
// = askEditDismiss（零写盘清账）。
//
// 会话问答路径已摘除：diff 审阅不再进 askDock 气泡列（本批把 canvas-ask-
// restricted-edit ③ 的 diff 卡整体退役）；askDock 仅把 askEdit* 帧路由到本模块。

import { activeCanvasEntry, closeTab, getTabPane, openTab, refreshFileTabByPath } from './canvas.js';
import { sendWs } from './ws.js';
import { h } from './dom.js';
import { key } from './branding.js';
import { esc } from './diffRender.js';
import { mountDiff } from './diffView/index.js';

// ── 文案（askDock tt 同源：locale 键 + 中英双文）────────────────────────────

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

// ── 状态（内存态；服务端 askState.review 为权威回放面）──────────────────────

/** @type {Map<string, {
 *   sid: string, path: string, fileName: string,
 *   baseText: string, currentText: string,
 *   hunks: Array<{id: string, oldStart: number, oldCount: number, newStart: number, newCount: number}>,
 *   count: number, updatedAt: number,
 *   error: string, waiting: null | {action: 'accept'|'reject'|'dismiss', hunkId?: string},
 * }>} */
const reviews = new Map();

const tabIdOf = (sid) => `askreview:${sid}`;

function reviewState(sid) {
  return reviews.get(sid) || null;
}

/** 帧里的审阅态 → 本地态 upsert（幂等；error/waiting 位保留——resolved error
 *  后的新 applied 帧代表更新的审阅事实，覆盖之）。 */
function upsertFromWire(sid, r) {
  if (!r || typeof r !== 'object') return null;
  const prev = reviews.get(sid);
  const hunks = (Array.isArray(r.hunks) ? r.hunks : []).filter((x) => x && typeof x.id === 'string');
  const next = {
    sid,
    path: typeof r.path === 'string' ? r.path : (prev ? prev.path : ''),
    fileName: typeof r.fileName === 'string' ? r.fileName : (prev ? prev.fileName : ''),
    baseText: typeof r.baseText === 'string' ? r.baseText : '',
    currentText: typeof r.currentText === 'string' ? r.currentText : '',
    hunks,
    count: typeof r.count === 'number' ? r.count : hunks.length,
    updatedAt: typeof r.updatedAt === 'number' ? r.updatedAt : Date.now(),
    error: '',
    waiting: prev ? prev.waiting : null,
  };
  reviews.set(sid, next);
  return next;
}

// ── 页签 ────────────────────────────────────────────────────────────────────

/** 开（或复用）审阅页签并渲染当前态。fileName 进标签 innerHTML 面——先 esc。 */
function ensureTab(sid) {
  const st = reviewState(sid);
  if (!st) return null;
  const id = tabIdOf(sid);
  const title = esc(`${st.fileName || 'review'} · ${tt('审阅', 'review')}`);
  const entry = openTab(id, title, { type: 'askreview', closable: true });
  if (!entry) return null;
  render(sid);
  return entry;
}

// ── 动作（WS 外发；waiting 防双发）──────────────────────────────────────────

/** 当前在列的组件 api（sid → api）。动作发出时用它在**原位**落等待面 —— 不整页
 *  重渲，否则「已接受/已拒绝」的就地状态与等待面会被刚建好的树立刻抹掉。 */
/** @type {Map<string, any>} */
const liveApi = new Map();

function send(action, sid, hunkId) {
  const st = reviewState(sid);
  if (!st || st.waiting) return;
  st.waiting = { action, hunkId };
  st.error = '';
  // waiting 面就地落地（组件已就绪）；组件尚在装载 ⇒ 回退整页重渲（状态不丢）。
  const api = liveApi.get(sid);
  if (api && typeof api.setWaiting === 'function') api.setWaiting(true);
  else render(sid);
  const frame = { type: action === 'dismiss' ? 'askEditDismiss' : action === 'accept' ? 'askEditAccept' : 'askEditReject', sessionId: sid };
  if (hunkId) frame.hunkId = hunkId;
  sendWs(frame);
}

// ── 渲染（迁移批 2026-11-19：渲染权移交共享组件 js/diffView）─────────────────
//
// 作者裁定 2026-11-18「手机diff 按推荐」：本面自研的「整树重建 + 手工折叠 +
// 手工行渲染」退役，改调共享组件（与 delegate 面同一组件 = 单源，硬不变式①）。
// 组件底座 CodeMirror 6 + @codemirror/merge 自带逐块接受/拒绝（既有语义不变：
// 逐块钮仍发 askEditAccept/askEditReject {sessionId, hunkId}，只是按钮由组件
// 按 ≥44px 改壳渲染，D6）；行号/词级/未变段折叠/视口虚拟化全部由底座承接
// （D2/D3/D8/D9 —— 1380 行文档不再一次性进 DOM、每次接受/拒绝不再重建整棵树）。

/** 整页渲染（幂等：状态变化重挂组件；组件自身按视口虚拟化）。 */
function render(sid) {
  const st = reviewState(sid);
  if (!st) return;
  const pane = getTabPane(tabIdOf(sid));
  if (!pane || !pane.isConnected) return;
  pane.textContent = '';
  const added = st.hunks.reduce((a, x) => a + (Number(x.newCount) || 0), 0);
  const removed = st.hunks.reduce((a, x) => a + (Number(x.oldCount) || 0), 0);
  const waiting = !!st.waiting;

  // 让位带壳（D4：审阅标题条此前从 y=0 起排，被画布浮钮压住；本壳沿用 delegate
  // 面同一条 56px 常量 --nf-dv-top-band，两个面共用同一层壳）。
  const shell = h('div', { class: 'nf-ar-shell' });

  // 冲突条（显式提示，绝不静默覆盖）：重试 + 保留盘上现状关闭。
  if (st.error) {
    const retry = st.waiting || null;
    shell.appendChild(h('div', { class: 'nf-ar-conflict', role: 'alert' }, [
      h('span', { class: 'nf-ar-conflict-text' }, tt('文件在审阅期间被修改，为避免覆盖，操作未执行。', 'The file changed during review — the action was NOT applied to avoid overwriting.')),
      h('span', { class: 'nf-ar-conflict-err', title: st.error }, st.error),
      h('div', { class: 'nf-ar-conflict-actions' }, [
        h('button', {
          type: 'button',
          class: 'nf-ar-btn nf-ar-retry',
          'aria-label': tt('重试该操作', 'Retry the action'),
          ...(retry ? { disabled: '' } : {}),
        }, tt('重试', 'Retry')),
        h('button', {
          type: 'button',
          class: 'nf-ar-btn nf-ar-dismiss',
          'aria-label': tt('保留盘上现状并关闭审阅', 'Keep the on-disk state and close the review'),
        }, tt('保留现状并关闭', 'Keep on-disk & close')),
      ]),
    ]));
  }

  // 文档体：共享组件（canvas 顶部标题 + 原文档全文 + 就地增删 + 逐块动作；
  // 折叠/虚拟化内置）。**标题与动作区构建权都在组件**（单源）：本面只把文本
  // （文件名 / 增删计数 / 审阅状态）经 title 选项交出去 —— 同屏只有一处标题、
  // 一处「全部接受」（本面自绘的旧 `.nf-ar-head` 头随之退役）。
  const editor = h('div', { class: 'nf-ar-editor' });
  shell.appendChild(editor);
  pane.appendChild(shell);

  const baseText = st.baseText;
  const currentText = st.currentText;
  const hunks = st.hunks.slice();
  mountDiff(editor, {
    original: baseText,
    current: currentText,
    mode: 'review',
    hunks,
    waiting,
    title: {
      file: st.fileName || st.path,
      path: st.path,
      counts: `+${added} −${removed}`,
      status: tt(`${st.hunks.length} 块待审`, `${st.hunks.length} block${st.hunks.length === 1 ? '' : 's'} to review`),
    },
    onChunkAction: (hunkId, action) => send(action, sid, hunkId),
  }).then((api) => {
    liveApi.set(sid, api);
    api.setWaiting(waiting);   // waiting 面（防双发）：动作钮（逐块 + 全量）整组禁用
  }).catch(() => { /* 组件装载失败：head 仍在（可见响应面），不静默炸页 */ });
}

// ── 帧消费（askDock 路由进来；会话已在本页建账的前提下）────────────────────

/** askEditApplied：直接落盘回执 → 审阅态 upsert + 开页签呈现。 */
export function consumeApplied(msg) {
  const sid = typeof msg.sessionId === 'string' ? msg.sessionId : '';
  if (!sid) return;
  const st = upsertFromWire(sid, msg);
  if (st && st.hunks.length) ensureTab(sid);
  else if (st) render(sid);
}

/** askEditResolved：done（review 空则收页签 + 重载文件页签）／error（冲突条）。 */
export function consumeResolved(msg) {
  const sid = typeof msg.sessionId === 'string' ? msg.sessionId : '';
  if (!sid) return;
  const st = reviewState(sid);
  if (!st) return;
  st.waiting = null;
  if (msg.outcome === 'error') {
    // 顺序：先 upsert（error 位会被新对象清零）再落 error——冲突文案必须活过
    // review 载荷的 upsert，冲突条才渲染得出（R6 红面抓的就是这个次序）。
    if (msg.review && typeof msg.review === 'object') upsertFromWire(sid, msg.review);
    const cur = reviews.get(sid) || st;
    cur.error = typeof msg.error === 'string' ? msg.error : 'resolve failed';
    render(sid);
    return;
  }
  st.error = '';
  // 接受/拒绝类动作**落地即重载**文件页签（作者令 2026-11-19：内容必来自盘上最新
  // 文本，冷却/防抖不许挡内容刷新）。走 force 腿 ⇒ 干净页签立刻重读一次、脏页签
  // 只挂「有更新」提示（不夺编辑）；剩余块分支同样要刷 —— 每块接受/拒绝都真写了盘。
  const reloadReviewedFile = () => {
    const cur = reviews.get(sid) || st;
    if (cur && cur.path) refreshFileTabByPath(cur.path, { force: true });
  };
  if (msg.review && typeof msg.review === 'object' && Array.isArray(msg.review.hunks) && msg.review.hunks.length) {
    upsertFromWire(sid, msg.review);
    render(sid);
    reloadReviewedFile();
  } else {
    // 了结：文件页签按最新盘上文本重载（force 腿），审阅页签收起。
    reloadReviewedFile();
    reviews.delete(sid);
    liveApi.delete(sid);
    closeTab(tabIdOf(sid));
  }
}

/** askState 回放：服务端在列审阅态播种（重载/换端后审阅页签重建）。 */
export function consumeAskState(msg) {
  const sid = typeof msg.sessionId === 'string' ? msg.sessionId : '';
  if (!sid) return;
  const r = msg.review;
  if (r && typeof r === 'object' && Array.isArray(r.hunks) && r.hunks.length) {
    upsertFromWire(sid, r);
    ensureTab(sid);
  }
}

// ── 事件接线（init 一次）────────────────────────────────────────────────────

let inited = false;

export function initAskEditReview() {
  if (inited) return;
  inited = true;

  // 页签内动作（委托——整树重渲后监听不丢）。
  document.addEventListener('click', (e) => {
    const target = /** @type {HTMLButtonElement|null} */ (e.target instanceof Element ? e.target : null);
    if (!target) return;
    const pane = /** @type {HTMLElement|null} */ (target.closest('.canvas-tab-pane[data-type="askreview"]'));
    if (!pane) return;
    const sid = (pane.dataset.tabId || '').slice('askreview:'.length);
    if (!sid) return;
    // 逐块接受/拒绝 **与** 全量接受/拒绝全部由共享组件（js/diffView）经
    // onChunkAction 回调上抛（作者令 2026-11-19：审阅动作区单源落在组件）；此处只留
    // 冲突条动作（重试 / 保留现状并关闭）。
    if (target.closest('.nf-ar-retry')) {
      const st = reviewState(sid);
      if (st && st.waiting === null) {
        // 重试 = 重发上一个失败动作（error 态已解锁 waiting；无记忆则等价全收）。
        send('accept', sid);
      }
      return;
    }
    if (target.closest('.nf-ar-dismiss')) { send('dismiss', sid); return; }
  });

  // 快捷键（作用域 = 审阅页签激活；对齐 VS Code：⌘↩ 接受 / ⌘⌫ 拒绝）。
  document.addEventListener('keydown', (e) => {
    if (!(e.metaKey || e.ctrlKey)) return;
    if (e.key !== 'Enter' && e.key !== 'Backspace') return;
    const entry = activeCanvasEntry();
    const id = entry && entry.id ? entry.id : '';
    if (!id.startsWith('askreview:')) return;
    const sid = id.slice('askreview:'.length);
    const st = reviewState(sid);
    if (!st || st.waiting) return;
    e.preventDefault();
    if (e.key === 'Enter') send('accept', sid);
    else send('reject', sid);
  });
}
