// onboarding-flow-skip.spec.mjs — Onboarding 整流程「跳过」批前端契约验收。
//
// 作者令 2026-10-30（Onboarding 跳过批）：用户可随时跳过进入主界面；
// 跳过状态持久化（`skipped` marker），不再反复弹；设置里保留重新进入入口。
//
// 前端契约 (onboarding.js)：
//   - 引导运行全程（greetings → 题卡 → writing ritual），聊天视口顶部常驻
//     [data-skip-flow] 悬浮钮（文案 = ob2.flow.skip，zh 跳过 / en Skip）；
//   - 点击 ⇒ flowAbort 单通道解开当前停泊的步骤（greeting / askCard / ritual
//     全部 race 同一 abort promise），并 await setOnboardingState('skipped')
//     （后端对 skipped 无 probe 硬门，OnboardingService.setState 只闸 done）；
//   - 写入被接受 ⇒ 按钮移除（runFlow 的 run-scoped finally）+ 用户回显气泡；
//   - 写入被拒 ⇒ notice + [data-retry-skip] 重试钮（不静默错过 —— 静默会让
//     下次冷启动整份问卷重跑）；
//   - 持久化：skipped 落账后冷启动不再触发引导（initOnboarding S0 早退）。
//
// Run: playwright test tests/onboarding-flow-skip.spec.mjs

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

const ROOT_SID = 'ob-flow-skip-root-session';

/**
 * Boot the app against a mocked WS. Returns the Node-side frame log so tests
 * can assert the exact frames the frontend sent.
 *
 * @param {import('@playwright/test').Page} page
 * @param {{ onboarding?: string, refuseSkip?: boolean }} opts
 */
async function bootApp(page, opts = {}) {
  const onboarding = opts.onboarding ?? 'pending';
  const refuseSkip = !!opts.refuseSkip;
  /** @type {any[]} frames the frontend sent, in order */
  const frames = [];

  // 打字动画必须真实发生：显式钉住 no-preference（typeInto 按
  // prefers-reduced-motion 分流，reduce 会整段瞬发）。
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
    // 帧序纪律（同 onboarding-skip-typing）：sessionList 先行，configData 押后
    // 到 getHistory 应答完 —— 引导气泡只落「已落定」的 chat DOM。
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding,
    }));
    let historySettled = false;
    let configPending = false;
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      frames.push(msg);
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
      } else if (msg.type === 'setOnboardingState') {
        if (refuseSkip) {
          ws.send(JSON.stringify({ type: 'error', message: 'disk full (mock refusal)' }));
        } else {
          ws.send(JSON.stringify({ type: 'onboardingStateSet', state: msg.state }));
        }
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  return { frames };
}

test('flow-skip: 运行全程常驻「跳过」钮；点击 ⇒ 落 skipped 帧 + 按钮移除 + 无题卡', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const { frames } = await bootApp(page);

  // 按钮在 runFlow 起步即挂载（greeting 打字期间就已可见），文案 = 跳过
  const skipBtn = page.locator('[data-skip-flow]');
  await expect(skipBtn).toBeVisible({ timeout: 10000 });
  await expect(skipBtn).toHaveText('跳过');

  await skipBtn.click();

  // 1) 持久化帧：setOnboardingState { state: 'skipped' }（mock 已回 onboardingStateSet）
  await expect.poll(() => frames.some((f) => f.type === 'setOnboardingState' && f.state === 'skipped'))
    .toBe(true);
  // 2) 按钮随 runFlow 的 run-scoped finally 移除，无残骸
  await expect(skipBtn).toHaveCount(0);
  // 3) 用户回显气泡（skipEcho）落位
  await expect(page.locator('#chat')).toContainText('新手引导先到这里');
  // 4) 流程真正解开：题卡永不到来（abort 单通道，非仅隐藏按钮）
  await expect(page.locator('.ask-card')).toHaveCount(0);
  // 5) 收尾态无未处理异常
  expect(pageErrors).toEqual([]);
});

test('flow-skip: skipped 冷启动不再触发引导（不反复弹，S0 持久化回归）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page, { onboarding: 'skipped' });

  // 给 boot 链充分时间：sessionList → getHistory → configData(skipped)。
  // S0 早退 ⇒ 零引导气泡、零跳过钮、零题卡。
  await page.waitForTimeout(1500);
  await expect(page.locator('.ob-sim-bubble')).toHaveCount(0);
  await expect(page.locator('[data-skip-flow]')).toHaveCount(0);
  await expect(page.locator('.ask-card')).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('flow-skip: skipped 写入被拒 ⇒ notice + 重试钮（不静默错过）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const { frames } = await bootApp(page, { refuseSkip: true });

  const skipBtn = page.locator('[data-skip-flow]');
  await expect(skipBtn).toBeVisible({ timeout: 10000 });
  await skipBtn.click();

  // 拒绝被命名呈现 + 重试钮出现（data-retry-skip）
  const retry = page.locator('[data-retry-skip]');
  await expect(retry).toBeVisible();
  await expect(page.locator('.row.notice').last()).toContainText('跳过状态保存失败');

  // 重试走成功路径（第二次点击时 mock 仍拒绝 —— 此处只验证重试会再发一帧；
  // 成功态覆盖见主路径用例）
  await retry.click();
  await expect.poll(() => frames.filter((f) => f.type === 'setOnboardingState' && f.state === 'skipped').length)
    .toBe(2);
  expect(pageErrors).toEqual([]);
});
