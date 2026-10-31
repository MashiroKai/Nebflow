// bg-merged-panel.spec.mjs — 后台合并面板验收（作者令 2026-10-23 ⑥：后台任务 +
// 后台子智能体两面板归并为**一个后台面板**载体）· 面板分区/树/说明三修批
// （2026-10-29 作者报障三件）· 后台任务面板层级+命名+版式批
// （2026-10-31 作者令四件 + 11:52/11:58 追加令）：
//
//   ① 派生树 … visibleBgAgentEntries 按 parentSessionId（live 帧 + 快照）与 G3
//      store 双源建父子：delegate 组头一级、子行缩进 + 左侧引导竖线
//      （bg-task-row-child，computed paddingLeft > 组头）；父不可见的孤儿行提升
//      顶层并带「来自 …」来源角标（.bg-task-orphan）。
//   ② 行说明 … 泛名行（name = builtin def 名「subagent」等）主名 = 任务摘要首行；
//      实名行附 .bg-task-subline 摘要次行 —— 任何条目不再出现纯「subagent」空洞行。
//   ③ 分区 … 载体内两节（.bg-section-tasks / .bg-section-agents）各自独立滚动
//      （各自 overflow-y:auto + 载体 flex 收缩），分区签带实时计数；手机窄宽两节
//      升格两张内嵌卡（mobileLayer.css）。
//   ④ 层级显形（10-31 ①）… 组头行首 caret 折叠子行树：默认展开进行中父、终态
//      组头默认收起；caret 点击偏好跨重渲染保持；行体点击开详情不变。
//   ⑤ 行首短名（10-31 ② · 锚点「行首必须显短名」）… Delegate 工具 name 参数
//      （≤12 字）经 DelegateRegistry.Record.name → provider 行镜像（row.name）/
//      快照 delegateName → 组头行首主名 = 短名（标题降为次行，全文 tooltip）；
//      弹窗头名 = 行首已解析显示名（不再显执行器 def 名「nebflow」）；无名旧数据
//      回退既有标题截断显示。
//   ⑥ 一屏装完（10-31 ③）… 禁横向滚动：面板宽度钳进视口，信息列全槽位溢出
//      省略（全文 tooltip 既有），桌面 + 375 双端 scrollWidth ≤ clientWidth。
//   ⑦ 桌面拆两面板（10-31 ④ · 追加令）… 桌面端 #bg-indicator 只显任务节、
//      #bgagent-indicator 只显智能体节（bg-view-* 视图类 + CSS 闸）；手机端
//      （mobile-layer）维持合并单面板不变。
//
// 数据面 = WS activeBgTasks 快照 + state.sessionBgAgents 直种 + delegateChainStub
// 夹具（rows/children——结构双源断言），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/bg-merged-panel.spec.mjs

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

const ROOT_SID = 'bgm-root-session';
const T0 = Date.now();

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
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
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: { [ROOT_SID]: [
      { taskId: 'job-1', description: '合并面板样例任务', status: 'running', startedAt: T0 - 45_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 4_000, outputLines: 9 } },
    ] } }));
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 种入 delegate 链夹具（G3 store 双源：行集 + 星型子代理镜像）。 */
async function seedChainFixture(page, fx) {
  // 返回内层 promise（evaluate 会 await）——杜绝「夹具未落地就重渲染」的竞态。
  await page.evaluate((fixture) => {
    return import('/js/delegateChainStub.js').then((m) => {
      m.__setFixture(fixture);
    });
  }, fx);
}

/** 种子智能体行（renderBgAgentDropdown 数据面直种）并刷新指示器。
 *  assertBadge=false 用于移动视口用例——窄宽自动进 mobile-layer，桌面顶键被
 *  手机层隐藏（可见性断言会假阴），种数与开面走 evaluate 直驱。 */
