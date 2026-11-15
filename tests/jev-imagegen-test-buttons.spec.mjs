// jev-imagegen-test-buttons.spec.mjs — JeV 分配 + 生图「测试可用性」钮钉
// （settings-panel-jev-imagegen 批 2026-10-29 作者令逐字：「然后 Jev/生图，也要
// 像我们LLM这样，有个按钮来测试可用性」）。
//
// 🔴 provider-add-tabs 批（2026-11-06 作者令「添加服务商统一成标签切换类型 +
// 单一服务商面板」）判据换代：两钮从旧内联表单迁入**统一服务商面板的统一表单
// 骨架**（js/sidebar/jevTestButton.js 原样保留；js/sidebar/imagegenSection.js
// 的钮腿迁 js/sidebar/imagegenTestButton.js 改读表单骨架 data-field）。本文件
// 改为驱动真壳（静态服 + mock，同 settings-imagegen-two-forms.spec 口径），
// 语义钉承接：
//   J1 归位：测试钮在统一 JeV 表单动作行 + 结果块在位；生产接线（providerPanel
//      bindExtra 读 data-field 现值 + bindJevTestButton）与引擎路由源级钉
//   J2 测试中禁用 + 单飞 + 结果保留可重测
//   J3 可用：绿 ✓ + 延迟 ms
//   J4 不可用：红 ✗ + 状态码原样可见 + 全量错误文本
//   J5 请求形状（表单覆写腿）：{provider, endpoint, keyRef} = 表单当前值
//      （未保存也测；keyRef = 派生名 jev-<name>-key，对用户不可见）
//   G1 生图归位：轻探活钮 + 实跑生成钮 + 结果块三件在位；产物只落临时目录（源级钉）
//   G2 轻探活可用：✓ + ms；请求形状 = {provider, type, model}（key 空 = 不覆写）
//   G3 轻探活死 key：✗ + 「HTTP 401」原样可见
//   G4 实跑一次最小生成：mode=generate；✓ + 耗时·字节数·格式 + 缩略图（data URL）
//   G5 实跑失败：✗ + 错误文本（状态码原样）
//   G6 双钮联动单飞
//   G7 key 覆写腿：key 输入框现值进 body.apiKey（未保存也测；值永不进结果行）
//
// SELF-CONTAINED：静态服务器 127.0.0.1:8249 + route mock API + routeWebSocket
// mock WS，零真实后端。
// Run: npx playwright test tests/jev-imagegen-test-buttons.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..');
const WEB = resolve(REPO_ROOT, 'src', 'main', 'resources', 'web');
const PORT = 8249; // fixed, 8100+ per task discipline — never the host 8080
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
  adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview'], jev: ['typesafe-jev', 'laya'] },
};

/**
 * Boot the real shell, open settings, and open the unified form for the given
 * type tab ('imagegen' | 'jev'). The form opens EMPTY (新增面) unless `name`
 * names an existing provider row.
 * @param {import('@playwright/test').Page} page
 * @param {{type: 'imagegen'|'jev', name?: string, imagegenStatus?: any, jevStatus?: any}} opts
 */
async function openForm(page, { type, name = '', imagegenStatus, jevStatus }) {
  await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    // No service worker: WebKit lets it fail every same-origin fetch when no real engine sits behind the server.
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/api/config/provider-types', r => r.fulfill({ json: TYPES }));
  await page.route('**/api/config/imagegen', r => r.fulfill({ json: imagegenStatus || { compiledIn: true, enabled: true, providers: {} } }));
  await page.route('**/api/config/jev', r => r.fulfill({ json: jevStatus || { enabled: true, providers: {} } }));
  await page.route('**/api/config/provider-keys', r => r.fulfill({ json: { providers: {} } }));
  await page.route('**/api/secrets', r => r.fulfill({ json: { secrets: [] } }));
  await page.route('**/api/secrets/**', r => r.fulfill({ status: 404, json: {} }));
  await page.route('**/api/decision/health', r => r.fulfill({ json: { decision: { status: 'unconfigured' } } }));
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
  await page.waitForTimeout(400);
  await page.evaluate((t) => document.querySelector(`.nf-ptab[data-provider-type="${t}"]`)?.click(), type);
  await page.waitForSelector(`.nf-provider-panel[data-type="${type}"]`, { timeout: 5000 });
  if (name) {
    await page.evaluate((n) => {
      const card = [...document.querySelectorAll('#provider-list .cfg-card')]
        .find(c => c.querySelector('.cfg-card-title')?.textContent.trim() === n);
      card?.querySelector('.cfg-card-title')?.click();
    }, name);
  } else {
    await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
  }
  await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 5000 });
}

