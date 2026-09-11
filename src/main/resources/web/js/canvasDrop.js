// canvasDrop.js — drop an OS file (Finder) onto a Canvas editing pane and
// insert a reference to it into the file being edited (canvasdrop batch,
// 2026-09-11).
//
// Author rulings implemented here (design spec §R1–R7, ruled 2026-09-11 —
// do not re-pick):
//   R1=①  the dropped image is COPIED into `<editedFileDir>/assets/`
//   R2=①  the inserted reference is a RELATIVE path — `![name](assets/x.png)`.
//         `~/…` forms, data URIs and gateway tokens are forbidden in documents
//   R3=②  same name → `name 2.png`, `name 3.png`, … — never overwrite
//   R4=④  non-image types (pdf/zip/csv/…) are out of scope for this batch, but
//         a drop MUST produce a visible notice — never a silent no-op
//   R5=②  every SOURCE-mode pane (the one holding a live Monaco handle) reacts;
//         rendered / read-only panes are a no-op + visible notice (never an
//         append-to-end fallback)
//   R6    batch drops, clipboard paste, screenshot paste and drag-to-tab-bar
//         belong to the follow-up batch (canvasdrop-impl-ext) — not
//         implemented and not stubbed here
//   R7    the 10「配图存」wordings are untouched (no edit, no unification)
//
// Write channel: the existing WS `writeFile` op with `encoding:'base64'`
// (WebSocketRoutes.scala `case "writeFile"`: client `rootPath` override → the
// session project root → `<dataRoot>/projects`; canonical containment check;
// `os.makeDir.all(basePath / os.up)`; base64 = byte-preserving). NO new
// endpoint, NO HTTP write path, NO relaxation of the containment check itself.
//
// Event discipline: preventDefault + stopPropagation are mandatory, in the
// CAPTURE phase at `#canvas-content`:
//   * the document-level handler in input.js (`initGlobalFileDrop`)
//     preventDefaults every file drag and then silently returns when the drop
//     is not over an input bar ("dropped outside any input bar — ignore") —
//     that swallow is exactly why "dragging a Finder file onto the Canvas does
//     nothing" today (same section-level + stopPropagation pattern as
//     explorer.js `bindSectionExternalDrop`).
//   * Monaco's vendor drop-into-editor provider listens on its own DOM inside
//     the pane; capturing at the pane container suppresses it before it can
//     inline a second, uncontrolled path line.

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';

/** Per-file byte cap — explorer parity (explorer.js EXTERNAL_IMPORT_MAX_BYTES). */
const MAX_DROP_BYTES = 20 * 1024 * 1024;
/** listDir probe / write round-trip guards (a local dir listing answers in ms). */
const LIST_TIMEOUT_MS = 3000;
const WRITE_TIMEOUT_MS = 30000;
/** How long the pane keeps its drop ring without a fresh dragover. */
const HIGHLIGHT_TTL_MS = 1200;

/** Image extensions accepted for a drop — mirrors the backend binary image
 *  entries (FileTypeRegistry.BuiltIn) and the /api/nf-file whitelist subset
 *  that matters here (11 entries: png jpg jpeg gif svg webp ico bmp avif
 *  tiff tif). Keep in sync with the backend table. */
const IMAGE_EXTS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'ico', 'avif', 'tiff', 'tif',
]);

/** Lowercased extension of a file name ('' when there is none). */
function extOf(name) {
  const i = String(name || '').lastIndexOf('.');
  return i > 0 ? String(name).slice(i + 1).toLowerCase() : '';
}

/** File name without its extension — used as the reference's alt text. */
function stemOf(name) {
  const s = String(name || '');
  const i = s.lastIndexOf('.');
  return i > 0 ? s.slice(0, i) : s;
}

/** True when the drag carries OS files (never our own internal app drags).
 *  Explorer file rows carry `application/x-nebflow-file`, tab rows
 *  `application/x-nebflow-ref` — neither sets `Files`/kind='file'. */
