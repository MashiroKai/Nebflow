// todo-capsule-create.spec.mjs — 胶囊面交互实现（胶囊面交互批 2026-11-04）
// 渲染层验收：右下角加号 / 空白区建 / 重要度落库 / 完成·取消完成·删除 /
// 动画护栏 / 手机 375 视口胶囊可见可展开。
//
// 断言清单（对应节点任务书 ①–⑧，逐条钉成机械判据）：
//   ① 右下角加号：存在、34px 圆钮（桌面档）、贴列表区右下角、**不压列表末行**；
//      点击 ⇒ 行内新建行出现且标题输入框获得焦点；
//   ①b 同一实现两套尺寸：手机层 44px 触摸档（⑧ 里量）。
//   ② 空白区点击建：列表空白 + footer 空白两条落点 ⇒ 行内新建 + 聚焦。
//   ③ 防误触 ①：创建行在开 / 输入框聚焦 ⇒ 重复点击零新行（不打断不重开）。
//   ④ 防误触 ③：pointerdown → pointerup 位移 > 8px（拖动/滑屏）⇒ 不触发。
//   ⑤ 新建时择重要度 ⇒ `createScheduledTask` 帧带 priority（落库，不是只排内存）。
//   ⑥ 完成 / 取消完成 / 删除 ⇒ completeTodo / uncompleteTodo / deleteScheduledTask
//      三帧各就位（帧型与 store 换血批契约同源）。
//   ⑦ 动画护栏：展开面板仍走 spring 形变路径（.tfl-card 逐帧内联 transform
//      scale 写入 ⇒ 落地后清空）；计数翻牌类 tfl-count-flip 仍在计数宿主上播；
//      逾期翻面（is-overdue）仍在。
//   ⑧ 手机 375 视口：body.mobile-layer 下 #task-list 可见、胶囊可见、点击可展开。
//
// 数据面 = WS 夹具（有状态写帧回帧）+ 静态文件服务器，零后端、零 sbt、零 6138。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/todo-capsule-create.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
// 截图落点：证据目录按 2026-11-04 文档命名新制 `<topic>__<time>`（收口批改写；
// 旧制时间打头目录已被 main 的命名迁移批改名，本行随即跟上，断言零改动）。
const SHOT_DIR = join(HERE, '..', 'reports-evidence', 'todo-unify-backend__20261104_145310__delegate-nebflow-79768ef5', 'panel');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'todo-capsule-create-root';
const T0 = Date.now();
const H = 3600_000;

test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });

/** 收件箱夹具（后端 listTaskJson 形状）：3 条 —— 一条逾期（胶囊翻面）、
 *  两条未来；priority 3/2/1 覆盖排序与行首色点。 */
function inboxFixture() {
  return [
    {
      id: 't1', content: '待办一：修登录态过期', triggerAt: T0 - 3 * H, createdAt: T0 - 10 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: SID, project: '工作',
      priority: 3, completed: false, completedAt: null,
    },
    {
      id: 't2', content: '待办二：缴房租', triggerAt: T0 + 2 * H, createdAt: T0 - 8 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: SID, project: '生活',
      priority: 2, completed: false, completedAt: null,
    },
    {
      id: 't3', content: '待办三：读设计中的设计', triggerAt: T0 + 30 * H, createdAt: T0 - 5 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: SID, project: null,
      priority: 1, completed: false, completedAt: null,
    },
  ];
}

/** 服务端排序权威复算（ScheduledTask.todoSortKey）。 */
function backendSort(rows) {
  return [...rows].sort((a, b) => {
    const ac = a.completed ? 1 : 0;
    const bc = b.completed ? 1 : 0;
    if (ac !== bc) return ac - bc;
    if (a.priority !== b.priority) return b.priority - a.priority;
    if (a.triggerAt !== b.triggerAt) return a.triggerAt - b.triggerAt;
    return a.createdAt - b.createdAt;
  });
}

const rowJson = (r) => ({
  id: r.id, content: r.content, triggerAt: r.triggerAt, createdAt: r.createdAt,
  triggered: r.triggered, triggeredAt: r.triggeredAt, referencePath: r.referencePath,
  repeat: r.repeat, enabled: r.enabled, kind: r.kind, name: r.name, missCount: r.missCount,
  sessionId: r.sessionId, project: r.project,
  priority: r.priority, completed: !!r.completed, completedAt: r.completedAt ?? null,
});

