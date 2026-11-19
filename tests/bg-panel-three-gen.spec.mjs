// bg-panel-three-gen.spec.mjs — 面板代际层级批（2026-11-06 · root → dispatcher → node
// 三级真缩进 + 野节点三态判定表收尾）。
//
// 作者令（复活续作令 2026-11-06 14:1x 摘要重申）：
//   G1 三层夹具（root 锚 + dispatcher + node，parentSessionId 属主边）depth=0/1/2，
//      getComputedStyle padding-left 与令牌公式一致（chat.css：16 + (depth-1)*16 px
//      ——375 / 1440 各读一次）；
//   G2 真实形夹具（同结构但 dispatcher-*/node-* **无 parentSessionId**——现网数据面
//      形状）行一行不少、depth 不伪造（野组归置既有口径）；
//   G3 bgDepsParentPolicy() 结论 = false 且 deps 边不产生缩进（深度唯一来源 = 白名单）；
//   G4 三态各一例落位符合判定表（bgTopRowParentStateOf 单点注释）：
//      ① 有父且在册 → 真子行（挂 dispatcher 行下 depth2，无任何标）；
//      ② 有父但不在场 → 父应在位置（挂 root 锚直下 depth1）+「父不在场」标，
//        **不进归置组**；
//      ③ 无父字段 → 既有 data-bg-orphan-group + data-bg-orphan="1"。
//   G5 折叠 dispatcher 行 → node 子树整棵隐藏、展开恢复、刷新持久（localStorage
//      同一把缝）；附 root 锚折叠 = 根下整棵子树隐藏（基座收起语义）。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows/chains），零后端、零 sbt、零 6138。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-panel-three-gen.spec.mjs --reporter=line

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

const ROOT_SID = 'threegen-root-session';
// 分发器代（dispatcher 行——属主 = 桶根，depth 1）。
const DISP_SID = 'dispatcher-3gen-a001';
// 节点代（node 行——属主 = dispatcher，depth 2）。
const NODE_SID = 'node-3gen-b002';
// G4 三态夹具：父不在场的节点代（父 = 已离场 dispatcher，不在行集、不在册）。
const ABSENT_NODE_SID = 'node-3gen-c003';
const ABSENT_PARENT_SID = 'dispatcher-3gen-gone9';
// G4 三态夹具：无父字段的节点代（现网旧形状——落野组）。
const NOPARENT_NODE_SID = 'node-3gen-d004';
// G3 夹具：整体行（delegate live 行）+ flow 结构投影（deps 边）。
const DELEGATE_SID = 'delegate-3gen-e005';
const T0 = Date.now();

/** 主桶行（dispatcher/node 顶层行——rootStart 形：sessionId = 桶根）。 */
function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 注册行镜像夹具（DelegateRow 形）。 */
function mirrorRow(over = {}) {
  return {
    taskNo: '95', delegateId: DELEGATE_SID, title: '面板代际层级批次',
    name: '三代批', executor: 'nebflow', status: 'running', external: false,
    project: '', createdAt: T0 - 30 * 60_000, orchestrationMode: 'agentflow',
    mode: 'dispatcher', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    ...over,
  };
}

