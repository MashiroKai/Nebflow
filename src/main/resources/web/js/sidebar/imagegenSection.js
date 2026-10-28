// imagegenSection.js — 设置页「生图」配置节（settings-panel-jev-imagegen 批，
// 2026-10-28 作者令 #2「把生图功能解禁。展示出来。…进设置里配置 key」）。
//
// 两形态（作者令逐字：「seal=OFF 构建下面板显示『未编入（release 构建零痕迹）』
// 提示位；seal=ON 构建显示正常配置面——两形态都要有 spec」）：
//   · compiledIn=false（release / 未开 STAND_IMAGEGEN 的 dev 构建）→ 只渲染
//     「未编入（release 构建零痕迹）」提示位 + 调试中徽标；
//   · compiledIn=true → 正常配置面：enabled 开关、当前 provider/model 编辑、
//     keyRef 指针显示、key 配置状态（已配置 / 待录入）+ key 录入。
//
// 数据源 = GET /api/config/imagegen（owner 鉴权，ConfigService.imagegenStatus
// 单点投影：compiledIn seal 常量 + 配置热读 + keyRef **在位性**，永不回传 key
// 值）。写通道 = PUT /api/config/imagegen（**整节替换**——本模块每次提交全量
// 目标形态：所有 provider 原样保留、仅被编辑的那一项被覆写）；key 录入走既有
// POST /api/secrets/<keyRef>（0600 落盘；409 冲突 = 已存在 → PUT 覆写重录），
// 值永不进配置文件/日志/回显（保存后输入框清空，回「已配置」态）。
//
// 🔴 边界申报（同 ConfigService 处）：本节是**设置/状态面**，release 构建照常
// 在（「未编入」提示位正需要它）——能力面的零痕迹锚（工具注册段 / ImageGenRoutes
// / 前端 imagegen.js DCE）不经过这里。「调试中」徽标延续工具面/插件面同一枚
// `.imagegen-badge`（chat.css 单源，视觉裁定「低调描边小徽标」）。
//
// 引擎 graceful degrade 语义照抄不改：缺 key = 生图腿自动关（Disabled）——面板
// 在缺 key 态明示「待录入」+ 提示行，配置面其余控件照常可编辑（配置先行，key
// 后补即恢复，与 ImageGenService.readSecret 的腿关判据逐字同源）。

