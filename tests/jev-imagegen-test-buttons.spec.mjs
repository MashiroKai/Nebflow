// jev-imagegen-test-buttons.spec.mjs — JeV 分配区 + 生图设置节「测试可用性」钮钉
// （settings-panel-jev-imagegen 批 2026-10-29 作者令逐字：「然后 Jev/生图，也要
// 像我们LLM这样，有个按钮来测试可用性」）。
//
// Drives the REAL production modules（js/sidebar/jevTestButton.js +
// js/sidebar/imagegenSection.js）over fixture pages served from the source tree
// （🔴 绝非 8080 宿主）；POST /api/jev/test、POST /api/imagegen/test、GET
// /api/config/imagegen 经 playwright route 拦截（成功/失败/慢响应剧本），零真实
// 引擎依赖。回显与 LLM 测试钮同组件同口径（同 cfg-test-results 族）。
//
// 语义钉：
//   J1 归位：测试钮在 JeV 表单保存行；结果块在位；生产接线（sidebar.js 读
//      #cfg-jev-* 现值 + bindJevTestButton）与引擎路由（POST /jev/test）源级钉
//   J2 测试中：钮禁用 + 结果行 spinner「测试中」；连点只发一次请求（单飞）；
//      结果保留可重测（判决行不被清）
//   J3 可用：绿 ✓ + 延迟 ms
//   J4 不可用：红 ✗ + 状态码原样可见（HTTP 401 死 key 剧本）+ 全量错误文本
//   J5 请求形状（表单覆写腿）：{provider, endpoint, keyRef} = 表单当前值
//      （未保存也测）；空字段不进 body
//   G1 生图归位：轻探活钮 + 实跑生成钮 + 结果块三件在位；引擎路由源级钉
//   G2 轻探活可用：✓ + ms；请求形状 = {provider, type, model}（key 空 = 不覆写）
//   G3 轻探活死 key：✗ + 「HTTP 401」原样可见
//   G4 实跑一次最小生成：请求 mode=generate；✓ + 耗时·字节数·格式 + 缩略图
//      （data URL <img class=cfg-test-thumb>）；tempPath 只进临时目录
//   G5 实跑失败：✗ + 错误文本（状态码原样）
//   G6 双钮联动单飞：一钮测试中双钮全禁；连点只发一次请求
//   G7 key 覆写腿：key 输入框现值进 body.apiKey（未保存也测）
//   S1/S2 亮暗双档截图（两处落点各一对）
//
// Run: npx playwright test tests/jev-imagegen-test-buttons.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const WEB_ROOT = path.join(REPO_ROOT, 'src', 'main', 'resources', 'web');
// harness 用根绝对路径（/css /js）引用生产资源 ⇒ 服务根必须是 web/；
// harness 本体在 tests/fixtures/ 下 ⇒ 运行期拷贝进 web/ 根（afterAll 删除，
// 零入库）。
const JEVMOD_TEST = '/js/sidebar/jevTestButton.js';
const HARNESS_JEV = '/__harness-jev-test.html';
const HARNESS_IG = '/__harness-jev-imagegen-test.html';
const SHOT_DIR = process.env.NB_JEVIG_TEST_SHOT_DIR ||
  path.join(REPO_ROOT, 'evidence/20261029_193000_jev-imagegen-test-buttons__delegate-nebflow-48dfd701');

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  const srv = spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
  servers.push(srv);
  return srv;
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  port = await freePort();
  fs.copyFileSync(path.join(REPO_ROOT, 'tests', 'fixtures', 'jev-imagegen-test', 'harness-jev.html'), path.join(WEB_ROOT, HARNESS_JEV));
  fs.copyFileSync(path.join(REPO_ROOT, 'tests', 'fixtures', 'settings-imagegen', 'harness.html'), path.join(WEB_ROOT, HARNESS_IG));
  startStaticServer(port, WEB_ROOT);
  await waitUntilUp(`http://127.0.0.1:${port}${JEVMOD_TEST}`);
  fs.mkdirSync(SHOT_DIR, { recursive: true });
});

test.afterAll(() => {
  for (const s of servers) s.kill();
  fs.rmSync(path.join(WEB_ROOT, HARNESS_JEV), { force: true });
  fs.rmSync(path.join(WEB_ROOT, HARNESS_IG), { force: true });
});

async function openJev(page) {
  await page.goto(`http://127.0.0.1:${port}${HARNESS_JEV}`);
  await page.waitForFunction(() => document.body.dataset.ready === '1');
}

