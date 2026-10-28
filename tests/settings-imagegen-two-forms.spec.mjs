// settings-imagegen-two-forms.spec.mjs — 设置面板「生图」节两形态钉
// （settings-panel-jev-imagegen 批 2026-10-28，作者令「seal=OFF 构建下面板显示
// 『未编入（release 构建零痕迹）』提示位；seal=ON 构建显示正常配置面——两形态
// 都要有 spec」）。
//
// Drives the REAL module (js/sidebar/imagegenSection.js) over a fixture page
// served from the source tree (🔴 绝非 8080 宿主)；全部网络面经 playwright
// route 拦截（GET /api/config/imagegen、POST|PUT /api/secrets/*、PUT
// /api/config/imagegen）——零真实引擎依赖。secrets POST 后的再读按引擎语义
// 返回 keyConfigured=true（在位性投影，scala 侧 ImageGenSettingsSpec 同钉）。
//
// 语义钉：
//   A1 seal OFF 形态：compiledIn=false → 「未编入（release 构建零痕迹）」提示位
//      渲染、配置面控件不存在（enabled 开关/模型输入/key 录入全无）
//   A2 seal OFF 形态同样携带「调试中」徽标（设计六验收⑥的面板腿）
//   B1 seal ON 形态：enabled 开关 + provider/model/keyRef 编辑面在位，
//      配置值与投影载荷一致（cogview / cogview-3-flash / zhipu-api-key）
//   B2 缺 key 态：「待录入」徽标 + 缺 key 提示行 + key 输入框默认掩码
//   B3 key 录入：输入 → POST /api/secrets/<keyRef> 一次成形（值只进请求体）
//      → 重拉状态 → 「已配置」态 + 输入框清空（值不回显不滞留）
//   B4 重录：已存在 → POST 409 → PUT 覆写 → 已配置态保持
//   B5 配置保存：整节替换 PUT——payload 携带全部 provider（其余原样保留）
//      + enabled + defaultProvider；keyConfigured 投影字段不回写
//   B6 两形态共享同一枚 .imagegen-badge（工具面/插件面单源续用）
//
// Run: npx playwright test tests/settings-imagegen-two-forms.spec.mjs

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
const HARNESS = '/__harness-settings-imagegen.html';
const SHOT_DIR = process.env.NB_IMAGEGEN_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261028_settings-panel-jev-imagegen', 'imagegen-two-forms');

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
    path.join(REPO_ROOT, 'tests', 'fixtures', 'settings-imagegen', 'harness.html'),
    path.join(WEB_ROOT, HARNESS),
  );
  startStaticServer(port, WEB_ROOT);
  await waitUntilUp(`http://127.0.0.1:${port}/js/sidebar/imagegenSection.js`);
  fs.mkdirSync(SHOT_DIR, { recursive: true });
});

test.afterAll(() => {
  for (const s of servers) s.kill();
  fs.rmSync(path.join(WEB_ROOT, HARNESS), { force: true });
});

/** Status payloads (spec-owned truth — the shape ConfigService.imagegenStatus
 *  projects; the scala side pins the same shape in ImageGenSettingsSpec). */
const statusOff = { compiledIn: false, debugLabel: '调试中', configured: true, enabled: true, defaultProvider: 'zhipu', providers: {} };
const zhipuMissing = { type: 'cogview', model: 'cogview-3-flash', baseUrl: null, keyRef: 'zhipu-api-key', keyConfigured: false };
const statusMissingKey = {
  compiledIn: true, debugLabel: '调试中', configured: true, enabled: true, defaultProvider: 'zhipu',
  providers: { zhipu: zhipuMissing },
};
const statusWithKey = {
  ...statusMissingKey,
  providers: { zhipu: { ...zhipuMissing, keyConfigured: true } },
};

/**
 * Route interception with a call ledger. The GET replies with the CURRENT
 * state.status; a secrets POST that lands flips it to state.afterSecret
 * (engine semantics: the secret now exists → keyConfigured=true on re-read).
 * @param {import('@playwright/test').Page} page
 * @param {{status: any, afterSecret?: any, existingSecrets: Set<string>, secretPostConflict?: boolean}} state
 */
