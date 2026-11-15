// settings-imagegen-two-forms.spec.mjs — 生图配置语义钉（settings-panel-jev-imagegen
// 批 2026-10-28 初版；settings-panel-fourfix 批 ③件 2026-10-29 作者令重做：
// 「就是普通配置过程——填 API Key 值、保存，完事」「前端永远不出现引用/keyRef/
// secrets 指针这类字眼与解释文案」「不要过拟合智谱——新用户看到的必须是全空」
// 「整体精简」）。
//
// 🔴 provider-add-tabs 批（2026-11-06 作者令「添加服务商统一成标签切换类型 +
// 单一服务商面板」）判据换代：生图节（#imagegen-advance-* 两形态 + 内联表单）
// 随旧节整件退役，驱动模块 js/sidebar/imagegenSection.js 已删除；本文件改钉
// **统一服务商面板 · Image generation 标签**上的同一批语义（编号承接）：
//   A  seal=OFF 形态：未编入提示位、配置面控件全无（列表/新增/表单都不渲染）
//   B1+B2 seal=ON 既有 provider：投影值在位 + 键位徽标 + 零 keyRef 字眼 + 单一保存钮
//   B3 新用户空白：providers 空 → 全空新增面（零 zhipu/cogview 特化值）
//   B4 保存形状（新 provider）：派生密钥名 imagegen-<name>-key（值只进请求体）
//      + 整节 PUT {enabled, providers, defaultProvider}
//   B5 既有 provider 重录：409 → PUT 覆写；已配置态 + 输入清空
//   B6 整节替换 PUT：全部 provider 原样保留、投影字段不回写
//   B7 删除提供方：统一确认框 → PUT 少该项（defaultProvider 让位）
//   B8 「调试中」徽标零渲染（功能开放批：debugBadge 单源已撤）
//
// SELF-CONTAINED（jev-panel-threefix.spec.mjs 同款 harness）：静态服务器
// 127.0.0.1:8247 + route mock API + routeWebSocket mock WS，零真实后端。
// Run: npx playwright test tests/settings-imagegen-two-forms.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8247; // fixed, 8100+ per task discipline — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

const TYPES = {
  domains: [{ id: 'llm', enabledSwitch: false }, { id: 'imagegen', enabledSwitch: true }, { id: 'jev', enabledSwitch: true }],
  adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview', 'openai-images'], jev: ['typesafe-jev'] },
};

/**
 * Route interception with a call ledger. GET /api/config/imagegen replies with
 * state.status; a secrets POST that lands flips it to state.afterSecret
 * (engine semantics: the secret now exists → keyConfigured=true on re-read).
 * @param {import('@playwright/test').Page} page
 * @param {{status: any, afterSecret?: any, existingSecrets?: Set<string>, secretPostConflict?: boolean}} st
 */
function wireRoutes(page, st, ledger) {
  st.existingSecrets = st.existingSecrets || new Set();
  page.route('**/api/config/provider-types', r => r.fulfill({ json: TYPES }));
  page.route('**/api/config/imagegen', async (route) => {
    const req = route.request();
    if (req.method() === 'GET') return route.fulfill({ json: st.status });
    if (req.method() === 'PUT') {
      ledger.sectionPuts.push(JSON.parse(req.postData() || '{}'));
      return route.fulfill({ json: { updated: true } });
    }
    return route.fulfill({ status: 405, json: {} });
  });
  page.route('**/api/secrets/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const name = decodeURIComponent(url.pathname.split('/').pop() || '');
    if (req.method() === 'POST' || req.method() === 'PUT') {
      ledger.secrets.push({ method: req.method(), name, value: JSON.parse(req.postData() || '{}').value });
      const exists = st.existingSecrets.has(name);
      if (req.method() === 'POST' && exists) return route.fulfill({ status: 409, json: { error: 'conflict' } });
      st.existingSecrets.add(name);
      if (st.afterSecret) st.status = st.afterSecret;
      return route.fulfill({ json: { ok: true } });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  page.route('**/api/secrets', r => r.fulfill({ json: { secrets: [...(st.existingSecrets || [])].map(n => ({ name: n })) } }));
  page.route('**/api/decision/health', r => r.fulfill({ json: { decision: { status: 'unconfigured' } } }));
  page.route('**/api/config/provider-keys', r => r.fulfill({ json: { providers: {} } }));
}

/** Boot the real shell (WS mock carries an LLM config so settings renders). */
async function bootPage(page, st, ledger) {
  wireRoutes(page, st, ledger);
  await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  let wsSend = () => {};
  await page.routeWebSocket(/\/ws/, ws => {
    wsSend = obj => ws.send(JSON.stringify(obj));
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    wsSend({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify({ llm: { providers: {} } }) });
    wsSend({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 });
    wsSend({ type: 'sessionList', sessions: [], activeId: null, folders: [] });
  });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 15000 });
  await page.evaluate(async () => {
    (await import('/js/sidebar.js')).openSettingsPanel();
    document.getElementById('settings-overlay')?.classList.add('on');
  });
  // Providers is a progressive-disclosure section: the unified panel mounts only once the user expands it.
  await page.evaluate(() => document.querySelector('[data-settings-disclosure="providers"]')?.click());
  await page.waitForSelector('#provider-panel-host .nf-provider-panel', { timeout: 10000 });
  await page.waitForTimeout(400); // projections land → repaint
  await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="imagegen"]')?.click());
  await page.waitForSelector('.nf-provider-panel[data-type="imagegen"]', { timeout: 5000 });
}

