// badge-injected-turn.spec.mjs — toolline-detail 批追加令（dispatcher
// 2026-10-06 16:54，作者截图令）回归：注入腿（device-mail / node-message /
// 定时事件）开轮必须重建 badge 分组——新 turn 的行线插在注入泡之后、终端
// 翻转出新 header，不再叠上旧 badge；用户消息路径回归不伤；注入泡展示形态
// 零改动（蓝泡原样，本 spec 只读它的 DOM 形态作锚点）。
//
// 根因（修复前）：workline.js turnAnchor 只认「非注入 user 行」——注入轮的
// 行线插在**上一个** user 轮的 user 行之后，正好落在旧 turn 的 chrome 区
// （作者截图：新工具行与旧 badge 位置重叠、注入轮看似无新 badge）。终端
// 切面本由 #403 closure cursor 正确管辖（turnScope 不动），唯一洞在行线
// 插入点。修法 = turnAnchor 收注入行（任何面向用户的开轮事件统一重开分组）。
//
// Drives the REAL render modules through tests/fixtures/toolline-detail/
// harness.html over a throwaway static server (never the 8080 host).
//
// Run: npx playwright test tests/badge-injected-turn.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/toolline-detail/harness.html';
// 取证截图目录（默认进仓内 .nebflow 证据位；NB_KEEP_TEXT_SHOT_DIR 可改道）。
const SHOT_DIR = process.env.NB_KEEP_TEXT_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261006_toolline-detail-x1', 'badge-spec');

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
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
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
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const BASH_INPUT = '{"command":"git status"}';

