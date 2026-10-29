// imagegenSection.js — 设置页「生图」配置节（settings-panel-jev-imagegen 批
// 2026-10-28 初版；settings-panel-fourfix 批 ③件 2026-10-29 作者令重做：
// 「配置风格对齐 JeV 面板那种普通 LLM 配置：就是普通配置过程——填 API Key 值、
// 保存，完事」「不要过拟合智谱——生图要支持所有生图模型/提供方」「整体精简，
// 现在感觉很乱」）。
//
// 两形态（seal 面，作者令 2026-10-28 保留不动）：
//   · compiledIn=false（release / 未开 STAND_IMAGEGEN 的 dev 构建）→ 只渲染
//     「未编入（release 构建零痕迹）」提示位 + 调试中徽标；
//   · compiledIn=true → 正常配置面（本件重做后的形态，见下）。
//
// 重做后的配置面（JeV 同款普通 LLM 配置过程）：
//   启用生图 toggle ＋ 提供方（既有下拉 / ＋新增，全通用输入）＋ 协议适配
//   ＋ 模型 ＋ 自定义端点（可选）＋ API Key（掩码输入）＋ 一枚「保存」。
//   🔴 凭据面无感化（作者令「前端永远不出现引用/keyRef/secrets 指针这类字眼与
//   解释文案」）：面板只认「API Key 值」——keyRef 指针名由引擎状态投影携带
//   （既有 provider 原样沿用）或按提供方名自动派生（`imagegen-<name>-key`），
//   对用户不可见、不可编辑；值走既有 secrets 写面（0600 落盘），永不进配置
//   文件/日志/回显（保存后输入框清空）。新用户所见 = 全空（零智谱特化预填：
//   提供方走「新增」、协议适配占位「选择协议适配」、模型/端点/key 全空）。
//   整节写通道照旧 = PUT /api/config/imagegen（整节替换，其余 provider 原样
//   保留）；新增「删除此提供方」（两段式确认）。
//
// 数据源 = GET /api/config/imagegen（owner 鉴权，ConfigService.imagegenStatus
// 单点投影：compiledIn seal 常量 + 配置热读 + key 在位性，永不回传 key 值）。
// 引擎 graceful degrade 语义照抄不改：缺 key = 生图腿自动关（Disabled）——面板
// 在缺 key 态明示「待录入」徽标（不再有引擎内部措辞长文案）。

