// todo-split.spec.mjs — 待办体系改版批（2026-10-31 作者令）前端验收：
//  ① 分家渲染不回退：提醒面（顶栏时钟）只渲染定时任务（徽标=已超时数）；
//  ② 入口行：消息列表顶部唯一 .fm-todo-entry（徽标=待销账待办数，含镜像）；
//    侧边栏 #todo-btn / #panel-todo 已退役（DOM 缺席）；
//  ③ 提醒事项页：点入口行 → #todo-page.open；分组固定序 已到期（浮顶）→
//    待决策 → 待批准 → 计划；ask- 镜像零圆钮零 ×、perm- 镜像 chip=待批准；
//  ④ 闭环：镜像行点击跳回卡片（jumpToAsk 接线，零 pageerror）；镜像销账帧 ⇒
//    徽标收敛；计划行圆钮 ⇒ completeTodo 帧带行自身 sessionId（跨会话寻址）；
//  ⑤ 收件箱跨会话：页面列表请求帧带 scope:'all'；跨会话行（其它 sessionId）
//    渲染于计划组；
//  ⑥ 手机层级下钻：375 视口入口行在消息页；点击 ⇒ todo 详情页在栈、
//    #todo-page 收编进页面体、返回钮在。
//
// 数据面 = WS 夹具（listScheduledTasks → scheduledTaskList；scope:'all' 回执
//  跨会话聚合列表），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/todo-split.spec.mjs

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
 *  与 project（追加令①：项目维度分组/筛选）。 */
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
    const sendInbox = () => ws.send(JSON.stringify({ type: 'scheduledTaskList', tasks: inbox, scope: 'all' }));
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

/** 打开待办事项页（先激活消息面板——入口行在 panel-messages 内，未 active 时
 *  祖先 display:none 不可点；再点入口行）。 */
async function openTodoPage(page) {
  await page.locator('#messages-btn').click();
  await expect(page.locator('#panel-messages')).toHaveClass(/active/);
  await page.locator('#fm-conversations .fm-todo-entry').click();
  await expect(page.locator('#todo-page')).toHaveClass(/open/);
}

test('①② 分家渲染 + 入口行徽标：提醒面纯定时任务（徽标=超时 1），侧边栏 todo 槽退役，入口行徽标=6', async ({ page }) => {
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

  // 入口行：消息列表顶部唯一行（先激活消息面板——行在 panel-messages 内，
  // 面板未 active 时祖先 display:none，visible 断言不可达），徽标 = 待销账
  // 待办数（含镜像）= 6。
  await page.locator('#messages-btn').click();
  await expect(page.locator('#panel-messages')).toHaveClass(/active/);
  const entry = page.locator('#fm-conversations .fm-todo-entry');
  await expect(entry).toHaveCount(1);
  await expect(entry).toHaveAttribute('role', 'button');
  await expect(entry.locator('.fm-row-name')).toHaveText('待办事项');
  await expect(entry.locator('.fm-row-badge')).toBeVisible();
  await expect(entry.locator('.fm-row-badge')).toHaveText('6');
  // 副行 = 最早一条待办摘录（o1 已到期人建待办，夹具首行）。
  await expect(entry.locator('.todo-entry-summary')).toContainText('已到期的人建待办');

  expect(errors).toEqual([]);
});

test('③ 提醒事项页：分组固定序（已到期浮顶→待决策→待批准→计划），镜像行零圆钮，页头计数=6', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await openTodoPage(page);

  // 分组签固定序。
  const grpLabels = await page.locator('#todo-page-body .todo-grp').allTextContents();
  expect(grpLabels).toEqual(['已到期1', '待决策1', '待批准1', '计划3']);

  // 已到期组在行集最前（浮顶），行内逾期语言（红钟 overdue 族）。
  const firstRow = page.locator('#todo-page-body .reminder-row').first();
  await expect(firstRow).toHaveClass(/overdue/);
  await expect(firstRow).toContainText('已到期的人建待办');

  // 页头计数 = 6。
  await expect(page.locator('#todo-page-count')).toHaveText('6');

  // 待决策镜像行：chip「待答」在、打卡圆钮与取消 × 不在（唯一销账路 = ask 终态）。
  const askRow = page.locator('#todo-page-body .reminder-row[data-id="a1"]');
  await expect(askRow.locator('.todo-ask-chip')).toHaveText('待答');
  await expect(askRow.locator('.reminder-circle')).toHaveCount(0);
  await expect(askRow.locator('.reminder-cancel')).toHaveCount(0);

  // 待批准镜像行：chip「待批准」在、零圆钮零 ×。
  const permRow = page.locator('#todo-page-body .reminder-row[data-id="p1"]');
  await expect(permRow.locator('.todo-perm-chip')).toHaveText('待批准');
  await expect(permRow.locator('.reminder-circle')).toHaveCount(0);
  await expect(permRow.locator('.reminder-cancel')).toHaveCount(0);

  // 计划组：人建待办打卡圆钮在；缺卡 ≥3 的行带调整建议；跨会话行渲染在计划组。
  const d1Row = page.locator('#todo-page-body .reminder-row[data-id="d1"]');
  await expect(d1Row.locator('.reminder-circle')).toHaveCount(1);
  const d2Row = page.locator('#todo-page-body .reminder-row[data-id="d2"]');
  await expect(d2Row.locator('.reminder-miss-suggest')).toHaveCount(1);
  const x1Row = page.locator('#todo-page-body .reminder-row[data-id="x1"]');
  await expect(x1Row).toContainText('另一会话的待办');
  await page.screenshot({ path: join(SHOT_DIR, '02-todo-page-groups.png') });

  expect(errors).toEqual([]);
});

