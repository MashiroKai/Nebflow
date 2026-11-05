// flowViewers.js — stable overlay shell (the only surviving piece of the
// legacy viewer family).
//
// All viewers mount on the stable overlay root (survives renderAll re-renders).
//
// 团队功能删除批（team-feature-deletion-20261104）阶段 3：本件原有 6 个
// **零 import 孤儿函数**（`openMailbox` / `refreshMailboxPending` +
// mailboxCtx/pendingRowHtml/loadPendingSection、`openRules`（团队 rules 编辑器）、
// `openDefinition` + populateAgentModel/renderAgentModelSection、
// `openNodeResultViewer`、`mailBodyHtml`、`closeViewer` 的对外导出）全部随团队面
// 退役删除——web/ 下 0 处调用（`openRules` 撞名的 `openRulesEditor` 是
// sidebar/pathRules.js 里的另一个函数）。**`openViewerShell` 保留**：它是唯一
// 被外部 import 的一个（`agentFileViewer.js` 复用 overlay 语义）。

import { esc, overlayRoot } from './flowHelpers.js';

// ── 内联 SVG/CSS 图标（2026-09-06 显示优化批：legacy viewer 域禁 emoji/符号字符）──
// 关闭叉（替代 ✕ U+2715）。阻塞旗 / 收件箭头 / 展开 chevron 三个随删除的
// 函数族一并移除（它们只被已删的 openMailbox / openNodeResultViewer 使用）。
const FV_CLOSE_SVG = '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6"/></svg>';

/** Tear down any mounted overlay (single-overlay invariant: a new shell
 *  replaces the old one). Internal — the shell is the only public entry. */
function closeViewer() {
  const existing = overlayRoot().querySelector('.flow-viewer-overlay');
  if (existing) existing.remove();
}

export function openViewerShell(title, opts = {}) {
  closeViewer();
  const overlay = document.createElement('div');
  overlay.className = 'flow-viewer-overlay';
  const footerHtml = opts.footer ? `<div class="flow-viewer-footer">${opts.footer}</div>` : '';
  overlay.innerHTML = `
    <div class="flow-viewer">
      <div class="flow-viewer-header">
        <span class="flow-viewer-title">${esc(title)}</span>
        <span class="flow-viewer-close" id="flow-viewer-close">${FV_CLOSE_SVG}</span>
      </div>
      <div class="flow-viewer-body" id="flow-viewer-body"></div>
      ${footerHtml}
    </div>`;
  overlayRoot().appendChild(overlay);
  overlay.addEventListener('click', (e) => {
    // 关闭钮内容已 SVG 化：命中 svg/path 子节点也要关（closest 上行替代 id 相等判定）
    if (e.target === overlay || (e.target instanceof Element && e.target.closest('#flow-viewer-close'))) closeViewer();
  });
  return overlay.querySelector('#flow-viewer-body');
}
