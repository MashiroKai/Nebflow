// wiki-graph-ux.spec.mjs — 文档图谱「控制面迁左栏」契约 spec（隔离静态服务器 +
// 页内 mock，真实 UI 代码全量执行；8100+ 端口纪律，永不触碰宿主 8080）。
//
// 作者令 2026-10-21（图谱布局重构 · 控制面迁左栏）：canvas 只留图谱本体
// （力导向图 + 悬停 tooltip）；激活图谱 tab 时左栏切换为「图谱控制面」——
// 搜索框 / 目录 chips / 选中节点详情卡 / 断链清单（固定区块）/ 统计行；
// 回到文件浏览态左栏还原文件浏览器。本 spec 锁左栏契约：
//
//   §A 展开与还原（承 2026-10-19 批 R 系语义，面换成控制面）：
//     R1 打开图谱 tab → Side Bar 自动展开 + files 面板激活 + 控制面挂载可见 +
//        explorer 让位（隐藏）；走既有持久化 API 零新键（R7）；
//     R4 刷新 restore 恢复的活动 tab 是图谱 → 首次渲染后自动展开；
//     R5 图谱可见期间手动收起 → 安静（不自动弹开）；
//     R6+R9 切走（explorer 还原）绝不自动收起；切回再次自动展开；
//     R3 Canvas 折叠 → explorer 还原；state 4 重开 → 再展开；
//     R8 移动端 ≤768px 不自动展开（抽屉互斥）；
//   §B canvas 纯图态：pane 子元素只有 canvas + tooltip（+桌面隐藏 panebar），
//      旧控件面（topbar/预览卡/信息行/图例/断链浮层）零残留；
//   §C 左栏↔canvas 双向联动：canvas 点节点 → 左栏详情卡刷新；左栏点词条/
//      目录 chip/断链行/搜索 → canvas 过滤聚焦；Esc 逐层退出（选中→过滤）；
//   §D 控制面内容契约：统计行口径（N docs · M references · K unresolved）；
//      断链清单 = 面板内固定区块（position:static，非浮层）；详情卡空态；
//   R0 全流程 console 零错误（每个用例尾部断言）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8193; // fixed, 8100+ — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

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
    { id: 'alpha:docs/ghost.md', project: 'alpha', rel: 'docs/ghost.md', title: 'ghost', kind: 'doc', stub: true },
  ],
  edges: [
    { from: 'alpha:docs/a.md', to: 'gamma:docs/g.md' },
    { from: 'alpha:docs/a.md', to: 'alpha:docs/ghost.md' },
  ],
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
 *  @param {{ collapsed?: boolean, canvasTabsApi?: object }} preset */
