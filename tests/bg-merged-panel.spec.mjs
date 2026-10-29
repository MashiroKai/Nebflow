// bg-merged-panel.spec.mjs — 后台合并面板验收（作者令 2026-10-23 ⑥：后台任务 +
// 后台子智能体两面板归并为**一个后台面板**载体）· 面板分区/树/说明三修批
// （2026-10-29 作者报障三件）：
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
  await page.evaluate((fixture) => {
    import('/js/delegateChainStub.js').then((m) => {
      m.__setFixture(fixture);
    });
  }, fx);
}

/** 种子智能体行（renderBgAgentDropdown 数据面直种）并刷新指示器。 */
async function seedAgents(page, sid, entries) {
  await page.evaluate(({ sid, entries }) => {
    import('/js/state.js').then((m) => {
      const bucket = {};
      for (const e of entries) bucket[e.id] = e.info;
      m.default.sessionBgAgents[sid] = bucket;
      m.default.updateBgAgentIndicator();
    });
  }, { sid, entries });
  await expect(page.locator('#bgagent-indicator')).toBeVisible();
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

test('T2 both header keys open the ONE carrier; both sections render; Esc closes', async ({ page }) => {
  const errors = watchErrors(page);
  await seedAgents(page, ROOT_SID, [HEAD]);

  // bg 键 → 面板开（任务节 1 行 + 智能体节组头 1 行 + store 子行 1 行）。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const taskRows = page.locator('#bg-dropdown .bg-section-tasks .bg-task-row');
  await expect(taskRows).toHaveCount(1);
  await expect(taskRows.first()).toContainText('合并面板样例任务');
  const agentRows = page.locator('#bg-dropdown .bg-section-agents .bg-task-row');
  await expect(agentRows).toHaveCount(2); // 组头 + store 子行
  await expect(agentRows.first()).toContainText('词条引用系统第二批实施');
  await expect(page.locator('#bg-dropdown .bg-section-tasks .bg-dropdown-header')).toHaveText('后台任务 · 1');
  // 分区签带实时计数（面板分区批 ③）。
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeVisible();
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toHaveText('智能体 · 2');

  // Esc → 关（桌面既有 Esc 链：幽灵面（恒隐）跳过 → 载体收）。
  await page.keyboard.press('Escape');
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);

  // bgagent 键 → 同一载体再开（幽灵面不落地）。
  await page.click('#bgagent-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bgagent-dropdown')).toBeHidden();
  await expect(agentRows).toHaveCount(2);
  await expect(page.locator('#bgagent-indicator')).toHaveAttribute('aria-expanded', 'true');

  // 外点 → 关（桌面 outside-close 同源）。
  await page.mouse.click(10, 400);
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);
  expect(errors).toEqual([]);
});

test('T3 empty agents section hides its group label; panel stays for tasks (merged semantics)', async ({ page }) => {
  const errors = watchErrors(page);
  // 零子智能体：开面板 → 「智能体」签隐藏，任务节照常。
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  await expect(page.locator('#bg-dropdown .mh-bg-grp')).toBeHidden();
  await expect(page.locator('#bg-dropdown .bg-section-tasks .bg-task-row')).toHaveCount(1);

  // 种入子智能体 → 签即现（MutationObserver 空节收签的反向腿）。
  await seedAgents(page, ROOT_SID, [HEAD]);
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
  await seedAgents(page, ROOT_SID, [HEAD]);
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
