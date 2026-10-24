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
//   §E 画布手感（2026-10-24 CAD 批·作者令——覆盖 10-22 滚轮约定）：鼠标滚轮 =
//      以光标为锚点缩放（0.1x–4x 上下限；exp 累积 + 25%/帧阻尼追帧，逐帧步进
//      ≤0.06 不跳变、锚点世界坐标保持 <2px、收敛 zoomTarget）；中键拖拽 = 平移
//      （CAD 核心手势，松手不触发点选）；触控板双指滚动/Shift+滚轮仍 = 平移、
//      捏合/⌘滚仍 = 缩放；左键点选/拖节点语义不变；约定在左栏 .wg-hint 注明；
//   §F frosted 统一（追加批 2026-10-22）：canvas 胶囊 / 左栏搜索框 / chips 的
//      材质（bg/blur/border/shadow）与 chat header 逐项相等（--glass-bg /
//      --glass-blur saturate(1.15) / --glass-border / --frost-shadow 单源）；
//      canvas 胶囊裙边渐隐桌面退役（.scroll-under-glass::after display:none，
//      与聊天窗口渐隐同令；手机层维持现状，不在本 spec 视口内）；
//   §G 胶囊环 × 手感分区（2026-10-22 胶囊交互重做批）：hover 展开的胶囊环上
//      触控板横滑 = 环换选（环优先消费，vx/vy/scale 纹丝不动）；画布区横滑
//      仍 = 平移、捏合仍 = 缩放（#716 契约不被环劫持）；
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

/** Settled probe — waits out the late tab-refetch rebuild race before any
 *  coordinate-based canvas interaction (canvas.js panelTabRefresh →
 *  rerenderWikiGraph: debounced 200ms refetch + rebuildSim re-lays the graph,
 *  so a probe read too early can be stale by the time the click lands).
 *  Holds until two probes ≥400ms apart agree (layout frozen, no rebuild in
 *  flight), then returns a fresh probe snapshot. */
async function settledProbe(page) {
  await expect.poll(() => page.evaluate(async () => {
    const a = window.__wikiGraphProbe();
    await new Promise((r) => setTimeout(r, 400));
    const b = window.__wikiGraphProbe();
    const frozen = (p) => JSON.stringify(p.nodes.map((n) => [n.id, Math.round(n.x), Math.round(n.y)]));
    return a.alpha < 0.02 && b.alpha < 0.02 &&
      a.scale === b.scale && a.vx === b.vx && a.vy === b.vy &&
      frozen(a) === frozen(b);
  }), { timeout: 15000 }).toBe(true);
  return page.evaluate(() => window.__wikiGraphProbe());
}

/** Bring a node to the canvas center with a REAL wheel pan (shift+wheel — the
 *  guaranteed pan path of the 2026-10-24 CAD contract: shiftKey ⇒ wheelIsPan,
 *  vx += deltaX 1:1, no sim wake, no zoom). The mock layout is unseeded:
 *  a node can settle a few px off the canvas edge (past the sidebar col-
 *  resizer), where pointer clicks land on the resizer instead of the canvas.
 *  Centering puts the target ~W/2 px inside the clickable area, deterministically. */
