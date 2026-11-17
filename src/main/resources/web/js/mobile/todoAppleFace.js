// mobile/todoAppleFace.js — Mobile todo pages laid out in the Apple Reminders
// information architecture.
//
// Three pages (registered in navController.PAGES; page shell, back button and
// slide transitions all reuse the existing pushPage/popPage/bindEdgeBack —
// this module only produces page-body content, no new navigation semantics):
//   tasks            lists home: white three-icon capsule top-right + 2x2
//                    colored four-card matrix + "My Lists" large-radius card
//                    (todoStore.tags) + blue FAB bottom-right
//   todo-apple-list  list detail: large colored title left-aligned + hollow
//                    check-circle rows + centered empty state
//   todo-apple-item  create/edit: placeholder input row + (i) info button +
//                    keyboard-hugging toolbar
//
// Data discipline: complete/create/edit all go through todoStore (in-memory
// store, pure frontend mock); the backend is never touched and no new entities
// are invented — buttons whose backing capability is absent are simply not
// rendered.
//
// Dependency direction: this module does NOT statically import navController
// (that would form a static cycle and turn scripts/check-circular.mjs red);
// navigation = injected nav {push, pop} (same convention as meFace.js).

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { todoStore, TAG_COLOR_PRESETS, PRIORITY_COLORS } from '../todoStore.js';
import { bindImeGuard, commitEnter } from '../imeGuard.js';
import state from '../state.js';

const DAY = 86_400_000;

/** @typedef {{ push: (pageId: string) => void, pop: () => void }} TaNav */
/** @typedef {'today'|'overdue'|'all'|'completed'|'tag'} ListMode */
/**
 * Item reading (element shape of the todoStore read-only projection).
 * @typedef {{ id: string, tagId: string, title: string, notes: string,
 *   due: number|null, priority: number, completed: boolean, createdAt: number }} TaItem
 */

// ── Small element factories ──────────────────────────────────────────────

/**
 * @param {string} tag
 * @param {string} cls
 * @param {string} [text]
 * @returns {HTMLElement}
 */
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

/**
 * @param {HTMLElement} parent
 * @param {string} tag
 * @param {string} cls
 * @param {string} [text]
 * @returns {HTMLElement}
 */
function elIn(parent, tag, cls, text) {
  const n = el(tag, cls, text);
  parent.appendChild(n);
  return n;
}

/** @param {string} cls @returns {HTMLButtonElement} */
function btnEl(cls) { return /** @type {HTMLButtonElement} */ (el('button', cls)); }

/** @param {string} cls @returns {HTMLInputElement} */
function inputEl(cls) { return /** @type {HTMLInputElement} */ (el('input', cls)); }

/**
 * Lucide icon carrier (`<i data-lucide="...">`; drawing is consolidated by
 * createIconsIn).
 * @param {string} icon
 * @param {string} [cls]
 * @returns {HTMLElement}
 */
function icoEl(icon, cls) {
  const i = el('i', cls);
  i.setAttribute('data-lucide', icon);
  return i;
}

/**
 * Icon button (lucide `<i data-lucide>`; drawing is consolidated by
 * createIconsIn).
 * @param {string} cls
 * @param {string} icon
 * @param {string} label
 * @returns {HTMLButtonElement}
 */
function iconBtn(cls, icon, label) {
  const b = btnEl(cls);
  b.type = 'button';
  b.setAttribute('aria-label', label);
  b.title = label;
  // Structured build (contract R5): one icon carrier, DOM identical to the
  // previous one-line template.
  b.appendChild(icoEl(icon));
  return b;
}

/** Local day boundary 00:00 (the due-today card and the "today" list share it). */
function startOfToday() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** A given hour today (anchor time for the toolbar "date" cycle — same-day reminders). */
/** @param {number} hour @returns {number} */
function todayAt(hour) {
  const d = new Date();
  d.setHours(hour, 0, 0, 0);
  return d.getTime();
}

/** Priority color (PRIORITY_COLORS is number-keyed; per-step reads avoid an index-signature error). */
/** @param {number} p @returns {string} */
function priorityColor(p) {
  if (p === 3) return PRIORITY_COLORS[3];
  if (p === 2) return PRIORITY_COLORS[2];
  if (p === 1) return PRIORITY_COLORS[1];
  return PRIORITY_COLORS[0];
}

/** Priority label (0 none / 1 low / 2 medium / 3 high; reuses the todoface.prio* entries). */
/** @param {number} p @returns {string} */
function priorityLabel(p) {
  if (p === 3) return t('todoface.prioHigh');
  if (p === 2) return t('todoface.prioMed');
  if (p === 1) return t('todoface.prioLow');
  return t('todoapple.none');
}