import { t } from '../i18n.js';
import { escapeHtml, statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { toggleHTML, setToggleState } from '../toggle.js';
import { eyeSvg, eyeOffSvg } from './providersUi.js';

// 适配器协议面三态（镜像 ImageGenAdapters.All 的 `type` 注册表——前端无后端
// 常量导入面，此处是唯一拼写点；引擎侧以 byType 校验，typos 400 不落盘）。
const PROVIDER_TYPES = ['cogview', 'openai-images', 'imagen'];

/** add-mode sentinel in the provider select. */
const ADD_SENTINEL = '__add__';

/** Module-scope cache of the last status payload (re-fetched on each bind). */
let lastStatus = null;

/** The provider currently being edited (existing name, or ADD_SENTINEL). */
let activeProvider = '';

/**
 * Fetch the status projection (GET /api/config/imagegen). Resolves null on
 * any failure — the panel then renders the error hint, never fake data.
 * @returns {Promise<object|null>}
 */
async function fetchStatus() {
  try {
    const resp = await fetch('/api/config/imagegen', { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch {
    return null;
  }
}

/**
 * The 调试中 badge — single source for the panel face (same glyph the tool
 * card / plugin face carry; design-六 验收⑤⑥ 纪律延续).
 * @returns {string}
 */
export function debugBadge() {
  return `<span class="imagegen-badge" title="${escapeHtml(t('settings.imagegenDebugTitle'))}">${escapeHtml(t('settings.imagegenDebug'))}</span>`;
}

/**
 * The seal=OFF face: the「未编入（release 构建零痕迹）」hint slot.
 * @returns {string}
 */
export function renderNotCompiledFace() {
  return `
    <div class="imagegen-face" data-form="not-compiled">
      <div class="imagegen-notcompiled">
        ${statusIcon('circle-slash', 13, '')}
        <span>${escapeHtml(t('settings.imagegenNotCompiled'))}</span>
      </div>
      <div class="cfg-hint">${escapeHtml(t('settings.imagegenNotCompiledHint'))}</div>
    </div>`;
}

/** The secret (pointer) name for a provider — the existing stored name when
 *  the engine projects one, else derived from the provider name. NEVER shown
 *  to the user (fourfix ③: the face is oblivious to the pointer mechanism). */
function keyRefFor(name) {
  const p = /** @type {any} */ (((lastStatus && lastStatus.providers) || {})[name]) || {};
  return (p.keyRef && String(p.keyRef).trim()) || `imagegen-${name}-key`;
}

/**
 * The edited provider's fieldset (cfg-form-group stacked = STT-advanced style,
 * JeV 重排批同基准). Reads module state (activeProvider + lastStatus).
 * 新用户空白底线（fourfix ③）：providers 空 = 全空新增面——提供方下拉只有
 * 「＋新增」、名称空、协议适配占位、模型/端点/key 全空，零智谱特化值。
 * @returns {string}
 */
function providerFieldsHTML() {
  const providers = (lastStatus && lastStatus.providers) || {};
  const names = Object.keys(providers);
  const isAdd = activeProvider === ADD_SENTINEL || (!names.includes(activeProvider) && names.length === 0);
  const name = isAdd ? '' : (providers[activeProvider] ? activeProvider : (names[0] || ''));
  const p = /** @type {any} */ (providers[name]) || {};
  const keyState = isAdd
    ? `<span class="st-badge" data-tone="warn">${escapeHtml(t('settings.imagegenKeyMissing'))}</span>`
    : (p.keyConfigured
      ? `<span class="st-badge" data-tone="ok">${escapeHtml(t('settings.imagegenKeyConfigured'))}</span>`
      : `<span class="st-badge" data-tone="warn">${escapeHtml(t('settings.imagegenKeyMissing'))}</span>`);
  const typeOpts = [
    `<option value="" disabled ${p.type ? '' : 'selected'}>${escapeHtml(t('settings.imagegenTypePlaceholder'))}</option>`,
    ...PROVIDER_TYPES.map((tp) => `<option value="${tp}"${tp === p.type ? ' selected' : ''}>${tp}</option>`),
  ].join('');
  return `
    <div class="cfg-form-group" data-imagegen-provider="${escapeHtml(name)}">
      <label class="cfg-label">${escapeHtml(t('settings.imagegenProvider'))}</label>
      <select class="cfg-input" id="cfg-imagegen-provider">
        ${names.map((n) => `<option value="${escapeHtml(n)}"${n === name ? ' selected' : ''}>${escapeHtml(n)}</option>`).join('')}
        <option value="${ADD_SENTINEL}"${isAdd ? ' selected' : ''}>${escapeHtml(t('settings.imagegenAddProvider'))}</option>
      </select>
      <input class="cfg-input" id="cfg-imagegen-newprovider" type="text" value="" placeholder="${escapeHtml(t('settings.imagegenProviderNamePlaceholder'))}" autocomplete="off" spellcheck="false" ${isAdd ? '' : 'hidden'}>
      <label class="cfg-label">${escapeHtml(t('settings.imagegenType'))}</label>
      <select class="cfg-input" id="cfg-imagegen-type">${typeOpts}</select>
      <label class="cfg-label">${escapeHtml(t('settings.imagegenModel'))}</label>
      <input class="cfg-input" id="cfg-imagegen-model" type="text" value="${escapeHtml(p.model || '')}" placeholder="${escapeHtml(t('settings.imagegenModelPlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label">${escapeHtml(t('settings.imagegenBaseUrl'))}</label>
      <input class="cfg-input" id="cfg-imagegen-baseurl" type="text" value="${escapeHtml(p.baseUrl || '')}" placeholder="${escapeHtml(t('settings.imagegenBaseUrlPlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label">${escapeHtml(t('settings.imagegenKeyEntry'))}</label>
      <div class="cfg-imagegen-keyrow">
        <div class="cfg-password-wrap">
          <input class="cfg-input" id="cfg-imagegen-key" type="text" value="" placeholder="${escapeHtml(t(p.keyConfigured && !isAdd ? 'settings.imagegenKeyReentryPlaceholder' : 'settings.imagegenKeyPlaceholder'))}" autocomplete="off" spellcheck="false" style="-webkit-text-security:disc">
          <button type="button" class="cfg-eye-btn" id="imagegen-key-eye" tabindex="-1" aria-label="${escapeHtml(t('settings.imagegenKeyShow'))}" aria-pressed="false" title="${escapeHtml(t('settings.imagegenKeyShow'))}">${eyeSvg}</button>
        </div>
        ${keyState}
      </div>
      <div style="display:flex;gap:8px;margin-top:8px;align-items:center;flex-wrap:wrap">
        <button class="cfg-btn cfg-btn-primary" id="btn-save-imagegen">${escapeHtml(t('settings.save'))}</button>
        ${isAdd ? '' : `<button class="cfg-btn" id="btn-delete-imagegen">${escapeHtml(t('settings.imagegenDelete'))}</button>`}
        <span class="inline-state" id="imagegen-save-state" role="status" aria-live="polite"></span>
      </div>
    </div>`;
}

/**
 * The seal=ON face: enabled toggle + one short hint + the provider fields.
 * @returns {string}
 */
export function renderCompiledFace() {
  const enabled = !!(lastStatus && lastStatus.enabled);
  return `
    <div class="imagegen-face" data-form="compiled">
      <div class="settings-row">
        <span class="settings-label">${escapeHtml(t('settings.imagegenEnabled'))}</span>
        ${toggleHTML({ on: enabled, id: 'toggle-imagegen', label: t('settings.imagegenEnabled') })}
      </div>
      <div class="cfg-hint">${escapeHtml(t('settings.imagegenShortHint'))}</div>
      <div id="imagegen-provider-fields">${providerFieldsHTML()}</div>
    </div>`;
}

/**
 * Pure dispatcher: status payload → the two faces' HTML (spec seam).
 * @param {object|null} status GET /api/config/imagegen payload
 * @returns {string}
 */
export function renderImagegenFace(status) {
  if (!status) {
    return `<div class="imagegen-face" data-form="error"><div class="cfg-hint">${escapeHtml(t('settings.imagegenStatusError'))}</div></div>`;
  }
  return status.compiledIn ? renderCompiledFace() : renderNotCompiledFace();
}

/** Flash the inline save-state slot (jev save-state precedent). */
function flashSaveState(ok) {
  const stateEl = document.getElementById('imagegen-save-state');
  if (!stateEl) return;
  stateEl.innerHTML = ok
    ? statusIcon('circle-check', 11, 'st-ok') + escapeHtml(t('settings.saved'))
    : statusIcon('circle-alert', 11, 'st-err') + escapeHtml(t('settings.saveFailed'));
  setTimeout(() => { if (stateEl.isConnected) stateEl.innerHTML = ''; }, 1500);
}

/** The provider name being edited (add mode = the typed new name). */
function currentProviderName() {
  if (activeProvider !== ADD_SENTINEL) return activeProvider;
  return /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-newprovider'))?.value.trim() || '';
}

/**
 * Write one secret value under the provider's (hidden) pointer name:
 * POST create first; 409 (already exists) → PUT overwrite (重录同语义).
 * The value's only appearance is the request body.
 * @param {string} keyRef pointer name (never user-visible)
 * @param {string} value the typed API key value
 * @returns {Promise<boolean>}
 */
async function writeSecret(keyRef, value) {
  try {
    let resp = await fetch(`/api/secrets/${encodeURIComponent(keyRef)}`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    });
    if (resp.status === 409) {
      resp = await fetch(`/api/secrets/${encodeURIComponent(keyRef)}`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ value }),
      });
    }
    if (!resp.ok) window.__showToast?.(t('settings.imagegenKeySaveFailed', { code: String(resp.status) }), 'error');
    return resp.ok;
  } catch {
    window.__showToast?.(t('settings.imagegenKeySaveFailed', { code: 'io' }), 'error');
    return false;
  }
}

