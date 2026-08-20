// chatSearch.js — Chat history search modal (v3.1: WeChat retrieval-window paradigm).
//
// Header button (or Cmd/Ctrl+F) opens a centered modal (no dimming backdrop,
// no blur). v3.1 information architecture on top of the v2.1 contract:
//   - Opens straight into a browse stream of recent messages (no idle hint).
//   - Category tabs (All / Images / Files / Pop) filter orthogonally with
//     scope; switching tabs keeps the keyword and re-runs.
//   - Date is an ANCHOR, not a filter: the calendar popover stages a single
//     day, OK applies it as a scroll anchor and the list loads
//     bidirectionally around it (before/after cursor pages, seamless across
//     days). Pop tab disables the date anchor (tool messages carry no ts).
//   - Esc is three-stage: calendar popover > keyword > close.
//
// Data model (spec §6.3):
//   - Browse/anchor modes stream pages of FETCH_PAGE with lazy rendering in
//     RENDER_BATCH chunks via an IntersectionObserver sentinel.
//   - Search mode (keyword non-empty) stays the v2.1 one-shot query
//     (300ms debounce + requestId race guard + MAX_RESULTS cap).
//
// Cursor contract (ratified 2026-08-17): fetchHistory sends
//   ?before=<epoch ms>&after=<epoch ms>&limit=<n>
// 'before' is inclusive (<= the anchor day's 23:59:59.999). Today's backend
// ignores unknown params and returns the full history, so pages are sliced
// client-side from the full response; once real pagination lands the
// frontend switches over with zero changes.
//
// Messages are fetched via the REST API (backend is the source of truth):
//   GET /api/sessions?includeUnindexed=1 → session list incl. unindexed
//   GET /api/sessions/{id}/history       → UiMessage list per session

import state from './state.js';
import { key } from './branding.js';
import { chatViews } from './chatView.js';
import { switchToSession } from './sidebar.js';
import { sendWs } from './ws.js';
import { t, getLocale } from './i18n.js';
import { escapeHtml } from './utils.js';
import { popArtifactFromInput, openPopArtifact } from './chat.js';
import { expandGroupContaining } from './turnGroup.js';

const MAX_RESULTS = 200;      // search-mode cap (v2.1 one-shot query)
const FETCH_PAGE = 100;       // stream page size (spec §6.3 caps a page at ≤200;
                              // 100 keeps cursor pagination reachable for B9's ≥120-seed)
const RENDER_BATCH = 50;      // lazy-render batch (spec §6.1)
const FETCH_CONCURRENCY = 4;
const DEBOUNCE_MS = 300;
const PAGE_JUMP = 5;

const CONTENT_TABS = ['all', 'images', 'files', 'pop'];
const TAB_I18N = { all: 'search.tabAll', images: 'search.tabImages', files: 'search.tabFiles', pop: 'search.tabPop' };

let initialized = false;
let lastResults = [];        // loaded window items in display order
let modelKeys = new Map();   // item key → model index
let renderedCount = 0;       // how many of lastResults are in the DOM
let lastTotal = 0;           // uncapped total (search mode) / loaded count (stream)
let activeIndex = -1;
let searchSeq = 0;           // race guard — responses from older runs are dropped
let searchAbort = null;      // AbortController for the in-flight search
let debounceTimer = 0;

// ── v3.1 view state ────────────────────────────────────────
let currentTab = 'all';      // 'all' | 'images' | 'files' | 'pop'
/** @type {{y:number, m0:number, d:number}|null} applied date anchor (local) */
let anchorDate = null;
let calOpen = false;
let calView = { y: 0, m0: 0 };       // calendar grid month being displayed
/** @type {{y:number, m0:number, d:number}|null} staged (unconfirmed) pick */
let calStaged = null;
let pillListboxEl = null;    // open year/month listbox element

// ── Stream state (browse/anchor modes) ─────────────────────
/** @type {Array<{id:string, name:string, all:any[], view:any[], start:number, end:number, el:HTMLElement|null, headerEl:HTMLElement|null}>} */
let streamSessions = [];
/** @type {Array<any>} sessions with non-empty windows, in display order */
let streamGroups = [];
let streamMode = 'browse';   // 'browse' | 'anchor' | 'search'
let pageInFlight = false;
let sentinelEl = null;
let sentinelObserver = null;

/**
 * Typed element lookups (getElementById returns bare HTMLElement).
 * @param {string} id
 * @returns {HTMLInputElement|null}
 */
const inputById = (id) => /** @type {HTMLInputElement|null} */ (document.getElementById(id));
/**
 * @param {string} id
 * @returns {HTMLSelectElement|null}
 */
const selectById = (id) => /** @type {HTMLSelectElement|null} */ (document.getElementById(id));

const isGrouped = () => (selectById('search-scope')?.value || 'current') === 'all';

// ── Init ───────────────────────────────────────────────────
export function initChatSearch() {
  if (initialized) return;
  initialized = true;

  document.getElementById('search-btn')?.addEventListener('click', openSearchModal);
  document.getElementById('search-modal-close')?.addEventListener('click', closeSearchModal);
  const overlay = document.getElementById('search-overlay');
  overlay?.addEventListener('click', (e) => {
    if (e.target === overlay) closeSearchModal();
  });
  // Demoted to a secondary submit (progressive search is primary).
  document.getElementById('search-go')?.addEventListener('click', triggerSearch);

  // Progressive search: typing and filter changes re-search after a 300ms
  // debounce.
  document.getElementById('search-keyword')?.addEventListener('input', scheduleSearch);
  for (const id of ['search-scope', 'search-agent', 'search-tool']) {
    document.getElementById(id)?.addEventListener('change', scheduleSearch);
  }

  // Category tabs (click). Date tab toggles the calendar popover instead of
  // switching category; its inline × clears the anchor.
  for (const tab of CONTENT_TABS) {
    document.getElementById(`search-tab-${tab}`)?.addEventListener('click', () => switchTab(tab));
  }
  document.getElementById('search-tab-date')?.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    if (e.target.closest('.search-date-clear')) { e.stopPropagation(); clearAnchor(); return; }
    if (currentTab === 'pop') return;   // aria-disabled: anchor is meaningless for ts=0 tool messages
    toggleCalendar();
  });

  // Outside click discards the staged calendar pick (staging-confirm
  // semantics: only OK applies).
  document.addEventListener('pointerdown', (e) => {
    if (!calOpen || !(e.target instanceof Element)) return;
    const pop = document.getElementById('search-date-popover');
    const tab = document.getElementById('search-tab-date');
    if (pop?.contains(e.target) || tab?.contains(e.target)) return;
    closeCalendar(false);
  }, true);

  // Keyboard contract, single capture-phase handler active only while the
  // modal is open — also silences background chat shortcuts.
  document.addEventListener('keydown', onModalKeydown, true);

  // Cmd/Ctrl+F opens the modal (Slack mode). Yield to Monaco's own find
  // when a canvas editor has focus.
  document.addEventListener('keydown', (e) => {
    if (!(e.metaKey || e.ctrlKey) || (e.key !== 'f' && e.key !== 'F')) return;
    if (e.target instanceof Element && e.target.closest('.monaco-editor')) return;
    e.preventDefault();
    openSearchModal();
  });

  const resultsEl = document.getElementById('search-results');

  // Result click — event delegation on the results container
  resultsEl?.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    const el = /** @type {HTMLElement|null} */ (e.target.closest('.search-result'));
    if (!el) return;
    const idx = modelKeys.get(el.dataset.key || '');
    const res = idx === undefined ? null : lastResults[idx];
    if (res) activateResult(res);
  });
  // Hovering a result clears the keyboard selection.
  resultsEl?.addEventListener('mouseover', (e) => {
    if (e.target instanceof Element && e.target.closest('.search-result')) setActive(-1);
  });

  // Anchor mode: scrolling to the very top loads a NEWER page (after cursor).
  resultsEl?.addEventListener('scroll', () => {
    if (streamMode !== 'anchor' || pageInFlight) return;
    if ((resultsEl?.scrollTop ?? 1) <= 2) loadAfterPages();
  });

  // Anchor dead-zone fallback (design review F-1): the anchored list is born
  // at scrollTop=0, where a wheel-up (or upward touch drag) produces NO
  // scroll event and the listener above never fires - R1's "slide from the
  // anchor day into the next" was unreachable without a down-then-up detour.
  // An upward gesture at the top IS the reached-the-top signal: fire the
  // newer-page load directly.
  resultsEl?.addEventListener('wheel', (e) => {
    if (streamMode !== 'anchor' || pageInFlight) return;
    if (e.deltaY < 0 && (resultsEl?.scrollTop ?? 1) <= 2) loadAfterPages();
  }, { passive: true });
  let anchorTouchY = null;
  resultsEl?.addEventListener('touchstart', (e) => {
    anchorTouchY = e.touches[0]?.clientY ?? null;
  }, { passive: true });
  resultsEl?.addEventListener('touchmove', (e) => {
    if (anchorTouchY === null || streamMode !== 'anchor' || pageInFlight) return;
    // Finger moving down over a topped-out list = user wants newer content.
    const dy = (e.touches[0]?.clientY ?? anchorTouchY) - anchorTouchY;
    if (dy > 12 && (resultsEl?.scrollTop ?? 1) <= 2) loadAfterPages();
  }, { passive: true });

  // Lazy rendering: the sentinel at the list end renders the next batch, and
  // once the rendered batches are exhausted it fires the before-cursor page.
  sentinelObserver = new IntersectionObserver((entries) => {
    if (!entries.some(en => en.isIntersecting)) return;
    onSentinel();
  }, { root: resultsEl, rootMargin: '120px' });
}