function wireRoutes(page, state) {
  const calls = { secrets: [], sectionPuts: [] };
  const install = async () => {
    await page.route('**/api/config/imagegen', async (route) => {
      const req = route.request();
      if (req.method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.status) });
      } else if (req.method() === 'PUT') {
        calls.sectionPuts.push(JSON.parse(req.postData() || '{}'));
        await route.fulfill({ status: 200, contentType: 'application/json', body: '{"updated":true}' });
      } else {
        await route.fulfill({ status: 405, contentType: 'application/json', body: '{}' });
      }
    });
    await page.route('**/api/secrets/*', async (route) => {
      const req = route.request();
      const url = new URL(req.url());
      const name = decodeURIComponent(url.pathname.split('/').pop() || '');
      if (req.method() === 'POST' || req.method() === 'PUT') {
        calls.secrets.push({ method: req.method(), name, value: JSON.parse(req.postData() || '{}').value });
        const exists = state.existingSecrets.has(name);
        if (req.method() === 'POST' && exists) {
          await route.fulfill({ status: 409, contentType: 'application/json', body: '{"error":"conflict"}' });
          return;
        }
        state.existingSecrets.add(name);
        if (state.afterSecret) state.status = state.afterSecret;
        await route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
        return;
      }
      await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    });
  };
  return { calls, install };
}

async function openHarness(page) {
  await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
  await page.waitForFunction(() => document.body.dataset.ready === '1');
}

