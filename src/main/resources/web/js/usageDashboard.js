// usageDashboard.js — #310 Token usage dashboard (spec token-dashboard-spec.md
// v1.3). GitHub-style day×week heatmap FIXED to a full year + independent
// stats-range summary cards + intraday 24h curve + drilldown as secondary.
//
// v1.3 rulings (user 2026-08-22 23:58, five points):
//   ① heatmap fills a full year (no gaps; empty days = level-0)
//   ② cost levels by order of magnitude: 0 / <1M / 1M-10M / 10M-100M / ≥100M
//   ③ clicking a cell shows the intraday 24h curve (dim=hour, SVG bars)
//   ④ stats-range buttons (1d/1w/1m/3m) drive ONLY the summary cards —
//      zero coupling with the heatmap
//   ⑤ heatmap dimension param: total | model | agent (Top-1 day distribution
//      when filter = all), legend label marks the active dimension value
//
// Data: GET /api/usage/aggregate (dim=day|hour|provider|model|agent, from/to
// epoch ms, provider/model/agent exact-match filters — D1 landed in backend).
// Caliber (v1.2): hitRate = cacheRead / input; total = input + output
// (inputTokens already INCLUDES cacheRead/cacheWrite — never sum four buckets).
//
// Visual: glass modal, no overlay dimming (visual-style ruling); sapphire
// alpha variants only (zero new color tokens).

import { t, getLocale } from './i18n.js';
import state from './state.js';
import { authHeaders } from './flowHelpers.js';
import { createIconsIn } from './utils.js';

/** Providers known not to report cache fields (spec §6.9c).
 *  Empty since 2026-08-21: reconciliation (report f5e80ca §4 F2) confirmed kimi
 *  reports cache normally (169/177 records cr>0, hit rate on par with the main
 *  provider). Mechanism kept; list empty — extend here if a new case appears. */
const NO_CACHE_PROVIDERS = [];

const MS_DAY = 86400000;
const YEAR_WEEKS = 52; // 52 full weeks before the current week column (53 cols)

// ── module state ────────────────────────────────────────────────────
let overlay = null;
let isOpen = false;
/** stats range for summary cards only (spec §2.3/§2.4, ruling ④) */
let statRange = '1w'; // '1d' | '1w' | '1m' | '3m'
/** heatmap dimension (spec §2.5, ruling ⑤) */
let heatDim = 'total'; // 'total' | 'model' | 'agent'
/** dimension value currently coloring the heatmap ('' = total view) */
let heatDimValue = '';
/** @type {{provider: string, model: string, agent: string}} */
let filters = { provider: '', model: '', agent: '' };
let colorMode = 'cost'; // 'cost' | 'hitRate'
/** @type {Map<string, any>} day buckets backing the current heatmap render */
let activeDayData = new Map();
/** @type {string[]} ordered date keys inside the one-year window */
let windowKeys = [];
// caches (cleared on filter change)
let mainDayAgg = null; // total dim=day one-year aggregate
/** @type {Map<string, any>} */
const rankCache = new Map(); // 'model'|'agent' -> year-window rank aggregate
/** @type {Map<string, any>} */
const dimDayCache = new Map(); // '<dim>:<value>' -> filtered dim=day aggregate
let curveDate = null; // 'YYYY-MM-DD' of the selected cell (curve section)
let drillOpen = false; // drilldown table expanded (secondary panel)
let drillDim = 'agent';
let mainFailCount = 0;
let lastTotals = null;
let lastFocus = null;
let weekStats = null; // { thisAvg, lastAvg, thisHit, lastHit }
let todayStats = null; // { today, yestSameTime }
let topAgent = '';
let topModel = '';
let topAgentCost = 0;
let topModelCost = 0;

// ── date helpers (local timezone — backend dayKey uses systemDefault) ──
function startOfToday() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}
function dateKeyOf(ms) {
  const d = new Date(ms);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}
function keyToMs(key) {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d).getTime();
}
/** Fixed one-year heatmap window (ruling ①): ends today, starts on the
 *  Sunday of the week 52 weeks back — columns always align to weeks. */
function yearWindow() {
  const today = startOfToday();
  const dow = new Date(today).getDay(); // 0 = Sunday
  const thisSunday = today - dow * MS_DAY;
  const from = thisSunday - YEAR_WEEKS * 7 * MS_DAY;
  return { from, to: today + MS_DAY };
}
/** Summary-card window driven by the stats-range buttons (ruling ④). */
function statWindow() {
  const today = startOfToday();
  const days = { '1d': 1, '1w': 7, '1m': 30, '3m': 90 }[statRange] || 7;
  const from = statRange === '1d' ? today : today - (days - 1) * MS_DAY;
  return { from, to: today + MS_DAY };
}

// ── formatting (spec §5.4) ──────────────────────────────────────────
function fmtTokens(v) {
  const loc = getLocale() === 'en' ? 'en-US' : 'zh-CN';
  if (v >= 1e8) {
    const n = (v / 1e8).toFixed(1);
    return loc === 'zh-CN' ? n + ' ' + t('usage.unit.billion') : n + t('usage.unit.billion');
  }
  if (v >= 1e4) {
    const n = (v / 1e4).toFixed(1);
    return loc === 'zh-CN' ? n + ' ' + t('usage.unit.million') : n + t('usage.unit.million');
  }
  return new Intl.NumberFormat(loc).format(Math.round(v));
}
function fmtPct(v) { return (Math.round(v * 10) / 10).toFixed(1); }
function fmtDateLong(ms) {
  return new Intl.DateTimeFormat(getLocale() === 'en' ? 'en-US' : 'zh-CN', { month: 'long', day: 'numeric' }).format(new Date(ms));
}
/** v1.2 hit rate = cacheRead / input; null when no data (spec §6.9). */
function hitRateOf(b) {
  const inn = b.inputTokens || 0;
  const cr = b.cacheReadTokens || 0;
  if (inn <= 0 && cr <= 0) return null;
  if (inn <= 0) return null;
  return (cr / inn) * 100;
}
function totalOf(b) { return (b.inputTokens || 0) + (b.outputTokens || 0); }
function hasCacheData(b) { return !((b.cacheReadTokens || 0) === 0 && (b.inputTokens || 0) === 0); }

