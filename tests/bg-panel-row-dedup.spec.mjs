// bg-panel-row-dedup.spec.mjs — 后台智能体面板「同一会话重复显示成多行」修复批
// （2026-11-02 · 依据定稿 RCA reports/20261102_191631_agent-panel-dup-rows）。
//
// 症状（作者 16:36:50 截图）：面板里两行一模一样的 `[节点] stand conversation-heads`，
// 引擎侧只有一个会话；四行计数「智能体 ·4」里含同一个实体的两次出现。
// 根因：bgPanelModel() 只做组内去重（Pass3 的 present 集合是每组一份），没有任何
// **跨通路**的 sessionId 全局收敛 —— 同一个会话既能作为某组头的链子行（作用域桶
// 通路），又能作为主桶 leftover（快照/live 通路）再出现一次；total 又是
// `entries.length + chainChildren` 另算的一套账。
//
// 断言面（任务书 H1-H7，机械读数）：
//   H1 改前红 …… 同 sessionId 两条通路（作用域桶链行 + 快照行）⇒ 两行并存
//       （同一 data-bg-key 出现两次）。
//   H2 改后绿 …… 同一探针 ⇒ 一行且 data-bg-key 唯一。
//   H3 计数同源 …… 面板头 total == 实际渲染行数（五种通路组合）。
//   H4 组头/子行可辨 …… data-bg-row-level 标识 + 视觉读数（缩进/强调条/字号）。
//   H5 不误伤 …… 三个不同 sessionId ⇒ 三行（反向门）。
//   H6 长空闲行 …… 多条长空闲行连续两帧顺序一致（并列规则固定）。
//   H7 可见性解耦 …… 标签页隐藏时行集不被错误淘汰；淘汰判据只看数据不看可见性；
//       面板开着时后台也照常取快照（静态钉 + 行为读数）。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows），零后端、零 sbt。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-panel-row-dedup.spec.mjs

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

const ROOT_SID = 'dedupe-root-session';
const DELEGATE_SID = 'delegate-dedup-a1b2c3d4';
// RCA 截图的同族会话（conversation-heads 节点）。
const NODE_SID = 'node-c329d28b';
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

/** 作用域桶链行（真实 routeSubagentWsSend 形：rootSessionId = delegateSid 自根）。 */
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
    taskNo: '91', delegateId: DELEGATE_SID, title: '分发器链批次——回流卡收编实施',
    name: '链批', executor: 'nebflow', status: 'completed', external: false,
    project: '', createdAt: T0 - 66 * 60_000, orchestrationMode: 'agentflow',
    mode: 'dispatcher', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    ...over,
  };
}

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

/** 装 stub 夹具（rows 读口）。 */
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

/** 行集 dump（H1/H2/H4/H6 的机械读数面）：每行的层级标识 / bg-key / 会话 id /
 *  状态词 / 起点戳 / 关键视觉读数。 */
function dumpRows(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    return [...list.children].filter((el) => el.classList.contains('bg-task-row')).map((el) => {
      const cs = getComputedStyle(el);
      const nameEl = el.querySelector('.bg-task-name');
      const nameCs = nameEl ? getComputedStyle(nameEl) : null;
      const upEl = el.querySelector('.bg-task-uptime');
      return {
        cls: el.className,
        level: el.getAttribute('data-bg-row-level') || '',
        bgKey: el.getAttribute('data-bg-key') || '',
        childKey: el.getAttribute('data-child-session') || '',
        sid: (el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || ''),
        state: (el.querySelector('.bg-task-state') || {}).textContent || '',
        name: nameEl ? nameEl.textContent : '',
        startedAt: upEl ? (upEl.getAttribute('data-started-at') || '') : '',
        padLeft: parseFloat(cs.paddingLeft),
        borderLeftWidth: parseFloat(cs.borderLeftWidth) || 0,
        borderLeftColor: cs.borderLeftColor,
        boxShadow: cs.boxShadow,
        nameFontSize: nameCs ? nameCs.fontSize : '',
        nameFontWeight: nameCs ? nameCs.fontWeight : '',
      };
    });
  }, LIST);
}

