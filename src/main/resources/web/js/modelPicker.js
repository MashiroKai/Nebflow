// modelPicker.js — Unified model selector with drag-to-reorder priority chain.
//
// Collapsed chip: shows the runtime current model (state.currentModel),
// which is updated by WS modelChanged when a fallback kicks in.
//
// Expanded panel: single draggable list where position = priority.
//   [0] → model.default (preferred), [1:] → model.fallbacks
// A green dot marks the current runtime model row.

import state from './state.js';
import { sendWs } from './ws.js';
import { t } from './i18n.js';

let mountEl = null;
let expanded = false;

/** All pickable model refs known to the frontend. */
function allModelRefs() {
  if (state.allModelRefs && state.allModelRefs.length) return state.allModelRefs;
  const cfg = state.parsedConfig || {};
  const providers = cfg.llm?.providers || {};
  const refs = [];
  Object.keys(providers).forEach(pName => {
    (providers[pName].models || []).forEach(m => {
      refs.push(`${pName}/${m.id}`);
    });
  });
  state.allModelRefs = refs;
  return refs;
}

/** The preferred model ref (model.default, with backward compat). */
function preferredModel() {
  const model = state.parsedConfig?.llm?.model || {};
  return model.default || model.preferred || '';
}

/** The fallback chain (model.fallbacks), de-duplicated, excluding preferred. */
function fallbackChain() {
  const model = state.parsedConfig?.llm?.model || {};
  const fbs = Array.isArray(model.fallbacks) ? model.fallbacks : [];
  const pref = preferredModel();
  const seen = new Set();
  const chain = [];
  for (const r of fbs) {
    if (r && r !== pref && !seen.has(r)) { chain.push(r); seen.add(r); }
  }
  return chain;
}

/** Unified ordered list: [preferred, ...fallbacks]. */
function orderedModels() {
  const pref = preferredModel();
  const fbs = fallbackChain();
  return pref ? [pref, ...fbs] : fbs;
}

/** The runtime current model (what's actually being used right now).
 *  Falls back to preferred if WS hasn't told us otherwise. */
function currentRuntimeModel() {
  return state.currentModel || preferredModel();
}

