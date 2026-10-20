// mobile-p3-reminder.spec.mjs — P3「定时任务」三分组面板验收
//（mobile-secondary-screens 实施批；样张 v3.5 P3 §8-4 + 开放点①②③⑤）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   §8-4 重复徽标枚举 {每小时,每天,每周}、单次行无徽标节点
//        …… .reminder-repeat-badge（桌面既有；repeatLabel 单次返回 '' 不建节点）。
//   §8-1 盒模型 …… #reminder-panel：left 6 / top 58+safe / 308×560 / r16。
//   开放点① 下次触发主行/上次副行 …… .reminder-row-time / .reminder-row-last
//        （桌面既有结构，样张同构）。
//   开放点③ 超时独立分组 …… .mh-rd-grp「已超时」组（renderList 手机层分支）。
//   开放点⑤ 已停用降透明垫底 …… 「已停用」组垫底 + .reminder-row.disabled 行级降透明。
//   开放点④ 创建入口 …… 头部 + 钮（#reminder-create-btn）与空态引导并存（桌面既有）。
//
// 数据面 = WS 夹具（listScheduledTasks → scheduledTaskList），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p3-reminder.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePanel } from './_mobile-panel-settle.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'p3-root-session';
const T0 = Date.now();

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 定时任务夹具：即将触发 4（含单次 t4 + 上次触发的 t1）/ 已超时 1 / 已停用 1。 */
function taskFixture() {
  return [
    { id: 't1', content: '每小时巡检构建', triggerAt: T0 + 3_600_000, repeat: 'hourly', enabled: true, triggered: false, lastTriggered: T0 - 3_600_000 },
    { id: 't2', content: '每天晨报摘要', triggerAt: T0 + 86_400_000, repeat: 'daily', enabled: true, triggered: false },
    { id: 't3', content: '每周复盘提醒', triggerAt: T0 + 7 * 86_400_000, repeat: 'weekly', enabled: true, triggered: false },
    { id: 't4', content: '单次：回看第 3 节', triggerAt: T0 + 1_800_000, enabled: true, triggered: false },
    { id: 't5', content: '已过期的旧任务', triggerAt: T0 - 3_600_000, enabled: true, triggered: false },
    { id: 't6', content: '已停用的旧任务', triggerAt: T0 + 999_999_999, enabled: false, triggered: false },
  ];
}

async function bootApp(page, { tasks = taskFixture() } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  // WS 句柄（Node 侧 WebSocketRoute）：供测试中段改推任务列表（空态腿）。
  // 注意：page.on('websocket') 的观察对象无 send —— 只有 routeWebSocket 的
  // WebSocketRoute 才能向页面注帧。
  let live = null;
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
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'listScheduledTasks') sendTasks();
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
    sendTasks: (list) => live?.send(JSON.stringify({ type: 'scheduledTaskList', tasks: list })),
  };
}

/** 开面板：左列液态下拉 → 定时键（面板 .open 判据 → floatController morph）。 */
async function openPanel(page) {
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.locator('#reminder-btn').click();
  await expect(page.locator('#reminder-panel')).toHaveClass(/open/);
  await expect(page.locator('#reminder-panel')).toBeVisible();
  // morph 终态（两段式守护，见 _mobile-panel-settle.mjs 头注）。
  await settlePanel(page, { overlaySel: '#reminder-panel', panelSel: '#reminder-panel', openClass: 'open' });
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  harness = await bootApp(page);
});
/** @type {{sendTasks: (list: any[]) => void}|null} */
let harness = null;

test('T1 panel geometry: top-hung 308×560, left 6, radius 16', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const box = await page.locator('#reminder-panel').boundingBox();
  expect(box).toBeTruthy();
  expect(box.x).toBe(6);
  expect(box.width).toBe(308);
  expect(box.height).toBe(560);
  const radius = await page.locator('#reminder-panel').evaluate((el) => getComputedStyle(el).borderRadius);
  expect(radius).toBe('16px');
  expect(errors).toEqual([]);
});