/** 面板头部计数（模型单点写点）+ 徽标 + 节签。 */
async function panelCounts(page) {
  return page.evaluate(() => ({
    header: (document.querySelector('#bgagent-dropdown .bg-dropdown-header') || {}).textContent || '',
    badge: (document.querySelector('#bgagent-indicator .bgagent-count') || {}).textContent || '',
    section: (document.querySelector('#bg-dropdown .mh-bg-grp') || {}).textContent || '',
    rowTotal: (document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list') || {}).getAttribute('data-bg-row-total') || '',
  }));
}

/** 采集页面错误（pageerror + console.error）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

// ── H1 / H2：跨通路同 sessionId 收敛 ───────────────────────────────────────
//
// 探针 = RCA §6 建议的两条通路各投一份同一 sessionId：
//   通路①（作用域桶 / 链子行）：agentStart 以 rootSessionId=delegateSid 自根 →
//     落桶 sessionBgAgents[delegateSid] → 经 bgDelegateScopeChainRows 作为组头的
//     链子行出现；
//   通路②（快照 / 主桶 leftover）：activeAgents 快照行以 rootSessionId=主会话 →
//     落主桶 → 无主认领 → 顶层 leftover 行。
// 改前：两行并存（同一 data-bg-key 出现两次）；改后：一行且 data-bg-key 唯一。

test('H1+H2 跨通路同 sessionId 收敛：作用域桶链行 + 快照行 ⇒ 同一会话只占一行', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'running' })] });
  // delegate live 组头（主桶）——链子行的挂载父。
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer', taskDescription: '分发器链批次——回流卡收编实施' }));
  // 通路①：作用域桶链行（真 spawn 起点 = RCA 13:06:44 同族形态，相对 T0 表达）。
  await inject(page, chainStart(NODE_SID, {
    status: 'Processing', name: 'stand', taskDescription: '【stand主仓 ·大件：conversation-heads',
  }));
  await page.evaluate(async (startedAt) => {
    // 链行起点戳落到真实 spawn 时刻。
    const s = (await import('/js/state.js')).default;
    const b = s.sessionBgAgents['delegate-dedup-a1b2c3d4'] || {};
    for (const k of Object.keys(b)) b[k].startedAt = startedAt;
  }, T0 - 3.5 * 3600_000);
  // 通路②：快照行（同一 sessionId，主桶；startedAt 为「重建」的较晚时刻）。
  await inject(page, {
    type: 'activeAgents',
    agents: [{
      sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: ROOT_SID,
      kind: 'Flow', status: 'Processing', startedAt: T0 - 22 * 60_000,
      project: 'Nebflow', parentSessionId: '',
      task: '【stand主仓 ·大件：conversation-heads',
    }],
  });
  await openPanel(page);

  const rows = await dumpRows(page);
  // 断言的机械读数先落盘（改前红 / 改后绿同一份 dump）。
  console.log('[H1/H2 dump] ' + JSON.stringify(rows.map((r) => ({
    level: r.level, bgKey: r.bgKey, sid: r.sid, state: r.state, startedAt: r.startedAt,
  }))));
  const nodeRows = rows.filter((r) => r.sid === NODE_SID);
  const keys = nodeRows.map((r) => r.bgKey);
  console.log('[H1/H2] 同 sessionId 行数=' + nodeRows.length + ' data-bg-key=' + JSON.stringify(keys));

  // H2：同一 sessionId 只有一行，且 data-bg-key 唯一。
  expect(nodeRows).toHaveLength(1);
  expect(new Set(keys).size).toBe(1);
  // 全表 data-bg-key 唯一（无第二条孪生）。
  const allKeys = rows.map((r) => r.bgKey).filter(Boolean);
  expect(new Set(allKeys).size).toBe(allKeys.length);
  // 收敛后保留真实 spawn 起点（最早 startedAt——RCA「保留 startedAt 最早者」）。
  expect(Number(nodeRows[0].startedAt)).toBe(T0 - 3.5 * 3600_000);
  // 收敛落点 = 组内链子行（层级归属不因去重而丢）。
  expect(nodeRows[0].level).toBe('child');
  expect(errors).toEqual([]);
});

// ── H3：计数与行集同源 ────────────────────────────────────────────────────

test('H3 计数同源：面板头 total == 实际渲染行数（五种通路组合）', async ({ page }) => {
  const errors = trackErrors(page);
  const combos = [];

  // 组合①：仅作用域桶（无主桶 live 行——合成延续组头承镜像 + 链子行）。
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'completed' })] });
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'node-eb2a5aa3', agentId: 'node-eb2a5aa3', agentName: '实现节点', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 6 * 60_000, project: 'Nebflow', parentSessionId: '' },
    ],
  });
  await openPanel(page);
  // Active: the two Processing nodes (the synthesized head has no state).
  combos.push({ name: '仅作用域桶', active: 2, rows: (await dumpRows(page)).length, counts: await panelCounts(page) });

  // 组合②：仅快照（主桶两行，无链）。
  await bootApp(page);
  await setFixture(page, { rows: [] });
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'delegate-dedup-zzz', agentId: 'delegate-dedup-zzz', agentName: '批次Z', rootSessionId: ROOT_SID, kind: 'Delegate', status: 'Processing', startedAt: T0 - 5 * 60_000, project: '', parentSessionId: '' },
    ],
  });
  await openPanel(page);
  combos.push({ name: '仅快照', active: 2, rows: (await dumpRows(page)).length, counts: await panelCounts(page) });

  // 组合③：两者同时（H1 探针形态——去重是计数的前提）。
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'running' })] });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer' }));
  await inject(page, chainStart(NODE_SID, { status: 'Processing', name: 'stand' }));
  await inject(page, {
    type: 'activeAgents',
    agents: [{ sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 22 * 60_000, project: 'Nebflow', parentSessionId: '' }],
  });
  await openPanel(page);
  combos.push({ name: '两者同时', active: 2, rows: (await dumpRows(page)).length, counts: await panelCounts(page) });

  // 组合④：含链子行（live 组头 + 三条链行）。
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'running' })] });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer' }));
  await inject(page, chainStart('dispatcher-20b0e8ea', { name: 'dispatcher/Nebflow' }));
  await inject(page, chainStart(NODE_SID, { status: 'Processing' }));
  await inject(page, chainStart('node-eb2a5aa3', { status: 'Processing' }));
  await openPanel(page);
  combos.push({ name: '含链子行', active: 4, rows: (await dumpRows(page)).length, counts: await panelCounts(page) });

  // 组合⑤：含合成组头（主桶空 + 注册行镜像 + 作用域桶链行）。
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'completed' })] });
  await inject(page, {
    type: 'activeAgents',
    agents: [{ sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' }],
  });
  await openPanel(page);
  // Active: the one Processing node (the synthesized head has no state).
  combos.push({ name: '含合成组头', active: 1, rows: (await dumpRows(page)).length, counts: await panelCounts(page) });

  console.log('[H3 combos] ' + JSON.stringify(combos));

  // 计数口径（2026-11-23 作者裁定）：头部 / 智能体签 = running 数（实际并行在跑，夹具定义
  // 于 combo.active）；徽标同取 running；data-bg-row-total = 行集长度（≥ 可见行，折叠子树
  // 计入行集但不出镜）。四读数同源于同一行集，口径各自名实相符。
  for (const c of combos) {
    const headTotal = Number(String(c.counts.header).replace(/[^0-9]/g, ''));
    expect(headTotal).toBe(c.active);
    expect(String(c.counts.badge).trim()).toBe(String(c.active));
    expect(String(c.counts.section)).toContain('· ' + c.active);
    expect(Number(c.counts.rowTotal)).toBeGreaterThanOrEqual(c.rows);
  }
  expect(errors).toEqual([]);
});

// ── H4：组头 / 子行视觉可辨 ───────────────────────────────────────────────

test('H4 组头与子行可辨：data-bg-row-level 标识 + 缩进/强调条/字号实测差异', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'running' })] });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer', taskDescription: '分发器链批次' }));
  await inject(page, chainStart(NODE_SID, { status: 'Processing', name: 'stand' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[H4 dump] ' + JSON.stringify(rows.map((r) => ({
    level: r.level, padLeft: r.padLeft, borderLeftWidth: r.borderLeftWidth,
    boxShadow: r.boxShadow, nameFontSize: r.nameFontSize, nameFontWeight: r.nameFontWeight,
  })), null, 1));

  const head = rows.find((r) => r.level === 'head');
  const child = rows.find((r) => r.level === 'child');
  expect(head).toBeTruthy();
  expect(child).toBeTruthy();
  // DOM 可区分标识：data-bg-row-level 值不同（class 另有两级类，双保险）。
  expect(head.cls).toContain('bg-task-row-head');
  expect(child.cls).toContain('bg-task-row-child');
  expect(head.level).not.toBe(child.level);
  // 视觉差异实测（三读数全不同）：
  //   ① 组头左侧 3px 强调条（inset 阴影，零布局位移）vs 子行 2px 引导竖线；
  //   ② 缩进——子行 paddingLeft > 组头；
  //   ③ 字号/字重档——组头主名 > 子行主名，组头中度字重。
  expect(head.boxShadow).toContain('inset');
  expect(head.boxShadow).toContain('3px');
  expect(child.borderLeftWidth).toBeGreaterThanOrEqual(2);
  expect(child.padLeft).toBeGreaterThan(head.padLeft);
  expect(parseFloat(head.nameFontSize)).toBeGreaterThan(parseFloat(child.nameFontSize));
  expect(head.nameFontWeight).not.toBe(child.nameFontWeight);
  expect(errors).toEqual([]);
});

// ── H5：不误伤真实多节点（反向门）──────────────────────────────────────────

test('H5 不误伤真实多节点：三个不同 sessionId 的链节点 ⇒ 三行', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'running' })] });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer' }));
  await inject(page, chainStart('node-aaaa1111', { status: 'Processing' }));
  await inject(page, chainStart('node-bbbb2222', { status: 'Processing' }));
  await inject(page, chainStart('node-cccc3333', { status: 'Processing' }));
  await openPanel(page);

  const rows = await dumpRows(page);
  const kids = rows.filter((r) => r.level === 'child');
  console.log('[H5] child rows=' + kids.length + ' sids=' + JSON.stringify(kids.map((r) => r.sid)));
  expect(kids).toHaveLength(3);
  expect(new Set(kids.map((r) => r.sid))).toEqual(new Set(['node-aaaa1111', 'node-bbbb2222', 'node-cccc3333']));
  const counts = await panelCounts(page);
  expect(Number(String(counts.badge).trim())).toBe(4); // 组头 + 三条链行
  expect(errors).toEqual([]);
});

// ── H6：长空闲行并列语义稳定 ──────────────────────────────────────────────

test('H6 长空闲行：多条长空闲行连续两帧顺序一致（并列规则固定，不每帧变）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [] });
  const longIdleAgo = Date.now() - 45 * 60_000;
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: 'delegate-idle-aaaa', agentId: 'delegate-idle-aaaa', agentName: '批次A', rootSessionId: ROOT_SID, kind: 'Delegate', status: 'Idle', startedAt: T0 - 5 * 3600_000, project: '', parentSessionId: '', lastActivityMs: longIdleAgo },
      { sessionId: 'node-idle-bbbb', agentId: 'node-idle-bbbb', agentName: '节点B', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Idle', startedAt: T0 - 4 * 3600_000, project: '', parentSessionId: '', lastActivityMs: longIdleAgo },
      { sessionId: 'node-idle-cccc', agentId: 'node-idle-cccc', agentName: '节点C', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Idle', startedAt: T0 - 3 * 3600_000, project: '', parentSessionId: '', lastActivityMs: longIdleAgo },
    ],
  });
  await openPanel(page);

  const frame1 = await dumpRows(page);
  // 长空闲行确实处于长空闲态（次行「空闲 N 分钟，无在飞工作」）。
  const idleTexts = await page.evaluate((sel) => [...document.querySelectorAll(sel + ' .bg-task-idle-detail')].map((el) => el.textContent), LIST);
  console.log('[H6 frame1] ' + JSON.stringify(frame1.map((r) => r.sid)) + ' idle=' + JSON.stringify(idleTexts));
  expect(idleTexts.length).toBe(3);
  expect(idleTexts.every((x) => /空闲 \d+ 分钟/.test(x))).toBe(true);

  // 第二/三帧：重渲染（同一数据面），顺序必须逐位一致。
  await page.evaluate(async () => {
    const m = await import('/js/main/bgAgents.js');
    m.renderBgAgentDropdown();
  });
  const frame2 = await dumpRows(page);
  await page.evaluate(async () => {
    const m = await import('/js/main/bgAgents.js');
    m.renderBgAgentDropdown();
  });
  const frame3 = await dumpRows(page);
  const order = (rs) => rs.map((r) => r.sid || r.bgKey);
  console.log('[H6 order] f1=' + JSON.stringify(order(frame1)) + ' f2=' + JSON.stringify(order(frame2)) + ' f3=' + JSON.stringify(order(frame3)));
  expect(order(frame2)).toEqual(order(frame1));
  expect(order(frame3)).toEqual(order(frame1));
  expect(errors).toEqual([]);
});

// ── H7：淘汰与标签页可见性解耦 ────────────────────────────────────────────

test('H7 可见性解耦：行集不因标签页隐藏被错误淘汰；淘汰判据只看数据', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [] });
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'node-eb2a5aa3', agentId: 'node-eb2a5aa3', agentName: '实现节点', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 6 * 60_000, project: 'Nebflow', parentSessionId: '' },
    ],
  });
  await openPanel(page);
  const before = await dumpRows(page);
  expect(before).toHaveLength(2);

  // 进后台（visibilityState=hidden 覆盖 + visibilitychange 事件）。
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  // 后台期间快照照常佐证两行（不淘汰）。
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: NODE_SID, agentId: NODE_SID, agentName: 'stand', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'node-eb2a5aa3', agentId: 'node-eb2a5aa3', agentName: '实现节点', rootSessionId: ROOT_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 6 * 60_000, project: 'Nebflow', parentSessionId: '' },
    ],
  });
  const hidden = await dumpRows(page);
  console.log('[H7] before=' + before.length + ' hidden=' + hidden.length);
  expect(hidden).toHaveLength(2);

  // 回到前台：行集原样（不被错误淘汰）。
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.waitForTimeout(150);
  const after = await dumpRows(page);
  console.log('[H7] after=' + after.length + ' sids=' + JSON.stringify(after.map((r) => r.sid)));
  expect(after.map((r) => r.sid)).toEqual(before.map((r) => r.sid));

  // 反向门：淘汰判据只看数据（连续未命中快照 + 陈旧窗），不看标签页可见性——
  // 隐藏状态下过期行照常被淘汰（不是「后台就永不淘汰」的另一种坏）。
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  // 第一拍快照不列它 ⇒ 记 _missSince；把时钟推过陈旧窗（BG_SNAPSHOT_STALE_MS=30s），
  // 第二拍快照仍不列 ⇒ 淘汰。
  await inject(page, { type: 'activeAgents', agents: [] });
  const miss1 = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    const bucket = s.sessionBgAgents['dedupe-root-session'] || {};
    return Object.entries(bucket).filter(([, v]) => v._missSince != null).length;
  });
  await page.evaluate(() => {
    const real = Date.now;
    // eslint-disable-next-line no-global-assign
    Date.now = () => real() + 31_000;
  });
  await inject(page, { type: 'activeAgents', agents: [] });
  const staleLeft = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return Object.keys(s.sessionBgAgents['dedupe-root-session'] || {}).length;
  });
  console.log('[H7] missMarked=' + miss1 + ' staleEvictedLeft=' + staleLeft);
  expect(miss1).toBeGreaterThan(0);
  expect(staleLeft).toBe(0);

  // 静态钉：面板开着时的快照回填不再按标签页可见性门控（解耦单点）——
  // 断言代码行本身（注释里叙旧提到 visibilityState 不算）。
  const { readFileSync: rfs } = await import('node:fs');
  const bgSrc = rfs(join(WEB, 'js', 'main', 'bgAgents.js'), 'utf8');
  const tick = bgSrc.slice(bgSrc.indexOf('export function armBgUptimeTick'), bgSrc.indexOf('// ── delegate-ui 批'));
  const codeLines = tick.split('\n').filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l));
  const code = codeLines.join('\n');
  console.log('[H7] armBgUptimeTick 代码行含 visibilityState=' + /visibilityState/.test(code));
  expect(/visibilityState/.test(code)).toBe(false);
  expect(/if \(\(\+\+bgUptimeTicks % 2\) === 0\) \{\s*\n\s*fetchActiveAgentsOnOpen\(\);/.test(code)).toBe(true);
  expect(errors).toEqual([]);
});
