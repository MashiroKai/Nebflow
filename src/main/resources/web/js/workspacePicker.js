// workspacePicker.js — the ONE in-app directory picker (pickeruni batch, 2026-09-26).
//
// Every directory-picking entry point in the app opens THIS dialog:
//   · chat.js ProjectCreate askUser dirPicker card (2026-09-06 author ruling:
//     in-app browser, never the OS directory dialog)
//   · projectTab.js FAB layer D4 path field
//   · explorer.js header "change root" button (pickeruni batch)
//   · sidebar.js folder context-menu "set project root" (pickeruni batch)
//
// API (backward compatible; the last two options are new in pickeruni):
//   openPicker({ sessionId, startPath='~', title, showClear=false,
//                onPick(path), onClear(), onCancel(), onListUnavailable(error) })
//   closePicker()                        // test/reentry helper → onCancel
//
// Wire contract (existing channels only — zero new backend endpoints):
//   wsBrowse.list  {path, sessionId}  → wsBrowseList {path, home, entries:string[], error?}
//       Plain navigation. Complete listing (server applies no cap), `home` feeds
//       the breadcrumb fold. Channel unchanged from the workspace-picker batch.
//   wsBrowse.mkdir {path, name}       → wsBrowseMkdir {path, ok, error?}
//   browsePath     {path, query}      → browseResult {path, query, entries:{name,path}[],
//                                        total, truncated, error?, errorKind?}
//       Search + typed-path goto. Server filters (case-insensitive substring,
//       BEFORE the 2000-entry cap), echoes `query` for staleness guarding, and
//       expands `~`/`~/…`; failures come back as typed error frames
//       (invalid-path / not-a-directory / unreadable) rendered inline.
//
// Channel selection rule: a listing request goes over `browsePath` when a goto
// jump is in flight or the search box holds text (filtering + cap + explicit
// truncation notice live there), and over `wsBrowse.list` otherwise (complete
// uncapped listing). Clearing the search box re-lists via `browsePath` with an
// empty query — the same restore-to-full semantics the retired picker had.
//
// Staleness guards (two, complementary — neither replaces the other):
//   · monotonic `ctx.seq` — every request bumps it; a response whose captured
//     seq is no longer current is dropped (covers rapid row clicks on both
//     channels);
//   · `query` echo — a browseResult frame whose echoed query differs from the
//     box's current text is dropped (covers debounce overlap while typing).
//
// Error-no-commit invariant (picker-trunc r3, preserved by the unified face):
// a failed navigation NEVER moves `ctx.current` — the value the Select button
// commits — so a directory the picker just said it cannot open can never be
// handed to the caller. `goto` failures additionally keep the previous listing
// and breadcrumb on screen (only the error line changes).
//
// Interaction: breadcrumbs (etched strip, clickable ancestor segments, home
// folded to its localized label) / up = first ".." row / new folder (inline
// input row, Enter creates + enters) / click row to descend / truncation note
// (two lines: the fact, then the way out) / inline path-error line / Cancel
// (+ optional Clear for project-root mode) + Select This Folder / Esc closes /
// overlay click cancels. IME composition is owned by imeGuard.js (author
// ruling 2026-09-12: single composition predicate).
//
// Visual: the shared glass-card group in modal.css carries the panel material
// (.wsp-panel); layout/control styles (.wsp-*) live in chat.css — values follow
// the app's directory-browser baseline, token-driven, both themes.
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import state from './state.js';
// ⑤ Chinese-IME takeover (author ruling 2026-09-12): the ONE composition source = imeGuard.js.
import { bindImeGuard, isImeComposing } from './imeGuard.js';

let openCtx = null; // live dialog context (singleton reentry guard)

/**
 * One WS request → reply frame (single in flight per call, 4s timeout floor,
 * dynamic onMessage subscription — probeCanvasFile pattern).
 * @param {string} replyType message type to subscribe on
 * @param {function(): void} send thunk that emits the request frame
 * @returns {Promise<object>} the reply frame, or `{__timeout:true}` on timeout
 */
