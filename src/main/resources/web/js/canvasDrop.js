// canvasDrop.js — drop / paste an OS file (Finder) into a Canvas editing pane
// and insert a reference to it into the file being edited (canvasdrop batch,
// 2026-09-11; extended for the R6 items, 2026-09-11).
//
// Author rulings implemented here (design spec §R1–R7, ruled 2026-09-11 —
// do not re-pick):
//   R1=①  the dropped image is COPIED into `<editedFileDir>/assets/`
//   R1′    (dispatcher ruling 2026-09-11, supersedes the spec §R1 fallback
//         rule) when the edited document sits OUTSIDE the write root the write
//         is ABORTED with a visible, actionable notice — the client `rootPath`
//         override is never used to force the write through (client bare value,
//         registered gap W7 "over-writable")
//   R2=①  the inserted reference is a RELATIVE path — `![name](assets/x.png)`.
//         `~/…` forms, data URIs and gateway tokens are forbidden in documents
//   R3=②  same name → `name 2.png`, `name 3.png`, … — never overwrite
//   R4=④  non-image types (pdf/zip/csv/…) are out of scope for this batch, but
//         every drop/paste MUST produce a visible notice — never a silent no-op
//   R5=②  every SOURCE-mode pane (the one holding a live Monaco handle) reacts;
//         rendered / read-only panes are a no-op + visible notice (never an
//         append-to-end fallback)
//   R6    re-scoped by the author (2026-09-11) into this batch:
//           ① batch drop — many files at once, `files[]` order, one reference
//              per file at the cursor, no in-batch overwrite
//           ② clipboard paste (⌘V) — same pipeline as a drop
//           ③ screenshot paste — the clipboard image is just another ②
//           ④ OS file dropped on `#canvas-tab-bar` → opened as a new tab
//   R7    the 10「配图存」wordings are untouched (no edit, no unification)
//
// Write channel: the existing WS `writeFile` op with `encoding:'base64'`
// (WebSocketRoutes.scala `case "writeFile"`: client `rootPath` override → the
// session project root → `<dataRoot>/projects`; canonical containment check;
// `os.makeDir.all(basePath / os.up)`; base64 = byte-preserving). NO new
// endpoint, NO HTTP write path, NO relaxation of the containment check itself.
//
// Event discipline: preventDefault + stopPropagation are mandatory, in the
// CAPTURE phase on `document`:
//   * the document-level handler in input.js (`initGlobalFileDrop`)
//     preventDefaults every file drag and then silently returns when the drop
//     is not over an input bar ("dropped outside any input bar — ignore") —
//     that swallow is exactly why "dragging a Finder file onto the Canvas does
//     nothing" today (same section-level + stopPropagation pattern as
//     explorer.js `bindSectionExternalDrop`).
//   * Monaco's vendor drop-into-editor provider listens on its own DOM inside
//     the pane; capturing above it suppresses a second, uncontrolled path line.
//   * the tab-bar branch must not swallow tab reordering: a reorder carries no
//     `Files` payload, so `hasExternalDrag` filters it out before any
//     preventDefault, and canvas.js keeps owning `dragstart`/`drop` per tab.
//   * `input.js:1335` has a document-level `paste` listener that turns ANY
//     clipboard image into a chat attachment — capturing the paste that lands
//     inside the Canvas preempts it (capture runs before the bubble listener).

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';

/** Per-file byte cap — explorer parity (explorer.js EXTERNAL_IMPORT_MAX_BYTES). */
const MAX_DROP_BYTES = 20 * 1024 * 1024;
/** listDir probe / write round-trip guards (a local dir listing answers in ms). */
const LIST_TIMEOUT_MS = 3000;
const WRITE_TIMEOUT_MS = 30000;
/** How long the drop ring stays without a fresh dragover. */
const HIGHLIGHT_TTL_MS = 1200;
/** Tab-bar drops (R6 ④) land in `<write root>/assets/` — the tab strip has no
 *  document to anchor on, so the workspace root is the R1 ① generalization. */
const ROOT_ASSETS_REL = 'assets';

/** Image extensions accepted for a drop — mirrors the backend binary image
 *  entries (FileTypeRegistry.BuiltIn) and the /api/nf-file whitelist subset
 *  that matters here (11 entries: png jpg jpeg gif svg webp ico bmp avif
 *  tiff tif). Keep in sync with the backend table. */
const IMAGE_EXTS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'ico', 'avif', 'tiff', 'tif',
]);

