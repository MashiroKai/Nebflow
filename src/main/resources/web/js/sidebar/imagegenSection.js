// imagegenSection.js — 设置页「生图」配置节（settings-panel-jev-imagegen 批
// 2026-10-28 初版；settings-panel-fourfix 批 ③件 2026-10-29 作者令重做：
// 「配置风格对齐 JeV 面板那种普通 LLM 配置：就是普通配置过程——填 API Key 值、
// 保存，完事」「不要过拟合智谱——生图要支持所有生图模型/提供方」「整体精简，
// 现在感觉很乱」；功能开放批 2026-10-30 作者令「直接开放出来」：seal 缺省编入、
// 「调试中」徽标退役——本节为正式产品配置面）。
//
// jev-imagegen-align-llm 批（2026-11-02 08:50 作者令）：生图配置面按 **LLM 配置面**
// 逐项对齐（同组件/同层级/同交互），契约 reports/20261102_jev-imagegen-align-llm/
// contract.md §A / §B.5 / §C / §E.3：
//   · 空态起步：providers 空 ⇒ 0 张卡片、表单字段全空、协议无预选、模型/端点/
//     密钥全空（零 zhipu / cogview 特化预填，无「＋新增」哨兵式套餐下拉）；
//   · 已配置 ⇒ 卡片列表（`providerCardHTML`，js/cfgProviderCard.js —— 与 LLM / Jev
//     两面同一组件），可并存、可单删（两段式确认）；
//   · 表单只保留 **一个协议字段** `#cfg-imagegen-type`（label 与 LLM 面「协议」
//     同源 = provider.protocol；旧「协议适配」那层措辞与字段并入本字段，界面不出现
//     第二个下拉，也不新增 timeout 之类字段）；
//   · 密钥直接录入：`#cfg-imagegen-key`（内联 -webkit-text-security:disc）+ 眼钮，
//     零 keyRef / 指针 / 「密钥引用」字眼——pointer 名由引擎状态投影携带（既有
//     provider 原样沿用）或按提供方名派生（`imagegen-<name 小写>-key`，≤64 字符），
//     对用户不可见、不可编辑；值只进输入框与请求体，保存后清空、永不回显。
//   🔴 退役 id（契约 §E.3 退役清单，本文件已零命中）：旧的两枚——提供方下拉
//   （含 `__add__` 哨兵项与「＋ 新增提供方…」套餐项）与新增提供方名输入框。
//   整节写通道照旧 = PUT /api/config/imagegen（整节替换，其余 provider 原样保留）。
//
// 两形态（seal 面保留为应急开关，机制不动）：
//   · compiledIn=false（显式 `STAND_IMAGEGEN=0` 的剔除构建）→ 只渲染
//     「未编入（release 构建零痕迹）」提示位；
//   · compiledIn=true → 正常配置面（本件重做后的形态，见上）。
//
// 数据源 = GET /api/config/imagegen（owner 鉴权，ConfigService.imagegenStatus
// 单点投影：compiledIn seal 常量 + 配置热读 + key 在位性，永不回传 key 值）。
// 引擎 graceful degrade 语义照抄不改：缺 key = 生图腿自动关（Disabled）——面板
// 在缺 key 态明示「未配置」徽标（不再有引擎内部措辞长文案）。