/**
 * Build the whole-section PUT payload: all providers preserved verbatim, the
 * edited one patched (or the deleted one dropped). keyConfigured is a
 * projection field and never rides back.
 * @param {string} editedName '' = no edit patch (delete flow patches the map too)
 * @param {{type?: string, model?: string, baseUrl?: string, keyRef?: string}|null} [patch]
 * @param {string|null} [deleteName]
 * @param {boolean} [enabled]
 */
function sectionPayload(editedName, patch, deleteName, enabled) {
  const providers = (lastStatus && lastStatus.providers) || {};
  /** @type {Record<string, Record<string, string>>} */
  const out = {};
  for (const [n, raw] of Object.entries(providers)) {
    if (n === deleteName) continue;
    const p = /** @type {any} */ (raw);
    /** @type {Record<string, string>} */
    const row = { type: p.type || 'cogview', model: p.model || '' };
    if (p.baseUrl) row.baseUrl = p.baseUrl;
    if (p.keyRef) row.keyRef = p.keyRef;
    out[n] = row;
  }
  if (editedName && patch) out[editedName] = /** @type {Record<string, string>} */ (patch);
  /** @type {Record<string, unknown>} */
  const body = { enabled: enabled || false, providers: out };
  const def = (editedName && !deleteName) ? editedName : Object.keys(out)[0] || '';
  if (def && out[def] && out[def].keyRef) body.defaultProvider = def;
  return body;
}

