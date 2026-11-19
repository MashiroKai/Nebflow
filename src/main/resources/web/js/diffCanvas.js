// diffCanvas.js — Canvas diff reader for a delegate batch's fileChanges payload
// (2026-10-10 裁定：聊天卡 diff 不再在气泡内联展开——点开 → 在 Canvas 打开；
// 2026-10-22 拆面板批（作者令「摘要上移+左右分体」）：旧 master-detail 一体大
// 面板（.fcv 分栏 = 左文件导航 + 右 diff）退役——文件列表迁往左栏
// #panel-filechanges（diffPanel.js），Canvas 只渲染**选中文件**的 diff 阅读面).
//
// 复用既有 canvas diff 渲染模式：diffRender.js 的 parseUnifiedDiff / diffRowHtml
// （与 Flow Map 节点详情同一渲染单源），样式沿用 diffView.css 的 .nf-* 行类与
// .fcv-view/.fcv-filehead（sticky 定心层 + .fcv-headpill 居中胶囊，2026-10-22
// 作者三令）阅读面容器（只用既有 --color-* / glass token，零新色值）。
//
// 超大 diff 的性能兜底（无硬截断）：
//   ① 分块渐进渲染 —— 每帧最多 CHUNK_ROWS 行，rAF 排空为止，全量内容必达
//      （不冻结主线程；200k 行 diff 也只是多排几帧）；
//   ② 切换文件即卸载上一文件的全部行节点（DOM 有界）；
//   ③ 懒加载 —— 只渲染当前聚焦文件的 diff。

import { openTab, setActiveTab } from './canvas.js';
import { esc } from './diffRender.js';
import { t } from './i18n.js';
import { mountDiff } from './diffView/index.js';

/** pane → render generation; a newer selection invalidates in-flight mounts. */
const renderGen = new WeakMap();

/** Basename for the canvas card title (path's last segment; '' for empty). */
function baseName(path) {
  const p = String(path || '');
  const i = p.lastIndexOf('/');
  return i >= 0 ? p.slice(i + 1) : p;
}

/** Mount the shared diff component (js/diffView) for one unified diff body.
 *  迁移批 2026-11-19（作者裁定「手机diff 按推荐」）：自研**按帧分块渲染器
 *  退役**，改调共享组件 —— 两个差异面单源（硬不变式①）；行号/词级/折叠/虚拟化
 *  由组件底座（CodeMirror 6 + @codemirror/merge）承接（D2/D3/D8/D9），VCS 元信息
 *  在组件的统一差异转换期丢弃（D8）。装载态在**首次调用帧同步落地**（点击 →
 *  首帧必有响应面，作者令②不变），组件就位后移除；失败进可见错误面（禁静默）。 */
function renderDiffShared(host, diffText) {
  const loading = document.createElement('div');
  loading.className = 'nf-diff-loading';
  loading.textContent = t('panel.diffRendering');
  host.appendChild(loading);
  mountDiff(host, { diffText: String(diffText ?? ''), mode: 'read' })
    .then(() => { loading.remove(); })
    .catch((err) => {
      loading.remove();
      const fail = document.createElement('div');
      fail.className = 'nf-fc-empty';
      fail.setAttribute('data-cv-hint', 'empty');
      fail.setAttribute('data-diff-mount-fail', '1');
      fail.textContent = `Diff not available (${String((err && err.message) || err)}).`;
      host.appendChild(fail);
    });
}

/** Ensure the pane carries the reader shell (single .fcv-view column, created
 *  once per tab so in-flight render generations stay cancelable across file
 *  switches). */
function ensureReaderShell(pane) {
  let view = /** @type {HTMLElement | null} */ (pane.querySelector('.fcv-view'));
  if (!view) {
    pane.innerHTML = '';
    view = document.createElement('div');
    view.className = 'fcv-view';
    pane.appendChild(view);
  }
  return view;
}

/** Reader body for one batch: file head + full diff (or reason row) for the
 *  focused file; on the mobile layer (mobileLayer.css retires #sidebar — the
 *  left panel has no surface there) the reader also carries a horizontal file
 *  strip so switching stays reachable. Re-renders IN PLACE — the .fcv-view
 *  shell persists so the render-generation guard keeps canceling superseded
 *  chunked renders. */