async function seedAgents(page, sid, entries, { assertBadge = true } = {}) {
  await page.evaluate(({ sid, entries }) => {
    import('/js/state.js').then((m) => {
      const bucket = {};
      for (const e of entries) bucket[e.id] = e.info;
      m.default.sessionBgAgents[sid] = bucket;
      m.default.updateBgAgentIndicator();
    });
  }, { sid, entries });
  if (assertBadge) await expect(page.locator('#bgagent-indicator')).toBeVisible();
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

const HEAD = { id: 'delegate-fixture-1', info: {
  name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1',
  status: 'Processing', startedAt: Date.now() - 30_000,
  parentSessionId: ROOT_SID, // root 直派 = 一等组头（root-parent 豁免）
} };

test.beforeEach(async ({ page }) => {
  await bootApp(page);
  // G3 夹具：delegate 行 + 星型子代理镜像（store 认领源）。
  await seedChainFixture(page, {
    rows: [{ delegateId: 'delegate-fixture-1', taskNo: '42', title: '词条引用系统第二批实施',
      executor: 'nebflow', status: 'running', project: '', createdAt: T0 }],
    children: { 'delegate-fixture-1': [
      { taskId: 'subagent-store-1', parentSessionId: 'delegate-fixture-1', agentName: 'subagent',
        prompt: '', description: '只读侦察：盘点 console 面板结构', status: 'running',
        retryCount: 0, spawnedAt: T0, completedAt: null, lastError: '', source: 'subagent' },
    ] },
  });
});

test('T1 merge structure: two sections in the carrier, ghost retired (desktop)', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgents(page, ROOT_SID, [HEAD]);

  // 结构（boot 即成，无需开面板）：两节容器；各节恰一列表；幽灵面退位。
  const structure = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const g = document.getElementById('bgagent-dropdown');
    const secs = c.querySelectorAll(':scope > .bg-section');
    return {
      sectionCount: secs.length,
      firstIsTasks: secs[0]?.className.includes('bg-section-tasks') || false,
      secondIsAgents: secs[1]?.className.includes('bg-section-agents') || false,
      tasksHeader: secs[0]?.querySelector('.bg-dropdown-header')?.textContent || '',
      agentsListInSec2: !!secs[1]?.querySelector('.bg-dropdown-list'),
      ghostStillHasList: !!g.querySelector('.bg-dropdown-list'),
      ghostDisplay: getComputedStyle(g).display,
    };
  });
  expect(structure.sectionCount).toBe(2);
  expect(structure.firstIsTasks).toBe(true);
  expect(structure.secondIsAgents).toBe(true);
  expect(structure.tasksHeader).toContain('后台任务');
  expect(structure.agentsListInSec2).toBe(true);
  expect(structure.ghostStillHasList).toBe(false);
  expect(structure.ghostDisplay).toBe('none');
  expect(errors).toEqual([]);

  // 布局（开面板后才有盒）：flex column + max-height 收口；两列表各自独立滚动。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const layout = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const lists = c.querySelectorAll('.bg-dropdown-list');
    return {
      displayFlex: getComputedStyle(c).display === 'flex',
      maxHeight: getComputedStyle(c).maxHeight,
      listCount: lists.length,
      overflowYs: [...lists].map((l) => getComputedStyle(l).overflowY),
    };
  });
  expect(layout.displayFlex).toBe(true);
  expect(layout.maxHeight).not.toBe('none');
  expect(layout.listCount).toBe(2);
  expect(layout.overflowYs).toEqual(['auto', 'auto']);
});

