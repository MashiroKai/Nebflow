// flowmap-realtime.spec.mjs — Flow Map 实时更新 + 变化动画验收（WS 事件驱动增量渲染）。
//
// 用户需求三点，逐条对应：
//   ① 实时看到节点变化（新增/消失/接线更改）  → T1/T2/T3/T4
//   ② 变化有流畅动画（淡入/淡出/连线生长/状态平滑过渡，首尾无跳变）
//                                            → T2 采样中间态 + T5 元素身份不变（增量
//                                              渲染不重建 DOM，轨道动画不打断）
//   ③ 不切换标签页也刷新（视图打开状态下后台事件实时渲染）
//                                            → 全部事件注入期间无任何导航/点击
//
// 事件帧 = 后端契约帧 {type, project, nodeId, node}（node 为 NodeList 同构 JSON，
// NodePayload.buildNodeJson——上游 NodeEventPushSpec 5/5 绿证明后端确实广播这些帧），
// 这里用真实 ws.js 分发路径注入（s.ws.onmessage），前端管线（ws.js → flowMapTab
// 增量 diff → 动画 → 对账拉取）全真。
//
// Run: node node_modules/@playwright/test/cli.js test tests/flowmap-realtime.spec.mjs

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

const ROOT_SID = 'fm-root-session';
const T0 = Date.now();

// 初始快照：n1(completed) → n2(running)。
// 2026-09-05 载荷收敛：快照/事件载荷元数据 only——无 result 键；hasResult 标记 +
// description（创建必写）为卡片展示层。
const N1 = {
  id: 'n1', name: '研究', agent: 'researcher', status: 'completed',
  in: [], out: 'n2', hasWorktree: false, worktree: null,
  description: '选题调研与定题', hasResult: true, retries: 0, createdAt: T0, completedAt: T0, ttlLeftSec: 300,
};
const N2 = {
  id: 'n2', name: '写作', agent: 'writer', status: 'running',
  in: ['n1'], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '初稿撰写节点', retries: 0, createdAt: T0, completedAt: null, ttlLeftSec: null,
};
const FM_A = () => ({ nodes: [structuredClone(N1), structuredClone(N2)], worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() } });
const nodeJson = (over) => ({
  hasWorktree: false, worktree: null, hasResult: false, retries: 0,
  createdAt: T0, completedAt: null, ttlLeftSec: null, ...over,
});

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
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
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

test.beforeEach(() => {});

