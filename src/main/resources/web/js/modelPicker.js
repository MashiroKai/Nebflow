// modelPicker.js — Draggable model chain selector on the input bar's right side.
//
// Replaces the /model slash command and the Settings "Model Chain" editor.
// The chain = [default(current) model, fallback1, fallback2, ...]. Drag to
// reorder: position 0 is the active model, the rest are the fallback order.
// On any reorder, the chain is written to state.parsedConfig.llm.model and
// flushed to the server via the existing updateConfig WS (hot-reload).
//
// Data sources:
//   - state.parsedConfig.llm.model.{default, fallbacks}  → current chain
//   - state.allModelRefs (filled from modelOptions / config providers) → pickable models

import state from './state.js';
import { sendWs } from './ws.js';
import { t } from './i18n.js';

let mountEl = null;
let expanded = false;

/** All pickable model refs known to the frontend. */
function allModelRefs() {
  if (state.allModelRefs && state.allModelRefs.length) return state.allModelRefs;
  // Derive from parsedConfig providers.
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

/** The current ordered chain (default + fallbacks), de-duplicated. */
function currentChain() {
  const model = state.parsedConfig?.llm?.model || {};
  const def = model.default || '';
  const fbs = Array.isArray(model.fallbacks) ? model.fallbacks : [];
  const chain = [];
  const seen = new Set();
  for (const r of [def, ...fbs]) {
    if (r && !seen.has(r)) { chain.push(r); seen.add(r); }
  }
  return chain;
}

function shortLabel(ref) {
  // "Zai/GLM-5.2" → "GLM-5.2"; keep full if no slash.
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

function providerLabel(ref) {
  // "Zai/GLM-5.2" → "Zai"; keep full if no slash.
  const idx = ref.indexOf('/');
  return idx >= 0 ? ref.slice(0, idx) : ref;
}

function flushConfigToServer() {
  const json = JSON.stringify(state.parsedConfig, null, 2);
  state.configText = json;
  sendWs({ type: 'updateConfig', config: json });
}

/** Persist a new chain order: index 0 = default, rest = fallbacks. */
function applyChain(newChain) {
  if (!state.parsedConfig) state.parsedConfig = { llm: {} };
  if (!state.parsedConfig.llm) state.parsedConfig.llm = {};
  if (!state.parsedConfig.llm.model) state.parsedConfig.llm.model = {};
  state.parsedConfig.llm.model.default = newChain[0] || '';
  state.parsedConfig.llm.model.fallbacks = newChain.slice(1);
  flushConfigToServer();
  render();
}

let delegateBound = false;

function bindDelegate() {
  if (delegateBound || !mountEl) return;
  delegateBound = true;
  // Delegated listener on the chip mount (survives innerHTML re-renders of
  // the chip). The panel is rendered separately in #input-area and gets its
  // own bindings in render() (see bindPanel).
  // NOTE: any click handled here MUST stopPropagation, because render() may
  // replace the clicked node (detaching e.target) before the event reaches
  // the document-level outside-click handler — which would otherwise mistake
  // the now-detached target as "outside" and immediately re-collapse.
  mountEl.addEventListener('click', (e) => {
    e.stopPropagation();
    if (e.target.closest('#mp-toggle')) { expanded = true; render(); return; }
  });
}

/** Bind interactions on the floating panel (mounted in #input-area). The
 *  panel is recreated on every expand, so bind fresh each time. */
function bindPanel(chain) {
  const panel = document.getElementById('mp-panel');
  if (!panel) return;
  panel.addEventListener('click', (e) => {
    e.stopPropagation();
    if (e.target.closest('#mp-close')) { expanded = false; render(); return; }
    const rm = e.target.closest('.mp-remove');
    if (rm) {
      const idx = Number(rm.getAttribute('data-idx'));
      const next = chain.slice();
      next.splice(idx, 1);
      if (next.length === 0) return; // keep at least one
      applyChain(next);
      return;
    }
    // Clicking a row (not its remove button) — no-op for now.
  });
  panel.addEventListener('change', (e) => {
    if (e.target.id === 'mp-add-select') {
      e.stopPropagation();
      const ref = e.target.value;
      if (!ref) return;
      applyChain([...currentChain(), ref]);
    }
  });
}

function render() {
  if (!mountEl) return;
  bindDelegate();
  const chain = currentChain();
  const first = chain[0];

  // The chip is ALWAYS rendered (collapsed or expanded) so it does not
  // visually "jump" or disappear when the panel opens. When expanded, the
  // panel is rendered as a separate element inside #input-area (a sibling of
  // #input-bar) — NOT inside #model-picker — so its backdrop-filter can blur
  // the chat content behind it instead of being trapped inside #input-bar's
  // own backdrop root (which would make it look nearly transparent).
  const chipHtml = `
    <button class="mp-chip mp-active${expanded ? ' mp-open' : ''}" id="mp-toggle" title="${t('modelPicker.changeModel') || 'Change model / fallback chain'}">
      <span class="mp-dot"></span>${first ? `<span class="mp-provider">${esc(providerLabel(first))}</span><span class="mp-label">${esc(shortLabel(first))}</span>` : `<span class="mp-label">${t('modelPicker.select') || 'select'}</span>`}
    </button>`;

  // Always render the chip into the picker mount.
  mountEl.innerHTML = chipHtml;

  // Remove any previously-rendered panel (from a prior expand or a re-render).
  removePanel();

  if (!expanded) {
    return;
  }

  // Expanded: build the panel and mount it on document.body with fixed
  // positioning so it floats above everything, anchored to the chip.
  const container = document.getElementById('top-overlays') || document.body;

  const items = chain.map((ref, i) => `
    <div class="mp-row" draggable="true" data-idx="${i}">
      <span class="mp-grip" title="drag to reorder">⠿</span>
      <span class="mp-pos">${i === 0 ? (t('modelPicker.active') || 'active') : i}</span>
      <span class="mp-row-label">${esc(shortLabel(ref))}</span>
      <span class="mp-row-provider">${esc(providerLabel(ref))}</span>
      <span class="mp-remove" data-idx="${i}" title="${t('modelPicker.remove') || 'remove'}">×</span>
    </div>`).join('');

  const available = allModelRefs().filter(r => !chain.includes(r));
  const addOptions = available.map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('');

  const panelHtml = `
    <div class="mp-panel" id="mp-panel">
      <div class="mp-panel-head">
        <span class="mp-panel-title">${t('modelPicker.chain') || 'Model & fallback chain'}</span>
        <span class="mp-close" id="mp-close">✕</span>
      </div>
      <div class="mp-rows" id="mp-rows">${items || `<div class="mp-empty">${t('modelPicker.empty') || 'No models'}</div>`}</div>
      ${available.length ? `<div class="mp-add"><select id="mp-add-select"><option value="">${t('modelPicker.add') || '+ add model…'}</option>${addOptions}</select></div>` : ''}
      <div class="mp-hint">${t('modelPicker.hint') || 'First = active model; order below = fallback order. Drag ⠿ to reorder.'}</div>
    </div>`;

  // Attach panel to the container, then position it relative to the chip.
  container.insertAdjacentHTML('beforeend', panelHtml);
  positionPanel();

  // Bind panel interactions + drag-to-reorder (fresh each expand).
  bindPanel(chain);
  setupDragReorder(chain);
}

/** Position the floating panel below the chip. Uses fixed positioning so
 *  the panel tracks the chip regardless of scroll or container. */
function positionPanel() {
  const panel = document.getElementById('mp-panel');
  const chip = mountEl?.querySelector('#mp-toggle');
  if (!panel || !chip) return;
  const chipRect = chip.getBoundingClientRect();
  // Drop down from the chip, left-aligned to the chip's left edge.
  panel.style.position = 'fixed';
  panel.style.top = `${chipRect.bottom + 6}px`;
  panel.style.left = `${chipRect.left}px`;
  panel.style.right = 'auto';
  panel.style.bottom = 'auto';
}

/** Remove the floating panel from #input-area (idempotent). */
function removePanel() {
  document.getElementById('mp-panel')?.remove();
}

function setupDragReorder(chain) {
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
      const next = chain.slice();
      const [moved] = next.splice(dragIdx, 1);
      next.splice(overIdx, 0, moved);
      applyChain(next);
    });
  });
}

