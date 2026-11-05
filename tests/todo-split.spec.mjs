// todo-split.spec.mjs — 待办体系改版批（2026-10-31 作者令）前端验收（**入口收敛批
// 2026-11-04 改写**：入口行与 #todo-page 退役，验收面整体迁到顶带待办胶囊）。
//
// ⚠ 入口收敛批 todo-unify-entry-collapse 2026-11-04 的改写说明（不删测试）：
//   原 ①②③④⑤⑥⑦ 的**意图逐条保留**（分家渲染 / 待办面读数与行集 / 闭环写帧 /
//   收件箱跨会话 / 项目维度筛选 / 手机下钻），但承载面从「消息列表入口行 +
//   #todo-page」换成「顶带胶囊 + 胶囊展开面板」：
//   · 入口行 `.fm-todo-entry` 与 `#todo-page` 的 DOM 断言 ⇒ 改为**退役断言**
//     （入口行零命中、页面无开合路径）——它们现在是该 spec 要防的回退点；
//   · 页头计数 `#todo-page-count` ⇒ 胶囊计数节点（`.tfl-cap-faces` /
//     `.tfl-capsule-count` / `.tfl-capsule-overdue`，展开时同一节点进面板头）；
//   · 页面行 `.reminder-row` ⇒ 胶囊行 `.tfl-row`；分组固定序页签 ⇒ 分组 chips
//     （project 投影）；打卡圆钮 `.reminder-circle` ⇒ 行尾完成钮 `.tfl-complete`；
//   · 页内新建 `#todo-create-btn` ⇒ 面板右下角新建加号 `.tfl-fab-add`；
//   · 手机 `pushPage('todo')` 下钻 ⇒ 胶囊在手机聊天根直接展开（无第二页面腿）。
//
// 数据面 = WS 夹具（listScheduledTasks → scheduledTaskList；scope:'all' 回执
//  跨会话聚合列表），零后端、零 sbt、零监听端口。
// Run: node node_modules/@playwright/test/cli.js test tests/todo-split.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '.nebflow', 'evidence', '20261031_todo-inbox-redesign', 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'todo-split-root';
const OTHER_SID = 'todo-split-other';
const T0 = Date.now();

test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });

/** 单会话夹具（提醒面渲染源）：定时 2（即将触发 1 + 已超时 1）+ 人建待办 1 +
 *  周报打卡待办 1（缺卡 4）+ 待答镜像 1。 */