test('T2 desktop split: bg key opens tasks-only view, bgagent key opens agents-only view', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgents(page, ROOT_SID, [HEAD]);

  // 后台任务键 → 面板开：只显任务节（智能体节 display:none——DOM 仍在，视图类闸）。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bg-dropdown')).toHaveClass(/bg-view-tasks/);
  const tasksView = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const cs = getComputedStyle(c);
    return {
      viewClass: c.className,
      panelRight: cs.right,
      panelWidthWithinViewport: c.getBoundingClientRect().right <= window.innerWidth,
      agentsSectionDisplay: getComputedStyle(c.querySelector('.bg-section-agents')).display,
      tasksSectionDisplay: getComputedStyle(c.querySelector('.bg-section-tasks')).display,
    };
  });
  expect(tasksView.viewClass).toContain('bg-view-tasks');
  expect(tasksView.agentsSectionDisplay).toBe('none');
  expect(tasksView.tasksSectionDisplay).not.toBe('none');
  expect(tasksView.panelWidthWithinViewport).toBe(true);
  const taskRows = page.locator('#bg-dropdown .bg-section-tasks .bg-task-row');
  await expect(taskRows).toHaveCount(1);
  await expect(taskRows.first()).toContainText('合并面板样例任务');
  await expect(page.locator('#bg-dropdown .bg-section-tasks .bg-dropdown-header')).toHaveText('后台任务 · 1');

  // Esc → 关（桌面既有 Esc 链：幽灵面（恒隐）跳过 → 载体收）。
  await page.keyboard.press('Escape');
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);

  // 智能体键 → 同一载体换智能体单族视图：任务节隐、智能体节显（组头 1 + store 子行 1）。
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bg-dropdown')).toHaveClass(/bg-view-agents/);
  const agentsView = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    return {
      tasksSectionDisplay: getComputedStyle(c.querySelector('.bg-section-tasks')).display,
      agentsSectionDisplay: getComputedStyle(c.querySelector('.bg-section-agents')).display,
    };
  });
  expect(agentsView.tasksSectionDisplay).toBe('none');
  expect(agentsView.agentsSectionDisplay).not.toBe('none');
  const agentRows = page.locator('#bg-dropdown .bg-section-agents .bg-task-row');
  await expect(agentRows).toHaveCount(2); // 组头 + store 子行
  await expect(agentRows.first()).toContainText('词条引用系统第二批实施');
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeVisible();
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toHaveText('智能体 · 2');
  await expect(page.locator('#bgagent-indicator')).toHaveAttribute('aria-expanded', 'true');

  // 外点 → 关（桌面 outside-close 同源）。
  await page.mouse.click(10, 400);
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);
  expect(errors).toEqual([]);
});

test('T3 split is desktop-only: mobile layer keeps the merged single panel', async ({ page }) => {
  const errors = watchErrors(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await seedAgents(page, ROOT_SID, [HEAD], { assertBadge: false });
  await page.evaluate(() => document.body.classList.add('mobile-layer'));
  // 手机端经 evaluate 直驱开面（桌面顶键在手机层不可见——开合处理器照常跑）：
  // 合并单面板不变（视图类即使存在也被 :not(.mobile-layer) 闸住）。
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const merged = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    return {
      tasksSectionDisplay: getComputedStyle(c.querySelector('.bg-section-tasks')).display,
      agentsSectionDisplay: getComputedStyle(c.querySelector('.bg-section-agents')).display,
    };
  });
  expect(merged.tasksSectionDisplay).not.toBe('none');
  expect(merged.agentsSectionDisplay).not.toBe('none');
  // 任务节渲染：bgagent 键开合不渲任务列表（bg 键的 toggle 才调 renderBgDropdown）——
  // 经开面板态补渲一次（updateBgTasksUI 的「开面板重渲」腿）。
  await page.evaluate(() => { import('/js/state.js').then((m) => m.default.updateBgTasksUI()); });
  await expect(page.locator('#bg-dropdown .bg-section-tasks .bg-task-row')).toHaveCount(1);

  // 空节收签语义保留（merged）：零子智能体 → 签隐藏，任务节照常。清桶走
  // keepDropdownOpen——否则 count=0 的自动收面板会干扰本断言（行 DOM 不随关面消失，
  // 但签的可见性断言需要开面语境）。
  await page.evaluate((sid) => {
    import('/js/state.js').then((m) => {
      m.default.sessionBgAgents[sid] = {};
      m.default.updateBgAgentIndicator(sid, { keepDropdownOpen: true });
    });
  }, ROOT_SID);
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeHidden();
  await expect(page.locator('#bg-dropdown .bg-section-tasks .bg-task-row')).toHaveCount(1);

  // 种入子智能体 → 签即现（MutationObserver 空节收签的反向腿）。
  await seedAgents(page, ROOT_SID, [HEAD], { assertBadge: false });
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeVisible();
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toHaveText('智能体 · 2');
  expect(errors).toEqual([]);
});

