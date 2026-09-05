// sidebar-plugins.spec.mjs — 插件页 shell 级 spec（隔离静态服务器 + 页内
// mock，真实 UI 代码全量执行）。2026-09-04 件 A 部分原样保留（Team/Flow
// 入口封存）；件 B 部分按「插件面板重设计——统一插件系统 + 每插件一开关」
// 重写：
//   B1 统一插件卡片：每插件一卡 = 名称/版本/作者 + 描述摘要 + 内容构成
//      标注（N 个技能·可展开 / MCP server+transport / 内建工具 +N）+
//      trust 语义化状态 pill + 启停开关（页面唯一操作件）。拒载插件出
//      信息卡（无开关）。智能体区块 = 摘要行（名称/描述/preset·model
//      现状）。独立 skills/MCP 板块、订阅 chips、平铺 config row 不复存在；
//      插件页不再请求 /api/skills 与 /api/mcp（独立 MCP 管理入口在设置页）。
//   B2 旧入口不存在：#agents-btn 标题「插件」/Plugins、图标 puzzle；摘要行
//      深链打开 agent:<name> 详情页（openAgentDetail 复用不变）。
//   B3 开关写回契约：点击 on → POST /api/plugins/:name/approve；点击 off →
//      POST /api/plugins/:name/revoke；渲染以 GET /api/plugins 重同步
//      （registry 为单一事实源）。订阅 PUT /api/agents {skills} 调用清零。
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
// GET /api/plugins → approvalManifest[] (PluginRegistry.scala:88-113) + rejected[].
const MOCK_AGENTS = {
  agents: [
    { name: 'Nebula', displayName: 'Nebula', description: 'orchestrator', category: 'standalone', layer: 'global' },
    { name: 'Coder', displayName: 'Coder', description: 'coder agent', category: 'standalone', layer: 'global' },
  ],
};
const MOCK_MODEL = { preferred: 'gpt/test-model', current: 'gpt/test-model', preset: 'preset-one' };

/** Manifest fixture with a mutable trust state (drives B1/B3). */
const pluginState = { approved: false, contentChanged: false };
function fixtureManifest(st) {
  const trusted = st.approved && !st.contentChanged;
  return {
    name: 'e2e-hello',
    version: '1.0.0',
    description: 'E2E fixture plugin for the plugins page',
    author: 'spec',
    digest: 'deadbeefcafe'.repeat(6),
    fileCount: 3,
    trust: trusted
      ? { status: 'trusted', approvedAt: 1757000000, digest: 'deadbeefcafe'.repeat(6) }
      : {
          status: 'untrusted',
          reason: st.contentChanged
            ? 'directory digest changed since approval (approved=aaa111, current=bbb222) — re-approval required (upgrade = re-review)'
            : 'never approved (default-deny)',
        },
    skills: [
      { id: 'e2e-hello/hello', description: 'Say hello', preview: '---\nname: hello\n---\nHello body line' },
      { id: 'e2e-hello/wave', description: 'Wave goodbye', preview: '' },
    ],
    mcpServers: [
      { server: 'fs-server', transport: 'stdio', command: 'npx', args: ['-y', 'fs-mcp'], url: null, envKeys: ['FS_ROOT'] },
    ],
    toolsExtension: ['read_file', 'write_file'],
    warnings: [],
  };
}
const MOCK_REJECTED = [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }];

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ page }) => {
  pluginState.approved = false;
  pluginState.contentChanged = false;
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // API mocks. Playwright route matching is LIFO — the catch-all must be
  // registered FIRST so the specific patterns registered after it win.
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/plugins/e2e-hello/approve', r => {
    if (r.request().method() === 'POST') { pluginState.approved = true; r.fulfill({ json: { ok: true, message: 'approved' } }); }
    else r.fulfill({ json: {} });
  });
  await page.route('**/api/plugins/e2e-hello/revoke', r => {
    if (r.request().method() === 'POST') { pluginState.approved = false; r.fulfill({ json: { ok: true, message: 'revoked' } }); }
    else r.fulfill({ json: {} });
  });
  await page.route('**/api/plugins', r => r.fulfill({ json: { plugins: [fixtureManifest(pluginState)], rejected: MOCK_REJECTED } }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: MOCK_MODEL }));
  await page.route('**/api/agents', r => r.fulfill({ json: MOCK_AGENTS }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
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

// ══ 件 A — Team/Flow 旧入口退役（2026-09-05 旧 UI 退役批改写：
// 原断言「封存但留在 DOM」已失效——作者裁定「直接删除，不是封存」，
// 入口/popover/按钮整体摘除；详细断言见 tests/legacy-ui-retire.spec.mjs，
// 此处保留零残留哨兵防止回归。）════════════════

test('A1: legacy Team/Flow entry retired from the shell (zh-CN + en)', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    const dump = await page.evaluate(() => ({
      btn: !!document.getElementById('legacy-btn'),
      pop: !!document.getElementById('legacy-pop'),
      teams: !!document.getElementById('teams-btn'),
      flows: !!document.getElementById('flows-btn'),
      flowsIndicator: !!document.getElementById('flows-indicator'),
      flowsDropdown: !!document.getElementById('flows-dropdown'),
      // Siblings that share the slot must remain untouched.
      projects: !!document.getElementById('projects-btn'),
      agents: !!document.getElementById('agents-btn'),
      settings: !!document.getElementById('settings-btn'),
    }));
    expect(dump.btn, `[${locale}] legacy-btn retired`).toBe(false);
    expect(dump.pop, `[${locale}] legacy-pop retired`).toBe(false);
    expect(dump.teams, `[${locale}] teams-btn retired`).toBe(false);
    expect(dump.flows, `[${locale}] flows-btn retired`).toBe(false);
    expect(dump.flowsIndicator, `[${locale}] flows-indicator retired`).toBe(false);
    expect(dump.flowsDropdown, `[${locale}] flows-dropdown retired`).toBe(false);
    expect(dump.projects, `[${locale}] projects-btn untouched`).toBe(true);
    expect(dump.agents, `[${locale}] agents-btn untouched`).toBe(true);
    expect(dump.settings, `[${locale}] settings-btn untouched`).toBe(true);
  }
});

