// plugins-panel-itemize.spec.mjs — 2026-10-07 插件管理面板条目式重构验收 spec
//（作者 09:40 令，与微信②令同规格：默认仅条目行，点击展开详情，再点收回）。
//
// 断言链（条目式钉）：
//   ① 默认态单行：每插件仅一条目行 = chevron + 名称 + 版本 meta + 状态药丸 +
//      一行摘要；详情区默认 hidden；行尾派发开关在场（2026-10-07 14:13 令①，
//      -row 钩子第二视图；既有钩子仍钉详情区）；空态药丸零元素（「删掉空框」
//      口径延续）。
//   ② 展开收起：点击条目行 ⇄ 详情区显隐 + aria-expanded/class 同步（accordion
//      语义自洽，逐条目独立）；Enter/Space 键盘可达。
//   ③ 操作钮层级：派发开关收进详情区 `.plugins-item-actions` 行（只动层级）——
//      乐观翻转 → POST /disable → 收敛，展开态跨收敛保持（节点身份不变）。
//   ④ skills 胶囊（2026-10-21 作者令⑥）：详情区逐 skill =「胶囊小图标 + 描述」
//      紧凑列表（data-skill-doc/data-skill-id 钩子）；旧 id+desc+preview 内联渲染
//      退役（.plugins-skill-preview 零在场）；多 skill 时清单容器内滚动
//      （max-height + overflow-y 计算样式钉）。
//   ⑤ Plugin.md 入口：包根有 Plugin.md 才在场（既有 /api/plugin-assets/<name>/
//      Plugin.md 探针 200；404 包永不在场）——只做入口呈现，点击走既有 Canvas
//      通路（workspace-open-item → markdown viewer，tab 出现且内容在 pane）；
//      探针一次/包/会话缓存（整页重渲不重发探测请求）。
//   ⑥ 组件面 org.nebflow 现读投影：viewerContributions.viewers[].name 数据投影
//      chip（无贡献 ⇒ 不渲染）；mcp/tools 既有 chips 照旧。
//   ⑦ 整页重渲（renderPlugins）⇒ 展开态回落默认收起（现机制无记忆 ⇒ 不新造
//      持久化）；rejected 条目 = 非展开信息行（无详情/无控件）。
//   ⑧ 全程 pageerror = 0。
//
// Shell 级：隔离静态服务器（127.0.0.1:8183，8100+ 纪律，绝非宿主 8080）+
// 页内 mock（/api/** 与 /api/plugin-assets/**），真实 UI 代码全量执行。
// 零 locale 键值断言 = 页内动态读 locale 真值（不硬编码文案）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// PLUGINS_SPEC_WEB escape hatch: point the static server at a bootable web
// snapshot when the SHARED main tree is mid-write by a parallel batch
// (e.g. 2026-10-07 chat.js/chatView.js in-flight pair broke module boot).
// Default = the repo web dir (normal case).
const WEB = resolve(process.env.PLUGINS_SPEC_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web'));
const PORT = 8183; // fixed, 8100+ — never the host 8080/8091/8092 (8179/8181 = other specs)
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

// ── Mock backend (shapes mirror RestApiRoutes.scala / PluginRegistry.scala) ──
const MOCK_AGENTS = {
  agents: [
    { name: 'Nebula', displayName: 'Nebula', description: 'orchestrator', category: 'standalone', layer: 'global' },
  ],
};
const MOCK_MODEL = { preferred: 'gpt/test-model', current: 'gpt/test-model', preset: 'preset-one' };

const DOC_OK = 'doc-ok';       // 包根有 Plugin.md 的夹具包（入口在场腿）
const DOC_ABSENT = 'doc-absent'; // 包根无 Plugin.md 的夹具包（入口缺席腿）
const DOC_TEXT = '# Fixture Plugin.md\n\nEntry-presentation fixture for the itemized panel spec.\n';

/** Mutable registry — reset per test in beforeEach. `many-skills` = skill 多包
 *  （12 个——胶囊紧凑形态下触发清单内滚动断言的真实溢出）；`doc-absent` 顺带断言 org.nebflow 无贡献不渲染。 */
const registry = { plugins: [], rejected: [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }] };
/** Probe bookkeeping: every /api/plugin-assets/&lt;name&gt;/Plugin.md request the page makes. */
let docProbes = [];
/** Skill-doc bookkeeping: every `&lt;plugin&gt;/&lt;skill&gt;` SKILL.md fetch the page makes. */
let skillDocFetches = [];