/** The imagegen harness fetches its status projection before rendering. */
const statusWithKey = {
  compiledIn: true, debugLabel: '调试中', configured: true, enabled: true, defaultProvider: 'zhipu',
  providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', baseUrl: null, keyRef: 'zhipu-api-key', keyConfigured: true } },
};

async function openImagegen(page, status = statusWithKey) {
  await page.route('**/api/config/imagegen', async (route) => {
    const req = route.request();
    if (req.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(status) });
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
  });
  await page.goto(`http://127.0.0.1:${port}${HARNESS_IG}`);
  await page.waitForFunction(() => document.body.dataset.ready === '1');
}

// ── JeV 分配区 ──────────────────────────────────────────────

test('J1. 归位：测试钮在 JeV 保存行 + 结果块在位；生产接线与引擎路由源级钉', async ({ page }) => {
  await page.route('**/api/jev/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latencyMs":42}' }));
  await openJev(page);
  const btn = page.locator('#btn-test-jev');
  await expect(btn).toBeVisible();
  await expect(btn).toContainText('测试可用性');
  const block = page.locator('.cfg-test-results-jev');
  await expect(block).toBeAttached();
  // 源级钉①：生产接线在 sidebar.js —— bindJevTestButton 读 #cfg-jev-* 现值。
  const sidebarSrc = fs.readFileSync(path.join(WEB_ROOT, 'js', 'sidebar.js'), 'utf8');
  expect(sidebarSrc).toContain('bindJevTestButton');
  expect(sidebarSrc).toContain("document.getElementById('cfg-jev-provider')");
  expect(sidebarSrc).toContain("document.getElementById('cfg-jev-endpoint')");
  expect(sidebarSrc).toContain("document.getElementById('cfg-jev-keyref')");
  // 源级钉②：引擎路由在位（PresenceRoutes POST /jev/test → JevProbe）。
  const routesSrc = fs.readFileSync(path.join(REPO_ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway', 'PresenceRoutes.scala'), 'utf8');
  expect(routesSrc).toContain('Root / "jev" / "test"');
  expect(routesSrc).toContain('Root / "imagegen" / "test"');
  await page.screenshot({ path: path.join(SHOT_DIR, 'J1-jev-button.png'), fullPage: true });
});

test('J2. 测试中禁用+单飞：慢响应期间连点两次 → 只发一次请求；结果保留可重测', async ({ page }) => {
  let hits = 0;
  await page.route('**/api/jev/test', async (route) => {
    hits += 1;
    await new Promise((r) => setTimeout(r, 400));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 400 }) });
  });
  await openJev(page);
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
  await openJev(page);
  await page.locator('#btn-test-jev').click();
  const line = page.locator('.cfg-test-results-jev .cfg-test-result-line');
  await expect(line.locator('.st-ok')).toBeVisible();
  await expect(line).toContainText('602ms');
});

test('J4. 不可用剧本：红 ✗ + 状态码原样可见 + 全量错误文本', async ({ page }) => {
  const fullError = 'HTTP 401: Invalid Authorization token (request id: req-20261029220100-ab12cd34)';
  await page.route('**/api/jev/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, error: fullError }) }));
  await openJev(page);
  await page.locator('#btn-test-jev').click();
  const line = page.locator('.cfg-test-results-jev .cfg-test-result-line');
  await expect(line.locator('.st-err')).toBeVisible();
  await expect(line).toContainText('HTTP 401'); // 状态码原样可见（cmdcode 死 key 口径）
  await expect(line).toContainText(fullError); // 全量不截断
  await page.screenshot({ path: path.join(SHOT_DIR, 'J4-jev-fail-401.png'), fullPage: true });
});

test('J5. 请求形状（表单覆写腿）：{provider, endpoint, keyRef} = 表单当前值；空字段不进 body', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/jev/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 50 }) });
  });
  await openJev(page);
  // 未保存也测：切到 laya（端点可编辑面）改端点/密钥引用后直接测（不走保存）。
  await page.selectOption('#cfg-jev-provider', 'laya');
  await page.fill('#cfg-jev-endpoint', 'http://127.0.0.1:9999/predict');
  await page.fill('#cfg-jev-keyref', 'my-fresh-ref');
  await page.locator('#btn-test-jev').click();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ provider: 'laya', endpoint: 'http://127.0.0.1:9999/predict', keyRef: 'my-fresh-ref' });
  // 清空 keyRef 再测：空字段不进 body（引擎回落存储配置腿）。
  await page.fill('#cfg-jev-keyref', '');
  await page.locator('#btn-test-jev').click();
  await expect.poll(() => calls.length).toBe(2);
  expect(calls[1]).toEqual({ provider: 'laya', endpoint: 'http://127.0.0.1:9999/predict' });
});

