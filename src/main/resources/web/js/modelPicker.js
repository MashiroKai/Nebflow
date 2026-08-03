// modelPicker.js — Model selector with separated preferred + fallback chain.
//
// Collapsed chip: shows the runtime current model (state.currentModel),
// which is updated by WS modelChanged when a fallback kicks in.
//
// Expanded panel: two sections:
//   1. 「Preferred Model」— dropdown select (single), writes model.default
//   2. 「Fallback Chain」— draggable ordered list, writes model.fallbacks
//
// Data sources:
//   - state.parsedConfig.llm.model.{default, fallbacks}  → config
//   - state.currentModel  → runtime model (WS modelChanged, init = default)
//   - state.allModelRefs  → pickable models

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

/** Set the preferred model and remove it from fallbacks if present. */
function applyPreferred(ref) {
  ensureModelConfig();
  const old = preferredModel();
  state.parsedConfig.llm.model.default = ref;
  // Remove new preferred from fallbacks; add old preferred to fallbacks head
  const fbs = fallbackChain().filter(r => r !== ref);
  if (old && old !== ref) fbs.unshift(old);
  state.parsedConfig.llm.model.fallbacks = fbs;
  // Update runtime model if it was showing the old preferred
  if (!state.currentModel || state.currentModel === old) {
    state.currentModel = ref;
  }
  flushConfigToServer();
  render();
}

/** Persist a new fallback order. */
function applyFallbacks(newFbs) {
  ensureModelConfig();
  state.parsedConfig.llm.model.fallbacks = newFbs;
  flushConfigToServer();
  render();
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
function bindPanel(pref, fbs) {
  const panel = document.getElementById('mp-panel');
  if (!panel) return;

  panel.addEventListener('click', (e) => {
    e.stopPropagation();
    if (e.target.closest('#mp-close')) { expanded = false; render(); return; }
    const rm = e.target.closest('.mp-remove');
    if (rm) {
      const idx = Number(rm.getAttribute('data-idx'));
      const next = fbs.slice();
      next.splice(idx, 1);
      applyFallbacks(next);
      return;
    }
  });

  panel.addEventListener('change', (e) => {
    if (e.target.id === 'mp-preferred-select') {
      e.stopPropagation();
      const ref = e.target.value;
      if (ref) applyPreferred(ref);
      return;
    }
    if (e.target.id === 'mp-add-select') {
      e.stopPropagation();
      const ref = e.target.value;
      if (!ref) return;
      applyFallbacks([...fbs, ref]);
      return;
    }
  });
}

function render() {
  if (!mountEl) return;
  bindDelegate();

  const runtime = currentRuntimeModel();
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

  // Fallback rows
  const fbItems = fbs.map((ref, i) => `
    <div class="mp-row" draggable="true" data-idx="${i}">
      <span class="mp-grip" title="drag to reorder">⠿</span>
      <span class="mp-pos">${i + 1}</span>
      <span class="mp-row-label">${esc(shortLabel(ref))}</span>
      <span class="mp-row-provider">${esc(providerLabel(ref))}</span>
      <span class="mp-remove" data-idx="${i}" title="${t('modelPicker.remove')}">×</span>
    </div>`).join('');

  // Available for fallback add (exclude preferred and existing fallbacks)
  const available = allModelRefs().filter(r => r !== pref && !fbs.includes(r));
  const addOptions = available.map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('');

  const panelHtml = `
    <div class="mp-panel" id="mp-panel">
      <div class="mp-panel-head">
        <span class="mp-panel-title">${t('modelPicker.chain')}</span>
        <span class="mp-close" id="mp-close">✕</span>
      </div>
      <div class="mp-section">
        <label class="mp-section-label">${t('modelPicker.preferred')}</label>
        <div class="mp-preferred-wrap">
          <select id="mp-preferred-select" class="mp-select">
            ${pref ? `<option value="${esc(pref)}" selected>${esc(pref)}</option>` : `<option value="" selected>${t('modelPicker.select')}</option>`}
            ${allModelRefs().filter(r => r !== pref).map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="mp-section">
        <label class="mp-section-label">${t('modelPicker.fallbacks')}</label>
        <div class="mp-rows" id="mp-rows">${fbItems || `<div class="mp-empty">${t('modelPicker.fallbackEmpty')}</div>`}</div>
        ${available.length ? `<div class="mp-add"><select id="mp-add-select"><option value="">${t('modelPicker.add')}</option>${addOptions}</select></div>` : ''}
      </div>
      <div class="mp-hint">${t('modelPicker.hint')}</div>
    </div>`;

  container.insertAdjacentHTML('beforeend', panelHtml);
  positionPanel();

  bindPanel(pref, fbs);
  setupDragReorder(fbs);
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

function setupDragReorder(fbs) {
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
      const next = fbs.slice();
      const [moved] = next.splice(dragIdx, 1);
      next.splice(overIdx, 0, moved);
      applyFallbacks(next);
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
