// mobile-p9-graph.spec.mjs — P9「图谱」右列推送页验收
//（mobile-secondary-screens 实施批；样张 v3.5 P9 + 开放点①②③④按设计批推荐）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   页范式 …… 右列键推入 .mobile-page（壳既有）；内容面 = 收编桌面
//        wiki-graph pane（GET /api/wiki/graph → 力导向画布，桌面零改动）。
//   §2-P9 状态行 …… 桌面 .wiki-toolbar-status「{n} 个项目 · {n} 篇文档 ·
//        {n} 条链接」（wiki.status 同源模板）。
//   缩放三钮 …… 桌面 .wx-zoom 玻璃珠（in/out/fit 三键）原样。
//   开放点① 预览+列表 vs 全画布漫游 …… 采纳「全画布漫游」腿：画布满页；
//        facet/文档清单宿主 = 桌面 explorer（手机层桌面壳退场 ⇒ 不入页面）。
//   开放点③ 项目轮换色点+灰阶度数沿用 …… 桌面灰阶家族原样（作者 2026-10-16
//        已裁，本批不触碰）。
//   开放点②④ 文档行/缩放钮 …… 侧栏清单不入页（偏差记报告）；缩放三钮保留。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p9-graph.spec.mjs

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

const SID = 'p9-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

function graphFixture() {
  return {
    projects: [{ name: 'nebflow 主仓' }, { name: '远端巡检' }],
    nodes: [
      { title: '架构总览', rel: 'docs/架构总览.md', project: 'nebflow 主仓', kind: 'doc', status: 'done' },
      { title: '巡检手册', rel: 'docs/巡检手册.md', project: '远端巡检', kind: 'doc', status: '' },
      { title: '待写草稿', rel: 'docs/待写草稿.md', project: 'nebflow 主仓', kind: 'doc', status: '', stub: true },
    ],
    edges: [
      { from: '架构总览', to: '巡检手册' },
      { from: '架构总览', to: '待写草稿' },
    ],
  };
}

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
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/wiki/graph') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(graphFixture()) });
      }
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{"projects":[]}' });
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
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function pushPage(page, face) {
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await page.locator(`.mh-dd[data-dd="right"] [data-face="${face}"]`).click();
  await expect(page.locator(`.mobile-page[data-page="${face}"]`)).toHaveClass(/live/);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 graph page: adopted pane, status line counts, canvas fills page', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'graph');

  // pane 收编进页面体。
  expect(await page.locator(
    '.mobile-page[data-page="graph"] .mobile-page-body .canvas-tab-pane[data-tab-id="wiki-graph"]',
  ).count()).toBe(1);

  // 状态行 = wiki.status 模板（stub 不计入）。
  await expect(page.locator('.wiki-toolbar-status')).toHaveText('2 个项目 · 2 篇文档 · 2 条链接');

  // 画布满页（flex:1）—— pane/画布盒非零高。
  const canvasBox = await page.locator('.wiki-canvas').boundingBox();
  expect(canvasBox).toBeTruthy();
  expect(canvasBox.height).toBeGreaterThan(400);

  // 页面推入终态全宽（poll 到 transform 归位）。
  const pageBox = () => page.locator('.mobile-page[data-page="graph"]').evaluate((el) => {
    const r = el.getBoundingClientRect();
    return { x: r.x, w: Math.round(r.width) };
  });
  await expect.poll(pageBox, { timeout: 5000 }).toEqual({ x: 0, w: 375 });
  expect(errors).toEqual([]);
});

test('T2 zoom cluster: three glass buttons, safe-area offset', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'graph');

  const zoom = page.locator('.wx-zoom');
  await expect(zoom).toBeVisible();
  expect(await zoom.locator('button').count()).toBe(3);
  // 玻璃珠材质（backdrop-filter blur 在场）。
  const bf = await zoom.evaluate((el) => getComputedStyle(el).backdropFilter || getComputedStyle(el).webkitBackdropFilter);
  expect(bf).toContain('blur');
  // 底距含安全区兜底（calc ≥16px）。
  const bottom = await zoom.evaluate((el) => getComputedStyle(el).bottom);
  expect(parseFloat(bottom)).toBeGreaterThanOrEqual(16);
  expect(errors).toEqual([]);
});

test('T3 back contract pops to chat; toolbar keeps glass buttons', async ({ page }) => {
  const errors = watchErrors(page);
  await pushPage(page, 'graph');

  // 工具条玻璃钮（刷新）在场（glass-control 族）。
  await expect(page.locator('.wiki-toolbar .wiki-toolbar-btn').first()).toBeVisible();

  // 返回契约：pop 一层回聊天。
  await page.locator('.mobile-page[data-page="graph"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="graph"]')).not.toHaveClass(/live/);
  expect(await page.locator('.mobile-page.live').count()).toBe(0);
  expect(errors).toEqual([]);
});