function manifest(name, over = {}) {
  return {
    name,
    version: '1.0.0',
    description: `Fixture plugin ${name} (itemize spec)`,
    author: 'itemize-spec',
    digest: 'a'.repeat(72),
    fileCount: 1,
    trusted: true,
    blocked: false,
    contentChanged: false,
    skills: [{ id: `${name}/s`, description: 'Skill', preview: 'body' }],
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
    manifest(DOC_OK, {
      skills: [
        { id: `${DOC_OK}/alpha`, description: 'Alpha skill', preview: 'alpha body' },
        { id: `${DOC_OK}/beta`, description: 'Beta skill', preview: 'beta body' },
      ],
      mcpServers: [{ server: 'fs-server', transport: 'stdio', command: 'uvx', args: ['mcp-fs'], url: null, envKeys: [] }],
      toolsExtension: ['tool_a', 'tool_b'],
    }),
    manifest('many-skills', {
      // 12 个 skill：胶囊紧凑形态下（作者令⑥）6 个已不再溢出 240px 容器——
      // 12 个（≈12×26px + 11×4px gap）才能触发内滚动断言的真实溢出。
      skills: Array.from({ length: 12 }, (_, i) => ({ id: `many-skills/s${i + 1}`, description: `Skill ${i + 1}`, preview: `s${i + 1} body` })),
      viewerContributions: { viewers: [{ name: 'bar-chart' }, { name: 'map-view' }] },
    }),
    manifest(DOC_ABSENT),
  ];
  registry.rejected = [{ name: 'broken-plugin', reason: 'missing plugin.json manifest' }];
  docProbes = [];
  skillDocFetches = [];
});

/** Shell load with the full API/WS/asset mock set. */
async function loadShell(page) {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/plugin-assets/*/Plugin.md*', r => {
    const name = decodeURIComponent(r.request().url().match(/plugin-assets\/([^/?]+)\/Plugin\.md/)?.[1] || '');
    docProbes.push(name);
    if (name === DOC_OK) return r.fulfill({ body: DOC_TEXT, contentType: 'text/markdown' });
    return r.fulfill({ status: 404, body: 'not found' });
  });
  await page.route(/\/api\/plugin-assets\/[^/]+\/skills\/[^/]+\/SKILL\.md$/, r => {
    const url = r.request().url();
    const m = url.match(/plugin-assets\/([^/]+)\/skills\/([^/]+)\/SKILL\.md$/);
    skillDocFetches.push(`${decodeURIComponent(m?.[1] || '')}/${decodeURIComponent(m?.[2] || '')}`);
    if (m?.[1] === DOC_OK) {
      return r.fulfill({ body: `# SKILL ${m[2]}\n\nSkill doc body for ${m[2]} (itemize spec fixture).\n`, contentType: 'text/markdown' });
    }
    return r.fulfill({ status: 404, body: 'not found' });
  });
  await page.route('**/api/plugins/*/enable', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    const name = r.request().url().match(/plugins\/([^/]+)\/enable/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, dispatch: { ...(p.dispatch || {}), authorEnabled: true } } : p);
    r.fulfill({ json: { ok: true } });
  });
  await page.route('**/api/plugins/*/disable', r => {
    if (r.request().method() !== 'POST') return r.fulfill({ json: {} });
    const name = r.request().url().match(/plugins\/([^/]+)\/disable/)?.[1];
    registry.plugins = registry.plugins.map(p => p.name === name ? { ...p, dispatch: { ...(p.dispatch || {}), authorEnabled: false } } : p);
    r.fulfill({ json: { ok: true } });
  });
  await page.route('**/api/plugins', r => r.fulfill({ json: { plugins: registry.plugins, rejected: registry.rejected } }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: MOCK_MODEL }));
  await page.route('**/api/agents', r => r.fulfill({ json: MOCK_AGENTS }));
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
  await page.waitForSelector(`#plugins-content .plugins-card[data-plugin="${DOC_OK}"]`, { timeout: 10000 });
}