test('Flow Map 实时更新：新节点淡入 / 接线重绘 / 完成过渡 / 消失淡出，全程不切标签页', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  let fmSnapshots = 0;
  await bootApp(page);
  // 服务端权威快照：事件先落服务端再广播——对账拉取必须看到事件后的状态，
  // 否则（旧快照）会把增量应用过的节点回滚掉。这里让桩随事件同步演化。
  let serverFm = FM_A();
  const patchServer = (id, over) => {
    const n = serverFm.nodes.find((x) => x.id === id);
    if (n) Object.assign(n, over);
  };
  // 具体接口桩后注册（覆盖 bootApp 的通用 /api → {}）
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => {
    fmSnapshots += 1;
    return r.fulfill({ json: structuredClone(serverFm) });
  });

  await openFlowMapInPlace(page);
  const body = page.locator('.flowmap-view-body');
  await expect(body.locator('.fm-node')).toHaveCount(2);
  await expect(body.locator('path[data-edge-id]')).toHaveCount(1); // n1=>n2
  // 项目列表卡片摘要也会拉一次 flow-map，快照基线取「视图打开完成」时点
  const snapshotsAtOpen = fmSnapshots;

  // 元素身份探针：增量渲染不得整页 innerHTML 重建（重建 = 动画/轨道全被打断）
  await page.evaluate(() => {
    document.querySelector('.fm-node[data-node-id="n1"]').dataset.probe = 'keep';
  });

  // ── 需求①③：视图保持打开，注入后台节点事件（无任何点击/导航）─────────
  // 1) nodeCreated n3（接线 n1→n3→n2）+ wiring 变更（n1 out 改指、n2 in 追加）
  const N3 = nodeJson({ id: 'n3', name: '评审', agent: 'reviewer', status: 'pending', in: ['n1'], out: 'n2' });
  serverFm.nodes.push(structuredClone(N3));
  patchServer('n1', { out: 'n3' });
  patchServer('n2', { in: ['n3'] });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n3', node: structuredClone(N3) });

  // ── 需求②：入场动画采样（趁 0.35s 过渡窗口内：淡入中间态 opacity < 1）──
  await page.waitForFunction(() => {
    const el = document.querySelector('.fm-node[data-node-id="n3"]');
    return el && parseFloat(getComputedStyle(el).opacity) < 1;
  }, null, { timeout: 800 });

  await inject(page, { type: 'nodeUpdated', project: 'alpha', nodeId: 'n1', node: nodeJson({ ...N1, out: 'n3' }) });
  await inject(page, { type: 'nodeUpdated', project: 'alpha', nodeId: 'n2', node: nodeJson({ ...N2, in: ['n3'] }) });

  // 需求①：新节点立即可见（同帧渲染，无需刷新）
  await expect(body.locator('.fm-node[data-node-id="n3"]')).toHaveCount(1);
  // 接线更改实时可见：旧边 n1=>n2 消失，新边 n1=>n3、n3=>n2 出现
  await expect(body.locator('path[data-edge-id="n1=>n2"]')).toHaveCount(0);
  await expect(body.locator('path[data-edge-id="n1=>n3"]')).toHaveCount(1);
  await expect(body.locator('path[data-edge-id="n3=>n2"]')).toHaveCount(1);

  // 淡入收敛到 1（过渡结束无残留）
  await page.waitForFunction(() => {
    const el = document.querySelector('.fm-node[data-node-id="n3"]');
    return el && getComputedStyle(el).opacity === '1';
  }, null, { timeout: 2000 });

  // 布局重排（n2 下移一层）走 left/top 过渡，而不是瞬跳
  const transitionProp = await page.evaluate(() => getComputedStyle(document.querySelector('.fm-node[data-node-id="n2"]')).transitionProperty);
  expect(transitionProp).toContain('left');
  expect(transitionProp).toContain('top');

  // ── 需求②佐证（v3 重写：核验点前移至链齐退场窗口之前）──────────────
  // 全程增量，n1 经 nodeCreated(n3) + 接线重绘多轮增量渲染后元素身份未变
  //（探针存活）。原核验点位于 nodeRemoved 之后，v3 链齐整链退场下 n1 已随
  // 链移除（见步骤 3 注），故前移至此。
  const probeEarly = await page.evaluate(() => document.querySelector('.fm-node[data-node-id="n1"]')?.dataset.probe);
  expect(probeEarly).toBe('keep');

  // 2) nodeCompleted n2：状态平滑过渡（class/dataset 原地更新）。2026-09-05 载荷
  //    收敛：完成帧不带 result 本体（hasResult 标记）——卡片描述行展示 description，
  //    全文经详情窗按需拉取（由 flowmap-archive-result-full spec 承载）。
  patchServer('n2', { status: 'completed', hasResult: true, completedAt: Date.now(), ttlLeftSec: 300 });
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'n2',
    node: nodeJson({ ...N2, in: ['n3'], status: 'completed', hasResult: true, completedAt: Date.now(), ttlLeftSec: 300 }),
  });
  await expect(body.locator('.fm-node[data-node-id="n2"][data-status="completed"]')).toHaveCount(1);
  // 卡片描述行 = description（创建必写元数据）；result 摘要行不复存在
  await expect(body.locator('.fm-node[data-node-id="n2"] .fm-desc')).toContainText('初稿撰写节点');
  await expect(body.locator('.fm-node[data-node-id="n2"] .fm-result-summary')).toHaveCount(0);

  // 边状态档位跟随上游：n3 完成 → 它发出的边 n3=>n2 转 delivered（idle→delivered 平滑变色调）
  patchServer('n3', { status: 'completed', hasResult: true, completedAt: Date.now(), ttlLeftSec: 300 });
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'n3',
    node: nodeJson({ ...N3, status: 'completed', hasResult: true, completedAt: Date.now(), ttlLeftSec: 300 }),
  });
  await expect(body.locator('path[data-edge-id="n3=>n2"].delivered')).toHaveCount(1);
  await expect(body.locator('circle[data-edge-id="n3=>n2"].delivered')).toHaveCount(1);

  // 3) v3 链齐整链退场语义（规格 §3.4/§6.1b/§7.6，[merge] T1 重写对齐 v3）：
  //    n3 完成使批次链 {n1,n2,n3} 链齐 → 整链成员同一帧 fm-exit（350ms 一条
  //    整体动画）→ 统一渲染后整链从主图移除。旧「nodeRemoved 逐卡淡出」断言
  //    为 pre-v3 过期语义（QA v2 §2.5 代码级 traced：v3 下该时刻 n3 已随链
  //    退场，旧谓词永假）。整链退场动画细节由归档 spec A16（绿）覆盖，此处
  //    哨兵 steady-state：
  await expect(body.locator('.fm-node[data-node-id="n3"]')).toHaveCount(0, { timeout: 3000 });
  await expect(body.locator('path[data-edge-id="n1=>n3"]')).toHaveCount(0);
  await expect(body.locator('path[data-edge-id="n3=>n2"]')).toHaveCount(0);
  await expect(body.locator('.fm-node')).toHaveCount(0); // 整链清场：主图可见集 = ∅

  // nodeRemoved n3（链归档后出库）：v3 收缩为出库墓碑记账（§7.2）。哨兵：
  // 已归档成员不得被退场动画复活回主图——refreshChains 成员集幂等跳过 /
  // 出库重建退场两条路径结束后，主图都必须维持零驻留卡。
  serverFm.nodes = serverFm.nodes.filter((x) => x.id !== 'n3');
  await inject(page, { type: 'nodeRemoved', project: 'alpha', nodeId: 'n3', node: structuredClone(N3) });
  await expect(body.locator('.fm-node')).toHaveCount(0, { timeout: 3000 });

  // 对账拉取兜底：事件后 600ms 防抖全量快照到达；稳态仍零复活（v3 口径）
  await page.waitForTimeout(900);
  expect(fmSnapshots).toBeGreaterThanOrEqual(snapshotsAtOpen + 1);
  await expect(body.locator('.fm-node')).toHaveCount(0);

  // 需求③：整个流程视图始终是同一份就地 Flow Map（未切走）
  await expect(page.locator('.canvas-tab-pane.active .flowmap-view-body')).toHaveCount(1);
  expect(pageErrors).toEqual([]);
});

test('断线重连后对打开的 Flow Map 视图重拉权威快照（缺口收敛）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_A() }));

  await openFlowMapInPlace(page);
  await expect(page.locator('.flowmap-view-body .fm-node')).toHaveCount(2);

  // 真实重连：forceReconnect() → 新连接 onopen → ws.js 重连回调（flowMapTab 注册的
  // refreshFlowMapViews）→ 打开中的视图重拉权威快照
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.forceReconnect();
  });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 10000 });
  await page.waitForTimeout(700);
  // 视图仍在（重拉后 diff 无变化 → 无跳变，节点数不变）
  await expect(page.locator('.flowmap-view-body .fm-node')).toHaveCount(2);
  expect(pageErrors).toEqual([]);
});
