// chatSearch.js — Chat history search modal (v3.1: WeChat retrieval-window paradigm).
//
// v3.2 (#491 主题二, author 2026-10-05 「把Pop的内容按图片/视频/链接/文件等，
// 微信的这样的设计来进行分类和检索。用户输入的文件和LLM Pop的文件，按这个方式
// 一起管理」): the standalone Pop tab is retired. Categories read the UNIFIED
// media index (popMediaIndex.js — user attachments + LLM Pop payloads in one
// model): tabs are 全部 / 图片与视频 / 文件 / 链接 / 日期; the typed tabs
// render a thumbnail grid grouped 本周/按月 with source chips (全部来源/用户/
// 助手) and in-category keyword search over entry fields. The standalone Pop
// tab and its search.tabPop key are gone; tool-name category matching retired.
// v3.1 information architecture on top of the v2.1 contract:
//   - Opens straight into a browse stream of recent messages (no idle hint).
//   - Category tabs filter orthogonally with scope; switching tabs keeps the
//     keyword and re-runs.
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
//
// v2 (2026-09-11 author ruling) — two changes on top of v3.1, nothing else:
//   1) A hit whose tool message produced a Card renders that card body inline
//      under the preview (same renderer as the chat: renderWithRegistry). Every
//      other row keeps its exact DOM/styling; the list layout is untouched.
//   2) Clicking a non-Pop hit opens an independent floating window that starts
//      at the hit (chatSearchFloat.js) instead of scrolling the MAIN session.
//      Pop/Card artifacts with a Canvas-openable filePath keep the existing
//      Canvas-direct routing. The in-session jump machinery (scrollToMessage /
//      flashRow / jumpToResult + its WS paging) is gone with it.

import state from './state.js';
import { key } from './branding.js';
import { t, getLocale } from './i18n.js';
import { escapeHtml } from './utils.js';
import { popArtifactFromInput, openPopArtifact } from './chat.js';
import { indexMedia, entryMatchesTab, entryMatchesKeyword, groupEntriesByTime } from './popMediaIndex.js';
import { openMediaLightbox } from './popArtifacts.js';
import { ticketUrl } from './nfTicket.js';
import { attachmentImageUrl } from './persistence.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
import { renderWithRegistry, cleanupCardIframes } from './cardRegistry.js';
import { openSearchMessageFloat } from './chatSearchFloat.js';

const MAX_RESULTS = 200;      // search-mode cap (v2.1 one-shot query)
const FETCH_PAGE = 100;       // stream page size (spec §6.3 caps a page at ≤200;
                              // 100 keeps cursor pagination reachable for B9's ≥120-seed)
const RENDER_BATCH = 50;      // lazy-render batch (spec §6.1)
const FETCH_CONCURRENCY = 4;
const DEBOUNCE_MS = 300;
const PAGE_JUMP = 5;

// v3.2: the date tab JOINS the roving enumeration (keyboard arrows now cross
// it naturally); it keeps its anchor semantics — clicking it opens the calendar
// popover, it never switches the category.
const CONTENT_TABS = ['all', 'media', 'files', 'links', 'date'];
const TAB_I18N = { all: 'search.tabAll', media: 'search.tabMedia', files: 'search.tabFiles', links: 'search.tabLinks', date: 'search.tabDate' };
/** Tabs rendered as the unified media-entry grid (popMediaIndex entries). */
const MEDIA_TABS = ['media', 'files', 'links'];

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
let currentTab = 'all';      // 'all' | 'media' | 'files' | 'links' (+ 'date' anchor tab)
let currentSource = '';      // media-grid source chip: '' | 'user' | 'agent'
/** @type {{y:number, m0:number, d:number}|null} applied date anchor (local) */
let anchorDate = null;
let calOpen = false;
let calView = { y: 0, m0: 0 };       // calendar grid month being displayed
/** @type {{y:number, m0:number, d:number}|null} staged (unconfirmed) pick */
let calStaged = null;
let pillListboxEl = null;    // open year/month listbox element

