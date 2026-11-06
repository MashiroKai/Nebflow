// term-unify-badge-words.spec.mjs — 术语统一批（2026-11-06 作者令「统一 根据英文」）
// 真渲染验收：身份/类型徽标词一律按英文词面（kernel / dispatcher / node / subagent /
// delegate / AgentFlow），中文面不再出现译名徽标词；桌面三层 + 手机层同一读数。
//
// 断言面：
//   W1 任务列表（桌面）：mode 徽标 = 英文词面（zh-CN 与 en 两语言面同串）；
//   W2 后台智能体面板（桌面）：组头形态词 / 整体行身份词 / 成员行 kind 词全部英文；
//   W3 后台任务面板 mode chip：英文词面（zh-CN 面同样是 kernel / dispatcher）；
//   W4 手机层（375×812，body.mobile-layer）：后台页收编的列表面与桌面同串（单套实现）；
//   W5 词表一致性：任何徽标位读到的词 ∈ 单源词表 BADGE_WORDS 的值集（同概念不得两个词面）。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows / children / chains），零后端、零 sbt。
// 截图：NF_SHOT_DIR 指定（缺省 reports-evidence/term-unify-badge-words/<pid>）。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test \
//        tests/term-unify-badge-words.spec.mjs --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = process.env.NF_SHOT_DIR || join(dirname(fileURLToPath(import.meta.url)), '..', 'reports-evidence', 'term-unify-badge-words', String(process.pid));
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'unify-root-session';
const KERNEL_SID = 'delegate-unify-kernel-01';
const DISP_SID = 'delegate-unify-disp-02';
const LEGACY_SID = 'delegate-unify-legacy-03';
const DISPATCHER_SID = 'dispatcher-unify0bda27';
const NODE_SID = 'node-unifyaaaa11';
const SUBAGENT_SID = 'subagent-unify18f29';
const SUBTASK_SID = 'subtask-unify6d7e8';
const DAG_SID = 'dag-unifyb7c6d1';
const T0 = Date.now();

mkdirSync(SHOT_DIR, { recursive: true });

function mirrorRow(over = {}) {
  return {
    taskNo: '92', delegateId: KERNEL_SID, title: 'kernel 批次',
    name: 'kernel', executor: 'nebflow', status: 'running', external: false,
    project: '', createdAt: T0 - 12 * 60_000, orchestrationMode: 'subagent-star',
    mode: 'kernel', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    ...over,
  };
}

function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId, rootSessionId: sessionId,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 作用域桶成员行（rootSessionId = delegateSid 自根）。 */
function chainStart(rootSid, sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: rootSid, rootSessionId: rootSid,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    project: 'Nebflow',
    ...over,
  };
}

async function bootApp(page, locale = 'zh-CN') {
  await page.addInitScript((lc) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', lc);
    localStorage.setItem('stand_sw', '0');
  }, locale);
  let live = null;
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
    live = ws;
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
  return { send: (msg) => live?.send(JSON.stringify(msg)) };
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function setFixture(page, fx) {
  await page.evaluate(async ([f, sid]) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, [fx, ROOT_SID]);
  await page.waitForTimeout(150);
}

/** 任务列表夹具：kernel 行 / dispatcher 行 / 旧盘行（无 mode）。 */
async function seedTaskList(page) {
  await setFixture(page, {
    rows: [
      mirrorRow(),
      mirrorRow({ taskNo: '93', delegateId: DISP_SID, title: 'dispatcher 批次', mode: 'dispatcher', orchestrationMode: 'agentflow' }),
      mirrorRow({ taskNo: '94', delegateId: LEGACY_SID, title: '旧盘批次', mode: '', orchestrationMode: '' }),
    ],
  });
}

/** 后台智能体面板夹具：kernel 组头 / dispatcher(agentflow) 组头 + 五类成员行。 */
async function seedBgPanel(page) {
  await setFixture(page, {
    rows: [
      mirrorRow(),
      mirrorRow({ taskNo: '93', delegateId: DISP_SID, title: 'dispatcher 批次', mode: 'dispatcher', orchestrationMode: 'agentflow' }),
    ],
  });
  await inject(page, rootStart(KERNEL_SID, { name: 'KernelBatch', taskDescription: 'kernel 批次' }));
  // kernel 的作用域桶也放一行只读侦察腿（R-3a：kernel 只能派生只读腿）——合成延续
  // 组头的判据含「作用域桶非空」，没有这一行 kernel 头就整行缺席。
  await inject(page, chainStart(KERNEL_SID, 'subagent-unify-k9', { status: 'Processing', name: '侦察腿' }));
  await inject(page, rootStart(DISP_SID, { name: 'DispBatch', taskDescription: 'dispatcher 批次' }));
  await inject(page, chainStart(DISP_SID, DISPATCHER_SID, { status: 'Processing', name: 'dispatcher/Nebflow' }));
  await inject(page, chainStart(DISP_SID, NODE_SID, { status: 'Processing', name: 'stand' }));
  await inject(page, chainStart(DISP_SID, SUBAGENT_SID, { status: 'Processing', name: '侦察' }));
  await inject(page, chainStart(DISP_SID, SUBTASK_SID, { status: 'Processing', name: '收编' }));
  await inject(page, chainStart(DISP_SID, DAG_SID, { status: 'Processing', name: '步级' }));
  await page.waitForTimeout(150);
}

