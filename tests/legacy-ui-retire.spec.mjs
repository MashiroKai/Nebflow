// legacy-ui-retire.spec.mjs — 旧 UI 退役批验收 spec（2026-09-05）。
//
// 覆盖（作者裁定「直接删除，不是封存」——legacy-pop/teams/flows 旧入口 +
// FlowCanvas 域整体摘除后的回归面）：
//   T1 侧边栏与顶栏无旧面板痕迹（legacy-btn/legacy-pop/teams-btn/flows-btn/
//      flows-indicator/flows-dropdown 全部不存在；同槽位 projects/agents/
//      settings 按钮与 bgagent-indicator 不受牵连）——zh-CN + en。
//   T2 后端照发的旧 WS 事件（flowStarted/flowProgress/flowCompleted/
//      flowNodesAdded/flowMail/mailQueued/mailDequeued/teamList/teamUpdate/
//      flowUpdate）注入后零 UI 副作用：无报错、无新 canvas 标签、无面板弹出。
//   T3 新体系面板回归：插件页（boot fallback）、Projects tab、设置面板均可见；
//      canvas 面板基建（canvas-toggle-btn / tab bar）健在。
//   T4 代码级：flowCanvas.js 已从静态产物消失（404）；activityBar.js 无
//      legacy 绑定；ws.js 不再注册旧事件类型。
//
// Self-contained: static server on 127.0.0.1:8187 (8100+ per task discipline,
// never the host 8080); WS/API mocked in-page; server closed at end.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8187; // fixed, 8100+ — never the host 8080/8091/8092
const MIME = {
  '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html',
  '.svg': 'image/svg+xml', '.json': 'application/json', '.png': 'image/png',
};

async function startServer() {
  const server = createServer(async (req, res) => {
    const p = (req.url || '/').split('?')[0];
    if (p === '/' || p === '/index.html') {
      try {
        const data = await readFile(join(WEB, 'index.html'));
        res.writeHead(200, { 'content-type': MIME['.html'] });
        res.end(data);
      } catch { res.writeHead(500); res.end(); }
      return;
    }
    try {
      const file = resolve(join(WEB, p));
      if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
      const data = await readFile(file);
      res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(data);
    } catch {
      res.writeHead(404); res.end('not found');
    }
  });
  await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
  return server;
}

let server;
const base = `http://127.0.0.1:${PORT}`;

// Mock server→client frames pushed on connect (mirrors sidebar-plugins.spec).
const BOOT_FRAMES = [
  { type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} },
  { type: 'sessionList', sessions: [], activeId: null, folders: [] },
  { type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] },
];

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

async function loadShell(page, locale) {
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript((l) => {
    localStorage.clear();
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    if (l) localStorage.setItem('nebflow_locale', l);
  }, locale);
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  /** Node-side handle to the mocked server socket — injectFrame uses it. */
  let wsRef = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    wsRef = ws;
    ws.onMessage(() => { /* client→server ignored */ });
    for (const f of BOOT_FRAMES) ws.send(JSON.stringify(f));
  });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  const injectFrame = (frame) => {
    if (!wsRef) throw new Error('mock WS not connected yet');
    wsRef.send(JSON.stringify(frame));
  };
  return { consoleErrors, injectFrame };
}

// ── T1 — 旧面板痕迹零存在 ──────────────────────────────────────────────

test('T1: no legacy panel traces in sidebar/header (zh-CN + en)', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    const dump = await page.evaluate(() => ({
      legacyBtn: document.getElementById('legacy-btn'),
      legacyPop: document.getElementById('legacy-pop'),
      teamsBtn: document.getElementById('teams-btn'),
      flowsBtn: document.getElementById('flows-btn'),
      flowsIndicator: document.getElementById('flows-indicator'),
      flowsDropdown: document.getElementById('flows-dropdown'),
      // 同槽位/同集群存活对照
      projectsBtn: !!document.getElementById('projects-btn'),
      agentsBtn: !!document.getElementById('agents-btn'),
      settingsBtn: !!document.getElementById('settings-btn'),
      canvasToggle: !!document.getElementById('canvas-toggle-btn'),
      bgagentIndicator: !!document.getElementById('bgagent-indicator'),
      // 旧面板 canvas 标签不得在启动时出现
      oldTabs: [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')]
        .map((t) => t.dataset.tabId).filter((id) => id === 'teams' || id === 'flows'),
    }));
    expect(dump.legacyBtn, `[${locale}] #legacy-btn must not exist`).toBeNull();
    expect(dump.legacyPop, `[${locale}] #legacy-pop must not exist`).toBeNull();
    expect(dump.teamsBtn, `[${locale}] #teams-btn must not exist`).toBeNull();
    expect(dump.flowsBtn, `[${locale}] #flows-btn must not exist`).toBeNull();
    expect(dump.flowsIndicator, `[${locale}] #flows-indicator must not exist`).toBeNull();
    expect(dump.flowsDropdown, `[${locale}] #flows-dropdown must not exist`).toBeNull();
    expect(dump.projectsBtn, `[${locale}] projects-btn kept`).toBe(true);
    expect(dump.agentsBtn, `[${locale}] agents-btn kept`).toBe(true);
    expect(dump.settingsBtn, `[${locale}] settings-btn kept`).toBe(true);
    expect(dump.canvasToggle, `[${locale}] canvas-toggle-btn kept`).toBe(true);
    expect(dump.bgagentIndicator, `[${locale}] bgagent-indicator kept`).toBe(true);
    expect(dump.oldTabs, `[${locale}] no teams/flows canvas tabs at boot`).toEqual([]);
  }
});

