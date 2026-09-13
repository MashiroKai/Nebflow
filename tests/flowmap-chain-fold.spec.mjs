// flowmap-chain-fold.spec.mjs — 链折叠交互验收（链级抽象 P1 · 设计 spec
// 20260910_flowmap-chain-abstraction-spec.md §3.3 方案 b「真折叠」）。
//
// 覆盖（逐条对应 spec 条款）：
//   T1 折叠形态（§3.3 链摘要卡）：折叠链成员卡出图、`.fm-chain-card` 代其在场（同一布局
//      单元）；卡内容 = 状态词（最坏态）+ 链名（后端下发 title）+ `N 节点` + 分状态计数；
//      跨链边重锚到链卡（成员 in 边改锚）；**≥2 可见成员才折叠**（单成员链折叠无收益
//      ——负控：折叠集里的单成员链恒不生成卡、成员恒在场上）；孤立节点/他链零影响。
//   T2 展开 + 持久化（§3.3 折叠状态存储）：点链卡本体 = 展开（成员卡回归）；折叠态写
//      localStorage `nebflow.flowmap.collapsedChains`（按 project 分桶）；折叠态跨 WS
//      增量重渲保持（增量 diff 管线吃的是同一派生视图）。
//   T3 折叠卡内容随成员状态刷新（§3.3「增量 diff 对接：链卡摘要刷新」）：注入成员
//      完成帧 → 卡状态词/计数原地刷新（零整页重渲）。
//   T4 无动画直落直剥（§3.3 2026-09-06 裁定）：折叠重渲无 fm-exit 残留（成员不退场
//      淡出）、无 fm-enter（链卡不淡入）、`.flowmap-card` 持 .fm-noanim（CSS 过渡按住）。
//   T5 任务面板链徽标（§7-B ⭐）：行级胶囊 = 链短名（>12 字符截断）+ 同链同色（色由
//      chainId 稳定散列）；点击徽标 = 跳主图并按 bbox fit 该链（不同于点行=节点定位）；
//      无 chainId 的孤立节点零徽标。
//   T6 折叠态下节点定位跳转自愈（任务行点击）：目标节点被折叠时先展开其链再定位，
//      不留「点了没反应」死路。
//
// 数据源全真：快照 = NodeList 载荷形态（nodes 带条件键 chainId + 顶层 chains 旁挂），
// 事件帧 = 后端契约帧，全部走真实 ws.js 分发路径注入；渲染管线/派生/相机全真。
//
// Run: node node_modules/@playwright/test/cli.js test tests/flowmap-chain-fold.spec.mjs

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

const ROOT_SID = 'fm-chain-root';
const T0 = Date.now();
const COLLAPSE_KEY = 'nebflow.flowmap.collapsedChains';

// 链 a：n1 → n2 → n3（终态 + running 混合 → 最坏态 = 进行中）；跨链出边 n3 → n9。
const N1 = {
  id: 'n1', name: '定位回归', status: 'completed', in: [], chainId: 'chain-a',
  description: '复现并定位登录回归', createdAt: T0, completedAt: T0 + 100, ttlLeftSec: null,
};
const N2 = {
  id: 'n2', name: '修复实现', status: 'completed', in: ['n1'], chainId: 'chain-a',
  description: '提交修复补丁', createdAt: T0 + 200, completedAt: T0 + 300, ttlLeftSec: null,
};
const N3 = {
  id: 'n3', name: '回归验证', status: 'running', in: ['n2'], chainId: 'chain-a',
  description: '全量回归验证', createdAt: T0 + 400, completedAt: null, ttlLeftSec: null,
};
// 链 b：n4 → n5（长链名 → 任务面板徽标截断用例）
const N4 = {
  id: 'n4', name: '插件页脚手架', status: 'completed', in: [], chainId: 'chain-b',
  description: '插件页骨架', createdAt: T0 + 500, completedAt: T0 + 600, ttlLeftSec: null,
};
const N5 = {
  id: 'n5', name: '插件页联调', status: 'pending', in: ['n4'], chainId: 'chain-b',
  description: '与后端联调', createdAt: T0 + 700, completedAt: null, ttlLeftSec: null,
};
// 链 c：可见成员只有 n6（另一成员已归档出库，memberIds 仍含它）——折叠负控。
const N6 = {
  id: 'n6', name: '归档残留', status: 'completed', in: [], chainId: 'chain-c',
  description: '链 c 唯一在场成员', createdAt: T0 + 800, completedAt: T0 + 900, ttlLeftSec: null,
};
// 孤立节点（无 chainId 条件键）：零徽标、零折叠。
const N9 = {
  id: 'n9', name: '独立节点', status: 'pending', in: ['n3'],
  description: '不属于任何多成员链', createdAt: T0 + 1000, completedAt: null, ttlLeftSec: null,
};

