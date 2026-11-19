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
//   llm      : reads state.parsedConfig.llm.providers; writes = saveProviderWithAck → WS
//              updateConfig (single point in providersUi.js). The request carries a
//              requestId and only the reply to that very save confirms it; a failure, a
//              timeout or a closed socket keeps the form open with its draft;
//   imagegen : 读 GET /api/config/imagegen；写 = PUT /api/config/imagegen
//              整节替换 + secrets 面写 key（原名 imagegen-<name>-key）。
//   secrets 派生名对用户不可见（凭据面无感化纪律照旧）。
//   （jev-retire 批 2026-11-16：jev 域随 JeV 全量退役移出本面板。）

import state from '../state.js';
import { t } from '../i18n.js';
import { h, frag } from '../dom.js';
import { escapeHtml } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { toggleHTML, setToggleState } from '../toggle.js';
import { providerCardHTML, addProviderButtonHTML, bindCardRemove, bindCardSelect } from '../cfgProviderCard.js';
import { showProviderForm, showProviderModal, protocolFormLabel, saveProviderWithAck, flushConfigToServer } from './providersUi.js';
import { imagegenTestButtonHTML, imagegenTestResultsHTML, bindImagegenTestButtons } from './imagegenTestButton.js';

/** 面板宿主 id（sidebar.js renderSettings 里唯一挂点）。 */
export const PROVIDER_PANEL_HOST_ID = 'provider-panel-host';

/** 类型枚举投影（GET /api/config/provider-types）；null = 尚未拉到。 */
/** @type {{domains: Array<{id:string, enabledSwitch:boolean}>, adapters: Record<string,string[]>}|null} */
let typeFace = null;

