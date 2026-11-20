// bg-panel-stale-rows.spec.mjs — 后台智能体面板「陈旧行按真实状态收敛」回归批
// （2026-11-18 · 依据作者报障：面板大量陈旧行标着在跑、时长 690h45m ≈ 29 天）。
//
// 现场实证（生产数据根，只读）：`~/.stand/subagent-tasks/d7c3cb54-….json` 里 1149 条
// 任务记录中 87 条 `status='pending-recovery'`（SubAgentStartupRecovery 标记的重启打断
// 行：执行已停、零通知、等人工恢复），`spawnedAt` 距今 694h。它们经
// `/api/subagent-tasks/<root>` 投影进面板，在 bgScopeStoreInfo 里被折成 'Processing'
// ⇒ 状态机末档 'active' ⇒ 一条 29 天龄的记录顶着「进行中」+「已运行 690h45m」。
//
// 断言面（任务书四问，机械读数；DOM 直读，零后端、零 sbt）：
//   ① 实例终结后不再活跃 …… completed / cancelled 的 store 行 → 终态档，无运行时钟。
//   ② 时长计算正确 ………… 在飞行 = now − spawnedAt（真时刻差，epoch ms）逐位正确；
//      数值样例见 scripts/check-bg-panel-active-liveness.mjs（含跨天 / 跨时区）。
//   ③ 重启后旧 sessionId 不残留为活跃 …… pending-recovery 行 →「重启中断 · 待恢复」，
//      不再是「进行中」，且不挂 690h 时钟（该 sessionId 引擎侧已无实例）。
//   ④ 真在跑的会话不受影响 …… status='running' 的行照旧「进行中」+ 走表时钟。
//   闸 …… 面板**活跃行**必须都能在引擎活跃快照（activeAgents）里找到对应实例。
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-panel-stale-rows.spec.mjs

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

const ROOT_SID = 'stale-root-session';
const STALE = 'delegate-stale-8bbd5826';   // 重启打断 · 待恢复（29 天龄）
const STALE2 = 'delegate-stale-2b17c0a4';  // 重启打断 · 待恢复（第二条）
const LIVE = 'delegate-live-0001';         // 真在跑
const DONE = 'delegate-done-0003';         // 实例已终结（completed）
const GONE = 'delegate-cancel-0004';       // 实例已终结（cancelled）

const HOUR = 3600_000;
const T0 = Date.now();
const STALE_SPAWN = T0 - 690 * HOUR - 45 * 60_000;  // 报障读数 = 690h45m
const LIVE_SPAWN = T0 - 42 * 60_000;                // 在飞 = 42m

/** `/api/subagent-tasks/<root>` 行投影夹具（ChainsReadFace.taskVisibilityJson 键域）。 */
function storeTask(taskId, over = {}) {
  return {
    taskId, parentSessionId: '', rootSessionId: ROOT_SID,
    agentName: 'nebflow', description: '陈旧行批', prompt: '', source: 'delegate',
    status: 'running', spawnedAt: T0 - HOUR, completedAt: null, retryCount: 0,
    runId: 'run-' + taskId, committedRevision: 1, executor: 'internal',
    executionStatus: '', activityAt: 0, activityKind: '', activityLabel: '',
    resultRef: '', terminalReason: '', incomplete: false,
    ...over,
  };
}

const TASKS = [
  storeTask(STALE, { diagnostic: 'Child report parked: parent identity unavailable (slot 1); retry on supervisor recovery.', status: 'pending-recovery', executionStatus: '', spawnedAt: STALE_SPAWN, completedAt: T0 - 600 * HOUR, committedRevision: 7, description: '【实施批 · 本地加速 M2 文件分块腿】' }),
  storeTask(STALE2, { status: 'pending-recovery', executionStatus: '', spawnedAt: STALE_SPAWN - 3 * HOUR, committedRevision: 5, description: '【修复批 · 搜索消息记录返回空】' }),
  storeTask(LIVE, { status: 'running', executionStatus: 'running', spawnedAt: LIVE_SPAWN, activityLabel: 'Bash', committedRevision: 9, description: '【在飞批】' }),
  storeTask(DONE, { status: 'completed', executionStatus: 'finished', spawnedAt: T0 - 700 * HOUR, committedRevision: 8, description: '【已完成批】' }),
  storeTask(GONE, { status: 'cancelled', executionStatus: 'cancelled', spawnedAt: T0 - 400 * HOUR, committedRevision: 6, description: '【已取消批】' }),
];