// ── API ─────────────────────────────────────────────────────────────
function aggUrl(params) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  }
  return '/api/usage/aggregate?' + q.toString();
}
async function fetchAgg(params) {
  const resp = await fetch(aggUrl(params), { headers: authHeaders() });
  if (!resp.ok) throw new Error('usage aggregate ' + resp.status);
  return resp.json();
}
function filterParams() {
  return { provider: filters.provider, model: filters.model, agent: filters.agent };
}

// ── DOM construction ────────────────────────────────────────────────
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function buildShell() {
  overlay = el('div', '');
  overlay.id = 'usage-overlay';
  overlay.hidden = true;

  const panel = el('div', '');
  panel.id = 'usage-dashboard';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-modal', 'true');
  panel.setAttribute('aria-label', t('usage.title'));

  // header
  const header = el('div', 'ud-header');
  const title = el('span', '', t('usage.title'));
  title.id = 'usage-title';
  const closeBtn = el('button', 'panel-btn', '');
  closeBtn.id = 'usage-close';
  closeBtn.title = t('usage.close');
  closeBtn.setAttribute('aria-label', t('usage.close'));
  closeBtn.innerHTML = '<i data-lucide="x"></i>';
  header.append(title, closeBtn);

  // filter row (global filters — spec §2.3)
  const filtersWrap = el('div', 'ud-filters');
  for (const dim of ['provider', 'model', 'agent']) {
    const sel = document.createElement('select');
    sel.className = 'ud-select glass-control';
    sel.id = 'usage-filter-' + dim;
    sel.setAttribute('aria-label', t('usage.dim.' + dim));
    sel.addEventListener('change', () => {
      filters[dim] = sel.value;
      reloadAll();
    });
    filtersWrap.appendChild(sel);
  }

  // stats range + summary cards (ruling ④ — range drives ONLY the cards)
  const statsWrap = el('div', 'ud-stats');
  const statsRow = el('div', 'ud-stats-row');
  const statsLabel = el('span', 'ud-stats-label', t('usage.statsRangeLabel'));
  const ranges = el('div', 'ud-sranges');
  ranges.id = 'usage-stats-ranges';
  ranges.setAttribute('role', 'radiogroup');
  ranges.setAttribute('aria-label', t('usage.statsRangeLabel'));
  for (const [val, key] of [['1d', 'usage.stats.1d'], ['1w', 'usage.stats.1w'], ['1m', 'usage.stats.1m'], ['3m', 'usage.stats.3m']]) {
    const b = el('button', 'ud-srange', t(key));
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(val === statRange));
    b.dataset.range = val;
    b.addEventListener('click', () => {
      if (statRange === val) return;
      statRange = val;
      for (const r of ranges.querySelectorAll('.ud-srange')) r.setAttribute('aria-checked', 'false');
      b.setAttribute('aria-checked', 'true');
      loadSummary(); // heatmap untouched (zero coupling, ruling ④)
    });
    ranges.appendChild(b);
  }
  statsRow.append(statsLabel, ranges);
  const summary = el('div', '');
  summary.id = 'usage-summary';
  summary.setAttribute('role', 'group');
  statsWrap.append(statsRow, summary);

  // heatmap controls (ruling ⑤ dimension + v1.1 color mode)
  const heatControls = el('div', 'ud-heat-controls');
  const dimSeg = el('div', 'ud-hdims');
  dimSeg.id = 'usage-heat-dims';
  dimSeg.setAttribute('role', 'radiogroup');
  dimSeg.setAttribute('aria-label', t('usage.heatDim'));
  for (const [dim, key] of [['total', 'usage.heatDim.total'], ['model', 'usage.heatDim.model'], ['agent', 'usage.heatDim.agent']]) {
    const b = el('button', 'ud-hdim', t(key));
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(dim === heatDim));
    b.dataset.dim = dim;
    b.addEventListener('click', () => {
      if (heatDim === dim) return;
      heatDim = dim;
      for (const r of dimSeg.querySelectorAll('.ud-hdim')) r.setAttribute('aria-checked', 'false');
      b.setAttribute('aria-checked', 'true');
      switchHeatDim();
    });
    dimSeg.appendChild(b);
  }
  const colorModeWrap = el('div', 'ud-colormode');
  colorModeWrap.setAttribute('role', 'radiogroup');
  colorModeWrap.setAttribute('aria-label', t('usage.colorMode'));
  for (const [mode, key] of [['cost', 'usage.colorMode.cost'], ['hitRate', 'usage.colorMode.hitRate']]) {
    const b = el('button', 'ud-cm', t(key));
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(mode === colorMode));
    b.dataset.mode = mode;
    b.addEventListener('click', () => {
      colorMode = mode;
      for (const r of colorModeWrap.querySelectorAll('.ud-cm')) r.setAttribute('aria-checked', 'false');
      b.setAttribute('aria-checked', 'true');
      renderHeatmap();
    });
    colorModeWrap.appendChild(b);
  }
  const dimLabel = el('span', 'ud-dimlabel');
  dimLabel.id = 'usage-dimlabel';
  dimLabel.hidden = true;
  heatControls.append(dimSeg, dimLabel, colorModeWrap);

  // heatmap area
  const heatwrap = el('div', '');
  heatwrap.id = 'usage-heatwrap';
  const heatscroll = el('div', '');
  heatscroll.id = 'usage-heatscroll';
  const heatmain = el('div', '');
  heatmain.id = 'usage-heatmain';
  const months = el('div', '');
  months.id = 'usage-months';
  const grid = el('div', '');
  grid.id = 'usage-grid';
  grid.setAttribute('role', 'grid');
  grid.setAttribute('aria-label', t('usage.heatmapLabel'));
  heatmain.append(months, grid);
  heatscroll.appendChild(heatmain);
  const legend = el('div', '');
  legend.id = 'usage-legend';
  heatwrap.append(heatscroll, legend);

  const empty = el('div', 'ud-empty');
  empty.id = 'usage-empty';
  empty.hidden = true;
  empty.append(el('div', 'ud-empty-title', t('usage.empty')), el('div', 'ud-empty-hint', t('usage.emptyHint')));

  // intraday curve section (ruling ③)
  const curve = el('div', 'ud-curve');
  curve.id = 'usage-curve';
  curve.hidden = true;

  // drilldown drawer (secondary panel, v1.3)
  const drill = el('div', 'ud-drill');
  drill.id = 'usage-drill';
  drill.hidden = true;
  drill.setAttribute('aria-live', 'polite');

  // tooltip
  const tip = el('div', 'ud-tooltip');
  tip.id = 'usage-tooltip';
  tip.hidden = true;

  panel.append(header, filtersWrap, statsWrap, heatControls, heatwrap, empty, curve, drill);
  overlay.appendChild(panel);
  document.body.appendChild(overlay);
  overlay.appendChild(tip);

  closeBtn.addEventListener('click', closeUsageDashboard);
  overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) closeUsageDashboard(); });
  document.addEventListener('keydown', onDocKeydown);

  // grid interactions (delegated)
  grid.addEventListener('click', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    const cell = /** @type {HTMLElement|null} */ (target.closest('.ud-cell[data-date]'));
    if (cell) toggleCurve(cell.dataset.date || null, cell);
  });
  grid.addEventListener('keydown', onGridKeydown);
  grid.addEventListener('mouseover', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    const cell = /** @type {HTMLElement|null} */ (target.closest('.ud-cell[data-date]'));
    if (cell) showTip(cell, e);
  });
  grid.addEventListener('mouseout', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    if (target.closest('.ud-cell[data-date]')) hideTip();
  });
  grid.addEventListener('mousemove', (e) => {
    if (!tip.hidden) moveTip(e);
  });

  createIconsIn(overlay);
}