function hasExternalDrag(e) {
  if (!e.dataTransfer) return false;
  const items = e.dataTransfer.items;
  if (items && Array.from(items).some((i) => i.kind === 'file')) return true;
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

function toast(msg, kind) {
  /** @type {any} */ (window).__showToast?.(msg, kind || 'info');
}

/** Split an absolute path into its directory + separator (mirrors the path
 *  handling in viewers/markdown.js). */
function dirOf(absPath) {
  const sep = absPath.includes('\\') ? '\\' : '/';
  const cut = absPath.lastIndexOf(sep);
  return { dir: cut > 0 ? absPath.slice(0, cut) : '', sep };
}

// ── Directory listing probes (R3 name-collision check) ────────────────────
// In-flight probes keyed by the ABSOLUTE directory path requested. The
// dirListing frame echoes `path` — except on error (the backend's error frame
// carries no `path`, so it cannot be correlated). Callers therefore probe only
// directories that exist under the root they pass, which keeps the error frame
// out of the happy path; a timeout degrades to "cannot tell" → the original
// name, exactly like explorer's scanDirNames.
const pendingProbes = new Map();  // absDirPath → (names: Set<string>) => void

onMessage('dirListing', (msg) => {
  if (!msg || msg.error || !msg.path) return;
  const done = pendingProbes.get(msg.path);
  if (!done) return;
  pendingProbes.delete(msg.path);
  done(new Set((msg.entries || []).map((/** @type {any} */ entry) => entry.name)));
});

/**
 * Names inside an existing directory (absolute path, same root as the write).
 * Resolves an empty set on timeout so the caller still proceeds.
 * @param {string} absDir
 * @param {string|null} rootPath
 * @returns {Promise<Set<string>>}
 */
function scanDirNames(absDir, rootPath) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (/** @type {Set<string>} */ names) => {
      if (settled) return;
      settled = true;
      pendingProbes.delete(absDir);
      resolve(names);
    };
    pendingProbes.set(absDir, done);
    sendWs({
      type: 'listDir', sessionId: state.activeSessionId,
      path: absDir, rootPath,
    });
    setTimeout(() => done(new Set()), LIST_TIMEOUT_MS);
  });
}

/** R3=② name bumping: `name.png` → `name 2.png` → `name 3.png` … , never
 *  overwrite. (Same family as explorer's uniqueDropName — that one spells the
 *  suffix `foo copy 2.txt`; the ruling for this feature is `name 2.png`.) */
function uniqueAssetName(name, taken) {
  if (!taken.has(name)) return name;
  const i = name.lastIndexOf('.');
  const stem = i > 0 ? name.slice(0, i) : name;
  const ext = i > 0 ? name.slice(i) : '';
  for (let n = 2; ; n++) {
    const candidate = `${stem} ${n}${ext}`;
    if (!taken.has(candidate)) return candidate;
  }
}

// ── Writes ───────────────────────────────────────────────────────────────

/**
 * Write one file through the WS `writeFile` op (base64 = byte preserving).
 * Resolves when the `fileSaved` frame for this exact path arrives; rejects on
 * `fileSaveError` (which includes the containment refusal — the backend checks
 * containment BEFORE writing, so a refused write never leaves a partial file).
 * @param {string} pathStr — absolute target path
 * @param {string|null} rootPath — client root override ('' / null = server-resolved)
 * @param {File} file
 * @returns {Promise<void>}
 */
async function writeAsset(pathStr, rootPath, file) {
  const b64 = await fileToBase64(file);
  return new Promise((resolve, reject) => {
    const cleanup = () => { offOk(); offErr(); };
    const offOk = onMessage('fileSaved', (msg) => {
      if (msg.path !== pathStr) return;
      cleanup();
      resolve();
    });
    const offErr = onMessage('fileSaveError', (msg) => {
      if (msg.path !== pathStr) return;
      cleanup();
      reject(new Error(msg.error || 'write failed'));
    });
    sendWs({
      type: 'writeFile', sessionId: state.activeSessionId,
      path: pathStr, content: b64, encoding: 'base64', rootPath,
    });
    setTimeout(() => {
      cleanup();
      reject(new Error('write timed out'));
    }, WRITE_TIMEOUT_MS);
  });
}

/**
 * Write the asset, falling back to a `rootPath` override when the primary root
 * refuses containment (spec §R1 fallback rule: "带 `rootPath` 覆盖重试，或可见
 * 错误提示"). The retry root is the edited file's own directory — the asset
 * lands in `<that dir>/assets/`, so the override never widens the write beyond
 * the directory the document itself lives in, and it reuses the existing
 * client-override parameter (no backend change).
 * @returns {Promise<{ root: string|null, retried: boolean }>}
 */
async function writeAssetWithFallback(absTarget, primaryRoot, mdDir, file) {
  try {
    await writeAsset(absTarget, primaryRoot, file);
    return { root: primaryRoot, retried: false };
  } catch (err) {
    const msg = String((err && err.message) || err);
    if (!/outside project root/i.test(msg) || primaryRoot === mdDir) throw err;
    await writeAsset(absTarget, mdDir, file);
    return { root: mdDir, retried: true };
  }
}