function wsRequest(replyType, send) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; unsub(); resolve(v); } };
    const unsub = onMessage(replyType, (msg) => finish(msg));
    setTimeout(() => finish({ __timeout: true }), 4000);
    send();
  });
}

/** Create a folder; resolves {ok, path?, error?} (timeout = not-ok). */
function mkdir(sessionId, path, name) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; unsub(); resolve(v); } };
    const unsub = onMessage('wsBrowseMkdir', (msg) => finish(msg));
    setTimeout(() => finish({ ok: false, error: 'timeout' }), 4000);
    sendWs({ type: 'wsBrowse.mkdir', path, name, sessionId });
  });
}

/** Breadcrumb segments from an absolute path (home prefix folded to one segment). */
function buildCrumbs(path, home) {
  if (!path) return [];
  if (home && (path === home || path.startsWith(home + '/'))) {
    const rest = path === home ? '' : path.slice(home.length);
    const segs = rest.split('/').filter(Boolean);
    const crumbs = [{ label: t('workspacePicker.home'), path: home }];
    let acc = home;
    for (const s of segs) { acc = (acc === '/' ? '' : acc) + '/' + s; crumbs.push({ label: s, path: acc }); }
    return crumbs;
  }
  // Outside home: unfold from the filesystem root
  const segs = path.split('/').filter(Boolean);
  const crumbs = [{ label: '/', path: '/' }];
  let acc = '';
  for (const s of segs) { acc += '/' + s; crumbs.push({ label: s, path: acc }); }
  return crumbs;
}

/**
 * Open the directory picker (singleton: reopening closes the old dialog and
 * fires its onCancel).
 * @param {object} opts {
 *   sessionId, startPath='~', title, showClear=false,
 *   onPick(path), onClear(), onCancel(), onListUnavailable(error)
 * }
 */