// ── open / close ────────────────────────────────────────────────────
export function openUsageDashboard() {
  if (!overlay) buildShell();
  if (isOpen) return;
  isOpen = true;
  lastFocus = document.activeElement;
  overlay.hidden = false;
  overlay.classList.remove('closing');
  const btn = document.getElementById('usage-btn');
  btn?.classList.add('active');
  refreshLocale();
  reloadAll();
  // focus first focusable
  setTimeout(() => { document.getElementById('usage-close')?.focus(); }, 30);
}

export function closeUsageDashboard() {
  if (!isOpen || !overlay) return;
  isOpen = false;
  overlay.classList.add('closing');
  setTimeout(() => {
    overlay.hidden = true;
    overlay.classList.remove('closing');
  }, 100);
  document.getElementById('usage-btn')?.classList.remove('active');
  hideTip();
  if (lastFocus && /** @type {HTMLElement} */ (lastFocus).focus) /** @type {HTMLElement} */ (lastFocus).focus();
  else document.getElementById('usage-btn')?.focus();
}

function onDocKeydown(e) {
  if (!isOpen) return;
  if (e.key === 'Escape') {
    e.stopPropagation();
    if (drillOpen) closeDrill();
    else if (curveDate) closeCurve();
    else closeUsageDashboard();
    return;
  }
  if (e.key === 'Tab') trapFocus(e);
}

function trapFocus(e) {
  if (!overlay) return;
  const focusables = [...overlay.querySelectorAll('button, select, input, [tabindex="0"]')]
    .filter((n) => !/** @type {HTMLButtonElement} */ (n).disabled && /** @type {HTMLElement} */ (n).offsetParent !== null);
  if (focusables.length === 0) return;
  const first = /** @type {HTMLElement} */ (focusables[0]);
  const last = /** @type {HTMLElement} */ (focusables[focusables.length - 1]);
  if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}

// ── grid keyboard (roving tabindex, APG grid) ───────────────────────
function onGridKeydown(e) {
  const target = /** @type {HTMLElement} */ (e.target);
  const cell = /** @type {HTMLElement|null} */ (target.closest('.ud-cell[data-date]'));
  if (!cell) return;
  const grid = document.getElementById('usage-grid');
  if (!grid) return;
  const cells = [...grid.querySelectorAll('.ud-cell[data-date]')];
  const idx = cells.indexOf(cell);
  if (idx < 0) return;
  let next = -1;
  if (e.key === 'ArrowRight') next = idx + 7 < cells.length ? idx + 7 : idx;
  else if (e.key === 'ArrowLeft') next = idx - 7 >= 0 ? idx - 7 : idx;
  else if (e.key === 'ArrowDown') next = (idx % 7) + 1 < 7 ? idx + 1 : idx;
  else if (e.key === 'ArrowUp') next = (idx % 7) - 1 >= 0 ? idx - 1 : idx;
  else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleCurve(cell.dataset.date || null, cell); return; }
  else return;
  e.preventDefault();
  if (next >= 0 && next !== idx) {
    cell.setAttribute('tabindex', '-1');
    const n = /** @type {HTMLElement} */ (cells[next]);
    n.setAttribute('tabindex', '0');
    n.focus();
  }
}

// ── data loading ────────────────────────────────────────────────────
function clearCaches() {
  mainDayAgg = null;
  rankCache.clear();
  dimDayCache.clear();
}

/** Full reload: open / filter change. Heatmap + summary cards (+ curve if open). */
async function reloadAll() {
  if (!overlay) return;
  clearCaches();
  const heatwrap = document.getElementById('usage-heatwrap');
  heatwrap?.classList.add('ud-reloading');
  try {
    await Promise.all([loadSummary(), loadHeatmap()]);
  } finally {
    heatwrap?.classList.remove('ud-reloading');
  }
  if (curveDate) await loadCurve(curveDate);
  if (drillOpen && curveDate) loadDrill(curveDate);
}