test('T4 tree: live child (parentSessionId) indents under its delegate head with guide line', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgents(page, ROOT_SID, [
    HEAD,
    { id: 'subagent-live-1', info: {
      name: 'subagent', kind: 'Subagent', sessionId: 'subagent-live-1',
      status: 'Processing', startedAt: Date.now() - 10_000,
      task: '只读侦察：核对后台面板树形缩进断言',
      parentSessionId: 'delegate-fixture-1', // live 认领源（快照/live 帧同键）
    } },
  ]);
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();

  const tree = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    const rows = [...list.querySelectorAll(':scope > .bg-task-row')];
    const head = rows[0];
    const live = rows[1];
    const store = rows[2];
    return {
      order: rows.map((r) => (r.querySelector('.bg-task-name') || {}).textContent || ''),
      headIsHead: head.classList.contains('bg-task-row-head'),
      liveIsChild: live.classList.contains('bg-task-row-child'),
      headPad: parseFloat(getComputedStyle(head).paddingLeft),
      livePad: parseFloat(getComputedStyle(live).paddingLeft),
      liveGuide: getComputedStyle(live).borderLeftWidth,
      storeIsChild: store ? store.classList.contains('bg-task-row-child') : false,
      // 泛名行（name="subagent"）主名 = 任务摘要首行（面板行说明批 ②）。
      liveName: (live.querySelector('.bg-task-name') || {}).textContent || '',
      liveHollow: (live.querySelector('.bg-task-name') || {}).textContent === 'subagent',
    };
  });
  expect(tree.headIsHead).toBe(true);
  expect(tree.liveIsChild).toBe(true);
  expect(tree.storeIsChild).toBe(true);
  expect(tree.livePad).toBeGreaterThan(tree.headPad);
  expect(parseFloat(tree.liveGuide)).toBeGreaterThanOrEqual(2);
  expect(tree.liveHollow).toBe(false);
  expect(tree.liveName).toContain('核对后台面板树形缩进断言');
  // 同父兄弟同级：store 子行与 live 子行缩进一致。
  const pads = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    const rows = [...list.querySelectorAll(':scope > .bg-task-row-child')];
    return rows.map((r) => getComputedStyle(r).paddingLeft);
  });
  expect(new Set(pads).size).toBe(1);
  expect(errors).toEqual([]);
});

test('T5 no hollow rows: generic names promoted to task text; orphan keeps a source chip', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgents(page, ROOT_SID, [
    HEAD,
    // 孤儿：父为 C1 内部件（node-*，不可见）→ 提升顶层 + 来源角标。
    { id: 'subagent-orphan-1', info: {
      name: 'subagent', kind: 'Subagent', sessionId: 'subagent-orphan-1',
      status: 'Processing', startedAt: Date.now() - 10_000,
      task: '只读侦察：为面板截图准备素材清单',
      parentSessionId: 'node-deadbeef',
    } },
    // 无任务摘要的泛名行：回落 id（绝不渲染纯「subagent」）。
    { id: 'subagent-orphan-2', info: {
      name: 'subagent', kind: 'Subagent', sessionId: 'subagent-orphan-2',
      status: 'Processing', startedAt: Date.now() - 8_000,
      task: '', parentSessionId: 'node-deadbeef',
    } },
  ]);
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();

  const rows = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    const bySid = (sid) => list.querySelector(`[data-node-session-id="${sid}"]`)
      || list.querySelector(`[data-child-session="${sid}"]`);
    const r1 = bySid('subagent-orphan-1');
    const r2 = bySid('subagent-orphan-2');
    return {
      orphan1: {
        isChild: r1.classList.contains('bg-task-row-child'),
        name: r1.querySelector('.bg-task-name')?.textContent || '',
        chip: r1.querySelector('.bg-task-orphan')?.textContent || '',
        chipTitle: r1.querySelector('.bg-task-orphan')?.getAttribute('title') || '',
      },
      orphan2: {
        isChild: r2.classList.contains('bg-task-row-child'),
        name: r2.querySelector('.bg-task-name')?.textContent || '',
      },
      hollowAnywhere: [...list.querySelectorAll('.bg-task-name')].some((n) => n.textContent === 'subagent'),
    };
  });
  // 孤儿提升顶层（非子行）+ 来源角标（父 = node-* → 「来自 AgentFlow 节点」）。
  expect(rows.orphan1.isChild).toBe(false);
  expect(rows.orphan1.chip).toContain('AgentFlow');
  expect(rows.orphan1.chipTitle).toContain('node-deadbeef');
  expect(rows.orphan1.name).toContain('准备素材清单');
  // 无摘要泛名行：回落 id（subagent-orphan-2），不再是空洞词。
  expect(rows.orphan2.isChild).toBe(false);
  expect(rows.orphan2.name).toBe('subagent-orphan-2');
  expect(rows.hollowAnywhere).toBe(false);
  expect(errors).toEqual([]);
});