/** Short due text (today / tomorrow / M/D + time; 13px readable step). */
/** @param {number} ms @returns {string} */
function dueText(ms) {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  const from = startOfToday();
  if (ms >= from && ms < from + DAY) return `${t('todoapple.today')} ${hm}`;
  if (ms >= from + DAY && ms < from + 2 * DAY) return `${t('todoapple.dueTomorrow')} ${hm}`;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

// ── Module state (list target / item target / draft / home inline state) ──

/** @type {{ mode: ListMode, tagId: string|null }} */
let listTarget = { mode: 'all', tagId: null };
/** @type {string|null} */
let itemTarget = null;
/** @type {{ title: string, notes: string, due: number|null, priority: number, tagId: string|null }} */
let draft = { title: '', notes: '', due: null, priority: 0, tagId: null };
let searchOpen = false;
let searchQuery = '';
let newListOpen = false;
let moreOpen = false;
/** Retain an unconfirmed creation across page navigation. */
let pendingCreate = null;
try { pendingCreate = JSON.parse(sessionStorage.getItem('stand.todo.pendingCreate') || 'null'); } catch { /* storage unavailable */ }
function persistPendingCreate() {
  try {
    if (pendingCreate) sessionStorage.setItem('stand.todo.pendingCreate', JSON.stringify(pendingCreate));
    else sessionStorage.removeItem('stand.todo.pendingCreate');
  } catch { /* retain the in-memory draft */ }
}

/**
 * Idempotency key for one todo create. The server accepts only a lowercase v4 UUID
 * (`WsTasksWorkspaceHandlers` requestId regex); `crypto.randomUUID` is absent in
 * non-secure contexts, so the fallbacks must still produce exactly that shape.
 * @returns {string}
 */
function newCreateRequestId() {
  try {
    const c = globalThis.crypto;
    if (c && typeof c.randomUUID === 'function') return c.randomUUID();
    if (c && typeof c.getRandomValues === 'function') {
      const b = new Uint8Array(16);
      c.getRandomValues(b);
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
      return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
    }
  } catch { /* fall through */ }
  const hex = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0');
  const time = Date.now().toString(16).padStart(12, '0').slice(-12);
  return `${time.slice(0, 8)}-${time.slice(8, 12)}-4${hex().slice(1)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${hex().slice(1)}-${hex()}${hex()}${hex()}`;
}

/** @param {ListMode} mode @param {string|null} tagId @param {TaNav} nav */
function openList(mode, tagId, nav) {
  listTarget = { mode, tagId };
  searchOpen = false;
  moreOpen = false;
  newListOpen = false;
  nav.push('todo-apple-list');
}

/** @param {string|null} itemId @param {TaNav} nav */
function openItem(itemId, nav) {
  itemTarget = itemId;
  nav.push('todo-apple-item');
}

/** Create without replacing the Apple list page or its scroll position.
 * @param {HTMLElement} section
 * @param {string|null} initialTagId
 * @param {HTMLButtonElement} trigger
 */
function openCreateSheet(section, initialTagId, trigger) {
  if (section.querySelector('[data-todo-create-overlay]')) return;
  const firstTag = todoStore.tags[0];
  let tagId = pendingCreate?.tagId ?? initialTagId ?? (firstTag ? firstTag.id : null);
  let priority = pendingCreate?.priority ?? 0;
  const overlay = elIn(section, 'div', 'ta-create-overlay');
  overlay.dataset.todoCreateOverlay = '';
  const sheet = elIn(overlay, 'div', 'ta-create-sheet nf-glass');
  sheet.dataset.todoCreateSheet = '';
  sheet.setAttribute('role', 'dialog');
  sheet.setAttribute('aria-label', t('todoapple.newTodo'));
  const actions = elIn(sheet, 'div', 'ta-create-actions');
  const cancel = btnEl('ta-create-cancel');
  cancel.type = 'button';
  cancel.textContent = t('todoface.cancel');
  const save = btnEl('ta-create-save');
  save.type = 'button';
  save.textContent = t('todoapple.save');
  actions.append(cancel, save);
  // D 批（同上）：新建名输入面同样换 textarea（多行名可创建）。
  const title = /** @type {HTMLTextAreaElement} */ (el('textarea', 'ta-create-title'));
  title.rows = 1;
  title.placeholder = t('todoapple.newTodo');
  title.setAttribute('aria-label', t('todoapple.newTodo'));
  bindImeGuard(title);
  const notes = /** @type {HTMLTextAreaElement} */ (el('textarea', 'ta-create-notes'));
  notes.rows = 3;
  notes.placeholder = t('todoapple.notes');
  notes.setAttribute('aria-label', t('todoapple.notes'));
  bindImeGuard(notes);
  sheet.append(title, notes);
  const options = elIn(sheet, 'div', 'ta-create-options');
  const flag = btnEl('ta-create-option');
  flag.type = 'button';
  const tag = btnEl('ta-create-option');
  tag.type = 'button';
  options.append(flag, tag);
  const renderOptions = () => {
    flag.textContent = `${t('todoapple.toPriority')}: ${priorityLabel(priority)}`;
    tag.textContent = `${t('todoapple.toTag')}: ${tagId ? (todoStore.tagById(tagId)?.name || t('todoapple.none')) : t('todoapple.none')}`;
  };
  renderOptions();
  flag.addEventListener('click', () => { priority = (priority + 1) % 4; renderOptions(); });
  tag.addEventListener('click', () => {
    const tags = todoStore.tags;
    if (!tags.length) return;
    const index = tags.findIndex((entry) => entry.id === tagId);
    tagId = tags[(index + 1) % tags.length].id;
    renderOptions();
  });
  const close = () => {
    document.removeEventListener('nf:mobile-nav', onNavigate);
    overlay.remove();
    if (trigger.isConnected) trigger.focus();
  };
  const onNavigate = () => close();
  document.addEventListener('nf:mobile-nav', onNavigate);
  cancel.addEventListener('click', close);
  overlay.addEventListener('click', (event) => { if (event.target === overlay) close(); });
  overlay.addEventListener('keydown', (event) => { if (event.key === 'Escape') close(); });
  const error = elIn(sheet, 'div', 'ta-create-error');
  error.setAttribute('role', 'alert');
  if (pendingCreate) {
    title.value = pendingCreate.title;
    notes.value = pendingCreate.notes;
    title.readOnly = notes.readOnly = true;
    flag.disabled = tag.disabled = true;
    // The retry is pinned to its original session/identity. If that can no longer succeed
    // (session switched, or the row was edited after a lost receipt), the user needs an
    // explicit way out that is not "close the tab": drop the identity, keep the text, and
    // allow a fresh create. The original row is never guessed away.
    const discard = btnEl('ta-create-discard');
    discard.type = 'button';
    discard.textContent = t('todoapple.discardPending');
    discard.addEventListener('click', () => {
      pendingCreate = null;
      persistPendingCreate();
      title.readOnly = notes.readOnly = false;
      flag.disabled = tag.disabled = false;
      error.textContent = '';
      discard.remove();
    });
    actions.insertBefore(discard, save);
  }
  let saving = false;
  const submit = async () => {
    const value = title.value.trim();
    if (!value || saving) { if (!value) title.focus(); return; }
    if (!state.activeSessionId) { error.textContent = t('todoapple.createUnconfirmed'); return; }
    if (!pendingCreate) {
      pendingCreate = { tagId, title: value, notes: notes.value, priority, sessionId: state.activeSessionId, requestId: newCreateRequestId() };
      persistPendingCreate();
    }
    saving = true;
    save.disabled = true;
    title.readOnly = notes.readOnly = true;
    flag.disabled = tag.disabled = true;
    error.textContent = '';
    const ok = await todoStore.createItemConfirmed(pendingCreate);
    saving = false;
    save.disabled = false;
    if (ok) { pendingCreate = null; persistPendingCreate(); close(); }
    else error.textContent = t('todoapple.createUnconfirmed');
  };
  save.addEventListener('click', submit);
  title.addEventListener('keydown', (event) => { if (commitEnter(event, title)) submit(); });
  requestAnimationFrame(() => { if (overlay.isConnected) title.focus(); });
}

/** Draft reset (rebuilt on every item-page entry: edit = the item's current reading; create = the list page's current tag). */
function resetDraft() {
  if (itemTarget) {
    const it = todoStore.allItems().find((x) => x.id === itemTarget) || null;
    draft = it
      ? { title: it.title, notes: it.notes, due: it.due, priority: it.priority, tagId: it.tagId }
      : { title: '', notes: '', due: null, priority: 0, tagId: null };
    return;
  }
  const first = todoStore.tags[0];
  draft = { title: '', notes: '', due: null, priority: 0, tagId: listTarget.tagId || (first ? first.id : null) };
}

// ── Keyboard avoidance (minimal visualViewport implementation; imeGuard only
// knows composition state, not keyboard geometry) ──
// The toolbar must hug the keyboard. Implementation: listen to visualViewport
// resize/scroll and write "window height − (visual height + visual offset)"
// into --ta-kb on the root; the toolbar CSS reads it in its bottom calc().
// Legacy WebViews without visualViewport degrade to --ta-kb staying 0, so the
// toolbar rests on the dock's top edge (resting position) with zero errors
// and zero occlusion.
let kbBound = false;
function bindKeyboardAvoidance() {
  if (kbBound) return;
  const vv = window.visualViewport;
  if (!vv) return;
  kbBound = true;
  const apply = () => {
    const kb = Math.max(0, window.innerHeight - (vv.height + vv.offsetTop));
    document.documentElement.style.setProperty('--ta-kb', `${Math.round(kb)}px`);
  };
  vv.addEventListener('resize', apply);
  vv.addEventListener('scroll', apply);
  apply();
}

// ── Mount table + single-flight rerender subscription ────────────────────
/** @type {Map<string, { root: HTMLElement, render: () => void }>} */
const mounts = new Map();
let subscribed = false;
let localeBound = false;

function rerenderAll() {
  for (const m of mounts.values()) {
    try { m.render(); } catch (e) { console.error('[todo-apple] render', e); }
  }
}

function ensureSubscribed() {
  if (!subscribed) {
    subscribed = true;
    // The Completed card and list read archived rows, which the store only
    // delivers on opt-in (includeCompleted). Refresh before subscribe so the
    // lazy first pull inside subscribe becomes a no-op (one frame, not two).
    todoStore.refresh({ includeCompleted: true });
    todoStore.subscribe(() => rerenderAll());
  }
  if (!localeBound) {
    localeBound = true;
    window.addEventListener('locale-changed', () => rerenderAll());
  }
  bindKeyboardAvoidance();
}

/** Page shell (section) — floating chrome (capsule/FAB/toolbar) hangs here so it does not scroll with the body. */
/** @param {Element} body @returns {HTMLElement|null} */
function sectionOf(body) {
  const s = body.closest('.mobile-page');
  return s instanceof HTMLElement ? s : null;
}

/** Clear stale chrome (the page shell is persistent; leave-and-reenter must not stack a second set of floating pieces). */
/** @param {HTMLElement} section */
function clearChrome(section) {
  for (const sel of ['.ta-cluster', '.ta-fab-wrap', '.ta-menu', '.ta-tool']) {
    for (const n of section.querySelectorAll(`:scope > ${sel}`)) n.remove();
  }
}

// ══════════════════════════════════════════════════════════════════════
// A · Lists home page (tasks)
// ══════════════════════════════════════════════════════════════════════

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildHome(root, section, nav) {
  // ── Four-card matrix (2x2): white outline icon top-left / large white
  // count top-right / white label bottom-left ──
  const grid = elIn(root, 'div', 'ta-grid');
  /**
   * @param {'today'|'overdue'|'all'|'completed'} kind
   * @param {string} icon
   * @returns {HTMLButtonElement}
   */
  const card = (kind, icon) => {
    const b = /** @type {HTMLButtonElement} */ (elIn(grid, 'button', `ta-card ta-card-${kind}`));
    b.type = 'button';
    // Structured build (contract R5): icon + two stat spans, DOM identical to
    // the previous template string.
    b.append(icoEl(icon, 'ta-card-ico'), el('span', 'ta-card-n'), el('span', 'ta-card-l'));
    return b;
  };
  const cToday = card('today', 'calendar-days');
  const cOverdue = card('overdue', 'circle-alert');
  const cAll = card('all', 'list-todo');
  const cDone = card('completed', 'circle-check');
  cToday.addEventListener('click', () => openList('today', null, nav));
  cOverdue.addEventListener('click', () => openList('overdue', null, nav));
  cAll.addEventListener('click', () => openList('all', null, nav));
  cDone.addEventListener('click', () => openList('completed', null, nav));

  // ── Inline search bar (expanded by the capsule search key; local filtering
  // = real capability, no backend) ──
  const searchWrap = elIn(root, 'div', 'ta-searchbar');
  searchWrap.hidden = true;
  const searchInput = inputEl('ta-search-input');
  searchInput.type = 'search';
  searchInput.autocomplete = 'off';
  bindImeGuard(searchInput);
  searchWrap.appendChild(searchInput);
  searchInput.addEventListener('input', () => { searchQuery = searchInput.value; render(); });
  searchInput.addEventListener('keydown', (e) => { if (commitEnter(e, searchInput)) searchInput.blur(); });

  // ── "My Lists" section heading (bold body-colored text, not inside a card)
  // + large-radius surface card ──
  const secLabel = elIn(root, 'div', 'ta-sec-label');
  const cardBox = elIn(root, 'div', 'ta-listcard');
  const rowsBox = elIn(cardBox, 'div', 'ta-rows');
  // New-list row (expanded by the capsule "new list" key; Enter persists it
  // via the real todoStore.createTag capability)
  const newRow = elIn(cardBox, 'div', 'ta-row ta-row-new');
  newRow.hidden = true;
  elIn(newRow, 'span', 'ta-dot ta-dot-new');
  const nlInput = inputEl('ta-newlist-input nf-field--bare');
  nlInput.type = 'text';
  nlInput.autocomplete = 'off';
  bindImeGuard(nlInput);
  newRow.appendChild(nlInput);
  const commitNewList = () => {
    const name = nlInput.value.trim();
    if (name) {
      const color = TAG_COLOR_PRESETS[todoStore.tags.length % TAG_COLOR_PRESETS.length];
      todoStore.createTag(name, color);
    }
    nlInput.value = '';
    newListOpen = false;
    render();
  };
  nlInput.addEventListener('keydown', (e) => { if (commitEnter(e, nlInput)) commitNewList(); });
  nlInput.addEventListener('blur', () => { if (newListOpen) commitNewList(); });

  // ── White three-icon capsule at the top right (left end stays empty for the
  // back button; all three keys have real backing capabilities) ──
  const cluster = elIn(section, 'div', 'ta-cluster');
  // 左槽隐形占位（ui-unify-panels 批 2026-11-17）：首页顶带左槽没有返回钮动作
  // ⇒ 按带几何口径放等宽 invisible spacer 保三区结构（子页同槽 = 壳返回钮）。
  // 绝不渲染可点但无功能的假按钮：spacer aria-hidden + pointer-events none。
  const slotSpacer = elIn(cluster, 'div', 'ta-slot-spacer');
  slotSpacer.setAttribute('aria-hidden', 'true');
  const pill = elIn(cluster, 'div', 'ta-pill nf-glass');
  const bSearch = iconBtn('ta-pill-btn', 'search', t('todoapple.search'));
  const bNewList = iconBtn('ta-pill-btn', 'list-plus', t('todoapple.newList'));
  const bMore = iconBtn('ta-pill-btn', 'ellipsis', t('todoapple.more'));
  pill.append(bSearch, bNewList, bMore);
  bSearch.addEventListener('click', () => {
    searchOpen = !searchOpen;
    moreOpen = false;
    if (!searchOpen) searchInput.value = searchQuery = '';
    render();
    if (searchOpen) searchInput.focus();
  });
  bNewList.addEventListener('click', () => {
    newListOpen = !newListOpen;
    moreOpen = false;
    render();
    if (newListOpen) nlInput.focus();
  });
  bMore.addEventListener('click', () => { moreOpen = !moreOpen; render(); });

  // More menu (two entries = real entries: completed view / all todos)
  const menu = elIn(section, 'div', 'ta-menu nf-glass');
  menu.hidden = true;
  const mDone = btnEl('ta-menu-row');
  mDone.type = 'button';
  // Structured build (contract R5): two label spans, DOM identical to the
  // previous template string.
  mDone.append(el('span', 'ta-menu-l'), el('span', 'ta-menu-n'));
  const mAll = btnEl('ta-menu-row');
  mAll.type = 'button';
  mAll.append(el('span', 'ta-menu-l'), el('span', 'ta-menu-n'));
  menu.append(mDone, mAll);
  mDone.addEventListener('click', () => { moreOpen = false; openList('completed', null, nav); });
  mAll.addEventListener('click', () => { moreOpen = false; openList('all', null, nav); });

  // ── Blue FAB bottom-right (+): new todo (defaults to the first tag) ──
  // Positioning shell = .ta-fab-wrap (absolute placement, kept out of the
  // chrome scan band — see the CSS note); button body = .ta-fab.g2-fab
  // (the spec-pinned base class: material/geometry/spring single source).
  const fabWrap = elIn(section, 'div', 'ta-fab-wrap');
  const fab = iconBtn('ta-fab g2-fab', 'plus', t('todoapple.newTodo'));
  fabWrap.appendChild(fab);
  fab.addEventListener('click', () => openCreateSheet(section, null, fab));

  /** @returns {void} */
  function render() {
    // Card readings: today = due today and open / overdue = past due and open /
    // all = total open / completed = completed count
    /** @param {HTMLButtonElement} b @param {string} label @param {number} n */
    const setCard = (b, label, n) => {
      b.classList.toggle('is-empty', n === 0);
      /** @type {HTMLElement} */ (b.querySelector('.ta-card-n')).textContent = String(n);
      /** @type {HTMLElement} */ (b.querySelector('.ta-card-l')).textContent = label;
    };
    setCard(cToday, t('todoapple.today'), todoStore.dueTodayCount());
    setCard(cOverdue, t('todoapple.overdue'), todoStore.overdueCount());
    setCard(cAll, t('todoapple.all'), todoStore.openCount());
    setCard(cDone, t('todoapple.completed'), todoStore.completedCount());

    // Capsule state
    bSearch.classList.toggle('on', searchOpen);
    bNewList.classList.toggle('on', newListOpen);
    bMore.classList.toggle('on', moreOpen);
    searchWrap.hidden = !searchOpen;
    searchInput.placeholder = t('todoapple.searchPh');
    newRow.hidden = !newListOpen;
    nlInput.placeholder = t('todoapple.newListPh');

    // More menu
    menu.hidden = !moreOpen;
    /** @type {HTMLElement} */ (mDone.querySelector('.ta-menu-l')).textContent = t('todoapple.completed');
    /** @type {HTMLElement} */ (mDone.querySelector('.ta-menu-n')).textContent = String(todoStore.completedCount());
    /** @type {HTMLElement} */ (mAll.querySelector('.ta-menu-l')).textContent = t('todoapple.allTodos');
    /** @type {HTMLElement} */ (mAll.querySelector('.ta-menu-n')).textContent = String(todoStore.openCount());

    // Section heading
    secLabel.textContent = searchOpen ? t('todoapple.searchResults') : t('todoapple.myLists');

    // List card rows
    rowsBox.replaceChildren();
    if (searchOpen) {
      const q = searchQuery.trim().toLowerCase();
      const hits = todoStore.allItems().filter((it) => !it.completed
        && (!q || it.title.toLowerCase().includes(q)
          || String(it.notes || '').toLowerCase().includes(q)));
      for (const it of hits.slice(0, 30)) {
        const tg = todoStore.tagById(it.tagId);
        const row = btnEl('ta-row ta-row-hit');
        row.type = 'button';
        // Structured build (contract R5): dot + label + sub spans, DOM
        // identical to the previous template string.
        row.append(el('span', 'ta-dot'), el('span', 'ta-row-label'), el('span', 'ta-row-sub'));
        const dot = /** @type {HTMLElement} */ (row.querySelector('.ta-dot'));
        dot.style.background = tg ? tg.color : 'var(--color-text-muted)';
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-label')).textContent = it.title || '—';
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-sub')).textContent = tg ? tg.name : '';
        row.addEventListener('click', () => openItem(it.id, nav));
        rowsBox.appendChild(row);
      }
      if (!hits.length) rowsBox.appendChild(el('div', 'ta-row-empty', t('todoapple.searchEmpty')));
    } else {
      for (const tg of todoStore.tags) {
        const row = btnEl('ta-row ta-row-tag');
        row.type = 'button';
        // Structured build (contract R5): dot + label + count + chevron, DOM
        // identical to the previous template string.
        row.append(el('span', 'ta-dot'), el('span', 'ta-row-label'),
          el('span', 'ta-row-count'), icoEl('chevron-right', 'ta-chev'));
        const dot = /** @type {HTMLElement} */ (row.querySelector('.ta-dot'));
        dot.style.background = tg.color;
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-label')).textContent = tg.name;
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-count')).textContent = String(todoStore.openCountOf(tg.id));
        row.addEventListener('click', () => openList('tag', tg.id, nav));
        rowsBox.appendChild(row);
      }
      // Last row "All todos" grey row = the All entry (with no tags it is the
      // only row, i.e. the degraded form)
      const allRow = btnEl('ta-row ta-row-all');
      allRow.type = 'button';
      // Structured build (contract R5): same shape as the tag row (grey dot),
      // DOM identical to the previous template string.
      allRow.append(el('span', 'ta-dot ta-dot-all'), el('span', 'ta-row-label'),
        el('span', 'ta-row-count'), icoEl('chevron-right', 'ta-chev'));
      /** @type {HTMLElement} */ (allRow.querySelector('.ta-row-label')).textContent = t('todoapple.allTodos');
      /** @type {HTMLElement} */ (allRow.querySelector('.ta-row-count')).textContent = String(todoStore.openCount());
      allRow.addEventListener('click', () => openList('all', null, nav));
      rowsBox.appendChild(allRow);
    }

    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

// ══════════════════════════════════════════════════════════════════════
// B · List detail page (todo-apple-list)
// ══════════════════════════════════════════════════════════════════════

/** Items under the current list target (local read-only filtering; the store's filter state is never written). */
function itemsForTarget() {
  const all = todoStore.allItems();
  const now = Date.now();
  const from = startOfToday();
  const to = from + DAY;
  /**
   * @param {TaItem} it
   * @returns {boolean}
   */
  const pick = (it) => {
    switch (listTarget.mode) {
      case 'completed': return it.completed;
      case 'today': return !it.completed && it.due != null && it.due >= from && it.due < to;
      case 'overdue': return !it.completed && it.due != null && it.due < now;
      case 'tag': return !it.completed && it.tagId === listTarget.tagId;
      default: return !it.completed;
    }
  };
  return all.filter(pick).sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    if (a.due != null && b.due != null) return a.due - b.due;
    if (a.due == null && b.due == null) return a.createdAt - b.createdAt;
    return a.due == null ? 1 : -1;
  });
}

/** List heading (text + color): mode pages take theme tokens, tag pages take tag.color (data color). */
/** @returns {{ text: string, color: string }} */
function listHead() {
  switch (listTarget.mode) {
    case 'completed': return { text: t('todoapple.completed'), color: 'var(--color-text-muted)' };
    case 'today': return { text: t('todoapple.today'), color: 'rgb(var(--sapphire))' };
    case 'overdue': return { text: t('todoapple.overdue'), color: 'var(--color-error)' };
    case 'tag': {
      const tg = listTarget.tagId ? todoStore.tagById(listTarget.tagId) : null;
      return { text: tg ? tg.name : t('todoapple.allTodos'), color: tg ? tg.color : 'var(--color-text)' };
    }
    default: return { text: t('todoapple.allTodos'), color: 'var(--color-text)' };
  }
}

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildList(root, section, nav) {
  const wrap = elIn(root, 'div', 'ta-detail');
  const title = elIn(wrap, 'h1', 'ta-dtitle');
  const itemsBox = elIn(wrap, 'div', 'ta-items');
  const empty = elIn(wrap, 'div', 'ta-empty');

  // White capsule top right: the only backed capability = create a todo into
  // this list (createItem)
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill nf-glass');
  const addBtn = iconBtn('ta-pill-btn', 'list-plus', t('todoapple.newTodo'));
  pill.appendChild(addBtn);
  addBtn.addEventListener('click', () => openCreateSheet(section, listTarget.mode === 'tag' ? listTarget.tagId : null, addBtn));

  /** @returns {void} */
  function render() {
    const head = listHead();
    title.textContent = head.text;
    title.style.color = head.color;
    const list = itemsForTarget();
    itemsBox.replaceChildren();
    for (const it of list) {
      const row = el('div', 'ta-item');
      row.classList.toggle('is-done', it.completed);
      row.dataset.itemId = it.id;

      // Left hollow-circle checkbox: visual 24, hit area 48x48 (>=44 contract)
      const check = btnEl('ta-check');
      check.type = 'button';
      check.setAttribute('aria-label', t('todoapple.markComplete'));
      // Structured build (contract R5): single circle span, DOM identical to
      // the previous template string.
      check.appendChild(el('span', 'ta-check-circle'));
      check.addEventListener('click', (e) => {
        e.stopPropagation();
        todoStore.completeItem(it.id);
      });
      row.appendChild(check);

      // Priority mark: only when priority > 0 (blue/orange/red data colors)
      if (it.priority > 0) {
        const prio = el('span', 'ta-prio');
        prio.style.background = priorityColor(it.priority);
        prio.setAttribute('aria-hidden', 'true');
        row.appendChild(prio);
      }

      // Row body = text button (click drills into the item detail; same
      // push/pop stack + back gesture)
      const bodyBtn = btnEl('ta-item-body');
      bodyBtn.type = 'button';
      elIn(bodyBtn, 'span', 'ta-item-title', it.title || '—');
      if (it.due != null) elIn(bodyBtn, 'span', 'ta-item-due', dueText(it.due));
      bodyBtn.addEventListener('click', () => openItem(it.id, nav));
      row.appendChild(bodyBtn);

      itemsBox.appendChild(row);
    }
    empty.hidden = list.length > 0;
    empty.textContent = listTarget.mode === 'completed'
      ? t('todoapple.emptyCompleted') : t('todoapple.emptyDone');
    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

// ══════════════════════════════════════════════════════════════════════
// C · Create / edit state (todo-apple-item)
// ══════════════════════════════════════════════════════════════════════

/** Toolbar "date" cycle: none → today 21:00 → tomorrow 21:00 → none (real due write). */
function cycleDue() {
  if (draft.due == null) { draft.due = todayAt(21); return; }
  if (draft.due < startOfToday() + DAY) { draft.due = todayAt(21) + DAY; return; }
  draft.due = null;
}

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildItem(root, section, nav) {
  const wrap = elIn(root, 'div', 'ta-edit');
  const row = elIn(wrap, 'div', 'ta-newrow');
  // D 批（ui-unify-panels 2026-11-17「待办内容不要截断，展示与编辑都多行」）：
  // 名编辑域 input → textarea（多行可编；保存走底部 ta-save 簇）。
  const input = /** @type {HTMLTextAreaElement} */ (elIn(row, 'textarea', 'ta-title-input nf-field--bare'));
  input.rows = 1;
  bindImeGuard(input);
  const infoBtn = iconBtn('ta-info', 'info', t('todoapple.info'));
  row.appendChild(infoBtn);
  const notes = /** @type {HTMLTextAreaElement} */ (elIn(wrap, 'textarea', 'ta-notes'));
  notes.rows = 3;
  notes.hidden = true;
  bindImeGuard(notes);
  const meta = elIn(wrap, 'div', 'ta-meta');
  const replyBox = elIn(wrap, 'div', 'ta-reply');
  const reply = /** @type {HTMLTextAreaElement} */ (elIn(replyBox, 'textarea', 'ta-notes'));
  reply.placeholder = t('todoface.replyPh');
  reply.setAttribute('aria-label', t('todoface.replyPh'));
  const replySend = btnEl('ta-create-save');
  replySend.type = 'button';
  replySend.textContent = t('todoface.send');
  replyBox.appendChild(replySend);
  const replyError = elIn(replyBox, 'div', 'ta-create-error');
  replyError.setAttribute('role', 'alert');
  replySend.addEventListener('click', async () => {
    const item = todoStore.allItems().find((entry) => entry.id === itemTarget);
    const text = reply.value.trim();
    if (!item || !text || replySend.disabled) return;
    replySend.disabled = true;
    let ok = false;
    try {
      const { sendPrimaryTodoReply } = await import('../input.js');
      ok = sendPrimaryTodoReply(`#${item.title}·${item.id} ${text}`);
    } catch { ok = false; }
    replySend.disabled = false;
    if (ok) { reply.value = ''; replyError.textContent = ''; }
    else replyError.textContent = t('todoapple.replyFailed');
  });

  // Top bar, three pieces: back on the left (page shell "<"), capsule on the
  // right, blue circular check save at the far right.
  // Capsule contents are trimmed to real capabilities (a button without a
  // backing capability is not rendered):
  //   · edit state = "mark complete" (the completeItem write);
  //   · create state = "flag" shortcut (same draft.priority write as the
  //     toolbar flag; no new entity) — in create state there is no item to
  //     complete, so the complete key never appears.
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill nf-glass');
  const doneBtn = iconBtn('ta-pill-btn', 'circle-check', t('todoapple.markComplete'));
  const flagBtn = iconBtn('ta-pill-btn', 'flag', t('todoapple.toPriority'));
  pill.append(doneBtn, flagBtn);
  doneBtn.addEventListener('click', () => {
    if (!itemTarget) return;
    todoStore.completeItem(itemTarget);
    nav.pop();
  });
  flagBtn.addEventListener('click', () => { draft.priority = (draft.priority + 1) % 4; render(); });
  const save = iconBtn('ta-save g2-fab', 'check', t('todoapple.save'));
  cluster.appendChild(save);
  save.addEventListener('click', () => saveDraft(nav));

  // Keyboard toolbar (hugs the keyboard: bottom reads --ta-kb; three keys =
  // date / flag / tag)
  const tool = elIn(section, 'div', 'ta-tool nf-glass');
  /**
   * @param {string} icon
   * @param {string} label
   * @returns {{ el: HTMLButtonElement, label: HTMLElement }}
   */
  const toolBtn = (icon, label) => {
    const b = btnEl('ta-tool-btn');
    b.type = 'button';
    // Structured build (contract R5): icon + label span, DOM identical to the
    // previous template string.
    b.append(icoEl(icon), el('span', 'ta-tool-label'));
    const lab = /** @type {HTMLElement} */ (b.querySelector('.ta-tool-label'));
    lab.textContent = label;
    b.setAttribute('aria-label', label);
    tool.appendChild(b);
    return { el: b, label: lab };
  };
  const tDue = toolBtn('calendar', t('todoapple.toDue'));
  const tFlag = toolBtn('flag', t('todoapple.toPriority'));
  const tTag = toolBtn('hash', t('todoapple.toTag'));
  tDue.el.addEventListener('click', () => { cycleDue(); render(); });
  tFlag.el.addEventListener('click', () => { draft.priority = (draft.priority + 1) % 4; render(); });
  tTag.el.addEventListener('click', () => {
    const tags = todoStore.tags;
    if (!tags.length) return;
    const i = tags.findIndex((x) => x.id === draft.tagId);
    if (i < 0) draft.tagId = tags[0] ? tags[0].id : null;
    else draft.tagId = i + 1 < tags.length ? tags[i + 1].id : null;
    render();
  });

  infoBtn.addEventListener('click', () => {
    notes.hidden = !notes.hidden;
    if (!notes.hidden) notes.focus();
  });
  input.addEventListener('input', () => { draft.title = input.value; });
  notes.addEventListener('input', () => { draft.notes = notes.value; });
  input.addEventListener('keydown', (e) => { if (commitEnter(e, input)) saveDraft(nav); });

  /** @returns {void} */
  function render() {
    input.placeholder = t('todoapple.newTodo');
    if (document.activeElement !== input) input.value = draft.title;
    if (document.activeElement !== notes) notes.value = draft.notes;
    notes.placeholder = t('todoapple.notes');

    // Draft summary (date / priority / tag chips; absent values are not shown
    // = no shell without an entity)
    meta.replaceChildren();
    if (draft.due != null) elIn(meta, 'span', 'ta-chip ta-chip-due', dueText(draft.due));
    if (draft.priority > 0) {
      const chip = elIn(meta, 'span', 'ta-chip ta-chip-prio');
      const dot = elIn(chip, 'span', 'ta-prio');
      dot.style.background = priorityColor(draft.priority);
      elIn(chip, 'span', 'ta-chip-t', priorityLabel(draft.priority));
    }
    const tg = draft.tagId ? todoStore.tagById(draft.tagId) : null;
    if (tg) {
      const chip = elIn(meta, 'span', 'ta-chip ta-chip-tag');
      const dot = elIn(chip, 'span', 'ta-dot');
      dot.style.background = tg.color;
      elIn(chip, 'span', 'ta-chip-t', tg.name);
    }

    // Toolbar readings mirror the current draft values
    tDue.label.textContent = draft.due == null ? t('todoapple.noDue') : dueText(draft.due);
    tFlag.label.textContent = priorityLabel(draft.priority);
    tTag.label.textContent = tg ? tg.name : t('todoapple.toTag');
    // Capsule: in create state only "flag" is backed (nothing to complete, so
    // the complete key is absent); in edit state both keys show
    doneBtn.hidden = !itemTarget;
    replyBox.hidden = !itemTarget;
    flagBtn.classList.toggle('on', draft.priority > 0);
    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

/** Save (create = createItem / edit = updateItem; an empty title never persists = no empty entities). */
/** @param {TaNav} nav */
function saveDraft(nav) {
  const title = draft.title.trim();
  if (!title) return;
  if (itemTarget) {
    /** @type {{title:string, notes:string, priority:number, due?:number, hasDue?:boolean, tagId?:string|null}} */
    const patch = { title, notes: draft.notes, priority: draft.priority };
    if (draft.due != null) patch.due = draft.due; else patch.hasDue = false;
    patch.tagId = draft.tagId;
    todoStore.updateItem(itemTarget, patch);
  } else {
    todoStore.createItem({
      tagId: draft.tagId, title, notes: draft.notes, due: draft.due, priority: draft.priority,
    });
  }
  nav.pop();
}

// ══════════════════════════════════════════════════════════════════════
// Mount entry (dispatched by navController.ensurePanelContent; idempotent)
// ══════════════════════════════════════════════════════════════════════

/**
 * Build one of the three page bodies into `body` (idempotent: repeated calls
 * only re-render).
 * @param {string} pageId 'tasks' | 'todo-apple-list' | 'todo-apple-item'
 * @param {Element|null} body page body container
 * @param {TaNav} nav injected navigation (pushPage/popPage; avoids a static
 *   cycle with navController)
 * @returns {void}
 */
export function mountTodoAppleInto(pageId, body, nav) {
  if (!body) return;
  const section = sectionOf(body);
  if (!section) return;
  ensureSubscribed();
  // Re-entry = full page rebuild (the page shell DOM is persistent; enter /
  // leave / re-enter must not stack a second set of floating pieces; the
  // three pages share no mutable DOM and a rebuild costs a few dozen rows).
  // Draft state lives in module state, so a rebuild does not lose it.
  const prev = mounts.get(pageId);
  if (prev) {
    if (prev.root.parentElement === body) prev.root.remove();
    mounts.delete(pageId);
  }
  clearChrome(section);
  const root = el('div', 'ta-root');
  root.dataset.taPage = pageId;
  body.appendChild(root);

  /** @type {{ root: HTMLElement, render: () => void }} */
  let built;
  if (pageId === 'tasks') {
    searchOpen = false;
    searchQuery = '';
    newListOpen = false;
    moreOpen = false;
    built = buildHome(root, section, nav);
  } else if (pageId === 'todo-apple-list') {
    built = buildList(root, section, nav);
  } else {
    resetDraft();
    built = buildItem(root, section, nav);
    // Create state = caret in place (new row = caret + grey placeholder)
    const inp = /** @type {HTMLInputElement|null} */ (root.querySelector('.ta-title-input'));
    if (inp) requestAnimationFrame(() => { try { inp.focus(); } catch { /* focus refused */ } });
  }
  mounts.set(pageId, built);
  built.render();
  if (typeof lucide !== 'undefined') createIconsIn(section);
}

/** Controlled rerender (QA/follow-up batches: external data changes need not wait for the store subscription). */
export function refreshTodoApple() { rerenderAll(); }
