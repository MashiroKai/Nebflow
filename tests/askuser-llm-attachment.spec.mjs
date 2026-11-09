import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const WEB = path.join(REPO, 'src/main/resources/web');
const PORT = await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
  server.on('error', reject);
});
const ORIGIN = `http://127.0.0.1:${PORT}`;
let server;
let backend;
let frameSeq = 0;
const opened = [];
const requested = [];

function mime(file) {
  return ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' })[path.extname(file)] || 'application/octet-stream';
}

test.beforeAll(async () => {
  server = await import('node:http').then(({ createServer }) => createServer((req, res) => {
    const url = new URL(req.url, ORIGIN);
    if (url.pathname.startsWith('/api/')) { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{}'); return; }
    const file = path.resolve(WEB, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(WEB + path.sep)) { res.writeHead(403); res.end(); return; }
    try { res.writeHead(200, { 'content-type': mime(file) }); res.end(fs.readFileSync(file)); }
    catch { res.writeHead(404); res.end(); }
  }));
  await new Promise(resolve => server.listen(PORT, '127.0.0.1', resolve));
});

test.afterAll(async () => { if (server) await new Promise(resolve => server.close(resolve)); });

test('AskUser question attachment cards open Canvas and gate access; legacy history keeps its face', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'test-token');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('stand_sw', '0');
  });
  await page.routeWebSocket(/\/ws/, ws => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'llm-ask-session', name: 'Root' }], folders: [], activeId: 'llm-ask-session' }));
    ws.onMessage(raw => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      if (msg.type === 'getSessions' || msg.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'llm-ask-session', name: 'Root' }], folders: [], activeId: 'llm-ask-session' }));
      if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      if (msg.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.route('**/api/nf-ticket', route => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { /* invalid fixture request */ }
    return route.fulfill({ json: { tickets: Object.fromEntries(paths.map(p => [p, { t: 'ticket-' + p, exp: Date.now() + 60000, uses: -1 }])), rejected: [] } });
  });
  await page.route('**/api/nf-file**', async route => {
    const u = new URL(route.request().url());
    requested.push({ path: u.searchParams.get('path'), range: route.request().headers().range || '' });
    if (u.searchParams.get('path') === '/fixture/private.md') return route.fulfill({ status: 403, body: 'denied' });
    return route.fulfill({ status: 200, body: 'fixture bytes', headers: { 'content-type': 'text/plain' } });
  });
  await page.route('**/*', route => {
    const u = new URL(route.request().url());
    if (u.pathname.startsWith('/api/') || u.pathname === '/ws') return route.fallback();
    const file = path.resolve(WEB, '.' + decodeURIComponent(u.pathname === '/' ? '/index.html' : u.pathname));
    if (!file.startsWith(WEB + path.sep)) return route.fulfill({ status: 403 });
    try { return route.fulfill({ status: 200, contentType: mime(file), body: fs.readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'missing' }); }
  });
  await page.goto(ORIGIN + '/');
  await page.waitForFunction(async () => { const s = (await import('/js/state.js')).default; return s.activeSessionId === 'llm-ask-session' && s.ws?.readyState === 1; });
  await page.evaluate(async () => {
    window.__opened = [];
    const chat = await import('/js/chat.js');
    const state = (await import('/js/chatView.js')).activeView;
    window.__ask = { chat, state };
    window.__attention = [];
    window.addEventListener('session-attention', e => window.__attention.push(e.detail));
    window.__toastCount = 0;
    window.__showToast = () => { window.__toastCount++; };
    window.addEventListener('workspace-open-item', e => window.__opened.push(e.detail));
  });
  await page.evaluate(() => { window.__opened = []; });
  const mount = (filePath, source) => page.evaluate(({ filePath, source }) => {
    const { chat, state } = window.__ask;
    return chat.renderAskUser([{ question: 'Open attachment', options: [{ label: 'OK' }] }], state.sessionId, 'Agent', 'llm-ask-' + Math.random(), source, [filePath]);
  }, { filePath, source });

  const entry = await mount('/fixture/readme.md', { llmQuestion: true, project: 'A', nodeName: 'Question' });
  const card = page.locator('.option-box').last();
  expect(await card.locator('.ask-att-question-card').count()).toBe(1);
  expect(await card.locator('.ask-att-file').count()).toBe(0);
  const keyCard = card.locator('.ask-att-question-card');
  await keyCard.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => page.evaluate(() => window.__opened.length)).toBe(1);
  expect(await page.evaluate(() => window.__opened[0])).toMatchObject({ id: 'file:/fixture/readme.md', title: 'readme.md' });
  expect(requested.some(r => r.path === '/fixture/readme.md')).toBe(true);
  expect(await page.evaluate(() => window.__attention.length)).toBe(0);

  const denied = await mount('/fixture/private.md', { llmQuestion: true });
  const deniedCard = page.locator('.option-box').last();
  await expect(deniedCard.locator('.ask-att-unavailable')).toBeVisible();
  await expect(deniedCard.locator('.ask-att-retry')).toBeVisible();
  expect(await page.evaluate(() => window.__opened.length)).toBe(1);
  expect(await page.evaluate(() => window.__attention.length)).toBe(0);
  expect(await page.evaluate(() => window.__toastCount)).toBe(0);

  await page.evaluate(async () => {
    const { chat } = window.__ask;
    const row = document.createElement('div');
    row.className = 'row ai';
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
    row.appendChild(bubble);
    document.querySelector('#chat').appendChild(row);
    chat.renderAskUserHistory(bubble, [{ question: 'Legacy history', options: [{ label: 'OK' }] }], 'answered', 'legacy', [], [{ path: '/fixture/ordinary.md', name: 'ordinary.md' }]);
  });
  const legacy = page.locator('.option-box').last();
  expect(await legacy.locator('.ask-att-question-card').count()).toBe(0);
  expect(await legacy.locator('.ask-att-file').count()).toBe(1);
  expect(await page.evaluate(() => window.__attention.length)).toBe(0);
  expect(entry).toMatchObject({ type: 'askUser', attachments: ['/fixture/readme.md'] });
  expect(denied.attachments).toEqual(['/fixture/private.md']);
  expect(pageErrors).toEqual([]);
});
