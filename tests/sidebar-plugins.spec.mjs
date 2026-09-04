// sidebar-plugins.spec.mjs — 2026-09-04 方案 A×2 验收 spec（新独立 spec，
// 不改任何既有 spec）：
//   件 A — Team/Flow 旧入口隐藏封存：
//     A1 侧边栏 DOM：#legacy-btn（「团队」「流程」唯一父入口）与 #legacy-pop
//        被 SIDEBAR_LEGACY_ENTRIES=false 置 hidden，点击不张开（zh/en 双语）；
//        teams-btn/flows-btn 本体留在 DOM（canvas.js 按 id 绑定、i18n map
//        引用——隐藏 ≠ 删除，删了才悬挂）。
//     A2 flag 翻 true 即恢复（代码级，任务允许）：activityBar.js 源码含
//        `const SIDEBAR_LEGACY_ENTRIES = false`，封存分支 early-return，
//        完整 popover 绑定路径（setOpen/anchor/监听器）位于守卫之外——
//        常量翻 true 后走原绑定路径，popover 原样回归。
//   件 B —「智能体」入口改造「插件」页：
//     B1 插件页渲染：清单（skills name/description/订阅者 + MCP id/状态/
//        命令）、订阅映射（每智能体技能 chips，含选中态）、配置区块
//        （preset select + 模型 pill）结构断言（zh/en 双语）。
//     B2 旧入口不存在：#agents-btn 标题为「插件」/Plugins、图标 puzzle
//        （非 bot）；点击打开 plugins 标签页而非 agents；openAgents 仍可
//        导入（保留函数只藏入口）；订阅映射行深链打开 agent:<name> 详情页。
//     B3 订阅写回走既有 PUT /api/agents/:name 契约（点击 chip 捕获请求体）。
//
// Self-contained: static server on 127.0.0.1:8179 (8100+ per task discipline,
// never the host 8080); WS/API mocked in-page; server closed at end.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8179; // fixed, 8100+ — never the host 8080/8091/8092
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

/** Static server rooted at the web dir (path-traversal safe). */
function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;

// ── Mock backend data (shapes mirror RestApiRoutes.scala responses) ──
const MOCK_AGENTS = {
  agents: [
    { name: 'Nebula', displayName: 'Nebula', description: 'orchestrator', category: 'standalone', layer: 'global' },
    { name: 'Coder', displayName: 'Coder', description: 'coder agent', category: 'standalone', layer: 'global' },
  ],
};
const MOCK_DETAILS = {
  Nebula: { name: 'Nebula', description: 'orchestrator', skills: ['skill-a'], flows: [] },
  Coder: { name: 'Coder', description: 'coder agent', skills: ['skill-a', 'skill-b'], flows: [] },
};
const MOCK_SKILLS = {
  skills: [
    { name: 'skill-a', description: 'Test skill A' },
    { name: 'skill-b', description: 'Test skill B' },
  ],
};
const MOCK_MCP = { mcpServers: { 'fs-server': { command: 'npx', args: ['-y', 'fs-mcp'] } } };
const MOCK_PRESETS = { presets: [{ name: 'preset-one' }, { name: 'preset-two' }], defaultPreset: 'preset-one' };
const MOCK_MODEL = { preferred: 'gpt/test-model', current: 'gpt/test-model', preset: 'preset-one' };

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // API mocks. Playwright route matching is LIFO — the catch-all must be
  // registered FIRST so the specific patterns registered after it win.
  // Glob notes: '**/api/agents/*/model' matches the per-agent model path.
  // Same overall shape as tests/micorb-settings-hidden.spec.mjs.
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: MOCK_MODEL }));
  await page.route('**/api/agents/Nebula', r => r.fulfill({ json: MOCK_DETAILS.Nebula }));
  await page.route('**/api/agents/Coder', r => r.fulfill({ json: MOCK_DETAILS.Coder }));
  await page.route('**/api/agents', r => r.fulfill({ json: MOCK_AGENTS }));
  await page.route('**/api/skills', r => r.fulfill({ json: MOCK_SKILLS }));
  await page.route('**/api/mcp', r => r.fulfill({ json: MOCK_MCP }));
  await page.route('**/api/presets', r => r.fulfill({ json: MOCK_PRESETS }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    // serverConfig carries the live MCP list (id + enabled) — the plugins
    // page's MCP source of truth, same as the settings page.
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [{ id: 'fs-server', enabled: true }], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
});

