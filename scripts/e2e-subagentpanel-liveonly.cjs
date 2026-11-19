#!/usr/bin/env node
// e2e-subagentpanel-liveonly.cjs — 子代理面板「分发器行仅在运行时显示 · 计数只计运行中」
// 真渲染探针（2026-09-14 面板可见性批）。
//
// 挂点理由：既有前端 e2e 面都不覆盖 Sub-Agents 面板（`bg-dropdown`）的行准入/计数
// 口径 —— `e2e-bgtask-panel-lifecycle.cjs` 打的是**后台任务面板**（`#bg-indicator` /
// `#bg-dropdown`，key space 天然分离，见 main.js bgTaskOrigin 注释）；`shot-bgtask-panel.cjs`
// 同类；`tests/*.spec.mjs` 无 Sub-Agents 面板准入用例。故本探针自包含直打真渲染。
//
// 装置（零 gateway、零端口、零后端）：page.route 从磁盘服务真实前端树 +
// MockWebSocket 走**生产 ws.js → main.js 分发路径**，注入生产形态帧：
//   - 快照帧 `activeAgents`（生产者 WebSocketRoutes.scala:5888-5914 activeAgentEntryJson：
//     agentId == sessionId、kind、status = AgentStatus.toString、startedAt、project）
//   - 实时帧 `agentStart` / `agentDone`（生产者 protocol.scala:802-808 toJson，
//     agentId = ctx.self.path.name = 子会话 id；routeSubagentWsSend 注入 rootSessionId
//     归桶键 / sessionId / nodeSessionId / project）
//
// 判据（作者 2026-09-14 16:30 裁定 · asknb-6d626dd4307a4d31「只显示运行中的，计数也只
// 计算运行中的，空闲不显示」）：
//   P1 空闲（Idle）分发器行 **不出现**在 DOM，且 **不计入**计数
//   P2 运行中（Processing）分发器行 **出现**且 **计入**
//   P3 非分发器行口径不变：`node-`（含 Idle 行）/ `delegate-` / `subtask-` 照旧
//   P4 计数同桶同口径：顶栏徽标 == 面板头计数 == DOM 行数（面板头 == 可见行数）
//   P5 空闲独占桶时：行 0 / 计数 0 / 空态文案出现 / 徽标隐藏（空态与画面自洽）
//   P6 实时路径（agentStart）：运行中分发器行可见；agentDone 后 2s 窗口内不计入
//   P7 覆盖：亮/暗双主题 × 窄/中/宽三视口
//
// 该脚本**同一份断言**在改动前应跑出 FAIL（红）、改动后应跑出全 PASS（绿）——
// 故「红/绿」是两次运行的同一判据，非两份脚本。
//
// Run（worktree 根）：node scripts/e2e-subagentpanel-liveonly.cjs
//   NEBFLOW_WEB_DIR=<dir>  指向**未改动**的前端树副本（红基线复跑用）
//   EVIDENCE_DIR=<dir>     截图落盘目录（可选）
//   RUN_LABEL=<str>        本次运行标签（写进输出行，便于证据对账）
// 不起任何端口、不碰 8080（零信号）；Playwright 一律 browser.close() into finally。
const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize, resolve } = require('node:path');

const WEB = process.env.NEBFLOW_WEB_DIR
  ? resolve(process.env.NEBFLOW_WEB_DIR)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const EVIDENCE = process.env.EVIDENCE_DIR ? resolve(process.env.EVIDENCE_DIR) : null;
const LABEL = process.env.RUN_LABEL || 'run';
if (EVIDENCE) mkdirSync(EVIDENCE, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};

