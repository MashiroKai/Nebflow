// modelCapabilities.js — Model capability tag editor.
// Renders a section inside Settings showing each configured model with
// preset capability checkboxes (vision, reasoning, code, etc.).
// Self-contained: fetches /api/models, PUTs updates on checkbox change.

import { t } from './i18n.js';

const PRESET_CAPS = [
  { key: 'vision',       label: 'Vision' },
  { key: 'reasoning',    label: 'Reasoning' },
  { key: 'code',         label: 'Code' },
  { key: 'fast',         label: 'Fast' },
  { key: 'long-context', label: 'Long Context' },
  { key: 'cheap',        label: 'Cheap' },
  { key: 'audio',        label: 'Audio' },
];

let models = [];
let loading = false;

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
  renderSection();
  try {
    const resp = await fetch('/api/models/capabilities', { headers: authHeaders() });
    if (!resp.ok) { models = []; loading = false; renderSection(); return; }
    const data = await resp.json();
    // API returns { models: { "provider/model": {vision, capabilities} } }
    const modelsMap = data.models || {};
    models = Object.entries(modelsMap).map(([id, info]) => ({
      id,
      capabilities: info.capabilities || [],
      vision: info.vision || false
    }));
  } catch (e) { models = []; }
  loading = false;
  renderSection();
}

async function updateModelCapability(modelEntry, caps) {
  // Split vision from other capabilities — API expects separate fields
  const hasVision = caps.includes('vision');
  const otherCaps = caps.filter(c => c !== 'vision');

  // Parse provider/modelId from id ("provider/modelId")
  const slashIdx = modelEntry.id.indexOf('/');
  const providerId = slashIdx >= 0 ? modelEntry.id.substring(0, slashIdx) : modelEntry.id;
  const modelId = slashIdx >= 0 ? modelEntry.id.substring(slashIdx + 1) : modelEntry.id;

  try {
    await fetch('/api/models/capabilities', {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        providerId,
        modelId,
        capabilities: otherCaps,
        vision: hasVision,
      }),
    });
    // Update local state
    modelEntry.capabilities = otherCaps;
    modelEntry.vision = hasVision;
  } catch (e) { /* non-critical — will refresh on next fetch */ }
}

// ── Render ─────────────────────────────────────────────────
function renderSection() {
  const container = document.getElementById('model-caps-container');
  if (!container) return;

  if (loading) {
    container.innerHTML = `<div class="cfg-empty">${t('chat.loading')}</div>`;
    return;
  }

  if (!models || models.length === 0) {
    container.innerHTML = `<div class="cfg-empty">${t('settings.noModels') || 'No models configured'}</div>`;
    return;
  }

  container.innerHTML = models.map(m => {
    const caps = new Set(m.capabilities || []);
    if (m.vision) caps.add('vision');

    const checkboxes = PRESET_CAPS.map(c => {
      const checked = caps.has(c.key);
      return `<label class="model-cap-chip${checked ? ' checked' : ''}" data-model="${esc(m.id)}" data-cap="${esc(c.key)}">
        <input type="checkbox" ${checked ? 'checked' : ''}>
        <span>${esc(c.label)}</span>
      </label>`;
    }).join('');

    return `<div class="model-cap-row">
      <div class="model-cap-name">${esc(m.id)}</div>
      <div class="model-cap-chips">${checkboxes}</div>
    </div>`;
  }).join('');

  // Bind checkbox changes
  container.querySelectorAll('.model-cap-chip input[type="checkbox"]').forEach(cb => {
    cb.addEventListener('change', async () => {
      const chip = cb.closest('.model-cap-chip');
      const modelId = chip.dataset.model;
      const capKey = chip.dataset.cap;
      chip.classList.toggle('checked', cb.checked);

      const model = models.find(m => m.id === modelId);
      if (!model) return;

      // Gather all currently checked caps for this model
      const allChips = container.querySelectorAll(`.model-cap-chip[data-model="${esc(modelId)}"] input[type="checkbox"]`);
      const checkedCaps = [];
      allChips.forEach(c => { if (c.checked) checkedCaps.push(c.closest('.model-cap-chip').dataset.cap); });

      await updateModelCapability(model, checkedCaps);
    });
  });
}

// ── Public ─────────────────────────────────────────────────

/** Render the model capabilities section into a container element.
 *  Called from renderSettings() in sidebar.js. */
export function renderModelCapabilities(container) {
  container.innerHTML = `<div id="model-caps-container"></div>`;
  fetchModels();
}
