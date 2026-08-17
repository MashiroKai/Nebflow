// activity-bar-smoke.mjs — pre-QA smoke for Activity Bar v1.1 (spec /tmp/activity-bar-spec.md §8).
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node tests/activity-bar-smoke.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SID = 'smoke-session-1';

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const browser = await chromium.launch();

function wireRoutes(page, { blockModules = false } = {}) {
  return page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: [], total: 0, sessionId: SID }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    // A5 discrimination: with module JS blocked, only inline scripts can restore state.
    if (blockModules && p.startsWith('/js/')) return route.fulfill({ status: 200, contentType: 'text/javascript', body: '' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
}

function wireWs(page) {
  return page.routeWebSocket(/\/ws/, (ws) => {
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
}

async function boot(page) {
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

// ── Main context (A1-A4, A6-A12, A15) ─────────────────────
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(e.message));
await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));
await wireRoutes(page);
await wireWs(page);
await boot(page);

// A1: button order — avatar, files-btn, spacer (messages/contacts unregistered → absent); no todos slot
const order = await page.locator('#activity-bar > *').evaluateAll(els => els.map(e => e.id || e.className));
check('A1 child 1 = #activity-avatar', order[0] === 'activity-avatar', JSON.stringify(order));
check('A1 child 2 = #files-btn', order[1] === 'files-btn', JSON.stringify(order.slice(0, 3)));
check('A1 child 3 = .activity-spacer', (order[2] || '').includes('activity-spacer'), order[2]);
check('A1 no #todos-btn', await page.locator('#todos-btn').count() === 0);

// A2: fresh load — expanded + files active + exactly 1 active panel containing explorer
check('A2 body not collapsed', await page.evaluate(() => !document.body.classList.contains('sidebar-collapsed')));
check('A2 #files-btn.active', await page.locator('#files-btn.active').count() === 1);
const activePanels = page.locator('#sidebar-panel .panel.active');
check('A2 exactly 1 .panel.active', await activePanels.count() === 1);
check('A2 active panel contains #explorer-section', await activePanels.locator('#explorer-section').count() === 1);

// A3: click active icon → collapse
await page.click('#files-btn');
check('A3 body collapsed', await page.evaluate(() => document.body.classList.contains('sidebar-collapsed')));
check('A3 #files-btn loses .active', await page.locator('#files-btn.active').count() === 0);
check('A3 LS sidebar_collapsed=true', await page.evaluate(() => localStorage.getItem('nebflow_sidebar_collapsed')) === 'true');
check('A3 aria-pressed=false', (await page.locator('#files-btn').getAttribute('aria-pressed')) === 'false');

// A4: click again → A2 restored
await page.click('#files-btn');
check('A4 body expanded again', await page.evaluate(() => !document.body.classList.contains('sidebar-collapsed')));
check('A4 #files-btn.active restored', await page.locator('#files-btn.active').count() === 1);
check('A4 exactly 1 .panel.active restored', await page.locator('#sidebar-panel .panel.active').count() === 1);
check('A4 LS panel id persisted', await page.evaluate(() => localStorage.getItem('nebflow_sidebar_active_panel')) === 'files');

// A6: persisted panel id reload → files active; unregistered id → fallback files
await page.evaluate(() => localStorage.setItem('nebflow_sidebar_active_panel', 'bogus-panel'));
await page.reload();
await boot(page);
check('A6 unregistered id falls back to files', await page.locator('#files-btn.active').count() === 1 &&
  (await page.locator('#sidebar-panel .panel.active').getAttribute('data-panel-id')) === 'files');

// A7: ⌘B equivalence — collapse, expand, restores files active
await page.locator('body').click({ position: { x: 700, y: 500 } }); // focus out of inputs
await page.keyboard.press('Meta+b');
check('A7 ⌘B collapses', await page.evaluate(() => document.body.classList.contains('sidebar-collapsed')));
check('A7 ⌘B: files-btn loses active', await page.locator('#files-btn.active').count() === 0);
await page.keyboard.press('Meta+b');
check('A7 ⌘B expands + restores files active', await page.locator('#files-btn.active').count() === 1 &&
  await page.evaluate(() => !document.body.classList.contains('sidebar-collapsed')));

// A12: header #sidebar-toggle syncs with #files-btn
await page.click('#sidebar-toggle');
check('A12 header toggle collapses + files-btn inactive',
  await page.evaluate(() => document.body.classList.contains('sidebar-collapsed')) &&
  await page.locator('#files-btn.active').count() === 0);
await page.click('#sidebar-toggle');
check('A12 header toggle expands + files-btn active', await page.locator('#files-btn.active').count() === 1);

// A8: collapsed — Activity Bar stays 48px visible
await page.click('#files-btn'); // collapse
const barBox = await page.locator('#activity-bar').evaluate(el => ({
  w: el.offsetWidth, vis: getComputedStyle(el).visibility,
}));
check('A8 collapsed: bar 48px visible', barBox.w === 48 && barBox.vis === 'visible', JSON.stringify(barBox));

// A9: active state — no left edge bar, shadow present (C1)
await page.click('#files-btn'); // expand
const activeStyle = await page.locator('#files-btn').evaluate(el => {
  const cs = getComputedStyle(el);
  return { blw: cs.borderLeftWidth, shadow: cs.boxShadow };
});
check('A9 border-left-width 0px', activeStyle.blw === '0px', activeStyle.blw);
check('A9 box-shadow present', activeStyle.shadow !== 'none', activeStyle.shadow);

// A11: explorer zero regression
for (const id of ['#explorer-new-file-btn', '#explorer-new-folder-btn', '#explorer-folder-btn']) {
  check(`A11 ${id} visible`, await page.locator(id).isVisible());
}
check('A11 #explorer-tree inside active panel',
  await page.locator('#sidebar-panel .panel.active #explorer-tree').count() === 1);

// A10: 375px — Manager ruling (2026-08-17, per msg-search B12 precedent):
// open-panel scrollWidth ≤ collapsed baseline + 300px panel budget (both
// states' overflow contributors — .header-right / offscreen #canvas-panel —
// are pre-existing backlog, not gated on this feature); bar subtree ≤ 48.
await page.setViewportSize({ width: 375, height: 812 });
await page.waitForTimeout(100);
const openSw = await page.evaluate(() => document.documentElement.scrollWidth);
await page.click('#files-btn'); // collapse
await page.waitForTimeout(400); // let collapse animation settle
const collapsedSw = await page.evaluate(() => document.documentElement.scrollWidth);
const barSw = await page.locator('#activity-bar').evaluate(el => el.scrollWidth);
check('A10 375px: open scrollWidth ≤ collapsed + 300px budget', openSw <= collapsedSw + 300, `${openSw} vs ${collapsedSw}+300`);
check('A10 375px: bar subtree scrollWidth ≤ 48', barSw <= 48, String(barSw));
await page.setViewportSize({ width: 1440, height: 900 });
await page.click('#files-btn'); // expand for the i18n checks

// A15: i18n — zh default then en
await page.waitForTimeout(200);
const zhTitle = await page.locator('#files-btn').getAttribute('title');
const zhPanel = await page.locator('#panel-title-explorer').textContent();
check('A15 zh: files-btn title = 文件', zhTitle === '文件', String(zhTitle));
check('A15 zh: panel title = 文件浏览器', zhPanel === '文件浏览器', String(zhPanel));
await page.evaluate(() => localStorage.setItem('nebflow_locale', 'en'));
await page.reload();
await boot(page);
const enTitle = await page.locator('#files-btn').getAttribute('title');
const enPanel = await page.locator('#panel-title-explorer').textContent();
check('A15 en: files-btn title = Files', enTitle === 'Files', String(enTitle));
check('A15 en: panel title = Explorer', enPanel === 'Explorer', String(enPanel));

// ── A5: no-flicker restore — with ALL module JS blocked, the inline scripts
// alone must restore collapsed + strip active states before first paint. ──
const page5 = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
await page5.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'smoke-token');
  localStorage.setItem('nebflow_sidebar_collapsed', 'true');
  localStorage.setItem('nebflow_sidebar_active_panel', 'files');
});
await wireRoutes(page5, { blockModules: true });
await page5.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
const pre = await page5.evaluate(() => ({
  collapsed: document.body.classList.contains('sidebar-collapsed'),
  btnActive: document.getElementById('files-btn')?.classList.contains('active') ?? null,
  panelActive: document.getElementById('panel-sessions')?.classList.contains('active') ?? null,
  ariaPressed: document.getElementById('files-btn')?.getAttribute('aria-pressed') ?? null,
}));
check('A5 inline restore: body collapsed at DCL (modules blocked)', pre.collapsed === true);
check('A5 inline restore: files-btn inactive at DCL', pre.btnActive === false);
check('A5 inline restore: panel inactive at DCL', pre.panelActive === false);
check('A5 inline restore: aria-pressed=false at DCL', pre.ariaPressed === 'false');
await page5.close();

// ── A13: prefers-reduced-motion ───────────────────────────
const ctxRm = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
const pageRm = await ctxRm.newPage();
await pageRm.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));
await wireRoutes(pageRm);
await wireWs(pageRm);
await boot(pageRm);
const dur = await pageRm.locator('#sidebar').evaluate(el => getComputedStyle(el).transitionDuration);
const maxDur = Math.max(...dur.split(',').map(d => parseFloat(d) || 0));
check('A13 reduced-motion: #sidebar transition-duration ≤ 0.01s', maxDur <= 0.011, dur);
await ctxRm.close();

check('no page errors', pageErrors.length === 0, pageErrors.join('; '));

const failed = checks.filter(c => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
await browser.close();
process.exit(failed.length ? 1 : 0);
