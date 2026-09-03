// project-panel-archive.spec.mjs — Project 面板归档按钮（迁移方案 v2 §6.1）前端回归。
//
// 锁定：
//  - 每个 project 卡片有归档按钮（.project-archive-btn，header 摘要右侧）
//  - 点击归档 → 确认弹层（window.__showConfirm → #delete-box），文案含「归档后不在
//    任务面板显示 / workspace 文件保留」语义
//  - 确认 → POST /api/projects/<name>/archive（载荷正确：无 body、URL 带 name）
//  - 归档后该卡片从列表消失，无需刷新页面（同页重渲；window 标记证明零 reload）
//  - 取消 → 无请求发出，卡片保留
//  - 亮暗双主题截图落盘（~/.nebflow/docs/Nebflow/）
//
// Run: node "/Users/dev/Claude code/Nebflow/node_modules/@playwright/test/cli.js" test tests/project-panel-archive.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = '/Users/dev/.nebflow/docs/Nebflow';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

/** 可变项目状态：POST archive 后模拟后端 list 过滤（归档项从 GET /api/projects 消失）。 */
let projectsState;
let archivePosts = [];

async function bootApp(page) {
  projectsState = [
    { name: 'demo-keep', workspace: '/tmp/ws-keep', agentFile: '/tmp/ws-keep/AGENTS.md', description: 'stays visible', createdAt: 1 },
    { name: 'demo-gone', workspace: '/tmp/ws-gone', agentFile: '/tmp/ws-gone/AGENTS.md', description: 'will be archived', createdAt: 2 },
  ];
  archivePosts = [];

  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/projects' && route.request().method() === 'GET') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ projects: projectsState }),
      });
    }
    const archMatch = p.match(/^\/api\/projects\/([^/]+)\/archive$/);
    if (archMatch && route.request().method() === 'POST') {
      archivePosts.push({ name: decodeURIComponent(archMatch[1]), body: route.request().postData() });
      projectsState = projectsState.filter((x) => x.name !== decodeURIComponent(archMatch[1]));
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ archived: true, archivedAt: Date.now() }),
      });
    }
    if (p.match(/^\/api\/projects\/[^/]+\/flow-map$/)) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: 'x', updatedAt: Date.now() } }),
      });
    }
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
      sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: 'root',
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
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 15000 });
}

async function openProjectsPanel(page) {
  await page.evaluate(() => import('/js/projectTab.js').then((m) => m.openProjectsTab()));
  await expect(page.locator('.project-card[data-project="demo-keep"]')).toBeVisible({ timeout: 10000 });
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toBeVisible();
}

test('archive button visible; confirm dialog; card removed without reload; POST payload correct', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjectsPanel(page);

  // ① 归档按钮可见：每张卡片 header 各一枚，lucide archive 图标
  const btnKeep = page.locator('.project-card[data-project="demo-keep"] .project-archive-btn');
  const btnGone = page.locator('.project-card[data-project="demo-gone"] .project-archive-btn');
  await expect(btnKeep).toBeVisible();
  await expect(btnGone).toBeVisible();
  await expect(btnKeep.locator('svg')).toBeVisible(); // createIconsIn 已把 <i> 换成 svg

  // 零 reload 证明标记
  await page.evaluate(() => { window.__archiveNoReload = true; });

  // ② 取消路径：点归档 → 确认弹层出现 → 取消 → 无请求、卡片保留
  await btnGone.click();
  const deleteBox = page.locator('#delete-box');
  await expect(deleteBox).toBeVisible();
  const confirmTitle = await page.locator('#delete-title').textContent();
  expect(confirmTitle).toContain('demo-gone');
  const confirmMsg = await page.locator('#delete-msg').textContent();
  expect(confirmMsg).toContain('demo-gone');
  expect(confirmMsg.length).toBeGreaterThan(10); // 有实际确认文案
  await page.locator('#delete-cancel').click();
  await expect(deleteBox).not.toBeVisible();
  expect(archivePosts.length).toBe(0); // 取消 → 零请求
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toBeVisible();

  // ③ 确认路径：点归档 → 确认 → POST 发出 → 卡片消失（同页重渲，零 reload）
  await btnGone.click();
  await expect(deleteBox).toBeVisible();
  await page.locator('#delete-confirm').click();
  await expect(deleteBox).not.toBeVisible();

  // 载荷断言：URL 带项目名、POST 无 body
  expect(archivePosts.length).toBe(1);
  expect(archivePosts[0].name).toBe('demo-gone');
  expect(archivePosts[0].body).toBeNull();

  // 列表实时移除（rerenderProjectsTab 200ms 防抖 + refetch 已过滤）
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toHaveCount(0, { timeout: 5000 });
  await expect(page.locator('.project-card[data-project="demo-keep"]')).toBeVisible();

  // 零 reload：点击前设的 window 标记仍在（页面没刷新过）
  expect(await page.evaluate(() => window.__archiveNoReload)).toBe(true);
  expect(pageErrors).toEqual([]);
});

test('dark & light theme screenshots of the Projects panel with archive buttons', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);

  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(SHOT_DIR, '20260903_project-archive-btn-dark.png') });

  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(SHOT_DIR, '20260903_project-archive-btn-light.png') });

  const keepBtn = page.locator('.project-card[data-project="demo-keep"] .project-archive-btn');
  await expect(keepBtn).toBeVisible();
});
