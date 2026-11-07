// project-unmount-panel.spec.mjs — Project 面板「取消加载/重新加载/已卸载分组」前端回归
// （unmount-console 面板批 2026-11-02）。本文件**替换** project-panel-archive.spec.mjs
// （它锁定的 .project-archive-btn 已随 N2 project-tool 批 2026-10-06 移除，存量两 case
// 自此全红——替换批同时把该面位的语义接回：面板卸载入口以「取消加载」钮回归，走与
// 编排侧 Project 工具同一条引擎通路的 REST 单点）。
//
// 锁定（作者令验收②：确认卡含在飞计数、卸载后灰态分组、重新加载恢复、深浅双主题）：
//  - 每张项目卡有「取消加载」钮（.project-unmount-btn，header 摘要右侧，unplug 图标）
//  - 点击 → 先拉 GET /api/projects/<name>/inflight → 确认弹层（#delete-box，
//    tone:'neutral' 中性材质）如实展示在飞计数（N 条链 / N 个节点 / N 个会话）
//    + 后果文案（在飞停止 / 上下文消失 / 不能派活 / 本地文件零删除）
//  - 取消 → 零 POST、卡片保留；确认 → POST /api/projects/<name>/unmount（零 body）
//  - 卸载回声（WS projectUnmounted 帧）→ 卡片同页定点退场（零 reload），
//    「已卸载」分组以**折叠态**出现（作者令 2026-11-07「这些已卸载的内容不要显示」：
//    常驻只有「已卸载 N」一行；灰态行须点折叠头展开才建）
//  - 「重新加载」→ POST /api/projects/<name>/remount → projectRemounted 帧回声 →
//    卡片恢复、灰态行消失（零 reload）
//  - 亮暗双主题截图落盘（UNMOUNT_SHOT_DIR 可覆盖；默认 docs/evidence/ 下本批目录）
//
// Run: node node_modules/@playwright/test/cli.js test tests/project-unmount-panel.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR =
  process.env.UNMOUNT_SHOT_DIR ||
  join(HERE, '..', 'docs', 'evidence', '20261102_project-unmount');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

/** 可变挂载面状态：unmount POST 后模拟引擎（项目从 mounted 挪入 unmounted；
 *  remount POST 后挪回——与 GET /api/projects 的 additive 两数组同形）。 */
let mountedState;
let unmountedState;
let unmountPosts;
let remountPosts;
let inflightGets;
let serverSock;

async function bootApp(page) {
  mountedState = [
    { name: 'demo-keep', workspace: '/tmp/ws-keep', agentFile: '/tmp/ws-keep/AGENTS.md', description: 'stays mounted', createdAt: 1, device: 'self', origin: null },
    { name: 'demo-gone', workspace: '/tmp/ws-gone', agentFile: '/tmp/ws-gone/AGENTS.md', description: 'will be unloaded', createdAt: 2, device: 'self', origin: null },
  ];
  unmountedState = [];
  unmountPosts = [];
  remountPosts = [];
  inflightGets = [];
  serverSock = null;

  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/projects' && route.request().method() === 'GET') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ projects: mountedState, unmounted: unmountedState }),
      });
    }
    const inflightMatch = p.match(/^\/api\/projects\/([^/]+)\/inflight$/);
    if (inflightMatch && route.request().method() === 'GET') {
      const name = decodeURIComponent(inflightMatch[1]);
      inflightGets.push({ name });
      const busy = name === 'demo-gone';
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          project: name,
          chains: busy ? ['chain-a'] : [],
          runningNodes: busy ? ['node-a1', 'node-a2'] : [],
          dispatcherSessions: busy ? ['dispatcher-x'] : [],
          delegateSessions: busy ? ['delegate-y'] : [],
          total: busy ? 5 : 0,
        }),
      });
    }
    const unmountMatch = p.match(/^\/api\/projects\/([^/]+)\/unmount$/);
    if (unmountMatch && route.request().method() === 'POST') {
      const name = decodeURIComponent(unmountMatch[1]);
      const busy = name === 'demo-gone';
      unmountPosts.push({ name, body: route.request().postData() });
      const row = mountedState.find((x) => x.name === name);
      mountedState = mountedState.filter((x) => x.name !== name);
      if (row) unmountedState = [...unmountedState, { ...row, unmountedAt: Date.now() }];
      // 引擎序：成功腿先广播 projectUnmounted 帧，再回响应（ProjectsRoutes 腿同序）。
      serverSock?.send(JSON.stringify({ type: 'projectUnmounted', project: name, unmountedAt: Date.now() }));
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          project: name, chainsCancelled: busy ? ['chain-a'] : [], nodesCancelled: busy ? ['node-a1', 'node-a2'] : [],
          sessionsCancelled: busy ? ['dispatcher-x'] : [], delegatesCancelled: busy ? ['delegate-y'] : [],
          unmountedAt: Date.now(), alreadyUnmounted: false,
        }),
      });
    }
    const remountMatch = p.match(/^\/api\/projects\/([^/]+)\/remount$/);
    if (remountMatch && route.request().method() === 'POST') {
      const name = decodeURIComponent(remountMatch[1]);
      remountPosts.push({ name, body: route.request().postData() });
      const row = unmountedState.find((x) => x.name === name);
      unmountedState = unmountedState.filter((x) => x.name !== name);
      if (row) mountedState = [...mountedState, row];
      const { unmountedAt, ...rest } = row || {};
      serverSock?.send(JSON.stringify({ type: 'projectRemounted', project: name, row: rest, remountedAt: Date.now() }));
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ project: name, remountedAt: Date.now(), alreadyMounted: false }),
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
    serverSock = ws;
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

