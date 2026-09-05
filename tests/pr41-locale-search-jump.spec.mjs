// pr41-locale-search-jump.spec.mjs — regression for #41: search jump for tool
// results under a non-English locale.
//
// Root cause (upstream analysis by @JiashengZeng, maintainer fix 18fc201f):
// the tool card DOM localizes label/summary, while the search index text
// heads with the raw (English) summary — so the jump snippet never matched
// under zh-CN. The fix passes raw summary/content through both result-rebuild
// sites (runSearch literal + wrapItem) and tries candidates in order
// content → summary → legacy joined text in scrollToMessage.
//
// Scenarios:
//   S1  zh-CN + Edit tool message + search 'gamma' → .search-hit-flash within
//       8s, flashed card shows the localized summary, no failure toast.
//   S2  zh-CN + Bash tool (input JSON head + content) + search 'delta' → jump.
//   S3  English + Edit tool → no regression on the legacy path.
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node tests/pr41-locale-search-jump.spec.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SID = 'pr41-session-1';
const EDIT_LABEL = 'Edit\n  (/tmp/alpha/app.js)';
const EDIT_SUMMARY = 'File updated';   // raw backend summary — localized in the DOM under zh-CN
const EDIT_INPUT = JSON.stringify({
  file_path: '/tmp/alpha/app.js',
  old_string: 'const marker = 1;',
  new_string: 'const marker = 2;',
});
const EDIT_CONTENT = 'The file /tmp/alpha/app.js has been updated.\ngamma marker line replaced ok';
const BASH_LABEL = 'Bash\n  (grep)';
const BASH_SUMMARY = '2 lines';
const BASH_INPUT = JSON.stringify({ command: 'grep -n delta /tmp/delta.sh', description: 'find delta' });
const BASH_CONTENT = '3:delta value found';

const SEED = [
  { type: 'user', text: '请帮我重构示例文件', timestamp: Date.now() - 3600000 },
  { type: 'ai', text: '好的，我先用 Edit 修改文件。', timestamp: Date.now() - 3500000 },
  { type: 'tool', label: EDIT_LABEL, summary: EDIT_SUMMARY, input: EDIT_INPUT, content: EDIT_CONTENT },
  { type: 'tool', label: BASH_LABEL, summary: BASH_SUMMARY, input: BASH_INPUT, content: BASH_CONTENT },
];

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const browser = await chromium.launch();

/** One isolated page per locale; seed serves both the WS historyPage (chat
 *  DOM) and the REST history (search index), mirroring the real backend. */
async function newPage(locale) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', loc);
  }, locale);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'PR41', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: SEED, total: SEED.length, sessionId: SID }) });
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
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'PR41', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: SEED, hasMore: false, offset: SEED.length, total: SEED.length }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  // Chat DOM must carry the tool cards before any jump can target them.
  await page.waitForSelector('.row.tool .tool-card', { timeout: 10000 });
  await page.waitForTimeout(300);
  return { page, context, pageErrors };
}

/** Open the modal, search for `kw`, wait until exactly one tool result row
 *  remains, click it. */
async function searchAndClickToolRow(page, kw) {
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
  await page.fill('#search-keyword', kw);
  await page.waitForFunction(() => {
    const rows = [...document.querySelectorAll('#search-results .search-result')];
    return rows.length === 1 && rows[0].dataset.kind === 'tool';
  }, { timeout: 8000 });
  await page.click('#search-results .search-result[data-kind="tool"]');
}

/** Assert the jump flashed a tool card containing `marker` within 8s, capture
 *  the flash screenshot, then assert no failure toast appears afterwards. */
async function assertJump(page, marker, label, shotPath) {
  const flashed = await page.waitForFunction((m) => {
    const el = document.querySelector('.search-hit-flash');
    if (!el || !el.textContent.includes(m)) return false;
    // The flashed element must be the tool card (or its row), not any row.
    return !!(el.closest('.row.tool') || el.classList.contains('tool-card') || el.closest('.tool-card'));
  }, marker, { timeout: 8000 });
  check(`${label}: tool card flashed (marker "${marker}") within 8s`, !!flashed);

  // Flash lasts 1800ms — capture it now, while the highlight is live.
  if (shotPath) await page.screenshot({ path: shotPath, fullPage: true });

  // Failure toast would only arrive after the settle ticks (~1s+); flash
  // already proves success, but assert the absence explicitly.
  await page.waitForTimeout(1400);
  const toast = await page.evaluate(() => {
    const t = document.querySelector('.nebflow-toast-error, .nebflow-toast');
    return t ? t.textContent : '';
  });
  check(`${label}: no jump-failed toast`, !toast.includes('未能定位') && !toast.includes('定位该消息'), toast);

  // Close any re-opened state and let the 1800ms flash clear before reuse.
  await page.keyboard.press('Escape');
  await page.waitForTimeout(2100);
}

// ── S1: zh-CN + Edit tool ──
{
  const { page, context, pageErrors } = await newPage('zh-CN');
  // Localized summary in the DOM is exactly what the raw-index snippet cannot
  // match — the #41 precondition holds in this fixture.
  const cardText = await page.locator('.row.tool .tool-card').first().textContent();
  check('S1 precondition: card label localized (文件已更新, not "File updated")', cardText.includes('文件已更新') && !cardText.includes('File updated'), cardText.slice(0, 80));

  await searchAndClickToolRow(page, 'gamma');
  await assertJump(page, 'gamma', 'S1 zh-CN Edit jump', '/tmp/pr41-shots/pr41-zh-edit-jump.png');

  await page.screenshot({ path: '/tmp/pr41-shots/pr41-zh-edit-settled.png', fullPage: true });
  check('S1 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await context.close();
}

// ── S2: zh-CN + Bash tool (input JSON head + content) ──
{
  const { page, context, pageErrors } = await newPage('zh-CN');
  await searchAndClickToolRow(page, 'delta');
  await assertJump(page, 'delta', 'S2 zh-CN Bash jump', '/tmp/pr41-shots/pr41-zh-bash-jump.png');
  check('S2 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await context.close();
}

// ── S3: English + Edit tool — legacy path must not regress ──
{
  const { page, context, pageErrors } = await newPage('en');
  const cardText = await page.locator('.row.tool .tool-card').first().textContent();
  check('S3 precondition: card label stays raw English under en locale', cardText.includes('File updated'), cardText.slice(0, 80));

  await searchAndClickToolRow(page, 'gamma');
  await assertJump(page, 'gamma', 'S3 English Edit jump', '/tmp/pr41-shots/pr41-en-edit-jump.png');
  check('S3 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await context.close();
}

await browser.close();

const failed = checks.filter(c => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
process.exit(failed.length ? 1 : 0);
