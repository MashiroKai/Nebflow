// canvas-tab-tombstone-revive.spec.mjs — #910 追补批验收（作者报障 2026-10-27
// 11:27「现在标签页还是老是会加载我已经关闭的」）
//
// 根因（复现+归档取证 ~/.stand/canvas_tabs.json：43 墓碑全部 reopenedAt >
// closedAt，2026-10-27 11:19-11:21 每 4-5s 一发的自动重开把墓碑翻惰性）：
// 恢复/投影路径（localStorage 缓存腿 / readFile 恢复管线）能把带生效墓碑的
// id 送进 openTab，而 openTab 无差别落 reopened 账 —— 服务端 merge 以
// reopenedAt > closedAt 翻惰性墓碑，一次自动复活 = 永久复活。
//
// 本 spec 钉任务书三条（前端恢复腿半边；服务端持久层半边在
// nebflow.core.CanvasTabTombstoneSpec 追补两测）：
//   ① 关闭→重载不复活：缓存带墓碑账（新 persistTabs 形态）→ 恢复过滤，
//      页签不开、readFile 不发、reopened 账不落（墓碑绝不被翻惰性）；
//   ② 关闭→对端广播同页签不复活（前端面）：陈旧缓存（老形态无账）先恢复
//      → 服务端 GET 迟到墓碑清扫压掉；全程零 reopened 账、缓存收敛干净；
//   ③ 显式重开才清墓碑（作者裁定「找少的那个认吧」的另一半）：用户显式
//      打开照落 reopened 账 —— 原语义不回退，重开态跨重载不丢。
//
// 运行：npx playwright test tests/canvas-tab-tombstone-revive.spec.mjs
// （自足 mock：静态服务 web/ 真源 + WS mock + /api/canvas-tabs GET/PUT 捕获）

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8233);
const BASE = `http://127.0.0.1:${PORT}`;

const CLOSED = '/tmp/tomb/已关闭报告.md';
const KEEPER = '/tmp/tomb/保留.md';
const CLOSED_ID = `file:${CLOSED}`;
const KEEPER_ID = `file:${KEEPER}`;
const T0 = 1793045060000; // 关闭时刻（过去）
const TOMB = { id: CLOSED_ID, closedAt: T0, dev: 'Win32' };

const MD = (n) => `# doc\n\n${Array.from({ length: n }, (_, i) => `L${i}: body`).join('\n')}\n`;
const tabOf = (id, absPath) => ({ id, title: absPath.split('/').pop(), type: 'markdown', absPath, pinned: true, closable: true });

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

/**
 * Boot: 真源 web 树 + WS mock（应答 pop.readFile）+ /api/canvas-tabs GET/PUT。
 * lsSeed = 开页前写入两个 flavor localStorage 的 v2 标签缓存（addInitScript
 * 播种——先于一切页面 JS，无首载异步流覆盖竞态）；serverArchive = GET 应答
 * （测内可变，模拟服务端 merge 演进）；getDelayMs 给 GET 加延迟（钉「先恢复、
 * 后到墓碑」的清扫时序）；putBodies 捕获全部 PUT 载荷。
 */