async function centerNodeOn(page, nodeId) {
  await page.evaluate((id) => {
    const p = window.__wikiGraphProbe();
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    const n = p.nodes.find((m) => m.id === id);
    const sx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx; // canvas-local px
    const sy = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
    c.dispatchEvent(new WheelEvent('wheel', {
      deltaX: p.W / 2 - sx, deltaY: p.H / 2 - sy, shiftKey: true,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
      bubbles: true, cancelable: true,
    }));
  }, nodeId);
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  const off = await page.evaluate((id) => {
    const p = window.__wikiGraphProbe();
    const n = p.nodes.find((m) => m.id === id);
    return Math.max(Math.abs((n.x - p.W / 2) * p.scale + p.vx), Math.abs((n.y - p.H / 2) * p.scale + p.vy));
  }, nodeId);
  if (off > 2) throw new Error(`centerNodeOn(${nodeId}) missed: ${off}px off-center`);
}

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
  // canvas 点节点（探针读布局坐标 → 真实 mouse 路径）；先把目标摇到画布中心
  // （mock 布局无种子，节点可能停在画布边缘外/col-resizer 下方），再等 rebuild
  // 竞态窗口过去；点击未命中则按当前布局重算重试一次。
  await centerNodeOn(page, 'gamma:docs/g.md');
  let selected = null;
  for (let attempt = 0; attempt < 2 && !selected; attempt++) {
    const p1 = await settledProbe(page);
    const hub = p1.nodes.find(n => n.id === 'gamma:docs/g.md');
    const box = await page.locator('.wiki-canvas').boundingBox();
    const sx = box.x + (hub.x - p1.W / 2) * p1.scale + p1.W / 2 + p1.vx;
    const sy = box.y + (hub.y - p1.H / 2) * p1.scale + p1.H / 2 + p1.vy;
    await page.mouse.click(sx, sy);
    try {
      await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected), { timeout: 1500 })
        .toBe('gamma:docs/g.md');
      selected = 'gamma:docs/g.md';
    } catch { /* rebuild raced the click — recompute from the fresh layout */ }
  }
  expect(selected).toBe('gamma:docs/g.md');
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
  // Esc 顺序：先选中、后过滤（目标先摇到画布中心 —— 布局无种子，见 centerNodeOn）
  await page.fill('#wiki-graph-side .wg-search-input', 'gamma');
  await centerNodeOn(page, 'gamma:docs/g.md');
  const g = await settledProbe(page);
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
  // 悬停 tooltip 仍是 canvas 面的交互面（title 行非空）——同样先把目标摇到中心
  await centerNodeOn(page, 'alpha:docs/a.md');
  const p = await settledProbe(page);
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

// ── §E 画布手感（追加批 2026-10-22）＋ §F frosted 统一／裙边退役 ───────

