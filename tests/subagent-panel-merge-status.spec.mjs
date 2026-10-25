// subagent-panel-merge-status.spec.mjs — 子智能体面板「候合并」状态钉验收
// （panel-merge-status 批 2026-10-25 · 作者令「子智能体面板显示候合并状态与队列位次」）。
//
// 作者令四条 → 断言面：
//   T1 状态钉 …… 候合并行状态位直显「候合并」（不再隐没在通用「停靠等待」文案）；
//      队列位次段「前面还有 N 位」（数据 = GET /api/merge-queue 条目 position，
//      前端只渲染不派生）+「第 N 次重试」（retryCount ≥ 1）+ 拒绝原因摘要一句
//      （展示截断、全文进 title tooltip）。
//   T2 队首降段 …… position=1 ⇒ 无「前面还有」段；retryCount=0 / 原因空 ⇒ 段全缺
//      ——行回静态「等待合并轮」形态。
//   T3 API 未生效降级 …… /api/merge-queue 404 ⇒ 静态「候合并」+「等待合并轮」，
//      零位次、零报错弹面（预期的资源 404 日志除外）、零 pageerror。
//   T4 形状漂移/空队列降级 …… 载荷 `{}` / 空 queues ⇒ 同静态形态，零噪音。
//   T5 不空转轮询 …… 无候合并行的面板零次 /api/merge-queue 请求（取数纯事件驱动，
//      模块内节流+失败退避兜底）。
//
// 数据面 = WS activeAgents 帧 + /api/merge-queue 路由打桩，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/subagent-panel-merge-status.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'mqst-root-session';
const DELEGATE_SID = 'delegate-mqst-1';
const T0 = Date.now();

/** merge-queue 打桩载荷（一条项目队列；entries 可空）。 */
function queuePayload(entries) {
  return {
    generatedAt: Date.now(),
    queues: [{ projectKey: 'stand', inFlight: false, entries }],
  };
}
function queueEntry(over = {}) {
  return {
    delegateId: DELEGATE_SID,
    description: '面板候合并样例批次',
    branch: 'delegate/delegate-mqst-1',
    baseBranch: 'main',
    baseWs: '/ws',
    taskId: null,
    parentSessionId: ROOT_SID,
    position: 2,
    total: 3,
    holder: null,
    pendingRetry: false,
    reminderSent: false,
    retryCount: 0,
    lastAttemptAt: 0,
    stallNotified: false,
    stallEscalated: false,
    readyAt: T0 - 60_000,
    lastRefusalReason: '',
    ...over,
  };
}

/**
 * 启动应用（bg-merged-panel 同款 harness）。`mqStatus` = /api/merge-queue 响应：
 *   { status, body } —— status 非 2xx 时 body 忽略；省略 = 端点不存在（404）。
 */
async function bootApp(page, mqStatus) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
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
  // merge-queue 打桩（后注册优先于上方 **/* 通配）。
  await page.route('**/api/merge-queue*', (route) => {
    if (mqStatus == null) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    return route.fulfill({ status: mqStatus.status, contentType: 'application/json', body: JSON.stringify(mqStatus.body) });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 种一个子智能体行（status 可指定 —— 'Waiting' = 引擎候合并停靠态）。 */
async function seedAgent(page, status) {
  await page.evaluate(({ sid, st }) => {
    import('/js/state.js').then((m) => {
      m.default.sessionBgAgents[sid] = {
        'delegate-mqst-1': {
          name: '候合并样例子agent', kind: 'Delegate', sessionId: 'delegate-mqst-1',
          status: st, startedAt: Date.now() - 30_000,
        },
      };
      m.default.updateBgAgentIndicator();
    });
  }, { sid: ROOT_SID, st: status });
  await expect(page.locator('#bgagent-indicator')).toBeVisible();
}

/** 打开合并载体面板（#bgagent-indicator 键 = 同一 #bg-dropdown 载体，bg-merged-panel
 *  T2 同款路径——纯 agent 数据面下 #bg-indicator 任务键不可见），返回智能体节行集。 */
async function openAgentSection(page) {
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  return page.locator('#bg-dropdown > .bg-dropdown-list:last-child .bg-task-row');
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error') {
      // Chromium 资源类 console error 的 text 不含 URL（T3 要按端点豁免预期 404
      // 日志）——location().url 并入行内供过滤。
      const loc = m.location && m.location() ? String(m.location().url || '') : '';
      errors.push(`console: ${m.text()} @ ${loc}`);
    }
  });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 merge-queued pin: status word + queue position + retry + refusal summary', async ({ page }) => {
  const errors = watchErrors(page);
  await page.route('**/api/merge-queue*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(queuePayload([queueEntry({
      position: 2,
      total: 3,
      retryCount: 1,
      lastRefusalReason: 'merge: dirty worktree: reports/20261025_panel_merge_status_REPORT.md 未提交',
    })])),
  }));
  await seedAgent(page, 'Waiting');

  const rows = await openAgentSection(page);
  await expect(rows).toHaveCount(1);
  const row = rows.first();
  // 状态钉：状态位直显「候合并」。
  await expect(row.locator('.bg-task-state')).toHaveText('候合并');
  // 队列细节段：位次 + 重试 + 拒绝摘要（一句话，同段 ' · ' 连接）。
  const detail = row.locator('.bg-task-waiting');
  await expect(detail).toHaveText(
    '等待合并轮 · 前面还有 1 位 · 第 1 次重试 · 上次被拒：merge: dirty worktree: reports/20261025_panel_merge_status_REPORT.md 未提交',
  );
  // 全文 tooltip（摘要截断防线之上的完整原因）。
  await expect(detail).toHaveAttribute('title', /merge: dirty worktree: reports\/20261025_panel_merge_status_REPORT\.md 未提交$/);
  expect(errors).toEqual([]);
});

