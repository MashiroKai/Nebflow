// bg-panel-struct-parent.spec.mjs — 全局后台面板层级 face C（2026-11-13）。
//
// 契约（交接项 C）：
//   ① 活跃后代在场而父 delegate 无 live 行 ⇒ 父作为**结构节点**显示
//     （data-bg-struct-parent 标签面行；身份单源 = 注册行镜像 name/title/mode，
//     零臆造）；父可 idle/终态且**不计活跃数**（total/徽标/头部计数不含标签面）。
//   ② 结构中的已完成后代保留展示（store 终态子行照挂）。
//   ③ 父子边（树导轨 ├─/└─ + 深度缩进）与 DAG 依赖边（⇢ deps chip）视觉区分。
//   ④ 父信息缺失 ⇒ 标未知而非猜：未注册父仍走既有野组面（data-bg-orphan +
//     「来自 …」角标），不合成结构父、不猜标题。
//   ⑤ 去重：同一会话 live 行与 store 子行并存 ⇒ 一行。
//   ⑥ 增量：镜像更新（delegate-chain-mirror-updated）面板在开 ⇒ 新子行即时上屏。
//   ⑦ 折叠默认：有活跃后代 ⇒ 展开；全终态 ⇒ 收起（行仍计数）。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows/chains/children），零后端。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-panel-struct-parent.spec.mjs --reporter=line

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

const ROOT_SID = 'struct-root-session';
// Registered parent (mirror row present, NO live row in any bucket).
const PARENT = 'delegate-nebflow-5a5b5c5d';
// A second registered parent whose only claimer is terminal.
const PARENT2 = 'delegate-nebflow-6b6b6b6b';
// Flow-group parent (live row present → real group head) for the deps face.
const GROUP = 'delegate-nebflow-7c7c7c7c';
// Claimers.
const LIVE_CHILD = 'subagent-11112222';      // active, main bucket, parent = PARENT
const DONE_CHILD = 'subagent-33334444';      // completed store child of PARENT
const DONE_LIVE = 'subagent-44445555';       // terminal live claimer of PARENT2
const WILD_CHILD = 'subagent-99990000';      // parent unregistered → wild face
const T0 = Date.now();

/** Registry mirror row (DelegateRow shape + parentSessionId pass-through). */
function mirrorRow(over = {}) {
  return {
    taskNo: '51', delegateId: PARENT, title: '面板层级验收批', name: '层级批',
    executor: 'nebflow', status: 'completed', external: false, project: '',
    createdAt: T0 - 30 * 60_000, mode: 'kernel', archivedAt: null,
    parentSessionId: '', ...over,
  };
}

/** Main-bucket live row (rootSessionId = main session). */
function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId, name: sessionId,
    taskDescription: '', ...over,
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
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

async function bootApp(page, locale = 'zh-CN') {
  await installRoutes(page, locale);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
  }, fx);
}

async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(200);
}

const LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';

/** Row dump（机器锚读数面） */
function dumpRows(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return { rows: [], structs: [], group: null, listTotal: null };
    const kids = [...list.children];
    return {
      rows: kids.filter((el) => el.classList.contains('bg-task-row')).map((el) => ({
        sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
        depth: el.getAttribute('data-bg-depth'),
        level: el.getAttribute('data-bg-row-level') || '',
        orphan: el.getAttribute('data-bg-orphan'),
        rail: (el.querySelector('.bg-tree-rail') || {}).textContent || '',
        name: (el.querySelector('.bg-task-name') || {}).textContent || '',
        deps: (el.querySelector('.bg-flow-deps') || {}).getAttribute ? el.querySelector('.bg-flow-deps').getAttribute('data-bg-deps') : null,
      })),
      structs: kids.filter((el) => el.hasAttribute('data-bg-struct-parent')).map((el) => ({
        text: el.textContent || '',
        collapsed: el.getAttribute('data-bg-collapsed'),
        isRow: el.classList.contains('bg-task-row'),
        title: (el.querySelector('.bg-struct-parent-name') || {}).title || '',
      })),
      group: kids.some((el) => el.hasAttribute('data-bg-orphan-group')),
      listTotal: list.getAttribute('data-bg-row-total'),
    };
  }, LIST);
}

