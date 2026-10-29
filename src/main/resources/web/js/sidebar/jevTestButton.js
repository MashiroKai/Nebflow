// jevTestButton.js — JeV 分配区「测试可用性」钮（settings-panel-jev-imagegen 批
// 2026-10-29 作者令逐字：「然后 Jev/生图，也要像我们LLM这样，有个按钮来测试
// 可用性」）。
//
// 口径与 LLM 测试钮（modelTestButton.js）逐字同源：真实探活、结果就地可见
// （✅+耗时 ms / ❌+HTTP 状态码+错误首行）、结果块同一套 cfg-test-results 组件
// （绘制函数直接复用 paintTestingLine/paintTestResultLine——零第二套状态面）；
// 测的是表单当前值（provider/endpoint/keyRef，未保存也测）；单飞+测试中禁用+
// 结果保留可重测（结果不落 localStorage、不改配置——纯诊断读数）。
//
// 引擎面：owner-auth 端点 POST /api/jev/test {provider?, endpoint?, keyRef?}
//（JevProbe 单点：laya 走 /health metadata 面、typesafe 走 1-noul-问题极小真实
// 补全，20s 有界诊断超时，错误文案服务侧已脱敏；keyRef secret 缺席 = 直接
// 「待录入」失败，不发注定 401 的击）。
//
// 模块化落位（sidebar/ 子模块单向纪律）：JeV 表单本体在 sidebar.js 内联渲染，
// 本模块只管钮 + 结果块 + 引擎调用的绑定面；spec 经 fixture 页驱动真实模块。

import { t } from '../i18n.js';
import { statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';
import { paintTestingLine, paintTestResultLine } from './modelTestButton.js';

/**
 * Idle-state test button (sits in the JeV form's save row, next to 保存).
 * @returns {string}
 */
export function jevTestButtonHTML() {
  const title = t('settings.jevTestTitle');
  return `<button type="button" class="cfg-btn cfg-jev-test" id="btn-test-jev" title="${title}" aria-label="${title}">${statusIcon('plug-zap', 12)}<span>${t('settings.jevTestBtn')}</span></button>`;
}

/**
 * The results block (one line; the LLM modal's same .cfg-test-results face).
 * `role=status` + aria-live so a verdict is announced.
 * @returns {string}
 */
export function jevTestResultsHTML() {
  return `<div class="cfg-test-results cfg-test-results-jev" data-role="test-results" role="status" aria-live="polite"></div>`;
}

/**
 * The engine call. Form's CURRENT values ride the body (unsaved edits
 * testable); an absent/empty field is omitted — the engine falls back to the
 * stored config leg field-by-field. Network/HTTP failures normalize into
 * {ok:false, error} with the server-sanitized text.
 * @param {{provider: string, endpoint: string, keyRef: string}} form
 * @returns {Promise<{ok: boolean, latencyMs?: number, error?: string}>}
 */
export async function runJevTest(form) {
  try {
    /** @type {Record<string, string>} */
    const body = {};
    const provider = (form.provider || '').trim();
    const endpoint = (form.endpoint || '').trim();
    const keyRef = (form.keyRef || '').trim();
    if (provider) body.provider = provider;
    if (endpoint) body.endpoint = endpoint;
    if (keyRef) body.keyRef = keyRef;
    const resp = await fetch('/api/jev/test', {
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

/**
 * Bind the JeV test button. `getForm` supplies the form's CURRENT values at
 * click time (never captured stale). Single-flight: one click in flight —
 * the button disables during the test and re-enables on the verdict; the
 * result line stays until the next test (re-test = fresh line state).
 * @param {HTMLElement} root container of both the button and the results block
 * @param {{getForm: () => ({provider: string, endpoint: string, keyRef: string})}} opts
 */
export function bindJevTestButton(root, opts) {
  const btn = /** @type {HTMLButtonElement|null} */ (root.querySelector('#btn-test-jev'));
  const block = root.querySelector('.cfg-test-results-jev');
  if (!btn || !block || btn.dataset.testBound === '1') return;
  btn.dataset.testBound = '1';
  const idleHTML = btn.innerHTML;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (btn.disabled) return; // 单飞：测试中不重入
    const form = opts.getForm();
    const head = (form.provider || '').trim() || 'jev';
    btn.disabled = true; // 测试中禁用（结果块 spinner 是主状态面）
    btn.classList.add('testing');
    btn.innerHTML = `<span class="cfg-model-test-spinner" aria-hidden="true"></span><span>${t('provider.testRunning')}</span>`;
    paintTestingLine(/** @type {HTMLElement} */ (block), head);
    void runJevTest(form).then((verdict) => {
      paintTestResultLine(/** @type {HTMLElement} */ (block), head, verdict);
      btn.disabled = false;
      btn.classList.remove('testing');
      btn.innerHTML = idleHTML;
    });
  });
}