/** 引擎活跃快照（真源）：只有真在跑的那一条。 */
const LIVE_SNAPSHOT = [{
  sessionId: LIVE, agentId: LIVE, agentName: 'nebula', rootSessionId: ROOT_SID,
  kind: 'Delegate', status: 'Processing', startedAt: LIVE_SPAWN,
  project: '', parentSessionId: '', task: '【在飞批】',
}];

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/subagent-tasks/')) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ rootSessionId: ROOT_SID, tasks: TASKS }),
      });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 走真实 ws.js 分发路径注入一帧。 */
const inject = (page, obj) => page.evaluate(async (o) => {
  const s = (await import('/js/state.js')).default;
  s.ws.onmessage({ data: JSON.stringify(o) });
}, obj);

/** 强制拉一次 durable store 投影（真路径：/api/subagent-tasks/<root>）。 */
const pullStore = (page) => page.evaluate(async (root) => {
  const m = await import('/js/delegateChainProvider.js');
  await m.refreshScopedTasks(root);
}, ROOT_SID);

async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(200);
}

const LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';

/** 行集 dump（机制读数面）。 */
function dumpRows(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    return [...list.querySelectorAll('.bg-task-row')].map((el) => {
      const upEl = el.querySelector('.bg-task-uptime');
      const btn = el.querySelector('[data-bg-restore]');
      const stEl = el.querySelector('.bg-task-state');
      return {
        bgKey: el.getAttribute('data-bg-key') || '',
        sid: (el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || ''),
        state: stEl ? stEl.textContent : '',
        // 状态色调锚（bgStatusHtml 单点发射，locale 无关）：active / waiting / done / error …
        tone: stEl ? (stEl.getAttribute('data-tone') || '') : '',
        uptime: upEl ? upEl.textContent : '',
        hasStartedAt: upEl ? upEl.hasAttribute('data-started-at') : false,
        restore: btn ? btn.getAttribute('data-bg-restore') : '',
        done: el.classList.contains('done'),
      };
    });
  }, LIST);
}

const panelCounts = (page) => page.evaluate(() => ({
  badge: (document.querySelector('#bgagent-indicator .bgagent-count') || {}).textContent || '',
  header: (document.querySelector('#bgagent-dropdown .bg-dropdown-header') || {}).textContent || '',
}));

