// project-create-panel.spec.mjs — ProjectCreate「未知路径 AskUser 式交互面板」
// 前端验证（S3 批：2026-09-17 作者裁定 ②-5/②-6/②-7）。
//
// 后端实现（NodeTools.scala ProjectCreateTool）：workspace 缺省/不可用 → 复用
// AskUser pending 机制（AgentCommand.AskUser → InteractionHub → 前端
// renderAskUser）弹路径面板；2026-09-09 作者裁定：不下发候选（options 空 +
// dirPicker=true）——其中「空 options ⇒ 自由输入 textarea 直接可见」一条已被
// 2026-09-17 作者裁定取代（②-7：dirPicker 卡显式 freeInput=false ⇒ 不渲染
// textarea、跳过 localStorage 草稿恢复；②-5：点选即自动提交保持不变；②-6：
// 选择面不可用 ⇒ 按需揭示降级输入面并聚焦，禁死路）；取消 → '__cancelled__' 哨兵。
//
// 前端实现 = 零新增组件：面板就是 AskUserQuestion 卡片本身（renderAskUser/
// showOptions）。本 spec 驱动真实渲染模块（无 stub、无打包），以临时静态服务
// （随机高位端口，never the 8080 host）载入 worktree 内的真实源码文件验证：
//   T1 正常路径真渲染：无 textarea；点选 → 自动提交（②-5 保持现状）；亮暗双主题
//   T2 陈旧草稿判红：localStorage 预置 draft 不得使确认 enabled / 不得提交假答案
//   T3 降级兜底 A：动态 import 拒绝 ⇒ 按需揭示 textarea + 聚焦 + 答案成功上送
//   T4 降级兜底 B：wsBrowse.list 超时 ⇒ 浏览器关闭 + textarea 揭示 + 答案成功上送
//   T5 协议帧对照：freeInput=false ⇒ 无 textarea；旧载荷（缺字段）⇒ 逐字节现状
//   T6 存量对照：其它 ask 卡（options 非空 + allowOther 缺省）行为逐字节不变
//   T7 回放双生：回放卡与实时卡一致地无 textarea（echo 缺失 = 既有 gap，如实记录）
//   T8 三帧截图（正常/兜底/回放）落 .nebflow/evidence/20260917_s3ui/shots/**
//
// 证据落点：REPO/.nebflow/evidence/20260917_s3ui/**（🔴 本批禁写 ~/.nebflow/**）。
//
// Run: npx playwright test tests/project-create-panel.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/project-create-panel/harness.html';
const EVID = path.join(REPO_ROOT, '.nebflow', 'evidence', '20260917_s3ui');
const SHOT_DIR = path.join(EVID, 'shots');

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

const frames = (page) => page.evaluate(() => window.__capturedFrames());
const answerFrames = async (page) => (await frames(page)).filter(f => f.type === 'askUserAnswer');

// ============================================================
// T1 正常路径真渲染：无 textarea；点选 → 自动提交（2026-09-17 ②-5/②-7）
// ============================================================
test('T1 正常路径真渲染：dirPicker 卡无 textarea；点选即自动提交（②-5/②-7）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  const { requestId } = await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  // 唯一卡片 + requestId 标签
  await expect(page.locator('.option-box')).toHaveCount(1);
  await expect(page.locator('.option-box')).toHaveAttribute('data-request-id', requestId);

  // 问题文案：引导目录浏览器 + 改述「选择后即完成创建」；退役叙述不得残留
  const question = await page.locator('.option-q').first().textContent();
  expect(question).toContain('ProjectCreate');
  expect(question).toContain('工作区路径');
  expect(question).toContain('选择后即完成创建');
  expect(question).not.toContain('输入框');
  expect(question).not.toContain('~');

  // 选择工作区大目标（dirPicker 卡，2026-09-05/06 作者裁定）
  const target = page.locator('.ws-pick-target');
  await expect(target).toBeVisible();
  await expect(target.locator('.ws-pick-title')).toHaveText(/选择工作区|Pick Workspace/);

  // 🔴 目标读数：正常路径 DOM 无 .option-custom-input（改造前 = 1）
  await expect(page.locator('.option-custom-input')).toHaveCount(0);
  await expect(page.locator('.option-btn')).toHaveCount(0);
  await expect(page.locator('.option-confirm')).toBeDisabled();
  await expect(page.locator('.option-cancel')).toBeEnabled();

  await page.screenshot({ path: path.join(SHOT_DIR, 'normal-dark.png'), fullPage: true });

  // 点选（打开真实应用内浏览器 → 选中此目录）→ 自动提交，无需再点确认（②-5 保持现状）
  await target.click();
  await expect(page.locator('.wsp-overlay')).toBeVisible();
  await page.locator('.wsp-pick').click();
  await expect(page.locator('.wsp-overlay')).toHaveCount(0);
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  const sent = await answerFrames(page);
  expect(sent[0]).toEqual({
    type: 'askUserAnswer',
    sessionId: 'harness-session',
    answers: ['~'],
    requestId: 'pc-e2e-01',
  });
  // 卡片锁定 + 答案回显（自动提交后即终态）
  await expect(page.locator('.option-answer')).toHaveCount(1);
  await expect(page.locator('.ws-pick-echo')).toContainText('~');
  await context.close();
});