/** locale 真值（页内 t() 现读，不硬编码文案——与 two-actions spec 同纪律）。 */
async function localeTruth(page, keyStr, params) {
  return page.evaluate(([k, p]) => import('/js/i18n.js').then(mod => mod.t(k, p)), [keyStr, params ?? null]);
}

/** 条目行形态读数（①/②/⑦ 共用）。 */
async function rowState(page, name) {
  return page.evaluate((n) => {
    const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
    const row = card?.querySelector('[data-plugin-item]');
    const detail = card?.querySelector('.plugins-item-detail');
    const txt = (el) => el?.textContent.trim();
    return {
      rowPresent: !!row,
      rowRole: row?.getAttribute('role') ?? null,
      rowAriaExpanded: row?.getAttribute('aria-expanded') ?? null,
      rowAriaControls: row?.getAttribute('aria-controls') ?? null,
      detailId: detail?.id ?? null,
      detailHidden: detail?.hidden ?? null,
      expandedClass: card?.classList.contains('expanded') ?? null,
      // 条目行构成（多行卡式：图标 + 名称行 / 摘要行）
      chevron: !!row?.querySelector('.plugins-item-chevron'),
      icon: !!row?.querySelector('.plugins-item-icon'),
      name: txt(row?.querySelector('.plugins-card-name')),
      nameFreeFlows: (() => {
        const el = row?.querySelector('.plugins-card-name');
        if (!el) return null;
        const cs = getComputedStyle(el);
        // 补充令②：名称不截断——禁 nowrap/ellipsis，长名自然换行
        return cs.whiteSpace !== 'nowrap' && cs.textOverflow !== 'ellipsis';
      })(),
      meta: txt(row?.querySelector('.plugins-card-meta')),
      summary: txt(row?.querySelector('.plugins-item-summary')),
      summaryClamp2: (() => {
        const el = row?.querySelector('.plugins-item-summary');
        if (!el) return null;
        const cs = getComputedStyle(el);
        // 补充令③：摘要独立行最多两行 line-clamp 超出省略（Chromium 把 -webkit-box
        // 归一化为 flow-root ⇒ 判据 = line-clamp 2 + overflow hidden，行为证在 ⑥b）。
        return cs.webkitLineClamp === '2' && cs.overflow === 'hidden';
      })(),
      pillElCount: card ? card.querySelectorAll('.plugins-state-pill').length : 0,
      // 层级钉（2026-10-07 14:13 令①改版）：条目行上有**行尾开关**（新钩子
      // data-plugin-dispatch-row）；既有钩子 data-plugin-dispatch 仍钉在详情区
      rowControls: row ? row.querySelectorAll('button, [role="switch"]').length : null,
      dispatchInRow: !!row?.querySelector('[data-plugin-dispatch]'),
      dispatchRowHookInRow: !!row?.querySelector('[data-plugin-dispatch-row]'),
      dispatchInDetail: !!detail?.querySelector('[data-plugin-dispatch]'),
      dispatchInActions: !!detail?.querySelector('.plugins-item-actions [data-plugin-dispatch]'),
      docBtnHidden: detail?.querySelector('[data-plugin-doc]')?.hidden ?? null,
      docBtnPresent: !!detail?.querySelector('[data-plugin-doc]'),
    };
  }, name);
}

// ══ ① 默认态单行 ═════════════════════════════════════════════════════════