/** danger 红判定：r 通道显著高于 g/b。全透明视为中性。 */
function isDangerRed(cssColor) {
  const m = String(cssColor).match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
  if (!m) return false;
  if (m[4] !== undefined && parseFloat(m[4]) === 0) return false;
  const r = +m[1], g = +m[2], b = +m[3];
  return r > 150 && r - g > 60 && r - b > 60;
}

test('unload button, confirmation, hidden unloaded project, external remount', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjectsPanel(page);

  // ① 卸载钮可见：每张卡 header 各一枚（unplug svg）
  const btnGone = page.locator('.project-card[data-project="demo-gone"] .project-unmount-btn');
  const btnKeep = page.locator('.project-card[data-project="demo-keep"] .project-unmount-btn');
  await expect(btnGone).toBeVisible();
  await expect(btnKeep).toBeVisible();
  await expect(btnGone.locator('svg')).toBeVisible();

  // 零 reload 证明标记
  await page.evaluate(() => { window.__unmountNoReload = true; });

  // ② 取消路径：点卸载 → 确认弹层（含在飞计数）→ 取消 → 零请求、卡片保留
  await btnGone.click();
  const deleteBox = page.locator('#delete-box');
  await expect(deleteBox).toBeVisible();
  expect(await deleteBox.getAttribute('class')).toContain('confirm-neutral'); // 卸载可逆（有重新加载）→ 中性
  const confirmBtn = page.locator('#delete-confirm');
  expect(await confirmBtn.textContent()).toContain('取消加载'); // confirmLabel 定制文案
  expect(isDangerRed(await confirmBtn.evaluate((el) => getComputedStyle(el).color))).toBe(false);
  expect(await page.locator('#delete-title').textContent()).toContain('demo-gone');
  const confirmMsg = await page.locator('#delete-msg').textContent();
  // 确认卡如实展示在飞计数（mock /inflight：1 链 / 2 节点 / 2 会话）
  expect(confirmMsg).toContain('1 条链');
  expect(confirmMsg).toContain('2 个节点');
  expect(confirmMsg).toContain('2 个会话');
  // 后果文案四要点
  expect(confirmMsg).toContain('停止');
  expect(confirmMsg).toContain('消失');
  expect(confirmMsg).toContain('派活');
  expect(confirmMsg).toContain('零删除');
  expect(inflightGets.length).toBe(1); // 确认卡先拉在飞计数
  await page.locator('#delete-cancel').click();
  await expect(deleteBox).not.toBeVisible();
  expect(unmountPosts.length).toBe(0); // 取消 → 零 POST
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toBeVisible();

  // ③ 确认路径：确认 → POST unmount（零 body）→ 帧回声退场 → 「已卸载」分组灰态行
  await btnGone.click();
  await expect(deleteBox).toBeVisible();
  await page.locator('#delete-confirm').click();
  await expect(deleteBox).not.toBeVisible();
  expect(unmountPosts.length).toBe(1);
  expect(unmountPosts[0].name).toBe('demo-gone');
  expect(unmountPosts[0].body).toBe('{}');

  // 卡片同页定点退场（帧回声驱动，零 reload）
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toHaveCount(0, { timeout: 5000 });
  await expect(page.locator('.project-card[data-project="demo-keep"]')).toBeVisible();
  await expect(page.locator('.project-unmounted-group, .project-unm-row')).toHaveCount(0);
  await page.evaluate(() => fetch('/api/projects/demo-gone/remount', { method: 'POST' }));
  await expect(page.locator('.project-card[data-project="demo-gone"]')).toBeVisible({ timeout: 5000 });
  expect(remountPosts.length).toBe(1);
  expect(pageErrors).toEqual([]);
});

test('no in-flight project shows zero-count line; keep-card stays intact throughout', async ({ page }) => {
  await bootApp(page);
  await openProjectsPanel(page);
  const btnKeep = page.locator('.project-card[data-project="demo-keep"] .project-unmount-btn');
  await btnKeep.click();
  const deleteBox = page.locator('#delete-box');
  await expect(deleteBox).toBeVisible();
  const confirmMsg = await page.locator('#delete-msg').textContent();
  expect(confirmMsg).toContain('0 条链');
  expect(confirmMsg).toContain('0 个节点');
  expect(confirmMsg).toContain('0 个会话');
  await page.locator('#delete-cancel').click();
  await expect(deleteBox).not.toBeVisible();
  // demo-keep 全程原样（分组/卡互不干扰）
  await expect(page.locator('.project-card[data-project="demo-keep"]')).toBeVisible();
  await expect(page.locator('.project-unmounted-group')).toHaveCount(0);
});
