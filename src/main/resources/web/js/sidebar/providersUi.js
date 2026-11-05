// sidebar.js 拆分(FE组件化批次二 2026-09-27):Provider 界面族 可复用模块(行为保持)。
// 覆盖 model-list auto-fetch(B1)+ Provider modal + Generic modal 三段,及随簇迁移的
// 闭包成员:Provider protocol face 常量与助手(sidebar.js 原 :29-57)、eyeSvg/eyeOffSvg
// (原 :70-71)、flushConfigToServer(原 :1607-1611,saveNewProvider 的写路径单点)。
// escapeHtml 随段落于本模块(sidebar.js 全文件共用,由此单向提供)。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
// authHeaders（Bearer 令牌头，flowHelpers 是 branding 叶子单依赖，无环）：
// 明文回读拉取与 secretsPanel.api 的鉴权头同一条 owner 面。
import { authHeaders } from '../flowHelpers.js';
// 探针单源（A 侧 uifix3 ② 抽共用）：POST /api/provider/models 的实现唯一属主 =
// ../providerModels.js（onboarding 模型题与设置面同用一份，禁第二份）。
import { fetchProviderModels } from '../providerModels.js';
// 连接性测试钮（fourfix 批 ②件，2026-10-29 作者令「连接性测试按钮应放到 LLM
// provider 配置页面里」+「保证测试通知能看得到」）：编辑弹窗每个模型行尾一枚
// 测试钮 + 弹窗级结果块（每模型一行全量文本，永不截断）。渲染/绑定/绘制单点
// = ./modelTestButton.js。
import { modelTestButtonHTML, testResultsBlockHTML, bindModelTestButtons } from './modelTestButton.js';
// 弹窗退场批（作者令 2026-10-31②）：手机层 provider 编辑窗铺满屏 + 左上返回（= 取消腿，桌面零变化）。
import { mobileSheetBack } from '../mobile/popSheet.js';
// 时间滚轮选择器（time-wheel-picker 实施批 2026-11-03，作者令 22:16/22:20）：
// provider 级与 per-model 级 unavailableWindows 的编辑面都收编为「段列表 + 原子段字段」，
// 交互单源 = ../timeWheel.js（禁第二份实现）。
import { rangeTriggerHTML, attachTimeTriggers } from '../timeWheel.js';

// ── Provider protocol face: display ←→ stored value (protoface-ui batch,
// 2026-09-22) ────────────────────────────────────────────────────────────────
// Stored values stay `openai` / `anthropic` forever (the engine's LlmProtocol
// decoder — config.scala:19-23 — is unchanged by this batch); only the FACE the
// user reads changes. The two id⇄endpoint-form pairs are the engine's own two
// adapters: OpenAiAdapter.scala:45-47 (`{base}/chat/completions`) and
// AnthropicAdapter.scala:30-32 (`{base}/v1/messages`).
//
// config-single-source 批（作者裁定⑤ 2026-11-05「解释性文字尽量减少」）：
// 旧的 `该服务商将收到：POST {url}` 请求预告行整体退役——它是**行为教学**型
// 说明（面板不预告引擎会做什么），且每次输入 baseUrl 就重绘一次。表单常驻
// 文字只留字段标签与值；必须解释的内容一律收进 `?` 悬停提示（hintField）。
const PROTOCOL_FORM_LABEL = { openai: 'chat/completions', anthropic: 'messages' };

/** Stored protocol id → the endpoint form shown to the user. An id this build
 *  does not know is shown verbatim (never silently re-labelled). */
function protocolFormLabel(protocol) {
  return PROTOCOL_FORM_LABEL[protocol] || protocol || '';
}

export const eyeSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
export const eyeOffSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

function flushConfigToServer() {
  const json = JSON.stringify(state.parsedConfig, null, 2);
  state.configText = json;
  sendWs({type: 'updateConfig', config: json});
}

// ── 设置页 apikey 明文回读（作者令 2026-10-27 10:33「设置里配置的 apikey，
//    直接显示就行了，比如 JeV 的以及我们 llm api 的」）────────────────────────
// GET /api/config/provider-keys（owner 鉴权，与设置写同门槛的 withAuth 面）→
// llm.providers.<id>.apiKey 真值，只落本模块内存 map，用于编辑弹窗把已存 key
// 预填成明文。JeV 配置节的 keyRef 无需此路径——那是指针名，config 读面本就
// 逐字回传（打码只发生在 provider apiKey / search.apiKey 形态字段上）。
// 🔴 纪律：明文只进弹窗输入框，不入 localStorage / 不打日志 / 不上卡面；
// 拉取失败或无鉴权 ⇒ map 保持空 ⇒ 弹窗退回既有空态（留空保存 = 回 "***" =
// 引擎保留旧值），保存路径零改动。60s 缓存与 refreshSecretsEntryCount 同约。
/** @type {Record<string, string>} */
let revealedProviderKeys = {};
/** @type {Promise<Record<string, string>>|null} */
let revealInflight = null;
let revealAt = 0;

