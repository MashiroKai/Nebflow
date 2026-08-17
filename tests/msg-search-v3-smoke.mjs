// msg-search-v3-smoke.mjs — pre-QA smoke for the v3.1 search modal.
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node tests/msg-search-v3-smoke.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SID = 'smoke-session-1';
const NOW = Date.now();
const DAY = 86400000;
// Cross-day seed: today, yesterday, 3 days ago
const HISTORY = [
  { type: 'user', text: 'today plain message', timestamp: NOW - 3600000 },
  { type: 'ai', text: 'today ai reply', timestamp: NOW - 3500000 },
  { type: 'user', text: 'yesterday with image', timestamp: NOW - DAY, attachments: [{ type: 'image', name: 'photo.png' }] },
  { type: 'ai', text: 'yesterday ai reply', timestamp: NOW - DAY + 60000 },
  { type: 'user', text: 'three days ago text', timestamp: NOW - 3 * DAY },
  { type: 'tool', label: 'Card(report)', summary: 'card output html', content: '<html>card</html>' },
  { type: 'tool', label: 'Bash\n  (ls)', summary: 'ls output', content: 'file list' },
];

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(e.message));

await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));

await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/api/sessions') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
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
  const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], folders: [], activeId: SID }));
  sendConfig(); sendSessions();
  ws.onMessage((raw) => {
    let msg; try { msg = JSON.parse(raw); } catch { return; }
    if (msg.type === 'getConfig') sendConfig();
    else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
    else if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});

await page.goto('http://localhost:1/');
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 15000 });
await page.waitForTimeout(300);

// ── Open the modal ──
await page.click('#search-btn');
await page.waitForSelector('#search-overlay.on', { timeout: 5000 });

// B1: open-into-browse — results within 2s, no legacy hint text
await page.waitForSelector('#search-results .search-result', { timeout: 2000 });
const hintText = await page.locator('#search-results .search-hint').allTextContents();
check('B1 open-into-browse (results present)', true);
check('B1 no legacy search.hint text', !hintText.some(t => t.includes('输入关键词')), JSON.stringify(hintText));

// B2: tab bar structure
const tabs = page.locator('.search-tabs .search-tab');
check('B2 exactly 5 tabs', await tabs.count() === 5);
const tabTexts = await tabs.allTextContents();
check('B2 tab labels', JSON.stringify(tabTexts) === JSON.stringify(['全部', '图片', '文件', 'Card', '日期']), JSON.stringify(tabTexts));
check('B2 first tab active', await tabs.nth(0).getAttribute('class') === 'search-tab active' || (await tabs.nth(0).getAttribute('class') || '').includes('active'));

// B3/B4: switch to Images tab — keyword retained, filtering correct
await page.fill('#search-keyword', 'test');
await page.waitForTimeout(500);   // debounce + fetch
await tabs.nth(1).click();
await page.waitForTimeout(600);
check('B3 keyword kept on tab switch', await page.inputValue('#search-keyword') === 'test');
check('B3 images tab active', (await tabs.nth(1).getAttribute('class') || '').includes('active'));
// 'test' matches nothing in seed → noResults state in Images tab
await page.waitForSelector('#search-results .search-hint', { timeout: 3000 });

// clear keyword, switch tabs for category filtering
await page.fill('#search-keyword', '');
await page.waitForTimeout(600);
await page.waitForSelector('#search-results .search-result', { timeout: 3000 });
const imgRows = await page.locator('#search-results .search-result').evaluateAll(els => els.map(e => e.dataset.attachments || ''));
check('B4 images tab: all rows carry image attachment', imgRows.length === 1 && imgRows.every(a => a.includes('image')), JSON.stringify(imgRows));

// Files tab: seed has no non-image attachments → emptyCategory
await tabs.nth(2).click();
await page.waitForSelector('#search-results .search-hint', { timeout: 3000 });
const emptyText = await page.locator('#search-results .search-hint').textContent();
check('B10 files tab empty state text', emptyText?.trim() === '该栏目下暂无内容', emptyText || '');

