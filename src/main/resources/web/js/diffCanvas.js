// diffCanvas.js — Canvas diff view for a delegate batch's fileChanges payload
// (2026-10-10 裁定：聊天卡 diff 不再在气泡内联展开——点展开/具体文件 → 在 Canvas
// 打开本视图；多文件 = 左侧文件导航 + 右侧 diff 阅读区的分栏形态).
//
// 复用既有 canvas diff 渲染模式：diffRender.js 的 parseUnifiedDiff / diffRowHtml
// （与 Flow Map 节点详情同一渲染单源），样式沿用 diffView.css 的 .nf-* 行类，
// 新增的分栏容器样式 (.fcv-*) 只用既有 --color-* 变量（零新色值）。
//
// 超大 diff 的性能兜底（无硬截断）：
//   ① 分块渐进渲染 —— 每帧最多 CHUNK_ROWS 行，rAF 排空为止，全量内容必达
//      （不冻结主线程；200k 行 diff 也只是多排几帧）；
//   ② 切换文件即卸载上一文件的全部行节点（DOM 有界）；
//   ③ 懒加载 —— 只渲染当前聚焦文件的 diff，其余文件在导航中按需点开。

import { openTab } from './canvas.js';
import { esc, parseUnifiedDiff, diffRowHtml } from './diffRender.js';

/** Rows mounted per animation frame while a (huge) diff body renders in. */
const CHUNK_ROWS = 4000;

/** pane → render generation; a newer selection invalidates in-flight chunks. */
const renderGen = new WeakMap();

function totalsOf(payload) {
  return (payload?.files || []).reduce((acc, f) => {
    acc.a += Number(f?.added) || 0;
    acc.r += Number(f?.removed) || 0;
    return acc;
  }, { a: 0, r: 0 });
}

/** Chunked, cancelable full render of one unified diff body into `host`. */
function renderDiffChunked(host, diffText) {
  const rows = parseUnifiedDiff(diffText);
  const wrap = document.createElement('div');
  wrap.className = 'nf-diff';
  const body = document.createElement('pre');
  body.className = 'nf-diff-body';
  wrap.appendChild(body);
  host.appendChild(wrap);
  const gen = (renderGen.get(host) || 0) + 1;
  renderGen.set(host, gen);
  let i = 0;
  const step = () => {
    if (renderGen.get(host) !== gen) return; // superseded by a newer selection
    const frag = document.createDocumentFragment();
    const end = Math.min(i + CHUNK_ROWS, rows.length);
    for (; i < end; i++) {
      const tpl = document.createElement('template');
      tpl.innerHTML = diffRowHtml(rows[i]);
      frag.appendChild(tpl.content.firstElementChild);
    }
    body.appendChild(frag);
    if (i < rows.length) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

/** Right-hand pane for one file entry: file head + full diff (or reason row). */
function renderFileView(view, f) {
  const gen = (renderGen.get(view) || 0) + 1;
  renderGen.set(view, gen); // invalidate any in-flight chunked render
  view.innerHTML = '';
  const path = String(f?.path || '(unknown)');
  const added = Number(f?.added) || 0;
  const removed = Number(f?.removed) || 0;
  const head = document.createElement('div');
  head.className = 'fcv-filehead';
  head.innerHTML = `<span class="nf-fc-path" title="${esc(path)}">${esc(path)}</span>` +
    `<span class="nf-fc-counts"><span class="nf-fc-added">+${added}</span> <span class="nf-fc-removed">−${removed}</span></span>` +
    (f?.source ? `<span class="nf-fc-src" title="diff source">${esc(String(f.source))}</span>` : '');
  view.appendChild(head);
  if (typeof f?.diffText === 'string' && f.diffText.length > 0) {
    renderDiffChunked(view, f.diffText);
  } else if (f?.diffText === '') {
    const empty = document.createElement('div');
    empty.className = 'nf-fc-empty';
    empty.textContent = 'Empty file — no content lines.';
    view.appendChild(empty);
  } else {
    const note = document.createElement('div');
    note.className = 'nf-fc-empty';
    note.textContent = f?.degradedReason
      ? `Diff not shown (${String(f.degradedReason)}).`
      : 'Diff not available.';
    view.appendChild(note);
  }
}

/** Build (or rebuild) the split view inside an open pane. */
function renderPane(pane, payload, focusIndex) {
  const files = Array.isArray(payload?.files) ? payload.files : [];
  const tot = totalsOf(payload);
  pane.innerHTML = `
    <div class="fcv">
      <div class="fcv-head">
        <span class="fcv-head-title">Changes · ${files.length} file${files.length === 1 ? '' : 's'} <span class="nf-fc-counts"><span class="nf-fc-added">+${tot.a}</span> <span class="nf-fc-removed">−${tot.r}</span></span></span>
        ${payload?.root ? `<span class="fcv-head-root" title="${esc(String(payload.root))}">${esc(String(payload.root))}</span>` : ''}
      </div>
      <div class="fcv-body">
        <div class="fcv-nav" role="listbox" aria-label="changed files"></div>
        <div class="fcv-view"></div>
      </div>
    </div>`;
  const nav = pane.querySelector('.fcv-nav');
  const view = pane.querySelector('.fcv-view');
  const focus = Math.min(Math.max(Number(focusIndex) || 0, 0), Math.max(files.length - 1, 0));
  const select = (idx) => {
    nav.querySelectorAll('.nf-fc').forEach((el, i) => el.classList.toggle('active', i === idx));
    renderFileView(view, files[idx]);
  };
  files.forEach((f, i) => {
    const added = Number(f?.added) || 0;
    const removed = Number(f?.removed) || 0;
    const row = document.createElement('div');
    row.className = 'nf-fc nf-fc-row';
    row.setAttribute('role', 'option');
    row.setAttribute('tabindex', '0');
    row.innerHTML =
      (f?.changeType ? `<span class="nf-fc-badge nf-fc-badge-${esc(String(f.changeType))}">${esc(String(f.changeType))}</span>` : '') +
      `<span class="nf-fc-path" title="${esc(String(f?.path || ''))}">${esc(String(f?.path || '(unknown)'))}</span>` +
      `<span class="nf-fc-counts"><span class="nf-fc-added">+${added}</span> <span class="nf-fc-removed">−${removed}</span></span>` +
      (f?.source ? `<span class="nf-fc-src" title="diff source">${esc(String(f.source))}</span>` : '');
    const open = () => select(i);
    row.addEventListener('click', open);
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    });
    nav.appendChild(row);
  });
  if (files.length) select(focus);
}

/**
 * Open the batch's diff view as a Canvas tab (panel auto-opens).
 * Re-clicking the SAME batch reuses its tab (openTab dedupes by id) and
 * re-renders; a NEW batch's tab replaces the previous preview tab (same
 * preview group 'filechanges' — VS Code behavior, no tab litter).
 * @param {object} payload fileChanges wire shape {nodeId, generatedAt, root, files[]}
 * @param {{focusIndex?: number}} [opts] focus this file index on open (default 0)
 * @returns {object|null} the canvas tab entry
 */
export function openFileChangesCanvas(payload, opts = {}) {
  const files = Array.isArray(payload?.files) ? payload.files : [];
  if (!files.length) return null;
  const id = `filechanges:${payload.nodeId || payload.generatedAt || 'adhoc'}`;
  const title = `Diff · ${files.length} file${files.length === 1 ? '' : 's'}`;
  const entry = openTab(id, esc(title), { type: 'filechanges', closable: true });
  if (!entry) return null;
  renderPane(entry.paneEl, payload, opts.focusIndex);
  return entry;
}