/** The droppable extension list, once — every notice that names the list
 *  interpolates this (`{types}`); the sentence itself lives in the locales. */
const IMAGE_LIST = Array.from(IMAGE_EXTS).join(' / ');

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
 *  `application/x-nebflow-ref`/`text/plain` — neither sets `Files`/kind='file'.
 *  Keeping this strict is what lets a tab REORDER pass through untouched. */
function hasExternalDrag(e) {
  if (!e.dataTransfer) return false;
  const items = e.dataTransfer.items;
  if (items && Array.from(items).some((i) => i.kind === 'file')) return true;
  return Array.from(e.dataTransfer.types || []).includes('Files');
}

/** The File objects of a drag/drop or clipboard payload (drop → `files`,
 *  clipboard → `items[].getAsFile()` on older engines). */
function filesOf(dataTransfer) {
  const files = Array.from(dataTransfer.files || []);
  if (files.length) return files;
  const out = [];
  for (const item of Array.from(dataTransfer.items || [])) {
    if (item.kind !== 'file') continue;
    const f = typeof item.getAsFile === 'function' ? item.getAsFile() : null;
    if (f) out.push(f);
  }
  return out;
}

/** A clipboard screenshot arrives with no usable name on some engines — give it
 *  one derived from its MIME type so the asset on disk is self-describing
 *  (the name still goes through the R3 bump, so repeats never overwrite). */
function nameOfFile(file) {
  const name = String(file.name || '');
  if (name) return name;
  const mime = String(file.type || '');
  const ext = mime.startsWith('image/') ? mime.slice(6).replace(/^jpeg$/, 'jpg') : 'png';
  return `screenshot.${ext}`;
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

// ── Directory listing probes ──────────────────────────────────────────────
// In-flight probes keyed by the path string SENT (the dirListing frame echoes
// `path`, and now also carries `resolvedPath`). Error frames carry no path, so
// only RELATIVE-path probes are resolved by the error branch — a relative probe
// targets a directory that may legitimately not exist yet. At most one such
// probe is ever in flight (every caller awaits its predecessor), and a timeout
// still degrades to "cannot tell" → the original name, exactly like explorer's
// scanDirNames.
const pendingProbes = new Map();  // sentPath → (result) => void

/** @param {string} p */
const isAbsolutePath = (p) => typeof p === 'string'
  && (p.startsWith('/') || p.startsWith('\\\\') || /^[A-Za-z]:[\\/]/.test(p));

onMessage('dirListing', (msg) => {
  if (!msg) return;
  if (msg.error) {
    for (const [sent, done] of Array.from(pendingProbes)) {
      if (isAbsolutePath(sent)) continue;
      pendingProbes.delete(sent);
      done({ resolvedPath: null, names: new Set() });
    }
    return;
  }
  if (!msg.path) return;
  const done = pendingProbes.get(msg.path);
  if (!done) return;
  pendingProbes.delete(msg.path);
  done({
    resolvedPath: typeof msg.resolvedPath === 'string' ? msg.resolvedPath : null,
    names: new Set((msg.entries || []).map((/** @type {any} */ entry) => entry.name)),
  });
});

/**
 * List one directory through the WS listDir op.
 * @param {string} dirPath — absolute, or relative to the resolved write root
 * @param {string|null} rootPath — client root override (null = server-resolved)
 * @returns {Promise<{ resolvedPath: string|null, names: Set<string> }>}
 *   resolvedPath = the absolute directory the server actually listed (null when
 *   the directory does not exist yet / the answer was lost).
 */
function probeDir(dirPath, rootPath) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (/** @type {{ resolvedPath: string|null, names: Set<string> }} */ result) => {
      if (settled) return;
      settled = true;
      pendingProbes.delete(dirPath);
      resolve(result);
    };
    pendingProbes.set(dirPath, done);
    sendWs({
      type: 'listDir', sessionId: state.activeSessionId,
      path: dirPath, rootPath,
    });
    setTimeout(() => done({ resolvedPath: null, names: new Set() }), LIST_TIMEOUT_MS);
  });
}

/** Existing names inside `<dir>/assets/` (R3 probe). The probe root is the
 *  document's OWN directory: `<dir>` contains `<dir>/assets` by construction,
 *  so the listing can never be refused by containment and never has to fall
 *  back to its timeout. A missing assets/ dir means "no collision possible"
 *  (the backend creates it on demand during the write).
 * @param {string} dir @param {string} sep
 * @returns {Promise<Set<string>>} */
