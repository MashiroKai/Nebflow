// providerPanel.js — 设置页「服务商」面板的**唯一实现**（provider-add-tabs 批，
// 2026-11-06 作者令逐字：「现在添加服务商是每个类型的模型各有一个独立的添加入口，
// 我们统一一下，在『添加服务商』顶部加几个标签，支持选择添加比如生图模型的服务商等，
// 样式保持一致。这样一来，我们添加的模型都会集中显示在同一个面板里（即现在的 LLM
// 服务商 / LLM Provider 面板），让这个面板能够展示不同类型的服务商，把整个配置和
// 显示流程都统一起来。」）
//
// ── 本批退役的三套入口（一删即不再渲染，零空壳）────────────────────────────
//   · LLM 面：`#provider-list` 裸列表 + 设置页内联的 `#btn-add-provider` 绑定
//     （sidebar.js bindSettingsEvents）—— 列表/新增/删除全部并入本面板；
//   · JeV 面：设置页独立一节 `#jev-advance-toggle` → `#jev-advance-body`
//     （层级下钻浮层 / 手机子页）+ `#jev-provider-list` + `#btn-add-jev-provider`
//     + 内联表单 `#jev-provider-form`；
//   · 生图面：设置页独立一节 `#imagegen-advance-toggle` → `#imagegen-advance-body`
//     + `#imagegen-provider-list` + `#btn-add-imagegen-provider` + 内联表单；
//     模块 `sidebar/imagegenSection.js` 整体退役（只剩探活/实跑按钮腿迁到
//     `sidebar/imagegenTestButton.js`）。
// 三者现在只有**一个面**：本面板的类型标签 + 一张列表 + 一套表单骨架。
//
// ── 单实现清单（每样只有一套）─────────────────────────────────────────────
//   ① 服务商面板 = 本文件（列表 / 类型标签 / 新增 / 删除 / 启用开关）；
//   ② 表单骨架 = `sidebar/providersUi.js` 的 showProviderForm（字段描述进、
//      values 出；渲染器按字段类型单点，不按服务商类型分叉）；
//   ③ 类型标签 = 本文件的 `.nf-ptabs`（分段控件，样式单点 = css/sidebar.css）；
//   ④ 类型徽标 = `cfgProviderCard.js` 的 `typeBadge`（卡片组件单点）；
//   ⑤ 类型词面 = locales 的 `settings.providerType.*`（英文原词，两语言同值）。
//
// ── 类型枚举单源 = 后端 ────────────────────────────────────────────────────
// 域清单不在前端拼写：`GET /api/config/provider-types`（ConfigService.providerTypes
// ← shared.ProviderDomains.All）下发 `domains[]` 与各域的适配器（协议）清单。
// 本文件的 `DOMAIN_SPECS` 只是**行为绑定**（取数/保存/删除各走哪条既有通道），
// 启动时与后端投影做漂移校验：后端多一个域 / 少一个域 ⇒ 面板顶部显式报错行，
// 绝不静默吞掉一个类型（静态闸 scripts/check-provider-panel.mjs 同钉「两表键集合
// 相等」，改后端枚举而忘了前端行为绑定 = 红）。
//
// ── 取数与保存腿（各自的既有通道，零新协议）─────────────────────────────────
//   llm      : 读 state.parsedConfig.llm.providers；写 = saveNewProvider → WS
//              updateConfig（providersUi.js 单点，与 onboarding 同一条腿）；
//   imagegen : 读 GET /api/config/imagegen；写 = PUT /api/config/imagegen
//              整节替换 + secrets 面写 key（原名 imagegen-<name>-key）；
//   jev      : 读 GET /api/config/jev；写 = PUT /api/config/jev 整块 + secrets
//              面写 key（原名 jev-<name>-key）。
//   两种 secrets 派生名对用户不可见（凭据面无感化纪律照旧）。

