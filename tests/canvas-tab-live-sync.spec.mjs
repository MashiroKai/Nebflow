// canvas-tab-live-sync.spec.mjs — canvas-tab-sync 批验收（作者报障 2026-11-17
// 「各端 canvas 标签页不同步——搞单实例就是想从各端打开状态都是同步的」）
//
// 根因（设计缺口，非回归）：F1/#910 的服务端权威存档只在页面加载时被 GET
// （restoreTabs），运行中任一端的开/关/切/排序不外推，他端不刷新永远看不到。
// 修法 = 既有通道上补最小广播：PUT /api/canvas-tabs 落盘后把合并存档
// wsHub.broadcast 一帧 `canvasTabsChanged`，前端 applyLiveTabsFrame 按 #910
// 墓碑语义就地收敛（服务端 = ConfigRoutes PUT 臂；前端 = canvas.js）。
//
// 本 spec 钉任务书四项同步面 + 顺序面（双客户端隔离实测，A=桌面视口、B=手机
// 仿真视口，同一 mock 引擎）：
//   a. A 开新页签 → B 不刷新出现（记时延）；
//   b. A 关闭 → B 跟随消失（墓碑腿）；
//   c. A 切激活 → B 激活跟随（activeTabId 载荷腿）；
//   d. 两端并发各开一签 → 并集收敛、顺序一致、不分叉（extras 写穿腿）；
//   e. 顺序：A 关闭再重开中位签 → 新顺序广播 → B 重排（reorderTabsToArchive）。
//
// 红态（基线）：服务端无广播消费面 ⇒ 测 a 在「B 出现」断言超时红。
// 运行：npx playwright test tests/canvas-tab-live-sync.spec.mjs
// （自足 mock：静态服务 web/ 真源 + WS mock + /api/canvas-tabs mock 实现
// #910 merge 语义：墓碑并集压开放集 + 显式重开 + PUT 的 tabs 覆写 + 落盘即
// 广播 canvasTabsChanged 给全部已连 WS 客户端——服务端 Scala 臂的逐字语义镜像）

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8234);
const BASE = `http://127.0.0.1:${PORT}`;

const MD = (n) => `# doc\n\n${Array.from({ length: n }, (_, i) => `L${i}: body`).join('\n')}\n`;

// ── 静态服务（serve 真实 web/ 前端源码树）──
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    if (!res.headersSent) { res.writeHead(404); res.end('not found'); }
  }
});
test.beforeAll(async () => { await new Promise((r) => server.listen(PORT, '127.0.0.1', r)); });
test.afterAll(async () => { await new Promise((r) => server.close(r)); });

// ── mock 引擎：canvas-tabs 存档（#910 merge 语义）+ WS 广播面 ──────────────
// 每个 test.describe 重置（describe 串行；上下文间共享这一个「引擎」）。
const engine = {
  archive: null,              // null = 无存档（GET 404）
  wsClients: new Set(),       // 全部已连 WS 客户端（A/B 两端都在内）
  puts: [],                   // 全部 PUT 载荷（时间序）
  events: [],                 // { t, kind } 时延拆账：put / broadcast
  reset() { this.archive = null; this.wsClients.clear(); this.puts = []; this.events = []; },
  // #910 merge（CanvasTabs.merge 的 Node 镜像，够本 spec 的语义面）：
  // 墓碑并集取更晚 closedAt；reopened 只作用于已有墓碑；tabs = PUT 开放集
  // 滤除生效墓碑；activeTabId = PUT 的（缺省沿用 prev）再滤墓碑。
  mergePut(put) {
    const now = Date.now();
    const tombs = new Map((this.archive?.tombstones || []).map((t) => [t.id, { ...t }]));
    for (const t of put.tombstones || []) {
      const old = tombs.get(t.id);
      tombs.set(t.id, old
        ? { ...old, closedAt: Math.max(old.closedAt, t.closedAt), dev: t.dev || old.dev, reopenedAt: t.reopenedAt ?? old.reopenedAt }
        : { id: t.id, closedAt: t.closedAt, dev: t.dev, reopenedAt: t.reopenedAt });
    }
    for (const r of put.reopened || []) {
      const old = tombs.get(r.id);
      if (old) tombs.set(r.id, { ...old, reopenedAt: Math.max(old.reopenedAt || 0, r.reopenedAt) });
    }
    const activeIds = new Set([...tombs.values()].filter((t) => (t.reopenedAt || -Infinity) < t.closedAt).map((t) => t.id));
    const seen = new Set();
    const tabs = (put.tabs || []).filter((t) => t && t.id && !activeIds.has(t.id) && !seen.has(t.id) && seen.add(t.id));
    const prevActive = this.archive?.activeTabId || null;
    const activeTabId = (put.activeTabId ?? prevActive);
    this.archive = {
      v: 2, tabs, tombstones: [...tombs.values()],
      ...(activeTabId && !activeIds.has(activeTabId) ? { activeTabId } : {}),
    };
  },
  broadcast() {
    this.events.push({ t: Date.now(), kind: 'broadcast' });
    const frame = JSON.stringify({ type: 'canvasTabsChanged', ...this.archive });
    for (const ws of [...this.wsClients]) { try { ws.send(frame); } catch { /* closed */ } }
  },
};