/** Open the unified add/edit form (imagegen tab). */
async function openForm(page) {
  await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
  await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 5000 });
}

/** B2 seam: the panel + form carry zero pointer wording. */
async function expectNoPointerWording(page) {
  const text = await page.evaluate(() => document.getElementById('settings-content')?.innerText || '');
  expect(text).not.toContain('keyRef');
  expect(text).not.toContain('密钥引用');
  expect(text).not.toContain('指针');
  // 用户可见 locale 面（源级补钉）：两份 locale 的 imagegen 键无 keyRef 字眼。
  for (const f of ['js/locales/zh-CN.js', 'js/locales/en.js']) {
    const src = await readFile(resolve(WEB, f), 'utf8');
    const imagegenKeys = src.split('\n').filter((l) => l.includes("'settings.imagegen"));
    expect(imagegenKeys.some((l) => /keyRef|密钥引用|指针/.test(l))).toBe(false);
  }
}

test('A. seal=OFF 形态：未编入提示位 + 配置面控件全无（列表/新增/表单都不渲染）', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: false, enabled: true, providers: {} } };
  await bootPage(page, st, ledger);
  const dump = await page.evaluate(() => ({
    hint: document.getElementById('provider-list')?.textContent.trim() || '',
    addBtn: !!document.getElementById('btn-add-provider'),
    cards: document.querySelectorAll('#provider-list .cfg-card').length,
    badge: document.querySelectorAll('.imagegen-badge').length,
  }));
  expect(dump.hint, 'seal OFF = the not-compiled hint instead of a face').toContain('未编入');
  expect(dump.addBtn, 'no add entry in the sealed-off tab').toBe(false);
  expect(dump.cards, 'no provider cards in the sealed-off tab').toBe(0);
  expect(dump.badge, 'zero debug badges (B8 legacy anchor)').toBe(0);
  await expectNoPointerWording(page);
});

test('B1+B2. seal=ON 既有 provider：投影值在位 + 键位徽标 + 零 keyRef 字眼 + 单一保存钮', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: true, enabled: true, defaultProvider: 'zhipu', providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: false } } } };
  await bootPage(page, st, ledger);
  const dump = await page.evaluate(() => {
    const card = document.querySelector('#provider-list .cfg-card');
    return {
      name: card?.querySelector('.cfg-card-title')?.textContent.trim(),
      type: card?.querySelector('.cfg-card-type')?.textContent.trim(),
      protocol: card?.querySelector('.cfg-card-badge')?.textContent.trim(),
      key: card?.querySelector('.st-badge')?.textContent.trim(),
      model: card?.querySelector('.cfg-model-name')?.textContent.trim() || '',
    };
  });
  expect(dump.name, 'provider name from the projection').toBe('zhipu');
  expect(dump.type, 'type badge = the panel tab face').toBe('Image generation');
  expect(dump.protocol, 'adapter badge = projected adapter').toBe('cogview');
  expect(dump.key, 'missing-key state badge (zh 词面 = fourfix 定版「未配置」)').toBe('未配置');
  expect(dump.model, 'model id rides the meta line').toContain('cogview-3-flash');
  await expectNoPointerWording(page);
});

