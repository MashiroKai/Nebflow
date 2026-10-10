// explorer.js — VS Code-style file tree in the sidebar.
//
// Lazy-loads directories via `listDir` WS message. Clicking a file sends
// `readFile`, opens the content as a Canvas tab (markdown/code/html).
// Expand/collapse state is kept in-memory (not persisted).
// The header folder button opens the unified directory picker
// (workspacePicker.js) to set the tree root.

import state from './state.js';
import { key } from './branding.js';
import { sendWs, onMessage, onReconnect } from './ws.js';
import { createIconsIn } from './utils.js';
import { t } from './i18n.js';
import { makeReference } from './reference.js';
import { appendRefToActiveView } from './input.js';
import { showSidePanel } from './activityBar.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

// explorer.js 拆分(FE组件化批次五 2026-09-28):选择状态族与文件规则小簇迁至 js/explorer/
// 子模块 —— selection.js(多选模型族,簇私态 selectedPaths/anchorPath 整态随迁)、
// fileRules.js(getFileInfo/shouldHide + FILE_ICONS/HIDDEN_DIRS 两表随迁)。子模块不得
// 反向 import 本文件;本文件公共导出面(initExplorer/openExplorerAt/refreshExplorer)
// 不变,外部 import 方零改动。树内拖放族(B)与外部拖入导入族(A)本批降级留主体:其函数
// 体直接读写 explorerRoot/dragMoveSrc/currentDir 文件级可变态且调用面横跨簇外主体。
import { selectedPaths, applySelectionClasses, selectSingle, clearSelection, pruneSelection, handleSelectClick, injectSelectionHostFns } from './explorer/selection.js';
import { getFileInfo, shouldHide } from './explorer/fileRules.js';
// selection.js 的 rangeSelect/updateSelectionBar 需主体 getTargetDir/deleteSelected
//(该两段留守本文件):模块求值即注入,函数声明提升保证此处已可引用。
injectSelectionHostFns({ getTargetDir, deleteSelected });

// ── State ──────────────────────────────────────────────────────────────

/** Explorer root path (absolute). null = default ~/.nebflow/projects/.
 *  Persisted to localStorage so it survives restarts. */
let explorerRoot = null;
const EXPLORER_ROOT_KEY = key('explorer_root');
/**
 * Explorer root ORIGIN device (ui-device-faces 批 2026-10-10 · 设计稿 §2.1)：远端项目
 * 「在文件浏览器中打开」以 {path, device} 建根——整棵树同源一设备，所有 listDir /
 * readFile 都 additive 携带 `device` 键（后端远端腿）；本机根 = null，**零键**（self
 * 守卫：后端对解析为本机的 device 显式回错）。localStorage 伴生键 additive；旧值
 * 缺 device = 本机，存量零迁移。
 */
let explorerRootDevice = null;
const EXPLORER_ROOT_DEVICE_KEY = key('explorer_root_device');

function loadPersistedRoot() {
  try {
    const saved = localStorage.getItem(EXPLORER_ROOT_KEY);
    if (!saved) return null;
    // 伴生 device 键只在 root 在场时有效（悬挂 device = 视为本机根）。
    explorerRootDevice = localStorage.getItem(EXPLORER_ROOT_DEVICE_KEY) || null;
    return saved;
  } catch (_) { return null; }
}

function persistRoot(path, device = null) {
  explorerRoot = path;
  explorerRootDevice = path ? (device || null) : null;
  try {
    if (path) localStorage.setItem(EXPLORER_ROOT_KEY, path);
    else localStorage.removeItem(EXPLORER_ROOT_KEY);
    if (explorerRootDevice) localStorage.setItem(EXPLORER_ROOT_DEVICE_KEY, explorerRootDevice);
    else localStorage.removeItem(EXPLORER_ROOT_DEVICE_KEY);
  } catch (_) {}
  updateExplorerTitle();
}

/** hard-drive icon（lucide 同源描边；芯片/徽标两处共用一串 SVG）。 */
const DEVICE_HD_SVG =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
  '<line x1="22" x2="2" y1="12" y2="12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>';

/** 头部设备徽标 chip（`.explorer-title` 旁常显；子行零徽标——整树同源）。 */
function renderExplorerDeviceChip() {
  const header = document.querySelector('.explorer-header');
  if (!header) return;
  let chip = /** @type {HTMLElement | null} */ (header.querySelector('.dev-chip'));
  if (!explorerRootDevice) {
    chip?.remove();
    return;
  }
  if (!chip) {
    chip = document.createElement('span');
    chip.className = 'dev-chip';
    const titleEl = header.querySelector('.explorer-title');
    if (titleEl) titleEl.after(chip);
    else header.appendChild(chip);
  }
  chip.innerHTML = DEVICE_HD_SVG;
  chip.appendChild(document.createTextNode(explorerRootDevice));
  chip.title = explorerRootDevice;
  chip.classList.remove('is-offline');
  // 显示名 + 离线态异步补写（花名册 60s 内存缓存；失败回落 deviceId 直显）。
  import('./deviceRoster.js').then(async ({ resolveDeviceName, isDeviceOnline }) => {
    if (!explorerRootDevice) return;
    const [name, online] = await Promise.all([resolveDeviceName(explorerRootDevice), isDeviceOnline(explorerRootDevice)]);
    if (!explorerRootDevice || !chip.isConnected) return;
    chip.textContent = '';
    chip.innerHTML = DEVICE_HD_SVG;
    chip.appendChild(document.createTextNode(name));
    chip.title = explorerRootDevice;
    if (online === false) {
      chip.classList.add('is-offline');
      const dot = document.createElement('span');
      dot.className = 'warn-dot';
      chip.prepend(dot);
    }
  }).catch(() => {});
}