async function boot(page, preset = {}) {
  await page.setViewportSize(preset.viewport || { width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(({ collapsed }) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    // 静态服务无 gateway 注入 → brand.lowerName 走 brand.js 的 'stand' 兜底；
    // 预写 'stand_' 应用键 + 'nebflow_'（pre-paint 脚本的 legacy 读口）。
    if (collapsed) {
      localStorage.setItem('stand_sidebar_collapsed', 'true');
      localStorage.setItem('nebflow_sidebar_collapsed', 'true');
    }
    localStorage.setItem('stand_locale', 'en');
  }, { collapsed: !!preset.collapsed });
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
 *  and wait until the layout settles and the control face is live. */
async function openGraph(page) {
  await page.click('#wiki-btn');
  await page.waitForSelector('.wiki-graph-pane', { state: 'attached', timeout: 10000 });
  await page.waitForFunction(() => {
    const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
    return p && p.alpha < 0.02;
  }, { timeout: 15000 });
  await page.waitForSelector('#wiki-graph-side', { state: 'attached', timeout: 10000 });
}

const bodyNotCollapsed = (page) => page.evaluate(() => !document.body.classList.contains('sidebar-collapsed'));
const bodyCollapsed = (page) => page.evaluate(() => document.body.classList.contains('sidebar-collapsed'));

/** One-shot read of the whole side-face contract surface (page context). */
const readFace = (page) => page.evaluate(() => {
  const face = document.getElementById('wiki-graph-side');
  const explorer = document.getElementById('explorer-section');
  const panel = document.getElementById('panel-sessions');
  const pane = document.querySelector('.wiki-graph-pane');
  return {
    mounted: !!face,
    visible: !!face && getComputedStyle(face).display === 'flex',
    bodyFlag: document.body.classList.contains('wg-side-active'),
    sidebarOpen: !document.body.classList.contains('sidebar-collapsed'),
    panelActive: !!panel && panel.classList.contains('active'),
    explorerHidden: !!explorer && getComputedStyle(explorer).display === 'none',
    search: !!face && !!face.querySelector('.wg-search-input'),
    chips: face ? face.querySelectorAll('.wg-chip').length : -1,
    stats: face && face.querySelector('.wg-stats') ? face.querySelector('.wg-stats').textContent : '',
    emptyShown: !!face && !!face.querySelector('.wg-detail-empty') && !face.querySelector('.wg-detail-empty').hidden,
    brokenVisible: !!face && !!face.querySelector('.wg-broken') &&
      getComputedStyle(face.querySelector('.wg-broken')).display !== 'none' &&
      !face.querySelector('.wg-broken').classList.contains('none'),
    brokenStatic: !!face && !!face.querySelector('.wg-broken') &&
      getComputedStyle(face.querySelector('.wg-broken')).position === 'static',
    brokenRows: face ? face.querySelectorAll('.wg-broken .row').length : -1,
    paneKids: pane ? [...pane.children].map(e => String(e.className)) : [],
    paneLegacy: pane ? ['.wg-topbar', '.wg-preview', '.wg-infoline', '.wg-legend'].filter(s => pane.querySelector(s)) : [],
    panebarDisplay: (() => {
      const bar = pane && pane.querySelector('.wg-panebar');
      return bar ? getComputedStyle(bar).display : null;
    })(),
  };
});

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

// ── §A 展开与还原 ────────────────────────────────────────────────────

test('R1 opens graph tab → Side Bar auto-expands, face swaps in, explorer yields, zero new keys', async ({ page }) => {
  const errors = await boot(page, { collapsed: true });
  await page.waitForFunction(() => document.body.classList.contains('sidebar-collapsed'));
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  const face = await readFace(page);
  expect(face.bodyFlag).toBe(true);
  expect(face.visible).toBe(true);
  expect(face.panelActive).toBe(true);
  expect(face.explorerHidden).toBe(true);
  // R7 零新持久化键：localStorage 里不出现任何 wiki 命名空间的新键。
  const wikiKeys = await page.evaluate(() => Object.keys(localStorage).filter(k => /wiki/i.test(k)));
  expect(wikiKeys).toEqual([]);
  expect(await page.evaluate(() => localStorage.getItem('stand_sidebar_active_panel'))).toBe('files');
  expect(errors).toEqual([]);
});

test('§B canvas purity: pane holds canvas + tooltip only; desktop panebar hidden', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  const face = await readFace(page);
  expect(face.paneKids).toEqual(['wiki-canvas', 'wg-tip', 'wg-panebar']);
  expect(face.paneLegacy).toEqual([]);
  expect(face.panebarDisplay).toBe('none');
  expect(errors).toEqual([]);
});

test('R4 reload restore with graph as the active tab auto-expands after first render', async ({ page }) => {
  // F1: 服务端存档是权威介质 — restore 的服务端腿（异步）走 reconcileFromServer →
  // openPanelTab → canvas-tab-restore（必须在 goto 前注册路由，boot 阶段的 fetch 才吃得到）。
  const errors = await boot(page, {
    collapsed: true,
    canvasTabsApi: { v: 2, tabs: [{ id: 'wiki-graph', type: 'wiki-graph', title: 'Wiki Graph', pinned: true }], activeTabId: 'wiki-graph' },
  });
  // 终态轮询（并行跑时中间态会抖）：Canvas 开 + 图谱 pane 激活 + 控制面挂载。
  await expect.poll(() => page.evaluate(() => ({
    open: document.body.classList.contains('canvas-open'),
    active: document.querySelector('.canvas-tab-pane.active')?.dataset.tabId || null,
    face: !!document.getElementById('wiki-graph-side') && !!window.__wikiGraphProbe?.(),
  })), { timeout: 15000 }).toEqual({ open: true, active: 'wiki-graph', face: true });
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  expect(await page.evaluate(() => localStorage.getItem('stand_sidebar_active_panel'))).toBe('files');
  expect(errors).toEqual([]);
});

test('R5 manual collapse while graph visible stays quiet (no auto re-open)', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  // 手动收起（头部开关 — setSideBarCollapsed 的真实入口之一）。
  await page.click('#sidebar-toggle');
  await expect.poll(() => bodyCollapsed(page)).toBe(true);
  // 图谱仍可见：任何自动展开路径都必须保持安静（含数据重渲）。
  await page.waitForTimeout(900);
  expect(await bodyCollapsed(page)).toBe(true);
  // 控制面仍挂载（收起的是栏，不是面）。
  expect(await page.evaluate(() => !!document.getElementById('wiki-graph-side'))).toBe(true);
  expect(errors).toEqual([]);
});

