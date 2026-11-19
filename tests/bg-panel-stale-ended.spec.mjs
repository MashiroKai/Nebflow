// bg-panel-stale-ended.spec.mjs — 陈旧后台代理面板批（2026-11-08）三态回归。
//
// 作者报障：后台代理面板上挂着「运行中」的行累计到 690h（= 28.75 天）——底层任务
// 记录早就终态了，注册表行滞留，前端行仍在 `!terminal` 分支按 `now - startedAt`
// 无上界走表。本批把「任务记录已终态」这一硬事实随快照出帧（引擎 `activeAgents`
// 行 `ended` / `endedAt` / `durationMs`），面板据此显式「已结束 + 真实时长」；
// 荒谬时长（> 7 天 / 非法 / 起点未知）一律拒收，不渲染。
//
// 三态（同一份判据 `bgUptimeDisplay` 单点）：
//   ① 在飞（快照未标 ended）⇒ 时长走表 + 带 data-started-at（ticker 可刷新）；
//   ② 已结束（快照 ended=true + durationMs）⇒ 冻结真实时长 + 「已结束」标 +
//      **不带 data-started-at**（ticker 永不改写它）；
//   ③ 荒谬/陈旧（未标 ended 而时长越 7 天上界）⇒ 行内零时长渲染（拒收）。
//
// 数据面 = WS 帧直种（零后端、零 sbt、零 6138）。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-panel-stale-ended.spec.mjs --reporter=line

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
const T0 = Date.now();
const HOUR = 60 * 60 * 1000;

/** activeAgents 快照行（刷新恢复路径 = ended/durationMs 的出帧面）。 */
function snapAgent(sessionId, over = {}) {
  return {
    sessionId,
    agentId: sessionId,
    rootSessionId: ROOT_SID,
    parentSessionId: ROOT_SID,
    agentName: sessionId,
    task: '陈旧面板三态',
    kind: 'Delegate',
    status: 'Idle',
    startedAt: T0 - 60 * 1000,
    retryCount: 0,
    lastActivityMs: T0,
    bgWaiting: [],
    heldReceipts: 0,
    unreadReceipts: 0,
    outstandingSubagents: 0,
    project: '', taskId: '', taskTitle: '',
    ...over,
  };
}

async function installRoutes(page, locale) {
  await page.addInitScript((lc) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', lc);
    localStorage.setItem('stand_sw', '0');
  }, locale);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
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
}

/** 起应用 + 种一张 activeAgents 快照（agent 行）并开面板。 */
async function bootAndSeed(page, agents, locale = 'zh-CN') {
  await installRoutes(page, locale);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  await page.evaluate(async (payload) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(payload) });
  }, { type: 'activeAgents', agents });
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(200);
}

/** 行读数（机器锚：类名/属性/textContent）。 */
function rowDump(page, marker) {
  return page.evaluate((wanted) => {
    const sel = '#bg-dropdown .bg-section-agents .bg-dropdown-list';
    const list = document.querySelector(sel);
    if (!list) return { found: false, total: 0, missingList: true };
    const rows = [...list.querySelectorAll('.bg-task-row')];
    const texts = rows.map((el) => (el.textContent || '').slice(0, 160));
    const row = rows.find((el) => (el.textContent || '').includes(wanted));
    if (!row) return { found: false, total: rows.length, texts };
    const up = row.querySelector('.bg-task-uptime');
    const ended = row.querySelector('.bg-task-ended');
    return {
      found: true,
      total: rows.length,
      uptimeText: up ? up.textContent : null,
      uptimeTicking: !!(up && up.hasAttribute('data-started-at')),
      uptimeEnded: !!(up && up.hasAttribute('data-ended')),
      uptimeFrozenClass: !!(up && up.classList.contains('bg-task-uptime-frozen')),
      endedChip: ended ? ended.textContent : null,
      rowText: row.textContent || '',
    };
  }, marker);
}

test('stale-row ①: a live snapshot row still ticks (data-started-at present)', async ({ page }) => {
  const LIVE = 'delegate-stale-live01';
  const MK = 'stale-marker-1';
  await bootAndSeed(page, [snapAgent(LIVE, { status: 'Idle', lastActivityMs: Date.now(), task: MK })]);
  const d = await rowDump(page, MK);
  expect(d && d.found, JSON.stringify(d)).toBe(true);
  expect(d.uptimeTicking, JSON.stringify(d)).toBe(true);
  expect(d.uptimeText).toMatch(/^1m /);
  expect(d.endedChip).toBeNull();
});

test('stale-row ②: an ended snapshot row freezes at the real duration and says so', async ({ page }) => {
  const ENDED = 'delegate-stale-ended2';
  const MK = 'stale-marker-2';
  const started = Date.now() - 25 * 60 * 1000;
  await bootAndSeed(page, [snapAgent(ENDED, {
    status: 'Processing',
    startedAt: started,
    taskStatus: 'completed',
    ended: true,
    endedAt: started + 25 * 60 * 1000,
    durationMs: 25 * 60 * 1000,
    lastActivityMs: started + 25 * 60 * 1000,
    task: MK,
  })]);
  const d = await rowDump(page, MK);
  expect(d && d.found, JSON.stringify(d)).toBe(true);
  // 冻结真实时长（不再走表）。
  expect(d.uptimeText).toBe('25m 0s');
  expect(d.uptimeTicking).toBe(false);
  expect(d.uptimeEnded).toBe(true);
  expect(d.uptimeFrozenClass).toBe(true);
  // 显式「已结束」标。
  expect(d.endedChip).toBe('已结束');
});

test('stale-row ③: an implausible (690h) clock is rejected, not rendered', async ({ page }) => {
  const BOGUS = 'delegate-stale-bogus3';
  const MK = 'stale-marker-3';
  await bootAndSeed(page, [snapAgent(BOGUS, {
    status: 'Processing',
    startedAt: Date.now() - 690 * HOUR,
    lastActivityMs: Date.now() - 690 * HOUR,
    task: MK,
  })]);
  const d = await rowDump(page, MK);
  expect(d && d.found, JSON.stringify(d)).toBe(true);
  expect(d.uptimeText).toBeNull();
  expect(d.rowText.includes('690h')).toBe(false);
});

test('stale-row ②b: an ended row with an implausible duration shows the marker but no number', async ({ page }) => {
  const ENDED_BAD = 'delegate-stale-endedb4';
  const MK = 'stale-marker-4';
  await bootAndSeed(page, [snapAgent(ENDED_BAD, {
    status: 'Processing',
    startedAt: Date.now() - 690 * HOUR,
    taskStatus: 'failed',
    ended: true,
    endedAt: Date.now() - 690 * HOUR,
    durationMs: 690 * HOUR,
    task: MK,
  })]);
  const d = await rowDump(page, MK);
  expect(d && d.found, JSON.stringify(d)).toBe(true);
  expect(d.endedChip).toBe('已结束');
  expect(d.uptimeText).toBeNull();
  expect(d.rowText.includes('690h')).toBe(false);
});