import { t } from '../i18n.js';
import { escapeHtml, statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { toggleHTML, setToggleState } from '../toggle.js';
import { eyeSvg, eyeOffSvg } from './providersUi.js';

// 适配器协议面三态（镜像 ImageGenAdapters.All 的 `type` 注册表——前端无后端
// 常量导入面，此处是唯一拼写点；引擎侧以 byType 校验，typos 400 不落盘）。
const PROVIDER_TYPES = ['cogview', 'openai-images', 'imagen'];

/** Module-scope cache of the last status payload (re-fetched on each bind). */
let lastStatus = null;

/** The provider currently being edited ('' = first of the map). */
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

/**
 * The edited provider's fieldset (cfg-form-group stacked = STT-advanced style,
 * JeV 重排批同基准). Reads module state (activeProvider + lastStatus).
 * @returns {string}
 */
function providerFieldsHTML() {
  const providers = (lastStatus && lastStatus.providers) || {};
  const names = Object.keys(providers);
  const name = providers[activeProvider] ? activeProvider : (names[0] || '');
  if (!name) {
    return `<div class="cfg-hint">${escapeHtml(t('settings.imagegenNoProvider'))}</div>`;
  }
  const p = /** @type {any} */ (providers[name]) || {};
  const keyState = p.keyConfigured
    ? `<span class="st-badge" data-tone="ok">${escapeHtml(t('settings.imagegenKeyConfigured'))}</span>`
    : `<span class="st-badge" data-tone="warn">${escapeHtml(t('settings.imagegenKeyMissing'))}</span>`;
  const typeOpts = PROVIDER_TYPES.map(tp =>
    `<option value="${tp}"${tp === p.type ? ' selected' : ''}>${tp}</option>`).join('');
  return `
    <div class="cfg-form-group" data-imagegen-provider="${escapeHtml(name)}">
      <label class="cfg-label">${escapeHtml(t('settings.imagegenProvider'))}</label>
      <select class="cfg-input" id="cfg-imagegen-provider">
        ${names.map(n => `<option value="${escapeHtml(n)}"${n === name ? ' selected' : ''}>${escapeHtml(n)}</option>`).join('')}
      </select>
      <label class="cfg-label">${escapeHtml(t('settings.imagegenType'))}</label>
      <select class="cfg-input" id="cfg-imagegen-type">${typeOpts}</select>
      <label class="cfg-label">${escapeHtml(t('settings.imagegenModel'))}</label>
      <input class="cfg-input" id="cfg-imagegen-model" type="text" value="${escapeHtml(p.model || '')}" placeholder="cogview-3-flash" autocomplete="off" spellcheck="false">
      <label class="cfg-label">${escapeHtml(t('settings.imagegenBaseUrl'))}</label>
      <input class="cfg-input" id="cfg-imagegen-baseurl" type="text" value="${escapeHtml(p.baseUrl || '')}" placeholder="${escapeHtml(t('settings.imagegenBaseUrlPlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label">${escapeHtml(t('settings.imagegenKeyRef'))}</label>
      <div class="cfg-imagegen-keyrow">
        <input class="cfg-input" id="cfg-imagegen-keyref" type="text" value="${escapeHtml(p.keyRef || '')}" placeholder="zhipu-api-key" autocomplete="off" spellcheck="false">
        ${keyState}
      </div>
      <div class="cfg-hint">${escapeHtml(t('settings.imagegenKeyHint'))}</div>
      <label class="cfg-label">${escapeHtml(t('settings.imagegenKeyEntry'))}</label>
      <div class="cfg-password-wrap">
        <input class="cfg-input" id="cfg-imagegen-key" type="text" value="" placeholder="${escapeHtml(t(p.keyConfigured ? 'settings.imagegenKeyReentryPlaceholder' : 'settings.imagegenKeyPlaceholder'))}" autocomplete="off" spellcheck="false" style="-webkit-text-security:disc">
        <button type="button" class="cfg-eye-btn" id="imagegen-key-eye" tabindex="-1" aria-label="${escapeHtml(t('settings.imagegenKeyShow'))}" aria-pressed="false" title="${escapeHtml(t('settings.imagegenKeyShow'))}">${eyeSvg}</button>
      </div>
      <div style="display:flex;gap:8px;margin-top:8px;align-items:center;flex-wrap:wrap">
        <button class="cfg-btn" id="btn-save-imagegen-key">${escapeHtml(t('settings.imagegenKeySave'))}</button>
        <button class="cfg-btn cfg-btn-primary" id="btn-save-imagegen">${escapeHtml(t('settings.save'))}</button>
        <span class="inline-state" id="imagegen-save-state" role="status" aria-live="polite"></span>
      </div>
      ${p.keyConfigured ? '' : `<div class="cfg-hint" style="margin-top:6px">${escapeHtml(t('settings.imagegenMissingKeyHint'))}</div>`}
    </div>`;
}

/**
 * The seal=ON face: enabled toggle + provider/model/key editing.
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
      <div class="cfg-hint">${escapeHtml(t('settings.imagegenEnabledHint'))}</div>
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

  // Provider switch → repaint fields for that provider (in-module state).
  document.getElementById('cfg-imagegen-provider')?.addEventListener('change', function () {
    activeProvider = /** @type {HTMLSelectElement} */ (this).value;
    const fields = document.getElementById('imagegen-provider-fields');
    if (fields) {
      fields.innerHTML = providerFieldsHTML();
      bindSectionBody(wrap);
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

  // Key entry: the dedicated 保存密钥 button (secrets-face write; the value
  // never enters the config-section PUT below).
  document.getElementById('btn-save-imagegen-key')?.addEventListener('click', () => {
    const sel = /** @type {HTMLSelectElement|null} */ (document.getElementById('cfg-imagegen-provider'));
    const name = (sel && sel.value) || activeProvider;
    void saveImagegenKey(name);
  });

  // Save (whole-section PUT): patch ONLY the edited provider, keep the rest.
  document.getElementById('btn-save-imagegen')?.addEventListener('click', async function () {
    const providers = (lastStatus && lastStatus.providers) || {};
    const sel = /** @type {HTMLSelectElement|null} */ (document.getElementById('cfg-imagegen-provider'));
    const name = (sel && sel.value) || activeProvider || Object.keys(providers)[0] || '';
    if (!name) return;
    const prev = /** @type {any} */ (providers[name]) || {};
    const model = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-model'))?.value.trim() || '';
    if (!model) {
      window.__showToast?.(t('settings.imagegenModelRequired'), 'error');
      return;
    }
    const type = /** @type {HTMLSelectElement|null} */ (document.getElementById('cfg-imagegen-type'))?.value || prev.type || 'cogview';
    const baseUrl = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-baseurl'))?.value.trim() || '';
    const keyRef = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-keyref'))?.value.trim() || '';
    const enabled = document.getElementById('toggle-imagegen')?.classList.contains('on') || false;
    // 全量目标形态：其余 provider 原样保留（keyConfigured 是投影字段，不回写；
    // defaultProvider = 被编辑的 provider——面板「当前 provider」语义）。
    /** @type {Record<string, Record<string, string>>} */
    const outProviders = {};
    for (const [n, raw] of Object.entries(providers)) {
      const p = /** @type {any} */ (raw);
      /** @type {Record<string, string>} */
      const row = { type: p.type || 'cogview', model: p.model || '' };
      if (p.baseUrl) row.baseUrl = p.baseUrl;
      if (p.keyRef) row.keyRef = p.keyRef;
      outProviders[n] = row;
    }
    /** @type {Record<string, string>} */
    const patched = { type, model };
    if (baseUrl) patched.baseUrl = baseUrl;
    if (keyRef) patched.keyRef = keyRef;
    outProviders[name] = patched;
    const body = { enabled, providers: outProviders };
    if (keyRef) body.defaultProvider = name;
    let ok = false;
    try {
      const resp = await fetch('/api/config/imagegen', {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      ok = resp.ok;
      if (!resp.ok) {
        const data = await resp.json().catch(() => null);
        window.__showToast?.((data && /** @type {any} */ (data).error) || t('settings.saveFailed'), 'error');
      }
    } catch { ok = false; }
    flashSaveState(ok);
    if (ok) await bindImagegenSection();
  });
}

/**
 * Save the typed key for the named provider's keyRef (spec seam + the key
 * row's button path). The value's only appearance is the request body; the
 * field clears after save and the refreshed status paints the 已配置 state.
 * @param {string} name provider id ('' = activeProvider)
 * @returns {Promise<boolean>}
 */
export async function saveImagegenKey(name) {
  const providers = (lastStatus && lastStatus.providers) || {};
  const target = (name && providers[name]) ? name : activeProvider;
  const p = /** @type {any} */ (providers[target]) || {};
  const keyRef = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-keyref'))?.value.trim() || p.keyRef || '';
  const value = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'))?.value || '';
  if (!keyRef) {
    window.__showToast?.(t('settings.imagegenKeyRefRequired'), 'error');
    return false;
  }
  if (!value) {
    window.__showToast?.(t('settings.imagegenKeyEmpty'), 'error');
    return false;
  }
  // POST create first; 409 (already exists) → PUT overwrite (重录同语义).
  let ok = false;
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
    ok = resp.ok;
    if (!ok) window.__showToast?.(t('settings.imagegenKeySaveFailed', { code: String(resp.status) }), 'error');
  } catch {
    window.__showToast?.(t('settings.imagegenKeySaveFailed', { code: 'io' }), 'error');
  }
  if (ok) {
    const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'));
    if (input) input.value = ''; // 值不回显、不滞留——保存后清空回已配置态
    window.__showToast?.(t('settings.imagegenKeySaved'), 'success');
    await bindImagegenSection(); // re-fetch → keyConfigured=true 面刷新
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
    if (!activeProvider || !lastStatus.providers[activeProvider]) activeProvider = names[0] || '';
  }
  wrap.innerHTML = renderImagegenFace(lastStatus);
  bindSectionBody(wrap);
}
