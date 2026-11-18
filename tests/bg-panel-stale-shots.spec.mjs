// bg-panel-stale-shots.mjs — 陈旧面板行批的证据录制件（改前 / 改后 × 桌面 / 手机）。
//
// 与 tests/bg-panel-stale-rows.spec.mjs 同一夹具与同一 data 面，只把「改前」作为
// **真实基线字节**喂回页面：`git show f5300f375:src/main/resources/web/js/main/bgAgents.js`
// （feat/base tip = 本批基线），其余文件一律用工作区的当前字节——所以两帧之间的差异
// **只有本批这一处修复**，不是再造一个假页面。
//
// 产物（全部落到 worktree 之外，见 EVIDENCE 常量）：
//   desktop-before.png / desktop-after.png   (1280×800 整视口)
//   mobile-before.png  / mobile-after.png    (375×812  整视口)
//   rows.json                                (两模式 × 两视口的行状态读数)
//
// Run: ./node_modules/.bin/playwright test tests/bg-panel-stale-shots.mjs --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
// 证据落点：默认 tmp（普通套件跑不往仓外写）；录证据时用 BG_PANEL_EVIDENCE 指名。
const EVIDENCE = process.env.BG_PANEL_EVIDENCE || join(tmpdir(), 'bg-panel-stale-shots');
const BASE = 'f5300f375';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'stale-root-session';
const STALE = 'delegate-stale-8bbd5826';
const STALE2 = 'delegate-stale-2b17c0a4';
const LIVE = 'delegate-live-0001';
const DONE = 'delegate-done-0003';
const GONE = 'delegate-cancel-0004';

const HOUR = 3600_000;
const T0 = Date.now();
const STALE_SPAWN = T0 - 690 * HOUR - 45 * 60_000; // 报障读数 = 690h45m
const LIVE_SPAWN = T0 - 42 * 60_000;               // 在飞 = 42m

/** 基线（改前）字节：直接从 feat/base tip 取，不靠任何再造。 */
const baseBgAgents = execFileSync('git', ['-C', REPO, 'show', `${BASE}:src/main/resources/web/js/main/bgAgents.js`], { encoding: 'utf8' });

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
  storeTask(STALE, { status: 'pending-recovery', executionStatus: '', spawnedAt: STALE_SPAWN, completedAt: T0 - 600 * HOUR, committedRevision: 7, description: '【实施批 · 本地加速 M2 文件分块腿】' }),
  storeTask(STALE2, { status: 'pending-recovery', executionStatus: '', spawnedAt: STALE_SPAWN - 3 * HOUR, committedRevision: 5, description: '【修复批 · 搜索消息记录返回空】' }),
  storeTask(LIVE, { status: 'running', executionStatus: 'running', spawnedAt: LIVE_SPAWN, activityLabel: 'Bash', committedRevision: 9, description: '【在飞批】' }),
  storeTask(DONE, { status: 'completed', executionStatus: 'finished', spawnedAt: T0 - 700 * HOUR, committedRevision: 8, description: '【已完成批】' }),
  storeTask(GONE, { status: 'cancelled', executionStatus: 'cancelled', spawnedAt: T0 - 400 * HOUR, committedRevision: 6, description: '【已取消批】' }),
];

const LIVE_SNAPSHOT = [{
  sessionId: LIVE, agentId: LIVE, agentName: 'nebula', rootSessionId: ROOT_SID,
  kind: 'Delegate', status: 'Processing', startedAt: LIVE_SPAWN,
  project: '', parentSessionId: '', task: '【在飞批】',
}];

async function boot(page, mode, isMobile = false) {
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
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ rootSessionId: ROOT_SID, tasks: TASKS }) });
    }
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p === '/js/main/bgAgents.js') {
      const body = mode === 'before' ? baseBgAgents : readFileSync(join(WEB, 'js', 'main', 'bgAgents.js'));
      return route.fulfill({ status: 200, contentType: 'text/javascript', body });
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
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  await page.evaluate(async (root) => {
    const m = await import('/js/delegateChainProvider.js');
    await m.refreshScopedTasks(root);
  }, ROOT_SID);
  await page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, { type: 'activeAgents', agents: LIVE_SNAPSHOT });
  if (isMobile) {
    // 手机层：后台智能体面 = 手机页 'background'（overlayFaceByPage 把它收编进页体，
    // 见 mobile/navController.js:516），页面进入后补跑子智能体列表渲染。
    await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.pushPage('background');
    });
    await page.waitForTimeout(300);
    await page.evaluate(async () => {
      const m = await import('/js/main/bgAgents.js');
      m.fetchActiveAgentsOnOpen();
      m.renderBgAgentDropdown();
    });
  } else {
    await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
    await page.click('#bgagent-indicator');
  }
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(500);
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    return [...list.querySelectorAll('.bg-task-row')].map((el) => {
      const stEl = el.querySelector('.bg-task-state');
      const upEl = el.querySelector('.bg-task-uptime');
      return {
        key: el.getAttribute('data-bg-key') || '',
        label: stEl ? stEl.textContent : '',
        tone: stEl ? stEl.getAttribute('data-tone') : '',
        uptime: upEl ? upEl.textContent : '',
      };
    });
  }, '#bg-dropdown .bg-section-agents .bg-dropdown-list');
}

mkdirSync(EVIDENCE, { recursive: true });
const report = {};

for (const [vpName, viewport] of [['desktop', { width: 1280, height: 800 }], ['mobile', { width: 375, height: 812 }]]) {
  for (const mode of ['before', 'after']) {
    test(`shots ${vpName} ${mode}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      const rows = await boot(page, mode, vpName === 'mobile');
      const out = join(EVIDENCE, `${vpName}-${mode}.png`);
      await page.screenshot({ path: out }); // 整视口 ⇒ PNG 真实像素 = 视口像素
      report[`${vpName}-${mode}`] = { viewport, file: out, rows };
      writeFileSync(join(EVIDENCE, 'rows.json'), JSON.stringify(report, null, 1));
      console.log(`[shot] ${vpName}-${mode} ${JSON.stringify(rows)}`);
      expect(rows.length).toBeGreaterThan(0);
    });
  }
}