export function openPicker(opts = {}) {
  closePicker();
  const ctx = {
    sessionId: opts.sessionId || (state.activeSessionId ?? undefined),
    onPick: opts.onPick || (() => {}),
    onClear: opts.onClear || (() => {}),
    onCancel: opts.onCancel || (() => {}),
    // Listing unavailable (nav-origin timeout/error) must reach the caller
    // verbatim (2026-09-17 ruling ②-6: no silent swallow — chat.js reveals its
    // free-input fallback face from this callback).
    onListUnavailable: opts.onListUnavailable || (() => {}),
    current: opts.startPath || '~',
    home: '',
    seq: 0, // monotonic request token (staleness guard)
    searchTimer: null,
    teardown: null, // set below: detach the Esc listener + pending debounce
  };
  const titleText = opts.title || t('workspacePicker.browseTitle');

  const overlay = document.createElement('div');
  overlay.className = 'wsp-overlay';
  overlay.innerHTML =
    '<div class="wsp-panel" role="dialog" aria-modal="true" aria-label="' + escapeHtml(titleText) + '">' +
      '<div class="wsp-title"></div>' +
      '<div class="wsp-goto">' +
        '<input class="wsp-goto-input" type="text" spellcheck="false" autocomplete="off" placeholder="' + escapeHtml(t('workspacePicker.gotoPlaceholder')) + '" />' +
        '<button type="button" class="wsp-goto-btn">' + escapeHtml(t('workspacePicker.go')) + '</button>' +
      '</div>' +
      '<div class="wsp-search">' +
        '<input class="wsp-search-input" type="text" spellcheck="false" autocomplete="off" placeholder="' + escapeHtml(t('workspacePicker.searchPlaceholder')) + '" />' +
      '</div>' +
      '<div class="wsp-crumbs"></div>' +
      '<div class="wsp-note" hidden></div>' +
      '<div class="wsp-err" hidden></div>' +
      '<div class="wsp-list"></div>' +
      '<div class="wsp-foot">' +
        '<button type="button" class="wsp-mkdir">+ ' + escapeHtml(t('workspacePicker.newFolder')) + '</button>' +
        '<span class="wsp-cur"></span>' +
        '<span class="wsp-foot-btns">' +
          '<button type="button" class="wsp-cancel">' + escapeHtml(t('workspacePicker.cancel')) + '</button>' +
          (opts.showClear ? '<button type="button" class="wsp-clear">' + escapeHtml(t('workspacePicker.clear')) + '</button>' : '') +
          '<button type="button" class="wsp-pick">' + escapeHtml(t('workspacePicker.selectHere')) + '</button>' +
        '</span>' +
      '</div>' +
    '</div>';
  overlay.querySelector('.wsp-title').textContent = titleText;
  document.body.appendChild(overlay);

  const listEl = overlay.querySelector('.wsp-list');
  const crumbsEl = overlay.querySelector('.wsp-crumbs');
  const curEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-cur'));
  const noteEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-note'));
  const errEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-err'));
  const gotoEl = /** @type {HTMLInputElement} */ (overlay.querySelector('.wsp-goto-input'));
  const searchEl = /** @type {HTMLInputElement} */ (overlay.querySelector('.wsp-search-input'));
  openCtx = ctx;

  /** Terminal transition: teardown, then exactly one caller callback. */
  const finish = (outcome) => {
    if (openCtx !== ctx) return;
    openCtx = null;
    if (ctx.teardown) ctx.teardown();
    overlay.remove();
    if (outcome.type === 'pick') ctx.onPick(outcome.path);
    else if (outcome.type === 'clear') ctx.onClear();
    else ctx.onCancel();
  };

  // Esc closes (cancel) — never during IME composition (Esc belongs to the IME then).
  /** @param {KeyboardEvent} e */
  const onKey = (e) => {
    if (e.key !== 'Escape') return;
    const el = /** @type {HTMLElement|null} */ (e.target);
    if (el && el.tagName === 'INPUT' && isImeComposing(e, /** @type {HTMLInputElement} */ (el))) return;
    finish({ type: 'cancel' });
  };
  ctx.teardown = () => {
    document.removeEventListener('keydown', onKey);
    if (ctx.searchTimer) { clearTimeout(ctx.searchTimer); ctx.searchTimer = null; }
  };
  document.addEventListener('keydown', onKey);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) finish({ type: 'cancel' }); }); // overlay click = cancel
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-cancel')).onclick = () => finish({ type: 'cancel' });
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-pick')).onclick = () => finish({ type: 'pick', path: ctx.current });
  const clearBtn = /** @type {HTMLElement|null} */ (overlay.querySelector('.wsp-clear'));
  if (clearBtn) clearBtn.onclick = () => finish({ type: 'clear' }); // legacy parity: Clear closes WITHOUT onCancel
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-mkdir')).onclick = () => startMkdir();

  function hideErr() { errEl.hidden = true; errEl.textContent = ''; }
  function hideNote() { noteEl.hidden = true; noteEl.textContent = ''; }

  function showErr(msg) {
    errEl.textContent = msg;
    errEl.hidden = false;
  }

  /** Render breadcrumbs (ancestor segments are sapphire links, current is plain text). */
  function renderCrumbs() {
    crumbsEl.innerHTML = '';
    const crumbs = buildCrumbs(ctx.current, ctx.home);
    crumbs.forEach((c, i) => {
      if (i > 0) crumbsEl.appendChild(document.createTextNode(' / '));
      if (i === crumbs.length - 1) {
        crumbsEl.appendChild(document.createTextNode(c.label)); // current segment: plain text
      } else {
        const seg = document.createElement('span');
        seg.className = 'wsp-crumb';
        seg.textContent = c.label;
        seg.title = c.path;
        seg.addEventListener('click', () => navigate(c.path));
        crumbsEl.appendChild(seg);
      }
    });
  }

  /** Inline new-folder row at the top of the list (Enter creates + enters). */
  function startMkdir() {
    if (listEl.querySelector('.wsp-mkdir-row')) return;
    const row = document.createElement('div');
    row.className = 'wsp-row wsp-mkdir-row';
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'wsp-mkdir-input';
    input.placeholder = t('workspacePicker.folderPlaceholder');
    const ok = document.createElement('button');
    ok.type = 'button';
    ok.className = 'wsp-mkdir-ok';
    ok.textContent = t('workspacePicker.create');
    const no = document.createElement('button');
    no.type = 'button';
    no.className = 'wsp-mkdir-no';
    no.textContent = '×';
    row.append(input, ok, no);
    listEl.prepend(row);
    input.focus();
    const submit = async () => {
      const name = input.value.trim();
      if (!name) { row.remove(); return; }
      const res = await mkdir(ctx.sessionId, ctx.current, name);
      if (openCtx !== ctx) return;
      if (res && res.ok && res.path) {
        navigate(res.path); // created → enter the new directory
      } else {
        row.remove();
        curEl.textContent = t('workspacePicker.mkdirFail') + (res && res.error ? ' (' + res.error + ')' : '');
      }
    };
    ok.onclick = submit;
    no.onclick = () => row.remove();
    // ⑤ During composition Enter/Esc go back to the IME (non-composition keys unchanged).
    bindImeGuard(input);
    input.addEventListener('keydown', (e) => {
      if (isImeComposing(e, input)) return;
      if (e.key === 'Enter') submit();
      else if (e.key === 'Escape') row.remove();
    });
  }

  /**
   * List `path` and render. Channel rule: goto jumps and query-carrying
   * refreshes go over `browsePath` (server filter + cap + typed errors);
   * plain navigation goes over `wsBrowse.list` (complete uncapped listing).
   * @param {string} path
   * @param {{origin?: 'nav'|'goto'|'refresh'}} [opts]
   */
  async function navigate(path, opts = {}) {
    if (openCtx !== ctx) return;
    const origin = opts.origin || 'nav';
    const query = searchEl.value.trim();
    const seq = ++ctx.seq;
    hideErr();
    hideNote();
    curEl.textContent = ''; // transient line only (mkdir failures etc.)
    if (origin !== 'goto') {
      // goto keeps the current listing + breadcrumbs on screen while the jump
      // is in flight (error must not wipe what the user was looking at).
      listEl.innerHTML = '<div class="wsp-row wsp-empty">' + escapeHtml(t('workspacePicker.loading')) + '</div>';
    }
    const res = (origin === 'goto' || query)
      ? await wsRequest('browseResult', () => sendWs({ type: 'browsePath', path, query }))
      : await wsRequest('wsBrowseList', () => sendWs({ type: 'wsBrowse.list', path, sessionId: ctx.sessionId }));
    if (openCtx !== ctx || seq !== ctx.seq) return; // closed or superseded by a newer request
    renderResult(res, { origin, query, requested: path });
  }

  /**
   * Paint a listing frame (or its failure) — the error-no-commit invariant
   * lives here: failures never touch ctx.current.
   */
  function renderResult(res, env) {
    const failed = !res || res.__timeout || res.error || res.errorKind;
    if (failed) {
      const detail = res && typeof res.error === 'string' ? res.error : '';
      showErr(detail || t('workspacePicker.error.invalid'));
      hideNote();
      if (env.origin !== 'goto') {
        // Navigation-origin failure: the target listing never arrived, so the
        // picking face is unavailable — say so in the list AND raise it to the
        // caller (caller decides degradation; never a silent dead end).
        listEl.innerHTML = '<div class="wsp-row wsp-empty">' + escapeHtml(t('workspacePicker.readFail')) + '</div>';
        ctx.onListUnavailable(detail || 'unavailable');
      }
      // goto-origin: keep the previous listing + breadcrumbs untouched (the
      // user can correct the typed path — the box keeps its text).
      return;
    }

    hideErr();
    if (res.home) ctx.home = res.home;
    ctx.current = res.path || env.requested; // server-resolved absolute path
    renderCrumbs();

    // A: explicit truncation word (never silent) — the fact, then the way out.
    const entries = (res.entries || []).map((e) => (typeof e === 'string' ? { name: e } : e));
    const total = typeof res.total === 'number' ? res.total : entries.length;
    const hiddenCount = Math.max(0, total - entries.length);
    if (res.truncated && hiddenCount > 0) {
      noteEl.innerHTML =
        '<div>' + escapeHtml(t('workspacePicker.truncated', { count: hiddenCount, total })) + '</div>' +
        '<div class="wsp-note-hint">' + escapeHtml(t('workspacePicker.hint.truncated')) + '</div>';
      noteEl.hidden = false;
    } else {
      hideNote();
    }

    listEl.innerHTML = '';
    // Parent row first (hidden at the filesystem root)
    if (ctx.current !== '/') {
      const parentRow = document.createElement('button');
      parentRow.type = 'button';
      parentRow.className = 'wsp-row wsp-dir wsp-up-row';
      parentRow.innerHTML =
        '<span class="wsp-row-icon">..</span>' +
        '<span class="wsp-row-name">..</span>';
      parentRow.onclick = () => {
        const parts = ctx.current.replace(/\/$/, '').split('/');
        parts.pop();
        navigate(parts.join('/') || '/');
      };
      listEl.appendChild(parentRow);
    }

    if (!entries.length) {
      // Empty state names its own way out: "nothing here" vs "nothing matched".
      const empty = document.createElement('div');
      empty.className = 'wsp-row wsp-empty';
      const line = document.createElement('div');
      line.textContent = env.query ? t('workspacePicker.noMatch', { query: env.query }) : t('workspacePicker.empty');
      const hint = document.createElement('div');
      hint.className = 'wsp-empty-hint';
      hint.textContent = env.query ? t('workspacePicker.hint.noMatch') : t('workspacePicker.hint.empty');
      empty.append(line, hint);
      listEl.appendChild(empty);
      return;
    }
    for (const entry of entries) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'wsp-row wsp-dir';
      row.innerHTML =
        '<span class="wsp-row-icon">' +
          '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7a2 2 0 0 1 2-2h4l2.2 2.5H19a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>' +
        '</span>' +
        '<span class="wsp-row-name">' + escapeHtml(entry.name) + '</span>';
      const child = entry.path || ((ctx.current === '/' ? '' : ctx.current) + '/' + entry.name);
      row.onclick = () => navigate(child);
      listEl.appendChild(row);
    }
  }

  // ── goto: direct entry. Enter or the Go button jumps to the typed path; the
  // box keeps its text so a wrong path can be corrected instead of retyped. ──
  const gotoTyped = () => {
    const typed = gotoEl.value.trim();
    if (!typed) return;
    navigate(typed, { origin: 'goto' });
  };
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-goto-btn')).onclick = gotoTyped;
  bindImeGuard(gotoEl);
  gotoEl.addEventListener('keydown', (e) => {
    if (isImeComposing(e, gotoEl)) return;
    if (e.key === 'Enter') { e.preventDefault(); gotoTyped(); }
  });

  // ── search: server-side filter, 250ms debounce. Clearing the box restores
  // the full listing (the server treats an empty query as "no filter"). ─────
  const searchRefresh = () => navigate(ctx.current, { origin: 'refresh' });
  searchEl.addEventListener('input', () => {
    if (ctx.searchTimer) clearTimeout(ctx.searchTimer);
    ctx.searchTimer = setTimeout(() => {
      ctx.searchTimer = null;
      searchRefresh();
    }, 250);
  });
  bindImeGuard(searchEl);
  searchEl.addEventListener('keydown', (e) => {
    if (isImeComposing(e, searchEl)) return;
    if (e.key === 'Enter') {
      e.preventDefault();
      if (ctx.searchTimer) { clearTimeout(ctx.searchTimer); ctx.searchTimer = null; }
      searchRefresh();
    }
  });

  navigate(ctx.current); // first paint (backend expands `~`)
}

/** Close the live dialog (if any) and fire its onCancel — test/reentry helper. */
export function closePicker() {
  if (!openCtx) return;
  const ctx = openCtx;
  openCtx = null;
  if (ctx.teardown) ctx.teardown(); // detach Esc listener + pending debounce
  const overlay = document.querySelector('.wsp-overlay');
  if (overlay) overlay.remove();
  ctx.onCancel();
}