test('A2: SIDEBAR_LEGACY_ENTRIES flag removed from activityBar.js (code-level)', async ({ page }) => {
  await page.goto(base + '/index.html');
  const src = await page.evaluate(async () => await (await fetch('/js/activityBar.js')).text());

  // The sealed-entry flag + binding functions are fully deleted (not archived).
  expect(src).not.toMatch(/SIDEBAR_LEGACY_ENTRIES/);
  expect(src).not.toMatch(/bindLegacyPanels/);
  expect(src).not.toMatch(/anchorLegacyPop/);

  // Settings + avatar wiring (the rest of the module) stays intact.
  expect(src).toMatch(/bindSettingsButton\(\)/);
  expect(src).toMatch(/bindAvatar\(\)/);
});

// ══ 件 B — 统一插件系统 + 每插件一开关（重设计后形态）══════════════════

/** Close the plugins tab if present (boot fallback auto-opens it in a fresh
 *  session — main.js restoreTabs fallback lands on openPlugins), so the
 *  entry-click path can be exercised from the canvas.js 4-state machine. */
async function closePluginsTabIfOpen(page) {
  await page.evaluate(() => {
    const tab = document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"] .canvas-tab-close');
    tab?.click();
  });
  await page.waitForFunction(() =>
    !document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]'), { timeout: 5000 });
}

