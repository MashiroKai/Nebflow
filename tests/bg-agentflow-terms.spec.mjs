// bg-agentflow-terms.spec.mjs — 后台智能体面板「agentflow vs 节点」术语分层批
// （2026-11-05 · 术语审计依据 reports-evidence/agentflow-panel-terms-20261105_091134/
// terminology-audit.md §②混用举证 / §⑤统一口径提案）。
//
// 作者语义（任务书逐字）：agentflow = 整个组织行为的代称（一次编排 = 一个 agentflow）；
// 节点 = agentflow 里的成员，成员分层。既有 kernel / dispatcher 两个词是作者术语，
// **逐字保留、不许改名**。本 spec 只收口「节点 vs agentflow」这一层：
//   整体行（代表一次编排：delegate 组头 / 合成延续组头）= agentflow 词（locale 键
//     subagents.kindAgentflow，zh/en 同词面）；
//   成员行（该编排里的节点）= 成员自身 kind（分发器 / 节点 / Subagent / Delegate …）；
//     机械禁令：成员行徽标不出现 agentflow / AgentFlow / Flow 字样；
//   反向门：整体行徽标不出现 节点 / Node 字样；
//   归属靠结构：成员行 data-bg-agentflow="<所属整体行定位键>"（与 data-bg-key /
//     bgRowLocatorKey 同口径），整体行 data-bg-entity="agentflow"——不写解释句
//     （文案面 node scripts/check-self-explaining.mjs 必须 EXIT=0）。
//
// 断言面：H1 整体行词（zh/en 各一次）/ H2 成员行归属锚 + 成员词禁令 /
//   H3 反向门 / H4 kernel·dispatcher 逐字保留 / H5 词源单点（静态读源码）。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows/children/chains），零后端、零 sbt。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-agentflow-terms.spec.mjs --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'terms-root-session';
const DELEGATE_SID = 'delegate-terms-a1b2c3d4';
const NODE_SID = 'node-aaaa1111';
const DISPATCHER_SID = 'dispatcher-000bda27';
const SUBAGENT_SID = 'subagent-4bf18f29';
const SUBTASK_SID = 'subtask-5c6d7e8f';
const DAG_SID = 'dag-9a8b7c6d';
const T0 = Date.now();

/** 主桶行（delegate live 行 / 快照 leftover 行同构）。 */
function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 作用域桶成员行（真实 routeSubagentWsSend 形：rootSessionId = delegateSid 自根）。 */
function chainStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: DELEGATE_SID, rootSessionId: DELEGATE_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    project: 'Nebflow',
    ...over,
  };
}

/** 注册行镜像夹具（DelegateRow 形）。 */
function mirrorRow(over = {}) {
  return {
    taskNo: '91', delegateId: DELEGATE_SID, title: '术语分层批次——agentflow 与节点',
    name: '术语批', executor: 'nebflow', status: 'running', external: false,
    project: '', createdAt: T0 - 66 * 60_000, orchestrationMode: 'agentflow',
    mode: 'dispatcher', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    ...over,
  };
}

async function bootApp(page, locale = 'zh-CN') {
  await page.addInitScript((lc) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', lc);
    localStorage.setItem('stand_sw', '0');
  }, locale);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
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
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 装 stub 夹具（rows / children / chains 读口）。 */
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

/** 行集 dump：层级 / 定位键 / agentflow 归属锚 / 整体行实体锚 / 徽标词面。 */
function dumpRows(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    return [...list.children].filter((el) => el.classList.contains('bg-task-row')).map((el) => ({
      level: el.getAttribute('data-bg-row-level') || '',
      bgKey: el.getAttribute('data-bg-key') || '',
      nodeSid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
      entity: el.getAttribute('data-bg-entity') || '',
      agentflow: el.getAttribute('data-bg-agentflow') || '',
      kindText: [...el.querySelectorAll('.bg-task-kind')].map((x) => x.textContent || '').join('|'),
      entityText: [...el.querySelectorAll('.bg-task-entity')].map((x) => x.textContent || '').join('|'),
      name: (el.querySelector('.bg-task-name') || {}).textContent || '',
    }));
  }, LIST);
}