/** Fetch (cached ≤60s, single-flight) the real provider apiKeys. Resolves
 *  with the map even on failure (empty map ⇒ legacy blank-field fallback). */
export function revealProviderApiKeys() {
  if (revealInflight) return revealInflight;
  if (revealAt && Date.now() - revealAt < 60000) return Promise.resolve(revealedProviderKeys);
  revealInflight = fetch('/api/config/provider-keys', { headers: authHeaders() })
    .then(r => (r.ok ? r.json() : null))
    .then(data => {
      // Defensive shaping: strings only — anything unexpected never lands.
      const provs = data && typeof data.providers === 'object' && data.providers ? data.providers : {};
      /** @type {Record<string, string>} */
      const next = {};
      for (const [id, v] of Object.entries(provs)) {
        if (typeof v === 'string' && v) next[id] = v;
      }
      revealedProviderKeys = next;
      revealAt = Date.now();
      return revealedProviderKeys;
    })
    .catch(() => revealedProviderKeys)
    .finally(() => { revealInflight = null; });
  return revealInflight;
}

/** The revealed real key for one provider id ('' = unknown ⇒ blank field). */
function revealedApiKeyFor(name) {
  return (name && revealedProviderKeys[name]) || '';
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

// uifix3 ②: `providerAuthHeaders` / `normalizeModelEntry` / `fetchProviderModels`
// moved verbatim to providerModels.js (the onboarding model question imports the
// SAME probe now — 复用不另造). This module keeps only the fetched-list STATE
// (providerModelChoices) and its readers (contextLengthFor / renderModelIdSelect).
// 合流落位（A 侧 sidebar.js :1388-:1410 改动随簇迁入本模块）：探针实现单源 =
// ../providerModels.js，本模块不再自持第二份。

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
  const baseInput = /** @type {HTMLInputElement|null} */ (overlay.querySelector('[data-field="baseUrl"]'));
  const keyInput = /** @type {HTMLInputElement|null} */ (overlay.querySelector('[data-field="apiKey"]'));
  const container = overlay.querySelector('[data-field="models"]');
  const group = container?.closest('.cfg-form-group');
  if (!baseInput || !container || !group) return;

  const statusRow = document.createElement('div');
  statusRow.className = 'cfg-fetch-status-row';
  statusRow.innerHTML = `<button type="button" class="cfg-fetch-models-btn">${t('provider.fetchModels')}</button><span class="cfg-fetch-status"></span>`;
  group.insertBefore(statusRow, container);
  const fetchBtn = /** @type {HTMLButtonElement} */ (statusRow.querySelector('.cfg-fetch-models-btn'));
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
    const hit = /** @type {Element} */ (e.target);
    if (hit.matches('select.cfg-model-id')) fillContextIfEmpty(hit.closest('.cfg-model-row'));
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
  // 模型不可用时段（model-schedule-unavailable 批 2026-10-29；滚轮批 2026-11-03 收编）：
  // 文本框逗号串 → 段列表（每段一颗原子段字段），days 由面板的星期多选面编辑，
  // 保存形状仍是后端的 `{from,to,days?}`（wire 契约零改）。
  const uwList = Array.isArray(p.unavailableWindows) ? p.unavailableWindows : [];
  // 明文直显（作者令 2026-10-27）：编辑既有 provider 时，config 读面上的
  // "***" 用回读真值替换（revealProviderApiKeys 已在设置页打开时预取、卡点
  // 点击时再兜一次）；回读未落地/失败 ⇒ '' = 既有空态（占位符提示留为 ***
  // 保持不变）。保存逻辑不变：字段里的明文原样进 payload，留空回 "***"。
  const apiKeyFace = p.apiKey && p.apiKey !== '***' ? p.apiKey : revealedApiKeyFor(existingName);

  showModal({
    title: isEdit ? t('provider.edit', { name: existingName }) : t('provider.add'),
    fields: [
      {key: 'name', label: t('provider.id'), type: 'text', value: existingName || '', placeholder: t('provider.idPlaceholder'), disabled: isEdit},
      {key: 'baseUrl', label: t('provider.baseUrl'), type: 'text', value: p.baseUrl || '', placeholder: 'https://api.example.com/v1'},
      {key: 'apiKey', label: 'API Key', type: 'text', password: true, value: apiKeyFace, placeholder: isEdit ? t('provider.keyPlaceholder') : t('provider.required')},
      {key: 'protocol', label: t('provider.protocol'), type: 'select', value: p.protocol || 'anthropic', options: [
        { value: 'anthropic', label: 'messages' },
        { value: 'openai', label: 'chat/completions' },
      ]},
      {key: 'models', label: t('provider.models'), type: 'models', value: initialModels},
      // 不可用时段 = **provider 级唯一一份**（config-single-source 批，作者裁定④
      // 2026-11-05「不需要逐 model 设置，按 provider 统一设置就行，一个 provider
      // 对应的行为是一致的」）。per-model 级编辑面已删；存量 model 级数据由引擎
      // 在加载/落盘时向上合并到本处（不丢数据）。
      {
        key: 'unavailableWindows',
        label: t('provider.unavailableWindows'),
        type: 'windows',
        value: uwList,
        hint: 'provider.unavailableWindowsHint',
      },
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
        /** @type {{id:string, contextWindow:number, modelMaxContext?:number, unavailableWindows?:{from:string,to:string,days?:number[]}[]}} */
        const checked = { id: m.id, contextWindow: res.value };
        if (Number.isFinite(m.modelMaxContext) && m.modelMaxContext > 0) checked.modelMaxContext = m.modelMaxContext;
        checkedModels.push(checked);
      }
      // Vision is never written from this form (B1 裁定 2026-08-25): the
      // edit-modal checkbox snapshot polluted nebflow.json ModelConfig.vision
      // (inline outranks models.json runtime annotations). An explicit inline
      // vision set by hand rides through untouched; new models omit the key.
      // per-model 不可用时段：编辑面已删（config-single-source 批，作者裁定④
      // 2026-11-05）——一个 provider 只有一份 provider 级时段，见下方 uwIn。
      // 模型行不再产出该键 ⇒ 保存后文件里 model 级条目随之消失（引擎侧已把它们
      // 向上合并进 provider 级，故零信息损失）。
      const modelsOut = checkedModels.map(m => {
        const prev = (p.models || []).find(x => x.id === m.id);
        return prev && prev.vision !== undefined ? { ...m, vision: prev.vision } : m;
      });
      // provider 级不可用时段：DOM 段列表即真相（时间滚轮选择器批 2026-11-03）。
      // 旧的「逗号分隔 HH:mm-HH:mm 文本框 + 正则解析」腿整体退役——它的两个
      // 结构性缺陷随之消失：①编辑永不下传（div 无 .value，values 恒 undefined
      // 走「未改动」分支）；②手工输入回读会丢 days。现在 from/to/days 都在
      // 触发钮 dataset 上，空列表 = 显式 null（深合并删键）。
      const uwIn = Array.isArray(values.unavailableWindows) ? values.unavailableWindows : [];
      // 起止相等是非法窗口（backend config.scala:9-35）；面板已禁用确认钮，
      // 此处兜一层拒存（同 validateContextWindow 的先例）。
      for (const w of uwIn) {
        if (!w.from || !w.to || w.from === w.to) {
          window.__showToast?.(t('provider.unavailableWindowsInvalid', { value: `${w.from}-${w.to}` }), 'error');
          return;
        }
      }
      const uwOut = uwIn.length ? uwIn : null;
      onSave(name, {
        baseUrl,
        apiKey,
        protocol: values.protocol,
        models: modelsOut,
        unavailableWindows: uwOut,
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
}

// --- Generic modal ---

/** 一段「不可用时段」行（原子段字段 + 按星期徽标 + 删除）。
 *  值域 = 触发钮 dataset（data-start / data-end / data-days），wire 形状仍 {from,to,days?}。
 *  @param {{from?:string, to?:string, days?:number[]}} w */
function windowRowHTML(w) {
  const days = Array.isArray(w.days) ? w.days.filter((d) => d >= 1 && d <= 7) : [];
  const hasDays = days.length > 0 && days.length < 7;
  return `
        <div class="tw-row" data-window-row>
          ${rangeTriggerHTML({
            start: w.from || '14:00',
            end: w.to || '18:00',
            label: t('provider.unavailableWindows'),
            presets: 'standard',
            daysAllowed: true,
            days: hasDays ? days : null,
          })}
          <span class="tw-row-note" data-window-days${hasDays ? '' : ' hidden'}>${hasDays ? t('provider.unavailableWindowsDays') + ' · ' + days.join(',') : ''}</span>
          <button type="button" class="tw-del" data-window-remove title="${t('settings.removeSegment')}" aria-label="${t('settings.removeSegment')}">×</button>
        </div>`;
}

/** @param {{from?:string,to?:string,days?:number[]}[]} list */
function windowsRowsHTML(list) {
  const rows = (list || []).map((w) => windowRowHTML(w)).join('').trim();
  return rows || `<div class="tw-empty">${t('provider.unavailableWindowsEmpty')}</div>`;
}

/** 从弹窗当前 DOM 收集段列表（唯一读面，正则解析已退役）。
 *  @param {HTMLElement} host */
function collectWindows(host) {
  const out = [];
  host.querySelectorAll('[data-window-row] [data-tw-range]').forEach((node) => {
    const btn = /** @type {HTMLElement} */ (node);
    /** @type {{from:string,to:string,days?:number[]}} */
    const w = { from: btn.dataset.start || '', to: btn.dataset.end || '' };
    const raw = btn.dataset.days;
    if (raw) {
      const days = String(raw).split(',').map(Number).filter((d) => d >= 1 && d <= 7).sort((a, b) => a - b);
      if (days.length && days.length < 7) w.days = days;
    }
    out.push(w);
  });
  return out;
}

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
  // config-single-source 批（作者裁定⑤ 2026-11-05「解释性文字尽量减少」）：
  // 版面只留字段标签与值；**必须解释**的内容收进标签尾的 `?` 悬停提示
  // （`f.hint` = 文案键或直文），不铺在版面上。字号/颜色沿用 .cfg-label 的
  // muted 面，`:hover`/`:focus-visible` 都可读出（title 也给出原生 tooltip）。
  const hintHTML = (f) => {
    if (!f.hint) return '';
    const text = f.hint.startsWith('provider.') || f.hint.includes('.') ? t(f.hint) : f.hint;
    return `<span class="cfg-hintbadge" tabindex="0" role="note" aria-label="${escapeHtml(text)}" title="${escapeHtml(text)}">?</span>`;
  };
  // spellcheck 纪律（input-focus-ring 批 2026-11-05，作者令「红波浪线一并处理」）：
  // 服务商表单里**每一个**字段都是机器取值（id / URL / key / 模型名 / 数字），
  // 没有一处自由散文 ⇒ 整脸关拼写检查，同时关掉移动端自动大写/自动更正
  // （否则 iOS 会把 key 首字母大写、把 URL 当单词改）。
  const NO_SPELL = 'spellcheck="false" autocapitalize="off" autocorrect="off"';
  const renderField = (f) => `
          <div class="cfg-form-group">
            <label class="cfg-label">${escapeHtml(f.label)}${hintHTML(f)}</label>
            ${f.type === 'select' ? `<select class="cfg-input" data-field="${f.key}" ${f.disabled ? 'disabled' : ''}>
              ${f.options.map(o => optionHtml(o, f.value)).join('')}
            </select>` : f.type === 'textarea' ? `<textarea class="cfg-input cfg-textarea" data-field="${f.key}" placeholder="${escapeHtml(f.placeholder || '')}" ${NO_SPELL}>${escapeHtml(f.value || '')}</textarea>` :
            f.type === 'models' ? `<div class="cfg-models-container" data-field="${f.key}">
              ${f.value.map((m, i) => renderModelRow(m, i)).join('')}
              <button class="cfg-model-add" type="button">${t('model.add')}</button>
            </div>` :
            f.type === 'windows' ? `<div class="tw-windows" data-field="${f.key}" data-windows>
              ${windowsRowsHTML(f.value)}
              <button class="tw-add-row" type="button" data-window-add>${t('provider.unavailableWindowsAdd')}</button>
            </div>` :
            f.password ? `<div class="cfg-password-wrap"><input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" autocomplete="off" ${NO_SPELL} ${f.disabled ? 'disabled' : ''}><button class="cfg-eye-btn" type="button" tabindex="-1" aria-label="Toggle visibility">${eyeOffSvg}</button></div>` :
            f.type === 'number' ? `<input class="cfg-input" type="number" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${NO_SPELL} ${f.min != null ? `min="${f.min}"` : ''} ${f.disabled ? 'disabled' : ''}>` :
            `<input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${NO_SPELL} ${f.disabled ? 'disabled' : ''}>`}
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
  // 返回 = 取消（overlay 摘除，不触发 onConfirm——与 #cfg-modal-cancel 同语义）。
  mobileSheetBack(overlay, () => overlay.remove());

  // 连接性测试（fourfix 批 ②件）：结果块挂在 models 组之下（弹窗级共享——
  // 每模型一行，全量文本），绑定走弹窗级委托（动态加行也被覆盖）。三个解析器
  // 都在点击时读表单现值：name/apiKey 输入框、protocol 下拉、行内 model 输入。
  const modelsGroup = overlay.querySelector('[data-field="models"]')?.closest('.cfg-form-group');
  if (modelsGroup) {
    modelsGroup.insertAdjacentHTML('afterend', testResultsBlockHTML());
    const fieldVal = (key) => /** @type {HTMLInputElement|HTMLSelectElement|null} */ (overlay.querySelector(`[data-field="${key}"]`))?.value?.trim() || '';
    bindModelTestButtons(overlay, {
      resultsEl: /** @type {HTMLElement} */ (overlay.querySelector('.cfg-test-results')),
      getProvider: () => fieldVal('name'),
      getModel: (btn) => {
        const row = btn.closest('.cfg-model-row');
        const el = row && /** @type {HTMLInputElement|HTMLSelectElement|null} */ (row.querySelector('.cfg-model-id'));
        return (el && el.value.trim()) || btn.dataset.testModel || '';
      },
      getOverrides: () => {
        const baseUrl = fieldVal('baseUrl');
        if (!baseUrl) return null; // 未填端点 = 无表单可测 → 存储配置腿（旧形状）
        return { baseUrl, apiKey: fieldVal('apiKey'), protocol: fieldVal('protocol') };
      },
    });
  }

  // Wire up models add/remove
  overlay.querySelectorAll('.cfg-model-add').forEach(btn => {
    btn.addEventListener('click', () => {
      const container = btn.parentElement;
      const row = document.createElement('div');
      row.className = 'cfg-model-row';
      row.innerHTML = renderModelRowContent();
      container.insertBefore(row, btn);
      row.querySelector('.cfg-model-remove').addEventListener('click', () => row.remove());
      (/** @type {HTMLElement} */ (row.querySelector('.cfg-model-id'))).focus();
    });
  });
  overlay.querySelectorAll('.cfg-model-remove').forEach(btn => {
    btn.addEventListener('click', () => btn.closest('.cfg-model-row').remove());
  });

  // Wire up eye toggle. 明文直显（作者令 2026-10-27）后默认态 = 已揭示：
  // 判掩码只认内联 disc（初始无内联样式 = 明文），首击即真实切换到遮蔽。
  overlay.querySelectorAll('.cfg-eye-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const input = btn.parentElement.querySelector('input');
      const inputStyle = /** @type {{webkitTextSecurity?: string}} */ (input.style);
      const isMasked = inputStyle.webkitTextSecurity === 'disc';
      inputStyle.webkitTextSecurity = isMasked ? 'none' : 'disc';
      btn.innerHTML = isMasked ? eyeOffSvg : eyeSvg;
    });
  });

  // ── 不可用时段组（时间滚轮选择器批 2026-11-03）：段行 + 加行/删行 + 滚轮面板。
  // 交互单源 = ../timeWheel.js（这里只做行级增删与徽标刷新，禁第二份面板）。
  /** @param {HTMLElement} host */
  const bindWindows = (host) => {
    // 加行：追加一段默认 14:00–18:00，空态占位随之摘除。
    host.querySelector('[data-window-add]')?.addEventListener('click', () => {
      host.querySelector('.tw-empty')?.remove();
      host.querySelector('[data-window-add]')?.insertAdjacentHTML('beforebegin', windowRowHTML({ from: '14:00', to: '18:00' }));
      const rows = host.querySelectorAll('[data-window-row]');
      const last = /** @type {HTMLElement|undefined} */ (rows[rows.length - 1]);
      /** @type {HTMLElement|null} */ (last?.querySelector('[data-tw-range]'))?.focus();
    });
    // 删行：删空回到空态占位（保存时 collectWindows 得空数组 = 清空语义）。
    host.addEventListener('click', (ev) => {
      const btn = ev.target instanceof Element ? ev.target.closest('[data-window-remove]') : null;
      if (!btn) return;
      ev.preventDefault();
      btn.closest('[data-window-row]')?.remove();
      if (!host.querySelector('[data-window-row]')) {
        host.insertAdjacentHTML('afterbegin', `<div class="tw-empty">${t('provider.unavailableWindowsEmpty')}</div>`);
      }
    });
    // 滚轮确认后刷新「按星期」徽标（星期多选与时段同一面板，O1 裁定）。
    attachTimeTriggers(host, {
      onRangeChange: (btn) => {
        const row = btn.closest('[data-window-row]');
        const note = row && /** @type {HTMLElement|null} */ (row.querySelector('[data-window-days]'));
        if (!note) return;
        const raw = btn.dataset.days || '';
        const days = raw ? raw.split(',').filter(Boolean) : [];
        if (days.length) {
          note.textContent = `${t('provider.unavailableWindowsDays')} · ${days.join(',')}`;
          note.hidden = false;
        } else {
          note.textContent = '';
          note.hidden = true;
        }
      },
    });
  };
  // provider 级段列表接滚轮绑定（config-single-source 批：per-model 级编辑面已删，
  // 这是唯一一份时段交互）。
  overlay.querySelectorAll('[data-field="unavailableWindows"]').forEach(bindWindows);

  const close = () => overlay.remove();
  document.getElementById('cfg-modal-cancel').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.getElementById('cfg-modal-save').addEventListener('click', () => {
    const values = {};
    // 段列表组（models / unavailableWindows）以各自专用读面收集，不走 .value。
    overlay.querySelectorAll('[data-field]:not([data-field="models"]):not([data-field="unavailableWindows"])').forEach(el => {
      const field = /** @type {HTMLElement & {value: string}} */ (el);
      values[field.dataset.field] = field.value;
    });
    // 不可用时段：DOM 是唯一真相（时间滚轮选择器批 2026-11-03）——旧的逗号
    // 正则解析腿已退役，空列表 = 显式清空（null）。
    const uwHost = /** @type {HTMLElement|null} */ (overlay.querySelector('[data-field="unavailableWindows"]'));
    if (uwHost) values.unavailableWindows = collectWindows(uwHost);
    // Collect models
    const modelsContainer = overlay.querySelector('[data-field="models"]');
    if (modelsContainer) {
      values.models = [];
      modelsContainer.querySelectorAll('.cfg-model-row').forEach(row => {
        const id = (/** @type {HTMLInputElement} */ (row.querySelector('.cfg-model-id'))).value.trim();
        if (!id) return;
        // chain-ctxbound: carry the RAW input string — no parseInt()||1000000
        // coercion here (that line silently saved absurd values, and
        // parseInt("1e9") === 1 truncated scientific notation). The raw string
        // is a VALIDATION INPUT only; normalization happens at the
        // showProviderModal.onConfirm check point against the provider-reported
        // truth (validateContextWindow) and never rides into the saved payload.
        const ctxEl = /** @type {HTMLInputElement|null} */ (row.querySelector('.cfg-model-ctx'));
        /** @type {{id:string, contextWindowRaw:string, modelMaxContext?:number, unavailableWindows?:{from:string,to:string,days?:number[]}[]}} */
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
        // per-model 不可用时段：编辑面已删（config-single-source 批，作者裁定④）——
        // 不再收集，也不再写该键。
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
    : `<input class="cfg-input cfg-model-id" type="text" value="${escapeHtml(id)}" placeholder="${t('model.idPlaceholder')}" spellcheck="false" autocapitalize="off" autocorrect="off">`;
  // Vision is auto-detected at runtime (B3) and shown as a read-only badge on
  // provider cards — no per-model control in this form (B1 裁定 2026-08-25).
  // maxTokens control removed (maxcfg batch 2026-09-16, author ruling): the
  // output cap is an internal engine constant, not user config.
  // 连接性测试钮（fourfix 批 ②件）：行尾一枚——data-test-provider 是渲染时已知
  // 名（add 弹窗为空串），绑定面在点击时读表单 name 字段现值（getProvider）。
  // per-model 不可用时段行已删（config-single-source 批，作者裁定④ 2026-11-05）：
  // 时段只在 provider 级一份，模型行回到「id + 上下文窗口 + 测试钮 + 删除」。
  return `${idField}
<input class="cfg-input cfg-model-ctx" type="number" value="${ctx}"${maxAttr} placeholder="${t('model.contextPlaceholder')}" spellcheck="false">
${modelTestButtonHTML('', id)}
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
