// modelCapabilities.js — Model capability tag editor.
// Renders capability pills inline within provider cards in Settings.
// Fetches /api/models/capabilities, PUTs updates on pill toggle.

import { t } from './i18n.js';

const PRESET_CAPS = [
  { key: 'vision',       label: 'Vision',       color: '#5b7fbf' },
  { key: 'reasoning',    label: 'Reasoning',    color: '#8b5fbf' },
  { key: 'code',         label: 'Code',          color: '#3da77f' },
  { key: 'fast',         label: 'Fast',          color: '#d49020' },
  { key: 'long-context', label: 'Long Context',  color: '#bf6b3d' },
  { key: 'cheap',        label: 'Cheap',         color: '#5b9e9e' },
  { key: 'audio',        label: 'Audio',         color: '#bf5b7f' },
];

let models = [];
let loading = false;
let onReadyCb = null;

// ── Helpers ────────────────────────────────────────────────
function getToken() { return localStorage.getItem('nebflow_token') || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

// ── API ────────────────────────────────────────────────────
async function fetchModels() {
  loading = true;
  try {
    const resp = await fetch('/api/models/capabilities', { headers: authHeaders() });
    if (!resp.ok) { models = []; loading = false; if (onReadyCb) onReadyCb(); return; }
    const data = await resp.json();
    const modelsMap = data.models || {};
    models = Object.entries(modelsMap).map(([id, info]) => ({
      id,
      capabilities: info.capabilities || [],
      vision: info.vision || false
    }));
  } catch (e) { models = []; }
  loading = false;
  if (onReadyCb) onReadyCb();
}

async function updateModelCapability(modelEntry, caps) {
  const hasVision = caps.includes('vision');
  const otherCaps = caps.filter(c => c !== 'vision');
  const slashIdx = modelEntry.id.indexOf('/');
  const providerId = slashIdx >= 0 ? modelEntry.id.substring(0, slashIdx) : modelEntry.id;
  const modelId = slashIdx >= 0 ? modelEntry.id.substring(slashIdx + 1) : modelEntry.id;

  try {
    await fetch('/api/models/capabilities', {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ providerId, modelId, capabilities: otherCaps, vision: hasVision }),
    });
    modelEntry.capabilities = otherCaps;
    modelEntry.vision = hasVision;
  } catch (e) { /* non-critical */ }
}

// ── Public ─────────────────────────────────────────────────

/** Pre-fetch model capabilities. Call once when Settings opens. */
export function preloadModelCapabilities(callback) {
  onReadyCb = callback || null;
  fetchModels();
}

/** Get capabilities for a model ref (e.g. "USTC/glm-4.6").
 *  Returns a Set of cap keys (including 'vision' if set). */
export function getModelCaps(ref) {
  const m = models.find(m => m.id === ref);
  if (!m) return new Set();
  const caps = new Set(m.capabilities || []);
  if (m.vision) caps.add('vision');
  return caps;
}

/** Get the underlying model entry object for mutation. */
function getModelEntry(ref) {
  return models.find(m => m.id === ref);
}

/** Render capability pills HTML for a model ref.
 *  Active pills are filled with their color; inactive are outlined.
 *  Clicking a pill toggles it. */
export function renderCapPills(ref) {
  const caps = getModelCaps(ref);

  return PRESET_CAPS.map(c => {
    const active = caps.has(c.key);
    const bg = active ? c.color : 'transparent';
    const fg = active ? '#fff' : c.color;
    const border = active ? c.color : `${c.color}55`;
    return `<button class="cap-pill${active ? ' active' : ''}"
      style="--pill-color:${c.color};background:${bg};color:${fg};border-color:${border}"
      data-model="${esc(ref)}" data-cap="${esc(c.key)}"
      title="${esc(c.label)}">${esc(c.label)}</button>`;
  }).join('');
}

/** Bind click handlers for capability pills within a container. */
export function bindCapPills(container) {
  container.querySelectorAll('.cap-pill').forEach(pill => {
    pill.addEventListener('click', async (e) => {
      e.stopPropagation();
      const modelRef = pill.dataset.model;
      const capKey = pill.dataset.cap;
      const entry = getModelEntry(modelRef);
      if (!entry) return;

      const caps = getModelCaps(modelRef);
      if (caps.has(capKey)) caps.delete(capKey);
      else caps.add(capKey);

      await updateModelCapability(entry, [...caps]);

      // Re-render just this model's pills
      const parent = pill.parentElement;
      if (parent) parent.innerHTML = renderCapPills(modelRef);
      // Re-bind
      bindCapPills(parent);
    });
  });
}