/** 页面侧 locale 词（词源 = subagents.* 键族，与渲染面同一条 t()）。 */
function localeWords(page) {
  return page.evaluate(async () => {
    const i18n = await import('/js/i18n.js');
    return {
      agentflow: i18n.t('subagents.kindAgentflow'),
      node: i18n.t('subagents.kindNode'),
      dispatcher: i18n.t('subagents.kindDispatcher'),
      locale: i18n.getLocale(),
    };
  });
}

/** 采集页面错误（pageerror + console.error）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

/** 标准夹具：一个整体行（delegate live 组头，mode=dispatcher）+ 五类成员行 + 一条
 *  store 成员行（source=subagent）。 */
async function seedOrchestration(page) {
  await setFixture(page, {
    rows: [mirrorRow()],
    children: {
      [DELEGATE_SID]: [{
        taskId: 'subagent-store-7f8e9d0c', source: 'subagent', status: 'completed',
        agentName: 'stand', description: '只读普查：野节点清单',
      }],
    },
  });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer', taskDescription: '术语分层批次——agentflow 与节点' }));
  await inject(page, chainStart(DISPATCHER_SID, { status: 'Processing', name: 'dispatcher/Nebflow' }));
  await inject(page, chainStart(NODE_SID, { status: 'Processing', name: 'stand' }));
  await inject(page, chainStart('node-bbbb2222', { status: 'Processing', name: '实现节点' }));
  await inject(page, chainStart(SUBAGENT_SID, { status: 'Processing', name: '侦察' }));
  await inject(page, chainStart(SUBTASK_SID, { status: 'Processing', name: '收编' }));
  await inject(page, chainStart(DAG_SID, { status: 'Processing', name: '步级' }));
}

// ── H1：整体行 = agentflow 词（zh-CN / en 各一次）───────────────────────────

test('H1 整体行（zh-CN）：data-bg-entity=agentflow 且显示词 = agentflow 词', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrchestration(page);
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H1 zh dump] ' + JSON.stringify(rows.map((r) => ({ level: r.level, bgKey: r.bgKey, entity: r.entity, entityText: r.entityText, kindText: r.kindText }))));
  const heads = rows.filter((r) => r.level === 'head');
  const words = await localeWords(page);
  console.log('[H1 zh locale] ' + JSON.stringify(words));

  expect(heads).toHaveLength(1);
  expect(heads[0].entity).toBe('agentflow');
  // 整体行显示词 = agentflow 词（locale 单点），且不是节点词。
  expect(words.agentflow).toBe('AgentFlow');
  expect(words.locale).toBe('zh-CN');
  expect(heads[0].entityText).toBe(words.agentflow);
  expect(heads[0].entityText).toBe('AgentFlow');
  expect(heads[0].entityText).not.toBe(words.node);
  expect(errors).toEqual([]);
});

test('H1 整体行（en）：data-bg-entity=agentflow 且显示词 = agentflow 词', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'en');
  await seedOrchestration(page);
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H1 en dump] ' + JSON.stringify(rows.map((r) => ({ level: r.level, entity: r.entity, entityText: r.entityText }))));
  const heads = rows.filter((r) => r.level === 'head');
  const words = await localeWords(page);
  console.log('[H1 en locale] ' + JSON.stringify(words));

  expect(heads).toHaveLength(1);
  expect(heads[0].entity).toBe('agentflow');
  expect(words.locale).toBe('en');
  expect(words.node).toBe('Node');
  expect(heads[0].entityText).toBe(words.agentflow);
  expect(heads[0].entityText).toBe('AgentFlow');
  expect(errors).toEqual([]);
});