/** Load the real shell with the given locale.
 *  The locale loop drives TWO navigations over the SAME context — localStorage
 *  must be cleared per navigation, or tabs persisted by the first iteration
 *  (e.g. the agent:Coder deep-link detail tab) restore as the active tab in
 *  the second and hide the plugins pane. */
async function loadShell(page, locale) {
  await page.addInitScript((l) => {
    localStorage.clear();
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
}

// ══ 件 A — Team/Flow 入口隐藏封存 ══════════════════════════════════════

test('A1: legacy Team/Flow entry sealed in the sidebar DOM (zh-CN + en)', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    const dump = await page.evaluate(() => {
      const btn = document.getElementById('legacy-btn');
      const pop = document.getElementById('legacy-pop');
      const teams = document.getElementById('teams-btn');
      const flows = document.getElementById('flows-btn');
      // #legacy-pop is body-level and out of the activity bar's clipping
      // context — offsetParent is a faithful visibility probe for it.
      const visible = (el) => !!el && !el.hidden && el.offsetParent !== null && el.style.display !== 'none';
      // Click the parent entry: a sealed entry must not open the popover.
      btn?.click();
      return {
        btnExists: !!btn,
        btnHiddenAttr: btn?.hidden === true,
        btnAriaHidden: btn?.getAttribute('aria-hidden') === 'true',
        btnAriaControlsRemoved: btn?.getAttribute('aria-controls') === null,
        btnVisible: visible(btn),
        popExists: !!pop,
        popHidden: pop?.hidden === true,
        popVisibleAfterClick: visible(pop),
        // Panel bodies preserved in DOM (hidden ≠ deleted; canvas.js binds
        // teams-btn/flows-btn by id document-wide).
        teamsInDom: !!teams,
        flowsInDom: !!flows,
      };
    });
    expect(dump.btnExists, `[${locale}] legacy parent entry must exist`).toBe(true);
    expect(dump.btnHiddenAttr, `[${locale}] legacy entry hidden attribute`).toBe(true);
    expect(dump.btnAriaHidden, `[${locale}] legacy entry aria-hidden`).toBe(true);
    expect(dump.btnAriaControlsRemoved, `[${locale}] stale aria-controls dropped`).toBe(true);
    expect(dump.btnVisible, `[${locale}] legacy entry must not be visible`).toBe(false);
    expect(dump.popHidden, `[${locale}] legacy popover hidden`).toBe(true);
    expect(dump.popVisibleAfterClick, `[${locale}] clicking sealed entry must not open the popover`).toBe(false);
    expect(dump.teamsInDom, `[${locale}] teams-btn preserved in DOM`).toBe(true);
    expect(dump.flowsInDom, `[${locale}] flows-btn preserved in DOM`).toBe(true);
  }
});

