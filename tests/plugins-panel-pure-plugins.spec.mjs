// plugins-panel-pure-plugins.spec.mjs — 2026-10-21 作者令批（插件面板七条）验收 spec
//
// 断言链（七条令对应）：
//   ① 只看纯插件：agent 区块整段退役——#plugins-section-agents 零在场、
//      .plugins-agent-* 零元素、页面零 /api/agents 请求（纯插件视角）。
//   ③ 展开态布局收紧：展开态行摘要 display:none（旧 visibility:hidden 占位留白
//      退役——空间塌缩）；详情区间距收密（gap ≤6、padding-top ≤8）。
//   ④ 不再提示变更：contentChanged=true 清单不产生任何变更 UI（药丸 changed 档 /
//      .plugins-card-hint.changed / 卡片 changed 类全零在场）；blocked 面保留
//      （药丸 + hint + 类三件照常）。
//   ⑤ 统计口径：顶部统计 = skill 总数 + MCP server 总数（data-stat-skills /
//      data-stat-mcp 数值钉 + locale 键现读文案钉）。
//   ⑥ skill 胶囊深链：点击胶囊 → 取 /api/plugin-assets/<name>/skills/<skill>/
//      SKILL.md → 既有 Canvas 通路开 markdown tab（id = plugin-skill-md:<id>，
//      正文在 pane）；404 → toast + 零 tab（零静默）。
//   ⑦ nebflow.space 品牌残留：插件面板数据链静态扫描——seed 全部包的
//      plugin.json description / Plugin.md / SKILL.md frontmatter description
//      零裸 nebflow.space（neblink.nebflow.space 服务地址与显式 legacy 标注除外）。
//
// Shell 级：隔离静态服务器（127.0.0.1:8185，8100+ 纪律，绝非宿主 8080）+
// 页内 mock（/api/** 与 /api/plugin-assets/**），真实 UI 代码全量执行。
// 零 locale 键值断言 = 页内动态读 locale 真值（不硬编码文案）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(process.env.PLUGINS_SPEC_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web'));
const PORT = 8185; // fixed, 8100+ — never the host 8080/8091/8092 (8183 = itemize spec)
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

const DOC = 'capsule-doc';      // 夹具包：Plugin.md 200（入口在场 + 深链腿）
const CHANGED = 'changed-pkg';  // contentChanged=true（④ 退役腿）
const BLOCKED = 'blocked-pkg';  // blocked=true（blocked 面保留腿）
const DOC_TEXT = '# Fixture Plugin.md\n\nPure-plugins spec fixture.\n';
const SKILL_TEXT = '# Skill Doc\n\nCapsule deep-link fixture body.\n';

const registry = { plugins: [], rejected: [] };
let docProbes = [];
let skillFetches = [];
let apiAgentCalls = [];
let pluginsCalls = [];

function manifest(name, over = {}) {
  return {
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} (pure-plugins spec)`,
    author: 'pure-spec',
    digest: 'a'.repeat(72),
    fileCount: 1,
    trusted: true,
    blocked: false,
    contentChanged: false,
    skills: [{ id: `${name}/alpha`, description: `Alpha of ${name}`, preview: 'body' }],
    mcpServers: [],
    toolsExtension: [],
    warnings: [],
    ...over,
  };
}

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(() => {
  registry.plugins = [
    manifest(DOC, {
      skills: [
        { id: `${DOC}/alpha`, description: 'Alpha skill', preview: 'alpha body' },
        { id: `${DOC}/beta`, description: 'Beta skill', preview: 'beta body' },
      ],
      mcpServers: [{ server: 'fs-server', transport: 'stdio', command: 'uvx', args: ['mcp-fs'], url: null, envKeys: [] }],
    }),
    manifest('plain-a', { skills: [{ id: 'plain-a/s1', description: 'S one', preview: '' }] }),
    manifest('plain-b', { skills: [], mcpServers: [
      { server: 'srv-a', transport: 'stdio', command: 'x', args: [], url: null, envKeys: [] },
      { server: 'srv-b', transport: 'streamable-http', command: null, args: [], url: 'http://x', envKeys: [] },
    ] }),
    manifest(CHANGED, { contentChanged: true }),
    manifest(BLOCKED, { blocked: true }),
  ];
  registry.rejected = [];
  docProbes = [];
  skillFetches = [];
  apiAgentCalls = [];
  pluginsCalls = [];
});

async function loadShell(page) {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/agents**', r => { apiAgentCalls.push(r.request().url()); return r.fulfill({ json: { agents: [] } }); });
  await page.route('**/api/plugin-assets/*/Plugin.md*', r => {
    const name = decodeURIComponent(r.request().url().match(/plugin-assets\/([^/?]+)\/Plugin\.md/)?.[1] || '');
    docProbes.push(name);
    if (name === DOC) return r.fulfill({ body: DOC_TEXT, contentType: 'text/markdown' });
    return r.fulfill({ status: 404, body: 'not found' });
  });
  await page.route(/\/api\/plugin-assets\/[^/]+\/skills\/[^/]+\/SKILL\.md$/, r => {
    const m = r.request().url().match(/plugin-assets\/([^/]+)\/skills\/([^/]+)\/SKILL\.md$/);
    skillFetches.push(`${decodeURIComponent(m?.[1] || '')}/${decodeURIComponent(m?.[2] || '')}`);
    if (m?.[2] === 'alpha') return r.fulfill({ body: SKILL_TEXT, contentType: 'text/markdown' });
    return r.fulfill({ status: 404, body: 'not found' });
  });
  await page.route('**/api/plugins', r => { pluginsCalls.push(r.request().url()); return r.fulfill({ json: { plugins: registry.plugins, rejected: registry.rejected } }); });
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await page.waitForSelector(`#plugins-content .plugins-card[data-plugin="${DOC}"]`, { timeout: 10000 });
}

