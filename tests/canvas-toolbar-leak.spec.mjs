// canvas-toolbar-leak.spec.mjs — canvas 工具条族「不许串到其他页面」验收钉
//
// 作者报障（2026-11-07 14:36，手机实拍 IMG_1805）：「canvas 的图标/按钮串到其他
// 页面上了 … 主会话聊天页上浮着 canvas 那套按钮 … 也就是说 canvas 面板不在了，
// 但它的工具条还在显示」。
//
// ── 根因（本 spec 的改前红基线即判据，非推测）────────────────────────────
// 泄漏成员 = `#canvas-capsule-ring`（canvas.js:4156-4161 挂 document.body，
// `.capsule-ring` position:fixed / z-246，split.css:1392-1397）。它的显隐由
// canvas.js `syncCoarseRow()`（:4544-4568，想开 = coarse ∧ !mobile-layer ∧
// !card-window ∧ !deck.open ∧ tabs≥2 ∧ isCanvasOpen）**在若干触发点**上判定：
// 触发面只有 renderCanvasHeader 出口 / resize / orientationchange / 能力断点
// 变更 / `.canvas-panel` class 变更（:4634-4641）—— `body` 上的 `canvas-open`
// 与 `mobile-layer` 两枚事实变化，一个都不在触发面里。于是：
//   腿 R1（关面板）：closeCanvas()（canvas.js:494-518）只摘 body 类 + 内联
//     flex，面板 class 不变 ⇒ 环不收 ⇒ 面板没了、3 枚胶囊仍 opacity=1 留在视口；
//   腿 R2（手机层落位）：手机层类是异步激活的（mobile/bootstrap.js activate
//     内多枚动态 import 完成后才 add），首屏「页签恢复 → openCanvas →
//     renderCanvasHeader → syncCoarseRow」全程早于它 ⇒ 判出「非手机层」并开环，
//     此后无人重判 ⇒ 手机上任何页面都浮着这排胶囊（作者实拍态）。
//
// ── 修法（本 spec 锁定的行为契约）──────────────────────────────────────
// 显隐与 canvas 面挂载同源：body 级画布浮件族守卫（split.css:1694-1710）
// 补入 `#canvas-capsule-ring`（`body.mobile-layer` ⇒ display:none），并新增
// `body:not(.canvas-open) #canvas-capsule-ring { display:none }` —— 判据与
// `body.canvas-open #canvas-panel`（split.css:626）同一枚，不是 z-index 遮盖，
// 也不依赖任何 JS 事后同步。
//
// ── 断言（三态 × 亮暗 × 375×812，全部二值、全部实测几何）─────────────────
//   S1 主会话页：canvas 工具条族**完全不在场**（有效可见 ∧ 在视口内的成员数 = 0；
//      每个成员给 getBoundingClientRect 读数）；
//   S2 canvas 面：工具条族正常在场（环/签条/工具钮/文档 chrome 至少各一名在位）；
//   S3 从 canvas 面回主会话后：工具条族再次全部退场（零残影）；
//   R1/R2 泄漏腿（改前即红）：关面板后 / 手机层落位后 —— 族成员必须为 0；
//   G  同类泄漏巡检：缩放条/工具簇/图层钮/note·ask 按钮/lightbox 角标在主会话页
//      一律不在场。
//
// 运行：node tests/canvas-toolbar-leak.spec.mjs
// 可选 env：PORT（默认 8399）、LEAK_SHOTS_DIR（截图目录）
// 隔离性：本 spec 自起静态服务器（只读本仓 web/ 树）+ 真实 Chromium，
// 全程零宿主控制台请求（不连 :6138、不读写宿主数据根）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8399);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.LEAK_SHOTS_DIR || resolve(HERE, '../evidence/20261107_canvas-toolbar-leak/shots');
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 族清单（工具条族 = body 级画布浮件 + 手机 canvas 页 chrome + 文档 chrome）──
const FAMILY = [
  '#canvas-capsule-ring', '.cr-capsule',                    // 桌面/触屏胶囊环（body 级 fixed）
  '#deck-fab', '#canvas-head-close',                        // 图层(堆叠)钮 / 关签钮（body 级 fixed）
  '.mf-strip', '.mf-strip-chip', '#canvas-refback-btn',     // 手机 canvas 页签条
  '.mf-cv-toolbar', '.mf-cv-close', '.mf-cv-stack', '.mf-dots',
  '.canvas-zoom-bar', '.canvas-source-toggle',              // 文档缩放条 / 工具簇（viewers）
  '.canvas-ref-select', '.canvas-find-toggle',
];
const OTHER = ['.ask-fab', '.note-fab', '.nf-lightbox', '.canvas-tooltip'];