const CHAINS = [
  { id: 'chain-a', title: '登录回归修复', entries: ['n1'], ends: ['n3'], memberIds: ['n1', 'n2', 'n3'] },
  { id: 'chain-b', title: '实施-侧边栏插件页迁移-长链名', entries: ['n4'], ends: ['n5'], memberIds: ['n4', 'n5'] },
  { id: 'chain-c', title: '归档单员链', entries: ['n6'], ends: ['n6'], memberIds: ['n6', 'n-archived-1'] },
];

const nodeJson = (over) => ({
  hasWorktree: false, worktree: null, hasResult: false, retries: 0,
  createdAt: T0, completedAt: null, ttlLeftSec: null, ...over,
});

function snapshot() {
  return {
    nodes: [N1, N2, N3, N4, N5, N6, N9].map((n) => structuredClone(n)),
    chains: structuredClone(CHAINS),
    worktrees: [],
    meta: { project: 'alpha', updatedAt: T0 },
  };
}

/** 预置折叠集（§3.3 折叠状态存储按 project 分桶）+ 项目/快照桩。
 *  桩必须在 page.goto **之前**注册：任务面板在 boot 首渲时即拉一次快照
 *  （taskList.refreshNodeSnapshot 单次闸门），boot 后再注册就只剩空清单。 */
async function bootApp(page, { collapsed = [], fm = null } = {}) {
  await page.addInitScript(([key, ids]) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (ids.length) localStorage.setItem(key, JSON.stringify({ alpha: ids }));
  }, [COLLAPSE_KEY, collapsed]);

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
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  // 具体 API 桩后注册（Playwright：后注册者优先）——覆盖上面的通用 /api → {} 兜底。
  if (fm) {
    await page.route('**/api/projects', (r) => r.fulfill({
      json: {
        projects: [{
          name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '',
          createdAt: new Date(T0).toISOString(),
        }],
      },
    }));
    await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: structuredClone(fm) }));
  }

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
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
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

/** 走真实 ws.js 分发路径注入一帧（与后端广播等价）。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 打开 projects 标签页 → 点进 alpha 的 Flow Map 就地视图（同标签页）。 */
async function openFlowMapInPlace(page) {
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
}

/** 任务面板渲染入口（与 sidebar.js 会话切换同入口）。 */
async function renderPanel(page) {
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await page.waitForTimeout(150);
}