async function installRoutes(page, locale = 'zh-CN') {
  await page.addInitScript((locale) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', locale);
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
}

async function waitReady(page) {
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function bootApp(page, locale = 'zh-CN') {
  await installRoutes(page, locale);
  await page.goto('http://localhost:1/');
  await waitReady(page);
}

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 装 stub 夹具（rows / chains 读口）。 */
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

/**
 * G1/G5 三层树种子（真实属主边，后端注册形状）：
 *   dispatcher 行（parentSessionId = 桶根 = ProjectActor.spawnDispatcher A① 形）→
 *   node 行（parentSessionId = dispatcher = NodeStarter/NodeLoopRunner A② 形）。
 */
async function seedThreeGen(page) {
  await setFixture(page, { rows: [] });
  await inject(page, rootStart(DISP_SID, {
    status: 'Processing', name: 'dispatcher/Nebflow',
    taskDescription: '分发器代', parentSessionId: ROOT_SID,
  }));
  await inject(page, rootStart(NODE_SID, {
    status: 'Processing', name: 'stand',
    taskDescription: '节点代', parentSessionId: DISP_SID,
  }));
  // 起点戳固定（并列定序 = startedAt 升序 ⇒ 树序与行序同向：dispatcher 打头）。
  await page.evaluate(async ({ r, d, n }) => {
    const s = (await import('/js/state.js')).default;
    const b = s.sessionBgAgents[r] || {};
    if (b[d]) b[d].startedAt = 1000;
    if (b[n]) b[n].startedAt = 2000;
  }, { r: ROOT_SID, d: DISP_SID, n: NODE_SID });
}

/** 行集 dump（机械读数面）：行 + root 锚 + 野组 + 计数。 */
function dumpPanel(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return { rows: [], anchor: null, group: null, rowTotal: '', header: '' };
    const kids = [...list.children];
    const rows = kids.filter((el) => el.classList.contains('bg-task-row')).map((el) => {
      const cs = getComputedStyle(el);
      const caretEl = el.querySelector('[data-bg-caret]');
      const absentChip = el.querySelector('[data-bg-absent-parent]');
      return {
        level: el.getAttribute('data-bg-row-level') || '',
        depth: el.getAttribute('data-bg-depth'),
        padLeft: parseFloat(cs.paddingLeft),
        collapsed: el.getAttribute('data-bg-collapsed'),
        caretKey: caretEl ? (caretEl.getAttribute('data-bg-caret') || '') : '',
        caretCollapsed: caretEl ? caretEl.classList.contains('is-collapsed') : null,
        orphan: el.getAttribute('data-bg-orphan'),
        absentParent: el.getAttribute('data-bg-parent-absent'),
        agentflow: el.getAttribute('data-bg-agentflow'),
        sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
        name: (el.querySelector('.bg-task-name') || {}).textContent || '',
        origin: (el.querySelector('.bg-task-orphan') || {}).textContent || '',
        absentChip: absentChip ? absentChip.textContent : '',
        absentChipTitle: absentChip ? absentChip.getAttribute('title') : '',
        scrollHeight: el.scrollHeight,
      };
    });
    const anchorEl = kids.find((el) => el.hasAttribute('data-bg-root-anchor'));
    const anchor = anchorEl ? {
      depth: anchorEl.getAttribute('data-bg-depth'),
      text: (anchorEl.querySelector('.bg-root-anchor-name') || {}).textContent || '',
      isRow: anchorEl.classList.contains('bg-task-row'),
      caretKey: (anchorEl.querySelector('[data-bg-caret]') || {}).getAttribute
        ? anchorEl.querySelector('[data-bg-caret]').getAttribute('data-bg-caret') : '',
      collapsed: anchorEl.getAttribute('data-bg-collapsed'),
      childCount: anchorEl.querySelectorAll('.bg-task-row').length,
    } : null;
    const groupEl = kids.find((el) => el.hasAttribute('data-bg-orphan-group'));
    const group = groupEl ? { idx: kids.indexOf(groupEl), depth: groupEl.getAttribute('data-bg-depth') } : null;
    return {
      rows,
      anchor,
      group,
      rowTotal: list.getAttribute('data-bg-row-total') || '',
      header: (document.querySelector('#bgagent-dropdown .bg-dropdown-header') || {}).textContent || '',
    };
  }, LIST);
}