test('A2: SIDEBAR_LEGACY_ENTRIES flag — flip to true restores (code-level)', async ({ page }) => {
  await page.goto(base + '/index.html');
  const src = await page.evaluate(async () => await (await fetch('/js/activityBar.js')).text());

  // (a) module-level flag exists, defaults to false (sealed).
  expect(src).toMatch(/const SIDEBAR_LEGACY_ENTRIES = false;/);

  // (b) sealing branch hides the entry + popover and skips wiring.
  expect(src).toMatch(/if \(!SIDEBAR_LEGACY_ENTRIES\) \{/);
  expect(src).toContain("btn.hidden = true;");
  expect(src).toContain("pop.hidden = true;");
  expect(src).toMatch(/if \(!SIDEBAR_LEGACY_ENTRIES\) \{[\s\S]*?return;/);

  // (c) the restore path is intact behind the guard: original popover
  //     binding (setOpen wiring + listeners) still present in the function.
  expect(src).toMatch(/const setOpen = \(open\) => \{/);
  expect(src).toContain('anchorLegacyPop(btn, pop)');
  expect(src).toMatch(/btn\.addEventListener\('click'/);
  expect(src).toMatch(/pop\.addEventListener\('click'/);

  // (d) author ruling comment is on the flag (traceability).
  expect(src).toMatch(/2026-09-04[\s\S]{0,600}SIDEBAR_LEGACY_ENTRIES/);
});

// ══ 件 B —「智能体」入口改造「插件」页 ══════════════════════════════════

/** Close the plugins tab if present (boot fallback auto-opens it in a fresh
 *  session — main.js restoreTabs fallback now lands on openPlugins instead
 *  of the sealed openTeams), so the entry-click path can be exercised from
 *  the canvas.js 4-state machine's state 1. */
async function closePluginsTabIfOpen(page) {
  await page.evaluate(() => {
    const tab = document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"] .canvas-tab-close');
    tab?.click();
  });
  await page.waitForFunction(() =>
    !document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]'), { timeout: 5000 });
}

test('B1: plugins page renders catalog + subscription map + config block (zh-CN + en)', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);

    // Boot fallback (2026-09-04 件 B): fresh session with nothing persisted
    // auto-opens the plugins page — the old auto-openTeams is sealed away.
    await page.waitForSelector('#plugins-content .plugins-skill-card', { timeout: 10000 });
    const boot = await page.evaluate(() => ({
      tabOpenAtBoot: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]'),
      teamsTabAtBoot: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="teams"]'),
    }));
    expect(boot.tabOpenAtBoot, `[${locale}] fresh boot lands on the plugins page`).toBe(true);
    expect(boot.teamsTabAtBoot, `[${locale}] sealed Teams panel must not auto-open at boot`).toBe(false);

    // Real entry path: close the auto-opened tab, then #agents-btn must
    // re-open it through the canvas.js 4-state machine (state 1).
    await closePluginsTabIfOpen(page);
    await page.click('#agents-btn');
    await page.waitForSelector('#plugins-content .plugins-skill-card', { timeout: 10000 });
    // Model pills fill asynchronously after the catalog paint — wait for the
    // resolved model to land before reading the config block.
    await page.waitForFunction(() =>
      document.querySelector('.plugins-model-tag[data-agent="Coder"]')?.textContent.trim() === 'test-model',
      { timeout: 5000 });

    const dump = await page.evaluate(() => {
      const q = (s) => document.querySelector(s);
      const qa = (s) => [...document.querySelectorAll(s)];
      const txt = (el) => el?.textContent.trim();
      return {
        tabId: q('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]') ? 'plugins' : null,
        tabLabel: txt(q('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"] .canvas-tab-label')),
        agentsTab: !!q('#canvas-tab-bar .canvas-tab[data-tab-id="agents"]'),
        catalog: !!q('#plugins-section-catalog'),
        skills: qa('.plugins-skill-card .plugins-skill-name').map(txt),
        skillADesc: txt(q('.plugins-skill-card[data-skill="skill-a"] .plugins-skill-desc')),
        skillASubscribers: qa('.plugins-skill-card[data-skill="skill-a"] .plugins-subscriber-chip').map(txt),
        mcpNames: qa('.plugins-mcp-card .plugins-mcp-name').map(txt),
        mcpState: txt(q('.plugins-mcp-card[data-mcp="fs-server"] .plugins-mcp-state')),
        mcpCmd: txt(q('.plugins-mcp-card[data-mcp="fs-server"] .plugins-mcp-cmd')),
        mapRows: qa('#plugins-section-map .plugins-map-row .plugins-agent-name').map(txt),
        coderChecked: qa('.plugins-sub-check[data-agent="Coder"].checked').map(el => el.dataset.skill),
        nebulaA: !!q('.plugins-sub-check[data-agent="Nebula"][data-skill="skill-a"].checked'),
        nebulaUncheckedB: !!q('.plugins-sub-check[data-agent="Nebula"][data-skill="skill-b"]:not(.checked)'),
        configRows: qa('#plugins-section-config .plugins-config-row').length,
        presetOptions: [...(q('.plugins-preset-select[data-agent="Coder"]')?.options || [])].map(o => o.value),
        presetSelected: q('.plugins-preset-select[data-agent="Coder"]')?.value,
        modelTag: txt(q('.plugins-model-tag[data-agent="Coder"]')),
        titles: {
          catalog: txt(q('#plugins-section-catalog .plugins-section-title')),
          map: txt(q('#plugins-section-map .plugins-section-title')),
          config: txt(q('#plugins-section-config .plugins-section-title')),
        },
      };
    });

    expect(dump.tabId, `[${locale}] plugins tab opened by the entry click`).toBe('plugins');
    expect(dump.agentsTab, `[${locale}] entry click must not create an agents tab`).toBe(false);
    expect(dump.tabLabel, `[${locale}] tab label localized`).toBe(locale === 'zh-CN' ? '插件' : 'Plugins');
    expect(dump.catalog, `[${locale}] catalog section present`).toBe(true);
    expect(dump.skills, `[${locale}] skill catalog names`).toEqual(['skill-a', 'skill-b']);
    expect(dump.skillADesc, `[${locale}] skill description rendered`).toBe('Test skill A');
    expect(dump.skillASubscribers.sort(), `[${locale}] skill-a subscribers = agents whose details list it`).toEqual(['Coder', 'Nebula']);
    expect(dump.mcpNames, `[${locale}] MCP server listed`).toEqual(['fs-server']);
    expect(dump.mcpState, `[${locale}] MCP enabled state from WS serverConfig`).toBe(locale === 'zh-CN' ? '已启用' : 'enabled');
    expect(dump.mcpCmd, `[${locale}] MCP command detail from GET /api/mcp`).toContain('fs-mcp');
    expect(dump.mapRows.sort(), `[${locale}] subscription map covers every agent`).toEqual(['Coder', 'Nebula']);
    expect(dump.coderChecked.sort(), `[${locale}] Coder subscription chips checked`).toEqual(['skill-a', 'skill-b']);
    expect(dump.nebulaA, `[${locale}] Nebula skill-a checked`).toBe(true);
    expect(dump.nebulaUncheckedB, `[${locale}] Nebula skill-b unchecked`).toBe(true);
    expect(dump.configRows, `[${locale}] config block: one row per agent`).toBe(2);
    expect(dump.presetOptions, `[${locale}] preset select migrated (default + both presets)`).toEqual(['', 'preset-one', 'preset-two']);
    expect(dump.presetSelected, `[${locale}] preset select honors agent's current preset`).toBe('preset-one');
    expect(dump.modelTag, `[${locale}] resolved model pill`).toBe('test-model');
    expect(dump.titles.catalog, `[${locale}] catalog title zh/en paired`)
      .toBe(locale === 'zh-CN' ? '插件清单' : 'Plugin Catalog');
    expect(dump.titles.map, `[${locale}] map title zh/en paired`)
      .toBe(locale === 'zh-CN' ? '智能体订阅映射' : 'Agent Subscriptions');
    expect(dump.titles.config, `[${locale}] config title zh/en paired`)
      .toBe(locale === 'zh-CN' ? '智能体配置' : 'Agent Configuration');
  }
});