// Card tab: all rows tool+Card badge
await tabs.nth(3).click();
await page.waitForSelector('#search-results .search-result', { timeout: 3000 });
const cardRows = await page.locator('#search-results .search-result').evaluateAll(els => els.map(e => ({ kind: e.dataset.kind, badge: e.querySelector('.search-result-type')?.textContent || '' })));
check('B4 cards tab: all tool+Card', cardRows.length === 1 && cardRows.every(r => r.kind === 'tool' && r.badge.includes('Card')), JSON.stringify(cardRows));

// Card tab: date tab disabled
const dateDisabled = await tabs.nth(4).getAttribute('aria-disabled');
check('§3 Card×Date: date tab aria-disabled', dateDisabled === 'true', String(dateDisabled));

// Back to All
await tabs.nth(0).click();
await page.waitForTimeout(600);
const dateEnabled = await tabs.nth(4).getAttribute('aria-disabled');
check('§3 date tab restored after leaving Cards', dateEnabled === 'false');

// B11: tool select linkage
await tabs.nth(1).click();
await page.waitForTimeout(300);
check('B11 tool select disabled in Images', await page.locator('#search-tool').isDisabled());
check('B11 tool select title hint', (await page.locator('#search-tool').getAttribute('title')) === '当前栏目下不适用工具筛选');
await tabs.nth(0).click();
await page.waitForTimeout(300);
check('B11 tool select restored in All', !(await page.locator('#search-tool').isDisabled()));

// B5: calendar popover
await tabs.nth(4).click();
await page.waitForSelector('#search-date-popover:not([hidden])', { timeout: 3000 });
const bf = await page.locator('#search-date-popover').evaluate(el => getComputedStyle(el).backdropFilter || getComputedStyle(el).webkitBackdropFilter);
check('B5 popover glass blur', (bf || '').includes('blur('), bf);
const overlayBg = await page.locator('#search-overlay').evaluate(el => getComputedStyle(el).backgroundColor);
check('B5 overlay stays transparent', overlayBg === 'rgba(0, 0, 0, 0)', overlayBg);
const weekdayCount = await page.locator('.cal-weekdays span').count();
check('B5 weekday header 7 cells', weekdayCount === 7);
const todayRing = await page.locator('.cal-day.cal-today').count();
check('B5 today has cal-today', todayRing === 1);
const futureDisabled = await page.locator('.cal-day[aria-disabled="true"]').evaluateAll(els =>
  els.every(e => +e.textContent > new Date().getDate()));
check('B5 future days aria-disabled', futureDisabled);

// B8: staging — pick yesterday then Cancel → no anchor, no new fetch
let fetchCount = 0;
page.on('request', (r) => { if (r.url().includes('/history')) fetchCount++; });
const firstRowBefore = await page.locator('#search-results .search-result').first().getAttribute('data-ts');
const yd = new Date(Date.now() - DAY);
await page.click(`.cal-day[data-d="${yd.getDate()}"]:not([aria-disabled="true"])`);
await page.click('.cal-cancel');
await page.waitForTimeout(600);
check('B8 cancel: no new history fetch', fetchCount === 0, `fetches=${fetchCount}`);
check('B8 cancel: popover closed', await page.locator('#search-date-popover').isHidden());
const firstRowAfter = await page.locator('#search-results .search-result').first().getAttribute('data-ts');
check('B8 cancel: list unchanged', firstRowBefore === firstRowAfter, `${firstRowBefore} vs ${firstRowAfter}`);