const ROOT_SID = 'e2e-subagentpanel-root';
// 会话 id 前缀即判别键（§3.0）：`dispatcher-` = Project 分发器；`node-` = Project 节点；
// `delegate-` / `subtask-` = Delegate/SubTask 子代理。
const IDLE_DISPATCHER = 'dispatcher-aaaa0001';   // 空闲常驻分发器（保活窗内，考古 §2.2）
const ACTIVE_DISPATCHER = 'dispatcher-bbbb0002'; // 运行中的分发器
const IDLE_NODE = 'node-cccc0003';               // 空闲的 Project 节点会话（口径本批不动）
const DELEGATE = 'delegate-probe-dddd0004';
const SUBTASK = 'subtask-eeee0005';
const LIVE_DISPATCHER = 'dispatcher-ffff0006';   // 实时路径分发器
const LIVE_DELEGATE = 'delegate-probe-99990007';

const results = [];
const ok = (name, cond, extra = '') => {
  results.push([name, !!cond]);
  console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra ? `  — ${extra}` : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 快照行（activeAgentEntryJson 生产形态：agentId == sessionId）。 */
const snapshotRow = (sessionId, kind, status, agentName, project = '') => ({
  sessionId, agentId: sessionId, agentName, rootSessionId: ROOT_SID,
  kind, task: '', status, startedAt: Date.now() - 60000, retryCount: 0, project,
});

/** Boot index.html from disk with a MockWebSocket (production ws.js dispatch path). */
async function newPanelPage(browser, { colorScheme = 'light', viewport = { width: 1440, height: 900 }, tag = '' } = {}) {
  const p = await browser.newPage({ viewport, colorScheme });
  const errors = [];
  p.on('pageerror', (e) => errors.push({ text: `pageerror: ${e.message}`, url: '' }));
  p.on('console', (m) => {
    if (m.type() === 'error') errors.push({ text: `console.error: ${m.text()}`, url: m.location()?.url || '' });
  });
  await p.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__wsSent = [];
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send(d) { try { window.__wsSent.push(typeof d === 'string' ? JSON.parse(d) : d); } catch { window.__wsSent.push(d); } }
      close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });
  await p.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let pth = decodeURIComponent(url.pathname);
    if (pth === '/') pth = '/index.html';
    if (pth.startsWith('/api/')) return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    const file = normalize(join(WEB, pth));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await p.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await p.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await p.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await p.waitForTimeout(250);
  const df = (frame) => p.evaluate((f) => window.__wsMock.onmessage({ data: JSON.stringify(f) }), frame);
  await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
  await p.waitForTimeout(400);
  return { p, df, tag, errors };
}

/** 打开 Sub-Agents 面板（行内容只在 open 路径渲染 ⇒ 强制 close→open 再读）。 */
async function openPanel(p) {
  return p.evaluate(async () => {
    const ind = document.getElementById('bgagent-indicator');
    const d = document.getElementById('bgagent-dropdown');
    if (!ind || !d) return false;
    if (!d.classList.contains('hidden')) { ind.click(); await new Promise((r) => setTimeout(r, 120)); }
    for (let i = 0; i < 3 && d.classList.contains('hidden'); i++) { ind.click(); await new Promise((r) => setTimeout(r, 150)); }
    return !d.classList.contains('hidden');
  });
}

/** 规范读（DOM + 徽标 + 面板头 + 桶，一次取全）。 */
async function readPanel(p) {
  const opened = await openPanel(p);
  const r = await p.evaluate(async () => {
    const ind = document.getElementById('bgagent-indicator');
    const d = document.getElementById('bgagent-dropdown');
    const rows = [...document.querySelectorAll('#bgagent-dropdown .bg-task-row')].map((el) => ({
      key: el.getAttribute('data-bg-key') || '',
      nodeSid: el.getAttribute('data-node-session-id') || '',
      state: (el.querySelector('.bg-task-state')?.textContent || '').trim(),
      name: (el.querySelector('.bg-task-name')?.textContent || '').trim(),
      dotCls: el.querySelector('.bg-task-status')?.className || '',
    }));
    let bucket = null;
    try { const st = (await import('/js/state.js')).default; bucket = st.sessionBgAgents || {}; } catch { /* noop */ }
    const headerText = (d?.querySelector('.bg-dropdown-header')?.textContent || '').trim();
    const hm = headerText.match(/(\d+)\s*$/);
    return {
      dropdownHidden: d ? d.classList.contains('hidden') : null,
      indicatorHidden: ind ? ind.classList.contains('hidden') : null,
      badgeText: (ind?.querySelector('.bgagent-count')?.textContent || '').trim(),
      headerText,
      headerCount: hm ? Number(hm[1]) : null,
      emptyText: (d?.querySelector('.bg-dropdown-empty')?.textContent || '').trim(),
      rows,
      bucketKeys: Object.keys(bucket?.['e2e-subagentpanel-root'] || {}),
    };
  });
  r.opened = opened;
  // 徽标语义：count===0 时 updateBgAgentIndicator 只加 hidden、**不重置**文本 ⇒
  // 隐藏态的徽标文本是陈旧值，有效计数以「隐藏 ⇒ 0」为准（两值都记录，不掩盖）。
  r.badgeEffective = r.indicatorHidden ? 0 : (r.badgeText === '' ? null : Number(r.badgeText));
  return r;
}