async function assetNamesOf(dir, sep) {
  const sibling = await probeDir(dir, dir);
  if (!sibling.names.has('assets')) return new Set();
  return (await probeDir(`${dir}${sep}assets`, dir)).names;
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
 * @param {string} pathStr — absolute target path, or one relative to `rootPath`
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

/** True when a writeFile refusal came from the backend containment check. */
function isOutsideRoot(msg) {
  return /outside project root/i.test(String(msg || ''));
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
  // Caret stays right after the inserted reference (forceMoveMarkers), so the
  // next file of the batch lands next to it, in files[] order.
  editor.focus();
}

// ── Drop / paste handling ────────────────────────────────────────────────

/** Pane → its tab target (absPath / rootPath / itemType); supplied by
 *  canvas.js, which owns the tab registry. */
/** @type {(pane: HTMLElement) => { absPath?: string, rootPath: string|null, itemType?: string } | null} */
let resolveTargetOf = () => null;

/**
 * Copy every image of `files` into `<editedFileDir>/assets/` and insert one
 * relative reference per file at the cursor, in `files[]` order (R6 ①②③ —
 * drops, clipboard pastes and screenshots all come through here; there is
 * deliberately no second implementation).
 *
 * @param {HTMLElement} pane — the canvas tab pane the drop/paste landed on
 * @param {File[]} files
 * @param {'drop'|'paste'} source — only picks the notice wording
 */
async function handleFiles(pane, files, source) {
  if (!files.length) return;
  const paste = source === 'paste';

  // R5=② — the single source-mode criterion used everywhere else in the
  // codebase: a live Monaco handle on the pane (canvas.js isTabDirty).
  const handle = /** @type {any} */ (pane)._editorHandle;
  if (!handle || !handle.editor || !handle.model) {
    toast(t(paste ? 'canvas.pasteNotEditable' : 'canvas.dropNotEditable'));
    return;
  }
  const target = resolveTargetOf(pane);
  if (!target || !target.absPath) {
    toast(t(paste ? 'canvas.pasteNoTarget' : 'canvas.dropNoTarget'));
    return;
  }
  const { dir, sep } = dirOf(target.absPath);
  if (!dir) {
    toast(t(paste ? 'canvas.pasteNoTarget' : 'canvas.dropNoTarget'));
    return;
  }
  const assetsDir = `${dir}${sep}assets`;

  // R3 — one name probe per batch (lazily: a batch with nothing acceptable
  // costs no round trip). `taken` accumulates the names this batch claims, so
  // two same-named files inside one batch bump instead of overwriting.
  /** @type {Set<string>|null} */ let taken = null;
  /** @type {string[]} */ const inserted = [];

  for (const file of files) {
    const name0 = nameOfFile(file);
    // R4=④ — non-image types get a visible notice and nothing else.
    if (!IMAGE_EXTS.has(extOf(name0))) {
      toast(t(paste ? 'canvas.pasteUnsupported' : 'canvas.dropUnsupported',
        { name: name0, types: IMAGE_LIST }));
      continue;
    }
    if (file.size > MAX_DROP_BYTES) {
      toast(t('canvas.dropTooLarge', { name: name0 }), 'error');
      continue;
    }
    if (!taken) taken = await assetNamesOf(dir, sep);
    const name = uniqueAssetName(name0, taken);
    taken.add(name);
    const absTarget = `${assetsDir}${sep}${name}`;
    try {
      // Dispatcher ruling 2026-09-11 (supersedes the spec's §R1 fallback rule):
      // a document whose directory sits OUTSIDE the write root is NOT written
      // through a client `rootPath` override — that parameter is a bare client
      // value (registered gap W7, "over-writable"). The write is aborted with a
      // visible, actionable notice instead; nothing is retried, nothing partial
      // is left behind (the backend refuses BEFORE writing).
      await writeAsset(absTarget, target.rootPath, file);
      // R2=① — a relative reference; the document stays portable and carries no
      // token. The md/html viewers resolve it against the file's own dir.
      insertAtCursor(handle, referenceText(target.itemType, `assets/${name}`, stemOf(name)));
      inserted.push(`assets/${name}`);
    } catch (err) {
      const msg = String((err && err.message) || err);
      console.error('[canvasDrop] failed:', msg);
      toast(t(isOutsideRoot(msg) ? 'canvas.dropOutsideRoot' : 'canvas.dropFailed',
        { name, error: msg }), 'error');
    }
  }

  if (inserted.length === 1 && files.length === 1) {
    toast(t(paste ? 'canvas.pasteInserted' : 'canvas.dropInserted', { ref: inserted[0] }), 'success');
  } else if (inserted.length > 0) {
    toast(t('canvas.dropBatchInserted', { count: inserted.length }), 'success');
  }
}

/** Hand a written file to the canonical Canvas open channel — the same
 *  `workspace-open-item` event explorer.js dispatches after a readFile (no
 *  second opener, no second tab registry). */
function openAsTab(absPath, name, size) {
  window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
    id: `file:${absPath}`,
    title: name,
    itemType: 'image',
    content: '',
    absPath,
    size,
    path: absPath,
    rootPath: null,
    pinned: true,
  }}));
}