test('§E-1 mouse wheel = cursor-anchored damped zoom; bounds 0.1x–4x; smooth & convergent (CAD)', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  const N_WHEEL = 12;    // 连续滚轮帧数（每帧 Δ-60 = 鼠标档，避开 48px 触控板阈值）
  const PINCH_K = 0.0015; // 必须与 wikiGraph.js 的 PINCH_K 同值
  const STEP = 60;
  const ZOOM_MIN = 0.1, ZOOM_MAX = 4.0; // 必须与 wikiGraph.js 的 ZOOM_MIN/MAX 同值
  await settledProbe(page); // 等 rerenderWikiGraph 的 debounce rebuild 窗口过去

  // 图例：左栏 .wg-hint 注明滚轮/中键/触控板约定（canvas 保纯，图例进左栏）。
  const hint = await page.textContent('#wiki-graph-side .wg-hint');
  expect(hint.trim().length).toBeGreaterThan(0);

  // 单格滚轮（整数档 Δ=-120，无修饰）= 缩放而非平移：zoomTarget 按 exp 曲线累积。
  const one = await page.evaluate(() => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    const before = window.__wikiGraphProbe();
    c.dispatchEvent(new WheelEvent('wheel', {
      deltaY: -120, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true,
    }));
    const after = window.__wikiGraphProbe();
    return { scale: before.scale, target: after.zoomTarget, dvx: after.vx - before.vx, dvy: after.vy - before.vy };
  });
  expect(one.target).toBeCloseTo(Math.min(ZOOM_MAX, one.scale * Math.exp(120 * PINCH_K)), 6);
  expect(one.dvx).toBe(0);   // 缩放不串扰平移自由度
  expect(one.dvy).toBe(0);

  // 连续滚轮：逐格派发（每 4 帧一格——模拟真实滚轮节奏 ~15Hz vs rAF 60Hz；rAF
  // 交错是必须的：同步整批会让追帧一帧吃完全部累积，无法验证平滑契约），
  // 每帧采样 scale；再追 40 帧确认收敛。
  const wheel = await page.evaluate(({ N, K, step }) => new Promise((resolve) => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    const ax = r.left + r.width / 2, ay = r.top + r.height / 2;
    const before = window.__wikiGraphProbe();
    // 世界坐标取 wikiGraph.js toWorld 的精确形：(px − W/2 − vx)/scale + W/2
    // （screen = (w − W/2)·s + W/2 + v 的逆；旧口径漏 −W/2 项，与旧补偿公式
    // 同错而对消——CAD 批两者同修正，屏幕漂移 <2px 才是真锚定）。
    const wOf = (p) => ({
      x: ((ax - r.left) - p.W / 2 - p.vx) / p.scale + p.W / 2,
      y: ((ay - r.top) - p.H / 2 - p.vy) / p.scale + p.H / 2,
    });
    const wBefore = wOf(before);
    const seen = [];
    let i = 0, f = 0;
    const tick = () => {
      if (i < N && f % 4 === 0) {
        c.dispatchEvent(new WheelEvent('wheel', {
          deltaY: -step, clientX: ax, clientY: ay, bubbles: true, cancelable: true,
        }));
        i++;
      }
      f++;
      seen.push(window.__wikiGraphProbe().scale);
      if (seen.length >= N * 4 + 40) resolve({ seen, target: before.zoomTarget * Math.exp(N * step * K), wBefore, r: { left: r.left, top: r.top } });
      else requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }), { N: N_WHEEL, K: PINCH_K, step: STEP });

  const final = wheel.seen[wheel.seen.length - 1];
  let maxStep = 0;
  for (let i = 1; i < wheel.seen.length; i++) maxStep = Math.max(maxStep, Math.abs(wheel.seen[i] - wheel.seen[i - 1]));
  expect(maxStep).toBeGreaterThan(0);            // 缩放确实发生了
  // 逐帧步进有界（阻尼不跳变）：一格 60px 滚轮 @15Hz 下阻尼追帧峰值 ≈0.11，
  // 上界 0.12 —— 真跳变会是 0.5+；触控板捏合微帧（Δ≈2px）的平滑口径承 10-22 批。
  expect(maxStep).toBeLessThanOrEqual(0.12);
  expect(Math.abs(final - wheel.target)).toBeLessThan(0.02); // 收敛 zoomTarget
  // 光标锚点几何保持：锚点（canvas 中心）下的世界坐标漂移 < 2px。
  const drift = await page.evaluate(({ wBefore, rect }) => {
    const p = window.__wikiGraphProbe();
    const ax = rect.left + (document.querySelector('.wiki-canvas').getBoundingClientRect().width / 2);
    const ay = rect.top + (document.querySelector('.wiki-canvas').getBoundingClientRect().height / 2);
    const w = {
      x: (ax - rect.left - p.W / 2 - p.vx) / p.scale + p.W / 2,
      y: (ay - rect.top - p.H / 2 - p.vy) / p.scale + p.H / 2,
    };
    return Math.hypot(w.x - wBefore.x, w.y - wBefore.y);
  }, { wBefore: wheel.wBefore, rect: wheel.r });
  expect(drift).toBeLessThan(2);
  // 同一世界点的屏幕位置也不动（用户体感的锚定）：原锚点世界点仍落在锚点上。
  const screenDrift = await page.evaluate(({ wBefore }) => {
    const p = window.__wikiGraphProbe();
    const sx = (wBefore.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
    const sy = (wBefore.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
    return Math.hypot(sx - p.W / 2, sy - p.H / 2);
  }, { wBefore: wheel.wBefore, rect: wheel.r });
  expect(screenDrift).toBeLessThan(2);

  // 上下限：向上一口气滚 30 格 ⇒ zoomTarget 钳在 ZOOM_MAX；收敛后 scale ≤ 上限。
  await page.evaluate(() => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    for (let i = 0; i < 30; i++) c.dispatchEvent(new WheelEvent('wheel', {
      deltaY: -120, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true,
    }));
  });
  expect(await page.evaluate(() => window.__wikiGraphProbe().zoomTarget)).toBe(ZOOM_MAX);
  await page.waitForFunction(() => {
    const p = window.__wikiGraphProbe();
    return Math.abs(p.scale - p.zoomTarget) <= 0.0005;
  }, { timeout: 8000 });
  expect(await page.evaluate(() => window.__wikiGraphProbe().scale)).toBeLessThanOrEqual(ZOOM_MAX + 0.0005);

  // 向下滚 60 格 ⇒ 钳在 ZOOM_MIN；收敛后 scale ≥ 下限（0.1x 不塌到 0）。
  await page.evaluate(() => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    for (let i = 0; i < 60; i++) c.dispatchEvent(new WheelEvent('wheel', {
      deltaY: 120, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true,
    }));
  });
  expect(await page.evaluate(() => window.__wikiGraphProbe().zoomTarget)).toBe(ZOOM_MIN);
  await page.waitForFunction(() => {
    const p = window.__wikiGraphProbe();
    return Math.abs(p.scale - p.zoomTarget) <= 0.0005;
  }, { timeout: 8000 });
  expect(await page.evaluate(() => window.__wikiGraphProbe().scale)).toBeGreaterThanOrEqual(ZOOM_MIN - 0.0005);
  expect(errors).toEqual([]);
});

