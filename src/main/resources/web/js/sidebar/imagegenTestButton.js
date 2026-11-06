// imagegenTestButton.js — 生图「测试可用性 / 实跑一次最小生成」双腿（provider-add-tabs
// 批 2026-11-06 自 `sidebar/imagegenSection.js` 迁出，逻辑逐字保留）。
//
// 为什么单独成件：生图配置面在 provider-add-tabs 批整体并入**统一服务商面板**
// （js/sidebar/providerPanel.js）与**统一表单骨架**（providersUi.showProviderForm），
// 旧的整节渲染器（卡片列表 + 内联表单 + 两段式删除 + 整节 PUT）全部退役；只剩
// 「诊断按钮」这条能力是无处安放的类型专属件，故迁到本文件、改成**读表单骨架
// 的字段**（不再 hard-code 旧 id：
//   #cfg-imagegen-name / -type / -model / -baseurl / -key  →  data-field="…" ）。
//
// 口径（settings-panel-jev-imagegen 批 2026-10-29 作者令「然后 Jev/生图，也要像
// 我们LLM这样，有个按钮来测试可用性」）不变：
//   a) 轻探活 = key 解析 + 上游可达（401 = 死 key 状态码原样可见）；
//   b) 实跑一次最小生成 = size 256 / 最低质量真打上游，回显 ✅+耗时+字节数+格式+缩略图。
//   测的是表单当前值（未保存也测；key 有值 = 覆写腿，空 = 存储凭据腿）；单飞 +
//   测试中禁用（双钮联动）+ 结果保留可重测（不落 localStorage、不改配置）。