/** Summary cards only (ruling ④ — stats range switch lands here alone). */
async function loadSummary() {
  if (!overlay) return;
  const { from, to } = statWindow();
  const fp = filterParams();
  const today = startOfToday();
  const wkStart = today - 6 * MS_DAY; // rolling 7-day week incl. today (absolute)
  try {
    const [totals, todayH, yestH, thisW, lastW, agents, models] = await Promise.all([
      fetchAgg({ from, to, ...fp }),
      fetchAgg({ dim: 'hour', from: today, to: today + MS_DAY, ...fp }),
      fetchAgg({ dim: 'hour', from: today - MS_DAY, to: today, ...fp }),
      fetchAgg({ from: wkStart, to: today + MS_DAY, ...fp }),
      fetchAgg({ from: wkStart - 7 * MS_DAY, to: wkStart, ...fp }),
      fetchAgg({ dim: 'agent', from, to, ...fp }),
      fetchAgg({ dim: 'model', from, to, ...fp }),
    ]);
    mainFailCount = 0;
    lastTotals = totals;

    // today vs yesterday-same-time from hour buckets
    const nowHour = new Date().getHours();
    const hourVal = (/** @type {any} */ agg) => (agg.buckets || []).reduce((s, b) => {
      const h = parseInt(String(b.key).slice(11, 13) || '0', 10);
      return h <= nowHour ? s + totalOf(b) : s;
    }, 0);
    const dayTotal = (/** @type {any} */ agg) => (agg.buckets || []).reduce((s, b) => s + totalOf(b), 0);
    todayStats = { today: dayTotal(todayH), yestSameTime: hourVal(yestH) };

    const thisSum = (thisW.totalInput || 0) + (thisW.totalOutput || 0);
    const lastSum = (lastW.totalInput || 0) + (lastW.totalOutput || 0);
    weekStats = {
      thisAvg: thisSum / 7,
      lastAvg: lastSum / 7,
      thisHit: (thisW.totalInput || 0) > 0 ? (thisW.totalCacheRead || 0) / thisW.totalInput * 100 : null,
      lastHit: (lastW.totalInput || 0) > 0 ? (lastW.totalCacheRead || 0) / lastW.totalInput * 100 : null,
    };
    const pickTop = (/** @type {any} */ agg) => {
      let best = null;
      for (const b of agg.buckets || []) {
        if (!best || (b.costEquivalent || 0) > (best.costEquivalent || 0)) best = b;
      }
      return best;
    };
    const ta = pickTop(agents);
    topAgent = ta ? ta.key : '';
    topAgentCost = ta ? (ta.costEquivalent || 0) : 0;
    const tm = pickTop(models);
    topModel = tm ? tm.key : '';
    topModelCost = tm ? (tm.costEquivalent || 0) : 0;

    populateFilterOptions(agents);
    renderSummary();
    const emptyEl = document.getElementById('usage-empty');
    if (emptyEl) emptyEl.hidden = (totals.count || 0) > 0 || activeDayData.size > 0;
  } catch (err) {
    mainFailCount++;
    renderSummaryError();
  }
}

/** Heatmap data only (one-year window, ruling ①). */
async function loadHeatmap() {
  const { from, to } = yearWindow();
  const fp = filterParams();
  try {
    if (heatDim === 'total') {
      if (!mainDayAgg) mainDayAgg = await fetchAgg({ dim: 'day', from, to, ...fp });
      activeDayData = bucketsToMap(mainDayAgg.buckets || []);
      heatDimValue = '';
    } else {
      let target = filters[heatDim];
      if (!target) {
        if (!rankCache.has(heatDim)) {
          rankCache.set(heatDim, await fetchAgg({ dim: heatDim, from, to, ...fp }));
        }
        const rk = /** @type {any} */ (rankCache.get(heatDim));
        let best = null;
        for (const b of rk.buckets || []) {
          if (!best || (b.costEquivalent || 0) > (best.costEquivalent || 0)) best = b;
        }
        target = best ? best.key : '';
      }
      heatDimValue = target;
      if (target) {
        const cacheKey = heatDim + ':' + target;
        if (!dimDayCache.has(cacheKey)) {
          // NB: ...fp first — its empty-string values must not clobber the
          // dimension filter (aggUrl drops '' params, but ordering matters
          // for the computed key when both are set).
          dimDayCache.set(cacheKey, await fetchAgg({ ...fp, dim: 'day', [heatDim]: target, from, to }));
        }
        activeDayData = bucketsToMap((/** @type {any} */ (dimDayCache.get(cacheKey))).buckets || []);
      } else {
        activeDayData = new Map();
      }
    }
    windowKeys = [];
    for (let ms = from; ms < to; ms += MS_DAY) windowKeys.push(dateKeyOf(ms));
    renderHeatmap();
  } catch (err) {
    mainFailCount++;
    renderSummaryError();
  }
}
function bucketsToMap(buckets) {
  const m = new Map();
  for (const b of buckets) m.set(b.key, b);
  return m;
}

/** Heatmap dimension switch (ruling ⑤): re-color the heatmap only. */
async function switchHeatDim() {
  const heatwrap = document.getElementById('usage-heatwrap');
  heatwrap?.classList.add('ud-reloading');
  try {
    await loadHeatmap();
    if (curveDate) await loadCurve(curveDate);
    if (drillOpen && curveDate) loadDrill(curveDate);
  } finally {
    heatwrap?.classList.remove('ud-reloading');
  }
}

function populateFilterOptions(agentsAgg) {
  const provSel = document.getElementById('usage-filter-provider');
  const modelSel = document.getElementById('usage-filter-model');
  const agentSel = document.getElementById('usage-filter-agent');
  if (!provSel || !modelSel || !agentSel) return;
  const providers = state.parsedConfig?.llm?.providers || {};
  fillSelect(provSel, Object.keys(providers), filters.provider);
  const models = new Set();
  for (const p of Object.values(providers)) {
    for (const m of (/** @type {any} */ (p)?.models || [])) {
      models.add(typeof m === 'string' ? m : (m?.id || m?.name || ''));
    }
  }
  models.delete('');
  fillSelect(modelSel, [...models], filters.model);
  fillSelect(agentSel, (agentsAgg.buckets || []).map((b) => b.key), filters.agent);
}
function fillSelect(sel, keys, current) {
  const prev = current;
  sel.innerHTML = '';
  const all = document.createElement('option');
  all.value = '';
  all.textContent = t('usage.filterAll');
  sel.appendChild(all);
  for (const k of keys) {
    const o = document.createElement('option');
    o.value = k;
    o.textContent = k;
    sel.appendChild(o);
  }
  sel.value = prev;
}