async function bootPage(browser, { serverArchive, getDelayMs = 0, files = {}, lsSeed = null } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  await ctx.addInitScript(({ seed }) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    for (const k of ['nebflow_canvas_tabs', 'neblink_canvas_tabs']) {
      if (seed) localStorage.setItem(k, JSON.stringify(seed));
      else localStorage.removeItem(k);
    }
  }, { seed: lsSeed });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  const putBodies = [];
  await page.route('**/api/**', (r) => {
    const url = new URL(r.request().url());
    if (url.pathname === '/api/canvas-tabs') {
      if (r.request().method() === 'GET') {
        setTimeout(() => r.fulfill({ json: serverArchive }), getDelayMs);
        return;
      }
      if (r.request().method() === 'PUT') {
        try { putBodies.push(r.request().postDataJSON()); } catch { /* ignore */ }
        r.fulfill({ json: { ok: true } });
        return;
      }
    }
    r.fulfill({ json: {} });
  });
  await installTicketMock(page);
  const wsFrames = [];
  await page.routeWebSocket(/\/ws/, (ws) => {
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
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  return { ctx, page, errors, wsFrames, putBodies };
}

const esc = (id) => String(id).replace(/\\/g, '\\\\').replace(/"/g, '\\"'); // Node 侧无 CSS.escape
const paneSel = (id) => `.canvas-tab-pane[data-tab-id="${esc(id)}"]`;
const reopenedFor = (putBodies, id) => (putBodies || []).some((b) => Array.isArray(b?.reopened) && b.reopened.some((r) => r?.id === id));
const putTabsContain = (putBodies, id) => (putBodies || []).some((b) => Array.isArray(b?.tabs) && b.tabs.some((t) => t?.id === id));

// ════ ① 关闭→重载不复活（缓存带墓碑账）════
test.describe('close → reload never revives', () => {
  test('B1 stale cache WITH tombstone ledger: closed tab not restored, no readFile, no reopen journal', async ({ browser }) => {
    // 服务端存档 = 已被服务端过滤的形态（已关页签不在 tabs，墓碑在场）——
    // 正常服务端行为；复活源只剩本地缓存（陈旧窗口回写会把已关页签带回来）
    const serverArchive = { v: 2, tabs: [tabOf(KEEPER_ID, KEEPER)], tombstones: [TOMB] };
    const { ctx, page, errors, wsFrames, putBodies } = await bootPage(browser, {
      serverArchive,
      files: { [KEEPER]: { content: MD(5) } },
      lsSeed: { v: 2, tabs: [tabOf(KEEPER_ID, KEEPER), tabOf(CLOSED_ID, CLOSED)], activeTabId: null, tombstones: [TOMB] },
    });
    // 控制组：未关闭的页签正常恢复（过滤不误伤）
    await expect(page.locator(paneSel(KEEPER_ID)), 'keeper tab restored as control').toBeVisible({ timeout: 15000 });
    // 已关页签：不开页签、不自动加载（无 readFile）、不落重开账
    await page.waitForTimeout(1200); // 恢复管线 + 防抖 PUT 窗口全走完
    expect(await page.locator(paneSel(CLOSED_ID)).count()).toBe(0);
    expect(await page.locator(`#canvas-tab-bar .canvas-tab[data-tab-id="${esc(CLOSED_ID)}"]`).count()).toBe(0);
    expect(wsFrames.filter((m) => m.type === 'pop.readFile' && m.path === CLOSED), 'closed file never auto-loads').toEqual([]);
    expect(reopenedFor(putBodies, CLOSED_ID), 'no reopened journal → server tombstone never deactivated').toBe(false);
    // 任何 PUT 都绝不把已关页签放回开放集（双保险：服务端另有闸）
    expect(putTabsContain(putBodies, CLOSED_ID), 'no PUT ever re-adds the closed tab').toBe(false);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });
});

// ════ ② 陈旧缓存无账 → 迟到墓碑清扫（先恢复、后压掉）════
test.describe('late tombstone sweep', () => {
  test('B2 pre-fix cache (no ledger): tab restores transiently, swept on server GET, cache converges clean, zero reopen journal', async ({ browser }) => {
    const serverArchive = { v: 2, tabs: [tabOf(KEEPER_ID, KEEPER)], tombstones: [TOMB] };
    // GET 延迟 800ms：本地恢复先走完（无账可滤 → 页签短暂复活），墓碑后到
    const { ctx, page, errors, putBodies } = await bootPage(browser, {
      serverArchive, getDelayMs: 800,
      files: { [KEEPER]: { content: MD(5) }, [CLOSED]: { content: MD(5) } },
      lsSeed: { v: 2, tabs: [tabOf(KEEPER_ID, KEEPER), tabOf(CLOSED_ID, CLOSED)], activeTabId: null }, // 老形态：无 tombstones
    });
    // 先恢复进来（无账可滤）：已关页签短暂在场
    await expect(page.locator(paneSel(CLOSED_ID)), 'stale cache restores the tab before the tombstone arrives').toBeVisible({ timeout: 15000 });
    // 迟到墓碑把它压掉
    await expect(page.locator(paneSel(CLOSED_ID)), 'late tombstone sweeps the revived tab').toHaveCount(0, { timeout: 15000 });
    // 控制组：keeper 不被误扫（pane 在场即可——被扫的 CLOSED 曾是活动签，
    // 幸存签处于非活动态时 pane 隐藏属正常 UI 行为，不拿可见性当判据）
    await expect(page.locator(paneSel(KEEPER_ID)), 'keeper tab survives the sweep').toHaveCount(1, { timeout: 15000 });
    // 墓碑绝未被翻惰性（本 bug 的根因断言；B1 另钉「从不重加开放集」——
    // 本场景（老缓存无账）允许墓碑已知前的瞬时恢复 PUT 带 tabs[CLOSED]，
    // 由服务端墓碑闸滤除，见 CanvasTabTombstoneSpec「陈旧全量 PUT」两测）
    expect(reopenedFor(putBodies, CLOSED_ID), 'restore leg never journals reopened').toBe(false);
    // 缓存收敛：清扫后的持久化把已关页签从 localStorage 清出去。
    // 键 = 品牌命名空间（静态服务的 fallback brand='stand'；init 种子走
    // nebflow_*/neblink_* 是 legacy 迁移源），经应用的 key() 读真键。
    await expect.poll(async () => {
      const ls = await page.evaluate(async () => {
        const { key } = await import('/js/branding.js');
        return JSON.parse(localStorage.getItem(key('canvas_tabs')) || 'null');
      });
      return ls?.tabs?.some((t) => t.id === CLOSED_ID) === false;
    }, { timeout: 8000 }).toBe(true);
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });
});

// ════ ③ 显式重开仍清墓碑（作者语义不回退）════
test.describe('explicit reopen still clears the tombstone', () => {
  test('B3 explicit open journals reopened; reopened state survives reload (tombstone stays inert)', async ({ browser }) => {
    // serverArchive 由测内随 PUT 演进（模拟真实服务端 merge 行为）：
    //   初始 = 关闭态（墓碑生效、开放集不含）→ 显式重开 PUT 后 = 重开态
    //   （墓碑惰性 reopenedAt、开放集含该页签）。
    const serverArchive = { v: 2, tabs: [], tombstones: [{ ...TOMB }] };
    const { ctx, page, errors, putBodies } = await bootPage(browser, { serverArchive, files: { [CLOSED]: { content: MD(5) } } });
    // 用户显式打开（explorer 双击同链路：workspace-open-item → openTab，无恢复标记）
    await page.evaluate(({ id, absPath }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id, itemType: 'markdown', title: '已关闭报告.md', content: '# back\n', absPath, pinned: true },
      }));
    }, { id: CLOSED_ID, absPath: CLOSED });
    await expect(page.locator(paneSel(CLOSED_ID)), 'explicit open creates the tab despite active tombstone').toBeVisible({ timeout: 15000 });
    // 防抖 PUT 到达：reopened 账在 payloads（服务端据此翻惰性墓碑）
    await expect.poll(() => reopenedFor(putBodies, CLOSED_ID), { timeout: 8000 }).toBe(true);
    const rTs = putBodies.flatMap((b) => b?.reopened || []).find((r) => r?.id === CLOSED_ID)?.reopenedAt;
    expect(rTs, 'reopen journal carries a timestamp').toBeGreaterThan(TOMB.closedAt);
    // 服务端 merge 后的形态：墓碑转惰性、开放集含该页签
    serverArchive.tabs = [tabOf(CLOSED_ID, CLOSED)];
    serverArchive.tombstones = [{ id: CLOSED_ID, closedAt: TOMB.closedAt, reopenedAt: rTs }];
    // 重载：重开态不丢（服务端惰性墓碑 + 本地缓存账一致）
    await page.reload();
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
    await expect(page.locator(paneSel(CLOSED_ID)), 'reopened tab survives reload').toBeVisible({ timeout: 15000 });
    expect(errors, 'zero pageerrors').toEqual([]);
    await ctx.close();
  });
});