test.describe('injected turn opens its OWN badge group (16:54 追加令)', () => {
  test('idle injection → tool run: the line anchors AFTER the injected row, not on the old turn chrome', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // ── turn 1（用户消息触发，已闭合）───────────────────────────────
    await page.evaluate(() => window.__tdUser('查一下仓库状态'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"git stat'));
    await page.evaluate(() => window.__tdArg('Bash', 'us"}'));
    await page.evaluate(() => window.__tdExecStart('Bash\n  (git status)'));
    await page.evaluate(() => window.__tdDone('Bash\n  (git status)', 'No output', 'ok', false, '{"command":"git status"}'));
    await page.evaluate(() => window.__tdFinishTurn(1200, 'test-model'));
    const c1 = await page.evaluate(() => window.__tdCensus());
    expect(c1.headerCount).toBe(1);
    expect(c1.headerMeta[0].prevIsUser).toBe(true);             // header 紧贴 user 行后
    expect(pageErrors).toEqual([]);

    // ── 注入腿开轮（修复点）────────────────────────────────────────
    // 蓝泡自身形态零改动：.row.user + .bubble.injected 原样出现。
    await page.evaluate(() => window.__tdInject('【定时】每日站会提醒', 'schedule'));
    const injCls = await page.evaluate(() => {
      const el = Array.from(document.querySelectorAll('#chat .row'))
        .find(r => r.querySelector('.bubble.injected'));
      return el ? el.className : null;
    });
    expect(injCls).toContain('user');
    expect(injCls).toContain('row');

    // 注入轮的工具开始 → 行线必须插在注入泡之后（旧代码插在旧 user 行后
    // = 叠上旧 badge 区）。
    await page.evaluate(() => window.__tdCallStart('Bash'));
    const c2 = await page.evaluate(() => window.__tdCensus());
    expect(c2.linePresent).toBe(true);
    expect(c2.linePrevIsInjected).toBe(true);                   // ← THE FIX
    // 不重叠：行线 top 在注入泡之下、旧 header 之上没有第三个 chrome。
    const tops = c2.rows.map(r => r.top);
    expect(new Set(tops).size).toBe(tops.length);              // 无两行同位

    // 注入轮闭合 → 新 badge 重建（groupCount 2），位置 = 注入泡之后。
    await page.evaluate(() => window.__tdDone('Bash\n  (git status)', 'No output', 'ok', false, '{"command":"git status"}'));
    await page.evaluate(() => window.__tdFinishTurn(1500, 'test-model', '注入轮的最终回复。', '✻ 注入轮 2 秒'));
    const c3 = await page.evaluate(() => window.__tdCensus());
    expect(c3.headerCount).toBe(2);
    expect(c3.headerMeta[1].prevIsInjected).toBe(true);         // 新 header 贴注入泡
    expect(c3.headerMeta[1].top).toBeGreaterThan(c3.headerMeta[0].top);
    // 各归各 turn：header2 的 scope 只含注入轮行；header1 计数不变。
    expect(c3.headerMeta[0].tuckedScope.tools).toBe(1);
    expect(c3.headerMeta[1].tuckedScope.tools).toBe(1);
    // 旧 header 文本不被注入轮重写（badge 重建 ≠ 旧 badge 吞并）。
    expect(c3.headerMeta[0].text).not.toBe(c3.headerMeta[1].text);
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 'injected-turn-own-badge.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);

    await context.close();
  });

  test('mid-turn continuation injection does NOT re-anchor the live line; the queued turn gets its own badge (续聊注入)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // turn 1 运行中 → 续聊注入泡落在行线之下（不重锚行线）。
    await page.evaluate(() => window.__tdUser('跑一个长任务'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"sleep 30"}'));
    await page.evaluate(() => window.__tdExecStart('Bash\n  (sleep 30)'));
    const before = await page.evaluate(() => window.__tdCensus());
    expect(before.linePresent).toBe(true);
    expect(before.linePrevIsUser).toBe(true);                   // 贴 user 行（本 turn 的开轮行）

    await page.evaluate(() => window.__tdInject('【节点消息】swift-dev 汇报：子任务已启动', 'nodeMessage'));
    const after = await page.evaluate(() => window.__tdCensus());
    expect(after.linePresent).toBe(true);
    expect(after.linePrevIsUser).toBe(true);                    // 行线未动（isConnected 短路）
    expect(after.linePrevIsInjected).toBe(false);

    // turn 1 闭合；排队腿随后开 turn 2 → 自己的行线贴注入泡、自己的 badge。
    await page.evaluate(() => window.__tdDone('Bash\n  (sleep 30)', 'No output', 'ok', false, '{"command":"sleep 30"}'));
    await page.evaluate(() => window.__tdFinishTurn(900, 'test-model', '第一段完成。'));
    await page.evaluate(() => window.__tdInject('【节点消息】续聊：继续第二段', 'nodeMessage'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    const c2 = await page.evaluate(() => window.__tdCensus());
    expect(c2.linePrevIsInjected).toBe(true);                   // 新轮行线贴自己的开轮泡
    await page.evaluate(() => window.__tdDone('Bash\n  (git log)', 'No output', 'ok', false, '{"command":"git log"}'));
    await page.evaluate(() => window.__tdFinishTurn(700, 'test-model', '第二段完成。'));
    const c3 = await page.evaluate(() => window.__tdCensus());
    expect(c3.headerCount).toBe(2);
    expect(c3.headerMeta[1].prevIsInjected).toBe(true);
    expect(pageErrors).toEqual([]);

    const shot = path.join(SHOT_DIR, 'continuation-injection-queued-turn.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);

    await context.close();
  });

  test('user-message path regression: header still lands right after the user row (原路径不伤)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__tdUser('普通用户消息'));
    await page.evaluate(() => window.__tdCallStart('Read'));
    await page.evaluate(() => window.__tdArg('Read', '{"file_path":"/tmp/a.md"}'));
    await page.evaluate(() => window.__tdExecStart('Read\n  ("/tmp/a.md")'));
    await page.evaluate(() => window.__tdDone('Read\n  ("/tmp/a.md")', '12 lines', 'body', false, '{"file_path":"/tmp/a.md"}'));
    await page.evaluate(() => window.__tdFinishTurn(2000, 'test-model'));
    const c = await page.evaluate(() => window.__tdCensus());
    expect(c.headerCount).toBe(1);
    expect(c.headerMeta[0].prevIsUser).toBe(true);
    expect(c.headerMeta[0].prevIsInjected).toBe(false);
    expect(c.headerMeta[0].tuckedScope.tools).toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
