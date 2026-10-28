// modelTestButton.js — 每模型连接性测试钮（settings-panel-jev-imagegen 批 B 件，
// 作者令 2026-10-28 21:38 逐字：「在 provider 每一个模型旁边，加一个小按钮，用于
// 连接性测试，测试模型是否可用」）。
//
// 形态：provider 卡模型行尾小 ghost 钮 → 引擎 owner-auth 端点
// POST /api/provider/test {name, model}（ProviderModelProbe 单点：1 token 最小
// 真实调用，20s 有界诊断超时，错误文案服务侧已脱敏）→ 钮旁即时三态：
//   · 测试中 = spinner + 钮禁用（双击不重入；服务侧单飞闸 409 同兜）
//   · 可用   = 绿 ✓（+ 延迟 ms）
//   · 不可用 = 红 ✗ + 错误摘要（title 全文）
// 结果不落 localStorage、不改配置——纯诊断读数，再点即重测。
//
// 模块化落位（sidebar/ 子模块单向纪律）：渲染 + 绑定 + 结果绘制都是纯函数/
// 小绑定面，spec 可经 fixture 页驱动真实模块三态。

import { t } from '../i18n.js';
import { statusIcon } from '../utils.js';
import { authHeaders } from '../flowHelpers.js';

/**
 * Idle-state button markup for one model row. `data-*` carries the routing
 * identity (provider id + model id) — never credential material.
 * @param {string} provider stored provider id
 * @param {string} model model id
 * @returns {string}
 */
export function modelTestButtonHTML(provider, model) {
  return `<button type="button" class="cfg-model-test" data-test-provider="${escapeAttr(provider)}" data-test-model="${escapeAttr(model)}" title="${escapeAttr(t('provider.testModelTitle'))}" aria-label="${escapeAttr(t('provider.testModelTitle')) + ' ' + escapeAttr(model)}">${statusIcon('plug-zap', 12)}</button><span class="cfg-model-test-result" role="status" aria-live="polite"></span>`;
}

/** Attribute-safe escaping (quotes are the only hazard inside data-*). */
function escapeAttr(text) {
  return String(text ?? '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/**
 * The engine call. Resolves the normalized verdict; network/HTTP failures are
 * normalized into {ok:false, error} with the server-sanitized text (the
 * engine scrubs the stored key before it ever leaves the route).
 * @param {string} provider
 * @param {string} model
 * @returns {Promise<{ok: boolean, latencyMs?: number, error?: string}>}
 */
export async function runModelTest(provider, model) {
  try {
    const resp = await fetch('/api/provider/test', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: provider, model }),
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
 * Paint the tri-state result next to one test button. Any prior result first
 * clears (re-test repaints from scratch).
 * @param {HTMLButtonElement} btn
 * @param {{ok: boolean, latencyMs?: number, error?: string}} verdict
 */
export function paintTestResult(btn, verdict) {
  const slot = btn.nextElementSibling instanceof HTMLElement
    && btn.nextElementSibling.classList.contains('cfg-model-test-result')
    ? /** @type {HTMLElement} */ (btn.nextElementSibling) : null;
  btn.disabled = false;
  btn.classList.remove('testing');
  btn.innerHTML = statusIcon('plug-zap', 12);
  if (!slot) return;
  if (verdict.ok) {
    slot.innerHTML = statusIcon('circle-check', 11, 'st-ok')
      + (typeof verdict.latencyMs === 'number' ? `${verdict.latencyMs}ms` : '');
    slot.title = t('provider.testModelOk', { ms: String(verdict.latencyMs ?? 0) });
  } else {
    slot.innerHTML = statusIcon('circle-x', 11, 'st-err');
    slot.title = verdict.error || '';
    // 摘要面：错误首行截短进 slot 文本（title 全文）——可诊断错误显示位。
    const summary = (verdict.error || '').split('\n')[0].slice(0, 60);
    slot.appendChild(document.createTextNode(summary ? ` ${summary}` : ''));
  }
}

/** Paint the testing (spinner, disabled) state. */
function paintTesting(btn) {
  const slot = btn.nextElementSibling instanceof HTMLElement
    && btn.nextElementSibling.classList.contains('cfg-model-test-result')
    ? /** @type {HTMLElement} */ (btn.nextElementSibling) : null;
  btn.disabled = true; // 测试中禁重复点击
  btn.classList.add('testing');
  btn.innerHTML = `<span class="cfg-model-test-spinner" aria-hidden="true"></span>`;
  if (slot) {
    slot.innerHTML = '';
    slot.title = '';
  }
}

/**
 * Delegated binding for every test button under `root` (the settings panel
 * re-renders wholesale — a document-level delegation would leak across
 * re-renders; this binds one live subtree per render, provider card family
 * precedent).
 * @param {HTMLElement} root
 */
export function bindModelTestButtons(root) {
  root.querySelectorAll('.cfg-model-test').forEach((el) => {
    if (!(el instanceof HTMLButtonElement) || el.dataset.testBound === '1') return;
    el.dataset.testBound = '1';
    el.addEventListener('click', async (e) => {
      // 🔴 停冒泡：provider 卡是整卡点击=编辑弹窗（sidebar.js card click →
      // showProviderModal）。测试钮嵌在模型行内，事件冒泡到卡即把编辑弹窗
      // 盖上来——测试请求被遮、后续交互全被 overlay 拦截。按钮语义必须止于
      // 本钮（2026-10-28 真浏览器 e2e 实证：不拦截则面板被 cfg-modal 锁死）。
      e.stopPropagation();
      e.preventDefault();
      if (el.disabled) return; // 测试中不重入
      const provider = el.dataset.testProvider || '';
      const model = el.dataset.testModel || '';
      if (!provider || !model) return;
      paintTesting(el);
      const verdict = await runModelTest(provider, model);
      paintTestResult(el, verdict);
    });
  });
}