test('T1 折叠：成员卡出图 + 链摘要卡在场 + ≥2 成员门槛负控 + 跨链边重锚', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 链 c 预置在折叠集（单成员链负控：控件与卡都不该出现——只能靠预置态触达）
  await bootApp(page, { collapsed: ['chain-c'], fm: snapshot() });
  await openFlowMapInPlace(page);
  const body = page.locator('.flowmap-view-body');

  // 折叠前基线：7 张节点卡、零链卡（链 c 在折叠集但单成员 → 不折叠，n6 仍在场）
  await expect(body.locator('.fm-node')).toHaveCount(7);
  await expect(body.locator('.fm-chain-card')).toHaveCount(0);
  await expect(body.locator('.fm-node[data-node-id="n6"]')).toHaveCount(1);

  // 手动折叠链 a：展开态控件 = 链入口成员卡（n1）head 行的 chevron
  await expect(body.locator('.fm-node[data-node-id="n4"] .fm-chain-chev')).toHaveCount(1); // 链 b 入口
  await expect(body.locator('.fm-node[data-node-id="n6"] .fm-chain-chev')).toHaveCount(0); // 单成员链无控件
  await body.locator('.fm-node[data-node-id="n1"] .fm-chain-chev').click();
  await expect(body.locator('.fm-chain-card[data-chain-id="chain-a"]')).toHaveCount(1);

  // ① 成员卡出图（3 名成员全部离场）
  await expect(body.locator('.fm-node[data-node-id="n1"]')).toHaveCount(0);
  await expect(body.locator('.fm-node[data-node-id="n2"]')).toHaveCount(0);
  await expect(body.locator('.fm-node[data-node-id="n3"]')).toHaveCount(0);
  // ② 他链成员与孤立节点零影响（单成员链 c 不接受折叠 → n6 仍在场）
  await expect(body.locator('.fm-node[data-node-id="n4"]')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n5"]')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n6"]')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n9"]')).toHaveCount(1);
  await expect(body.locator('.fm-chain-card[data-chain-id="chain-c"]')).toHaveCount(0);
  // 卡总数 = 4 张在场节点卡（n4/n5/n6/n9）+ 1 张链卡
  await expect(body.locator('.fm-node')).toHaveCount(5);

  // ③ 卡内容：状态词（最坏态 running）+ 链名（后端 title）+ `N 节点` + 分状态计数
  const card = body.locator('.fm-chain-card[data-chain-id="chain-a"]');
  await expect(card.locator('.fm-st-word')).toHaveText('运行中');
  await expect(card.locator('.solar-node-label')).toHaveText('登录回归修复');
  await expect(card.locator('.solar-node-sub')).toHaveText('3 节点');
  await expect(card.locator('.fm-chain-count.completed')).toHaveText('2');
  await expect(card.locator('.fm-chain-count.running')).toHaveText('1');
  await expect(card.locator('.fm-chain-count.failed')).toHaveCount(0); // 零计数桶不渲染

  // ④ 链内边不画（成员不在场）+ 跨链边重锚到链卡（n9.in = ['n3'] → chain-a）
  await expect(body.locator('path[data-edge-id="n1=>n2"]')).toHaveCount(0);
  await expect(body.locator('path[data-edge-id="n2=>n3"]')).toHaveCount(0);
  await expect(body.locator('path[data-edge-id="chain-a=>n9"]')).toHaveCount(1);
  // 链 b 内部边不受影响
  await expect(body.locator('path[data-edge-id="n4=>n5"]')).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});