test('T2 head-of-queue: no ahead segment; zero retry / empty reason drop their segments', async ({ page }) => {
  const errors = watchErrors(page);
  await page.route('**/api/merge-queue*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(queuePayload([queueEntry({ position: 1, total: 2, retryCount: 0, lastRefusalReason: '' })])),
  }));
  await seedAgent(page, 'Waiting');

  const rows = await openAgentSection(page);
  await expect(rows).toHaveCount(1);
  // 队首（前面 0 位）+ 无重试 + 无拒绝原因 ⇒ 段全缺，静态「等待合并轮」。
  await expect(rows.first().locator('.bg-task-waiting')).toHaveText('等待合并轮');
  expect(errors).toEqual([]);
});

test('T3 API not live (404): static merge-queued status, no position digits, no error surfacing', async ({ page }) => {
  const errors = watchErrors(page); // mqStatus 省略 = 404（API 腿未生效的旧后端）
  await seedAgent(page, 'Waiting');

  const rows = await openAgentSection(page);
  await expect(rows).toHaveCount(1);
  await expect(rows.first().locator('.bg-task-state')).toHaveText('候合并');
  await expect(rows.first().locator('.bg-task-waiting')).toHaveText('等待合并轮');
  // 降级红线：不报错不空转 —— 除该端点预期 404 资源日志外零 console 错误、零 pageerror。
  const unexpected = errors.filter((e) => !/merge-queue/.test(e));
  expect(unexpected).toEqual([]);
  await page.waitForTimeout(650); // 退避窗内静默（无重试风暴的粗检）
  expect(errors.filter((e) => !/merge-queue/.test(e))).toEqual([]);
});

test('T4a shape drift ({} payload) degrades to the static form', async ({ page }) => {
  const errors = watchErrors(page);
  await page.route('**/api/merge-queue*', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await seedAgent(page, 'Waiting');
  const rows = await openAgentSection(page);
  await expect(rows).toHaveCount(1);
  await expect(rows.first().locator('.bg-task-state')).toHaveText('候合并');
  await expect(rows.first().locator('.bg-task-waiting')).toHaveText('等待合并轮');
  expect(errors).toEqual([]);
});

test('T4b empty queue degrades to the static form', async ({ page }) => {
  const errors = watchErrors(page);
  await page.route('**/api/merge-queue*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(queuePayload([])),
  }));
  await seedAgent(page, 'Waiting');
  const rows = await openAgentSection(page);
  await expect(rows).toHaveCount(1);
  await expect(rows.first().locator('.bg-task-waiting')).toHaveText('等待合并轮');
  expect(errors).toEqual([]);
});

test('T5 no idle polling: a panel without merge-queued rows never hits /api/merge-queue', async ({ page }) => {
  let mqHits = 0;
  await page.route('**/api/merge-queue*', (route) => {
    mqHits += 1;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(queuePayload([])) });
  });
  await seedAgent(page, 'running'); // 非候合并行
  await openAgentSection(page);
  await expect(page.locator('#bg-dropdown > .bg-dropdown-list:last-child .bg-task-row')).toHaveCount(1);
  await page.waitForTimeout(700); // 覆盖一次 uptick 节拍的富余量
  expect(mqHits).toBe(0);
});
