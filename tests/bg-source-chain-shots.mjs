// bg-source-chain-shots.mjs — 来源链批（作者令 2026-11-01 19:23「后台 agent 的
// badge 要标注来源链」）视觉证据 + 机械断言。
//
// 契约：后台 agent 徽标升级为「上层 · 下层」**名字链**（delegate 短名/subagent 名/
// node 名；不是 id 不是 type）——
//   - 后台任务面板行来源 chip：delegate 作用域的 subagent/node 任务 = 「delegate短名
//     · 自己名」；根直派 delegate = 恰单段短名（不造空段）；根会话自开 = 主 agent
//     显示名单段（既有显示不变）；设备任务链不掺轴（设备 chip 照旧）。
//   - 详情卡来源行 = 面板行同一条链（bgSourceChain 单点解析）。
//   - delegate 完工卡（注入行 nf-rcpt-pill）：收件会话是 delegate ⇒ pill 首段前插
//     「父短名」；根会话收件 = 卡面逐字节不变（主会话注入行无链）。
//   - Flow 面板会话行（智能体节）：delegate 组头短名 + 分发器/节点 kind 徽标照旧
//     （type 契约不动）；孤儿提升行来源角标 = 父名字链（解析不到回退类型词）。
// 数据面：纯显示层既有镜像（delegateChainStub 夹具缝 + state.sessionBgAgents），
// 引擎侧 id 生成与协议字段零触碰。
//
// 产出 = .nebflow/evidence/20261101_bg-source-chain/shots/
//        {delegate-view,root-view}-{chromium,webkit}-{light,dark}.png
// 用法：node tests/bg-source-chain-shots.mjs
import { chromium, webkit } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', '.nebflow', 'evidence', '20261101_bg-source-chain', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

// 三层实况（作者验收口径：根派 delegate → delegate 起 subagent 与 node 各一）：
//   delegate 会话 delegate-nebflow-aa11bb22（批次短名「模型链面板精简」）
//     ├─ subagent-cc33dd44（子代理名「资料搜集」——G3 children 镜像）
//     └─ node-ee55ff66（Flow Map 节点名「修文档节点」——origin/originLabel 行字段）
// 作者令示例链恰 = 「模型链面板精简 · 修文档节点」。
const DELEGATE_SID = 'delegate-nebflow-aa11bb22';
const SUBAGENT_SID = 'subagent-cc33dd44';
const NODE_SID = 'node-ee55ff66';
const GONE_DELEGATE_SID = 'delegate-nebflow-gone7777'; // 孤儿行的不可见父（有注册行、无 live 组头）
const ROOT_SID = 'chain-root-session';
const T0 = Date.now();

const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };

// delegate 注册行镜像夹具（DelegateRow 形；name = 批次短名 ≤12 字）。
const ROWS = [
  { taskNo: '7', delegateId: DELEGATE_SID, title: '模型链面板精简任务批次标题全文', name: '模型链面板精简',
    executor: 'nebflow', status: 'running', external: false, project: '', createdAt: T0 - 600_000,
    orchestrationMode: 'kernel', mode: 'kernel', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    restartInterruptedAt: null, continuedBy: '' },
  { taskNo: '6', delegateId: GONE_DELEGATE_SID, title: '已结束批次的标题全文', name: '链上逝者',
    executor: 'nebflow', status: 'completed', external: false, project: '', createdAt: T0 - 3_600_000,
    orchestrationMode: 'single', mode: '', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    restartInterruptedAt: null, continuedBy: '' },
];
// G3 星型子代理镜像（children 以 parentSessionId 键挂）。
const CHILDREN = {
  [DELEGATE_SID]: [
    { taskId: SUBAGENT_SID, parentSessionId: DELEGATE_SID, agentName: '资料搜集', prompt: 'collect materials',
      description: '资料搜集任务摘要', status: 'running', retryCount: 0, spawnedAt: T0 - 120_000,
      completedAt: null, lastError: '', source: 'subagent' },
  ],
};