// ── summary cards ───────────────────────────────────────────────────
function renderSummary() {
  const wrap = document.getElementById('usage-summary');
  if (!wrap || !lastTotals) return;
  wrap.innerHTML = '';
  const tot = lastTotals;
  const total = (tot.totalInput || 0) + (tot.totalOutput || 0);
  const hit = (tot.totalInput || 0) > 0 ? (tot.totalCacheRead || 0) / tot.totalInput * 100 : null;

  const cards = [];
  cards.push(card(t('usage.total'), fmtTokens(total), String(total),
    fmtTokens(tot.costEquivalent || 0) + ' · ' + t('usage.costEquivalent')));

  // today + trend vs yesterday same time
  let todaySub = t('usage.noData');
  if (todayStats && todayStats.yestSameTime > 0) {
    const pct = ((todayStats.today - todayStats.yestSameTime) / todayStats.yestSameTime) * 100;
    const dir = pct > 0.05 ? '↑' : pct < -0.05 ? '↓' : '';
    todaySub = dir === '' ? t('usage.trend.flat')
      : t(pct > 0 ? 'usage.trend.up' : 'usage.trend.down', { pct: fmtPct(Math.abs(pct)) });
  }
  cards.push(card(t('usage.today'), todayStats ? fmtTokens(todayStats.today) : t('usage.noData'), '', todaySub, true));

  // week-over-week
  let wkSub = t('usage.noData');
  if (weekStats && weekStats.lastAvg > 0) {
    const pct = ((weekStats.thisAvg - weekStats.lastAvg) / weekStats.lastAvg) * 100;
    wkSub = t('usage.weekLabel', { dir: pct >= 0 ? '↑' : '↓', pct: fmtPct(Math.abs(pct)) });
  }
  cards.push(card(t('usage.weekTrend'), weekStats ? fmtTokens(weekStats.thisAvg) : t('usage.noData'), t('usage.weekTrend'), wkSub, true));

  // cache hit rate + pp delta
  let hitSub = t('usage.noData');
  if (weekStats && weekStats.thisHit != null && weekStats.lastHit != null) {
    const pp = weekStats.thisHit - weekStats.lastHit;
    hitSub = (pp >= 0 ? '↑' : '↓') + fmtPct(Math.abs(pp)) + 'pp';
  }
  const hitCard = card(t('usage.cacheHitRate'),
    hit == null ? t('usage.noData') : t('usage.hitRateUnit', { pct: fmtPct(hit) }),
    hit == null ? t('usage.cacheHitRateNoData') : t('usage.cacheHitRateHint'),
    hitSub, true);
  cards.push(hitCard);

  cards.push(card(t('usage.topAgent'), topAgent || t('usage.noData'), '', topAgent ? fmtTokens(topAgentCost) : ''));
  cards.push(card(t('usage.topModel'), topModel || t('usage.noData'), '', topModel ? fmtTokens(topModelCost) : ''));

  for (const c of cards) wrap.appendChild(c);
}
function card(label, value, title, sub, trend) {
  const c = el('div', 'ud-card');
  c.setAttribute('role', 'group');
  c.setAttribute('aria-label', label + ' ' + value);
  const l = el('div', 'ud-card-label', label);
  const v = el('div', 'ud-card-value', value);
  if (title) c.title = title;
  c.append(l, v);
  if (sub) {
    const s = el('div', 'ud-card-sub' + (trend ? ' ud-trend' : ''), sub);
    c.appendChild(s);
  }
  return c;
}
function renderSummaryError() {
  const wrap = document.getElementById('usage-summary');
  if (!wrap) return;
  wrap.innerHTML = '';
  const c = el('div', 'ud-card');
  const l = el('div', 'ud-card-label', t('usage.loadError'));
  const retry = el('button', 'glass-control cfg-btn cfg-btn-sm ud-retry', t('usage.retry'));
  retry.addEventListener('click', () => reloadAll());
  c.append(l, retry);
  wrap.appendChild(c);
}

// ── heatmap ─────────────────────────────────────────────────────────
/** v1.3 thresholds (ruling ②): orders of magnitude 0 / 1M / 10M / 100M. */
function costLevel(v) {
  if (v <= 0) return 0;
  if (v < 1e6) return 1;
  if (v < 1e7) return 2;
  if (v < 1e8) return 3;
  return 4;
}
function hitLevel(b) {
  if (!hasCacheData(b)) return 0;
  const hr = hitRateOf(b) || 0;
  if (hr < 25) return 1;
  if (hr < 50) return 2;
  if (hr < 75) return 3;
  return 4;
}

function renderHeatmap() {
  const grid = document.getElementById('usage-grid');
  const months = document.getElementById('usage-months');
  const legend = document.getElementById('usage-legend');
  if (!grid || !months || !legend) return;
  grid.innerHTML = '';
  months.innerHTML = '';
  legend.innerHTML = '';

  if (windowKeys.length === 0) return;
  const fromMs = keyToMs(windowKeys[0]);
  const toMs = keyToMs(windowKeys[windowKeys.length - 1]);
  // align to weeks: Sunday-first columns (window already Sunday-aligned, but
  // keep the generic padding for safety)
  const first = new Date(fromMs);
  const leadPad = first.getDay(); // 0=Sun
  const last = new Date(toMs);
  const trailPad = 6 - last.getDay();

  const cells = [];
  for (let i = 0; i < leadPad; i++) cells.push(null);
  for (const k of windowKeys) cells.push(k);
  for (let i = 0; i < trailPad; i++) cells.push(null);

  const weeks = [];
  for (let w = 0; w < cells.length; w += 7) weeks.push(cells.slice(w, w + 7));

  let lastMonth = -1;
  weeks.forEach((week, wi) => {
    const weekEl = el('div', 'ud-week');
    weekEl.setAttribute('role', 'row');
    // month label: month of the week's first in-window day
    let monthTxt = '';
    for (const k of week) {
      if (k) { const m = new Date(keyToMs(k)).getMonth(); if (m !== lastMonth) { monthTxt = new Intl.DateTimeFormat(getLocale() === 'en' ? 'en-US' : 'zh-CN', { month: 'short' }).format(keyToMs(k)); lastMonth = m; } break; }
    }
    const mspan = el('span', '', monthTxt);
    months.appendChild(mspan);
    week.forEach((k) => {
      if (!k) {
        const pad = el('span', 'ud-cell ud-pad');
        pad.setAttribute('role', 'gridcell');
        weekEl.appendChild(pad);
        return;
      }
      const b = activeDayData.get(k);
      const cell = el('button', 'ud-cell');
      cell.dataset.date = k;
      cell.setAttribute('role', 'gridcell');
      const level = colorMode === 'cost' ? costLevel(b ? totalOf(b) : 0) : hitLevel(b || { inputTokens: 0, cacheReadTokens: 0 });
      cell.classList.add(colorMode === 'cost' ? 'level-' + level : 'hit-' + level);
      cell.setAttribute('tabindex', '-1');
      if (k === curveDate) cell.classList.add('ud-active');
      // aria-label = tooltip content (spec §7.2)
      cell.setAttribute('aria-label', cellAria(k, b));
      weekEl.appendChild(cell);
    });
    grid.appendChild(weekEl);
  });
  // roving tabindex: first cell focusable
  const firstCell = grid.querySelector('.ud-cell[data-date]');
  if (firstCell) firstCell.setAttribute('tabindex', '0');

  // GitHub convention: initial view shows the most recent weeks — scroll the
  // year strip to the right edge on every render (dimension switches too).
  // Deferred to the next frame: scrollWidth is only accurate after layout
  // settles (synchronous assignment raced the first layout pass).
  const scroll = document.getElementById('usage-heatscroll');
  if (scroll) requestAnimationFrame(() => { scroll.scrollLeft = scroll.scrollWidth; });

  // legend
  const labels = colorMode === 'cost' ? t('usage.legend.cost').split(' · ') : t('usage.legend.hitRate').split(' · ');
  for (let i = 0; i < 5; i++) {
    const row = el('div', 'ud-legend-row');
    const sw = el('span', 'ud-legend-swatch');
    sw.style.background = legendColor(i);
    if (colorMode === 'hitRate' && i === 0) {
      sw.style.backgroundImage = 'repeating-linear-gradient(45deg, transparent 0 2px, rgba(128,128,128,0.08) 2px 4px)';
    }
    const lb = el('span', 'ud-legend-label', labels[i] || '');
    row.append(sw, lb);
    legend.appendChild(row);
  }

  // dimension label (ruling ⑤): which dimension value colors the heatmap
  const dimLabel = document.getElementById('usage-dimlabel');
  if (dimLabel) {
    if (heatDim !== 'total' && heatDimValue) {
      dimLabel.hidden = false;
      dimLabel.textContent = t('usage.heatDimLabel', { dim: t('usage.heatDim.' + heatDim), value: heatDimValue });
    } else {
      dimLabel.hidden = true;
      dimLabel.textContent = '';
    }
  }
}
function legendColor(i) {
  const alphas = [0, 0.15, 0.32, 0.55, 0.85];
  if (i === 0) return 'var(--glass-etched-bg)';
  return `rgba(91, 127, 191, ${alphas[i]})`;
}
function cellAria(k, b) {
  const dateStr = fmtDateLong(keyToMs(k));
  const tokens = fmtTokens(b ? totalOf(b) : 0);
  const calls = b ? (b.count || 0) : 0;
  if (b && hasCacheData(b)) {
    const hr = hitRateOf(b);
    return t('usage.heatmapCell', { date: dateStr, tokens, calls, hitRate: hr == null ? t('usage.noData') : fmtPct(hr) + '%' });
  }
  return t('usage.heatmapCellNoCache', { date: dateStr, tokens, calls });
}

