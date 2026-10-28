// provider-model-test-button.spec.mjs — 每模型连接性测试钮三态钉
// （settings-panel-jev-imagegen 批 B 件，2026-10-28 作者令逐字：「在 provider
// 每一个模型旁边，加一个小按钮，用于连接性测试，测试模型是否可用」）。
//
// Drives the REAL module (js/sidebar/modelTestButton.js) over a fixture page
// served from the source tree (🔴 绝非 8080 宿主)；POST /api/provider/test 经
// playwright route 拦截（成功/失败/慢响应三剧本），零真实引擎依赖。
//
// 语义钉：
//   T1 空闲态：每模型行尾一枚测试钮（data-provider/data-model 路由身份，
//      零凭据材料）
//   T2 测试中：spinner + 钮禁用——测试中禁重复点击（快速双击只发一次请求）
//   T3 可用：绿 ✓ + 延迟读数（st-ok 语义类 + ms 数）
//   T4 不可用：红 ✗ + 错误摘要进结果位（title 全文）——错误文案 = 服务侧
//      脱敏后的可诊断文本
//   T5 请求形状：POST {name, model}——凭据由服务端从存储读，请求体零 key
//
// Run: npx playwright test tests/provider-model-test-button.spec.mjs

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
const HARNESS = '/__harness-model-test-button.html';
const SHOT_DIR = process.env.NB_MODELTEST_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261028_settings-panel-jev-imagegen', 'model-test-button');

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
  fs.copyFileSync(
    path.join(REPO_ROOT, 'tests', 'fixtures', 'model-test-button', 'harness.html'),
    path.join(WEB_ROOT, HARNESS),
  );
  startStaticServer(port, WEB_ROOT);
  await waitUntilUp(`http://127.0.0.1:${port}/js/sidebar/modelTestButton.js`);
  fs.mkdirSync(SHOT_DIR, { recursive: true });
});

test.afterAll(() => {
  for (const s of servers) s.kill();
  fs.rmSync(path.join(WEB_ROOT, HARNESS), { force: true });
});

async function openHarness(page) {
  await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
  await page.waitForFunction(() => document.body.dataset.ready === '1');
}

test('T1+T3. 空闲三枚钮 → 可用剧本：绿 ✓ + 延迟读数', async ({ page }) => {
  /** @type {{name:string, model:string}[]} */
  const calls = [];
  await page.route('**/api/provider/test', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    calls.push(body);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 123, reply: 'pong' }) });
  });
  await openHarness(page);

  const btns = page.locator('.cfg-model-test');
  await expect(btns).toHaveCount(3); // cmdcode×2 + zhipu×1
  // 路由身份在 data-*（T1，零凭据材料）。
  await expect(btns.nth(0)).toHaveAttribute('data-test-provider', 'cmdcode');
  await expect(btns.nth(0)).toHaveAttribute('data-test-model', 'deepseek/deepseek-v4.1-flash');

  await btns.nth(0).click();
  const slot0 = page.locator('.cfg-model-test-result').nth(0);
  await expect(slot0.locator('.st-ok')).toBeVisible();
  await expect(slot0).toContainText('123ms');
  // 请求形状（T5）：POST {name, model}，请求体零 key 材料。
  expect(calls).toEqual([{ name: 'cmdcode', model: 'deepseek/deepseek-v4.1-flash' }]);
  await page.screenshot({ path: path.join(SHOT_DIR, 'T3-test-ok.png'), fullPage: true });
});

test('T2. 测试中禁重复点击：慢响应期间连点两次 → 只发一次请求', async ({ page }) => {
  let hits = 0;
  await page.route('**/api/provider/test', async (route) => {
    hits += 1;
    await new Promise((r) => setTimeout(r, 400));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 400 }) });
  });
  await openHarness(page);
  const btn = page.locator('.cfg-model-test').nth(0);
  await btn.click();
  await expect(btn).toBeDisabled(); // spinner + 禁用（T2）
  await expect(btn.locator('.cfg-model-test-spinner')).toBeVisible();
  await btn.click({ force: true }); // 双击意图（disabled 按钮不吃点击）
  await expect(btn).toBeEnabled({ timeout: 3000 });
  expect(hits).toBe(1); // 测试中禁重复点击
});

test('T4. 不可用剧本：红 ✗ + 错误摘要（title 全文 = 服务侧脱敏文本）', async ({ page }) => {
  const sanitized = 'HTTP 400: Model "deepseek/deepseek-v4.1-flash" is not supported on this endpoint';
  await page.route('**/api/provider/test', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, error: sanitized }) });
  });
  await openHarness(page);
  const btn = page.locator('.cfg-model-test').nth(1); // cmdcode/claude-x 行
  await btn.click();
  const slot = page.locator('.cfg-model-test-result').nth(1);
  await expect(slot.locator('.st-err')).toBeVisible();
  // 摘要面：错误首行截短 60 字符进结果位（可诊断显示位）——断言钉在截断边界。
  const summary = sanitized.split('\n')[0].slice(0, 60);
  await expect(slot).toContainText(summary);
  // title 全文。
  await expect(slot).toHaveAttribute('title', sanitized);
  await page.screenshot({ path: path.join(SHOT_DIR, 'T4-test-fail.png'), fullPage: true });
});