test('R6+R9 leaving restores the explorer WITHOUT collapsing; returning re-expands', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await page.click('#sidebar-toggle');           // R5: 图谱可见期间收起
  await expect.poll(() => bodyCollapsed(page)).toBe(true);
  await page.click('#projects-btn');             // 切走（state 1 开 projects tab）
  await page.waitForTimeout(400);
  // R9: 离开图谱绝不自动收起 —— 收起状态原样保留。
  expect(await bodyCollapsed(page)).toBe(true);
  // explorer 还原：body 标志随可见性熄灭（Canvas 折叠时同样还原）。
  await page.click('#canvas-toggle-btn');
  await expect.poll(() => page.evaluate(() => !document.body.classList.contains('canvas-open'))).toBe(true);
  await expect.poll(() => page.evaluate(() => !document.body.classList.contains('wg-side-active'))).toBe(true);
  // R6: 会话已结束；R2: 切回图谱 → 再次自动展开 + 控制面回归。
  await page.click('#wiki-btn');
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  const face = await readFace(page);
  expect(face.bodyFlag).toBe(true);
  expect(face.explorerHidden).toBe(true);
  expect(errors).toEqual([]);
});

test('R3 canvas close hands the column back; state-4 reopen re-expands', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await expect.poll(() => bodyNotCollapsed(page)).toBe(true);
  await page.click('#canvas-toggle-btn');        // 折叠 Canvas（tab 保留）
  await expect.poll(() => page.evaluate(() => !document.body.classList.contains('wg-side-active'))).toBe(true);
  // explorer 还原（body 标志熄灭 → 面内显示规则回到文件浏览器）。
  expect(await page.evaluate(() => getComputedStyle(document.getElementById('explorer-section')).display)).not.toBe('none');
  await page.click('#sidebar-toggle');           // 收起（此时图谱不可见 ⇒ 不产生新的可见跃迁）
  await expect.poll(() => bodyCollapsed(page)).toBe(true);
  await page.click('#wiki-btn');                 // state 4: openCanvas + setActiveTab
  await expect.poll(() => page.evaluate(() => document.body.classList.contains('canvas-open'))).toBe(true);
  await expect.poll(() => bodyNotCollapsed(page), { timeout: 5000 }).toBe(true);
  expect(errors).toEqual([]);
});

test('R8 mobile viewport never auto-expands; the in-pane bar rides with the pane', async ({ page }) => {
  const errors = await boot(page, { viewport: { width: 375, height: 812 } });
  // 真实用户路径：顶栏右圆钮液态下拉列的 graph face（navController navigateTo）。
  await page.waitForSelector('.mh-dd[data-dd="right"] [data-face="graph"]', { state: 'attached', timeout: 10000 });
  await page.evaluate(() => document.querySelector('.mh-dd[data-dd="right"] [data-face="graph"]').click());
  await expect.poll(() => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.dataset.tabId), { timeout: 10000 }).toBe('wiki-graph');
  await page.waitForTimeout(600);               // 给任何（不应发生的）自动展开留窗口
  // R8: Side Bar 抽屉保持收起 —— 自动开被拦下；files 面板不激活。
  expect(await bodyCollapsed(page)).toBe(true);
  expect(await page.evaluate(() => !document.getElementById('panel-sessions').classList.contains('active'))).toBe(true);
  // 移动层无左栏 → panebar 随 pane 收编（display:flex + 搜索可用）。
  const bar = await page.evaluate(() => {
    const el = document.querySelector('.wiki-graph-pane .wg-panebar');
    const input = el && el.querySelector('.wg-search-input');
    return { display: el ? getComputedStyle(el).display : null, inputH: input ? input.getBoundingClientRect().height : 0 };
  });
  expect(bar.display).toBe('flex');
  expect(bar.inputH).toBeGreaterThan(0);
  expect(errors).toEqual([]);
});

// ── §C 双向联动 + §D 内容契约 ─────────────────────────────────────────

