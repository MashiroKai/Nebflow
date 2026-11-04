#!/usr/bin/env node
// accept2/shots.mjs — 最终验收节点（accept2）自建取证脚本：合并后双端入口形态 + 新建关键帧。
//
// 目的（节点任务书 ④⑥）：
//   ④ 入口收敛选择器级实测（本树 HEAD）：`.fm-todo-entry` 缺席 / 手机 `[data-chrome="tasks"]`
//      与 `#mobile-task-panel` 缺席 / 胶囊双端在场且可展开。
//   ⑥ 合并后截图：桌面 1280×800 与手机 375×812（单胶囊 + 右下角加号），
//      新建流程 ≥4 帧（加号 → 行内新建 → 填标题选重要度 → 提交 → 刷新仍在）。
//
// 载体：零后端、零 sbt、零端口占用 —— page.route 静态服务器（WEB 根 = 本树 web/）
//       + page.routeWebSocket 有状态夹具（与 WsTasksWorkspaceHandlers 同口径回帧）。
// 用法：node reports-evidence/<batch>/accept2/shots.mjs
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(dirname(dirname(HERE)));
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const OUT = join(HERE, 'shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'accept2-root';
const T0 = Date.now();
const H = 3600_000;

const checks = [];
const ok = (name, cond, extra = '') => {
  checks.push({ name, ok: !!cond, extra });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
};

function fixtureRows() {
  return [
    { id: 't1', content: '修登录态过期', triggerAt: T0 - 3 * H, createdAt: T0 - 10 * H, priority: 3, project: '工作' },
    { id: 't2', content: '缴房租', triggerAt: T0 + 2 * H, createdAt: T0 - 8 * H, priority: 2, project: '生活' },
    { id: 't3', content: '读《设计中的设计》第三章', triggerAt: T0 + 30 * H, createdAt: T0 - 5 * H, priority: 1, project: null },
  ].map((r) => ({
    triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
    kind: 'todo', name: null, missCount: 0, sessionId: SID, completed: false, completedAt: null, ...r,
  }));
}
const rowJson = (r) => ({ ...r, completed: !!r.completed, completedAt: r.completedAt ?? null });
const backendSort = (rows) => [...rows].sort((a, b) => {
  const ac = a.completed ? 1 : 0, bc = b.completed ? 1 : 0;
  if (ac !== bc) return ac - bc;
  if (a.priority !== b.priority) return b.priority - a.priority;
  if (a.triggerAt !== b.triggerAt) return a.triggerAt - b.triggerAt;
  return a.createdAt - b.createdAt;
});

async function boot(page) {
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
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  const backend = { rows: fixtureRows(), seq: 0 };
  const captured = [];
  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = (o) => ws.send(JSON.stringify(o));
    const cfg = () => send({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    const sess = () => send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
    const list = (sid) => send({ type: 'scheduledTaskList', sessionId: sid, scope: 'all', includeCompleted: false,
      tasks: backendSort(backend.rows.filter((r) => !r.completed)).map(rowJson) });
    cfg(); sess();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      captured.push(m);
      switch (m.type) {
        case 'getConfig': cfg(); break;
        case 'getSessions': case 'listSessions': sess(); break;
        case 'getHistory': send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }); break;
        case 'listScheduledTasks': list(m.sessionId); break;
        case 'createScheduledTask': {
          const row = { id: `srv-${++backend.seq}`, content: m.content, triggerAt: T0 + 24 * H, createdAt: Date.now(),
            triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true, kind: 'todo',
            name: null, missCount: 0, sessionId: m.sessionId, project: m.project ?? null,
            priority: m.priority ?? 0, completed: false, completedAt: null };
          backend.rows.push(row);
          send({ type: 'scheduledTaskCreated', sessionId: m.sessionId, task: rowJson(row) });
          break;
        }
        case 'completeTodo': {
          const row = backend.rows.find((r) => r.id === m.id); if (!row) break;
          row.completed = true; row.completedAt = Date.now();
          send({ type: 'scheduledTaskCompleted', id: row.id, sessionId: row.sessionId, completedAt: row.completedAt, task: rowJson(row) });
          send({ type: 'scheduledTaskDeleted', id: row.id, sessionId: row.sessionId });
          break;
        }
        case 'deleteScheduledTask': backend.rows = backend.rows.filter((r) => r.id !== m.id);
          send({ type: 'scheduledTaskDeleted', id: m.id, sessionId: m.sessionId }); break;
        case 'updateScheduledTask': {
          const row = backend.rows.find((r) => r.id === m.id); if (!row) break;
          if (m.priority !== undefined) row.priority = m.priority;
          if (m.content !== undefined) row.content = m.content;
          send({ type: 'scheduledTaskUpdated', sessionId: row.sessionId, task: rowJson(row) });
          break;
        }
        default: break;
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { timeout: 15000 });
  await page.waitForTimeout(300);
  return { captured, backend };
}

const shot = (page, name) => page.screenshot({ path: join(OUT, name) });

const browser = await chromium.launch();
try {
  // ───────────────────────── 桌面 1280×800 ─────────────────────────
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const { captured } = await boot(page);

  // ④ 入口收敛（选择器级）
  ok('桌面 .fm-todo-entry 缺席', await page.locator('.fm-todo-entry').count() === 0);
  ok('桌面手机任务圆钮 [data-chrome="tasks"] 缺席', await page.locator('[data-chrome="tasks"]').count() === 0);
  ok('桌面 #mobile-task-panel 缺席', await page.locator('#mobile-task-panel').count() === 0);
  ok('桌面 #todo-page 不可达（DOM 留档、无入口）', await page.locator('#todo-page.open').count() === 0);
  ok('桌面胶囊在场可见（唯一入口）', await page.locator('#task-list .tfl-capsule').isVisible());
  ok('胶囊逾期翻面态（is-overdue）在场', await page.locator('.tfl-capsule.is-overdue').count() === 1);
  await shot(page, 'a1_desktop_1280x800_single_entry_capsule.png');

  // 展开
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]');
  await page.waitForTimeout(950);
  ok('桌面胶囊可展开（.tfl-card[data-state=open]）', await page.locator('.tfl-card[data-state="open"]').count() === 1);
  ok('桌面右下角加号 .tfl-fab-add 在场可见', await page.locator('.tfl-fab-add').isVisible());
  await shot(page, 'a2_desktop_1280x800_expanded_fab.png');

  // ⑥ 新建流程关键帧
  await page.locator('.tfl-fab-add').click();
  await page.waitForSelector('.tfl-create-row');
  await page.waitForTimeout(200);
  ok('帧1 加号 → 行内新建行出现并聚焦标题输入', await page.evaluate(() => (document.activeElement?.className || '').includes('tfl-row-title-input')));
  await shot(page, 'b1_desktop_create_row.png');

  const prio = page.locator('.tfl-create-panel .tfl-edit-prio-btn');
  ok('新建面板重要度四签在场', await prio.count() === 4);
  await prio.nth(3).click();
  await page.locator('.tfl-create-row .tfl-row-title-input').fill('验收：新建带高重要度');
  await page.waitForTimeout(150);
  ok('帧2 填标题 + 选中「高」重要度', await prio.nth(3).evaluate((el) => el.classList.contains('selected')));
  await shot(page, 'b2_desktop_create_filled_priority.png');

  await page.locator('.tfl-create-row .tfl-row-title-input').press('Enter');
  await page.waitForTimeout(500);
  ok('帧3 提交后行出现在列表', await page.locator('.tfl-row', { hasText: '验收：新建带高重要度' }).count() === 1);
  const frame = captured.find((f) => f.type === 'createScheduledTask' && f.content === '验收：新建带高重要度');
  ok('提交发出 createScheduledTask 帧且 priority=3（落库口径）', frame && frame.priority === 3, JSON.stringify(frame && { type: frame.type, priority: frame.priority, kind: frame.kind }));
  await shot(page, 'b3_desktop_create_submitted.png');

  // 刷新后仍在（同夹具后端 = 落库面）
  await page.reload();
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { timeout: 15000 });
  await page.waitForTimeout(300);
  ok('帧4 刷新后胶囊计数可见', (await page.locator('.tfl-capsule').innerText()).length > 0);
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]');
  await page.waitForTimeout(900);
  ok('帧4 刷新后新建行仍在（.tfl-row 命中）', await page.locator('.tfl-row', { hasText: '验收：新建带高重要度' }).count() === 1);
  await shot(page, 'b4_desktop_create_after_reload.png');
  ok('桌面零 pageerror', errors.length === 0, JSON.stringify(errors));
  await ctx.close();

  // ───────────────────────── 手机 375×812 ─────────────────────────
  const mctx = await browser.newContext({ viewport: { width: 375, height: 812 }, colorScheme: 'light', isMobile: true, hasTouch: true });
  const mp = await mctx.newPage();
  const merr = [];
  mp.on('pageerror', (e) => merr.push(e.message));
  await boot(mp);
  await mp.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });
  ok('手机 .fm-todo-entry 缺席', await mp.locator('.fm-todo-entry').count() === 0);
  ok('手机左上角任务圆钮 [data-chrome="tasks"] 缺席', await mp.locator('[data-chrome="tasks"]').count() === 0);
  ok('手机 #mobile-task-panel 缺席', await mp.locator('#mobile-task-panel').count() === 0);
  ok('手机 #task-list 不再被 display:none 压死', await mp.locator('#task-list').evaluate((el) => getComputedStyle(el).display) === 'block');
  ok('手机胶囊在场可见（唯一入口）', await mp.locator('#task-list .tfl-capsule').isVisible());
  const capBox = await mp.locator('.tfl-capsule').boundingBox();
  ok('手机胶囊在视口内（顶带一行）', capBox && capBox.y >= 0 && capBox.y < 812, JSON.stringify(capBox));
  await shot(mp, 'c1_mobile_375x812_single_entry_capsule.png');
  await mp.locator('.tfl-capsule').click();
  await mp.waitForSelector('.tfl-card[data-state="open"]');
  await mp.waitForTimeout(950);
  const cardBox = await mp.locator('.tfl-card').boundingBox();
  ok('手机胶囊可展开（面板宽 ≤ 375−32）', cardBox && cardBox.width <= 343, JSON.stringify(cardBox));
  const mfab = await mp.locator('.tfl-fab-add').boundingBox();
  ok('手机右下角加号 44px 触摸档', mfab && Math.round(mfab.width) === 44 && Math.round(mfab.height) === 44, JSON.stringify(mfab));
  await shot(mp, 'c2_mobile_375x812_expanded_fab.png');
  ok('手机零 pageerror', merr.length === 0, JSON.stringify(merr));
  await mctx.close();
} finally {
  await browser.close();
}

const fail = checks.filter((c) => !c.ok).length;
console.log(`\n=== accept2 入口收敛 + 截图: ${checks.length - fail} PASS / ${fail} FAIL ===`);
process.exit(fail ? 1 : 0);
