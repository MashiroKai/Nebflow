// lib.mjs — shared harness for the mobile diff survey.
//
// Serves the REAL web tree (src/main/resources/web) through Playwright route
// interception, boots the real app, and exposes injectors for the two current
// diff faces:
//   · delegate diff  → injected delegate bubble → chip → Canvas 'filechanges' tab
//   · canvas ask diff → askEditApplied frame → Canvas 'askreview' tab
// Same pattern as tests/diff-filehead-capsule-shots.mjs and
// tests/canvas-ask-inline-review.spec.mjs (real tree + real WS stub).
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, normalize, extname } from 'node:path';

/** Client-identical ask session id (sha256(absPath) first 8 hex, prefix ask-). */
export function sidOf(absPath) {
  return 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);
}

const WEB = process.env.SURVEY_WEB
  || '/private/tmp/stand-mobile-diff-survey-20261118/src/main/resources/web';

export const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

export const SID = 'survey-root';

/**
 * @param {{width:number,height:number,scheme:'dark'|'light',mobile?:boolean,dsf?:number}} o
 */
export async function boot(browser, o) {
  const ctx = await browser.newContext({
    viewport: { width: o.width, height: o.height },
    colorScheme: o.scheme,
    deviceScaleFactor: o.dsf ?? 2,
    isMobile: !!o.mobile,
    hasTouch: !!o.mobile,
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula', title: 'root', updatedAt: Date.now() }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'askOpen') {
        const abs = (m.file && m.file.absPath) || '';
        const sid = sidOf(abs);
        ws.send(JSON.stringify({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: abs, fileName: (m.file && m.file.name) || 'file', messages: [], review: null }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForSelector('#input', { state: 'visible', timeout: 25000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  if (o.mobile) {
    await page.evaluate(() => import('/js/mobile/navController.js').then((m) => m.touchLayer()));
    await page.waitForTimeout(200);
  }
  return { ctx, page, errors };
}

/** Inject a delegate-completed bubble carrying a fileChanges payload. */
export async function injectDelegate(page, payload, header = 'DELEGATE · survey · diff · COMPLETED') {
  await page.evaluate(({ payload, header, sid }) => import('/js/state.js').then((mod) => {
    mod.default.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: sid, source: 'delegate', eventType: 'completed',
        header, text: 'Survey fixture: real engine file-changes report.',
        timestamp: Date.now(), fileChanges: payload,
      }),
    });
  }), { payload, header, sid: SID });
  await page.waitForTimeout(250);
}

/** Mobile layer: bring the canvas page to the front (panes are re-parented and
 *  hidden behind the chat page otherwise). */
export async function revealCanvas(page) {
  await page.evaluate(() => import('/js/mobile/navController.js').then((m) => m.navigateTo('canvas')));
  await page.waitForTimeout(350);
}

/** Click the batch's diff chip → opens the Canvas 'filechanges' reader. */
export async function openDelegateReader(page, timeout = 20000) {
  await page.locator('.bubble.injected .nf-rcpt-note').last().click({ timeout });
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .nf-diff-body', { state: 'attached', timeout });
  await revealCanvas(page);
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .nf-diff-row', { state: 'visible', timeout });
  await page.waitForTimeout(400);
}

/** Open a file tab + the ask dock so the page has an ask session ledger; returns
 *  the client-derived session id for that path. */
export async function ensureAskSession(page, absPath, fileName) {
  await page.evaluate(({ absPath, fileName }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: fileName, content: '# ' + fileName + '\n\nmock body\n', absPath, pinned: true },
    }));
  }, { absPath, fileName });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'attached', timeout: 15000 });
  // make the doc tab the active canvas entry, then open the ask dock (the fab is
  // display:none-gated until the active entry is a file tab)
  await page.evaluate(async (absPath) => {
    const c = await import('/js/canvas.js');
    c.setActiveTab('file:' + absPath);
  }, absPath);
  await page.waitForTimeout(250);
  await page.evaluate(() => { const b = document.querySelector('.ask-fab:not(.note-fab)'); if (b) b.click(); });
  await page.waitForTimeout(700);
  return sidOf(absPath);
}

/** Inject an askEditApplied frame → opens the Canvas 'askreview' tab. */
export async function injectAsk(page, ask) {
  await page.evaluate((ask) => import('/js/state.js').then((mod) => {
    mod.default.ws.onmessage({
      data: JSON.stringify({
        type: 'askEditApplied', sessionId: ask.sessionId, changeId: 'c1',
        path: ask.path, fileName: ask.fileName, baseText: ask.baseText,
        currentText: ask.currentText, hunks: ask.hunks, count: ask.count ?? 1,
        updatedAt: Date.now(),
      }),
    });
  }), ask);
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${ask.sessionId}"] .nf-ar-body`, { state: 'attached', timeout: 20000 });
  await revealCanvas(page);
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${ask.sessionId}"] .nf-ar-line`, { state: 'visible', timeout: 20000 });
  await page.waitForTimeout(400);
}

export const raf = (page, n = 2) => page.evaluate((k) => new Promise((res) => {
  let i = 0;
  const step = () => { if (++i >= k) res(); else requestAnimationFrame(step); };
  requestAnimationFrame(step);
}), n);

export { chromium };