test('§C canvas click → detail card refresh; sidebar entry click → canvas refocus', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  // canvas 点节点（探针读布局坐标 → 真实 mouse 路径）
  const p1 = await page.evaluate(() => window.__wikiGraphProbe());
  const hub = p1.nodes.find(n => n.id === 'gamma:docs/g.md');
  const box = await page.locator('.wiki-canvas').boundingBox();
  const sx = box.x + (hub.x - p1.W / 2) * p1.scale + p1.W / 2 + p1.vx;
  const sy = box.y + (hub.y - p1.H / 2) * p1.scale + p1.H / 2 + p1.vy;
  await page.mouse.click(sx, sy);
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe('gamma:docs/g.md');
  await expect(page.locator('#wiki-graph-side .wg-detail-body h2')).toHaveText('Gamma Note');
  expect(await page.locator('#wiki-graph-side .wg-detail-body .ref').count()).toBeGreaterThan(0);
  // 左栏词条点击 → canvas 换选
  await page.click('#wiki-graph-side .wg-detail-body .ref');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe('alpha:docs/a.md');
  await expect(page.locator('#wiki-graph-side .wg-detail-body h2')).toHaveText('Alpha One');
  // Esc 逐层退出：选中 → 无
  await page.keyboard.press('Escape');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe(null);
  await expect(page.locator('#wiki-graph-side .wg-detail-empty')).toBeVisible();
  expect(errors).toEqual([]);
});

test('§C chips + search drive the canvas filter; Esc peels selection then filter', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  // 目录 chips 单选（全部/根目录 + dirs：三节点全在 docs/ → 2 片）
  const chips = page.locator('#wiki-graph-side .wg-chips .wg-chip');
  await expect(chips).toHaveCount(2);
  await chips.nth(1).click();                    // 「docs/」
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().dirFilter)).toBe('docs');
  await expect(page.locator('#wiki-graph-side .wg-chip.on')).toHaveCount(1);
  // 再点同片 = 取消（回「全部」）
  await chips.nth(1).click();
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().dirFilter)).toBe('');
  // 搜索框 → canvas 过滤（可清空）
  await page.fill('#wiki-graph-side .wg-search-input', 'alpha');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().filter)).toBe('alpha');
  await page.fill('#wiki-graph-side .wg-search-input', '');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().filter)).toBe('');
  // Esc 顺序：先选中、后过滤
  await page.fill('#wiki-graph-side .wg-search-input', 'gamma');
  const g = await page.evaluate(() => window.__wikiGraphProbe());
  const node = g.nodes.find(n => n.id === 'gamma:docs/g.md');
  const box = await page.locator('.wiki-canvas').boundingBox();
  await page.mouse.click(
    box.x + (node.x - g.W / 2) * g.scale + g.W / 2 + g.vx,
    box.y + (node.y - g.H / 2) * g.scale + g.H / 2 + g.vy,
  );
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe('gamma:docs/g.md');
  await page.keyboard.press('Escape');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe(null);
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().filter)).toBe('gamma');
  await page.keyboard.press('Escape');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().filter)).toBe('');
  expect(errors).toEqual([]);
});

test('§D face contract: stats line wording, broken block fixed in-panel, row selects source, open button', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  const face = await readFace(page);
  // 统计行口径：3 docs · 1 references · 1 unresolved（stub 不计 docs，断链不立节点）
  expect(face.stats).toBe('3 docs · 1 references · 1 unresolved');
  // 目录 chips = 全部 + dirs（a.md/fig.svg → docs；g.md → docs）= 2 片
  expect(face.chips).toBe(2);
  // 详情空态在场
  expect(face.emptyShown).toBe(true);
  // 断链清单 = 面板内固定区块（非浮层）：display 打开 + position static + 有行
  //   （2 行 = beta 不可读文档库行 + a.md → ghost.md 未解析引用行）
  expect(face.brokenVisible).toBe(true);
  expect(face.brokenStatic).toBe(true);
  expect(face.brokenRows).toBe(2);
  // 断链行点击 → 源文档选中 + 详情刷新（vault 错误行是 div；引用行是 button）
  await page.click('#wiki-graph-side .wg-broken button.row');
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe('alpha:docs/a.md');
  await expect(page.locator('#wiki-graph-side .wg-detail-body h2')).toHaveText('Alpha One');
  // 详情卡按钮契约：打开文档（唯一离开图谱的路）
  await expect(page.locator('#wiki-graph-side .wg-detail-body .openbtn')).toHaveText('Open document');
  // 悬停 tooltip 仍是 canvas 面的交互面（title 行非空）
  const p = await page.evaluate(() => window.__wikiGraphProbe());
  const node = p.nodes.find(n => n.id === 'alpha:docs/a.md');
  const box = await page.locator('.wiki-canvas').boundingBox();
  await page.mouse.move(
    box.x + (node.x - p.W / 2) * p.scale + p.W / 2 + p.vx,
    box.y + (node.y - p.H / 2) * p.scale + p.H / 2 + p.vy,
    { steps: 6 },
  );
  await expect.poll(() => page.evaluate(() => {
    const tip = document.querySelector('.wiki-graph-pane .wg-tip');
    return tip && tip.style.display === 'block' && tip.querySelector('.tt').textContent.length > 0;
  })).toBe(true);
  expect(errors).toEqual([]);
});
