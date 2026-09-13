// sidebar-plugins.spec.mjs — 插件页 shell 级 spec（隔离静态服务器 + 页内
// mock，真实 UI 代码全量执行）。2026-09-04 件 A 部分原样保留（Team/Flow
// 入口封存）；件 B 部分**2026-09-13 按「无审批」批（装了就是信任）重写**：
//   B1 统一插件卡片：每插件一卡 = 名称/版本/作者 + 描述摘要 + 内容构成
//      标注（N 个技能·可展开 / MCP server+transport / 内建工具 +N）+
//      **状态药丸（绑 blocked / contentChanged，🔴 不绑 trusted）** +
//      **次级动作入口（「更多」→ 封禁/解封）** + **派发开关（卡片底部独立一行）**。
//      拒载插件出信息卡（无任何控件）。智能体区块 = 摘要行。独立 skills/MCP
//      板块、订阅 chips、平铺 config row 不复存在；插件页不再请求 /api/skills
//      与 /api/mcp（独立 MCP 管理入口在设置页）。
//      🔴 **内容审批开关（`[data-plugin-switch]`）已退场**——本 spec 断言它在
//      页面上零残留（无审批批 C7 的「主控件退场」）。
//   B2 旧入口不存在：#agents-btn 标题「插件」/Plugins、图标 puzzle；摘要行
//      深链打开 agent:<name> 详情页（openAgentDetail 复用不变）。
//   B3 封禁/解封写回契约（2026-09-13 重写；原「开关 approve/revoke 契约」随
//      内容审批面退场而作废）：更多菜单 → 封禁 ⇒ POST /api/plugins/:name/revoke
//      （语义 = 封禁）；解封 ⇒ POST /api/plugins/:name/unblock。渲染以
//      GET /api/plugins 重同步（registry 为单一事实源）。订阅 PUT /api/agents
//      {skills} 调用清零；**UI 主线不再调用 /approve**（wire 断言）。
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

/** Manifest fixture with the mutable C6 status (drives B1/B3).
 *  🔴 `trust` 是**契约保留但已不决定装载**的 legacy 块：本 spec 刻意把它写成
 *  untrusted（旧语义下的「未审批」），用来证明**药丸不读 trust**——
 *  一处改错（药丸重新绑回 trust）B1 立刻红。 */
