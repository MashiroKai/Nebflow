// workspace-picker-shots.mjs — 亮暗双主题截图（workspace-picker 批次）。
// 产出：/tmp/nb-ws-picker/shots/{light,dark}-{card,modal}.png
//
// 用法：node tests/workspace-picker-shots.mjs <baseURL> <token>

import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const BASE_URL = process.argv[2] ?? 'http://localhost:8300';
const TOKEN = process.argv[3] ?? '';
const OUT = '/tmp/nb-ws-picker/shots';
mkdirSync(OUT, { recursive: true });

const SID = 'e2e-ws-picker-session';
const RID = 'req-wsp-1';
const ITEM = {
  question: 'ProjectCreate 需要项目工作区路径 — 点击上方「选择工作区」打开应用内目录浏览器（可逐级浏览、新建文件夹）。',
  dirPicker: true,
  options: [{ label: '/Users/dev/Claude code/alpha-proj' }, { label: '/Users/dev/Claude code/beta-lab' }],
};

const browser = await chromium.launch();

for (const scheme of ['light', 'dark']) {
  const ctx = await browser.newContext({ colorScheme: scheme, viewport: { width: 1280, height: 860 } });
  const page = await ctx.newPage();
  await page.goto(`${BASE_URL}/?token=${TOKEN}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(700);

  const overlay = page.locator('.onboarding-overlay');
  if (await overlay.isVisible().catch(() => false)) {
    const dismiss = overlay.locator('#ob-skip, #ob-no');
    if (await dismiss.count()) await dismiss.first().click();
    await overlay.waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
  }
  await page.evaluate(() => localStorage.removeItem('nebflow_askuser_drafts'));
  // 关 Canvas 面板（history restore 竞态会清空聊天列，先关防遮挡——askuser.spec 先例）
  await page.evaluate(async () => {
    const { isCanvasOpen, closeCanvas } = await import('/js/canvas.js');
    if (isCanvasOpen()) closeCanvas();
    const { sendWs } = await import('/js/ws.js');
    sendWs({ type: 'createSession', name: 'ws-picker-shots', agentName: 'Nebula' });
  });
  await page.waitForTimeout(900);
  await page.evaluate(async ({ item }) => {
    const { renderAskUser } = await import('/js/chat.js');
    const { chatViews, setActiveView } = await import('/js/chatView.js');
    const state = (await import('/js/state.js')).default;
    setActiveView(chatViews.primary);
    window.__origOnMessage = state.ws && state.ws.onmessage ? state.ws.onmessage : null;
    state.ws = { readyState: 1, send: () => {} };
    renderAskUser([item], 'e2e-ws-picker-session', 'E2E', 'req-wsp-1');
  }, { item: ITEM });

  await page.waitForSelector('.ws-pick-target');
  // 卡片滚入视野再拍（防滚动/遮挡），元素级 + 全页各一张
  await page.locator('.ws-pick-target').last().scrollIntoViewIfNeeded();
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${scheme}-card.png`), fullPage: false });

  // 2026-09-06 新流程：点击目标 → openPicker 直接打开应用内目录浏览器（无 fallback 中转）。
  // 不再注入 workspaceDirPicked fallback 事件；打开后由脚本注入 wsBrowseList 渲染列表。
  await page.locator('.ws-pick-target').click();
  await page.waitForSelector('.wsp-overlay');
  await page.waitForTimeout(120);
  const respond = (path, entries) => page.evaluate(({ m }) => window.__origOnMessage({ data: JSON.stringify(m) }), { m: { type: 'wsBrowseList', path, home: '/Users/dev', entries } });
  // 等首屏 '~' 请求后回数据
  await page.waitForFunction(() => true); // noop tick
  await respond('~', ['Claude code', 'Documents']);
  await page.waitForTimeout(200);
  await page.locator('.wsp-row.wsp-dir', { hasText: 'Claude code' }).click().catch(() => {});
  await page.waitForTimeout(250);
  await respond('/Users/dev/Claude code', ['alpha-proj', 'beta-lab']);
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${scheme}-modal.png`) });
  await ctx.close();
}

await browser.close();
console.log('SHOTS DONE → ' + OUT);