test('B1: unified plugin cards + agent summary rows; no subscription/config blocks (zh-CN + en)', async ({ page }) => {
  const apiRequests = [];
  page.on('request', (req) => {
    const p = new URL(req.url()).pathname;
    if (p === '/api/skills' || p === '/api/mcp') apiRequests.push(p);
  });

  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);

    // Boot fallback: fresh session auto-opens the plugins page.
    await page.waitForSelector('#plugins-content .plugins-card[data-plugin="e2e-hello"]', { timeout: 10000 });
    // Agent summary rows fill model/preset pills asynchronously.
    await page.waitForFunction(() =>
      document.querySelector('.plugins-agent-row[data-detail-agent="Coder"] .plugins-agent-meta-pill')?.textContent.trim() === 'preset-one',
      { timeout: 5000 });

    const dump = await page.evaluate(() => {
      const q = (s) => document.querySelector(s);
      const qa = (s) => [...document.querySelectorAll(s)];
      const txt = (el) => el?.textContent.trim();
      return {
        tabId: q('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]') ? 'plugins' : null,
        tabLabel: txt(q('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"] .canvas-tab-label')),
        // unified card
        card: !!q('.plugins-card[data-plugin="e2e-hello"]'),
        cardName: txt(q('.plugins-card[data-plugin="e2e-hello"] .plugins-card-name')),
        cardMeta: txt(q('.plugins-card[data-plugin="e2e-hello"] .plugins-card-meta')),
        cardDesc: txt(q('.plugins-card[data-plugin="e2e-hello"] .plugins-card-desc')),
        comps: qa('.plugins-card[data-plugin="e2e-hello"] .plugins-comp').map(txt),
        statePill: txt(q('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')),
        switchOff: q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.getAttribute('aria-checked'),
        // expandable skill previews
        expandHiddenBefore: q('[data-expand-for="skills-e2e-hello"]')?.hidden,
        // rejected card
        rejectedPill: txt(q('.plugins-card[data-plugin="broken-plugin"] .plugins-rejected-pill')),
        rejectedNoSwitch: !q('.plugins-card[data-plugin="broken-plugin"] [data-plugin-switch]'),
        // old forms must be gone
        noSubChecks: qa('.plugins-sub-check').length,
        noPresetSelects: qa('.plugins-preset-select').length,
        noModelTags: qa('.plugins-model-tag').length,
        noSkillCards: qa('.plugins-skill-card').length,
        noMcpCards: qa('.plugins-mcp-card').length,
        // agent summary rows
        agentRows: qa('#plugins-section-agents .plugins-agent-row').length,
        coderName: txt(q('.plugins-agent-row[data-detail-agent="Coder"] .plugins-agent-name')),
        coderDesc: txt(q('.plugins-agent-row[data-detail-agent="Coder"] .plugins-agent-desc')),
        coderMeta: qa('.plugins-agent-row[data-detail-agent="Coder"] .plugins-agent-meta-pill').map(txt),
        titles: {
          plugins: txt(q('#plugins-section-plugins .plugins-section-title')),
          agents: txt(q('#plugins-section-agents .plugins-section-title')),
        },
      };
    });

    expect(dump.tabId, `[${locale}] plugins tab opened at boot`).toBe('plugins');
    expect(dump.tabLabel, `[${locale}] tab label localized`).toBe(locale === 'zh-CN' ? '插件' : 'Plugins');
    expect(dump.card, `[${locale}] unified plugin card rendered`).toBe(true);
    expect(dump.cardName, `[${locale}] card shows plugin name`).toBe('e2e-hello');
    expect(dump.cardMeta, `[${locale}] card meta = version + author`).toBe(locale === 'zh-CN' ? 'v1.0.0 · 作者 spec' : 'v1.0.0 · by spec');
    expect(dump.cardDesc, `[${locale}] description summary`).toBe('E2E fixture plugin for the plugins page');
    expect(dump.comps.length, `[${locale}] composition annotations: skills + mcp + tools`).toBe(3);
    expect(dump.comps[0], `[${locale}] skills annotation with count`).toBe(locale === 'zh-CN' ? '2 个技能' : '2 skill(s)');
    expect(dump.comps[1], `[${locale}] mcp annotation with server + transport`).toBe(locale === 'zh-CN' ? 'MCP：fs-server (stdio)' : 'MCP: fs-server (stdio)');
    expect(dump.comps[2], `[${locale}] tools annotation with count`).toBe(locale === 'zh-CN' ? '内建工具 +2' : 'builtin tools +2');
    expect(dump.statePill, `[${locale}] state pill = off for untrusted`).toBe(locale === 'zh-CN' ? '未启用' : 'Off');
    expect(dump.switchOff, `[${locale}] switch aria-checked=false when untrusted`).toBe('false');
    expect(dump.expandHiddenBefore, `[${locale}] skill previews collapsed initially`).toBe(true);
    expect(dump.rejectedPill, `[${locale}] rejected card carries the pill`).toBe(locale === 'zh-CN' ? '拒载' : 'Rejected');
    expect(dump.rejectedNoSwitch, `[${locale}] rejected card has no switch`).toBe(true);
    expect(dump.noSubChecks, `[${locale}] subscription chips removed`).toBe(0);
    expect(dump.noPresetSelects, `[${locale}] preset selects removed`).toBe(0);
    expect(dump.noModelTags, `[${locale}] legacy model tags removed`).toBe(0);
    expect(dump.noSkillCards, `[${locale}] standalone skills section removed`).toBe(0);
    expect(dump.noMcpCards, `[${locale}] standalone MCP section removed`).toBe(0);
    expect(dump.agentRows, `[${locale}] one summary row per agent`).toBe(2);
    expect(dump.coderName, `[${locale}] agent row name`).toBe('Coder');
    expect(dump.coderDesc, `[${locale}] agent row description`).toBe('coder agent');
    expect(dump.coderMeta, `[${locale}] agent row preset + model pills`).toEqual(['preset-one', 'test-model']);
    expect(dump.titles.plugins, `[${locale}] plugins section title zh/en paired`)
      .toBe(locale === 'zh-CN' ? '插件' : 'Plugins');
    expect(dump.titles.agents, `[${locale}] agents section title zh/en paired`)
      .toBe(locale === 'zh-CN' ? '智能体' : 'Agents');

    // Expand path: click the skills annotation → previews show.
    await page.click('.plugins-card[data-plugin="e2e-hello"] .plugins-comp-toggle');
    const expanded = await page.evaluate(() => ({
      visible: !document.querySelector('[data-expand-for="skills-e2e-hello"]')?.hidden,
      ids: [...document.querySelectorAll('[data-expand-for="skills-e2e-hello"] .plugins-skill-item-id')].map(el => el.textContent.trim()),
    }));
    expect(expanded.visible, `[${locale}] skill expand opens`).toBe(true);
    expect(expanded.ids, `[${locale}] expanded skill ids`).toEqual(['e2e-hello/hello', 'e2e-hello/wave']);
  }

  expect(apiRequests, 'plugins page must not call /api/skills or /api/mcp (unified plugin system)').toEqual([]);
});