test('④ 闭环：镜像行点击跳回卡片（零 pageerror）；销账帧 ⇒ 徽标收敛；圆钮 ⇒ completeTodo 带行自身 sessionId', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoPage(page);

  // 收件箱请求帧带 scope:'all'（跨会话聚合语义）。
  const listFrame = harness.captured.find((m) => m.type === 'listScheduledTasks' && m.scope === 'all');
  expect(listFrame).toBeTruthy();

  // ask / perm 镜像行点击：jumpToAsk 接线（会话内无实体卡 ⇒ 对账退役 + toast，
  // 属预期路径；本钉只断「接线零报错 + 行可点」）。
  await page.locator('#todo-page-body .reminder-row[data-id="a1"]').click();
  await page.waitForTimeout(400);
  await page.locator('#todo-page-body .reminder-row[data-id="p1"]').click();
  await page.waitForTimeout(800);

  // 引擎侧销账帧（AskTodoBridge retire 广播形态）到达：ask + perm 镜像行消失。
  const remaining = inboxFixture().filter((t) => t.id !== 'a1' && t.id !== 'p1');
  await harness.sendInbox(remaining);
  await expect(page.locator('#todo-page-body .reminder-row[data-id="a1"]')).toHaveCount(0);
  await expect(page.locator('#todo-page-body .reminder-row[data-id="p1"]')).toHaveCount(0);

  // 跨会话行（x1，sessionId=other）圆钮打卡 ⇒ completeTodo 帧带行自身 sessionId。
  await page.locator('#todo-page-body .reminder-row[data-id="x1"] .reminder-circle').click();
  const doneFrame = harness.captured.find((m) => m.type === 'completeTodo');
  expect(doneFrame).toBeTruthy();
  expect(doneFrame.sessionId).toBe(OTHER_SID);
  expect(doneFrame.id).toBe('x1');

  // 本地乐观收敛 + 入口行徽标 6→3（a1/p1 镜像销账 + x1 打卡）。
  await expect(page.locator('#fm-conversations .fm-todo-entry .fm-row-badge')).toHaveText('3');
  await expect(page.locator('#todo-page-count')).toHaveText('3');

  expect(errors).toEqual([]);
});

test('⑤ 待办创建：纯文本内联创建 ⇒ createScheduledTask 帧带 kind:todo；乐观行落收件箱面，提醒面零混入', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoPage(page);

  await page.locator('#todo-create-btn').click();
  const input = page.locator('#todo-inline-input');
  await expect(input).toBeVisible();
  await input.fill('写周报前三段');
  await input.press('Enter');

  const createFrame = harness.captured.find((m) => m.type === 'createScheduledTask');
  expect(createFrame).toBeTruthy();
  expect(createFrame.kind).toBe('todo');
  expect(createFrame.content).toBe('写周报前三段');
  expect(createFrame.sessionId).toBe(SID);

  // 乐观行落在待办页（收件箱面 6+1）；提醒面行集不变（纯定时任务 2 条）。
  await expect(page.locator('#todo-page-body .reminder-row')).toHaveCount(6 + 1);
  const reminderRows = await page.locator('#reminder-panel .reminder-row').allTextContents();
  expect(reminderRows).toHaveLength(2);
  expect(reminderRows.join('\n')).not.toContain('写周报前三段');
  await page.screenshot({ path: join(SHOT_DIR, '03-todo-inline-create.png') });

  expect(errors).toEqual([]);
});