test('B2: old Agents entry gone — button drives the plugins page, panel functions preserved', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    // Boot fallback auto-opens plugins; make sure content is rendered before
    // exercising the deep-link path.
    await page.waitForSelector('#plugins-content .plugins-sub-check', { timeout: 10000 });
    const dump = await page.evaluate(async () => {
      const btn = document.getElementById('agents-btn');
      // After createIconsIn the <i data-lucide> is replaced by an
      // <svg class="lucide lucide-<name>"> — read both forms.
      const holder = btn?.querySelector('[data-lucide]');
      const svg = btn?.querySelector('svg');
      const iconName = holder?.getAttribute('data-lucide')
        || [...(svg?.getAttribute('class') || '').split(' ')].find(c => c.startsWith('lucide-'))?.slice('lucide-'.length)
        || null;
      // Deep-link: the plugins page exposes openAgentDetail via the map rows.
      const am = await import('/js/agentManager.js');
      // Exercise the REAL deep-link path: a subscription-map agent name
      // opens the per-agent detail tab.
      const rowName = document.querySelector('#plugins-section-map .plugins-agent-name[data-detail-agent="Coder"]');
      rowName?.click();
      await new Promise(r => setTimeout(r, 500));
      return {
        btnTitle: btn?.title,
        iconName,
        agentsTabCreated: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="agents"]'),
        pluginsTabCreated: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]'),
        detailTabCreated: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="agent:Coder"]'),
        openAgentsType: typeof am.openAgents,
        renderAgentManagerType: typeof am.renderAgentManager,
        openAgentDetailType: typeof am.openAgentDetail,
      };
    });
    expect(dump.btnTitle, `[${locale}] entry title is Plugins (not Agents)`)
      .toBe(locale === 'zh-CN' ? '插件' : 'Plugins');
    expect(dump.iconName, `[${locale}] icon is puzzle (not bot)`).toBe('puzzle');
    expect(dump.pluginsTabCreated, `[${locale}] plugins tab rendered`).toBe(true);
    expect(dump.agentsTabCreated, `[${locale}] no standalone agents tab may appear`).toBe(false);
    expect(dump.detailTabCreated, `[${locale}] map row deep-links to the agent detail tab`).toBe(true);
    expect(dump.openAgentsType, 'openAgents preserved (hidden entry ≠ deleted function)').toBe('function');
    expect(dump.renderAgentManagerType, 'renderAgentManager preserved').toBe('function');
    expect(dump.openAgentDetailType, 'openAgentDetail exported for the plugins deep-link').toBe('function');
  }
});