// ── Stream state (browse/anchor modes) ─────────────────────
/** @type {Array<{id:string, name:string, all:any[], view:any[], entries:any[], start:number, end:number, el:HTMLElement|null, headerEl:HTMLElement|null}>} */
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

  // v3.2: the tab strip is REBUILT from CONTENT_TABS — the static HTML carries
  // the retired images/pop scaffold, and the strip must stay the single
  // normative tab list (index.html stays untouched; the category set is code-
  // owned). Runs before every binding below reads the nodes.
  buildTabBar();
  buildSourceChips();

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
  // ⑤ 元素级组字登记：模态键盘契约（capture 阶段）读同一个谓词。
  bindImeGuard(document.getElementById('search-keyword'));
  for (const id of ['search-scope', 'search-agent', 'search-tool']) {
    document.getElementById(id)?.addEventListener('change', scheduleSearch);
  }

  // Category tabs (click). The date tab (now IN the roving enumeration but
  // never a category) toggles the calendar popover instead of switching; its
  // inline × clears the anchor.
  for (const tab of CONTENT_TABS) {
    if (tab === 'date') continue;
    document.getElementById(`search-tab-${tab}`)?.addEventListener('click', () => switchTab(tab));
  }
  document.getElementById('search-tab-date')?.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    if (e.target.closest('.search-date-clear')) { e.stopPropagation(); clearAnchor(); return; }
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
  // Card bodies in the result list own sandboxed iframes — release their
  // browsing contexts/observers while the modal is closed (the next open
  // re-renders the list from scratch).
  const resultsEl = document.getElementById('search-results');
  if (resultsEl) cleanupCardIframes(resultsEl);
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
  currentSource = '';
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
  syncSourceChips();
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
  // v3.2: no tab disables the date anchor any more — the index derives entry
  // times (nearest earlier row with a ts), so anchoring stays meaningful in
  // every category. (search.dateDisabledInPop's only consumer is gone; the
  // locale key itself stays untouched — it is outside the §6 candidate list.)
  const dateTab = document.getElementById('search-tab-date');
  if (dateTab) {
    dateTab.classList.toggle('active', !!anchorDate);
    dateTab.setAttribute('aria-selected', anchorDate ? 'true' : 'false');
    dateTab.setAttribute('aria-expanded', calOpen ? 'true' : 'false');
  }
  syncDateTabLabel();
  syncSourceChips();
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
/** True when the event originated inside a detached message window (its own
 *  Esc / Tab handling must win over the modal's capture-phase handler). */
function inMessageFloat(target) {
  return target instanceof Element && !!target.closest('[data-nf-float-layer]');
}

