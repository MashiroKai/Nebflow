// explorer.js — VS Code-style file tree in the sidebar.
//
// Lazy-loads directories via `listDir` WS message. Clicking a file sends
// `readFile`, opens the content as a Canvas tab (markdown/code/html).
// Expand/collapse state is kept in-memory (not persisted).
// Folder picker button in the header opens the path picker modal.

import state from './state.js';
import { key } from './branding.js';
import { sendWs, onMessage } from './ws.js';
import { openPathPickerCallback } from './sidebar.js';
import { createIconsIn } from './utils.js';
import { t } from './i18n.js';
import { makeReference } from './reference.js';
import { appendRefToActiveView } from './input.js';

// ── State ──────────────────────────────────────────────────────────────

/** Explorer root path (absolute). null = default ~/.nebflow/projects/.
 *  Persisted to localStorage so it survives restarts. */
let explorerRoot = null;
const EXPLORER_ROOT_KEY = key('explorer_root');

function loadPersistedRoot() {
  try {
    const saved = localStorage.getItem(EXPLORER_ROOT_KEY);
    return saved || null;
  } catch (_) { return null; }
}

function persistRoot(path) {
  explorerRoot = path;
  try {
    if (path) localStorage.setItem(EXPLORER_ROOT_KEY, path);
    else localStorage.removeItem(EXPLORER_ROOT_KEY);
  } catch (_) {}
  updateExplorerTitle();
}

function updateExplorerTitle() {
  const titleEl = document.querySelector('.explorer-title');
  if (!titleEl) return;
  if (explorerRoot) {
    const name = explorerRoot.split('/').pop() || explorerRoot;
    titleEl.textContent = name;
    titleEl.title = explorerRoot;
  } else {
    titleEl.textContent = t('panel.explorer');
    titleEl.title = '';
  }
}

/** Expanded directory paths (relative to explorer root). */
const expandedDirs = new Set();

/** Current working directory for new file creation. Updated when a folder is
 *  expanded or a file is opened. The "New File" button creates here. */
let currentDir = '';

/** Loading indicator set — prevents double-fetching the same dir. */
const loadingDirs = new Set();

// ── Selection model (multi-select) ──────────────────────────────────────

/** Selected item paths (relative to explorer root). */
const selectedPaths = new Set();
/** Anchor path for shift-range selection (last plain-clicked / cmd-added item). */
let anchorPath = null;