test('§E-2 trackpad/shift pan intact; middle-drag = pan; left semantics regression (CAD)', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  await settledProbe(page);

  // 触控板双指滚动（小帧 + 横分量）仍 = 平移：deltaX 直通、小纵帧不误触缩放。
  const pan = await page.evaluate(() => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    const ev = (init) => c.dispatchEvent(new WheelEvent('wheel', {
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true, ...init,
    }));
    const a = window.__wikiGraphProbe();
    ev({ deltaX: 6, deltaY: 3 });            // 触控板斜滑帧
    const b = window.__wikiGraphProbe();
    ev({ deltaY: -120, shiftKey: true });    // shift+纵滚 = 横移（符号直通 -120 → vx-120）
    const c2 = window.__wikiGraphProbe();
    ev({ deltaY: 4 });                       // 纯纵小帧 = 触控板慢滑 → 仍平移
    const d = window.__wikiGraphProbe();
    return {
      dvx1: Math.round(b.vx - a.vx), dvy1: Math.round(b.vy - a.vy), dscale1: +(b.scale - a.scale).toFixed(6),
      dvx2: Math.round(c2.vx - b.vx), dvy2: Math.round(c2.vy - b.vy), dscale2: +(c2.scale - b.scale).toFixed(6),
      dvy3: Math.round(d.vy - c2.vy), dscale3: +(d.scale - c2.scale).toFixed(6),
    };
  });
  expect(pan).toMatchObject({ dvx1: 6, dvy1: 3, dscale1: 0, dvx2: -120, dvy2: 0, dscale2: 0, dscale3: 0 });

  // 找一块距所有节点 ≥ 30px 的空画布点（中键/左键空白手势的确定性落点）。
  const empty = await page.evaluate(() => {
    const p = window.__wikiGraphProbe();
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    let best = null, bestD = 0;
    for (let gx = 1; gx < 8; gx++) for (let gy = 1; gy < 6; gy++) {
      const sx = r.width * gx / 8, sy = r.height * gy / 6;
      let dmin = Infinity;
      for (const n of p.nodes) {
        const nx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
        const ny = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
        dmin = Math.min(dmin, Math.hypot(nx - sx, ny - sy));
      }
      if (dmin > bestD) { bestD = dmin; best = { sx, sy }; }
    }
    return { ...best, dmin: bestD, left: r.left, top: r.top };
  });
  expect(empty.dmin).toBeGreaterThan(30);
  const ex = empty.left + empty.sx, ey = empty.top + empty.sy;

  // 中键拖拽 = 平移（CAD 核心手势）：1:1 跟手，松手不点选。
  const v0 = await page.evaluate(() => { const p = window.__wikiGraphProbe(); return { vx: p.vx, vy: p.vy }; });
  await page.mouse.move(ex, ey);
  await page.mouse.down({ button: 'middle' });
  await page.mouse.move(ex + 90, ey + 60, { steps: 5 });
  const vMid = await page.evaluate(() => { const p = window.__wikiGraphProbe(); return { vx: p.vx, vy: p.vy }; });
  await page.mouse.up({ button: 'middle' });
  expect(Math.round(vMid.vx - v0.vx)).toBe(90);
  expect(Math.round(vMid.vy - v0.vy)).toBe(60);
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe(null);

  // 中键点按（不动）= 纯收尾：仍不触发点选。
  await page.mouse.move(ex, ey);
  await page.mouse.down({ button: 'middle' });
  await page.mouse.up({ button: 'middle' });
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe(null);

  // 左键语义回归：左键拖空白仍 = 平移（既有兜底入口），松手无点选。
  const v1 = await page.evaluate(() => { const p = window.__wikiGraphProbe(); return { vx: p.vx, vy: p.vy }; });
  await page.mouse.move(ex, ey);
  await page.mouse.down({ button: 'left' });
  await page.mouse.move(ex - 50, ey - 40, { steps: 5 });
  const vLeft = await page.evaluate(() => { const p = window.__wikiGraphProbe(); return { vx: p.vx, vy: p.vy }; });
  await page.mouse.up({ button: 'left' });
  expect(Math.round(vLeft.vx - v1.vx)).toBe(-50);
  expect(Math.round(vLeft.vy - v1.vy)).toBe(-40);
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe(null);
  expect(errors).toEqual([]);
});

