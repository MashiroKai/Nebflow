// bg-flow-chain-visibility.spec.mjs — 后台代理面板 Flow 会话可见性批（2026-11-01
// 作者令「控制台后台代理面板修复」）验收。
//
// 症状（作者 15:18 实证）：分发器模式的链会话（kind=Flow：dispatcher-*/node-*）
// 对用户完全不可见——引擎 AgentControl 全部在册，前端面板零渲染；spawn 它们的
// delegate 会话终态=completed 后，活链在前端无任何痕迹（用户视角=批死了）。
//
// 根因两面：① C1 过渡过滤（delegate-ui 批 2026-10-08）把 dispatcher-*/node-* 整体
// 滤出陈列面；② delegate 作用域链的会话归桶 `sessionBgAgents[delegateSid]`
// （引擎 DelegateFlowHosts 自根事实），该桶不是任何视图的桶——整桶不可见。
//
// 断言面（作者令四条）：
//   T1 溯源分组 …… delegate 组头 + 作用域桶链行挂为缩进子行（分发器置顶；kind
//      徽标=分发器/节点；各带状态/运行时长）；徽标计数含链行。
//   T2 延续态（live 行终态在场）…… delegate 行 done 而链仍活 → 组头琥珀「链运行
//      中」（非 done 灰化、状态点 active）+ 子行在列。
//   T3 延续态（live 行已离场）…… 主桶空、注册行镜像 completed、作用域桶有活链
//      → 注册行合成延续组头（短名/mode 徽标承镜像）+「链运行中」+ 链子行；徽标
//      计数 > 0（活链在场时面板绝不空）。
//   T4 链全终态 …… 作用域桶行全 done → 合成组头消失、面板回空（延续徽标绝不
//      滞留死链）。
//   T5 无主 Flow 行 …… 主桶内无归属 dispatcher-*/node-* → 顶层行直显 + kind 徽标
//      （分发器/节点），计数照计（C1 过滤退役）。
//   T6 console 零错误 …… 全程 pageerror/console.error 均空。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows），零后端、零 sbt。
// Run: node node_modules/@playwright/test/cli.js test tests/bg-flow-chain-visibility.spec.mjs

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

const ROOT_SID = 'fcv-root-session';
const DELEGATE_SID = 'delegate-flow-a1b2c3d4';
const T0 = Date.now();

/** 作用域桶链行（真实 routeSubagentWsSend 形：rootSessionId=delegateSid 自根）。 */
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
/** 主桶行（delegate/子代理同构）。 */
function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 注册行镜像夹具（DelegateRow 形——stub schema 加宽形）。 */
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
    // SW 逃生门（应用自带开关）：service worker 的预取请求不经 page.route mock，
    // 会以真实网络 404 污染 console——零 console error 断言必须关注册。
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
      // rootName 探测（initRootNameProbe）：agents/<root>/agent.json 静态文件逐字
      // fetch（cache: no-store）——mock 缺席 = 真实 404 帧两次（boot + 重连 reprobe），
      // Chrome 逐帧记 console error「Failed to load resource」。零 console error
      // 断言必须关注册；{} = displayName 缺席 → 未命名判读（fail 方向，零副作用）。
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
const rowSel = (sel) => `${LIST} .bg-task-row${sel || ''}`;