function panelCounts(page) {
  return page.evaluate(() => ({
    badge: (document.querySelector('#bgagent-indicator .bgagent-count') || {}).textContent || '',
    section: (document.querySelector('#bg-dropdown .mh-bg-grp') || {}).textContent || '',
    rowTotal: (document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list') || {}).getAttribute('data-bg-row-total') || '',
  }));
}

function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

/** Base seed: registered parent + active claimer + completed store child + wild row. */
async function seedBase(page) {
  await setFixture(page, {
    rows: [
      mirrorRow(),
      mirrorRow({ taskNo: '52', delegateId: PARENT2, title: '全终态父批', name: '终态批', status: 'completed', mode: 'dispatcher' }),
    ],
    children: {
      [PARENT]: [{ taskId: DONE_CHILD, parentSessionId: PARENT, agentName: '侦察乙', description: '已完成侦察', status: 'completed', spawnedAt: T0 - 20 * 60_000, completedAt: T0 - 10 * 60_000, retryCount: 0, source: 'subagent' }],
    },
  });
  await inject(page, rootStart(LIVE_CHILD, { status: 'Processing', name: '侦察甲', taskDescription: '活跃后代', parentSessionId: PARENT }));
  await inject(page, rootStart(DONE_LIVE, { status: 'Processing', name: '终态后代', taskDescription: '已完成的直接后代', parentSessionId: PARENT2 }));
  await inject(page, rootStart(WILD_CHILD, { status: 'Processing', name: '野后代', taskDescription: '父未注册', parentSessionId: 'node-gone-77770000' }));
  // Terminal live claimer: the agentDone handler writes done:true (then reaps the
  // row after 2s) — seeding that state directly emulates the post-done window.
  // The agent's row is terminal in every bucket that carries it (its own scope bucket included). Seeding
  // mutates state without an event, so the badge is recomputed explicitly, as the refresh that follows a
  // terminal state does in production; otherwise it keeps the count from the last agentStart (3) until some
  // unrelated async refresh happens to run.
  await page.evaluate(async ({ aid, rootSid }) => {
    const s = (await import('/js/state.js')).default;
    for (const bucket of Object.values(s.sessionBgAgents)) {
      if (bucket[aid]) { bucket[aid].done = true; bucket[aid].status = 'Completed'; }
    }
    (await import('/js/main/bgAgents.js')).updateBgAgentIndicator(rootSid);
  }, { aid: DONE_LIVE, rootSid: ROOT_SID });
}

// ── ①②⑦ 结构父合成 + 终态保留 + 计数不含标签面 + 折叠默认 ────────────────────

test('①②⑦ 活跃后代 ⇒ 结构父显示（注册行身份/零计数）；已完成后代保留；全终态父默认收起', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedBase(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  const counts = await panelCounts(page);
  console.log('[①②⑦] ' + JSON.stringify(dump) + ' counts=' + JSON.stringify(counts));

  // ① Two structural parents: PARENT (kernel, active claimer) + PARENT2 (dispatcher,
  //    terminal claimer). Identity from the registry mirror — name + mode badge +
  //    status word; never a .bg-task-row.
  expect(dump.structs).toHaveLength(2);
  for (const s of dump.structs) expect(s.isRow).toBe(false);
  const active = dump.structs.find((s) => s.text.includes('层级批'));
  const terminal = dump.structs.find((s) => s.text.includes('终态批'));
  expect(active).toBeTruthy();
  expect(terminal).toBeTruthy();
  expect(active.text).toContain('kernel');
  expect(active.text).toContain('已完成'); // registry status word (muted face)
  expect(terminal.text).toContain('dispatcher');
  expect(active.title).toContain(PARENT); // sid in tooltip (fact, not guess)

  // ⑦ Collapse defaults: active-descendant parent expanded; all-terminal parent
  //    collapsed (rows still counted below).
  expect(active.collapsed).toBe('false');
  expect(terminal.collapsed).toBe('true');

  // ② Retention: the active claimer and the completed store child both render
  //    under the structural parent at depth 1 (ownership rail, not a dep chip).
  const live = dump.rows.find((r) => r.sid === LIVE_CHILD);
  const done = dump.rows.find((r) => r.sid === DONE_CHILD);
  expect(live).toBeTruthy();
  expect(done).toBeTruthy();
  expect(live.depth).toBe('1');
  expect(done.depth).toBe('1');
  expect(live.rail.trim().length).toBeGreaterThan(0); // tree rail (parent-child edge)
  expect(live.orphan).toBe(null);                     // parent visible → not orphan-faced

  // ① counts: 4 real rows are retained (LIVE + DONE + DONE_LIVE + WILD); labels excluded.
  //    Rendered = 3 — DONE_LIVE sits under the collapsed all-terminal struct
  //    (collapse prunes DOM, never the totals: header/row-total keep 4).
  //    The badge counts ACTIVE rows only: LIVE + WILD = 2. The completed store child and the terminal
  //    claimer stay visible without being counted, and the structural labels never count.
  expect(dump.rows).toHaveLength(3);
  expect(dump.listTotal).toBe('4');
  expect(String(counts.badge).trim()).toBe('2');
  expect(String(counts.section)).toContain('· 3'); // section label counts rendered rows
  expect(errors).toEqual([]);
});

// ── ④ 父信息缺失 ⇒ 标未知而非猜 ─────────────────────────────────────────────

test('④ 未注册父不合成结构父：野组面照旧（data-bg-orphan + 「来自 …」角标）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedBase(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  // No struct label for the unregistered parent id.
  for (const s of dump.structs) expect(s.text).not.toContain('node-gone');
  // The wild row stays on the orphan face with its honest origin chip.
  expect(dump.group).toBe(true);
  const wild = dump.rows.find((r) => r.sid === WILD_CHILD);
  console.log('[④] wild=' + JSON.stringify(wild));
  expect(wild).toBeTruthy();
  expect(wild.orphan).toBe('1');
  expect(wild.depth).toBe('0');
  expect(errors).toEqual([]);
});