test('H1b 合成延续组头（live 行已离场）同样是整体行：data-bg-entity=agentflow + agentflow 词', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  // 主桶无 live delegate 行；注册行镜像 + 作用域桶成员行 ⇒ 合成延续组头
  //（帧形照 bg-flow-chain-visibility T3：快照恢复路径，rootSessionId = delegateSid 自根）。
  await setFixture(page, { rows: [mirrorRow({ status: 'completed' })] });
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: DISPATCHER_SID, agentId: DISPATCHER_SID, agentName: 'dispatcher/Nebflow', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Idle', startedAt: T0 - 12 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' },
    ],
  });
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H1b dump] ' + JSON.stringify(rows.map((r) => ({ level: r.level, bgKey: r.bgKey, entity: r.entity, entityText: r.entityText, agentflow: r.agentflow }))));
  const heads = rows.filter((r) => r.level === 'head');
  expect(heads).toHaveLength(1);
  expect(heads[0].entity).toBe('agentflow');
  expect(heads[0].entityText).toBe('AgentFlow');
  // 合成组头的 data-bg-key = 合成键 `chain:<delegateSid>`（合成形态独有），而成员归属锚
  // = 该整体行的**定位键**（bgRowLocatorKey = delegate sid，与 data-node-session-id
  // 同值）——同一批成员在「live 组头」与「合成延续组头」两种形态下归属锚不变。
  const kids = rows.filter((r) => r.level === 'child');
  expect(kids.length).toBeGreaterThan(0);
  for (const k of kids) expect(k.agentflow).toBe(heads[0].nodeSid);
  expect(heads[0].nodeSid).toBe(DELEGATE_SID);
  expect(errors).toEqual([]);
});

// ── H2：成员行归属锚 + 成员词禁令 ─────────────────────────────────────────

test('H2 成员行：data-bg-agentflow 全部 == 整体行 key，且成员徽标不含 agentflow/AgentFlow/Flow', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrchestration(page);
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H2 dump] ' + JSON.stringify(rows.map((r) => ({ level: r.level, bgKey: r.bgKey, sid: r.nodeSid, agentflow: r.agentflow, kindText: r.kindText }))));
  const heads = rows.filter((r) => r.level === 'head');
  const kids = rows.filter((r) => r.level === 'child');
  expect(heads).toHaveLength(1);
  // 成员行齐备：分发器 + 三节点族 + Subagent + Delegate + store 成员。
  expect(kids.length).toBe(7);
  expect(new Set(kids.map((r) => r.nodeSid))).toEqual(new Set([
    DISPATCHER_SID, NODE_SID, 'node-bbbb2222', SUBAGENT_SID, SUBTASK_SID, DAG_SID,
    'subagent-store-7f8e9d0c',
  ]));
  // 归属靠结构：每条成员行的归属锚 = 所属整体行的定位键（data-bg-key 同口径）。
  for (const k of kids) {
    expect(k.agentflow).toBe(heads[0].bgKey);
    expect(k.agentflow).toBe(heads[0].nodeSid);
  }
  // 成员词 = 自身 kind（真实 kind，非整体词）。
  const wordOf = Object.fromEntries(kids.map((r) => [r.nodeSid, r.kindText]));
  console.log('[H2 words] ' + JSON.stringify(wordOf));
  expect(wordOf[DISPATCHER_SID]).toBe('分发器');
  expect(wordOf[NODE_SID]).toBe('节点');
  expect(wordOf['node-bbbb2222']).toBe('节点');
  expect(wordOf[SUBAGENT_SID]).toBe('Subagent');
  expect(wordOf[SUBTASK_SID]).toBe('Delegate');
  expect(wordOf[DAG_SID]).toBe('节点');
  expect(wordOf['subagent-store-7f8e9d0c']).toBe('Subagent');
  // 机械禁令：成员徽标里不得出现 agentflow / AgentFlow / Flow 字样。
  for (const k of kids) {
    expect(/agentflow/i.test(k.kindText)).toBe(false);
    expect(k.kindText.includes('AgentFlow')).toBe(false);
    expect(k.kindText.includes('Flow')).toBe(false);
  }
  expect(errors).toEqual([]);
});