// ── Insertion ────────────────────────────────────────────────────────────

/** Reference form per pane type (R5=②: every source-mode pane reacts; the
 *  form follows the syntax of the file being edited). */
function referenceText(itemType, relPath, alt) {
  switch (itemType) {
    case 'markdown': {
      // A destination containing whitespace / parentheses / angle brackets is
      // only a valid CommonMark link destination inside `<…>` — without it the
      // parser leaves the whole thing as plain text and nothing renders (this
      // is exactly the R3 name-bump case: `name 2.png`). `<…>` is stripped by
      // the parser, so the rendered `src` is the plain relative path.
      const dest = /[\s()<>\\]/.test(relPath) ? `<${relPath}>` : relPath;
      return `![${alt.replace(/[[\]]/g, '')}](${dest})`;
    }
    case 'html':
      return `<img src="${relPath}" alt="${alt.replace(/[&<>"]/g, '')}">`;
    case 'json':
      // A bare path would be invalid JSON — insert a string literal instead.
      return `"${relPath}"`;
    default:
      // code / csv / plain text: the relative path itself is the useful form.
      return relPath;
  }
}

/** Insert `text` at the editor's cursor (selection replaced). One undo stop;
 *  the text before and after the cursor is untouched (acceptance A2). */
function insertAtCursor(handle, text) {
  const editor = handle.editor;
  const range = editor.getSelection() || {
    startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 1,
  };
  editor.pushUndoStop();
  editor.executeEdits('canvasDrop', [{ range, text, forceMoveMarkers: true }]);
  editor.pushUndoStop();
  // Caret stays right after the inserted reference (forceMoveMarkers), so a
  // second drop appends next to it instead of landing at the document start.
  editor.focus();
}

// ── Drop handling ────────────────────────────────────────────────────────

/** Pane → its tab target (absPath / rootPath / itemType); supplied by
 *  canvas.js, which owns the tab registry. */
/** @type {(pane: HTMLElement) => { absPath?: string, rootPath: string|null, itemType?: string } | null} */
let resolveTargetOf = () => null;

/**
 * @param {HTMLElement} pane — the canvas tab pane the drop landed on
 * @param {DataTransfer} dt
 */
async function handleDrop(pane, dt) {
  const files = Array.from(dt.files || []);
  if (files.length === 0) return;                       // hasExternalDrag already filtered
  if (files.length > 1) {
    // Batch drops are out of scope (R6, follow-up batch) — but never silent.
    toast(t('canvas.dropBatchUnsupported'));
    return;
  }
  const file = files[0];

  // R5=② — the single source-mode criterion used everywhere else in the
  // codebase: a live Monaco handle on the pane (canvas.js isTabDirty).
  const handle = /** @type {any} */ (pane)._editorHandle;
  if (!handle || !handle.editor || !handle.model) {
    toast(t('canvas.dropNotEditable'));
    return;
  }
  const target = resolveTargetOf(pane);
  if (!target || !target.absPath) {
    toast(t('canvas.dropNoTarget'));
    return;
  }
  // R4=④ — non-image types get a visible notice and nothing else.
  if (!IMAGE_EXTS.has(extOf(file.name))) {
    toast(t('canvas.dropUnsupported', { name: file.name }));
    return;
  }
  if (file.size > MAX_DROP_BYTES) {
    toast(t('canvas.dropTooLarge', { name: file.name }), 'error');
    return;
  }

  const { dir, sep } = dirOf(target.absPath);
  if (!dir) {
    toast(t('canvas.dropNoTarget'));
    return;
  }
  const assetsDir = `${dir}${sep}assets`;

  try {
    // R3 — probe for a name collision. The probe root is the document's OWN
    // directory, not the tab's write root: `<dir>` contains `<dir>/assets` by
    // construction, so the listing can never be refused by containment and
    // never has to fall back to its timeout. (This is a read-only listing of
    // the directory the open document already lives in — the same client
    // `rootPath` override the write path accepts.) The probes only touch
    // directories that exist today; a missing assets/ dir means "no collision
    // possible" (the backend creates it on demand during the write).
    let name = file.name;
    const siblings = await scanDirNames(dir, dir);
    if (siblings.has('assets')) {
      name = uniqueAssetName(file.name, await scanDirNames(assetsDir, dir));
    }

    const absTarget = `${assetsDir}${sep}${name}`;
    const written = await writeAssetWithFallback(absTarget, target.rootPath, dir, file);

    // R2=① — a relative reference; the document stays portable and carries no
    // token. The md/html viewers resolve it against the file's own dir.
    const relRef = `assets/${name}`;
    insertAtCursor(handle, referenceText(target.itemType, relRef, stemOf(name)));
    toast(t('canvas.dropInserted', { ref: relRef }), 'success');
    if (written.retried) {
      console.warn('[canvasDrop] containment refused the tab root; wrote with a rootPath override', dir);
    }
  } catch (err) {
    const msg = String((err && err.message) || err);
    console.error('[canvasDrop] failed:', msg);
    toast(t('canvas.dropFailed', { error: msg }), 'error');
  }
}