// ── Modal open/close ───────────────────────────────────────
export function openSearchModal() {
  const overlay = document.getElementById('search-overlay');
  if (!overlay) return;
  resetSearchState();   // Spotlight mode: every open is a fresh session
  renderStaticLabels();
  syncTabsUI();
  overlay.classList.add('on');
  const kw = inputById('search-keyword');
  if (kw) { kw.value = ''; kw.focus(); }
  triggerSearch();      // open-into-browse: no idle hint state anymore
}

export function closeSearchModal() {
  // Abort any in-flight search and drop pending work.
  searchSeq++;
  searchAbort?.abort();
  searchAbort = null;
  clearTimeout(debounceTimer);
  if (calOpen) closeCalendar(false);
  sentinelObserver?.disconnect();
  sentinelEl = null;
  document.getElementById('search-overlay')?.classList.remove('on');
  document.getElementById('search-btn')?.focus();   // return focus to the opener
}

/** Cancel pending/in-flight work and reset all view dimensions. */
function resetSearchState() {
  searchSeq++;
  searchAbort?.abort();
  searchAbort = null;
  clearTimeout(debounceTimer);
  lastResults = [];
  modelKeys = new Map();
  renderedCount = 0;
  lastTotal = 0;
  activeIndex = -1;
  currentTab = 'all';
  anchorDate = null;
  calStaged = null;
  if (calOpen) closeCalendar(false);
  streamSessions = [];
  streamGroups = [];
  streamMode = 'browse';
  pageInFlight = false;
  sentinelObserver?.disconnect();
  sentinelEl = null;
  const resultsEl = document.getElementById('search-results');
  resultsEl?.removeAttribute('data-loading');
  document.getElementById('search-keyword')?.removeAttribute('aria-activedescendant');
}

/** Fill labels/placeholders that depend on the current locale. */
function renderStaticLabels() {
  const scope = document.getElementById('search-scope');
  if (scope) {
    scope.innerHTML =
      `<option value="current">${escapeHtml(t('search.scopeCurrent'))}</option>` +
      `<option value="all">${escapeHtml(t('search.scopeAll'))}</option>`;
  }
  const tool = document.getElementById('search-tool');
  if (tool) {
    tool.innerHTML = `<option value="">${escapeHtml(t('search.allTypes'))}</option>`;
  }
  const agent = document.getElementById('search-agent');
  if (agent) {
    agent.innerHTML = `<option value="">${escapeHtml(t('search.allAgents'))}</option>`;
  }
  for (const tab of CONTENT_TABS) {
    const el = document.getElementById(`search-tab-${tab}`);
    if (el) el.textContent = t(TAB_I18N[tab]);
  }
  syncDateTabLabel();
  const go = document.getElementById('search-go');
  if (go) go.textContent = t('search.go');
  const status = document.getElementById('search-status');
  if (status) status.textContent = '';
  const results = document.getElementById('search-results');
  if (results) results.setAttribute('aria-label', t('search.title'));
}

// ── Category tabs ──────────────────────────────────────────
function switchTab(tab) {
  if (tab === currentTab) return;
  currentTab = tab;
  syncTabsUI();
  scheduleSearch();   // keyword is kept; re-run in the new category range
}

/** Sync tab bar visuals + linked control states (tool select, date tab). */
function syncTabsUI() {
  for (const tab of CONTENT_TABS) {
    const el = document.getElementById(`search-tab-${tab}`);
    if (!el) continue;
    const active = tab === currentTab;
    el.classList.toggle('active', active);
    el.setAttribute('aria-selected', active ? 'true' : 'false');
  }
  // Tool select is a fine filter under All; mutually exclusive with the
  // typed categories (images/files/pop), so disable it there.
  const toolSel = selectById('search-tool');
  if (toolSel) {
    const disabled = currentTab !== 'all';
    toolSel.disabled = disabled;
    toolSel.setAttribute('aria-disabled', disabled ? 'true' : 'false');
    toolSel.title = disabled ? t('search.toolDisabledHint') : '';
  }
  // Date anchor is meaningless in the Pop tab (tool messages carry ts=0).
  const dateTab = document.getElementById('search-tab-date');
  if (dateTab) {
    const dateDisabled = currentTab === 'pop';
    dateTab.setAttribute('aria-disabled', dateDisabled ? 'true' : 'false');
    dateTab.title = dateDisabled ? t('search.dateDisabledInPop') : '';
    dateTab.classList.toggle('active', !!anchorDate);
    dateTab.setAttribute('aria-selected', anchorDate ? 'true' : 'false');
    dateTab.setAttribute('aria-expanded', calOpen ? 'true' : 'false');
  }
  syncDateTabLabel();
}

/** Date tab label: plain "Date", or the anchor's short date + × clearer. */
function syncDateTabLabel() {
  const dateTab = document.getElementById('search-tab-date');
  if (!dateTab) return;
  if (!anchorDate) {
    dateTab.textContent = t('search.tabDate');
    return;
  }
  const short = new Date(anchorDate.y, anchorDate.m0, anchorDate.d)
    .toLocaleDateString(getLocale(), { month: 'short', day: 'numeric' });
  dateTab.innerHTML =
    `<span class="search-tab-label">${escapeHtml(short)}</span>` +
    `<span class="search-date-clear" role="button" tabindex="-1" aria-label="${escapeHtml(t('search.dateClear'))}">×</span>`;
}

function clearAnchor() {
  if (!anchorDate) return;
  anchorDate = null;
  syncTabsUI();
  triggerSearch();   // back to the default browse stream (positioned at latest)
}

// ── Calendar popover (date anchor picker) ──────────────────
function toggleCalendar() {
  if (calOpen) closeCalendar(true);
  else openCalendar();
}

