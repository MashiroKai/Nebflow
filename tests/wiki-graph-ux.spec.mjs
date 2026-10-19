// wiki-graph-ux.spec.mjs — 文档图谱三件工整化验收 spec（隔离静态服务器 + 页内
// mock，真实 UI 代码全量执行；8100+ 端口纪律，永不触碰宿主 8080）。
//
// 设计基准 = reports/20261019_203500_wiki-graph-ux-redesign__delegate-nebflow-5ccd5236.html
// （作者批准样张）。覆盖面：
//
//   §2 行为规则 R1-R10（侧边栏自动开）：
//     R1 打开图谱 tab → Side Bar 自动展开并激活 files 面板；走既有持久化
//        API（sidebar_collapsed=false + sidebar_active_panel=files），零新键；
//     R2 切走再切回 → 再次自动展开；R3 Canvas 折叠后重开（state 4）→ 同样自动展开；
//     R4 刷新 restore 恢复的活动 tab 是图谱 → 首次渲染后自动展开；
//     R5 图谱可见期间手动收起 → 本查看会话内不再自动弹开（含 Refresh 重渲染后仍安静）；
//     R6 切走即会话结束（dismissed 清除，R2 复验）；R9 离开绝不自动收起；
//     R8 移动端 ≤768px 不自动开（与 Canvas 抽屉互斥，谁后开谁赢）；
//     R10 单面板下 ensure 幂等（files 面板保持激活、无副作用）。
//   §3 侧边栏排版：计数 meta 行拆分 / 节标题+右挂总计 / 项目行 12.5-500 h26 /
//      文档行 12-400 h24 缩进 26px / 附件 SVG 图标（⎘ 文本前缀退役）/
//      0 计数降档 / chips h20+计数右挂 / 图例两列 SVG 网格 / hover .06 /
//      :focus-visible 蓝晶环 / 筛选空态（含 Clear 链接复通）。
//   §4 控件映射：Filter documents → 条内无边框输入 + 前置放大镜（条 focus-within
//      蓝晶环）/ Refresh → button.glass-control 次操作档 h28 / 三个 facet →
//      自绘下拉（APG 键盘 ↑↓/Enter/Esc/Home/End；原生 select 仍是状态所有者，
//      侧栏 chips/Clear 的 change 事件镜像回脸面）/ 降级路径 hover:none →
//      原生 select 换肤（appearance:none + data-URI chevron，零自绘脸）/
//      缩放钮 → 1.8 线重 SVG、30px r9。
//   R0 全流程 console 零错误（每个用例尾部断言）。
//
// 截图：WIKI_SHOTS_DIR 存在时产出深亮双主题 + 下拉展开 + 筛选空态四张
//（验收 ③ 的 evidence 目录由运行方以该变量指定）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8193; // fixed, 8100+ — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };
const SHOT_DIR = process.env.WIKI_SHOTS_DIR || '';

const WIKI_GRAPH = {
  projects: [
    { name: 'alpha', vault: '/w/alpha', docCount: 2 },
    { name: 'beta', vault: '/w/beta', docCount: 0, stub: true },
    { name: 'gamma', vault: '/w/gamma', docCount: 1 },
  ],
  nodes: [
    { id: 'alpha:docs/a.md', project: 'alpha', rel: 'docs/a.md', title: 'Alpha One', kind: 'doc', type: 'spec', status: 'solved' },
    { id: 'alpha:docs/fig.svg', project: 'alpha', rel: 'docs/fig.svg', title: 'Alpha Fig', kind: 'attachment' },
    { id: 'gamma:docs/g.md', project: 'gamma', rel: 'docs/g.md', title: 'Gamma Note', kind: 'doc', type: 'spec' },
  ],
  edges: [{ from: 'alpha:docs/a.md', to: 'gamma:docs/g.md' }],
};

function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
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


/** Shared page boot. Playwright route matching is LIFO — the catch-all
 *  registers FIRST, the specific wiki-graph route AFTER it.
 *  @param {import('@playwright/test').Page} page
 *  @param {{ collapsed?: boolean, canvasTabs?: object, hoverNone?: boolean }} preset */