// ── tooltip ─────────────────────────────────────────────────────────
function showTip(cell, e) {
  const tip = document.getElementById('usage-tooltip');
  if (!tip) return;
  const k = cell.dataset.date || '';
  const b = activeDayData.get(k);
  tip.innerHTML = '';
  const d = new Date(keyToMs(k));
  const dateStr = new Intl.DateTimeFormat(getLocale() === 'en' ? 'en-US' : 'zh-CN', { month: 'short', day: 'numeric' }).format(d);
  const line1 = el('div', 'tt-line', dateStr + ' · ' + fmtTokens(b ? totalOf(b) : 0) + ' · ' + (b ? b.count || 0 : 0) + '×');
  tip.appendChild(line1);
  if (b) {
    const line2 = el('div', 'tt-line tt-muted',
      t('usage.input') + ' ' + fmtTokens(b.inputTokens || 0) + ' · ' + t('usage.output') + ' ' + fmtTokens(b.outputTokens || 0));
    tip.appendChild(line2);
    if (hasCacheData(b)) {
      const hr = hitRateOf(b);
      const line3 = el('div', 'tt-line tt-muted', t('usage.hitRate') + ' ' + (hr == null ? t('usage.noData') : fmtPct(hr) + '%'));
      tip.appendChild(line3);
    }
  }
  tip.hidden = false;
  moveTip(e);
}
function moveTip(e) {
  const tip = document.getElementById('usage-tooltip');
  if (!tip) return;
  const x = Math.min(e.clientX + 14, window.innerWidth - 270);
  const y = Math.min(e.clientY + 14, window.innerHeight - 90);
  tip.style.left = x + 'px';
  tip.style.top = y + 'px';
}
function hideTip() {
  const tip = document.getElementById('usage-tooltip');
  if (tip) tip.hidden = true;
}

// ── intraday curve (ruling ③) ───────────────────────────────────────
function toggleCurve(dateKey, cell) {
  if (!dateKey) return;
  if (curveDate === dateKey) { closeCurve(); return; }
  curveDate = dateKey;
  document.querySelectorAll('#usage-grid .ud-cell.ud-active').forEach((c) => c.classList.remove('ud-active'));
  cell?.classList.add('ud-active');
  const curve = document.getElementById('usage-curve');
  if (curve) curve.hidden = false;
  loadCurve(dateKey);
}
function closeCurve() {
  curveDate = null;
  closeDrill();
  const curve = document.getElementById('usage-curve');
  if (curve) curve.hidden = true;
  document.querySelectorAll('#usage-grid .ud-cell.ud-active').forEach((c) => c.classList.remove('ud-active'));
}

/** Intraday 24h bar chart (lightweight SVG, spec §2.5.1). */
async function loadCurve(dateKey) {
  const curve = document.getElementById('usage-curve');
  if (!curve) return;
  const from = keyToMs(dateKey);
  const to = from + MS_DAY;
  const fp = filterParams();
  const dimFp = heatDim !== 'total' && heatDimValue ? { [heatDim]: heatDimValue } : {};
  curve.innerHTML = '';

  const header = el('div', 'ud-curve-header');
  const cTitle = el('span', 'ud-curve-title', t('usage.curveTitle', { date: fmtDateLong(from) }));
  cTitle.id = 'usage-curve-title';
  header.appendChild(cTitle);
  const drillBtn = el('button', 'glass-control cfg-btn cfg-btn-sm ud-drill-open', t('usage.drillOpen'));
  drillBtn.addEventListener('click', () => {
    if (drillOpen) closeDrill();
    else openDrill(dateKey);
  });
  header.appendChild(drillBtn);
  curve.appendChild(header);

  const body = el('div', 'ud-curve-body');
  body.id = 'usage-curve-body';
  curve.appendChild(body);

  try {
    const agg = await fetchAgg({ dim: 'hour', from, to, ...fp, ...dimFp });
    renderCurveBars(body, agg, dateKey);
  } catch (err) {
    body.appendChild(el('div', 'ud-curve-empty', t('usage.loadError')));
  }
}