const pluginState = { blocked: false, contentChanged: false };
function fixtureManifest(st) {
  return {
    name: 'e2e-hello',
    version: '1.0.0',
    description: 'E2E fixture plugin for the plugins page',
    author: 'spec',
    digest: 'deadbeefcafe'.repeat(6),
    fileCount: 3,
    // C6（2026-09-13 无审批批）：trusted / blocked（新）/ contentChanged（新）
    trusted: true,
    blocked: st.blocked,
    contentChanged: st.contentChanged,
    trust: {
      status: 'untrusted',
      reason: 'legacy gate record — retained in the payload, no longer decides loading',
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
/** /approve 调用记录——UI 主线不得再调用它（审批语义退场，C5）。 */
const approveCalls = [];

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ page }) => {
  pluginState.blocked = false;
  pluginState.contentChanged = false;
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // API mocks. Playwright route matching is LIFO — the catch-all must be
  // registered FIRST so the specific patterns registered after it win.
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  // 🔴 UI 主线**不再**调用 /approve（审批语义退场）——本路由只用来记录
  // 「有没有人误调它」（B3 的 wire 断言）。
  approveCalls.length = 0;
  await page.route('**/api/plugins/e2e-hello/approve', r => {
    approveCalls.push(r.request().method());
    r.fulfill({ json: { ok: true, message: 'legacy approve (UI must not call this)' } });
  });
  // revoke = 封禁（保留路径名 + 语义翻转，C5）
  await page.route('**/api/plugins/e2e-hello/revoke', r => {
    if (r.request().method() === 'POST') { pluginState.blocked = true; r.fulfill({ json: { ok: true, message: 'blocked' } }); }
    else r.fulfill({ json: {} });
  });
  // unblock = 解封（C5 新增）
  await page.route('**/api/plugins/e2e-hello/unblock', r => {
    if (r.request().method() === 'POST') { pluginState.blocked = false; r.fulfill({ json: { ok: true, message: 'unblocked' } }); }
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
        pillClass: q('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.className,
        // C7 主控件退场：页面级零残留
        contentSwitchCount: qa('#plugins-content [data-plugin-switch]').length,
        // C7 次级动作入口（更多 → 封禁）+ 派发开关（保留，独立一行）
        moreBtn: !!q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-more]'),
        moreExpanded: q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-more]')?.getAttribute('aria-expanded'),
        menuHidden: q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-menu]')?.hidden,
        blockBtnLabel: txt(q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]')),
        blockBtnState: q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]')?.dataset.blocked,
        dispatchSwitchRole: q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.getAttribute('role'),
        dispatchSwitchDisabled: q('.plugins-card[data-plugin="e2e-hello"] [data-plugin-dispatch]')?.disabled,
        // expandable skill previews
        expandHiddenBefore: q('[data-expand-for="skills-e2e-hello"]')?.hidden,
        // rejected card：信息卡，两个动作都不得出现
        rejectedPill: txt(q('.plugins-card[data-plugin="broken-plugin"] .plugins-rejected-pill')),
        rejectedNoControls: !q('.plugins-card[data-plugin="broken-plugin"] [data-plugin-dispatch]')
          && !q('.plugins-card[data-plugin="broken-plugin"] [data-plugin-block]')
          && !q('.plugins-card[data-plugin="broken-plugin"] [data-plugin-more]'),
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
    expect(dump.statePill, `[${locale}] state pill = 已启用 (bound to blocked/contentChanged, NOT to trust)`).toBe(locale === 'zh-CN' ? '已启用' : 'Enabled');
    expect(dump.pillClass, `[${locale}] pill carries the on class`).toContain('on');
    // C7 主控件退场 + 次级动作入口 + 派发开关保留
    expect(dump.contentSwitchCount, `[${locale}] content approval switch retired — zero on the page`).toBe(0);
    expect(dump.moreBtn, `[${locale}] 更多（次级动作入口）present`).toBe(true);
    expect(dump.moreExpanded, `[${locale}] 更多 collapsed by default`).toBe('false');
    expect(dump.menuHidden, `[${locale}] 次级动作行 collapsed by default`).toBe(true);
    expect(dump.blockBtnLabel, `[${locale}] 封禁入口文案 = locale plugins.blockAction`)
      .toBe(locale === 'zh-CN' ? '封禁该插件' : 'Block this plugin');
    expect(dump.blockBtnState, `[${locale}] data-blocked = 0 when not blocked`).toBe('0');
    expect(dump.dispatchSwitchRole, `[${locale}] dispatch switch still on the card (role=switch)`).toBe('switch');
    expect(dump.dispatchSwitchDisabled, `[${locale}] dispatch switch NOT blocked when the plugin is not blocked`).toBe(false);
    expect(dump.expandHiddenBefore, `[${locale}] skill previews collapsed initially`).toBe(true);
    expect(dump.rejectedPill, `[${locale}] rejected card carries the pill`).toBe(locale === 'zh-CN' ? '拒载' : 'Rejected');
    expect(dump.rejectedNoControls, `[${locale}] rejected card carries no control at all`).toBe(true);
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

/** 卡片形态读数（B3 用）：药丸 / 封禁入口 / 派发开关 / 提示行。 */
async function cardDump(page) {
  return page.evaluate(() => {
    const card = document.querySelector('.plugins-card[data-plugin="e2e-hello"]');
    const btn = card?.querySelector('[data-plugin-block]');
    const dw = card?.querySelector('[data-plugin-dispatch]');
    return {
      pill: card?.querySelector('.plugins-state-pill')?.textContent.trim() ?? null,
      pillClass: card?.querySelector('.plugins-state-pill')?.className ?? null,
      blockLabel: btn?.textContent.trim() ?? null,
      blockState: btn?.dataset.blocked ?? null,
      blockDisabled: btn ? btn.disabled : null,
      dispatchDisabled: dw ? dw.disabled : null,
      dispatchBlockedAttr: dw?.getAttribute('data-dispatch-blocked') ?? null,
      blockedHint: card?.querySelector('.plugins-card-hint.blocked')?.textContent.trim() ?? null,
      changedHint: card?.querySelector('.plugins-card-hint.changed')?.textContent.trim() ?? null,
    };
  });
}

test('B3: block/unblock contract — 更多 → 封禁 ⇒ POST /revoke; 解封 ⇒ POST /unblock; /approve never called', async ({ page }) => {
  const puts = [];
  const posts = [];
  page.on('request', (req) => {
    if (req.method() === 'PUT' && new URL(req.url()).pathname.startsWith('/api/agents')) puts.push(req.url());
    if (req.method() === 'POST' && new URL(req.url()).pathname.startsWith('/api/plugins/')) posts.push(new URL(req.url()).pathname);
  });

  await loadShell(page, 'zh-CN');
  await page.waitForSelector('#plugins-content .plugins-card[data-plugin="e2e-hello"]', { timeout: 10000 });

  // 起点：未封禁 ⇒ 已启用 + 派发开关可用（在位即信任；药丸不读 legacy trust）。
  const before = await cardDump(page);
  expect(before.pill, 'start: 已启用').toBe('已启用');
  expect(before.dispatchDisabled, 'start: dispatch switch usable').toBe(false);
  expect(before.blockState, 'start: block entry offers 封禁').toBe('0');

  // 次级动作：展开更多菜单是不可点动作的前置（不可见即不可点 = 零静默的形态保证）。
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-more]');
  await page.waitForFunction(() =>
    !document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-menu]')?.hidden, { timeout: 5000 });
  const expanded = await page.evaluate(() => ({
    aria: document.querySelector('.plugins-card[data-plugin="e2e-hello"] [data-plugin-more]')?.getAttribute('aria-expanded'),
  }));
  expect(expanded.aria, '更多按钮 aria-expanded=true when open').toBe('true');

  // 封禁 ⇒ POST /revoke（语义 = 封禁）⇒ 注册表回 blocked:true ⇒ 卡片转「已封禁」。
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim() === '已封禁',
    { timeout: 8000 });
  const blocked = await cardDump(page);
  expect(blocked.pillClass, 'blocked pill class').toContain('blocked');
  expect(blocked.blockLabel, 'block entry flips to 解封').toBe('解封该插件');
  expect(blocked.blockState, 'data-blocked=1').toBe('1');
  expect(blocked.dispatchDisabled, 'blocked ⇒ dispatch switch disabled (封禁下写 authorEnabled 不会生效)').toBe(true);
  expect(blocked.dispatchBlockedAttr, 'blocked reason exposed on the element').toBe('blocked');
  expect(blocked.blockedHint, 'blocked hint is actionable and non-empty').toBeTruthy();
  expect(posts, 'wire: exactly one POST /revoke (block)').toEqual(['/api/plugins/e2e-hello/revoke']);

  // 解封 ⇒ POST /unblock ⇒ 回「已启用」+ 派发开关恢复可用。
  await page.click('.plugins-card[data-plugin="e2e-hello"] [data-plugin-block]');
  await page.waitForFunction(() =>
    document.querySelector('.plugins-card[data-plugin="e2e-hello"] .plugins-state-pill')?.textContent.trim() === '已启用',
    { timeout: 8000 });
  const after = await cardDump(page);
  expect(after.pillClass, 'pill back to on').toContain('on');
  expect(after.dispatchDisabled, 'dispatch switch usable again').toBe(false);
  expect(after.dispatchBlockedAttr, 'blocked attribute cleared').toBe(null);
  expect(after.blockedHint, 'blocked hint removed').toBe(null);
  expect(posts, 'wire: revoke then unblock, nothing else').toEqual([
    '/api/plugins/e2e-hello/revoke', '/api/plugins/e2e-hello/unblock',
  ]);

  // 内容已变更 = **非拦截**可见性：卡片**不**被封禁、派发开关**不**锁死，
  // 只是药丸与提示行改义（内容已按新版本生效，不再需要审批）。
  pluginState.contentChanged = true;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await page.waitForSelector('.plugins-card[data-plugin="e2e-hello"] .plugins-card-hint.changed', { timeout: 5000 });
  const changed = await cardDump(page);
  expect(changed.pill, 'contentChanged ⇒ 药丸改义为「内容已变更」（不是封禁）').toBe('内容已变更');
  expect(changed.pillClass, 'pill 带 changed class').toContain('changed');
  expect(changed.changedHint, 'contentChanged shows the visibility-only hint')
    .toBe('内容与上次记录的版本不同——已按新内容生效，此提示仅为可见性（无需再审批）');
  expect(changed.blockedHint, 'contentChanged 不是封禁——无封禁提示').toBe(null);
  expect(changed.dispatchDisabled, 'contentChanged does NOT disable dispatch (≠ 封禁)').toBe(false);
  expect(changed.dispatchBlockedAttr, '无 data-dispatch-blocked').toBe(null);

  expect(approveCalls, 'UI 主线绝不调用 /approve（审批语义已退场，C5）').toEqual([]);
  expect(puts, 'PUT /api/agents {skills} subscription writes must be zero (per-plugin controls replace per-agent config)').toEqual([]);
});