// 在视口内 ∧ 有效可见（不靠 z-index 推断：沿祖先链查 display/visibility，再查
// 自身 opacity 与 rect 是否真的落在视口里 —— 「在可视区」二值断言）
const PROBE = ({ family, other }) => {
  const chainHidden = (el) => {
    for (let n = el; n; n = n.parentElement) {
      const c = getComputedStyle(n);
      if (c.display === 'none') return 'display:none@' + (n.id || (typeof n.className === 'string' ? n.className : n.tagName));
      if (c.visibility === 'hidden') return 'visibility:hidden@' + (n.id || (typeof n.className === 'string' ? n.className : n.tagName));
    }
    return '';
  };
  const read = (sel) => {
    const out = [];
    for (const el of document.querySelectorAll(sel)) {
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      const hiddenBy = chainHidden(el);
      const inViewport = r.width > 1 && r.height > 1 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
      const paint = !hiddenBy && Number(cs.opacity) > 0.05 && Number(cs.visibility) !== 0;
      out.push({
        sel, hiddenBy, inViewport, op: cs.opacity,
        // 「在场」= 真的画在视口里：无祖先隐藏 ∧ opacity>0.05 ∧ rect 落在视口内
        present: !hiddenBy && Number(cs.opacity) > 0.05 && inViewport,
        r: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
        txt: (el.textContent || '').trim().slice(0, 12),
        paint,
      });
    }
    return out;
  };
  const gather = (list) => list.flatMap(read);
  return {
    bodyCls: document.body.className,
    pages: [...document.querySelectorAll('.mobile-page.live')].map((p) => p.dataset.page),
    family: gather(family),
    other: gather(other),
  };
};

let pass = 0; const fails = [];
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fails.push(name + (detail ? ' — ' + detail : ''));
}
const present = (probe, list) => (list ? probe.other : probe.family).filter((o) => o.present);
const brief = (items) => items.slice(0, 4).map((o) => `${o.sel}@[${o.r}]`).join(' ');

const HTML_DOC = '<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:system-ui;padding:24px;line-height:1.7}</style></head><body><h1>巡检报告</h1><p>' + '正文段落。'.repeat(200) + '</p></body></html>';
const ITEMS = [
  { id: 'file:/tmp/leak/a.html', itemType: 'html', title: '巡检报告', content: HTML_DOC, absPath: '/tmp/leak/a.html' },
  { id: 'file:/tmp/leak/b.html', itemType: 'html', title: '插件', content: HTML_DOC, absPath: '/tmp/leak/b.html' },
];