/**
 * 静态服务器 + 有状态 WS 夹具后端（写帧回帧，与 WsTasksWorkspaceHandlers 同口径）。
 * @param {{viewport?: {width:number,height:number}, rows?: any[]}} [opts]
 */
async function bootApp(page, { viewport, rows = inboxFixture() } = {}) {
  if (viewport) await page.setViewportSize(viewport);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  const captured = [];
  const errors = [];
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const text = m.text();
    if (/Failed to load resource|fetching the script/i.test(text)) return;
    errors.push(text);
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
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

  const backend = { rows: rows.map((r) => ({ ...r })), createSeq: 0 };
  let live = null;
  const send = (obj) => live && live.send(JSON.stringify(obj));
  const findRow = (id) => backend.rows.find((r) => r.id === id);
  const sendList = (sessionId) => send({
    type: 'scheduledTaskList',
    tasks: backendSort(backend.rows.filter((r) => !r.completed)).map(rowJson),
    sessionId, scope: 'all', includeCompleted: false,
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    live = ws;
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      captured.push(m);
      switch (m.type) {
        case 'getConfig': sendConfig(); break;
        case 'getSessions': case 'listSessions': sendSessions(); break;
        case 'getHistory':
          ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
          break;
        case 'listScheduledTasks':
          sendList(m.sessionId);
          break;
        case 'createScheduledTask': {
          const row = {
            id: `srv-${++backend.createSeq}`, content: m.content, triggerAt: T0 + 24 * H, createdAt: Date.now(),
            triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
            kind: 'todo', name: null, missCount: 0, sessionId: m.sessionId,
            project: m.project ?? null, priority: m.priority ?? 0, completed: false, completedAt: null,
          };
          backend.rows.push(row);
          send({ type: 'scheduledTaskCreated', sessionId: m.sessionId, task: rowJson(row) });
          break;
        }
        case 'completeTodo': {
          const row = findRow(m.id);
          if (!row) break;
          row.completed = true;
          row.completedAt = row.completedAt ?? Date.now();
          send({ type: 'scheduledTaskCompleted', id: row.id, sessionId: row.sessionId, completedAt: row.completedAt, task: rowJson(row) });
          send({ type: 'scheduledTaskDeleted', id: row.id, sessionId: row.sessionId });
          break;
        }
        case 'uncompleteTodo': {
          const row = findRow(m.id);
          if (!row) break;
          row.completed = false;
          row.completedAt = null;
          send({ type: 'scheduledTaskUpdated', sessionId: row.sessionId, task: rowJson(row) });
          break;
        }
        case 'deleteScheduledTask': {
          backend.rows = backend.rows.filter((r) => r.id !== m.id);
          send({ type: 'scheduledTaskDeleted', id: m.id, sessionId: m.sessionId });
          break;
        }
        default: break;
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  // 胶囊 = 会话渲染链末端产物（sidebar 切会话后挂 #task-list）。
  // 端区分批 2026-11-05：手机层 #task-list 整槽 display:none（胶囊只在桌面可见）
  // ⇒ 等待条件从「可见」放宽到「已挂载」（DOM 仍在、渲染链照跑）。
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { timeout: 15000, state: 'attached' });
  await page.waitForTimeout(200);
  return { captured, errors };
}

/** 展开面板（点胶囊 → 等形变落地）。 */
async function openPanel(page) {
  await page.locator('.tfl-capsule').click();
  await expect(page.locator('.tfl-card[data-state="open"]')).toHaveCount(1);
  await page.waitForTimeout(900); // spring 落地
}

/** 夹具模式注入（零帧；滚动测试要可控行数）。 */
async function seedFixture(page, count) {
  await page.evaluate(async (n) => {
    const m = await import('/js/todoStore.js');
    const now = Date.now();
    const itemList = [];
    for (let i = 0; i < n; i++) {
      itemList.push({
        id: `f-${i}`, tagId: 'tag-work', title: `夹具待办 ${i + 1}`, notes: '',
        due: now + (i + 1) * 3600000, priority: i % 4, createdAt: now - i * 3600000,
      });
    }
    m.todoStore.__seed({
      tagList: [{ id: 'tag-work', name: '工作', color: m.TAG_COLOR_PRESETS[5] }],
      itemList,
    });
  }, count);
  await page.waitForTimeout(200);
}

// ── ① 右下角加号 ─────────────────────────────────────────────────────────
test('① 右下角加号：34px 圆钮贴列表右下角且不压末行，点击 ⇒ 行内新建行出现并聚焦', async ({ page }) => {
  const { errors } = await bootApp(page);
  await openPanel(page);

  const fab = page.locator('.tfl-fab-add');
  await expect(fab).toHaveCount(1);
  await expect(fab).toBeVisible();

  const fabBox = await fab.boundingBox();
  const cardBox = await page.locator('.tfl-card').boundingBox();
  // 桌面档 34px（32–40 量级内）
  expect(Math.round(fabBox.width)).toBe(34);
  expect(Math.round(fabBox.height)).toBe(34);
  // 圆形（999px 全圆胶囊）
  const radius = await fab.evaluate((el) => getComputedStyle(el).borderRadius);
  expect(['999px', '17px']).toContain(radius);
  // 贴在卡片内右下角
  expect(fabBox.x).toBeGreaterThan(cardBox.x + cardBox.width / 2);
  expect(fabBox.x + fabBox.width).toBeLessThanOrEqual(cardBox.x + cardBox.width + 0.5);
  expect(fabBox.y + fabBox.height).toBeLessThanOrEqual(cardBox.y + cardBox.height + 0.5);
  // 不压列表末行（几何：加号顶在末行底之下）
  const lastRow = await page.locator('.tfl-row').last().boundingBox();
  expect(fabBox.y).toBeGreaterThanOrEqual(lastRow.y + lastRow.height);
  // 次级色（不抢主视觉）：墨色 = --color-text 的 60% alpha 档（非强调色/非实心）
  const ink = await fab.evaluate((el) => getComputedStyle(el).color);
  expect(ink).toMatch(/^(rgba?\(|color\(srgb)/);
  expect(ink).toMatch(/0\.6\)/);

  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOT_DIR, '01_fab_desktop_open.png') });

  // 点击 ⇒ 行内新建行 + 标题输入聚焦
  await fab.click();
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  await page.waitForTimeout(150);
  const focused = await page.evaluate(() => {
    const a = document.activeElement;
    return a ? `${a.tagName}.${a.className}` : '';
  });
  expect(focused).toContain('INPUT');
  expect(focused).toContain('tfl-row-title-input');
  await page.screenshot({ path: join(SHOT_DIR, '02_fab_click_createrow.png') });

  expect(errors).toEqual([]);
});

