// provider-model-test-button.spec.mjs — provider 连接性测试钮钉
// （settings-panel-jev-imagegen 批 B 件 2026-10-28 作者令逐字：「在 provider
// 每一个模型旁边，加一个小按钮，用于连接性测试，测试模型是否可用」；
// settings-panel-fourfix 批 ②件 2026-10-29 作者令逐字：「连接性测试按钮应放到
// LLM provider 配置页面里」「保证测试通知能看得到……改成醒目不截断」）。
//
// Drives the REAL provider edit modal (js/sidebar/providersUi.js
// showProviderModal → modelTestButton.js 测试钮 + 弹窗级结果块) over a fixture
// page served from the source tree (🔴 绝非 8080 宿主)；POST /api/provider/test
// 经 playwright route 拦截（成功/失败/慢响应剧本），零真实引擎依赖。
//
// 语义钉：
//   T1 归位：测试钮在 provider 编辑弹窗的每个模型行尾；弹窗级结果块在位
//      （data-* 只携带路由身份，零凭据材料）；设置页 provider 卡模型行无测试钮
//      （归拢，源级钉）
//   T2 测试中：spinner + 钮禁用——测试中禁重复点击（连点只发一次请求）
//   T3 可用：结果块该模型行绿 ✓ + 延迟读数（肉眼完整可见）
//   T4 不可用：结果块该模型行红 ✗ + **全量错误文本**（不截断——整串错误出现在
//      行文本里，含 60 字符截断位之后的尾段）
//   T5 请求形状（表单覆写腿）：POST {name, model, baseUrl, apiKey, protocol} =
//      弹窗表单当前值（未保存可测）；apiKey 空 = 不覆写（沿用存储凭据）
//   T6 请求形状（存储腿）：无 baseUrl（新建弹窗未填）→ body 只 {name, model}
//      （旧形状逐字兼容）
//   T7 行内现值解析：动态加行后现打的 model id 被原样测试（读行内输入现值）
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
const HARNESS = '/__harness-provider-test-modal.html';
const SHOT_DIR = process.env.NB_MODELTEST_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261029_settings-panel-fourfix', 'provider-test-modal');

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
    path.join(REPO_ROOT, 'tests', 'fixtures', 'provider-test-modal', 'harness.html'),
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

async function openHarness(page, mode = 'edit') {
  await page.goto(`http://127.0.0.1:${port}${HARNESS}?mode=${mode}`);
  await page.waitForFunction(() => document.body.dataset.ready === '1');
}

test('T1. 归位：弹窗内每模型行一枚测试钮 + 弹窗级结果块在位；设置卡模型行无钮（源级钉）', async ({ page }) => {
  await page.route('**/api/provider/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"latencyMs":42}' }));
  await openHarness(page);
  const btns = page.locator('#cfg-modal .cfg-model-test');
  await expect(btns).toHaveCount(2);
  // 路由身份在 data-*（零凭据材料；provider 名在点击时读表单现值，不在行上）。
  await expect(btns.nth(0)).toHaveAttribute('data-test-model', 'deepseek/deepseek-v4.1-flash');
  // 弹窗级结果块（每模型一行的显示面）挂在 models 组之后。
  const block = page.locator('#cfg-modal .cfg-test-results');
  await expect(block).toBeAttached();
  // 源级钉：设置页 provider 卡（sidebar.js renderProviderCard）不再拼测试钮。
  const sidebarSrc = fs.readFileSync(path.join(WEB_ROOT, 'js', 'sidebar.js'), 'utf8');
  expect(sidebarSrc).not.toContain('modelTestButtonHTML');
  await page.screenshot({ path: path.join(SHOT_DIR, 'T1-modal-buttons.png'), fullPage: true });
});