function updateExplorerTitle() {
  const titleEl = /** @type {HTMLElement | null} */ (document.querySelector('.explorer-title'));
  if (!titleEl) return;
  if (explorerRoot) {
    const name = explorerRoot.split('/').pop() || explorerRoot;
    titleEl.textContent = name;
    titleEl.title = explorerRoot;
  } else {
    titleEl.textContent = t('panel.explorer');
    titleEl.title = '';
  }
  renderExplorerDeviceChip();
  // 远端根 = 只读视图（本批只开列目录 + 单文件取件两条腿）：写入口整排摘除。
  const remote = !!explorerRootDevice;
  for (const id of ['explorer-new-file-btn', 'explorer-new-folder-btn']) {
    const btn = document.getElementById(id);
    if (btn) btn.hidden = remote;
  }
}

/** Expanded directory paths (relative to explorer root). */
const expandedDirs = new Set();

/** Current working directory for new file creation. Updated when a folder is
 *  expanded or a file is opened. The "New File" button creates here. */
let currentDir = '';

/** Loading indicator set — prevents double-fetching the same dir. */
const loadingDirs = new Set();

// ── Selection model → 已迁 ./explorer/selection.js(FE组件化批次五 2026-09-28,行为保持)────

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
    // Internal drag-to-move only (existing #303 behavior). External OS-file
    // drags are owned by the section-level channel (bindSectionExternalDrop)
    // — the whole explorer panel accepts them, not just tree zones.
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
    // Internal drags only: leaving the tree entirely → clear highlight
    // (child-element transitions keep relatedTarget inside and are ignored).
    // External drags key off the section-level handler instead.
    if (!dragMoveSrc) return;
    if (!tree.contains(e.relatedTarget)) setDropTarget(null);
  });

  tree.addEventListener('drop', (e) => {
    // Internal drag-to-move only. External OS drops bubble past this handler
    // (no preventDefault here) to the section-level channel below.
    if (!dragMoveSrc) return;
    const dir = dropDirForEvent(e);
    const src = dragMoveSrc;
    setDropTarget(null);
    if (dir === null || !isValidDropTarget(src.path, dir)) return;
    if (refuseRemoteWrite()) return;
    e.preventDefault();
    e.stopPropagation();
    sendWs({ type: 'movePath', sessionId: state.activeSessionId, path: src.path, targetDir: dir, rootPath: explorerRoot });
  });
}

// ── External drag-in landing zones (section-level) ──────────────────────
// c0cc89df bound the OS-file channel to the TREE only, and dropDirForEvent
// returned null for file rows / blank tree space / the header. Those spots
// never saw preventDefault → the browser dispatched drop, the document-level
// handler swallowed it, and a Finder drag onto most of the visible panel did
// nothing. Fix: one channel on #explorer-section. VS Code parity for landing
// resolution — folder row → that dir; file row → its parent dir; everything
// else in the section (tree blank, header, margins) → project root.

/** Resolve the landing directory for an external drag over the section.
 *  Always returns a string — the whole section accepts external drops. */
function externalDropDir(e) {
  const t = e.target instanceof Element ? e.target : null;
  if (!t) return '';
  const folder = t.closest('.explorer-item.explorer-folder');
  if (folder && folder.dataset.path !== undefined) return folder.dataset.path;
  const item = t.closest('.explorer-item');
  if (item && item.dataset.path !== undefined) {
    const p = item.dataset.path;
    const cut = p.lastIndexOf('/');
    return cut > 0 ? p.slice(0, cut) : '';   // file row → parent dir
  }
  return '';
}

/** Highlight the folder row the resolved dir maps to (the highlight names the
 *  landing folder). Reuses the existing .drop-target styles — zero new CSS. */
function setExternalDropHighlight(dir) {
  const tree = document.getElementById('explorer-tree');
  if (!tree) return;
  if (!dir) { setDropTarget(tree.querySelector('.explorer-root'), true); return; }
  const row = tree.querySelector(
    `.explorer-dir-wrapper[data-path="${CSS.escape(dir)}"] > .explorer-item.explorer-folder`);
  setDropTarget(row || tree.querySelector('.explorer-root'), true);
}

/** External drag-in channel at SECTION level: header, blank tree space, rows —
 *  the whole visible panel accepts OS file/folder drops. Internal app drags
 *  still route through the tree channels above (dragMoveSrc guard). */