// ── JeV 统一表单 ────────────────────────────────────────────

test('J1. 归位：测试钮在统一 JeV 表单动作行 + 结果块在位；生产接线与引擎路由源级钉', async ({ page }) => {
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await page.route('**/api/jev/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latencyMs":42}' }));
  await openForm(page, { type: 'jev', name: 'typesafe-jev', jevStatus: { enabled: true, providers: { 'typesafe-jev': { type: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', model: 'jev', keyConfigured: true } } } });
  const btn = page.locator('#btn-test-jev');
  await expect(btn).toBeVisible();
  await expect(btn).toContainText('测试可用性');
  await expect(page.locator('.cfg-test-results-jev')).toBeAttached();
  // 源级钉①：生产接线在 providerPanel.js —— bindExtra 读 data-field 现值。
  const panelSrc = readFileSync(resolve(WEB, 'js', 'sidebar', 'providerPanel.js'), 'utf8');
  expect(panelSrc).toContain('bindJevTestButton');
  expect(panelSrc).toContain('fieldValue(overlay, \'endpoint\')');
  expect(panelSrc).toContain("keyRefFor('jev'");
  // 源级钉②：引擎路由在位（PresenceRoutes POST /jev/test → JevProbe）。
  const routesSrc = readFileSync(resolve(REPO_ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway', 'PresenceRoutes.scala'), 'utf8');
  expect(routesSrc).toContain('Root / "jev" / "test"');
  expect(routesSrc).toContain('Root / "imagegen" / "test"');
});

test('J2. 测试中禁用+单飞：慢响应期间连点两次 → 只发一次请求；结果保留可重测', async ({ page }) => {
  let hits = 0;
  await page.route('**/api/jev/test', async (route) => {
    hits += 1;
    await new Promise((r) => setTimeout(r, 400));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 400 }) });
  });
  await openForm(page, { type: 'jev', name: 'typesafe-jev', jevStatus: { enabled: true, providers: { 'typesafe-jev': { type: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', model: 'jev', keyConfigured: true } } } });
  const btn = page.locator('#btn-test-jev');
  await btn.click();
  await expect(btn).toBeDisabled(); // 测试中禁用
  const line = page.locator('.cfg-test-results-jev .cfg-test-result-line');
  await expect(line).toHaveClass(/testing/);
  await expect(line).toContainText('测试中');
  await btn.click({ force: true }); // 双击意图（disabled 按钮不吃点击）
  await expect(btn).toBeEnabled({ timeout: 3000 });
  expect(hits).toBe(1); // 单飞
  // 结果保留：判决行还在（绿 ✓ + 延迟），可重测（钮已回到可用态）。
  await expect(line).toHaveClass(/ok/);
  await expect(line).toContainText('400ms');
});

test('J3. 可用剧本：绿 ✓ + 延迟读数', async ({ page }) => {
  await page.route('**/api/jev/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 602 }) }));
  await openForm(page, { type: 'jev', name: 'typesafe-jev', jevStatus: { enabled: true, providers: { 'typesafe-jev': { type: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', model: 'jev', keyConfigured: true } } } });
  await page.locator('#btn-test-jev').click();
  const line = page.locator('.cfg-test-results-jev .cfg-test-result-line');
  await expect(line.locator('.st-ok')).toBeVisible();
  await expect(line).toContainText('602ms');
});

test('J4. 不可用剧本：红 ✗ + 状态码原样可见 + 全量错误文本', async ({ page }) => {
  const fullError = 'HTTP 401: Invalid Authorization token (request id: req-20261029220100-ab12cd34)';
  await page.route('**/api/jev/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, error: fullError }) }));
  await openForm(page, { type: 'jev', name: 'typesafe-jev', jevStatus: { enabled: true, providers: { 'typesafe-jev': { type: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', model: 'jev', keyConfigured: true } } } });
  await page.locator('#btn-test-jev').click();
  const line = page.locator('.cfg-test-results-jev .cfg-test-result-line');
  await expect(line.locator('.st-err')).toBeVisible();
  await expect(line).toContainText('HTTP 401'); // 状态码原样可见（cmdcode 死 key 口径）
  await expect(line).toContainText(fullError); // 全量不截断
});

test('J5. 请求形状（表单覆写腿）：{provider, endpoint, keyRef} = 表单当前值（未保存也测）', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/jev/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 50 }) });
  });
  await openForm(page, { type: 'jev', jevStatus: { enabled: true, providers: { 'typesafe-jev': { type: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', model: 'jev', keyConfigured: true } } } });
  // 未保存也测：新增面填名（laya 型）+ 手填端点后直接测（不走保存）。
  await page.fill('#cfg-modal [data-field="name"]', 'local-laya');
  await page.selectOption('#cfg-modal [data-field="type"]', 'laya');
  await page.fill('#cfg-modal [data-field="endpoint"]', 'http://127.0.0.1:9999/predict');
  await page.fill('#cfg-modal [data-field="model"]', 'current-model');
  await page.fill('#cfg-modal [data-field="apiKey"]', 'fixture-key');
  await page.locator('#btn-test-jev').click();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({
    provider: 'laya',
    endpoint: 'http://127.0.0.1:9999/predict',
    model: 'current-model',
    apiKey: 'fixture-key',
    keyRef: 'jev-local-laya-key', // 派生名（对用户不可见；无既有 keyRef 时取派生）
  });
});

// ── 生图统一表单 ────────────────────────────────────────────

const IG_STATUS = {
  compiledIn: true, enabled: true, defaultProvider: 'zhipu',
  providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true } },
};

test('G1. 归位：轻探活钮 + 实跑生成钮 + 结果块三件在位；产物只落临时目录（源级钉）', async ({ page }) => {
  await page.route('**/api/imagegen/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"mode":"probe","latencyMs":42}' }));
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await expect(page.locator('#btn-test-imagegen')).toBeVisible();
  await expect(page.locator('#btn-imagegen-generate')).toBeVisible();
  await expect(page.locator('.imagegen-test-results')).toBeAttached();
  const probeSrc = readFileSync(resolve(REPO_ROOT, 'src', 'main', 'scala', 'nebflow', 'llm', 'imagegen', 'ImageGenProbe.scala'), 'utf8');
  // Artifacts land only in the system temp area through EvidenceTemp, never in the formal imagegen archive
  // (the earlier os.temp.dir pin predates the EvidenceTemp batches; the intent is unchanged).
  expect(probeSrc).toContain('EvidenceTemp.createBatch("imagegen-probe"');
  expect(probeSrc).not.toMatch(/dataRoot\s*\/\s*"imagegen"/);
});

test('G2. 轻探活可用：✓ + ms；请求形状 = {provider, type, model}（key 空 = 不覆写）', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/imagegen/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, mode: 'probe', latencyMs: 88 }) });
  });
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await page.locator('#btn-test-imagegen').click();
  const line = page.locator('.imagegen-test-results .cfg-test-result-line');
  await expect(line.locator('.st-ok')).toBeVisible();
  await expect(line).toContainText('88ms');
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ provider: 'zhipu', type: 'cogview', model: 'cogview-3-flash' });
});

test('G3. 轻探活死 key：✗ + 「HTTP 401」状态码原样可见', async ({ page }) => {
  await page.route('**/api/imagegen/test', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ ok: false, error: 'HTTP 401: Invalid API key (1002)' }),
  }));
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await page.locator('#btn-test-imagegen').click();
  const line = page.locator('.imagegen-test-results .cfg-test-result-line');
  await expect(line.locator('.st-err')).toBeVisible();
  await expect(line).toContainText('HTTP 401');
});

