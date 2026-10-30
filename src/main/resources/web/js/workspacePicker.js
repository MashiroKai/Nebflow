// workspacePicker.js — the ONE in-app directory picker (pickeruni batch, 2026-09-26).
//
// Every directory-picking entry point in the app opens THIS dialog:
//   · chat.js ProjectCreate askUser dirPicker card (2026-09-06 author ruling:
//     in-app browser, never the OS directory dialog)
//   · projectTab.js FAB layer D4 path field
//   · explorer.js header "change root" button (pickeruni batch)
//   · sidebar.js folder context-menu "set project root" (pickeruni batch)
//
// API (backward compatible; the last two options are new in pickeruni,
// `device`/`deviceName` in the ui-device-faces batch 2026-10-10):
//   openPicker({ sessionId, startPath='~', title, showClear=false,
//                device, deviceName,
//                onPick(path), onClear(), onCancel(), onListUnavailable(error) })
//   closePicker()                        // test/reentry helper → onCancel
//
// DEVICE MODE (ui-device-faces 批 · 设计稿 A4): with `device` set the picker browses a
// REMOTE device's directories — every listing goes over `wsBrowse.list` WITH the
// `device` key (additive backend leg; scope=browse metadata only). The face is
// READ-ONLY by design (G.13: the directory must already exist — no mkdir leg, and
// the goto/search boxes are hidden: `browsePath` has no remote leg, D4 "picker
// only" still holds). Breadcrumbs are prefixed with a device chip; the foot shows
// the remote read-only note.
//
// DEVICE SWITCHER (picker-device-switch 批 2026-10-30，作者报单 09:36：「选择项目
// 路径的时候，只能看到本机的路径」)：面板顶部常驻设备选择行——平级设备列表
// （设计语言「所有设备平等」：self 以花名册设备名平级在列，无本机/self 特殊化），
// 选中对端后目录列表经既有设备通道读该设备文件系统（wsBrowse.list 的 additive
// `device` 腿，复用 dirPicker 远端伺服腿，零新端点）；离线对端行置灰禁选 +
// 「离线」标注。面板内切设备 = 换盘：已浏览路径不复用（本机从 ~ 起、远端从根
// 起），读写 chrome 按模式重挂（远端只读：无 mkdir/goto/search——沿用 R-A5 的
// 「Absent-not-hidden」纪律，既有断言 .wsp-mkdir 计数不被隐藏元素污染）。
// 数据源 = deviceRoster.js（/api/neblink/status，60s 内存缓存，零新后端面）。
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
// uncapped listing). Clearing the search box re-lists over `wsBrowse.list` —
// the same restore-to-full (complete, uncapped) semantics the retired picker
// had.
//
// Staleness guards (two, complementary — neither replaces the other):
//   · monotonic `ctx.seq` — every request bumps it; a response whose captured
//     seq is no longer current is dropped (covers rapid row clicks on both
//     channels);
//   · `query` echo (browsePath channel only) — ws.js onMessage is multicast,
//     so the first browseResult frame to arrive resolves every live
//     subscription and, during debounce overlap, the newer request can be
//     handed a frame that answers the OLDER filter — a mismatch the seq guard
//     cannot see (the consuming request IS the current one). A frame whose
//     echoed query differs from the box's current text is ignored before it
//     can resolve anything (echoCurrent), leaving the subscription live for
//     the real answer. (Restored in round-2 — round-1 shipped this guard in
//     the header comment only; the retired sidebar.js picker had the working
//     comparison in handleBrowseResult.)
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
import { escapeHtml, statusIcon } from './utils.js';
import state from './state.js';
// ⑤ Chinese-IME takeover (author ruling 2026-09-12): the ONE composition source = imeGuard.js.
import { bindImeGuard, isImeComposing } from './imeGuard.js';

let openCtx = null; // live dialog context (singleton reentry guard)

