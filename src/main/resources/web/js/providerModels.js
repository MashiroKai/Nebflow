// providerModels.js — the shared provider model-list probe (POST /api/provider/models).
//
// uifix3 ② (2026-10-06 21:36 author screenshot order): the onboarding config-model
// question must reuse the settings face's model detection ("逻辑照搬设置 … 复用或
// 抽共用，禁平行实现"). This module IS that extraction: `fetchProviderModels` +
// `normalizeModelEntry` moved VERBATIM out of sidebar.js (the settings provider
// dialog's auto-fetch, B1), and both consumers now import this one implementation:
//   - sidebar.js — the provider modal's fetch button + debounced auto-fetch
//     (wireProviderModelFetch) and the chain editors' dynamic layer;
//   - onboarding.js — the model question's "add a provider" field face, which
//     gains the same probe → candidate-list → pick flow (settings-same degrade:
//     a failed probe leaves the manual input usable, never blocks the flow).
//
// Backend contract (PresenceRoutes.scala "Provider model discovery"): POST body
// {baseUrl, apiKey?, protocol?, name?} → `{models: [...]}` on success, 502 +
// {error} on any probe failure — best-effort by design, the frontend degrades.
// Elements arrive either as plain id strings (old contract) or objects with an
// optional contextLength (e.g. OpenRouter exposes context_length; OpenAI and
// Anthropic do not) — normalized to {id, contextLength|null} below.

import { key } from './branding.js';

function providerAuthHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

/** Normalize one models[] element: 'id-string' | {id, contextLength?} →
 *  {id, contextLength|null}. Unknown shapes are dropped. */
export function normalizeModelEntry(m) {
  if (typeof m === 'string' && m) return { id: m, contextLength: null };
  if (m && typeof m.id === 'string' && m.id) {
    return { id: m.id, contextLength: Number.isFinite(m.contextLength) ? m.contextLength : null };
  }
  return null;
}

/** Ask the gateway to fetch one provider's live model list. Never throws — the
 *  {ok, models|error} shape IS the error path (settings parity: the caller
 *  degrades to manual input instead of blocking the flow). 10s abort, matching
 *  the settings dialog's patience. */
export async function fetchProviderModels(baseUrl, apiKey) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 10000);
  try {
    const resp = await fetch('/api/provider/models', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...providerAuthHeaders() },
      body: JSON.stringify({ baseUrl, apiKey }),
      signal: ctrl.signal,
    });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) return { ok: false, error: data.error || `HTTP ${resp.status}` };
    const models = Array.isArray(data.models) ? data.models.map(normalizeModelEntry).filter(Boolean) : [];
    return { ok: true, models };
  } catch (e) {
    return { ok: false, error: e.name === 'AbortError' ? 'timeout' : e.message };
  } finally {
    clearTimeout(timer);
  }
}
