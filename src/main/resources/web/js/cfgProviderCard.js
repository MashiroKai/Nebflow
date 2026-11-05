// cfgProviderCard.js — 配置提供方卡片「唯一共用组件」（jev-imagegen-align-llm 批 2026-11-02）。
//
// 为什么独立成件：设置页三面（LLM 服务商 / Jev / 生图）此前各写一份卡片 DOM，
// 结构一漂移就出现「同一个东西三个长相」。本件把卡片结构与两个交互绑定收成
// 单源，LLM 面 = 第一个消费方（sidebar.js 的 renderProviderCard 退化为薄壳），
// Jev / 生图面跟进（各自节点只读本件，不回改）。
//
// 落位：web/js/ 根而非 web/js/sidebar/ —— 消费方跨 sidebar.js 与 sidebar/ 两个
// 目录，放进 sidebar/ 会形成「sidebar.js → sidebar/x.js → sidebar.js」的语义倒挂。
//
// 门禁兼容：本文件被 sidebar.js import（打包图 orphan guard 的登记点），
// 全文零设计字面量（颜色/圆角/字号一律留给 css/sidebar.css 的 Config Cards 段），
// 零输入法组字判定（imeGuard 单源）。
//
// 契约：reports/20261102_jev-imagegen-align-llm/contract.md §A.1/§A.2/§A.3。

import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import { renderVisionBadge } from './modelCapabilities.js';

/**
 * 配置提供方卡片 HTML。
 *
 * DOM 与 LLM 面既有卡片逐字同形（契约 §A.2）：新增属性只有
 * `data-component="cfg-provider-card"` / `data-face` / `data-active` 三个，
 * 既有 class 族与层级顺序一个不动。
 *
 * 模型行的 `data-model-ref` 与 vision 徽标沿用既有约定：
 * ref = `<providerName>/<modelId>`（与 sidebar.js 抽取前的写法逐字一致），
 * 所以调用方只需给模型**显示串**，不必自己拼 ref。
 *
 * @param {{
 *   name: string,
 *   protocolBadge?: string,
 *   models?: Array<string>,
 *   metaExtra?: string,
 *   active?: boolean,
 *   face: 'llm'|'jev'|'imagegen'
 * }} spec
 * @returns {string}
 */
export function providerCardHTML(spec) {
  const name = String(spec.name ?? '');
  const face = String(spec.face || '');
  const models = Array.isArray(spec.models) ? spec.models : [];
  const badge = spec.protocolBadge
    ? `<span class="cfg-card-badge">${escapeHtml(spec.protocolBadge)}</span>`
    : '';
  // metaExtra = 徽标右侧附加 HTML（如 key 状态徽标）；缺席则退回「N 个模型」。
  // 原样插入 —— 调用方负责自行转义，这样 LLM 面能逐字保住既有 "N models" 文案。
  const metaExtra = spec.metaExtra == null ? '' : String(spec.metaExtra);
  const sub = metaExtra || escapeHtml(t('settings.cfgCard.models', { n: models.length }));

  const modelRows = models.map((m) => {
    const id = String(m);
    const ref = `${name}/${id}`;
    return `<div class="cfg-model-row" data-model-ref="${escapeHtml(ref)}">
      <span class="cfg-model-name">${escapeHtml(id)}</span>
      <div class="cfg-model-vision">${renderVisionBadge(ref)}</div>
    </div>`;
  }).join('');
  const modelList = models.length ? `<div class="cfg-model-list">${modelRows}</div>` : '';

  return `
    <div class="cfg-card" data-provider="${escapeHtml(name)}" data-component="cfg-provider-card" data-face="${escapeHtml(face)}" data-active="${spec.active ? '1' : '0'}">
      <div class="cfg-card-header">
        <span class="cfg-card-title">${escapeHtml(name)}</span>
        <button class="cfg-card-remove" data-provider="${escapeHtml(name)}" title="${escapeHtml(t('provider.remove'))}">×</button>
      </div>
      <div class="cfg-card-meta">
        ${badge}
        <span class="cfg-card-sub">${sub}</span>
      </div>
      ${modelList}
    </div>`;
}

/**
 * 「新增提供方」按钮 HTML（三面同一形态；LLM 面既有 `#btn-add-provider`
 * 由 sidebar.js 沿用旧写法不动，本助手供 Jev / 生图面使用）。
 *
 * @param {{ id: string, face?: string }} opts
 * @returns {string}
 */
export function addProviderButtonHTML(opts) {
  const id = escapeHtml(String(opts.id));
  const faceAttr = opts.face ? ` data-face="${escapeHtml(String(opts.face))}"` : '';
  return `<button class="cfg-btn cfg-btn-add" id="${id}" data-component="cfg-add-provider"${faceAttr}>${escapeHtml(t('settings.cfgCard.addProvider'))}</button>`;
}

/**
 * 给容器内所有 `.cfg-card-remove` 一次性挂 click（事件委托）。
 * 幂等：重复调用靠容器上的 data 哨兵短路。回调收到卡片名（data-provider）。
 *
 * @param {HTMLElement|null} container
 * @param {(name: string) => void} cb
 * @returns {void}
 */
export function bindCardRemove(container, cb) {
  if (!container || container.dataset.cfgCardRemoveBound === '1') return;
  container.dataset.cfgCardRemoveBound = '1';
  container.addEventListener('click', (e) => {
    const target = e.target instanceof HTMLElement ? e.target : null;
    const btn = target ? target.closest('.cfg-card-remove') : null;
    if (!btn) return;
    // 删除钮的点击不得穿透到「选中卡片」语义（既有 LLM 面 stopPropagation 同款）。
    e.stopPropagation();
    const card = btn.closest('.cfg-card[data-provider]');
    const name = card instanceof HTMLElement ? card.dataset.provider || '' : '';
    cb(name);
  });
}

/**
 * 卡片点击（选中 → 打开表单）。与 remove 分离：remove 的 stopPropagation
 * 之外，三面不必各自重写选择逻辑。
 *
 * @param {HTMLElement|null} container
 * @param {(name: string) => void} cb
 * @returns {void}
 */
export function bindCardSelect(container, cb) {
  if (!container || container.dataset.cfgCardSelectBound === '1') return;
  container.dataset.cfgCardSelectBound = '1';
  container.addEventListener('click', (e) => {
    const target = e.target instanceof HTMLElement ? e.target : null;
    if (!target) return;
    if (target.closest('.cfg-card-remove')) return;
    const card = target.closest('.cfg-card[data-provider]');
    if (!(card instanceof HTMLElement)) return;
    cb(card.dataset.provider || '');
  });
}
