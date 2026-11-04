// todo-page-shots.mjs — 待办体系改版批（2026-10-31）截图取证脚本（自足，零后端）。
// ⚠ 入口收敛批 todo-unify-entry-collapse 2026-11-04 改写：#todo-page 整面退役
// （DOM 保留、不可达）+ 消息列表「待办事项」入口行撤除 ⇒ 本脚本不再拍待办页，
// 改拍现役待办面（顶带待办胶囊 / 胶囊展开面板 / 消息列表零入口行）。
// 产出：桌面亮/暗 × {messages-no-entry, capsule, capsule-expanded}、
//       手机亮/暗 × {capsule, capsule-expanded, messages-no-entry}，
//       入 .nebflow/evidence/20261031_todo-inbox-redesign/shots/。
// 运行：node tests/todo-page-shots.mjs

import { chromium } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', '.nebflow', 'evidence', '20261031_todo-inbox-redesign', 'shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todo-shots-root';
const T0 = Date.now();

const INBOX = [
  { id: 'o1', content: '已到期：给 VPS 续费', triggerAt: T0 - 3_600_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID },
  { id: 'a1', content: '[待答] 选哪个方案？ — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'ask-asknb-aaa111', missCount: 0, sessionId: SID },
  { id: 'p1', content: '[待批准] Bash · 清理构建缓存 — Nebula', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'perm-permnb-bbb222', missCount: 0, sessionId: SID },
  { id: 'd1', content: '整理周报模板进 memory', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID, project: 'stand' },
  { id: 'd2', content: '周报打卡', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'weekly-report-44', missCount: 4, sessionId: SID, project: 'stand' },
  { id: 'd3', content: 'DOSTEL：传感器数据落盘核对', triggerAt: T0 + 172_800_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID, project: 'DOSTEL' },
];
const TASKS = [
  { id: 's1', content: '即将触发的定时任务', triggerAt: T0 + 3_600_000, enabled: true, triggered: false, kind: null },
  { id: 's2', content: '已超时的定时任务', triggerAt: T0 - 3_600_000, enabled: true, triggered: false, kind: null },
];

async function boot(browser, { viewport, colorScheme }) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'shot-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
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
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'listScheduledTasks') {
        ws.send(JSON.stringify(m.scope === 'all'
          ? { type: 'scheduledTaskList', tasks: INBOX, scope: 'all' }
          : { type: 'scheduledTaskList', tasks: TASKS }));
      } else if (m.type === 'getPendingAsks') ws.send(JSON.stringify({ type: 'pendingAsksSnapshot', asks: [] }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
  return { ctx, page };
}

const browser = await chromium.launch();
try {
  for (const colorScheme of ['light', 'dark']) {
    // 桌面：消息列表面（入口行已退役 = 纯会话名册）+ 胶囊静默态/展开态。
    const d = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme });
    await d.page.locator('#messages-btn').click().catch(() => {});
    await d.page.waitForTimeout(300);
    await d.page.screenshot({ path: join(OUT, `desktop-${colorScheme}-messages-entry.png`) });
    await d.page.waitForSelector('#task-list .tfl-capsule', { timeout: 8000 });
    await d.page.screenshot({ path: join(OUT, `desktop-${colorScheme}-todo-capsule.png`) });
    await d.page.locator('#task-list .tfl-capsule').click();
    await d.page.waitForSelector('#task-list .tfl-card[data-state="open"]', { timeout: 8000 });
    await d.page.waitForTimeout(400);
    await d.page.screenshot({ path: join(OUT, `desktop-${colorScheme}-todo-capsule-expanded.png`) });
    await d.ctx.close();

    // 手机：胶囊（唯一入口）静默态 / 展开态 + 消息页零入口行（先点 dock 消息键
    // 到根页——首启默认落在 chat 根页）。
    const m = await boot(browser, { viewport: { width: 390, height: 780 }, colorScheme });
    await m.page.waitForSelector('#task-list .tfl-capsule', { timeout: 8000 });
    await m.page.waitForTimeout(300);
    await m.page.screenshot({ path: join(OUT, `mobile-${colorScheme}-todo-capsule.png`) });
    await m.page.locator('#task-list .tfl-capsule').click();
    await m.page.waitForSelector('#task-list .tfl-card[data-state="open"]', { timeout: 8000 });
    await m.page.waitForTimeout(400);
    await m.page.screenshot({ path: join(OUT, `mobile-${colorScheme}-todo-capsule-expanded.png`) });
    await m.page.locator('#task-list .tfl-collapse-btn').click();
    await m.page.waitForTimeout(400);
    await m.page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
    await m.page.waitForSelector('.mobile-page[data-page="messages"].live', { timeout: 8000 });
    await m.page.waitForTimeout(300);
    await m.page.screenshot({ path: join(OUT, `mobile-${colorScheme}-messages-entry.png`) });
    await m.ctx.close();
  }
  console.log('SHOTS OK →', OUT);
} finally {
  await browser.close();
}
