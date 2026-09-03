// askuser-answer-source.spec.mjs — issue #43: AskUser pending 期间到达的
// agent 侧注入消息（delegate 结果等）被误消费为答案。
//
// 真实事故（2026-09-03 11:47-12:03，截图 + ui.json [763]-[766] + gateway log
// 12:04:05）：两问 pending 期间两个 delegate 结果落进历史 → 刷新/切回后
// restore 把注入气泡当已录答案（askUserAnswerText 未排除 injected）→
// markAnsweredPick 按 '\n' 拆进两个答案槽（Q1 = 汇报首行 token、
// Q2 = "任务完成。"）并锁卡；同时 main.js 的 pending 判定要求 askUser 是
// 字面最后一条历史消息，被注入气泡打败 → 交互卡片不重建 → 用户无卡可答 →
// 被迫走输入框直通（1 槽答案）→ Q2 "(skipped)"。
//
// 修复面（前端 restore/答案来源判定）：
//  1. persistence.js askUserAnswerText — injected:true 的相邻 user 消息不是
//     已录答案（只有用户发起的非 injected 消息才算）。
//  2. persistence.js findLastRealMessage + main.js 调用 — pending 判定回看
//     时跳过 injected 气泡，pending 卡片重建为可交互。
//
// Drives the REAL render/restore modules (persistence.js / chat.js) in a
// static harness page (tests/fixtures/askuser-answer-source/harness.html)
// against REAL incident-shaped fixtures — no live Nebflow instance involved,
// never the 8080 host. Two throwaway static file servers are spawned by this
// spec and killed in afterAll:
//   - baseline: src/main/resources/web extracted from git HEAD^ (pre-fix code)
//   - fixed:    the working tree (post-fix code)
//
// Run: npx playwright test tests/askuser-answer-source.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn, execSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_DIR = path.join(REPO_ROOT, 'tests', 'fixtures', 'askuser-answer-source');
const SHOT_DIR = path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');
const HARNESS_PATH = '/tests/fixtures/askuser-answer-source/harness.html';

// Baseline ref for the polluted-state comparison: the commit BEFORE the
// answer-source fix (3c551f76). Pinned so the degraded baseline stays stable
// as HEAD advances; override with ASKUSER_SRC_BASELINE_REF to point at
// another pre-fix revision.
const BASELINE_REF = process.env.ASKUSER_SRC_BASELINE_REF ?? 'bc95dec1';

let baselineRoot;
let baselinePort;
let fixedPort;
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

function startStaticServer(port, dir) {
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
  // Baseline tree: the web resources exactly as they were before the fix.
  baselineRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nb-askuser-baseline-'));
  execSync(`git archive ${BASELINE_REF} src/main/resources/web | tar -x -C "${baselineRoot}"`, { cwd: REPO_ROOT });
  // Harness + fixtures must be reachable from BOTH roots (same-origin module imports).
  const baseHarnessDir = path.join(baselineRoot, 'tests', 'fixtures', 'askuser-answer-source');
  fs.mkdirSync(baseHarnessDir, { recursive: true });
  for (const f of ['harness.html', 'incident-fixture.json', 'answered-fixture.json']) {
    fs.copyFileSync(path.join(HARNESS_DIR, f), path.join(baseHarnessDir, f));
  }

  baselinePort = await freePort();
  fixedPort = await freePort();
  servers.push(startStaticServer(baselinePort, baselineRoot));
  servers.push(startStaticServer(fixedPort, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${baselinePort}${HARNESS_PATH}`);
  await waitUntilUp(`http://127.0.0.1:${fixedPort}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
  if (baselineRoot) fs.rmSync(baselineRoot, { recursive: true, force: true });
});

async function newPage(browser, port) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

// ============================================================
// (a) 验红基线：pre-fix 代码上复现真实缺陷 —— 注入气泡被当答案
// ============================================================
test.describe('issue #43 验红基线 — pre-fix restore 把 delegate 气泡当卡片答案', () => {
  test('事故 fixture 重放：答案槽被 delegate 汇报片段污染 + 卡片锁定 + 交互卡片不重建', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, baselinePort);
    const replayed = await page.evaluate(() => window.__replay('incident-fixture'));
    await page.waitForTimeout(400);
    expect(pageErrors).toEqual([]);

    // 旧 pending 判定：askUser 不是字面最后一条（后面排着注入气泡）→ 不重建
    expect(replayed.isAskUserPending).toBe(false);

    // 唯一的 option-box 是 renderAskUserHistory 锁死的旧卡 —— 无交互卡片
    await expect(page.locator('.option-box')).toHaveCount(1);

    // 答案槽被污染：markAnsweredPick 按 '\n' 拆注入气泡文本
    // Q1 槽 = 汇报首行 token、Q2 槽 = "任务完成。"（事故截图逐字形态）
    const inputs = page.locator('.option-custom-input');
    await expect(inputs).toHaveCount(2);
    await expect(inputs.nth(0)).toHaveValue('"分层压缩提示词设计稿":');
    await expect(inputs.nth(1)).toHaveValue('任务完成。');
    // Other 按钮被选中（片段不匹配任何预设选项）
    const picked = page.locator('.option-btn.picked');
    await expect(picked).toHaveCount(2);
    // 卡片锁定（渲染为已答终态）+ 答案回显行
    const btnCount = await page.locator('.option-btn').count();
    for (let i = 0; i < btnCount; i++) await expect(page.locator('.option-btn').nth(i)).toBeDisabled();
    const ansDiv = page.locator('.option-answer');
    await expect(ansDiv).toHaveCount(1);
    expect(await ansDiv.textContent()).toContain('分层压缩提示词设计稿');

    await page.screenshot({ path: path.join(SHOT_DIR, '20260903-askuser-answer-source-1-polluted-baseline.png'), fullPage: true });
    await context.close();
  });
});