test('B3: subscription toggles persist via the existing PUT /api/agents/:name contract', async ({ page }) => {
  // Capture the PUT body off the wire (request interception).
  const captured = [];
  page.on('request', (req) => {
    if (req.method() === 'PUT' && new URL(req.url()).pathname === '/api/agents/Nebula') {
      captured.push(req.postData());
    }
  });

  await loadShell(page, 'zh-CN');
  // Exercise the REAL entry path: close the boot-fallback tab, reopen via
  // the #agents-btn entry, then toggle.
  await page.waitForSelector('#plugins-content .plugins-sub-check', { timeout: 10000 });
  await closePluginsTabIfOpen(page);
  await page.click('#agents-btn');
  await page.waitForSelector('#plugins-content .plugins-sub-check', { timeout: 10000 });

  // Toggle Nebula's unchecked skill-b chip → checked, PUT fires with the
  // merged skills array (same contract agentManager.js already ships).
  await page.click('.plugins-sub-check[data-agent="Nebula"][data-skill="skill-b"]');
  await page.waitForTimeout(300);
  expect(captured.length, 'PUT fired once for the toggle').toBe(1);
  const body = JSON.parse(captured[0]);
  expect(body.skills.sort(), 'PUT body = merged skills list').toEqual(['skill-a', 'skill-b']);

  // UI reflects the new state without a reload.
  const checkedNow = await page.evaluate(() =>
    document.querySelector('.plugins-sub-check[data-agent="Nebula"][data-skill="skill-b"]')?.classList.contains('checked'));
  expect(checkedNow, 'chip shows checked after toggle').toBe(true);
});