function taskFixture() {
  return [
    { id: 's1', content: '即将触发的定时任务', triggerAt: T0 + 3_600_000, enabled: true, triggered: false, kind: null },
    { id: 's2', content: '已超时的定时任务', triggerAt: T0 - 3_600_000, enabled: true, triggered: false, kind: null },
    { id: 'd1', content: '人建待办甲', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0 },
    { id: 'd2', content: '周报打卡待办', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'weekly-report-42', missCount: 4 },
    { id: 'a1', content: '[待答] 选哪个方案？ — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'ask-asknb-aaa111', missCount: 0 },
  ];
}

/** 跨会话收件箱夹具（scope:'all' 回执）：已到期 1 + 待决策 1 + 待批准 1 +
 *  计划 3（含一条其它会话、两条带项目标签）。逐行带 sessionId（行级寻址面）
 *  与 project（追加令①：项目维度分组/筛选）。
 *  胶囊读数（todoStore 契约：镜像行 ask-/perm- 不入人待办面）：
 *  未完成 = o1 + d1 + d2 + x1 = 4，逾期 = o1 = 1。 */
function inboxFixture() {
  return [
    { id: 'o1', content: '已到期的人建待办', triggerAt: T0 - 3_600_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID },
    { id: 'a1', content: '[待答] 选哪个方案？ — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'ask-asknb-aaa111', missCount: 0, sessionId: SID },
    { id: 'p1', content: '[待批准] Bash · 删除临时目录 — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'perm-permnb-bbb222', missCount: 0, sessionId: SID },
    { id: 'd1', content: '人建待办甲', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID, project: 'stand' },
    { id: 'd2', content: '周报打卡待办', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'weekly-report-42', missCount: 4, sessionId: SID, project: 'stand' },
    { id: 'x1', content: '另一会话的待办', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: OTHER_SID, project: 'DOSTEL' },
  ];
}

async function bootApp(page, { tasks = taskFixture(), inbox = inboxFixture(), viewport } = {}) {
  if (viewport) await page.setViewportSize(viewport);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  let live = null;
  const captured = [];
  /** 真源回执模拟（本 spec 轻量夹具）：`completeTodo` 帧被服务端接受后，
   *  真源列表里该行即变 completed ⇒ 后续 scope:'all' 回执必须回禁行
   *  （否则 todoStore 的去抖 refresh 会把已完成行拉回未完成态，行不会退场）。
   *  与持久化票 persist2 的「完成 ⇒ 刷新后仍在已完成态」同语义。 */
  const completedIds = new Set();
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
    live = ws;
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    const sendTasks = () => ws.send(JSON.stringify({ type: 'scheduledTaskList', tasks }));
    /** scope:'all' 回执按**真源当前态**回（completedIds 里的行带 completed:true）——
     *  否则 todoStore 的去抖 refresh 会用未改态夹具把已完成行拉回可见面。 */
    const sendInbox = () => ws.send(JSON.stringify({
      type: 'scheduledTaskList',
      scope: 'all',
      tasks: inbox.map((t) => (completedIds.has(t.id) ? { ...t, completed: true, completedAt: Date.now() } : { ...t })),
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      captured.push(parsed);
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'listScheduledTasks') {
        if (parsed.scope === 'all') sendInbox();
        else sendTasks();
      }
      else if (parsed.type === 'completeTodo') {
        // 写帧被接受 ⇒ 真源置 completed，并回 scheduledTaskCompleted（+ 重发
        // 收件箱回执）；与后端「完成 ⇒ 刷新后仍在已完成态」同语义。
        completedIds.add(parsed.id);
        const row = inbox.find((t) => t.id === parsed.id);
        ws.send(JSON.stringify({ type: 'scheduledTaskCompleted', task: row, completedAt: Date.now() }));
        sendInbox();
      }
      else if (parsed.type === 'getPendingAsks') ws.send(JSON.stringify({ type: 'pendingAsksSnapshot', asks: [] }));
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return {
    captured,
    sendTasks: (list) => live?.send(JSON.stringify({ type: 'scheduledTaskList', tasks: list })),
    sendInbox: (list) => live?.send(JSON.stringify({ type: 'scheduledTaskList', tasks: list, scope: 'all' })),
  };
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const text = m.text();
    // 资源面噪音豁免：夹具只伺服 WEB 目录，品牌 SVG 等静态件 404 属环境噪声，
    // 与功能断言无关（pageerror 与 JS console.error 仍全收）。
    if (/Failed to load resource|fetching the script/i.test(text)) return;
    errors.push(`console: ${text}`);
  });
  return errors;
}

/** 打开待办面（入口收敛后 = 顶带胶囊；点开即展开面板）。 */
async function openTodoFace(page) {
  const capsule = page.locator('#task-list .tfl-capsule');
  await expect(capsule).toHaveCount(1);
  await expect(capsule).toBeVisible();
  await capsule.click();
  await expect(page.locator('#task-list .tfl-card')).toHaveAttribute('data-state', 'open');
  await expect(page.locator('#task-list .tfl-card')).toBeVisible();
  return capsule;
}

test('①② 分家渲染 + 待办入口收敛：提醒面纯定时任务（徽标=超时 1），侧边栏 todo 槽退役，入口行退役，胶囊读数=4 待办 / 1 逾期', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);

  // 侧边栏槽位退役（改版批硬约束：不占单独侧边栏空间）。
  await expect(page.locator('#todo-btn')).toHaveCount(0);
  await expect(page.locator('#panel-todo')).toHaveCount(0);

  // 提醒面：只有两条定时任务，零待办行（分家语义不回退）。
  await page.locator('#reminder-btn').click();
  await expect(page.locator('#reminder-panel')).toHaveClass(/open/);
  const reminderRows = await page.locator('#reminder-panel .reminder-row').allTextContents();
  expect(reminderRows.join('\n')).toContain('即将触发的定时任务');
  expect(reminderRows.join('\n')).toContain('已超时的定时任务');
  expect(reminderRows.join('\n')).not.toContain('人建待办甲');
  expect(reminderRows.join('\n')).not.toContain('选哪个方案');
  await expect(page.locator('#reminder-btn .reminder-badge')).toHaveText('1');
  await page.screenshot({ path: join(SHOT_DIR, '01-reminder-face-scheduled-only.png') });

  // 入口收敛（本批硬约束）：消息列表顶部「待办事项」入口行退役 ⇒ 零命中；
  // 消息列表面本身仍在（不是把面板一起撤了）。
  await page.locator('#messages-btn').click();
  await expect(page.locator('#panel-messages')).toHaveClass(/active/);
  await expect(page.locator('.fm-todo-entry')).toHaveCount(0);
  await expect(page.locator('#fm-conversations [data-todo-entry]')).toHaveCount(0);

  // 读数接班：待销账待办数（原入口行徽标）= 胶囊计数节点；逾期读数同节点翻面。
  await expect(page.locator('#task-list .tfl-capsule')).toHaveCount(1);
  await expect(page.locator('#task-list .tfl-capsule-count')).toHaveText('4 待办');
  await expect(page.locator('#task-list .tfl-capsule-overdue')).toHaveText('1 逾期');
  await expect(page.locator('#task-list .tfl-capsule')).toHaveClass(/is-overdue/);

  expect(errors).toEqual([]);
});

test('③ 待办面（胶囊展开面板）：行集=人待办 4 行（镜像行不入），逾期行红字，分组 chips=stand/DOSTEL', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await openTodoFace(page);

  // 行集：4 条人建待办（o1 已到期 + d1/d2 计划 + x1 跨会话）；ask-/perm-
  // 镜像行按 todoStore 契约不入人待办面（唯一销账路 = ask/perm 终态）。
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(4);
  await expect(page.locator('#task-list .tfl-row[data-id="a1"]')).toHaveCount(0);
  await expect(page.locator('#task-list .tfl-row[data-id="p1"]')).toHaveCount(0);

  // 逾期行 = 红字 due 读数（ReminderIsland 复刻：overdue 语言）。
  await expect(page.locator('#task-list .tfl-row[data-id="o1"] .tfl-row-due-text.overdue')).toHaveCount(1);
  await expect(page.locator('#task-list .tfl-row[data-id="d1"]')).toContainText('人建待办甲');
  // 跨会话行（其它 sessionId）在人待办面照常可见（收件箱跨会话语义）。
  await expect(page.locator('#task-list .tfl-row[data-id="x1"]')).toContainText('另一会话的待办');

  // 分组可读性 = project 投影 chips（stand / DOSTEL），行内组徽标在场。
  await page.locator('#task-list .tfl-filter-toggle').click();
  const chips = (await page.locator('#task-list .tfl-chip-tag').allTextContents()).map((s) => s.trim()).sort();
  expect(chips).toEqual(['DOSTEL', 'stand']);
  await expect(page.locator('#task-list .tfl-row[data-id="d2"] .tfl-row-badge')).toHaveText('stand');
  await page.screenshot({ path: join(SHOT_DIR, '02-todo-capsule-panel.png') });

  expect(errors).toEqual([]);
});

