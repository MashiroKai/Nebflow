// popup-retire-shots.mjs — 弹窗退场批（作者令 2026-10-31 三件）双端截图 + 动画帧取证。
//
// 覆盖：
//   ① 检索命中 act-and-exit → 主会话跳转 + 闪击动画帧（手机层：关搜索面回聊天根；
//      桌面：原位跳转闪击）。
//   ② 弹窗退场：/model 面板、后台任务输出卡 —— 手机层铺满屏 + 左上返回钮
//      （.mfs-back）；桌面同弹层居中模态零变化（.mfs-back 隐藏）。
//   ③ 设置层级子页下钻帧（STT 入口行 push 动画中段）+ /model 角色下钻帧。
//
// 用法：node tests/popup-retire-shots.mjs
// 产出：evidence/20261031_popup-retire/<面>-<端>-<主题>.png（deviceScaleFactor 2）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(HERE, '..', 'evidence', '20261031_popup-retire');
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-popup-retire';
const NOW = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const HISTORY = [
  { type: 'user', text: 'hello omega seed', timestamp: NOW - 3600000 },
  { type: 'ai', text: 'omega reply text', timestamp: NOW - 3500000 },
];

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', config: '{}', configured: true, onboarding: 'done',
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({
        type: 'historyPage', sessionId: m.sessionId, messages: HISTORY,
        hasMore: false, offset: HISTORY.length, total: HISTORY.length,
      }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

/** 通用启动：viewport/touch/colorScheme 由调用方给；isMobile 桌面腿 false。 */
async function boot(viewport, colorScheme, { touch = false } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2, hasTouch: touch });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  await page.route('**/api/**', (route) => {
    const p = new URL(route.request().url()).pathname;
    if (p === '/api/sessions') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
    }
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ mode: 'follow', chain: null, effectiveChain: [] }) });
    }
    return route.fulfill({ contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  // 检索面靠 WS sessionList 驱动 —— 等真连接就绪（同 guard spec 前置）。
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await sleep(800);
  return { ctx, page };
}

const shot = (page, name) => page.screenshot({ path: join(SHOTS_DIR, name), fullPage: false });

/** 几何探针：面板盒 + 返回钮可见性。 */
async function probeSheet(page, panelSel) {
  return page.evaluate((sel) => {
    const panel = document.querySelector(sel);
    if (!panel) return null;
    const r = panel.getBoundingClientRect();
    const back = panel.parentElement && panel.parentElement.querySelector(':scope > .mfs-back');
    return {
      w: Math.round(r.width), h: Math.round(r.height),
      vw: window.innerWidth, vh: window.innerHeight,
      r: getComputedStyle(panel).borderRadius,
      backDisplay: back ? getComputedStyle(back).display : 'none',
    };
  }, panelSel);
}

const isFull = (g) => g && g.w === g.vw && g.h === g.vh && g.r === '0px' && g.backDisplay !== 'none';

// ══ ②/③ 手机层 375×812 × 亮/暗 ═══════════════════════════════════════════
for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await boot({ width: 375, height: 812 }, scheme, { touch: true });

  // —— /model 面板：铺满屏 + 返回钮 ——
  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
  });
  await sleep(400);
  const mp = await probeSheet(page, '#model-panel-overlay .model-panel');
  ok(`/model 手机层铺满屏+返回（${scheme}）`, isFull(mp), JSON.stringify(mp));
  await shot(page, `model-mobile-${scheme}.png`);

  // —— /model 角色下钻动画帧（drill 中段截两帧；关注者组需 separate 模式）——
  const modeBtn = page.locator('.mp-mode-btn[data-mode="separate"]');
  if (await modeBtn.count()) { await modeBtn.click(); await sleep(300); }
  const roleToggle = page.locator('.mp-roleblock-follower .mp-role-head-toggle').first();
  if (await roleToggle.count()) {
    await roleToggle.click();
    await sleep(120);
    await shot(page, `model-drill-frame1-${scheme}.png`);
    await sleep(260);
    await shot(page, `model-drill-frame2-${scheme}.png`);
  }
  // 返回钮回列表 → 再按返回关面板。
  const mpBack = page.locator('#model-panel-overlay > .mfs-back');
  if (await mpBack.count()) {
    await mpBack.click();
    await sleep(250);
    if (await page.locator('#model-panel-overlay').count()) await mpBack.click();
    await sleep(250);
  }

  // —— 后台任务输出卡 ——
  await page.evaluate(async () => {
    const b = await import('/js/bgTaskOutputPopup.js');
    b.openBgTaskOutput({ taskId: 'shot-task-1', description: 'demo task output', status: 'completed' });
  });
  await sleep(300);
  const bgt = await probeSheet(page, '.bgt-overlay .bgt-modal');
  ok(`bgt 输出卡手机层铺满屏+返回（${scheme}）`, isFull(bgt), JSON.stringify(bgt));
  await shot(page, `bgt-mobile-${scheme}.png`);
  const bgtBack = page.locator('.bgt-overlay > .mfs-back');
  if (await bgtBack.count()) await bgtBack.click();
  await sleep(250);

  // —— ③ 设置子页下钻（push 动画帧）——
  await dockTo(page, 'workbench');
  // 经 dock 进设置页
  const settingsEntry = page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]');
  if (await settingsEntry.count()) {
    await settingsEntry.click();
    await sleep(400);
    await shot(page, `settings-mobile-${scheme}.png`);
    const sttRow = page.locator('#stt-advance-toggle');
    if (await sttRow.count()) {
      await sttRow.click();
      await sleep(130);
      await shot(page, `settings-sub-frame1-${scheme}.png`);
      await sleep(250);
      await shot(page, `settings-sub-frame2-${scheme}.png`);
      const subBack = page.locator('.mobile-page[data-page="settings-stt"] .mobile-back-btn');
      if (await subBack.count()) await subBack.click();
      await sleep(300);
    }
  }

  // —— ① 检索跳转闪击帧（手机层：FAB 搜索 → 命中 → 回聊天根闪击）——
  // 聊天根无 .mobile-page 面（dockTo 不适用）——直点中键 home + 等栈顶回 chat。
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  await page.waitForFunction(() => window.__nfMobileNav && window.__nfMobileNav.top === 'chat', { timeout: 5000 });
  await sleep(300);
  const searchBtn = page.locator('.mh-chrome-btn[data-chrome="search"]');
  if (await searchBtn.count()) {
    await searchBtn.click();
    await sleep(400);
    await shot(page, `search-mobile-${scheme}.png`);
    await page.fill('#search-keyword', 'omega');
    await page.waitForSelector('#search-results .search-result', { timeout: 5000 });
    await page.click('#search-results .search-result');
    // 闪击 1800ms：尽快连拍两帧（跳转中 + 闪击带）。
    await sleep(150);
    await shot(page, `search-jump-frame1-${scheme}.png`);
    await sleep(350);
    await shot(page, `search-jump-frame2-${scheme}.png`);
    const flash = await page.evaluate(() => !!document.querySelector('#chat .search-hit-flash'));
    ok(`检索命中回聊天根闪击带在场（${scheme}）`, flash);
    const top = await page.evaluate(() => window.__nfMobileNav && window.__nfMobileNav.top);
    ok(`检索命中后回聊天根（${scheme}）`, top === 'chat', `top=${top}`);
  }
  await ctx.close();
}