/**
 * R6 ④ — an OS image dropped on the tab strip opens as a NEW tab (browser
 * behaviour). The bytes must become readable by `/api/nf-file` first, so they
 * go through the SAME writeFile pipeline; with no document behind the tab
 * strip the write is anchored at the server-resolved session root
 * (`rootPath: null` + a relative path — containment holds by construction) and
 * the absolute directory is read back from listDir's `resolvedPath`. Nothing is
 * inserted anywhere; tab REORDERING never reaches here (`hasExternalDrag`).
 *
 * @param {File[]} files
 */
async function handleTabBarFiles(files) {
  if (!files.length) return;
  let probe = await probeDir(ROOT_ASSETS_REL, null);
  const taken = new Set(probe.names);

  for (const file of files) {
    const name0 = nameOfFile(file);
    if (!IMAGE_EXTS.has(extOf(name0))) {
      toast(t('canvas.dropUnsupported', { name: name0, types: IMAGE_LIST }));
      continue;
    }
    if (file.size > MAX_DROP_BYTES) {
      toast(t('canvas.dropTooLarge', { name: name0 }), 'error');
      continue;
    }
    const name = uniqueAssetName(name0, taken);
    taken.add(name);
    const relPath = `${ROOT_ASSETS_REL}/${name}`;
    try {
      await writeAsset(relPath, null, file);
      if (!probe.resolvedPath) probe = await probeDir(ROOT_ASSETS_REL, null);
      if (!probe.resolvedPath) throw new Error('write root could not be resolved');
      openAsTab(`${probe.resolvedPath}/${name}`, name, file.size);
      toast(t('canvas.tabDropOpened', { name }), 'success');
    } catch (err) {
      const msg = String((err && err.message) || err);
      console.error('[canvasDrop] tab-bar open failed:', msg);
      toast(t('canvas.dropFailed', { error: msg }), 'error');
    }
  }
}

/**
 * Bind the external-file channels of the Canvas: file drop onto an editing
 * pane (R1–R5), clipboard paste / screenshot paste (R6 ②③) and OS-file drop on
 * the tab strip → open as a new tab (R6 ④).
 *
 * The drop listeners sit on `document` in the CAPTURE phase and bail out unless
 * the pointer is over `contentEl` or `tabBarEl` — binding them on those
 * elements would miss a drop that lands on an overlay covering them (the toast
 * this very feature raises sits fixed in the bottom-right corner, i.e. over the
 * Canvas panel). The hit test is a rect check, so no event ever reaches the
 * pane's own listeners when the drop is elsewhere.
 *
 * @param {HTMLElement} contentEl — `#canvas-content`
 * @param {(pane: HTMLElement) => { absPath?: string, rootPath: string|null, itemType?: string } | null} resolvePane
 *        — pane → its tab target (absPath / rootPath / itemType); null when the
 *        pane has no file behind it (panel tabs).
 * @param {?HTMLElement} [tabBarEl] - `#canvas-tab-bar`: OS files dropped on the tab strip open as a new tab (R6 item 4).
 */