// delegate 视图桶（activeBgTasks 快照行；桶键 = delegate sid = rootSessionId 语义）：
//   row-1 delegate 自开（ownerName 短名）——根直派 ⇒ 恰单段
//   row-2 子代理会话任务（origin=nebula + subagent- 前缀 ⇒ G3 名字链）
//   row-3 节点任务（origin=node + originLabel 节点名 ⇒ 桶键补上层 delegate 名）
//   row-4 根会话自开件（origin=nebula + 主会话 id ⇒ 主 agent 显示名单段）
function delegateBucketTasks() {
  return [
    { taskId: 'job-delegate-own', description: 'run sbt compile in delegate worktree', status: 'running',
      startedAt: T0 - 86_000, sessionId: DELEGATE_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: 'kernel', ownerName: '模型链面板精简', heartbeat: { idleMs: 3_000, outputLines: 8 } },
    { taskId: 'job-subagent', description: 'collect reference materials', status: 'running',
      startedAt: T0 - 61_000, sessionId: SUBAGENT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: '', ownerName: '', heartbeat: { idleMs: 2_000, outputLines: 5 } },
    { taskId: 'job-node', description: '修文档节点 · flow step running', status: 'running',
      startedAt: T0 - 31_000, sessionId: NODE_SID, kind: 'local', origin: 'node', originLabel: '修文档节点',
      ownerMode: '', ownerName: '', heartbeat: { idleMs: 1_000, outputLines: 12 } },
    { taskId: 'job-root-plain', description: 'npm run build --workspace=web', status: 'running',
      startedAt: T0 - 12_000, sessionId: 'chain-root-session', kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: '', ownerName: '', heartbeat: { idleMs: 1_000, outputLines: 42 } },
  ];
}

// root 视图桶（单层 delegate 回归 + 主 agent 单段回归）：
function rootBucketTasks() {
  return [
    { taskId: 'job-root-plain', description: 'npm run build --workspace=web', status: 'running',
      startedAt: T0 - 12_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: '', ownerName: '', heartbeat: { idleMs: 1_000, outputLines: 42 } },
    { taskId: 'job-delegate-own', description: 'run sbt compile in delegate worktree', status: 'running',
      startedAt: T0 - 86_000, sessionId: DELEGATE_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: 'kernel', ownerName: '模型链面板精简', heartbeat: { idleMs: 3_000, outputLines: 8 } },
  ];
}

// 智能体节夹具（root 视图）在 shootRootView 内直注（state.sessionBgAgents 同源形状）：
// delegate 组头（live 行）+ 作用域桶链行（node/dispatcher）+ 孤儿提升行（父注册行
// 有名、组头不可见 ⇒ 角标 = 父名字链）。

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// 静态 harness 基址：全部请求走 route 拦截（端口不监听即可）。chromium 沿用仓内
// 既有 spec 的 localhost:1；webkit 把 <1024 特权口列为 restricted network port
// （连 route 拦截都到不了）⇒ 双引擎取各自的不监听高位口。
const BASE_URL = (engine) => (engine === 'webkit' ? 'http://127.0.0.1:59903/' : 'http://localhost:1/');