test('T2 three groups in order: 即将触发 → 已超时 → 已停用, disabled rows dimmed', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  const labels = await page.locator('.mh-rd-grp').allTextContents();
  expect(labels).toEqual(['即将触发 · 4', '已超时 · 1', '已停用 · 1']);

  // 组序：行在其组签之后、已停用行整体垫底。
  const body = page.locator('#reminder-panel .reminder-panel-body');
  const texts = await body.locator('.reminder-row-text').allTextContents();
  expect(texts[0]).toBe('每小时巡检构建');
  expect(texts[3]).toBe('单次：回看第 3 节');
  expect(texts[4]).toBe('已过期的旧任务');
  expect(texts[5]).toBe('已停用的旧任务');

  // 已停用行：disabled 类（行级降透明）+ 超时行：overdue 类（红 clock-alert 语义）。
  await expect(body.locator('.reminder-row', { hasText: '已停用的旧任务' })).toHaveClass(/disabled/);
  await expect(body.locator('.reminder-row', { hasText: '已过期的旧任务' })).toHaveClass(/overdue/);
  // 已停用行整行降透明（computed opacity < 1）。
  const dim = await body.locator('.reminder-row.disabled').first().evaluate((el) => getComputedStyle(el).opacity);
  expect(parseFloat(dim)).toBeLessThan(1);
  expect(errors).toEqual([]);
});

test('T3 repeat badge enum {每小时,每天,每周}; one-shot row has NO badge node (§8-4)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const body = page.locator('#reminder-panel .reminder-panel-body');

  const hourly = body.locator('.reminder-row', { hasText: '每小时巡检构建' });
  await expect(hourly.locator('.reminder-repeat-badge')).toHaveText('每小时');
  const daily = body.locator('.reminder-row', { hasText: '每天晨报摘要' });
  await expect(daily.locator('.reminder-repeat-badge')).toHaveText('每天');
  const weekly = body.locator('.reminder-row', { hasText: '每周复盘提醒' });
  await expect(weekly.locator('.reminder-repeat-badge')).toHaveText('每周');

  // 单次行：无徽标节点（§8-4 二值）。
  const once = body.locator('.reminder-row', { hasText: '单次：回看第 3 节' });
  await expect(once.locator('.reminder-repeat-badge')).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('T4 next-trigger main line + last-triggered sub line (open point ①)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const body = page.locator('#reminder-panel .reminder-panel-body');
  const row = body.locator('.reminder-row', { hasText: '每小时巡检构建' });
  await expect(row.locator('.reminder-row-time')).toContainText('今天');
  await expect(row.locator('.reminder-row-last')).toContainText('上次触发');
  // 无 lastTriggered 的行不出现副行节点。
  const daily = body.locator('.reminder-row', { hasText: '每天晨报摘要' });
  await expect(daily.locator('.reminder-row-last')).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('T5 create entries: header + button and empty-state guide both alive', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);

  // 头部 + 创建钮入口（开放点④：头部入口；桌面既有路径在面板内原样可用）。
  await expect(page.locator('#reminder-create-btn')).toBeVisible();
  await page.locator('#reminder-create-btn').click();
  await expect(page.locator('#reminder-inline-input')).toBeVisible();

  // 退出行内创建：点面板体非行区（分组签 div —— body 监听的取消路径；Esc 会
  // 关整个面板，不入本断言）。
  await page.locator('#reminder-panel .mh-rd-grp').first().click();
  await expect(page.locator('#reminder-inline-input')).toHaveCount(0);

  // 空态引导（开放点④：空态入口；tasks 空 + 非 creating 才显示）。
  harness?.sendTasks([]);
  const empty = page.locator('#reminder-panel .reminder-empty');
  await expect(empty).toBeVisible();
  await expect(empty).toContainText('暂无定时任务');
  await expect(empty).toContainText('点击空白处或按 + 创建');
  // 命中用 dispatchEvent（真点击动作性检查会撞上 smooth-scroll 重渲不稳定窗口；
  // 行为腿 = 同一 body click 委托路径）。
  await empty.dispatchEvent('click');
  await expect(page.locator('#reminder-inline-input')).toBeVisible();
  expect(errors).toEqual([]);
});