// ============================================================
// T2 陈旧草稿判红：不得使确认 enabled / 不得提交假答案
// ============================================================
test('T2 预置 stale 草稿不得解锁确认、不得提交假答案（②-7 跳过草稿恢复）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__draft('harness-session', 0, '/stale/ghost-path'));
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  await expect(page.locator('.option-custom-input')).toHaveCount(0);
  await expect(page.locator('.option-confirm')).toBeDisabled(); // 看不见的草稿不得满足门控
  expect(await answerFrames(page)).toEqual([]);

  // 点选后答案 = 选中路径（非草稿），且只发一帧
  await page.locator('.ws-pick-target').click();
  await page.locator('.wsp-pick').click();
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  const sent = await answerFrames(page);
  expect(sent[0].answers).toEqual(['~']);
  expect(JSON.stringify(sent[0])).not.toContain('ghost');
  await context.close();
});

// ============================================================
// T3 降级兜底 A：动态 import 拒绝 ⇒ 揭示 textarea + 聚焦 + 成功上送（②-6）
// ============================================================
test('T3 降级兜底（import 拒绝）：按需揭示 textarea + 聚焦，答案成功上送（②-6 禁死路）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.route('**/workspacePicker.js', (r) => r.abort());
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);

  await expect(page.locator('.option-custom-input')).toHaveCount(0); // 揭示前仍是零
  await page.locator('.ws-pick-target').click();

  const input = page.locator('.option-custom-input');
  await expect(input).toHaveCount(1, { timeout: 10000 });
  await expect(input).toBeVisible();
  await expect(page.locator('.ws-pick-target')).not.toHaveClass(/picking/); // 不悬挂
  expect(await page.evaluate(() => document.activeElement?.className)).toContain('option-custom-input');

  await input.fill('/Users/dev/scratch/fallback-proj');
  await expect(page.locator('.option-confirm')).toBeEnabled();
  await page.locator('.option-confirm').click();
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  expect((await answerFrames(page))[0]).toEqual({
    type: 'askUserAnswer',
    sessionId: 'harness-session',
    answers: ['/Users/dev/scratch/fallback-proj'],
    requestId: 'pc-e2e-01',
  });
  expect(pageErrors).toEqual([]);
  await context.close();
});

// ============================================================
// T4 降级兜底 B：wsBrowse.list 超时 ⇒ 关浏览器 + 揭示 textarea + 成功上送（②-6）
// ============================================================
test('T4 降级兜底（list 超时）：浏览器关闭 + textarea 揭示，答案成功上送（②-6 禁死路）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);

  await page.locator('.ws-pick-target').click();
  await expect(page.locator('.wsp-overlay')).toBeVisible();
  // 真实超时路径：workspacePicker.listDir 4s 无响应 ⇒ {error:'timeout'} ⇒ 上抛调用方
  await expect(page.locator('.wsp-overlay')).toHaveCount(0, { timeout: 15000 });
  const input = page.locator('.option-custom-input');
  await expect(input).toHaveCount(1, { timeout: 5000 });
  await expect(input).toBeVisible();
  expect(await page.evaluate(() => document.activeElement?.className)).toContain('option-custom-input');

  await input.fill('~/scratch/fallback-tilde');
  await page.locator('.option-confirm').click();
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  // '~' 原样上送（展开归后端：NodeTools.expandTilde 保留）
  expect((await answerFrames(page))[0].answers).toEqual(['~/scratch/fallback-tilde']);
  expect(pageErrors).toEqual([]);
  await context.close();
});

// ============================================================
// T5 协议帧对照：freeInput=false vs 旧载荷（缺字段）逐字节现状
// ============================================================
test('T5 载荷对照：freeInput=false ⇒ 无 textarea；旧载荷（缺 freeInput）⇒ 现状 textarea 可见', async ({ browser }) => {
  const { context, page } = await newPage(browser, 'dark');

  // (a) 新载荷：fixture 携带 freeInput=false
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  await expect(page.locator('.option-custom-input')).toHaveCount(0);

  // (b) 旧载荷（2026-09-17 之前：无 freeInput 字段）⇒ 行为逐字节现状：空 options 使 textarea 可见
  await page.evaluate(() => window.__panelWith([{
    question: 'legacy payload — 空 options + dirPicker（无 freeInput 字段）',
    options: [],
    dirPicker: true,
  }], 'pc-e2e-legacy'));
  await page.waitForTimeout(100);
  await expect(page.locator('.option-custom-input')).toHaveCount(1);
  await expect(page.locator('.option-custom-input')).toBeVisible();
  await expect(page.locator('.option-confirm')).toBeDisabled();
  await page.locator('.option-custom-input').fill('/tmp/legacy-path');
  await expect(page.locator('.option-confirm')).toBeEnabled();
  await page.locator('.option-confirm').click();
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  expect((await answerFrames(page))[0].answers).toEqual(['/tmp/legacy-path']);
  await context.close();
});