function openCalendar() {
  const pop = document.getElementById('search-date-popover');
  const tab = document.getElementById('search-tab-date');
  if (!pop || !tab) return;
  const today = new Date();
  const base = anchorDate || { y: today.getFullYear(), m0: today.getMonth(), d: today.getDate() };
  calView = { y: base.y, m0: base.m0 };
  calStaged = anchorDate ? { ...anchorDate } : null;
  calOpen = true;
  renderCalendar(pop);
  positionCalendar(pop, tab);
  pop.hidden = false;
  tab.setAttribute('aria-expanded', 'true');
  // Focus the staged (or today's) day cell, falling back to the OK button.
  const focusTarget = pop.querySelector('.cal-day.cal-selected') ||
    pop.querySelector('.cal-day.cal-today:not([aria-disabled="true"])') ||
    pop.querySelector('.cal-confirm');
  if (focusTarget instanceof HTMLElement) focusTarget.focus();
}

function closeCalendar(refocus) {
  calOpen = false;
  calStaged = null;
  pillListboxEl?.remove();
  pillListboxEl = null;
  const pop = document.getElementById('search-date-popover');
  if (pop) pop.hidden = true;
  const tab = document.getElementById('search-tab-date');
  tab?.setAttribute('aria-expanded', 'false');
  if (refocus) tab?.focus();
}

/** Anchor the popover under the Date tab; keep it inside the panel on
 *  narrow layouts (left edge ≥ 12px handled by CSS at ≤768px). Vertical
 *  overflow is handled by #search-modal's overflow:visible (N1: in short
 *  modal states the popover is taller than the modal interior and must
 *  escape the modal's box rather than be clipped by it). */
function positionCalendar(pop, tab) {
  const controls = pop.parentElement;
  if (!controls) return;
  const cRect = controls.getBoundingClientRect();
  const tRect = tab.getBoundingClientRect();
  const tabCenter = tRect.left + tRect.width / 2 - cRect.left;
  const popW = 264;
  let left = tabCenter - popW / 2;
  left = Math.max(12, Math.min(left, cRect.width - popW - 12));
  pop.style.left = `${left}px`;
  pop.style.setProperty('--cal-arrow-left', `${tabCenter - left}px`);
}

/** Earliest year with a loaded timestamped message (year pill lower bound). */
function earliestMessageYear() {
  let min = Infinity;
  for (const s of streamSessions) {
    for (const m of s.all) {
      if (m.ts > 0) min = Math.min(min, new Date(m.ts).getFullYear());
    }
  }
  return min === Infinity ? new Date().getFullYear() : min;
}

function renderCalendar(pop) {
  pillListboxEl = null;   // innerHTML rebuild below drops any open listbox
  const locale = getLocale();
  const weekStart = locale.startsWith('zh') ? 1 : 0;   // zh: Monday-first
  const now = new Date();
  const today = { y: now.getFullYear(), m0: now.getMonth(), d: now.getDate() };

  // Weekday header via Intl (no i18n keys per spec §5.2).
  const weekdays = Array.from({ length: 7 }, (_, i) =>
    new Date(2024, 0, 7 + weekStart + i).toLocaleDateString(locale, { weekday: 'narrow' }));

  const first = new Date(calView.y, calView.m0, 1);
  const offset = (first.getDay() - weekStart + 7) % 7;
  const dim = new Date(calView.y, calView.m0 + 1, 0).getDate();
  const isFuture = (y, m0, d) =>
    new Date(y, m0, d).getTime() > new Date(today.y, today.m0, today.d).getTime();

  let grid = '';
  for (let i = 0; i < offset; i++) grid += '<span></span>';   // no邻月 cells
  for (let d = 1; d <= dim; d++) {
    const cls = ['cal-day'];
    if (d === today.d && calView.m0 === today.m0 && calView.y === today.y) cls.push('cal-today');
    const staged = calStaged && calStaged.y === calView.y && calStaged.m0 === calView.m0 && calStaged.d === d;
    if (staged) cls.push('cal-selected');
    const fut = isFuture(calView.y, calView.m0, d);
    grid += `<button type="button" class="${cls.join(' ')}" role="gridcell" data-y="${calView.y}" data-m0="${calView.m0}" data-d="${d}"${fut ? ' aria-disabled="true"' : ''}>${d}</button>`;
  }

  const monthLabel = new Date(calView.y, calView.m0, 1).toLocaleDateString(locale, { month: 'short' });
  const yearLabel = locale.startsWith('zh') ? `${calView.y}年` : `${calView.y}`;
  const monthPill = locale.startsWith('zh') ? `${calView.m0 + 1}月` : monthLabel;

  pop.innerHTML =
    `<div class="cal-head">` +
      `<span class="cal-title">${escapeHtml(t('search.datePickTitle'))}</span>` +
      `<span class="cal-pills">` +
        `<button type="button" class="cal-pill glass-control" data-pill="year" aria-haspopup="listbox">${escapeHtml(yearLabel)} ▾</button>` +
        `<button type="button" class="cal-pill glass-control" data-pill="month" aria-haspopup="listbox">${escapeHtml(monthPill)} ▾</button>` +
      `</span>` +
    `</div>` +
    `<div class="cal-weekdays">${weekdays.map(w => `<span>${escapeHtml(w)}</span>`).join('')}</div>` +
    `<div class="cal-grid" role="grid">${grid}</div>` +
    `<div class="cal-foot">` +
      (anchorDate ? `<button type="button" class="cal-clear">${escapeHtml(t('search.dateClear'))}</button>` : '') +
      `<span class="cal-spacer"></span>` +
      `<button type="button" class="cal-cancel">${escapeHtml(t('search.dateCancel'))}</button>` +
      `<button type="button" class="cal-confirm glass-control cfg-btn cfg-btn-sm">${escapeHtml(t('search.dateConfirm'))}</button>` +
    `</div>`;
  pop.setAttribute('aria-label', t('search.datePickTitle'));

  pop.querySelectorAll('.cal-day').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.getAttribute('aria-disabled') === 'true') return;   // future days inert
      calStaged = { y: +btn.dataset.y, m0: +btn.dataset.m0, d: +btn.dataset.d };
      pop.querySelectorAll('.cal-day.cal-selected').forEach(el => el.classList.remove('cal-selected'));
      btn.classList.add('cal-selected');
    });
  });
  pop.querySelector('.cal-cancel')?.addEventListener('click', () => closeCalendar(true));
  pop.querySelector('.cal-confirm')?.addEventListener('click', () => {
    if (calStaged) {
      anchorDate = { ...calStaged };
      syncTabsUI();
      closeCalendar(true);
      triggerSearch();   // apply the anchor
    } else {
      closeCalendar(true);
    }
  });
  pop.querySelector('.cal-clear')?.addEventListener('click', () => {
    closeCalendar(false);
    clearAnchor();
  });
  pop.querySelectorAll('.cal-pill').forEach(pill => {
    pill.addEventListener('click', (e) => {
      e.stopPropagation();
      openPillListbox(/** @type {HTMLElement} */ (pill), pop);
    });
  });
}

/** Small glass listbox for the year/month pills. */
function openPillListbox(pill, pop) {
  pillListboxEl?.remove();
  pillListboxEl = null;
  const kind = pill.dataset.pill;
  const locale = getLocale();
  const now = new Date();
  const box = document.createElement('div');
  box.className = 'cal-pill-listbox';
  box.setAttribute('role', 'listbox');

  if (kind === 'year') {
    const minY = earliestMessageYear();
    for (let y = now.getFullYear(); y >= minY; y--) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cal-pill-option' + (y === calView.y ? ' active' : '');
      b.setAttribute('role', 'option');
      b.textContent = locale.startsWith('zh') ? `${y}年` : `${y}`;
      b.addEventListener('click', () => {
        calView.y = y;
        renderCalendar(pop);
      });
      box.appendChild(b);
    }
  } else {
    for (let m0 = 0; m0 < 12; m0++) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cal-pill-option' + (m0 === calView.m0 ? ' active' : '');
      b.setAttribute('role', 'option');
      b.textContent = locale.startsWith('zh')
        ? `${m0 + 1}月`
        : new Date(2000, m0, 1).toLocaleDateString(locale, { month: 'short' });
      b.addEventListener('click', () => {
        calView.m0 = m0;
        renderCalendar(pop);
      });
      box.appendChild(b);
    }
  }
  const pRect = pill.getBoundingClientRect();
  const popRect = pop.getBoundingClientRect();
  box.style.left = `${pRect.left - popRect.left}px`;
  box.style.top = `${pRect.bottom - popRect.top + 4}px`;
  pop.appendChild(box);
  pillListboxEl = box;
}

