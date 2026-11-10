// todo-mobile-edit-real.spec.mjs — Chromium loop from the real mobile task
// button through the Apple-face todo list into item edit and save.
// Static assets are served by page.route from this repo's real web tree; WS and
// todoStore run on local fixtures — no backend, no production data.
// Run: node tests/todo-mobile-edit-real.spec.mjs

import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, 'reports-evidence', 'todo-mobile-edit');
mkdirSync(EVID, { recursive: true });
const SID = 'todo-mobile-edit-real-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => Promise.resolve(), configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    config(); sessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') config();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws?.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.evaluate(() => {
    const now = Date.now();
    return import('/js/todoStore.js').then(({ todoStore }) => todoStore.__seed({
      tagList: [{ id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' }],
      itemList: [{ id: 'edit-mobile-1', tagId: 'tag-work', title: '原事项名', notes: '原详情', due: null, priority: 1, createdAt: now }],
    }));
  });
}

const browser = await chromium.launch();
try {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true, colorScheme: 'light' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await boot(page);
  const taskButton = page.locator("[data-chrome='tasks']");
  await taskButton.waitFor({ state: 'visible' });
  // Enter the tasks page from the real mobile task button; no desktop capsule
  // clicks and no direct render calls.
  await taskButton.click();
  const taskPage = page.locator(".mobile-page[data-page='tasks']");
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='tasks']")?.classList.contains('live'));
  // Apple home: the seeded tag is one "My Lists" row; open its list page.
  const row = taskPage.locator('.ta-row.ta-row-tag', { hasText: '工作' });
  await row.waitFor({ state: 'visible' });
  await row.click();
  const listPage = page.locator(".mobile-page[data-page='todo-apple-list']");
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-list']")?.classList.contains('live'));
  const itemBody = listPage.locator(".ta-item[data-item-id='edit-mobile-1'] .ta-item-body");
  await itemBody.waitFor({ state: 'visible' });
  await page.screenshot({ path: join(EVID, '01-mobile-todo-list.png') });

  // Browse the item page, then go back without editing — no store writes.
  await itemBody.click();
  const itemPage = page.locator(".mobile-page[data-page='todo-apple-item']");
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-item']")?.classList.contains('live'));
  const titleInput = itemPage.locator('.ta-title-input');
  await titleInput.waitFor({ state: 'visible' });
  assert.equal(await titleInput.inputValue(), '原事项名', 'item page must show the current title');
  await page.screenshot({ path: join(EVID, '02-mobile-detail.png') });
  await itemPage.locator('.mobile-back-btn').click();
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-list']")?.classList.contains('live'));
  const afterExit = await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const item = todoStore.visibleItems().find((x) => x.id === 'edit-mobile-1');
    return { name: item.name, detail: item.detail };
  });
  assert.deepEqual(afterExit, { name: '原事项名', detail: '原详情' }, 'leaving the item page without saving must not write the todo');

  // Reopen the item, reveal the notes field with the info button, edit both
  // fields and save with the item page save button.
  await itemBody.click();
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-item']")?.classList.contains('live'));
  await titleInput.fill('手机编辑后名称');
  await itemPage.locator('.ta-info').click();
  const notes = itemPage.locator('.ta-notes');
  await notes.waitFor({ state: 'visible' });
  await notes.fill('手机编辑后详情');
  await page.waitForTimeout(500);
  const editorState = await itemPage.evaluate((el) => {
    const rect = (node) => { const r = node.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; };
    return { panel: rect(el.querySelector('.ta-edit')), title: rect(el.querySelector('.ta-title-input')), notes: rect(el.querySelector('.ta-notes')) };
  });
  assert.ok(editorState.title.height > 0 && editorState.notes.height > 0, `edit fields must be visibly laid out: ${JSON.stringify(editorState)}`);
  await page.screenshot({ path: join(EVID, '03-mobile-edit-before-save.png') });
  await itemPage.locator('.ta-save').click();
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-list']")?.classList.contains('live'));
  const saved = await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const item = todoStore.visibleItems().find((x) => x.id === 'edit-mobile-1');
    return { name: item.name, detail: item.detail, dataSource: todoStore.dataSource };
  });
  assert.deepEqual(saved, { name: '手机编辑后名称', detail: '手机编辑后详情', dataSource: 'fixture' });
  await page.screenshot({ path: join(EVID, '04-mobile-edit-saved.png') });
  assert.deepEqual(errors, [], 'page must not produce unhandled exceptions');
  console.log('PASS real mobile task-button → Apple home → list → item page → edit → save; leaving without save preserved the fixture');
  console.log(`PASS evidence: ${EVID}`);
  await context.close();
} finally {
  await browser.close();
}
