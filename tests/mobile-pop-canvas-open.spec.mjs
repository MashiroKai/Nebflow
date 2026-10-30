// mobile-pop-canvas-open.spec.mjs — 作者报障 2026-10-30 12:30：「手机端 点击
// 图片然后在canvas打开好像有点问题 不会自动弹canvas 而是等我把canvas点开后
// 才弹」。本 spec 钉死修复：手机视口点 Pop 图片 ⇒ canvas 页自动弹出（不等
// 手动开面板）；桌面视口点击即开面板零回归。
//
// 通路：Pop 卡点击 = openPopArtifact 单点 → workspace-open-item（empty
// content + absPath）→ canvas.js openWorkspaceItem 早退腿发 pop.readFile →
// 服务端 fileContent 应答 → explorer.js 重派 workspace-open-item（全量 item）
// → openTab → openCanvas + canvas-tab-switched → navController.maybeAdopt →
// dockTo('canvas')（自动弹页的唯一消费腿）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-pop-canvas-open.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'pop-canvas-session';
const IMG_PATH = 'docs/pic.png';

async function bootApp(page, { noPopReply = false } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (parsed.type === 'pop.readFile') {
        // 服务端应答腿：fileContent 全量重派（图片 = itemType image、无 content）。
        // noPopReply 模式：不应答——钉「开面板与 WS 往返解耦」（修前此模式点击
        // 零反馈，必须等应答才 openTab→openCanvas）。
        if (noPopReply) return;
        ws.send(JSON.stringify({
          type: 'fileContent',
          path: parsed.path,
          absPath: '/tmp/' + parsed.path,
          fileName: parsed.path.split('/').pop(),
          itemType: 'image',
          content: '',
          size: 3,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
}

/** 真实单点：Pop 卡点击处理器同一个 openPopArtifact（evaluate 内动态 import
 *  复用生产代码，零第二套开法）。 */
async function clickPopImage(page) {
  await page.evaluate(async (filePath) => {
    const { openPopArtifact } = await import('/js/popArtifacts.js');
    openPopArtifact(filePath, 'pic');
  }, IMG_PATH);
}

/** 真实 UI 路径：生产 buildPopArtifactRow 渲染单图 Pop 行进聊天 DOM
 * （与历史恢复/实时渲染同一构建器），返回可点元素的选择器。 */
async function renderRealPopRow(page) {
  return page.evaluate(async (filePath) => {
    const { buildPopArtifactRow } = await import('/js/popArtifacts.js');
    const row = buildPopArtifactRow({ items: [{ kind: 'image', name: 'pic.png', path: filePath }] });
    row.dataset.testid = 'pop-artifact-row';
    // 聊天消息列的真实容器（index.html #chat；行形态 = .row.pop-artifact）。
    document.getElementById('chat')?.appendChild(row);
    return !!row;
  }, IMG_PATH);
}

test.describe('mobile pop image click auto-opens canvas page', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

  test('M1: click Pop image → canvas page auto-opens (no manual dock tap)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await bootApp(page);
    // navController.maybeAdopt 的启动抑制窗 = 3s（boot 恢复不算用户开卡）——
    // 真实用户点击远在抑制窗之后，等过它再点。
    await page.waitForFunction(() => window.__nfMobileLayer?.active === true, null, { timeout: 15000 });
    await page.waitForTimeout(3300);

    await clickPopImage(page);

    // 不等手动开：canvas 独立页自动 live（maybeAdopt → dockTo('canvas')）。
    await expect(page.locator('.mobile-page[data-page="canvas"]')).toHaveClass(/live/, { timeout: 5000 });
    await settlePage(page, 'canvas');
    const nav = await page.evaluate(() => ({
      top: window.__nfMobileNav?.top,
      home: window.__nfMobileNav?.home,
      canvasOpen: document.body.classList.contains('canvas-open'),
      dockCanvasActive: document.querySelector('#mobile-dock .dock-key[data-dock="canvas"]')?.classList.contains('active'),
    }));
    expect(nav.top).toBe('canvas');
    expect(nav.canvasOpen).toBe(true);
    expect(nav.dockCanvasActive).toBe(true);
    expect(errors).toEqual([]);
  });

  test('M2: real UI tap on the Pop image row → canvas page auto-opens', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await bootApp(page);
    await page.waitForFunction(() => window.__nfMobileLayer?.active === true, null, { timeout: 15000 });
    await page.waitForTimeout(3300);

    expect(await renderRealPopRow(page)).toBe(true);
    // 真实触屏轻点（走完整 pointer/click 合成序，非 element.click() 捷径）。
    await page.locator('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat').tap();

    await expect(page.locator('.mobile-page[data-page="canvas"]')).toHaveClass(/live/, { timeout: 5000 });
    await settlePage(page, 'canvas');
    const nav = await page.evaluate(() => window.__nfMobileNav?.top);
    expect(nav).toBe('canvas');
    // 页内卡片 chip 条出现且选中该图片卡（内容臂收编生效）。
    await expect(page.locator('.mf-strip-chip.on')).toHaveCount(1, { timeout: 5000 });
    expect(await page.locator('.mf-strip-chip.on').textContent()).toContain('pic.png');
    expect(errors).toEqual([]);
  });

  test('M3: WS never replies → canvas page STILL auto-opens (open decoupled from readFile round trip)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await bootApp(page, { noPopReply: true });
    await page.waitForFunction(() => window.__nfMobileLayer?.active === true, null, { timeout: 15000 });
    await page.waitForTimeout(3300);

    await clickPopImage(page);

    // 修前：此模式点击零反馈（openCanvas 押在 fileContent 应答之后）——红。
    await expect(page.locator('.mobile-page[data-page="canvas"]')).toHaveClass(/live/, { timeout: 5000 });
    await settlePage(page, 'canvas');
    // 应答未到 ⇒ 无文档卡（空态），但页必须已弹出。
    await expect(page.locator('.mf-strip-chip')).toHaveCount(0);
    expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('canvas');
    expect(errors).toEqual([]);
  });

  test('M4: click that landed BEFORE the mobile layer mounted is replayed on activation', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await bootApp(page, { noPopReply: true });
    await page.waitForFunction(() => window.__nfMobileLayer?.active === true, null, { timeout: 15000 });
    await page.waitForTimeout(3300);

    // 模拟「点击早于 nav 挂载」：用户开卡意图旗 + 面板已开，但栈层错过事件
    // （慢机冷启竞态）。随后视口跨出再跨回 = 层拆除重挂（重挂后 3s 启动抑制
    // 闸在场——回放腿必须绕过它，旗是显式用户意图而非 boot 恢复）。
    await page.evaluate(() => {
      document.body.classList.add('canvas-open');
      window.__nfPendingCanvasOpen = Date.now();
    });
    await page.setViewportSize({ width: 900, height: 812 });
    await page.waitForFunction(() => window.__nfMobileLayer?.active === false, null, { timeout: 15000 });
    await page.setViewportSize({ width: 375, height: 812 });
    // 修前：无回放腿——重挂后停在聊天根，红。
    await expect(page.locator('.mobile-page[data-page="canvas"]')).toHaveClass(/live/, { timeout: 15000 });
    expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('canvas');
    expect(errors).toEqual([]);
  });
});