test('H2b flow 结构投影成员行（chain.nodes flow-step）：徽标 = 节点词，不再是 AgentFlow', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await setFixture(page, {
    rows: [mirrorRow({ mode: 'dispatcher' })],
    chains: [{
      taskId: '91', delegateId: DELEGATE_SID,
      nodes: [
        { id: 'step-1', kind: 'flow-step', label: '步骤一', status: 'running', deps: [] },
        { id: 'step-2', kind: 'flow-step', label: '步骤二', status: 'pending', deps: ['step-1'] },
      ],
    }],
  });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Planner', taskDescription: '术语分层批次' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H2b dump] ' + JSON.stringify(rows.map((r) => ({ level: r.level, name: r.name, agentflow: r.agentflow, kindText: r.kindText }))));
  const heads = rows.filter((r) => r.level === 'head');
  const kids = rows.filter((r) => r.level === 'child');
  expect(heads).toHaveLength(1);
  expect(kids).toHaveLength(2);
  const words = await localeWords(page);
  for (const k of kids) {
    expect(k.agentflow).toBe(heads[0].bgKey);
    expect(k.kindText).toBe(words.node);
    expect(k.kindText.includes('Flow')).toBe(false);
  }
  expect(errors).toEqual([]);
});

// ── H3：反向门 ───────────────────────────────────────────────────────────

test('H3 反向门：整体行徽标不含 节点 / Node 字样（zh-CN 与 en 双验）', async ({ page }) => {
  const errors = trackErrors(page);
  for (const lc of ['zh-CN', 'en']) {
    await bootApp(page, lc);
    await seedOrchestration(page);
    await openPanel(page);
    const rows = await dumpRows(page);
    const head = rows.find((r) => r.level === 'head');
    expect(head).toBeTruthy();
    const badges = (head.kindText + '|' + head.entityText).split('|').filter(Boolean);
    console.log('[H3 ' + lc + '] badges=' + JSON.stringify(badges));
    expect(badges.length).toBeGreaterThan(0);
    for (const b of badges) {
      expect(b.includes('节点')).toBe(false);
      expect(b.includes('Node')).toBe(false);
    }
    expect(errors).toEqual([]);
  }
});

// ── H4：kernel / dispatcher 逐字保留 + agentflow 徽标按来源字段定 ——───────────
// 作者令（2026-11-06）：agentflow 徽标只准标 dispatcher 派生出来的行；kernel 实例
// （Delegate(mode="kernel") 起的最小单会话执行器）不得带它。判据 = **来源字段**
// DelegateRegistry.Record.mode（provider 镜像 row.mode）——不看会话 id 前缀、
// 不看名字、不看 orchestrationMode（spawn 副产品，惰性升级）。