test('④ 闭环：完成钮 ⇒ completeTodo 帧带行自身 sessionId；计数收敛 4→3；收件箱请求帧带 scope:all', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoFace(page);

  // 收件箱请求帧带 scope:'all'（跨会话聚合语义）。
  const listFrame = harness.captured.find((m) => m.type === 'listScheduledTasks' && m.scope === 'all');
  expect(listFrame).toBeTruthy();

  // 跨会话行（x1，sessionId=other）行尾完成钮 ⇒ completeTodo 帧带行自身 sessionId。
  // 完成 = 三段动画（0s 填绿 → +0.15 对勾 → +0.5 行淡出 → +0.8s 真落库），
  // 故断言等「行退场 + 计数收敛」后再读帧，不用定时 sleep。
  await page.locator('#task-list .tfl-row[data-id="x1"] .tfl-complete').click();
  await expect(page.locator('#task-list .tfl-row[data-id="x1"]')).toHaveCount(0, { timeout: 5000 });
  const doneFrame = harness.captured.find((m) => m.type === 'completeTodo');
  expect(doneFrame).toBeTruthy();
  expect(doneFrame.sessionId).toBe(OTHER_SID);
  expect(doneFrame.id).toBe('x1');

  // 本地乐观收敛：读数 4→3（胶囊与面板头是同一节点）。
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(3);
  await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('3 待办');

  expect(errors).toEqual([]);
});

test('⑤ 待办创建：面板加号内联创建 ⇒ createScheduledTask 帧带 kind:todo；乐观行落胶囊面，提醒面零混入', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoFace(page);

  await page.locator('#task-list .tfl-fab-add').click();
  const input = page.locator('#task-list .tfl-create-row .tfl-row-title-input');
  await expect(input).toBeVisible();
  await input.fill('写周报前三段');
  await input.press('Enter');

  const createFrame = harness.captured.find((m) => m.type === 'createScheduledTask');
  expect(createFrame).toBeTruthy();
  expect(createFrame.kind).toBe('todo');
  expect(createFrame.content).toBe('写周报前三段');
  expect(createFrame.sessionId).toBe(SID);

  // 乐观行落在胶囊面（4+1）；提醒面行集不变（纯定时任务 2 条）。
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(5);
  await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('5 待办');
  const reminderRows = await page.locator('#reminder-panel .reminder-row').allTextContents();
  expect(reminderRows).toHaveLength(2);
  expect(reminderRows.join('\n')).not.toContain('写周报前三段');
  await page.screenshot({ path: join(SHOT_DIR, '03-todo-inline-create.png') });

  expect(errors).toEqual([]);
});

