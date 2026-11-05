// bg-agentflow-layering.spec.mjs — 面板层级缩进 + 折叠持久化批（2026-11-05 腿②）
//
// 作者令（2026-11-05 08:47）：「我们的（折叠）控制还应该能显示出 agentflow（也就是
// 节点）的关系，因为节点也是分很多层的，每一层都应该是上一层的缩进。」+ 既有铁律：
// **元素不许为了腾地方而消失**——折叠是用户主动行为允许隐藏，但不能因为层级深就被
// 裁掉或压成不可读。
//
// 断言面（任务书四断言 ①-⑤，机械读数）：
//   ① 深度层进 …… depth(节点 A) === depth(整体行) + 1
//   ② 深度层进 …… depth(A 的子) === depth(A) + 1（三层是**真实父子链接**算出来的，
//      不是渲染顺序猜的：A 的子挂的是 A 的 sessionId）
//   ③ 折叠子树 …… 折叠 A ⇒ A 子树整行数 = 0（A 的子退出 DOM），整体行仍在、A 行仍在
//   ④ 折叠持久 …… page.reload() + 同 fixture 重种 ⇒ A 仍收起（子树 0 行、A 行
//      data-bg-collapsed="true"、整体行在），**不需要重新点**
//   ⑤ 反向门 …… 不折叠时三层行全在（不因深度被裁）；padding-left 随 depth 严格
//      单调递增；每行 scrollHeight > 0；depth≥2 的行主名非空且可见
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows），零后端、零 sbt、零 6138。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-agentflow-layering.spec.mjs --reporter=line

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

const ROOT_SID = 'layering-root-session';
// 整体行（一次编排 = 一个 agentflow）。
const FLOW_SID = 'delegate-layer-a1b2c3d4';
// 一级节点（成员）——父 = 整体行。
const NODE_A = 'node-layer-11112222';
// A 的子（二级成员）——父 = NODE_A（真实 parentSessionId 链接，不是渲染顺序）。
const NODE_A_CHILD = 'node-layer-33334444';
const T0 = Date.now();

/** 主桶行（delegate live 行）。 */
function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 作用域桶链行（真 spawn 形：rootSessionId = delegateSid 自根；parentSessionId = 真实父会话）。 */
function chainStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: FLOW_SID, rootSessionId: FLOW_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    project: 'Nebflow',
    ...over,
  };
}

/** 注册行镜像夹具（DelegateRow 形）。 */
function mirrorRow(over = {}) {
  return {
    taskNo: '97', delegateId: FLOW_SID, title: '层级缩进批次——折叠持久化',
    name: '层级批', executor: 'nebflow', status: 'running', external: false,
    project: '', createdAt: T0 - 30 * 60_000, orchestrationMode: 'agentflow',
    mode: 'dispatcher', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    ...over,
  };
}

/** 路由装配（静态 web + WS 假引擎）——reload 后仍生效（route 挂在 page 上）。 */
async function installRoutes(page) {
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
}

/** 等应用就绪（首次进入与 reload 后共用）。 */
async function waitReady(page) {
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function bootApp(page) {
  await installRoutes(page);
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

/** 装 stub 夹具（rows 读口）。 */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
  }, fx);
}

/**
 * 三层树种子（同一 fixture 可重种：reload 后重种验折叠持久）：
 *   整体行（delegate live 行，深度 0）→ 节点 A（parentSessionId = 整体行）→
 *   A 的子（parentSessionId = A）。前后两层都由**真实父链接**决定，不靠行序。
 */
async function seedTree(page) {
  await setFixture(page, { rows: [mirrorRow()] });
  await inject(page, rootStart(FLOW_SID, { name: '层级批', taskDescription: '层级缩进 + 折叠持久化' }));
  await inject(page, chainStart(NODE_A, {
    name: '节点A', taskDescription: '一级节点 A', parentSessionId: FLOW_SID,
  }));
  await inject(page, chainStart(NODE_A_CHILD, {
    name: 'A的子', taskDescription: 'A 的子节点', parentSessionId: NODE_A,
  }));
  // 起点戳固定（链行序 = startedAt 升序；A 早于其子——树序与行序同向）。
  await page.evaluate(async ({ a, c, flow }) => {
    const s = (await import('/js/state.js')).default;
    const b = s.sessionBgAgents[flow] || {};
    if (b[a]) b[a].startedAt = 1000;
    if (b[c]) b[c].startedAt = 2000;
  }, { a: NODE_A, c: NODE_A_CHILD, flow: FLOW_SID });
}

async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(200);
}

const LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';