/** Re-apply `.selected` classes to match selectedPaths (after render/refresh). */
function applySelectionClasses() {
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
function selectSingle(path) {
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
function clearSelection() {
  if (!selectedPaths.size && !anchorPath) return;
  selectedPaths.clear();
  anchorPath = null;
  applySelectionClasses();
  updateSelectionBar();
}

/** Remove a deleted path from the selection model. */
function pruneSelection(path) {
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
    delBtn.textContent = t('explorer.delete');
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
function handleSelectClick(e, path) {
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

// ── Drag-to-move (VS Code-style) ──────────────────────────────────────
// Files drag with the existing 'application/x-nebflow-file' MIME (shared with
// the input-bar attach flow); dirs drag with a dedicated dir MIME so the input
// bar never treats a folder as an attachment. Drop targets are folder rows and
// the blank area of the root container. The actual move is a backend `movePath`
// op answered by `pathMoved` / `fileOpError`.

const DIR_DRAG_MIME = 'application/x-nebflow-dir';

/** Current in-tree drag payload: { path, isDir } | null. Set on dragstart,
 *  cleared on dragend — dataTransfer data is unreadable during dragover. */
let dragMoveSrc = null;

/** Validate dropping dragMoveSrc onto targetDir (explorer-relative, '' = root).
 *  Guards: no src, drop onto itself, into its own descendant (cycle), and
 *  same-parent no-ops. */
function isValidDropTarget(srcPath, targetDir) {
  if (!srcPath) return false;
  if (targetDir === srcPath) return false;                          // onto itself
  if (targetDir.startsWith(srcPath + '/')) return false;            // own subtree
  if (getTargetDir(srcPath) === targetDir) return false;            // same parent
  return true;
}

/** Row currently highlighted as a drop target (cleared on drop/dragend/leave). */
let dropTargetEl = null;
function setDropTarget(el, valid = false) {
  if (dropTargetEl === el && (!el || el.classList.contains(valid ? 'drop-target' : 'drop-invalid'))) return;
  clearDropTarget();
  if (!el) return;
  el.classList.add(valid ? 'drop-target' : 'drop-invalid');
  dropTargetEl = el;
}
function clearDropTarget() {
  if (dropTargetEl) {
    dropTargetEl.classList.remove('drop-target', 'drop-invalid');
    dropTargetEl = null;
  }
}

/** Resolve the drop-target directory for a drag event over the tree:
 *  a folder row → that dir; blank root area / root container / bottom margin → ''.
 *  null = not a drop zone. */
function dropDirForEvent(e) {
  const folder = e.target instanceof Element
    ? /** @type {HTMLElement|null} */ (e.target.closest('.explorer-item.explorer-folder'))
    : null;
  if (folder && folder.dataset.path !== undefined) return folder.dataset.path;
  const t = e.target;
  if (!(t instanceof Element)) return null;
  if (t.closest('.explorer-item')) return null;   // on a file (non-folder) row → not a zone
  // #303 E1/E3: root container, its children's blank area, or the bottom margin
  // are all root drop zones — return '' (movePath targetDir:'' = project root).
  if (t.closest('.explorer-root, .explorer-root-drop-margin')) return '';
  return null;
}

function bindTreeDragMove(tree) {
  tree.addEventListener('dragover', (e) => {
    if (!dragMoveSrc) return;
    const dir = dropDirForEvent(e);
    if (dir === null) { setDropTarget(null); return; }
    const valid = isValidDropTarget(dragMoveSrc.path, dir);
    // stopPropagation: keep the input-bar's document-level dragover from
    // overriding dropEffect for file-MIME drags inside the tree.
    e.stopPropagation();
    const zone = dir
      ? (e.target instanceof Element ? e.target.closest('.explorer-item.explorer-folder') : null)
      : tree.querySelector('.explorer-root');
    if (!valid) { setDropTarget(zone, false); return; }
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDropTarget(zone, true);
  });

  tree.addEventListener('dragleave', (e) => {
    if (!dragMoveSrc) return;
    // Leaving the tree entirely → clear highlight (child-element transitions
    // keep relatedTarget inside the tree and are ignored).
    if (!tree.contains(e.relatedTarget)) setDropTarget(null);
  });

  tree.addEventListener('drop', (e) => {
    if (!dragMoveSrc) return;
    const dir = dropDirForEvent(e);
    const src = dragMoveSrc;
    setDropTarget(null);
    if (dir === null || !isValidDropTarget(src.path, dir)) return;
    e.preventDefault();
    e.stopPropagation();
    sendWs({ type: 'movePath', sessionId: state.activeSessionId, path: src.path, targetDir: dir, rootPath: explorerRoot });
  });
}

/** Read dragMoveSrc into a dragstart payload + visual state. */
function startRowDrag(e, node, path, isDir) {
  dragMoveSrc = { path, isDir };
  if (isDir) {
    e.dataTransfer.setData(DIR_DRAG_MIME, JSON.stringify({ path, rootPath: explorerRoot || '' }));
    e.dataTransfer.effectAllowed = 'move';
  } else {
    // Same payload the input-bar attach flow consumes (#303).
    e.dataTransfer.setData('application/x-nebflow-file',
      JSON.stringify({ path, rootPath: explorerRoot || '' }));
    e.dataTransfer.effectAllowed = 'copyMove';   // copy → input bar, move → tree
  }
  node.classList.add('dragging');
}

// ── Helpers ────────────────────────────────────────────────────────────

function $(sel) { return document.querySelector(sel); }

/** File extension → lucide icon name + color class. */
const FILE_ICONS = {
  scala: { icon: 'file-code', cls: 'f-scala' },
  java:  { icon: 'file-code', cls: 'f-java' },
  py:    { icon: 'file-code', cls: 'f-py' },
  js:    { icon: 'file-code', cls: 'f-js' },
  ts:    { icon: 'file-code', cls: 'f-ts' },
  css:   { icon: 'file-code', cls: 'f-css' },
  html:  { icon: 'file-code', cls: 'f-html' },
  json:  { icon: 'braces',    cls: 'f-json' },
  yaml:  { icon: 'file-text', cls: 'f-yaml' },
  yml:   { icon: 'file-text', cls: 'f-yaml' },
  md:    { icon: 'file-text', cls: 'f-md' },
  xml:   { icon: 'file-code', cls: 'f-xml' },
  sql:   { icon: 'database',  cls: 'f-sql' },
  sh:    { icon: 'terminal',  cls: 'f-sh' },
};

/** Directories to hide in the tree (build artifacts, VCS, etc.). */
const HIDDEN_DIRS = new Set([
  '.git', '.svn', 'target', 'node_modules', 'dist', 'build',
  '.gradle', '.idea', '.vscode', '__pycache__', '.cache',
  '.meta', 'DerivedData',
]);

function getFileInfo(name) {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  return FILE_ICONS[ext] || { icon: 'file', cls: 'f-default' };
}

function shouldHide(name, isDir) {
  return isDir && HIDDEN_DIRS.has(name);
}

// ── Render ─────────────────────────────────────────────────────────────

function renderTree() {
  const body = $('#explorer-tree');
  if (!body) return;
  clearSelection();
  body.innerHTML = '';
  // Root-level listing — path "" means project root
  body.appendChild(buildDirNode('', true, 0));
}

function buildDirNode(path, isRoot, depth) {
  const wrapper = document.createElement('div');
  wrapper.className = 'explorer-dir-wrapper';
  wrapper.dataset.path = path;

  // For root, we don't render a folder row — just load children inline.
  if (isRoot) {
    wrapper.classList.add('explorer-root');
    const children = document.createElement('div');
    children.className = 'explorer-children';
    wrapper.appendChild(children);
    // #303 E3: constant blank bottom drop area — so a full root list still has a
    // visible "drop here to move to root" landing zone.
    const dropMargin = document.createElement('div');
    dropMargin.className = 'explorer-root-drop-margin';
    wrapper.appendChild(dropMargin);
    loadDir(path, children, depth);
    return wrapper;
  }

  const name = path.split('/').pop();
  const row = document.createElement('div');
  row.className = 'explorer-item explorer-folder';
  row.dataset.path = path;
  row.style.paddingLeft = `${depth * 14 + 8}px`;

  const chevron = document.createElement('span');
  chevron.className = 'explorer-chevron';
  chevron.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M9 6l6 6-6 6"/></svg>';

  const icon = document.createElement('span');
  icon.className = 'explorer-icon';
  icon.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"/></svg>';

  const label = document.createElement('span');
  label.className = 'explorer-label';
  label.textContent = name;

  row.appendChild(chevron);
  row.appendChild(icon);
  row.appendChild(label);

  // Drag-to-move: folder rows are draggable onto other folders (or root).
  // The drop targets are bound once on the tree container (bindTreeDragMove).
  row.draggable = true;
  row.addEventListener('dragstart', (e) => {
    e.stopPropagation();
    startRowDrag(e, row, path, true);
  });
  row.addEventListener('dragend', () => {
    row.classList.remove('dragging');
    dragMoveSrc = null;
    clearDropTarget();
  });

  const children = document.createElement('div');
  children.className = 'explorer-children';
  children.style.display = 'none';

  row.addEventListener('click', (e) => {
    e.stopPropagation();
    // Cmd/Ctrl or Shift click = selection only — never expand/collapse
    if (handleSelectClick(e, path)) return;
    toggleDir(path, chevron, children, depth);
  });

  wrapper.appendChild(row);
  wrapper.appendChild(children);
  return wrapper;
}

function buildFileNode(path, depth) {
  const name = path.split('/').pop();
  const node = document.createElement('div');
  node.className = 'explorer-item explorer-file';
  node.dataset.path = path;
  node.style.paddingLeft = `${depth * 14 + 22}px`; // extra indent for chevron space

  const info = getFileInfo(name);
  const icon = document.createElement('span');
  icon.className = `explorer-icon explorer-file-icon ${info.cls}`;
  icon.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>`;

  const label = document.createElement('span');
  label.className = 'explorer-label';
  label.textContent = name;

  node.appendChild(icon);
  node.appendChild(label);

  // #303 internal drag: FILE rows are draggable into a chat input bar (copy)
  // AND onto tree folders (move, VS Code-style). The payload carries the
  // explorer-relative path + root; input.js consumes the copy channel in
  // initGlobalFileDrop, the tree consumes the move channel (bindTreeDragMove).
  node.draggable = true;
  node.addEventListener('dragstart', (e) => {
    e.stopPropagation();
    startRowDrag(e, node, path, false);
  });
  node.addEventListener('dragend', () => {
    node.classList.remove('dragging');
    dragMoveSrc = null;
    clearDropTarget();
  });

  // Single click → open as preview tab (italic, temporary)
  // Cmd/Ctrl or Shift click = selection only — no open
  node.addEventListener('click', (e) => {
    e.stopPropagation();
    if (handleSelectClick(e, path)) return;
    openFile(path, name);
  });

  // Double-click → promote to pinned (permanent tab)
  node.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    e.preventDefault();
    const tabId = `file:${path}`;
    import('./canvas.js').then(({ pinTab, hasTab }) => {
      if (hasTab(tabId)) {
        // Already open — promote from preview to pinned
        pinTab(tabId);
      } else {
        // Not open yet — open directly as pinned
        openFile(path, name, true);
      }
    });
  });

  return node;
}

// ── Actions ────────────────────────────────────────────────────────────

function toggleDir(path, chevronEl, childrenEl, depth) {
  if (expandedDirs.has(path)) {
    // Collapse
    expandedDirs.delete(path);
    chevronEl.classList.remove('expanded');
    childrenEl.style.display = 'none';
  } else {
    // Expand — always reload to pick up file system changes
    expandedDirs.add(path);
    currentDir = path; // track active folder for "New File" button
    chevronEl.classList.add('expanded');
    childrenEl.style.display = '';
    childrenEl.innerHTML = '';
    loadDir(path, childrenEl, depth);
  }
}

function loadDir(path, container, depth) {
  if (loadingDirs.has(path)) return;
  loadingDirs.add(path);

  // Loading indicator
  const loading = document.createElement('div');
  loading.className = 'explorer-loading';
  loading.textContent = t('explorer.loading');
  container.appendChild(loading);

  // Store container reference for the WS response handler
  const reqId = path || '__root__';
  pendingLoads.set(reqId, { container, depth, path });

  sendWs({ type: 'listDir', sessionId: state.activeSessionId, path, rootPath: explorerRoot });
}

// Map: reqId → { container, depth, path }
const pendingLoads = new Map();

function openFile(path, fileName, pinned = false) {
  // Update currentDir to the file's parent so "New File" creates in the same folder
  currentDir = getTargetDir(path);
  // If already open as a pinned tab, just switch to it
  const tabId = `file:${path}`;
  import('./canvas.js').then(({ hasTab, setActiveTab }) => {
    if (hasTab(tabId)) {
      setActiveTab(tabId);
      return;
    }
    // Request file content — response arrives as 'fileContent' WS message,
    // which triggers 'workspace-open-item' event that canvas.js listens for.
    sendWs({ type: 'readFile', sessionId: state.activeSessionId, path, rootPath: explorerRoot });
    // Store pinned flag for the fileContent handler
    pendingPinned.set(path, pinned);
  });
}

// Track pinned requests keyed by file path
const pendingPinned = new Map();
// Track background-refresh requests (canvas.js scheduleFileRefresh) keyed by
// file path — their fileContent responses must not steal tab activation.
const pendingRefresh = new Set();

// Listen for canvas tab restore requests — set pinned state before readFile
window.addEventListener('explorer-preload-pinned', (e) => {
  if (e.detail && e.detail.path) {
    pendingPinned.set(e.detail.path, e.detail.pinned !== false);
    if (e.detail.refresh) pendingRefresh.add(e.detail.path);
  }
});

// ── WS Message Handlers ───────────────────────────────────────────────

onMessage('dirListing', (msg) => {
  const reqId = msg.path || '__root__';
  const pending = pendingLoads.get(reqId);
  pendingLoads.delete(reqId);
  loadingDirs.delete(msg.path);

  if (!pending) return;

  if (msg.error) {
    pending.container.innerHTML = `<div class="explorer-error">${msg.error}</div>`;
    return;
  }

  pending.container.innerHTML = '';
  const entries = msg.entries || [];
  const depth = pending.depth + 1;

  let hasVisible = false;
  for (const entry of entries) {
    if (shouldHide(entry.name, entry.type === 'dir')) continue;
    hasVisible = true;

    const fullPath = pending.path
      ? `${pending.path}/${entry.name}`
      : entry.name;

    if (entry.type === 'dir') {
      pending.container.appendChild(buildDirNode(fullPath, false, depth));
    } else {
      pending.container.appendChild(buildFileNode(fullPath, depth));
    }
  }

  if (!hasVisible) {
    pending.container.innerHTML = `<div class="explorer-empty">${t('explorer.empty')}</div>`;
  }

  // Restore expansion state: a directory reload wipes its subtree DOM, which
  // would otherwise silently collapse every expanded child (focus refresh,
  // pathMoved, fileCreated all reload ancestors). Re-expand + reload any child
  // dir still present in expandedDirs.
  for (const child of pending.container.children) {
    if (!child.classList.contains('explorer-dir-wrapper')) continue;
    const p = child.dataset.path;
    if (!p || !expandedDirs.has(p)) continue;
    const chevron = child.querySelector('.explorer-chevron');
    const kids = child.querySelector('.explorer-children');
    if (chevron && kids) {
      chevron.classList.add('expanded');
      kids.style.display = '';
      kids.innerHTML = '';
      loadDir(p, kids, p.split('/').length);
    }
  }

  // Directory re-rendered — restore .selected classes from the selection model
  applySelectionClasses();

  if (typeof lucide !== 'undefined') createIconsIn(pending.container);
});

onMessage('fileContent', (msg) => {
  // #303 internal drag: a drop-triggered readFile is answered here — hand the
  // content to the input-bar attachment pipeline instead of opening a tab.
  // One-shot guard: input.js sets the window flag before sending readFile and
  // this branch consumes it (nulls it) on the matching path.
  const win = /** @type {Window & { __internalDragReadPath: string | null }} */ (/** @type {any} */ (window));
  if (win.__internalDragReadPath && win.__internalDragReadPath === msg.path) {
    win.__internalDragReadPath = null;
    window.dispatchEvent(new CustomEvent('internal-file-read', {
      detail: {
        path: msg.path,
        absPath: msg.absPath || '',
        content: msg.content || '',
        size: msg.size || 0,
        name: msg.fileName || msg.path.split('/').pop() || msg.path,
        error: msg.error || null,
      },
    }));
    return;
  }
  if (msg.error) {
    console.warn('readFile error:', msg.error);
    // F3 (2026-08-30 作者裁定 + 方案 §8.4 F3): readFile 失败不再静默丢弃——保留
    // 「文件不可读」骨架标签（标题 + 提示），让用户可感知而非标签无声消失。
    // 若该路径已有打开标签（后台刷新失败），canvas 端保留原内容不覆盖。
    pendingPinned.delete(msg.path);
    const isBackground = pendingRefresh.delete(msg.path);
    const filePath = msg.path;
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
      id: `file:${filePath}`,
      title: filePath ? filePath.split('/').pop() : filePath,
      itemType: 'code',
      content: '',
      absPath: msg.absPath || filePath,
      path: filePath,
      error: msg.error,
      pinned: false,
      background: isBackground,
    }}));
    return;
  }
  // Open in canvas
  const tabId = `file:${msg.path}`;
  const pinned = pendingPinned.get(msg.path) || false;
  pendingPinned.delete(msg.path);
  // Background refresh responses (activation-triggered) re-render in place but
  // must not yank the user back to that tab.
  const background = pendingRefresh.delete(msg.path);
  const item = {
    id: tabId,
    title: msg.fileName || msg.path,
    itemType: msg.itemType || 'code',
    content: msg.content || '',
    absPath: msg.absPath,
    size: msg.size,
    path: msg.path,
    rootPath: explorerRoot,
    pinned,
    background,
  };
  window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: item }));
});

// ── Public Init ────────────────────────────────────────────────────────

export function initExplorer() {
  // Restore persisted root on startup
  explorerRoot = loadPersistedRoot();
  updateExplorerTitle();

  // Wire folder picker button — uses callback mode (no folderId needed)
  const folderBtn = document.getElementById('explorer-folder-btn');
  if (folderBtn && !folderBtn._bound) {
    folderBtn._bound = true;
    folderBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      openPathPickerCallback(explorerRoot, (selectedPath) => {
        persistRoot(selectedPath);
        expandedDirs.clear();
        renderTree();
      });
    });
  }

  // New File button
  const newFileBtn = document.getElementById('explorer-new-file-btn');
  if (newFileBtn && !newFileBtn._bound) {
    newFileBtn._bound = true;
    newFileBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      startCreateNode(false);
    });
  }

  // New Folder button
  const newFolderBtn = document.getElementById('explorer-new-folder-btn');
  if (newFolderBtn && !newFolderBtn._bound) {
    newFolderBtn._bound = true;
    newFolderBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      startCreateNode(true);
    });
  }

  // Context menu — right-click on tree items
  const tree = document.getElementById('explorer-tree');

  // Drag-to-move: delegated drop targets on folder rows + root blank area.
  if (tree && !tree._dragMoveBound) {
    tree._dragMoveBound = true;
    bindTreeDragMove(tree);
  }

  if (tree && !tree._ctxBound) {
    tree._ctxBound = true;
    tree.addEventListener('contextmenu', (e) => {
      const item = e.target.closest('.explorer-item');
      if (item) {
        e.preventDefault();
        e.stopPropagation();
        const p = item.dataset.path;
        // Right-click on an unselected item → make it the sole selection
        // (menu falls back to single-path actions). Right-click on a selected
        // item keeps the selection (menu offers batch actions).
        if (!selectedPaths.has(p)) selectSingle(p);
        showContextMenu(e.clientX, e.clientY, p, item.classList.contains('explorer-folder'));
      }
    });
    // Click on blank tree area → clear selection
    tree.addEventListener('click', (e) => {
      if (!e.target.closest('.explorer-item')) clearSelection();
    });
  }

  // Esc clears the selection (unless an inline create input has focus)
  if (!window.__explorerEscBound) {
    window.__explorerEscBound = true;
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && selectedPaths.size && !e.target.closest?.('.explorer-creating')) {
        clearSelection();
      }
    });
  }

  // Close context menu on any interaction outside the menu.
  // Use mousedown + capture so it fires BEFORE child stopPropagation calls.
  document.addEventListener('mousedown', (e) => {
    if (ctxMenuEl && !ctxMenuEl.contains(e.target)) hideContextMenu();
  }, true);
  document.addEventListener('contextmenu', () => hideContextMenu(), true);

  // Listen for project root changes (from path picker)
  window.addEventListener('project-root-changed', () => {
    if (state.activeSessionId) {
      expandedDirs.clear();
      renderTree();
    }
  });

  bindFocusRefresh();

  if (state.activeSessionId) {
    expandedDirs.clear();
    renderTree();
  }
}

// ── File operations (create, delete) ──────────────────────────────────

/** Determine the directory to create in: the selected/expanded folder, or root. */
function getTargetDir(forPath) {
  if (!forPath) return '';
  const parts = forPath.split('/');
  parts.pop();
  return parts.join('/');
}

/** Absolute workspace path for an explorer-relative path (explorerRoot join). */
function absPathFor(relPath) {
  const root = explorerRoot || '';
  if (!root) return relPath;
  return root.endsWith('/') ? root + relPath : root + '/' + relPath;
}

/** Show inline input for creating a new file or folder in the tree.
 *  @param {boolean} isDir — true for folder, false for file
 *  @param {string} dirPath — directory to create in (relative to root). Defaults to currentDir. */
function startCreateNode(isDir, dirPath) {
  // If no dirPath given, use the last expanded folder (currentDir)
  if (dirPath === undefined) dirPath = currentDir || '';

  // Find the children container for the target directory
  let container;
  if (!dirPath) {
    // Root level
    container = document.querySelector('.explorer-root .explorer-children');
  } else {
    const wrapper = document.querySelector(`.explorer-dir-wrapper[data-path="${CSS.escape(dirPath)}"]`);
    if (wrapper) {
      // Expand if collapsed
      if (!expandedDirs.has(dirPath)) {
        const row = wrapper.querySelector('.explorer-folder');
        const chevron = wrapper.querySelector('.explorer-chevron');
        const children = wrapper.querySelector('.explorer-children');
        if (row && chevron && children) {
          expandedDirs.add(dirPath);
          currentDir = dirPath;
          chevron.classList.add('expanded');
          children.style.display = '';
          children.innerHTML = '';
          loadDir(dirPath, children, dirPath.split('/').length);
          // Wait for load to complete, then retry
          setTimeout(() => startCreateNode(isDir, dirPath), 500);
          return;
        }
      }
      container = wrapper.querySelector('.explorer-children');
    }
  }

  if (!container) {
    // Fallback: create at root
    dirPath = '';
    container = document.querySelector('.explorer-root .explorer-children');
  }

  // Create inline input row
  const depth = dirPath ? dirPath.split('/').length + 1 : 1;
  const inputRow = document.createElement('div');
  inputRow.className = 'explorer-item explorer-creating';
  inputRow.style.paddingLeft = `${depth * 14 + 8}px`;

  const icon = document.createElement('span');
  icon.className = 'explorer-icon';
  icon.innerHTML = isDir
    ? '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"/></svg>'
    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>';

  const input = document.createElement('input');
  input.className = 'explorer-name-input';
  input.placeholder = isDir ? 'folder name' : 'file name';
  input.autocomplete = 'off';

  inputRow.appendChild(icon);
  inputRow.appendChild(input);
  container.insertBefore(inputRow, container.firstChild);
  input.focus();

  const commit = () => {
    const name = input.value.trim();
    inputRow.remove();
    if (!name) return;
    const fullPath = dirPath ? `${dirPath}/${name}` : name;
    if (isDir) {
      sendWs({ type: 'createDir', sessionId: state.activeSessionId, path: fullPath, rootPath: explorerRoot });
    } else {
      sendWs({ type: 'createFile', sessionId: state.activeSessionId, path: fullPath, rootPath: explorerRoot });
    }
  };

  const cancel = () => inputRow.remove();

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); commit(); }
    else if (e.key === 'Escape') { e.preventDefault(); cancel(); }
  });
  input.addEventListener('blur', commit);
}

/** Delete a file or folder. */
function deleteNode(path) {
  const name = path.split('/').pop();
  const send = () => sendWs({ type: 'deletePath', sessionId: state.activeSessionId, path, rootPath: explorerRoot });
  if (typeof window.__showConfirm === 'function') {
    window.__showConfirm('Delete File', `Delete "${name}"? This cannot be undone.`, send);
  } else {
    send();
  }
}

/** Batch-delete every path in the current selection — one confirm, one WS message. */
function deleteSelected() {
  const paths = [...selectedPaths];
  if (!paths.length) return;
  const send = () => sendWs({ type: 'deletePaths', sessionId: state.activeSessionId, paths, rootPath: explorerRoot });
  if (typeof window.__showConfirm === 'function') {
    window.__showConfirm(t('explorer.deleteTitle'), t('explorer.deleteConfirmN', { count: paths.length }), send);
  } else {
    send();
  }
}

// ── Context Menu ──────────────────────────────────────────────────────

let ctxMenuEl = null;

function showContextMenu(x, y, path, isDir) {
  hideContextMenu();
  // Selection-aware: right-clicked item is part of a ≥2 selection → batch menu
  const batch = path && selectedPaths.size >= 2 && selectedPaths.has(path);
  ctxMenuEl = document.createElement('div');
  ctxMenuEl.className = 'explorer-context-menu';
  ctxMenuEl.style.left = x + 'px';
  ctxMenuEl.style.top = y + 'px';
  if (batch) {
    const btn = document.createElement('button');
    btn.dataset.action = 'delete-selected';
    btn.className = 'danger';
    btn.textContent = t('explorer.deleteN', { count: selectedPaths.size });
    ctxMenuEl.appendChild(btn);
  } else {
    ctxMenuEl.innerHTML = `
      ${path ? `<button data-action="reference" class="reference">${t('explorer.reference')}</button><hr>` : ''}
      <button data-action="new-file">${t('explorer.newFile')}</button>
      <button data-action="new-folder">${t('explorer.newFolder')}</button>
      ${path ? `<hr><button data-action="delete" class="danger">${t('explorer.delete')}</button>` : ''}
    `;
  }
  document.body.appendChild(ctxMenuEl);

  // Adjust position if off-screen
  const rect = ctxMenuEl.getBoundingClientRect();
  if (rect.right > window.innerWidth) ctxMenuEl.style.left = (x - rect.width) + 'px';
  if (rect.bottom > window.innerHeight) ctxMenuEl.style.top = (y - rect.height) + 'px';

  ctxMenuEl.querySelectorAll('button').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const action = btn.dataset.action;
    hideContextMenu();
    if (action === 'reference') {
      // #303 global-reference: right-click → 引用 → produce a file Reference
      // (@path pointer) into the active view's pendingAttachments.
      const abs = absPathFor(path);
      const name = path.split('/').pop() || path;
      appendRefToActiveView(makeReference({
        refType: 'file',
        source: { kind: 'workspace', path: abs, fileName: name, title: name },
      }));
    }
    else if (action === 'new-file') startCreateNode(false, isDir ? path : getTargetDir(path));
      else if (action === 'new-folder') startCreateNode(true, isDir ? path : getTargetDir(path));
      else if (action === 'delete') deleteNode(path);
      else if (action === 'delete-selected') deleteSelected();
    });
  });
}

function hideContextMenu() {
  if (ctxMenuEl) { ctxMenuEl.remove(); ctxMenuEl = null; }
}

// ── WS response handlers for file ops ─────────────────────────────────

onMessage('fileCreated', (msg) => {
  refreshDirOf(msg.path);
});
onMessage('dirCreated', (msg) => {
  refreshDirOf(msg.path);
});
onMessage('pathDeleted', (msg) => {
  handlePathDeleted(msg.path);
});

/** Batch delete response: { type:'pathsDeleted', deleted:[...], failed:[{path,error}] }
 *  Each deleted path reuses the single-path cleanup; failures are summarized
 *  into a single toast. */
onMessage('pathsDeleted', (msg) => {
  const deleted = msg.deleted || [];
  const failed = msg.failed || [];
  for (const p of deleted) handlePathDeleted(p);
  if (failed.length) {
    const f = failed[0];
    window.__showToast?.(
      t('explorer.deleteResult', {
        ok: deleted.length,
        fail: failed.length,
        path: f.path,
        error: f.error || '',
      }),
      'error'
    );
  }
});

/** Shared cleanup for a deleted path (single or batch): refresh parent dir,
 *  close its tab, drop it from the selection model. */
function handlePathDeleted(path) {
  refreshDirOf(path);
  // Also close any open tab for this file
  window.dispatchEvent(new CustomEvent('canvas-close-tab', { detail: { id: `file:${path}` } }));
  pruneSelection(path);
}
onMessage('fileOpError', (msg) => {
  console.error('File operation error:', msg.error);
  window.__showToast?.(msg.error || 'File operation failed', 'error');
});

/** Drag-to-move response: { type:'pathMoved', oldPath, newPath } — refresh both
 *  parent dirs, retarget open canvas tabs (ids, absPaths, editor save paths),
 *  drop the stale path from the selection, and confirm with a toast. */
onMessage('pathMoved', (msg) => {
  const { oldPath, newPath } = msg;
  if (!oldPath || !newPath) return;
  // New parent FIRST: refreshing the old parent may wipe the target dir's
  // subtree DOM when the target sits inside it (e.g. root refresh rebuilds
  // all rows collapsed) — the reverse order would silently drop the target
  // refresh. An ancestor refresh simply re-collapses the target dir; its next
  // expand reloads from disk anyway.
  refreshDirOf(newPath);
  if (getTargetDir(oldPath) !== getTargetDir(newPath)) refreshDirOf(oldPath);
  import('./canvas.js').then(({ retargetFileTabs }) =>
    retargetFileTabs(oldPath, newPath, explorerRoot || ''));
  pruneSelection(oldPath);
  window.__showToast?.(t('explorer.moved', { name: newPath.split('/').pop() }), 'info');
});

/** Refresh the parent directory of a created/deleted path. */
function refreshDirOf(path) {
  const dirPath = getTargetDir(path);
  // Force reload by removing from expanded and re-adding
  const reqId = dirPath || '__root__';
  const wrapper = dirPath
    ? document.querySelector(`.explorer-dir-wrapper[data-path="${CSS.escape(dirPath)}"]`)
    : document.querySelector('.explorer-root');
  if (wrapper) {
    const children = wrapper.querySelector('.explorer-children');
    if (children) {
      children.innerHTML = '';
      const depth = dirPath ? dirPath.split('/').length : 0;
      expandedDirs.delete(dirPath);
      expandedDirs.add(dirPath);
      loadDir(dirPath, children, depth);
    }
  }
}

export function refreshExplorer(sessionId) {
  expandedDirs.clear();
  loadingDirs.clear();
  pendingLoads.clear();
  clearSelection();
  if (sessionId) {
    renderTree();
  } else {
    const body = $('#explorer-tree');
    if (body) body.innerHTML = '';
  }
}

// ── Focus refresh ─────────────────────────────────────────────────────
// External edits (another editor, an agent on the host) don't reach the UI:
// the tree only reloads on expand, and file tabs only on activation. When the
// window regains focus, reload the root + every expanded dir and ask canvas to
// re-check the active file tab (dirty-guarded + content-compare downstream).

/** Reload the root listing and every currently expanded directory in place. */
function refreshExpandedDirs() {
  const rootChildren = document.querySelector('.explorer-root > .explorer-children');
  if (rootChildren && !loadingDirs.has('')) {
    rootChildren.innerHTML = '';
    loadDir('', rootChildren, 0);
  }
  for (const p of [...expandedDirs]) {
    if (!p || loadingDirs.has(p)) continue;
    const wrapper = document.querySelector(`.explorer-dir-wrapper[data-path="${CSS.escape(p)}"]`);
    const children = wrapper?.querySelector('.explorer-children');
    if (children) {
      children.innerHTML = '';
      loadDir(p, children, p.split('/').length);
    }
  }
}

let focusRefreshTimer = null;
function onRegainFocus() {
  clearTimeout(focusRefreshTimer);
  focusRefreshTimer = setTimeout(() => {
    // Tree not rendered yet (no session) — nothing to refresh.
    if (!document.querySelector('.explorer-root')) return;
    refreshExpandedDirs();
    // canvas.js listens and re-checks the active file tab (dirty-safe).
    window.dispatchEvent(new CustomEvent('explorer-focus-refresh'));
  }, 250);
}

/** Bind window focus/visibility refresh once (called from initExplorer). */
function bindFocusRefresh() {
  const win = /** @type {any} */ (window);
  if (win.__explorerFocusBound) return;
  win.__explorerFocusBound = true;
  window.addEventListener('focus', onRegainFocus);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) onRegainFocus();
  });
}
