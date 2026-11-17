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

function send(action, sid, hunkId) {
  const st = reviewState(sid);
  if (!st || st.waiting) return;
  st.waiting = { action, hunkId };
  st.error = '';
  render(sid);
  const frame = { type: action === 'dismiss' ? 'askEditDismiss' : action === 'accept' ? 'askEditAccept' : 'askEditReject', sessionId: sid };
  if (hunkId) frame.hunkId = hunkId;
  sendWs(frame);
}

// ── 渲染 ────────────────────────────────────────────────────────────────────

/** 上下文折叠阈：长未变段只露头尾各 FOLD_EDGE 行（点击展开——「原文档内联」
 *  的可读性面，不是硬截断）。 */
const FOLD_MIN = 14;
const FOLD_EDGE = 6;

function lineRow(cls, text, prefix) {
  return h('div', { class: 'nf-ar-line ' + cls }, (prefix ? prefix + (text === '' ? '' : ' ' + text) : text));
}

/** 一段未变行 →（可能折叠的）行节点数组。 */
function contextRows(lines, from, to) {
  const run = to - from;
  if (run <= FOLD_MIN) {
    const rows = [];
    for (let i = from; i < to; i++) rows.push(lineRow('ctx', lines[i], ''));
    return rows;
  }
  const rows = [];
  for (let i = from; i < from + FOLD_EDGE; i++) rows.push(lineRow('ctx', lines[i], ''));
  const hidden = run - FOLD_EDGE * 2;
  const fold = h('button', {
    type: 'button',
    class: 'nf-ar-fold',
    'aria-label': tt(`展开中间 ${hidden} 行未变内容`, `expand ${hidden} unchanged lines`),
  }, tt(`⋯ ${hidden} 行未变（点击展开）`, `⋯ ${hidden} unchanged lines (click to expand)`));
  const wrap = h('div', { class: 'nf-ar-foldwrap' }, fold);
  const hiddenRows = [];
  for (let i = from + FOLD_EDGE; i < to - FOLD_EDGE; i++) {
    const row = lineRow('ctx', lines[i], '');
    row.style.display = 'none';
    hiddenRows.push(row);
  }
  fold.addEventListener('click', () => {
    for (const r of hiddenRows) r.style.display = '';
    wrap.remove();
  });
  rows.push(wrap, ...hiddenRows);
  for (let i = to - FOLD_EDGE; i < to; i++) rows.push(lineRow('ctx', lines[i], ''));
  return rows;
}

/** 一个可审块：块动作条 + del（基线行）/ add（现文行）。行文本一律
 *  textContent——文件原文绝不进 innerHTML（红线）。 */
function hunkNode(st, hunk) {
  const baseL = st.baseText.split(/\r?\n/);
  const curL = st.currentText.split(/\r?\n/);
  const dels = baseL.slice(hunk.oldStart, hunk.oldStart + hunk.oldCount);
  const adds = curL.slice(hunk.newStart, hunk.newStart + hunk.newCount);
  const disabled = !!st.waiting;
  const bar = h('div', { class: 'nf-ar-hunkbar' }, [
    h('span', { class: 'nf-ar-hunkmeta' }, `+${adds.length} −${dels.length}`),
    h('button', {
      type: 'button',
      class: 'nf-ar-btn nf-ar-hunk-accept',
      'data-hunk-id': hunk.id,
      'aria-label': tt('接受本块（保留该改动）', 'Accept this block (keep the change)'),
      ...(disabled ? { disabled: '' } : {}),
    }, tt('接受本块', 'Accept')),
    h('button', {
      type: 'button',
      class: 'nf-ar-btn nf-ar-btn-danger nf-ar-hunk-reject',
      'data-hunk-id': hunk.id,
      'aria-label': tt('拒绝本块（回退该改动）', 'Reject this block (revert the change)'),
      ...(disabled ? { disabled: '' } : {}),
    }, tt('拒绝本块', 'Reject')),
  ]);
  const lines = h('div', { class: 'nf-ar-hunklines' }, [
    ...dels.map((l) => lineRow('del', l, '−')),
    ...adds.map((l) => lineRow('add', l, '+')),
  ]);
  return h('div', { class: 'nf-ar-hunk', 'data-hunk-id': hunk.id, 'data-state': disabled ? 'waiting' : 'pending' }, [bar, lines]);
}