/** 采集页面错误（pageerror + console.error）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

async function bootWithRows(page) {
  await bootApp(page);
  await pullStore(page);
  await inject(page, { type: 'activeAgents', agents: LIVE_SNAPSHOT });
  await openPanel(page);
}

test('parked report diagnostic is visible on the stored task row', async ({ page }) => {
  await bootWithRows(page);
  await expect(page.locator('#bg-dropdown .bg-task-diag').filter({ hasText: 'Child report parked:' })).toBeVisible();
});

// ── ①③：重启打断的旧 sessionId 不再残留为「活跃」，如实标注 待恢复 ───────────
test('①③ 重启打断行如实标注待恢复、不再是活跃、不挂 690h 时钟', async ({ page }) => {
  const errors = trackErrors(page);
  await bootWithRows(page);
  const rows = await dumpRows(page);
  console.log('[stale dump] ' + JSON.stringify(rows));

  const stale = rows.filter((r) => r.bgKey === STALE || r.bgKey === STALE2);
  expect(stale).toHaveLength(2);
  for (const r of stale) {
    // ③ 诚实标注（与「在跑」分开）：既有词表 delegate.pendingRecovery，且带逐行恢复入口。
    expect(r.state).toBe('重启中断 · 待恢复');
    expect(r.tone).toBe('waiting');           // 不再是 active 色调（在跑才会是 active）
    expect(r.state).not.toBe('进行中');
    expect(r.restore).toBe(r.bgKey);
    // ① 不在跑的行不挂运行时钟（690h45m 的荒谬读数自此绝迹）。
    expect(r.uptime).toBe('');
  }
  expect(errors).toEqual([]);
});

// ── ①：实例终结（completed / cancelled）后的行不再显示为活跃 ────────────────
test('① 实例终结后不再活跃：completed/cancelled 行落终态档且无运行时钟', async ({ page }) => {
  const errors = trackErrors(page);
  await bootWithRows(page);
  const rows = await dumpRows(page);
  const done = rows.find((r) => r.bgKey === DONE);
  const gone = rows.find((r) => r.bgKey === GONE);
  expect(done).toBeTruthy();
  expect(gone).toBeTruthy();
  expect(done.tone).toBe('done');
  expect(gone.tone).toBe('done');
  expect(done.state).not.toBe('进行中');
  expect(done.uptime).toBe('');
  expect(gone.uptime).toBe('');
  expect(errors).toEqual([]);
});

// ── ②④：在飞行不受影响（时长 = 真时刻差）＋ 闸：活跃行 ⊆ 引擎活跃快照 ────────
test('②④ 在飞行照旧活跃且时长逐位正确；活跃行必须都能在引擎注册表里找到', async ({ page }) => {
  const errors = trackErrors(page);
  await bootWithRows(page);
  const rows = await dumpRows(page);
  const live = rows.find((r) => r.bgKey === LIVE);
  expect(live).toBeTruthy();
  expect(live.tone).toBe('active');
  expect(live.hasStartedAt).toBe(true);
  // 时长 = now − spawnedAt（epoch ms 真时刻差）；注入夹具 = 42 分钟前。
  expect(live.uptime).toMatch(/^42m \d+s$/);

  // 闸：面板活跃行（active 色调）必须都能在引擎活跃快照（LIVE_SNAPSHOT）里找到对应实例。
  const liveIds = new Set(LIVE_SNAPSHOT.map((a) => a.agentId));
  const activeRows = rows.filter((r) => r.tone === 'active').map((r) => r.sid || r.bgKey);
  console.log('[gate] active=' + JSON.stringify(activeRows) + ' liveRegistry=' + JSON.stringify([...liveIds]));
  expect(activeRows.length).toBeGreaterThan(0);
  for (const sid of activeRows) expect(liveIds.has(sid)).toBe(true);

  // 徽标活跃计数 = 1（引擎真实在飞数；87 条陈旧行不再计入）。
  const counts = await panelCounts(page);
  console.log('[counts] ' + JSON.stringify(counts));
  expect(String(counts.badge).trim()).toBe('1');
  expect(errors).toEqual([]);
});

// ── 闸反向门：不误伤真实在跑的会话（回归）───────────────────────────────────
test('④ 回归：真在跑的会话照旧活跃、照旧走表（不被本批误伤）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await pullStore(page);
  await inject(page, {
    type: 'activeAgents',
    agents: [
      ...LIVE_SNAPSHOT,
      { sessionId: 'node-live-0002', agentId: 'node-live-0002', agentName: '实现节点', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 6 * 60_000, project: 'Nebflow', parentSessionId: '', task: '在飞节点' },
    ],
  });
  await openPanel(page);
  const rows = await dumpRows(page);
  const active = rows.filter((r) => r.tone === 'active');
  console.log('[regression] active=' + JSON.stringify(active.map((r) => r.sid)));
  expect(active.map((r) => r.sid).sort()).toEqual([LIVE, 'node-live-0002'].sort());
  expect(errors).toEqual([]);
});
