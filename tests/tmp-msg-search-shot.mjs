// tmp-msg-search-shot.mjs — 视觉验证：搜索弹窗 Pop 标签截图
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json' };
const SID = 'shot-session';
const NOW = Date.now();
const HISTORY = [
  { type: 'user', text: '帮我看一下这个报告', timestamp: NOW - 3600000 },
  { type: 'ai', text: '好的，报告已生成并展示在 Canvas。', timestamp: NOW - 3500000 },
  { type: 'tool', label: 'Pop(report.html)', summary: 'Open report.html in Canvas', content: '' },
  { type: 'user', text: '昨天的图片', timestamp: NOW - 86400000, attachments: [{ type: 'image', name: 'photo.png' }] },
];

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })).newPage();
await page.addInitScript(() => { localStorage.setItem('nebflow_token', 't'); localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN'); });
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Shot', agentName: 'Nebula' }], activeId: SID }) });
  if (p.startsWith(`/api/sessions/${SID}/history`)) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403 });
  try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
  catch { return route.fulfill({ status: 404 }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Shot', agentName: 'Nebula' }], folders: [], activeId: SID }));
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY.slice(-50), total: HISTORY.length, offset: 0, hasMore: false }));
  });
});
await page.goto('http://localhost:1/');
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 15000 });
await page.waitForTimeout(300);
await page.click('#search-btn');
await page.waitForSelector('#search-overlay.on');
await page.waitForSelector('#search-results .search-result', { timeout: 5000 });
await page.waitForTimeout(500);
await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/msg-search-pop-tab.png' });
// Pop tab active state
await page.locator('.search-tabs .search-tab').nth(3).click();
await page.waitForTimeout(600);
await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/msg-search-pop-tab-active.png' });
await browser.close();
console.log('shots saved');