async function makePage(browser, colorScheme, activeSessionId, engine) {
  const page = await (await browser.newContext({
    viewport: { width: 1440, height: 900 },
    colorScheme, deviceScaleFactor: 2,
  })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  page.on('response', (r) => { if (r.status() >= 400) console.log('HTTP', r.status(), r.url()); });
  await page.addInitScript(({ rows, children }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_sw', '0');
    // delegate 镜像夹具（stub 夹具缝：夹具在场 = 读取口恒夹具优先，provider 零回归）。
    globalThis.__CHAIN_ROWS = rows;
    globalThis.__CHAIN_CHILDREN = children;
  }, { rows: ROWS, children: CHILDREN });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // hotStamp（#919）轮询 /api/nf-asset-stamp：夹具页给恒定 stamp——基线拍与
    // 后续拍同值 ⇒ 判路恒 'same'，页面永不自动 reload（reload 会清空
    // delegateChainStub 模块级夹具 Map，链解析回退——webkit 偶发失败的根因）。
    if (p === '/api/nf-asset-stamp') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ stamp: 'e2e-fixed' }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Spider' }) });
    }
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: SELF_DEVICE,
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [
        { id: ROOT_SID, name: 'root', agentName: 'Nebula' },
        { id: DELEGATE_SID, name: DELEGATE_SID, agentName: 'nebflow' },
      ],
      folders: [], activeId: activeSessionId,
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
  await page.goto(BASE_URL(engine));
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    if (s.activeSessionId !== sid) return false;
    if (!s.ws || s.ws.readyState !== 1) return false;
    const stub = await import('/js/delegateChainStub.js');
    if (!stub.__hasFixture()) {
      stub.__setFixture({ rows: globalThis.__CHAIN_ROWS || [], children: globalThis.__CHAIN_CHILDREN || {} });
    }
    return stub.__hasFixture();
  }, activeSessionId, { timeout: 15000 });
  await page.waitForTimeout(400);
  return { page, errors };
}

async function openBgPanel(page) {
  // 双保险（夹具竞态防线②）：每次驱动渲染前确保 stub 夹具在场——gate 已过后若
  // 模块状态曾被重置（reload/换实例），这里静默补种，链解析不再回退。
  const ok = await ensureFixture(page);
  if (!ok) throw new Error('ensureFixture failed: stub fixture missing after reseed');
  await page.evaluate(() => document.getElementById('bg-dropdown').classList.remove('hidden'));
  await page.evaluate(() => import('/js/state.js').then((m) => {
    const s = m.default;
    const v = s.getActiveView && s.getActiveView();
    if (s.updateBgTasksUI && v) s.updateBgTasksUI(v);
  }));
  await page.waitForTimeout(400);
}

// stub 夹具再种（护栏）：读口恒夹具优先；不在场 = 用 init script 落的
// globalThis 镜像补种并回报。返回 true = 夹具确定在场。
async function ensureFixture(page) {
  return page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    if (!stub.__hasFixture()) {
      stub.__setFixture({ rows: globalThis.__CHAIN_ROWS || [], children: globalThis.__CHAIN_CHILDREN || {} });
    }
    return stub.__hasFixture();
  });
}

const readRowChips = (tag) => `(() => {
  const list = document.querySelector('#bg-dropdown .bg-section-tasks .bg-dropdown-list');
  const rows = list ? [...list.querySelectorAll('.bg-task-row')] : [];
  const read = (id) => {
    const r = rows.find((x) => x.getAttribute('data-task-id') === id);
    if (!r) return null;
    const meta = r.querySelector('.bg-task-meta');
    return {
      origins: [...meta.querySelectorAll('.bg-task-origin:not(.bg-origin-device)')].map((c) => ({ text: c.textContent.trim(), cls: c.className, title: c.getAttribute('title') || '' })),
      devices: [...meta.querySelectorAll('.bg-origin-device')].map((c) => c.textContent.trim()),
      modes: [...meta.querySelectorAll('.bg-task-mode')].map((c) => c.textContent.trim()),
    };
  };
  return { rows: rows.length, ${tag} };
})()`;