function bindSectionExternalDrop(section) {
  section.addEventListener('dragover', (e) => {
    if (dragMoveSrc || !hasExternalDrag(e)) return;
    e.preventDefault();
    e.stopPropagation();   // keep the document-level dragover from overriding dropEffect
    e.dataTransfer.dropEffect = 'copy';
    setExternalDropHighlight(externalDropDir(e));
  });
  section.addEventListener('dragleave', (e) => {
    if (dragMoveSrc || !dropTargetEl) return;
    // dataTransfer types are unreadable during dragleave on some engines —
    // external leaves key off dropTargetEl (set only while we highlight).
    if (!section.contains(e.relatedTarget)) setDropTarget(null);
  });
  section.addEventListener('drop', (e) => {
    if (dragMoveSrc || !hasExternalDrag(e)) return;
    e.preventDefault();
    e.stopPropagation();   // the document-level drop handler must not swallow this
    const dir = externalDropDir(e);
    setDropTarget(null);
    importExternalDrop(e.dataTransfer, dir);
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

// ── External drag-in (OS files/folders → tree copy) ───────────────────────
// VS Code parity: dropping OS files/folders onto a folder row (or the blank
// root area) COPIES them into that directory. Writes go through the existing
// `writeFile` op with `encoding:'base64'` (byte-preserving; the backend
// creates parent dirs on demand, so a dropped folder tree lands recursively
// without extra mkdir round trips — empty dropped folders are not recreated,
// documented口径). Same-name top-level items open a 3-choice conflict dialog
// (Replace / Keep Both / Cancel), one per conflicted item, sequential;
// Cancel skips THAT item and continues with the rest.

/** Per-file byte cap — the WS JSON channel is not a bulk-transfer medium. */
const EXTERNAL_IMPORT_MAX_BYTES = 20 * 1024 * 1024;

/** True when the drag carries OS files/folders (never our internal drags). */
function hasExternalDrag(e) {
  if (!e.dataTransfer || dragMoveSrc) return false;
  const items = e.dataTransfer.items;
  if (items && Array.from(items).some(i => i.kind === 'file')) return true;
  return Array.from(e.dataTransfer.types || []).includes('Files');
}

/** Read a File as base64 (chunked to stay clear of argument-count limits). */
async function fileToBase64(file) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let bin = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

/** Top-level dropped items. Prefers webkitGetAsEntry (folder trees); without
 *  it, degrades to flat File objects (folders arrive as their loose files). */
function collectTopDropItems(dataTransfer) {
  const tops = [];
  let usedEntryApi = false;
  for (const it of Array.from(dataTransfer.items || [])) {
    if (it.kind !== 'file') continue;
    const entry = typeof it.webkitGetAsEntry === 'function' ? it.webkitGetAsEntry() : null;
    if (entry) {
      usedEntryApi = true;
      tops.push({ name: entry.name, isDir: entry.isDirectory, entry });
    }
  }
  if (usedEntryApi) return tops;
  return Array.from(dataTransfer.files || []).map(f => ({ name: f.name, isDir: false, file: f }));
}

/** Recursively read a FileSystemEntry into file leaves with paths relative to
 *  the drop root — every rel starts with the top entry's own name. */
function readEntryLeaves(entry, base) {
  return new Promise((resolve, reject) => {
    if (entry.isFile) {
      entry.file((f) => resolve([{ rel: base + entry.name, file: f }]), reject);
      return;
    }
    if (!entry.isDirectory) { resolve([]); return; }
    const reader = entry.createReader();
    const kids = [];
    const readBatch = () => {
      // readEntries returns entries in batches — must loop until an empty batch.
      reader.readEntries(async (batch) => {
        if (!batch.length) {
          const nested = await Promise.all(
            kids.map((k) => readEntryLeaves(k, base + entry.name + '/')));
          resolve(nested.flat());
          return;
        }
        kids.push(...batch);
        readBatch();
      }, reject);
    };
    readBatch();
  });
}

/** Leaves for one top item, with a possibly-renamed top segment (conflict
 *  resolution renames the ROOT of the dropped tree, VS Code "Keep Both"). */
async function leavesForTopItem(item, resolvedTop) {
  // Entry-based tops (every real-browser drop — Finder/Explorer — carries a
  // working webkitGetAsEntry) MUST go through readEntryLeaves: the entry
  // branch of collectTopDropItems carries NO `file`, so `item.file` here was
  // undefined and the write threw "Cannot read properties of undefined" —
  // the c0cc89df drop-in never landed a real OS file (synthetic-DataTransfer
  // tests only ever exercised the files[] fallback below, which is why the
  // old QA passed). readEntryLeaves(entry.file) materializes the File lazily.
  if (item.entry) {
    const leaves = await readEntryLeaves(item.entry, '');
    return leaves.map((l) => ({ rel: resolvedTop + l.rel.slice(item.name.length), file: l.file }));
  }
  return [{ rel: resolvedTop, file: item.file }];   // files[] fallback only
}

/** VS Code-style "Keep Both" name: "foo copy.txt", "foo copy 2.txt", …
 *  (dotfiles like .gitignore never split — lastIndexOf('.')===0). */
function uniqueDropName(name, taken) {
  const dot = name.lastIndexOf('.');
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot) : '';
  for (let n = 1; ; n++) {
    const candidate = n === 1 ? `${stem} copy${ext}` : `${stem} copy ${n}${ext}`;
    if (!taken.has(candidate)) return candidate;
  }
}

/** One-shot listDir scans used by external drop-in for conflict detection.
 *  Keyed by explorer-relative dir path; consumed by the dirListing handler
 *  BEFORE the normal render path (scans never touch pendingLoads). */
const externalListScans = new Map();

/** Promise the set of entry names in `dirPath` (explorer-relative).
 *  Resolves empty on error/timeout so the drop still proceeds — individual
 *  writes surface their own errors. */
function scanDirNames(dirPath) {
  return new Promise((resolve) => {
    externalListScans.set(dirPath, (entries) => resolve(new Set(entries.map(e => e.name))));
    sendWs({ type: 'listDir', sessionId: state.activeSessionId, path: dirPath, rootPath: explorerRoot });
    setTimeout(() => {
      if (externalListScans.has(dirPath)) {
        externalListScans.delete(dirPath);
        resolve(new Set());
      }
    }, 10000);
  });
}

/** Pending external writes: explorer-relative path → { resolve, reject }.
 *  Correlates fileSaved / fileSaveError frames (path-keyed) with in-flight
 *  writes so the import can sequence folder leaves deterministically. */
const pendingExternalWrites = new Map();

/** Write one leaf via the writeFile op (base64 = byte-preserving copy). */
async function writeExternalLeaf(relPath, file) {
  if (file.size > EXTERNAL_IMPORT_MAX_BYTES) {
    throw new Error(`${file.name}: exceeds ${Math.round(EXTERNAL_IMPORT_MAX_BYTES / 1024 / 1024)}MB limit`);
  }
  const b64 = await fileToBase64(file);
  return new Promise((resolve, reject) => {
    pendingExternalWrites.set(relPath, { resolve, reject });
    sendWs({
      type: 'writeFile', sessionId: state.activeSessionId,
      path: relPath, content: b64, encoding: 'base64', rootPath: explorerRoot,
    });
    // Lost-response guard: a vanished frame must not hang the import forever.
    setTimeout(() => {
      const p = pendingExternalWrites.get(relPath);
      if (p) { pendingExternalWrites.delete(relPath); reject(new Error(`${file.name}: write timed out`)); }
    }, 30000);
  });
}

/** 3-choice conflict resolution for one dropped item. Resolves
 *  'replace' | 'keepboth' | 'cancel'. */
function confirmImportConflict(name) {
  return new Promise((resolve) => {
    const api = /** @type {any} */ (window).__showConflict;
    if (typeof api !== 'function') { resolve('replace'); return; }  // dialog unavailable
    api(t('explorer.conflictTitle'), t('explorer.conflictMsg', { name }), {
      onReplace: () => resolve('replace'),
      onKeepBoth: () => resolve('keepboth'),
      onCancel: () => resolve('cancel'),
    });
  });
}

/** Import OS-dropped items into `targetDir` (explorer-relative, '' = root).
 *  Sequential per top item; same-name items prompt Replace / Keep Both /
 *  Cancel. Refreshes + expands the target dir when done. */
async function importExternalDrop(dataTransfer, targetDir) {
  if (refuseRemoteWrite()) return;
  let tops;
  try { tops = collectTopDropItems(dataTransfer); }
  catch (e) { window.__showToast?.(String(e?.message || e), 'error'); return; }
  if (!tops.length) return;

  const existing = await scanDirNames(targetDir);
  let imported = 0;
  for (const top of tops) {
    let resolvedTop = top.name;
    if (existing.has(top.name)) {
      const choice = await confirmImportConflict(top.name);
      if (choice === 'cancel') continue;              // skip this item, keep the rest
      if (choice === 'keepboth') {
        resolvedTop = uniqueDropName(top.name, existing);
        existing.add(resolvedTop);
      }
      // 'replace' keeps resolvedTop = top.name: an existing file is
      // overwritten; an existing folder merges (same-name leaves replaced).
    } else {
      existing.add(resolvedTop);
    }
    let leaves;
    try { leaves = await leavesForTopItem(top, resolvedTop); }
    catch (e) {
      window.__showToast?.(`${top.name}: ${e?.message || e}`, 'error');
      continue;
    }
    for (const leaf of leaves) {
      const rel = targetDir ? `${targetDir}/${leaf.rel}` : leaf.rel;
      try {
        await writeExternalLeaf(rel, leaf.file);
        imported++;
      } catch (e) {
        window.__showToast?.(String(e?.message || e), 'error');
      }
    }
  }
  if (imported > 0) {
    refreshImportTarget(targetDir);
    window.__showToast?.(t('explorer.imported', { count: imported }), 'success');
  }
}

/** After an external import: make the result visible, VS Code-style — the
 *  drop target folder OPENS and shows the imported items (root is always
 *  visible; refreshDirOf('') reloads it). NOTE: refreshDirOf(path) refreshes
 *  the PARENT of path (getTargetDir semantics), which is wrong for a drop
 *  target — the imported items live INSIDE targetDir, so its own children
 *  container must be expanded + reloaded here. */
function refreshImportTarget(targetDir) {
  if (!targetDir) { refreshDirOf(''); return; }
  const wrapper = /** @type {HTMLElement|null} */ (
    document.querySelector(`.explorer-dir-wrapper[data-path="${CSS.escape(targetDir)}"]`));
  const children = /** @type {HTMLElement|null} */ (wrapper?.querySelector('.explorer-children'));
  // Wrapper gone (dir deleted mid-flight / not rendered) → fall back to
  // refreshing its parent listing so the tree reflects disk reality.
  if (!wrapper || !children) { refreshDirOf(targetDir); return; }
  expandedDirs.add(targetDir);
  currentDir = targetDir;
  const chevron = /** @type {HTMLElement|null} */ (wrapper.querySelector('.explorer-chevron'));
  if (chevron) chevron.classList.add('expanded');
  children.style.display = '';
  children.innerHTML = '';
  loadDir(targetDir, children, targetDir.split('/').length);
}

// ── Helpers ────────────────────────────────────────────────────────────

function $(sel) { return document.querySelector(sel); }

// FILE_ICONS/HIDDEN_DIRS/getFileInfo/shouldHide 已迁 ./explorer/fileRules.js(FE组件化批次五,行为保持)。

// ── Render ─────────────────────────────────────────────────────────────

function renderTree() {
  const body = $('#explorer-tree');
  if (!body) return;
  clearSelection();
  body.innerHTML = '';
  // Root-level listing — path "" means project root
  body.appendChild(buildDirNode('', true, 0));
  syncWatch();
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
  // The visible-dir set changed — the watch subscription follows it (throttled).
  syncWatch();
}

function loadDir(path, container, depth) {
  if (loadingDirs.has(path)) return;
  loadingDirs.add(path);

  // Loading indicator. 远端根（面B B2）：「正在从 <设备> 读取…」+ sapphire spinner
  // （既有 loading 行语言；spinner 在减动效下停转——见 split.css 降级块）。
  const loading = document.createElement('div');
  loading.className = 'explorer-loading';
  if (explorerRootDevice) {
    loading.classList.add('is-remote');
    loading.innerHTML = '<svg class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>';
    loading.appendChild(document.createTextNode(t('explorer.loadingRemote', { device: explorerRootDevice })));
    // 显示名补写（R-B1「加载行报设备名」）：先以 deviceId 立行（同步、零依赖），
    // 花名册就绪后就地换文本——行已被应答摘除时零操作（isConnected 守卫）。
    import('./deviceRoster.js')
      .then(({ resolveDeviceName }) => resolveDeviceName(explorerRootDevice))
      .then((name) => {
        if (!name || !loading.isConnected || !explorerRootDevice) return;
        if (loading.lastChild && loading.lastChild.nodeType === Node.TEXT_NODE) {
          loading.replaceChild(
            document.createTextNode(t('explorer.loadingRemote', { device: name })),
            loading.lastChild
          );
        }
      })
      .catch(() => {});
  } else {
    loading.textContent = t('explorer.loading');
  }
  container.appendChild(loading);

  // Store container reference for the WS response handler
  const reqId = path || '__root__';
  pendingLoads.set(reqId, { container, depth, path });

  // 🔴 self 守卫（设计稿 §2.1）：本机根**不发** device 键（后端对解析为本机的
  // device 回显错误帧）；只有 remote 根的 listDir 才携带。
  sendWs({
    type: 'listDir', sessionId: state.activeSessionId, path, rootPath: explorerRoot,
    ...(explorerRootDevice ? { device: explorerRootDevice } : {}),
  });
}

// Map: reqId → { container, depth, path }
const pendingLoads = new Map();

/** Row pickup states (面B B4/B5 · ui-device-faces 批): path → true while a remote
 *  single-file fetch is in flight. The row DOM carries `data-pickup`; the map only
 *  gates re-entry (a second click on a fetching row must not double-send). */
const pendingPickups = new Set();

/** 行内取件态三切（loading / failed / 清除）。图标换 spinner、行减淡、行尾标签。 */
function setPickupState(path, state) {
  const row = /** @type {HTMLElement | null} */ (document.querySelector(`.explorer-item.explorer-file[data-path="${CSS.escape(path)}"]`));
  if (!row) return;
  row.querySelector('.pickup-tag')?.remove();
  row.querySelector('.fail-ic')?.remove();
  // 行内图标 = `.explorer-icon`（buildFileNode 的图标 span；曾误写 `.ic` —— 该类
  // 不在行内任何元素上，spinner 挂载/清理会双双静默 no-op）。
  row.querySelector('.explorer-icon .spin')?.remove();
  if (state) {
    row.dataset.pickup = state;
  } else {
    delete row.dataset.pickup;
    return;
  }
  if (state === 'loading') {
    const icon = row.querySelector('.explorer-icon');
    if (icon) {
      const spin = document.createElement('span');
      spin.innerHTML = '<svg class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>';
      icon.prepend(spin.firstChild);
    }
    const tag = document.createElement('span');
    tag.className = 'pickup-tag';
    tag.textContent = t('explorer.pickupLoading');
    row.appendChild(tag);
  } else if (state === 'failed') {
    const fail = document.createElement('span');
    fail.className = 'fail-ic';
    fail.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/></svg>';
    row.appendChild(fail);
  }
}

/** 取件失败玻璃浮条（.explorer-selection-bar 同材质族；设计稿 B5）。单例：新失败
 *  覆盖旧条。[重试] = 重发该文件 readFile；[关闭] = 摘条 + 行复原。 */
function showPickupFailureBar(path, error) {
  hidePickupFailureBar();
  const section = document.getElementById('explorer-section');
  if (!section) return;
  const bar = document.createElement('div');
  bar.className = 'explorer-pickup-bar';
  const icon = document.createElement('span');
  icon.className = 'bar-ic';
  icon.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/></svg>';
  const txt = document.createElement('span');
  txt.className = 'bar-msg';
  txt.textContent = error || t('explorer.pickupFailed');
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'bar-act';
  retry.setAttribute('data-pickup-retry', '1');
  retry.textContent = t('explorer.retry');
  retry.addEventListener('click', (e) => {
    e.stopPropagation();
    hidePickupFailureBar();
    rowFailState(path, true);
    openFile(path, path.split('/').pop() || path);
  });
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'bar-act';
  close.setAttribute('data-pickup-close', '1');
  close.textContent = t('explorer.pickupClose');
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    hidePickupFailureBar();
    rowFailState(path, true);
  });
  bar.append(icon, txt, retry, close);
  section.appendChild(bar);
}