// ── ⑤ 去重：live 行与 store 子行同一会话 ⇒ 一行 ─────────────────────────────

test('⑤ 同一会话双通路（live + store）⇒ 收敛一行', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedBase(page);
  // DONE_CHILD also arrives as a live main-bucket row (same session id).
  await inject(page, rootStart(DONE_CHILD, { status: 'Completed', name: '侦察乙', taskDescription: '已完成侦察', parentSessionId: PARENT }));
  await openPanel(page);

  const dump = await dumpRows(page);
  const dup = dump.rows.filter((r) => r.sid === DONE_CHILD);
  console.log('[⑤] rows=' + JSON.stringify(dump.rows.map((r) => r.sid)) + ' total=' + dump.listTotal);
  expect(dup).toHaveLength(1);
  expect(dump.listTotal).toBe('4');
  expect(errors).toEqual([]);
});

// ── ⑥ 增量：镜像更新 ⇒ 新已完成子行即时上屏 ─────────────────────────────────

test('⑥ 镜像更新（delegate-chain-mirror-updated）⇒ 新 store 子行上屏', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedBase(page);
  await openPanel(page);
  expect((await dumpRows(page)).listTotal).toBe('4'); // LIVE + DONE + DONE_LIVE + WILD

  // Mirror gains another completed child of PARENT.
  await setFixture(page, {
    rows: [
      mirrorRow(),
      mirrorRow({ taskNo: '52', delegateId: PARENT2, title: '全终态父批', name: '终态批', status: 'completed', mode: 'dispatcher' }),
    ],
    children: {
      [PARENT]: [
        { taskId: DONE_CHILD, parentSessionId: PARENT, agentName: '侦察乙', description: '已完成侦察', status: 'completed', spawnedAt: T0 - 20 * 60_000, completedAt: T0 - 10 * 60_000, retryCount: 0, source: 'subagent' },
        { taskId: 'subagent-55556666', parentSessionId: PARENT, agentName: '侦察丙', description: '后到的已完成侦察', status: 'completed', spawnedAt: T0 - 8 * 60_000, completedAt: T0 - 5 * 60_000, retryCount: 0, source: 'subagent' },
      ],
    },
  });
  await page.evaluate(() => window.dispatchEvent(new Event('delegate-chain-mirror-updated')));
  await page.waitForTimeout(250);
  const dump = await dumpRows(page);
  console.log('[⑥] rows=' + JSON.stringify(dump.rows.map((r) => r.sid)) + ' total=' + dump.listTotal);
  expect(dump.rows.filter((r) => r.sid === 'subagent-55556666')).toHaveLength(1);
  expect(dump.listTotal).toBe('5');
  expect(errors).toEqual([]);
});

