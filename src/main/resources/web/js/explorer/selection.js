// explorer.js 拆分(FE组件化批次五 2026-09-28):选择状态族 可复用模块(行为保持)。
// 原 explorer.js :66-:213 多选自模型段。簇私态 selectedPaths/anchorPath 整态随迁
// (外部主体仅只读方法调用,不直接赋值);currentDir(:61)/loadingDirs(:64) 属渲染
// 与加载主体、被主体直接赋值,留守 explorer.js,不随本簇迁出。
import { t } from '../i18n.js';
// ui-status-redesign（S10 / 盘点 #47, A 侧 explorer.js 原 :197-:198 改动随簇迁入）：
// 删除钮字转常规色，行首 trash-2 红图标承载。
import { statusIcon, escapeHtml } from '../utils.js';

// 主体侧回调注入(拆分批次纪律):getTargetDir/deleteSelected 留守 explorer.js 主体,
// 子模块不得反向 import 之;rangeSelect/updateSelectionBar 所需的主体函数由主体在
// 模块求值期经 injectSelectionHostFns 注入(见 explorer.js 的注入调用点)。
let getTargetDir = null;
let deleteSelected = null;
function injectSelectionHostFns(deps) { getTargetDir = deps.getTargetDir; deleteSelected = deps.deleteSelected; }

// ── Selection model (multi-select) ──────────────────────────────────────

/** Selected item paths (relative to explorer root). */
export const selectedPaths = new Set();
/** Anchor path for shift-range selection (last plain-clicked / cmd-added item). */
let anchorPath = null;

/** Re-apply `.selected` classes to match selectedPaths (after render/refresh). */
export function applySelectionClasses() {
  const tree = document.getElementById('explorer-tree');
  if (!tree) return;
  tree.querySelectorAll('.explorer-item.selected').forEach(el => {
    if (!selectedPaths.has(el.dataset.path)) el.classList.remove('selected');
  });
  for (const p of selectedPaths) {
    const el = tree.querySelector(`.explorer-item[data-path="${CSS.escape(p)}"]`);
    if (el) el.classList.add('selected');
  }
}

/** Replace selection with a single path and make it the anchor. */
export function selectSingle(path) {
  selectedPaths.clear();
  selectedPaths.add(path);
  anchorPath = path;
  applySelectionClasses();
  updateSelectionBar();
}

/** Cmd/Ctrl+click — toggle path in/out of the selection. */
function toggleSelection(path) {
  if (selectedPaths.has(path)) {
    selectedPaths.delete(path);
    if (anchorPath === path) anchorPath = null;
  } else {
    selectedPaths.add(path);
    anchorPath = path;
  }
  applySelectionClasses();
  updateSelectionBar();
}

/** Ordered visible sibling rows of a directory's children container. */
function getSiblingPaths(parentDir) {
  const container = parentDir
    ? document.querySelector(`.explorer-dir-wrapper[data-path="${CSS.escape(parentDir)}"] > .explorer-children`)
    : document.querySelector('.explorer-root > .explorer-children');
  if (!container) return null;
  const out = [];
  for (const child of container.children) {
    const item = child.classList.contains('explorer-item')
      ? child
      : child.querySelector(':scope > .explorer-item');
    if (item && item.dataset.path !== undefined) out.push(item.dataset.path);
  }
  return out;
}

/** Shift+click — range-select visible siblings from anchor to target.
 *  Cross-level (different parent dir) degenerates to single-select of target. */
function rangeSelect(from, to) {
  if (getTargetDir(from) !== getTargetDir(to)) {
    selectSingle(to);
    return;
  }
  const sibs = getSiblingPaths(getTargetDir(to));
  const i = sibs ? sibs.indexOf(from) : -1;
  const j = sibs ? sibs.indexOf(to) : -1;
  if (i === -1 || j === -1) {
    selectSingle(to);
    return;
  }
  selectedPaths.clear();
  for (let k = Math.min(i, j); k <= Math.max(i, j); k++) selectedPaths.add(sibs[k]);
  applySelectionClasses();
  updateSelectionBar();
}

/** Clear selection + anchor (Esc, blank click, root/session switch). */
export function clearSelection() {
  if (!selectedPaths.size && !anchorPath) return;
  selectedPaths.clear();
  anchorPath = null;
  applySelectionClasses();
  updateSelectionBar();
}

/** Remove a deleted path from the selection model. */
export function pruneSelection(path) {
  const had = selectedPaths.delete(path);
  if (anchorPath === path) anchorPath = null;
  if (had) {
    applySelectionClasses();
    updateSelectionBar();
  }
}

/** Floating action bar (appears when ≥2 items selected), pinned to tree top. */
function updateSelectionBar() {
  const section = document.getElementById('explorer-section');
  if (!section) return;
  let bar = document.getElementById('explorer-selection-bar');
  if (selectedPaths.size < 2) {
    if (bar) bar.remove();
    return;
  }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'explorer-selection-bar';
    bar.className = 'explorer-selection-bar';

    const count = document.createElement('span');
    count.className = 'explorer-selection-count';

    const delBtn = document.createElement('button');
    delBtn.className = 'explorer-selection-delete';
    // ui-status-redesign（S10 / 盘点 #47）：字转常规色，行首 trash-2 红图标承载。
    delBtn.innerHTML = statusIcon('trash-2', 12, 'st-err') + escapeHtml(t('explorer.delete'));
    delBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteSelected();
    });

    bar.appendChild(count);
    bar.appendChild(delBtn);
    section.appendChild(bar);
  }
  bar.querySelector('.explorer-selection-count').textContent =
    t('explorer.selectedCount', { count: selectedPaths.size });
}

/** Shared click routing for tree rows.
 *  @returns {boolean} true if the click was consumed as a selection op
 *                     (caller must NOT run its default open/toggle behavior). */
export function handleSelectClick(e, path) {
  if (e.metaKey || e.ctrlKey) {
    e.preventDefault();
    toggleSelection(path);
    return true;
  }
  if (e.shiftKey && anchorPath && anchorPath !== path) {
    e.preventDefault();
    rangeSelect(anchorPath, path);
    return true;
  }
  // Plain click — single-select + anchor; default behavior continues.
  selectSingle(path);
  return false;
}

export { injectSelectionHostFns };