function shortLabel(ref) {
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

function providerLabel(ref) {
  const idx = ref.indexOf('/');
  return idx >= 0 ? ref.slice(0, idx) : ref;
}

function flushConfigToServer() {
  const json = JSON.stringify(state.parsedConfig, null, 2);
  state.configText = json;
  sendWs({ type: 'updateConfig', config: json });
}

function ensureModelConfig() {
  if (!state.parsedConfig) state.parsedConfig = { llm: {} };
  if (!state.parsedConfig.llm) state.parsedConfig.llm = {};
  if (!state.parsedConfig.llm.model) state.parsedConfig.llm.model = {};
}

/** Persist a new ordered model list.
 *  [0] → model.default, [1:] → model.fallbacks. */
function applyOrderedModels(newOrder) {
  ensureModelConfig();
  const [first, ...rest] = newOrder;
  state.parsedConfig.llm.model.default = first || '';
  state.parsedConfig.llm.model.fallbacks = rest;
  flushConfigToServer();
  render();
}

/** Set the preferred model (internal helper for applyOrderedModels). */
function applyPreferred(ref) {
  const current = orderedModels();
  const filtered = current.filter(r => r !== ref);
  applyOrderedModels([ref, ...filtered]);
}

let delegateBound = false;

function bindDelegate() {
  if (delegateBound || !mountEl) return;
  delegateBound = true;
  mountEl.addEventListener('click', (e) => {
    e.stopPropagation();
    if (e.target.closest('#mp-toggle')) { expanded = true; render(); return; }
  });
}

/** Bind interactions on the floating panel. */
function bindPanel(models) {
  const panel = document.getElementById('mp-panel');
  if (!panel) return;

  panel.addEventListener('click', (e) => {
    e.stopPropagation();
    if (e.target.closest('#mp-close')) { expanded = false; render(); return; }
    const rm = e.target.closest('.mp-remove');
    if (rm) {
      const idx = Number(rm.getAttribute('data-idx'));
      const next = models.slice();
      next.splice(idx, 1);
      applyOrderedModels(next);
      return;
    }
  });

  panel.addEventListener('change', (e) => {
    if (e.target.id === 'mp-add-select') {
      e.stopPropagation();
      const ref = e.target.value;
      if (!ref) return;
      applyOrderedModels([...models, ref]);
      return;
    }
  });
}

function render() {
  if (!mountEl) return;
  bindDelegate();

  const runtime = currentRuntimeModel();
  const models = orderedModels();
  const pref = preferredModel();
  const isFallback = runtime && pref && runtime !== pref;

  // ── Chip (always rendered) ──
  const chipHtml = `
    <button class="mp-chip mp-active${expanded ? ' mp-open' : ''}" id="mp-toggle" title="${t('modelPicker.changeModel')}">
      <span class="mp-dot${isFallback ? ' mp-dot-fallback' : ''}"></span>${runtime ? `<span class="mp-provider">${esc(providerLabel(runtime))}</span><span class="mp-label">${esc(shortLabel(runtime))}</span>${isFallback ? `<span class="mp-fallback-tag">${t('modelPicker.usingFallback')}</span>` : ''}` : `<span class="mp-label">${t('modelPicker.select')}</span>`}
    </button>`;

  mountEl.innerHTML = chipHtml;
  removePanel();

  if (!expanded) return;

  // ── Panel ──
  const container = document.getElementById('top-overlays') || document.body;

  // Unified drag rows — green dot on runtime model
  const rowItems = models.map((ref, i) => {
    const isPlaying = ref === runtime;
    return `
    <div class="mp-row${isPlaying ? ' mp-playing' : ''}" draggable="true" data-idx="${i}">
      ${isPlaying ? '<span class="mp-playing-dot"></span>' : '<span class="mp-grip" title="drag to reorder">⠿</span>'}
      <span class="mp-pos">${i === 0 ? '★' : i}</span>
      <span class="mp-row-label">${esc(shortLabel(ref))}</span>
      <span class="mp-row-provider">${esc(providerLabel(ref))}</span>
      <span class="mp-remove" data-idx="${i}" title="${t('modelPicker.remove')}">×</span>
    </div>`;
  }).join('');

  // Available models not yet in the chain
  const available = allModelRefs().filter(r => !models.includes(r));
  const addOptions = available.map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('');

  const panelHtml = `
    <div class="mp-panel" id="mp-panel">
      <div class="mp-panel-head">
        <span class="mp-panel-title">${t('modelPicker.chain')}</span>
        <span class="mp-close" id="mp-close">✕</span>
      </div>
      <div class="mp-rows" id="mp-rows">${rowItems || `<div class="mp-empty">${t('modelPicker.empty')}</div>`}</div>
      ${available.length ? `<div class="mp-add"><select id="mp-add-select"><option value="">${t('modelPicker.add')}</option>${addOptions}</select></div>` : ''}
      <div class="mp-hint">${t('modelPicker.hint')}</div>
    </div>`;

  container.insertAdjacentHTML('beforeend', panelHtml);
  positionPanel();

  bindPanel(models);
  setupDragReorder(models);
}

function positionPanel() {
  const panel = document.getElementById('mp-panel');
  const chip = mountEl?.querySelector('#mp-toggle');
  if (!panel || !chip) return;
  const chipRect = chip.getBoundingClientRect();
  panel.style.position = 'fixed';
  panel.style.top = `${chipRect.bottom + 6}px`;
  panel.style.left = `${chipRect.left}px`;
  panel.style.right = 'auto';
  panel.style.bottom = 'auto';
}

function removePanel() {
  document.getElementById('mp-panel')?.remove();
}

function setupDragReorder(models) {
  const rowsEl = document.getElementById('mp-rows');
  if (!rowsEl) return;
  let dragIdx = null;

  rowsEl.querySelectorAll('.mp-row').forEach(row => {
    row.addEventListener('dragstart', (e) => {
      dragIdx = Number(row.getAttribute('data-idx'));
      row.classList.add('mp-dragging');
      e.dataTransfer.effectAllowed = 'move';
    });
    row.addEventListener('dragend', () => {
      row.classList.remove('mp-dragging');
      dragIdx = null;
      rowsEl.querySelectorAll('.mp-row').forEach(r => r.classList.remove('mp-over'));
    });
    row.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      rowsEl.querySelectorAll('.mp-row').forEach(r => r.classList.remove('mp-over'));
      row.classList.add('mp-over');
    });
    row.addEventListener('drop', (e) => {
      e.preventDefault();
      const overIdx = Number(row.getAttribute('data-idx'));
      if (dragIdx === null || dragIdx === overIdx) return;
      const next = models.slice();
      const [moved] = next.splice(dragIdx, 1);
      next.splice(overIdx, 0, moved);
      applyOrderedModels(next);
    });
  });
}

/** Insert the picker into the input bar (right side). Idempotent. */
export function initModelPicker() {
  const existing = document.getElementById('model-picker');
  if (mountEl || existing) { mountEl = mountEl || existing; return; }
  const headerLeft = document.querySelector('.header-left');
  if (!headerLeft) return;
  mountEl = document.createElement('div');
  mountEl.id = 'model-picker';
  const ctxInfo = document.getElementById('header-model-info');
  if (ctxInfo) ctxInfo.after(mountEl);
  else headerLeft.appendChild(mountEl);
  document.addEventListener('click', (e) => {
    if (!expanded) return;
    const panel = document.getElementById('mp-panel');
    if (!mountEl?.contains(e.target) && !panel?.contains(e.target)) {
      expanded = false; try { render(); } catch (_) {}
    }
  });
  window.addEventListener('resize', () => { if (expanded) positionPanel(); });
  try { render(); }
  catch (e) { console.error('[modelPicker] init render failed:', e); }
}

/** Re-render after config / model list changes. */
export function refreshModelPicker() {
  try { render(); } catch (e) { console.error('[modelPicker] refresh failed:', e); }
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