// ── ② 空白区点击建 ───────────────────────────────────────────────────────
test('② 空白区点击建：列表空白 ⇒ 新建并聚焦；footer 空白 ⇒ 新建并聚焦', async ({ page }) => {
  const { errors } = await bootApp(page);
  await openPanel(page);

  // 列表下缘空白（padding-bottom 区，加号在右侧 ⇒ 取左半）
  const list = page.locator('.tfl-list');
  const listBox = await list.boundingBox();
  await list.click({ position: { x: 24, y: listBox.height - 12 } });
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  await page.waitForTimeout(150);
  expect(await page.evaluate(() => document.activeElement?.className || '')).toContain('tfl-row-title-input');
  // 丢弃草稿（Esc：创建行分层退出）
  await page.keyboard.press('Escape');
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);

  // footer 空白：先进单标签视图（footer 只在标签视图在场）
  await page.locator('.tfl-filter-toggle').click();
  await expect(page.locator('.tfl-filter-wrap')).toBeVisible();
  await page.locator('.tfl-chip-tag').first().click();
  await page.waitForTimeout(600);
  await expect(page.locator('.tfl-footer')).toBeVisible();
  await page.locator('.tfl-footer').click({ position: { x: 5, y: 5 } });
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  await page.waitForTimeout(150);
  expect(await page.evaluate(() => document.activeElement?.className || '')).toContain('tfl-row-title-input');
  await page.screenshot({ path: join(SHOT_DIR, '03_blank_footer_createrow.png') });

  expect(errors).toEqual([]);
});