// ============================================================
// (b) 修复后：pending 卡片不被 agent 消息填充，重建为可交互
// ============================================================
test.describe('issue #43 修复后 — 注入气泡排队可见，pending 卡片保持可答', () => {
  test('事故 fixture 重放：答案槽干净、pending 卡片重建为可交互、注入气泡照常渲染', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const replayed = await page.evaluate(() => window.__replay('incident-fixture'));
    await page.waitForTimeout(400);
    expect(pageErrors).toEqual([]);

    // 新 pending 判定（findLastRealMessage）：跳过 injected 气泡 → askUser 仍 pending
    expect(replayed.isAskUserPending).toBe(true);

    // 注入气泡照常渲染（排队消息不丢、可见）：两条 delegate 汇报
    await expect(page.locator('.bubble.injected')).toHaveCount(2);
    await expect(page.locator('.bubble.injected').nth(0)).toContainText('分层压缩提示词设计稿');
    await expect(page.locator('.bubble.injected').nth(1)).toContainText('v3.2：纵排层级');

    // 交互卡片重建：唯一 option-box、按钮可用、确认键可见、无答案回显
    await expect(page.locator('.option-box')).toHaveCount(1);
    await expect(page.locator('.option-answer')).toHaveCount(0);
    const btnCount = await page.locator('.option-btn').count();
    for (let i = 0; i < btnCount; i++) await expect(page.locator('.option-btn').nth(i)).toBeEnabled();
    await expect(page.locator('.option-confirm')).toBeVisible();

    // 答案槽干净：textarea 全空、无选中按钮（agent 消息未进答案通道）
    const inputs = page.locator('.option-custom-input');
    await expect(inputs).toHaveCount(2);
    await expect(inputs.nth(0)).toHaveValue('');
    await expect(inputs.nth(1)).toHaveValue('');
    await expect(page.locator('.option-btn.picked')).toHaveCount(0);

    await page.screenshot({ path: path.join(SHOT_DIR, '20260903-askuser-answer-source-2-fixed-pending.png'), fullPage: true });
    await context.close();
  });

  test('findLastRealMessage（main.js 同源实现）：incident → askUser；answered → 尾部 ai；空历史 → null', async ({ browser }) => {
    const { context, page } = await newPage(browser, fixedPort);
    const incidentLast = await page.evaluate(() => window.__pendingOf('incident-fixture'));
    expect(incidentLast.type).toBe('askUser');
    const answeredLast = await page.evaluate(() => window.__pendingOf('answered-fixture'));
    expect(answeredLast.type).toBe('ai');
    const emptyLast = await page.evaluate(() => window.__persistence.findLastRealMessage([]));
    expect(emptyLast).toBe(null);
    await context.close();
  });
});

// ============================================================
// (c) 用户路径回归：真实已录答案的还原 + 活动卡片作答不受影响
// ============================================================
test.describe('issue #43 用户路径回归 — 已录答案照常还原，活动卡片照常作答', () => {
  test('已录答案 fixture 重放：卡片按用户真实回答锁定，槽位逐字一致', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    const replayed = await page.evaluate(() => window.__replay('answered-fixture'));
    await page.waitForTimeout(400);
    expect(pageErrors).toEqual([]);

    // 尾部是非 injected 的 ai 消息 → 不再 pending，不重建交互卡片
    expect(replayed.isAskUserPending).toBe(false);

    // 已答锁定终态：预设选项被选中、答案回显与用户输入逐字一致
    await expect(page.locator('.option-box')).toHaveCount(1);
    await expect(page.locator('.option-answer')).toHaveText('-> 部分调整, 重启后再启动');
    await expect(page.locator('.option-btn.picked')).toHaveCount(2);
    const btnCount = await page.locator('.option-btn').count();
    for (let i = 0; i < btnCount; i++) await expect(page.locator('.option-btn').nth(i)).toBeDisabled();
    await context.close();
  });

  test('活动卡片作答（live WS 路径）：选项作答 → 确认 → 出站帧 answers 完整、卡片锁定', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, fixedPort);
    await page.evaluate(() => window.__live('incident-fixture'));
    await page.waitForTimeout(200);
    expect(pageErrors).toEqual([]);

    // 活动路径：交互卡片 + 两条注入气泡并存
    await expect(page.locator('.option-box')).toHaveCount(1);

    // Q1 选预设「部分调整」、Q2 选预设「重启后再启动」→ 确认
    const box = page.locator('.option-box');
    await box.locator('.option-btn[data-label="部分调整"]').first().click();
    await box.locator('.option-btn[data-label="重启后再启动"]').first().click();
    await box.locator('.option-confirm').click();
    await page.waitForTimeout(200);

    // 确认帧出站（真实 ws stub 捕获），answers 双槽完整 —— 用户发起的回答未被破坏
    const frame = await page.evaluate(() => window.__captured.find(m => m.type === 'askUserAnswer'));
    expect(frame).toBeTruthy();
    expect(frame.answers).toEqual(['部分调整', '重启后再启动']);

    // 活动确认终态：锁定 + 回显
    await expect(page.locator('.option-answer')).toHaveText('-> 部分调整, 重启后再启动');
    const btnCount = await page.locator('.option-btn').count();
    for (let i = 0; i < btnCount; i++) await expect(page.locator('.option-btn').nth(i)).toBeDisabled();
    await context.close();
  });
});