test('G4. 实跑一次最小生成：mode=generate；✓+耗时·字节数·格式+缩略图（data URL）', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  const thumbPng = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAIAAADYYG7QAAAAQklEQVR4nO3OQQ0AIAwAsQmbfxWIwQXHo0kFdM7uVyYfCAkJCdUDISEhoXogJCQkVA+EhISE6oGQkJBQPRASEnrsAiFk9KYAFWdWAAAAAElFTkSuQmCC'; // 48x48 solid sample — 肉眼可见的证据缩略图
  await page.route('**/api/imagegen/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ ok: true, mode: 'generate', latencyMs: 3210, bytes: 51200, format: 'jpeg', tempPath: '/tmp/nebflow-imagegen-test-x/probe-51200.jpeg', thumbnail: thumbPng }),
    });
  });
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await page.locator('#btn-imagegen-generate').click();
  const line = page.locator('.imagegen-test-results .cfg-test-result-line');
  await expect(line.locator('.st-ok')).toBeVisible();
  await expect(line).toContainText('3210ms');
  await expect(line).toContainText('51200 bytes');
  await expect(line).toContainText('jpeg');
  const thumb = line.locator('img.cfg-test-thumb');
  await expect(thumb).toBeVisible();
  await expect(thumb).toHaveAttribute('src', thumbPng);
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0].mode).toBe('generate'); // 两级 b 腿的真打标记
});