// ── 生图设置节 ──────────────────────────────────────────────

test('G1. 归位：轻探活钮 + 实跑生成钮 + 结果块三件在位；引擎路由源级钉', async ({ page }) => {
  await page.route('**/api/imagegen/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"mode":"probe","latencyMs":42}' }));
  await openImagegen(page);
  await expect(page.locator('#btn-test-imagegen')).toBeVisible();
  await expect(page.locator('#btn-imagegen-generate')).toBeVisible();
  await expect(page.locator('.imagegen-test-results')).toBeAttached();
  const probeSrc = fs.readFileSync(path.join(REPO_ROOT, 'src', 'main', 'scala', 'nebflow', 'llm', 'imagegen', 'ImageGenProbe.scala'), 'utf8');
  expect(probeSrc).toContain('os.temp.dir'); // 产物只落临时目录（源级钉）
  await page.screenshot({ path: path.join(SHOT_DIR, 'G1-imagegen-buttons.png'), fullPage: true });
});

test('G2. 轻探活可用：✓ + ms；请求形状 = {provider, type, model}（key 空 = 不覆写）', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/imagegen/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, mode: 'probe', latencyMs: 88 }) });
  });
  await openImagegen(page);
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
  await openImagegen(page);
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
  await openImagegen(page);
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
  await page.screenshot({ path: path.join(SHOT_DIR, 'G4-imagegen-generate-ok.png'), fullPage: true });
});

test('G5. 实跑失败：✗ + 错误文本（状态码原样）', async ({ page }) => {
  await page.route('**/api/imagegen/test', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ ok: false, error: 'HTTP 429: rate limited, retry after 30s' }),
  }));
  await openImagegen(page);
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
  await openImagegen(page);
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

test('G7. key 覆写腿：key 输入框现值进 body.apiKey（未保存也测）', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/imagegen/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, error: 'HTTP 401: dead key' }) });
  });
  await openImagegen(page);
  await page.fill('#cfg-imagegen-key', 'sk-freshly-typed-dead-key');
  await page.locator('#btn-test-imagegen').click();
  const line = page.locator('.imagegen-test-results .cfg-test-result-line');
  await expect(line.locator('.st-err')).toBeVisible();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ provider: 'zhipu', type: 'cogview', model: 'cogview-3-flash', apiKey: 'sk-freshly-typed-dead-key' });
  // 🔴 凭据纪律：key 值只进请求体，永不进结果行文本。
  await expect(line).not.toContainText('sk-freshly-typed-dead-key');
});

// ── 亮暗双档截图（两处落点各一对）────────────────────────────

test('S1. 亮暗双档：JeV 面可用态截图', async ({ page }) => {
  test.setTimeout(20_000);
  await page.route('**/api/jev/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 512 }) }));
  await openJev(page);
  await page.locator('#btn-test-jev').click();
  await expect(page.locator('.cfg-test-results-jev .cfg-test-result-line .st-ok')).toBeVisible();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.screenshot({ path: path.join(SHOT_DIR, 'S1-jev-light.png'), fullPage: true });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.screenshot({ path: path.join(SHOT_DIR, 'S1-jev-dark.png'), fullPage: true });
});

test('S2. 亮暗双档：生图面实跑生成态截图', async ({ page }) => {
  test.setTimeout(20_000);
  const thumbPng = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAIAAADYYG7QAAAAQklEQVR4nO3OQQ0AIAwAsQmbfxWIwQXHo0kFdM7uVyYfCAkJCdUDISEhoXogJCQkVA+EhISE6oGQkJBQPRASEnrsAiFk9KYAFWdWAAAAAElFTkSuQmCC'; // 48x48 solid sample — 肉眼可见的证据缩略图
  await page.route('**/api/imagegen/test', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ ok: true, mode: 'generate', latencyMs: 2870, bytes: 48128, format: 'jpeg', tempPath: '/tmp/nebflow-imagegen-test-x/probe-48128.jpeg', thumbnail: thumbPng }),
  }));
  await openImagegen(page);
  await page.locator('#btn-imagegen-generate').click();
  await expect(page.locator('.imagegen-test-results img.cfg-test-thumb')).toBeVisible();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.screenshot({ path: path.join(SHOT_DIR, 'S2-imagegen-light.png'), fullPage: true });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.screenshot({ path: path.join(SHOT_DIR, 'S2-imagegen-dark.png'), fullPage: true });
});
