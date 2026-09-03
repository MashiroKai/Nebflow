// flowmap-archive-resize-follow.spec.mjs — D-QA1 resize-follow 回归断言（2026-09-04）。
//
// 背景：QA v2 报告 §1 D-QA1——dock 判定改 JS host.clientWidth>=780 后，window resize
// 事件时刻读到的 host 宽是 flex-basis 0.32s 过渡前旧值，落定后无重判 → dock 形态按
// 旧宽锁死（1280→1920 该并排不并排；1920→1280 详情悬出 host 被裁成残条）。
// 修复：flowMapArchive.js 对 host 挂 ResizeObserver（每帧布局落定后回调，终帧即最终
// 宽度）；无 RO 环境降级 resize+双 rAF+transitionend。本 spec 补上此前缺失的动态
// resize-follow 覆盖（既有 A9.6/T9 均为「直接开在目标宽度」，结构上拦不住本缺陷）。
//
// 用例：
//   ① RO 主路径：面板+详情双开，序列 1280→1920→1280，每步 resize 后等过渡落定
//     （600ms ≥ 320ms 过渡 + 裕量）再断言 dock 形态（对齐 QA 探针双向锁死检验）。
//   ② 强制降级路径：delete window.ResizeObserver 激活 resize+双 rAF+transitionend
//     兜底，单发 resize（仿真窗口最大化/还原）落定后形态正确。
// 夹具/route 拦截与 flowmap-archive-panel.spec.mjs 同构（零端口，localhost:1）；
// 既有产品 spec / 静态护栏零改动。
//
// Run: node node_modules/@playwright/test/cli.js test tests/flowmap-archive-resize-follow.spec.mjs --workers=1

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  PROJECT, ROOT_SID, allNodes, fmPayload,
} from './fixtures/flowmap-archive-panel/fixture.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT = '/tmp/flowmap-fix3-record/shots';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

test.setTimeout(90_000);

// 落定等待：须覆盖级联链——flex-basis 过渡 0.32s（降级路径 dock 翻转须等其
// transitionend）→ 详情 right 0.22s 跟随动画，合计 ~540ms，取 900ms 留裕量
// （≥500ms 口径要求；无头环境 rAF/帧调度抖动下 600ms 曾采到 right 中间态）。
const SETTLE = 900;

async function bootApp(page) {
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
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 服务端权威快照（与产品 spec 同构）。 */
const serverNodes = [];
function seedServer() {
  serverNodes.length = 0;
  serverNodes.push(...allNodes().map((n) => structuredClone(n)));
}

async function mockApi(page) {
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: PROJECT, workspace: '/w/alpha', agentFile: PROJECT, description: '', createdAt: new Date().toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({
    json: fmPayload(serverNodes.map((n) => structuredClone(n))),
  }));
}

async function openFlowMapInPlace(page) {
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
  await page.waitForTimeout(250);
}

/** 面板 + 详情双开（dock 判定最坏形态）。 */
async function openPanelDetail(page) {
  await page.locator('[data-testid="archive-toggle"]').click();
  await page.waitForSelector('[data-testid="archive-panel"].open', { timeout: 4000 });
  const c1 = page.locator('[data-testid="archive-entry"]', { hasText: 'console-404' });
  if (!(await c1.evaluate((el) => el.classList.contains('expanded')))) {
    await c1.click();
    await page.waitForTimeout(160);
  }
  await c1.locator('.fm-member', { hasText: '验收-console-404' }).click();
  await page.waitForSelector('[data-testid="detail-panel"].open', { timeout: 4000 });
  await page.waitForTimeout(400);
}

/** dock 形态几何探针（对齐 A9.6 / QA 探针 geo 口径）。 */
function geo(page) {
  return page.evaluate(() => {
    const host = document.querySelector('.flowmap-view-body');
    const d = document.querySelector('[data-testid="detail-panel"]');
    const p = document.querySelector('[data-testid="archive-panel"]');
    const hr = host.getBoundingClientRect();
    const dr = d.getBoundingClientRect();
    const pr = p.getBoundingClientRect();
    const hit = document.elementFromPoint(dr.left + dr.width / 2, dr.top + 120);
    return {
      hostW: Math.round(hr.width),
      docked: d.classList.contains('dock-left'),
      right: getComputedStyle(d).right,
      noOverlap: dr.right <= pr.left + 1 || dr.left >= pr.right - 1,
      inHostX: dr.left >= hr.left - 1 && dr.right <= hr.right + 1,
      hitDetail: hit ? !!hit.closest('[data-testid="detail-panel"]') : false,
    };
  });
}