/** 采集页面错误（pageerror + console.error）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

// ── G1：三层真缩进（depth 0/1/2 + 令牌公式 px 读数，1440 桌面 / 375 手机档各一次）──
// 375 ≤ 768px ⇒ 手机交互层自动激活（bootstrap.js MOBILE_MQ）——面板经 navController
// 收编进后台页（overlayFaceByPage.background = '#bg-dropdown'，同一实现只改一份），
// 该腿 = 手机档矩阵抽查：缩进随手机档公式（mpad-x 基准 + 逐层 --ds-space-3）。

test('G1 三层夹具：root 锚 depth0 + dispatcher depth1 + node depth2，1440 桌面 16/32px + 375 手机档同构', async ({ page }) => {
  const errors = trackErrors(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await bootApp(page);
  await seedThreeGen(page);
  await openPanel(page);

  // Flat list rows expose the real depth to assistive technology, without invalid aria-level on listitem.
  const dispatcher = page.locator(`.bg-task-row[data-node-session-id="${DISP_SID}"]`);
  const node = page.locator(`.bg-task-row[data-node-session-id="${NODE_SID}"]`);
  await expect(page.locator('.bg-root-anchor .bg-hierarchy-sr')).toHaveText('层级 1');
  await expect(dispatcher.locator('.bg-hierarchy-sr')).toHaveText('层级 2');
  await expect(node.locator('.bg-hierarchy-sr')).toHaveText('层级 3');
  const caret = dispatcher.getByRole('button', { name: '收起子任务' });
  await expect(caret).toHaveAttribute('aria-expanded', 'true');
  await caret.focus();
  await page.keyboard.press('Enter');
  const expandedCaret = dispatcher.getByRole('button', { name: '展开子任务' });
  await expect(expandedCaret).toBeFocused();
  await expect(expandedCaret).toHaveAttribute('aria-expanded', 'false');
  await expect(node).toHaveCount(0);
  await page.keyboard.press('Space');
  await expect(node).toHaveCount(1);
  await expect(dispatcher.getByRole('button', { name: '收起子任务' })).toBeFocused();

  // 1440 桌面读数：公式 = --ds-space-4 + (depth-1)*--ds-space-4 ⇒ depth1=16px、depth2=32px。
  const dump1440 = await dumpPanel(page);
  const disp1440 = dump1440.rows.find((r) => r.sid === DISP_SID);
  const node1440 = dump1440.rows.find((r) => r.sid === NODE_SID);
  console.log('[G1 1440] ' + JSON.stringify({ anchor: dump1440.anchor, rows: dump1440.rows.map((r) => ({ sid: r.sid, level: r.level, depth: r.depth, padLeft: r.padLeft })) }));
  // root 锚：标签面行（不是 .bg-task-row）、depth 0、caret 键 = 桶根会话 id。
  expect(dump1440.anchor).toBeTruthy();
  expect(dump1440.anchor.isRow).toBe(false);
  expect(dump1440.anchor.depth).toBe('0');
  expect(dump1440.anchor.caretKey).toBe(ROOT_SID);
  // dispatcher 行：depth 1，padding-left = --ds-space-4 = 16px（令牌公式读数）。
  expect(disp1440).toBeTruthy();
  expect(disp1440.level).toBe('top');
  expect(disp1440.depth).toBe('1');
  expect(disp1440.padLeft).toBe(16);
  // node 行：depth 2，padding-left = 16 + 16 = 32px（同一公式，零新几何常量）。
  expect(node1440).toBeTruthy();
  expect(node1440.level).toBe('top');
  expect(node1440.depth).toBe('2');
  expect(node1440.padLeft).toBe(32);
  // 树序：dispatcher 先于 node（父行后紧跟子树）。
  expect(dump1440.rows.findIndex((r) => r.sid === DISP_SID))
    .toBeLessThan(dump1440.rows.findIndex((r) => r.sid === NODE_SID));
  // 真子行不带任何标：无野组锚、无「父不在场」、无「来自 …」来源标。
  for (const r of [disp1440, node1440]) {
    expect(r.orphan).toBe(null);
    expect(r.absentParent).toBe(null);
    expect(r.origin).toBe('');
  }
  // 野组不出现（G1 夹具无野行）。
  expect(dump1440.group).toBe(null);
  // 锚是标签面行：不入行集/计数（total = 2，签与行集同源）。
  expect(dump1440.rows).toHaveLength(2);
  expect(dump1440.rowTotal).toBe('2');
  expect(Number(String(dump1440.header).replace(/[^0-9]/g, ''))).toBe(2);

  // 375 手机档：交互层自动激活 → 后台页收编同一载体 → 公式 = --mpad-x + 6px +
  // (depth-1)*--ds-space-3（mobileLayer 合并面档），深度锚与桌面同构。
  await page.setViewportSize({ width: 375, height: 812 });
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 8000 });
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage('background');
    const m = await import('/js/main/bgAgents.js');
    m.fetchActiveAgentsOnOpen();
    m.renderBgAgentDropdown();
  });
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForFunction(() => {
    const dd = document.querySelector('#bg-dropdown');
    return dd && dd.closest('.mobile-page-body'); // 已收编进后台页体（手机档真身）
  }, null, { timeout: 8000 });
  await page.waitForTimeout(200);
  const dump375 = await dumpPanel(page);
  const disp375 = dump375.rows.find((r) => r.sid === DISP_SID);
  const node375 = dump375.rows.find((r) => r.sid === NODE_SID);
  const mpad = await page.evaluate(() =>
    parseFloat(getComputedStyle(document.body).getPropertyValue('--mpad-x')) || 16);
  console.log('[G1 375] ' + JSON.stringify({ mpad, anchor: dump375.anchor, rows: dump375.rows.map((r) => ({ sid: r.sid, depth: r.depth, padLeft: r.padLeft })) }));
  expect(dump375.anchor).toBeTruthy();
  expect(dump375.anchor.depth).toBe('0');
  expect(disp375).toBeTruthy();
  expect(disp375.depth).toBe('1');
  expect(disp375.padLeft).toBe(mpad + 6);           // 22px（--mpad-x 16 + 6 基准）
  expect(node375).toBeTruthy();
  expect(node375.depth).toBe('2');
  expect(node375.padLeft).toBe(mpad + 6 + 12);      // 34px（逐层 + --ds-space-3）
  expect(dump375.rows).toHaveLength(2);
  expect(dump375.rowTotal).toBe('2');
  expect(errors).toEqual([]);
});

test('Hierarchy accessibility text follows English locale', async ({ page }) => {
  await bootApp(page, 'en');
  await seedThreeGen(page);
  await openPanel(page);
  await expect(page.locator('.bg-root-anchor .bg-hierarchy-sr')).toHaveText('Level 1');
  await expect(page.locator(`.bg-task-row[data-node-session-id="${DISP_SID}"] .bg-hierarchy-sr`)).toHaveText('Level 2');
  await expect(page.locator(`.bg-task-row[data-node-session-id="${NODE_SID}"] .bg-hierarchy-sr`)).toHaveText('Level 3');
  await expect(page.locator(`.bg-task-row[data-node-session-id="${DISP_SID}"]`).getByRole('button', { name: 'Collapse subtasks' })).toHaveAttribute('aria-expanded', 'true');
});

// ── G2：真实形夹具（无 parentSessionId——现网数据面形状）行一行不少、depth 不伪造 ──

test('G2 真实形夹具：无 parentSessionId 的 dispatcher/node 行一行不少落野组，depth 不伪造', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  // 现网形状：同结构、无 parentSessionId 字段（旧数据面——后端批次之前的形状）。
  await setFixture(page, { rows: [] });
  await inject(page, rootStart(DISP_SID, { status: 'Processing', name: 'dispatcher/Nebflow', taskDescription: '分发器代' }));
  await inject(page, rootStart(NODE_SID, { status: 'Processing', name: 'stand', taskDescription: '节点代' }));
  await page.evaluate(async ({ r, d, n }) => {
    const s = (await import('/js/state.js')).default;
    const b = s.sessionBgAgents[r] || {};
    if (b[d]) b[d].startedAt = 1000;
    if (b[n]) b[n].startedAt = 2000;
  }, { r: ROOT_SID, d: DISP_SID, n: NODE_SID });
  await openPanel(page);

  const dump = await dumpPanel(page);
  console.log('[G2 dump] ' + JSON.stringify({ anchor: dump.anchor, group: dump.group, rows: dump.rows.map((r) => ({ sid: r.sid, depth: r.depth, padLeft: r.padLeft, orphan: r.orphan })) }));
  // 真实形不出 root 锚（没有任何属主边指向桶根——空树不出空锚）。
  expect(dump.anchor).toBe(null);
  // 一行不少：两条行都在（收进野组归置面——既有口径），计数同源。
  expect(dump.rows).toHaveLength(2);
  expect(new Set(dump.rows.map((r) => r.sid))).toEqual(new Set([DISP_SID, NODE_SID]));
  expect(dump.rowTotal).toBe('2');
  expect(Number(String(dump.header).replace(/[^0-9]/g, ''))).toBe(2);
  // depth 不伪造：野组行 depth 0、带野组锚、不挂树、零缩进档。
  expect(dump.group).toBeTruthy();
  expect(dump.group.depth).toBe('0');
  for (const r of dump.rows) {
    expect(r.orphan).toBe('1');
    expect(r.depth).toBe('0');
    expect(r.level).toBe('top');
    expect(r.absentParent).toBe(null);
    expect(r.padLeft).toBeLessThan(16);
  }
  // 每行可读（归置不是压扁）。
  for (const r of dump.rows) {
    expect(r.name.trim().length).toBeGreaterThan(0);
    expect(r.scrollHeight).toBeGreaterThan(0);
  }
  expect(errors).toEqual([]);
});

// ── G3：deps 显式判定（bgDepsParentPolicy 结论 false + deps 边不产生缩进）──────

test('G3 deps 判定：bgDepsParentPolicy().parentEdgeFromDeps === false 且 deps 边同深度平级', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  // flow 结构投影夹具：step-2 deps step-1（DAG 依赖边——不是属主边）。
  await setFixture(page, {
    rows: [mirrorRow()],
    chains: [{
      taskId: '95', delegateId: DELEGATE_SID,
      nodes: [
        { id: 'step-1', kind: 'flow-step', label: '步骤一', status: 'running', deps: [] },
        { id: 'step-2', kind: 'flow-step', label: '步骤二', status: 'pending', deps: ['step-1'] },
      ],
    }],
  });
  await inject(page, {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: DELEGATE_SID, nodeSessionId: DELEGATE_SID,
    name: 'Planner', taskDescription: 'deps 判定批次',
  });
  await openPanel(page);

  // 判定函数结论（页面侧读渲染面同一模块的单点）。
  const policy = await page.evaluate(async () => {
    const m = await import('/js/main/bgAgents.js');
    return m.bgDepsParentPolicy();
  });
  console.log('[G3 policy] ' + JSON.stringify(policy));
  expect(policy.parentEdgeFromDeps).toBe(false);
  expect(String(policy.reason || '').length).toBeGreaterThan(0);

  const dump = await dumpPanel(page);
  console.log('[G3 dump] ' + JSON.stringify(dump.rows.map((r) => ({ name: r.name, level: r.level, depth: r.depth, padLeft: r.padLeft }))));
  const s1 = dump.rows.find((r) => r.name === '步骤一');
  const s2 = dump.rows.find((r) => r.name === '步骤二');
  expect(s1).toBeTruthy();
  expect(s2).toBeTruthy();
  // deps 边不产生父子缩进：两步同深度平级（都挂整体行下 depth1），padding 同档。
  expect(s1.depth).toBe('1');
  expect(s2.depth).toBe('1');
  expect(s2.padLeft).toBe(s1.padLeft);
  expect(errors).toEqual([]);
});

// ── G4：三态各一例落位符合判定表 ─────────────────────────────────────────

test('G4 判定表三态各一例：①真子行 depth2 ②父不在场 depth1+标不进野组 ③无父字段落野组', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [] });
  // ① 有父且在册：node 挂在场的 dispatcher 行下。
  await inject(page, rootStart(DISP_SID, {
    status: 'Processing', name: 'dispatcher/Nebflow',
    taskDescription: '分发器代', parentSessionId: ROOT_SID,
  }));
  await inject(page, rootStart(NODE_SID, {
    status: 'Processing', name: 'stand',
    taskDescription: '节点代', parentSessionId: DISP_SID,
  }));
  // ② 有父但不在场：node 的父 dispatcher 已离场（不在行集、不在册）。
  await inject(page, rootStart(ABSENT_NODE_SID, {
    status: 'Processing', name: '孤儿链节点',
    taskDescription: '父不在场', parentSessionId: ABSENT_PARENT_SID,
  }));
  // ③ 无父字段：现网旧形状（落野组）。
  await inject(page, rootStart(NOPARENT_NODE_SID, {
    status: 'Processing', name: '无父节点',
    taskDescription: '无父字段',
  }));
  await page.evaluate(async ({ r, d, n, a, x }) => {
    const s = (await import('/js/state.js')).default;
    const b = s.sessionBgAgents[r] || {};
    if (b[d]) b[d].startedAt = 1000;
    if (b[n]) b[n].startedAt = 2000;
    if (b[a]) b[a].startedAt = 3000;
    if (b[x]) b[x].startedAt = 4000;
  }, { r: ROOT_SID, d: DISP_SID, n: NODE_SID, a: ABSENT_NODE_SID, x: NOPARENT_NODE_SID });
  await openPanel(page);

  const dump = await dumpPanel(page);
  console.log('[G4 dump] ' + JSON.stringify({ anchor: dump.anchor, group: dump.group, rows: dump.rows.map((r) => ({ sid: r.sid, depth: r.depth, padLeft: r.padLeft, orphan: r.orphan, absent: r.absentParent, origin: r.origin, absentChip: r.absentChip })) }));
  const disp = dump.rows.find((r) => r.sid === DISP_SID);
  const node = dump.rows.find((r) => r.sid === NODE_SID);
  const absent = dump.rows.find((r) => r.sid === ABSENT_NODE_SID);
  const noparent = dump.rows.find((r) => r.sid === NOPARENT_NODE_SID);
  expect(disp && node && absent && noparent).toBeTruthy();

  // ① 有父且在册 → 真子行：node 挂 dispatcher 下 depth2，无任何标（父可见）。
  expect(disp.depth).toBe('1');
  expect(node.depth).toBe('2');
  expect(node.orphan).toBe(null);
  expect(node.absentParent).toBe(null);
  expect(node.origin).toBe('');

  // ② 有父但不在场 → 父应在位置（root 锚直下 depth1）+「父不在场」标，不进归置组。
  expect(dump.anchor).toBeTruthy();          // 锚在场（②态行与①态行都以它为基座）
  expect(absent.depth).toBe('1');            // 父的槽位，不伪造 depth2
  expect(absent.absentParent).toBe('1');     // 行级机器锚
  expect(absent.orphan).toBe(null);          // 不进归置组（无野组锚）
  expect(absent.absentChip).toBe('父不在场'); // 标上屏（zh-CN locale 单点）
  expect(absent.absentChipTitle).toContain(ABSENT_PARENT_SID); // tooltip 承载父 sid

  // ③ 无父字段 → 既有野组：data-bg-orphan + depth0，在野组里（恰 1 行）。
  expect(noparent.orphan).toBe('1');
  expect(noparent.depth).toBe('0');
  expect(dump.group).toBeTruthy();
  const wildRows = dump.rows.filter((r) => r.orphan === '1');
  expect(wildRows).toHaveLength(1);
  expect(wildRows[0].sid).toBe(NOPARENT_NODE_SID);

  // 行集与计数：4 行全在（锚不是行），签同源。
  expect(dump.rows).toHaveLength(4);
  expect(dump.rowTotal).toBe('4');
  expect(Number(String(dump.header).replace(/[^0-9]/g, ''))).toBe(4);
  expect(errors).toEqual([]);
});

// ── G5：深层折叠跟随（dispatcher 收起 → node 子树隐藏；展开恢复；刷新持久）────

test('G5 折叠 dispatcher → node 子树整棵隐藏、展开恢复、刷新持久；root 锚收起 = 根下全隐', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await seedThreeGen(page);
  await openPanel(page);

  // 折叠前：node 在场（进行中默认展开）。
  const before = await dumpPanel(page);
  expect(before.rows.some((r) => r.sid === NODE_SID)).toBe(true);

  // 折叠 dispatcher 行 → node 整棵子树隐藏；dispatcher 行仍在、锚仍在。
  await page.click(`[data-bg-caret="${DISP_SID}"]`);
  await page.waitForTimeout(150);
  const folded = await dumpPanel(page);
  console.log('[G5 folded] ' + JSON.stringify(folded.rows.map((r) => ({ sid: r.sid, depth: r.depth, collapsed: r.collapsed, caretCollapsed: r.caretCollapsed }))) + ' anchor=' + JSON.stringify(folded.anchor));
  expect(folded.rows.filter((r) => r.sid === NODE_SID)).toHaveLength(0);
  expect(folded.rows.some((r) => r.sid === DISP_SID)).toBe(true);
  expect(folded.rows.find((r) => r.sid === DISP_SID).collapsed).toBe('true');
  expect(folded.rows.find((r) => r.sid === DISP_SID).caretCollapsed).toBe(true);
  expect(folded.anchor).toBeTruthy();
  // 折叠不改计数（行集合/计数不受影响——既有口径）。
  expect(folded.rowTotal).toBe('2');

  // 展开恢复。
  await page.click(`[data-bg-caret="${DISP_SID}"]`);
  await page.waitForTimeout(150);
  const expanded = await dumpPanel(page);
  expect(expanded.rows.some((r) => r.sid === NODE_SID)).toBe(true);
  expect(expanded.rows.find((r) => r.sid === DISP_SID).collapsed).toBe('false');

  // 再折叠 + 刷新持久（同 fixture 重种，不点任何 caret）。
  await page.click(`[data-bg-caret="${DISP_SID}"]`);
  await page.waitForTimeout(150);
  const stored = await page.evaluate(() => {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i) || '';
      if (/collapse/i.test(k)) out[k] = localStorage.getItem(k);
    }
    return out;
  });
  console.log('[G5 localStorage] ' + JSON.stringify(stored));
  expect(JSON.stringify(stored)).toContain(DISP_SID);
  await page.reload();
  await waitReady(page);
  await seedThreeGen(page);
  await openPanel(page);
  const persisted = await dumpPanel(page);
  console.log('[G5 persisted] ' + JSON.stringify(persisted.rows.map((r) => ({ sid: r.sid, collapsed: r.collapsed }))));
  expect(persisted.rows.filter((r) => r.sid === NODE_SID)).toHaveLength(0);
  expect(persisted.rows.find((r) => r.sid === DISP_SID).collapsed).toBe('true');
  expect(persisted.anchor).toBeTruthy();

  // 基座收起语义：折叠 root 锚 → 根下整棵子树隐藏（dispatcher 也退场）；锚仍在、计数不变。
  await page.click(`[data-bg-caret="${ROOT_SID}"]`);
  await page.waitForTimeout(150);
  const anchorFolded = await dumpPanel(page);
  console.log('[G5 anchorFolded] rows=' + anchorFolded.rows.length + ' anchor=' + JSON.stringify(anchorFolded.anchor) + ' rowTotal=' + anchorFolded.rowTotal);
  expect(anchorFolded.rows).toHaveLength(0);
  expect(anchorFolded.anchor).toBeTruthy();
  expect(anchorFolded.anchor.collapsed).toBe('true');
  expect(anchorFolded.rowTotal).toBe('2');
  // 展开锚 → 子树恢复（dispatcher 行保持自己的折叠偏好：仍收起）。
  await page.click(`[data-bg-caret="${ROOT_SID}"]`);
  await page.waitForTimeout(150);
  const anchorExpanded = await dumpPanel(page);
  expect(anchorExpanded.rows.some((r) => r.sid === DISP_SID)).toBe(true);
  expect(anchorExpanded.anchor.collapsed).toBe('false');
  expect(errors).toEqual([]);
});

// ── 静态钉：判定单点 + 机器锚写点唯一 ─────────────────────────────────────

test('S 静态钉：三态判定单点一个函数；父不在场/根锚机器锚各一个写点', async () => {
  const src = readFileSync(join(WEB, 'js', 'main', 'bgAgents.js'), 'utf8');
  // 判面 = 代码行（注释里叙旧提到锚名不算写点，与 terms spec H5 的 codeOnly 同口径）。
  const codeOnly = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  const count = (needle) => codeOnly.split(needle).length - 1;
  // 三态判定单点：定义恰一次（bgRowTree / bgRowPlan / 野判定式三处消费面只读它）。
  expect(count('function bgTopRowParentStateOf(')).toBe(1);
  // 机器锚写点唯一。
  expect(count('data-bg-parent-absent="1"')).toBe(1);
  expect(count('data-bg-root-anchor="1"')).toBe(1);
  expect(count('data-bg-absent-parent="1"')).toBe(1);
  // deps 显式判定结论照旧（作者令「不许只有注释」的代码判定不被本批回退）。
  expect(codeOnly.includes('parentEdgeFromDeps: false')).toBe(true);
});