async function boot(page, preset = {}) {
  await page.setViewportSize(preset.viewport || { width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(({ collapsed, canvasTabs, hoverNone }) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    // 静态服务无 gateway 注入 → brand.lowerName 走 brand.js 的 'stand' 兜底；
    // 预写 'stand_' 应用键 + 'nebflow_'（pre-paint 脚本的 legacy 读口）。
    if (collapsed) {
      localStorage.setItem('stand_sidebar_collapsed', 'true');
      localStorage.setItem('nebflow_sidebar_collapsed', 'true');
    }
    if (canvasTabs) {
      localStorage.setItem('stand_canvas_tabs', JSON.stringify(canvasTabs));
      localStorage.setItem('nebflow_canvas_tabs', JSON.stringify(canvasTabs));
    }
    localStorage.setItem('stand_locale', 'en');
    if (hoverNone) {
      // Scoped matchMedia patch: ONLY '(hover: none)' reports true — the
      // degrade-path trigger. Everything else delegates to the real matcher
      // (theme / mobile queries must stay truthful).
      const orig = window.matchMedia.bind(window);
      window.matchMedia = (q) => (q === '(hover: none)'
        ? { matches: true, media: q, onchange: null,
            addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, dispatchEvent() { return false; } }
        : orig(q));
    }
  }, { collapsed: !!preset.collapsed, canvasTabs: preset.canvasTabs || null, hoverNone: !!preset.hoverNone });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/graph', r => r.fulfill({ json: WIKI_GRAPH }));
  // 可选人格档案（app 静默容缺）——静态服务器没有该文件，mock 掉避免 404 噪声进 console 断言。
  await page.route('**/agents/**', r => r.fulfill({ json: {} }));
  if (preset.canvasTabsApi) await page.route('**/api/canvas-tabs', r => r.fulfill({ json: preset.canvasTabsApi }));
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: { loggedIn: false, device: null, peers: [] },
  }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
      if (m.type === 'autostartSet') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: m.enabled, supported: true }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });

  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  await page.goto(base);
  return errors;
}

/** Open the wiki graph tab through the activity-bar button (state 1 entry)
 *  and wait until the graph file list is hosted in the Side Bar panel space. */
async function openGraph(page) {
  await page.click('#wiki-btn');
  await page.waitForSelector('#wiki-graph-explorer-section', { state: 'attached', timeout: 10000 });
  await page.waitForSelector('#wiki-graph-explorer-section .wx-proj', { state: 'attached', timeout: 10000 })
    .catch(async (e) => { throw new Error('R4 state: ' + JSON.stringify(await page.evaluate(() => ({
      panes: [...document.querySelectorAll('.canvas-tab-pane')].map(p => p.dataset.tabId),
      active: document.querySelector('.canvas-tab-pane.active')?.dataset.tabId || null,
      canvasOpen: document.body.classList.contains('canvas-open'),
    }))) + ' | ' + e.message); });
}

const bodyNotCollapsed = (page) => page.evaluate(() => !document.body.classList.contains('sidebar-collapsed'));
const bodyCollapsed = (page) => page.evaluate(() => document.body.classList.contains('sidebar-collapsed'));

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

// ── §2 行为规则 R1-R10 ────────────────────────────────────────────────

test('R1 opens graph tab → Side Bar auto-expands on files via the persisted API, zero new keys', async ({ page }) => {
  const errors = await boot(page, { collapsed: true });
  await page.waitForFunction(() => document.body.classList.contains('sidebar-collapsed'));
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  // files 面板激活 + R7 既有持久化值（与手动展开同一条 API 写入）。
  await expect(page.locator('#panel-sessions')).toHaveClass(/active/);
  const lsGet = (k) => page.evaluate((key) => localStorage.getItem(key), k);
  expect(await lsGet('stand_sidebar_collapsed')).toBe('false');
  expect(await lsGet('stand_sidebar_active_panel')).toBe('files');
  // R7 零新持久化键：localStorage 里不出现任何 wiki 命名空间的新键。
  const wikiKeys = await page.evaluate(() => Object.keys(localStorage).filter(k => /wiki/i.test(k)));
  expect(wikiKeys).toEqual([]);
  expect(errors).toEqual([]);
});

test('R5 manual collapse while graph visible → quiet for the session (Refresh re-render stays quiet)', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  // 手动收起（头部开关 — setSideBarCollapsed 的真实入口之一）。
  await page.click('#sidebar-toggle');
  await expect.poll(() => bodyCollapsed(page)).toBe(true);
  // Refresh → loadData + rebuildSim + 列表重渲染 —— 被收起的 Side Bar 必须保持安静。
  await page.click('.wiki-toolbar-btn');
  await page.waitForTimeout(400);
  expect(await bodyCollapsed(page)).toBe(true);
  expect(await page.evaluate(() => !!document.getElementById('wiki-graph-explorer-section'))).toBe(true);
  expect(errors).toEqual([]);
});