test('B3. 新用户空白：providers 空 → 全空新增面（零 zhipu/cogview 特化值）', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: true, enabled: false, providers: {} } };
  await bootPage(page, st, ledger);
  await openForm(page);
  const form = await page.evaluate(() => ({
    name: document.querySelector('#cfg-modal [data-field="name"]')?.value,
    type: document.querySelector('#cfg-modal [data-field="type"]')?.value,
    model: document.querySelector('#cfg-modal [data-field="model"]')?.value,
    key: document.querySelector('#cfg-modal [data-field="apiKey"]')?.value,
    saveBtns: [...document.querySelectorAll('#cfg-modal .cfg-btn-save')].length,
    text: document.getElementById('cfg-modal').innerText,
  }));
  expect(form.name, 'name starts empty').toBe('');
  expect(form.type, 'adapter starts unset (placeholder, no cogview default)').toBe('');
  expect(form.model, 'model starts empty').toBe('');
  expect(form.key, 'key starts empty').toBe('');
  expect(form.saveBtns, 'exactly ONE save button (B2 单一保存钮)').toBe(1);
  expect(form.text, 'zero zhipu/cogview specialization in a fresh form').not.toContain('zhipu');
});

test('B4. 保存形状（新 provider）：派生密钥名 + 整节 PUT + defaultProvider', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: true, enabled: true, providers: {} } };
  await bootPage(page, st, ledger);
  await openForm(page);
  await page.fill('#cfg-modal [data-field="name"]', 'Alpha');
  await page.selectOption('#cfg-modal [data-field="type"]', 'cogview');
  await page.fill('#cfg-modal [data-field="model"]', 'cogview-3-flash');
  await page.fill('#cfg-modal [data-field="apiKey"]', 'sk-live-value-123');
  await page.evaluate(() => document.querySelector('#cfg-modal .cfg-btn-save')?.click());
  await page.waitForTimeout(400);
  expect(ledger.secrets, 'one secrets write').toHaveLength(1);
  expect(ledger.secrets[0].name, '🔴 derived secret name = imagegen-<name>-key（值只进请求体）').toBe('imagegen-alpha-key');
  expect(ledger.secrets[0].value, 'the key value travels in the request body').toBe('sk-live-value-123');
  expect(ledger.sectionPuts, 'one section PUT').toHaveLength(1);
  const put = ledger.sectionPuts[0];
  expect(put.enabled, 'enabled travels').toBe(true);
  // 名字按输入原样保存（大小写保留）；密钥派生名取小写（B4 第一断言）。
  expect(put.providers.Alpha, 'provider row = {type, model, keyRef}').toEqual({ type: 'cogview', model: 'cogview-3-flash', keyRef: 'imagegen-alpha-key' });
  expect(put.defaultProvider, 'defaultProvider = the new provider').toBe('Alpha');
  expect(JSON.stringify(put), '投影字段（keyConfigured）不回写').not.toContain('keyConfigured');
});

test('B5. 既有 provider 重录：POST 409 → PUT 覆写 → 已配置态 + 表单关闭', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  // 真重录语义：既有 provider 已配置（占位词 = 覆写提示），派生密钥名已在
  // secrets 库（POST 409 ⇒ PUT 覆写腿）。
  const st = { status: { compiledIn: true, enabled: true, defaultProvider: 'zhipu', providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true } } }, existingSecrets: new Set(['imagegen-zhipu-key']) };
  await bootPage(page, st, ledger);
  await page.evaluate(() => document.querySelector('#provider-list .cfg-card .cfg-card-title')?.click());
  await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 5000 });
  const placeholder = await page.evaluate(() => document.querySelector('#cfg-modal [data-field="apiKey"]')?.placeholder || '');
  expect(placeholder, 'configured provider shows the re-entry placeholder (值不回显)').toContain('覆盖');
  await page.fill('#cfg-modal [data-field="apiKey"]', 'sk-rotated-value');
  await page.evaluate(() => document.querySelector('#cfg-modal .cfg-btn-save')?.click());
  await page.waitForTimeout(400);
  expect(ledger.secrets.map(s => s.method), 'POST then PUT overwrite (409 leg)').toEqual(['POST', 'PUT']);
  expect(ledger.secrets[0].name, 'same derived name').toBe('imagegen-zhipu-key');
  expect(st.status.providers.zhipu.keyConfigured, 're-read projects configured=true').toBe(true);
  await page.waitForFunction(() => !document.getElementById('cfg-modal'), { timeout: 5000 });
  const badge = await page.evaluate(() => document.querySelector('#provider-list .cfg-card .st-badge')?.textContent.trim());
  expect(badge, 'key badge flips to the configured face').toBe('已保存');
});

