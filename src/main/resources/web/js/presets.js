// presets.js — Model preset shared module.
// API helpers + read-only chain chips + interactive chain editor.
// Used by: settings page preset section (sidebar.js), agent detail page
// (agentManager.js), flow/team definition viewer (flowViewers.js).

import state from './state.js';
import { key } from './branding.js';
import { t } from './i18n.js';

// ── Helpers ────────────────────────────────────────────────
function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}
export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
export function shortModel(ref) {
  if (!ref) return '';
  const idx = ref.lastIndexOf('/');
  return idx >= 0 ? ref.slice(idx + 1) : ref;
}

/**
 * All model refs for pickers. state.parsedConfig is the live source - it is
 * re-parsed on every configData push, so models added via the provider editor
 * appear immediately. state.allModelRefs (filled once from modelOptions at WS
 * open, never refreshed) is only the fallback for before the first configData.
 * Both sources derive from the same server configRef, so contents are
 * identical modulo freshness.
 */
export function getAllModelRefs() {
  const providers = state.parsedConfig?.llm?.providers;
  if (providers) {
    const refs = [];
    for (const [name, p] of Object.entries(providers)) {
      (p.models || []).forEach(m => refs.push(`${name}/${m.id}`));
    }
    if (refs.length > 0) return refs;
  }
  return state.allModelRefs || [];
}

// ── API ────────────────────────────────────────────────────

/** GET /api/presets → { defaultPreset, presets: [...], agents: {name: preset|null} } */
export async function fetchPresets() {
  try {
    const resp = await fetch('/api/presets', { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch (e) { return null; }
}

export async function createPreset(body) {
  const resp = await fetch('/api/presets', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${resp.status}`);
  }
  return resp.json();
}

export async function updatePreset(name, body) {
  const resp = await fetch(`/api/presets/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${resp.status}`);
  }
  return resp.json();
}

export async function deletePreset(name) {
  const resp = await fetch(`/api/presets/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${resp.status}`);
  }
  return resp.json();
}

export async function setDefaultPreset(name) {
  const resp = await fetch('/api/presets/default', {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${resp.status}`);
  }
  return resp.json();
}

/** PUT /api/agents/:name/preset — preset is a name string or null (use default). */
export async function setAgentPreset(agentName, preset) {
  try {
    await fetch(`/api/agents/${encodeURIComponent(agentName)}/preset`, {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ preset: preset || null }),
    });
  } catch (e) { /* non-critical */ }
}

/**
 * Detect agents still using legacy per-agent model config
 * (GET /agents list + per-agent /model resolvedFrom === 'legacy-model').
 * Returns detailed entries [{name, preferred, fallbacks}] for the
 * migration preview (P5).
 */