const keysOf = (r) => r.rows.map((x) => x.key);

/** 通用片段：P1 隐藏 / P1b 桶不删 / P4 计数同桶同口径。 */
function assertHiddenAndCount(tag, r, expectedVisibleCount, hiddenKeys) {
  const keys = keysOf(r);
  ok(`[${tag}] P1 空闲 dispatcher- 行不在 DOM`, hiddenKeys.every((k) => !keys.includes(k)),
    `rowsKeys=${JSON.stringify(keys)}`);
  ok(`[${tag}] P1b 桶仍是原始全集（呈现口径不删桶）`,
    hiddenKeys.every((k) => r.bucketKeys.includes(k)), `bucketKeys=${JSON.stringify(r.bucketKeys)}`);
  ok(`[${tag}] P4 计数同桶同口径：徽标 == 面板头 == DOM 行数 == ${expectedVisibleCount}`,
    r.badgeEffective === expectedVisibleCount && r.headerCount === expectedVisibleCount && keys.length === expectedVisibleCount,
    `badge=${r.badgeEffective}(raw=${r.badgeText},hidden=${r.indicatorHidden}) header=${r.headerText} rows=${keys.length}`);
}

/** S1 场景整段断言（主题×视口矩阵复用）：通用片段 + P2/P3 正例。 */
function assertVisibleSet(tag, r, expectedKeys, hiddenKeys) {
  const keys = keysOf(r);
  assertHiddenAndCount(tag, r, expectedKeys.length, hiddenKeys);
  ok(`[${tag}] P2 运行中 dispatcher- 行在 DOM`, keys.includes(ACTIVE_DISPATCHER), `rowsKeys=${JSON.stringify(keys)}`);
  ok(`[${tag}] P3 非分发器行口径不变（node-/delegate-/subtask- 全在，含 Idle 的 node-）`,
    [IDLE_NODE, DELEGATE, SUBTASK].every((k) => keys.includes(k)), `rowsKeys=${JSON.stringify(keys)}`);
  ok(`[${tag}] P4b 徽标可见（有可见行时不得隐藏）`, r.indicatorHidden === false, `indicatorHidden=${r.indicatorHidden}`);
  ok(`[${tag}] P5b 有可见行时无空态文案`, r.emptyText === '', `emptyText=${JSON.stringify(r.emptyText)}`);
}

