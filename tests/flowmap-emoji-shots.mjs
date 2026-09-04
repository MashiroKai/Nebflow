// flowmap-emoji-shots.mjs — 件七（emoji 清理 + 脚注防溢出）交付截图驱动。
// 亮暗双主题 6 张 → /tmp/nb-flowmap-emoji-clean/shots/：
//   wait-note-{light,dark}.png     等待脚注特写（SVG 沙漏 + ellipsis 截断）
//   status-icons-{light,dark}.png  状态图标 SVG 四态（替代旧 ⏱/TTL 徽章域截图位）
//   longname-{light,dark}.png      375px 窄卡长名节点（卡级防溢出）
// Run: node tests/flowmap-emoji-shots.mjs
import { chromium } from '../node_modules/playwright/index.mjs';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = '/tmp/nb-flowmap-emoji-clean/shots';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'fm-emoji-root';
const T0 = Date.now();
const LONG_NAME = '实施-侧边栏插件页迁移-超长节点名称用于卡片边界截断验证-FlowMapEmojiClean-E2E';

const N_OLD = { id: 'n-arch-old', name: '已归档上游节点', agent: 'researcher', status: 'completed', in: [], out: 'Nebula', hasWorktree: false, worktree: null, result: '早批完成', retries: 0, createdAt: T0 - 600_000, completedAt: T0 - 590_000, ttlLeftSec: null };
const N_A = { id: 'n-a', name: '已完成节点', agent: 'researcher', status: 'completed', in: [], out: 'n-w', hasWorktree: false, worktree: null, result: '完成', retries: 0, createdAt: T0, completedAt: T0 + 100, ttlLeftSec: null };
const N_F = { id: 'n-f', name: '失败节点', agent: 'qa', status: 'failed', in: [], out: 'n-w', hasWorktree: false, worktree: null, result: null, retries: 1, createdAt: T0 + 1_000, completedAt: T0 + 1_100, ttlLeftSec: null };
const N_B = { id: 'n-b', name: '阻塞节点', agent: 'coder', status: 'blocked', in: [], out: 'n-w', hasWorktree: false, worktree: null, result: null, retries: 0, createdAt: T0 + 2_000, completedAt: null, ttlLeftSec: null };
const N_X = { id: 'n-x', name: '已取消节点', agent: 'docs', status: 'cancelled', in: [], out: 'n-w', hasWorktree: false, worktree: null, result: null, retries: 0, createdAt: T0 + 3_000, completedAt: T0 + 3_100, ttlLeftSec: null };
const N_W = { id: 'n-w', name: LONG_NAME, agent: 'writer', status: 'pending', in: ['n-a'], deps: ['n-arch-old', 'ghost-upstream-id-8ac3ef'], out: 'Nebula', hasWorktree: false, worktree: null, result: null, retries: 0, createdAt: T0 + 4_000, completedAt: null, ttlLeftSec: null };
const FM = () => ({ nodes: [N_OLD, N_A, N_F, N_B, N_X, N_W].map((n) => structuredClone(n)), worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() } });

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
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
      else if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM() }));
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
  await page.waitForTimeout(600); // 入场动画落定
}

const browser = await chromium.launch();
try {
  // 默认视口：等待脚注特写 + 状态图标全景（亮/暗）
  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ colorScheme: scheme, viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    await boot(page);
    await page.locator('.fm-node[data-node-id="n-w"]').screenshot({ path: `${OUT}/wait-note-${scheme}.png` });
    await page.locator('.flowmap-view-body .solar-canvas').screenshot({ path: `${OUT}/status-icons-${scheme}.png` });
    await ctx.close();
    console.log(`done ${scheme} (default viewport)`);
  }
  // 375px 窄卡：长名节点（亮/暗）
  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ colorScheme: scheme, viewport: { width: 375, height: 667 }, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    await boot(page);
    await page.setViewportSize({ width: 375, height: 667 });
    await page.waitForTimeout(500);
    await page.locator('.fm-node[data-node-id="n-w"]').screenshot({ path: `${OUT}/longname-${scheme}.png` });
    await ctx.close();
    console.log(`done ${scheme} (375px narrow)`);
  }
} finally {
  await browser.close();
}
console.log('all shots saved');