test('§F frosted: capsule ≡ search ≡ chips ≡ chat header material; capsule skirt fade retired on desktop', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  const mat = await page.evaluate(() => {
    const pick = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const cs = getComputedStyle(el);
      return { bg: cs.backgroundColor, blur: cs.backdropFilter || cs.webkitBackdropFilter, border: cs.borderColor, shadow: cs.boxShadow };
    };
    return { header: pick('#header'), capsule: pick('.canvas-header'), search: pick('.wg-search'), chip: pick('.wg-chip:not(.on)') };
  });
  // chips 契约取「静息片」：无过滤时首片（全部）恒为 .on 选中态 accent 材质，
  // 不在本契约内（同 chat header 的 hover 控件）。
  expect(mat.header && mat.capsule && mat.search && mat.chip).toBeTruthy();
  expect(mat.capsule).toEqual(mat.header);
  expect(mat.search).toEqual(mat.header);
  expect(mat.chip).toEqual(mat.header);
  // ③ canvas 胶囊裙边渐隐桌面退役：类仍挂（手机层共用样式表），桌面 ::after
  // display:none —— 画布内容不再穿胶囊渐变淡出。
  const skirt = await page.evaluate(() => {
    const hdr = document.querySelector('.canvas-header');
    return { cls: hdr.classList.contains('scroll-under-glass'), after: getComputedStyle(hdr, '::after').display };
  });
  expect(skirt.cls).toBe(true);
  expect(skirt.after).toBe('none');
  expect(errors).toEqual([]);
});

