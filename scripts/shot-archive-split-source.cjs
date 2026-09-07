#!/usr/bin/env node
// shot-archive-split-source.cjs — 裁定④「TTL 分开」批（2026-09-07）视觉验收截图。
//
// 验收对象：归档面板数据源切换到后端 Flow Archive 分批内容
// （GET /api/projects/<p>/flow-map/archive）——
//   ① 徽章/面板条目来自 remote 批次（快照里不存在这些节点 → 纯 remote 渲染）；
//   ② remote 成员详情可开（remoteMembers 兜底查找）+ 结果全文按需通道；
//   ③ 派生链（在途未归档的终态链）与 remote 链并集去重同显。
//
// 自包含打桩（同 shot-node-detail-config.cjs 路线）：page.route 从磁盘服务真实前端
// 文件（不起任何端口、不碰 8080），MockWebSocket 注入真实 ws.js 分发路径，
// flow-map / flow-map/archive / nodes/<id>/result 三端点按 fixture 应答。
//
// Run（worktree 根）：node scripts/shot-archive-split-source.cjs
// 产出：.nebflow/Spec/assets/archive-split-source/*.png（gitignore 层，不进 repo）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = join(__dirname, '..', '.nebflow', 'Spec', 'assets', 'archive-split-source');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'shot-root';
const PROJ = 'shot-proj';
const now = Date.now();

// ── 活动区快照：一条在途链（一终态一运行中 → 链未齐，终态卡保留主图）──
// 字段逐一对齐 NodePayload.buildNodeJson（ProjectTypes.scala）。
const ACTIVE_NODES = [
  {
    id: 'n-live1', name: '实施-归档入口数据源切换', agent: 'general', skill: null, mcp: null,
    preset: 'code', description: '归档面板改读后端分批落盘内容。',
    status: 'completed', in: [], out: 'n-live2', hasWorktree: false, worktree: null,
    blockCount: 0, createdAt: now - 1200_000, completedAt: now - 600_000,
    ttlLeftSec: 82000, hasResult: true,
  },
  {
    id: 'n-live2', name: '验收-归档入口数据源切换', agent: 'general', skill: null, mcp: null,
    preset: 'code', description: '截图验收归档面板 remote 数据源。',
    status: 'running', in: ['n-live1'], out: 'Nebula', hasWorktree: false, worktree: null,
    blockCount: 0, createdAt: now - 1140_000, completedAt: null, ttlLeftSec: null,
  },
];

// ── 后端归档批次（flow-map/archive 应答；快照中不存在 → 纯 remote 渲染链）──
const ARCHIVE_BATCHES = [
  {
    id: 'chain-n-arch1', archivedAt: now - 3300_000, completedAt: now - 3300_000,
    members: [
      {
        id: 'n-arch1', name: '实施-缓存命中率看板', agent: 'general', skill: null, mcp: null,
        preset: 'code', description: 'router 日志五维聚合 + 命中率趋势图。',
        status: 'completed', in: [], out: 'n-arch2', hasWorktree: false, worktree: null,
        blockCount: 0, createdAt: now - 4200_000, completedAt: now - 3600_000,
        ttlLeftSec: 7200, hasResult: true,
      },
      {
        id: 'n-arch2', name: '验收-缓存命中率看板', agent: 'general', skill: null, mcp: null,
        preset: 'code', description: '看板数据与 router 日志对账。',
        status: 'completed', in: ['n-arch1'], out: 'Nebula', hasWorktree: false, worktree: null,
        blockCount: 0, createdAt: now - 4140_000, completedAt: now - 3300_000,
        ttlLeftSec: 6900, hasResult: true,
      },
    ],
  },
  {
    id: 'chain-n-arch3', archivedAt: now - 900_000, completedAt: now - 900_000,
    members: [
      {
        id: 'n-arch3', name: '修复-详情窗占位符泄漏', agent: 'general', skill: null, mcp: null,
        preset: null, description: 'blocked 原文折叠区异步换装兜底。',
        status: 'failed', in: [], out: 'Nebula', hasWorktree: true, worktree: 'fix-detail-placeholder',
        blockCount: 0, createdAt: now - 1500_000, completedAt: now - 900_000,
        ttlLeftSec: 300, hasResult: true,
        plugins: ['nebflow-frontend-dev'],
      },
    ],
  },
];