test('R6+R9 leaving ends the session WITHOUT collapsing; returning re-expands (R2)', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await page.click('#sidebar-toggle');           // R5: dismissed = true
  await expect.poll(() => bodyCollapsed(page)).toBe(true);
  await page.click('#projects-btn');             // 切走（state 1 开 projects tab）
  await page.waitForTimeout(400);
  // R9: 离开图谱绝不自动收起 —— 收起状态原样保留。
  expect(await bodyCollapsed(page)).toBe(true);
  // R6: 会话已结束（dismissed 清除）；R2: 切回图谱 → 再次自动展开。
  await page.click('#wiki-btn');
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  await expect(page.locator('#panel-sessions')).toHaveClass(/active/);
  expect(errors).toEqual([]);
});

test('R3 Canvas reopen (state 4) re-triggers the auto-expand', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await page.click('#canvas-toggle-btn');        // 折叠 Canvas（tab 保留）
  await expect.poll(() => page.evaluate(() => !document.body.classList.contains('canvas-open'))).toBe(true);
  await page.click('#sidebar-toggle');           // 收起（此时图谱不可见 ⇒ 不产生 dismissed）
  await expect.poll(() => bodyCollapsed(page)).toBe(true);
  await page.click('#wiki-btn');                 // state 4: openCanvas + setActiveTab
  await expect.poll(() => page.evaluate(() => document.body.classList.contains('canvas-open'))).toBe(true);
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  expect(errors).toEqual([]);
});

test('R4 reload restore with graph as the active tab auto-expands after first render', async ({ page }) => {
  // F1: 服务端存档是权威介质 — restore 的服务端腿（异步）走 reconcileFromServer →
  // openPanelTab → canvas-tab-restore。设计基准 §2 R4 即此路径（必须在 goto 前
  // 注册路由，boot 阶段的 fetch 才吃得到）。
  const errors = await boot(page, {
    collapsed: true,
    canvasTabsApi: { v: 2, tabs: [{ id: 'wiki-graph', type: 'wiki-graph', title: 'Wiki Graph', pinned: true }], activeTabId: 'wiki-graph' },
  });
  // restoreTabs → canvas-tab-restore → renderInto → ensure（服务端存档 mock 返回
  // 空 {}，parseTabsData 判非法 → 本地存档生效）。
  // 终态轮询（并行跑时中间态会抖）：Canvas 开 + 图谱 pane 激活 + 列表已挂载。
  await expect.poll(() => page.evaluate(() => ({
    open: document.body.classList.contains('canvas-open'),
    active: document.querySelector('.canvas-tab-pane.active')?.dataset.tabId || null,
    rows: !!document.querySelector('#wiki-graph-explorer-section .wx-proj'),
  })), { timeout: 15000 }).toEqual({ open: true, active: 'wiki-graph', rows: true });
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  const lsGet2 = (k) => page.evaluate((key) => localStorage.getItem(key), k);
  expect(await lsGet2('stand_sidebar_active_panel')).toBe('files');
  expect(errors).toEqual([]);
});

test('R8 mobile viewport never auto-opens (drawer exclusivity wins)', async ({ page }) => {
  const errors = await boot(page, { viewport: { width: 375, height: 812 } });
  // ≤768 移动层会抑制一切启动/程序化画板展开（watchBootAutoOpen）。真实用户
  // 路径（v3 批更新）：顶栏右圆钮液态下拉列的 graph face（onKeyClick：
  // touchLayer + navigateTo('graph')；r4 底部 bar 已随 v3③ 退役）。
  // 程序化 click 同样触发绑定的 click handler，且 touchLayer 同步抬除抑制。
  await page.waitForSelector('.mh-dd[data-dd="right"] [data-face="graph"]', { state: 'attached', timeout: 10000 }); // 移动层异步装配
  await page.evaluate(() => document.querySelector('.mh-dd[data-dd="right"] [data-face="graph"]').click());
  // 前提成立：图谱 pane 已是激活内容（移动层接管了桌面侧栏槽位，explorer
  // 槽位不再承载图谱列表 —— R8 的被测面是 Side Bar 不自动开）。
  await expect.poll(() => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.dataset.tabId), { timeout: 10000 }).toBe('wiki-graph');
  await page.waitForTimeout(600);               // 给任何（不应发生的）自动展开留窗口
  // R8: Side Bar 抽屉保持收起 —— 自动开被拦下；files 面板不激活。
  expect(await bodyCollapsed(page)).toBe(true);
  expect(await page.evaluate(() => !document.getElementById('panel-sessions').classList.contains('active'))).toBe(true);
  expect(errors).toEqual([]);
});

