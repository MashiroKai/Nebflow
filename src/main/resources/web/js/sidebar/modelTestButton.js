// modelTestButton.js — provider 连接性测试钮（settings-panel-jev-imagegen 批 B 件
// 2026-10-28「在 provider 每一个模型旁边，加一个小按钮，用于连接性测试」；
// fourfix 批 ②件 2026-10-29 作者令「连接性测试按钮应放到 LLM provider 配置页面
// 里」+「保证测试通知能看得到」——按钮自设置页 provider 卡模型行**迁入 provider
// 编辑弹窗**（被测对象 = 表单当前值），结果自行内截断摘要位改为弹窗内**独立
// 结果块**：每模型一行，全量错误文本换行显示，永不截断）。
//
// 引擎面：owner-auth 端点 POST /api/provider/test {name, model, baseUrl?,
// apiKey?, protocol?}（ProviderModelProbe 单点：1 token 最小真实调用，20s 有界
// 诊断超时，错误文案服务侧已脱敏）。覆写键 = 编辑弹窗表单当前值（可测未保存
// 配置/未入库新 provider；空 apiKey 不覆写 = 沿用存储凭据）。钮旁即时三态：
//   · 测试中 = 结果块该模型行 spinner + 「测试中…」（转圈状态肉眼明确）
//   · 可用   = 绿 ✓ + 延迟 ms
//   · 不可用 = 红 ✗ + 全量错误文本（换行完整显示，不截断）
// 结果不落 localStorage、不改配置——纯诊断读数，再点即重测。
//
// 模块化落位（sidebar/ 子模块单向纪律）：渲染 + 绑定 + 结果绘制都是纯函数/
// 小绑定面，spec 可经 fixture 页驱动真实模块三态。