(async () => {
  console.log(`subagent-panel live-only probe — label=${LABEL} web=${WEB}`);
  const browser = await chromium.launch();
  const shot = async (p, name) => {
    if (!EVIDENCE) return;
    try { await p.screenshot({ path: join(EVIDENCE, `shot_${name}_${LABEL}.png`), fullPage: false }); } catch { /* noop */ }
  };
  try {
    // ══ S1：快照路径（空闲+运行中分发器 + node-/delegate-/subtask-）· 亮/暗 × 窄/中/宽 ══
    const matrix = [
      ['light', 'narrow', { width: 420, height: 900 }],
      ['light', 'mid', { width: 900, height: 900 }],
      ['light', 'wide', { width: 1440, height: 900 }],
      ['dark', 'narrow', { width: 420, height: 900 }],
      ['dark', 'mid', { width: 900, height: 900 }],
      ['dark', 'wide', { width: 1440, height: 900 }],
    ];
    for (const [scheme, size, viewport] of matrix) {
      const tag = `S1 ${scheme}/${size}`;
      const { p, df, errors } = await newPanelPage(browser, { colorScheme: scheme, viewport, tag });
      await df({
        type: 'activeAgents',
        agents: [
          snapshotRow(IDLE_DISPATCHER, 'Flow', 'Idle', 'dispatcher/ProjX', 'ProjX'),
          snapshotRow(ACTIVE_DISPATCHER, 'Flow', 'Processing', 'dispatcher/ProjX', 'ProjX'),
          snapshotRow(IDLE_NODE, 'Flow', 'Idle', '节点-A', 'ProjX'),
          snapshotRow(DELEGATE, 'Delegate', 'Processing', 'delegate/worker'),
          snapshotRow(SUBTASK, 'SubTask', 'Processing', 'subtask/worker'),
        ],
      });
      await sleep(350);
      const r = await readPanel(p);
      console.log(`  [read ${tag}] ${JSON.stringify({ badge: r.badgeEffective, header: r.headerText, rows: keysOf(r), bucket: r.bucketKeys, empty: r.emptyText })}`);
      assertVisibleSet(tag, r, [ACTIVE_DISPATCHER, IDLE_NODE, DELEGATE, SUBTASK], [IDLE_DISPATCHER]);
      const real = errors.filter((e) => !e.url.includes('/api/'));
      ok(`[${tag}] 零真实 console/page 错误`, real.length === 0, real.slice(0, 2).map((e) => e.text).join(' ; '));
      await shot(p, `s1_${scheme}_${size}`);
      await p.close();
    }

    // ══ S2：空闲分发器独占桶 ⇒ 行 0 / 计数 0 / 空态出现（空态与画面自洽）══
    for (const [scheme, size, viewport] of [['light', 'narrow', { width: 420, height: 900 }], ['dark', 'wide', { width: 1440, height: 900 }]]) {
      const tag = `S2 ${scheme}/${size}`;
      const { p, df, errors } = await newPanelPage(browser, { colorScheme: scheme, viewport, tag });
      await df({ type: 'activeAgents', agents: [snapshotRow(IDLE_DISPATCHER, 'Flow', 'Idle', 'dispatcher/ProjX', 'ProjX')] });
      await sleep(350);
      const r = await readPanel(p);
      console.log(`  [read ${tag}] ${JSON.stringify({ badge: r.badgeEffective, header: r.headerText, rows: keysOf(r), bucket: r.bucketKeys, empty: r.emptyText })}`);
      assertHiddenAndCount(tag, r, 0, [IDLE_DISPATCHER]);
      ok(`[${tag}] P5 徽标隐藏（空闲零计入 ⇒ 无可见行）`, r.indicatorHidden === true, `indicatorHidden=${r.indicatorHidden}`);
      ok(`[${tag}] P5 空态文案出现且非空`, r.emptyText.length > 0, `emptyText=${JSON.stringify(r.emptyText)}`);
      ok(`[${tag}] P5c 空态文案即「无运行中」语义（与计数 0 自洽）`, r.emptyText === '当前无运行中的子智能体',
        `emptyText=${JSON.stringify(r.emptyText)}`);
      const real = errors.filter((e) => !e.url.includes('/api/'));
      ok(`[${tag}] 零真实 console/page 错误`, real.length === 0, real.slice(0, 2).map((e) => e.text).join(' ; '));
      await shot(p, `s2_${scheme}_${size}`);
      await p.close();
    }

    // ══ S3：实时路径（agentStart / agentDone）══
    {
      const tag = 'S3 live light/wide';
      const { p, df, errors } = await newPanelPage(browser, { colorScheme: 'light', viewport: { width: 1440, height: 900 }, tag });
      // 实时帧形态：agentId = 子会话 id（ActorStart json），routeSubagentWsSend 注入归桶键。
      await df({
        type: 'agentStart', agentId: LIVE_DISPATCHER, name: 'dispatcher/ProjX', agentType: 'ProjectDispatcher',
        rootSessionId: ROOT_SID, sessionId: ROOT_SID, nodeSessionId: LIVE_DISPATCHER, project: 'ProjX', taskDescription: '分发器探针',
      });
      await df({
        type: 'agentStart', agentId: LIVE_DELEGATE, name: 'delegate/probe', agentType: 'Delegate',
        rootSessionId: ROOT_SID, sessionId: ROOT_SID, nodeSessionId: LIVE_DELEGATE, taskDescription: '子代理探针',
      });
      await sleep(350);
      const rLive = await readPanel(p);
      console.log(`  [read ${tag} run] ${JSON.stringify({ badge: rLive.badgeEffective, header: rLive.headerText, rows: keysOf(rLive), states: rLive.rows.map((x) => x.state) })}`);
      ok(`[${tag}] P6 运行中分发器行可见且计入`, keysOf(rLive).includes(LIVE_DISPATCHER) && rLive.badgeEffective === 2 && rLive.headerCount === 2,
        `rows=${JSON.stringify(keysOf(rLive))} badge=${rLive.badgeEffective} header=${rLive.headerText}`);
      ok(`[${tag}] P6 运行中行状态字为「进行中」（subagents.status.running）`, rLive.rows.find((x) => x.key === LIVE_DISPATCHER)?.state === '进行中',
        `state=${rLive.rows.find((x) => x.key === LIVE_DISPATCHER)?.state}`);
      // agentDone（2s 保留窗内）：done 行不得计入（口径边界 ①）
      await df({ type: 'agentDone', agentId: LIVE_DISPATCHER, nodeSessionId: LIVE_DISPATCHER, rootSessionId: ROOT_SID, sessionId: ROOT_SID });
      const rDone = await readPanel(p);
      console.log(`  [read ${tag} done] ${JSON.stringify({ badge: rDone.badgeEffective, header: rDone.headerText, rows: keysOf(rDone), states: rDone.rows.map((x) => x.state) })}`);
      ok(`[${tag}] P6b agentDone 后（2s 窗内）分发器行不计入且不显示`,
        !keysOf(rDone).includes(LIVE_DISPATCHER) && rDone.badgeEffective === 1 && rDone.headerCount === 1,
        `rows=${JSON.stringify(keysOf(rDone))} badge=${rDone.badgeEffective} header=${rDone.headerText}`);
      ok(`[${tag}] P6b 同批 delegate- 行不受影响`, keysOf(rDone).includes(LIVE_DELEGATE), `rows=${JSON.stringify(keysOf(rDone))}`);
      // 2s 后桶内清除
      await sleep(2400);
      const rGone = await readPanel(p);
      ok(`[${tag}] P6c 2s 后 done 行从桶中退场`, rGone.bucketKeys.length === 1 && rGone.bucketKeys.includes(LIVE_DELEGATE) && rGone.badgeEffective === 1,
        `bucketKeys=${JSON.stringify(rGone.bucketKeys)} badge=${rGone.badgeEffective}`);
      const real = errors.filter((e) => !e.url.includes('/api/'));
      ok(`[${tag}] 零真实 console/page 错误`, real.length === 0, real.slice(0, 2).map((e) => e.text).join(' ; '));
      await shot(p, 's3_live_light_wide');
      await p.close();
    }
  } finally {
    await browser.close();
  }
  const failed = results.filter(([, c]) => !c);
  console.log(`\n[${LABEL}] ${results.length - failed.length}/${results.length} assertions passed`);
  if (failed.length) {
    console.log('FAILED assertions:');
    for (const [n] of failed) console.log(`  - ${n}`);
  }
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