test('T2. 测试中禁重复点击：慢响应期间连点两次 → 只发一次请求', async ({ page }) => {
  let hits = 0;
  await page.route('**/api/provider/test', async (route) => {
    hits += 1;
    await new Promise((r) => setTimeout(r, 400));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 400 }) });
  });
  await openHarness(page);
  const btn = page.locator('#cfg-modal .cfg-model-test').nth(0);
  await btn.click();
  await expect(btn).toBeDisabled(); // spinner + 禁用（T2）
  // 结果块该模型行 = testing 态（转圈状态肉眼明确——作者令「转圈状态不明显」修复面）。
  const line = page.locator('#cfg-modal .cfg-test-result-line[data-model="deepseek/deepseek-v4.1-flash"]');
  await expect(line).toHaveClass(/testing/);
  await expect(line).toContainText('测试中');
  await btn.click({ force: true }); // 双击意图（disabled 按钮不吃点击）
  await expect(btn).toBeEnabled({ timeout: 3000 });
  expect(hits).toBe(1); // 测试中禁重复点击
});

test('T3. 可用剧本：结果块绿 ✓ + 延迟读数（不截断）', async ({ page }) => {
  await page.route('**/api/provider/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 602 }) }));
  await openHarness(page);
  await page.locator('#cfg-modal .cfg-model-test').nth(0).click();
  const line = page.locator('#cfg-modal .cfg-test-result-line[data-model="deepseek/deepseek-v4.1-flash"]');
  await expect(line.locator('.st-ok')).toBeVisible();
  await expect(line).toContainText('602ms');
  await page.screenshot({ path: path.join(SHOT_DIR, 'T3-test-ok.png'), fullPage: true });
});

test('T4. 不可用剧本：红 ✗ + 全量错误文本进结果行（永不截断）', async ({ page }) => {
  // 误差串含 60 字符截断位之后的尾段——旧行内摘要位会截掉，结果块必须整串可见。
  const fullError = "HTTP 401: Invalid 'Authorization' header or token. (request id: req-20261029104700-ab12cd34ef56)";
  await page.route('**/api/provider/test', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, error: fullError }) }));
  await openHarness(page);
  await page.locator('#cfg-modal .cfg-model-test').nth(0).click();
  const line = page.locator('#cfg-modal .cfg-test-result-line[data-model="deepseek/deepseek-v4.1-flash"]');
  await expect(line.locator('.st-err')).toBeVisible();
  // 🔴 全量钉：整串错误文本（含尾段）出现在行文本里——截断即红。
  await expect(line).toContainText(fullError);
  await page.screenshot({ path: path.join(SHOT_DIR, 'T4-test-fail-full-text.png'), fullPage: true });
});

test('T5. 请求形状（表单覆写腿）：{name, model, baseUrl, apiKey, protocol} = 表单现值', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/provider/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 50 }) });
  });
  await openHarness(page);
  // 改 baseUrl/key 后直接测（未保存也可测——归位到配置页的动机本体）。
  await page.fill('[data-field="baseUrl"]', 'https://staging.example.com/v1/');
  await page.fill('[data-field="apiKey"]', 'sk-fresh-typed-key');
  await page.locator('#cfg-modal .cfg-model-test').nth(1).click();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({
    name: 'cmdcode',
    model: 'gpt-5.5',
    baseUrl: 'https://staging.example.com/v1/',
    apiKey: 'sk-fresh-typed-key',
    protocol: 'openai',
  });
});

test('T6. 请求形状（存储腿）：新建弹窗未填 baseUrl → body 只 {name, model}', async ({ page }) => {
  /** @type {any[]} */
  const calls = [];
  await page.route('**/api/provider/test', async (route) => {
    calls.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, latencyMs: 51 }) });
  });
  await openHarness(page, 'add');
  await page.fill('[data-field="name"]', 'newprov');
  // 首行 model 输入现打 id（T7 同击覆盖）。
  await page.fill('.cfg-model-row >> nth=0 >> .cfg-model-id', 'some/new-model');
  await page.locator('#cfg-modal .cfg-model-test').nth(0).click();
  await expect.poll(() => calls.length).toBe(1);
  // baseUrl 空 = 无表单覆写 → 存储腿旧形状（零空串键）。
  expect(calls[0]).toEqual({ name: 'newprov', model: 'some/new-model' });
});