function renderCurveBars(body, agg, dateKey) {
  const buckets = agg.buckets || [];
  if (buckets.length === 0) {
    body.appendChild(el('div', 'ud-curve-empty', t('usage.curveEmpty')));
    return;
  }
  /** @type {Map<number, any>} */
  const byHour = new Map();
  for (const b of buckets) {
    const h = parseInt(String(b.key).slice(11, 13) || '0', 10);
    byHour.set(h, b);
  }
  let max = 0;
  let peakHour = 0;
  for (const [h, b] of byHour) {
    const v = totalOf(b);
    if (v > max) { max = v; peakHour = h; }
  }

  const NS = 'http://www.w3.org/2000/svg';
  const W = 504, H = 132, BASE = 104, HEAD = 16; // 24 slots × 21px; HEAD = room for peak label
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', t('usage.curveTitle', { date: fmtDateLong(keyToMs(dateKey)) }));
  svg.classList.add('ud-curve-svg');

  const barW = 15, slot = 21;
  for (let h = 0; h < 24; h++) {
    const b = byHour.get(h);
    const v = b ? totalOf(b) : 0;
    const x = h * slot + 3;
    const rect = document.createElementNS(NS, 'rect');
    const bh = max > 0 ? Math.max(v > 0 ? 2 : 0, Math.round((v / max) * (BASE - HEAD))) : 0;
    rect.setAttribute('x', String(x));
    rect.setAttribute('y', String(BASE - bh));
    rect.setAttribute('width', String(barW));
    rect.setAttribute('height', String(bh));
    rect.setAttribute('rx', '2');
    rect.classList.add('ud-curve-bar');
    if (h === peakHour && max > 0) rect.classList.add('ud-curve-peak');
    rect.setAttribute('aria-label', t('usage.curveBar', {
      hour: String(h).padStart(2, '0'),
      tokens: fmtTokens(v),
      calls: String(b ? b.count || 0 : 0),
    }));
    svg.appendChild(rect);
  }

  // axis labels: 0/6/12/18/23
  for (const h of [0, 6, 12, 18, 23]) {
    const txt = document.createElementNS(NS, 'text');
    txt.setAttribute('x', String(h * slot + 3 + barW / 2));
    txt.setAttribute('y', String(BASE + 16));
    txt.setAttribute('text-anchor', 'middle');
    txt.classList.add('ud-curve-axis');
    txt.textContent = String(h).padStart(2, '0');
    svg.appendChild(txt);
  }

  // peak annotation above the tallest bar (HEAD room keeps it inside viewBox)
  if (max > 0) {
    const ptxt = document.createElementNS(NS, 'text');
    ptxt.setAttribute('x', String(Math.min(Math.max(peakHour * slot + 3 + barW / 2, 40), W - 40)));
    ptxt.setAttribute('y', String(BASE - (BASE - HEAD) - 6));
    ptxt.setAttribute('text-anchor', 'middle');
    ptxt.classList.add('ud-curve-peak-label');
    ptxt.textContent = fmtTokens(max);
    ptxt.setAttribute('aria-label', t('usage.curvePeak', { tokens: fmtTokens(max), hour: String(peakHour).padStart(2, '0') }));
    svg.appendChild(ptxt);
  }

  body.appendChild(svg);

  // hover tooltip on bars (delegate)
  svg.addEventListener('mousemove', (e) => {
    const target = /** @type {Element} */ (e.target);
    if (!target.classList?.contains('ud-curve-bar')) { hideTip(); return; }
    const tip = document.getElementById('usage-tooltip');
    if (!tip) return;
    const label = target.getAttribute('aria-label') || '';
    tip.innerHTML = '';
    tip.appendChild(el('div', 'tt-line', label));
    tip.hidden = false;
    moveTip(e);
  });
  svg.addEventListener('mouseleave', hideTip);
}

// ── drilldown (secondary panel, v1.3) ───────────────────────────────
function openDrill(dateKey) {
  drillOpen = true;
  const drill = document.getElementById('usage-drill');
  if (drill) drill.hidden = false;
  loadDrill(dateKey);
}
function closeDrill() {
  drillOpen = false;
  const drill = document.getElementById('usage-drill');
  if (drill) drill.hidden = true;
}

async function loadDrill(dateKey) {
  const drill = document.getElementById('usage-drill');
  if (!drill) return;
  const from = keyToMs(dateKey);
  const to = from + MS_DAY;
  const fp = filterParams();
  const dimFp = heatDim !== 'total' && heatDimValue ? { [heatDim]: heatDimValue } : {};
  drill.innerHTML = '';
  const header = el('div', 'ud-drill-header');
  const dTitle = el('span', '', t('usage.drilldownTitle', { date: fmtDateLong(from) }));
  dTitle.id = 'usage-drill-title';
  header.appendChild(dTitle);
  const dims = el('div', 'ud-drill-dims');
  dims.setAttribute('role', 'radiogroup');
  for (const [dim, key] of [['agent', 'usage.dim.agent'], ['provider', 'usage.dim.provider'], ['model', 'usage.dim.model']]) {
    const b = el('button', 'ud-dim', t(key));
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(dim === drillDim));
    b.addEventListener('click', () => {
      drillDim = dim;
      for (const r of dims.querySelectorAll('.ud-dim')) r.setAttribute('aria-checked', 'false');
      b.setAttribute('aria-checked', 'true');
      loadDrill(dateKey);
    });
    dims.appendChild(b);
  }
  header.appendChild(dims);
  const closeD = el('button', 'panel-btn', '');
  closeD.innerHTML = '<i data-lucide="x"></i>';
  closeD.title = t('usage.close');
  closeD.addEventListener('click', closeDrill);
  header.appendChild(closeD);
  drill.appendChild(header);
  createIconsIn(drill);

  const rankWrap = el('div', '');
  rankWrap.id = 'usage-rank';
  drill.appendChild(rankWrap);

  try {
    const agg = await fetchAgg({ dim: drillDim, from, to, ...fp, ...dimFp });
    const buckets = agg.buckets || [];
    if (buckets.length === 0) {
      drill.appendChild(el('div', 'ud-drill-empty', t('usage.drillEmpty')));
      return;
    }
    renderRank(rankWrap, buckets);
    renderTable(drill, buckets);
  } catch (err) {
    const errEl = el('div', 'ud-drill-error', t('usage.loadError'));
    const retry = el('button', 'glass-control cfg-btn cfg-btn-sm ud-retry', t('usage.retry'));
    retry.addEventListener('click', () => loadDrill(dateKey));
    errEl.appendChild(retry);
    drill.appendChild(errEl);
  }
}

