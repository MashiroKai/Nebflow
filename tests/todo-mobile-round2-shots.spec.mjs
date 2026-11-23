#!/usr/bin/env node
// tests/todo-mobile-round2-shots.spec.mjs — 待办移动端 round2 的截图断言（手机视口）。
//
// 判据（作者令 2026-11-22 10:48「决策卡页的按钮功能布局和条目详情页的按钮功能高度重合
// 就应该用条目详情页的设计」）：
//   R1 决策卡页在场「完成」键 `.ta-save`（顶带簇右上角，与详情页同一颗键）；
//   R2 决策卡页不再有四键胶囊带 `.ta-qd-act`（已退役，0 个节点）；
//   R3 同一颗键在两页几何一致（宽高差 ≤ 0.5px）；
//   R4 两页截图非空白（PNG 字节数 > 阈值），截图落证据目录（不入库）。
//
// 运行：node tests/todo-mobile-round2-shots.spec.mjs   （独立脚本，自起 chromium）
// 证据：QD_R2_OUT（默认 /Users/kaiyu/Claude code/Stand/.stand/tmp/archive-revive/evidence/todo-mobile-round2/shots）

import { chromium } from 'playwright';
import { mkdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.QD_R2_OUT
  || '/Users/kaiyu/Claude code/Stand/.stand/tmp/archive-revive/evidence/todo-mobile-round2/shots';
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'todo-round2-root';
const VW = 375, VH = 812;
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
};

const fixture = () => {
  const now = Date.now();
  return {
    tagList: [{ id: 'tw', name: '工作', color: 'rgb(0, 122, 255)' }],
    itemList: [
      {
        id: 'r2-1', tagId: 'tw', priority: 3, due: now + 3_600_000,
        name: '周会先讲哪件事',
        detail: '正文第一段。\n正文第二段。',
        decision: { question: '本周先推进哪一件？', options: ['A 数据模型', 'B 列表整改', 'C 两个并行'] },
      },
    ],
  };
};

const pageErrors = [];
const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: VW, height: VH }, deviceScaleFactor: 2,
  hasTouch: true, isMobile: true, colorScheme: 'light', locale: 'zh-CN',
});
const page = await ctx.newPage();
page.on('pageerror', (e) => { if (!NOISE.test(e.message)) pageErrors.push(e.message); });
page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) pageErrors.push(m.text()); });

await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  if (navigator.serviceWorker) {
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  }
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
  sendConfig();
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(String(raw)); } catch { return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'session', tasks: [] }));
  });
});

await page.goto('http://localhost:1/');
await page.waitForFunction(async (rootSid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 20000 });
await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
await page.evaluate(async (f) => {
  const store = await import('/js/todoStore.js');
  store.todoStore.__seed({ tagList: f.tagList, itemList: f.itemList });
}, fixture());
await page.waitForTimeout(200);

const push = async (id) => {
  await page.evaluate(async (pid) => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage(pid);
  }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(350);
};
const box = (sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return null;
  const b = el.getBoundingClientRect();
  return { w: b.width, h: b.height, visible: b.width > 0 && b.height > 0 };
}, sel);

// ── 决策卡页 ──
await push('todo-quick-decision');
const qdLive = `#mobile-pages .mobile-page.live[data-page="todo-quick-decision"]`;
const qdSave = await box(`${qdLive} .ta-save`);
const qdRetired = await page.evaluate((s) => document.querySelectorAll(s).length, `${qdLive} .ta-qd-act`);
ok('R1 决策卡页在场「完成」键 .ta-save（顶带簇右上角）', !!qdSave?.visible, JSON.stringify(qdSave));
ok('R2 决策卡页四键胶囊带 .ta-qd-act 已退役（0 节点）', qdRetired === 0, `count=${qdRetired}`);
await page.screenshot({ path: join(OUT, 'quick-decision-375x812.png') });
await page.evaluate(async () => {
  const nav = await import('/js/mobile/navController.js');
  try { nav.popToRoot(); } catch { /* root */ }
});
await page.waitForTimeout(300);

// ── 条目详情页（同一颗键的参照）──
await page.evaluate(async () => {
  const store = await import('/js/todoStore.js');
  const item = store.todoStore.list?.()?.[0];
  if (item && globalThis.__todoOpenHint) globalThis.__todoOpenHint(item.id);
});
await push('todo-apple-item');
const detLive = `#mobile-pages .mobile-page.live[data-page="todo-apple-item"]`;
const detSave = await box(`${detLive} .ta-save`);
await page.screenshot({ path: join(OUT, 'item-detail-375x812.png') });

ok('R3 决策卡页与详情页同一颗键几何一致（宽高差 ≤ 0.5px）',
  !!qdSave?.visible && !!detSave?.visible
    && Math.abs(qdSave.w - detSave.w) <= 0.5 && Math.abs(qdSave.h - detSave.h) <= 0.5,
  `quick=${JSON.stringify(qdSave)} detail=${JSON.stringify(detSave)}`);

for (const name of ['quick-decision-375x812.png', 'item-detail-375x812.png']) {
  const size = statSync(join(OUT, name)).size;
  ok(`R4 截图非空白：${name}`, size > 20_000, `bytes=${size}`);
}

ok('Z 零页面错误', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));

await browser.close();
console.log(`\n=== ${pass} passed, ${fail} failed ===`);
process.exit(fail ? 1 : 0);