test('B6. 配置保存：整节替换 PUT——全 provider 保留 + 投影字段不回写', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: true, enabled: true, defaultProvider: 'zhipu', providers: {
    zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true },
    ark: { type: 'openai-images', model: 'seedream-3', keyConfigured: true },
  } } };
  await bootPage(page, st, ledger);
  await page.evaluate(() => {
    const card = [...document.querySelectorAll('#provider-list .cfg-card')].find(c => c.querySelector('.cfg-card-title')?.textContent.trim() === 'ark');
    card?.querySelector('.cfg-card-title')?.click();
  });
  await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 5000 });
  await page.fill('#cfg-modal [data-field="model"]', 'seedream-3-0-t2i');
  await page.evaluate(() => document.querySelector('#cfg-modal .cfg-btn-save')?.click());
  await page.waitForTimeout(400);
  expect(ledger.sectionPuts, 'one section PUT').toHaveLength(1);
  const put = ledger.sectionPuts[0];
  expect(Object.keys(put.providers).sort(), '🔴 both providers survive the section replace').toEqual(['ark', 'zhipu']);
  expect(put.providers.zhipu.model, 'untouched provider keeps its model').toBe('cogview-3-flash');
  expect(put.providers.ark.model, 'edited provider carries the new model').toBe('seedream-3-0-t2i');
  expect(JSON.stringify(put), 'projection fields never round-trip').not.toContain('keyConfigured');
});

test('B7. 删除提供方：统一确认框 → PUT 少该项', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: true, enabled: true, defaultProvider: 'zhipu', providers: {
    zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true },
    ark: { type: 'openai-images', model: 'seedream-3', keyConfigured: true },
  } } };
  await bootPage(page, st, ledger);
  await page.evaluate(() => {
    const card = [...document.querySelectorAll('#provider-list .cfg-card')].find(c => c.querySelector('.cfg-card-title')?.textContent.trim() === 'zhipu');
    card?.querySelector('.cfg-card-remove')?.click();
  });
  await page.waitForTimeout(300);
  // 统一确认框 = 共享 #delete-box（modal.js showConfirm 面）→ 确认腿。
  await expect(page.locator('#delete-box')).toBeVisible();
  await page.click('#delete-confirm');
  await page.waitForTimeout(500);
  expect(ledger.sectionPuts, 'delete lands as a section PUT').toHaveLength(1);
  const put = ledger.sectionPuts[0];
  expect(put.providers.zhipu, 'deleted provider is gone').toBeUndefined();
  expect(put.providers.ark, 'remaining provider stays').toBeTruthy();
});

test('B8. 「调试中」徽标零渲染 + 旧 imagegenSection 零复活（渲染面）', async ({ page }) => {
  const ledger = { secrets: [], sectionPuts: [] };
  const st = { status: { compiledIn: true, enabled: true, defaultProvider: 'zhipu', providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true } } } };
  await bootPage(page, st, ledger);
  const dump = await page.evaluate(() => ({
    badges: document.querySelectorAll('.imagegen-badge').length,
    oldWrap: !!document.getElementById('imagegen-section-wrap'),
    oldAdd: !!document.getElementById('btn-add-imagegen-provider'),
    oldList: !!document.getElementById('imagegen-provider-list'),
  }));
  expect(dump.badges, 'zero debug badges').toBe(0);
  expect(dump.oldWrap, 'old section wrap is gone').toBe(false);
  expect(dump.oldAdd, 'old add button is gone').toBe(false);
  expect(dump.oldList, 'old list is gone').toBe(false);
});