export async function detectLegacyAgents() {
  try {
    const resp = await fetch('/api/agents', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    const agents = data.agents || [];
    const flags = await Promise.all(agents.map(async a => {
      try {
        const r = await fetch(`/api/agents/${encodeURIComponent(a.name)}/model`, { headers: authHeaders() });
        if (!r.ok) return null;
        const m = await r.json();
        if (m.resolvedFrom !== 'legacy-model') return null;
        return { name: a.name, preferred: m.preferred || null, fallbacks: m.fallbacks || [] };
      } catch (e) { return null; }
    }));
    return flags.filter(Boolean);
  } catch (e) { return []; }
}

/** POST /api/presets/migrate-legacy — batch-migrate legacy configs to presets. */
export async function migrateLegacy(agentNames) {
  const resp = await fetch('/api/presets/migrate-legacy', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentNames }),
  });
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${resp.status}`);
  }
  return resp.json(); // { migratedAgents: [{agent, preset}], createdPresets: [...] }
}

/**
 * Local migration preview (P5 stage 1) — mirrors the backend grouping:
 * group legacy agents by config fingerprint (preferred + fallbacks), reuse
 * an existing preset when its chain matches, otherwise allocate mig-<n>
 * names avoiding collisions. Pure function (unit-testable).
 * Returns [{presetName, reused, preferred, fallbacks, agents: [...]}].
 */
export function previewMigration(legacyAgents, presetData) {
  const existing = presetData?.presets || [];
  const fpOf = (preferred, fallbacks) => `${preferred || ''}|${(fallbacks || []).join(',')}`;

  // Group by fingerprint
  const groups = new Map();
  for (const a of legacyAgents || []) {
    const fp = fpOf(a.preferred, a.fallbacks);
    if (!groups.has(fp)) groups.set(fp, { fp, preferred: a.preferred || null, fallbacks: a.fallbacks || [], agents: [] });
    groups.get(fp).agents.push(a.name);
  }
  const sorted = [...groups.values()].sort((x, y) => x.fp.localeCompare(y.fp));

  const existingNames = new Set(existing.map(p => p.name));
  const taken = new Set();
  let migN = 1;
  return sorted.map(g => {
    // Reuse an existing preset with the identical chain
    const match = existing.find(p =>
      (p.preferred || null) === g.preferred &&
      JSON.stringify(p.fallbacks || []) === JSON.stringify(g.fallbacks));
    let presetName;
    let reused = false;
    if (match) {
      presetName = match.name;
      reused = true;
    } else {
      presetName = `mig-${migN}`;
      while (existingNames.has(presetName) || taken.has(presetName)) { migN++; presetName = `mig-${migN}`; }
      migN++;
      taken.add(presetName);
    }
    return { presetName, reused, preferred: g.preferred, fallbacks: g.fallbacks, agents: g.agents.sort() };
  });
}

// ── Read-only chain chips ──────────────────────────────────

/**
 * Render a model chain as read-only chips: preferred highlighted, fallbacks
 * small, pulsing dot on the currently running model.
 * chain: [preferred, ...fallbacks] (may be empty); current: running ref.
 */
export function presetChainHtml(chain, current) {
  const refs = (chain || []).filter(Boolean);
  if (refs.length === 0) {
    return `<div class="preset-chain-empty">${esc(t('preset.emptyChain'))}</div>`;
  }
  const chips = refs.map((ref, i) => {
    const playing = current && ref === current;
    return `<span class="preset-chip${i === 0 ? ' preferred' : ''}${playing ? ' playing' : ''}">${playing ? '<span class="agent-detail-playing-dot"></span>' : ''}${esc(ref)}</span>`;
  }).join('<span class="preset-chain-arrow">→</span>');
  return `<div class="preset-chain">${chips}</div>`;
}

/** Chain chips from a GET /agents/:name/model response (resolved config). */
export function resolvedChainHtml(model) {
  const chain = [model?.preferred, ...(model?.fallbacks || [])].filter(Boolean);
  return presetChainHtml(chain, model?.current || '');
}

// ── Interactive chain editor (preset edit modal) ───────────

/**
 * Drag-to-reorder model chain editor (first = preferred). Reuses the
 * agent-detail-model-row styles/interaction. Manages its own state copy.
 * Returns { getChain } for reading the current order on save.
 */
export function renderChainEditor(container, initial, allRefs) {
  let chain = [...(initial || [])].filter(Boolean);

  function render() {
    const rowsHtml = chain.map((ref, i) => `
      <div class="agent-detail-model-row" draggable="true" data-idx="${i}">
        <span class="agent-detail-grip">⠿</span>
        <span class="agent-detail-model-name">${esc(ref)}</span>
        <span class="agent-detail-model-pos">${i === 0 ? '★' : i}</span>
        <span class="agent-detail-model-remove" data-idx="${i}" title="Remove">×</span>
      </div>`).join('');
    const available = (allRefs || []).filter(r => !chain.includes(r));
    const addHtml = available.length
      ? `<div class="agent-detail-model-add"><select><option value="">${esc(t('preset.addModel'))}</option>${available.map(r => `<option value="${esc(r)}">${esc(r)}</option>`).join('')}</select></div>`
      : '';
    container.innerHTML = `${rowsHtml}<div class="agent-detail-empty-spacer"></div>${addHtml}`;

    // Drag reorder
    let dragIdx = null;
    container.querySelectorAll('.agent-detail-model-row').forEach(row => {
      row.addEventListener('dragstart', (e) => {
        dragIdx = Number(row.dataset.idx);
        row.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
      });
      row.addEventListener('dragend', () => {
        row.classList.remove('dragging');
        dragIdx = null;
        container.querySelectorAll('.agent-detail-model-row').forEach(r => r.classList.remove('drag-over'));
      });
      row.addEventListener('dragover', (e) => {
        e.preventDefault();
        container.querySelectorAll('.agent-detail-model-row').forEach(r => r.classList.remove('drag-over'));
        row.classList.add('drag-over');
      });
      row.addEventListener('drop', (e) => {
        e.preventDefault();
        const overIdx = Number(row.dataset.idx);
        if (dragIdx === null || dragIdx === overIdx) return;
        const [moved] = chain.splice(dragIdx, 1);
        chain.splice(overIdx, 0, moved);
        render();
      });
    });

    // Add select
    const addSel = container.querySelector('.agent-detail-model-add select');
    if (addSel) {
      addSel.addEventListener('change', () => {
        const ref = addSel.value;
        if (!ref) return;
        chain.push(ref);
        render();
      });
    }

    // Remove buttons
    container.querySelectorAll('.agent-detail-model-remove').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        chain.splice(Number(btn.dataset.idx), 1);
        render();
      });
    });
  }

  render();
  return { getChain: () => [...chain] };
}