/** 采集页面错误（pageerror + console.error），用例尾断言（T6）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

test('T1 溯源分组：作用域桶链行挂组头子行（分发器置顶 + 分发器/节点徽标 + 运行时长）＋计数含链行', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'running' })] });
  // delegate live 行（主桶，进行中）+ 作用域桶链行（dispatcher Idle 等链 + node ×2 Processing）。
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer', taskDescription: '分发器链批次——回流卡收编实施' }));
  await inject(page, chainStart('dispatcher-20b0e8ea', { status: 'Processing', name: 'dispatcher/Nebflow' }));
  await inject(page, chainStart('node-7846cc03', { taskDescription: '只读侦察：盘点回流卡消费点' }));
  await inject(page, chainStart('node-eb2a5aa3', { taskDescription: '实现：回流卡收编（含 wire 探针）' }));
  await openPanel(page);

  // 组头 + 3 条链子行；分发器置顶（作者口径「分发器 + 节点」）。
  const rows = page.locator(`${LIST} > .bg-task-row`);
  await expect(rows).toHaveCount(4);
  const head = page.locator(`${LIST} > .bg-task-row.bg-task-row-head`);
  await expect(head).toHaveAttribute('data-node-session-id', DELEGATE_SID);
  // 组头徽标 = delegate 执行形态 row.mode（延续既有归位批铁律）。
  await expect(head.locator('.bg-task-kind')).toHaveText('dispatcher');
  const kids = page.locator(`${LIST} > .bg-task-row.bg-task-row-child`);
  await expect(kids.nth(0)).toHaveAttribute('data-node-session-id', 'dispatcher-20b0e8ea');
  await expect(kids.nth(0).locator('.bg-task-kind')).toHaveText('分发器');
  await expect(kids.nth(1)).toHaveAttribute('data-node-session-id', 'node-7846cc03');
  await expect(kids.nth(1).locator('.bg-task-kind')).toHaveText('节点');
  await expect(kids.nth(2)).toHaveAttribute('data-node-session-id', 'node-eb2a5aa3');
  await expect(kids.nth(2).locator('.bg-task-kind')).toHaveText('节点');
  // 各带运行时长（uptime 槽在列——startedAt 属性行级可刷新）。
  await expect(kids.nth(0).locator('.bg-task-uptime[data-started-at]')).toHaveCount(1);
  await expect(kids.nth(1).locator('.bg-task-uptime[data-started-at]')).toHaveCount(1);
  // 子行缩进一格（C2 两级语义）。
  const headPad = await head.evaluate((el) => parseFloat(getComputedStyle(el).paddingLeft));
  const kidPad = await kids.nth(0).evaluate((el) => parseFloat(getComputedStyle(el).paddingLeft));
  expect(kidPad).toBeGreaterThan(headPad);
  // 计数签 = 主桶可见行（delegate）∪ 链行（3）= 4。
  await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('4');
  // 面板节签与徽标同值（后台合并批载体：智能体节签行数现算——MutationObserver
  // 同源；「后台任务」头属任务节，不含智能体计数）。
  await expect(page.locator('#bg-dropdown .bg-section-agents .mh-bg-grp')).toContainText('· 4');
  expect(errors).toEqual([]);
});

test('T2 延续态（live 行终态在场）：delegate done 而链仍活 → 琥珀「链运行中」非灰化 + 子行在列', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'completed' })] });
  await inject(page, rootStart(DELEGATE_SID, { name: 'Explorer', taskDescription: '分发器链批次' }));
  await inject(page, chainStart('dispatcher-20b0e8ea'));
  await inject(page, chainStart('node-7846cc03'));
  // delegate 终态（agentDone 同构——done 置位，2s 清理前断言）。
  await inject(page, { type: 'agentDone', sessionId: ROOT_SID, rootSessionId: ROOT_SID, agentId: DELEGATE_SID, nodeSessionId: DELEGATE_SID });
  await openPanel(page);

  const head = page.locator(`${LIST} > .bg-task-row.bg-task-row-head`);
  await expect(head).toHaveAttribute('data-node-session-id', DELEGATE_SID);
  // 延续徽标：琥珀词「链运行中」+ warn 图标档 + active 点；行不挂 .done（不灰化）。
  await expect(head.locator('.bg-task-state.bg-task-chain')).toHaveText('链运行中');
  await expect(head.locator('.bg-task-chain .st-ic')).toHaveCount(1);
  await expect(head).not.toHaveClass(/done/);
  await expect(head.locator('.bg-task-status')).toHaveClass(/bg-status-active/);
  // 链子行在列（默认展开——活链可见即本批目的）。
  await expect(page.locator(`${LIST} > .bg-task-row.bg-task-row-child`)).toHaveCount(2);
  expect(errors).toEqual([]);
});

test('T3 延续态（live 行已离场）：主桶空 + 注册行镜像 → 快照恢复链行合成延续组头「链运行中」，徽标计数 > 0', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'completed' })] });
  // 只种作用域桶链行（delegate live 行已终态离场——agentDone 2s 清理的终局面）。
  // 帧 = activeAgents 快照恢复路径的真实形状（刷新后链行按 rootSessionId 归桶
  // delegateSid；快照处理器尾拍无条件刷 activeView 徽标——延续态在刷新后自愈）。
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: 'dispatcher-20b0e8ea', agentId: 'dispatcher-20b0e8ea', agentName: 'dispatcher/Nebflow', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Idle', startedAt: T0 - 12 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'node-7846cc03', agentId: 'node-7846cc03', agentName: '侦察节点', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '', task: '只读侦察：盘点回流卡消费点' },
      { sessionId: 'node-eb2a5aa3', agentId: 'node-eb2a5aa3', agentName: '实现节点', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 6 * 60_000, project: 'Nebflow', parentSessionId: '', task: '实现：回流卡收编（含 wire 探针）' },
    ],
  });
  await openPanel(page);

  // 合成延续组头：短名「链批」承镜像 + mode 徽标 dispatcher + 琥珀「链运行中」。
  const head = page.locator(`${LIST} > .bg-task-row.bg-task-row-head`);
  await expect(head).toHaveAttribute('data-node-session-id', DELEGATE_SID);
  await expect(head.locator('.bg-task-name')).toHaveText('链批');
  await expect(head.locator('.bg-task-kind')).toHaveText('dispatcher');
  await expect(head.locator('.bg-task-state.bg-task-chain')).toHaveText('链运行中');
  await expect(head.locator('.bg-task-caret')).toHaveCount(1);
  // 3 条链子行在列（分发器置顶）；面板绝不为空（活链在场）。
  const kids = page.locator(`${LIST} > .bg-task-row.bg-task-row-child`);
  await expect(kids).toHaveCount(3);
  await expect(kids.nth(0)).toHaveAttribute('data-node-session-id', 'dispatcher-20b0e8ea');
  await expect(kids.nth(0).locator('.bg-task-kind')).toHaveText('分发器');
  await expect(kids.nth(1)).toHaveAttribute('data-node-session-id', 'node-7846cc03');
  await expect(kids.nth(1).locator('.bg-task-kind')).toHaveText('节点');
  // 徽标计数 = 链行 3 + 合成组头 1 = 4（主桶 0 行——「批死了」症状的反面钉）。
  await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('4');
  // 合成组头行体点击 = delegate 详情弹窗（历史恢复承载）。
  await head.click();
  await expect(page.locator('.flow-agent-overlay, .bgagent-delegate-strip').first()).toBeVisible({ timeout: 8000 });
  expect(errors).toEqual([]);
});

test('T4 链全终态：合成组头消失、面板回空——延续徽标绝不滞留死链', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [mirrorRow({ status: 'completed' })] });
  await inject(page, {
    type: 'activeAgents',
    agents: [
      { sessionId: 'dispatcher-20b0e8ea', agentId: 'dispatcher-20b0e8ea', agentName: 'dispatcher/Nebflow', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Idle', startedAt: T0 - 12 * 60_000, project: 'Nebflow', parentSessionId: '' },
      { sessionId: 'node-7846cc03', agentId: 'node-7846cc03', agentName: '侦察节点', rootSessionId: DELEGATE_SID, kind: 'Flow', status: 'Processing', startedAt: T0 - 9 * 60_000, project: 'Nebflow', parentSessionId: '' },
    ],
  });
  await openPanel(page);
  await expect(page.locator(`${LIST} > .bg-task-row.bg-task-row-head`)).toHaveCount(1);
  // 链全终态（会话级 done——node-*/dispatcher-* 行立即删除，既有终态管线）。
  await inject(page, { type: 'done', sessionId: 'node-7846cc03' });
  await inject(page, { type: 'done', sessionId: 'dispatcher-20b0e8ea' });
  await page.waitForTimeout(300);
  await expect(page.locator(`${LIST} > .bg-task-row`)).toHaveCount(0);
  await expect(page.locator('#bgagent-indicator')).toHaveClass(/hidden/);
  expect(errors).toEqual([]);
});

test('T5 无主 Flow 行顶层直显：分发器/节点 kind 徽标 + 计数照计（C1 过滤退役）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page);
  await setFixture(page, { rows: [] });
  // 主桶直派的项目域链（挂载根 = 主会话；行无 parentSessionId → 无主）。
  await inject(page, chainStart('dispatcher-9c4d1e2f', { sessionId: ROOT_SID, rootSessionId: ROOT_SID }));
  await inject(page, chainStart('node-7a1b2c3d', { sessionId: ROOT_SID, rootSessionId: ROOT_SID }));
  await openPanel(page);

  const rows = page.locator(`${LIST} > .bg-task-row`);
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).not.toHaveClass(/bg-task-row-head/);
  await expect(rows.nth(0)).not.toHaveClass(/bg-task-row-child/);
  await expect(rows.nth(0).locator('.bg-task-kind')).toHaveText('分发器');
  await expect(rows.nth(1).locator('.bg-task-kind')).toHaveText('节点');
  await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('2');
  expect(errors).toEqual([]);
});