// ── T2 — 旧事件注入零副作用 ────────────────────────────────────────────

test('T2: legacy WS events injected → no errors, no tabs, no popovers', async ({ page }) => {
  const { consoleErrors, injectFrame } = await loadShell(page, 'zh-CN');

  // Boot fallback lands on the plugins tab — record the baseline tab set.
  await page.waitForSelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]', { timeout: 10000 });
  const tabsBefore = await page.evaluate(() =>
    [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')].map((t) => t.dataset.tabId));

  // 后端照发的旧事件逐帧注入（前端 handler 已删，未知事件必须被静默忽略）。
  const LEGACY_FRAMES = ['flowStarted', 'flowProgress', 'flowCompleted', 'flowNodesAdded',
    'flowMail', 'mailQueued', 'mailDequeued', 'teamList', 'teamUpdate', 'flowUpdate',
    'treeBranchMounted', 'treeBranchUpdated'];
  for (const type of LEGACY_FRAMES) {
    injectFrame({ type, sessionId: 'nonexistent-sid' });
  }
  await page.waitForTimeout(600);

  const after = await page.evaluate(() => ({
    tabs: [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')].map((t) => t.dataset.tabId),
    legacyPop: !!document.getElementById('legacy-pop'),
    flowsDropdown: !!document.getElementById('flows-dropdown'),
    // Open-markers mirror main.js: bypass-menu toggles .show; the
    // bgagent/bg dropdowns toggle the .hidden class.
    anyDropdown:
      [...document.querySelectorAll('.header-dropdown-menu')].some((el) => el.classList.contains('show')) ||
      [...document.querySelectorAll('#bgagent-dropdown, #bg-dropdown')].some((el) => !el.classList.contains('hidden')),
  }));
  expect(after.tabs, 'no canvas tabs spawned by legacy events').toEqual(tabsBefore);
  expect(after.legacyPop, 'legacy-pop still absent').toBe(false);
  expect(after.flowsDropdown, 'flows-dropdown still absent').toBe(false);
  expect(after.anyDropdown, 'no dropdown popped open').toBe(false);

  const fatal = consoleErrors.filter((t) =>
    !t.includes('WebSocket') && !t.includes('fetch') && !t.includes('404') && !t.includes('Failed to load resource'));
  expect(fatal, `console errors: ${JSON.stringify(fatal)}`).toEqual([]);
});

// ── T3 — 新体系面板回归 ────────────────────────────────────────────────

test('T3: new-system panels intact — plugins boot, projects tab, settings panel', async ({ page }) => {
  await loadShell(page, 'zh-CN');

  // 插件页（boot fallback 自动打开）
  await page.waitForSelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]', { timeout: 10000 });
  await expect(page.locator('#plugins-content')).toBeVisible();

  // Projects 面板（canvas tab 打开）
  await page.click('#projects-btn');
  await page.waitForSelector('#canvas-tab-bar .canvas-tab[data-tab-id="projects"]', { timeout: 5000 });

  // 设置面板（Side Bar 之上的居中 modal：#settings-overlay.on）
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-overlay.on', { timeout: 5000 });

  // 关掉插件 tab 后 canvas 面板基建仍可开合
  await page.evaluate(() => {
    const tab = document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"] .canvas-tab-close');
    tab?.click();
  });
  await page.click('#canvas-toggle-btn');
  await page.waitForTimeout(300);
  await page.click('#canvas-toggle-btn');
});

// ── T4 — 代码级零残留 ──────────────────────────────────────────────────

test('T4: flowCanvas.js gone from static output; sources carry no legacy symbols', async ({ page }) => {
  // 删除的文件必须 404
  const resp = await page.request.get(base + '/js/flowCanvas.js');
  expect(resp.status(), 'flowCanvas.js must be absent from static output').toBe(404);

  for (const [file, forbidden] of [
    // 注意：纯注释里的历史提及（如 main.js 顶部 flowAnim 再锚定注释）允许保留，
    // 这里只禁代码符号形态（import 路径 / 绑定调用 / 事件名字面量）。
    ['/js/activityBar.js', ['SIDEBAR_LEGACY_ENTRIES', 'bindLegacyPanels', 'anchorLegacyPop', "getElementById('legacy-btn')"]],
    ['/js/main.js', ["from './flowCanvas.js'", '* as flowCanvas', 'flowCanvas.', "'flowMail'", "'flowStarted'", "'flowNodesAdded'", "'flowProgress'", "'flowCompleted'", "'teamList'", "'mailQueued'", "'mailDequeued'", "'treeBranchMounted'", "'treeBranchUnmounted'", "'treeBranchUpdated'"]],
    ['/js/ws.js', ["'flowMail'", "'flowStarted'", "'flowNodesAdded'", "'flowProgress'", "'flowCompleted'", "'teamList'", "'treeBranchMounted'", "'treeBranchUnmounted'", "'treeBranchUpdated'"]],
    ['/js/state.js', ['flows:']],
    ['/js/i18n.js', ['legacy-btn', 'teams-btn', 'flows-btn', 'legacy-pop', 'flows-indicator', 'flows.panelTitle', 'activity.teams', 'activity.flows', 'activity.legacy']],
  ]) {
    const r = await page.request.get(base + file);
    expect(r.status(), `${file} reachable`).toBe(200);
    const src = await r.text();
    for (const sym of forbidden) {
      expect(src.includes(sym), `${file} must not contain ${sym}`).toBe(false);
    }
  }
});