async function putSection(body) {
  try {
    const resp = await fetch('/api/config/imagegen', {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      const data = await resp.json().catch(() => null);
      window.__showToast?.((data && /** @type {any} */ (data).error) || t('settings.saveFailed'), 'error');
      return false;
    }
    return true;
  } catch {
    return false;
  }
}

/**
 * Wire the events of one rendered compiled face (called after every repaint).
 * @param {HTMLElement} wrap
 */
function bindSectionBody(wrap) {
  if (!lastStatus || !lastStatus.compiledIn) return;
  const sw = document.getElementById('toggle-imagegen');
  sw?.addEventListener('click', function () {
    setToggleState(this, !this.classList.contains('on'));
  });

  // Provider switch → repaint fields for that provider (in-module state);
  // 「＋新增」= 全空新增面（新用户底线：看到的必须全空）。
  document.getElementById('cfg-imagegen-provider')?.addEventListener('change', function () {
    const v = /** @type {HTMLSelectElement} */ (this).value;
    activeProvider = v;
    const fields = document.getElementById('imagegen-provider-fields');
    if (fields) {
      fields.innerHTML = providerFieldsHTML();
      bindSectionBody(wrap);
      const nameInput = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-newprovider'));
      if (v === ADD_SENTINEL && nameInput) nameInput.focus();
    }
  });

  // Key input eye (entry field = type=text + inline disc = masked default;
  // 判掩码只认内联 disc，首击即揭示——providersUi showModal 同款读法).
  document.getElementById('imagegen-key-eye')?.addEventListener('click', () => {
    const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'));
    const eye = document.getElementById('imagegen-key-eye');
    if (!input || !eye) return;
    const style = /** @type {any} */ (input.style);
    const isMasked = style.webkitTextSecurity === 'disc';
    style.webkitTextSecurity = isMasked ? 'none' : 'disc';
    eye.innerHTML = isMasked ? eyeOffSvg : eyeSvg;
    const label = t(isMasked ? 'settings.imagegenKeyHide' : 'settings.imagegenKeyShow');
    eye.setAttribute('aria-label', label);
    eye.title = label;
    eye.setAttribute('aria-pressed', isMasked ? 'true' : 'false');
  });

  // 保存（唯一保存钮）：填 API Key 值、保存，完事——key 有值先写 secrets 面
  // （指针名对用户不可见），再整节 PUT 配置。
  document.getElementById('btn-save-imagegen')?.addEventListener('click', () => {
    void saveImagegenSection();
  });

  // 删除此提供方（两段式确认——btn-clear-nf-cache 先例）：第一击挂确认位，
  // 第二击真删（整节 PUT 少该项）。
  const delBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-delete-imagegen'));
  delBtn?.addEventListener('click', function () {
    if (this.dataset.armed !== '1') {
      this.dataset.armed = '1';
      this.textContent = t('settings.imagegenDeleteConfirm');
      setTimeout(() => { if (this.isConnected) { this.dataset.armed = ''; this.textContent = t('settings.imagegenDelete'); } }, 4000);
      return;
    }
    const name = currentProviderName();
    if (!name) return;
    void putSection(sectionPayload('', null, name, document.getElementById('toggle-imagegen')?.classList.contains('on') || false))
      .then(async (ok) => {
        if (!ok) { flashSaveState(false); return; }
        window.__showToast?.(t('settings.imagegenDeleted'), 'success');
        activeProvider = '';
        await bindImagegenSection();
      });
  });
}

/**
 * The one-save flow (fourfix ③): typed key → secrets write under the hidden
 * pointer name; then the whole-section config PUT. Both-or-config-only: an
 * empty key field saves the config alone (re-entry optional).
 * @returns {Promise<boolean>}
 */
export async function saveImagegenSection() {
  const sel = /** @type {HTMLSelectElement|null} */ (document.getElementById('cfg-imagegen-provider'));
  const selectName = (sel && sel.value) || activeProvider;
  const name = selectName === ADD_SENTINEL
    ? (/** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-newprovider'))?.value.trim() || '')
    : selectName;
  if (!name) {
    window.__showToast?.(t('settings.imagegenNameRequired'), 'error');
    return false;
  }
  if (!/^[A-Za-z0-9._-]+$/.test(name)) {
    window.__showToast?.(t('settings.imagegenNameInvalid'), 'error');
    return false;
  }
  const type = /** @type {HTMLSelectElement|null} */ (document.getElementById('cfg-imagegen-type'))?.value || '';
  if (!type) {
    window.__showToast?.(t('settings.imagegenTypeRequired'), 'error');
    return false;
  }
  const model = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-model'))?.value.trim() || '';
  if (!model) {
    window.__showToast?.(t('settings.imagegenModelRequired'), 'error');
    return false;
  }
  const baseUrl = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-baseurl'))?.value.trim() || '';
  const keyValue = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'))?.value || '';
  const keyRef = keyRefFor(name);
  if (keyValue.trim()) {
    const okKey = await writeSecret(keyRef, keyValue.trim());
    if (!okKey) { flashSaveState(false); return false; }
  }
  /** @type {Record<string, string>} */
  const patch = { type, model };
  if (baseUrl) patch.baseUrl = baseUrl;
  patch.keyRef = keyRef;
  const enabled = document.getElementById('toggle-imagegen')?.classList.contains('on') || false;
  const ok = await putSection(sectionPayload(name, patch, null, enabled));
  flashSaveState(ok);
  if (ok) {
    const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'));
    if (input) input.value = ''; // 值不回显、不滞留——保存后清空回已配置态
    activeProvider = name; // 保存后面板停在刚保存的提供方上
    await bindImagegenSection();
  }
  return ok;
}

/**
 * Legacy single-purpose seam kept for the spec/external callers: save the
 * typed key for the edited provider ONLY (secrets write; no config PUT).
 * @param {string} name provider id ('' = active)
 * @returns {Promise<boolean>}
 */
export async function saveImagegenKey(name) {
  const target = (name && ((lastStatus && lastStatus.providers) || {})[name]) ? name
    : (activeProvider === ADD_SENTINEL ? '' : activeProvider);
  const value = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'))?.value || '';
  if (!target) {
    window.__showToast?.(t('settings.imagegenNameRequired'), 'error');
    return false;
  }
  if (!value.trim()) {
    window.__showToast?.(t('settings.imagegenKeyEmpty'), 'error');
    return false;
  }
  const ok = await writeSecret(keyRefFor(target), value.trim());
  if (ok) {
    const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'));
    if (input) input.value = '';
    window.__showToast?.(t('settings.imagegenKeySaved'), 'success');
    await bindImagegenSection();
  }
  return ok;
}

/**
 * Mount/refresh the section: fetch status → paint the two-face body → wire.
 * Idempotent; the settings panel calls this on every render.
 * @returns {Promise<void>}
 */
export async function bindImagegenSection() {
  const wrap = document.getElementById('imagegen-section-wrap');
  if (!wrap) return; // settings panel not open
  lastStatus = await fetchStatus();
  if (lastStatus && lastStatus.providers) {
    const names = Object.keys(lastStatus.providers);
    if (activeProvider !== ADD_SENTINEL && (!activeProvider || !lastStatus.providers[activeProvider])) {
      activeProvider = names[0] || ADD_SENTINEL;
    }
  }
  wrap.innerHTML = renderImagegenFace(lastStatus);
  bindSectionBody(wrap);
}