async function boot(browser, { viewport, colorScheme, mobile }) {
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile, isMobile: mobile, locale: 'zh-CN' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    for (const k of ['nebflow_canvas_tabs', 'neblink_canvas_tabs', 'stand_canvas_tabs']) {
      localStorage.setItem(k, JSON.stringify({ v: 2, tabs: [] }));
    }
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/agents/**', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await page.route('**/api/**', (r) => {
    const u = decodeURIComponent(r.request().url());
    if (u.includes('/api/nf-file')) return r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: HTML_DOC });
    return r.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'leak-root', name: 'root', agentName: 'Spider' }], folders: [], activeId: 'leak-root' }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId && s.ws && s.ws.readyState === 1;
  }, null, { timeout: 20000 });
  await sleep(1200);
  return { ctx, page, errors };
}

async function openItems(page) {
  await page.evaluate((list) => {
    for (const it of list) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...it, pinned: true } }));
  }, ITEMS);
  for (const it of ITEMS) await page.waitForSelector(`.canvas-tab-pane[data-tab-id="${it.id}"]`, { timeout: 15000, state: 'attached' });
  await sleep(1200);
}
const probe = (page) => page.evaluate(PROBE, { family: FAMILY, other: OTHER });

const browser = await chromium.launch();
try {
  /* ═════════ S1/S2/S3：手机层 375×812 × 亮暗 —— 三态主验收 ═════════ */
  for (const colorScheme of ['dark', 'light']) {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 375, height: 812 }, colorScheme, mobile: true });
    await openItems(page);

    /* S1 主会话页（未进 canvas 面）：工具条族必须完全不在场 */
    await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
    await sleep(900);
    let p = await probe(page);
    ok(`S1[${colorScheme}] 主会话页 canvas 工具条族零在场`, present(p).length === 0, present(p).length ? brief(present(p)) : `members=${p.family.length}`);
    ok(`S1[${colorScheme}] 主会话页同类浮件（ask/note/lightbox）零在场`, present(p, true).length === 0, brief(present(p, true)));
    await page.screenshot({ path: join(SHOTS_DIR, `s1-chat-${colorScheme}.png`) });

    /* S2 canvas 面：工具条族正常在场 */
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
    await sleep(1100);
    p = await probe(page);
    const inCanvas = present(p);
    const haveChip = inCanvas.some((o) => o.sel === '.mf-strip-chip');
    const haveTool = inCanvas.some((o) => o.sel === '.mf-cv-stack');
    const haveChrome = inCanvas.some((o) => o.sel === '.canvas-zoom-bar');
    ok(`S2[${colorScheme}] canvas 面 签条/工具钮/文档 chrome 均在位`, haveChip && haveTool && haveChrome,
      `chip=${haveChip} stack=${haveTool} zoom=${haveChrome} live=${p.pages.join(',')}`);
    await page.screenshot({ path: join(SHOTS_DIR, `s2-canvas-${colorScheme}.png`) });

    /* S3 从 canvas 面回主会话（真实 UI 路径 = dock 对话键）：工具条族必须消失 */
    await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
    await sleep(1100);
    p = await probe(page);
    ok(`S3[${colorScheme}] 回主会话后 canvas 工具条族零残影`, present(p).length === 0,
      present(p).length ? brief(present(p)) : `live=${p.pages.join(',') || '(chat 根)'}`);
    await page.screenshot({ path: join(SHOTS_DIR, `s3-return-${colorScheme}.png`) });

    /* S4 其它页面（消息/工作台/联系人）：作者报障「串到其他页面」逐页对账 */
    const strays = [];
    for (const pageId of ['messages', 'workbench', 'contacts']) {
      await page.locator(`#mobile-dock .dock-key[data-dock="${pageId}"]`).click();
      await sleep(800);
      const q = await probe(page);
      if (present(q).length) strays.push(`${pageId}:${brief(present(q))}`);
    }
    ok(`S4[${colorScheme}] 消息/工作台/联系人页 canvas 工具条族零在场`, strays.length === 0, strays.join(' | '));

    ok(`S1-S3[${colorScheme}] 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }

  /* ═════════ R1：泄漏腿①—— 触屏桌面腿关面板后（改前红）═════════════════ */
  {
    const { ctx, page } = await boot(browser, { viewport: { width: 900, height: 700 }, colorScheme: 'dark', mobile: true });
    await openItems(page);
    let p = await probe(page);
    ok('R1a 面板开（粗指针常驻横向档在位）', present(p).some((o) => o.sel === '.cr-capsule'), brief(present(p)));
    await page.evaluate(async () => { const m = await import('/js/canvas.js'); m.closeCanvas(); });
    await sleep(1100);
    p = await probe(page);
    const leaked = present(p).filter((o) => o.sel === '.cr-capsule' || o.sel === '#canvas-capsule-ring');
    ok('R1b 关面板后胶囊环零在场（body 无 canvas-open）', leaked.length === 0 && !p.bodyCls.includes('canvas-open'),
      `body="${p.bodyCls.slice(0, 40)}" leaked=${brief(leaked)}`);
    await page.screenshot({ path: join(SHOTS_DIR, 'r1-closed.png') });
    await ctx.close();
  }

  /* ═════════ R2：泄漏腿②—— 手机层落位（异步激活）后环层零在场（改前红）════ */
  {
    const { ctx, page } = await boot(browser, { viewport: { width: 900, height: 700 }, colorScheme: 'dark', mobile: true });
    await openItems(page);
    // 复刻手机首屏时序：先在「非手机层」把环判开，再让 mobile-layer 落位（无 resize）
    await page.evaluate(() => { document.body.classList.remove('mobile-layer'); });
    await page.evaluate(() => { window.dispatchEvent(new Event('resize')); });
    await sleep(500);
    await page.evaluate(() => { document.body.classList.add('mobile-layer'); });
    await sleep(800);
    const p = await probe(page);
    const leaked = present(p).filter((o) => o.sel === '.cr-capsule');
    ok('R2 手机层落位后胶囊环零在场（异时机/无 resize 亦然）', leaked.length === 0 && p.bodyCls.includes('mobile-layer'),
      `body="${p.bodyCls.slice(0, 50)}" leaked=${brief(leaked)}`);
    await page.screenshot({ path: join(SHOTS_DIR, 'r2-mobile-late-class.png') });
    await ctx.close();
  }

  /* ═════════ G：同类巡检 —— 触屏桌面腿关面板后其它固定浮件同源退场 ═════════ */
  {
    const { ctx, page } = await boot(browser, { viewport: { width: 900, height: 700 }, colorScheme: 'light', mobile: true });
    await openItems(page);
    await page.evaluate(async () => { const m = await import('/js/canvas.js'); m.closeCanvas(); });
    await sleep(900);
    const p = await probe(page);
    const stray = present(p);
    ok('G 关面板后全族（环/图层/关签/缩放/工具簇/ask/note/lightbox）零在场', stray.length === 0, brief(stray));
    await ctx.close();
  }
} finally {
  await browser.close();
  server.close();
}

console.log(`\n通过 ${pass} 条 / 失败 ${fails.length} 条`);
if (fails.length) { console.log('失败项：\n- ' + fails.join('\n- ')); process.exitCode = 1; }