// ══ 桌面守卫 1440×900 × 亮/暗：同弹层居中模态零变化、.mfs-back 隐藏 ═════
for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await boot({ width: 1440, height: 900 }, scheme);

  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
    const b = await import('/js/bgTaskOutputPopup.js');
    b.openBgTaskOutput({ taskId: 'shot-task-2', description: 'demo', status: 'completed' });
  });
  await sleep(400);
  const mp = await probeSheet(page, '#model-panel-overlay .model-panel');
  const bgt = await probeSheet(page, '.bgt-overlay .bgt-modal');
  ok(`桌面 /model 不拉伸（${scheme}）`, mp && mp.w < 600 && mp.r !== '0px', JSON.stringify(mp));
  ok(`桌面 /model 返回钮隐藏（${scheme}）`, mp && mp.backDisplay === 'none');
  ok(`桌面 bgt 不拉伸（${scheme}）`, bgt && bgt.r !== '0px' && bgt.backDisplay === 'none', JSON.stringify(bgt));
  await shot(page, `model-desktop-${scheme}.png`);
  // 关掉 /model 拍 bgt 桌面形态。
  await page.keyboard.press('Escape');
  await sleep(300);
  await page.evaluate(async () => {
    const b = await import('/js/bgTaskOutputPopup.js');
    b.openBgTaskOutput({ taskId: 'shot-task-3', description: 'demo', status: 'completed' });
  }).catch(() => {});
  await sleep(300);
  await shot(page, `bgt-desktop-${scheme}.png`);

  // —— ① 桌面检索跳转闪击帧（独立新页：模态族 Z 序不互相挡）——
  await ctx.close();
  const desktop2 = await boot({ width: 1440, height: 900 }, scheme);
  const page2 = desktop2.page;
  await page2.evaluate(async () => {
    const m = await import('/js/chatSearch.js');
    m.openSearchModal();
  });
  await sleep(300);
  await page2.fill('#search-keyword', 'omega').catch(() => {});
  const hasResults = await page2.waitForSelector('#search-results .search-result', { timeout: 5000 }).then(() => true).catch(() => false);
  if (hasResults) {
    await page2.click('#search-results .search-result');
    await sleep(200);
    await shot(page2, `search-jump-desktop-${scheme}.png`);
    const flash = await page2.evaluate(() => !!document.querySelector('#chat .search-hit-flash'));
    ok(`桌面检索命中闪击带在场（${scheme}）`, flash);
  } else {
    ok(`桌面检索命中闪击带在场（${scheme}）`, false, '无搜索结果');
  }
  await desktop2.ctx.close();
}

ok('console 零新增错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

await browser.close();
server.close();
console.log(failures === 0 ? 'ALL PASS (shots)' : `${failures} FAIL (shots)`);
process.exit(failures === 0 ? 0 : 1);
