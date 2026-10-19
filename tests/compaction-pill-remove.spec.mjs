// compaction-pill-remove.spec.mjs — 作者令 2026-10-09 10:23 ①「『压缩中』可见
// 指示列为拆除项」验收（compaction-pill-remove 批）。
//
// 拆面 = UI 呈现腿全拆（legacy / §E-1 回落 / 手动 / 非根全覆盖）；CompactStart
// 事件流保留（仍有功能消费者：input.js isBusy 输入闸、main.js 队列 drain 闸、
// 后台会话跟踪、WebSocketRoutes 历史落盘）。完成/失败卡零改动（elapsed 后缀
// 改由 main.js 盖 view.compactStartTs 供数）。
//
// 三段断言：
//   S-A 静态钉：起卡渲染函数/文案键/spinner CSS/侧栏压缩 spinner 元素全部不在；
//       事件流保留面（setCompacting / saveMsg / TERMINAL_MSG_TYPES / 后端发射点）
//       全部仍在。
//   S-B 重放腿（刷新路径）：chat.compacting 起条目被抑制、恰一张 done 卡、
//       零 active 胶囊、零 spinner、零 error 回落卡。
//   S-C live 腿（WS 帧路径）：compactStart 零起卡（仅盖时间戳），compactComplete
//       照出 done 卡且带「用时」后缀（完成态零改动的活证）。
//
// 隔离：静态文件服务器指向本树（never 8080 宿主）；复用
// tests/fixtures/history-replay/harness.html 的 __replay/__live 驱动与 fixture。
// Run: npx playwright test tests/compaction-pill-remove.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const WEB = path.join(REPO_ROOT, 'src', 'main', 'resources', 'web');
const HARNESS_DIR = path.join(REPO_ROOT, 'tests', 'fixtures', 'history-replay');
const SHOT_DIR = path.join(REPO_ROOT, 'evidence', '20261019_101043_compaction-pill-remove');
const HARNESS_PATH = '/tests/fixtures/history-replay/harness.html';

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
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

// ---- S-A: 静态钉（源码直读） ----
test.describe('S-A static pins', () => {
  test('in-progress pill faces dismantled; event-flow consumers retained', () => {
    const read = (...p) => fs.readFileSync(path.join(WEB, ...p), 'utf8');
    const chat = read('js', 'chat.js');
    const main = read('js', 'main.js');
    const sidebar = read('js', 'sidebar.js');
    const chatCss = read('css', 'chat.css');
    const sidebarCss = read('css', 'sidebar.css');
    const zh = read('js', 'locales', 'zh-CN.js');
    const en = read('js', 'locales', 'en.js');
    const wsjs = read('js', 'ws.js');

    // 拆除面（全部消失）
    expect(chat).not.toContain('renderCompactStartCard');
    expect(chat).not.toContain("chat.compactingCard");
    expect(chat).not.toContain('compact-card-spinner');
    expect(chat).not.toContain("data-state=\"active\"");
    expect(chatCss).not.toContain('.compact-card-spinner');
    expect(chatCss).not.toContain('[data-state="active"]');
    expect(main).not.toContain('renderCompactStartCard');
    expect(zh).not.toContain('chat.compactingCard');
    expect(en).not.toContain('chat.compactingCard');
    expect(sidebar).not.toContain('status-compact-spinner');
    expect(sidebarCss).not.toContain('status-compact-spinner');
    expect(sidebarCss).not.toContain('compactPulse');
    expect(sidebarCss).not.toContain('.session-status.compacting');
    expect(sidebarCss).not.toContain('.folder-status.compacting');
    expect(sidebar).not.toContain("return 'compacting'");

    // 事件流保留面（消费闸照旧）
    expect(main).toContain("onMessage('compactStart'");
    expect(main).toContain('setCompacting(sid, true)');
    expect(main).toContain('view.compactStartTs = Date.now()');
    expect(main).toContain("t('chat.compacting')");
    expect(wsjs).toContain("'compactStart', 'compactComplete', 'compactFailed'");
    expect(zh).toContain("'chat.compacting'");
    // 完成态零改动
    expect(chat).toContain('renderCompactDoneCard');
    expect(chat).toContain('renderCompactFailCard');
    expect(chat).toContain('compactElapsed');
  });
});

// ---- S-B + S-C: 重放腿 / live 腿（真实模块，真实刷新路径） ----
test.describe('S-B/S-C no start card, done card intact', () => {
  test('replay: start entry suppressed, one done card, zero pill/spinner', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__replay());
    await page.waitForTimeout(400);
    expect(pageErrors).toEqual([]);

    // 起卡 / 胶囊 / spinner 全灭
    await expect(page.locator('.compact-card[data-state="active"]')).toHaveCount(0);
    await expect(page.locator('.compact-card-spinner')).toHaveCount(0);
    // 完成卡照出（历史记录保留）
    await expect(page.locator('.compact-card')).toHaveCount(1);
    const done = page.locator('.compact-card[data-state="done"]');
    await expect(done).toHaveCount(1);
    await expect(done.locator('.compact-card-icon.ok svg')).toHaveCount(1);
    // 重放无「用时」后缀（历史条目无时间戳——与改前语义一致）
    const label = await done.locator('.compact-card-label').textContent();
    expect(label.startsWith('上下文已压缩：184 → 1 条消息')).toBe(true);
    expect(label).not.toContain('用时');

    await page.screenshot({ path: path.join(SHOT_DIR, 'replay-no-pill-done-card.png'), fullPage: true });
    await context.close();
  });

  test('live: compactStart renders nothing, compactComplete appends done card with elapsed', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__live());
    await page.waitForTimeout(300);
    expect(pageErrors).toEqual([]);

    // 起卡全灭（回落腿 / legacy / 手动同面——渲染点已不存在）
    await expect(page.locator('.compact-card[data-state="active"]')).toHaveCount(0);
    await expect(page.locator('.compact-card-spinner')).toHaveCount(0);
    // 恰一张完成卡
    await expect(page.locator('.compact-card')).toHaveCount(1);
    const done = page.locator('.compact-card[data-state="done"]');
    await expect(done.locator('.compact-card-icon.ok svg')).toHaveCount(1);
    // 「用时」后缀照出（view.compactStartTs 供数——完成态 UI 零改动）
    const label = await done.locator('.compact-card-label').textContent();
    expect(label).toMatch(/上下文已压缩：184 → 1 条消息.*· 用时 \d+s/);

    await page.screenshot({ path: path.join(SHOT_DIR, 'live-no-pill-done-card.png'), fullPage: true });
    await context.close();
  });
});
