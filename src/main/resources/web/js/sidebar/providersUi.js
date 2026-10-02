// sidebar.js 拆分(FE组件化批次二 2026-09-27):Provider 界面族 可复用模块(行为保持)。
// 覆盖 model-list auto-fetch(B1)+ Provider modal + Generic modal 三段,及随簇迁移的
// 闭包成员:Provider protocol face 常量与助手(sidebar.js 原 :29-57)、eyeSvg/eyeOffSvg
// (原 :70-71)、flushConfigToServer(原 :1607-1611,saveNewProvider 的写路径单点)。
// escapeHtml 随段落于本模块(sidebar.js 全文件共用,由此单向提供)。
import state from '../state.js';
import { key } from '../branding.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';

// ── Provider protocol face: display ←→ stored value (protoface-ui batch,
// 2026-09-22) ────────────────────────────────────────────────────────────────
// Stored values stay `openai` / `anthropic` forever (the engine's LlmProtocol
// decoder — config.scala:19-23 — is unchanged by this batch); only the FACE the
// user reads changes. The two id⇄endpoint-form pairs are the engine's own two
// adapters: OpenAiAdapter.scala:45-47 (`{base}/chat/completions`) and
// AnthropicAdapter.scala:30-32 (`{base}/v1/messages`).
const PROTOCOL_FORM_LABEL = { openai: 'chat/completions', anthropic: 'messages' };
const PROTOCOL_ENDPOINT_PATH = { openai: '/chat/completions', anthropic: '/v1/messages' };

/** Stored protocol id → the endpoint form shown to the user. An id this build
 *  does not know is shown verbatim (never silently re-labelled). */
function protocolFormLabel(protocol) {
  return PROTOCOL_FORM_LABEL[protocol] || protocol || '';
}

/** The POST target the engine will hit for `protocol` + `baseUrl`. Same
 *  normalization as the adapters (OpenAiAdapter.scala:45-47 /
 *  AnthropicAdapter.scala:30-32): trailing slashes stripped, a base that
 *  already ends in the endpoint path kept as-is. Empty string = "no target to
 *  promise yet" — an unknown face, or a baseUrl still blank (the same rule the
 *  model-fetch status line follows at :1709-1713). */
function protocolPostTarget(protocol, baseUrl) {
  const path = PROTOCOL_ENDPOINT_PATH[protocol];
  if (!path) return '';
  const base = (baseUrl || '').trim().replace(/\/+$/, '');
  if (!base) return '';
  return base.endsWith(path) ? base : base + path;
}

const eyeSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const eyeOffSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

function flushConfigToServer() {
  const json = JSON.stringify(state.parsedConfig, null, 2);
  state.configText = json;
  sendWs({type: 'updateConfig', config: json});
}

// --- Provider model-list auto-fetch (B1) ---
// Model choices fetched for the currently open provider modal. Set on a
// successful POST /api/provider/models; renderModelRowContent reads it so
// both existing and newly added rows offer a dropdown instead of free text.
// The proxy endpoint may not exist yet (404) — fetch failure degrades to
// manual input without blocking the flow.
// Elements are normalized to {id, contextLength} — the backend may send plain
// strings (old contract) or objects with an optional contextLength (e.g.
// OpenRouter exposes context_length; OpenAI/Anthropic do not).
let providerModelChoices = null;

function providerAuthHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

/** Normalize one models[] element: 'id-string' | {id, contextLength?} →
 *  {id, contextLength|null}. Unknown shapes are dropped. */
function normalizeModelEntry(m) {
  if (typeof m === 'string' && m) return { id: m, contextLength: null };
  if (m && typeof m.id === 'string' && m.id) {
    return { id: m.id, contextLength: Number.isFinite(m.contextLength) ? m.contextLength : null };
  }
  return null;
}

