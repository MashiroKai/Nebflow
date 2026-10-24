// canvas-tabs-lww.spec.mjs — 修复批 · canvas 标签页跨设备关闭不收敛（关了刷新又复活）spec 钉
//
// 作者报障（2026-10-24 14:18 逐字）：「我在canvas关闭的标签页。（在另一台电脑上
// 控制stand）刷新之后标签页又重新打开了」。
//
// 根因（修复批判定）：B 机过期 localStorage 缓存先行恢复（旧 restoreTabs 本地
// 先行）+ reconcileFromServer 只加不减（canvas.js 旧 :4327 `present.has → skip`），
// 且恢复期 setActiveTab→persistTabs 把本地并集防抖 PUT 回服务端（把已关标签二次
// 污染进权威存档 → 两台互相复活）。
//
// 修复语义（对齐 identity-sync 批 LWW 收敛）：关闭/打开 = 带戳（updatedAt）权威写
// → 服务端 LWW 裁决（陈旧写 409 回权威存档）→ 写穿广播 canvasTabsChanged → 各设备
// 按戳收敛（补开缺失 + 收敛删除）；刷新 = server-first，localStorage 仅离线兜底；
// 恢复期写入被 hydration 闸压住。spec 四景：
//   A  单设备关闭 → 服务端持久化 → 第二设备刷新不复活（含：无回写污染、恢复
//      readFile 不给已关标签发）；
//   B  写穿广播：他端打开 → 本端补开；他端关闭 → 本端收敛删除（不等刷新）；
//   C  LWW 陈旧写落选：PUT 409 回权威存档 → 本端即时收敛（离线旧视图不覆盖）；
//   D  存档契约：PUT 载荷带 updatedAt 戳 + per-tab openedAt。
//
// 运行：node tests/canvas-tabs-lww.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8171);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

// 文件内容（pop.readFile 响应）：恢复管线把 file 标签内容拉回来。
const T0 = Date.now() - 60_000;        // B 机缓存写入时刻（陈旧）
const T_CLOSE = Date.now() - 30_000;   // A 机关闭 file:X 的时刻（缓存之后）
const X = 'file:/tmp/lww-fixture/x-doc.md';
const Y = 'file:/tmp/lww-fixture/y-doc.md';
const Z = 'file:/tmp/lww-fixture/z-doc.md';
const W = 'file:/tmp/lww-fixture/w-doc.md';
const FILE_CONTENTS = new Map([
  [X.slice(5), { content: '# X doc\n', itemType: 'markdown' }],
  [Y.slice(5), { content: '# Y doc\n', itemType: 'markdown' }],
  [Z.slice(5), { content: '# Z doc\n', itemType: 'markdown' }],
  [W.slice(5), { content: '# W doc\n', itemType: 'markdown' }],
]);
const tab = (id, extra = {}) => ({
  id, title: (id.startsWith('file:') ? id.slice(5) : id).split('/').pop(),
  type: id.startsWith('file:') ? 'markdown' : id, absPath: id.startsWith('file:') ? id.slice(5) : null,
  pinned: true, closable: true, ...extra,
});

/**
 * boot：localStorage 种子缓存（B 机过期视图）+ /api/canvas-tabs mock（GET=服务端
 * 权威存档 / PUT 可 409 回权威存档）+ 最小 WS mock（握手 + pop.readFile 回声 +
 * 广播注入口）。返回 { page, wsSend, puts, errors }。
 */
async function boot({ localTabs, serverArchive, putBehavior } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  const puts = [];   // { body, at }
  let wsSend = null; // 广播注入口（canvasTabsChanged 测试面）
  await ctx.addInitScript((seed) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', seed);
  }, JSON.stringify(localTabs || { v: 2, tabs: [] }));
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await ctx.route('**/api/canvas-tabs', async (r) => {
    if (r.request().method() === 'PUT') {
      let body = null;
      try { body = JSON.parse(r.request().postData() || 'null'); } catch { /* ignore */ }
      puts.push({ body, at: Date.now() });
      if (putBehavior && putBehavior.status === 409) {
        return r.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'stale canvas tabs write (LWW)', archive: putBehavior.archive }) });
      }
      return r.fulfill({ json: { ok: true } });
    }
    if (serverArchive) return r.fulfill({ json: serverArchive });
    return r.fulfill({ status: 404, json: { error: 'no canvas tabs archive' } });
  });
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    wsSend = (msg) => ws.send(JSON.stringify(msg));
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(400);
  return { ctx, page, wsSend: () => wsSend, puts, errors };
}

const paneIds = (page) => page.evaluate(() =>
  [...document.querySelectorAll('.canvas-tab-pane')].map((p) => p.dataset.tabId));
const putTabIds = (puts) => puts.flatMap((p) => ((p.body && p.body.tabs) || []).map((t) => t.id));