// ═══════════ ① RO 主路径：1280→1920→1280 序列（QA D-QA1 双向锁死检验同型）═══════════

test('resize-follow 序列 1280→1920→1280：面板+详情双开，每步过渡落定后 dock 形态跟随', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  seedServer();
  await page.setViewportSize({ width: 1280, height: 800 });
  await bootApp(page);
  await mockApi(page);
  await openFlowMapInPlace(page);
  await openPanelDetail(page);

  // 基线 @1280：host <780 → overlay 形态，详情完整在 host 内
  const g0 = await geo(page);
  expect.soft(g0.hostW).toBeLessThan(780);
  expect.soft(g0.docked).toBe(false);
  expect.soft(g0.right).toBe('16px');
  expect.soft(g0.inHostX).toBe(true);
  expect.soft(g0.hitDetail).toBe(true);

  // 单发 resize → 1920（仿真最大化）：落定后必须并排（D-QA1 症状① overlay 锁死 → 转）
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.waitForTimeout(SETTLE);
  const g1 = await geo(page);
  expect.soft(g1.hostW).toBeGreaterThanOrEqual(780);
  expect.soft(g1.docked).toBe(true);
  expect.soft(g1.right).toBe('404px');
  expect.soft(g1.noOverlap).toBe(true);
  expect.soft(g1.inHostX).toBe(true);
  expect.soft(g1.hitDetail).toBe(true);
  await page.screenshot({ path: `${SHOT}/resize-follow-1920-dock.png` });

  // 单发 resize → 1280（仿真还原）：落定后必须撤回 overlay（D-QA1 症状② dock 锁死 → 转）
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.waitForTimeout(SETTLE);
  const g2 = await geo(page);
  expect.soft(g2.hostW).toBeLessThan(780);
  expect.soft(g2.docked).toBe(false);
  expect.soft(g2.right).toBe('16px');
  expect.soft(g2.inHostX).toBe(true); // boundingBox 完整在 host 内（不被裁成残条）
  expect.soft(g2.hitDetail).toBe(true); // elementFromPoint 中心命中详情自身
  await page.screenshot({ path: `${SHOT}/resize-follow-1280-overlay.png` });
  expect.soft(errors).toEqual([]);
});

// ═══════════ ② 强制降级路径：无 ResizeObserver 环境的单发最大化/还原 ════════════

test('无 ResizeObserver 降级路径：单发 resize（最大化/还原）落定后形态正确', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  seedServer();
  await page.addInitScript(() => { delete window.ResizeObserver; }); // 激活 flowMapArchive 降级分支
  await page.setViewportSize({ width: 1920, height: 1080 });
  await bootApp(page);
  await mockApi(page);
  await openFlowMapInPlace(page);
  await openPanelDetail(page);

  // 前置：降级分支确实生效（产品码特性检测看到的是无 RO 环境）
  const roGone = await page.evaluate(() => typeof window.ResizeObserver === 'undefined');
  expect(roGone).toBe(true);

  // 直接开在 1920 → dock 并排基线（A9.6 同口径，走开启时判定）
  const g0 = await geo(page);
  expect.soft(g0.hostW).toBeGreaterThanOrEqual(780);
  expect.soft(g0.docked).toBe(true);
  expect.soft(g0.right).toBe('404px');

  // 单发 resize → 1280（还原）：transitionend 落定重判 → overlay 正确
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.waitForTimeout(SETTLE);
  const g1 = await geo(page);
  expect.soft(g1.hostW).toBeLessThan(780);
  expect.soft(g1.docked).toBe(false);
  expect.soft(g1.right).toBe('16px');
  expect.soft(g1.inHostX).toBe(true);
  expect.soft(g1.hitDetail).toBe(true);
  await page.screenshot({ path: `${SHOT}/resize-fallback-1280-overlay.png` });

  // 单发 resize → 1920（再最大化）：transitionend 落定重判 → 并排正确
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.waitForTimeout(SETTLE);
  const g2 = await geo(page);
  expect.soft(g2.hostW).toBeGreaterThanOrEqual(780);
  expect.soft(g2.docked).toBe(true);
  expect.soft(g2.right).toBe('404px');
  expect.soft(g2.noOverlap).toBe(true);
  expect.soft(g2.inHostX).toBe(true);
  expect.soft(g2.hitDetail).toBe(true);
  await page.screenshot({ path: `${SHOT}/resize-fallback-1920-dock.png` });
  expect.soft(errors).toEqual([]);
});
