// msg-search-v3-stream-smoke.mjs — pre-QA smoke for §6.3 streaming/pagination.
// Seed: 130 messages across 5 days (26/day). Verifies B9 lazy rendering +
// cursor pagination and B13 anchor-mode bidirectional continuity.
// Run: node tests/msg-search-v3-stream-smoke.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SID = 'stream-session';
const DAY = 86400000;
const now = new Date();
const today0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
// 26 messages per day across 5 days: D-4 … D (today). Hours 0..25 → wrap is fine.
const HISTORY = [];
for (let day = 4; day >= 0; day--) {
  for (let i = 0; i < 26; i++) {
    HISTORY.push({
      type: i % 2 ? 'ai' : 'user',
      text: `day-${day} msg-${String(i).padStart(2, '0')}`,
      timestamp: today0 - day * DAY + (i % 24) * 3600000 + Math.floor(i / 24) * 60000,
    });
  }
}

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(e.message));

const historyReqs = [];
await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));

await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/api/sessions') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Stream', agentName: 'Nebula' }], activeId: SID }) });
  }
  if (p.startsWith(`/api/sessions/${SID}/history`)) {
    historyReqs.push({ before: url.searchParams.get('before'), after: url.searchParams.get('after'), limit: url.searchParams.get('limit') });
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
  const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Stream', agentName: 'Nebula' }], folders: [], activeId: SID }));
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

await page.click('#search-btn');
await page.waitForSelector('#search-results .search-result', { timeout: 3000 });
await page.waitForTimeout(300);

const countRows = () => page.locator('#search-results .search-result').count();
const firstTs = () => page.locator('#search-results .search-result').first().getAttribute('data-ts');

// B9①: first render ≤ 50
const initial = await countRows();
check('B9① first render <= 50', initial <= 50, `rendered=${initial}`);
check('B9 init: exactly 1 history fetch with limit', historyReqs.length === 1 && historyReqs[0].limit === '100' && !historyReqs[0].before, JSON.stringify(historyReqs));

// B9②: scroll down → next render batch, NO new request
await page.locator('#search-results').evaluate(el => { el.scrollTop = el.scrollHeight; });
await page.waitForTimeout(500);
const afterScroll1 = await countRows();
check('B9② scroll renders next batch, no new request', afterScroll1 > initial && historyReqs.length === 1, `rendered=${afterScroll1} reqs=${historyReqs.length}`);

// B9③: batches exhausted (100 loaded, all rendered) → sentinel fires exactly
// one before-cursor request; rows grow, first row untouched.
const firstBefore = await firstTs();
await page.locator('#search-results').evaluate(el => { el.scrollTop = el.scrollHeight; });
await page.waitForTimeout(600);
const afterScroll2 = await countRows();
const beforeReqs = historyReqs.filter(r => r.before);
check('B9③ exhausted → exactly 1 before-cursor request', beforeReqs.length === 1, `reqs=${JSON.stringify(historyReqs)}`);
check('B9③ rows grew, first row stable', afterScroll2 > afterScroll1 && (await firstTs()) === firstBefore, `rendered=${afterScroll2}`);

// Drain to the very end (130 total): keep scrolling until count stabilizes.
let prev = 0;
for (let i = 0; i < 8; i++) {
  await page.locator('#search-results').evaluate(el => { el.scrollTop = el.scrollHeight; });
  await page.waitForTimeout(500);
  const c = await countRows();
  if (c === prev) break;
  prev = c;
}
const total = await countRows();
check('B9 stream drains to all 130 rows', total === 130, `rendered=${total}`);
const reqsAfterDrain = historyReqs.length;
check('B9 no further requests once exhausted', reqsAfterDrain === 2, `reqs=${reqsAfterDrain}`);

// ── B13: anchor D-2, then scroll both directions across the day boundary ──
const anchorDay = new Date(today0 - 2 * DAY);   // D-2 (has messages); D-1 and D-3 also have messages
await page.click('#search-tab-date');
await page.waitForSelector('#search-date-popover:not([hidden])');
await page.click(`.cal-day[data-y="${anchorDay.getFullYear()}"][data-m0="${anchorDay.getMonth()}"][data-d="${anchorDay.getDate()}"]`);
const reqsBeforeAnchor = historyReqs.length;
await page.click('.cal-confirm');
await page.waitForTimeout(800);

const anchorEnd = new Date(anchorDay.getFullYear(), anchorDay.getMonth(), anchorDay.getDate(), 23, 59, 59, 999).getTime();
const anchorFirstTs = +(await firstTs() || '0');
check('B7/B13 anchor page: first row ts <= anchor end', anchorFirstTs > 0 && anchorFirstTs <= anchorEnd, `ts=${anchorFirstTs}`);
const anchorReqs = historyReqs.slice(reqsBeforeAnchor);
check('B7 anchor: exactly 1 anchor-page request with before=anchorEnd', anchorReqs.length === 1 && anchorReqs[0].before === String(anchorEnd), JSON.stringify(anchorReqs));

// Scroll UP to the top → after-cursor page (newer: D-1 / today items)
const tsSeqBefore = await page.locator('#search-results .search-result').evaluateAll(els => els.map(e => e.dataset.ts));
await page.locator('#search-results').evaluate(el => { el.scrollTop = 0; });
await page.waitForTimeout(700);
const afterReqs = historyReqs.filter(r => r.after);
check('B13 scroll-top → after-cursor request', afterReqs.length >= 1, JSON.stringify(afterReqs));

const tsSeqAfter = await page.locator('#search-results .search-result').evaluateAll(els => els.map(e => e.dataset.ts));
// Old entries remain a contiguous in-place subsequence (zero removal/reorder)
const oldJoined = tsSeqBefore.join(',');
const newJoined = tsSeqAfter.join(',');
check('B13③ existing entries preserved in place', newJoined.includes(oldJoined), `old=${tsSeqBefore.length} new=${tsSeqAfter.length}`);
// Cross-day coexistence: rows from D-2 and D-1 (or newer) in one container
const dayOf = (ts) => new Date(+ts).toDateString();
const daysPresent = new Set(tsSeqAfter.map(dayOf));
check('B13① cross-day coexistence (>=2 days in one container)', daysPresent.size >= 2, [...daysPresent].join(' | '));

check('no page errors', pageErrors.length === 0, pageErrors.join('; '));

const failed = checks.filter(c => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
await browser.close();
process.exit(failed.length ? 1 : 0);