/**
 * Bind the external-file drop channel for the Canvas content area.
 *
 * The listeners sit on `document` in the CAPTURE phase and bail out unless the
 * pointer is over `contentEl` — binding them on `contentEl` itself would miss a
 * drop that lands on an overlay covering the pane (the toast that this very
 * feature raises sits fixed in the bottom-right corner, i.e. over the Canvas
 * panel). The hit test is a rect check, so no event ever reaches the pane's
 * own listeners when the drop is elsewhere.
 *
 * @param {HTMLElement} contentEl — `#canvas-content`
 * @param {(pane: HTMLElement) => { absPath?: string, rootPath: string|null, itemType?: string } | null} resolvePane
 *        — pane → its tab target (absPath / rootPath / itemType); null when the
 *        pane has no file behind it (panel tabs).
 */
export function initCanvasDrop(contentEl, resolvePane) {
  if (!contentEl || /** @type {any} */ (contentEl)._canvasDropBound) return;
  /** @type {any} */ (contentEl)._canvasDropBound = true;
  resolveTargetOf = resolvePane;

  /** @type {HTMLElement|null} */ let hlPane = null;
  /** @type {any} */ let hlTimer = 0;

  const clearHighlight = () => {
    if (hlTimer) { clearTimeout(hlTimer); hlTimer = 0; }
    if (hlPane) { hlPane.classList.remove('canvas-drop-target'); hlPane = null; }
  };
  const highlight = (pane) => {
    if (hlPane !== pane) {
      if (hlPane) hlPane.classList.remove('canvas-drop-target');
      pane.classList.add('canvas-drop-target');
      hlPane = pane;
    }
    // dragover repeats while the drag stays over us (HTML DnD processing
    // model) — the TTL clears the ring when it stops (dropped elsewhere,
    // drag aborted outside the window).
    if (hlTimer) clearTimeout(hlTimer);
    hlTimer = setTimeout(clearHighlight, HIGHLIGHT_TTL_MS);
  };
  /** Pointer over the Canvas content area (rect test, overlay-proof). */
  const overCanvas = (e) => {
    const r = contentEl.getBoundingClientRect();
    return r.width > 0 && r.height > 0
      && e.clientX >= r.left && e.clientX <= r.right
      && e.clientY >= r.top && e.clientY <= r.bottom;
  };
  /** Innermost pane under the cursor, falling back to the active tab's pane. */
  const paneAt = (e) => {
    const hit = e.target instanceof Element ? e.target.closest('.canvas-tab-pane') : null;
    if (hit instanceof HTMLElement) return hit;
    const active = contentEl.querySelector('.canvas-tab-pane.active');
    return active instanceof HTMLElement ? active : null;
  };

  document.addEventListener('dragover', (e) => {
    if (!hasExternalDrag(e) || !overCanvas(e)) return;
    const pane = paneAt(e);
    if (!pane) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
    highlight(pane);
  }, true);

  document.addEventListener('dragleave', (e) => {
    if (!hlPane) return;
    // Moving between the pane's own descendants must not clear the ring;
    // relatedTarget is null on some engines (then the TTL covers us).
    if (e.relatedTarget && hlPane.contains(/** @type {Node} */ (e.relatedTarget))) return;
    if (!overCanvas(e)) clearHighlight();
  }, true);

  document.addEventListener('drop', (e) => {
    if (!hasExternalDrag(e) || !overCanvas(e)) return;
    // Own the drop: suppress the document-level swallow (input.js) and the
    // Monaco default provider in one move, before either can run.
    e.preventDefault();
    e.stopPropagation();
    const pane = paneAt(e);
    const dt = e.dataTransfer;
    clearHighlight();
    if (!pane || !dt) return;
    void handleDrop(pane, dt);
  }, true);

  window.addEventListener('dragend', clearHighlight);
}
