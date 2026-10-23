// panels-mobile-ux-shots.mjs — 面板合并 + 移动端 UX 七件批视觉自验截图
//（作者令 2026-10-23 ①-⑦；桌面 1440×900 + 手机 375×812 × dark/light 双套）。
// 形态沿 mobile-v3-shots.mjs 既有路线：静态 route 引真实 js/css/locale +
// routeWebSocket 注入应答帧，零后端。产物 = <outDir>（由参数指定）。
//
// 覆盖：
//   d1/d2  桌面合并面板（智能体分区 = 头像点击即换 + 记忆分区 = 双页签）
//   d3     桌面后台合并面板（任务节 + 「智能体」分组节）
//   m1/m2  手机合并面板（header 头像直入；居中形态）
//   m3     手机设置页（无智能体入口行，⑤ 居中族）
//   m4     手机后台合并面板（⑥ 紧凑化）
//   m5     手机搜索面板（⑤ 居中）
//   m6     手机画板 bottom sheet（⑦ 底部弹出卡片）
//
// 用法：node tests/panels-mobile-ux-shots.mjs <outDir>
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/tmp/panels-mobile-ux-shots';
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'pmux-session-1';
const T0 = Date.now();

const browser = await chromium.launch();

async function makePage(colorScheme, { width = 375, height = 812 } = {}) {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme, deviceScaleFactor: 2, hasTouch: width <= 768,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '笨笨的会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: { [SID]: [
      { taskId: 'job-1', description: '跑移动端 UI 回归', status: 'running', startedAt: T0 - 65_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 3_000, outputLines: 14 } },
      { taskId: 'job-2', description: '整理证据链截图', status: 'running', startedAt: T0 - 180_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 9_000, outputLines: 40 } },
    ] } }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getMemory') ws.send(JSON.stringify({ type: 'memoryData', scope: m.scope, content: `【${m.scope}】长期记忆样例：项目偏好 Stand；面板合并批 2026-10-23 落地七件 UX。` }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(800);
  return page;
}

/** 种一个运行中子智能体（合并面板「智能体」分组节 + bgagent 指示器计数）。 */
async function seedAgent(page) {
  await page.evaluate((sid) => {
    import('/js/state.js').then((m) => {
      m.default.sessionBgAgents[sid] = {
        'delegate-pmux-1': {
          name: '前端批实现', kind: 'Delegate', sessionId: 'delegate-pmux-1',
          status: 'running', startedAt: Date.now() - 95_000,
        },
      };
      m.default.updateBgAgentIndicator();
    });
  }, SID);
  await page.waitForTimeout(200);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

for (const scheme of ['dark', 'light']) {
  // ── 桌面 1440×900 ────────────────────────────────────────────────
  const d = await makePage(scheme, { width: 1440, height: 900 });
  await seedAgent(d);

  // d1 合并面板 · 智能体分区（头像点击直入；预览图点击即换，无「设置头像」钮）
  await d.locator('#agent-avatar-btn').click();
  await d.waitForSelector('#memory-overlay.on', { timeout: 8000 });
  await sleep(600);
  await d.screenshot({ path: join(OUT, `desktop-merged-agent-${scheme}.png`) });

  // d2 合并面板 · 记忆分区（双页签切换）
  await d.locator('.merged-tab[data-pane="memory"]').click();
  await sleep(700);
  await d.screenshot({ path: join(OUT, `desktop-merged-memory-${scheme}.png`) });
  await d.keyboard.press('Escape');
  await sleep(400);

  // d3 后台合并面板（任务节 + 「智能体」分组节；幽灵面退场）
  await d.locator('#bg-indicator').click();
  await d.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await sleep(500);
  await d.screenshot({ path: join(OUT, `desktop-bg-merged-${scheme}.png`) });
  await d.close();

  // ── 手机 375×812 ────────────────────────────────────────────────
  const m = await makePage(scheme, { width: 375, height: 812 });
  await seedAgent(m);

  // m1 合并面板 · 智能体分区（header 头像直入；居中）
  await m.locator('#agent-avatar-btn').click();
  await m.waitForSelector('#memory-overlay.on', { timeout: 8000 });
  await sleep(600);
  await m.screenshot({ path: join(OUT, `mobile-merged-agent-${scheme}.png`) });

  // m2 合并面板 · 记忆分区
  await m.locator('.merged-tab[data-pane="memory"]').click();
  await sleep(700);
  await m.screenshot({ path: join(OUT, `mobile-merged-memory-${scheme}.png`) });
  await m.keyboard.press('Escape');
  await sleep(400);

  // m3 设置页（无智能体入口行；cfg 键非绿）
  await m.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await sleep(500);
  await m.locator('.mh-dd[data-dd="right"] [data-face="settings"]').click();
  await m.waitForSelector('.mobile-page[data-page="settings"].live', { timeout: 8000 });
  await sleep(700);
  await m.screenshot({ path: join(OUT, `mobile-settings-${scheme}.png`) });
  await m.locator('.mobile-page[data-page="settings"] .mobile-page-header .mobile-back-btn').click();
  await sleep(400);

  // m4 后台合并面板（左列 bg 键；紧凑化 + 「智能体」分组节）
  await m.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await sleep(500);
  await m.locator('#bg-indicator').click();
  await m.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await sleep(600);
  await m.screenshot({ path: join(OUT, `mobile-bg-merged-${scheme}.png`) });
  await m.keyboard.press('Escape');
  await sleep(400);

  // m5 搜索面板（⑤ 居中族代表）
  await m.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await sleep(400);
  await m.locator('#search-btn').click();
  await m.waitForSelector('#search-overlay.on', { timeout: 8000 });
  await sleep(700);
  await m.screenshot({ path: join(OUT, `mobile-search-centered-${scheme}.png`) });
  await m.keyboard.press('Escape');
  await sleep(400);

  // m6 画板 bottom sheet（⑦ 底部弹出卡片；touchLayer 先置位避开启动抑制）
  await m.evaluate(async () => {
    const [canvas, nav] = await Promise.all([import('/js/canvas.js'), import('/js/mobile/navController.js')]);
    nav.touchLayer();
    canvas.openTab('doc-shot', '重构笔记.md', { type: 'docShot' });
  });
  await m.waitForSelector('body.mf-cards-live', { timeout: 8000 });
  await sleep(800); // mhSheetIn 入场终态
  await m.screenshot({ path: join(OUT, `mobile-canvas-sheet-${scheme}.png`) });
  await m.close();
}

await browser.close();
console.log(`panels-mobile-ux shots → ${OUT}`);