// ── ③ 防误触：已聚焦不打断 + 命中排除 ───────────────────────────────────
test('③ 防误触①②：创建行在开 / 输入框聚焦 ⇒ 重复点击零新行；行/chip/筛选栏 ⇒ 不触发', async ({ page }) => {
  const { errors } = await bootApp(page);
  await openPanel(page);
  const rowsBefore = await page.locator('.tfl-row').count();

  await page.locator('.tfl-fab-add').click();
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  await page.waitForTimeout(150);
  // 重复点加号（输入框已聚焦）
  await page.locator('.tfl-fab-add').click();
  await page.locator('.tfl-fab-add').click();
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  // 点列表空白（也不得重开）
  const listBox = await page.locator('.tfl-list').boundingBox();
  await page.locator('.tfl-list').click({ position: { x: 24, y: listBox.height - 12 } });
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  // 行数不变（没有多出来的行）
  expect(await page.locator('.tfl-row').count()).toBe(rowsBefore);
  // 丢弃草稿
  await page.keyboard.press('Escape');
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);

  // 命中排除（②）：行体点击 = 进编辑（不是建）；筛选栏/chip 点击同理零新建
  await page.locator('.tfl-row-main').first().click();
  await expect(page.locator('.tfl-row-editing')).toHaveCount(1);
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);
  await page.keyboard.press('Escape');
  await expect(page.locator('.tfl-row-editing')).toHaveCount(0);
  await page.locator('.tfl-filter-toggle').click();
  await expect(page.locator('.tfl-filter-wrap')).toBeVisible();
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);
  await page.locator('.tfl-chip-tag').first().click();
  await page.waitForTimeout(500);
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);

  expect(errors).toEqual([]);
});

// ── ④ 防误触：滚动位移不触发 ─────────────────────────────────────────────
test('④ 防误触③：pointerdown→up 位移 > 8px（拖动/滑屏）⇒ 不进入新建', async ({ page }) => {
  const { errors } = await bootApp(page);
  await seedFixture(page, 4); // 少量行 ⇒ 列表下缘留出可点的空白带
  await openPanel(page);
  const listBox = await page.locator('.tfl-list').boundingBox();
  const x = listBox.x + 40;
  const y = listBox.y + listBox.height - 12; // 下缘空白（padding-bottom 区）

  // 位移 60px ≫ 8px 阈值（起点在空白、终点落在行上 ⇒ 浏览器把 click 派给公共祖先
  // .tfl-list，正好命中本口径：handler 会跑，但位移判据压掉本次触发）
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y - 60, { steps: 6 });
  await page.mouse.up();
  await page.waitForTimeout(250);
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);
  await expect(page.locator('.tfl-row-editing')).toHaveCount(0);

  // 轮滚（真滚动容器）同样不得凭空建行
  await seedFixture(page, 16);
  await page.waitForTimeout(200);
  const box2 = await page.locator('.tfl-list').boundingBox();
  await page.mouse.move(box2.x + box2.width / 2, box2.y + box2.height / 2);
  await page.mouse.wheel(0, 240);
  await page.waitForTimeout(250);
  await expect(page.locator('.tfl-create-row')).toHaveCount(0);

  // 对照腿：空白处原地点击（位移 0）⇒ 建（证明抑制来自位移判据，不是死处理）
  await seedFixture(page, 4);
  await page.waitForTimeout(200);
  const box3 = await page.locator('.tfl-list').boundingBox();
  await page.mouse.click(box3.x + 40, box3.y + box3.height - 12);
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);

  expect(errors).toEqual([]);
});

// ── ⑤ 重要度随创建帧落库 ────────────────────────────────────────────────
test('⑤ 新建择重要度 ⇒ createScheduledTask 帧带 priority（落库，不是只排内存）', async ({ page }) => {
  const { captured, errors } = await bootApp(page);
  await openPanel(page);

  await page.locator('.tfl-fab-add').click();
  await expect(page.locator('.tfl-create-row')).toHaveCount(1);
  await page.waitForTimeout(150);
  // 重要度四签（既有视觉：0..3，第 4 个 = 高）
  const prioBtns = page.locator('.tfl-create-panel .tfl-edit-prio-btn');
  await expect(prioBtns).toHaveCount(4);
  await prioBtns.nth(3).click();
  await expect(prioBtns.nth(3)).toHaveClass(/selected/);
  await page.locator('.tfl-create-row .tfl-row-title-input').fill('带优先级的新建');
  await page.locator('.tfl-create-row .tfl-row-title-input').press('Enter');
  await page.waitForTimeout(400);

  const frame = captured.find((f) => f.type === 'createScheduledTask' && f.content === '带优先级的新建');
  expect(frame).toBeTruthy();
  expect(frame.priority).toBe(3);
  expect(frame.kind).toBe('todo');
  expect(frame.sessionId).toBe(SID);

  expect(errors).toEqual([]);
});