/**
 * One WS request → reply frame (single in flight per call, 4s timeout floor,
 * dynamic onMessage subscription — probeCanvasFile pattern).
 * @param {string} replyType message type to subscribe on
 * @param {function(): void} send thunk that emits the request frame
 * @param {function(object): boolean} [accept] consume filter: a frame is
 *        resolved into the request only when this returns true; a rejected
 *        frame is ignored and the subscription stays live for the real answer
 *        (query-echo staleness guard — see echoCurrent in openPicker)
 * @returns {Promise<object>} the reply frame, or `{__timeout:true}` on timeout
 */
function wsRequest(replyType, send, accept) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; unsub(); resolve(v); } };
    const unsub = onMessage(replyType, (msg) => { if (accept && !accept(msg)) return; finish(msg); });
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

/** Breadcrumb segments from an absolute path (home prefix folded to one segment).
 *  DEVICE MODE (remote=true): the path lives on the REMOTE OS — its separator may be
 *  `\` (Windows peer) — segments split on both separators and crumbs rebuild with the
 *  path's own separator; the leading crumb is the share root (`/` POSIX · `C:\` drive)
 *  so no crumb/up-row ever emits a separatorless drive letter or a POSIX root at a
 *  Windows peer (2026-10-30 三修①：开箱 `/` ⇒ `\` 的根路径折损族). */