test('B2: entry drives the plugins page; agent summary row deep-links to detail editor', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await loadShell(page, locale);
    await page.waitForSelector('#plugins-content .plugins-agent-row', { timeout: 10000 });
    const dump = await page.evaluate(async () => {
      const btn = document.getElementById('agents-btn');
      const holder = btn?.querySelector('[data-lucide]');
      const svg = btn?.querySelector('svg');
      const iconName = holder?.getAttribute('data-lucide')
        || [...(svg?.getAttribute('class') || '').split(' ')].find(c => c.startsWith('lucide-'))?.slice('lucide-'.length)
        || null;
      // Deep-link: the agent summary row opens the per-agent detail tab.
      const am = await import('/js/agentManager.js');
      const row = document.querySelector('#plugins-section-agents .plugins-agent-row[data-detail-agent="Coder"]');
      row?.click();
      await new Promise(r => setTimeout(r, 500));
      return {
        btnTitle: btn?.title,
        iconName,
        agentsTabCreated: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="agents"]'),
        detailTabCreated: !!document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="agent:Coder"]'),
        openAgentsType: typeof am.openAgents,
        openAgentDetailType: typeof am.openAgentDetail,
      };
    });
    expect(dump.btnTitle, `[${locale}] entry title is Plugins (not Agents)`)
      .toBe(locale === 'zh-CN' ? '插件' : 'Plugins');
    expect(dump.iconName, `[${locale}] icon is puzzle (not bot)`).toBe('puzzle');
    expect(dump.agentsTabCreated, `[${locale}] no standalone agents tab may appear`).toBe(false);
    expect(dump.detailTabCreated, `[${locale}] summary row deep-links to the agent detail tab`).toBe(true);
    expect(dump.openAgentsType, 'openAgents preserved (hidden entry ≠ deleted function)').toBe('function');
    expect(dump.openAgentDetailType, 'openAgentDetail exported for the summary-row deep-link').toBe('function');
  }
});

test('B3: switch fires the trust contract — approve/revoke POSTs, registry resync, PUT skills zero', async ({ page }) => {
  const puts = [];
  page.on('request', (req) => {
    if (req.method() === 'PUT' && new URL(req.url()).pathname.startsWith('/api/agents')) puts.push(req.url());
  });

  await loadShell(page, 'zh-CN');
  await page.waitForSelector('#plugins-content .plugins-card[data-plugin="e2e-hello"]', { timeout: 10000 });

  // off → on: POST approve, then the page re-fetches the registry (mock flips
  // state) and the card re-renders on.
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.classList.contains('on'),
    { timeout: 5000 });
  const onState = await page.evaluate(() => ({
    ariaChecked: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.getAttribute('aria-checked'),
    pill: document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim(),
  }));
  expect(onState.ariaChecked, 'switch aria-checked=true after approve').toBe('true');
  expect(onState.pill, 'state pill flips to 已启用').toBe('已启用');

  // on → off: POST revoke, card re-renders off.
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]');
  await page.waitForFunction(() =>
    !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.classList.contains('on'),
    { timeout: 5000 });

  // Content-changed path: trust flips back to untrusted with the digest-changed
  // reason → card renders off + the 内容已变更 hint. (The manual refresh
  // button was removed 2026-09-05 — the same render pipeline is triggered
  // directly here; the live poller drives the equivalent refresh in prod.)
  pluginState.contentChanged = true;
  pluginState.approved = true;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await page.waitForSelector('.plugins-card[data-plugin="e2e-hello"].changed', { timeout: 5000 });
  const changed = await page.evaluate(() => ({
    on: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-switch]')?.classList.contains('on'),
    hint: document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-card-hint')?.textContent.trim(),
  }));
  expect(changed.on, 'changed digest renders the switch off').toBe(false);
  expect(changed.hint, 'changed digest shows the re-approval hint').toBe('内容已变更，重新启用将按新内容审批');

  expect(puts, 'PUT /api/agents {skills} subscription writes must be zero (per-plugin switch replaces per-agent config)').toEqual([]);
});