test('⑦ 项目维度（追加令①②）：分组 chips 单选生效（行随滤）；行内编辑 ⇒ updateScheduledTask 帧带 content+project', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoFace(page);
  await page.locator('#task-list .tfl-filter-toggle').click();

  // 分组 chips 由收件箱派生：DOSTEL / stand（升序；无 project 的行归 All 总条目）。
  const chipTexts = (await page.locator('#task-list .tfl-chip-tag').allTextContents()).map((s) => s.trim()).sort();
  expect(chipTexts).toEqual(['DOSTEL', 'stand']);

  // 点 stand ⇒ 只剩该项目 2 行；行内项目徽标在。
  await page.locator('#task-list .tfl-chip-tag', { hasText: 'stand' }).click();
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(2);
  await expect(page.locator('#task-list .tfl-row[data-id="x1"]')).toHaveCount(0);

  // 回到 All（总条目）⇒ 恢复 4 行。
  await page.locator('#task-list .tfl-chip-all').click();
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(4);

  // 行内编辑（追加令②）：点行体进编辑 → 改标题 + Enter ⇒ updateScheduledTask
  // 帧（字段级 patch：content + 未变的 project 不重发）+ 行就地更新。
  await page.locator('#task-list .tfl-row[data-id="d1"] .tfl-row-main').click();
  const edit = page.locator('#task-list .tfl-row[data-id="d1"] .tfl-row-title-input');
  await expect(edit).toHaveValue('人建待办甲');
  await edit.fill('人建待办甲（改版后）');
  await edit.press('Enter');

  const updFrame = harness.captured.find((m) => m.type === 'updateScheduledTask');
  expect(updFrame).toBeTruthy();
  expect(updFrame.id).toBe('d1');
  expect(updFrame.content).toBe('人建待办甲（改版后）');
  expect(updFrame.sessionId).toBe(SID);
  await expect(page.locator('#task-list .tfl-row[data-id="d1"] .tfl-row-title')).toHaveText('人建待办甲（改版后）');

  // 分组内新建（stand 视图下）⇒ createScheduledTask 帧带 project=stand。
  await page.locator('#task-list .tfl-chip-tag', { hasText: 'stand' }).click();
  await page.locator('#task-list .tfl-fab-add').click();
  const createInput = page.locator('#task-list .tfl-create-row .tfl-row-title-input');
  await createInput.fill('stand 的新待办');
  await createInput.press('Enter');
  const createFrames = harness.captured.filter((m) => m.type === 'createScheduledTask' && m.content === 'stand 的新待办');
  expect(createFrames).toHaveLength(1);
  expect(createFrames[0].project).toBe('stand');
  await page.screenshot({ path: join(SHOT_DIR, '05-todo-project-filter-edit.png') });

  expect(errors).toEqual([]);
});

test('⑥ 手机层级：胶囊是唯一待办入口（聊天根顶带），点击直接展开；无入口行、无 todo 页面腿', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { viewport: { width: 375, height: 720 } });

  // 手机层激活 + 无待办入口行（原「消息列表入口行 → pushPage('todo')」两腿都退役）。
  await expect(page.locator('body.mobile-layer')).toHaveCount(1);
  await expect(page.locator('.fm-todo-entry')).toHaveCount(0);
  await expect(page.locator('[data-chrome="tasks"]')).toHaveCount(0);
  await expect(page.locator('#mobile-task-panel')).toHaveCount(0);

  // 胶囊在顶带可点（手机端唯一入口）⇒ 直接展开同一张面板。
  const capsule = await openTodoFace(page);
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(4);
  await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('4 待办');
  await page.screenshot({ path: join(SHOT_DIR, '04-mobile-todo-capsule.png') });

  // 收起后胶囊仍在（无第二个面可去）。展开态胶囊按设计让位给面板本体
  // （JS 置 visibility:hidden）⇒ 收起走面板头 chevron。
  await page.locator('#task-list .tfl-collapse-btn').click();
  await expect(page.locator('#task-list .tfl-card')).toHaveAttribute('data-state', 'capsule');
  await expect(capsule).toBeVisible();

  // 消息页（dock 键）不再有待办入口行。
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await expect(page.locator('.mobile-page[data-page="messages"].live')).toHaveCount(1);
  await expect(page.locator('.mobile-page[data-page="messages"] .fm-todo-entry')).toHaveCount(0);
  await expect(page.locator('#todo-page')).not.toHaveClass(/open/);

  expect(errors).toEqual([]);
});