test('T2 展开 + 折叠态持久化 + 跨增量重渲保持', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  const serverFm = snapshot();
  await bootApp(page, { fm: serverFm });
  await openFlowMapInPlace(page);
  const body = page.locator('.flowmap-view-body');
  await expect(body.locator('.fm-node')).toHaveCount(7);

  // ① 点链入口卡的 chevron → 折叠（成员出图、链卡在场）
  await body.locator('.fm-node[data-node-id="n1"] .fm-chain-chev').click();
  await expect(body.locator('.fm-chain-card[data-chain-id="chain-a"]')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n1"]')).toHaveCount(0);

  // ② 折叠态落 localStorage（按 project 分桶）
  const stored = await page.evaluate((k) => localStorage.getItem(k), COLLAPSE_KEY);
  expect(JSON.parse(String(stored))).toEqual({ alpha: ['chain-a'] });

  // ③ 折叠态跨 WS 增量重渲保持（增量 diff 吃同一派生视图）
  const n9b = { ...N9, status: 'running' };
  serverFm.nodes = serverFm.nodes.map((n) => (n.id === 'n9' ? structuredClone(n9b) : n));
  await inject(page, { type: 'nodeUpdated', project: 'alpha', nodeId: 'n9', node: nodeJson(n9b) });
  await expect(body.locator('.fm-node[data-node-id="n9"]')).toHaveAttribute('data-status', 'running');
  await expect(body.locator('.fm-chain-card[data-chain-id="chain-a"]')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n2"]')).toHaveCount(0);

  // ④ 点链卡本体 → 展开（成员卡回归 + 边重画）
  await body.locator('.fm-chain-card[data-chain-id="chain-a"]').click();
  await expect(body.locator('.fm-chain-card[data-chain-id="chain-a"]')).toHaveCount(0);
  await expect(body.locator('.fm-node[data-node-id="n1"]')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n3"]')).toHaveCount(1);
  await expect(body.locator('path[data-edge-id="n1=>n2"]')).toHaveCount(1);
  const stored2 = await page.evaluate((k) => localStorage.getItem(k), COLLAPSE_KEY);
  expect(JSON.parse(String(stored2 || '{}'))).toEqual({});

  expect(pageErrors).toEqual([]);
});

test('T3 折叠卡内容随成员状态增量刷新（零整页重渲）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  const serverFm = snapshot();
  await bootApp(page, { collapsed: ['chain-a'], fm: serverFm });
  await openFlowMapInPlace(page);
  const body = page.locator('.flowmap-view-body');
  const card = body.locator('.fm-chain-card[data-chain-id="chain-a"]');
  await expect(card.locator('.fm-st-word')).toHaveText('运行中');
  await expect(card.locator('.fm-chain-count.running')).toHaveText('1');

  // 元素身份探针：增量路径不得整页 innerHTML 重建
  await page.evaluate(() => {
    document.querySelector('.fm-chain-card[data-chain-id="chain-a"]').dataset.probe = 'keep';
    document.querySelector('.fm-node[data-node-id="n9"]').dataset.probe = 'keep';
  });

  // 成员 n3 完成 → 链内全终态：卡状态词翻「已完成」、计数 completed 3、running 桶消失
  const n3b = { ...N3, status: 'completed', hasResult: true, completedAt: Date.now(), ttlLeftSec: 900 };
  serverFm.nodes = serverFm.nodes.map((n) => (n.id === 'n3' ? structuredClone(n3b) : n));
  await inject(page, { type: 'nodeCompleted', project: 'alpha', nodeId: 'n3', node: nodeJson(n3b) });

  await expect(card.locator('.fm-st-word')).toHaveText('已完成');
  await expect(card.locator('.fm-chain-count.completed')).toHaveText('3');
  await expect(card.locator('.fm-chain-count.running')).toHaveCount(0);
  expect(await page.evaluate(() => document.querySelector('.fm-chain-card[data-chain-id="chain-a"]')?.dataset.probe)).toBe('keep');
  expect(await page.evaluate(() => document.querySelector('.fm-node[data-node-id="n9"]')?.dataset.probe)).toBe('keep');

  expect(pageErrors).toEqual([]);
});

test('T4 折叠重渲无动画直落直剥（无退场/入场残留 + CSS 过渡按住）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, { fm: snapshot() });
  await openFlowMapInPlace(page);
  const body = page.locator('.flowmap-view-body');
  await expect(body.locator('.fm-node')).toHaveCount(7);

  await body.locator('.fm-node[data-node-id="n1"] .fm-chain-chev').click();

  // ① 成员「直落」：无 .fm-exit 退场残留（有动画时成员会淡出 380ms 才移除）
  await expect(body.locator('.fm-node.fm-exit')).toHaveCount(0);
  await expect(body.locator('.fm-node[data-node-id="n1"]')).toHaveCount(0);
  // ② 链卡「直剥」：无 .fm-enter 入场淡入
  await expect(body.locator('.fm-node.fm-enter')).toHaveCount(0);
  // ③ CSS 过渡按住（.fm-noanim）：节点位移与画布尺寸过渡同帧关闭
  await expect(page.locator('.flowmap-view-body .flowmap-card.fm-noanim')).toHaveCount(1);
  const trans = await body.locator('.fm-node[data-node-id="n4"]')
    .evaluate((el) => getComputedStyle(el).transitionProperty);
  expect(trans).toBe('none');
  const canvasTrans = await body.locator('.solar-canvas')
    .evaluate((el) => getComputedStyle(el).transitionProperty);
  expect(canvasTrans).toBe('none');

  // ④ 窗口过后自动摘类（不长期抑制正常动画）
  await expect(page.locator('.flowmap-view-body .flowmap-card.fm-noanim')).toHaveCount(0, { timeout: 3000 });

  expect(pageErrors).toEqual([]);
});