test('A. seal=OFF 形态：未编入提示位 + 调试中徽标 + 配置面控件全无', async ({ page }) => {
  const state = { status: statusOff, existingSecrets: new Set() };
  const { install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  const face = page.locator('#imagegen-section-wrap .imagegen-face[data-form="not-compiled"]');
  await expect(face).toBeVisible();
  await expect(face).toContainText('未编入（release 构建零痕迹）');
  // 配置面控件不存在——提示位形态零能力面。
  await expect(page.locator('#toggle-imagegen')).toHaveCount(0);
  await expect(page.locator('#cfg-imagegen-model')).toHaveCount(0);
  await expect(page.locator('#cfg-imagegen-key')).toHaveCount(0);
  // 徽标（A2/B6）：调试中，同一枚单源。
  const badge = page.locator('#badge-slot .imagegen-badge');
  await expect(badge).toHaveText('调试中');
  await page.screenshot({ path: path.join(SHOT_DIR, 'A-seal-off-not-compiled.png'), fullPage: true });
});

test('B1+B2. seal=ON 缺 key 形态：配置面在位 + 「待录入」+ 缺 key 提示 + 掩码默认态', async ({ page }) => {
  const state = { status: statusMissingKey, existingSecrets: new Set() };
  const { install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  const face = page.locator('#imagegen-section-wrap .imagegen-face[data-form="compiled"]');
  await expect(face).toBeVisible();
  // 配置值与投影一致（B1）。
  await expect(page.locator('#toggle-imagegen')).toBeVisible();
  await expect(page.locator('#toggle-imagegen')).toHaveClass(/on/);
  await expect(page.locator('#cfg-imagegen-provider')).toHaveValue('zhipu');
  await expect(page.locator('#cfg-imagegen-type')).toHaveValue('cogview');
  await expect(page.locator('#cfg-imagegen-model')).toHaveValue('cogview-3-flash');
  await expect(page.locator('#cfg-imagegen-keyref')).toHaveValue('zhipu-api-key');
  // 缺 key 态（B2）：待录入徽标 + 缺 key 提示行；key 输入框默认掩码。
  await expect(page.locator('.st-badge', { hasText: '待录入' })).toBeVisible();
  await expect(face).toContainText('缺 key');
  const keyInput = page.locator('#cfg-imagegen-key');
  await expect(keyInput).toHaveValue('');
  const sec = await keyInput.evaluate((el) => /** @type {any} */ (getComputedStyle(el)).webkitTextSecurity);
  expect(String(sec)).toContain('disc');
  // 徽标仍单源在场（B6）。
  await expect(page.locator('#badge-slot .imagegen-badge')).toHaveText('调试中');
  await page.screenshot({ path: path.join(SHOT_DIR, 'B1-seal-on-missing-key.png'), fullPage: true });
});

test('B3. key 录入：POST secrets 一次成形 → 重拉 → 已配置 + 输入清空（值不回显）', async ({ page }) => {
  const state = { status: statusMissingKey, afterSecret: statusWithKey, existingSecrets: new Set() };
  const { calls, install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  await page.fill('#cfg-imagegen-key', 'sk-test-value-12345');
  await page.click('#btn-save-imagegen-key');

  // 值只进请求体一次；密钥名 = keyRef 指针名。
  await expect.poll(() => calls.secrets.length).toBe(1);
  expect(calls.secrets[0]).toMatchObject({ method: 'POST', name: 'zhipu-api-key', value: 'sk-test-value-12345' });
  // 重拉后面 = 已配置 + 输入框清空（值不回显不滞留）。
  await expect(page.locator('.st-badge', { hasText: '已配置' })).toBeVisible();
  await expect(page.locator('#cfg-imagegen-key')).toHaveValue('');
  await expect(page.locator('#toast-slot')).toContainText('密钥已保存');
  await page.screenshot({ path: path.join(SHOT_DIR, 'B3-key-configured.png'), fullPage: true });
});

test('B4. 重录：已存在 → POST 409 → PUT 覆写 → 已配置态保持', async ({ page }) => {
  const state = { status: statusWithKey, afterSecret: statusWithKey, existingSecrets: new Set(['zhipu-api-key']) };
  const { calls, install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  await page.fill('#cfg-imagegen-key', 'sk-rotated-value');
  await page.click('#btn-save-imagegen-key');
  await expect(page.locator('.st-badge', { hasText: '已配置' })).toBeVisible();
  await expect(page.locator('#cfg-imagegen-key')).toHaveValue('');
  // 形状钉：409 冲突后必须有一次同名 PUT（重录语义），值随请求体一次。
  await expect.poll(() => calls.secrets.length).toBe(2);
  expect(calls.secrets[0]).toMatchObject({ method: 'POST', name: 'zhipu-api-key' });
  expect(calls.secrets[1]).toMatchObject({ method: 'PUT', name: 'zhipu-api-key', value: 'sk-rotated-value' });
  await page.screenshot({ path: path.join(SHOT_DIR, 'B4-key-reentry.png'), fullPage: true });
});

test('B5. 配置保存：整节替换 PUT——全 provider 保留 + defaultProvider + 投影字段不回写', async ({ page }) => {
  const state = {
    status: {
      ...statusWithKey,
      providers: {
        zhipu: statusWithKey.providers.zhipu,
        bean: { type: 'openai-images', model: 'gpt-image-1', baseUrl: 'https://ark.example/v1', keyRef: 'ark-key', keyConfigured: true },
      },
    },
    existingSecrets: new Set(['zhipu-api-key', 'ark-key']),
  };
  const { calls, install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  // 编辑 zhipu 的模型 → 保存。
  await page.fill('#cfg-imagegen-model', 'cogview-4');
  await page.click('#btn-save-imagegen');
  await expect(page.locator('#imagegen-save-state')).toContainText('已保存');

  // 整节替换形状：全部 provider 在 payload（bean 原样保留）、编辑项覆写、
  // enabled/defaultProvider 在位、keyConfigured 投影字段绝不回写。
  await expect.poll(() => calls.sectionPuts.length).toBe(1);
  const put = calls.sectionPuts[0];
  expect(put.enabled).toBe(true);
  expect(put.defaultProvider).toBe('zhipu');
  expect(Object.keys(put.providers).sort()).toEqual(['bean', 'zhipu']);
  expect(put.providers.zhipu).toEqual({ type: 'cogview', model: 'cogview-4', keyRef: 'zhipu-api-key' });
  expect(put.providers.bean).toEqual({ type: 'openai-images', model: 'gpt-image-1', baseUrl: 'https://ark.example/v1', keyRef: 'ark-key' });
  expect(JSON.stringify(put)).not.toContain('keyConfigured');
  await page.screenshot({ path: path.join(SHOT_DIR, 'B5-section-saved.png'), fullPage: true });
});
