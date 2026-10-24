// onboarding-name-header.spec.mjs — 「起名 ⇒ Header 标题」写回链验收（onboarding-ux 批点 2）。
//
// 作者 2026-10-05 原话：「起名是会影响Header的标题的。目前是Nebula3」
//
// 链路（本批修复后）：
//   onboarding 起名卡作答 X ⇒ record('name') ⇒ renameEverywhere(X)：
//     · state.rootDisplayName = X（in-session 覆盖）
//     · dispatch 'nebflow-root-name-change'
//   ⇒ sidebar.js updateHeaderSessionName 对根 agent 走单一读点 rootName.rootDisplayName()
//   ⇒ #session-name 显示 X（旧面只读 agentsData，起名后无任何渲染路径会刷新它）。
//
// AC：onboarding 起名 X ⇒ Header 显示 X —— 本 spec 用真实问卷流走到起名卡、
// 键入名字、提交，然后直读 Header 中心标题。
//
// Run: playwright test tests/onboarding-name-header.spec.mjs

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

const ROOT_SID = 'ob-name-root-session';
const CHOSEN_NAME = '星尘三号';   // onboarding 起的名（非 Nebula / 非 agentsData 旧名）

async function bootApp(page) {
  // 打字动画真实发生：把问候/引子气泡用「跳过打字」快进（顺带覆盖点 4 的点击路径）。
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
    // agentsData 带「旧显示名」：Header 必须跟随起名覆盖（X），而不是这行旧值。
    const sendAgents = () => ws.send(JSON.stringify({
      type: 'agentList',
      agents: [{ name: 'Nebula', description: '', displayName: '旧名不要出现在Header', avatar: null, tools: ['*'] }],
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
      } else if (msg.type === 'listAgents') {
        sendAgents();
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

/** 快进第 `nth` 个气泡（点「跳过打字」）。
 *  作用域纪律：按钮 locator 必须锚定在本气泡内 —— 点击成功后下一气泡立刻开流
 *  并带自己的新按钮，全局 first() 会在 waitFor(detached) 时重解析到那颗新按钮，
 *  把「已消失」误判成「未消失」。 */
/**
 * Fast-forward CSS animations (headless Chromium may stall the animation clock
 * for idle pages, leaving entry animations stuck at their opacity:0 first
 * frame — the click-actionability "stable" check then never passes and the
 * click times out at the test budget. Same fix as onboarding-skip-typing /
 * dialog-unify; 2026-10-24 CI 存量红分诊（run 40063846949）.
 */
async function settleAnimations(page) {
  await page.evaluate(() => {
    try { document.getAnimations().forEach(a => { try { a.finish(); } catch { /* not finishable */ } }); } catch { /* API absent */ }
  });
}

async function skipCurrentBubble(page, nth) {
  const skip = page.locator('.ob-sim-bubble').nth(nth).locator('[data-skip-typing]');
  await expect(skip).toBeVisible({ timeout: 15000 });
  await settleAnimations(page);
  await skip.click();
  await skip.waitFor({ state: 'detached', timeout: 10000 });
}

test('起名 X ⇒ Header 中心标题显示 X（写回链端到端）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 问候 ×2 + name 题的 intro + lead/question —— 全部快进到起名卡
  await skipCurrentBubble(page, 0);
  await skipCurrentBubble(page, 1);
  await skipCurrentBubble(page, 2);
  await skipCurrentBubble(page, 3);

  const card = page.locator('.ask-card[data-q="name"]');
  await expect(card).toBeVisible({ timeout: 10000 });

  // 键入名字并提交（free-first 面：输入框 + 「就用这个」）
  const input = card.locator('.free-row input.option-input');
  await input.fill(CHOSEN_NAME);
  const useBtn = card.locator('.free-row button').first();
  await expect(useBtn).toBeEnabled();
  await useBtn.click();

  // 🔴 AC：Header 中心标题 = 用户起的名（覆盖旧 agentsData displayName）
  await page.waitForFunction((n) => document.getElementById('session-name')?.textContent === n,
    CHOSEN_NAME, { timeout: 5000 });
  const headerText = await page.locator('#session-name').textContent();
  expect(headerText).toBe(CHOSEN_NAME);

  // 单一读点侧证：override 面（rootName.js）也返回新名
  const readBack = await page.evaluate(async (n) => {
    const { rootDisplayName } = await import('/js/rootName.js');
    return { display: rootDisplayName(), override: (await import('/js/state.js')).default.rootDisplayName, want: n };
  }, CHOSEN_NAME);
  expect(readBack.display).toBe(readBack.want);
  expect(readBack.override).toBe(readBack.want);

  expect(pageErrors).toEqual([]);
});