/** 非-LLM 域的状态投影（GET /api/config/<domain>）；null = 尚未拉到。 */
/** @type {any} */
let imagegenStatus = null;

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
 *   healthBadge?: boolean,
 *   providers: () => Record<string, any>,
 *   rows: () => Array<{name:string, badge:string, models:string[], metaExtra:string, active:boolean}>,
 *   fields: (of: any, ctx: any) => any[],
 *   extraActionsHTML: () => string,
 *   bindExtra: (overlay: HTMLElement, ctx: any) => void,
 *   persist: (values: any, ctx: any) => Promise<boolean>,
 *   remove: (name: string) => Promise<boolean>,
 *   setEnabled: (on: boolean) => Promise<boolean>,
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
            metaExtra: `<span class="cfg-card-sub">${escapeHtml(t('settings.cfgCard.models', { n: models.length }))}</span>`
              + `<span class="cfg-card-sub">${escapeHtml(endpointText(pc.baseUrl))}</span>`,
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
      return saveProviderWithAck(ctx.name, ctx.data, ctx.existingName);
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
          metaExtra: keyBadgeHTML(!!pc.keyConfigured)
            + `<span class="cfg-card-sub">${escapeHtml(endpointText(pc.baseUrl))}</span>`,
          active: false,
        };
      });
    },
    fields(of, ctx) {
      return [
        nameField(of),
        {
          key: 'type',
          label: t('provider.protocol'),
          type: 'select',
          value: of.type || '',
          options: adapterSelectOptions('imagegen', of.type),
          disabled: false,
        },
        { key: 'model', label: t('settings.imagegenModel'), type: 'text', value: of.model || '', placeholder: t('settings.imagegenModelPlaceholder') },
        { key: 'baseUrl', label: t('settings.imagegenBaseUrl'), type: 'text', value: of.baseUrl || '', placeholder: t('settings.imagegenBaseUrlPlaceholder') },
        secretField(ctx),
      ];
    },
    extraActionsHTML: imagegenTestButtonHTML,
    bindExtra(overlay, ctx) {
      bindImagegenTestButtons(overlay, imagegenFormReader);
      const ref = this.providers()[ctx.existingName]?.keyRef;
      const input = overlay.querySelector('input[data-field="apiKey"]');
      if (!ref || !ctx.keyConfigured || !(input instanceof HTMLInputElement)) return;
      input.dataset.revealSecretRef = ref;
    },
    async persist(values, ctx) {
      const name = values.name.trim();
      if (values.apiKey && values.apiKey.trim()) {
        const ref = existingKeyRef(this.providers(), ctx.existingName, name, `imagegen-${name.toLowerCase()}-key`.slice(0, 64));
        const okKey = await writeSecret(ref, values.apiKey.trim());
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
};

// ── 小件 ────────────────────────────────────────────────────────────────────

/**
 * 端点读数（作者令 2026-11-18「端点的配置这些……在设置里应该要可见」）：
 * **已配置 ⇒ 如实显示 URL**；**未配置 ⇒ 显式写出「默认端点」**（未配置显示
 * 占位是正确的，不是 bug —— 这里把「没配」写成一句可读的话，不留白、也不假装
 * 有一个端点）。端点不是凭据（密钥永远只在表单里按需揭示、面板零渲染）。
 * 用的是既有 `.cfg-card-sub`（已登记的可折行长文本件）⇒ 长 URL 在卡内折行，
 * 不顶宽、不新增造型件。
 * @param {unknown} baseUrl
 * @returns {string}
 */
function endpointText(baseUrl) {
  const url = String(baseUrl == null ? '' : baseUrl).trim();
  return url || t('settings.cfgCard.endpointDefault');
}

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

/**
 * 协议选择器的选项表（作者令 2026-11-18「已配置的…在设置里应该要可见」）：
 * 占位选项 + 后端投影的适配器清单；**存量值不在清单里时把它补回**（本构建没编入
 * 该适配器 / 引擎侧枚举改了名）——否则 select 会静默落到首项，已配置的协议在面上
 * 消失、保存时被悄悄改写。与 `renderModelIdSelect` 的「存量值补回」同一口径。
 * @param {string} domain
 * @param {unknown} current 存量值（'' = 新增面）
 * @returns {Array<{value:string,label:string}>}
 */
function adapterSelectOptions(domain, current) {
  const opts = [{ value: '', label: t('settings.imagegenTypePlaceholder') }]
    .concat(adapterOptions(domain).map((a) => ({ value: String(a), label: String(a) })));
  const cur = String(current == null ? '' : current).trim();
  if (cur && !opts.some((o) => o.value === cur)) opts.push({ value: cur, label: cur });
  return opts;
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

/** 整节/整块 PUT（imagegen 写腿形）。 */
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
    // Network failure: the form stays open, so the user must be told why nothing was saved.
    window.__showToast?.(t('settings.saveFailed'), 'error');
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
  return false;
}

function domainSpec(id) {
  return DOMAIN_SPECS[id] || null;
}

/** 整面板 HTML（类型标签 + 类型级附加行 + 统一列表 + 新增钮）。 */
export function providerPanelDOM() {
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
  const cardsFragment = document.createRange().createContextualFragment(cards);
  const listContent = sealedOff
    ? h('div', { class: 'cfg-hint' }, t('settings.imagegenNotCompiled'))
    : cardsFragment.childNodes.length
      ? cardsFragment
      : h('div', { class: 'cfg-hint' }, t('settings.cfgCard.emptyHint'));
  const drift = (typeFace && typeFace.domains || []).some((d) => !DOMAIN_SPECS[d.id]);
  const panel = h('div', { class: 'nf-provider-panel', 'data-provider-panel': true, 'data-type': current }, [
    h('div', { class: 'nf-ptabs', role: 'tablist', id: 'provider-type-tabs' }, ids.map((id) => h('button', {
      type: 'button', class: 'nf-ptab', role: 'tab', 'data-provider-type': id,
      'aria-selected': id === current ? 'true' : 'false',
    }, tabLabel(id)))),
    typeFaceError && h('div', { class: 'cfg-hint nf-provider-face-error', id: 'provider-face-error' }, t('settings.providerTypesError')),
    drift && h('div', { class: 'cfg-hint nf-provider-face-error', id: 'provider-face-drift' }, t('settings.providerTypesDrift')),
    h('div', { id: 'provider-type-extra' }, [
      spec && spec.enabledSwitch && h('div', { class: 'settings-row' }, [
        h('span', { class: 'settings-label' }, t('settings.imagegenEnabled')),
        document.createRange().createContextualFragment(toggleHTML({ on: enabledSwitchOn(current), id: 'toggle-provider-enabled', label: t('settings.enabled') })),
      ]),
    ]),
    h('div', { id: 'provider-list', class: 'provider-card-list' }, listContent),
    !sealedOff && document.createRange().createContextualFragment(addProviderButtonHTML({ id: 'btn-add-provider', face: 'provider' })),
  ]);
  return frag(panel);
}

/** 面板重画（宿主节点内原位替换，交互全部重绑）。 */
export function paintProviderPanel() {
  const host = document.getElementById(PROVIDER_PANEL_HOST_ID);
  if (!host) return;
  host.replaceChildren(providerPanelDOM());
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
    showProviderModal(name || null, existing || null, async (newName, data) => {
        const ok = await spec.persist({}, { name: newName, existingName: name || '', data });
        if (!ok) {
          window.__showToast?.(t('settings.saveFailed'), 'error');
          return false;
        }
        paintProviderPanel();
        return true;
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
    onConfirm: async (values) => {
      const verdict = validateProviderValues(type, values, ctx);
      if (!verdict.ok) {
        window.__showToast?.(t(verdict.key, verdict.params), 'error');
        return;
      }
      const ok = await spec.persist(values, ctx);
      if (!ok) return false;
      await refreshProjections();
      paintProviderPanel();
      window.__showToast?.(t('settings.cfgCard.saved'), 'success');
      return true;
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

/** 拉类型投影 + 非-LLM 域的状态投影并重画（幂等，可在任意时点调用）。 */
export async function refreshProjections() {
  const [face, img] = await Promise.all([
    fetchJson('/api/config/provider-types'),
    fetchJson('/api/config/imagegen'),
  ]);
  typeFaceError = !face;
  if (face) typeFace = face;
  imagegenStatus = img || imagegenStatus;
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

  // 启用开关（imagegen 才有）：即时落盘（开关类设置变更即保存，与设置页既有
  // toggle 纪律同源）。
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