function buildCrumbs(path, home, remote) {
  if (!path) return [];
  if (remote) {
    const segs = path.split(/[/\\]+/).filter(Boolean);
    if (!segs.length) return [];
    const sep = path.indexOf('\\') >= 0 ? '\\' : '/';
    const drive = /^[A-Za-z]:$/.test(segs[0]);
    const crumbs = [{ label: drive ? segs[0] : '/', path: drive ? segs[0] + sep : '/' }];
    let acc = crumbs[0].path;
    for (const s of segs.slice(drive ? 1 : 0)) {
      acc = (acc.endsWith(sep) ? acc : acc + sep) + s;
      crumbs.push({ label: s, path: acc });
    }
    return crumbs;
  }
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
 *   sessionId, startPath='~', title, showClear=false, device, deviceName,
 *   deviceSwitch=true (picker-device-switch 批：面板顶部设备选择行；个别消费方
 *     的路径语义绑定本机（sidebar set-project-root）可显式关掉),
 *   onPick(path, device) (第二参 = 选择时面板所处的 origin 设备（null=本机）——
 *     面板内可切设备，裸路径之外补 origin 绑定，调用方按消费),
 *   onClear(), onCancel(), onListUnavailable(error)
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
    // DEVICE MODE (ui-device-faces 批 A4): browse a remote device's dirs read-only.
    device: opts.device || null,
    deviceName: opts.deviceName || opts.device || '',
    // 设备选择行开关（picker-device-switch 批）：默认常驻；绑定本机语义的消费方
    // （sidebar set-project-root）显式 deviceSwitch:false（其回写面无设备定语）。
    deviceSwitch: opts.deviceSwitch !== false,
    current: opts.startPath || '~',
    home: '',
    seq: 0, // monotonic request token (staleness guard)
    searchTimer: null,
    rosterSelf: null, // 花名册 self {id,name}（设备行显示名；未取到 = 回落本机串）
    devMenuOpen: false,
    teardown: null, // set below: detach the Esc listener + pending debounce
  };
  const hasCustomTitle = !!opts.title;
  const titleEl = document.createElement('div');
  titleEl.className = 'wsp-title';
  /** 标题随模式变（自定义标题除外）：本机 = browseTitle，远端 = deviceTitle。 */
  const updateTitle = () => {
    const titleText = hasCustomTitle
      ? /** @type {string} */ (opts.title)
      : (ctx.device ? t('workspacePicker.deviceTitle', { device: ctx.deviceName }) : t('workspacePicker.browseTitle'));
    titleEl.textContent = titleText;
    titleEl.setAttribute('aria-label', titleText);
  };
  updateTitle();

  // Device icons（设计稿 A1/A3 两形，与 projectTab.js 同款串）：本机 = monitor、
  // 远端 = hard-drive + sapphire 着色（.dev-btn.is-remote 族）。
  const DEV_ICON_SELF =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/></svg>';
  const DEV_ICON_REMOTE =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="22" x2="2" y1="12" y2="12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/><line x1="6" x2="6.01" y1="16" y2="16"/><line x1="10" x2="10.01" y1="16" y2="16"/></svg>';
  const DEV_ICON_CHECK =
    '<svg class="ck" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';

  const overlay = document.createElement('div');
  overlay.className = 'wsp-overlay';
  overlay.innerHTML =
    '<div class="wsp-panel" role="dialog" aria-modal="true">' +
      '<div class="wsp-title-slot"></div>' +
      // 设备选择行（picker-device-switch 批）：标题下、面包屑上。行类复用
      // projectPanel.css 全局 .dev-row/.dev-btn/.dev-menu 族（零新样式面，仅
      // .wsp-dev-row 间距微调在 chat.css）。deviceSwitch=false 时整行不挂。
      (ctx.deviceSwitch
        ? '<div class="wsp-dev-row dev-row">' +
            '<button type="button" class="dev-btn wsp-dev-btn" aria-haspopup="listbox" aria-expanded="false" aria-label="' + escapeHtml(t('workspacePicker.deviceSelectAria')) + '">' +
              '<span class="dev-ic"></span>' +
              '<span class="dev-nm"></span>' +
              '<svg class="chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>' +
            '</button>' +
            '<div class="dev-menu" hidden></div>' +
          '</div>'
        : '') +
      '<div class="wsp-chrome"></div>' + // goto/search 挂点（仅本机模式挂载——Absent-not-hidden）
      '<div class="wsp-crumbs"></div>' +
      '<div class="wsp-note" hidden></div>' +
      '<div class="wsp-err" hidden></div>' +
      '<div class="wsp-list"></div>' +
      '<div class="wsp-foot">' +
        '<span class="wsp-foot-slot"></span>' + // mkdir | 远端只读脚注（按模式挂载）
        '<span class="wsp-cur"></span>' +
        '<span class="wsp-foot-btns">' +
          '<button type="button" class="wsp-cancel">' + escapeHtml(t('workspacePicker.cancel')) + '</button>' +
          (opts.showClear ? '<button type="button" class="wsp-clear">' + statusIcon('trash-2', 12, 'st-err') + escapeHtml(t('workspacePicker.clear')) + '</button>' : '') +
          '<button type="button" class="wsp-pick">' + escapeHtml(t('workspacePicker.selectHere')) + '</button>' +
        '</span>' +
      '</div>' +
    '</div>';
  overlay.querySelector('.wsp-title-slot').replaceWith(titleEl);
  document.body.appendChild(overlay);

  const listEl = overlay.querySelector('.wsp-list');
  const crumbsEl = overlay.querySelector('.wsp-crumbs');
  const curEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-cur'));
  const noteEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-note'));
  const errEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-err'));
  const chromeEl = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-chrome'));
  const footSlot = /** @type {HTMLElement} */ (overlay.querySelector('.wsp-foot-slot'));
  // 本机模式输入框（mountChrome 挂载；远端模式 = null——🔴 引用点必须 optional
  // chaining，曾裸引用在 device 模式首帧抛 TypeError 的坑见 navigate 注）。
  /** @type {HTMLInputElement|null} */
  let gotoEl = null;
  /** @type {HTMLInputElement|null} */
  let searchEl = null;
  openCtx = ctx;

  /** Terminal transition: teardown, then exactly one caller callback. */
  const finish = (outcome) => {
    if (openCtx !== ctx) return;
    openCtx = null;
    if (ctx.teardown) ctx.teardown();
    overlay.remove();
    // onPick 第二参 = 选择时面板所处的 origin 设备（null=本机）——picker-device-switch
    // 批：面板内可切设备，裸路径之外补 origin 绑定（旧调用方单参回调零影响）。
    if (outcome.type === 'pick') ctx.onPick(outcome.path, ctx.device);
    else if (outcome.type === 'clear') ctx.onClear();
    else ctx.onCancel();
  };

  // ── 设备选择行（picker-device-switch 批）：平级设备列表，所有设备平等 ──────
  const devBtn = ctx.deviceSwitch ? /** @type {HTMLElement} */ (overlay.querySelector('.wsp-dev-btn')) : null;
  const devMenu = ctx.deviceSwitch ? /** @type {HTMLElement} */ (overlay.querySelector('.wsp-dev-row .dev-menu')) : null;

  const renderDevBtn = () => {
    if (!devBtn) return;
    devBtn.classList.toggle('is-remote', !!ctx.device);
    const ic = devBtn.querySelector('.dev-ic');
    if (ic) ic.innerHTML = ctx.device ? DEV_ICON_REMOTE : DEV_ICON_SELF;
    const nm = devBtn.querySelector('.dev-nm');
    if (nm) nm.textContent = ctx.device ? ctx.deviceName : (ctx.rosterSelf?.name || t('workspacePicker.thisDevice'));
    devBtn.title = ctx.device ? ctx.deviceName : (ctx.rosterSelf?.name || t('workspacePicker.thisDevice'));
    devBtn.setAttribute('aria-expanded', String(ctx.devMenuOpen));
  };

  const closeDevMenu = () => {
    if (!ctx.devMenuOpen) return;
    ctx.devMenuOpen = false;
    if (devMenu) { devMenu.hidden = true; devMenu.innerHTML = ''; }
    renderDevBtn();
  };

  /** 菜单行填充：self（花名册名，平级呈现）+ peers；离线行禁选 + 离线 tag。 */
  const fillDevMenu = (rows) => {
    if (!devMenu) return;
    devMenu.innerHTML = '';
    const currentId = ctx.device || (ctx.rosterSelf && ctx.rosterSelf.id) || null;
    for (const row of rows) {
      const selected = row.id === currentId;
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'dev-item' + (selected ? ' active' : '') + (row.online ? '' : ' disabled');
      if (!row.online) item.disabled = true;
      item.innerHTML =
        '<span class="dot' + (row.online ? '' : ' off') + '"></span>' +
        '<span class="nm">' + escapeHtml(row.name) + '</span>';
      if (selected) item.innerHTML += DEV_ICON_CHECK;
      else if (!row.online) item.innerHTML += '<span class="off-tag">' + escapeHtml(t('project.deviceOfflineTag')) + '</span>';
      if (row.online) item.addEventListener('click', (e) => { e.stopPropagation(); selectDevice(row); });
      devMenu.appendChild(item);
    }
    const foot = document.createElement('div');
    foot.className = 'dev-menu-foot';
    foot.textContent = t('project.deviceMenuFoot');
    devMenu.appendChild(foot);
  };

  const openDevMenu = () => {
    if (!devBtn || !devMenu) return;
    ctx.devMenuOpen = true;
    devMenu.hidden = false;
    renderDevBtn();
    import('./deviceRoster.js')
      .then(({ getDeviceRoster }) => getDeviceRoster())
      .then((roster) => {
        if (!ctx.devMenuOpen) return;
        ctx.rosterSelf = roster.self;
        // 显示名补写：调用方只给 deviceId 未给显示名时，花名册命中即换名（id 不上显示面）。
        if (ctx.device && ctx.deviceName === ctx.device) {
          const peer = roster.peers.find((p) => p.id === ctx.device);
          if (peer) { ctx.deviceName = peer.name; updateTitle(); }
        }
        renderDevBtn();
        fillDevMenu([
          // 「所有设备平等」：self 以花名册设备名平级在列，无本机/self 特殊化。
          roster.self
            ? { id: roster.self.id, name: roster.self.name, online: true, self: true }
            : { id: 'self', name: t('workspacePicker.thisDevice'), online: true, self: true },
          ...roster.peers.map((p) => ({ id: p.id, name: p.name, online: p.online, self: false })),
        ]);
      })
      .catch(() => {
        // 花名册不可得 = 回落单本机行（现状形态），不弹错误（可继续本机浏览）。
        if (!ctx.devMenuOpen) return;
        ctx.rosterSelf = null;
        fillDevMenu([{ id: 'self', name: t('workspacePicker.thisDevice'), online: true, self: true }]);
      });
  };

  /** 选中一台设备。面板内切设备 = 换盘：路径不复用、chrome 按模式重挂。 */
  const selectDevice = (row) => {
    const next = row && !row.self ? { id: row.id, name: row.name } : null;
    const changed = (next && next.id) !== (ctx.device || null);
    ctx.device = (next && next.id) || null;
    ctx.deviceName = (next && next.name) || '';
    closeDevMenu();
    renderDevBtn();
    if (!changed) return;
    ctx.home = '';
    mountChrome();
    updateTitle();
    navigate(ctx.device ? '/' : '~'); // 远端从根起（wsBrowse.list device 腿）；本机从 ~ 起
  };

  if (devBtn) {
    devBtn.onclick = (e) => {
      e.stopPropagation();
      if (ctx.devMenuOpen) closeDevMenu();
      else openDevMenu();
    };
  }
  // 点面板其它区域收菜单（capture：先于子件 stopPropagation——菜单内点击除外）。
  /** @param {Event} e */
  const devOutsideClose = (e) => {
    if (!ctx.devMenuOpen) return;
    const tgt = /** @type {Element|null} */ (e.target);
    if (tgt && (tgt.closest('.wsp-dev-row'))) return;
    closeDevMenu();
  };
  document.addEventListener('pointerdown', devOutsideClose, true);
  // 面板一打开即预取花名册（self 显示名异步补写——本机设备名不在显示面直出「local」）。
  import('./deviceRoster.js')
    .then(({ getDeviceRoster }) => getDeviceRoster())
    .then((roster) => {
      if (openCtx !== ctx) return;
      ctx.rosterSelf = roster.self;
      if (ctx.device && ctx.deviceName === ctx.device) {
        const peer = roster.peers.find((p) => p.id === ctx.device);
        if (peer) { ctx.deviceName = peer.name; updateTitle(); }
      }
      renderDevBtn();
    })
    .catch(() => {});

  // ── 读写 chrome 按模式挂载（mountChrome）────────────────────────────────
  // Absent-not-hidden 纪律（R-A5 断言 .wsp-mkdir 计数）：远端只读腿下 goto/search/
  // mkdir **不挂 DOM**（不是 hidden）——既有断言与查询不受污染；切回本机时重挂并
  // 重新绑定输入处理。
  function mountChrome() {
    chromeEl.innerHTML = '';
    footSlot.innerHTML = '';
    gotoEl = null;
    searchEl = null;
    if (ctx.device) {
      // 远端只读：无 mkdir/goto/search（browsePath 无远端腿，D4 "picker only"），
      // mkdir 的座位让远端只读脚注（deviceNote）。
      const note = document.createElement('span');
      note.className = 'pick-note';
      note.textContent = t('workspacePicker.deviceNote');
      footSlot.appendChild(note);
      return;
    }
    chromeEl.innerHTML =
      '<div class="wsp-goto">' +
        '<input class="wsp-goto-input" type="text" spellcheck="false" autocomplete="off" placeholder="' + escapeHtml(t('workspacePicker.gotoPlaceholder')) + '" />' +
        '<button type="button" class="wsp-goto-btn">' + escapeHtml(t('workspacePicker.go')) + '</button>' +
      '</div>' +
      '<div class="wsp-search">' +
        '<input class="wsp-search-input" type="text" spellcheck="false" autocomplete="off" placeholder="' + escapeHtml(t('workspacePicker.searchPlaceholder')) + '" />' +
      '</div>';
    gotoEl = /** @type {HTMLInputElement} */ (chromeEl.querySelector('.wsp-goto-input'));
    searchEl = /** @type {HTMLInputElement} */ (chromeEl.querySelector('.wsp-search-input'));
    const mkdir = document.createElement('button');
    mkdir.type = 'button';
    mkdir.className = 'wsp-mkdir';
    mkdir.textContent = '+ ' + t('workspacePicker.newFolder');
    mkdir.onclick = () => startMkdir();
    footSlot.appendChild(mkdir);
    bindLocalChromeInputs();
  }

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
    document.removeEventListener('pointerdown', devOutsideClose, true);
    if (ctx.searchTimer) { clearTimeout(ctx.searchTimer); ctx.searchTimer = null; }
  };
  document.addEventListener('keydown', onKey);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) finish({ type: 'cancel' }); }); // overlay click = cancel
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-cancel')).onclick = () => finish({ type: 'cancel' });
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-pick')).onclick = () => finish({ type: 'pick', path: ctx.current });
  const clearBtn = /** @type {HTMLElement|null} */ (overlay.querySelector('.wsp-clear'));
  if (clearBtn) clearBtn.onclick = () => finish({ type: 'clear' }); // legacy parity: Clear closes WITHOUT onCancel

  function hideErr() { errEl.hidden = true; errEl.innerHTML = ''; }
  function hideNote() { noteEl.hidden = true; noteEl.textContent = ''; }

  function showErr(msg) {
    // ui-status-redesign（S9 同族 / 盘点 #34）：行首 circle-alert 红图标 + 中性正文。
    errEl.innerHTML = statusIcon('circle-alert', 13, 'st-err') + '<span>' + escapeHtml(msg) + '</span>';
    errEl.hidden = false;
  }

  /** Render breadcrumbs (ancestor segments are sapphire links, current is plain text).
   *  Device mode: the chip prefix names the ORIGIN device — the path space is that
   *  machine's disk, and the chip is the one constant "where am I" cue (设计稿 A4). */
  function renderCrumbs() {
    crumbsEl.innerHTML = '';
    if (ctx.device) {
      const chip = document.createElement('span');
      chip.className = 'path-chip';
      chip.innerHTML =
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
        '<line x1="22" x2="2" y1="12" y2="12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>';
      chip.appendChild(document.createTextNode(ctx.deviceName));
      chip.title = ctx.deviceName;
      crumbsEl.appendChild(chip);
      crumbsEl.appendChild(document.createTextNode(' '));
    }
    const crumbs = buildCrumbs(ctx.current, ctx.home, !!ctx.device);
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

  // Query-echo staleness guard for the browsePath channel (round-2: restored —
  // the retired sidebar.js picker had this same comparison in its
  // handleBrowseResult). While a browsePath request is in flight the search box
  // may already hold newer text (250ms debounce overlap), and ws.js onMessage
  // is MULTICAST: the first frame to arrive resolves every live subscription,
  // so the current request can be handed a frame answering the PREVIOUS filter
  // — a mismatch the seq guard cannot catch, because the request consuming the
  // stale frame IS the current one. A frame whose echoed query differs from
  // the box's CURRENT text is therefore ignored before it can resolve anything;
  // the subscription stays live so the real answer still lands when it arrives
  // (the 4s timeout still bounds the wait). The synthetic `{__timeout:true}`
  // needs no guard: it is produced by the request's own timer and bypasses
  // onMessage entirely, so it can never be stale.
  /** @param {object} res browseResult frame
   *  @returns {boolean} true when the frame may resolve the pending request */
  const echoCurrent = (res) =>
    (res && typeof res.query === 'string' ? res.query : '') === (searchEl?.value.trim() ?? '');

  /**
   * List `path` and render. Channel rule: goto jumps and query-carrying
   * refreshes go over `browsePath` (server filter + cap + typed errors);
   * plain navigation goes over `wsBrowse.list` (complete uncapped listing).
   * 🔴 device 模式两输入框不渲染（read-only 远端腿）⇒ 引用为 null——必须走
   *    optional chaining（曾裸 `.value` 在 device 模式首帧即抛 TypeError，
   *    列表永不渲染 = 静默死端）。
   * @param {string} path
   * @param {{origin?: 'nav'|'goto'|'refresh'}} [opts]
   */
  async function navigate(path, opts = {}) {
    if (openCtx !== ctx) return;
    const origin = opts.origin || 'nav';
    const query = searchEl?.value.trim() ?? '';
    const seq = ++ctx.seq;
    hideErr();
    hideNote();
    curEl.textContent = ''; // transient line only (mkdir failures etc.)
    if (origin !== 'goto') {
      // goto keeps the current listing + breadcrumbs on screen while the jump
      // is in flight (error must not wipe what the user was looking at).
      listEl.innerHTML = '<div class="wsp-row wsp-empty">' + escapeHtml(t('workspacePicker.loading')) + '</div>';
    }
    const res = ctx.device
      // DEVICE MODE: one channel only — wsBrowse.list with the additive `device`
      // key (metadata listing; browsePath has no remote leg, goto/search hidden).
      ? await wsRequest('wsBrowseList', () =>
          sendWs({ type: 'wsBrowse.list', path, sessionId: ctx.sessionId, device: ctx.device }))
      : (origin === 'goto' || query)
      ? await wsRequest('browseResult', () => sendWs({ type: 'browsePath', path, query }), echoCurrent)
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
      // 设备共享白名单拒绝 = 预期可读态（2026-10-30 三修①「从家目录开始」）：不给
      // 原始英文报错，给本地化的「未共享 + 出路」说明；其余错误照旧透传（诊断价值）。
      const msg = ctx.device && /outside the device share whitelist/i.test(detail)
        ? t('workspacePicker.deviceShareDenied', { device: ctx.deviceName })
        : (detail || t('workspacePicker.error.invalid'));
      showErr(msg);
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
    // DEVICE 腿：优先采用**对端解析后**的绝对路径（additive `resolvedPath` 键——
    // 初始 `~` 回显为真实家目录，面包屑/上级行才有正确展开点）；老帧无该键 ⇒
    // 回退 `res.path`/`env.requested`（逐字节现状，零回归）。
    ctx.current = res.resolvedPath || res.path || env.requested;
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
    // Parent row first. DEVICE MODE: hide at the share root (single-segment path —
    // `C:\` / `/`); stripping one segment from there would emit a separatorless
    // drive letter / empty path (2026-10-30 三修①根路径折损族)。
    const showUp = ctx.device
      ? ctx.current.split(/[/\\]+/).filter(Boolean).length > 1
      : ctx.current !== '/';
    if (showUp) {
      const parentRow = document.createElement('button');
      parentRow.type = 'button';
      parentRow.className = 'wsp-row wsp-dir wsp-up-row';
      parentRow.innerHTML =
        '<span class="wsp-row-icon">..</span>' +
        '<span class="wsp-row-name">..</span>';
      parentRow.onclick = () => {
        if (ctx.device) {
          const up = ctx.current.replace(/[/\\][^/\\]*$/, '');
          if (up) navigate(up);
        } else {
          const parts = ctx.current.replace(/\/$/, '').split('/');
          parts.pop();
          navigate(parts.join('/') || '/');
        }
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
      const child = entry.path || (
        (ctx.current === '/' ? '' : ctx.current) +
        // 子路径拼接用**当前路径自己的**分隔符（Windows 对端 = `\`）——混拼虽被
        // 接收端 normalize 救回，但出站帧/面包屑读数保持对端原生形态（三修①）。
        (ctx.current.indexOf('\\') >= 0 ? '\\' : '/') +
        entry.name
      );
      row.onclick = () => navigate(child);
      listEl.appendChild(row);
    }
  }

  // ── goto: direct entry. Enter or the Go button jumps to the typed path; the
  // box keeps its text so a wrong path can be corrected instead of retyped. ──
  // 仅本机模式挂载（mountChrome 内调用；远端只读腿无输入框）。
  const gotoTyped = () => {
    const typed = gotoEl ? gotoEl.value.trim() : '';
    if (!typed) return;
    navigate(typed, { origin: 'goto' });
  };
  /** 本机模式输入处理绑定（每次 chrome 重挂后调用；元素为本次挂载实例）。 */
  function bindLocalChromeInputs() {
    if (!gotoEl || !searchEl) return;
    /** @type {HTMLElement} */ (chromeEl.querySelector('.wsp-goto-btn')).onclick = gotoTyped;
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
  }

  renderDevBtn();
  mountChrome(); // 首挂：按初始模式（本机全功能 / 远端只读）
  navigate(ctx.current); // first paint (backend expands `~`; device mode = remote root list)
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