import { t } from '../i18n.js';
import { statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';

/** Attribute-safe escaping (quotes are the only hazard inside data-*). */
function escapeAttr(text) {
  return String(text ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/** Text-safe escaping for model ids / error text landing in the results block. */
function escapeText(text) {
  return String(text ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Idle-state button markup for one model row (provider EDIT MODAL face since
 * the fourfix batch ②; the row carries only the routing identity — results
 * paint into the modal's shared results block, never inline).
 * @param {string} provider provider id (form field value)
 * @param {string} model model id
 * @returns {string}
 */
export function modelTestButtonHTML(provider, model) {
  return `<button type="button" class="cfg-model-test" data-test-provider="${escapeAttr(provider)}" data-test-model="${escapeAttr(model)}" title="${escapeAttr(t('provider.testModelTitle'))}" aria-label="${escapeAttr(t('provider.testModelTitle')) + ' ' + escapeAttr(model)}">${statusIcon('plug-zap', 12)}</button>`;
}

/**
 * The modal-level results block (one line per tested model). Sits below the
 * models list; `role=status` + aria-live so a verdict is announced.
 * @returns {string}
 */
export function testResultsBlockHTML() {
  return `<div class="cfg-test-results" data-role="test-results" role="status" aria-live="polite"></div>`;
}

/**
 * The engine call. Resolves the normalized verdict; network/HTTP failures are
 * normalized into {ok:false, error} with the server-sanitized text (the
 * engine scrubs key material before it ever leaves the route).
 * @param {string} provider
 * @param {string} model
 * @param {{baseUrl?: string, apiKey?: string, protocol?: string}|null} [overrides]
 *   the provider-config form's current values (unsaved edits testable);
 *   null/absent = stored-config leg (legacy shape).
 * @returns {Promise<{ok: boolean, latencyMs?: number, error?: string}>}
 */
export async function runModelTest(provider, model, overrides) {
  try {
    /** @type {Record<string, string>} */
    const body = { name: provider, model };
    if (overrides) {
      const baseUrl = (overrides.baseUrl || '').trim();
      const apiKey = (overrides.apiKey || '').trim();
      const protocol = (overrides.protocol || '').trim();
      if (baseUrl) body.baseUrl = baseUrl; // 覆写腿的判据键：引擎见 baseUrl 即走表单值
      if (apiKey) body.apiKey = apiKey; // 空 = 沿用存储凭据（留空保存语义同源）
      if (protocol) body.protocol = protocol;
    }
    const resp = await fetch('/api/provider/test', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = await resp.json().catch(() => null);
    if (resp.ok && data && data.ok === true) {
      return { ok: true, latencyMs: typeof data.latencyMs === 'number' ? data.latencyMs : undefined };
    }
    const err = data && /** @type {any} */ (data).error;
    return { ok: false, error: typeof err === 'string' && err ? err : t('provider.testModelFailedHttp', { code: String(resp.status) }) };
  } catch {
    return { ok: false, error: t('provider.testModelFailedIo') };
  }
}

/** Locate the modal results block relative to a root (modal overlay). */
function resultsBlockOf(root) {
  return root.querySelector('.cfg-test-results');
}

/** Find (or create) the result line for one model in the block. */
function lineFor(block, model) {
  const esc = escapeAttr(model);
  let line = /** @type {HTMLElement|null} */ (block.querySelector(`.cfg-test-result-line[data-model="${esc}"]`));
  if (!line) {
    line = document.createElement('div');
    line.className = 'cfg-test-result-line';
    line.dataset.model = model;
    block.appendChild(line);
  }
  return line;
}

/**
 * Paint the testing (spinner + visible label) line for one model. Any prior
 * verdict for the model is repainted in place (re-test = fresh line state).
 * @param {HTMLElement} block results block
 * @param {string} model
 */
export function paintTestingLine(block, model) {
  const line = lineFor(block, model);
  line.className = 'cfg-test-result-line testing';
  line.title = '';
  line.innerHTML = `<span class="cfg-model-test-spinner" aria-hidden="true"></span><span class="cfg-test-line-head">${escapeText(model)}</span><span class="cfg-test-line-text">${escapeText(t('provider.testRunning'))}</span>`;
}

/**
 * Paint the verdict for one model into the results block — the full,
 * non-truncated display face: green ✓ + latency, or red ✗ + the COMPLETE
 * error text (wrapping; `title` carries it too). Author order: 「保证测试通知
 * 能看得到……改成醒目不截断」.
 * @param {HTMLElement} block results block
 * @param {string} model
 * @param {{ok: boolean, latencyMs?: number, error?: string}} verdict
 */
export function paintTestResultLine(block, model, verdict) {
  const line = lineFor(block, model);
  if (verdict.ok) {
    line.className = 'cfg-test-result-line ok';
    line.title = t('provider.testModelOk', { ms: String(verdict.latencyMs ?? 0) });
    line.innerHTML = statusIcon('circle-check', 11, 'st-ok')
      + `<span class="cfg-test-line-head">${escapeText(model)}</span>`
      + `<span class="cfg-test-line-text">${typeof verdict.latencyMs === 'number' ? escapeText(`${verdict.latencyMs}ms`) : ''}</span>`;
  } else {
    const error = verdict.error || '';
    line.className = 'cfg-test-result-line err';
    line.title = error;
    line.innerHTML = statusIcon('circle-x', 11, 'st-err')
      + `<span class="cfg-test-line-head">${escapeText(model)}</span>`
      + `<span class="cfg-test-line-text">${escapeText(error)}</span>`;
  }
}

/**
 * Delegated binding for every test button under `root` (the provider edit
 * modal overlay). ONE delegated click listener on the overlay — rows are
 * added/removed freely inside a live modal (cfg-model-add), so per-button
 * listeners would miss every row created after bind time. `getProvider`/
 * `getModel`/`getOverrides` supply the form's CURRENT provider id, model id
 * and baseUrl/apiKey/protocol at click time (never captured stale — add-modal
 * name/model fields edit freely before saving; the render-time data-* attrs
 * are the fallback).
 * @param {HTMLElement} root
 * @param {{getProvider?: () => string, getModel?: (btn: HTMLButtonElement) => string, getOverrides?: () => ({baseUrl?: string, apiKey?: string, protocol?: string}|null), resultsEl?: HTMLElement|null}} [opts]
 */
export function bindModelTestButtons(root, opts) {
  const block = (opts && opts.resultsEl) || resultsBlockOf(root);
  if (!block || root.dataset.testDelegated === '1') return;
  root.dataset.testDelegated = '1';
  root.addEventListener('click', (e) => {
    const path = e.composedPath ? /** @type {EventTarget[]} */ (e.composedPath()) : [];
    const hit = path.find((n) => n instanceof HTMLElement && n.classList.contains('cfg-model-test'));
    if (!hit) return;
    const el = /** @type {HTMLButtonElement} */ (hit);
    // 🔴 停冒泡：provider 弹窗家族（cfg-modal-overlay 点击即关）——按钮语义
    // 必须止于本钮，测试动作不得顺手把配置弹窗关掉。
    e.stopPropagation();
    e.preventDefault();
    if (el.disabled) return; // 测试中不重入
    const provider = (opts && opts.getProvider ? opts.getProvider() : el.dataset.testProvider) || '';
    const model = (opts && opts.getModel ? opts.getModel(el) : el.dataset.testModel) || '';
    if (!provider || !model) return;
    el.disabled = true; // 本钮测试中禁用（结果块 spinner 是主状态面）
    el.classList.add('testing');
    el.innerHTML = `<span class="cfg-model-test-spinner" aria-hidden="true"></span>`;
    paintTestingLine(block, model);
    const overrides = opts && opts.getOverrides ? opts.getOverrides() : null;
    void runModelTest(provider, model, overrides).then((verdict) => {
      paintTestResultLine(block, model, verdict);
      el.disabled = false;
      el.classList.remove('testing');
      el.innerHTML = statusIcon('plug-zap', 12);
    });
  });
}