import { t } from '../i18n.js';
import { escapeHtml, statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { toggleHTML, setToggleState } from '../toggle.js';
import { eyeSvg, eyeOffSvg } from './providersUi.js';
// 测试结果行的绘制基座与 LLM 测试钮同组件同口径（settings-panel-jev-imagegen
// 批 2026-10-29 作者令「然后 Jev/生图，也要像我们LLM这样，有个按钮来测试可用
// 性」）：spinner 行 / 判决行直接复用 modelTestButton 的绘制函数与 class 族。
import { paintTestingLine, paintTestResultLine } from './modelTestButton.js';
// 配置提供方卡片「唯一共用组件」（jev-imagegen-align-llm 批 · imgpanel 件，契约
// §A.1/§A.4：import 区接在 modelTestButton 之后）：生图卡片与 LLM / Jev 两面
// 同组件、同层级（.cfg-card-header / .cfg-card-meta / .cfg-model-list）、同交互。
import { providerCardHTML, addProviderButtonHTML, bindCardRemove, bindCardSelect } from '../cfgProviderCard.js';
import { setRow } from '../settingsRows.js';

// 适配器协议面三态（镜像 ImageGenAdapters.All 的 `type` 注册表——前端无后端
// 常量导入面，此处是唯一拼写点；引擎侧以 byType 校验，typos 400 不落盘）。
const PROVIDER_TYPES = ['cogview', 'openai-images', 'imagen'];

/** Module-scope cache of the last status payload (re-fetched on each bind). */
let lastStatus = null;

/** The provider currently loaded into the form; '' = 空表单（新增/空态）。 */
let activeProvider = '';

/** 未保存的启用态：卡片切换/新增重绘时保持用户刚点的开关，新投影到达即清。 */
let draftEnabled = null;

/** 两段式删除的中间态（按 provider 名；4s 无后续动作自动解除）。 */
let armedDeleteName = '';

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

/** The projection's provider map ({} when absent — 空态即 0 张卡片). */
function providerMap() {
  return /** @type {Record<string, any>} */ ((lastStatus && lastStatus.providers) || {});
}

/**
 * The secret name derived from the provider name — 契约 §C.2 单点规则：
 * `imagegen-<name 小写>-key`，≤64 字符（SecretsStore.NamePattern 只认小写，
 * MaxLen = 64）。名字对用户不可见（零 keyRef 字眼）。
 * @param {string} name provider 名（大小写原样，派生时小写化）
 * @returns {string}
 */
function secretNameFor(name) {
  return `imagegen-${String(name).toLowerCase()}-key`.slice(0, 64);
}

/** The secret (pointer) name for a provider — 既有 stored 名优先（旧配置不断腿），
 *  否则派生名（契约 §C.5）。NEVER shown to the user. */
function keyRefFor(name) {
  const p = providerMap()[name] || {};
  return (p.keyRef && String(p.keyRef).trim()) || secretNameFor(name);
}

/**
 * Key 在位性徽标（与 LLM / Jev 两面同一组共用键）。
 * @param {boolean} configured
 * @returns {string}
 */
function keyBadgeHTML(configured) {
  return configured
    ? `<span class="st-badge" data-tone="ok">${escapeHtml(t('settings.cfgCard.keyConfigured'))}</span>`
    : `<span class="st-badge" data-tone="warn">${escapeHtml(t('settings.cfgCard.keyMissing'))}</span>`;
}

/**
 * 卡片列表：0 张 = 一行空态提示（不是卡片，故 `.cfg-card` 计数恒为 0）。
 * 卡片本体一律由共用组件产出（红线③：三面 class 族与子元素序列逐字同构）。
 * @returns {string}
 */
function providerListHTML() {
  const map = providerMap();
  const names = Object.keys(map);
  // 空态提示与 Jev 面逐字同款（同一 `.cfg-hint` 包装 + 同一句文案，契约 §D.3 / §E.2）。
  if (!names.length) return `<div class="cfg-hint">${escapeHtml(t('settings.imagegen.emptyHint'))}</div>`;
  return names.map((name) => {
    const p = map[name] || {};
    return providerCardHTML({
      name,
      face: 'imagegen',
      // 协议徽标 = 该 provider 的 adapter type（cogview | openai-images | imagen），
      // 与 LLM 卡片的协议徽标同位置同口径（空则组件不出 .cfg-card-badge）。
      protocolBadge: p.type ? String(p.type) : '',
      // 生图 = provider↔model 绑定（契约 §B.6）：卡片上 1 行 .cfg-model-row。
      models: p.model ? [String(p.model)] : [],
      metaExtra: keyBadgeHTML(!!p.keyConfigured),
      active: name === activeProvider,
    });
  }).join('');
}

/**
 * The edited provider's fieldset (cfg-form-group stacked = STT-advanced style,
 * JeV 重排批同基准). Reads module state (activeProvider + lastStatus).
 * 空态铁律（契约红线①）：providers 空 = 全空表单——名称/模型/端点/key 全空、
 * 协议占位未预选（value=''），零 zhipu / cogview 特化值。
 * @returns {string}
 */
function providerFieldsHTML() {
  const map = providerMap();
  const isExisting = !!(activeProvider && map[activeProvider]);
  const p = isExisting ? map[activeProvider] : {};
  /** @type {Record<string, any>} */
  const prov = p || {};
  const name = isExisting ? activeProvider : '';
  // 协议字段 = 原「协议适配」下拉并入本字段（界面只有这一个下拉）：label 与 LLM
  // 面「协议」同源（provider.protocol），空态占位不预选任何 adapter。
  const typeOpts = [
    `<option value="" disabled ${prov.type ? '' : 'selected'}>—</option>`,
    ...PROVIDER_TYPES.map((tp) => `<option value="${tp}"${tp === prov.type ? ' selected' : ''}>${tp}</option>`),
  ].join('');
  return `
    <div class="cfg-form-group" data-imagegen-provider="${escapeHtml(name)}">
      <label class="cfg-label" for="cfg-imagegen-name">${escapeHtml(t('settings.imagegen.name'))}</label>
      <input class="cfg-input" id="cfg-imagegen-name" type="text" value="${escapeHtml(name)}" placeholder="${escapeHtml(t('settings.imagegen.namePlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label" for="cfg-imagegen-type">${escapeHtml(t('provider.protocol'))}</label>
      <select class="cfg-input" id="cfg-imagegen-type">${typeOpts}</select>
      <label class="cfg-label" for="cfg-imagegen-model">${escapeHtml(t('settings.imagegenModel'))}</label>
      <input class="cfg-input" id="cfg-imagegen-model" type="text" value="${escapeHtml(prov.model || '')}" placeholder="${escapeHtml(t('settings.imagegenModelPlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label" for="cfg-imagegen-baseurl">${escapeHtml(t('settings.imagegenBaseUrl'))}</label>
      <input class="cfg-input" id="cfg-imagegen-baseurl" type="text" value="${escapeHtml(prov.baseUrl || '')}" placeholder="${escapeHtml(t('settings.imagegenBaseUrlPlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label" for="cfg-imagegen-key">${escapeHtml(t('settings.imagegenKeyEntry'))}</label>
      <div class="cfg-imagegen-keyrow">
        <div class="cfg-password-wrap">
          <input class="cfg-input" id="cfg-imagegen-key" type="text" value="" placeholder="${escapeHtml(t(prov.keyConfigured && isExisting ? 'settings.imagegenKeyReentryPlaceholder' : 'settings.imagegenKeyPlaceholder'))}" autocomplete="off" spellcheck="false" style="-webkit-text-security:disc">
          <button type="button" class="cfg-eye-btn" id="imagegen-key-eye" tabindex="-1" aria-label="${escapeHtml(t('settings.imagegenKeyShow'))}" aria-pressed="false" title="${escapeHtml(t('settings.imagegenKeyShow'))}">${eyeSvg}</button>
        </div>
        ${keyBadgeHTML(!!prov.keyConfigured)}
      </div>
      <div class="cfg-card-action-row">
        <button class="cfg-btn cfg-btn-primary" id="btn-save-imagegen">${escapeHtml(t('settings.save'))}</button>
        <button class="cfg-btn" id="btn-test-imagegen" type="button" title="${escapeHtml(t('settings.imagegenTestTitle'))}" aria-label="${escapeHtml(t('settings.imagegenTestTitle'))}">${statusIcon('plug-zap', 12)}<span>${escapeHtml(t('settings.imagegenTestBtn'))}</span></button>
        <button class="cfg-btn" id="btn-imagegen-generate" type="button" title="${escapeHtml(t('settings.imagegenGenTitle'))}" aria-label="${escapeHtml(t('settings.imagegenGenTitle'))}">${statusIcon('image', 12)}<span>${escapeHtml(t('settings.imagegenGenBtn'))}</span></button>
        <button class="cfg-btn" id="btn-delete-imagegen" type="button" data-armed="${armedDeleteName === activeProvider && isExisting ? '1' : '0'}"${isExisting ? '' : ' disabled'}>${escapeHtml(armedDeleteName === activeProvider && isExisting ? t('settings.cfgCard.deleteConfirm') : t('settings.cfgCard.delete'))}</button>
        <span class="inline-state" id="imagegen-save-state" role="status" aria-live="polite"></span>
      </div>
    </div>
    <div class="cfg-test-results imagegen-test-results" data-role="test-results" role="status" aria-live="polite"></div>`;
}

/**
 * The seal=ON face: enabled toggle + one short hint + 卡片列表 ＋ 单一表单。
 * @returns {string}
 */
export function renderCompiledFace() {
  const enabled = draftEnabled === null ? !!(lastStatus && lastStatus.enabled) : draftEnabled;
  return `
    <div class="imagegen-face" data-form="compiled">
      ${setRow({
        title: escapeHtml(t('settings.imagegenEnabled')),
        control: toggleHTML({ on: enabled, id: 'toggle-imagegen', label: t('settings.imagegenEnabled') }),
        hint: escapeHtml(t('settings.imagegenShortHint')),
      })}
      <div id="imagegen-provider-list">${providerListHTML()}</div>
      ${addProviderButtonHTML({ id: 'btn-add-imagegen-provider', face: 'imagegen' })}
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

// ── 测试可用性（settings-panel-jev-imagegen 批 2026-10-29 作者令「然后 Jev/
// 生图，也要像我们LLM这样，有个按钮来测试可用性」）────────────────────────────
// 两级：a) 轻探活 = key 解析 + 上游可达（models 端点 + 适配器鉴权头；401 =
// 死 key 状态码原样可见）；b) 「实跑一次最小生成」= size 256 / 最低质量真打
// 上游，回显 ✅+耗时+字节数+格式+缩略图预览（产物落引擎侧临时目录，不进正式
// 归档）。测的是表单当前值（未保存也测；key 输入框有值 = 覆写腿，空 = 存储
// 凭据腿）。同 LLM 测试钮口径：单飞 + 测试中禁用（双钮联动）+ 结果保留可重
// 测（结果不落 localStorage、不改配置——纯诊断读数）。

/** Shared single-flight flag: both test controls disable while either runs. */
let imagegenTestInFlight = false;

/**
 * The engine call (owner-auth POST /api/imagegen/test). Required-field
 * validation reuses the save flow's toasts — a test without provider/type/
 * model guides instead of firing a注定 400 的击.
 * @param {'probe'|'generate'} mode
 * @returns {Promise<{ok: boolean, latencyMs?: number, bytes?: number, format?: string, thumbnail?: string, modelsFaceAbsent?: number, error?: string}>}
 */
export async function runImagegenTest(mode) {
  const name = currentProviderName();
  if (!name) { window.__showToast?.(t('settings.imagegenNameRequired'), 'error'); return { ok: false, error: 'missing-provider' }; }
  const type = /** @type {HTMLSelectElement|null} */ (document.getElementById('cfg-imagegen-type'))?.value || '';
  if (!type) { window.__showToast?.(t('settings.imagegenTypeRequired'), 'error'); return { ok: false, error: 'missing-type' }; }
  const model = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-model'))?.value.trim() || '';
  if (!model) { window.__showToast?.(t('settings.imagegenModelRequired'), 'error'); return { ok: false, error: 'missing-model' }; }
  const baseUrl = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-baseurl'))?.value.trim() || '';
  const keyValue = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'))?.value.trim() || '';
  /** @type {Record<string, string>} */
  const body = { provider: name, type, model };
  if (baseUrl) body.baseUrl = baseUrl; // 覆写腿判据键：引擎见 baseUrl 即走表单值
  if (keyValue) body.apiKey = keyValue; // 空 = 沿用存储凭据（留空保存语义同源）
  if (mode === 'generate') body.mode = 'generate';
  try {
    const resp = await fetch('/api/imagegen/test', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = await resp.json().catch(() => null);
    if (resp.ok && data && /** @type {any} */ (data).ok === true) {
      const d = /** @type {any} */ (data);
      return { ok: true, latencyMs: d.latencyMs, bytes: d.bytes, format: d.format, thumbnail: d.thumbnail || '', modelsFaceAbsent: d.modelsFaceAbsent };
    }
    const err = data && /** @type {any} */ (data).error;
    return { ok: false, error: typeof err === 'string' && err ? err : t('provider.testModelFailedHttp', { code: String(resp.status) }) };
  } catch {
    return { ok: false, error: t('provider.testModelFailedIo') };
  }
}

/**
 * Append a muted note under an already-painted verdict line (the probe's
 * 「models 面缺失——以实跑生成为准」face).
 * @param {HTMLElement} block results block
 * @param {string} key line key (the provider name)
 * @param {string} note
 */
function appendLineNote(block, key, note) {
  const line = /** @type {HTMLElement|null} */ (block.querySelector(`.cfg-test-result-line[data-model="${escapeHtml(key)}"]`));
  const textEl = line?.querySelector('.cfg-test-line-text');
  if (textEl) textEl.textContent += ` ${note}`;
}

/**
 * Decorate a generate-verdict line with the real-run stats + thumbnail
 * preview (✅+耗时+字节数/格式+缩略图——作者令的两级 b 腿回显口径).
 * @param {HTMLElement} block results block
 * @param {string} key line key (the provider name)
 * @param {{latencyMs?: number, bytes?: number, format?: string, thumbnail?: string}} v
 */
function decorateGenerateLine(block, key, v) {
  const line = /** @type {HTMLElement|null} */ (block.querySelector(`.cfg-test-result-line[data-model="${escapeHtml(key)}"]`));
  const textEl = /** @type {HTMLElement|null} */ (line?.querySelector('.cfg-test-line-text'));
  if (!textEl) return;
  const stats = [
    typeof v.latencyMs === 'number' ? `${v.latencyMs}ms` : '',
    typeof v.bytes === 'number' ? `${v.bytes} bytes` : '',
    v.format || '',
  ].filter(Boolean).join(' · ');
  textEl.textContent = stats;
  if (v.thumbnail) {
    const img = document.createElement('img');
    img.className = 'cfg-test-thumb';
    img.src = v.thumbnail;
    img.alt = '';
    textEl.appendChild(img);
  }
}

/**
 * Wire the two test buttons (probe + minimal generation) of the current
 * provider fields face. Shared single-flight: while one test runs both
 * buttons disable; the verdict repaints the shared results line in place
 * (re-test = fresh line state).
 */
function bindImagegenTestButtons() {
  const probeBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-test-imagegen'));
  const genBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-imagegen-generate'));
  const block = /** @type {HTMLElement|null} */ (document.querySelector('.imagegen-test-results'));
  if (!probeBtn || !genBtn || !block) return;
  const probeIdle = probeBtn.innerHTML;
  const genIdle = genBtn.innerHTML;
  const spinnerFace = (label) => `<span class="cfg-model-test-spinner" aria-hidden="true"></span><span>${escapeHtml(label)}</span>`;
  const setName = currentProviderName();
  const setDisabled = (v) => { probeBtn.disabled = v; genBtn.disabled = v; };

  probeBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (imagegenTestInFlight || probeBtn.disabled) return; // 单飞：测试中不重入
    const name = currentProviderName() || setName;
    imagegenTestInFlight = true;
    setDisabled(true);
    probeBtn.innerHTML = spinnerFace(t('provider.testRunning'));
    paintTestingLine(block, name);
    const v = await runImagegenTest('probe');
    if (v.ok) {
      paintTestResultLine(block, name, { ok: true, latencyMs: v.latencyMs });
      if (v.modelsFaceAbsent) appendLineNote(block, name, t('settings.imagegenProbeModelsAbsent', { code: String(v.modelsFaceAbsent) }));
    } else if (v.error !== 'missing-provider' && v.error !== 'missing-type' && v.error !== 'missing-model') {
      paintTestResultLine(block, name, { ok: false, error: v.error });
    }
    setDisabled(false);
    probeBtn.innerHTML = probeIdle;
    imagegenTestInFlight = false;
  });

  genBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (imagegenTestInFlight || genBtn.disabled) return; // 单飞：测试中不重入
    const name = currentProviderName() || setName;
    imagegenTestInFlight = true;
    setDisabled(true);
    genBtn.innerHTML = spinnerFace(t('settings.imagegenGenRunning'));
    paintTestingLine(block, name);
    const v = await runImagegenTest('generate');
    if (v.ok) {
      paintTestResultLine(block, name, { ok: true, latencyMs: v.latencyMs });
      decorateGenerateLine(block, name, v);
    } else if (v.error !== 'missing-provider' && v.error !== 'missing-type' && v.error !== 'missing-model') {
      paintTestResultLine(block, name, { ok: false, error: v.error });
    }
    setDisabled(false);
    genBtn.innerHTML = genIdle;
    imagegenTestInFlight = false;
  });
}

/** The provider name currently in the form (空态 = ''). */
function currentProviderName() {
  const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-name'));
  return (input?.value || '').trim();
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
 * @param {string|null} [deleteName] 整节替换时要丢掉的键（删除 / 改名让位）
 * @param {boolean} [enabled]
 */
function sectionPayload(editedName, patch, deleteName, enabled) {
  const providers = providerMap();
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

/** The live enabled state (form toggle first — unsaved edits must not be lost). */
function currentEnabled() {
  const sw = document.getElementById('toggle-imagegen');
  if (sw) return sw.classList.contains('on');
  return !!(lastStatus && lastStatus.enabled);
}

/** Repaint the whole compiled face from the cached projection (no re-fetch). */
function repaint(wrap) {
  wrap.innerHTML = renderImagegenFace(lastStatus);
  bindSectionBody(wrap);
}

/** 两段式删除的中间态可视化：卡片 × 钮 + 表单删除钮同口径翻成「再点一次确认删除」。 */
function markArmed(name, on) {
  const label = on ? t('settings.cfgCard.deleteConfirm') : t('settings.cfgCard.delete');
  const listEl = document.getElementById('imagegen-provider-list');
  if (listEl) {
    for (const card of listEl.querySelectorAll('.cfg-card[data-provider]')) {
      if (!(card instanceof HTMLElement) || card.dataset.provider !== name) continue;
      const btn = card.querySelector('.cfg-card-remove');
      if (!(btn instanceof HTMLElement)) continue;
      btn.dataset.armed = on ? '1' : '';
      const rmLabel = on ? t('settings.cfgCard.deleteConfirm') : t('provider.remove');
      btn.title = rmLabel;
      btn.setAttribute('aria-label', rmLabel);
    }
  }
  // 表单删除钮（#btn-delete-imagegen，与 JeV 面同款常驻钮）跟着翻文案——只在该
  // 钮正对着被武装的那个提供方时才翻，避免「武装卡片 B、表单停在 A」时误翻。
  const delBtn = document.getElementById('btn-delete-imagegen');
  if (delBtn instanceof HTMLElement && name === activeProvider) {
    delBtn.dataset.armed = on ? '1' : '0';
    delBtn.textContent = label;
  }
}

/** Delete one provider through the whole-section PUT (engine drops the key). */
async function deleteProvider(name) {
  const ok = await putSection(sectionPayload('', null, name, currentEnabled()));
  if (!ok) { flashSaveState(false); return; }
  window.__showToast?.(t('settings.cfgCard.deleted'), 'success');
  activeProvider = '';
  await bindImagegenSection();
}

/** 两段式：第一击只挂中间态（零网络），4s 内第二击真删（btn-clear-nf-cache 先例）。 */
function armOrDelete(name) {
  if (!name) return;
  if (armedDeleteName !== name) {
    armedDeleteName = name;
    markArmed(name, true);
    setTimeout(() => {
      if (armedDeleteName === name) { armedDeleteName = ''; markArmed(name, false); }
    }, 4000);
    return;
  }
  armedDeleteName = '';
  markArmed(name, false);
  void deleteProvider(name);
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
    draftEnabled = this.classList.contains('on'); // 未保存态：卡片切换重绘时保持
  });

  // 卡片列表（共用组件事件委托，容器级幂等哨兵）：点卡片 = 载入该提供方的表单
  // （与 LLM 面「点卡片即编辑」同一交互）；卡片 × = 两段式删除该提供方。
  const listEl = /** @type {HTMLElement|null} */ (document.getElementById('imagegen-provider-list'));
  bindCardSelect(listEl, (name) => {
    if (!name || name === activeProvider) return;
    activeProvider = name;
    repaint(wrap);
  });
  bindCardRemove(listEl, (name) => { armOrDelete(name); });

  // 「＋ 新增提供方」= 清空表单（空态铁律：名称/模型/端点/key 全空、协议无预选）。
  document.getElementById('btn-add-imagegen-provider')?.addEventListener('click', () => {
    activeProvider = '';
    armedDeleteName = '';
    repaint(wrap);
    /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-name'))?.focus();
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

  // 测试可用性双钮（轻探活 / 实跑一次最小生成）——settings-panel-jev-imagegen
  // 批 2026-10-29；单飞 + 测试中禁用 + 结果保留可重测，同 LLM 测试钮口径。
  bindImagegenTestButtons();

  // 删除此提供方（常驻钮，与 JeV 面 #btn-delete-jev 同款：无提供方时 disabled）：
  // 两段式确认（btn-clear-nf-cache 先例）——第一击挂确认位，第二击真删
  // （整节 PUT 少该项，其余 provider 原样保留）。
  const delBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-delete-imagegen'));
  delBtn?.addEventListener('click', () => {
    if (!activeProvider || !providerMap()[activeProvider]) return;
    armOrDelete(activeProvider);
  });
}

/**
 * The one-save flow: typed key → secrets write under the hidden pointer name;
 * then the whole-section config PUT. Both-or-config-only: an empty key field
 * saves the config alone (re-entry optional).
 * @returns {Promise<boolean>}
 */
export async function saveImagegenSection() {
  const map = providerMap();
  const editing = (activeProvider && map[activeProvider]) ? activeProvider : '';
  const typed = currentProviderName();
  const name = typed || editing;
  if (!name) {
    window.__showToast?.(t('settings.imagegenNameRequired'), 'error');
    return false;
  }
  // 允许大写输入（派生名保存时小写化，契约 §C.2）；长度留出 `imagegen-`/`-key` 余量。
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$/.test(name)) {
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
  // 名称在表单里被改写过 = 旧键让位（整节替换：删旧名 + 写新名）。
  const renameFrom = (editing && name !== editing) ? editing : null;
  const ok = await putSection(sectionPayload(name, patch, renameFrom, currentEnabled()));
  if (ok) {
    const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-imagegen-key'));
    if (input) input.value = ''; // 值不回显、不滞留——保存后清空回已配置态
    activeProvider = name; // 保存后面板停在刚保存的提供方上
    await bindImagegenSection();
  }
  // 保存态落在**重绘后**的节点上（重绘会换掉 #imagegen-save-state，先闪再绘会被抹掉）。
  flashSaveState(ok);
  return ok;
}

/**
 * Legacy single-purpose seam kept for the spec/external callers: save the
 * typed key for the edited provider ONLY (secrets write; no config PUT).
 * @param {string} name provider id ('' = active)
 * @returns {Promise<boolean>}
 */
export async function saveImagegenKey(name) {
  const map = providerMap();
  const target = (name && map[name]) ? name : ((activeProvider && map[activeProvider]) ? activeProvider : '');
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
  draftEnabled = null; // 新投影到达 = 权威启用态
  armedDeleteName = '';
  const map = providerMap();
  const names = Object.keys(map);
  if (names.length) {
    // 表单默认载入「当前使用」的提供方（defaultProvider），缺席退回首个。
    const def = String((lastStatus && /** @type {any} */ (lastStatus).defaultProvider) || '');
    activeProvider = (activeProvider && map[activeProvider])
      ? activeProvider
      : ((def && map[def]) ? def : names[0]);
  } else {
    activeProvider = ''; // 空态：0 张卡片 + 全空表单
  }
  wrap.innerHTML = renderImagegenFace(lastStatus);
  bindSectionBody(wrap);
}