/** 整页渲染（幂等：整树重建）。 */
function render(sid) {
  const st = reviewState(sid);
  if (!st) return;
  const pane = getTabPane(tabIdOf(sid));
  if (!pane || !pane.isConnected) return;
  pane.textContent = '';
  const baseL = st.baseText.split(/\r?\n/);
  const added = st.hunks.reduce((a, x) => a + (Number(x.newCount) || 0), 0);
  const removed = st.hunks.reduce((a, x) => a + (Number(x.oldCount) || 0), 0);
  const waiting = !!st.waiting;

  // 顶栏：文件 + 计数 + 全量动作（快捷键 ⌘↩ / ⌘⌫ 同一动作面）。
  const head = h('div', { class: 'nf-ar-head' }, [
    h('div', { class: 'nf-ar-headpill' }, [
      h('span', { class: 'nf-ar-file', title: st.path }, st.fileName || st.path),
      h('span', { class: 'nf-ar-counts' }, [
        h('span', { class: 'nf-ar-added' }, `+${added}`),
        ' ',
        h('span', { class: 'nf-ar-removed' }, `−${removed}`),
        ` · ${tt(`${st.hunks.length} 块待审`, `${st.hunks.length} block${st.hunks.length === 1 ? '' : 's'} to review`)}`,
      ]),
    ]),
    h('div', { class: 'nf-ar-actions' }, [
      waiting ? h('span', { class: 'nf-ar-waiting' }, tt('已发送…', 'Sent…')) : null,
      h('button', {
        type: 'button',
        class: 'nf-ar-btn nf-ar-accept-all',
        'aria-label': tt('全部接受（保留全部改动）', 'Accept all (keep every change)'),
        ...(waiting ? { disabled: '' } : {}),
      }, tt('全部接受 ⌘↩', 'Accept all ⌘↩')),
      h('button', {
        type: 'button',
        class: 'nf-ar-btn nf-ar-btn-danger nf-ar-reject-all',
        'aria-label': tt('全部拒绝（回退全部改动）', 'Reject all (revert every change)'),
        ...(waiting ? { disabled: '' } : {}),
      }, tt('全部拒绝 ⌘⌫', 'Reject all ⌘⌫')),
    ]),
  ]);
  pane.appendChild(head);

  // 冲突条（显式提示，绝不静默覆盖）：重试 + 保留盘上现状关闭。
  if (st.error) {
    const retry = st.waiting || null;
    pane.appendChild(h('div', { class: 'nf-ar-conflict', role: 'alert' }, [
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

  // 文档体：原文档全文 + 就地增删（折叠长未变段）。
  const body = h('div', { class: 'nf-ar-body' });
  const rows = [];
  let prevEnd = 0;
  for (const hunk of st.hunks) {
    rows.push(...contextRows(baseL, prevEnd, hunk.oldStart));
    rows.push(hunkNode(st, hunk));
    prevEnd = hunk.oldStart + hunk.oldCount;
  }
  // 尾段：文件以换行结尾时 split 末位是空串——不渲染该伪行。
  const tailEnd = /(^|\n)$/.test(st.baseText) ? baseL.length - 1 : baseL.length;
  rows.push(...contextRows(baseL, prevEnd, Math.max(prevEnd, tailEnd)));
  body.append(...rows);
  pane.appendChild(body);
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
  if (msg.review && typeof msg.review === 'object' && Array.isArray(msg.review.hunks) && msg.review.hunks.length) {
    upsertFromWire(sid, msg.review);
    render(sid);
  } else {
    // 了结：文件页签按路径重载（refreshFileTabByPath 同守卫：脏编辑让路），
    // 审阅页签收起。
    refreshFileTabByPath(st.path);
    reviews.delete(sid);
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
    const target = e.target instanceof Element ? e.target : null;
    if (!target) return;
    const pane = target.closest('.canvas-tab-pane[data-type="askreview"]');
    if (!pane) return;
    const sid = (pane.dataset.tabId || '').slice('askreview:'.length);
    if (!sid) return;
    const hunkBtn = target.closest('.nf-ar-hunk-accept, .nf-ar-hunk-reject');
    if (hunkBtn && !hunkBtn.disabled) {
      const hunkId = hunkBtn.getAttribute('data-hunk-id') || '';
      send(hunkBtn.classList.contains('nf-ar-hunk-accept') ? 'accept' : 'reject', sid, hunkId);
      return;
    }
    if (target.closest('.nf-ar-accept-all') && !target.disabled) { send('accept', sid); return; }
    if (target.closest('.nf-ar-reject-all') && !target.disabled) { send('reject', sid); return; }
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
