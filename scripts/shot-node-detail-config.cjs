#!/usr/bin/env node
// shot-node-detail-config.cjs — 节点详情完整配置批（2026-09-07）视觉验收截图。
//
// 三类样例 × 明暗双主题：普通节点 / 多插件节点（≥2 plugins）/ worktree+loop 节点，
// 点击进详情的完整配置窗（配置/拓扑/执行形态三分区）。
//
// 自包含打桩（同 shot-tasklist-nodes.cjs 路线）：page.route 从磁盘服务真实前端
// 文件（不起任何端口、不碰 8080），MockWebSocket 注入真实 ws.js 分发路径，
// /api/projects/<p>/flow-map 与 nodes/<id>/result 两端点按 fixture 应答。
// 详情经生产入口 flowMapArchive.openDetailFor 打开（= 主图节点点击同一函数，
// flowMapTab.js:1225）——相机画布内的 DOM 点击有 focus-scroll 陷阱（案例002⑤），
// 视觉验收目标是详情窗渲染，点击链为既有行为不在本批改动面。
//
// Run（worktree 根）：node scripts/shot-node-detail-config.cjs
// 产出：.nebflow/Spec/assets/node-detail-config/*.png（gitignore 层，不进 repo）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, dirname, extname, normalize } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(__dirname, '..', '.nebflow', 'Spec', 'assets', 'node-detail-config');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'shot-root';
const PROJ = 'shot-proj';
const now = Date.now();

// ── fixture 三节点（字段逐一对齐 NodePayload.buildNodeJson，ProjectTypes.scala:202）──
// 批次聚簇窗口 120s：三节点 createdAt 相邻间隔 ≤100s → 同一条链；n-loop running
// → 链未齐，n-plain/n-plugins 终态保留主图（三卡都可点开详情）。
const NODES = [
  { // ① 普通节点：preset + 无插件 + out→Nebula + TTL 剩余
    id: 'n-plain', name: '实施-登录页对齐修复', agent: 'general', skill: null, mcp: null,
    preset: 'code', description: '修复登录页按钮对齐：flex 间距节奏与焦点环统一。',
    status: 'completed', in: [], out: 'Nebula', hasWorktree: false, worktree: null,
    blockCount: 0, createdAt: now - 3600_000, completedAt: now - 3000_000,
    ttlLeftSec: 82000, hasResult: true,
  },
  { // ② 多插件节点：默认预设空态 + 3 插件全列 + in 上游 + 无出边
    id: 'n-plugins', name: '实施-前端三域联调', agent: 'general', skill: null, mcp: null,
    preset: null, description: '设计系统对齐 + 前端实现 + QA 验证三域联调。',
    status: 'completed', in: ['n-plain'], out: null, hasWorktree: false, worktree: null,
    blockCount: 0, createdAt: now - 3500_000, completedAt: now - 2500_000,
    ttlLeftSec: 43000,
    plugins: ['nebflow-frontend-dev', 'nebflow-qa', 'design-system'],
  },
  { // ③ worktree + loop 节点：执行形态全字段（worktree/loop 配置+运行态/回流通知）
    id: 'n-loop', name: '实施-loop节点前端展示', agent: 'general', skill: null, mcp: null,
    preset: 'code', description: 'LoopNode 详情展示迭代：worker 生产 + verify 逐轮验收。',
    status: 'running', in: ['n-plugins'], out: 'Nebula',
    hasWorktree: true, worktree: '实施-loop节点前端展示',
    blockCount: 0, createdAt: now - 3420_000, completedAt: null, ttlLeftSec: null,
    plugins: ['nebflow-frontend-dev'],
    loop: { maxRounds: 5, verify: 'general', enabled: true },
    loopRound: 2, loopPhase: 'verify',
    loopLastVerdict: 'FAIL: 暗主题截图缺失，插件列未换行',
    notifyDispatcher: true,
  },
];

const RESULT_N1 = '## 修复完成\n\n- `login.css` 按钮区改 `gap: 12px`，对齐 4px 间距节奏\n- 焦点环统一 `box-shadow 0 0 0 2px rgb(var(--sapphire) / 0.55)`\n\n**验证**：`node scripts/check-js-types.mjs` 零新增；亮暗双主题截图已过。';

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
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

    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/')) {
        if (p === '/api/projects') {
          return route.fulfill({ json: { projects: [{ name: PROJ, workspace: '/tmp/shot', description: '', createdAt: now }] } });
        }
        if (p === `/api/projects/${PROJ}/flow-map`) {
          return route.fulfill({ json: { nodes: NODES, worktrees: [], meta: { project: PROJ, updatedAt: now } } });
        }
        if (p === `/api/projects/${PROJ}/flow-map/nodes/n-plain/result`) {
          return route.fulfill({ json: { id: 'n-plain', name: NODES[0].name, status: 'completed', result: RESULT_N1 } });
        }
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
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

    // 打开就地 Flow Map 视图（projectTab 生产入口）并等三卡渲染
    await page.evaluate((proj) => import('/js/projectTab.js').then((m) => m.openProjectFlowMapAt(proj)), PROJ);
    await page.waitForFunction(() => document.querySelectorAll('.flowmap-view-body .fm-node').length >= 3, null, { timeout: 10000 });
    await page.waitForTimeout(600); // 相机 autoFit + 入场动画收敛

    // 三类样例逐个开详情（生产入口 openDetailFor = 主图点击同一函数）→ 截详情窗
    const samples = [
      ['n-plain', 'plain'],       // 普通：preset/TTL/out→Nebula/结果全文
      ['n-plugins', 'plugins'],   // 多插件：默认预设空态 + 3 插件 + in 上游 + 无出边
      ['n-loop', 'worktree-loop'],// worktree + loop 配置/运行态 + 回流通知
    ];
    for (const [nodeId, tag] of samples) {
      await page.evaluate(([proj, id]) => import('/js/flowMapArchive.js').then((m) => {
        const body = document.querySelector('.flowmap-view-body');
        m.openDetailFor(body, proj, id);
      }), [PROJ, nodeId]);
      await page.waitForFunction(() => {
        const d = document.querySelector('.fm-detail');
        return d && !d.hidden && d.classList.contains('open');
      }, null, { timeout: 8000 });
      await page.waitForTimeout(450); // 开窗过渡 + 结果全文按需换装（n-plain）
      const detail = page.locator('.fm-detail');
      const out = join(OUT_DIR, `20260907_node-detail-${tag}-${colorScheme}.png`);
      await detail.screenshot({ path: out });
      console.log(`saved ${out}`);
    }
    // console 无新增错误哨兵
    const errors = await page.evaluate(() => (window.__shotErrors || []));
    if (errors.length) console.log('console errors:', errors);
    await page.close();
  }
  await browser.close();
  console.log('done');
})().catch((e) => { console.error(e); process.exit(1); });