test('T6 store child description surfaces (generic def name yields to task text)', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgents(page, ROOT_SID, [HEAD]);
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const store = await page.evaluate(() => {
    const row = document.querySelector('[data-child-session="subagent-store-1"]');
    return {
      name: row?.querySelector('.bg-task-name')?.textContent || '',
      isChild: row?.classList.contains('bg-task-row-child') || false,
    };
  });
  // store 子行泛名（agentName="subagent"）主名 = description 首行。
  expect(store.isChild).toBe(true);
  expect(store.name).toBe('只读侦察：盘点 console 面板结构');
  expect(errors).toEqual([]);
});

test('T7 mobile narrow viewport: the two sections render as two separated cards', async ({ page }) => {
  const errors = watchErrors(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await seedAgents(page, ROOT_SID, [HEAD], { assertBadge: false });
  // 手机层激活（mobile-layer body 类；float dock 的 DOM 改装不在本 harness ——
  // 开面走 hidden 类直开：本测试只断言两节卡面渲染，开合路径归 bgStatusFace 面）。
  await page.evaluate(() => document.body.classList.add('mobile-layer'));
  await page.evaluate(() => document.getElementById('bg-dropdown').classList.remove('hidden'));
  await expect(page.locator('#bg-dropdown')).toBeVisible();

  const cards = await page.evaluate(() => {
    const c = document.getElementById('bg-dropdown');
    const secs = [...c.querySelectorAll(':scope > .bg-section')];
    return {
      count: secs.length,
      radius: secs.map((s) => getComputedStyle(s).borderRadius),
      borders: secs.map((s) => getComputedStyle(s).borderTopWidth),
      margins: secs.map((s) => getComputedStyle(s).marginTop),
      grpBorderTop: getComputedStyle(c.querySelector('.mh-bg-grp')).borderTopWidth,
    };
  });
  expect(cards.count).toBe(2);
  for (const r of cards.radius) expect(parseFloat(r)).toBeGreaterThan(0);
  for (const b of cards.borders) expect(b).not.toBe('0px');
  expect(cards.grpBorderTop).toBe('0px');
  expect(errors).toEqual([]);
});

test('T8 hierarchy: head caret collapses/expands child rows; running head expanded, terminal head collapsed by default', async ({ page }) => {
  const errors = watchErrors(page);
  // 组头 A（进行中，store 子行 ×1）+ 组头 B（已完成，store 子行 ×1）。
  await seedChainFixture(page, {
    rows: [
      { delegateId: 'delegate-fixture-1', taskNo: '42', title: '词条引用系统第二批实施',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0 },
      { delegateId: 'delegate-fixture-2', taskNo: '43', title: '已收尾的旧批次',
        executor: 'nebflow', status: 'completed', project: '', createdAt: T0 },
    ],
    children: {
      'delegate-fixture-1': [
        { taskId: 'subagent-store-1', parentSessionId: 'delegate-fixture-1', agentName: 'subagent',
          prompt: '', description: '只读侦察：盘点 console 面板结构', status: 'running',
          retryCount: 0, spawnedAt: T0, completedAt: null, lastError: '', source: 'subagent' },
      ],
      'delegate-fixture-2': [
        { taskId: 'subagent-store-2', parentSessionId: 'delegate-fixture-2', agentName: 'subagent',
          prompt: '', description: '旧批次的收尾子任务', status: 'completed',
          retryCount: 0, spawnedAt: T0, completedAt: T0, lastError: '', source: 'subagent' },
      ],
    },
  });
  await seedAgents(page, ROOT_SID, [
    HEAD,
    { id: 'delegate-fixture-2', info: {
      name: 'delegate-fixture-2', kind: 'Delegate', sessionId: 'delegate-fixture-2',
      status: 'Idle', done: true, startedAt: T0 - 3600_000,
      parentSessionId: ROOT_SID,
    } },
  ]);
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();

  // 默认规：进行中父（A）展开——子行在场 + caret 展开态；终态父（B）收起——
  // 子行不在场 + caret 收起态。
  const before = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list');
    const carets = [...list.querySelectorAll('[data-bg-caret]')];
    return {
      caretCount: carets.length,
      caretsCollapsed: carets.map((c) => c.classList.contains('is-collapsed')),
      child1Present: !!list.querySelector('[data-child-session="subagent-store-1"]'),
      child2Present: !!list.querySelector('[data-child-session="subagent-store-2"]'),
      ariaLabels: carets.map((c) => c.getAttribute('aria-label') || ''),
    };
  });
  expect(before.caretCount).toBe(2);
  expect(before.child1Present).toBe(true);   // 进行中父默认展开
  expect(before.child2Present).toBe(false);  // 终态父默认收起
  expect(before.caretsCollapsed).toEqual([false, true]);

  // 点 A 的 caret → 收起（子行退出 DOM，偏好跨重渲染保持）。
  await page.click('[data-bg-caret="delegate-fixture-1"]');
  await expect(page.locator('#bg-dropdown [data-child-session="subagent-store-1"]')).toHaveCount(0);
  const collapsedA = await page.evaluate(() => {
    const caret = document.querySelector('[data-bg-caret="delegate-fixture-1"]');
    return {
      isCollapsed: caret.classList.contains('is-collapsed'),
      aria: caret.getAttribute('aria-label') || '',
    };
  });
  expect(collapsedA.isCollapsed).toBe(true);
  expect(collapsedA.aria).toContain('展开');

  // 再点 → 展开还原。
  await page.click('[data-bg-caret="delegate-fixture-1"]');
  await expect(page.locator('#bg-dropdown [data-child-session="subagent-store-1"]')).toHaveCount(1);
  expect(errors).toEqual([]);
});

