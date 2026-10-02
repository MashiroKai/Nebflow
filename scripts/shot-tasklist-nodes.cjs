#!/usr/bin/env node
// shot-tasklist-nodes.cjs — 任务列表 Flow Map 节点条目视觉验收截图（亮暗双主题）。
//
// 自包含打桩：page.route 从磁盘服务真实前端文件（不起任何端口、不碰 8080），
// MockWebSocket 注入真实 ws.js 分发路径。产出（v2：绿圈简约化 + team 解耦）：
//   ~/.nebflow/docs/Nebflow/20260902_tasklist-node-entries-v2-dark.png
//   ~/.nebflow/docs/Nebflow/20260902_tasklist-node-entries-v2-light.png
//
// 2026-10-01 随改随对（chainview-ui-impl-r2 批）：原 `taskListUpdate` /
// `teamTaskListUpdate` 注入随旧任务区退役**已无渲染路径**（taskList.js 无 handler、
// ws.js 无路由表项，见 tests/tasklist-nodes.spec.mjs T7），继续注入只是死代码 ⇒
// 删除该段，改装**链视图**夹具：快照 `chains` 旁挂 + 节点 `chainId` 条件键，
// 覆盖折叠链行（28px）/ 展开成员行（22px）/ 徽标五态 / 未分组组。
// 🔴 本脚本只做视觉取证；像素/滚动读数归 evidence 的 chainview-measure.cjs。
//
// Run: node scripts/shot-tasklist-nodes.cjs

const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join, dirname, extname, normalize } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(process.env.HOME, '.nebflow', 'docs', 'Nebflow');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'shot-root';
const now = Date.now();
const node = (over) => ({
  hasWorktree: false, worktree: null, result: null, retries: 0,
  createdAt: now, completedAt: null, startedAt: null, ttlLeftSec: null, ...over,
});

// ── 链视图夹具（chainview 批）：两条链（活跃 4 位 + 暂停 1 位）+ 两个无链节点 ──
const A = now - 400_000;
const CHAIN_NODES = [
  { project: 'nebflow-project', node: node({ id: 'c-disp', name: 'dispatch', agent: 'general', status: 'completed', createdAt: A, completedAt: A + 60_000, ttlLeftSec: 300, chainId: 'chain-a', role: 'task', in: [], deps: [] }) },
  { project: 'nebflow-project', node: node({ id: 'c-impl', name: 'chainview-impl', agent: 'general', status: 'running', createdAt: A + 70_000, startedAt: A + 70_000, chainId: 'chain-a', role: 'task', in: ['c-disp'], deps: ['c-disp'] }) },
  { project: 'nebflow-project', node: node({ id: 'c-verify', name: 'chainview-verify', agent: 'general', status: 'pending', createdAt: A + 80_000, chainId: 'chain-a', role: 'verifier', in: ['c-impl'], deps: ['c-impl'] }) },
  { project: 'nebflow-project', node: node({ id: 'c-sink', name: 'merge-sink', agent: 'general', status: 'pending', createdAt: A + 90_000, chainId: 'chain-a', role: 'task', merge: true, in: ['c-verify'], deps: ['c-verify'] }) },
  { project: 'nebflow-project', node: node({ id: 'd-one', name: 'paused-work', agent: 'general', status: 'running', createdAt: A + 100_000, startedAt: A + 100_000, chainId: 'chain-b' }) },
  { project: 'czt-project', node: node({ id: 'u-kernel', name: 'kernel-boot', agent: 'general', status: 'running', createdAt: A + 110_000, startedAt: A + 110_000 }) },
  { project: 'czt-project', node: node({ id: 'u-idle', name: 'idle-probe', agent: 'general', status: 'pending', createdAt: A + 120_000 }) },
];
const CHAINS = [
  { project: 'nebflow-project', chain: { id: 'chain-a', title: 'chainview', entries: ['c-disp'], ends: ['c-sink'], memberIds: ['c-disp', 'c-impl', 'c-verify', 'c-sink'], status: 'active' } },
  { project: 'nebflow-project', chain: { id: 'chain-b', title: 'paused-chain', entries: ['d-one'], ends: ['d-one'], memberIds: ['d-one'], status: 'paused' } },
];

(async () => {
  const browser = await chromium.launch();
  for (const colorScheme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'shot-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send() {} close() {} onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
    });

    // 从磁盘服务真实前端（含全部 ES module 相对导入），API 打桩
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/')) {
        if (p === '/api/projects') {
          return route.fulfill({ json: { projects: [{ name: 'nebflow-project', workspace: '/w/nb', agentFile: 'nebflow-project', description: '', createdAt: new Date(now).toISOString() }, { name: 'czt-project', workspace: '/w/czt', agentFile: 'czt-project', description: '', createdAt: new Date(now).toISOString() }] } });
        }
        if (/\/flow-map$/.test(p)) {
          const proj = p.split('/')[3];
          const mine = CHAIN_NODES.filter((n) => n.project === proj);
          return route.fulfill({ json: { nodes: mine.map((e) => e.node), worktrees: [], chains: CHAINS.filter((c) => c.project === proj).map((c) => c.chain), meta: { project: proj, updatedAt: now } } });
        }
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

    await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);

    const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
    await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
    await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    await page.waitForTimeout(400);

    // 节点入口：先 renderTaskList 起渲染管线（首渲会拉快照 —— 上面已按项目桩住
    // `/api/projects` 与 `/flow-map`，链旁挂随快照到达），再补 WS 帧。
    await page.evaluate(async (sid) => {
      const { renderTaskList, refreshNodeSnapshot } = await import('/js/taskList.js');
      renderTaskList([], undefined, sid);
      await refreshNodeSnapshot();
    }, ROOT_SID);
    for (const e of CHAIN_NODES) {
      await df({ type: 'nodeCreated', project: e.project, nodeId: e.node.id, node: e.node });
    }

    const panel = page.locator('#task-list');
    await panel.waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForFunction(() => document.querySelectorAll('#task-list .task-chain').length >= 2, null, { timeout: 8000 });
    // 展开首链 → 成员行入镜（截图同时含折叠态与展开态）
    await page.locator('.task-chain[data-chain-id="chain-a"]').click();
    await page.waitForTimeout(500); // 入场动画收敛

    const out = join(OUT_DIR, `20260902_tasklist-node-entries-v2-${colorScheme}.png`);
    await panel.screenshot({ path: out });
    console.log(`saved ${out}`);
    await page.close();
  }
  await browser.close();
  console.log('done');
})().catch((e) => { console.error(e); process.exit(1); });