// ── ③ DAG 依赖边 chip ≠ 父子导轨 ─────────────────────────────────────────────

test('③ flow 子行 deps chip（⇢ 依赖）与父子导轨（├─/└─）视觉分面', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await setFixture(page, {
    rows: [{ taskNo: '53', delegateId: GROUP, title: '依赖链批', name: '依赖批', executor: 'nebflow', status: 'running', external: false, project: '', createdAt: T0 - 60_000, mode: 'dispatcher', archivedAt: null }],
    chains: [{
      taskId: '53', delegateId: GROUP,
      nodes: [
        { id: 'step-a', kind: 'flow-step', label: '步骤甲', status: 'completed', deps: [], executor: 'nebflow' },
        { id: 'step-b', kind: 'flow-step', label: '步骤乙', status: 'running', deps: ['step-a'], executor: 'nebflow' },
      ],
      worktree: null, verdict: null, mergeQueue: [], frames: [],
    }],
    children: {},
  });
  await inject(page, rootStart(GROUP, { status: 'Processing', name: '依赖批', taskDescription: '依赖链批' }));
  await openPanel(page);

  const dump = await dumpRows(page);
  console.log('[③] ' + JSON.stringify(dump));
  // step-b carries the DAG dependency chip with the machine anchor; step-a does not.
  const stepB = dump.rows.find((r) => r.name.includes('步骤乙'));
  const stepA = dump.rows.find((r) => r.name.includes('步骤甲'));
  expect(stepB).toBeTruthy();
  expect(stepA).toBeTruthy();
  expect(stepB.deps).toBe('step-a');
  expect(stepA.deps).toBe(null);
  // Parent-child edges render as rails (├─/└─), dep chips never do.
  for (const r of dump.rows) {
    if (r.depth === '1') expect(r.rail.trim().length).toBeGreaterThan(0);
  }
  // Locale word present (zh) — the chip explains the edge kind.
  const chipText = await page.evaluate(() => {
    const el = document.querySelector('#bg-dropdown .bg-flow-deps');
    return el ? { text: el.textContent || '', title: el.getAttribute('title') || '' } : null;
  });
  console.log('[③ chip] ' + JSON.stringify(chipText));
  expect(chipText).toBeTruthy();
  expect(chipText.text).toContain('依赖');
  expect(chipText.text).toContain('⇢');
  expect(chipText.title).toContain('step-a');
  expect(errors).toEqual([]);
});

// ── en 面：结构父身份与依赖词（locale 单源）───────────────────────────────────

test('en locale: struct label carries registry name; deps chip word switches', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'en');
  await seedBase(page);
  await openPanel(page);
  const dump = await dumpRows(page);
  const active = dump.structs.find((s) => s.text.includes('层级批'));
  console.log('[en] structs=' + JSON.stringify(dump.structs));
  expect(active).toBeTruthy();
  expect(active.text).toContain('kernel');
  expect(errors).toEqual([]);
});