test('R10 ensure is idempotent with the files panel already active', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await expect(page.locator('#panel-sessions')).toHaveClass(/active/);
  // 再走一次 tab 离开/回归（ensure 幂等路径）——面板保持 files，无翻转。
  await page.click('#projects-btn');
  await page.waitForTimeout(250);
  await page.click('#wiki-btn');
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await expect(page.locator('#panel-sessions')).toHaveClass(/active/);
  expect(await page.evaluate(() => localStorage.getItem('stand_sidebar_active_panel'))).toBe('files');
  expect(errors).toEqual([]);
});

// ── §3 侧边栏排版 ─────────────────────────────────────────────────────

test('§3 sidebar typography: meta row, label+total, row metrics, zero demotion, chips, legend, hover', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  // 计数 meta 行（计数移出节标题）：3 项目 · 3 文档 · 1 链接。
  await expect(page.locator('.wx-sb-meta')).toHaveText('3 projects · 3 docs · 1 links');
  // 节标题只留名 + 右挂总计。
  await expect(page.locator('.wx-sb-proj-title span').first()).toHaveText('Projects');
  await expect(page.locator('.wx-sb-proj-title .wx-sb-tot')).toHaveText('3');
  // 项目组头行：h26 / 12.5px / 500。
  const proj = page.locator('.wx-proj').first();
  await expect(proj).toHaveAttribute('tabindex', '0');
  expect(await proj.evaluate(el => getComputedStyle(el).height)).toBe('26px');
  expect(await proj.locator('.wx-proj-name').evaluate(el => getComputedStyle(el).fontSize)).toBe('12.5px');
  expect(await proj.locator('.wx-proj-name').evaluate(el => getComputedStyle(el).fontWeight)).toBe('500');
  // 0 计数降档 + (remote) 后缀弱化。
  const betaRow = page.locator('.wx-proj', { hasText: 'beta' });
  await expect(betaRow.locator('.wx-count')).toHaveText('0');
  expect(await betaRow.locator('.wx-count').evaluate(el => el.classList.contains('zero'))).toBe(true);
  await expect(betaRow.locator('.wx-proj-remote')).toHaveText(' (remote)');
  // 文档行：h24 / 缩进 26px / 12px 400。
  const doc = page.locator('.wx-doc', { hasText: 'Alpha One' });
  expect(await doc.evaluate(el => getComputedStyle(el).height)).toBe('24px');
  expect(await doc.evaluate(el => getComputedStyle(el).paddingLeft)).toBe('26px');
  expect(await doc.locator('.wx-doc-name').evaluate(el => getComputedStyle(el).fontSize)).toBe('12px');
  expect(await doc.locator('.wx-doc-name').evaluate(el => getComputedStyle(el).fontWeight)).toBe('400');
  // 附件行：SVG 图标进缩进槽；「⎘ 」文本前缀退役。
  const att = page.locator('.wx-doc', { hasText: 'Alpha Fig' });
  await expect(att.locator('svg.wx-doc-icon')).toHaveCount(1);
  await expect(att.locator('.wx-doc-name')).toHaveText('Alpha Fig');
  // chips：真 button + 计数右挂 .n。
  const chip = page.locator('.wx-chip', { hasText: 'spec' });
  expect(await chip.evaluate(el => el.tagName)).toBe('BUTTON');
  await expect(chip.locator('.wx-chip-n')).toHaveText('2');
  expect(await chip.evaluate(el => getComputedStyle(el).height)).toBe('20px');
  // 图例：两列 grid + 4 枚 SVG 形状（字符符号退役）。
  const legend = page.locator('.wx-sb-legend');
  expect(await legend.evaluate(el => getComputedStyle(el).display)).toBe('grid');
  expect((await legend.evaluate(el => getComputedStyle(el).gridTemplateColumns)).trim().split(/\s+/).length).toBe(2);
  await expect(legend.locator('.wx-lg-item svg')).toHaveCount(4);
  await expect(legend.locator('.wx-lg-docs')).toHaveText('Document');
  // hover 降到 .06（行多时不燥）；active 保持 tint-15（点击后）。
  await proj.hover();
  expect(await proj.evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgba(91, 127, 191, 0.06)');
  await proj.click();
  expect(await proj.evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgba(91, 127, 191, 0.15)');
  // :focus-visible 蓝晶环（先按一次 Tab 建立键盘输入模态，脚本聚焦才命中）。
  await page.keyboard.press('Tab');
  await doc.focus();
  expect(await doc.evaluate(el => getComputedStyle(el).boxShadow)).toContain('91, 127, 191');
  // 列表随筛选镜像：筛选框输入 ⇒ 不匹配的行隐藏（上一次 click 已把 active
  // 翻回 off，正好回到无筛选基线）。
  await page.fill('.wiki-toolbar-search', 'alpha');
  await page.waitForTimeout(120);
  expect(await page.locator('.wx-doc', { hasText: 'Gamma Note' }).evaluate(el => el.style.display)).toBe('none');
  expect(await page.locator('.wx-doc', { hasText: 'Alpha One' }).evaluate(el => el.style.display)).toBe('');
  expect(errors).toEqual([]);
});

test('§3 filter empty state shows on no match; the Clear link restores', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await page.fill('.wiki-toolbar-search', 'zzz');
  await expect(page.locator('.wx-sb-empty')).toHaveClass(/show/);
  await expect(page.locator('.wx-sb-empty-big')).toHaveText('No matching documents');
  expect(await page.locator('.wx-proj').first().evaluate(el => el.style.display)).toBe('none');
  // 空态里的 Clear 链接复通：清搜索 + facets + 行隐藏。
  await page.click('.wx-sb-empty a');
  await expect(page.locator('.wx-sb-empty')).not.toHaveClass(/show/);
  expect(await page.locator('.wx-proj').first().evaluate(el => el.style.display)).toBe('');
  expect(await page.evaluate(() => document.querySelector('.wiki-toolbar-search').value)).toBe('');
  expect(errors).toEqual([]);
});

