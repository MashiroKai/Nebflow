// project-create-panel.spec.mjs — ProjectCreate「未知路径 AskUser 式交互面板」
// 前端验证。
//
// 后端实现（NodeTools.scala ProjectCreateTool）：workspace 缺省/不可用 → 复用
// AskUser pending 机制（AgentCommand.AskUser → InteractionHub → 前端
// renderAskUser）弹路径面板；2026-09-09 作者裁定：不下发候选（options 空 +
// dirPicker=true），选择面 = 应用内目录浏览器（前端大目标 → workspacePicker.js）
// 或自由输入（textarea，~ 展开由后端负责）；取消 → '__cancelled__' 哨兵。
//
// 前端实现 = 零新增：面板就是 AskUserQuestion 卡片本身（renderAskUser/
// showOptions），i18n 无新增 key（chat.confirm/chat.cancel 中英已成对），
// dirPicker 大目标复用既有 workspace-picker 组件。本 spec 驱动真实渲染模块
// （无 stub、无打包）验证面板载荷下的：
//   1. 卡片渲染：问题 + 「选择工作区」大目标 + 自由输入 textarea + 取消键；
//      无候选、无「其他…」（确认门控随输入解锁）；
//   2. 自由输入绝对路径 → 确认 → askUserAnswer 帧载荷正确（answers=[路径], requestId）；
//   3. '~' 前缀原样上送（限定前端不做展开——展开/绝对化校验由后端负责）；
//   4. 取消 → answers=['__cancelled__']；
//   5. 亮暗双主题截图落盘（验收证据）。
//
// Static file server on a random high port (never the 8080 host), spawned in
// beforeAll and killed in afterAll.
//
// Run: npx playwright test tests/project-create-panel.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/project-create-panel/harness.html';
const SHOT_DIR = path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');
const DARK_SHOT = path.join(SHOT_DIR, '20260903_project-create-panel-dark.png');
const LIGHT_SHOT = path.join(SHOT_DIR, '20260903_project-create-panel-light.png');

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

function startStaticServer(dir) {
  return spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  servers.push(startStaticServer(REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser, colorScheme) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    colorScheme,
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

// ============================================================
// 1. 面板渲染：问题 + 选择工作区大目标 + 自由输入 + 取消键（无候选无其他…）
// ============================================================
test('面板渲染：选择工作区大目标 + 自由输入兜底 + 取消键（无候选、无其他…，2026-09-09 作者裁定）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  const { requestId } = await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  // 唯一卡片，带 requestId 标签（chat-input passthrough 定位用）
  await expect(page.locator('.option-box')).toHaveCount(1);
  await expect(page.locator('.option-box')).toHaveAttribute('data-request-id', requestId);

  // 问题文本含面板语义（目录浏览器引导 + 自由输入/~ 提示）
  const question = await page.locator('.option-q').first().textContent();
  expect(question).toContain('ProjectCreate');
  expect(question).toContain('工作区路径');
  expect(question).toContain('绝对路径');
  expect(question).toContain('~');

  // 选择工作区大目标（dirPicker 卡，2026-09-05/06 作者裁定）
  const target = page.locator('.ws-pick-target');
  await expect(target).toBeVisible();
  await expect(target.locator('.ws-pick-title')).toHaveText(/选择工作区|Pick Workspace/);

  // 无候选按钮、无「其他…」——空 options = 自由输入 textarea 直接可见
  await expect(page.locator('.option-btn')).toHaveCount(0);
  const input = page.locator('.option-custom-input');
  await expect(input).toBeVisible();

  // 确认/取消键在位；未作答时确认禁用（门控随输入解锁）
  await expect(page.locator('.option-confirm')).toBeDisabled();
  await expect(page.locator('.option-cancel')).toBeEnabled();

  await page.screenshot({ path: DARK_SHOT, fullPage: true });
  await context.close();
});

// ============================================================
// 2. 自由输入绝对路径 → 确认 → 载荷正确
// ============================================================
test('自由输入绝对路径 → 确认：askUserAnswer 载荷 = answers[路径] + requestId，卡片锁定', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  const custom = '/Users/dev/scratch/new-project';
  const input = page.locator('.option-custom-input');
  await input.fill(custom);
  await expect(page.locator('.option-confirm')).toBeEnabled(); // 输入解锁确认门控
  await page.locator('.option-confirm').click();

  const frames = await page.evaluate(() => window.__capturedFrames());
  expect(frames).toHaveLength(1);
  expect(frames[0]).toEqual({
    type: 'askUserAnswer',
    sessionId: 'harness-session',
    answers: [custom],
    requestId: 'pc-e2e-01',
  });

  // 卡片锁定（幂等防双答：确认/取消隐藏 + 答案回显）
  await expect(page.locator('.option-answer')).toHaveCount(1);
  await expect(page.locator('.option-answer')).toContainText(custom);
  await context.close();
});

// ============================================================
// 3. '~' 前缀原样上送（不展开——后端负责展开/绝对化校验）
// ============================================================
test("'~' 前缀原样上送：answers = 键入值（不展开；展开/校验由后端负责）", async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  const custom = '~/scratch/new-project';
  await page.locator('.option-custom-input').fill(custom);
  await page.locator('.option-confirm').click();

  const frames = await page.evaluate(() => window.__capturedFrames());
  expect(frames).toHaveLength(1);
  expect(frames[0].type).toBe('askUserAnswer');
  expect(frames[0].answers).toEqual([custom]);
  expect(frames[0].requestId).toBe('pc-e2e-01');
  await context.close();
});

// ============================================================
// 4. 取消 → '__cancelled__' 哨兵（后端据回搁置消息，不创建）
// ============================================================
test('取消键：answers = __cancelled__ 哨兵（后端回搁置消息，不创建项目）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  await page.locator('.option-cancel').click();

  const frames = await page.evaluate(() => window.__capturedFrames());
  expect(frames).toHaveLength(1);
  expect(frames[0]).toEqual({
    type: 'askUserAnswer',
    sessionId: 'harness-session',
    answers: ['__cancelled__'],
    requestId: 'pc-e2e-01',
  });
  await context.close();
});

// ============================================================
// 5. 亮色主题截图（暗色已在用例 1 落盘）
// ============================================================
test('亮色主题渲染并截图（与暗色成对，验收证据）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'light');
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  await expect(page.locator('.option-box')).toHaveCount(1);
  await expect(page.locator('.ws-pick-target')).toBeVisible();
  await expect(page.locator('.option-btn')).toHaveCount(0);
  await expect(page.locator('.option-custom-input')).toBeVisible();
  await page.screenshot({ path: LIGHT_SHOT, fullPage: true });
  await context.close();
});