import state from '../state.js';
import { t } from '../i18n.js';
import { escapeHtml, statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { toggleHTML, setToggleState } from '../toggle.js';
import { providerCardHTML, addProviderButtonHTML, bindCardRemove, bindCardSelect } from '../cfgProviderCard.js';
import { showProviderForm, showProviderModal, protocolFormLabel, saveNewProvider, flushConfigToServer, revealProviderApiKeys } from './providersUi.js';
import { jevTestButtonHTML, jevTestResultsHTML, bindJevTestButton } from './jevTestButton.js';
import { imagegenTestButtonHTML, imagegenTestResultsHTML, bindImagegenTestButtons } from './imagegenTestButton.js';

/** 面板宿主 id（sidebar.js renderSettings 里唯一挂点）。 */
export const PROVIDER_PANEL_HOST_ID = 'provider-panel-host';

/** 类型枚举投影（GET /api/config/provider-types）；null = 尚未拉到。 */
/** @type {{domains: Array<{id:string, enabledSwitch:boolean}>, adapters: Record<string,string[]>}|null} */
let typeFace = null;

/** 两个非-LLM 域的状态投影（GET /api/config/<domain>）；null = 尚未拉到。 */
/** @type {any} */
let imagegenStatus = null;
/** @type {any} */
let jevStatus = null;

/** 当前选中的类型标签（= 列表筛选 + 新增表单的类型）；'' = 尚未定。 */
let activeType = '';

/** 投影拉取失败标记（面板顶部显式报错，不造假数据）。 */
let typeFaceError = false;

// ── 域行为绑定表 ──────────────────────────────────────────────────────────
// 🔴 键集合必须等于后端 ProviderDomains.All（静态闸钉死）。每项只描述「这个域
// 走哪条既有通道」，不含任何 DOM 结构（结构单点在下面 renderProviderPanel）。

/** @type {Record<string, {
 *   adapter: string,
 *   labelKey: string,
 *   enabledSwitch: boolean,
 *   providers: () => Record<string, any>,
 *   rows: () => Array<{name:string, badge:string, models:string[], metaExtra:string, active:boolean}>,
 *   fields: (of: any, ctx: any) => any[],
 *   extraActionsHTML: () => string,
 *   bindExtra: (overlay: HTMLElement, ctx: any) => void,
 *   persist: (values: any, ctx: any) => Promise<boolean>,
 *   remove: (name: string) => Promise<boolean>,
 *   setEnabled: (on: boolean) => Promise<boolean>,
 *   healthBadge: boolean,
 * }>} */
const DOMAIN_SPECS = {
  llm: {
    adapter: 'llm',
    labelKey: 'settings.providerType.llm',
    enabledSwitch: false,
    healthBadge: false,
    providers: () => /** @type {Record<string, any>} */ ((state.parsedConfig && state.parsedConfig.llm && state.parsedConfig.llm.providers) || {}),
    rows() {
      return Object.entries(this.providers())
        .filter(([, p]) => !!p) // 删除标记 = null（config 深合并的删键语义）⇒ 不渲染
        .map(([name, p]) => {
          const pc = /** @type {any} */ (p);
          const models = (pc.models || []).map((m) => m.id).filter(Boolean);
          return {
            name,
            badge: protocolFormLabel(pc.protocol),
            models,
            metaExtra: `<span class="cfg-card-sub">${escapeHtml(t('settings.cfgCard.models', { n: models.length }))}</span>`,
            active: false,
          };
        });
    },
    // LLM 的字段/校验/保存腿全部留在 providersUi.js（那一套是 LLM 专属的
    // models[] + 上下文窗口 + 不可用时段 + 探活钮），本面板只调它的入口。
    fields: () => [],
    extraActionsHTML: () => '',
    bindExtra: () => {},
    async persist(values, ctx) {
      // ctx = {name, isEdit, existingName, data}（由 showProviderModal 自己算好）
      if (ctx.existingName && ctx.name !== ctx.existingName) {
        state.parsedConfig.llm.providers[ctx.existingName] = null;
      }
      saveNewProvider(ctx.name, ctx.data);
      return true;
    },
    async remove(name) {
      state.parsedConfig.llm.providers[name] = null;
      state.configDirty = true;
      flushConfigToServer();
      return true;
    },
    async setEnabled() { return true; }, // llm 无 enabled 节键
  },

  imagegen: {
    adapter: 'imagegen',
    labelKey: 'settings.providerType.imagegen',
    enabledSwitch: true,
    healthBadge: false,
    providers: () => /** @type {Record<string, any>} */ ((imagegenStatus && imagegenStatus.providers) || {}),
    rows() {
      return Object.entries(this.providers()).map(([name, p]) => {
        const pc = /** @type {any} */ (p || {});
        return {
          name,
          badge: pc.type ? String(pc.type) : '',
          models: pc.model ? [String(pc.model)] : [],
          metaExtra: keyBadgeHTML(!!pc.keyConfigured),
          active: false,
        };
      });
    },
    fields(of, ctx) {
      const adapters = adapterOptions('imagegen');
      return [
        nameField(of),
        {
          key: 'type',
          label: t('provider.protocol'),
          type: 'select',
          value: of.type || '',
          options: [{ value: '', label: t('settings.imagegenTypePlaceholder') }, ...adapters.map((a) => ({ value: a, label: a }))],
          disabled: false,
        },
        { key: 'model', label: t('settings.imagegenModel'), type: 'text', value: of.model || '', placeholder: t('settings.imagegenModelPlaceholder') },
        { key: 'baseUrl', label: t('settings.imagegenBaseUrl'), type: 'text', value: of.baseUrl || '', placeholder: t('settings.imagegenBaseUrlPlaceholder') },
        secretField(ctx),
      ];
    },
    extraActionsHTML: imagegenTestButtonHTML,
    bindExtra(overlay) {
      bindImagegenTestButtons(overlay, imagegenFormReader);
    },
    async persist(values, ctx) {
      const name = values.name.trim();
      if (values.apiKey && values.apiKey.trim()) {
        const okKey = await writeSecret(`imagegen-${name.toLowerCase()}-key`.slice(0, 64), values.apiKey.trim());
        if (!okKey) return false;
      }
      const patch = { type: values.type, model: values.model.trim() };
      if (values.baseUrl.trim()) patch.baseUrl = values.baseUrl.trim();
      patch.keyRef = existingKeyRef(this.providers(), ctx.existingName, name, `imagegen-${name.toLowerCase()}-key`);
      return putSection('imagegen', sectionBodyImagegen(this.providers(), ctx.existingName, name, patch));
    },
    async remove(name) {
      return putSection('imagegen', sectionBodyImagegen(this.providers(), name, '', null));
    },
    async setEnabled(on) {
      return putSection('imagegen', { ...sectionBodyImagegen(this.providers(), '', '', null), enabled: on });
    },
  },

  jev: {
    adapter: 'jev',
    labelKey: 'settings.providerType.jev',
    enabledSwitch: true,
    healthBadge: true,
    providers: () => /** @type {Record<string, any>} */ ((jevStatus && jevStatus.providers) || {}),
    rows() {
      return Object.entries(this.providers()).map(([name, p]) => {
        const pc = /** @type {any} */ (p || {});
        return {
          name,
          badge: pc.type ? String(pc.type) : '',
          models: pc.model ? [String(pc.model)] : [],
          metaExtra: keyBadgeHTML(!!pc.keyConfigured),
          active: false,
        };
      });
    },
    fields(of, ctx) {
      const adapters = adapterOptions('jev');
      const pinned = endpointPinned(of.type);
      return [
        nameField(of),
        {
          key: 'type',
          label: t('provider.protocol'),
          type: 'select',
          value: of.type || '',
          options: [{ value: '', label: t('settings.imagegenTypePlaceholder') }, ...adapters.map((a) => ({ value: a, label: a }))],
        },
        {
          key: 'endpoint',
          label: t('settings.jevEndpoint'),
          type: 'text',
          value: pinned ? TYPESAFE_JEV_ENDPOINT : (of.endpoint || ''),
          placeholder: 'http://127.0.0.1:8080/predict',
          disabled: pinned,
          title: pinned ? t('settings.jevEndpointPinned') : '',
        },
        { key: 'model', label: t('settings.jev.model'), type: 'text', value: of.model || '', placeholder: t('settings.jev.modelPlaceholder') },
        secretField(ctx),
      ];
    },
    extraActionsHTML: jevTestButtonHTML,
    bindExtra(overlay, ctx) {
      // 结果块与按钮同组呈现：块落在表单体尾（LLM 面 .cfg-test-results 同族）。
      if (!overlay.querySelector('.cfg-test-results-jev')) {
        const body = overlay.querySelector('.cfg-modal-body') || overlay;
        body.insertAdjacentHTML('beforeend', jevTestResultsHTML());
      }
      // 端点钉死联动（R3 语义在统一骨架上的延续）：切换 type 下拉 ⇒ typesafe-jev
      // 预填钉死值 + 禁用 + title；laya/其它 ⇒ 放开可编辑、钉死值不残留。
      const typeSel = /** @type {HTMLSelectElement|null} */ (overlay.querySelector('[data-field="type"]'));
      const endpointInput = /** @type {HTMLInputElement|null} */ (overlay.querySelector('[data-field="endpoint"]'));
      if (typeSel && endpointInput) {
        const apply = () => {
          // R3 语义：切到 typesafe-jev ⇒ 预填钉死 + 禁用；切走 ⇒ 放开且钉死值
          // 不残留（旧值不泄漏进 laya 面）。
          const pin = endpointPinned(typeSel.value);
          endpointInput.value = pin ? TYPESAFE_JEV_ENDPOINT : '';
          endpointInput.disabled = pin;
          endpointInput.title = pin ? t('settings.jevEndpointPinned') : '';
        };
        typeSel.addEventListener('change', apply);
      }
      bindJevTestButton(overlay, {
        getForm: () => ({
          provider: fieldValue(overlay, 'name'),
          endpoint: fieldValue(overlay, 'endpoint'),
          keyRef: keyRefFor('jev', ctx.existingName, fieldValue(overlay, 'name')),
        }),
      });
    },
    async persist(values, ctx) {
      const name = values.name.trim();
      if (values.apiKey && values.apiKey.trim()) {
        const okKey = await writeSecret(`jev-${name.toLowerCase()}-key`.slice(0, 64), values.apiKey.trim());
        if (!okKey) return false;
      }
      return putSection('jev', sectionBodyJev(this.providers(), ctx.existingName, name, values));
    },
    async remove(name) {
      return putSection('jev', sectionBodyJev(this.providers(), name, '', null));
    },
    async setEnabled(on) {
      const body = sectionBodyJev(this.providers(), '', '', null);
      return putSection('jev', { ...body, enabled: on });
    },
  },
};

// ── 小件 ────────────────────────────────────────────────────────────────────

// typesafe-jev 的端点钉死（jev-panel-threefix 批 R3 语义，2026-11-02 定版，随
// 面板统一原样保留）：该分配通道端点是引擎侧约定值，用户永远不用（也不许）手填
// ——表单预填 + 禁用 + title 说明；laya 自建通道保持可编辑。词面 title 两语言
// 走既有 settings.jevEndpointPinned 键。
const TYPESAFE_JEV_ENDPOINT = 'https://api.typesafe.ai/v1/systemone';
/** @param {string} type @returns {boolean} */
const endpointPinned = (type) => String(type) === 'typesafe-jev';

/** key 在位性徽标（三域同一组共用键，与旧窗逐字同款）。 */
function keyBadgeHTML(configured) {
  return configured
    ? `<span class="st-badge" data-tone="ok">${escapeHtml(t('settings.cfgCard.keyConfigured'))}</span>`
    : `<span class="st-badge" data-tone="warn">${escapeHtml(t('settings.cfgCard.keyMissing'))}</span>`;
}

/** 表单首个字段（服务商 ID）：编辑既有项时禁用（改名=新建，旧名让位由 persist 处理）。 */
function nameField(of) {
  return {
    key: 'name',
    label: t('provider.id'),
    type: 'text',
    value: of.name || '',
    placeholder: t('provider.idPlaceholder'),
    disabled: !!of.name,
  };
}

/** 密钥字段：值永不带入（placeholder 按在位性换一句），值只进输入框与请求体。 */
function secretField(ctx) {
  const configured = !!ctx.keyConfigured;
  return {
    key: 'apiKey',
    label: 'API Key',
    type: 'text',
    password: true,
    value: '',
    placeholder: t(configured ? 'settings.cfgCard.keyReentryPlaceholder' : 'settings.cfgCard.keyPlaceholder'),
  };
}

/** 适配器（协议）清单 = 后端投影（不在前端拼写）。 */
function adapterOptions(domain) {
  const list = typeFace && typeFace.adapters && typeFace.adapters[domain];
  return Array.isArray(list) ? list : [];
}

function fieldValue(overlay, key) {
  const el = /** @type {HTMLInputElement|HTMLSelectElement|null} */ (overlay.querySelector(`[data-field="${key}"]`));
  return (el && el.value.trim()) || '';
}

/** 既有条目的 keyRef 优先（旧配置不断腿），否则按派生名（对用户不可见）。 */
function existingKeyRef(map, existingName, name, derived) {
  const prevName = existingName || name;
  const prev = map[prevName] || {};
  return String(prev.keyRef || derived);
}

function keyRefFor(domain, existingName, typedName) {
  const spec = DOMAIN_SPECS[domain];
  const map = spec.providers();
  const name = typedName || existingName;
  return existingKeyRef(map, existingName, name, `${domain}-${String(name).toLowerCase()}-key`.slice(0, 64));
}

/**
 * 写一枚 secret（POST 先建、409 ⇒ PUT 覆写）：三域共用一条写腿，值只出现在
 * 请求体里，永不回显、不落 localStorage、不打日志。
 * @param {string} name
 * @param {string} value
 * @returns {Promise<boolean>}
 */
async function writeSecret(name, value) {
  try {
    let resp = await fetch(`/api/secrets/${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    });
    if (resp.status === 409) {
      resp = await fetch(`/api/secrets/${encodeURIComponent(name)}`, {
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

/** 整节/整块 PUT（imagegen 与 jev 同一条写腿形）。 */
async function putSection(domain, body) {
  try {
    const resp = await fetch(`/api/config/${domain}`, {
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
 * imagegen 整节体（原 imagegenSection.sectionPayload 逐字搬入，行为不变）：
 * 全部 provider 原样保留、被编辑项覆写、被删项剔除；keyConfigured 是投影字段
 * 永不回写。@param {string} editName '' = 不覆写；@param {string} deleteName '' = 不删
 */
function sectionBodyImagegen(map, deleteName, editName, patch) {
  /** @type {Record<string, any>} */
  const out = {};
  for (const [n, raw] of Object.entries(map)) {
    if (n === deleteName) continue;
    const p = /** @type {any} */ (raw || {});
    /** @type {Record<string, string>} */
    const row = { type: p.type || '', model: p.model || '' };
    if (p.baseUrl) row.baseUrl = p.baseUrl;
    if (p.keyRef) row.keyRef = p.keyRef;
    out[n] = row;
  }
  if (editName && patch) out[editName] = patch;
  const def = (editName && !deleteName) ? editName : Object.keys(out)[0] || '';
  /** @type {Record<string, any>} */
  const body = { enabled: !!(imagegenStatus && imagegenStatus.enabled), providers: out };
  if (def && out[def] && out[def].keyRef) body.defaultProvider = def;
  return body;
}

/** jev 整块体（原 sidebar.js saveJevProvider 的形状逐字搬入）。 */
function sectionBodyJev(map, deleteName, editName, values) {
  /** @type {Record<string, any>} */
  const providers = {};
  for (const [n, raw] of Object.entries(map)) {
    if (n === deleteName) continue;
    const e = /** @type {any} */ (raw || {});
    /** @type {Record<string, string>} */
    const row = { type: String(e.type || '') };
    if (e.endpoint) row.endpoint = String(e.endpoint);
    if (e.model) row.model = String(e.model);
    if (e.keyRef) row.keyRef = String(e.keyRef);
    providers[n] = row;
  }
  if (editName && values) {
    /** @type {Record<string, string>} */
    const row = { type: String(values.type || '') };
    if (String(values.endpoint || '').trim()) row.endpoint = String(values.endpoint).trim();
    row.model = String(values.model || '').trim();
    const prev = map[editName] || {};
    const keyRef = (values.apiKey && values.apiKey.trim())
      ? `jev-${editName.toLowerCase()}-key`.slice(0, 64)
      : String(prev.keyRef || '');
    if (keyRef) row.keyRef = keyRef;
    providers[editName] = row;
  }
  const remaining = Object.keys(providers);
  return {
    enabled: !!(jevStatus && jevStatus.enabled),
    provider: editName || remaining[0] || '',
    providers,
  };
}

// ── 面板渲染（唯一一份 DOM 结构）────────────────────────────────────────────

/** 可用域清单（后端投影优先；投影未到时退回行为绑定表，绝不因一次 GET 失败吞域）。 */
function domainIds() {
  if (typeFace && Array.isArray(typeFace.domains) && typeFace.domains.length) {
    return typeFace.domains.map((d) => d.id);
  }
  return Object.keys(DOMAIN_SPECS);
}

/** 当前生效的类型（选中项失效/未定时落到首个可用域）。 */
function currentType() {
  const ids = domainIds();
  return ids.includes(activeType) ? activeType : (ids[0] || '');
}

function tabLabel(id) {
  const spec = DOMAIN_SPECS[id];
  return spec ? t(spec.labelKey) : id; // 后端新增而前端未绑定的域：显式露原名（漂移可见）
}

function enabledSwitchOn(id) {
  if (id === 'imagegen') return !!(imagegenStatus && imagegenStatus.enabled);
  if (id === 'jev') return !!(jevStatus && jevStatus.enabled);
  return false;
}

function domainSpec(id) {
  return DOMAIN_SPECS[id] || null;
}

/** 整面板 HTML（类型标签 + 类型级附加行 + 统一列表 + 新增钮）。 */
export function providerPanelHTML() {
  const ids = domainIds();
  const current = currentType();
  const spec = domainSpec(current);
  // 生图 seal-OFF 形态（settings-imagegen-two-forms 批 A1 语义，2026-10-28 定版，
  // 随面板统一原样保留）：release 构建未编入（compiledIn=false）⇒ 该类型只给
  // 「未编入」提示位，列表/新增/表单全无（不许露出一个必然 404 的配置面）。
  const sealedOff = current === 'imagegen' && !!(imagegenStatus && imagegenStatus.compiledIn === false);
  const rows = sealedOff ? [] : (spec ? spec.rows() : []);
  const cards = rows.map((r) => providerCardHTML({
    name: r.name,
    face: 'provider',
    typeBadge: tabLabel(current),
    protocolBadge: r.badge,
    models: r.models,
    metaExtra: r.metaExtra,
    active: r.active,
  })).join('');
  const listInner = cards || `<div class="cfg-hint">${escapeHtml(t('settings.cfgCard.emptyHint'))}</div>`;
  const drift = (typeFace && typeFace.domains || []).some((d) => !DOMAIN_SPECS[d.id]);
  return `
    <div class="nf-provider-panel" data-provider-panel data-type="${escapeHtml(current)}">
      <div class="nf-ptabs" role="tablist" id="provider-type-tabs">
        ${ids.map((id) => `<button type="button" class="nf-ptab" role="tab" data-provider-type="${escapeHtml(id)}"
          aria-selected="${id === current ? 'true' : 'false'}">${escapeHtml(tabLabel(id))}</button>`).join('')}
      </div>
      ${typeFaceError ? `<div class="cfg-hint nf-provider-face-error" id="provider-face-error">${escapeHtml(t('settings.providerTypesError'))}</div>` : ''}
      ${drift ? `<div class="cfg-hint nf-provider-face-error" id="provider-face-drift">${escapeHtml(t('settings.providerTypesDrift'))}</div>` : ''}
      <div id="provider-type-extra">
        ${spec && spec.enabledSwitch ? `<div class="settings-row">
          <span class="settings-label">${escapeHtml(t(current === 'imagegen' ? 'settings.imagegenEnabled' : 'settings.jevEnabled'))}</span>
          ${toggleHTML({ on: enabledSwitchOn(current), id: 'toggle-provider-enabled', label: t('settings.enabled') })}
        </div>` : ''}
        ${spec && spec.healthBadge ? `<div class="settings-row nf-provider-health-row">
          <span class="settings-label">${escapeHtml(t('settings.jevHealthTitle'))}</span>
          <span class="cfg-hint stt-status"><span class="st-badge" data-tone="mut" id="settings-jev-health">…</span></span>
        </div>` : ''}
      </div>
      <div id="provider-list" class="provider-card-list">${sealedOff ? `<div class="cfg-hint">${escapeHtml(t('settings.imagegenNotCompiled'))}</div>` : listInner}</div>
      ${sealedOff ? '' : addProviderButtonHTML({ id: 'btn-add-provider', face: 'provider' })}
    </div>`;
}

/** 面板重画（宿主节点内原位替换，交互全部重绑）。 */
export function paintProviderPanel() {
  const host = document.getElementById(PROVIDER_PANEL_HOST_ID);
  if (!host) return;
  host.innerHTML = providerPanelHTML();
  bindPanelEvents(host);
}

// ── 表单开合（唯一入口：新增/编辑都走这里）─────────────────────────────────

/**
 * 打开当前类型的表单。`name` 为空 = 新增面。
 * @param {string} name '' = 新增
 */
function openProviderForm(name) {
  const type = currentType();
  const spec = domainSpec(type);
  if (!spec) return;

  // LLM：字段/校验/保存腿在 providersUi.js 单点（models[] + 上下文窗口 +
  // 不可用时段 + 探活钮都在那套骨架里），本面板只给它一条 persist。
  if (type === 'llm') {
    const providers = /** @type {Record<string, any>} */ (state.parsedConfig && state.parsedConfig.llm && state.parsedConfig.llm.providers) || {};
    const existing = name ? providers[name] : null;
    Promise.resolve(revealProviderApiKeys()).catch(() => {}).then(() => {
      showProviderModal(name || null, existing || null, (newName, data) => {
        void spec.persist({}, { name: newName, existingName: name || '', data }).then(() => paintProviderPanel());
      });
    });
    return;
  }

  const map = spec.providers();
  const existing = name ? /** @type {any} */ (map[name] || {}) : {};
  const ctx = { existingName: name || '', keyConfigured: !!existing.keyConfigured };
  const of = { ...existing, name: name || '' };
  showProviderForm({
    title: name ? t('provider.edit', { name }) : t('provider.add'),
    fields: spec.fields(of, ctx),
    extraActionsHTML: spec.extraActionsHTML(),
    bindExtra: (overlay) => spec.bindExtra(overlay, ctx),
    onConfirm: (values) => {
      const verdict = validateProviderValues(type, values, ctx);
      if (!verdict.ok) {
        window.__showToast?.(t(verdict.key, verdict.params), 'error');
        return;
      }
      void spec.persist(values, ctx).then(async (ok) => {
        if (ok) {
          await refreshProjections();
          paintProviderPanel();
          window.__showToast?.(t('settings.cfgCard.saved'), 'success');
        }
      });
    },
  });
}

/**
 * 校验单点（与 LLM 面的 onConfirm 同一入口语义：不合法 ⇒ toast + 不关窗、不落
 * 任何写）。域规则是数据（每域自己的必填项），校验实现只有这一处。
 * @param {string} type
 * @param {any} v 表单值
 * @param {{existingName:string}} ctx
 */
function validateProviderValues(type, v, ctx) {
  const name = String(v.name || '').trim();
  if (!name) return { ok: false, key: 'provider.idRequired' };
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$/.test(name)) return { ok: false, key: 'settings.imagegenNameInvalid' };
  if (!String(v.type || '').trim()) return { ok: false, key: 'settings.imagegenTypeRequired' };
  if (!String(v.model || '').trim()) return { ok: false, key: 'settings.imagegenModelRequired' };
  if (!ctx.existingName && !String(v.apiKey || '').trim()) {
    // 新服务商必须当场给 key（与 LLM 面 provider.keyRequired 同口径：留空保存
    // 等于配了一个用不了的服务商）。
    return { ok: false, key: 'provider.keyRequired' };
  }
  return { ok: true, key: '', params: {} };
}

// ── 取数 ────────────────────────────────────────────────────────────────────

async function fetchJson(path) {
  try {
    const resp = await fetch(path, { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch {
    return null;
  }
}

/** 拉类型投影 + 两个非-LLM 域的状态投影并重画（幂等，可在任意时点调用）。 */
export async function refreshProjections() {
  const [face, img, jev] = await Promise.all([
    fetchJson('/api/config/provider-types'),
    fetchJson('/api/config/imagegen'),
    fetchJson('/api/config/jev'),
  ]);
  typeFaceError = !face;
  if (face) typeFace = face;
  imagegenStatus = img || imagegenStatus;
  jevStatus = jev || jevStatus;
}

// ── 事件绑定（每次重画后重绑：节点随 innerHTML 重建）───────────────────────

function bindPanelEvents(host) {
  // 类型标签：选中即筛选 + 定为新增表单的类型（同一份状态，零第二处）。
  host.querySelectorAll('[data-provider-type]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const id = /** @type {HTMLElement} */ (btn).dataset.providerType || '';
      if (!id || id === currentType()) return;
      activeType = id;
      paintProviderPanel();
    });
  });

  // 列表：点卡片 = 编辑（与 LLM 面既有交互同一条），× = 删除（统一确认框）。
  const list = document.getElementById('provider-list');
  bindCardSelect(list, (name) => { if (name) openProviderForm(name); });
  bindCardRemove(list, (name) => { void confirmRemove(name); });

  document.getElementById('btn-add-provider')?.addEventListener('click', () => openProviderForm(''));

  // 启用开关（imagegen / jev 才有）：即时落盘（开关类设置变更即保存，与
  // 设置页既有 toggle 纪律同源）。
  document.getElementById('toggle-provider-enabled')?.addEventListener('click', function () {
    const spec = domainSpec(currentType());
    if (!spec) return;
    const next = !this.classList.contains('on');
    setToggleState(this, next);
    void spec.setEnabled(next).then((ok) => {
      if (!ok) {
        setToggleState(/** @type {HTMLElement} */ (this), !next); // 失败回弹，不报假态
        return;
      }
      void refreshProjections().then(() => paintProviderPanel());
    });
  });

  // Jev 分配通道健康徽标（读权面 GET /api/decision/health；jev-panel-threefix
  // 三改③ 的三态 + 分腿语义逐字保留）。
  if (host.querySelector('#settings-jev-health')) paintJevHealth();
}

/** 删除：统一一个确认框（LLM 面原有动效；三域不再各写一套）。 */
async function confirmRemove(name) {
  const spec = domainSpec(currentType());
  if (!spec) return;
  const run = async () => {
    const ok = await spec.remove(name);
    if (!ok) { window.__showToast?.(t('settings.saveFailed'), 'error'); return; }
    await refreshProjections();
    paintProviderPanel();
    window.__showToast?.(t('settings.cfgCard.deleted'), 'success');
  };
  if (typeof window.__showConfirm === 'function') {
    window.__showConfirm(t('provider.remove'), t('provider.removeConfirm', { name }), () => { void run(); });
    return;
  }
  await run();
}

/** Jev 通道健康徽标（三态 + legs 回退档，逻辑逐字承自旧 Jev 节）。 */
function paintJevHealth() {
  const slot = document.getElementById('settings-jev-health');
  if (!slot) return;
  fetch('/api/decision/health', { headers: authHeaders() })
    .then((r) => (r.ok ? r.json() : null))
    .then((data) => {
      const d = data && data.decision;
      if (!d || !slot.isConnected) return;
      const up = d.status === 'up';
      const down = d.status === 'down';
      const legs = d.legs || null;
      const jevLeg = legs && legs.jev;
      const fellBack = !!(up && jevLeg && jevLeg.status === 'down');
      const icon = fellBack ? 'triangle-alert' : up ? 'activity' : down ? 'octagon-alert' : 'circle-dashed';
      const cls = fellBack ? 'st-warn' : up ? 'st-ok' : down ? 'st-err' : '';
      const tone = fellBack ? 'warn' : up ? 'ok' : down ? 'err' : 'mut';
      const word = fellBack ? t('settings.jevHealthFallback')
        : up ? t('settings.jevHealthUp')
        : down ? t('settings.jevHealthDown')
        : t('settings.jevHealthUnconfigured');
      const jevReason = jevLeg && jevLeg.status === 'down' && jevReasonText(jevLeg) ? jevReasonText(jevLeg) : '';
      const reason = fellBack ? jevReason : (down && d.reason ? String(d.reason) : '');
      const llmReason = down && d.reason ? String(d.reason) : '';
      const fullTitle = [jevReason, llmReason].filter(Boolean).join(' | ') || word;
      slot.dataset.tone = tone;
      slot.title = fullTitle;
      slot.innerHTML = statusIcon(icon, 10, cls) + escapeHtml(word)
        + (reason ? `<span class="badge-reason">— ${escapeHtml(reason)}</span>` : '');
    })
    .catch(() => {});
}

function jevReasonText(leg) {
  return leg && leg.reason ? String(leg.reason) : '';
}

// ── 装配入口（settings 每次重渲调用一次）───────────────────────────────────

/**
 * 挂载/刷新面板：先按手上的数据画（首帧 = LLM 面 + 空态），再并行拉三份投影
 * 重画。拉取失败 ⇒ 面板顶部显式一行错误（不造假数据、不吞类型）。
 * @returns {Promise<void>}
 */
export async function bindProviderPanel() {
  const host = document.getElementById(PROVIDER_PANEL_HOST_ID);
  if (!host) return;
  paintProviderPanel();
  await refreshProjections();
  if (!document.getElementById(PROVIDER_PANEL_HOST_ID)) return; // 面板已关
  paintProviderPanel();
}

/** imagegen 探活/实跑的表单读取面（读单一表单骨架里的字段，未保存也测）。 */
function imagegenFormReader(overlay) {
  return {
    getProvider: () => fieldValue(overlay, 'name'),
    getType: () => fieldValue(overlay, 'type'),
    getModel: () => fieldValue(overlay, 'model'),
    getBaseUrl: () => fieldValue(overlay, 'baseUrl'),
    getApiKey: () => {
      const el = /** @type {HTMLInputElement|null} */ (overlay.querySelector('[data-field="apiKey"]'));
      return el ? el.value : '';
    },
  };
}