// ── §4 控件映射 ───────────────────────────────────────────────────────

test('§4 Filter documents → borderless findbar input + bar focus-within ring; Refresh secondary tier; zoom SVG buttons', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  // 前置放大镜 + 无边框透明输入 h28。
  await expect(page.locator('.wiki-search > svg')).toHaveCount(1);
  const input = page.locator('.wiki-toolbar-search');
  expect(await input.evaluate(el => getComputedStyle(el).borderStyle)).toBe('none');
  expect(await input.evaluate(el => getComputedStyle(el).borderWidth)).toBe('0px');
  expect(await input.evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgba(0, 0, 0, 0)');
  expect(await input.evaluate(el => getComputedStyle(el).height)).toBe('28px');
  // 条 :focus-within → 蓝晶描边环（--glass-etched-border-focus）。
  const bar = page.locator('.wiki-toolbar');
  await input.click();
  expect(await bar.evaluate(el => getComputedStyle(el).borderColor)).toContain('91, 127, 191');
  await page.evaluate(() => document.activeElement?.blur());
  // Refresh = button.glass-control 次操作档 · h28 · 带自旋图标。
  const refresh = page.locator('.wiki-toolbar-btn');
  expect(await refresh.evaluate(el => el.classList.contains('glass-control'))).toBe(true);
  expect(await refresh.evaluate(el => getComputedStyle(el).height)).toBe('28px');
  await expect(refresh.locator('svg')).toHaveCount(1);
  // 缩放钮：3 枚全 SVG（文本字形退役）、30×30。
  const zbtns = page.locator('.wx-zoom button');
  await expect(zbtns).toHaveCount(3);
  for (let i = 0; i < 3; i++) {
    const b = zbtns.nth(i);
    await expect(b.locator('svg')).toHaveCount(1);
    const box = await b.boundingBox();
    expect(Math.round(box.width)).toBe(30);
    expect(Math.round(box.height)).toBe(30);
  }
  expect((await zbtns.nth(0).textContent()).trim()).toBe('');
  expect(errors).toEqual([]);
});