/** 开后台面板（后台合并批后：智能体键与后台任务键开同一个 #bg-dropdown 载体，
 *  子智能体列表收编在 .bg-section-agents 分节里；幽灵面 #bgagent-dropdown 恒隐藏）。 */
async function openBgAgents(page) {
  // 键的可见性由行数驱动（0 行时指示器隐藏）——开面走真实 click 事件的同一处理器
  // （dispatch 到指示器元素，不依赖它当前是否可见）。
  await page.evaluate(() => {
    const el = document.getElementById('bgagent-indicator');
    if (el) el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(250);
}

/** 任务列表读数：每行的 mode 徽标词面。 */
function readTaskList(page) {
  return page.evaluate(() => [...document.querySelectorAll('#task-list .task-chain-head')].map((el) => ({
    taskNo: (el.querySelector('.task-delegate-no') || {}).textContent || '',
    mode: [...el.querySelectorAll('.task-delegate-badge-mode')].map((x) => (x.textContent || '').trim()).join('|'),
  })));
}

/** 后台智能体面板读数：行层级 + kind 徽标 / 整体行身份词。 */
function readBgPanel(page) {
  return page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list')
      || document.querySelector('#bgagent-dropdown .bg-dropdown-list');
    if (!list) return [];
    return [...list.children].filter((el) => el.classList.contains('bg-task-row')).map((el) => ({
      level: el.getAttribute('data-bg-row-level') || '',
      sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
      kind: [...el.querySelectorAll('.bg-task-kind')].map((x) => (x.textContent || '').trim()).join('|'),
      entity: [...el.querySelectorAll('.bg-task-entity')].map((x) => (x.textContent || '').trim()).join('|'),
    }));
  });
}

/** 词表值集（单源）。改前（词表模块尚未建立）返回空集 —— 让红读数落在断言上，
 *  而不是模块解析错误上（截图与显示串采集在断言之前完成）。 */
function badgeFaceSet(page) {
  return page.evaluate(async () => {
    try {
      const m = await import('/js/termWords.js');
      return Object.values(m.BADGE_WORDS);
    } catch {
      return [];
    }
  });
}

function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

// ── W1：任务列表 mode 徽标 ────────────────────────────────────────────────
for (const locale of ['zh-CN', 'en']) {
  test('W1 任务列表 mode 徽标 = 英文词面（' + locale + '）', async ({ page }) => {
    const errors = trackErrors(page);
    await bootApp(page, locale);
    await seedTaskList(page);
    await page.waitForTimeout(200);
    const rows = await readTaskList(page);
    console.log('[W1 ' + locale + ' rows] ' + JSON.stringify(rows));
    await page.locator('#task-list').screenshot({ path: join(SHOT_DIR, 'w1-tasklist-' + locale + '.png') });
    const words = await badgeFaceSet(page);
    console.log('[W1 ' + locale + ' faces] ' + JSON.stringify(words));

    const byNo = Object.fromEntries(rows.map((r) => [r.taskNo, r.mode]));
    expect(byNo['#92']).toBe('kernel');
    expect(byNo['#93']).toBe('dispatcher');
    expect(byNo['#94']).toBe(''); // mode 缺席 ⇒ 零徽标（撤回不猜）
    // 同一概念不得两个词面：读到的每个词都在单源词表值集里。
    for (const w of rows.map((r) => r.mode).filter(Boolean)) expect(words).toContain(w);

    expect(errors).toEqual([]);
  });
}

