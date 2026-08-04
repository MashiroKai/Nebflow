// modelCapabilities.js — Vision toggle for provider model cards.
// Renders a single Vision pill inline within provider cards in Settings.
// Fetches /api/models/capabilities, PUTs updates on toggle.

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

async function updateVision(modelEntry, vision) {
  const slashIdx = modelEntry.id.indexOf('/');
  const providerId = slashIdx >= 0 ? modelEntry.id.substring(0, slashIdx) : modelEntry.id;
  const modelId = slashIdx >= 0 ? modelEntry.id.substring(slashIdx + 1) : modelEntry.id;

  try {
    await fetch('/api/models/capabilities', {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ providerId, modelId, capabilities: modelEntry.capabilities || [], vision }),
    });
    modelEntry.vision = vision;
  } catch (e) { /* non-critical */ }
}

// ── Public ─────────────────────────────────────────────────

/** Pre-fetch model capabilities. Call once when Settings opens. */
export function preloadModelCapabilities(callback) {
  onReadyCb = callback || null;
  fetchModels();
}

/** Get vision status for a model ref. Returns boolean. */
export function getVision(ref) {
  const m = models.find(m => m.id === ref);
  return m ? m.vision : false;
}

/** Get the underlying model entry object for mutation. */
function getModelEntry(ref) {
  return models.find(m => m.id === ref);
}

/** Render vision toggle HTML for a model ref.
 *  If vision is on → solid blue "Vision" pill.
 *  If off → dashed gray "Vision" pill. Click to toggle. */
export function renderVisionToggle(ref) {
  const vision = getVision(ref);
  return `<button class="vision-toggle${vision ? ' on' : ''}"
    data-model="${esc(ref)}"
    title="图片理解">Vision</button>`;
}

/** Bind click handlers for vision toggles within a container. */
export function bindVisionToggles(container) {
  container.querySelectorAll('.vision-toggle').forEach(toggle => {
    toggle.addEventListener('click', async (e) => {
      e.stopPropagation();
      const modelRef = toggle.dataset.model;
      const entry = getModelEntry(modelRef);
      if (!entry) return;

      const newVision = !entry.vision;
      await updateVision(entry, newVision);

      // Re-render just this toggle
      const parent = toggle.parentElement;
      if (parent) parent.innerHTML = renderVisionToggle(modelRef);
      bindVisionToggles(parent);
    });
  });
}