test('§4 self-drawn facet dropdown: APG keyboard face, select stays the state owner, chips mirror back', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  const dds = page.locator('.wiki-facets .wiki-dd');
  await expect(dds).toHaveCount(3);
  const kind = dds.nth(0);
  const face = kind.locator('.wiki-dd-face');
  // 脸面 ARIA + 原生 select 仍为状态所有者（被视觉遮蔽）。
  await expect(face).toHaveAttribute('aria-haspopup', 'listbox');
  await expect(face).toHaveAttribute('aria-expanded', 'false');
  const kindSel = page.locator('select.wiki-facet-kind');
  expect(await kindSel.evaluate(el => el.classList.contains('wiki-facet-veiled'))).toBe(true);
  expect(await kindSel.evaluate(el => Math.round(el.getBoundingClientRect().width))).toBeLessThanOrEqual(1);
  // 点击展开 → listbox/option/aria-selected。
  await face.click();
  await expect(face).toHaveAttribute('aria-expanded', 'true');
  const pop = kind.locator('.wiki-dd-pop');
  await expect(pop).toBeVisible();
  await expect(pop).toHaveAttribute('role', 'listbox');
  const firstOpt = pop.locator('.wiki-dd-opt').first();
  await expect(firstOpt).toHaveAttribute('role', 'option');
  await expect(firstOpt).toHaveAttribute('aria-selected', 'true');
  // 键盘：↓ 展开（hl=当前选中）→ ↓ 移动 → Enter 选中（写回 select + change）。
  await page.keyboard.press('Escape');           // 先关上（click 已展开）
  await expect(face).toHaveAttribute('aria-expanded', 'false');
  await face.focus();
  await page.keyboard.press('ArrowDown');        // 关着时 ↓ = 展开（hl=选中项）
  await page.keyboard.press('ArrowDown');        // 移动到 Documents
  const hl = pop.locator('.wiki-dd-opt.hl');
  await expect(hl).toHaveCount(1);
  const hlText = await hl.textContent();
  await page.keyboard.press('Enter');
  await expect(face).toHaveAttribute('aria-expanded', 'false');
  await expect(face.locator('.wiki-dd-label')).toHaveText(hlText.trim());
  expect(await kindSel.evaluate(el => el.value)).toBe('doc');
  // Esc 关闭。
  await face.click();
  await page.keyboard.press('Escape');
  await expect(face).toHaveAttribute('aria-expanded', 'false');
  await expect(pop).not.toBeVisible();
  // Home/End：End → 末项高亮 → Enter 选中。
  await face.focus();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('End');
  await page.keyboard.press('Enter');
  await expect(face.locator('.wiki-dd-label')).toHaveText('Attachments');
  expect(await kindSel.evaluate(el => el.value)).toBe('attachment');
  // 外部 change 事件镜像回脸面：侧栏 chip 点击 → type select 写值 → 脸面跟随。
  await page.locator('.wx-chip', { hasText: 'spec' }).click();
  const typeFaceLabel = dds.nth(1).locator('.wiki-dd-label');
  await expect(typeFaceLabel).toHaveText('spec');
  expect(await page.locator('select.wiki-facet-type').evaluate(el => el.value)).toBe('spec');
  expect(errors).toEqual([]);
});

test('§4 native degrade path (hover:none) keeps the reskinned select, zero custom faces', async ({ page }) => {
  const errors = await boot(page, { hoverNone: true });
  await openGraph(page);
  await expect(page.locator('.wiki-facets .wiki-dd')).toHaveCount(0);
  const kindSel = page.locator('select.wiki-facet-kind');
  expect(await kindSel.evaluate(el => el.classList.contains('wiki-facet-veiled'))).toBe(false);
  await expect(kindSel).toBeVisible();
  expect(await kindSel.evaluate(el => getComputedStyle(el).appearance)).toBe('none');
  expect(await kindSel.evaluate(el => getComputedStyle(el).backgroundImage)).toContain('data:image/svg+xml');
  expect(errors).toEqual([]);
});

// ── 验收 ③ 深亮双主题截图（WIKI_SHOTS_DIR 指定 evidence 目录时产出）──────

test('dual-theme screenshots for the evidence directory', async ({ page }) => {
  test.skip(!SHOT_DIR, 'WIKI_SHOTS_DIR not set — evidence run only');
  mkdirSync(SHOT_DIR, { recursive: true });
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await page.waitForTimeout(900); // force sim settle
  await page.screenshot({ path: join(SHOT_DIR, 'dark-main.png') });
  // 下拉展开态（深）。
  await page.locator('.wiki-facets .wiki-dd').nth(0).locator('.wiki-dd-face').click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(SHOT_DIR, 'dark-dropdown-open.png') });
  await page.keyboard.press('Escape');
  // 筛选空态（深）。
  await page.fill('.wiki-toolbar-search', 'zzz');
  await page.waitForTimeout(200);
  await page.screenshot({ path: join(SHOT_DIR, 'dark-empty-filter.png') });
  await page.fill('.wiki-toolbar-search', '');
  // 亮档主视图。
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(500);
  await page.screenshot({ path: join(SHOT_DIR, 'light-main.png') });
  expect(errors).toEqual([]);
});