async function fetchProviderModels(baseUrl, apiKey) {
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

function contextLengthFor(id) {
  if (!id || !providerModelChoices) return null;
  const found = providerModelChoices.find(m => m.id === id);
  return found && found.contextLength ? found.contextLength : null;
}

// --- chain-ctxbound (2026-09-21): contextWindow entrance bound check ---
// Paired defense with the engine-side clamp (案② chain-llmstall-fix:
// effective = min(configured, modelMaxContext)). One truth source, two layers:
// the per-model bound is the provider-reported contextLength (contextLengthFor
// ← POST /api/provider/models) — the same payload the engine clamp persists as
// models[].modelMaxContext. When that truth is unknown (fetch failed / custom
// endpoint), the entrance falls back to the absolute absurdity ceiling only:
// 10,000,000 = the largest mainstream provider context window on record
// (Llama 4 Scout) — anything above is fat-fingered, and the ceiling keeps
// saved values well inside the engine's Int domain.
/** Absolute absurdity ceiling for a context window whose model truth is
 *  unknown. NOT a per-model limit — known-truth models are bounded by their
 *  own provider-reported maximum instead (which may legitimately differ). */
export const CTX_WINDOW_HARD_CEILING = 10000000;
/** Panel default when the field is left empty (author 07:31 令: unchanged). */
const CTX_WINDOW_DEFAULT = 1000000;

/**
 * Validate one raw contextWindow input against the paired-defense bound.
 * Number() semantics, not parseInt: parseInt("1e9") === 1 was a live defect.
 * Single-shape return (no union narrowing — checkJs baseline discipline).
 * note: '' clean | 'defaulted-empty' (empty → panel default) |
 *       'clamped-to-truth' (untouched wizard prefill 1,000,000 with a lower
 *       provider-reported truth → clamped to the truth, visibly toasted —
 *       keeps the saved value identical to the engine clamp's effective value).
 * @param {string|number|null|undefined} rawValue raw input value (un-parsed)
 * @param {number|null|undefined} [modelMax] provider-reported per-model truth;
 *   absent ⇒ only the absolute absurdity ceiling applies
 * @returns {{ok:boolean, value:number, note:string, errKey:string, errParams:Record<string,string>}}
 *   ok:false ⇒ value/note are inert; errKey/errParams carry the error toast.
 */
export function validateContextWindow(rawValue, modelMax) {
  const raw = String(rawValue ?? '').trim();
  if (raw === '') return { ok: true, value: CTX_WINDOW_DEFAULT, note: 'defaulted-empty', errKey: '', errParams: {} };
  const n = Number(raw);
  if (!Number.isFinite(n) || !Number.isInteger(n) || n < 1) {
    return { ok: false, value: 0, note: '', errKey: 'provider.ctxInvalid', errParams: { value: raw } };
  }
  const truth = Number.isFinite(modelMax) && modelMax > 0 ? modelMax : null;
  if (truth !== null && n > truth) {
    if (n === CTX_WINDOW_DEFAULT) {
      // The wizard prefills 1,000,000 (author-preserved default); with a known
      // lower truth the untouched prefill is clamped to the truth + info toast.
      return { ok: true, value: truth, note: 'clamped-to-truth', errKey: '', errParams: {} };
    }
    return { ok: false, value: 0, note: '', errKey: 'provider.ctxExceedsModel', errParams: { value: String(n), max: String(truth) } };
  }
  if (truth === null && n > CTX_WINDOW_HARD_CEILING) {
    return { ok: false, value: 0, note: '', errKey: 'provider.ctxExceedsCeiling', errParams: { value: String(n), max: String(CTX_WINDOW_HARD_CEILING) } };
  }
  return { ok: true, value: n, note: '', errKey: '', errParams: {} };
}

/** Fill the row's contextWindow input with the fetched contextLength — only
 *  when the input is empty (never overwrite a user-set value).
 *
 *  B5（案② chain-llmstall-fix，2026-09-21）：**不论输入框是否已有值**，都把 provider
 *  上报的真值记为 clamp 上界（`data-model-max`，保存时写成
 *  `models[].modelMaxContext`；后端取数单点取 `min(configured, 真值)`）。
 *  旧行为：`ctxInput.value` 非空即直接 return ⇒ 真值永远落不了地——而新模型行在
 *  `showProviderModal` 里已被预填 1000000，于是 provider 上报的上限（如 200k）被
 *  完整覆盖（定谳报告 `20260921_182544` 核查 2）。
 *  🔴 显示值仍**只在空时**填：绝不覆盖用户手写值（保存路径的 1M 兜底同样保持不动）。 */
function fillContextIfEmpty(row) {
  if (!row) return;
  const sel = row.querySelector('.cfg-model-id');
  const ctxInput = row.querySelector('.cfg-model-ctx');
  if (!sel || !ctxInput) return;
  const len = contextLengthFor(sel.value);
  if (!len) return;
  ctxInput.dataset.modelMax = String(len);
  if (ctxInput.value) return;
  ctxInput.value = len;
}

function renderModelIdSelect(currentId) {
  const opts = providerModelChoices.map(m => m.id);
  if (currentId && !opts.includes(currentId)) opts.unshift(currentId);
  return `<select class="cfg-select cfg-model-id">
    <option value="" disabled ${currentId ? '' : 'selected'}>${t('provider.modelSelectPlaceholder')}</option>
    ${opts.map(o => `<option value="${escapeHtml(o)}" ${o === currentId ? 'selected' : ''}>${escapeHtml(o)}</option>`).join('')}
  </select>`;
}

/** Convert manual id inputs in existing rows to dropdowns after a successful
 *  fetch. Current values are preserved (added as an extra option if absent
 *  from the fetched list); empty contextWindow inputs are auto-filled from
 *  the fetched contextLength. */
function upgradeModelRowsToSelects(container) {
  container.querySelectorAll('.cfg-model-row').forEach(row => {
    const input = row.querySelector('input.cfg-model-id');
    if (input) {
      const current = input.value.trim();
      const tmp = document.createElement('template');
      tmp.innerHTML = renderModelIdSelect(current).trim();
      input.replaceWith(tmp.content.firstChild);
    }
    fillContextIfEmpty(row);
  });
}

/** Wire base URL → auto-fetch model list inside the provider modal: a fetch
 *  button + status line above the models list, plus debounced auto-fetch on
 *  baseUrl/apiKey change. Degrades gracefully when the proxy endpoint is
 *  missing or the provider errors — manual input stays usable. */
function wireProviderModelFetch() {
  const overlay = document.getElementById('cfg-modal');
  if (!overlay) return;
  providerModelChoices = null;
  const baseInput = overlay.querySelector('[data-field="baseUrl"]');
  const keyInput = overlay.querySelector('[data-field="apiKey"]');
  const container = overlay.querySelector('[data-field="models"]');
  const group = container?.closest('.cfg-form-group');
  if (!baseInput || !container || !group) return;

  const statusRow = document.createElement('div');
  statusRow.className = 'cfg-fetch-status-row';
  statusRow.innerHTML = `<button type="button" class="cfg-fetch-models-btn">${t('provider.fetchModels')}</button><span class="cfg-fetch-status"></span>`;
  group.insertBefore(statusRow, container);
  const fetchBtn = statusRow.querySelector('.cfg-fetch-models-btn');
  const status = statusRow.querySelector('.cfg-fetch-status');

  let fetchGen = 0; // race guard: stale responses (older trigger) are dropped
  async function runFetch() {
    const baseUrl = baseInput.value.trim();
    if (!baseUrl || !/^https?:\/\//.test(baseUrl)) {
      status.textContent = '';
      status.className = 'cfg-fetch-status';
      return;
    }
    const gen = ++fetchGen;
    fetchBtn.disabled = true;
    status.textContent = t('provider.fetchingModels');
    status.className = 'cfg-fetch-status loading';
    const res = await fetchProviderModels(baseUrl, keyInput ? keyInput.value.trim() : '');
    if (gen !== fetchGen || !overlay.isConnected) return;
    fetchBtn.disabled = false;
    if (res.ok && res.models.length > 0) {
      providerModelChoices = res.models;
      upgradeModelRowsToSelects(container);
      // A successful list fetch with a non-empty key doubles as credential
      // validation — flag the key as valid. Anonymous providers (empty key)
      // never get this hint.
      const hasKey = !!(keyInput && keyInput.value.trim());
      status.textContent = t('provider.fetchModelsLoaded', { count: res.models.length })
        + (hasKey ? ` · ${t('provider.keyValid')}` : '');
      status.className = 'cfg-fetch-status ok';
    } else {
      providerModelChoices = null;
      status.textContent = t('provider.fetchModelsFailed');
      status.className = 'cfg-fetch-status fail';
    }
  }

  fetchBtn.addEventListener('click', runFetch);
  let debounceTimer = null;
  const autoFetch = () => { clearTimeout(debounceTimer); debounceTimer = setTimeout(runFetch, 400); };
  baseInput.addEventListener('change', autoFetch);
  keyInput?.addEventListener('change', autoFetch);
  // Delegated: picking a model from the dropdown auto-fills an empty
  // contextWindow from the fetched contextLength (never overwrites).
  container.addEventListener('change', (e) => {
    if (e.target.matches('select.cfg-model-id')) fillContextIfEmpty(e.target.closest('.cfg-model-row'));
  });
}

// --- Provider modal ---
/** Persist a newly added provider: mutate parsedConfig + flush. Provider
 *  writes never touch model chains — per-role chains live in each agent's
 *  agent.json (`model` key) behind the /model panel, so there is no chain
 *  write here. Exported for the chat-native onboarding flow
 *  (onboarding-redesign-spec §5.1) — one write path. */
export function saveNewProvider(name, data) {
  if (!state.parsedConfig) state.parsedConfig = {llm: {providers: {}}};
  if (!state.parsedConfig.llm) state.parsedConfig.llm = {providers: {}};
  if (!state.parsedConfig.llm.providers) state.parsedConfig.llm.providers = {};
  state.parsedConfig.llm.providers[name] = data;
  state.configDirty = true;
  flushConfigToServer();
}

/**
 * Onboarding wizard entry: run the same add-provider modal outside the
 * settings panel. onSaved fires after the config has been flushed.
 */
export function openProviderWizard(onSaved) {
  showProviderModal(null, null, (name, data) => {
    saveNewProvider(name, data);
    if (typeof onSaved === 'function') onSaved(name);
  });
}

function showProviderModal(existingName, existingData, onSave) {
  const isEdit = !!existingName;
  const p = existingData || {baseUrl: '', apiKey: '', protocol: 'anthropic', models: []};
  const initialModels = p.models.length > 0 ? p.models.map(m => ({
    ...m,
  })) : [{id: '', contextWindow: 1000000}];

  showModal({
    title: isEdit ? t('provider.edit', { name: existingName }) : t('provider.add'),
    fields: [
      {key: 'name', label: t('provider.id'), type: 'text', value: existingName || '', placeholder: t('provider.idPlaceholder'), disabled: isEdit},
      {key: 'baseUrl', label: t('provider.baseUrl'), type: 'text', value: p.baseUrl || '', placeholder: 'https://api.example.com/v1'},
      {key: 'apiKey', label: 'API Key', type: 'text', password: true, value: p.apiKey && p.apiKey !== '***' ? p.apiKey : '', placeholder: isEdit ? t('provider.keyPlaceholder') : t('provider.required')},
      {key: 'protocol', label: t('provider.protocol'), type: 'select', value: p.protocol || 'anthropic', options: [
        { value: 'anthropic', label: 'messages' },
        { value: 'openai', label: 'chat/completions' },
      ]},
      {key: 'models', label: t('provider.models'), type: 'models', value: initialModels},
    ],
    onConfirm(values) {
      const name = values.name.trim();
      if (!name) { window.__showToast?.(t('provider.idRequired'), 'error'); return; }
      if (/\s/.test(name)) { window.__showToast?.(t('provider.noSpaces'), 'error'); return; }
      let baseUrl = values.baseUrl.trim();
      if (!baseUrl) { window.__showToast?.(t('provider.baseUrlRequired'), 'error'); return; }
      // Auto-add trailing slash
      if (!baseUrl.endsWith('/')) baseUrl += '/';
      const apiKey = values.apiKey.trim() || '***';
      if (!isEdit && apiKey === '***') { window.__showToast?.(t('provider.keyRequired'), 'error'); return; }
      const validModels = values.models.filter(m => m.id && m.id.trim());
      if (validModels.length === 0) { window.__showToast?.(t('provider.modelRequired'), 'error'); return; }
      // chain-ctxbound entrance gate (paired with the engine-side clamp):
      // bound per model = live fetched truth (contextLengthFor) → persisted
      // row truth carried from the edit form (the sibling llmstall-fix batch's
      // B5 data-model-max, post-merge) → absolute absurdity ceiling. Absurd /
      // over-truth values are REJECTED with a visible toast (nothing saved);
      // empty fields keep the 1M panel default and are surfaced in an info
      // toast after a successful save (钳制 + 明示, author 2026-09-21 裁定⑵).
      const defaultedIds = [];
      const clampedRows = [];
      const checkedModels = [];
      for (const m of validModels) {
        const liveMax = contextLengthFor(m.id);
        const res = validateContextWindow(m.contextWindowRaw,
          liveMax ?? (Number.isFinite(m.modelMaxContext) ? m.modelMaxContext : null));
        if (!res.ok) {
          window.__showToast?.(t(res.errKey, { id: m.id, ...res.errParams }), 'error');
          return;
        }
        if (res.note === 'defaulted-empty') defaultedIds.push(m.id);
        if (res.note === 'clamped-to-truth') clampedRows.push({ id: m.id, max: res.value });
        // Normalization ownership = this check point: `contextWindow` takes the
        // validator's output value (the raw string never reaches the payload).
        // The B5 persistence key rides along unchanged — dropping it here would
        // silently wipe the engine-side clamp bound on every save.
        /** @type {{id:string, contextWindow:number, modelMaxContext?:number}} */
        const checked = { id: m.id, contextWindow: res.value };
        if (Number.isFinite(m.modelMaxContext) && m.modelMaxContext > 0) checked.modelMaxContext = m.modelMaxContext;
        checkedModels.push(checked);
      }
      // Vision is never written from this form (B1 裁定 2026-08-25): the
      // edit-modal checkbox snapshot polluted nebflow.json ModelConfig.vision
      // (inline outranks models.json runtime annotations). An explicit inline
      // vision set by hand rides through untouched; new models omit the key.
      const modelsOut = checkedModels.map(m => {
        const prev = (p.models || []).find(x => x.id === m.id);
        return prev && prev.vision !== undefined ? { ...m, vision: prev.vision } : m;
      });
      onSave(name, {
        baseUrl,
        apiKey,
        protocol: values.protocol,
        models: modelsOut,
      });
      if (clampedRows.length) {
        for (const c of clampedRows) {
          window.__showToast?.(t('provider.ctxClampedToMax', { id: c.id, max: c.max.toLocaleString() }), 'info');
        }
      }
      if (defaultedIds.length) {
        window.__showToast?.(t('provider.ctxDefaulted', { ids: defaultedIds.join('、'), def: CTX_WINDOW_DEFAULT.toLocaleString() }), 'info');
      }
    }
  });
  // baseUrl/apiKey change → auto-fetch model list (dropdown); degrades to
  // manual input when the proxy endpoint is unavailable.
  wireProviderModelFetch();
  // protocol select → live POST-target helper (render-only, zero network).
  wireProtocolHelper();
}

/** Protocol select → helper line under it showing the POST target the engine
 *  will hit for the currently selected face + the currently typed baseUrl
 *  (protoface-ui batch 2026-09-22). Precedent for the "small muted status line
 *  inside a .cfg-form-group" shape = the model-list fetch row (:1699-1704).
 *  Live on both inputs; 🔴 pure rendering — no fetch, no send, no config write. */
function wireProtocolHelper() {
  const overlay = document.getElementById('cfg-modal');
  if (!overlay) return;
  // checkJs: querySelector returns Element; the casts pin the two element
  // types this function reads `.value` from (no new baseline rows).
  const select = /** @type {HTMLSelectElement|null} */ (overlay.querySelector('[data-field="protocol"]'));
  const baseInput = /** @type {HTMLInputElement|null} */ (overlay.querySelector('[data-field="baseUrl"]'));
  if (!select || !baseInput) return;

  const line = document.createElement('div');
  line.className = 'cfg-hint cfg-protocol-helper';
  line.dataset.helper = 'protocol';
  select.closest('.cfg-form-group')?.appendChild(line);

  const paint = () => {
    const target = protocolPostTarget(select.value, baseInput.value);
    line.textContent = target ? t('provider.protocolHelper', { target }) : '';
  };
  select.addEventListener('change', paint);
  // 'input' too: the target follows the baseUrl keystroke-by-keystroke.
  baseInput.addEventListener('input', paint);
  paint();
}

// --- Generic modal ---
function showModal({title, fields, onConfirm}) {
  // Remove existing modal
  document.getElementById('cfg-modal')?.remove();

  // Option shape (protoface-ui batch 2026-09-22): a plain string means
  // value === label and keeps the legacy interpolation VERBATIM (byte-identical
  // output for every string input, so the other string-array selects render
  // exactly as before); `{value, label}` is the new shape — value is the stored
  // value, label is display-only, and both are escaped (new surface).
  const optionHtml = (o, selected) => {
    const obj = o && typeof o === 'object';
    const value = obj ? o.value : o;
    const label = obj ? o.label : o;
    return `<option value="${obj ? escapeHtml(value) : value}" ${value === selected ? 'selected' : ''}>${obj ? escapeHtml(label) : label}</option>`;
  };
  const renderField = (f) => `
          <div class="cfg-form-group">
            <label class="cfg-label">${escapeHtml(f.label)}</label>
            ${f.type === 'select' ? `<select class="cfg-input" data-field="${f.key}" ${f.disabled ? 'disabled' : ''}>
              ${f.options.map(o => optionHtml(o, f.value)).join('')}
            </select>` : f.type === 'textarea' ? `<textarea class="cfg-input cfg-textarea" data-field="${f.key}" placeholder="${escapeHtml(f.placeholder || '')}">${escapeHtml(f.value || '')}</textarea>` :
            f.type === 'models' ? `<div class="cfg-models-container" data-field="${f.key}">
              ${f.value.map((m, i) => renderModelRow(m, i)).join('')}
              <button class="cfg-model-add" type="button">${t('model.add')}</button>
            </div>` :
            f.password ? `<div class="cfg-password-wrap"><input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" autocomplete="off" style="-webkit-text-security:disc" ${f.disabled ? 'disabled' : ''}><button class="cfg-eye-btn" type="button" tabindex="-1" aria-label="Toggle visibility">${eyeSvg}</button></div>` :
            f.type === 'number' ? `<input class="cfg-input" type="number" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${f.min != null ? `min="${f.min}"` : ''} ${f.disabled ? 'disabled' : ''}>` :
            `<input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${f.disabled ? 'disabled' : ''}>`}
          </div>`;

  const overlay = document.createElement('div');
  overlay.id = 'cfg-modal';
  overlay.className = 'cfg-modal-overlay';
  overlay.innerHTML = `
    <div class="cfg-modal">
      <div class="cfg-modal-title">${escapeHtml(title)}</div>
      <div class="cfg-modal-body">
        ${fields.map(renderField).join('')}
      </div>
      <div class="cfg-modal-actions">
        <button class="cfg-btn cfg-btn-cancel" id="cfg-modal-cancel">${t('modal.cancel')}</button>
        <button class="cfg-btn cfg-btn-save" id="cfg-modal-save">${t('settings.save')}</button>
      </div>
    </div>`;

  document.body.appendChild(overlay);

  // Wire up models add/remove
  overlay.querySelectorAll('.cfg-model-add').forEach(btn => {
    btn.addEventListener('click', () => {
      const container = btn.parentElement;
      const row = document.createElement('div');
      row.className = 'cfg-model-row';
      row.innerHTML = renderModelRowContent();
      container.insertBefore(row, btn);
      row.querySelector('.cfg-model-remove').addEventListener('click', () => row.remove());
      row.querySelector('.cfg-model-id').focus();
    });
  });
  overlay.querySelectorAll('.cfg-model-remove').forEach(btn => {
    btn.addEventListener('click', () => btn.closest('.cfg-model-row').remove());
  });

  // Wire up eye toggle
  overlay.querySelectorAll('.cfg-eye-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const input = btn.parentElement.querySelector('input');
      const isMasked = input.style.webkitTextSecurity !== 'none';
      input.style.webkitTextSecurity = isMasked ? 'none' : 'disc';
      btn.innerHTML = isMasked ? eyeOffSvg : eyeSvg;
    });
  });

  const close = () => overlay.remove();
  document.getElementById('cfg-modal-cancel').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.getElementById('cfg-modal-save').addEventListener('click', () => {
    const values = {};
    overlay.querySelectorAll('[data-field]:not([data-field="models"])').forEach(el => {
      values[el.dataset.field] = el.value;
    });
    // Collect models
    const modelsContainer = overlay.querySelector('[data-field="models"]');
    if (modelsContainer) {
      values.models = [];
      modelsContainer.querySelectorAll('.cfg-model-row').forEach(row => {
        const id = row.querySelector('.cfg-model-id').value.trim();
        if (!id) return;
        // chain-ctxbound: carry the RAW input string — no parseInt()||1000000
        // coercion here (that line silently saved absurd values, and
        // parseInt("1e9") === 1 truncated scientific notation). The raw string
        // is a VALIDATION INPUT only; normalization happens at the
        // showProviderModal.onConfirm check point against the provider-reported
        // truth (validateContextWindow) and never rides into the saved payload.
        const ctxEl = /** @type {HTMLInputElement|null} */ (row.querySelector('.cfg-model-ctx'));
        /** @type {{id:string, contextWindowRaw:string, modelMaxContext?:number}} */
        const entry = { id, contextWindowRaw: ctxEl ? ctxEl.value : '' };
        // B5 persistence contract (llmstallfix batch, merged): the provider-
        // reported truth upper bound is carried on the row as data-model-max
        // (rendered by renderModelRowContent, recorded by fillContextIfEmpty)
        // and is read back here under its persisted key name `modelMaxContext`,
        // so onConfirm's rebuilt payload re-emits it. Unknown ⇒ the key is NOT
        // written (backend `modelMaxContext = None` ⇒ the effective value stays
        // byte-identical to `contextWindow` = the old behaviour; banned to
        // tighten opportunistically).
        const maxCtx = ctxEl ? parseInt(ctxEl.dataset.modelMax || '', 10) : NaN;
        if (Number.isFinite(maxCtx) && maxCtx > 0) entry.modelMaxContext = maxCtx;
        values.models.push(entry);
      });
    }
    onConfirm(values);
    close();
  });
}

function renderModelRowContent(m) {
  const id = m ? m.id : '';
  const ctx = m ? m.contextWindow : '';
  // B5：真值上界随行渲染——编辑既有 provider 时不得把它丢掉（保存路径从行内读回；
  // 丢掉 = 该模型的 clamp 防线在下次保存后静默失效）。缺席 ⇒ 不渲染该属性。
  const maxAttr = m && Number.isFinite(m.modelMaxContext) ? ` data-model-max="${m.modelMaxContext}"` : '';
  const idField = providerModelChoices && providerModelChoices.length > 0
    ? renderModelIdSelect(id)
    : `<input class="cfg-input cfg-model-id" type="text" value="${escapeHtml(id)}" placeholder="${t('model.idPlaceholder')}">`;
  // Vision is auto-detected at runtime (B3) and shown as a read-only badge on
  // provider cards — no per-model control in this form (B1 裁定 2026-08-25).
  // maxTokens control removed (maxcfg batch 2026-09-16, author ruling): the
  // output cap is an internal engine constant, not user config.
  return `${idField}
<input class="cfg-input cfg-model-ctx" type="number" value="${ctx}"${maxAttr} placeholder="${t('model.contextPlaceholder')}">
<button class="cfg-model-remove" type="button" title="${t('provider.remove')}">&times;</button>`;
}

function renderModelRow(m, index) {
  return `<div class="cfg-model-row">${renderModelRowContent(m)}</div>`;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// sidebar.js 主体(renderSettings/renderProviderCard/bindSettingsEvents)与本模块
// 其余模块的单向消费面(sidebar.js 不得被反向 import)。
export { protocolFormLabel, escapeHtml, flushConfigToServer, showProviderModal };
