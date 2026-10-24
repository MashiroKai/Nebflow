// onboarding-skip-typing.spec.mjs — 「跳过打字」按钮生命周期验收（onboarding-ux 批点 4）。
//
// 作者 2026-10-05 原话：「伪流式输出结束后，跳过打字的按钮应该消失。」
//
// 前端契约 (onboarding.js simBubble / typeInto)：
//   - 流式中：气泡右上角有 [data-skip-typing] 按钮（reduced-motion 除外）；
//   - 打字自然完成：typeInto 的 onDone 触发（doneFired 单次闸）⇒ 按钮从 DOM 移除；
//   - 点击跳过：ctrl.finish ⇒ 同一 onDone 通道 ⇒ 按钮移除；
//   - Esc 跳过：activeSim.finish ⇒ 同一通道 ⇒ 按钮移除。
// 即按钮只存在于「正在打字」窗口期，结束后不留残骸。
//
// 作用域纪律：断言一律钉在本气泡内（.ob-sim-bubble nth）——流是逐气泡接续的，
// 下一气泡在其完成瞬间就开始打字并带自己的光标/按钮，全局断言必然误报。
//
// Run: playwright test tests/onboarding-skip-typing.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'ob-skip-root-session';

async function bootApp(page) {
  // 打字动画必须真实发生：显式钉住 no-preference（typeInto 按
  // prefers-reduced-motion 分流，reduce 会整段瞬发、根本不建按钮）。
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    // 帧序纪律：sessionList 先行（视图激活 + 初始 history 链），configData（⇒
    // onboarding 启动）押后到 getHistory 应答完 —— 引导气泡只落「已落定」的
    // chat DOM；若 configData 抢在初始 history 渲染前，气泡会被整段 DOM 重置
    // 连带清掉（实测的偶发起不来）。listSessions 不重放（重放会再走会话重置链）。
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'pending',
    }));
    let historySettled = false;
    let configPending = false;
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
        historySettled = true;
        if (configPending) { configPending = false; sendConfig(); }
      } else if (msg.type === 'getConfig') {
        if (historySettled) sendConfig(); else configPending = true;
      } else if (msg.type === 'setOnboardingAnswers') {
        ws.send(JSON.stringify({ type: 'onboardingAnswersSaved', count: 1 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
}

/**
 * Fast-forward CSS animations (headless Chromium may stall the animation clock
 * for idle pages, leaving entry animations stuck at their opacity:0 first
 * frame — the click-actionability "stable" check then never passes and the
 * click times out at the test budget. Same fix as dialog-unify.spec.mjs;
 * 2026-10-24 CI 存量红分诊（run 40063846949 + 本地矩阵同红实证）.
 */
async function settleAnimations(page) {
  await page.evaluate(() => {
    try { document.getAnimations().forEach(a => { try { a.finish(); } catch { /* not finishable */ } }); } catch { /* API absent */ }
  });
}

test('skip-typing: 自然完成后按钮自动消失（伪流式输出结束 ⇒ 无残骸）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 第一条问候气泡（greet1，短句）正在打字 ⇒ 按钮存在（流式中可见）
  const bubble1 = page.locator('.ob-sim-bubble').nth(0);
  const skip1 = bubble1.locator('[data-skip-typing]');
  await expect(skip1).toBeVisible({ timeout: 10000 });

  // 不点击，等打字自然完成 ⇒ 按钮自己消失（作者点 4 的正身），光标一并收走
  await skip1.waitFor({ state: 'detached', timeout: 20000 });
  await expect(bubble1.locator('.ob-cursor')).toHaveCount(0);
  const text = await bubble1.innerText();
  expect(text.trim().length, '自然完成后全文落位').toBeGreaterThan(0);
  expect(pageErrors).toEqual([]);
});

test('skip-typing: 点击跳过 ⇒ 文本瞬完 + 按钮移除；Esc 同通道', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 气泡 1：点跳过快进
  const bubble1 = page.locator('.ob-sim-bubble').nth(0);
  const skip1 = bubble1.locator('[data-skip-typing]');
  await expect(skip1).toBeVisible({ timeout: 10000 });
  await settleAnimations(page);
  await skip1.click();
  await skip1.waitFor({ state: 'detached', timeout: 10000 });

  // 气泡 2 出现 ⇒ 流式中按钮再次存在（存在性 = 打字窗口期的标志）；点击后
  // 按钮移除、光标收走（作用域钉在气泡 2 —— 气泡 3 此刻已在打字）
  const bubble2 = page.locator('.ob-sim-bubble').nth(1);
  const skip2 = bubble2.locator('[data-skip-typing]');
  await expect(skip2).toBeVisible({ timeout: 10000 });
  await settleAnimations(page);
  await skip2.click();
  await skip2.waitFor({ state: 'detached', timeout: 10000 });
  await expect(bubble2.locator('.ob-cursor')).toHaveCount(0);

  // 气泡 3（name 题的 intro）：Esc 走 activeSim.finish，同一 onDone 通道
  const bubble3 = page.locator('.ob-sim-bubble').nth(2);
  const skip3 = bubble3.locator('[data-skip-typing]');
  await expect(skip3).toBeVisible({ timeout: 10000 });
  await page.keyboard.press('Escape');
  await skip3.waitFor({ state: 'detached', timeout: 10000 });

  expect(pageErrors).toEqual([]);
});