test('T5 任务面板链徽标：短名 + 同链同色 + 点击跳主图 fit 该链', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, { fm: snapshot() });
  await renderPanel(page);
  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);
  await page.waitForFunction(() => document.querySelector('#task-list .task-node'), null, { timeout: 5000 });

  // ① 链徽标：紧贴节点名（首行 flex 容器内）+ 链短名（>12 字符截断）
  const row1 = panel.locator('.task-node[data-node-key="alpha:n1"]');
  const row2 = panel.locator('.task-node[data-node-key="alpha:n2"]');
  const badge1 = row1.locator('.task-node-chain');
  await expect(badge1).toHaveCount(1);
  await expect(badge1.locator('.task-node-chain-name')).toHaveText('登录回归修复');
  await expect(badge1.locator('.task-node-chain-dot')).toHaveCount(1);
  await expect(panel.locator('.task-node[data-node-key="alpha:n4"] .task-node-chain-name'))
    .toHaveText('实施-侧边栏插件页迁移-…'); // 12 字符 + 省略号
  // ② 同链同色：链 a 的两行同色类；异链不同色（链 b = c1..c4 之一，且 ≠ 链 a 的值）
  const cls1 = await badge1.getAttribute('class');
  const cls2 = await row2.locator('.task-node-chain').getAttribute('class');
  expect(cls1).toBe(cls2);
  const clsB = await panel.locator('.task-node[data-node-key="alpha:n4"] .task-node-chain').getAttribute('class');
  expect(clsB).not.toBe(cls1);
  // ③ 孤立节点（无 chainId 条件键）零徽标
  await expect(panel.locator('.task-node[data-node-key="alpha:n9"] .task-node-chain')).toHaveCount(0);

  // ④ 点击徽标 = 跳主图并按 bbox fit 该链（不落节点定位语义）
  await badge1.click();
  const body = page.locator('.flowmap-view-body');
  await expect(body).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n1"]')).toHaveCount(1);
  // fit 语义：链 a 三成员全部落在视口内（bbox 收进视口）
  for (const id of ['n1', 'n2', 'n3']) {
    const box = await body.locator(`.fm-node[data-node-id="${id}"]`).boundingBox();
    const vp = await body.locator('.fm-viewport').boundingBox();
    expect(box).not.toBeNull();
    expect(vp).not.toBeNull();
    expect(box.x).toBeGreaterThanOrEqual(vp.x - 1);
    expect(box.x + box.width).toBeLessThanOrEqual(vp.x + vp.width + 1);
    expect(box.y).toBeGreaterThanOrEqual(vp.y - 1);
    expect(box.y + box.height).toBeLessThanOrEqual(vp.y + vp.height + 1);
  }
  // 成员卡闪烁（定位高亮：复用既有 fm-node-flash，零新动画语言）
  await expect(body.locator('.fm-node.fm-node-flash')).not.toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T6 折叠态下任务行点击自愈：先展开该链再定位', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, { collapsed: ['chain-a'], fm: snapshot() });
  await renderPanel(page);
  const panel = page.locator('#task-list');
  await page.waitForFunction(() => document.querySelector('#task-list .task-node'), null, { timeout: 5000 });

  // 点被折叠成员（n3）的任务行 → 打开主图 + 自动展开链 a + 高亮该节点
  await panel.locator('.task-node[data-node-key="alpha:n3"]').click();
  const body = page.locator('.flowmap-view-body');
  await expect(body).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n3"]')).toHaveCount(1);
  await expect(body.locator('.fm-chain-card[data-chain-id="chain-a"]')).toHaveCount(0);
  await expect(body.locator('.fm-node[data-node-id="n3"].fm-node-flash')).toHaveCount(1);
  // 展开一并落 localStorage（不残留脏折叠态）
  const stored = await page.evaluate((k) => localStorage.getItem(k), COLLAPSE_KEY);
  expect(JSON.parse(String(stored || '{}'))).toEqual({});

  expect(pageErrors).toEqual([]);
});