const RESULT_ARCH1 = '## 看板完成\n\n- 五维聚合端点对齐 usage-records.jsonl\n- 命中率趋势图按小时分桶\n\n**对账**：与 router summary 偏差 <0.3%。';

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  for (const colorScheme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme });
    const consoleErrors = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => consoleErrors.push(String(e)));
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
          return route.fulfill({ json: { nodes: ACTIVE_NODES, worktrees: [], meta: { project: PROJ, updatedAt: now } } });
        }
        if (p === `/api/projects/${PROJ}/flow-map/archive`) {
          return route.fulfill({ json: { batches: ARCHIVE_BATCHES, ttlMs: 86400000, count: ARCHIVE_BATCHES.length } });
        }
        if (p === `/api/projects/${PROJ}/flow-map/nodes/n-arch1/result`) {
          return route.fulfill({ json: { id: 'n-arch1', name: '实施-缓存命中率看板', status: 'completed', result: RESULT_ARCH1 } });
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

    // 打开就地 Flow Map 视图（生产入口）→ 等在途链两卡渲染 + remote 首拉落定（徽章=2）
    await page.evaluate((proj) => import('/js/projectTab.js').then((m) => m.openProjectFlowMapAt(proj)), PROJ);
    await page.waitForFunction(() => document.querySelectorAll('.flowmap-view-body .fm-node').length >= 2, null, { timeout: 10000 });
    await page.waitForFunction(() => {
      const b = document.querySelector('.fm-archive-badge');
      return b && !b.classList.contains('hidden') && b.textContent === '2';
    }, null, { timeout: 10000 });
    await page.waitForTimeout(600);

    // ① 徽章 + 主图（在途链保留；归档链不在主图）
    const shotMain = join(OUT_DIR, `20260907_archive-split-1-main-badge-${colorScheme}.png`);
    await page.screenshot({ path: shotMain });
    console.log(`saved ${shotMain}`);

    // ② 打开归档面板 → 两条 remote 链（含 failed 链 TTL 标签）；展开第一条
    await page.click('.fm-fab');
    await page.waitForFunction(() => {
      const p = document.querySelector('.fm-archive-panel');
      return p && !p.hidden && p.classList.contains('open')
        && p.querySelectorAll('.fm-entry').length === 2;
    }, null, { timeout: 8000 });
    await page.click('.fm-entry[data-chain-id="chain-n-arch1"]');
    await page.waitForTimeout(350);
    const shotPanel = join(OUT_DIR, `20260907_archive-split-2-panel-${colorScheme}.png`);
    await page.locator('.fm-archive-panel').screenshot({ path: shotPanel });
    console.log(`saved ${shotPanel}`);

    // ③ remote 成员详情（remoteMembers 兜底；结果全文按需换装）
    await page.click('.fm-member[data-node="n-arch1"]');
    await page.waitForFunction(() => {
      const d = document.querySelector('.fm-detail');
      return d && !d.hidden && d.classList.contains('open');
    }, null, { timeout: 8000 });
    await page.waitForTimeout(500); // 开窗过渡 + 全文按需换装
    const shotDetail = join(OUT_DIR, `20260907_archive-split-3-detail-${colorScheme}.png`);
    await page.locator('.fm-detail').screenshot({ path: shotDetail });
    console.log(`saved ${shotDetail}`);

    if (consoleErrors.length) console.log(`console errors (${colorScheme}):`, consoleErrors);
    await page.close();
  }
  await browser.close();
  console.log('done');
})().catch((e) => { console.error(e); process.exit(1); });
