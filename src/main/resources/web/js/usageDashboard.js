// usageDashboard.js — #310 Token usage dashboard (spec token-dashboard-spec.md
// v1.2.1). GitHub-style day×week heatmap + summary cards + drilldown drawer.
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

/** Providers known not to report cache fields (spec §6.9c). */
const NO_CACHE_PROVIDERS = ['kimi'];

const MS_DAY = 86400000;

// ── module state ────────────────────────────────────────────────────
let overlay = null;
let isOpen = false;
let rangeDays = 30;
let customFrom = ''; // yyyy-mm-dd
let customTo = '';
/** @type {{provider: string, model: string, agent: string}} */
let filters = { provider: '', model: '', agent: '' };
let colorMode = 'cost'; // 'cost' | 'hitRate'
/** @type {Map<string, any>} */
let dayData = new Map();
/** @type {string[]} ordered date keys inside the window */
let windowKeys = [];
let drillDate = null; // 'YYYY-MM-DD' or null
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
function windowRange() {
  const to = startOfToday() + MS_DAY; // exclusive upper = tomorrow 00:00
  if (rangeDays === 0) {
    const f = customFrom ? keyToMs(customFrom) : to - 30 * MS_DAY;
    const t2 = customTo ? keyToMs(customTo) + MS_DAY : to;
    return { from: f, to: t2 };
  }
  return { from: to - rangeDays * MS_DAY, to };
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

  // summary cards
  const summary = el('div', '');
  summary.id = 'usage-summary';
  summary.setAttribute('role', 'group');

  // controls
  const controls = el('div', 'ud-controls');
  const filtersWrap = el('div', 'ud-filters');
  for (const dim of ['provider', 'model', 'agent']) {
    const sel = document.createElement('select');
    sel.className = 'ud-select glass-control';
    sel.id = 'usage-filter-' + dim;
    sel.setAttribute('aria-label', t('usage.dim.' + dim));
    sel.addEventListener('change', () => {
      filters[dim] = sel.value;
      loadMain();
    });
    filtersWrap.appendChild(sel);
  }
  const ranges = el('div', 'ud-ranges');
  ranges.setAttribute('role', 'radiogroup');
  ranges.setAttribute('aria-label', t('usage.range.30'));
  for (const [days, key] of [[7, 'usage.range.7'], [30, 'usage.range.30'], [90, 'usage.range.90'], [0, 'usage.range.custom']]) {
    const b = el('button', 'ud-range', t(key));
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(days === rangeDays));
    b.dataset.days = String(days);
    b.addEventListener('click', () => {
      for (const r of ranges.querySelectorAll('.ud-range')) r.setAttribute('aria-checked', 'false');
      b.setAttribute('aria-checked', 'true');
      rangeDays = Number(days);
      customWrap.hidden = Number(days) !== 0;
      loadMain();
    });
    ranges.appendChild(b);
  }
  const customWrap = el('div', 'ud-custom-range');
  customWrap.hidden = true;
  const dFrom = document.createElement('input');
  dFrom.type = 'date'; dFrom.className = 'ud-date glass-control'; dFrom.id = 'usage-from';
  const dSep = el('span', 'ud-date-sep', '–');
  const dTo = document.createElement('input');
  dTo.type = 'date'; dTo.className = 'ud-date glass-control'; dTo.id = 'usage-to';
  const applyCustom = () => {
    customFrom = dFrom.value; customTo = dTo.value;
    if (customFrom && customTo) {
      const span = (keyToMs(customTo) - keyToMs(customFrom)) / MS_DAY;
      if (span > 365) { window.__showToast?.(t('usage.range.tooLong'), 'error'); return; }
      loadMain();
    }
  };
  dFrom.addEventListener('change', applyCustom);
  dTo.addEventListener('change', applyCustom);
  customWrap.append(dFrom, dSep, dTo);

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
  controls.append(filtersWrap, ranges, customWrap, colorModeWrap);

  // heatmap area
  const heatwrap = el('div', '');
  heatwrap.id = 'usage-heatwrap';
  const heatmain = el('div', '');
  heatmain.id = 'usage-heatmain';
  const months = el('div', '');
  months.id = 'usage-months';
  const grid = el('div', '');
  grid.id = 'usage-grid';
  grid.setAttribute('role', 'grid');
  grid.setAttribute('aria-label', t('usage.heatmapLabel'));
  heatmain.append(months, grid);
  const legend = el('div', '');
  legend.id = 'usage-legend';
  heatwrap.append(heatmain, legend);

  const empty = el('div', 'ud-empty');
  empty.id = 'usage-empty';
  empty.hidden = true;
  empty.append(el('div', 'ud-empty-title', t('usage.empty')), el('div', 'ud-empty-hint', t('usage.emptyHint')));

  // drilldown drawer
  const drill = el('div', 'ud-drill');
  drill.id = 'usage-drill';
  drill.hidden = true;
  drill.setAttribute('aria-live', 'polite');

  // tooltip
  const tip = el('div', 'ud-tooltip');
  tip.id = 'usage-tooltip';
  tip.hidden = true;

  panel.append(header, summary, controls, heatwrap, empty, drill);
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
    if (cell) toggleDrill(cell.dataset.date || null, cell);
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
  loadMain();
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
    if (drillDate) closeDrill();
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
  else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleDrill(cell.dataset.date || null, cell); return; }
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
async function loadMain() {
  if (!overlay) return;
  const heatwrap = document.getElementById('usage-heatwrap');
  heatwrap?.classList.add('ud-reloading');
  const { from, to } = windowRange();
  const fp = filterParams();
  const today = startOfToday();
  const wkStart = today - 6 * MS_DAY; // rolling 7-day week incl. today
  try {
    const [days, todayH, yestH, thisW, lastW, agents, models] = await Promise.all([
      fetchAgg({ dim: 'day', from, to, ...fp }),
      fetchAgg({ dim: 'hour', from: today, to: today + MS_DAY, ...fp }),
      fetchAgg({ dim: 'hour', from: today - MS_DAY, to: today, ...fp }),
      fetchAgg({ from: wkStart, to: today + MS_DAY, ...fp }),
      fetchAgg({ from: wkStart - 7 * MS_DAY, to: wkStart, ...fp }),
      fetchAgg({ dim: 'agent', from, to, ...fp }),
      fetchAgg({ dim: 'model', from, to, ...fp }),
    ]);
    mainFailCount = 0;
    lastTotals = days;
    dayData = new Map();
    for (const b of days.buckets || []) dayData.set(b.key, b);
    windowKeys = [];
    for (let ms = from; ms < to; ms += MS_DAY) windowKeys.push(dateKeyOf(ms));

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
    renderHeatmap();
    const emptyEl = document.getElementById('usage-empty');
    if (emptyEl) emptyEl.hidden = (days.count || 0) > 0;
    if (drillDate) loadDrill(drillDate);
  } catch (err) {
    mainFailCount++;
    renderSummaryError();
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
  retry.addEventListener('click', () => loadMain());
  c.append(l, retry);
  wrap.appendChild(c);
}

// ── heatmap ─────────────────────────────────────────────────────────
function costLevel(v) {
  if (v <= 0) return 0;
  if (v < 1e4) return 1;
  if (v < 1e6) return 2;
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
  // align to weeks: Sunday-first columns
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
      const b = dayData.get(k);
      const cell = el('button', 'ud-cell');
      cell.dataset.date = k;
      cell.setAttribute('role', 'gridcell');
      const level = colorMode === 'cost' ? costLevel(b ? totalOf(b) : 0) : hitLevel(b || { inputTokens: 0, cacheReadTokens: 0 });
      cell.classList.add(colorMode === 'cost' ? 'level-' + level : 'hit-' + level);
      cell.setAttribute('tabindex', '-1');
      if (k === drillDate) cell.classList.add('ud-active');
      // aria-label = tooltip content (spec §7.2)
      cell.setAttribute('aria-label', cellAria(k, b));
      weekEl.appendChild(cell);
    });
    grid.appendChild(weekEl);
  });
  // roving tabindex: first cell focusable
  const firstCell = grid.querySelector('.ud-cell[data-date]');
  if (firstCell) firstCell.setAttribute('tabindex', '0');

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
}
function legendColor(i) {
  const alphas = [0, 0.15, 0.32, 0.55, 0.85];
  if (i === 0) return 'var(--glass-etched-bg)';
  return `rgba(91, 127, 191, ${alphas[i]})`;
}
function cellAria(k, b) {
  const d = new Date(keyToMs(k));
  const dateStr = new Intl.DateTimeFormat(getLocale() === 'en' ? 'en-US' : 'zh-CN', { month: 'long', day: 'numeric' }).format(d);
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
  const b = dayData.get(k);
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

// ── drilldown ───────────────────────────────────────────────────────
function toggleDrill(dateKey, cell) {
  if (!dateKey) return;
  if (drillDate === dateKey) { closeDrill(); return; }
  drillDate = dateKey;
  document.querySelectorAll('#usage-grid .ud-cell.ud-active').forEach((c) => c.classList.remove('ud-active'));
  cell?.classList.add('ud-active');
  const drill = document.getElementById('usage-drill');
  if (drill) drill.hidden = false;
  loadDrill(dateKey);
}
function closeDrill() {
  drillDate = null;
  const drill = document.getElementById('usage-drill');
  if (drill) drill.hidden = true;
  document.querySelectorAll('#usage-grid .ud-cell.ud-active').forEach((c) => c.classList.remove('ud-active'));
}

async function loadDrill(dateKey) {
  const drill = document.getElementById('usage-drill');
  if (!drill) return;
  const from = keyToMs(dateKey);
  const to = from + MS_DAY;
  const fp = filterParams();
  drill.innerHTML = '';
  const header = el('div', 'ud-drill-header');
  const d = new Date(from);
  const dateStr = new Intl.DateTimeFormat(getLocale() === 'en' ? 'en-US' : 'zh-CN', { month: 'long', day: 'numeric' }).format(d);
  const dTitle = el('span', '', t('usage.drilldownTitle', { date: dateStr }));
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
    const agg = await fetchAgg({ dim: drillDim, from, to, ...fp });
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
  // range + colormode + dim labels
  overlay.querySelectorAll('.ud-range').forEach((b) => {
    const days = Number(/** @type {HTMLElement} */ (b).dataset.days);
    const key = days === 0 ? 'usage.range.custom' : 'usage.range.' + days;
    /** @type {HTMLElement} */ (b).textContent = t(key);
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