test('⑦ 项目维度（追加令①②）：筛选 chips 单选生效（计划组行随滤）；✎ 编辑 ⇒ updateScheduledTask 帧带 content+project', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoPage(page);

  // 筛选 chips 由收件箱派生：全部 / DOSTEL / stand / 未分组（升序 + 未分组殿后）。
  const chipTexts = await page.locator('.todo-filter-row .todo-filter-chip').allTextContents();
  expect(chipTexts).toEqual(['全部', 'DOSTEL', 'stand', '未分组']);

  // 点 stand ⇒ 只剩该项目 2 行（计划组）；行内项目标签在。
  await page.locator('.todo-filter-chip[data-project="stand"]').click();
  await expect(page.locator('#todo-page-body .reminder-row')).toHaveCount(2);
  const d1Tag = page.locator('#todo-page-body .reminder-row[data-id="d1"] .reminder-proj-tag');
  await expect(d1Tag).toHaveText('stand');
  await expect(page.locator('#todo-page-body .reminder-row[data-id="x1"]')).toHaveCount(0);

  // 点全部 ⇒ 恢复 6 行。
  await page.locator('.todo-filter-chip[data-project=""]').click();
  await expect(page.locator('#todo-page-body .reminder-row')).toHaveCount(6);

  // ✎ 编辑（追加令②）：表单预填原内容/归属 → 改内容保存 ⇒ updateScheduledTask
  // 帧（字段级 patch）+ 行就地更新。
  await page.locator('#todo-page-body .reminder-row[data-id="d1"] .reminder-edit').click();
  const input = page.locator('#todo-inline-input');
  await expect(input).toHaveValue('人建待办甲');
  await expect(page.locator('#todo-inline-project')).toHaveValue('stand');
  await input.fill('人建待办甲（改版后）');
  await input.press('Enter');

  const updFrame = harness.captured.find((m) => m.type === 'updateScheduledTask');
  expect(updFrame).toBeTruthy();
  expect(updFrame.id).toBe('d1');
  expect(updFrame.content).toBe('人建待办甲（改版后）');
  expect(updFrame.project).toBe('stand');
  await expect(page.locator('#todo-page-body .reminder-row[data-id="d1"] .reminder-row-text')).toHaveText('人建待办甲（改版后）');

  // 创建表单项目预填 = 当前筛选（stand）⇒ createScheduledTask 帧带 project。
  await page.locator('.todo-filter-chip[data-project="stand"]').click();
  await page.locator('#todo-create-btn').click();
  await page.locator('#todo-inline-input').fill('stand 的新待办');
  await page.locator('#todo-inline-input').press('Enter');
  const createFrames = harness.captured.filter((m) => m.type === 'createScheduledTask' && m.content === 'stand 的新待办');
  expect(createFrames).toHaveLength(1);
  expect(createFrames[0].project).toBe('stand');
  await page.screenshot({ path: join(SHOT_DIR, '05-todo-project-filter-edit.png') });

  expect(errors).toEqual([]);
});

test('⑥ 手机层级下钻：375 视口入口行在消息页；点击 ⇒ todo 详情页收编 #todo-page、返回钮在', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { viewport: { width: 375, height: 720 } });

  // 手机层激活（body.mobile-layer）+ dock 到消息根页（首启默认 chat 根页，
  // 消息页体未 live 时入口行不可点）⇒ 入口行随容器收编可见。
  await expect(page.locator('body.mobile-layer')).toHaveCount(1);
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await expect(page.locator('.mobile-page[data-page="messages"].live')).toHaveCount(1);
  const entry = page.locator('.mobile-page[data-page="messages"] .fm-todo-entry');
  await expect(entry).toHaveCount(1);
  await expect(entry.locator('.fm-row-badge')).toHaveText('6');

  // 点击入口行 ⇒ pushPage('todo')：详情页在栈、#todo-page 收编进页面体、.open。
  await entry.click();
  await expect(page.locator('.mobile-page[data-page="todo"]')).toHaveCount(1);
  await expect(page.locator('#todo-page')).toHaveClass(/open/);
  const adopted = await page.evaluate(() => {
    const pageEl = document.querySelector('.mobile-page[data-page="todo"] .mobile-page-body');
    return !!(pageEl && pageEl.querySelector('#todo-page'));
  });
  expect(adopted).toBe(true);
  // 层级页返回钮在（左上「<」）。
  await expect(page.locator('.mobile-page[data-page="todo"] .mobile-back-btn')).toHaveCount(1);
  // 分组签在手机页照常渲染。
  await expect(page.locator('#todo-page-body .todo-grp')).toHaveCount(4);
  await page.screenshot({ path: join(SHOT_DIR, '04-mobile-todo-page.png') });

  // 返回 ⇒ 出栈归还：#todo-page 关态、离开页面体。
  await page.locator('.mobile-page[data-page="todo"] .mobile-back-btn').click();
  await expect(page.locator('#todo-page')).not.toHaveClass(/open/);

  expect(errors).toEqual([]);
});