test('① 默认态单行：条目行 = chevron+名称+版本+摘要，详情默认收起，行上零控件', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  const st = await rowState(page, DOC_OK);
  expect(st.rowPresent, '条目行在（data-plugin-item 钩子）').toBe(true);
  expect(st.rowRole, '条目行 role=button').toBe('button');
  expect(st.rowAriaExpanded, '默认收起（aria-expanded=false）').toBe('false');
  expect(st.detailHidden, '详情区默认 hidden').toBe(true);
  expect(st.expandedClass, '卡片无 expanded 类').toBe(false);
  expect(st.rowAriaControls, 'aria-controls 指向详情区 id（accordion 可访问性面）').toBe(`plugins-detail-${DOC_OK}`);
  expect(st.detailId, '详情区 id 与 aria-controls 对账').toBe(`plugins-detail-${DOC_OK}`);
  // 条目行构成（2026-10-07 补充令·多行卡式）：图标完整 + 名称不截断 + 摘要两行 clamp
  expect(st.chevron, '条目行带 chevron（下行翻转语言）').toBe(true);
  expect(st.icon, '①图标完整呈现（puzzle glyph，非单字母缩写）').toBe(true);
  expect(st.name, '条目行名称').toBe(DOC_OK);
  expect(st.nameFreeFlows, '②名称独立行不截断（禁 nowrap/ellipsis，长名自然换行）').toBe(true);
  const authorTruth = await localeTruth(page, 'plugins.author', { author: 'itemize-spec' });
  expect(st.meta, '条目行 meta = v版本 · locale author 键现读（不硬编码文案）').toBe(`v1.0.0 · ${authorTruth}`);
  expect(st.summary, '一行摘要 = description 既有字段').toBe(`Fixture plugin ${DOC_OK} (itemize spec)`);
  expect(st.summaryClamp2, '③摘要独立行最多两行（-webkit-box line-clamp 2 超出省略）').toBe(true);
  expect(st.pillElCount, '默认态药丸零元素（「删掉空框」延续）').toBe(0);
  // 层级钉（2026-10-07 14:13 令①改版）：行尾开关上条目行（-row 钩子第二视图）；
  // 既有钩子仍在详情区（只动层级那批的原钉保留，钩子字面不变）
  expect(st.rowControls, '条目行恰一控件（行尾派发开关，14:13 令①）').toBe(1);
  expect(st.dispatchRowHookInRow, '收起态行尾开关在场（data-plugin-dispatch-row）').toBe(true);
  expect(st.dispatchInRow, '既有钩子 data-plugin-dispatch 不在条目行上（行上是 -row 钩子）').toBe(false);
  expect(st.dispatchInDetail, '既有钩子开关在详情区（只动层级）').toBe(true);
  expect(st.dispatchInActions, '派发开关在详情区 actions 行').toBe(true);
  // Plugin.md 入口：元素在详情区 DOM（显形语义由 ⑤ 的 200/404 两腿钉——探针在
  // 页内即时决，时序钉放那里）；折叠态下入口不可见（详情区收起即整区隐藏）。
  expect(st.docBtnPresent, '入口元素在详情区 DOM').toBe(true);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ② 展开收起（accordion 语义自洽）══════════════════════════════════════

test('② 点击展开 ⇄ 再点收回（aria/class 同步）；Enter 键盘可达；逐条目独立', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  const sel = `.plugins-card[data-plugin="${DOC_OK}"]`;
  await page.click(`${sel} [data-plugin-item]`);
  await page.waitForFunction((s) => !document.querySelector(`${s} .plugins-item-detail`)?.hidden, sel);
  let st = await rowState(page, DOC_OK);
  expect(st.rowAriaExpanded, '展开后 aria-expanded=true').toBe('true');
  expect(st.expandedClass, '展开后卡片带 expanded 类').toBe(true);
  expect(st.dispatchInActions, '展开态：开关在详情区 actions 行（可见可点）').toBe(true);

  // 逐条目独立：另一条目仍收起（互不挤占）
  const other = await rowState(page, DOC_ABSENT);
  expect(other.detailHidden, '未点击条目保持收起（accordion 逐条目自洽）').toBe(true);

  // 再点收回
  await page.click(`${sel} [data-plugin-item]`);
  await page.waitForFunction((s) => document.querySelector(`${s} .plugins-item-detail`)?.hidden, sel);
  st = await rowState(page, DOC_OK);
  expect(st.rowAriaExpanded, '收回后 aria-expanded=false').toBe('false');
  expect(st.expandedClass, '收回后 expanded 类移除').toBe(false);

  // 键盘：Enter 展开（焦点在条目行上）
  await page.focus(`${sel} [data-plugin-item]`);
  await page.keyboard.press('Enter');
  await page.waitForFunction((s) => !document.querySelector(`${s} .plugins-item-detail`)?.hidden, sel);
  st = await rowState(page, DOC_OK);
  expect(st.rowAriaExpanded, 'Enter 展开且 aria 同步').toBe('true');
  // Space 收回
  await page.keyboard.press(' ');
  await page.waitForFunction((s) => document.querySelector(`${s} .plugins-item-detail`)?.hidden, sel);
  const afterSpace = await rowState(page, DOC_OK);
  expect(afterSpace.rowAriaExpanded, 'Space 收回且 aria 同步').toBe('false');
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ③ 操作钮层级：开关语义零变（乐观翻转 → 收敛；展开态跨收敛保持）════════

test('③ 派发开关在详情区语义零变：乐观翻转→收敛；展开态跨收敛保持；节点身份不变', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  const sel = `.plugins-card[data-plugin="${DOC_OK}"]`;
  await page.click(`${sel} [data-plugin-item]`);
  await page.waitForFunction((s) => !document.querySelector(`${s} .plugins-item-detail`)?.hidden, sel);

  await page.evaluate((n) => {
    const card = document.querySelector(`.plugins-card[data-plugin="${n}"]`);
    card.__identityTag = 'card-identity';
    window.__reloadMarker = 11;
  }, DOC_OK);

  // 乐观翻转（同步读数）
  await page.click(`${sel} [data-plugin-dispatch]`);
  const optimistic = await page.evaluate((s) => {
    const dw = document.querySelector(`${s} [data-plugin-dispatch]`);
    return { aria: dw.getAttribute('aria-checked'), on: dw.classList.contains('on') };
  }, sel);
  expect(optimistic.aria, '乐观 aria-checked=false（开关语义零变）').toBe('false');
  expect(optimistic.on, '乐观 off 类').toBe(false);

  // 收敛：开关解锁且态 ≡ backend；展开态与节点身份跨收敛保持
  await page.waitForFunction((s) =>
    !document.querySelector(`${s} [data-plugin-dispatch]`)?.disabled, sel, { timeout: 8000 });
  const converged = await page.evaluate((s) => {
    const card = document.querySelector(s);
    return {
      reloadMarker: window.__reloadMarker,
      identity: card.__identityTag === 'card-identity',
      aria: card.querySelector('[data-plugin-dispatch]')?.getAttribute('aria-checked'),
      expandedStill: !card.querySelector('.plugins-item-detail')?.hidden,
      ariaExpanded: card.querySelector('[data-plugin-item]')?.getAttribute('aria-expanded'),
    };
  }, sel);
  expect(converged.reloadMarker, '零 page reload').toBe(11);
  expect(converged.identity, '卡片节点身份跨收敛保持（无重渲）').toBe(true);
  expect(converged.aria, '收敛态 ≡ backend（off）').toBe('false');
  expect(converged.expandedStill, '展开态跨收敛保持（不收起）').toBe(true);
  expect(converged.ariaExpanded, 'aria-expanded 保持 true').toBe('true');
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ④ skills 胶囊（2026-10-21 作者令⑥）：胶囊小图标 + 描述；preview 内联渲染退役 ═

test('④ skills 胶囊：详情区逐 skill 胶囊（图标+描述，data-skill-doc 钩子）；preview 零在场；多 skill 容器内滚动', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  await page.click(`.plugins-card[data-plugin="${DOC_OK}"] [data-plugin-item]`);
  const few = await page.evaluate((n) => {
    const detail = document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-item-detail`);
    const list = detail?.querySelector('.plugins-item-skills');
    const capsules = [...(list?.querySelectorAll('.plugins-skill-capsule') || [])].map(el => ({
      plugin: el.getAttribute('data-skill-doc'),
      skillId: el.getAttribute('data-skill-id'),
      hasIcon: !!el.querySelector('.st-ic'),
      desc: el.querySelector('.plugins-skill-capsule-desc')?.textContent.trim(),
    }));
    const cs = list ? getComputedStyle(list) : null;
    return {
      capsules,
      listPresent: !!list,
      overflow: cs?.overflowY,
      maxH: cs?.maxHeight,
      // 退役形态判据：preview 块与旧条目族零在场
      previewCount: detail?.querySelectorAll('.plugins-skill-preview').length ?? null,
      legacyItemCount: detail?.querySelectorAll('.plugins-skill-item').length ?? null,
    };
  }, DOC_OK);
  expect(few.listPresent, '详情区带 skills 清单容器').toBe(true);
  expect(few.capsules.map(c => c.skillId), '逐 skill 胶囊（既有投影 id）').toEqual([`${DOC_OK}/alpha`, `${DOC_OK}/beta`]);
  expect(few.capsules.map(c => c.plugin), '胶囊携带插件名（SKILL.md 深链路由输入）').toEqual([DOC_OK, DOC_OK]);
  expect(few.capsules.every(c => c.hasIcon), '每胶囊带小图标（st-ic）').toBe(true);
  expect(few.capsules.map(c => c.desc), '胶囊文本 = skill 描述（既有投影）').toEqual(['Alpha skill', 'Beta skill']);
  expect(few.previewCount, 'preview 内联渲染退役（零在场）').toBe(0);
  expect(few.legacyItemCount, '旧 .plugins-skill-item 条目族退役（零在场）').toBe(0);

  // skill 多包：清单容器 max-height + overflow-y:auto（详情区内滚动）
  await page.click('.plugins-card[data-plugin="many-skills"] [data-plugin-item]');
  const many = await page.evaluate((n) => {
    const list = document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-item-skills`);
    const capsules = [...(list?.querySelectorAll('.plugins-skill-capsule') || [])];
    const cs = list ? getComputedStyle(list) : null;
    return {
      count: capsules.length,
      maxH: parseFloat(cs?.maxHeight || '0'),
      overflowY: cs?.overflowY,
      scrollable: list ? list.scrollHeight > list.clientHeight || parseFloat(cs.maxHeight) < list.scrollHeight : false,
    };
  }, 'many-skills');
  expect(many.count, 'skill 多包全胶囊在清单').toBe(12);
  expect(many.maxH, '清单容器 max-height 上限（内滚动语义）').toBeGreaterThan(0);
  expect(many.overflowY, '清单容器 overflow-y auto').toBe('auto');
  expect(many.scrollable, 'skill 多包实际可滚动（scrollHeight > clientHeight 或受 max-height 约束）').toBe(true);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑤ Plugin.md 入口：在场判据 + Canvas 既有通路 + 探针缓存 ═══════════════

test('⑤ Plugin.md 入口：200 包显形、404 包永缺席；点击经 workspace-open-item 开 Canvas markdown tab；探针一次/包', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  // 探针显形（200 包）
  await page.waitForFunction((n) =>
    document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-doc]`)?.hidden === false, DOC_OK, { timeout: 8000 });
  const docText = await page.evaluate((n) =>
    document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-doc]`)?.textContent.trim(), DOC_OK);
  expect(docText, '入口标注 = 文件名本身（Plugin.md，数据投影非 locale 串）').toBe('Plugin.md');

  // 404 包：入口永不在场（探针已发、判 null）
  await expect.poll(() => docProbes.filter(n => n === DOC_ABSENT).length, { timeout: 8000 })
    .toBeGreaterThan(0);
  const absentBtn = await page.evaluate((n) =>
    document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-doc]`)?.hidden, DOC_ABSENT);
  expect(absentBtn, '404 包入口保持 hidden（永不在场）').toBe(true);

  // 探针缓存：整页重渲不重发（Node 侧 docProbes 计数不变）
  const probesBefore = docProbes.length;
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await page.waitForSelector(`#plugins-content .plugins-card[data-plugin="${DOC_OK}"]`, { timeout: 8000 });
  await page.waitForTimeout(300);
  expect(docProbes.length, '探针一次/包/会话（重渲零重发）').toBe(probesBefore);
  // 重渲后入口仍显形（缓存复用）
  await page.waitForFunction((n) =>
    document.querySelector(`.plugins-card[data-plugin="${n}"] [data-plugin-doc]`)?.hidden === false, DOC_OK, { timeout: 8000 });

  // 点击 → 既有 Canvas 通路（workspace-open-item → markdown viewer tab）。
  // 入口在详情区内 ⇒ 先展开条目行再点入口。
  await page.click(`.plugins-card[data-plugin="${DOC_OK}"] [data-plugin-item]`);
  await page.waitForFunction((n) =>
    !document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-item-detail`)?.hidden, DOC_OK, { timeout: 8000 });
  await page.click(`.plugins-card[data-plugin="${DOC_OK}"] [data-plugin-doc]`);
  // canvas-stack v5 后桌面标签行恒 display:none（split.css body:not(.mobile-layer)）：
  // 激活形态 = pane active；标签 dataset 读数不受隐藏影响（模型仍在）。
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="plugin-md:doc-ok"].active', { timeout: 8000 });
  const canvasTab = await page.evaluate(() => {
    const tab = document.querySelector('#canvas-tab-bar .canvas-tab[data-tab-id="plugin-md:doc-ok"]');
    return { present: !!tab, active: tab?.classList.contains('active') ?? false, title: tab?.querySelector('.canvas-tab-label')?.textContent.trim(),
      barHidden: getComputedStyle(document.getElementById('canvas-tab-bar')).display === 'none' };
  });
  expect(canvasTab.present, 'Canvas tab 模型在位（id = plugin-md:<name>）').toBe(true);
  expect(canvasTab.active, '新 tab 即激活').toBe(true);
  expect(canvasTab.title, 'tab 标题 = 文件名').toBe('Plugin.md');
  expect(canvasTab.barHidden, '桌面标签行退役防复活（display:none）').toBe(true);
  // markdown 内容进 pane（viewer 渲染面）——headings 由 renderMarkdownWithMath 产出
  const paneHtml = await page.evaluate(() => document.body.innerHTML.includes('Entry-presentation fixture'));
  expect(paneHtml, 'Plugin.md 正文进 Canvas 渲染面（内容呈现归既有通路，面板不承载）').toBe(true);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑥ 组件面 org.nebflow 现读投影 ═════════════════════════════════════════

test('⑥ 组件面：org.nebflow viewer 名数据投影（有贡献才渲染）；mcp/tools chips 照旧', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  await page.click('.plugins-card[data-plugin="many-skills"] [data-plugin-item]');
  const comps = await page.evaluate((n) => {
    const detail = document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-item-detail`);
    return [...(detail?.querySelectorAll('.plugins-comp') || [])].map(el => el.textContent.trim());
  }, 'many-skills');
  const viewerChip = comps.find(c => c.startsWith('org.nebflow:'));
  expect(viewerChip, 'org.nebflow chip = 命名空间字面量 + viewers[].name 数据投影').toBe('org.nebflow: bar-chart、map-view');
  const skillsChipTruth = await localeTruth(page, 'plugins.compositionSkills', { n: 12 });
  expect(comps, 'skills 计数 chip = locale 键现读（组件面）').toContain(skillsChipTruth);

  const none = await page.evaluate((n) => {
    const detail = document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-item-detail`);
    return [...(detail?.querySelectorAll('.plugins-comp') || [])].map(el => el.textContent.trim());
  }, DOC_ABSENT);
  expect(none.some(c => c.startsWith('org.nebflow:')), '无 viewerContributions ⇒ org.nebflow chip 不渲染').toBe(false);
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑥b 长名/长摘要压测（补充令取证：10 包样例）══════════════════════════

test('⑥b 长名/长摘要压测（10 包样例）：名称不截断自然换行；摘要两行 clamp 超出省略', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  // 压测语料 = 真包集现读（seed 12 包 plugin.json 的名称/描述，非自造文案）。
  const { readdirSync, readFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const seedDir = join(HERE, '..', 'src', 'main', 'resources', 'seed', 'plugins');
  const corpus = readdirSync(seedDir).map((d) => {
    try {
      const j = JSON.parse(readFileSync(join(seedDir, d, 'plugin.json'), 'utf8'));
      return { name: String(j.name || d), desc: String(j.description || '') };
    } catch { return null; }
  }).filter(Boolean);
  const longestName = corpus.map(c => c.name).sort((a, b) => b.length - a.length)[0];
  const longestDesc = corpus.map(c => c.desc).sort((a, b) => b.length - a.length)[0];
  expect(longestName.length, '语料自检：最长名称在案').toBeGreaterThan(0);
  expect(longestDesc.length, '语料自检：最长摘要在案').toBeGreaterThan(0);

  // 10 个压测包：名称 = 最长名称 + 序号尾（保持最长形态）；摘要 = 最长描述＋续尾
  //（确保两行以上触发 clamp）。既有夹具保留（loadShell 以 DOC_OK 卡为锚）。
  const stress = Array.from({ length: 10 }, (_, i) => manifest(`stress-${i}-${longestName}`, {
    description: `${longestDesc}（压测样例 ${i + 1}/10——摘要超出两行断言：此续尾必须被 line-clamp 收起）`,
  }));
  registry.plugins = [...registry.plugins, ...stress];

  await loadShell(page);
  await page.waitForSelector('#plugins-content .plugins-card[data-plugin^="stress-"]', { timeout: 10000 });
  const rows = await page.evaluate(() =>
    [...document.querySelectorAll('#plugins-content .plugins-card[data-plugin^="stress-"]')].map((card) => {
      const nameEl = card.querySelector('.plugins-card-name');
      const sumEl = card.querySelector('.plugins-item-summary');
      const cs = (el) => (el ? getComputedStyle(el) : null);
      const ncs = cs(nameEl), scs = cs(sumEl);
      return {
        name: nameEl?.textContent.trim(),
        nameNotTruncated: !!ncs && ncs.whiteSpace !== 'nowrap' && ncs.textOverflow !== 'ellipsis',
        summaryClamp2: !!scs && scs.webkitLineClamp === '2' && scs.overflow === 'hidden',
        // clamp 生效证：内容实际高度超过可视高度（两行收起，超出部分裁切）
        summaryClampedOverflow: !!sumEl && sumEl.scrollHeight > sumEl.clientHeight,
      };
    }));
  expect(rows.length, '压测 10 包全部渲染为条目行').toBe(10);
  for (const [i, r] of rows.entries()) {
    expect(r.nameNotTruncated, `压测#${i} 名称不截断（无 nowrap/ellipsis）`).toBe(true);
    expect(r.summaryClamp2, `压测#${i} 摘要两行 clamp（-webkit-box + line-clamp 2）`).toBe(true);
    expect(r.summaryClampedOverflow, `压测#${i} clamp 生效（scrollHeight > clientHeight = 超两行内容被收起）`).toBe(true);
  }
  expect(errors, 'pageerror 0').toEqual([]);
});