/** Boot 一个客户端：真源 web 树 + WS mock（getHistory/pop.readFile/广播面）+
 *  /api/canvas-tabs REST mock（GET 存档 / PUT → engine.mergePut + broadcast）。
 *  mobile=true = 手机仿真视口（任务书要求的 B 端形态）。 */
async function bootClient(browser, { mobile = false, files = {} } = {}) {
  const ctx = await browser.newContext(mobile
    ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' }
    : { viewport: { width: 1440, height: 960 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.removeItem('nebflow_canvas_tabs'); localStorage.removeItem('neblink_canvas_tabs');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => {
    const url = new URL(r.request().url());
    if (url.pathname === '/api/canvas-tabs') {
      if (r.request().method() === 'GET') {
        if (engine.archive) r.fulfill({ json: engine.archive });
        else r.fulfill({ status: 404, json: { error: 'no canvas tabs archive' } });
        return;
      }
      if (r.request().method() === 'PUT') {
        try { engine.puts.push(r.request().postDataJSON()); } catch { /* ignore */ }
        engine.events.push({ t: Date.now(), kind: 'put' });
        engine.mergePut(r.request().postDataJSON());
        engine.broadcast(); // 修后服务端语义：落盘即外推（基线无此腿 = 红态）
        r.fulfill({ json: { ok: true } });
        return;
      }
    }
    r.fulfill({ json: {} });
  });
  await installTicketMock(page);
  const wsFrames = [];
  await page.routeWebSocket(/\/ws/, (ws) => {
    engine.wsClients.add(ws);
    ws.onClose(() => engine.wsClients.delete(ws));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      wsFrames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'pop.readFile' && files[m.path] !== undefined) {
        const f = files[m.path];
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          ...(f.content !== undefined ? { content: f.content } : {}),
          itemType: f.itemType || 'markdown', fileName: m.path.split('/').pop(),
          size: f.size ?? (f.content ? f.content.length : 0),
        }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  // 手机仿真视口下 activity-bar 是隐藏元素（mobile layer）——attached 即boot完成
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  return { ctx, page, errors, wsFrames };
}

const esc = (id) => String(id).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
const paneSel = (id) => `.canvas-tab-pane[data-tab-id="${esc(id)}"]`;
const tabSel = (id) => `#canvas-tab-bar .canvas-tab[data-tab-id="${esc(id)}"]`;

/** 页内驱动：canvas.js 导出面直接调用（openTab/closeTab/setActiveTab 都是导出函数，
 *  显式腿语义与用户动作一致——落 reopened 账、persistTabs、防抖 PUT）。 */
const drive = (page, code) => page.evaluate(`(async () => { const m = await import('/js/canvas.js'); ${code} })()`);

/** B 端页签序（DOM 序 = 语义序）。 */
const tabOrder = (page) => page.evaluate(() =>
  Array.from(document.querySelectorAll('#canvas-tab-bar .canvas-tab')).map((el) => el.dataset.tabId));

/** 等待两端 + mock 引擎三方同序（收敛判据：无分叉 = 两端序相等且集合 ⊇ want）。 */
async function waitForConverged(a, b, wantIds, timeout = 12000) {
  await expect.poll(async () => {
    const [oa, ob] = await Promise.all([tabOrder(a.page), tabOrder(b.page)]);
    const ok = (o) => wantIds.every((id) => o.includes(id));
    return ok(oa) && ok(ob) && JSON.stringify(oa) === JSON.stringify(ob);
  }, { timeout }).toBe(true);
  const [oa] = await Promise.all([tabOrder(a.page), tabOrder(b.page)]);
  return oa;
}

test.describe.configure({ mode: 'serial' });

test('stale frame cannot overwrite a pending local activation', async ({ browser }) => {
  // Legacy PUT acknowledgement must not let a stale frame undo a local switch.
  engine.reset();
  const a = await bootClient(browser);
  await drive(a.page, `m.openTab('guard-a', 'A', {type:'generic', pinned:true}); m.openTab('guard-b', 'B', {type:'generic', pinned:true})`);
  await expect.poll(() => engine.archive?.tabs?.some(t => t.id === 'guard-b')).toBe(true);
  await drive(a.page, `m.setActiveTab('guard-a')`);
  const stale = { ...engine.archive, activeTabId: 'guard-b', writeVersion: 1 };
  for (const ws of engine.wsClients) ws.send(JSON.stringify({ type: 'canvasTabsChanged', ...stale }));
  await expect.poll(() => a.page.locator(tabSel('guard-a') + '.active').count(), { timeout: 3000 }).toBe(1);
  await a.page.waitForTimeout(200);
  await expect(a.page.locator(tabSel('guard-a') + '.active')).toHaveCount(1);
  await a.ctx.close();
});

// ════ a. A 开新页签 → B 不刷新出现（记时延）════
test('A opens a tab; B (mobile viewport, no reload) follows — latency recorded', async ({ browser }) => {
  engine.reset();
  const a = await bootClient(browser);
  const b = await bootClient(browser, { mobile: true });
  const id = 'sync-open-a1';
  const t0 = Date.now();
  await drive(a.page, `m.openTab(${JSON.stringify(id)}, '同步甲', { type: 'generic', pinned: true })`);
  // B 不刷新页面：页签与 pane 都要出现（attached 即判——手机视口 canvas 收起
  // 时 tab bar 隐藏，visible 判据会假红；探针实测收敛 ≈ 防抖500ms+广播<1s）
  await b.page.waitForSelector(tabSel(id), { timeout: 8000, state: 'attached' });
  const domMs = Date.now() - t0;
  await expect(b.page.locator(paneSel(id))).toHaveCount(1, { timeout: 4000 });
  // 时延拆账：PUT 到达 mock 引擎的时刻（防抖 500ms 应被计入）
  const putEv = engine.events.find((e) => e.kind === 'put' && e.t >= t0);
  expect(putEv, 'PUT reached the mock engine (server persist leg)').toBeTruthy();
  console.log(`[cts] a. open → B tab DOM: ${domMs}ms (PUT at mock: ${putEv.t - t0}ms, broadcast→apply: ${domMs - (putEv.t - t0)}ms)`);
  expect(domMs, 'cross-device open sync under 4s').toBeLessThan(4000);
  expect(a.errors, 'zero pageerrors (A)').toEqual([]);
  expect(b.errors, 'zero pageerrors (B)').toEqual([]);
  await a.ctx.close(); await b.ctx.close();
});

// ════ b. A 关闭 → B 跟随消失（墓碑腿）════
test('A closes the tab; B follows — tab disappears without reload', async ({ browser }) => {
  engine.reset();
  const a = await bootClient(browser);
  const b = await bootClient(browser, { mobile: true });
  const id = 'sync-close-b1';
  await drive(a.page, `m.openTab(${JSON.stringify(id)}, '关闭乙', { type: 'generic', pinned: true })`);
  await b.page.waitForSelector(tabSel(id), { timeout: 8000, state: 'attached' });   // 手机收起时也可验证在场
  const t0 = Date.now();
  await drive(a.page, `m.closeTab(${JSON.stringify(id)})`);
  await expect(b.page.locator(paneSel(id)), 'B pane removed live (tombstone leg)').toHaveCount(0, { timeout: 8000 });
  const domMs = Date.now() - t0;
  console.log(`[cts] b. close → B pane gone: ${domMs}ms`);
  // 墓碑落到 mock 引擎（关单不是集合缺席——是显式关闭账）
  await expect.poll(() => (engine.archive?.tombstones || []).some((t) => t.id === id), { timeout: 4000 }).toBe(true);
  expect(domMs, 'cross-device close sync under 4s').toBeLessThan(4000);
  expect(a.errors).toEqual([]); expect(b.errors).toEqual([]);
  await a.ctx.close(); await b.ctx.close();
});

// 验收重复判据：本用例 --repeat-each=20 --workers=1 必须 20/20；另需稳定窗口验证。
// ════ c. A 切激活 → B 激活跟随（activeTabId 载荷腿）════
test('A switches the active tab; B active follows via activeTabId payload', async ({ browser }) => {
  engine.reset();
  const a = await bootClient(browser);
  const b = await bootClient(browser, { mobile: true });
  const ids = ['sync-act-c1', 'sync-act-c2'];
  await drive(a.page, `m.openTab(${JSON.stringify(ids[0])}, '切一', { type: 'generic', pinned: true })`);
  await drive(a.page, `m.openTab(${JSON.stringify(ids[1])}, '切二', { type: 'generic', pinned: true })`);
  await waitForConverged(a, b, ids);
  // A 从 c2（新开即激活）切回 c1
  const t0 = Date.now();
  await drive(a.page, `m.setActiveTab(${JSON.stringify(ids[0])})`);
  await expect(b.page.locator(tabSel(ids[0]) + '.active'), 'B active follows A').toHaveCount(1, { timeout: 8000 });
  await expect(b.page.locator(tabSel(ids[1]) + '.active')).toHaveCount(0);
  const domMs = Date.now() - t0;
  console.log(`[cts] c. active switch → B follows: ${domMs}ms`);
  // 服务端真源面：存档 activeTabId = A 的选择
  await expect.poll(() => engine.archive?.activeTabId, { timeout: 4000 }).toBe(ids[0]);
  expect(domMs, 'active sync under 4s').toBeLessThan(4000);
  expect(a.errors).toEqual([]); expect(b.errors).toEqual([]);
  await a.ctx.close(); await b.ctx.close();
});

// ════ d. 两端并发各开一签 → 并集收敛、同序、不分叉 ════
test('concurrent opens on both ends converge to the same union set+order (no fork)', async ({ browser }) => {
  engine.reset();
  const a = await bootClient(browser);
  const b = await bootClient(browser, { mobile: true });
  await Promise.all([
    drive(a.page, `m.openTab('cc-a', '并发甲', { type: 'generic', pinned: true })`),
    drive(b.page, `m.openTab('cc-b', '并发乙', { type: 'generic', pinned: true })`),
  ]);
  const order = await waitForConverged(a, b, ['cc-a', 'cc-b']);
  // 服务端 = 唯一真源：mock 引擎存档同集
  const archIds = () => (engine.archive?.tabs || []).map((t) => t.id);
  await expect.poll(async () => {
    const [oa, ob] = await Promise.all([tabOrder(a.page), tabOrder(b.page)]);
    return ['cc-a', 'cc-b'].every((id) => archIds().includes(id)) &&
      JSON.stringify(oa) === JSON.stringify(ob) && JSON.stringify(oa) === JSON.stringify(archIds());
  }, { timeout: 4000 }).toBe(true);
  console.log(`[cts] d. concurrent opens converged; order=${JSON.stringify(order)}`);
  expect(a.errors).toEqual([]); expect(b.errors).toEqual([]);
  await a.ctx.close(); await b.ctx.close();
});

// ════ e. 顺序：A 关闭再重开中位签 → B 重排（reorderTabsToArchive）════
test('A close+reopen reorders; B tab order follows the archive order', async ({ browser }) => {
  engine.reset();
  const a = await bootClient(browser);
  const b = await bootClient(browser, { mobile: true });
  const ids = ['ord-1', 'ord-2', 'ord-3'];
  for (const id of ids) await drive(a.page, `m.openTab(${JSON.stringify(id)}, '${id}', { type: 'generic', pinned: true })`);
  await waitForConverged(a, b, ids);
  // A 关闭 ord-2 再重开 ⇒ 服务端序变为 [ord-1, ord-3, ord-2]
  await drive(a.page, `m.closeTab('ord-2')`);
  await expect(b.page.locator(tabSel('ord-2'))).toHaveCount(0, { timeout: 8000 });
  await drive(a.page, `m.openTab('ord-2', 'ord-2', { type: 'generic', pinned: true })`);
  const relevantOrder = async (page) => (await tabOrder(page)).filter((id) => ids.includes(id));
  await expect.poll(async () => relevantOrder(b.page), { timeout: 10000 }).toEqual(['ord-1', 'ord-3', 'ord-2']);
  await expect.poll(async () => relevantOrder(a.page), { timeout: 10000 }).toEqual(['ord-1', 'ord-3', 'ord-2']);
  console.log('[cts] e. reorder propagated: both ends [ord-1, ord-3, ord-2]');
  expect(a.errors).toEqual([]); expect(b.errors).toEqual([]);
  await a.ctx.close(); await b.ctx.close();
});