// ── ⑥ 完成 / 取消完成 / 删除 三帧 ───────────────────────────────────────
test('⑥ 完成 / 撤回（取消完成）/ 删除 ⇒ completeTodo / uncompleteTodo / deleteScheduledTask 三帧', async ({ page }) => {
  const { captured, errors } = await bootApp(page);
  await openPanel(page);

  // 完成（三段动画 + 完成帧）
  const firstRow = page.locator('.tfl-row').first();
  const firstId = await firstRow.getAttribute('data-id');
  await firstRow.locator('.tfl-complete').click();
  await page.waitForTimeout(260);
  await expect(page.locator('.tfl-complete.checked')).toHaveCount(1); // 三段动画中段
  await page.waitForTimeout(800);
  const completeFrame = captured.find((f) => f.type === 'completeTodo');
  expect(completeFrame).toBeTruthy();
  expect(completeFrame.id).toBe(firstId);

  // 撤回（取消完成）—— 完成后的可达路径：撤回条
  await expect(page.locator('.tfl-undo')).toBeVisible();
  await page.locator('.tfl-undo-btn').click();
  await page.waitForTimeout(300);
  const undoFrame = captured.find((f) => f.type === 'uncompleteTodo');
  expect(undoFrame).toBeTruthy();
  expect(undoFrame.id).toBe(firstId);
  await expect(page.locator('.tfl-undo')).toBeHidden();

  // 删除（行尾 ×）
  const delRow = page.locator('.tfl-row').first();
  const delId = await delRow.getAttribute('data-id');
  await delRow.locator('.tfl-del').click();
  await page.waitForTimeout(400);
  const delFrame = captured.find((f) => f.type === 'deleteScheduledTask');
  expect(delFrame).toBeTruthy();
  expect(delFrame.id).toBe(delId);
  await expect(page.locator(`.tfl-row[data-id="${delId}"]`)).toHaveCount(0);

  expect(errors).toEqual([]);
});

// ── ⑦ 动画护栏 ─────────────────────────────────────────────────────────
// 2026-11-05 件5 改写（探针跟随实现换通道，**声明不变**：展开必须是「同一个元素
// 多帧真 spring」而不是单跳换态）：展开几何从「满尺寸面板 scale 缩放」改成
// 「载体自身长高」（作者原话「要在同一行展开。有一种胶囊变大的感觉。」⇒ 载体
// rect.left 不许横移，scale 通道已被件5 判据 A1 禁掉）。故探针从「内联 transform
// 里出现 scale(」改成「内联 height 逐帧写入」，其余三条断言原样：
//   多帧写入(>3) / 落地清空 / 计数翻牌与逾期翻面仍在。
test('⑦ 动画护栏：展开仍走 spring 几何通道（内联 height 逐帧写入→落地清空）；计数翻牌与逾期翻面仍在', async ({ page }) => {
  const { errors } = await bootApp(page);
  // 逾期夹具 ⇒ 胶囊翻面态（红字「N 逾期」）
  await expect(page.locator('.tfl-capsule.is-overdue')).toHaveCount(1);
  await expect(page.locator('.tfl-capsule')).toContainText('逾期');

  // 生长探针：观察 .tfl-card 内联 height 的逐帧写入（growOpen 的 spring 通道）
  await page.evaluate(() => {
    window.__morphFrames = 0;
    window.__morphSeen = false;
    const card = document.querySelector('.tfl-card');
    new MutationObserver(() => {
      const h = card.style.height || '';
      if (/^\d+(\.\d+)?px$/.test(h)) { window.__morphSeen = true; window.__morphFrames += 1; }
    }).observe(card, { attributes: true, attributeFilter: ['style'] });
    // 计数翻牌探针：计数宿主（展开后 = .tfl-header-count）的类变化
    window.__countFlipSeen = false;
    const host = document.querySelector('.tfl-header-count');
    new MutationObserver(() => {
      if (host.classList.contains('tfl-count-flip')) window.__countFlipSeen = true;
    }).observe(host, { attributes: true, attributeFilter: ['class'] });
  });

  await page.locator('.tfl-capsule').click();
  await expect(page.locator('.tfl-card[data-state="open"]')).toHaveCount(1);
  await page.waitForTimeout(1000);

  expect(await page.evaluate(() => window.__morphSeen)).toBe(true);
  expect(await page.evaluate(() => window.__morphFrames)).toBeGreaterThan(3); // 多帧 = 真 spring，不是单跳
  // 落地后的几何收尾契约（灵动岛批 2026-11-05 改写）：旧契约是「内联 height 清空、
  // 回到 natural 高」——岛改成单一变形元素后，**岛显式持有终态尺寸**（width/height
  // 逐帧写、落地保持在内容自然尺寸，由 ResizeObserver 跟随面板高度），所以判据从
  // 「清空」改成「落定值 == 面板自然尺寸」，transform 仍必须零内联残留（岛只写
  // 尺寸三通道，位移靠 CSS 静态 translateX(-50%)）。
  const settled = await page.evaluate(() => {
    const card = document.querySelector('.tfl-card');
    const panel = document.querySelector('.tfl-panel');
    return { h: card.style.height, w: card.style.width, t: card.style.transform,
      panelH: panel.offsetHeight, panelW: panel.offsetWidth };
  });
  expect(settled.t).toBe('');
  expect(Math.abs(parseFloat(settled.h) - settled.panelH)).toBeLessThanOrEqual(1);
  expect(Math.abs(parseFloat(settled.w) - settled.panelW)).toBeLessThanOrEqual(1);

  // 计数翻牌：完成一条 ⇒ 计数变化那一拍 tfl-count-flip 落在计数宿主上
  await page.locator('.tfl-row').first().locator('.tfl-complete').click();
  await page.waitForTimeout(1100);
  expect(await page.evaluate(() => window.__countFlipSeen)).toBe(true);
  // 单一计数节点仍在面板头槽（搬家不复制）
  await expect(page.locator('.tfl-header-count .tfl-cap-faces')).toHaveCount(1);

  expect(errors).toEqual([]);
});

