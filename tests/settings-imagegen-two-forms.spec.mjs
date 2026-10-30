// settings-imagegen-two-forms.spec.mjs — 设置面板「生图」节两形态钉
// （settings-panel-jev-imagegen 批 2026-10-28 初版两形态；settings-panel-fourfix
// 批 ③件 2026-10-29 作者令重做：「就是普通配置过程——填 API Key 值、保存，完事」
// 「前端永远不出现引用/keyRef/secrets 指针这类字眼与解释文案」「不要过拟合智谱
// ——新用户看到的必须是全空」「整体精简」）。
//
// Drives the REAL module (js/sidebar/imagegenSection.js) over a fixture page
// served from the source tree (🔴 绝非 8080 宿主)；全部网络面经 playwright
// route 拦截（GET /api/config/imagegen、POST|PUT /api/secrets/*、PUT
// /api/config/imagegen）——零真实引擎依赖。secrets POST 后的再读按引擎语义
// 返回 keyConfigured=true（在位性投影，scala 侧 ImageGenSettingsSpec 同钉）。
//
// 语义钉：
//   A1 seal OFF 形态：「未编入（release 构建零痕迹）」提示位、配置面控件全无
//   A2 seal OFF 形态零「调试中」徽标（功能开放批 2026-10-30：徽标退役，两形态
//      均不得再渲染 .imagegen-badge）
//   B1 seal ON 既有 provider：配置值与投影载荷一致；「待录入」徽标；掩码默认态
//   B2 凭据面无感化：整节渲染文本零 keyRef/密钥引用/指针字眼（fourfix ③铁钉）；
//      单一「保存」钮（无「保存密钥」第二钮）
//   B3 新用户空白：providers 空 → 全空新增面（提供方=＋新增、名称空、协议适配
//      占位、模型/端点/key 空，零 zhipu/cogview 特化值——fourfix ③代码层铁钉）
//   B4 保存形状（新 provider）：secrets POST 名 = 派生名 imagegen-<name>-key
//      （值只进请求体）+ 整节 PUT {enabled, providers, defaultProvider}
//   B5 既有 provider 保存：409→PUT 覆写重录；已配置态 + 输入清空
//   B6 整节替换 PUT：全部 provider 原样保留、投影字段不回写
//   B7 删除提供方：两段式确认 → PUT 少该项（defaultProvider 让位）
//   B8 「调试中」徽标零渲染（功能开放批：debugBadge 单源已撤，页面零 .imagegen-badge）
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
  path.join(REPO_ROOT, 'evidence/20261029_121500_settings-panel-fourfix__delegate-nebflow-73f5fdd8', 'imagegen-two-forms');

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
const statusOff = { compiledIn: false, debugLabel: '', configured: true, enabled: true, defaultProvider: 'zhipu', providers: {} };
const zhipuMissing = { type: 'cogview', model: 'cogview-3-flash', baseUrl: null, keyRef: 'zhipu-api-key', keyConfigured: false };
const statusMissingKey = {
  compiledIn: true, debugLabel: '', configured: true, enabled: true, defaultProvider: 'zhipu',
  providers: { zhipu: zhipuMissing },
};
const statusWithKey = {
  ...statusMissingKey,
  providers: { zhipu: { ...zhipuMissing, keyConfigured: true } },
};
const statusEmpty = { compiledIn: true, debugLabel: '', configured: false, enabled: false, defaultProvider: null, providers: {} };

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

/** B2 seam: the whole rendered section carries zero pointer wording. */
async function expectNoPointerWording(page) {
  const text = await page.locator('#imagegen-section-wrap').innerText();
  expect(text).not.toContain('keyRef');
  expect(text).not.toContain('密钥引用');
  expect(text).not.toContain('指针');
  // 用户可见 locale 面（源级补钉）：两份 locale 的 imagegen 键无 keyRef 字眼。
  for (const f of ['js/locales/zh-CN.js', 'js/locales/en.js']) {
    const src = fs.readFileSync(path.join(WEB_ROOT, f), 'utf8');
    const imagegenKeys = src.split('\n').filter((l) => l.includes("'settings.imagegen"));
    expect(imagegenKeys.some((l) => /keyRef|密钥引用|指针/.test(l))).toBe(false);
  }
}

test('A. seal=OFF 形态：未编入提示位 + 零调试徽标 + 配置面控件全无', async ({ page }) => {
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
  // 徽标（A2/B8）：功能开放批退役——页面零 .imagegen-badge 渲染。
  await expect(page.locator('.imagegen-badge')).toHaveCount(0);
  await page.screenshot({ path: path.join(SHOT_DIR, 'A-seal-off-not-compiled.png'), fullPage: true });
});