test('T9 naming: head row shows short name first (title demoted to subline); unnamed rows keep title; popup header carries the row name', async ({ page }) => {
  const errors = watchErrors(page);
  const LONG_TITLE = '液体玻璃实现批·收尾续作（代码已合 main，回归 + 双端截图验证 + 报告落 docs）';
  await seedChainFixture(page, {
    rows: [
      { delegateId: 'delegate-fixture-1', taskNo: '42', title: LONG_TITLE, name: '液体玻璃批',
        executor: 'nebflow', status: 'running', project: '', createdAt: T0 },
    ],
    children: { 'delegate-fixture-1': [] },
  });
  await seedAgents(page, ROOT_SID, [
    { id: 'delegate-fixture-1', info: {
      name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-fixture-1',
      status: 'Processing', startedAt: Date.now() - 30_000,
      task: LONG_TITLE, parentSessionId: ROOT_SID,
      delegateName: '',
    } },
  ]);
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();

  // 行首主名 = 短名（执行器名「nebflow」不再上屏）；标题降为次行；tooltip 承载全文。
  const head = await page.evaluate(() => {
    const row = document.querySelector('#bg-dropdown .bg-section-agents .bg-task-row-head');
    const name = row.querySelector('.bg-task-name');
    const sub = row.querySelector('.bg-task-sub');
    return {
      name: name.textContent || '',
      nameTitle: name.getAttribute('title') || '',
      sub: sub ? sub.textContent || '' : '',
      subPresent: !!sub,
    };
  });
  expect(head.name).toBe('液体玻璃批');
  expect(head.subPresent).toBe(true);
  expect(head.sub).toContain('液体玻璃实现批·收尾续作');
  expect(head.nameTitle).toContain('液体玻璃批');
  expect(head.nameTitle).toContain('液体玻璃实现批');

  // 弹窗头名 = 行首已解析显示名（作者锚点：不再显执行器 def 名「nebflow」）。
  await page.click('#bg-dropdown .bg-section-agents .bg-task-row-head');
  await expect(page.locator('.flow-agent-name').first()).toHaveText('液体玻璃批');
  await page.click('#bgagent-close');

  // 无名旧数据（无 row.name / 无 delegateName）回退既有标题截断显示。
  await seedChainFixture(page, {
    rows: [{ delegateId: 'delegate-fixture-1', taskNo: '42',
      title: LONG_TITLE, executor: 'nebflow', status: 'running', project: '', createdAt: Date.now() }],
    children: { 'delegate-fixture-1': [] },
  });
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  const fallback = await page.evaluate(() => {
    const row = document.querySelector('#bg-dropdown .bg-section-agents .bg-task-row-head');
    const name = row.querySelector('.bg-task-name');
    return { name: name.textContent || '', subPresent: !!row.querySelector('.bg-task-sub') };
  });
  expect(fallback.name).toContain('液体玻璃实现批');
  expect(fallback.subPresent).toBe(false);
  expect(errors).toEqual([]);
});