test('H4 kernel 行：身份词逐字 kernel，且无 agentflow 徽标 / 无整体行实体锚', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  const KERNEL_SID = 'delegate-terms-kernel-01';
  const DISP_SID = 'delegate-terms-disp-02';
  await setFixture(page, {
    rows: [
      mirrorRow({ taskNo: '92', delegateId: KERNEL_SID, title: '内核批次', mode: 'kernel' }),
      mirrorRow({ taskNo: '93', delegateId: DISP_SID, title: '分发器批次', mode: 'dispatcher' }),
    ],
  });
  await inject(page, rootStart(KERNEL_SID, { name: 'KernelBatch', taskDescription: '内核批次' }));
  await inject(page, rootStart(DISP_SID, { name: 'DispBatch', taskDescription: '分发器批次' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H4 dump] ' + JSON.stringify(rows.map((r) => ({ sid: r.nodeSid, level: r.level, kindText: r.kindText, entityText: r.entityText, entity: r.entity }))));
  const headOf = (sid) => rows.find((r) => r.level === 'head' && r.nodeSid === sid);
  const k = headOf(KERNEL_SID);
  const d = headOf(DISP_SID);
  expect(k).toBeTruthy();
  expect(d).toBeTruthy();
  // 作者术语逐字（不改名、不翻译、不本地化）——kernel 保留自己的身份词。
  expect(k.kindText).toBe('kernel');
  expect(d.kindText).toBe('dispatcher');
  // kernel 行：**零** agentflow 徽标（词面与结构锚都不得出现）。
  expect(k.entityText).toBe('');
  expect(k.entity).toBe('');
  // dispatcher 行：agentflow 徽标 + 实体锚同在（dispatcher 派生的编排才带）。
  expect(d.entityText).toBe('AgentFlow');
  expect(d.entity).toBe('agentflow');
  expect(errors).toEqual([]);
});

test('H4b kernel 徽标面（en）同样零 agentflow；dispatcher 面同样带', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'en');
  const KERNEL_SID = 'delegate-terms-kernel-01';
  const DISP_SID = 'delegate-terms-disp-02';
  await setFixture(page, {
    rows: [
      mirrorRow({ taskNo: '92', delegateId: KERNEL_SID, title: 'kernel batch', mode: 'kernel' }),
      mirrorRow({ taskNo: '93', delegateId: DISP_SID, title: 'dispatcher batch', mode: 'dispatcher' }),
    ],
  });
  await inject(page, rootStart(KERNEL_SID, { name: 'KernelBatch', taskDescription: 'kernel batch' }));
  await inject(page, rootStart(DISP_SID, { name: 'DispBatch', taskDescription: 'dispatcher batch' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H4b dump] ' + JSON.stringify(rows.map((r) => ({ sid: r.nodeSid, kindText: r.kindText, entityText: r.entityText, entity: r.entity }))));
  const headOf = (sid) => rows.find((r) => r.level === 'head' && r.nodeSid === sid);
  expect(headOf(KERNEL_SID).kindText).toBe('kernel');
  expect(headOf(KERNEL_SID).entityText).toBe('');
  expect(headOf(KERNEL_SID).entity).toBe('');
  expect(headOf(DISP_SID).entityText).toBe('AgentFlow');
  expect(errors).toEqual([]);
});

test('H6 mode 缺席（旧盘 / 外部腿）：无来源证据 ⇒ 不落 agentflow 徽标，也不落形态词', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  const LEGACY_SID = 'delegate-terms-legacy-03';
  await setFixture(page, {
    rows: [mirrorRow({ taskNo: '94', delegateId: LEGACY_SID, title: '旧盘批次', mode: '' })],
  });
  await inject(page, rootStart(LEGACY_SID, { name: 'LegacyBatch', taskDescription: '旧盘批次' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H6 dump] ' + JSON.stringify(rows.map((r) => ({ sid: r.nodeSid, level: r.level, kindText: r.kindText, entityText: r.entityText, entity: r.entity }))));
  const head = rows.find((r) => r.level === 'head');
  expect(head).toBeTruthy();
  // 无信息胜过错信息：不拿「组头」当「dispatcher 派生」的证据。
  expect(head.entityText).toBe('');
  expect(head.entity).toBe('');
  expect(head.kindText).toBe('');
  expect(errors).toEqual([]);
});

// ── H8：行类型 → 徽标对照表（一张表钉死所有行型的徽标来源）─────────────────────

test('H8 行类型 → 徽标对照表：只有 dispatcher 派生的整体行带 agentflow 词', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  const K = 'delegate-terms-h8-kernel';
  const D = 'delegate-terms-h8-disp';
  const inD = (sid, over = {}) => ({
    type: 'agentStart',
    sessionId: D, rootSessionId: D,
    agentId: sid, nodeSessionId: sid, name: sid, taskDescription: '',
    project: 'Nebflow', ...over,
  });
  await setFixture(page, {
    rows: [
      mirrorRow({ taskNo: '95', delegateId: K, title: '内核批次', mode: 'kernel' }),
      mirrorRow({
        taskNo: '96', delegateId: D, title: '分发器批次', mode: 'dispatcher',
      }),
    ],
    children: {
      [D]: [
        { taskId: 'store-subagent-h8', source: 'subagent', status: 'completed', agentName: 'stand', description: '侦察' },
        { taskId: 'store-subtask-h8', source: 'subtask', status: 'completed', agentName: 'stand', description: '收编' },
        { taskId: 'store-delegate-h8', source: 'delegate', status: 'completed', agentName: 'stand', description: '委托' },
      ],
    },
  });
  await inject(page, rootStart(K, { name: 'KernelBatch', taskDescription: '内核批次' }));
  await inject(page, rootStart(D, { name: 'DispBatch', taskDescription: '分发器批次' }));
  await inject(page, inD('dispatcher-h8aaaa', { status: 'Processing', name: 'dispatcher/Nebflow' }));
  await inject(page, inD('node-h8bbbb', { status: 'Processing', name: 'stand' }));
  await inject(page, inD('subagent-h8cccc', { status: 'Processing', name: '侦察' }));
  await inject(page, inD('subtask-h8dddd', { status: 'Processing', name: '收编' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  const table = rows.map((r) => ({ sid: r.nodeSid, level: r.level, kind: r.kindText, entity: r.entityText }));
  console.log('[H8 table] ' + JSON.stringify(table, null, 0));

  const at = (sid) => rows.find((r) => r.nodeSid === sid);
  const badges = (sid) => [at(sid).kindText, at(sid).entityText].filter(Boolean);
  const words = await localeWords(page);

  // ① kernel 实例（最小单会话执行器）：只有身份词 kernel，零 agentflow。
  expect(badges(K)).toEqual(['kernel']);
  // ② dispatcher 编排整体行：形态词 + agentflow 词（唯一允许同时两词的位）。
  expect(badges(D)).toEqual(['dispatcher', words.agentflow]);
  // ③ 成员行：各自 kind，零 agentflow。
  expect(badges('dispatcher-h8aaaa')).toEqual([words.dispatcher]);
  expect(badges('node-h8bbbb')).toEqual([words.node]);
  expect(badges('subagent-h8cccc')).toEqual(['Subagent']);
  expect(badges('subtask-h8dddd')).toEqual(['Delegate']);
  expect(badges('store-subagent-h8')).toEqual(['Subagent']);
  expect(badges('store-subtask-h8')).toEqual(['Delegate']);
  expect(badges('store-delegate-h8')).toEqual(['Delegate']);
  // ④ 全表机械禁令：除 dispatcher 整体行外，任何行都不得出现 agentflow 词。
  for (const r of rows) {
    if (r.level === 'head' && r.nodeSid === D) continue;
    expect(r.entityText).toBe('');
    expect(/agentflow/i.test(r.kindText)).toBe(false);
  }
  expect(errors).toEqual([]);
});

test('H5 词源单点：两个显示词只在 bgAgents.js 一处消费，其它 web 模块不硬编码同批词', async () => {
  const read = (p) => readFileSync(p, 'utf8');
  const bgSrc = read(join(WEB, 'js', 'main', 'bgAgents.js'));
  const zh = read(join(WEB, 'js', 'locales', 'zh-CN.js'));
  const en = read(join(WEB, 'js', 'locales', 'en.js'));

  // ① 单点判定函数在场；旧的多词源函数退役。
  expect(/function bgAgentflowWord\s*\(/.test(bgSrc)).toBe(true);
  expect(/function bgMemberKindWord\s*\(/.test(bgSrc)).toBe(true);
  expect(/function bgFlowKindWord\s*\(/.test(bgSrc)).toBe(false);

  // ② 两个显示词各自只有一个 locale 键消费点（一次 t() 调用）。
  //    判面 = **代码行**（注释里叙旧提到键名不算词源；块注释与行注释先剔）。
  const codeOnly = (src) => src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  const bgCode = codeOnly(bgSrc);
  const count = (src, needle) => src.split(needle).length - 1;
  console.log('[H5 counts] kindAgentflow=' + count(bgCode, 'subagents.kindAgentflow')
    + ' kindNode=' + count(bgCode, 'subagents.kindNode')
    + ' kindDispatcher=' + count(bgCode, 'subagents.kindDispatcher'));
  expect(count(bgCode, 'subagents.kindAgentflow')).toBe(1);
  expect(count(bgCode, 'subagents.kindNode')).toBe(1);

  // ③ 行构造器里不得有 agentflow 词的字面量徽标（词只能来自 locale）。
  const sliceOf = (name, endName) => bgSrc.slice(bgSrc.indexOf('function ' + name), bgSrc.indexOf('function ' + endName));
  for (const [a, b] of [['bgTaskRowHtml', 'bgStoreChildHtml'], ['bgStoreChildHtml', 'bgFlowChildHtml'], ['bgFlowChildHtml', 'export function renderBgAgentDropdown']]) {
    const body = sliceOf(a, b);
    expect(body.length).toBeGreaterThan(0);
    expect(/['"]AgentFlow['"]/.test(body)).toBe(false);
    expect(/<span class="bg-task-kind">\s*AgentFlow/.test(body)).toBe(false);
  }
  // 归属锚与实体锚是结构事实（属性名只此一处字面量；注释里的同名叙旧不算）。
  expect(count(bgCode, 'data-bg-entity="agentflow"')).toBe(1);
  expect(count(bgCode, 'data-bg-agentflow=')).toBeGreaterThanOrEqual(1);

  // ④ 其它 web 模块不得硬编码同一批词（键族只在 bgAgents.js 消费）。
  const walk = (dir, out = []) => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) walk(p, out);
      else if (p.endsWith('.js')) out.push(p);
    }
    return out;
  };
  const others = walk(join(WEB, 'js'))
    .filter((p) => !p.endsWith(join('locales', 'zh-CN.js')) && !p.endsWith(join('locales', 'en.js')))
    .filter((p) => p !== join(WEB, 'js', 'main', 'bgAgents.js'));
  const offenders = [];
  for (const p of others) {
    const body = read(p);
    for (const needle of ['subagents.kindAgentflow', 'subagents.kindNode', 'subagents.kindDispatcher']) {
      if (body.includes(needle)) offenders.push(p.replace(WEB, '') + ' → ' + needle);
    }
  }
  console.log('[H5 offenders] ' + JSON.stringify(offenders));
  expect(offenders).toEqual([]);

  // ⑤ locale 双版同键同词面（zh/en 各一个键，值 = AgentFlow）。
  for (const [name, src] of [['zh-CN.js', zh], ['en.js', en]]) {
    const m = src.match(/'subagents\.kindAgentflow':\s*'([^']*)'/);
    console.log('[H5 locale ' + name + '] ' + (m ? m[1] : '<missing>'));
    expect(m).toBeTruthy();
    expect(m[1]).toBe('AgentFlow');
    expect(/'subagents\.kindNode':\s*'[^']+'/.test(src)).toBe(true);
  }
});

// ── H7：来源字段门（静态读源码）——agentflow 徽标由 row.mode 判定，不靠猜 ──────

test('H7 agentflow 徽标判据 = 来源字段 dispatcher；head 行不无条件渲染实体徽标/实体锚', async () => {
  const read = (p) => readFileSync(p, 'utf8');
  const bgSrc = read(join(WEB, 'js', 'main', 'bgAgents.js'));
  const slice = (name, endName) => bgSrc.slice(bgSrc.indexOf('function ' + name), bgSrc.indexOf('function ' + endName));

  // ① 词源函数体 = 来源字段判定：逐字比较 'dispatcher'，并**只**认这一个词。
  const wordBody = slice('bgAgentflowWord', 'bgMemberKindWord');
  expect(wordBody.length).toBeGreaterThan(0);
  expect(/===\s*'dispatcher'/.test(wordBody)).toBe(true);
  // 不许字符串猜来源（前缀 / 包含 / 名字）——来源只认字段。
  for (const guess of ['startsWith', 'endsWith', '.includes(', 'indexOf(', /name\s*===/]) {
    expect(typeof guess === 'string' ? wordBody.includes(guess) : guess.test(wordBody)).toBe(false);
  }

  // ② 行构造器里实体徽标是**条件**渲染（entityWord 在场才出 span），实体锚同样按同一
  //    读数条件化——旧「headLevel ⇒ 恒 data-bg-entity」形态退役（错标根因那条判据）。
  const rowBody = slice('bgTaskRowHtml', 'bgStoreChildHtml');
  expect(/<span class="bg-task-entity">/.test(rowBody)).toBe(true);
  expect(/const entityPart = entityWord\s*\?/.test(rowBody)).toBe(true);
  expect(/entityWord \? ' data-bg-entity="agentflow"' : ''/.test(rowBody)).toBe(true);
  expect(rowBody.includes('data-bg-row-level="head" data-bg-entity="agentflow"')).toBe(false);

  // ③ 执行形态单一提取点：row.mode → kernel|dispatcher（值域外的词一律 ''）。
  expect(/function bgDelegateExecMode\s*\(/.test(bgSrc)).toBe(true);
  const modeBody = slice('bgDelegateExecMode', 'bgDelegateBadgeWord');
  expect(/row\s*&&\s*row\.mode/.test(modeBody)).toBe(true);
  expect(/===\s*'kernel'/.test(modeBody)).toBe(true);
  expect(/===\s*'dispatcher'/.test(modeBody)).toBe(true);
});