// ============================================================
// T6 存量对照：其它 ask 卡（options 非空 + allowOther 缺省）行为逐字节不变
// ============================================================
test('T6 存量对照：其它 ask 卡（options 非空 + allowOther 缺省）textarea 行为不变', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__panelWith([{
    question: 'pick one',
    options: [{ label: 'A' }, { label: 'B' }],
  }], 'req-other'));
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  // 既有形态：textarea 在 DOM 但隐藏，直到点「其他…」
  const input = page.locator('.option-custom-input');
  await expect(input).toHaveCount(1);
  await expect(input).toBeHidden();
  await expect(page.locator('.option-confirm')).toBeDisabled();

  const other = page.locator('.option-btn').last();
  await other.click(); // 「其他…」
  await expect(input).toBeVisible();
  await input.fill('custom answer');
  await expect(page.locator('.option-confirm')).toBeEnabled();
  await page.locator('.option-confirm').click();
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  expect((await answerFrames(page))[0].answers).toEqual(['custom answer']);
  await context.close();
});

// ============================================================
// T7 回放双生：回放卡与实时卡一致地无 textarea（echo 缺失 = 既有 gap，如实记录）
// ============================================================
test('T7 回放卡：无 textarea（与实时卡一致）；echo 缺失为既有 gap（不在本批）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(async () => {
    const fx = await (await fetch('./project-create-panel-fixture.json')).json();
    await window.__history(fx.items, '/Users/dev/scratch/replayed');
  });
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  const box = page.locator('.option-box');
  await expect(box).toHaveCount(1);
  await expect(box.locator('.option-custom-input')).toHaveCount(0);
  await expect(box.locator('.option-answer')).toContainText('/Users/dev/scratch/replayed');
  // 既有 gap（如实记录，不在本批）：回放卡重建了 .ws-pick-echo 容器但无填充点
  // （markAnsweredPick 只认 .option-btn）⇒ echo 恒隐藏、无 '-> <path>' 回显。
  await expect(box.locator('.ws-pick-echo')).toHaveCount(1);
  await expect(box.locator('.ws-pick-echo')).toBeHidden();
  await box.scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(SHOT_DIR, 'replay-dark.png'), fullPage: true });
  await context.close();
});

// ============================================================
// T8 兜底态截图 + 亮色主题（正常/兜底/回放 × 亮暗）
// ============================================================
test('T8 三帧截图（正常/兜底/回放）× 亮暗主题落盘', async ({ browser }) => {
  for (const scheme of ['light', 'dark']) {
    const { context, page } = await newPage(browser, scheme);
    // 正常态
    await page.evaluate(() => window.__panel());
    await page.waitForTimeout(120);
    await expect(page.locator('.option-custom-input')).toHaveCount(0);
    await page.screenshot({ path: path.join(SHOT_DIR, `normal-${scheme}.png`), fullPage: true });
    // 兜底态（import 拒绝 ⇒ 揭示）
    await page.route('**/workspacePicker.js', (r) => r.abort());
    await page.evaluate(() => window.__panel());
    await page.waitForTimeout(120);
    await page.locator('.ws-pick-target').click();
    await expect(page.locator('.option-custom-input')).toBeVisible({ timeout: 10000 });
    await page.screenshot({ path: path.join(SHOT_DIR, `fallback-${scheme}.png`), fullPage: true });
    await context.close();
  }
});

// ============================================================
// T9 取消 → '__cancelled__' 哨兵（后端据回搁置消息，不创建）
// ============================================================
test('T9 取消键：answers = __cancelled__ 哨兵（后端回搁置消息，不创建项目）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser, 'dark');
  await page.evaluate(() => window.__panel());
  await page.waitForTimeout(100);
  expect(pageErrors).toEqual([]);

  await page.locator('.option-cancel').click();
  await expect.poll(async () => (await answerFrames(page)).length).toBe(1);
  expect((await answerFrames(page))[0]).toEqual({
    type: 'askUserAnswer',
    sessionId: 'harness-session',
    answers: ['__cancelled__'],
    requestId: 'pc-e2e-01',
  });
  await context.close();
});