// ── Keyboard contract ──────────────────────────────────────
function onModalKeydown(e) {
  const overlay = document.getElementById('search-overlay');
  if (!overlay?.classList.contains('on')) return;
  if (e.isComposing) return;   // never intercept IME composition keys

  const modal = document.getElementById('search-modal');
  const ae = document.activeElement;
  // Native keyboard behavior wins inside selects.
  const inNativeControl = !!(ae && modal?.contains(ae) && ae.tagName === 'SELECT');
  const onTab = !!(ae instanceof Element && ae.classList?.contains('search-tab'));

  switch (e.key) {
    case 'Escape': {
      e.preventDefault();
      e.stopImmediatePropagation();
      // Three-stage Esc: ① calendar popover → close it only; ② keyword →
      // clear back to the browse state (tab and date anchor are navigation
      // state, NOT input content - both are kept); ③ otherwise close.
      if (calOpen) { closeCalendar(true); return; }
      const kw = inputById('search-keyword');
      if (kw && kw.value) {
        kw.value = '';
        triggerSearch();
      } else {
        closeSearchModal();
      }
      return;
    }
    case 'Enter': {
      // Buttons and selects keep their native Enter behavior.
      if (inNativeControl || ae?.tagName === 'BUTTON') return;
      e.preventDefault();
      e.stopImmediatePropagation();
      if (activeIndex >= 0 && lastResults[activeIndex]) activateResult(lastResults[activeIndex]);
      return;
    }
    case 'ArrowLeft':
    case 'ArrowRight': {
      // APG tabs pattern: ←/→ roves among the four content tabs (the Date
      // action button is deliberately outside the arrow sequence).
      if (!onTab) {
        e.stopImmediatePropagation();
        return;
      }
      e.preventDefault();
      e.stopImmediatePropagation();
      const idx = CONTENT_TABS.findIndex(tb => ae.id === `search-tab-${tb}`);
      if (idx === -1) return;
      const next = (idx + (e.key === 'ArrowRight' ? 1 : -1) + CONTENT_TABS.length) % CONTENT_TABS.length;
      const el = document.getElementById(`search-tab-${CONTENT_TABS[next]}`);
      el?.focus();
      switchTab(CONTENT_TABS[next]);   // automatic activation
      return;
    }
    case 'Home':
    case 'End': {
      if (!onTab) { e.stopImmediatePropagation(); return; }
      e.preventDefault();
      e.stopImmediatePropagation();
      const tab = e.key === 'Home' ? CONTENT_TABS[0] : CONTENT_TABS[CONTENT_TABS.length - 1];
      document.getElementById(`search-tab-${tab}`)?.focus();
      switchTab(tab);
      return;
    }
    case 'ArrowDown':
    case 'ArrowUp':
    case 'PageDown':
    case 'PageUp': {
      if (inNativeControl || onTab || calOpen) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const isPage = e.key === 'PageDown' || e.key === 'PageUp';
      const delta = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1
        : e.key === 'PageDown' ? PAGE_JUMP : -PAGE_JUMP;
      moveActive(delta, isPage);
      return;
    }
    case 'Tab':
      e.stopImmediatePropagation();
      trapFocus(e);
      return;
    default:
      // Silence background chat shortcuts while the modal is open.
      e.stopImmediatePropagation();
  }
}

/** Move the keyboard selection within the RENDERED results. */
function moveActive(delta, clamp) {
  const n = renderedCount;
  if (!n) return;
  let i = activeIndex;
  if (i < 0) i = delta > 0 ? 0 : n - 1;
  else if (clamp) i = Math.max(0, Math.min(n - 1, i + delta));
  else i = (i + delta + n) % n;
  setActive(i, true);
}

/** Apply (i >= 0) or clear (i < 0) the keyboard-selected result. */
function setActive(i, scroll = false) {
  const resultsEl = document.getElementById('search-results');
  if (!resultsEl) return;
  const prev = /** @type {HTMLElement|null} */ (resultsEl.querySelector('.search-result.active'));
  if (prev) {
    prev.classList.remove('active');
    prev.setAttribute('aria-selected', 'false');
    prev.tabIndex = -1;
  }
  activeIndex = i;
  const kw = inputById('search-keyword');
  if (i < 0) { kw?.removeAttribute('aria-activedescendant'); return; }
  const item = lastResults[i];
  if (!item) return;
  const el = /** @type {HTMLElement|null} */ (resultsEl.querySelector(`[data-key="${CSS.escape(item.key)}"]`));
  if (!el) return;
  el.classList.add('active');
  el.setAttribute('aria-selected', 'true');
  el.tabIndex = 0;
  kw?.setAttribute('aria-activedescendant', el.id);
  if (scroll) el.scrollIntoView({ block: 'nearest' });
}

/** Focus trap: Tab/Shift+Tab cycle inside the modal (or inside the open
 *  calendar popover), never escaping to the background chat area. */
function trapFocus(e) {
  const scope = calOpen
    ? document.getElementById('search-date-popover')
    : document.getElementById('search-modal');
  if (!scope) return;
  const focusables = /** @type {HTMLElement[]} */ ([...scope.querySelectorAll('button, input, select, [tabindex="0"]')])
    .filter(el => !(/** @type {HTMLButtonElement} */ (el).disabled) &&
      el.getAttribute('aria-disabled') !== 'true' && el.offsetParent !== null &&
      !el.classList.contains('search-date-clear'));
  if (!focusables.length) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  const ae = document.activeElement;
  if (e.shiftKey) {
    if (ae === first || !scope.contains(ae)) { e.preventDefault(); last.focus(); }
  } else if (ae === last || !scope.contains(ae)) {
    e.preventDefault();
    first.focus();
  }
}

// ── Progressive search scheduling ──────────────────────────
function scheduleSearch() {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(triggerSearch, DEBOUNCE_MS);
}

/** Mode dispatch: keyword non-empty → v2.1 one-shot search; keyword empty →
 *  browse/anchor stream. The date anchor never filters - it only positions. */
function triggerSearch() {
  const kw = (inputById('search-keyword')?.value || '').trim();
  if (kw) runSearch();
  else runStream();
}

// ── Data fetching ──────────────────────────────────────────
function authHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

async function fetchSessionList(signal) {
  // includeUnindexed=1: also returns delegate-*/subtask-*/dag-* sessions whose
  // ui.json exists on disk but which never entered the session index.
  const resp = await fetch('/api/sessions?includeUnindexed=1', { headers: authHeaders(), signal });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const data = await resp.json();
  return data.sessions || [];
}

/**
 * History fetch with the v3.1 cursor contract: before/after are epoch ms
 * ('before' inclusive), limit the page size. The backend ignores unknown
 * params today and returns the full history; pages are sliced client-side.
 * @param {string} sessionId
 * @param {AbortSignal} [signal]
 * @param {{before?:number, after?:number, limit?:number}} [cursor]
 */
async function fetchHistory(sessionId, signal, cursor = {}) {
  const params = new URLSearchParams();
  if (cursor.before != null) params.set('before', String(cursor.before));
  if (cursor.after != null) params.set('after', String(cursor.after));
  if (cursor.limit != null) params.set('limit', String(cursor.limit));
  const qs = params.toString();
  const resp = await fetch(
    `/api/sessions/${encodeURIComponent(sessionId)}/history${qs ? `?${qs}` : ''}`,
    { headers: authHeaders(), signal });
  if (!resp.ok) return [];
  const data = await resp.json();
  return data.messages || [];
}