test('G5. 实跑失败：✗ + 错误文本（状态码原样）', async ({ page }) => {
  await page.route('**/api/imagegen/test', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ ok: false, error: 'HTTP 429: rate limited, retry after 30s' }),
  }));
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await page.locator('#btn-imagegen-generate').click();
  const line = page.locator('.imagegen-test-results .cfg-test-result-line');
  await expect(line.locator('.st-err')).toBeVisible();
  await expect(line).toContainText('HTTP 429');
});

test('G6. 双钮联动单飞：一钮测试中双钮全禁；连点只发一次请求', async ({ page }) => {
  let hits = 0;
  await page.route('**/api/imagegen/test', async (route) => {
    hits += 1;
    await new Promise((r) => setTimeout(r, 400));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, mode: 'probe', latencyMs: 400 }) });
  });
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  const probeBtn = page.locator('#btn-test-imagegen');
  const genBtn = page.locator('#btn-imagegen-generate');
  await probeBtn.click();
  await expect(probeBtn).toBeDisabled();
  await expect(genBtn).toBeDisabled(); // 联动禁用
  await genBtn.click({ force: true });
  await expect(probeBtn).toBeEnabled({ timeout: 3000 });
  expect(hits).toBe(1);
  // 结果保留可重测：钮回可用态，判决行仍在。
  await expect(page.locator('.imagegen-test-results .cfg-test-result-line')).toHaveClass(/ok/);
});

test('G7. key 覆写腿：key 输入框现值进 body.apiKey（未保存也测；值永不进结果行）', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/imagegen/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, error: 'HTTP 401: dead key' }) });
  });
  await openForm(page, { type: 'imagegen', name: 'zhipu', imagegenStatus: IG_STATUS });
  await page.fill('#cfg-modal [data-field="apiKey"]', 'sk-freshly-typed-dead-key');
  await page.locator('#btn-test-imagegen').click();
  const line = page.locator('.imagegen-test-results .cfg-test-result-line');
  await expect(line.locator('.st-err')).toBeVisible();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ provider: 'zhipu', type: 'cogview', model: 'cogview-3-flash', apiKey: 'sk-freshly-typed-dead-key' });
  // 🔴 凭据纪律：key 值只进请求体，永不进结果行文本。
  await expect(line).not.toContainText('sk-freshly-typed-dead-key');
});