function onModalKeydown(e) {
  const overlay = document.getElementById('search-overlay');
  if (!overlay?.classList.contains('on')) return;
  // ⑤ 收归：单一谓词（元素级 dataset 兜底 + 事件级 isComposing/keyCode 229）。
  if (isImeComposing(e, e.target instanceof HTMLElement ? e.target : null)) return;
  if (inMessageFloat(e.target)) return;   // keys belong to the message window

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
  // v2: a detached message window (chatSearchFloat) is its own focus scope —
  // keys raised while focus sits inside one belong to that window.
  if (inMessageFloat(e.target)) return;
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
/**
 * Message-source classification — THE single point (author ruling 2026-09-19,
 * "检索分类粒度": 「用户消息」= the author's own input ONLY; the agent-produced
 * blue bubble is its own class). Every consumer — the filters/badges/counts
 * below AND chatSearchFloat.js's raw-message lookup — derives from this one
 * function; never re-map UiMessage.type to a kind anywhere else.
 *
 *   - 'user'  ⇔ type === 'user' ∧ injected !== true
 *               Real input by the author (also: the recorded answer on an
 *               askUser card). UiMessage.User's encoder writes `injected` ONLY
 *               when true (shared/protocol.scala) ⇒ a MISSING key means a real
 *               user row, which is why pre-field legacy history rows stay
 *               'user' as well.
 *   - 'agent' ⇔ type === 'user' ∧ injected === true
 *               Agent-produced injected row (node result / mail / deviceMail /
 *               task / dispatch / background …) rendered as a blue bubble.
 *               Same divider the history readers already use: persistence.js
 *               skips `injected` rows when it scans for the author's real
 *               answer, and turnGroup.js renders them as `.bubble.injected` —
 *               an injected row is NOT a user message.
 *   - 'ai'    ⇔ type ∈ {ai, agent, ask} — the raw `agent` TYPE is an assistant
 *               message; it is NOT the `agent` KIND above.
 *   - 'tool'  ⇔ type === 'tool'. Everything else (system / askUser /
 *               askPermission …) is not searchable → null.
 * @param {any} m raw UiMessage (see shared/protocol.scala UiMessage encoder)
 * @returns {'user'|'agent'|'ai'|'tool'|null}
 */
export function messageKind(m) {
  switch (m?.type) {
    case 'user':
      return m.injected === true ? 'agent' : 'user';
    case 'ai':
    case 'agent':
    case 'ask':
      return 'ai';
    case 'tool':
      return 'tool';
    default:
      return null;
  }
}

// Extract searchable plain text per UiMessage type (see shared/protocol.scala
// UiMessage encoder for the JSON shape). Attachment names are merged into the
// searchable text so the Images/Files categories can be keyword-hit by
// filename (spec §6.1); attachments render as text only, never inline images.
// The row's `kind` always comes from messageKind() — the switch below only
// picks the text/attachment shape per raw type.
function normalizeMessage(m, ord) {
  const kind = messageKind(m);
  if (kind === null) return null; // system/askUser/askPermission etc. are not searchable
  switch (m.type) {
    case 'user': {
      const attachments = (Array.isArray(m.attachments) ? m.attachments : [])
        .map(a => ({ type: a?.type || '', name: a?.name || '' }))
        .filter(a => a.type || a.name);
      const attText = attachments.map(a => `📎 ${a.name}`).filter(s => s.trim().length > 2).join('\n');
      return {
        kind, ord, attachments,
        text: (m.text || '') + (attText ? `\n${attText}` : ''),
        ts: m.timestamp || 0,
      };
    }
    case 'ai':
      return { kind, ord, attachments: [], text: (m.text || '') + (m.thinking ? '\n' + m.thinking : ''), ts: m.timestamp || 0 };
    case 'tool':
      return {
        kind, ord, attachments: [],
        tool: m.label || '',
        input: m.input,   // raw tool input (JSON string) — Pop artifacts parse from it
        text: [m.summary, m.input, m.content].filter(Boolean).join('\n'),
        // Raw segments kept separately (#41): the chat DOM localizes tool
        // labels/summaries, so jump-by-text must also carry the unlocalized
        // content/summary through to the jump site to survive locale swaps.
        summary: m.summary || '',
        content: m.content || '',
        ts: 0,
      };
    case 'agent':
      return { kind, ord, attachments: [], text: m.text || '', ts: 0 };
    case 'ask':
      return { kind, ord, attachments: [], text: [m.question, m.answer].filter(Boolean).join('\n'), ts: 0 };
    default:
      return null; // unreachable — messageKind() already gated the raw type
  }
}

// ── Filters (category tab × tool select) ───────────────────
/** v3.2: typed tabs read the UNIFIED INDEX — membership is the message's own
 *  entry types, so user attachments and LLM Pop content land in the same
 *  categories (the standalone Pop tab and its tool-name test are retired;
 *  legacy payload-less rows are covered by the index's degrade path). */
function matchesTab(m) {
  const entries = m.mediaEntries || [];
  switch (currentTab) {
    case 'media': return entries.some(e => e.type === 'image' || e.type === 'video');
    case 'files': return entries.some(e => e.type === 'file');
    case 'links': return entries.some(e => e.type === 'link');
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

/** Normalize one session's raw history: the message view (searchable rows)
 *  PLUS the unified media index over the SAME array (popMediaIndex.js). Each
 *  normalized message carries its own entries on `.mediaEntries` (the typed
 *  tabs' message-level membership), and the session's full entry list comes
 *  back for the media grid. */
function normalizeAll(raw, meta) {
  const all = [];
  raw.forEach((m, i) => {
    const n = normalizeMessage(m, i);
    if (n) all.push(n);
  });
  const entries = indexMedia(raw, meta || {});
  const byOrd = new Map();
  for (const e of entries) {
    if (!byOrd.has(e.msgOrd)) byOrd.set(e.msgOrd, []);
    byOrd.get(e.msgOrd).push(e);
  }
  for (const n of all) n.mediaEntries = byOrd.get(n.ord) || [];
  return { all, entries };
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
        clearResults(resultsEl);
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
      const norm = normalizeAll(raw, { sessionId: s.id, sessionName: s.name || s.id });
      const all = norm.all;
      const view = buildView(all);
      let start = 0;
      if (anchorEnd) {
        const ai = view.findIndex(m => m.ts > 0 && m.ts <= anchorEnd);
        // All entries newer than the anchor → position at the stream tail
        // (the earliest end) per spec §3 ruling 3.
        start = ai === -1 ? Math.max(0, view.length - FETCH_PAGE) : ai;
      }
      return {
        id: s.id, name: s.name || s.id, all, view, entries: norm.entries,
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
      const norm = normalizeAll(raw, { sessionId: s.id, sessionName: s.name });
      s.all = norm.all;
      s.entries = norm.entries;
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
      const norm = normalizeAll(raw, { sessionId: s.id, sessionName: s.name });
      s.all = norm.all;
      s.entries = norm.entries;
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
        clearResults(resultsEl);
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
      const norm = normalizeAll(raw, { sessionId: s.id, sessionName: s.name || s.id });
      return { session: s, all: norm.all, entries: norm.entries };
    }, showProgress ? (done, total) => {
      if (mySeq === searchSeq) statusEl.textContent = t('search.progress', { done, total });
    } : null);

    if (mySeq !== searchSeq) return;

    populateToolFilter(perSession.map(p => ({ all: p.all })), selectById('search-tool')?.value || '');

    // v3.2: typed media tabs search ENTRY FIELDS, not message text (PLAN §3.5)
    // — the one-shot contract (debounce upstream, seq race guard, cap) stays.
    if (MEDIA_TABS.includes(currentTab)) {
      const entries = [];
      for (const { entries: es } of perSession) {
        for (const e of es || []) {
          if (!entryMatchesTab(e, currentTab)) continue;
          if (currentSource && e.source !== currentSource) continue;
          if (kw && !entryMatchesKeyword(e, kw)) continue;
          entries.push(e);
        }
      }
      entries.sort((a, b) => (b.ts - a.ts) || (b.msgOrd - a.msgOrd));
      streamSessions = sessions.map(s => {
        const mine = entries.filter(e => e.sessionId === s.id);
        return { id: s.id, name: s.name || s.id, all: [], view: mine, entries: mine,
                 start: 0, end: mine.length, el: null, headerEl: null };
      });
      buildModel();          // message view empty under grid tabs — keep state coherent
      renderStreamFresh(resultsEl, statusEl);
      return;
    };

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
          // #41: carry the raw segments through — keyword-search results are
          // rebuilt field-by-field here, and dropping them starved the jump
          // site of its locale-independent candidates.
          summary: m.summary || '',
          content: m.content || '',
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
    // #41: copy the raw tool segments too — this wrapper is what the click
    // handler hands to the message window (chatSearchFloat); without them the
    // window's hit-locating candidates are undefined under every locale.
    summary: m.summary || '',
    content: m.content || '',
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

/** Drop the rendered list. Card bodies own sandboxed iframes, so their
 *  observers/browsing contexts are released FIRST (cardRegistry contract:
 *  cleanup before a mass DOM removal — a re-search reruns on every keystroke).
 *  @param {HTMLElement} resultsEl */
function clearResults(resultsEl) {
  cleanupCardIframes(resultsEl);
  resultsEl.innerHTML = '';
}

/** Fresh full render of the current window (initial load / mode switch). */
function renderStreamFresh(resultsEl, statusEl) {
  resultsEl.removeAttribute('data-loading');
  sentinelObserver?.disconnect();
  sentinelEl = null;
  clearResults(resultsEl);
  renderedCount = 0;
  activeIndex = -1;

  // v3.2: the typed media tabs render the ENTRY GRID (thumbnails, time groups,
  // source chips) — a different view over the same fetched history, so the
  // message-stream pipeline below is untouched for all/date tabs.
  if (MEDIA_TABS.includes(currentTab)) {
    renderMediaGrid(resultsEl, statusEl);
    return;
  }

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
/** Card marker written by the Card tool (`___CARD_HTML___{json}`) — the same
 *  payload chat.js / persistence.js feed to renderWithRegistry when they draw
 *  a tool message's card body. */
const CARD_MARKER_RE = /^___\w+_HTML___/;

/** The card payload to hand to renderWithRegistry for this row, or null when
 *  the message carries no card body. */
function cardPayloadOf(r) {
  if (r.kind !== 'tool') return null;
  const c = r.content;
  if (typeof c === 'string' && CARD_MARKER_RE.test(c)) return c;
  if (c && typeof c === 'object' && typeof c.html === 'string' && c.html) return c;
  return null;
}

/** True when the message WAS a Card delivery, so a missing/unrenderable body
 *  is a visible failure rather than a no-op (row gets an inline warning). */
function expectsCardBody(r) {
  return r.kind === 'tool' && cleanToolName(r.tool) === 'Card';
}

/** Inline warning strip inside a result row (failure must be visible, never
 *  console-only and never a silently missing card). */
function mountRowNotice(rowEl, msg) {
  const existing = rowEl.querySelector('.search-result-card-warn');
  if (existing) { existing.textContent = msg; return existing; }
  const el = document.createElement('div');
  el.className = 'search-result-card-warn';
  el.setAttribute('role', 'status');
  el.textContent = msg;
  rowEl.appendChild(el);
  return el;
}

/**
 * Render a historical card body inside a result row through the shared card
 * registry renderer (no bespoke card DOM). Every failure branch is surfaced in
 * the row itself.
 * @param {HTMLElement} host  the row's `.search-result-card` container
 * @param {any} payload       renderWithRegistry payload (marker string/object)
 * @param {HTMLElement} rowEl the owning result row (notice target)
 */
function mountResultCard(host, payload, rowEl) {
  const fail = (msg) => {
    host.dataset.cardState = 'failed';
    mountRowNotice(rowEl, msg);
  };
  if (!payload) { fail(t('search.cardUnavailable')); return; }
  let ok = false;
  try {
    ok = renderWithRegistry(host, payload);
  } catch (e) {
    console.debug('[chatSearch] card render threw', e);
    fail(t('search.cardRenderFailed'));
    return;
  }
  if (!ok) { fail(t('search.cardRenderFailed')); return; }
  host.dataset.cardState = 'rendered';
  // Late failure detector: a card iframe that got its srcdoc but never reported
  // a height means its own content/assets never rendered (the height protocol
  // is the only signal the parent gets from inside the sandboxed frame).
  setTimeout(() => {
    if (!host.isConnected || host.dataset.cardState !== 'rendered') return;
    const frame = host.querySelector('iframe[data-nf-card-id]');
    if (!(frame instanceof HTMLIFrameElement)) { fail(t('search.cardRenderFailed')); return; }
    if (frame.hasAttribute('srcdoc') && !frame.style.height) fail(t('search.cardLoadFailed'));
  }, 4000);
}

/** One result row element. data-key is the stable identity (sessionId:ord);
 *  data-ts / data-kind / data-attachments are the QA assertion surface.
 *  data-kind carries the full kind vocabulary ('user' | 'agent' | 'ai' |
 *  'tool') — 'agent' = the injected blue-bubble class, a class of its own. */
function resultElement(r) {
  const kw = (inputById('search-keyword')?.value || '').trim();
  // Queue #2: Pop/Card artifact rows (Canvas-openable) get data-artifact="1" —
  // the QA surface for the click-routing split + the CSS ↗ affordance.
  const artifact = popArtifactFromInput(r.tool, r.input) ? '1' : '';
  const typeBadge = r.kind === 'tool'
    ? `${escapeHtml(t('search.typeTool'))} · ${escapeHtml(cleanToolName(r.tool))}`
    : r.kind === 'user'
      ? escapeHtml(t('search.typeUser'))
      : r.kind === 'agent'
        ? escapeHtml(t('search.typeAgent'))
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
  const el = /** @type {HTMLElement} */ (tpl.content.firstElementChild);
  // Card-bearing messages get their card body expanded right under the
  // preview; every other row keeps its exact previous DOM (zero new nodes).
  const payload = cardPayloadOf(r);
  if (payload || expectsCardBody(r)) {
    const host = document.createElement('div');
    host.className = 'search-result-card';
    el.appendChild(host);
    mountResultCard(host, payload, el);
  }
  return el;
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

// ── v3.2 media grid (#491 主题二) ───────────────────────────
// The unified-index view: one grid per typed tab, grouped 本周/按月, source
// chips (全部来源/用户/助手), in-category keyword over entry fields. Entries
// are REFERENCES — thumbnails mint at render time through the single URL
// builders (inline data URI → uploads route for user refs → nf-file ticket for
// agent refs); video duration badges lazy-load via <video preload=metadata>
// (PLAN §3.4 default: zero backend change).

/** Rebuild the tab strip from CONTENT_TABS. index.html keeps its static
 *  scaffold untouched; THIS list is the normative set (v3.2: images/pop tabs
 *  retired, media/links added, date joins the roving enumeration). */
function buildTabBar() {
  const wrap = document.querySelector('#search-modal .search-tabs');
  if (!wrap) return;
  wrap.innerHTML = CONTENT_TABS.map(tab => tab === 'date'
    ? `<button type="button" class="search-tab" id="search-tab-date" role="tab" aria-selected="false" aria-haspopup="dialog" aria-expanded="false" data-tab="date"></button>`
    : `<button type="button" class="search-tab" id="search-tab-${tab}" role="tab" aria-selected="false" data-tab="${tab}"></button>`
  ).join('');
}

/** The source chip row (dynamic — same zero-index.html-change rule). */
function buildSourceChips() {
  const controls = document.querySelector('#search-modal .search-controls');
  if (!controls || document.getElementById('search-source-chips')) return;
  const row = document.createElement('div');
  row.id = 'search-source-chips';
  row.setAttribute('role', 'group');
  row.hidden = true;
  for (const v of ['', 'user', 'agent']) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'search-source-chip';
    b.dataset.source = v;
    b.addEventListener('click', () => {
      currentSource = v;
      syncSourceChips();
      // Re-filter from the already-fetched entries — no refetch.
      if (MEDIA_TABS.includes(currentTab)) {
        const resultsEl = document.getElementById('search-results');
        const statusEl = document.getElementById('search-status');
        if (resultsEl && statusEl) { clearResults(resultsEl); renderMediaGrid(resultsEl, statusEl); }
      }
    });
    row.appendChild(b);
  }
  controls.appendChild(row);
}

/** Chip labels/visibility follow the current tab + selection. */
function syncSourceChips() {
  const row = document.getElementById('search-source-chips');
  if (!row) return;
  row.hidden = !MEDIA_TABS.includes(currentTab);
  for (const b of /** @type {HTMLElement[]} */ (Array.from(row.querySelectorAll('.search-source-chip')))) {
    const v = b.dataset.source || '';
    const on = v === currentSource;
    b.classList.toggle('active', on);
    b.setAttribute('aria-pressed', on ? 'true' : 'false');
    b.textContent = t(v === 'user' ? 'search.sourceUser' : v === 'agent' ? 'search.sourceAgent' : 'search.sourceAll');
  }
}

/** All index entries across the loaded sessions. */
function collectEntries() {
  const out = [];
  for (const s of streamSessions) for (const e of (s.entries || [])) out.push(e);
  return out;
}

/** The grid's data set: tab membership × source chip × in-category keyword. */
function filteredEntries() {
  let entries = collectEntries().filter(e => entryMatchesTab(e, currentTab));
  if (currentSource) entries = entries.filter(e => e.source === currentSource);
  const kw = (inputById('search-keyword')?.value || '').trim();
  if (kw) entries = entries.filter(e => entryMatchesKeyword(e, kw));
  entries.sort((a, b) => (b.ts - a.ts) || (b.msgOrd - a.msgOrd));
  return entries;
}

/** Thumbnail URL for one entry: inline data URI wins; user refs ride the
 *  uploads route; agent refs mint an nf-file ticket — the same two byte legs
 *  the inline faces use. Returns a promise (ticket minting is async). */
function resolveEntryThumb(e) {
  if (e.thumbRef) return Promise.resolve(e.thumbRef);
  if (!e.ref) return Promise.resolve('');
  if (e.source === 'user') {
    let url = '';
    try { url = attachmentImageUrl(e.ref) || ''; } catch { url = ''; }
    return Promise.resolve(url);
  }
  return ticketUrl(e.ref).catch(() => '');
}

function mediaEntryCard(e) {
  const card = document.createElement('div');
  card.className = 'search-media-card';
  card.dataset.entryType = e.type;
  card.dataset.source = e.source;
  card.dataset.ts = String(e.ts || 0);
  card.dataset.entryId = e.id;
  if (e.type === 'image' || e.type === 'video') {
    if (e.type === 'image') {
      const img = document.createElement('img');
      img.className = 'search-media-thumb';
      img.alt = e.name;
      img.loading = 'lazy';
      img.draggable = false;
      resolveEntryThumb(e).then(url => { if (img.isConnected && url) img.src = url; }).catch(() => {});
      img.addEventListener('error', () => card.classList.add('search-media-broken'));
      card.appendChild(img);
    } else {
      // Duration badge rides the browser's own metadata probe — the payload
      // carries no duration field (PLAN §3.4: front-end lazy fill chosen over
      // a backend read leg).
      const vid = document.createElement('video');
      vid.className = 'search-media-thumb';
      vid.preload = 'metadata';
      vid.muted = true;
      vid.playsInline = true;
      resolveEntryThumb(e).then(url => { if (vid.isConnected && url) vid.src = url; }).catch(() => {});
      const chip = document.createElement('div');
      chip.className = 'search-media-dur';
      vid.addEventListener('loadedmetadata', () => {
        const d = Math.round(vid.duration);
        if (Number.isFinite(d) && d > 0) {
          chip.textContent = Math.floor(d / 60) + ':' + String(d % 60).padStart(2, '0');
        }
      });
      card.appendChild(vid);
      card.appendChild(chip);
    }
    const name = document.createElement('div');
    name.className = 'search-media-name';
    name.textContent = e.name;
    name.title = e.name;
    card.appendChild(name);
  } else if (e.type === 'link') {
    const icon = document.createElement('div');
    icon.className = 'search-media-ext link';
    icon.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>';
    const body = document.createElement('div');
    body.className = 'search-media-linkbody';
    const label = document.createElement('div');
    label.className = 'search-media-name';
    label.textContent = e.name;
    const url = document.createElement('div');
    url.className = 'search-media-url';
    url.textContent = e.ref;
    body.append(label, url);
    card.append(icon, body);
  } else {
    const icon = document.createElement('div');
    icon.className = 'search-media-ext' + (e.ext ? '' : ' generic');
    icon.textContent = (e.ext || 'file').toUpperCase().slice(0, 4);
    const body = document.createElement('div');
    body.className = 'search-media-linkbody';
    const label = document.createElement('div');
    label.className = 'search-media-name';
    label.textContent = e.name;
    label.title = e.ref || e.name;
    body.appendChild(label);
    card.append(icon, body);
  }
  // Source badge (user / assistant) — the alignment marker (主题三).
  const badge = document.createElement('span');
  badge.className = 'search-media-source src-' + e.source;
  badge.textContent = t(e.source === 'user' ? 'search.sourceUser' : 'search.sourceAgent');
  card.appendChild(badge);
  const time = document.createElement('span');
  time.className = 'search-media-time';
  time.textContent = e.ts ? formatTime(e.ts) : '';
  card.appendChild(time);
  card.addEventListener('click', () => activateMediaEntry(e));
  return card;
}

/** Grid activation: the SAME open faces as the in-stream cards (AC-S1/S2
 *  parity holds panel-wide) — media → the shared lightbox (Canvas secondary
 *  entry on the overlay), file → the workspace-open-item single point,
 *  link → a new tab. */
function activateMediaEntry(e) {
  if (e.type === 'image' || e.type === 'video') {
    openMediaLightbox({
      kind: e.type, name: e.name, src: e.thumbRef || '', path: e.ref,
      resolver: e.source === 'user'
        ? (p) => { try { return attachmentImageUrl(p) || ''; } catch { return ''; } }
        : undefined,
    });
  } else if (e.type === 'file') {
    // Act-and-exit, mirroring the artifact-row routing (v2 ruling): the modal
    // closes and the file opens in Canvas through the single point.
    if (e.ref) {
      closeSearchModal();
      openPopArtifact(e.ref, e.name);
    }
  } else if (e.type === 'link') {
    window.open(e.ref, '_blank', 'noopener');
  }
}

/** Fresh grid render: time groups → headers → card grids. */
function renderMediaGrid(resultsEl, statusEl) {
  syncSourceChips();
  activeIndex = -1;
  const entries = filteredEntries();
  lastTotal = entries.length;
  lastResults = [];
  if (!entries.length) {
    const kw = (inputById('search-keyword')?.value || '').trim();
    resultsEl.innerHTML = kw
      ? `<div class="search-hint">${escapeHtml(t('search.noResults'))}<br>${escapeHtml(t('search.noResultsSuggestion'))}</div>`
      : `<div class="search-hint">${escapeHtml(t('search.emptyCategory'))}</div>`;
    statusEl.textContent = statusSummary();
    return;
  }
  const groups = groupEntriesByTime(entries, new Date());
  for (const g of groups) {
    if (g.key !== 'undated') {
      const header = document.createElement('div');
      header.className = 'search-media-group-header';
      header.textContent = g.key === 'week'
        ? t('search.groupThisWeek')
        : t('search.groupMonth', { n: g.m0 + 1 });
      resultsEl.appendChild(header);
    }
    const grid = document.createElement('div');
    grid.className = 'search-media-grid';
    for (const e of g.entries) grid.appendChild(mediaEntryCard(e));
    resultsEl.appendChild(grid);
  }
  statusEl.textContent = statusSummary();
}

// ── Result activation ───────────────────────────────────────
/**
 * Activate a result row (click or Enter).
 *   - Pop/Card artifact messages (Canvas-openable filePath — same detection as
 *     the message bubble's Pop card) keep the existing Canvas-direct routing:
 *     the modal closes and the artifact opens in Canvas (act-and-exit).
 *   - Every other result opens a detached floating window that STARTS at the
 *     clicked message (chatSearchFloat.js). The main session is left exactly as
 *     it was — no session switch, no scroll, no focus change — and the search
 *     modal stays open (opening a window changes neither the main window's
 *     state nor the modal's, and several windows can be opened in a row).
 */
function activateResult(res) {
  const art = popArtifactFromInput(res.tool, res.input);
  if (art) {
    closeSearchModal();
    openPopArtifact(art.filePath, art.title);
    return;
  }
  const row = document.querySelector(`#search-results .search-result[data-key="${CSS.escape(res.key || '')}"]`);
  openSearchMessageFloat(res, {
    // The raw-message lookup inside the window cannot import the predicate
    // back (that would close a static cycle with this module — rejected by
    // scripts/check-circular.mjs), so the single point is injected here: the
    // window's kind↔UiMessage comparison runs THIS function, never a copy.
    kindOf: messageKind,
    onError: (msg) => { if (row instanceof HTMLElement) mountRowNotice(row, msg); },
  });
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
  if (resultsEl && (lastResults.length || MEDIA_TABS.includes(currentTab))) {
    renderStreamFresh(resultsEl, /** @type {HTMLElement} */ (statusEl));
  }
});