// B7: apply anchor = yesterday
await tabs.nth(4).click();
await page.waitForSelector('#search-date-popover:not([hidden])');
await page.click(`.cal-day[data-d="${yd.getDate()}"]:not([aria-disabled="true"])`);
await page.click('.cal-confirm');
await page.waitForTimeout(800);
const anchorEnd = new Date(yd.getFullYear(), yd.getMonth(), yd.getDate(), 23, 59, 59, 999).getTime();
const firstTs = +(await page.locator('#search-results .search-result').first().getAttribute('data-ts') || '0');
check('B7 anchor: first visible row ts <= anchor end', firstTs > 0 && firstTs <= anchorEnd, `ts=${firstTs} end=${anchorEnd}`);
const dateTabText = await tabs.nth(4).textContent();
check('B7 date tab shows anchor label + ×', (await tabs.nth(4).getAttribute('class') || '').includes('active') && await tabs.nth(4).locator('.search-date-clear').count() === 1, dateTabText || '');

// B7④: clear via × → back to latest browse
await tabs.nth(4).locator('.search-date-clear').click();
await page.waitForTimeout(800);
const firstTsCleared = +(await page.locator('#search-results .search-result').first().getAttribute('data-ts') || '0');
const newestTs = Math.max(...HISTORY.map(m => m.timestamp || 0));
check('B7 clear: back to latest', firstTsCleared === newestTs, `ts=${firstTsCleared} newest=${newestTs}`);
check('B7 clear: date tab label restored', (await tabs.nth(4).textContent())?.trim() === '日期');

// B6: Esc three-stage
await tabs.nth(4).click();
await page.waitForSelector('#search-date-popover:not([hidden])');
await page.fill('#search-keyword', 'abc');
await page.keyboard.press('Escape');
await page.waitForTimeout(200);
check('B6 Esc1: popover closed, modal open, kw kept',
  await page.locator('#search-date-popover').isHidden() &&
  await page.locator('#search-overlay.on').count() === 1 &&
  await page.inputValue('#search-keyword') === 'abc');
await page.waitForTimeout(500);   // let the 'abc' search settle
await page.keyboard.press('Escape');
await page.waitForTimeout(200);
check('B6 Esc2: kw cleared, modal open',
  await page.inputValue('#search-keyword') === '' && await page.locator('#search-overlay.on').count() === 1);

// A4 regression: arrow nav + activeIndex + Enter jump (key-based lookup rework)
// Focus must be back in the keyword input: after Esc1 the focus returned to
// the Date tab button, where arrows/Enter are the tab's own APG behavior.
await page.locator('#search-keyword').focus();
await page.waitForSelector('#search-results .search-result', { timeout: 3000 });
const active0 = await page.locator('#search-results .search-result.active').count();
check('A4 first result pre-selected on render', active0 === 1);
await page.keyboard.press('ArrowDown');
await page.waitForTimeout(150);
const actives = await page.locator('#search-results .search-result').evaluateAll(els => els.map(e => e.classList.contains('active')));
check('A4 ArrowDown moves active to 2nd', actives[1] === true && actives[0] === false, JSON.stringify(actives));
await page.keyboard.press('Enter');
await page.waitForTimeout(300);
check('A4 Enter closes modal', await page.locator('#search-overlay.on').count() === 0);

// A8 regression: focus trap — 20 Tabs never leave the modal subtree
await page.click('#search-btn');
await page.waitForSelector('#search-overlay.on', { timeout: 3000 });
await page.locator('#search-modal-close').focus();
let trapped = true;
for (let i = 0; i < 20; i++) {
  await page.keyboard.press('Tab');
  const inside = await page.evaluate(() => document.getElementById('search-modal')?.contains(document.activeElement));
  if (!inside) { trapped = false; break; }
}
check('A8 focus trap holds for 20 Tabs (tabs bar included)', trapped);

await page.keyboard.press('Escape');
await page.waitForTimeout(200);
check('B6 Esc3: modal closed', await page.locator('#search-overlay.on').count() === 0);

check('no page errors', pageErrors.length === 0, pageErrors.join('; '));

const failed = checks.filter(c => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
await browser.close();
process.exit(failed.length ? 1 : 0);