/** Run async tasks with a concurrency limit. */
async function mapLimit(items, limit, fn, onProgress) {
  const out = [];
  let idx = 0;
  let done = 0;
  async function worker() {
    while (idx < items.length) {
      const item = items[idx++];
      out.push(await fn(item));
      done++;
      onProgress?.(done, items.length);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

// ── Message normalization ──────────────────────────────────
// Extract searchable plain text per UiMessage type (see shared/protocol.scala
// UiMessage encoder for the JSON shape). Attachment names are merged into the
// searchable text so the Images/Files categories can be keyword-hit by
// filename (spec §6.1); attachments render as text only, never inline images.
function normalizeMessage(m, ord) {
  switch (m.type) {
    case 'user': {
      const attachments = (Array.isArray(m.attachments) ? m.attachments : [])
        .map(a => ({ type: a?.type || '', name: a?.name || '' }))
        .filter(a => a.type || a.name);
      const attText = attachments.map(a => `📎 ${a.name}`).filter(s => s.trim().length > 2).join('\n');
      return {
        kind: 'user', ord, attachments,
        text: (m.text || '') + (attText ? `\n${attText}` : ''),
        ts: m.timestamp || 0,
      };
    }
    case 'ai':
      return { kind: 'ai', ord, attachments: [], text: (m.text || '') + (m.thinking ? '\n' + m.thinking : ''), ts: m.timestamp || 0 };
    case 'tool':
      return {
        kind: 'tool', ord, attachments: [],
        tool: m.label || '',
        input: m.input,   // raw tool input (JSON string) — Pop artifacts parse from it
        text: [m.summary, m.input, m.content].filter(Boolean).join('\n'),
        ts: 0,
      };
    case 'agent':
      return { kind: 'ai', ord, attachments: [], text: m.text || '', ts: 0 };
    case 'ask':
      return { kind: 'ai', ord, attachments: [], text: [m.question, m.answer].filter(Boolean).join('\n'), ts: 0 };
    default:
      return null; // system/askUser/askPermission etc. are not searchable
  }
}

// ── Filters (category tab × tool select) ───────────────────
function matchesTab(m) {
  switch (currentTab) {
    case 'images': return m.attachments.some(a => a.type === 'image');
    case 'files': return m.attachments.some(a => a.type !== 'image');
    // Pop tool messages (Canvas 展示); legacy 'Card' labels kept for history
    // recorded before the tool rename.
    case 'pop': {
      if (m.kind !== 'tool') return false;
      const n = cleanToolName(m.tool);
      return n === 'Pop' || n === 'Card';
    }
    default: return true;
  }
}

function matchesTool(m, toolSel) {
  if (toolSel === '__any__') return m.kind === 'tool';
  if (toolSel) return m.kind === 'tool' && cleanToolName(m.tool) === toolSel;
  return true;
}

/** Tool select only applies under the All tab (disabled elsewhere). */
function activeToolFilter() {
  return currentTab === 'all' ? (selectById('search-tool')?.value || '') : '';
}

/** Filtered + display-sorted view: timestamped newest-first, untimestamped
 *  (ts=0: tool/ask/agent) last by reverse document order. */
function buildView(all) {
  const toolSel = activeToolFilter();
  const arr = all.filter(m => matchesTab(m) && matchesTool(m, toolSel));
  arr.sort((a, b) => ((b.ts || 0) - (a.ts || 0)) || (b.ord - a.ord));
  return arr;
}

function normalizeAll(raw) {
  const all = [];
  raw.forEach((m, i) => {
    const n = normalizeMessage(m, i);
    if (n) all.push(n);
  });
  return all;
}

/** End-of-day (23:59:59.999 local) epoch ms for an anchor date. */
function anchorEndTs(a) {
  return new Date(a.y, a.m0, a.d, 23, 59, 59, 999).getTime();
}

// ── Stream mode (browse / anchor) ──────────────────────────
async function runStream() {
  const mySeq = ++searchSeq;
  searchAbort?.abort();
  const ac = new AbortController();
  searchAbort = ac;

  const scope = selectById('search-scope')?.value || 'current';
  const agentSel = selectById('search-agent')?.value || '';
  const statusEl = document.getElementById('search-status');
  const resultsEl = document.getElementById('search-results');
  if (!statusEl || !resultsEl) return;

  streamMode = anchorDate ? 'anchor' : 'browse';
  resultsEl.dataset.loading = 'true';
  statusEl.textContent = t('search.searching');

  try {
    let sessions;
    if (scope === 'current') {
      const sid = state.activeSessionId;
      if (!sid) {
        if (mySeq !== searchSeq) return;
        resultsEl.removeAttribute('data-loading');
        resultsEl.innerHTML = `<div class="search-hint">${escapeHtml(t('search.emptyCategory'))}</div>`;
        statusEl.textContent = t('search.noSession');
        streamSessions = [];
        lastResults = [];
        renderedCount = 0;
        lastTotal = 0;
        return;
      }
      const local = (state.sessions || []).find(s => s.id === sid);
      sessions = [{ id: sid, name: local?.name || sid }];
    } else {
      sessions = await fetchSessionList(ac.signal);
      if (mySeq === searchSeq) populateAgentFilter(sessions, agentSel);
      if (agentSel) sessions = sessions.filter(s => agentNameOf(s) === agentSel);
    }

    const anchorEnd = anchorDate ? anchorEndTs(anchorDate) : null;
    const showProgress = sessions.length > 1;
    const perSession = await mapLimit(sessions, FETCH_CONCURRENCY, async (s) => {
      // Anchor page: newest page at or below the anchor day's end. Browse
      // page: newest page from the latest. Params are the cursor contract;
      // today's backend ignores them and returns everything.
      const raw = await fetchHistory(s.id, ac.signal,
        anchorEnd ? { before: anchorEnd, limit: FETCH_PAGE } : { limit: FETCH_PAGE });
      const all = normalizeAll(raw);
      const view = buildView(all);
      let start = 0;
      if (anchorEnd) {
        const ai = view.findIndex(m => m.ts > 0 && m.ts <= anchorEnd);
        // All entries newer than the anchor → position at the stream tail
        // (the earliest end) per spec §3 ruling 3.
        start = ai === -1 ? Math.max(0, view.length - FETCH_PAGE) : ai;
      }
      return {
        id: s.id, name: s.name || s.id, all, view,
        start, end: Math.min(view.length, start + FETCH_PAGE),
        el: null, headerEl: null,
      };
    }, showProgress ? (done, total) => {
      if (mySeq === searchSeq) statusEl.textContent = t('search.progress', { done, total });
    } : null);

    if (mySeq !== searchSeq) return;   // superseded by a newer run

    populateToolFilter(perSession, selectById('search-tool')?.value || '');
    streamSessions = perSession;
    buildModel();
    renderStreamFresh(resultsEl, statusEl);
  } catch (e) {
    if (e.name === 'AbortError' || mySeq !== searchSeq) return;
    resultsEl.removeAttribute('data-loading');
    showError(statusEl, e);
  }
}

/** Sentinel entry: render the next batch (no request) or, when the rendered
 *  batches are exhausted, fire the before-cursor page (exactly one per
 *  session with a live cursor). */
function onSentinel() {
  if (pageInFlight) return;
  if (renderedCount < lastResults.length) {
    renderNextBatch();
    updateStatus();
    return;
  }
  if (streamMode === 'search') return;   // one-shot mode never paginates
  loadBeforePages();
}

/** Older page(s): extend each session window downward (before cursor). */
async function loadBeforePages() {
  const targets = streamSessions.filter(s => s.end < s.view.length);
  if (!targets.length) return;
  pageInFlight = true;
  const mySeq = searchSeq;
  try {
    await mapLimit(targets, FETCH_CONCURRENCY, async (s) => {
      const lastLoaded = s.view[s.end - 1];
      const raw = await fetchHistory(s.id, searchAbort?.signal,
        { before: lastLoaded?.ts ?? 0, limit: FETCH_PAGE });
      s.all = normalizeAll(raw);
      s.view = buildView(s.all);
      s.end = Math.min(s.view.length, s.end + FETCH_PAGE);
      s.start = Math.min(s.start, s.end);
    });
    if (mySeq !== searchSeq) return;
    buildModel();
    renderNextBatch();
    updateStatus();
  } catch (e) {
    if (e.name !== 'AbortError') { /* pagination failure is non-fatal */ }
  } finally {
    if (mySeq === searchSeq) pageInFlight = false;
  }
}

/** Newer page(s) in anchor mode: extend each session window upward (after
 *  cursor) and PREPEND, preserving the scroll position. Existing entries
 *  stay put (zero removal, zero reorder - spec B13). */
async function loadAfterPages() {
  const targets = streamSessions.filter(s => s.start > 0);
  if (!targets.length) return;
  pageInFlight = true;
  const mySeq = searchSeq;
  const resultsEl = document.getElementById('search-results');
  const prevHeight = resultsEl?.scrollHeight || 0;
  const prevTop = resultsEl?.scrollTop || 0;
  try {
    const added = new Map();   // session → items prepended (display order)
    await mapLimit(targets, FETCH_CONCURRENCY, async (s) => {
      const oldStart = s.start;
      const firstLoaded = s.view[s.start];
      const raw = await fetchHistory(s.id, searchAbort?.signal,
        { after: firstLoaded?.ts ?? 0, limit: FETCH_PAGE });
      s.all = normalizeAll(raw);
      s.view = buildView(s.all);
      s.start = Math.max(0, s.start - FETCH_PAGE);
      const items = [];
      for (let i = s.start; i < Math.min(oldStart, s.view.length); i++) items.push(wrapItem(s, i));
      added.set(s.id, items);
    });
    if (mySeq !== searchSeq) return;
    buildModel();
    // Prepend into each group container, above its first existing item.
    let addedTotal = 0;
    for (const s of streamSessions) {
      const items = added.get(s.id);
      if (!items?.length) continue;
      addedTotal += items.length;
      const frag = document.createDocumentFragment();
      items.forEach((item) => frag.appendChild(resultElement(item)));
      const container = s.el || resultsEl;
      if (!container) continue;
      const anchor = s.headerEl ? s.headerEl.nextSibling : container.firstChild;
      container.insertBefore(frag, anchor);
    }
    renderedCount += addedTotal;
    if (activeIndex >= 0) activeIndex += addedTotal;   // selection follows its item
    if (resultsEl) resultsEl.scrollTop = prevTop + (resultsEl.scrollHeight - prevHeight);
    updateStatus();
  } catch (e) {
    if (e.name !== 'AbortError') { /* non-fatal */ }
  } finally {
    if (mySeq === searchSeq) pageInFlight = false;
  }
}

// ── Search mode (keyword) — v2.1 one-shot query ────────────
async function runSearch() {
  const mySeq = ++searchSeq;
  searchAbort?.abort();
  const ac = new AbortController();
  searchAbort = ac;

  const kw = (inputById('search-keyword')?.value || '').trim();
  const scope = selectById('search-scope')?.value || 'current';
  const agentSel = selectById('search-agent')?.value || '';
  const statusEl = document.getElementById('search-status');
  const resultsEl = document.getElementById('search-results');
  if (!statusEl || !resultsEl) return;

  streamMode = 'search';
  resultsEl.dataset.loading = 'true';
  statusEl.textContent = t('search.searching');

  try {
    let sessions;
    if (scope === 'current') {
      const sid = state.activeSessionId;
      if (!sid) {
        if (mySeq !== searchSeq) return;
        resultsEl.removeAttribute('data-loading');
        resultsEl.innerHTML = `<div class="search-hint">${escapeHtml(t('search.noResults'))}<br>${escapeHtml(t('search.noResultsSuggestion'))}</div>`;
        statusEl.textContent = t('search.noSession');
        streamSessions = [];
        lastResults = [];
        renderedCount = 0;
        lastTotal = 0;
        return;
      }
      const local = (state.sessions || []).find(s => s.id === sid);
      sessions = [{ id: sid, name: local?.name || sid }];
    } else {
      sessions = await fetchSessionList(ac.signal);
      if (mySeq === searchSeq) populateAgentFilter(sessions, agentSel);
      if (agentSel) sessions = sessions.filter(s => agentNameOf(s) === agentSel);
    }

    const showProgress = sessions.length > 1;
    const perSession = await mapLimit(sessions, FETCH_CONCURRENCY, async (s) => {
      const raw = await fetchHistory(s.id, ac.signal);
      return { session: s, all: normalizeAll(raw) };
    }, showProgress ? (done, total) => {
      if (mySeq === searchSeq) statusEl.textContent = t('search.progress', { done, total });
    } : null);

    if (mySeq !== searchSeq) return;

    populateToolFilter(perSession.map(p => ({ all: p.all })), selectById('search-tool')?.value || '');

    // Match (v2.1 semantics, plus the category tab filter)
    const kwLower = kw.toLowerCase();
    const toolSel = activeToolFilter();
    const results = [];
    for (const { session, all } of perSession) {
      for (const m of all) {
        if (!matchesTab(m)) continue;
        if (!matchesTool(m, toolSel)) continue;
        if (!m.text.toLowerCase().includes(kwLower)) continue;
        results.push({
          sessionId: session.id,
          sessionName: session.name || session.id,
          kind: m.kind,
          tool: m.tool || '',
          input: m.input,
          text: m.text,
          ts: m.ts,
          ord: m.ord,
          attachments: m.attachments,
        });
      }
    }

    // Newest first (timestamped), untimestamped last
    results.sort((a, b) => ((b.ts || 0) - (a.ts || 0)) || (b.ord - a.ord));
    const capped = results.slice(0, MAX_RESULTS);
    lastTotal = results.length;

    // Reuse the stream render pipeline: one pseudo window per session.
    streamSessions = sessions.map(s => {
      const view = capped.filter(r => r.sessionId === s.id);
      return {
        id: s.id, name: s.name || s.id, all: [], view,
        start: 0, end: view.length, el: null, headerEl: null,
      };
    });
    buildModel();
    renderStreamFresh(resultsEl, statusEl);
    if (anchorDate) scrollToAnchorInResults();
  } catch (e) {
    if (e.name === 'AbortError' || mySeq !== searchSeq) return;
    resultsEl.removeAttribute('data-loading');
    showError(statusEl, e);
  }
}

/** Search-mode anchor positioning: client-side scroll to the first result at
 *  or below the anchor day's end (no extra requests - spec §6.3). */
function scrollToAnchorInResults() {
  if (!anchorDate) return;
  const end = anchorEndTs(anchorDate);
  let idx = lastResults.findIndex(r => r.ts > 0 && r.ts <= end);
  const resultsEl = document.getElementById('search-results');
  if (!resultsEl) return;
  if (idx === -1) {
    // Everything is newer than the anchor → position at the stream tail.
    while (renderedCount < lastResults.length) renderNextBatch();
    resultsEl.scrollTop = resultsEl.scrollHeight;
    return;
  }
  while (renderedCount <= idx && renderedCount < lastResults.length) renderNextBatch();
  const item = lastResults[idx];
  const el = resultsEl.querySelector(`[data-key="${CSS.escape(item.key)}"]`);
  if (el instanceof HTMLElement) el.scrollIntoView({ block: 'center' });
}

// ── Model + incremental rendering ──────────────────────────
function wrapItem(s, viewIdx) {
  const m = s.view[viewIdx];
  return {
    sessionId: s.id,
    sessionName: s.name,
    kind: m.kind,
    tool: m.tool || '',
    input: m.input,
    text: m.text,
    ts: m.ts,
    ord: m.ord,
    attachments: m.attachments || [],
    key: `${s.id}:${m.ord}`,
  };
}

/** Flatten session windows into display order (scope=all groups per session,
 *  groups ordered by their newest loaded entry, newest first). */
function buildModel() {
  lastResults = [];
  modelKeys = new Map();
  if (!isGrouped()) {
    const s = streamSessions[0];
    streamGroups = s && s.end > s.start ? [s] : [];
    if (s) for (let i = s.start; i < s.end; i++) lastResults.push(wrapItem(s, i));
  } else {
    streamGroups = streamSessions
      .filter(s => s.end > s.start)
      .sort((a, b) => ((b.view[b.start]?.ts || 0) - (a.view[a.start]?.ts || 0)) ||
                      ((b.view[b.start]?.ord || 0) - (a.view[a.start]?.ord || 0)));
    for (const s of streamGroups) {
      for (let i = s.start; i < s.end; i++) lastResults.push(wrapItem(s, i));
    }
  }
  lastResults.forEach((r, i) => modelKeys.set(r.key, i));
  lastTotal = streamMode === 'search' ? lastTotal : lastResults.length;
}

/** Fresh full render of the current window (initial load / mode switch). */
function renderStreamFresh(resultsEl, statusEl) {
  resultsEl.removeAttribute('data-loading');
  sentinelObserver?.disconnect();
  sentinelEl = null;
  resultsEl.innerHTML = '';
  renderedCount = 0;
  activeIndex = -1;

  if (!lastResults.length) {
    // Empty state: browse/category empties use the single-line category key;
    // keyword search keeps the v2.1 two-line no-results state.
    const kw = (inputById('search-keyword')?.value || '').trim();
    resultsEl.innerHTML = kw
      ? `<div class="search-hint">${escapeHtml(t('search.noResults'))}<br>${escapeHtml(t('search.noResultsSuggestion'))}</div>`
      : `<div class="search-hint">${escapeHtml(t('search.emptyCategory'))}</div>`;
    statusEl.textContent = statusSummary();
    return;
  }

  if (isGrouped()) {
    for (const s of streamGroups) {
      const g = document.createElement('div');
      g.className = 'search-group';
      const header = document.createElement('div');
      header.className = 'search-group-header';
      g.appendChild(header);
      resultsEl.appendChild(g);
      s.el = g;
      s.headerEl = header;
      updateGroupHeader(s);
    }
  } else if (streamSessions[0]) {
    streamSessions[0].el = null;
    streamSessions[0].headerEl = null;
  }

  sentinelEl = document.createElement('div');
  sentinelEl.className = 'search-sentinel';
  resultsEl.appendChild(sentinelEl);
  sentinelObserver?.observe(sentinelEl);

  renderNextBatch();
  statusEl.textContent = statusSummary();
  if (lastResults.length) setActive(0);   // first entry pre-selected (Top Hit)
}

function updateGroupHeader(s) {
  if (!s.headerEl) return;
  s.headerEl.innerHTML =
    `<span class="search-result-session">${escapeHtml(s.name)}</span> (${s.end - s.start})`;
}

/** Render the next lazy batch (≤ RENDER_BATCH rows). No network. */
function renderNextBatch() {
  const resultsEl = document.getElementById('search-results');
  if (!resultsEl || renderedCount >= lastResults.length) return false;
  const batch = lastResults.slice(renderedCount, renderedCount + RENDER_BATCH);
  const grouped = isGrouped();
  const bySid = new Map();
  if (grouped) for (const s of streamGroups) bySid.set(s.id, s);
  let appended = 0;
  for (const item of batch) {
    const el = resultElement(item);
    if (grouped) {
      const s = bySid.get(item.sessionId);
      s?.el?.appendChild(el);
    } else {
      resultsEl.insertBefore(el, sentinelEl);
    }
    appended++;
  }
  renderedCount += appended;
  return appended > 0;
}

function updateStatus() {
  const statusEl = document.getElementById('search-status');
  if (statusEl) statusEl.textContent = statusSummary();
  if (isGrouped()) for (const s of streamGroups) updateGroupHeader(s);
}

/** Status line: result count (+ truncation notice in search mode only). */
function statusSummary() {
  const base = t('search.results', { n: lastTotal });
  return streamMode === 'search' && lastTotal > MAX_RESULTS
    ? `${base} (${t('search.truncated', { n: MAX_RESULTS })})`
    : base;
}

/** Error state: message + an inline retry button (glass control). */
function showError(statusEl, e) {
  statusEl.textContent = `${t('search.failed')}: ${e.message}`;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'glass-control cfg-btn cfg-btn-sm';
  btn.textContent = t('search.retry');
  btn.addEventListener('click', triggerSearch);
  statusEl.appendChild(btn);
}

// ── Tool name normalization ────────────────────────────────
// ui.json tool labels are RICH display strings, not tool names:
//   "Edit(jarvis.html)\n  (\"/path\")", "Bash\n  (cd \"...\")",
//   "Mail(→Manager) [RESULT]", "TaskUpdate(#1, completed)"
// Extract the clean tool name — the leading identifier before
// '(' / whitespace / newline.
function cleanToolName(label) {
  const m = /^([A-Za-z_][A-Za-z0-9_-]*)/.exec(label || '');
  return m ? m[1] : (label || '');
}

/** Display name of the agent that owns a session. */
function agentNameOf(s) {
  return s.agentName || '';
}

/** Rebuild the agent filter options from the fetched session list. */
function populateAgentFilter(sessions, keepValue) {
  const sel = selectById('search-agent');
  if (!sel) return;
  const counts = new Map();
  for (const s of sessions) {
    const name = agentNameOf(s);
    if (name) counts.set(name, (counts.get(name) || 0) + 1);
  }
  const sorted = [...counts.keys()].sort((a, b) => a.localeCompare(b));
  sel.innerHTML =
    `<option value="">${escapeHtml(t('search.allAgents'))}</option>` +
    sorted.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)} (${counts.get(n)})</option>`).join('');
  if ([...sel.options].some(o => o.value === keepValue)) sel.value = keepValue;
}

/** Rebuild the tool filter options from the tool labels in the fetched set.
 *  Options are CLEAN tool names (with call counts), not raw labels. */
function populateToolFilter(perSession, keepValue) {
  const sel = selectById('search-tool');
  if (!sel) return;
  const counts = new Map();
  for (const { all } of perSession) {
    for (const m of all) {
      if (m.kind === 'tool' && m.tool) {
        const name = cleanToolName(m.tool);
        if (name) counts.set(name, (counts.get(name) || 0) + 1);
      }
    }
  }
  const sorted = [...counts.keys()].sort();
  sel.innerHTML =
    `<option value="">${escapeHtml(t('search.allTypes'))}</option>` +
    `<option value="__any__">${escapeHtml(t('search.anyTool'))}</option>` +
    sorted.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)} (${counts.get(n)})</option>`).join('');
  // Restore previous selection if still available
  if ([...sel.options].some(o => o.value === keepValue)) sel.value = keepValue;
}