import { t } from '../i18n.js';
import { statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { paintTestingLine, paintTestResultLine } from './modelTestButton.js';

/** 结果块（与 LLM 弹窗同一套 .cfg-test-results 组件族）。 */
export function imagegenTestResultsHTML() {
  return `<div class="cfg-test-results imagegen-test-results" data-role="test-results" role="status" aria-live="polite"></div>`;
}

/** 两枚诊断钮（放在表单骨架的动作行里，经 extraActionsHTML 注入）。 */
export function imagegenTestButtonHTML() {
  return `<button type="button" class="cfg-btn" id="btn-test-imagegen" title="${t('settings.imagegenTestTitle')}" aria-label="${t('settings.imagegenTestTitle')}">${statusIcon('plug-zap', 12)}<span>${t('settings.imagegenTestBtn')}</span></button>
        <button type="button" class="cfg-btn" id="btn-imagegen-generate" title="${t('settings.imagegenGenTitle')}" aria-label="${t('settings.imagegenGenTitle')}">${statusIcon('image', 12)}<span>${t('settings.imagegenGenBtn')}</span></button>`;
}

/** Shared single-flight flag: both test controls disable while either runs. */
let imagegenTestInFlight = false;

/**
 * The engine call (owner-auth POST /api/imagegen/test). Required-field
 * validation reuses the save flow's toasts — a test without provider/type/
 * model guides instead of firing a注定 400 的击.
 * @param {'probe'|'generate'} mode
 * @param {{provider:string,type:string,model:string,baseUrl:string,apiKey:string}} form
 * @returns {Promise<{ok: boolean, latencyMs?: number, bytes?: number, format?: string, thumbnail?: string, modelsFaceAbsent?: number, error?: string}>}
 */
export async function runImagegenTest(mode, form) {
  const { provider: name, type, model, baseUrl, apiKey: keyValue } = form;
  if (!name) { window.__showToast?.(t('settings.imagegenNameRequired'), 'error'); return { ok: false, error: 'missing-provider' }; }
  if (!type) { window.__showToast?.(t('settings.imagegenTypeRequired'), 'error'); return { ok: false, error: 'missing-type' }; }
  if (!model) { window.__showToast?.(t('settings.imagegenModelRequired'), 'error'); return { ok: false, error: 'missing-model' }; }
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

/** Append a muted note under an already-painted verdict line. */
function appendLineNote(block, key, note) {
  const line = /** @type {HTMLElement|null} */ (block.querySelector(`.cfg-test-result-line[data-model="${escapeAttr(key)}"]`));
  const textEl = line?.querySelector('.cfg-test-line-text');
  if (textEl) textEl.textContent += ` ${note}`;
}

/** Decorate the generate verdict with real-run stats + thumbnail preview. */
function decorateGenerateLine(block, key, v) {
  const line = /** @type {HTMLElement|null} */ (block.querySelector(`.cfg-test-result-line[data-model="${escapeAttr(key)}"]`));
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

function escapeAttr(text) {
  return String(text).replace(/["&<>]/g, (c) => ({ '"': '&quot;', '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] || c));
}

/**
 * Wire the two test buttons (probe + minimal generation). Shared single-flight:
 * while one test runs both buttons disable; the verdict repaints the shared
 * results line in place (re-test = fresh line state).
 * @param {HTMLElement} root the form (modal) root — buttons live in its action row
 * @param {(root: HTMLElement) => {getProvider:()=>string,getType:()=>string,getModel:()=>string,getBaseUrl:()=>string,getApiKey:()=>string}} reader
 */
export function bindImagegenTestButtons(root, reader) {
  const probeBtn = /** @type {HTMLButtonElement|null} */ (root.querySelector('#btn-test-imagegen'));
  const genBtn = /** @type {HTMLButtonElement|null} */ (root.querySelector('#btn-imagegen-generate'));
  if (!probeBtn || !genBtn) return;
  let block = /** @type {HTMLElement|null} */ (root.querySelector('.imagegen-test-results'));
  if (!block) {
    const body = root.querySelector('.cfg-modal-body') || root;
    body.insertAdjacentHTML('beforeend', imagegenTestResultsHTML());
    block = /** @type {HTMLElement|null} */ (root.querySelector('.imagegen-test-results'));
  }
  if (!block) return;
  const results = /** @type {HTMLElement} */ (block);
  const form = () => {
    const r = reader(root);
    return {
      provider: r.getProvider(), type: r.getType(), model: r.getModel(), baseUrl: r.getBaseUrl(), apiKey: r.getApiKey(),
    };
  };
  const probeIdle = probeBtn.innerHTML;
  const genIdle = genBtn.innerHTML;
  const spinnerFace = (label) => `<span class="cfg-model-test-spinner" aria-hidden="true"></span><span>${label}</span>`;
  const setDisabled = (v) => { probeBtn.disabled = v; genBtn.disabled = v; };

  probeBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (imagegenTestInFlight || probeBtn.disabled) return;
    const f = form();
    const name = f.provider || 'imagegen';
    imagegenTestInFlight = true;
    setDisabled(true);
    probeBtn.innerHTML = spinnerFace(t('provider.testRunning'));
    paintTestingLine(results, name);
    const v = await runImagegenTest('probe', f);
    if (v.ok) {
      paintTestResultLine(results, name, { ok: true, latencyMs: v.latencyMs });
      if (v.modelsFaceAbsent) appendLineNote(results, name, t('settings.imagegenProbeModelsAbsent', { code: String(v.modelsFaceAbsent) }));
    } else if (v.error !== 'missing-provider' && v.error !== 'missing-type' && v.error !== 'missing-model') {
      paintTestResultLine(results, name, { ok: false, error: v.error });
    }
    setDisabled(false);
    probeBtn.innerHTML = probeIdle;
    imagegenTestInFlight = false;
  });

  genBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (imagegenTestInFlight || genBtn.disabled) return;
    const f = form();
    const name = f.provider || 'imagegen';
    imagegenTestInFlight = true;
    setDisabled(true);
    genBtn.innerHTML = spinnerFace(t('settings.imagegenGenRunning'));
    paintTestingLine(results, name);
    const v = await runImagegenTest('generate', f);
    if (v.ok) {
      paintTestResultLine(results, name, { ok: true, latencyMs: v.latencyMs });
      decorateGenerateLine(results, name, v);
    } else if (v.error !== 'missing-provider' && v.error !== 'missing-type' && v.error !== 'missing-model') {
      paintTestResultLine(results, name, { ok: false, error: v.error });
    }
    setDisabled(false);
    genBtn.innerHTML = genIdle;
    imagegenTestInFlight = false;
  });
}