test('T10 no horizontal scroll: panel and lists fit the viewport at desktop and 375', async ({ page }) => {
  const errors = watchErrors(page);
  // 作者病灶复刻：长批次标题 + 长工具行（Grep 绝对路径）+ 长子任务摘要。
  const LONG_TOOL = 'Grep("object DelegateBudget") (path="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-4cbce603/src")';
  await seedChainFixture(page, {
    rows: [{ delegateId: 'delegate-fixture-1', taskNo: '42', name: '待命态批',
      title: 'delegate 待命态（案A）实施批（作者 10-31 11:10 令 · 审计 + 设计草案 + 前端落地 + 回归合入）',
      executor: 'nebflow', status: 'running', project: '', createdAt: T0 }],
    children: { 'delegate-fixture-1': [
      { taskId: 'subagent-store-1', parentSessionId: 'delegate-fixture-1', agentName: 'subagent',
        prompt: '', description: 'Survey frontend + tests for delegate standby state machine（很长很长的侦察摘要标题用来压行宽断言）',
        status: 'running', retryCount: 0, spawnedAt: T0, completedAt: null, lastError: '', source: 'subagent' },
    ] },
  });
  await seedAgents(page, ROOT_SID, [
    { id: 'delegate-fixture-1', info: {
      name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1',
      status: 'Processing', startedAt: Date.now() - 30_000,
      task: 'delegate 待命态（案A）实施批', parentSessionId: ROOT_SID,
      currentTool: LONG_TOOL,
    } },
  ]);
  const assertNoHScroll = async (label) => {
    // 判据 = 用户可见病灶的两条真值：① 列表/载体 overflow-x 机制恒 hidden（永不
    // 出横向滚动条——「面板要左右拖」的机制根因）；② 行与文本槽的**布局矩形**不越
    // 出列表右缘（省略裁剪不算越界——scrollWidth 会把刻意 clip 的省略文本也算进
    // 来，不作判据）。
    const m = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const lists = [...c.querySelectorAll('.bg-dropdown-list')];
      const vis = (el) => el.getClientRects().length > 0;
      let rectEscape = 0;
      let worst = '';
      for (const l of lists) {
        if (!vis(l)) continue;
        const lr = l.getBoundingClientRect();
        for (const el of l.querySelectorAll('.bg-task-row, .bg-task-row *')) {
          if (!vis(el)) continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0) continue;
          if (r.right > lr.right + 1) { rectEscape++; if (!worst) worst = el.className; }
        }
      }
      return {
        panelOverflowX: getComputedStyle(c).overflowX,
        listOverflowX: lists.map((l) => getComputedStyle(l).overflowX),
        rectEscape,
        worst,
        panelRightBeyondViewport: c.getBoundingClientRect().right > window.innerWidth + 1,
        viewportWidth: window.innerWidth,
        panelWidth: Math.round(c.getBoundingClientRect().width),
      };
    });
    expect(m.panelOverflowX, `${label} panel overflow-x`).toBe('hidden');
    expect(m.listOverflowX, `${label} list overflow-x`).toEqual(['hidden', 'hidden']);
    expect(m.rectEscape, `${label} rect escape (${m.worst})`).toBe(0);
    expect(m.panelRightBeyondViewport, `${label} panel within viewport (w=${m.viewportWidth}, panel=${m.panelWidth})`).toBe(false);
  };
  // 桌面端：两键各开一次（单族视图），断言同样成立。
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await assertNoHScroll('desktop-agents');
  await page.keyboard.press('Escape');
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await assertNoHScroll('desktop-tasks');

  // 375 手机端（mobile-layer 合并单面板全内容同屏）。
  await page.setViewportSize({ width: 375, height: 812 });
  await page.evaluate(() => document.body.classList.add('mobile-layer'));
  await page.evaluate(() => document.getElementById('bg-dropdown').classList.remove('hidden'));
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await assertNoHScroll('mobile-375');
  expect(errors).toEqual([]);
});