// ── Result rendering ───────────────────────────────────────
/** One result row element. data-key is the stable identity (sessionId:ord);
 *  data-ts / data-kind / data-attachments are the QA assertion surface. */
function resultElement(r) {
  const kw = (inputById('search-keyword')?.value || '').trim();
  // Queue #2: Pop/Card artifact rows (Canvas-openable) get data-artifact="1" —
  // the QA surface for the click-routing split + the CSS ↗ affordance.
  const artifact = popArtifactFromInput(r.tool, r.input) ? '1' : '';
  const typeBadge = r.kind === 'tool'
    ? `${escapeHtml(t('search.typeTool'))} · ${escapeHtml(cleanToolName(r.tool))}`
    : r.kind === 'user'
      ? escapeHtml(t('search.typeUser'))
      : escapeHtml(t('search.typeAi'));
  const time = r.ts ? formatTime(r.ts) : '';
  const attTypes = (r.attachments || []).map(a => a.type).filter(Boolean).join(' ');
  const tpl = document.createElement('template');
  tpl.innerHTML = `<div class="search-result" id="sr-${escapeHtml(r.key)}" data-key="${escapeHtml(r.key)}" data-ts="${r.ts}" data-kind="${escapeHtml(r.kind)}" data-attachments="${escapeHtml(attTypes)}" data-artifact="${artifact}" role="option" aria-selected="false" tabindex="-1">
    <div class="search-result-head">
      <span class="search-result-session">${escapeHtml(r.sessionName)}</span>
      <span class="search-result-type type-${escapeHtml(r.kind)}">${typeBadge}</span>
      <span class="search-result-time">${escapeHtml(time)}</span>
    </div>
    <div class="search-result-preview">${highlightPreview(r.text, kw)}</div>
  </div>`;
  return /** @type {HTMLElement} */ (tpl.content.firstElementChild);
}