// ── 场景 A：delegate 视图（三层实况主战场）───────────────────────────────
async function shootDelegateView(browser, engine, colorScheme) {
  const { page, errors } = await makePage(browser, colorScheme, DELEGATE_SID, engine);
  await page.evaluate((tasks) => {
    return import('/js/state.js').then((m) => { m.default.sessionBgTasks['delegate-nebflow-aa11bb22'] = tasks; });
  }, delegateBucketTasks());
  await openBgPanel(page);
  const tag = `delegate-view-${engine}-${colorScheme}`;
  await page.locator('#bg-dropdown').screenshot({ path: join(OUT, `${tag}.png`) });

  const audit = await page.evaluate(readRowChips(`
    own: read('job-delegate-own'),
    sub: read('job-subagent'),
    node: read('job-node'),
    plain: read('job-root-plain')`));

  ok(`rows=4 (${tag})`, audit.rows === 4, String(audit.rows));
  // ① delegate 自开件（根直派）：来源 chip 恰单段短名（「不造空段」），样式/内核 chip 照旧。
  ok(`own chip = [模型链面板精简] 单段 (${tag})`,
    JSON.stringify(audit.own?.origins?.map((o) => o.text)) === JSON.stringify(['模型链面板精简']) &&
    audit.own?.origins?.[0]?.cls.includes('bg-origin-delegate'), JSON.stringify(audit.own?.origins));
  ok(`own device+mode intact (${tag})`,
    audit.own?.devices?.[0] === 'Mashiros-MacBook-Pro' && audit.own?.modes?.[0] === '内核',
    JSON.stringify({ d: audit.own?.devices, m: audit.own?.modes }));
  // ② 子代理件：链 = 「模型链面板精简 · 资料搜集」（归属 chip 样式），tooltip 全链。
  ok(`sub chain = [模型链面板精简 · 资料搜集] (${tag})`,
    audit.sub?.origins?.[0]?.text === '模型链面板精简 · 资料搜集' &&
    audit.sub?.origins?.[0]?.cls.includes('bg-origin-delegate'), JSON.stringify(audit.sub?.origins));
  ok(`sub title = 由全链开启 (${tag})`,
    audit.sub?.origins?.[0]?.title === '由 模型链面板精简 · 资料搜集 开启', audit.sub?.origins?.[0]?.title);
  // ③ 节点件：链 = 「模型链面板精简 · 修文档节点」（作者令示例链）。
  ok(`node chain = [模型链面板精简 · 修文档节点] (${tag})`,
    audit.node?.origins?.[0]?.text === '模型链面板精简 · 修文档节点' &&
    audit.node?.origins?.[0]?.cls.includes('bg-origin-delegate'), JSON.stringify(audit.node?.origins));
  // ④ 根会话件：单段主 agent 显示名（既有显示不变——Nebula 退役批读数 Spider）。
  ok(`plain chip = [Spider] 单段 (${tag})`,
    JSON.stringify(audit.plain?.origins?.map((o) => o.text)) === JSON.stringify(['Spider']), JSON.stringify(audit.plain?.origins));

  // 详情卡：节点行点开 → 副标题来源段 = 同一条链。
  await ensureFixture(page); // 夹具再种（护栏）：链解析读 stub 镜像
  await page.evaluate(() => document.querySelector('#bg-dropdown .bg-task-row[data-task-id="job-node"]').click());
  await page.waitForSelector('.bgt-overlay', { timeout: 5000 });
  const sub = await page.evaluate(() => document.querySelector('.bgt-overlay .bgt-subtitle')?.textContent || '');
  ok(`detail card subtitle chain (${tag})`, sub.includes('模型链面板精简 · 修文档节点'), sub);
  await page.evaluate(() => document.querySelector('.bgt-overlay .bgt-close')?.click());
  await page.waitForTimeout(200);

  // 完工卡（delegate 收件）：pill 首段 = 父短名（来源链上层段）。
  await ensureFixture(page); // 夹具再种（护栏）：chainNameOf 读 stub 镜像
  const pill = await page.evaluate(async () => {
    const m = await import('/js/chat/injectedRows.js');
    const row = m.buildInjectedRow('work done\n[delegate-judge] verdict: completed.', 'delegate', Date.now(),
      'completed', 'nebflow', '', undefined, undefined, undefined, 'DELEGATE · NEBFLOW · COMPLETED', null, null, 'delegate-nebflow-aa11bb22');
    return row.querySelector('.nf-rcpt-pill')?.textContent || '';
  });
  ok(`receipt pill chain (${tag})`, pill === '模型链面板精简 · DELEGATE · INTERNAL · COMPLETED', pill);

  ok(`zero console errors (${tag})`, errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
}

// ── 场景 B：root 视图（单层回归 + Flow 会话行 + 孤儿角标名字链）──────────
async function shootRootView(browser, engine, colorScheme) {
  const { page, errors } = await makePage(browser, colorScheme, ROOT_SID, engine);
  // 任务桶 + 智能体节夹具（state.sessionBgAgents 直注——live/快照行桶同源形状）。
  await page.evaluate(({ tasks, agents }) => {
    return import('/js/state.js').then((m) => {
      const s = m.default;
      s.sessionBgTasks['chain-root-session'] = tasks;
      for (const [bucket, list] of Object.entries(agents)) {
        s.sessionBgAgents[bucket] = list;
      }
    });
  }, { tasks: rootBucketTasks(), agents: {
    [ROOT_SID]: {
      [DELEGATE_SID]: {
        name: DELEGATE_SID, task: '模型链面板精简任务批次标题全文', sessionId: DELEGATE_SID,
        parentSessionId: '', delegateName: '模型链面板精简', kind: 'Delegate', project: '',
        startedAt: T0 - 600_000, status: 'Processing', retryCount: 0, bgWaiting: [],
        heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0, lastActivityMs: T0 - 3_000,
      },
      'subagent-orphan99': {
        name: 'subagent-orphan99', task: '孤儿行任务摘要', sessionId: 'subagent-orphan99',
        parentSessionId: GONE_DELEGATE_SID, delegateName: '', kind: 'SubTask', project: '',
        startedAt: T0 - 45_000, status: 'Processing', retryCount: 0, bgWaiting: [],
        heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0, lastActivityMs: T0 - 1_000,
      },
    },
    [DELEGATE_SID]: {
      [NODE_SID]: {
        name: NODE_SID, task: '修文档节点任务描述', sessionId: NODE_SID,
        parentSessionId: DELEGATE_SID, delegateName: '', kind: 'Flow', project: 'stand',
        startedAt: T0 - 31_000, status: 'Processing', retryCount: 0, bgWaiting: [],
        heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0, lastActivityMs: T0 - 2_000,
      },
      'dispatcher-zz88': {
        name: 'dispatcher-zz88', task: 'dispatcher/stand', sessionId: 'dispatcher-zz88',
        parentSessionId: DELEGATE_SID, delegateName: '', kind: 'Flow', project: 'stand',
        startedAt: T0 - 50_000, status: 'Processing', retryCount: 0, bgWaiting: [],
        heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0, lastActivityMs: T0 - 4_000,
      },
    },
  } });
  await openBgPanel(page);
  await ensureFixture(page); // 夹具再种（护栏）：孤儿链/组头短名读 stub 镜像
  await page.evaluate(() => import('/js/main/bgAgents.js').then((m) => m.renderBgAgentDropdown()));
  await page.waitForTimeout(400);
  const tag = `root-view-${engine}-${colorScheme}`;
  await page.locator('#bg-dropdown').screenshot({ path: join(OUT, `${tag}.png`) });

  // 任务节回归：root 自开单段主 agent 名；delegate 件单段短名（设备/内核 chip 照旧）。
  const tasks = await page.evaluate(readRowChips(`
    plain: read('job-root-plain'),
    own: read('job-delegate-own')`));
  ok(`root plain chip = [Spider] (${tag})`,
    JSON.stringify(tasks.plain?.origins?.map((o) => o.text)) === JSON.stringify(['Spider']), JSON.stringify(tasks.plain?.origins));
  ok(`delegate own chip = [模型链面板精简] 单段 (${tag})`,
    JSON.stringify(tasks.own?.origins?.map((o) => o.text)) === JSON.stringify(['模型链面板精简']) &&
    tasks.own?.devices?.[0] === 'Mashiros-MacBook-Pro' && tasks.own?.modes?.[0] === '内核',
    JSON.stringify(tasks.own));

  // 智能体节（Flow 会话行）：组头短名 + 节点/分发器 kind 徽标（type 契约不动）。
  const agents = await page.evaluate(`(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    if (!list) return { rows: 0 };
    const rows = [...list.querySelectorAll('.bg-task-row')];
    const head = rows.find((r) => (r.querySelector('.bg-task-name')?.textContent || '') === '模型链面板精简');
    const find = (sid) => rows.find((r) => (r.getAttribute('data-node-session-id') || '').includes(sid));
    const nodeRow = find('node-ee55ff66');
    const dispRow = find('dispatcher-zz88');
    const orphan = rows.find((r) => (r.getAttribute('data-node-session-id') || '').includes('subagent-orphan99'));
    return {
      rows: rows.length,
      head: !!head,
      // 组头徽标 = row.mode 原始数据词（徽标归位批：bgAgents 用原始数据词不经
      // i18n，类 = .bg-task-kind）——kernel 在场即「顶层只认执行形态」契约。
      headMode: head ? (head.querySelector('.bg-task-kind')?.textContent || '') : '',
      nodeKind: nodeRow ? (nodeRow.querySelector('.bg-task-kind')?.textContent || '') : '',
      dispKind: dispRow ? (dispRow.querySelector('.bg-task-kind')?.textContent || '') : '',
      orphanChip: orphan ? (orphan.querySelector('.bg-task-orphan')?.textContent || '') : '',
    };
  })()`);
  ok(`agent rows ≥3 (${tag})`, agents.rows >= 3, JSON.stringify({ rows: agents.rows }));
  ok(`delegate head short name + mode badge (${tag})`, agents.head === true && agents.headMode === 'kernel',
    JSON.stringify({ head: agents.head, mode: agents.headMode }));
  ok(`node row kind = 节点（type 契约不动） (${tag})`, agents.nodeKind === '节点', agents.nodeKind);
  ok(`dispatcher row kind = 分发器 (${tag})`, agents.dispKind === '分发器', agents.dispKind);
  // 孤儿角标 = 父名字链（链上逝者）——不再是「来自已结束的委托」类型词。
  ok(`orphan chip = 父名字链 (${tag})`, agents.orphanChip === '链上逝者', agents.orphanChip);

  // 完工卡（root 收件）：无上层链 ⇒ 卡面逐字节不变（主会话注入行来源段无链）。
  await ensureFixture(page); // 夹具再种（护栏）：有 fixture 时 chainNameOf 只对 delegate- 前缀生效，root 收件不变
  const pill = await page.evaluate(async () => {
    const m = await import('/js/chat/injectedRows.js');
    const row = m.buildInjectedRow('work done\n[delegate-judge] verdict: completed.', 'delegate', Date.now(),
      'completed', 'nebflow', '', undefined, undefined, undefined, 'DELEGATE · NEBFLOW · COMPLETED', null, null, 'chain-root-session');
    return row.querySelector('.nf-rcpt-pill')?.textContent || '';
  });
  ok(`receipt pill unchanged at root (${tag})`, pill === 'DELEGATE · INTERNAL · COMPLETED', pill);

  ok(`zero console errors (${tag})`, errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
}

const chromiumBrowser = await chromium.launch();
await shootDelegateView(chromiumBrowser, 'chromium', 'light');
await shootDelegateView(chromiumBrowser, 'chromium', 'dark');
await shootRootView(chromiumBrowser, 'chromium', 'light');
await shootRootView(chromiumBrowser, 'chromium', 'dark');
await chromiumBrowser.close();

const webkitBrowser = await webkit.launch();
await shootDelegateView(webkitBrowser, 'webkit', 'light');
await shootDelegateView(webkitBrowser, 'webkit', 'dark');
await shootRootView(webkitBrowser, 'webkit', 'light');
await shootRootView(webkitBrowser, 'webkit', 'dark');
await webkitBrowser.close();

console.log(`shots + audit done → ${OUT}`);
process.exit(failures ? 1 : 0);