test('§G capsule ring consumes horizontal swipe; canvas keeps pan/pinch (#716 non-conflict)', async ({ page }) => {
  const errors = await boot(page);
  await openGraph(page);
  // 第二页签（环需 ≥2）：开 projects 面板页签再回图谱（boot 自带 plugins 页签
  // → tabCount 3；环序 = 插序，图谱居中时缘位含 projects）
  await page.click('#projects-btn');
  await expect.poll(() => page.evaluate(() => window.__canvasDeck?.state?.()?.tabCount), { timeout: 8000 }).toBe(3);
  await page.click('#wiki-btn');
  await expect.poll(() => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.dataset.tabId), { timeout: 8000 }).toBe('wiki-graph');

  // hover 标题头 → 环展开：中心 = wiki-graph、缘位 = projects
  const hp = await page.evaluate(() => {
    const r = document.querySelector('#canvas-panel .canvas-header').getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.move(hp.x, hp.y);
  await page.waitForFunction(() => document.querySelector('#canvas-panel .canvas-header').classList.contains('ring-open'), { timeout: 4000 });
  const ring = await page.evaluate(() => {
    const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')];
    const center = caps.find((c) => c.classList.contains('is-center'));
    const edges = caps.filter((c) => c.classList.contains('is-edge')).map((c) => c.dataset.tabId);
    return { n: caps.length, center: center?.dataset.tabId || null, edges };
  });
  expect(ring.n).toBe(3);                       // 3 页签 = 中心 + 2 缘位（0 弧外，最多可见 3）
  expect(ring.center).toBe('wiki-graph');
  expect(ring.edges).toContain('projects');

  // 环上纵滚一格（hover 交互改向令：deltaY 主导，100 = 一格）：环游标移动到
  // projects；图谱 view 四自由度纹丝不动
  const viewBefore = await page.evaluate(() => {
    const p = window.__wikiGraphProbe();
    return { vx: p.vx, vy: p.vy, scale: p.scale, zoomTarget: p.zoomTarget };
  });
  await page.mouse.wheel(0, 100);  // 环序 [plugins, wiki-graph, projects]：+1 格 → projects 居中
  await page.waitForFunction(() => {
    const c = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    return c && c.dataset.tabId === 'projects';
  }, { timeout: 4000 });
  const viewAfter = await page.evaluate(() => {
    const p = window.__wikiGraphProbe();
    return { vx: p.vx, vy: p.vy, scale: p.scale, zoomTarget: p.zoomTarget };
  });
  expect(viewAfter).toEqual(viewBefore);

  // 离环（画布中下空区 —— 顶部修正批 2026-10-23 后环锚常驻顶带，胶囊不再沉降
  // canvas 中心，「回画布中心」虽已非胶囊本体，下移 240px 仍稳出环区）→ 环收；
  // 画布区横滑仍 = 平移、捏合仍 = 缩放（#716 不被劫持）
  const box = await page.locator('.wiki-canvas').boundingBox();
  const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
  await page.mouse.move(cx, cy + 240);
  await expect.poll(() => page.evaluate(() => !document.querySelector('#canvas-panel .canvas-header').classList.contains('ring-open')), { timeout: 4000 }).toBe(true);
  await page.evaluate(() => new Promise((resolve) => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    let i = 0;
    const tick = () => {
      if (i >= 6) { resolve(null); return; }
      c.dispatchEvent(new WheelEvent('wheel', {
        deltaY: -10, ctrlKey: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true,
      }));
      i++;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }));
  const pinched = await page.evaluate(() => window.__wikiGraphProbe().scale);
  expect(pinched).toBeGreaterThan(viewBefore.scale + 0.01);
  // 追帧收尾（锚点补偿会持续微调 vx）—— 收敛后再测横滑平移，口径才纯
  await page.waitForFunction(() => {
    const p = window.__wikiGraphProbe();
    return Math.abs(p.scale - p.zoomTarget) <= 0.0005;
  }, { timeout: 8000 });
  const vxBeforePan = await page.evaluate(() => window.__wikiGraphProbe().vx);
  await page.evaluate(() => new Promise((resolve) => {
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    c.dispatchEvent(new WheelEvent('wheel', {
      deltaX: 120, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true,
    }));
    requestAnimationFrame(() => requestAnimationFrame(resolve));
  }));
  const panned = await page.evaluate(() => window.__wikiGraphProbe().vx);
  expect(Math.round(panned)).toBe(Math.round(vxBeforePan + 120));
  expect(errors).toEqual([]);
});