export function initCanvasDrop(contentEl, resolvePane, tabBarEl) {
  if (!contentEl || /** @type {any} */ (contentEl)._canvasDropBound) return;
  /** @type {any} */ (contentEl)._canvasDropBound = true;
  resolveTargetOf = resolvePane;

  /** @type {HTMLElement|null} */ let hlEl = null;
  /** @type {any} */ let hlTimer = 0;

  const clearHighlight = () => {
    if (hlTimer) { clearTimeout(hlTimer); hlTimer = 0; }
    if (hlEl) { hlEl.classList.remove('canvas-drop-target'); hlEl = null; }
  };
  const highlight = (el) => {
    if (hlEl !== el) {
      if (hlEl) hlEl.classList.remove('canvas-drop-target');
      el.classList.add('canvas-drop-target');
      hlEl = el;
    }
    // dragover repeats while the drag stays over us (HTML DnD processing
    // model) — the TTL clears the ring when it stops (dropped elsewhere,
    // drag aborted outside the window).
    if (hlTimer) clearTimeout(hlTimer);
    hlTimer = setTimeout(clearHighlight, HIGHLIGHT_TTL_MS);
  };
  /** Pointer over an element (rect test, overlay-proof). */
  const overRect = (el, e) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0
      && e.clientX >= r.left && e.clientX <= r.right
      && e.clientY >= r.top && e.clientY <= r.bottom;
  };
  const overCanvas = (e) => overRect(contentEl, e);
  const overTabBar = (e) => overRect(tabBarEl, e);
  /** Innermost pane under the cursor, falling back to the active tab's pane. */
  const paneAt = (e) => {
    const hit = e.target instanceof Element ? e.target.closest('.canvas-tab-pane') : null;
    if (hit instanceof HTMLElement) return hit;
    const active = contentEl.querySelector('.canvas-tab-pane.active');
    return active instanceof HTMLElement ? active : null;
  };
  /** Pane that owns the focused element (paste has no pointer coordinates). */
  const paneOfFocus = (e) => {
    const el = e.target instanceof Element ? e.target : null;
    if (!el || !contentEl.contains(el)) return null;
    const hit = el.closest('.canvas-tab-pane');
    if (hit instanceof HTMLElement) return hit;
    const active = contentEl.querySelector('.canvas-tab-pane.active');
    return active instanceof HTMLElement ? active : null;
  };

  document.addEventListener('dragover', (e) => {
    if (!hasExternalDrag(e)) return;
    // R6 ④ — the tab strip takes the drop as "open this file", not as a reorder
    // (reorders have no Files payload, so they never get here).
    if (overTabBar(e)) {
      e.preventDefault();
      e.stopPropagation();
      if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
      highlight(tabBarEl);
      return;
    }
    if (!overCanvas(e)) return;
    const pane = paneAt(e);
    if (!pane) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
    highlight(pane);
  }, true);

  document.addEventListener('dragleave', (e) => {
    if (!hlEl) return;
    // Moving between the highlighted element's own descendants must not clear
    // the ring; relatedTarget is null on some engines (then the TTL covers us).
    if (e.relatedTarget && hlEl.contains(/** @type {Node} */ (e.relatedTarget))) return;
    if (!overCanvas(e) && !overTabBar(e)) clearHighlight();
  }, true);

  document.addEventListener('drop', (e) => {
    if (!hasExternalDrag(e)) return;
    const onTabBar = overTabBar(e);
    if (!onTabBar && !overCanvas(e)) return;
    // Own the drop: suppress the document-level swallow (input.js) and the
    // Monaco default provider in one move, before either can run.
    e.preventDefault();
    e.stopPropagation();
    const dt = e.dataTransfer;
    clearHighlight();
    if (!dt) return;
    if (onTabBar) { void handleTabBarFiles(filesOf(dt)); return; }
    const pane = paneAt(e);
    if (!pane) return;
    void handleFiles(pane, filesOf(dt), 'drop');
  }, true);

  // R6 ②③ — clipboard paste (⌘V) of an image / copied file / screenshot, the
  // same pipeline as a drop. Only pastes that land INSIDE the Canvas are ours:
  // everything else (chat input, other panels) stays with its own handler.
  document.addEventListener('paste', (e) => {
    const dt = /** @type {ClipboardEvent} */ (e).clipboardData;
    if (!dt) return;
    // Paste has no coordinates, so the focus target decides: anything outside
    // the Canvas (chat input, other panels) keeps its own handler.
    const pane = paneOfFocus(e);
    if (!pane) return;
    const box = contentEl.getBoundingClientRect();
    if (box.width <= 0 || box.height <= 0) return;   // panel collapsed — nothing to aim at
    const files = filesOf(dt);
    if (!files.length) return;   // ordinary text paste — untouched
    e.preventDefault();
    e.stopPropagation();         // the document-level image→attachment paste must not double-consume
    void handleFiles(pane, files, 'paste');
  }, true);

  window.addEventListener('dragend', clearHighlight);
}
