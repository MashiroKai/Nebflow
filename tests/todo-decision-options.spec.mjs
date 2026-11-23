// todo-decision-options.spec.mjs — 待办决策选项渲染回归门（2026-11-21 23:48 作者报障）。
//
// 报障：待办详情的 decision 选项全部渲染为字面量 `[object Object]`。
// 真源形态（引擎 `TodoDecision{question,options:[{id,title,detail,media}],multiSelect,...}`
// + `TodoTaxonomy.normalizeDecision` 只收对象形、字符串 options 在写侧即被拒）⇒
// 对象一定到前端；前端曾用 `String(option)` 渲染 ⇒ `[object Object]`。
//
// 断言：
//   D1 列表详情的决策选项：渲染为各选项 title（可读文本），绝不出现 `[object Object]`；
//      有选项 detail 时附显示的次级行。
//   D2 快速决策卡：选项 title 可读；选中后回传正文（`data-qd-answer-last.sent`）也是
//      可读文本而非 `[object Object]`（pickedLabels 同一破碎点）。
//   D3 options 缺失/为空 ⇒ 不渲染选项列表（列表详情与快速决策卡两处）。
//
// 数据面 = 静态 web 树 + WS 桩 + todoStore.__seed 夹具（零后端、不碰 :6138）。
// Run: npx playwright test tests/todo-decision-options.spec.mjs --workers=1

import { test, expect } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// TODO_DECISION_WEB_ROOT = 指向另一份 web 树（负对照：拿改前源码跑同一断言）。
const WEB = process.env.TODO_DECISION_WEB_ROOT
  ? normalize(process.env.TODO_DECISION_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = process.env.TODO_DECISION_SHOT_DIR
  || join(process.cwd(), '..', '..', '..', 'tmp', 'todo-panel-disclosure', 'evidence', 'decision-shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todo-decision-root';

test.use({ viewport: { width: 375, height: 812 }, colorScheme: 'light', hasTouch: true, locale: 'zh-CN' });
test.beforeAll(() => mkdirSync(SHOT_DIR, { recursive: true }));

const OPTIONS = [
  { id: 'o1', title: '选项甲', detail: '甲的说明' },
  { id: 'o2', title: '选项乙', detail: '' },
  { id: 'o3', title: '选项丙', detail: '丙的说明' },
];

function todoItem(over = {}) {
  const now = Date.now();
  return {
    id: 'd-1', tagId: 'tag-work', name: '分支历史对账', detail: '正文：对账说明。',
    priority: 1, completed: false, createdAt: now - 3600_000,
    decision: { question: '对账结论怎么处理？', options: OPTIONS, multiSelect: false, allowOther: false },
    ...over,
  };
}

async function boot(page, items) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') return ws.send(JSON.stringify({ type: 'pong' }));
      if (m.type === 'getConfig') return sendConfig();
      if (m.type === 'getSessions' || m.type === 'listSessions') return sendSessions();
      if (m.type === 'getHistory') return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid;
  }, SID, { timeout: 20000 });
  await page.evaluate(async ({ tags, items }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: items });
  }, { tags: [{ id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' }], items });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(400);
}

const push = async (page, id) => {
  await page.evaluate(async (pid) => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage(pid);
  }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(300);
};

test('D1 列表详情：决策选项渲染为可读 title（不出现 [object Object]），detail 附显示', async ({ page }) => {
  await boot(page, [todoItem()]);
  await push(page, 'todo-apple-list');

  const page0 = page.locator('.mobile-page[data-page="todo-apple-list"]');
  await page0.locator('.ta-item-body', { hasText: '分支历史对账' }).click();
  await page0.locator('.ta-decision-detail').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(200);

  const labels = await page0.locator('.ta-decision-option > span:not(.ta-decision-option-detail)').allInnerTexts();
  expect(labels).toEqual(['选项甲', '选项乙', '选项丙']);
  expect(await page0.locator('.ta-decision-option-detail').allInnerTexts()).toEqual(['甲的说明', '丙的说明']);
  const text = await page0.locator('.ta-decision-detail').first().innerText();
  expect(text).not.toContain('[object Object]');
  await page.screenshot({ path: join(SHOT_DIR, 'D1-list-detail-options.png') });
});

test('D2 快速决策卡：选项可读，回传正文也不含 [object Object]', async ({ page }) => {
  await boot(page, [todoItem()]);
  await push(page, 'todo-quick-decision');

  const card = page.locator('.mobile-page[data-page="todo-quick-decision"]');
  const labels = await card.locator('.ta-qd-opt-label').allInnerTexts();
  expect(labels).toEqual(['选项甲', '选项乙', '选项丙']);
  expect(await card.locator('.ta-qd-opt-detail').allInnerTexts()).toEqual(['甲的说明', '丙的说明']);
  expect(await card.innerText()).not.toContain('[object Object]');
  await page.screenshot({ path: join(SHOT_DIR, 'D2-quick-decision-options.png') });

  // 点选第一项后发送 ⇒ 回传正文（pickedLabels 同一破碎点）必须是可读文本
  await card.locator('.ta-qd-opt').first().click();
  await card.locator('.ta-qd-answer-send').click();
  const last = await page.locator('.ta-qd-card').first().evaluate((el) => el.dataset.qdAnswerLast || '');
  expect(last).toContain('选项甲');
  expect(last).not.toContain('[object Object]');
});

test('D3 options 缺失/为空：不渲染选项列表', async ({ page }) => {
  await boot(page, [
    todoItem({ id: 'd-2', name: '空选项待办', decision: { question: '空选项集', options: [] } }),
  ]);
  await push(page, 'todo-apple-list');

  const list = page.locator('.mobile-page[data-page="todo-apple-list"]');
  await list.locator('.ta-item-body', { hasText: '空选项待办' }).click();
  await list.locator('.ta-decision-detail').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(200);
  expect(await list.locator('.ta-decision-option').count()).toBe(0);

  await push(page, 'todo-quick-decision');
  const card = page.locator('.mobile-page[data-page="todo-quick-decision"]');
  await card.waitFor({ state: 'visible' });
  expect(await card.locator('.ta-qd-opts').count()).toBe(0);
  await page.screenshot({ path: join(SHOT_DIR, 'D3-empty-options.png') });
});
