#!/usr/bin/env node
// shot-tasklist-nodes.cjs — 任务列表 Flow Map 节点条目视觉验收截图（亮暗双主题）。
//
// 自包含打桩：page.route 从磁盘服务真实前端文件（不起任何端口、不碰 8080），
// MockWebSocket 注入真实 ws.js 分发路径。产出：
//   ~/.nebflow/docs/Nebflow/20260902_tasklist-node-entries-dark.png
//   ~/.nebflow/docs/Nebflow/20260902_tasklist-node-entries-light.png
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
          return route.fulfill({ json: { projects: [] } });
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

    // 任务条目（team 分组 + 成员标注）
    await df({
      type: 'taskListUpdate', sessionId: ROOT_SID,
      tasks: [
        { id: 't-1', subject: '任务列表并入 Flow Map 节点条目', status: 'in_progress', teamId: 'nebflow-project', assignee: 'Frontend', updatedAt: new Date(now - 30_000).toISOString() },
        { id: 't-2', subject: '视觉验收截图（亮暗双主题）', status: 'pending', teamId: 'nebflow-project', assignee: 'qa-frontend', updatedAt: new Date(now - 300_000).toISOString() },
      ],
    });
    await page.waitForTimeout(200);

    // 节点条目：两个项目 × 五状态
    await df({ type: 'nodeCreated', project: 'nebflow-project', nodeId: 'n-fe', node: node({ id: 'n-fe', name: 'tasklist-node-entries', agent: 'Frontend', status: 'running', startedAt: now - 45_000 }) });
    await df({ type: 'nodeCreated', project: 'nebflow-project', nodeId: 'n-rev', node: node({ id: 'n-rev', name: 'visual-review', agent: 'design-engineer', status: 'completed', completedAt: now - 60_000, ttlLeftSec: 240 }) });
    await df({ type: 'nodeCreated', project: 'nebflow-project', nodeId: 'n-api', node: node({ id: 'n-api', name: 'nodelist-api', agent: 'Backend', status: 'failed', completedAt: now - 150_000, ttlLeftSec: 150 }) });
    await df({ type: 'nodeCreated', project: 'czt-project', nodeId: 'n-doc', node: node({ id: 'n-doc', name: 'readme-draft', agent: 'Docs', status: 'wiring' }) });
    await df({ type: 'nodeCreated', project: 'czt-project', nodeId: 'n-plan', node: node({ id: 'n-plan', name: 'paper-outline', agent: 'czt-writer', status: 'pending' }) });
    await df({ type: 'nodeCreated', project: 'czt-project', nodeId: 'n-old', node: node({ id: 'n-old', name: 'first-draft', agent: 'Docs', status: 'cancelled', completedAt: now - 100_000, ttlLeftSec: 200 }) });

    const panel = page.locator('#task-list');
    await panel.waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForFunction(() => document.querySelectorAll('#task-list .task-node').length >= 6, null, { timeout: 8000 });
    await page.waitForTimeout(500); // 入场动画收敛

    const out = join(OUT_DIR, `20260902_tasklist-node-entries-${colorScheme}.png`);
    await panel.screenshot({ path: out });
    console.log(`saved ${out}`);
    await page.close();
  }
  await browser.close();
  console.log('done');
})().catch((e) => { console.error(e); process.exit(1); });