function renderReader(pane, payload, focus) {
  const files = Array.isArray(payload?.files) ? payload.files : [];
  const view = ensureReaderShell(pane);
  const gen = (renderGen.get(view) || 0) + 1;
  renderGen.set(view, gen); // invalidate any in-flight chunked render
  view.innerHTML = '';
  const f = files[focus] || files[0];
  const path = String(f?.path || '(unknown)');
  const added = Number(f?.added) || 0;
  const removed = Number(f?.removed) || 0;
  // 粘顶文件头 = 全宽 sticky 定心层（.fcv-filehead）+ 居中玻璃胶囊
  // （.fcv-headpill，diffView.css §文件头胶囊批 2026-10-22 作者三令：
  //   居中 + 全量 frosted token + 动画节奏）。文字断言面不变：
  //   .fcv-filehead 仍 contains 路径/+/−（胶囊为其子节点）。
  const head = document.createElement('div');
  head.className = 'fcv-filehead';
  head.innerHTML = `<div class="fcv-headpill">` +
    `<span class="nf-fc-path" title="${esc(path)}">${esc(path)}</span>` +
    `<span class="nf-fc-counts"><span class="nf-fc-added">+${added}</span> <span class="nf-fc-removed">−${removed}</span></span>` +
    (f?.source ? `<span class="nf-fc-src" title="diff source">${esc(String(f.source))}</span>` : '') +
    `</div>`;
  view.appendChild(head);
  // 手机层文件条（桌面不渲染——左栏 #panel-filechanges 承载切换）。
  if (document.body.classList.contains('mobile-layer') && files.length > 1) {
    const strip = document.createElement('div');
    strip.className = 'fcv-filestrip';
    strip.setAttribute('role', 'listbox');
    strip.setAttribute('aria-label', 'changed files');
    files.forEach((file, i) => {
      const row = document.createElement('div');
      row.className = 'nf-fc nf-fc-row' + (i === focus ? ' active' : '');
      row.setAttribute('role', 'option');
      row.setAttribute('tabindex', '0');
      row.setAttribute('aria-selected', i === focus ? 'true' : 'false');
      row.innerHTML =
        (file?.changeType ? `<span class="nf-fc-badge nf-fc-badge-${esc(String(file.changeType))}">${esc(String(file.changeType))}</span>` : '') +
        `<span class="nf-fc-path" title="${esc(String(file?.path || ''))}">${esc(String(file?.path || '(unknown)'))}</span>` +
        `<span class="nf-fc-counts"><span class="nf-fc-added">+${Number(file?.added) || 0}</span> <span class="nf-fc-removed">−${Number(file?.removed) || 0}</span></span>`;
      const open = () => { openFileChangesCanvas(payload, { focusIndex: i }); };
      row.addEventListener('click', open);
      row.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
      });
      strip.appendChild(row);
    });
    view.appendChild(strip);
  }
  if (typeof f?.diffText === 'string' && f.diffText.length > 0) {
    // 共享组件的挂载宿主：.fcv-view（= 文件头 + 文件条 + 组件）为弹性列，滚动由
    // 组件底座（CodeMirror .cm-scroller）自持 —— 虚拟化要在编辑面自己手里才成立
    // （D9），外层容器因此不再滚（见 diffView.css `.fcv-view` 段注）。
    const host = document.createElement('div');
    host.className = 'fcv-dvhost';
    view.appendChild(host);
    renderDiffShared(host, f.diffText);
  } else if (f?.diffText === '') {
    const empty = document.createElement('div');
    empty.className = 'nf-fc-empty';
    empty.setAttribute('data-cv-hint', 'empty');
    empty.textContent = 'Empty file — no content lines.';
    view.appendChild(empty);
  } else {
    const note = document.createElement('div');
    note.className = 'nf-fc-empty';
    note.setAttribute('data-cv-hint', 'empty');
    note.textContent = f?.degradedReason
      ? `Diff not shown (${String(f.degradedReason)}).`
      : 'Diff not available.';
    view.appendChild(note);
  }
}

/**
 * Open the batch's focused file as a Canvas diff reader (panel auto-opens).
 * Re-clicking the SAME batch (left-panel row switch / another chip click)
 * reuses its tab (openTab dedupes by id) and re-renders in place; a NEW
 * batch's tab replaces the previous preview tab (same preview group
 * 'filechanges' — VS Code behavior, no tab litter). The card title follows
 * the focused file's basename.
 * @param {object} payload fileChanges wire shape {nodeId, generatedAt, root, files[]}
 * @param {{focusIndex?: number}} [opts] focus this file index on open (default 0)
 * @returns {object|null} the canvas tab entry
 */
export function openFileChangesCanvas(payload, opts = {}) {
  const files = Array.isArray(payload?.files) ? payload.files : [];
  if (!files.length) return null;
  const focus = Math.min(Math.max(Number(opts.focusIndex) || 0, 0), files.length - 1);
  const f = files[focus];
  const id = `filechanges:${payload.nodeId || payload.generatedAt || 'adhoc'}`;
  const title = baseName(f?.path) || `Diff · ${files.length} file${files.length === 1 ? '' : 's'}`;
  const entry = openTab(id, esc(title), { type: 'filechanges', closable: true });
  if (!entry) return null;
  // Refocus on a reused tab: sync the card title to the newly focused file
  // (openTab's dedupe branch ignores its title argument), then re-activate so
  // the centered header re-renders with the fresh title.
  if (entry.title !== title) {
    entry.title = title;
    const label = entry.tabEl ? entry.tabEl.querySelector('.canvas-tab-label') : null;
    if (label) label.textContent = title;
    setActiveTab(id);
  }
  renderReader(entry.paneEl, payload, focus);
  return entry;
}
