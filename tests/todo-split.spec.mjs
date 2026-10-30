// todo-split.spec.mjs — 待办分家批（2026-10-30 作者令）前端验收：
//  ① 分家渲染：提醒面（顶栏时钟）只渲染定时任务，待办面（侧边栏 #todo-btn）
//    只渲染 kind=todo——打卡行（圆钮+取消×）与待答镜像行（chip，无圆钮）同列；
//  ② 徽标语义理清：提醒徽标 = 已超时定时任务数（不含待办）；todo 徽标 =
//    待销账待办数（含待答镜像）；
//  ③ 闭环 Ask→待办：ask- 镜像行点击跳回提问卡（askPending.jumpToAsk 接线，
//    零 pageerror）；镜像销账 = 引擎侧 scheduledTaskDeleted（模拟回执 ⇒ 徽标收敛）；
//  ④ 待办创建：纯文本内联创建 ⇒ createScheduledTask 帧带 kind:'todo'。
//
// 数据面 = WS 夹具（listScheduledTasks → scheduledTaskList），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/todo-split.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '.nebflow', 'evidence', '20261030_todo-split', 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'todo-split-root';
const T0 = Date.now();

test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });

/** 混合夹具：定时 2（即将触发 1 + 已超时 1）+ 人建待办 1 + 周报打卡待办 1（缺卡 4）+ 待答镜像 1。 */
function taskFixture() {
  return [
    { id: 's1', content: '即将触发的定时任务', triggerAt: T0 + 3_600_000, enabled: true, triggered: false, kind: null },
    { id: 's2', content: '已超时的定时任务', triggerAt: T0 - 3_600_000, enabled: true, triggered: false, kind: null },
    { id: 'd1', content: '人建待办甲', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0 },
    { id: 'd2', content: '周报打卡待办', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'weekly-report-42', missCount: 4 },
    { id: 'a1', content: '[待答] 选哪个方案？ — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'ask-asknb-aaa111', missCount: 0 },
  ];
}

async function bootApp(page, { tasks = taskFixture() } = {}) {
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
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      captured.push(parsed);
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'listScheduledTasks') sendTasks();
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

/** 打开待办侧边栏面板（点 #todo-btn：展开 + 激活）。 */
async function openTodoPanel(page) {
  await page.locator('#todo-btn').click();
  await expect(page.locator('#panel-todo')).toHaveClass(/active/);
}

test('①② 分家渲染 + 徽标语义：提醒面纯定时任务（徽标=超时 1），待办面纯待办（徽标=3，含待答镜像）', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);

  // 提醒面：只有两条定时任务，零待办行。
  await page.locator('#reminder-btn').click();
  await expect(page.locator('#reminder-panel')).toHaveClass(/open/);
  const reminderRows = await page.locator('#reminder-panel .reminder-row').allTextContents();
  expect(reminderRows.join('\n')).toContain('即将触发的定时任务');
  expect(reminderRows.join('\n')).toContain('已超时的定时任务');
  expect(reminderRows.join('\n')).not.toContain('人建待办甲');
  expect(reminderRows.join('\n')).not.toContain('选哪个方案');
  // 徽标 = 已超时定时任务数 = 1（待办不计——分家语义）。
  await expect(page.locator('#reminder-btn .reminder-badge')).toHaveText('1');
  await page.screenshot({ path: join(SHOT_DIR, '01-reminder-face-scheduled-only.png') });

  // 待办面：三条待办（打卡行 ×2 + 待答镜像 ×1），零定时任务行。
  await openTodoPanel(page);
  const todoRows = await page.locator('#todo-panel-body .reminder-row').allTextContents();
  expect(todoRows).toHaveLength(3);
  expect(todoRows.join('\n')).toContain('人建待办甲');
  expect(todoRows.join('\n')).toContain('周报打卡待办');
  expect(todoRows.join('\n')).toContain('选哪个方案');
  expect(todoRows.join('\n')).not.toContain('即将触发的定时任务');
  // 头部计数 + 按钮徽标 = 3。
  await expect(page.locator('#todo-panel-count')).toHaveText('3');
  await expect(page.locator('#todo-badge')).toBeVisible();
  await expect(page.locator('#todo-badge')).toHaveText('3');

  // 待答镜像行：chip「待答」在、打卡圆钮与取消 × 不在（镜像唯一销账路 = ask 终态）。
  const askRow = page.locator('#todo-panel-body .reminder-row[data-id="a1"]');
  await expect(askRow.locator('.todo-ask-chip')).toHaveText('待答');
  await expect(askRow.locator('.reminder-circle')).toHaveCount(0);
  await expect(askRow.locator('.reminder-cancel')).toHaveCount(0);
  // 人建待办行：打卡圆钮在（销账语义属于人建待办）。
  const d1Row = page.locator('#todo-panel-body .reminder-row[data-id="d1"]');
  await expect(d1Row.locator('.reminder-circle')).toHaveCount(1);
  // 缺卡 ≥3 的打卡行带调整建议。
  const d2Row = page.locator('#todo-panel-body .reminder-row[data-id="d2"]');
  await expect(d2Row.locator('.reminder-miss-suggest')).toHaveCount(1);
  await page.screenshot({ path: join(SHOT_DIR, '02-todo-face-with-ask-mirror.png') });

  expect(errors).toEqual([]);
});

test('③ 闭环：ask 镜像行点击跳回提问卡（零 pageerror）；镜像销账帧 ⇒ 徽标收敛', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoPanel(page);

  // 点击待答镜像行：jumpToAsk 接线（会话内无实体卡 ⇒ 对账退役 + toast，属预期
  // 路径；本钉只断「接线零报错 + 行可点」）。
  await page.locator('#todo-panel-body .reminder-row[data-id="a1"]').click();
  await page.waitForTimeout(800);

  // 引擎侧销账帧（AskTodoBridge retire 的广播形态）到达：镜像行消失、徽标 3→2。
  await harness.sendTasks(taskFixture().filter((t) => t.id !== 'a1'));
  await expect(page.locator('#todo-panel-body .reminder-row[data-id="a1"]')).toHaveCount(0);
  await expect(page.locator('#todo-badge')).toHaveText('2');
  await expect(page.locator('#todo-panel-count')).toHaveText('2');

  expect(errors).toEqual([]);
});

test('④ 待办创建：纯文本内联创建 ⇒ createScheduledTask 帧带 kind=todo', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);
  await openTodoPanel(page);

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

  // 乐观行落在待办面（kind=todo 临时行）；提醒面行集不变（渲染与开合态无关，
  // 行集仍纯定时任务 2 条，新待办不混入）。
  await expect(page.locator('#todo-panel-body .reminder-row')).toHaveCount(3 + 1);
  const reminderRows = await page.locator('#reminder-panel .reminder-row').allTextContents();
  expect(reminderRows).toHaveLength(2);
  expect(reminderRows.join('\n')).not.toContain('写周报前三段');
  await page.screenshot({ path: join(SHOT_DIR, '03-todo-inline-create.png') });

  expect(errors).toEqual([]);
});