async function localeTruth(page, keyStr, params) {
  return page.evaluate(([k, p]) => import('/js/i18n.js').then(mod => mod.t(k, p)), [keyStr, params ?? null]);
}

function expand(page, name) {
  return page.click(`.plugins-card[data-plugin="${name}"] [data-plugin-item]`);
}

// ══ ① 只看纯插件 ═════════════════════════════════════════════════════════

test('① 纯插件视角：agent 区块/行/深链零在场；页面零 /api/agents 请求', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  const dump = await page.evaluate(() => ({
    agentsSection: !!document.getElementById('plugins-section-agents'),
    agentRows: document.querySelectorAll('.plugins-agent-row').length,
    agentList: document.querySelectorAll('.plugins-agent-list').length,
    detailAgentHooks: document.querySelectorAll('[data-detail-agent]').length,
    pluginsSection: !!document.getElementById('plugins-section-plugins'),
    cards: document.querySelectorAll('#plugins-section-plugins .plugins-card').length,
  }));
  expect(dump.agentsSection, 'agent 区块零在场（纯插件视角）').toBe(false);
  expect(dump.agentRows, 'agent 行零元素').toBe(0);
  expect(dump.agentList, 'agent 列表容器零在场').toBe(0);
  expect(dump.detailAgentHooks, 'agent 深链钩子零在场').toBe(0);
  expect(dump.pluginsSection, '插件区块在场').toBe(true);
  expect(dump.cards, '五夹具包全部渲染为插件条目').toBe(5);
  // 零 /api/agents 归因到面板数据链：整页重渲一轮（panel-owned 管线），窗口内
  // /api/plugins 到场而 /api/agents 零增量（应用 boot 腿的其他调用不进本断言）。
  const agentsBefore = apiAgentCalls.length;
  const pluginsBefore = pluginsCalls.length;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await expect.poll(() => pluginsCalls.length, { timeout: 8000 }).toBeGreaterThan(pluginsBefore);
  await page.waitForTimeout(400);
  expect(pluginsCalls.length, '重渲一轮真实发生（/api/plugins 到场）').toBeGreaterThan(pluginsBefore);
  expect(apiAgentCalls.length, '面板数据链零 /api/agents 请求（枚举链前端腿整段退役）').toBe(agentsBefore);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ③ 展开态布局收紧 ═════════════════════════════════════════════════════

test('③ 展开态布局收紧：行摘要 display:none（空间塌缩非占位隐藏）；详情区间距收密', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  const before = await page.evaluate((n) => {
    const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
    const sum = card.querySelector('.plugins-item-summary');
    const cs = getComputedStyle(sum);
    return { collapsedDisplay: cs.display, collapsedH: sum.getBoundingClientRect().height };
  }, DOC);
  expect(before.collapsedDisplay, '收起态摘要正常显示（-webkit-box clamp 形态）').not.toBe('none');
  expect(before.collapsedH, '收起态摘要有实际占位').toBeGreaterThan(0);

  await expand(page, DOC);
  const expanded = await page.evaluate((n) => {
    const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
    const sum = card.querySelector('.plugins-item-summary');
    const detail = card.querySelector('.plugins-item-detail');
    const cs = getComputedStyle(detail);
    return {
      summaryDisplay: getComputedStyle(sum).display,
      summaryBox: sum.getBoundingClientRect().height, // display:none ⇒ 0（空间塌缩判据）
      gap: parseFloat(cs.rowGap || cs.gap || '0'),
      paddingTop: parseFloat(cs.paddingTop || '0'),
      descPresent: !!detail.querySelector('.plugins-card-desc'),
    };
  }, DOC);
  expect(expanded.summaryDisplay, '展开态行摘要 display:none（visibility 占位隐藏退役）').toBe('none');
  expect(expanded.summaryBox, '展开态摘要占位高度归零（中部大留白塌缩）').toBe(0);
  expect(expanded.gap, '详情区 gap 收密（≤6px）').toBeLessThanOrEqual(6);
  expect(expanded.paddingTop, '详情区 padding-top 收密（≤8px）').toBeLessThanOrEqual(8);
  expect(expanded.descPresent, '展开态描述全文由详情区承载（无信息丢失）').toBe(true);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ④ 不再提示变更 ═══════════════════════════════════════════════════════

test('④ 变更提示退役：contentChanged 清单零变更 UI；blocked 面三件照常', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  await expand(page, CHANGED);
  await expand(page, BLOCKED);
  const dump = await page.evaluate(([changed, blocked]) => {
    const read = (name) => {
      const card = document.querySelector(`.plugins-card[data-plugin="${name}"]`);
      return {
        cls: [...card.classList].filter(c => c === 'changed' || c === 'blocked'),
        pills: [...card.querySelectorAll('.plugins-state-pill')].map(p => p.className),
        hints: [...card.querySelectorAll('.plugins-card-hint')].map(h => h.className),
      };
    };
    return { changed: read(changed), blocked: read(blocked) };
  }, [CHANGED, BLOCKED]);

  expect(dump.changed.cls, 'contentChanged 卡片零 changed 类').toEqual([]);
  expect(dump.changed.pills, 'contentChanged 卡片零状态药丸（变更档退役后默认态无药丸）').toEqual([]);
  expect(dump.changed.hints, 'contentChanged 卡片零变更提示条').toEqual([]);

  expect(dump.blocked.cls, 'blocked 卡片保留 blocked 类').toContain('blocked');
  expect(dump.blocked.pills.length, 'blocked 药丸在场').toBe(1);
  expect(dump.blocked.pills[0], 'blocked 药丸带 blocked 档').toContain('blocked');
  expect(dump.blocked.hints.length, 'blocked 提示条在场（可行动信号保留）').toBe(1);
  expect(dump.blocked.hints[0], '提示条为 blocked 档（非 changed）').toContain('blocked');
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑤ 顶部统计口径 ═══════════════════════════════════════════════════════

test('⑤ 顶部统计 = skill 总数 + MCP server 总数（数值钉 + locale 文案钉）', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  // 夹具口径：skills = DOC(2) + plain-a(1) + plain-b(0) + changed(1) + blocked(1) = 5；
  // mcp = DOC(1) + plain-b(2) = 3。
  const dump = await page.evaluate(() => {
    const stats = document.querySelector('[data-plugins-stats]');
    return {
      present: !!stats,
      skillsAttr: stats?.querySelector('[data-stat-skills]')?.getAttribute('data-stat-skills'),
      mcpAttr: stats?.querySelector('[data-stat-mcp]')?.getAttribute('data-stat-mcp'),
      skillsText: stats?.querySelector('[data-stat-skills]')?.textContent.trim(),
      mcpText: stats?.querySelector('[data-stat-mcp]')?.textContent.trim(),
      inTopbar: !!stats?.closest('.plugins-topbar'),
    };
  });
  expect(dump.present, '顶部统计元素在场').toBe(true);
  expect(dump.inTopbar, '统计在顶部栏（topbar 内）').toBe(true);
  expect(dump.skillsAttr, 'skill 总数（全注册表面求和）').toBe('5');
  expect(dump.mcpAttr, 'MCP server 总数（全注册表面求和）').toBe('3');
  const skillsTruth = await localeTruth(page, 'plugins.statSkills', { n: 5 });
  const mcpTruth = await localeTruth(page, 'plugins.statMcp', { n: 3 });
  expect(dump.skillsText, 'skill 计数文案 = locale 键现读').toBe(skillsTruth);
  expect(dump.mcpText, 'MCP 计数文案 = locale 键现读').toBe(mcpTruth);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑥ skill 胶囊深链进 Canvas ════════════════════════════════════════════

test('⑥ 胶囊深链：点击 → 取 SKILL.md → Canvas markdown tab（正文在 pane）；404 → toast 零 tab', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);
  await expand(page, DOC);

  await page.click(`.plugins-card[data-plugin="${DOC}"] [data-skill-id="${DOC}/alpha"]`);
  // canvas-stack v5 后桌面标签行恒 display:none：激活形态 = pane active；
  // 标签 dataset 读数不受隐藏影响。
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="plugin-skill-md:capsule-doc/alpha"].active', { timeout: 8000 });
  const tab = await page.evaluate(() => {
    const t = document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugin-skill-md:capsule-doc/alpha"]');
    return { present: !!t, active: t?.classList.contains('active') ?? false, title: t?.querySelector('.canvas-tab-label')?.textContent.trim(),
      barHidden: getComputedStyle(document.getElementById('canvas-tab-bar')).display === 'none' };
  });
  expect(tab.present, 'Canvas tab 模型在位（id = plugin-skill-md:<plugin>/<skill>）').toBe(true);
  expect(tab.active, '新 tab 即激活').toBe(true);
  expect(tab.title, 'tab 标题 = <plugin>/<skill> · SKILL.md').toBe('capsule-doc/alpha · SKILL.md');
  expect(tab.barHidden, '桌面标签行退役防复活（display:none）').toBe(true);
  expect(skillFetches, 'SKILL.md 经既有 plugin-assets 路由取文').toEqual(['capsule-doc/alpha']);
  const bodySeen = await page.evaluate(() => document.body.innerHTML.includes('Capsule deep-link fixture body.'));
  expect(bodySeen, 'SKILL.md 正文进 Canvas 渲染面（文档全文归 Canvas，面板不承载）').toBe(true);

  // 404 腿：beta 胶囊 → toast（零静默）+ 零新 tab。（alpha 打开已把活动 pane 切到
  // 文档 tab——先切回插件 pane 再点，胶囊在非活动 pane 内不可点。桌面标签行
  // display:none 不可鼠标点击 ⇒ 程序化触发标签激活 handler。）
  await page.evaluate(() => document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugins"]')?.click());
  await page.waitForFunction(() =>
    document.querySelector('.canvas-tab-pane[data-tab-id="plugins"]')?.classList.contains('active'), null, { timeout: 8000 });
  const tabsBefore = await page.evaluate(() => document.querySelectorAll('#canvas-tab-bar .canvas-tab').length);
  await page.click(`.plugins-card[data-plugin="${DOC}"] [data-skill-id="${DOC}/beta"]`);
  await page.waitForSelector('.nebflow-toast', { timeout: 8000 });
  const tabsAfter = await page.evaluate(() => document.querySelectorAll('#canvas-tab-bar .canvas-tab').length);
  expect(tabsAfter, '404 包零新 tab（零静默空操作）').toBe(tabsBefore);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑦ 品牌残留静态扫描（面板数据链）══════════════════════════════════════

test('⑦ 品牌清扫：面板可见文案链（plugin.json description / Plugin.md / SKILL.md description）零裸 nebflow.space', async () => {
  const { readdirSync, readFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const seedDir = join(HERE, '..', 'src', 'main', 'resources', 'seed', 'plugins');
  const offenders = [];
  const bare = /(?<![\w.-])nebflow\.space(?![\w-])/; // 裸域；neblink.nebflow.space 服务地址不命中
  for (const dir of readdirSync(seedDir)) {
    const pkg = join(seedDir, dir);
    // a) plugin.json description（面板行摘要直读）
    try {
      const j = JSON.parse(readFileSync(join(pkg, 'plugin.json'), 'utf8'));
      if (bare.test(String(j.description || ''))) offenders.push(`${dir}/plugin.json description`);
    } catch { /* 无 manifest 的目录跳过 */ }
    // b) Plugin.md（分配注入面 + 面板入口文档）
    try {
      const md = readFileSync(join(pkg, 'Plugin.md'), 'utf8');
      if (bare.test(md)) offenders.push(`${dir}/Plugin.md`);
    } catch { /* 缺文件不在此断言（必要文件机制归 PluginInjectionSpec） */ }
    // c) SKILL.md frontmatter description（胶囊文本直读）
    let skillsDir;
    try { skillsDir = readdirSync(join(pkg, 'skills')); } catch { continue; }
    for (const sk of skillsDir) {
      try {
        const text = readFileSync(join(pkg, 'skills', sk, 'SKILL.md'), 'utf8');
        const fm = text.match(/^---\n[\s\S]*?\n---/);
        if (fm && bare.test(fm[0])) offenders.push(`${dir}/skills/${sk}/SKILL.md frontmatter`);
      } catch { /* 单 skill 目录无 SKILL.md 跳过 */ }
    }
  }
  expect(offenders, `面板可见文案链零裸 nebflow.space（offenders: ${offenders.join(', ')}）`).toEqual([]);
});
