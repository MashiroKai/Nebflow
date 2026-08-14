// modelCapabilities.js — Vision capability readouts for provider model cards.
// Renders a read-only Vision pill inline within provider cards in Settings.
// Fetches /api/models/capabilities. Toggles are retired (B3): vision is
// tri-state server-side (true/false/null=unknown), unknown resolves
// optimistically to true, and runtime errors auto-disable vision.

let models = [];
let loading = false;
let onReadyCb = null;

// ── Helpers ────────────────────────────────────────────────
function getToken() { return localStorage.getItem('nebflow_token') || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

// ── API ────────────────────────────────────────────────────
async function fetchModels() {
  loading = true;
  try {
    const resp = await fetch('/api/models/capabilities', { headers: authHeaders() });
    if (!resp.ok) { models = []; loading = false; if (onReadyCb) onReadyCb(); return; }
    const data = await resp.json();
    const modelsMap = data.models || {};
    // Tri-state: backend sends vision=false for explicit no-vision
    // annotations, null/absent for unknown. Unknown resolves optimistically
    // to true (matches backend resolveCapabilities, B3 Phase 1).
    models = Object.entries(modelsMap).map(([id, info]) => ({
      id,
      capabilities: info.capabilities || [],
      vision: info.vision === false ? false : true
    }));
  } catch (e) { models = []; }
  loading = false;
  if (onReadyCb) onReadyCb();
}

/** PUT a vision annotation via REST. Kept as the programmatic channel —
 *  no UI invokes it since toggles were retired (B3). */
export async function updateVision(modelEntry, vision) {
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

/** Read-only vision badge — only renders when vision is on. */
export function renderVisionBadge(ref) {
  const vision = getVision(ref);
  return vision ? '<span class="vision-badge">Vision</span>' : '';
}