test('B1+B2. seal=ON 既有 provider：投影值在位 + 待录入 + 掩码 + 零 keyRef 字眼 + 单一保存钮', async ({ page }) => {
  const state = { status: statusMissingKey, existingSecrets: new Set() };
  const { install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  const face = page.locator('#imagegen-section-wrap .imagegen-face[data-form="compiled"]');
  await expect(face).toBeVisible();
  // 配置值与投影一致（B1——作者机器已存值照实显示，不是预填）。
  await expect(page.locator('#toggle-imagegen')).toBeVisible();
  await expect(page.locator('#toggle-imagegen')).toHaveClass(/on/);
  await expect(page.locator('#cfg-imagegen-provider')).toHaveValue('zhipu');
  await expect(page.locator('#cfg-imagegen-type')).toHaveValue('cogview');
  await expect(page.locator('#cfg-imagegen-model')).toHaveValue('cogview-3-flash');
  // 缺 key 态（B1）：待录入徽标 + key 输入框默认掩码。
  await expect(page.locator('.st-badge', { hasText: '待录入' })).toBeVisible();
  const keyInput = page.locator('#cfg-imagegen-key');
  await expect(keyInput).toHaveValue('');
  const sec = await keyInput.evaluate((el) => /** @type {any} */ (getComputedStyle(el)).webkitTextSecurity);
  expect(String(sec)).toContain('disc');
  // 凭据面无感化（B2）：零 keyRef 字眼 + 单一保存钮（无「保存密钥」第二钮）。
  await expectNoPointerWording(page);
  await expect(page.locator('#btn-save-imagegen')).toHaveCount(1);
  await expect(page.locator('#btn-save-imagegen-key')).toHaveCount(0);
  // 删除钮在位（既有 provider 编辑面）。
  await expect(page.locator('#btn-delete-imagegen')).toBeVisible();
  await page.screenshot({ path: path.join(SHOT_DIR, 'B1-seal-on-missing-key.png'), fullPage: true });
});

test('B3. 新用户空白：providers 空 → 全空新增面（零 zhipu/cogview 特化值）', async ({ page }) => {
  const state = { status: statusEmpty, existingSecrets: new Set() };
  const { install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  const face = page.locator('#imagegen-section-wrap .imagegen-face[data-form="compiled"]');
  await expect(face).toBeVisible();
  // 提供方下拉只有「＋ 新增」且已选中；名称输入可见且空。
  const sel = page.locator('#cfg-imagegen-provider');
  await expect(sel).toHaveValue('__add__');
  await expect(sel.locator('option')).toHaveCount(1);
  await expect(page.locator('#cfg-imagegen-newprovider')).toBeVisible();
  await expect(page.locator('#cfg-imagegen-newprovider')).toHaveValue('');
  // 协议适配 = 占位（value 空）；模型/端点/key 全空。
  await expect(page.locator('#cfg-imagegen-type')).toHaveValue('');
  await expect(page.locator('#cfg-imagegen-model')).toHaveValue('');
  await expect(page.locator('#cfg-imagegen-baseurl')).toHaveValue('');
  await expect(page.locator('#cfg-imagegen-key')).toHaveValue('');
  // 🔴 新用户空白铁钉（fourfix ③）：整节 DOM 零智谱特化值。
  const html = await page.locator('#imagegen-section-wrap').innerHTML();
  for (const banned of ['zhipu', 'cogview-3-flash', 'zhipu-api-key', 'bigmodel']) {
    expect(html).not.toContain(banned);
  }
  await expectNoPointerWording(page);
  await page.screenshot({ path: path.join(SHOT_DIR, 'B3-new-user-blank.png'), fullPage: true });
});

test('B4. 保存形状（新 provider）：派生密钥名 + 整节 PUT + defaultProvider', async ({ page }) => {
  const state = { status: statusEmpty, existingSecrets: new Set() };
  const { calls, install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  await page.fill('#cfg-imagegen-newprovider', 'myprov');
  await page.selectOption('#cfg-imagegen-type', 'openai-images');
  await page.fill('#cfg-imagegen-model', 'gpt-image-1');
  await page.fill('#cfg-imagegen-key', 'sk-test-value-12345');
  await page.click('#toggle-imagegen'); // 启用
  await page.click('#btn-save-imagegen');

  // 密钥写入：名字 = 派生名（对用户不可见），值只进请求体一次。
  await expect.poll(() => calls.secrets.length).toBe(1);
  expect(calls.secrets[0]).toMatchObject({ method: 'POST', name: 'imagegen-myprov-key', value: 'sk-test-value-12345' });
  // 配置整节 PUT：新 provider 带 keyRef（指针机制后端延续，前端无感）。
  await expect.poll(() => calls.sectionPuts.length).toBe(1);
  const put = calls.sectionPuts[0];
  expect(put.enabled).toBe(true);
  expect(put.defaultProvider).toBe('myprov');
  expect(put.providers.myprov).toEqual({ type: 'openai-images', model: 'gpt-image-1', keyRef: 'imagegen-myprov-key' });
  expect(JSON.stringify(put)).not.toContain('keyConfigured');
  // 保存后输入框清空（值不回显不滞留）。
  await expect(page.locator('#cfg-imagegen-key')).toHaveValue('');
  await page.screenshot({ path: path.join(SHOT_DIR, 'B4-new-provider-saved.png'), fullPage: true });
});

test('B5. 既有 provider 重录：单保存钮 → 409 → PUT 覆写 → 已配置态 + 输入清空', async ({ page }) => {
  const state = { status: statusWithKey, afterSecret: statusWithKey, existingSecrets: new Set(['zhipu-api-key']) };
  const { calls, install } = wireRoutes(page, state);
  await install();
  await openHarness(page);

  await page.fill('#cfg-imagegen-key', 'sk-rotated-value');
  await page.click('#btn-save-imagegen');
  await expect(page.locator('.st-badge', { hasText: '已配置' })).toBeVisible();
  await expect(page.locator('#cfg-imagegen-key')).toHaveValue('');
  // 形状钉：409 冲突后必须有一次同名 PUT（重录语义），值随请求体一次。
  await expect.poll(() => calls.secrets.length).toBe(2);
  expect(calls.secrets[0]).toMatchObject({ method: 'POST', name: 'zhipu-api-key' });
  expect(calls.secrets[1]).toMatchObject({ method: 'PUT', name: 'zhipu-api-key', value: 'sk-rotated-value' });
  await page.screenshot({ path: path.join(SHOT_DIR, 'B5-key-reentry.png'), fullPage: true });
});

test('B6. 配置保存：整节替换 PUT——全 provider 保留 + 投影字段不回写', async ({ page }) => {
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

  // 编辑 zhipu 的模型 → 保存（key 留空 = 只存配置，凭据不动）。
  await page.fill('#cfg-imagegen-model', 'cogview-4');
  await page.click('#btn-save-imagegen');
  await expect(page.locator('#imagegen-save-state')).toContainText('已保存');

  // 整节替换形状：全部 provider 在 payload（bean 原样保留）、编辑项覆写、
  // keyConfigured 投影字段绝不回写；key 留空 → 零 secrets 调用。
  await expect.poll(() => calls.sectionPuts.length).toBe(1);
  expect(calls.secrets.length).toBe(0);
  const put = calls.sectionPuts[0];
  expect(Object.keys(put.providers).sort()).toEqual(['bean', 'zhipu']);
  expect(put.providers.zhipu).toEqual({ type: 'cogview', model: 'cogview-4', keyRef: 'zhipu-api-key' });
  expect(put.providers.bean).toEqual({ type: 'openai-images', model: 'gpt-image-1', baseUrl: 'https://ark.example/v1', keyRef: 'ark-key' });
  expect(JSON.stringify(put)).not.toContain('keyConfigured');
  await page.screenshot({ path: path.join(SHOT_DIR, 'B6-section-saved.png'), fullPage: true });
});

test('B7. 删除提供方：两段式确认 → PUT 少该项', async ({ page }) => {
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

  const del = page.locator('#btn-delete-imagegen');
  await del.click();
  // 第一击 = 挂确认位（文案翻转为再点确认），零网络。
  await expect(del).toContainText('再点一次');
  expect(calls.sectionPuts.length).toBe(0);
  await del.click();
  await expect.poll(() => calls.sectionPuts.length).toBe(1);
  const put = calls.sectionPuts[0];
  expect(Object.keys(put.providers)).toEqual(['bean']);
  expect(put.defaultProvider).toBe('bean'); // 删除项的 defaultProvider 让位
  await page.screenshot({ path: path.join(SHOT_DIR, 'B7-provider-deleted.png'), fullPage: true });
});

test('B8. 「调试中」徽标零渲染（功能开放批：debugBadge 单源已撤）', async ({ page }) => {
  const state = { status: statusMissingKey, existingSecrets: new Set() };
  const { install } = wireRoutes(page, state);
  await install();
  await openHarness(page);
  await expect(page.locator('.imagegen-badge')).toHaveCount(0);
});