function renderRank(wrap, buckets) {
  const withData = buckets.filter((b) => hasCacheData(b) && (b.inputTokens || 0) > 0);
  if (withData.length === 0) return;
  withData.sort((a, b) => (hitRateOf(b) || 0) - (hitRateOf(a) || 0));
  const top = withData.slice(0, 5);
  const bottom = withData.length > 5 ? withData.slice(-5).reverse() : [];
  const group = (title, rows) => {
    const g = el('div', '');
    g.appendChild(el('div', 'ud-rank-group-title', title));
    for (const b of rows) {
      const hr = hitRateOf(b) || 0;
      const row = el('div', 'ud-rank-row');
      const key = el('span', 'ud-rank-key', b.key);
      if (NO_CACHE_PROVIDERS.includes(String(b.key)) && drillDim === 'provider') key.title = t('usage.noCacheReported');
      const bar = el('div', 'ud-rank-bar');
      const fill = el('div', 'ud-rank-bar-fill');
      fill.style.width = Math.max(2, Math.min(100, hr)) + '%';
      bar.appendChild(fill);
      const val = el('span', 'ud-rank-val', fmtPct(hr) + '%');
      row.append(key, bar, val);
      g.appendChild(row);
    }
    wrap.appendChild(g);
  };
  group(t('usage.hitRateRankTop'), top);
  if (bottom.length) group(t('usage.hitRateRankBottom'), bottom);
}

function renderTable(drill, buckets) {
  const sorted = [...buckets].sort((a, b) => totalOf(b) - totalOf(a)).slice(0, 10);
  const table = document.createElement('table');
  table.className = 'ud-table';
  table.setAttribute('role', 'table');
  const thead = document.createElement('thead');
  const hr = document.createElement('tr');
  hr.setAttribute('role', 'row');
  const cols = ['key', t('usage.calls'), t('usage.input'), t('usage.output'), t('usage.cacheRead'), t('usage.cacheWrite'), t('usage.total'), t('usage.hitRate')];
  for (const c of cols) {
    const th = document.createElement('th');
    th.setAttribute('role', 'columnheader');
    th.textContent = c;
    if (c !== 'key') th.className = 'num';
    hr.appendChild(th);
  }
  thead.appendChild(hr);
  table.appendChild(thead);
  const tbody = document.createElement('tbody');
  for (const b of sorted) {
    const tr = document.createElement('tr');
    tr.setAttribute('role', 'row');
    const tdKey = document.createElement('td');
    tdKey.setAttribute('role', 'rowheader');
    tdKey.textContent = b.key;
    if (NO_CACHE_PROVIDERS.includes(String(b.key)) && drillDim === 'provider' && !hasCacheData(b)) tdKey.title = t('usage.noCacheReported');
    tr.appendChild(tdKey);
    const vals = [b.count || 0, b.inputTokens || 0, b.outputTokens || 0, b.cacheReadTokens || 0, b.cacheWriteTokens || 0, totalOf(b)];
    for (const v of vals) {
      const td = document.createElement('td');
      td.className = 'num';
      td.textContent = fmtTokens(v);
      tr.appendChild(td);
    }
    const tdH = document.createElement('td');
    tdH.className = 'num';
    if (!hasCacheData(b)) tdH.textContent = t('usage.noData');
    else {
      const h = hitRateOf(b);
      tdH.textContent = h == null ? t('usage.noData') : fmtPct(h) + '%';
      if (NO_CACHE_PROVIDERS.includes(String(b.key)) && drillDim === 'provider') tdH.title = t('usage.noCacheReported');
    }
    tr.appendChild(tdH);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  drill.appendChild(table);
}

// ── i18n ────────────────────────────────────────────────────────────
function refreshLocale() {
  if (!overlay) return;
  const title = document.getElementById('usage-title');
  if (title) title.textContent = t('usage.title');
  document.getElementById('usage-dashboard')?.setAttribute('aria-label', t('usage.title'));
  const closeBtn = document.getElementById('usage-close');
  if (closeBtn) { closeBtn.title = t('usage.close'); closeBtn.setAttribute('aria-label', t('usage.close')); }
  const btn = document.getElementById('usage-btn');
  if (btn) { btn.title = t('usage.activityEntryHint'); btn.setAttribute('aria-label', t('usage.activityEntry')); }
  // stats range + heat dims + colormode labels
  overlay.querySelectorAll('.ud-srange').forEach((b) => {
    const r = /** @type {HTMLElement} */ (b).dataset.range || '1w';
    /** @type {HTMLElement} */ (b).textContent = t('usage.stats.' + r);
  });
  const statsLabels = overlay.querySelectorAll('.ud-stats-label');
  statsLabels.forEach((n) => { /** @type {HTMLElement} */ (n).textContent = t('usage.statsRangeLabel'); });
  overlay.querySelectorAll('.ud-hdim').forEach((b) => {
    const d = /** @type {HTMLElement} */ (b).dataset.dim || 'total';
    /** @type {HTMLElement} */ (b).textContent = t('usage.heatDim.' + d);
  });
  overlay.querySelectorAll('.ud-cm').forEach((b) => {
    const m = /** @type {HTMLElement} */ (b).dataset.mode;
    /** @type {HTMLElement} */ (b).textContent = t(m === 'cost' ? 'usage.colorMode.cost' : 'usage.colorMode.hitRate');
  });
  const emptyEl = document.getElementById('usage-empty');
  if (emptyEl) {
    emptyEl.innerHTML = '';
    emptyEl.append(el('div', 'ud-empty-title', t('usage.empty')), el('div', 'ud-empty-hint', t('usage.emptyHint')));
  }
  renderSummary();
  renderHeatmap();
  if (curveDate) loadCurve(curveDate);
}

// ── init ────────────────────────────────────────────────────────────
export function initUsageDashboard() {
  const btn = document.getElementById('usage-btn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    if (isOpen) closeUsageDashboard();
    else openUsageDashboard();
  });
  btn.title = t('usage.activityEntryHint');
  btn.setAttribute('aria-label', t('usage.activityEntry'));
  window.addEventListener('locale-changed', () => { if (isOpen) refreshLocale(); else { btn.title = t('usage.activityEntryHint'); btn.setAttribute('aria-label', t('usage.activityEntry')); } });
}
