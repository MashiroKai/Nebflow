// todo-mobile-edit-real.spec.mjs — 真手机入口到待办详情编辑的 Chromium 闭环。
// 静态资源由 page.route 读取本仓真 web 树；WS 与 todoStore 使用本地夹具，不访问后端或生产数据。
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
  // 从真实手机任务圆钮进入任务页面；不点击桌面胶囊、不直接调用页面渲染。
  await taskButton.click();
  const taskPage = page.locator(".mobile-page[data-page='tasks']");
  await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='tasks']")?.classList.contains('live'));
  const row = taskPage.locator(".tfl-row[data-id='edit-mobile-1']");
  await row.waitFor({ state: 'visible' });
  await page.screenshot({ path: join(EVID, '01-mobile-todo-list.png') });

  // 浏览详情后 Escape 退出，不改变任何字段。
  await row.locator('.tfl-row-main').click();
  const detail = taskPage.locator('.tfl-hit');
  await detail.waitFor({ state: 'visible' });
  await detail.locator('.tfl-hit-edit').waitFor({ state: 'visible' });
  await page.screenshot({ path: join(EVID, '02-mobile-detail.png') });
  await page.keyboard.press('Escape');
  await detail.waitFor({ state: 'detached' });
  const afterExit = await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const item = todoStore.visibleItems().find((x) => x.id === 'edit-mobile-1');
    return { name: item.name, detail: item.detail };
  });
  assert.deepEqual(afterExit, { name: '原事项名', detail: '原详情' }, '退出详情不得写入待办');

  // 在详情里选择编辑，修改两字段并通过现有 Enter 语义保存。
  await row.locator('.tfl-row-main').click();
  await detail.waitFor({ state: 'visible' });
  await taskPage.locator('.tfl-hit-edit').click();
  const editor = taskPage.locator(".tfl-row[data-id='edit-mobile-1'].tfl-row-editing .tfl-edit-panel");
  await editor.waitFor({ state: 'visible' });
  const name = editor.locator('.tfl-edit-name');
  const notes = editor.locator('.tfl-edit-notes');
  await name.fill('手机编辑后名称');
  await notes.fill('手机编辑后详情');
  await page.waitForTimeout(500);
  const editorState = await editor.evaluate((el) => {
    const rect = (node) => { const r = node.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; };
    return { panel: rect(el), name: rect(el.querySelector('.tfl-edit-name')), notes: rect(el.querySelector('.tfl-edit-notes')) };
  });
  assert.ok(editorState.name.height > 0 && editorState.notes.height > 0, `edit fields must be visibly laid out: ${JSON.stringify(editorState)}`);
  await page.screenshot({ path: join(EVID, '03-mobile-edit-before-save.png') });
  await notes.press('Enter');
  await editor.waitFor({ state: 'detached' });
  const saved = await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const item = todoStore.visibleItems().find((x) => x.id === 'edit-mobile-1');
    return { name: item.name, detail: item.detail, dataSource: todoStore.dataSource };
  });
  assert.deepEqual(saved, { name: '手机编辑后名称', detail: '手机编辑后详情', dataSource: 'fixture' });
  await page.screenshot({ path: join(EVID, '04-mobile-edit-saved.png') });
  assert.deepEqual(errors, [], '页面不得产生未处理异常');
  console.log('PASS 真实 mobile task-button → list → detail → edit → save; detail exit preserved fixture');
  console.log(`PASS evidence: ${EVID}`);
  await context.close();
} finally {
  await browser.close();
}
