// modelThinkingSelect.js — 每模型思考档位下拉（thinking-config 实施批 Phase 2，
// 2026-10-29；设计 docs/design/20261028_215449_thinking-config-research…md §5
// 「按模型」分组的**最小面**落位：复用 provider 卡模型行，不再新增独立折叠区）。
//
// 形态：模型行内 effort 下拉——继承(缺省)/low/medium/high/max/off。
//   · 「继承」项右侧灰字显示解析继承链后的实际生效值（所见即所得）；
//   · 强制思考模型（glm / kimi-for-coding 族 / k3.1 恒推理，显示端镜像引擎
//     ThinkingFamily.forModel）：off 档置灰 + 行内徽标「模型强制开启思考」；
//   · 三档面（glm / k3.1 / deepseek）medium 项显示映射提示 medium → high
//     （EffortWire.threeTier 引擎侧同映射，medium 不会原样上线）。
// 写路：WS setThinking 带 provider+model 键（缺省=全局，老前端零感知）——
//   继承 = {effort:null, enabled:null}（整层回继承）；off = {enabled:false}；
//   档位 = {enabled:true, effort:v}。成功回执 thinkingLayerSaved → 重拉
//   getConfig 刷新 state.parsedConfig（下拉重渲即见新值）。
//
// 显示端纯读（state.parsedConfig），不发探测、不落 localStorage。

import { t } from '../i18n.js';
import { onMessage, sendWs } from '../ws.js';
import state from '../state.js';

/**
 * Display-side mirror of the engine's ThinkingFamily.forModel (nebflow.shared
 * .ThinkingMode.scala) — prefix table, same order. Display ONLY: the wire is
 * decided engine-side; a drifted mirror can at worst mis-gray an option.
 * @param {string} model model id
 * @returns {'toggle'|'effort'|'k3'|'budget'}
 */
export function thinkingFamilyOf(model) {
  const m = String(model || '').toLowerCase();
  if (m.includes('glm')) return 'toggle';
  if (m.includes('deepseek')) return 'effort';
  if (m.includes('k3')) return 'k3';
  if (m.includes('kimi')) return 'toggle';
  return 'budget';
}

/** Models whose thinking cannot be turned off (forced-on / always-reasoning). */
function isForced(family) {
  return family === 'toggle' || family === 'k3';
}

/** Three-tier faces only accept low/high/max — medium is mapped client-side. */
function isThreeTier(family) {
  return family === 'toggle' || family === 'k3' || family === 'effort';
}

/**
 * Resolved effective effort after the inheritance chain
 * (model > provider > global effort key > global budget derivation).
 * @param {{thinking?: {effort?: string}|null}|null} modelCfg
 * @param {{thinking?: {effort?: string}|null}|null} providerCfg
 * @returns {string}
 */
export function resolvedThinkingEffort(modelCfg, providerCfg) {
  const explicit = modelCfg?.thinking?.effort || providerCfg?.thinking?.effort
    || state.parsedConfig?.thinkingConfig?.effort;
  if (typeof explicit === 'string' && explicit) return explicit;
  // 全局 effort 未配 ⇒ 按全局 budget 推导（与全局下拉同一口径，#345 边界）。
  const tc = state.parsedConfig?.thinkingConfig;
  if (!tc || tc.enabled === false) return 'off';
  const b = typeof tc.budgetTokens === 'number' ? tc.budgetTokens : 32000;
  if (b <= 2048) return 'low';
  if (b <= 8192) return 'medium';
  return 'high';
}

/** Attribute/text escaping (model ids and provider ids are untrusted strings). */
function escapeAttr(text) {
  return String(text ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/**
 * One model row's thinking-effort select markup.
 * @param {string} provider stored provider id
 * @param {{id: string, thinking?: {enabled?: boolean, effort?: string}|null}} m model config node
 * @param {{thinking?: {effort?: string}|null}|null} providerCfg provider config node
 * @returns {string}
 */
export function modelThinkingSelectHTML(provider, m, providerCfg) {
  const model = String(m?.id ?? '');
  const family = thinkingFamilyOf(model);
  const forced = isForced(family);
  const layer = m?.thinking || null;
  // 当前层值：显式 effort 档位优先；enabled:false = off；否则继承。
  const current = layer && layer.effort ? String(layer.effort)
    : (layer && layer.enabled === false ? 'off' : 'inherit');
  const effective = resolvedThinkingEffort(m, providerCfg);
  const mediumHint = isThreeTier(family) ? ` (${t('settings.thinkingMediumMap')})` : '';
  const options = ['inherit', 'low', 'medium', 'high', 'max', 'off'].map((lvl) => {
    const label = lvl === 'inherit'
      ? `${t('settings.thinkingInherit')} · ${t('settings.thinkingEffective', { v: forced && effective === 'off' ? 'high' : effective })}`
      : t(`settings.thinkingEffort.${lvl}`);
    const disabled = lvl === 'off' && forced ? 'disabled' : '';
    const selected = current === lvl ? ' selected' : '';
    return `<option value="${lvl}"${selected}${disabled}>${escapeAttr(label)}${lvl === 'medium' ? escapeAttr(mediumHint) : ''}</option>`;
  }).join('');
  const badge = forced ? ` <span class="cfg-thinking-forced-badge" title="${escapeAttr(t('settings.thinkingForcedBadge'))}">${escapeAttr(t('settings.thinkingForcedBadge'))}</span>` : '';
  return `<select class="cfg-select cfg-model-thinking" data-thinking-provider="${escapeAttr(provider)}" data-thinking-model="${escapeAttr(model)}" aria-label="${escapeAttr(t('settings.thinkingPerModel'))}">${options}</select>${badge}`;
}

/**
 * Delegated binding for every thinking select under `root` (per-render subtree,
 * modelTestButton family precedent) — change writes one WS setThinking frame
 * with provider+model routing keys.
 * @param {HTMLElement} root
 */
export function bindModelThinkingSelects(root) {
  root.querySelectorAll('.cfg-model-thinking').forEach((el) => {
    if (!(el instanceof HTMLSelectElement) || el.dataset.thinkingBound === '1') return;
    el.dataset.thinkingBound = '1';
    el.addEventListener('change', function () {
      const provider = this.dataset.thinkingProvider || '';
      const model = this.dataset.thinkingModel || '';
      if (!provider || !model) return;
      /** @type {{effort?: string|null, enabled?: boolean|null}} */
      let thinking;
      if (this.value === 'inherit') thinking = { effort: null, enabled: null };
      else if (this.value === 'off') thinking = { enabled: false };
      else thinking = { enabled: true, effort: this.value };
      sendWs({ type: 'setThinking', provider, model, thinking });
    });
  });
}

/** Save-echo: re-pull configData once per saved layer (bound once per page). */
let echoBound = false;
export function bindThinkingLayerEcho() {
  if (echoBound) return;
  echoBound = true;
  onMessage('thinkingLayerSaved', () => {
    sendWs({ type: 'getConfig' });
  });
}