function hidePickupFailureBar() {
  document.querySelector('.explorer-pickup-bar')?.remove();
}

/** 失败行复原（[关闭]/[重试] 共用）：摘失败叹号 + 摘 data-pickup。 */
function rowFailState(path, clear) {
  const row = /** @type {HTMLElement | null} */ (document.querySelector(`.explorer-item.explorer-file[data-path="${CSS.escape(path)}"]`));
  if (row) {
    row.querySelector('.fail-ic')?.remove();
    if (clear) delete row.dataset.pickup;
  }
}

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
    // 面B B4：远端根 = 单文件按需取件（direction=get scope=browse），行内 loading 态。
    // 🔴 同目录其余行不受影响；本机根零态零键（存量逐字节）。
    const remote = !!explorerRootDevice;
    if (remote && !pendingPickups.has(path)) {
      pendingPickups.add(path);
      setPickupState(path, 'loading');
    }
    // Request file content — response arrives as 'fileContent' WS message,
    // which triggers 'workspace-open-item' event that canvas.js listens for.
    sendWs({
      type: 'readFile', sessionId: state.activeSessionId, path, rootPath: explorerRoot,
      ...(remote ? { device: explorerRootDevice } : {}),
    });
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
  // External drop-in conflict scans consume their own listDir replies and
  // never touch the render pipeline (pendingLoads).
  const scan = externalListScans.get(msg.path);
  if (scan) {
    externalListScans.delete(msg.path);
    scan(msg.entries || []);
    return;
  }
  const reqId = msg.path || '__root__';
  const pending = pendingLoads.get(reqId);
  pendingLoads.delete(reqId);
  loadingDirs.delete(msg.path);

  if (!pending) return;

  if (msg.error) {
    // 面B B3（ui-device-faces 批）：远端目录失败 = **显式错误行 + 重试链**，且已列
    // 元数据行保留（最后已知清单，不假装不存在）。本机腿维持现状（整容器错误行）。
    if (!explorerRootDevice) {
      pending.container.innerHTML = `<div class="explorer-error">${msg.error}</div>`;
      return;
    }
    pending.container.querySelector('.explorer-loading')?.remove();
    // 头部 chip 琥珀点（--status-warn-icon 载体；设备离线的可感知位）。
    document.querySelector('.explorer-header .dev-chip')?.classList.add('is-offline');
    const errRow = document.createElement('div');
    errRow.className = 'explorer-error';
    errRow.innerHTML =
      '<svg class="st-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/></svg>';
    const txt = document.createElement('span');
    txt.textContent = msg.error || '';
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'retry';
    retry.textContent = t('explorer.retry');
    retry.addEventListener('click', (e) => {
      e.stopPropagation();
      errRow.remove();
      loadingDirs.delete(msg.path || '');
      loadDir(pending.path, pending.container, pending.depth);
    });
    txt.appendChild(retry);
    errRow.appendChild(txt);
    pending.container.prepend(errRow);
    return;
  }

  // 成功腿：摘离线琥珀点（设备回来 = chip 回常态）。
  document.querySelector('.explorer-header .dev-chip')?.classList.remove('is-offline');
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
        // A >8MiB text file now arrives as a stream descriptor: it carries no
        // content, and the attachment pipeline cannot inline bytes it did not
        // receive. Say so explicitly instead of letting it degrade to the
        // pipeline's generic "looks binary" message (input.js stays untouched).
        error: msg.error
          || (msg.stream
            ? `file is too large to attach inline (${Math.round((msg.size || 0) / (1024 * 1024))}MB)`
            : null),
      },
    }));
    return;
  }
  if (msg.error) {
    // 面B B5（ui-device-faces 批）：远端单文件取件失败 —— 失败**绝不静默、绝不换源**
    // （G.12：离线就是错误，不落本机路径）：行复原为 failed 标记 + 底部玻璃浮条
    // （可读原因 + [重试][关闭]）。本机腿维持既有「骨架标签」纪律（F3）零变化。
    if (pendingPickups.has(msg.path)) {
      pendingPickups.delete(msg.path);
      setPickupState(msg.path, 'failed');
      showPickupFailureBar(msg.path, msg.error);
      return;
    }
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
  // 面B B5 成功腿：行复原（摘 loading 态）——既有查看器直开流程原样（文本内联 /
  // 二进制经 /api/nf-file 取本地 pickup 件，absPath = uploads 白名单子树）。
  if (pendingPickups.has(msg.path)) {
    pendingPickups.delete(msg.path);
    setPickupState(msg.path, null);
    hidePickupFailureBar();
  }
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
    // Text-stream descriptor passthrough (卡 §五 step ④): a >8MiB text file comes
    // back WITHOUT content + `stream:{v:1,kind:"text"}` + `mtimeMs`. canvas.js and
    // fileViewers.js consume these two fields; nothing else here changes.
    stream: msg.stream || null,
    mtimeMs: msg.mtimeMs || 0,
  };
  window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: item }));
});