test.describe('desktop pop image click opens panel (regression)', () => {
  test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });

  test('D1: click Pop image → canvas panel opens with the image tab active', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await bootApp(page);
    await page.waitForTimeout(500);

    await clickPopImage(page);

    await page.waitForFunction(() => document.body.classList.contains('canvas-open'), null, { timeout: 5000 });
    const st = await page.evaluate(() => ({
      activeTab: document.querySelector('#canvas-tab-bar .canvas-tab.active .canvas-tab-label')?.textContent,
    }));
    expect(st.activeTab).toContain('pic.png');
    expect(errors).toEqual([]);
  });

  test('D2: WS never replies → panel STILL opens on click (regression: open no longer waits for fileContent)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await bootApp(page, { noPopReply: true });
    await page.waitForTimeout(500);
    // 关键前置：把面板置回「关」态（boot 恢复腿会把它置真——不先关掉，本用例的
    // canvas-open 断言会被存量状态假性满足）。
    await page.evaluate(async () => {
      const { closeCanvas } = await import('/js/canvas.js');
      closeCanvas();
    });
    expect(await page.evaluate(() => document.body.classList.contains('canvas-open'))).toBe(false);

    await clickPopImage(page);

    // 修前：无应答 = 面板不开——红。修后：点击瞬间开面板（页签待应答落位）。
    await page.waitForFunction(() => document.body.classList.contains('canvas-open'), null, { timeout: 5000 });
    expect(errors).toEqual([]);
  });
});