// ══ ⑦ 整页重渲回落默认收起 + rejected 信息行 ══════════════════════════════

test('⑦ 整页重渲 ⇒ 展开态回落默认收起（无记忆不新造持久化）；rejected 行 = 非展开信息行', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await loadShell(page);

  await page.click(`.plugins-card[data-plugin="${DOC_OK}"] [data-plugin-item]`);
  await page.waitForFunction((n) => !document.querySelector(`.plugins-card[data-plugin="${n}"] .plugins-item-detail`)?.hidden, DOC_OK);
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.renderPlugins(); });
  await page.waitForSelector(`#plugins-content .plugins-card[data-plugin="${DOC_OK}"]`, { timeout: 8000 });
  const after = await rowState(page, DOC_OK);
  expect(after.detailHidden, '整页重渲后详情默认收起（现机制无记忆 ⇒ 不新造持久化）').toBe(true);
  expect(after.rowAriaExpanded, '重渲后 aria-expanded=false').toBe('false');

  const rejected = await page.evaluate(() => {
    const card = document.querySelector('.plugins-card[data-plugin="broken-plugin"]');
    return {
      row: !!card?.querySelector('.plugins-card-head'),
      expandable: !!card?.querySelector('[data-plugin-item]'),
      detail: !!card?.querySelector('.plugins-item-detail'),
      summary: card?.querySelector('.plugins-item-summary')?.textContent.trim(),
      pill: card?.querySelector('.plugins-rejected-pill')?.textContent.trim(),
      controls: card?.querySelectorAll('button, [role="switch"]').length,
    };
  });
  expect(rejected.row, 'rejected 行渲染').toBe(true);
  expect(rejected.expandable, 'rejected 无展开钩子（信息行非 accordion）').toBe(false);
  expect(rejected.detail, 'rejected 无详情区').toBe(false);
  expect(rejected.summary, '拒载原因 = 条目行摘要（既有字段）').toBe('missing plugin.json manifest');
  expect(rejected.controls, 'rejected 零控件').toBe(0);
  expect(errors, 'pageerror 0').toEqual([]);
});