// ── 场景 A：单设备关闭 → 服务端权威 → 第二设备刷新不复活 ──
{
  const localTabs = {
    v: 2, activeTabId: X, updatedAt: T0,
    tabs: [tab('plugins'), tab(X, { openedAt: T0 }), tab(Y, { openedAt: T0 })],
  };
  // A 机关闭 file:X 后的权威存档：无 X。
  const serverArchive = { v: 2, activeTabId: 'plugins', updatedAt: T_CLOSE, tabs: [tab('plugins'), tab(Y, { openedAt: T0 })] };
  const { page, puts, errors } = await boot({ localTabs, serverArchive });
  await sleep(1400); // 恢复 + hydration 闸放行 + 500ms 防抖 PUT 窗口全部走完

  const ids = await paneIds(page);
  ok('A1 已关标签不复活（刷新后无 file:X 页签）', !ids.includes(X), `panes=${ids.join(',')}`);
  ok('A2 存档在列标签照常恢复（plugins/file:Y 在）', ids.includes('plugins') && ids.includes(Y), `panes=${ids.join(',')}`);
  ok('A3 恢复 readFile 不给已关标签发（无回声复活载体）', await page.evaluate(() => true) && !(await paneIds(page)).includes(X));

  const pids = putTabIds(puts);
  ok('A4 恢复期/后续 PUT 不回写已关标签（无权威存档二次污染）', pids.length > 0 && !pids.includes(X), `putTabs=${pids.join(',')}`);
  const anyPut = puts.find((p) => p.body && Array.isArray(p.body.tabs));
  ok('D1 PUT 载荷带存档级 updatedAt 戳', !!anyPut && typeof anyPut.body.updatedAt === 'number');
  const yEntry = anyPut && anyPut.body.tabs.find((t) => t.id === Y);
  ok('D2 PUT 载荷 per-tab openedAt 随存档走', !!yEntry && typeof yEntry.openedAt === 'number' && yEntry.openedAt === T0);
  ok('E0 零页面错误', errors.length === 0, errors.join(' | '));
  await page.context().close();
}

// ── 场景 B：写穿广播 → 本端按戳收敛（他端开→补开；他端关→删除）──
{
  const { page, wsSend, errors } = await boot({
    serverArchive: { v: 2, activeTabId: 'plugins', updatedAt: T0, tabs: [tab('plugins')] },
  });
  await sleep(800);
  ok('B0 初始只有 plugins', (await paneIds(page)).join(',') === 'plugins', `panes=${(await paneIds(page)).join(',')}`);

  // 他端打开 file:Z（PUT 落盘 → 服务端广播）→ 本端补开，不等刷新。
  wsSend()({ type: 'canvasTabsChanged', v: 2, activeTabId: Z, updatedAt: Date.now(), tabs: [tab('plugins'), tab(Z, { openedAt: Date.now() - 1000 })] });
  await sleep(1200);
  ok('B1 他端打开 → 广播补开（file:Z 在）', (await paneIds(page)).includes(Z), `panes=${(await paneIds(page)).join(',')}`);

  // 他端关闭 file:Z（PUT 落盘 → 广播）→ 本端收敛删除（修「关了又复活」主刃）。
  wsSend()({ type: 'canvasTabsChanged', v: 2, activeTabId: 'plugins', updatedAt: Date.now(), tabs: [tab('plugins')] });
  await sleep(800); // closeTab 退场动画 finalize ≤200ms + 余量
  ok('B2 他端关闭 → 收敛删除（file:Z 不在，不等刷新）', !(await paneIds(page)).includes(Z), `panes=${(await paneIds(page)).join(',')}`);
  ok('E1 零页面错误', errors.length === 0, errors.join(' | '));
  await page.context().close();
}

// ── 场景 C：LWW 陈旧写落选（409 回权威存档 → 本端即时收敛）──
{
  const T_NEW = Date.now() - 5_000;
  const { page, puts, errors } = await boot({
    serverArchive: { v: 2, activeTabId: 'plugins', updatedAt: T0, tabs: [tab('plugins')] },
    // 本端任何 PUT 都落选（他端 T_NEW 已写走新存档：加了 file:W）。
    putBehavior: { status: 409, archive: { v: 2, activeTabId: 'plugins', updatedAt: T_NEW, tabs: [tab('plugins'), tab(W, { openedAt: T_NEW - 1000 })] } },
  });
  await sleep(1600); // hydration 结束 → hydrateDirty 重放 PUT → 409 → 收敛
  ok('C1 409 落选后按权威存档收敛（file:W 补开）', (await paneIds(page)).includes(W), `panes=${(await paneIds(page)).join(',')}`);
  ok('C2 409 路径确实发生过（PUT ≥1）', puts.length >= 1, `puts=${puts.length}`);
  ok('E2 零页面错误', errors.length === 0, errors.join(' | '));
  await page.context().close();
}

await browser.close();
server.close();
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);