// ── W2：后台智能体面板（组头形态词 / 整体身份词 / 成员 kind 词）────────────
for (const locale of ['zh-CN', 'en']) {
  test('W2 后台智能体面板徽标 = 英文词面（' + locale + '）', async ({ page }) => {
    const errors = trackErrors(page);
    await bootApp(page, locale);
    await seedBgPanel(page);
    await openBgAgents(page);
    const rows = await readBgPanel(page);
    console.log('[W2 ' + locale + ' rows] ' + JSON.stringify(rows));
    await page.locator('#bg-dropdown').screenshot({ path: join(SHOT_DIR, 'w2-bgpanel-' + locale + '.png') });
    const words = await badgeFaceSet(page);

    const all = rows.flatMap((r) => [r.kind, r.entity]).filter(Boolean);
    for (const w of all) expect(words).toContain(w);
    // 组头行：kernel 只有形态词；dispatcher+agentflow 形态词 + 身份词。
    const head = (sid) => rows.find((r) => r.level === 'head' && r.sid === sid);
    expect(head(KERNEL_SID).kind).toBe('kernel');
    expect(head(KERNEL_SID).entity).toBe('');
    expect(head(DISP_SID).kind).toBe('dispatcher');
    expect(head(DISP_SID).entity).toBe('AgentFlow');
    // 成员行 kind 词。
    const member = (sid) => rows.find((r) => r.level === 'child' && r.sid === sid);
    expect(member(DISPATCHER_SID).kind).toBe('dispatcher');
    expect(member(NODE_SID).kind).toBe('node');
    expect(member(SUBAGENT_SID).kind).toBe('subagent');
    expect(member(SUBTASK_SID).kind).toBe('delegate');
    expect(member(DAG_SID).kind).toBe('node');
    expect(member('subagent-unify-k9').kind).toBe('subagent');

    expect(errors).toEqual([]);
  });
}

// ── W3：后台任务面板 mode chip ────────────────────────────────────────────
for (const locale of ['zh-CN', 'en']) {
  test('W3 后台任务面板 mode chip = 英文词面（' + locale + '）', async ({ page }) => {
    const errors = trackErrors(page);
    const harness = await bootApp(page, locale);
    for (const t of [
      { taskId: 'j1', ownerMode: 'kernel', description: 'kernel delegate 的构建', kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
      { taskId: 'j2', ownerMode: 'dispatcher', description: 'dispatcher delegate 的测试', kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
      { taskId: 'j3', ownerMode: '', description: '旧形态后台任务', kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
    ]) {
      harness.send({
        type: 'backgroundTaskUpdate', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
        taskId: t.taskId, description: t.description, status: 'running',
        startedAt: Date.now(), kind: t.kind, origin: t.origin, originLabel: t.originLabel, ownerMode: t.ownerMode,
      });
    }
    await page.waitForTimeout(400);
    await page.locator('#bg-indicator').click();
    await expect(page.locator('#bg-dropdown')).toBeVisible();
    const chips = await page.evaluate(() => [...document.querySelectorAll('#bg-dropdown .bg-task-row')].map((el) => ({
      taskId: el.getAttribute('data-task-id') || '',
      mode: [...el.querySelectorAll('.bg-task-mode')].map((x) => (x.textContent || '').trim()).join('|'),
    })));
    console.log('[W3 ' + locale + ' chips] ' + JSON.stringify(chips));
    await page.locator('#bg-dropdown').screenshot({ path: join(SHOT_DIR, 'w3-bgtasks-' + locale + '.png') });
    const byId = Object.fromEntries(chips.map((c) => [c.taskId, c.mode]));
    expect(byId.j1).toBe('kernel');
    expect(byId.j2).toBe('dispatcher');
    expect(byId.j3).toBe('');
    expect(errors).toEqual([]);
  });
}

// ── W4：手机层（同一套实现，同一串）─────────────────────────────────────
test.describe('mobile layer', () => {
  test.use({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });

  test('W4 手机层后台页：桌面词面同一套（zh-CN）', async ({ page }) => {
    const errors = trackErrors(page);
    await bootApp(page, 'zh-CN');
    await seedBgPanel(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer') && !!window.__nfMobileNav, null, { timeout: 10000 });
    const nav = await page.evaluate(async () => {
      const nc = await import('/js/mobile/navController.js');
      nc.pushPage('background');
      return true;
    });
    expect(nav).toBe(true);
    await page.waitForTimeout(600);
    const rows = await readBgPanel(page);
    console.log('[W4 mobile rows] ' + JSON.stringify(rows));
    await page.screenshot({ path: join(SHOT_DIR, 'w4-mobile-zh-CN.png'), fullPage: false });
    const words = await badgeFaceSet(page);
    const all = rows.flatMap((r) => [r.kind, r.entity]).filter(Boolean);
    expect(all.length).toBeGreaterThan(0);
    for (const w of all) expect(words).toContain(w);
    const head = (sid) => rows.find((r) => r.level === 'head' && r.sid === sid);
    expect(head(KERNEL_SID).kind).toBe('kernel');
    expect(head(DISP_SID).entity).toBe('AgentFlow');
    expect(errors).toEqual([]);
  });
});