/** Insert the picker into the input bar (right side). Idempotent.
 *  Mount is created first so that even if render() throws, the element exists
 *  and later refreshModelPicker() can recover. */
export function initModelPicker() {
  // Idempotent across module instances: if #model-picker already exists in the
  // DOM (e.g. the index.html bootstrap created it), adopt it instead of
  // creating a duplicate.
  const existing = document.getElementById('model-picker');
  if (mountEl || existing) { mountEl = mountEl || existing; return; }
  // Mount in .header-left, AFTER the context ring (#header-model-info).
  // Layout: [sidebar-toggle] [ctx-ring] [model-picker] .... [session-name]
  const headerLeft = document.querySelector('.header-left');
  if (!headerLeft) return;
  mountEl = document.createElement('div');
  mountEl.id = 'model-picker';
  const ctxInfo = document.getElementById('header-model-info');
  if (ctxInfo) ctxInfo.after(mountEl);
  else headerLeft.appendChild(mountEl);
  // Close on outside click.
  document.addEventListener('click', (e) => {
    if (!expanded) return;
    const panel = document.getElementById('mp-panel');
    if (!mountEl?.contains(e.target) && !panel?.contains(e.target)) {
      expanded = false; try { render(); } catch (_) {}
    }
  });
  // Keep the floating panel aligned with its chip on resize/scroll while open.
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