/** Preview snippet centered on the first keyword match, with <mark> highlights. */
function highlightPreview(text, kw) {
  const MAX = 180;
  const flat = (text || '').replace(/\s+/g, ' ').trim();
  let start = 0;
  if (kw) {
    const idx = flat.toLowerCase().indexOf(kw.toLowerCase());
    if (idx > 60) start = idx - 60;
  }
  const slice = flat.slice(start, start + MAX);
  const prefix = start > 0 ? '…' : '';
  const suffix = start + MAX < flat.length ? '…' : '';
  if (!kw) return prefix + escapeHtml(slice) + suffix;

  // Index-based marking on the raw slice (escape each segment separately)
  const kwLower = kw.toLowerCase();
  let html = '';
  let pos = 0;
  const lower = slice.toLowerCase();
  while (pos < slice.length) {
    const idx = lower.indexOf(kwLower, pos);
    if (idx === -1) { html += escapeHtml(slice.slice(pos)); break; }
    html += escapeHtml(slice.slice(pos, idx));
    html += '<mark>' + escapeHtml(slice.slice(idx, idx + kw.length)) + '</mark>';
    pos = idx + kw.length;
  }
  return prefix + html + suffix;
}

function formatTime(ts) {
  const d = new Date(ts);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (sameDay) return hh + ':' + mm;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`;
}

// ── Jump to message ────────────────────────────────────────
// DOM matching normalizes away whitespace and non-alphanumeric characters so
// markdown formatting (**bold**, `code`, list bullets) doesn't break the match.
const stripForMatch = (s) => (s || '').replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();

// ── Jump-to-message ────────────────────────────────────────
// The chat view initially renders only the newest 50 messages and pages older
// history on scroll-to-top, so a search hit can sit far outside the loaded
// window — a pure DOM scan fails "most of the time" (2026-08-18 user report).
// scrollToMessage therefore drives the same WS pagination the scroll listener
// uses: while the target is unmatched and the view has older pages, request
// them (50 at a time) and retry, up to JUMP_MAX_PAGES / JUMP_DEADLINE_MS.
const JUMP_TICK_MS = 250;
const JUMP_MAX_PAGES = 40;        // 40 × 50 = 2000 messages of lookback
const JUMP_DEADLINE_MS = 25000;   // overall budget

// ── Result activation ───────────────────────────────────────
/**
 * Activate a result row (click or Enter). Pop/Card artifact messages (with a
 * Canvas-openable filePath — same detection as the message bubble's Pop card)
 * open the content DIRECTLY in Canvas, no message jump; everything else keeps
 * the locate-and-jump behavior. The modal closes in both cases (act-and-exit
 * convention — jumpToResult already closes; a Canvas tab opened under an open
 * modal would be hidden behind it).
 */
function activateResult(res) {
  const art = popArtifactFromInput(res.tool, res.input);
  if (art) {
    closeSearchModal();
    openPopArtifact(art.filePath, art.title);
    return;
  }
  jumpToResult(res);
}

function jumpToResult(res) {
  closeSearchModal();
  switchToSession(res.sessionId);
  scrollToMessage(res, { pages: 0, start: Date.now(), settleTicks: 0 });
}

/** @param {HTMLElement} el */
function flashRow(el) {
  expandGroupContaining(el); // #346 E10: reveal collapsed turn before scrolling
  el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  el.classList.add('search-hit-flash');
  setTimeout(() => el.classList.remove('search-hit-flash'), 1800);
}

/**
 * @param {any} res search result ({sessionId, text, ts})
 * @param {{pages:number, start:number, settleTicks:number}} st jump state
 */
function scrollToMessage(res, st) {
  const pv = chatViews.primary;
  const chat = pv?.dom?.chat;
  if (!chat) return;
  // User navigated away mid-jump — abort silently.
  if (state.activeSessionId !== res.sessionId) return;

  // 1. Text-snippet match (primary, human-meaningful).
  const snippet = stripForMatch(res.text).slice(0, 40);
  if (snippet.length >= 4) {
    const rows = chat.querySelectorAll('.row, .tool-card');
    for (const el of rows) {
      if (stripForMatch(el.textContent).includes(snippet)) { flashRow(el); return; }
    }
  }
  // 2. Exact-timestamp fallback — covers image-only / very short messages
  //    whose stripped snippet is under 4 chars (the duration badge carries
  //    data-ts epoch ms). Tool/agent/ask rows normalize to ts=0 and skip.
  if (res.ts) {
    const badge = chat.querySelector(`[data-ts="${res.ts}"]`);
    const row = badge?.closest('.row, .tool-card');
    if (row) { flashRow(row); return; }
  }

  // 3. Not in the loaded window — page older history in and retry.
  const p = pv.pagination;
  const expired = Date.now() - st.start > JUMP_DEADLINE_MS;
  if (!expired && p && !p.pendingInitialLoad && !p.loading && p.hasMore && p.offset > 0 && st.pages < JUMP_MAX_PAGES) {
    p.loading = true;
    st.pages++;
    sendWs({ type: 'getHistory', sessionId: res.sessionId, limit: 50, beforeIndex: p.offset });
  } else if (!expired && p && !p.pendingInitialLoad && !p.loading && !p.hasMore) {
    // History fully loaded and still no match — allow a few settle ticks for
    // deferred markdown rendering, then give up.
    if (++st.settleTicks > 4) {
      (/** @type {any} */ (window)).__showToast?.(t('search.jumpFailed'), 'error');
      return;
    }
  } else if (expired) {
    (/** @type {any} */ (window)).__showToast?.(t('search.jumpFailed'), 'error');
    return;
  }
  setTimeout(() => scrollToMessage(res, st), JUMP_TICK_MS);
}

// Re-apply labels when the locale changes while the modal is open.
// Rendered results are re-drawn too so badges/status stay localized; the
// calendar grid (weekday header / month names) re-renders as well.
window.addEventListener('locale-changed', () => {
  if (!document.getElementById('search-overlay')?.classList.contains('on')) return;
  renderStaticLabels();
  syncTabsUI();
  if (calOpen) {
    const pop = document.getElementById('search-date-popover');
    if (pop) renderCalendar(pop);
  }
  // Redraw the rendered window in the new locale (badges/times are localized).
  const resultsEl = document.getElementById('search-results');
  const statusEl = document.getElementById('search-status');
  if (resultsEl && lastResults.length) renderStreamFresh(resultsEl, /** @type {HTMLElement} */ (statusEl));
});