/** 行集 dump（四断言的机械读数面）：深度锚 / 折叠锚 / 缩进读值 / 行高 / 主名可见性。 */
function dumpRows(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    return [...list.children].filter((el) => el.classList.contains('bg-task-row')).map((el) => {
      const cs = getComputedStyle(el);
      const nameEl = el.querySelector('.bg-task-name');
      const nb = nameEl ? nameEl.getBoundingClientRect() : null;
      const caretEl = el.querySelector('[data-bg-caret]');
      return {
        cls: el.className,
        level: el.getAttribute('data-bg-row-level') || '',
        depth: el.getAttribute('data-bg-depth'),
        depthNum: Number(el.getAttribute('data-bg-depth')),
        collapsed: el.getAttribute('data-bg-collapsed'),
        caretKey: caretEl ? (caretEl.getAttribute('data-bg-caret') || '') : '',
        caretCollapsed: caretEl ? caretEl.classList.contains('is-collapsed') : null,
        sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
        name: nameEl ? nameEl.textContent : '',
        padLeft: parseFloat(cs.paddingLeft),
        scrollHeight: el.scrollHeight,
        nameWidth: nb ? Math.round(nb.width) : 0,
      };
    });
  }, LIST);
}

/** 采集页面错误（pageerror + console.error）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

// ── ① + ②：深度按真实父子链接算（三层）────────────────────────────────────

test('① ② 三层深度：depth(A) = depth(整体行)+1，depth(A 的子) = depth(A)+1', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await seedTree(page);
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[①② dump] ' + JSON.stringify(rows.map((r) => ({
    level: r.level, sid: r.sid, depth: r.depth, padLeft: r.padLeft, name: r.name,
  }))));
  const flow = rows.find((r) => r.sid === FLOW_SID);
  const a = rows.find((r) => r.sid === NODE_A);
  const kid = rows.find((r) => r.sid === NODE_A_CHILD);
  expect(flow).toBeTruthy();
  expect(a).toBeTruthy();
  expect(kid).toBeTruthy();

  // ① 整体行 depth=0，一级节点 = 整体行 + 1。
  expect(flow.depthNum).toBe(0);
  expect(a.depthNum).toBe(flow.depthNum + 1);
  // ② A 的子 = A + 1（父链接 = A 的 sessionId，不是行序）。
  expect(kid.depthNum).toBe(a.depthNum + 1);
  expect(kid.depthNum).toBe(2);
  // 三层的层级标识仍走既有 data-bg-row-level（整体行 head / 成员行 child）。
  expect(flow.level).toBe('head');
  expect(a.level).toBe('child');
  expect(kid.level).toBe('child');
  expect(errors).toEqual([]);
});

// ── ⑤：反向门（不折叠时三层行全在 + 缩进严格单调 + 每行可读）─────────────

test('⑤ 反向门：不折叠时三层行全在（不裁行）+ padding-left 严格递增 + 深行可读', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await seedTree(page);
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[⑤ dump] ' + JSON.stringify(rows.map((r) => ({
    sid: r.sid, depth: r.depth, padLeft: r.padLeft, scrollHeight: r.scrollHeight,
    name: r.name, nameWidth: r.nameWidth,
  }))));

  // 不裁行：模型里的每一行都在 DOM 里（三层 = 3 行；面板计数与行集同源）。
  expect(rows).toHaveLength(3);
  const rowTotal = await page.evaluate((sel) => document.querySelector(sel).getAttribute('data-bg-row-total'), LIST);
  console.log('[⑤] domRows=' + rows.length + ' data-bg-row-total=' + rowTotal);
  expect(Number(rowTotal)).toBe(rows.length);

  // 每行有真实高度（不被压成 0 高 / 不可读）。
  for (const r of rows) expect(r.scrollHeight).toBeGreaterThan(0);

  // padding-left 随 depth 严格单调递增（逐层缩进一格）。
  const byDepth = [...rows].sort((x, y) => x.depthNum - y.depthNum);
  console.log('[⑤] pads=' + JSON.stringify(byDepth.map((r) => [r.depthNum, r.padLeft])));
  for (let i = 1; i < byDepth.length; i++) {
    // 同深度行共享同一档缩进；跨深度必须严格更大。
    if (byDepth[i].depthNum > byDepth[i - 1].depthNum) {
      expect(byDepth[i].padLeft).toBeGreaterThan(byDepth[i - 1].padLeft);
    }
  }
  expect(byDepth.map((r) => r.depthNum)).toEqual([0, 1, 2]);

  // depth≥2 的行主名非空且可见（不因层级深被压成不可读）。
  const deep = rows.filter((r) => r.depthNum >= 2);
  expect(deep.length).toBeGreaterThan(0);
  for (const r of deep) {
    expect(r.name.trim().length).toBeGreaterThan(0);
    expect(r.nameWidth).toBeGreaterThan(0);
  }
  expect(errors).toEqual([]);
});

// ── ⑥：N 层不是两级帽（四层链反向门）─────────────────────────────────────

test('⑥ N 层：四层链（整体行 → A → A 的子 → A 的孙）逐层 +1 且全在 DOM', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  const NODE_GRAND = 'node-layer-55556666';
  await setFixture(page, { rows: [mirrorRow()] });
  await inject(page, rootStart(FLOW_SID, { name: '层级批' }));
  await inject(page, chainStart(NODE_A, { name: '节点A', parentSessionId: FLOW_SID }));
  await inject(page, chainStart(NODE_A_CHILD, { name: 'A的子', parentSessionId: NODE_A }));
  await inject(page, chainStart(NODE_GRAND, { name: 'A的孙', parentSessionId: NODE_A_CHILD }));
  await page.evaluate(async ({ a, c, g, flow }) => {
    const s = (await import('/js/state.js')).default;
    const b = s.sessionBgAgents[flow] || {};
    if (b[a]) b[a].startedAt = 1000;
    if (b[c]) b[c].startedAt = 2000;
    if (b[g]) b[g].startedAt = 3000;
  }, { a: NODE_A, c: NODE_A_CHILD, g: NODE_GRAND, flow: FLOW_SID });
  await openPanel(page);

  const rows = await dumpRows(page);
  console.log('[⑥ dump] ' + JSON.stringify(rows.map((r) => ({
    sid: r.sid, depth: r.depth, padLeft: r.padLeft, name: r.name,
  }))));
  const deps = [FLOW_SID, NODE_A, NODE_A_CHILD, NODE_GRAND].map((sid) => {
    const r = rows.find((x) => x.sid === sid);
    expect(r, 'row missing: ' + sid).toBeTruthy();
    return r;
  });
  // 逐层 +1，四层全在（不是两级帽），缩进逐层严格递增。
  expect(deps.map((r) => r.depthNum)).toEqual([0, 1, 2, 3]);
  for (let i = 1; i < deps.length; i++) {
    expect(deps[i].padLeft).toBeGreaterThan(deps[i - 1].padLeft);
    expect(deps[i].scrollHeight).toBeGreaterThan(0);
    expect(deps[i].name.trim().length).toBeGreaterThan(0);
  }
  expect(errors).toEqual([]);
});

// ── ③：折叠隐藏整棵子树 ───────────────────────────────────────────────────

test('③ 折叠 A ⇒ A 子树整行数 = 0，整体行与 A 行仍在', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await seedTree(page);
  await openPanel(page);

  // 折叠前：A 的子在场（进行中节点默认展开）。
  const before = await dumpRows(page);
  console.log('[③ before] ' + JSON.stringify(before.map((r) => ({ sid: r.sid, depth: r.depth, collapsed: r.collapsed }))));
  expect(before.some((r) => r.sid === NODE_A_CHILD)).toBe(true);

  // 点 A 的 caret（A 有子行 ⇒ 任意层都可折叠）——沿用既有 caret 形态与热区。
  await page.click(`[data-bg-caret="${NODE_A}"]`);
  await page.waitForTimeout(150);
  const after = await dumpRows(page);
  console.log('[③ after] ' + JSON.stringify(after.map((r) => ({ sid: r.sid, depth: r.depth, collapsed: r.collapsed, caretCollapsed: r.caretCollapsed }))));

  // A 的整棵子树 0 行（深层行不残留），整体行与 A 行仍在。
  expect(after.filter((r) => r.sid === NODE_A_CHILD)).toHaveLength(0);
  expect(after.some((r) => r.sid === FLOW_SID)).toBe(true);
  const aRow = after.find((r) => r.sid === NODE_A);
  expect(aRow).toBeTruthy();
  expect(aRow.collapsed).toBe('true');
  expect(aRow.caretCollapsed).toBe(true);
  expect(errors).toEqual([]);
});

// ── ④：折叠状态刷新后保持（localStorage 持久化）────────────────────────────

test('④ reload + 同 fixture 重种 ⇒ 折叠状态仍在（不需要重新点）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await seedTree(page);
  await openPanel(page);
  await page.click(`[data-bg-caret="${NODE_A}"]`);
  await page.waitForTimeout(150);
  const collapsedBefore = await dumpRows(page);

  // 落盘检查：命名空间键 + 行定位键（机器读点）。
  const stored = await page.evaluate(() => {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i) || '';
      if (/collapse/i.test(k)) out[k] = localStorage.getItem(k);
    }
    return out;
  });
  console.log('[④ localStorage] ' + JSON.stringify(stored));
  expect(Object.keys(stored).length).toBeGreaterThan(0);
  expect(JSON.stringify(stored)).toContain(NODE_A);

  // 刷新 + 同一 fixture 重种（不点任何 caret）。
  await page.reload();
  await waitReady(page);
  await seedTree(page);
  await openPanel(page);

  const after = await dumpRows(page);
  console.log('[④ after reload] ' + JSON.stringify(after.map((r) => ({ sid: r.sid, depth: r.depth, collapsed: r.collapsed }))));
  console.log('[④ before reload] ' + JSON.stringify(collapsedBefore.map((r) => ({ sid: r.sid, collapsed: r.collapsed }))));
  expect(after.filter((r) => r.sid === NODE_A_CHILD)).toHaveLength(0);
  const aRow = after.find((r) => r.sid === NODE_A);
  expect(aRow).toBeTruthy();
  expect(aRow.collapsed).toBe('true');
  expect(after.some((r) => r.sid === FLOW_SID)).toBe(true);
  expect(errors).toEqual([]);
});