// ── ⑧ 手机 375 视口（端区分批 2026-11-05：胶囊退场 / 左上圆钮入口）───────────
// 作者令 2026-11-05 09:28：「电脑端是一个胶囊，手机端还是需要是之前那个左上角的
// 一个悬浮的待办按钮」⇒ 本腿由「手机胶囊可见可展开」反转为「手机无胶囊、圆钮
// 开面板」；面板内的触摸档断言（加号 44 / 行尾删除常显）原样保留。
test('⑧ 手机 375：端区分——胶囊退场、左上任务圆钮开面板，加号 48px（Canvas 圆钮档）', async ({ page }) => {
  mkdirSync(SHOT_DIR, { recursive: true });
  const { errors } = await bootApp(page, { viewport: { width: 375, height: 812 } });
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });

  // 端区分的桌面侧（胶囊）在手机层退场
  expect(await page.locator('#task-list').evaluate((el) => getComputedStyle(el).display)).toBe('none');
  await expect(page.locator('#task-list .tfl-capsule')).not.toBeVisible();

  // 入口 = 聊天根左上悬浮「任务」圆钮（48 档，避安全区）
  const fab = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="tasks"]');
  await expect(fab).toBeVisible();
  const fabBox = await fab.boundingBox();
  expect(fabBox.x).toBeLessThan(24);
  expect(fabBox.y).toBeLessThan(48);
  expect(Math.round(fabBox.width)).toBe(48);
  expect(Math.round(fabBox.height)).toBe(48);
  await page.screenshot({ path: join(SHOT_DIR, '04_mobile_todo_fab.png') });

  // 点击 ⇒ push 任务页，面板体（同一张 .tfl-card，.tfl-mobile 档）展开
  await fab.click();
  await expect(page.locator('.mobile-page[data-page="tasks"].live')).toHaveCount(1);
  await expect(page.locator('#mobile-task-panel')).toBeVisible();
  const cardBox = await page.locator('#mobile-task-panel .tfl-card').boundingBox();
  expect(cardBox.width).toBeLessThanOrEqual(375);
  await expect(page.locator('#mobile-task-panel .tfl-list')).toBeVisible();
  await page.screenshot({ path: join(SHOT_DIR, '05_mobile_panel_open.png') });

  // 手机档尺寸：加号 = **Canvas 圆钮档 48**（todo-fullbleed 批 2026-11-05 作者令
  // 「加号按钮使用我们 canvas 同款的按钮风格」⇒ 钮挂 .g2-fab，尺寸跟
  // body.mobile-layer .g2-fab 的 48 = --mtap-lg，原 44 触摸档随批升档）；
  // 行尾删除常显。
  const addBox = await page.locator('#mobile-task-panel .tfl-fab-add').boundingBox();
  expect(Math.round(addBox.width)).toBe(48);
  expect(Math.round(addBox.height)).toBe(48);
  // 材质单源：加号必须是 .g2-fab 成员（不许自造第二套按钮样式）
  await expect(page.locator('#mobile-task-panel .tfl-fab-add')).toHaveClass(/g2-fab/);
  const delOpacity = await page.locator('#mobile-task-panel .tfl-del').first().evaluate((el) => getComputedStyle(el).opacity);
  expect(Number(delOpacity)).toBe(1);

  expect(errors).toEqual([]);
});