// ── Public Init ────────────────────────────────────────────────────────

export function initExplorer() {
  // Restore persisted root on startup
  explorerRoot = loadPersistedRoot();
  updateExplorerTitle();

  // Wire folder picker button — opens the unified directory picker
  // (workspacePicker.js) in callback mode: the picked path becomes the tree
  // root (persisted; tree collapses and re-renders from the new root).
  const folderBtn = document.getElementById('explorer-folder-btn');
  if (folderBtn && !folderBtn._bound) {
    folderBtn._bound = true;
    folderBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      import('./workspacePicker.js').then(({ openPicker }) => {
        openPicker({
          startPath: explorerRoot || '~',
          title: t('workspacePicker.projectTitle'),
          onPick: (selectedPath) => {
            persistRoot(selectedPath);
            expandedDirs.clear();
            renderTree();
          },
        });
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

  // External drag-in: whole-section accept (Finder parity — the whole visible
  // panel receives OS file drops, not just tree zones). Bound once.
  const section = /** @type {any} */ (document.getElementById('explorer-section'));
  if (section && !section._extDropBound) {
    section._extDropBound = true;
    bindSectionExternalDrop(section);
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
  bindWatchReconnect();
  bindSlowReconcile();

  if (state.activeSessionId) {
    expandedDirs.clear();
    renderTree();
  }
}

// ── File operations (create, delete) ──────────────────────────────────

/** 远端根只读闸（ui-device-faces 批 · 面B 边界）：本批只开 listDir 元数据 + 单文件
 *  取件两条腿；create/delete/move 的 device 腿不存在，放行会把帧落**本机**同形路径
 *  （G.12 路径错觉 = 静默错写）。显式 toast 拒绝，绝不静默。 */
function refuseRemoteWrite() {
  if (!explorerRootDevice) return false;
  window.__showToast?.(t('explorer.remoteReadonly'), 'error');
  return true;
}

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
  if (refuseRemoteWrite()) return;
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

  // ⑤ 组字期间 Enter/Esc 交还输入法（非组字态行为逐键不变）。
  bindImeGuard(input);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
    if (e.key === 'Enter') { e.preventDefault(); commit(); }
    else if (e.key === 'Escape') { e.preventDefault(); cancel(); }
  });
  input.addEventListener('blur', commit);
}

/** Delete a file or folder. */
function deleteNode(path) {
  if (refuseRemoteWrite()) return;
  const name = path.split('/').pop();
  const send = () => sendWs({ type: 'deletePath', sessionId: state.activeSessionId, path, rootPath: explorerRoot });
  if (typeof window.__showConfirm === 'function') {
    window.__showConfirm(t('explorer.deleteTitle'), t('explorer.deleteConfirmOne', { name }), send);
  } else {
    send();
  }
}

/** Batch-delete every path in the current selection — one confirm, one WS message. */
function deleteSelected() {
  if (refuseRemoteWrite()) return;
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

/** writeFile responses for external drop-in: correlate in-flight writes
 *  (pendingExternalWrites, path-keyed) so folder leaves sequence correctly.
 *  Non-external editor saves are untouched (monacoEditor handles them). */
onMessage('fileSaved', (msg) => {
  const pending = msg.path && pendingExternalWrites.get(msg.path);
  if (pending) {
    pendingExternalWrites.delete(msg.path);
    pending.resolve();
  }
});
onMessage('fileSaveError', (msg) => {
  const pending = msg.path && pendingExternalWrites.get(msg.path);
  if (pending) {
    pendingExternalWrites.delete(msg.path);
    pending.reject(new Error(msg.error || 'write failed'));
  } else {
    window.__showToast?.(msg.error || 'File save failed', 'error');
  }
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

/**
 * Point the file explorer at `path` and reveal it (Project card → 工作区
 * 「在文件浏览器中打开」, §3.5).
 *
 * Same three steps the folder-picker callback performs (persistRoot → drop
 * expanded state → renderTree), plus revealing the Files panel: the picker
 * runs from inside the panel, this runs from a Canvas tab where the Side Bar
 * may be collapsed or showing another panel. showSidePanel (not a synthetic
 * #files-btn click) because the icon toggles the bar CLOSED when Files is
 * already the active panel.
 * @param {string} path — absolute workspace path
 * @param {string=} device — 远端 origin 设备（ui-device-faces 批 · 面B 入口对偶：
 *   机制缺省 {self, workspace} 的 UI 对偶；缺省 = 本机根，零 device 键）
 */
export function openExplorerAt(path, device) {
  if (!path) return;
  persistRoot(path, device || null);
  expandedDirs.clear();
  loadingDirs.clear();
  pendingLoads.clear();
  pendingPickups.clear();
  hidePickupFailureBar();
  clearSelection();
  showSidePanel('files');
  renderTree();
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
  // Session switch: re-subscribe to the new session's project root (renderTree
  // path) or drop the subscription entirely (no session → empty tree).
  syncWatch();
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

// ── Real-time watch (explorer-rt · chain-n-1981ce87 case C hybrid) ────
// Main path: server pushes `fsChanged{rootPath, dirs, overflow}` and the
// affected visible dirs reload through the SAME pipeline as the focus leg
// (loadDir → dirListing → expand/selection restore). Fallback legs —
// expand-reload, focus refresh, canvas tab-activation refresh — are untouched;
// a slow reconcile timer (below) covers OVERFLOW / reconnect event-loss
// windows. Failure toasts ride the existing `fileOpError` → `__showToast`
// channel; zero new visual components.

const WATCH_SYNC_THROTTLE_MS = 250; // collapse expand/collapse bursts
let watchRootSent = undefined;      // undefined = never subscribed; '' or abs path = current
let watchSyncTimer = null;

/** Visible dir set (root + every expanded dir), explorer-relative paths. */
function visibleWatchDirs() {
  return ['', ...[...expandedDirs]];
}

/** (Re-)send watchSubscribe for the current root + visible dir set; drops the
 *  previous subscription when the root changed. Idempotent — safe to call on
 *  every tree mutation. No active session ⇒ unsubscribe (empty tree). */
function syncWatch(immediate = false) {
  clearTimeout(watchSyncTimer);
  watchSyncTimer = setTimeout(() => {
    const rootPath = explorerRoot || '';
    // 远端根（面B）：watch 面是本机盘语义（对端无订阅腿）——不订阅、只清旧订阅，
    // 防止把远端根当本机路径挂上本机 watcher（路径错觉防护）。降级重列 = 既有
    // 慢对账定时器照常（展开即重列 + refreshExpandedDirs）。
    if (explorerRootDevice) {
      if (watchRootSent !== undefined) {
        sendWs({ type: 'watchUnsubscribe', sessionId: state.activeSessionId, rootPath: watchRootSent });
        watchRootSent = undefined;
      }
      return;
    }
    if (!state.activeSessionId) {
      if (watchRootSent !== undefined) {
        sendWs({ type: 'watchUnsubscribe', sessionId: state.activeSessionId, rootPath: watchRootSent });
        watchRootSent = undefined;
      }
      return;
    }
    if (watchRootSent !== undefined && watchRootSent !== rootPath) {
      sendWs({ type: 'watchUnsubscribe', sessionId: state.activeSessionId, rootPath: watchRootSent });
    }
    sendWs({ type: 'watchSubscribe', sessionId: state.activeSessionId, rootPath: explorerRoot, dirs: visibleWatchDirs() });
    watchRootSent = rootPath;
  }, immediate ? 0 : WATCH_SYNC_THROTTLE_MS);
}

/** Reload one visible directory's children container in place — same shape
 *  as the per-dir leg of refreshExpandedDirs (zero new render mechanism).
 *  Dirs that are not visible (collapsed/absent) are skipped: their next
 *  expand reloads from disk anyway. */
function reloadVisibleDir(dirPath) {
  if (dirPath) {
    if (!expandedDirs.has(dirPath)) return;
    const wrapper = document.querySelector(`.explorer-dir-wrapper[data-path="${CSS.escape(dirPath)}"]`);
    // Direct child only: a bare descendant query would match a nested
    // wrapper's container first on some structures (same reason
    // refreshExpandedDirs/getSiblingPaths scope their queries).
    const children = wrapper?.querySelector(':scope > .explorer-children');
    if (children && !loadingDirs.has(dirPath)) {
      children.innerHTML = '';
      loadDir(dirPath, children, dirPath.split('/').length);
    }
  } else {
    const rootChildren = document.querySelector('.explorer-root > .explorer-children');
    if (rootChildren && !loadingDirs.has('')) {
      rootChildren.innerHTML = '';
      loadDir('', rootChildren, 0);
    }
  }
}

onMessage('fsChanged', (msg) => {
  // Only frames for the root this panel currently watches.
  if ((msg.rootPath || '') !== (explorerRoot || '')) return;
  if (msg.overflow) {
    // Watch-service overflow: events were dropped server-side — tighten the
    // reconcile period (card: shorter cycle only after OVERFLOW/reconnect) and
    // degrade to a full visible-tree reload (equivalent to the focus leg).
    boostReconcile();
    if (document.querySelector('.explorer-root')) refreshExpandedDirs();
  } else {
    for (const d of (msg.dirs || [])) reloadVisibleDir(d);
  }
  // File content may have changed too — canvas re-checks the active file tab
  // (dirty-guarded, debounced, cooldown there; zero new mechanism).
  window.dispatchEvent(new CustomEvent('explorer-focus-refresh'));
});

// ── Slow reconcile (fallback leg #4) ──────────────────────────────────
// Covers the "silent event loss" residual surface: baseline 120 s, tightened
// to 60 s for 5 minutes after an OVERFLOW or a reconnect. Visibility-gated
// (hidden tabs skip the tick — same gate as the focus leg).

const RECONCILE_TICK_MS = 60000;        // timer granularity
const RECONCILE_BASE_MS = 120000;       // healthy-watch period
const RECONCILE_BOOST_MS = 60000;       // post-OVERFLOW/reconnect period
const RECONCILE_BOOST_WINDOW_MS = 300000;
let reconcileNextAllowedAt = 0;
let reconcileBoostUntil = 0;
let reconcileTimer = null;

function boostReconcile() {
  reconcileBoostUntil = Date.now() + RECONCILE_BOOST_WINDOW_MS;
}

function reconcileTick() {
  if (document.hidden) return;
  const now = Date.now();
  if (now < reconcileNextAllowedAt) return;
  reconcileNextAllowedAt = now + (now < reconcileBoostUntil ? RECONCILE_BOOST_MS : RECONCILE_BASE_MS);
  if (!document.querySelector('.explorer-root')) return;
  refreshExpandedDirs();
  window.dispatchEvent(new CustomEvent('explorer-focus-refresh'));
}

function bindSlowReconcile() {
  if (reconcileTimer) return;
  reconcileTimer = setInterval(reconcileTick, RECONCILE_TICK_MS);
}

/** Bind reconnect compensation once (called from initExplorer): subscriptions
 *  die with the connection (server clears the table on close) — re-subscribe
 *  immediately and run one full visible-tree reconcile. */
function bindWatchReconnect() {
  const win = /** @type {any} */ (window);
  if (win.__explorerWatchReconnectBound) return;
  win.__explorerWatchReconnectBound = true;
  onReconnect(() => {
    boostReconcile();
    syncWatch(true);
    if (document.querySelector('.explorer-root')) {
      refreshExpandedDirs();
      window.dispatchEvent(new CustomEvent('explorer-focus-refresh'));
    }
  });
}
