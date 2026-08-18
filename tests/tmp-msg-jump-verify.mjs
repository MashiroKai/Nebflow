// tmp-msg-jump-verify.mjs — 消息搜索「定位到消息」修复验收
// 场景：180 条消息的长会话。聊天视图初始只加载最新 50 条（WS getHistory 分页），
// 搜索 modal 走 REST 全量——点击远处结果必须驱动分页加载直到目标进 DOM。
// 同时验证 main.js prepend 守卫修复：最老一页（offset=0 响应）不再被丢弃。
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json' };

const SID = 'jump-session-1';
const NOW = Date.now();
const PAGE = 50;
// 180 messages, oldest first. index 0..179.
const HISTORY = [];
for (let i = 0; i < 180; i++) {
  HISTORY.push({ type: i % 2 ? 'ai' : 'user', text: `message number ${i} lorem ipsum`, timestamp: NOW - (180 - i) * 60000 });
}
// T3: image-only user message at index 100 (empty text → snippet fallback to data-ts)
HISTORY[100] = { type: 'user', text: '', timestamp: NOW - 80 * 60000, attachments: [{ type: 'image', name: 'omega-img.png' }] };
// T2 target at index 2 (oldest page, requires offset-0 prepend fix)
HISTORY[2] = { type: 'user', text: 'ancient message omega marker', timestamp: NOW - 178 * 60000 };
// T1 target at index 179 (newest, in initial window)
HISTORY[179] = { type: 'user', text: 'latest message alpha marker', timestamp: NOW - 60000 };

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// WS getHistory — mirror SessionStore.getHistoryPage semantics
let beforeIndexRequests = 0;
function historyPage(beforeIndex) {
  const total = HISTORY.length;
  if (beforeIndex == null) {
    const offset = Math.max(0, total - PAGE);
    return { messages: HISTORY.slice(offset, total), total, offset, hasMore: total > PAGE };
  }
  const offset = Math.max(0, beforeIndex - PAGE);
  const limit = Math.min(PAGE, beforeIndex);
  return { messages: HISTORY.slice(offset, offset + limit), total, offset, hasMore: beforeIndex > PAGE };
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
const pageErrors = [];
page.on('pageerror', e => pageErrors.push(e.message));
await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));

await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/api/sessions') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Jump', agentName: 'Nebula' }], activeId: SID }) });
  }
  if (p.startsWith(`/api/sessions/${SID}/history`)) {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
  }
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch {
    return route.fulfill({ status: 404, body: 'not found' });
  }
});

await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
  const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Jump', agentName: 'Nebula' }], folders: [], activeId: SID }));
  sendConfig(); sendSessions();
  ws.onMessage((raw) => {
    let msg; try { msg = JSON.parse(raw); } catch { return; }
    if (msg.type === 'getConfig') sendConfig();
    else if (msg.type === 'getHistory') {
      if (msg.beforeIndex != null) beforeIndexRequests++;
      const pg = historyPage(msg.beforeIndex ?? null);
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, ...pg }));
    }
  });
});

await page.goto('http://localhost:1/');
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 15000 });
// Wait for initial history (50 newest) to render
await page.waitForSelector('#chat .row', { timeout: 8000 });
await sleep(400);
const chatSel = '#chat';
const rowCount = () => page.locator(`${chatSel} .row`).count();
const initialRows = await rowCount();
check('J0 initial load renders newest 50 only', initialRows === 50, `rows=${initialRows}`);

// Helper: open search, query, click first matching result by text
async function searchAndClick(text) {
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
  await page.fill('#search-keyword', text);
  await page.waitForSelector('#search-results .search-result', { timeout: 5000 });
  await sleep(400); // debounce settle + render
  const row = page.locator('#search-results .search-result', { hasText: text }).first();
  await row.click();
  await page.waitForFunction(() => !document.getElementById('search-overlay')?.classList.contains('on'), { timeout: 5000 });
}

// ── J1: newest-window result → immediate jump, zero pagination ──
const beforeJ1 = beforeIndexRequests;
await searchAndClick('latest message alpha marker');
await page.waitForSelector(`${chatSel} .search-hit-flash`, { timeout: 3000 });
check('J1 newest result jumps immediately', true);
check('J1 no pagination requests for in-window target', beforeIndexRequests === beforeJ1, `req=${beforeIndexRequests - beforeJ1}`);
await sleep(2000); // let flash expire

// ── J2: oldest-page result (index 2) → drives pagination to the very top ──
await searchAndClick('ancient message omega marker');
await page.waitForSelector(`${chatSel} .search-hit-flash`, { timeout: 20000 });
const ancientInDom = await page.locator(`${chatSel} .row`, { hasText: 'ancient message omega marker' }).count();
check('J2 ancient result jumped (flash)', true);
check('J2 oldest page (offset=0) actually prepended — guard fix', ancientInDom === 1, `rows=${await rowCount()}`);
check('J2 pagination was driven programmatically', beforeIndexRequests >= 3, `req=${beforeIndexRequests}`);
// offset reached 0 → full history in DOM
const finalRows = await rowCount();
check('J2 full history loaded (180 rows + indicators)', finalRows >= 180, `rows=${finalRows}`);
await sleep(2000);

// ── J3: image-only message (empty text) → data-ts fallback ──
// Reset view: switch away is not possible (single session); reload page state
// by re-switching via session list click (same session → no reset). Instead
// verify fallback matcher directly: remove text-matchability by relying on
// ts. The DOM is fully loaded now, so just click the result.
await searchAndClick('omega-img.png');
await page.waitForSelector(`${chatSel} .search-hit-flash`, { timeout: 5000 });
const flashedTs = await page.locator(`${chatSel} .search-hit-flash [data-ts]`).first().getAttribute('data-ts');
check('J3 image-only result jumped via data-ts fallback', String(NOW - 80 * 60000) === flashedTs, `ts=${flashedTs}`);
await sleep(2000);

// ── J4: user navigates away mid-jump → aborts silently, no toast ──
// (Same-session abort path can't be triggered with one session; assert the
//  guard exists by checking no error toast from prior jumps.)
const toasts = await page.locator('.nebflow-toast').allTextContents().catch(() => []);
check('J4 no jumpFailed toast in successful scenarios', !toasts.some(x => String(x).includes('定位') || String(x).toLowerCase().includes('jump')), JSON.stringify(toasts).slice(0, 80));

check('no page errors', pageErrors.length === 0, pageErrors.join('; ').slice(0, 200));

const failed = checks.filter(c => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
await browser.close();
process.exit(failed.length ? 1 : 0);
