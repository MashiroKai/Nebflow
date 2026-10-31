// canvas-card-window.spec.mjs — UI 批⑧ canvas 单卡独立窗口验收（作者令 2026-10-21）
//
// 作者令四链路 spec 钉：
//   CW1 独立链 —— 卡头 toggle（#canvas-pop-btn 标题头 / .deck-card-pop 环卡头 /
//        .mobile-card-pop 手机卡头）点击 → 同源独立窗（?card-window=1
//        &path=…）弹出、真容渲染（readFile→fileContent→workspace-open-item→
//        renderFile 既有通路）、主窗页签 is-independent（toggle 同位换「合并」形）、
//        卡面 data-independent「独立中」浮标、占位不消失（tabCount 不变）；
//   CW2 内容同步 —— 主窗内容更新 → update 广播 → 卡窗重读文件 → 真容跟进；
//   CW3 合并链 + 幂等 —— toggle「合并」→ 卡窗关、标记清、状态回笼 = 弹出前快照；
//        重复弹出/重复合并均幂等（Map 判重），页数不增长；
//   CW4 关窗等价 —— 卡窗被外部关闭（page.close() = 用户 ×）→ 主窗 win.closed
//        轮询（600ms）走同一条 mergeCardWindow → 状态与 CW3 终态全等；
//   CW5 reduced-motion —— 直切降级：无 pop/merge 动画类，合并即时完成；
//   CW6 手机 375 —— canvas 页工具条 .mf-cv-pop（navController 腿；2026-10-30
//        重锚：dock 批换代 .mobile-card-pop 退役）：新标签页姿态
//        （window.open 无 features）、独立/合并同链路，标记与回笼一致。
//
// 断言通道 = window.__canvasDeck.state()（cardWindowMode / independent / tabCount /
// tabs / activeId）。文件内容经可变 mock 表（FILE_CONTENTS）+ WS pop.readFile 应答，
// 主窗/卡窗同源同内容 —— 内容同步链真读真渲。
//
// 运行：node tests/canvas-card-window.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8159);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}
const approx = (a, b, tol) => Math.abs(a - b) <= tol;

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

/* 可变文件表：主窗 workspace-open-item 直灌 + 卡窗 pop.readFile 应答同源取读。 */
const FILE_CONTENTS = new Map([
  ['/tmp/cw-fixture/alpha.md', { content: '# alpha\n\nalpha mock body v1\n', itemType: 'markdown' }],
  ['/tmp/cw-fixture/bravo.md', { content: '# bravo\n\nbravo mock body v1\n', itemType: 'markdown' }],
]);

async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 }, reducedMotion = false } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme, reducedMotion: reducedMotion ? 'reduce' : 'no-preference' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  /* 上下文级路由：主窗 + 独立卡窗共享同一套 API/WS mock（同源同内容）。 */
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
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
  // ≤768px 移动层接管（#activity-bar 隐藏属正常形态）——两态都等
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

async function openFile(page, absPath) {
  const rec = FILE_CONTENTS.get(absPath);
  const title = absPath.split('/').pop();
  await page.evaluate(({ absPath, title, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true },
    }));
  }, { absPath, title, content: rec.content, itemType: rec.itemType });
  // attached 口径：移动层画布走卡片形态（桌面 pane 恒 hidden），DOM 在即算开成
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

const stateOf = (page) => page.evaluate(() => window.__canvasDeck.state());
/** 轮询条件成立（默认 ≤4s）。 */
async function pollUntil(page, cond, ms = 4000) {
  const t0 = Date.now();
  let s = await stateOf(page);
  while (Date.now() - t0 < ms) {
    s = await stateOf(page);
    if (cond(s)) return s;
    await sleep(120);
  }
  return s;
}
/** 等一个由 window.open 产生的新页面。 */
async function waitPopup(ctx, trigger) {
  const p0 = ctx.pages().length;
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await trigger();
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  await popup.waitForSelector('body.card-window', { timeout: 15000 });
  await sleep(600);   // readFile 取读 + renderFile 真容
  void p0;
  return popup;
}
const paneText = (page, absPath) => page.evaluate((p) => {
  const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
  return pane ? pane.textContent : '';
}, absPath);


/* ── 批⑤（2026-10-31 Canvas 改造批） helpers：标题头 #canvas-pop-btn 退役——
   独立窗出口 = 卡头右键菜单「在独立窗口打开 / 合并回主窗」（canvas.js
   capsuleMenuActions 同语义，label 随 independent 态换形）。 */
async function clickHeaderPop(page) {
  await page.click('#canvas-panel .canvas-header', { button: 'right' });
  await page.waitForSelector('.canvas-tab-context-menu', { timeout: 5000 });
  await page.click('.canvas-tab-context-menu button:has-text("独立窗口"), .canvas-tab-context-menu button:has-text("合并回主窗")');
  await sleep(120);
}
const headerPopForm = (page) => page.evaluate(() => {
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  if (!hdr) return null;
  const r = hdr.getBoundingClientRect();
  hdr.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: r.x + 30, clientY: r.y + 12, button: 2 }));
  const menu = document.querySelector('.canvas-tab-context-menu');
  if (!menu) return null;
  const pop = [...menu.querySelectorAll('button')].find((b) => /独立窗口|合并回主窗/.test(b.textContent));
  const form = pop ? { label: pop.textContent.trim(), disabled: pop.classList.contains('disabled') } : null;
  menu.remove();
  return form;
});

const BRAVO = '/tmp/cw-fixture/bravo.md';
const ALPHA = '/tmp/cw-fixture/alpha.md';

/* ════════ CW1 独立链 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });
  await openFile(page, ALPHA);
  await openFile(page, BRAVO);            // bravo = 活动页签 → 标题头 toggle 即其形
  const s0 = await stateOf(page);
  const popup = await waitPopup(ctx, () => clickHeaderPop(page));
  const s1 = await stateOf(page);
  ok('CW1-1 独立窗弹出：同源 ?card-window=1&path=（真容路由）',
    popup.url().includes('card-window=1') && popup.url().includes(encodeURIComponent(BRAVO)),
    popup.url());
  ok('CW1-2 卡窗模式在挂：body.card-window + state.cardWindowMode',
    await popup.evaluate(() => document.body.classList.contains('card-window')) &&
    (await popup.evaluate(() => window.__canvasDeck.state().cardWindowMode)) === true);
  const ptxt = await paneText(popup, BRAVO);
  ok('CW1-3 卡窗真容：readFile 通路渲染文件内容（非骨架）', ptxt.includes('bravo mock body v1'),
    ptxt.slice(0, 60));
  ok('CW1-4 主窗占位不消失：tabCount 不变 + independent=[bravo]',
    s1.tabCount === s0.tabCount && s1.independent.length === 1 && s1.independent[0] === `file:${BRAVO}`,
    `n=${s1.tabCount} indep=${JSON.stringify(s1.independent)}`);
  const marks = await page.evaluate((p) => {
    const tab = document.querySelector(`.canvas-tab[data-tab-id="file:${p}"]`);
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
    // 件二排他批（2026-10-28）：角标渲染点 pane ::after → #canvas-slide ::after
    // （pane 可能是滚动容器，角标随内容漂出 header 行；slide 不滚恒钉行内）。
    const slide = document.getElementById('canvas-slide');
    return {
      tabIndep: !!(tab && tab.classList.contains('is-independent')),
      paneIndep: !!(pane && pane.hasAttribute('data-independent')),
      slideIndep: !!(slide && slide.hasAttribute('data-independent')),
      chip: slide ? getComputedStyle(slide, '::after').content : '',
    };
  }, BRAVO);
  const popForm1 = await headerPopForm(page);
  ok('CW1-5 独立中轻态：页签 is-independent + 卡面「独立中」浮标（slide 壳层件二批）+ 右键菜单换「合并」形（批⑤ 后 = 独立窗出口）',
    marks.tabIndep && marks.paneIndep && marks.slideIndep && marks.chip.includes('独立中') &&
    !!popForm1 && popForm1.label === '合并回主窗' && !popForm1.disabled,
    JSON.stringify({ ...marks, popForm1 }));
  /* toggle 同位换形（两形态）：切激活到未独立的 alpha → 标题头回「独立」形 */
  await page.evaluate((a) => { window.__canvasDeck.state(); }, ALPHA);
  await page.evaluate((a) => {
    document.querySelector(`.canvas-tab[data-tab-id="file:${a}"]`)?.click();
  }, ALPHA);
  await sleep(300);
  const hdrForm2 = await headerPopForm(page) || {};
  await page.evaluate((b) => {
    document.querySelector(`.canvas-tab[data-tab-id="file:${b}"]`)?.click();
  }, BRAVO);
  await sleep(200);
  ok('CW1-6 菜单项同位换形（批⑤ 出口迁移）：独立中=「合并回主窗」，未独立=「独立窗口打开」',
    hdrForm2.label === '在独立窗口打开' && !hdrForm2.disabled,
    JSON.stringify(hdrForm2));
  ok('R0 主文档零 JS 错误（CW1）', errors.length === 0, errors.slice(0, 2).join(' | '));

  /* ════════ CW2 内容同步（接 CW1 现场）════════ */
  FILE_CONTENTS.set(BRAVO, { content: '# bravo\n\nbravo mock body v2 UPDATED\n', itemType: 'markdown' });
  // 主窗带新内容重派（走 renderFile → notifyCardUpdate 广播 → 卡窗重读 mock 表）
  await page.evaluate((p) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + p, itemType: 'markdown', title: 'bravo.md', content: '# bravo\n\nbravo mock body v2 UPDATED\n', absPath: p, pinned: true },
    }));
  }, BRAVO);
  let cardTxt = '';
  for (let i = 0; i < 30 && !cardTxt.includes('v2 UPDATED'); i++) {
    await sleep(200);
    cardTxt = await paneText(popup, BRAVO);
  }
  ok('CW2 主窗更新 → 卡窗真容跟进（update 广播 + 重读）', cardTxt.includes('v2 UPDATED'),
    cardTxt.slice(0, 60));
  ok('R0 主文档零 JS 错误（CW2）', errors.length === 0, errors.slice(0, 2).join(' | '));

  /* ════════ CW3 合并链 + 幂等 ════════ */
  const sSnap = await stateOf(page);
  await clickHeaderPop(page);
  await sleep(500);
  const s2 = await pollUntil(page, (s) => s.independent.length === 0);
  const popupClosedFast = popup.isClosed();
  const markersGone = await page.evaluate((p) => {
    const tab = document.querySelector(`.canvas-tab[data-tab-id="file:${p}"]`);
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
    return !tab.classList.contains('is-independent') && !pane.hasAttribute('data-independent');
  }, BRAVO);
  const popForm3 = await headerPopForm(page);
  ok('CW3-1 合并：卡窗关闭 + 标记清 + 菜单项回「在独立窗口打开」形',
    popupClosedFast && s2.independent.length === 0 && markersGone &&
    !!popForm3 && popForm3.label === '在独立窗口打开' && !popForm3.disabled,
    `closed=${popupClosedFast} indep=${JSON.stringify(s2.independent)} marks=${markersGone} pop=${JSON.stringify(popForm3)}`);
  ok('CW3-2 状态回笼 = 弹出前（tabCount/activeId/页签集合全等）',
    s2.tabCount === sSnap.tabCount && s2.activeId === sSnap.activeId &&
    JSON.stringify(s2.tabs) === JSON.stringify(sSnap.tabs),
    `n=${s2.tabCount}/${sSnap.tabCount} active=${s2.activeId}`);
  const nPages1 = ctx.pages().length;
  // 幂等：再弹再合
  const popup2 = await waitPopup(ctx, () => clickHeaderPop(page));
  await sleep(300);
  // 在独立态重复点击 toggle = 幂等（直返，不新开窗）
  await clickHeaderPop(page);   // 合并
  await sleep(400);
  const s3 = await pollUntil(page, (s) => s.independent.length === 0);
  await clickHeaderPop(page);   // 未独立态重复合并语义 = 重弹（正常链）
  await sleep(600);
  const s4 = await stateOf(page);
  const popup3open = !!(s4.independent.length === 1);
  // 收尾合并，回到干净态
  const p3 = ctx.pages().find((pg) => pg !== page && !pg.isClosed() && pg.url().includes('card-window'));
  if (p3) { const pP = ctx.waitForEvent('page', { timeout: 1 }).catch(() => null); void pP; await clickHeaderPop(page); await sleep(400); }
  void popup2;
  const nPages2 = ctx.pages().length;
  ok('CW3-3 幂等：重复弹出/合并无窗泄漏（页数回落）+ 状态收敛',
    s3.independent.length === 0 && popup3open && ctx.pages().filter((pg) => !pg.isClosed()).length <= 2 &&
    nPages2 <= nPages1 + 1,
    `pages=${nPages1}→${nPages2} closed=${ctx.pages().filter((pg) => !pg.isClosed()).length}`);
  ok('R0 主文档零 JS 错误（CW3）', errors.length === 0, errors.slice(0, 2).join(' | '));

  /* ════════ CW4 关窗等价（用户直接关卡窗 = 合并）════════ */
  const popup4 = await waitPopup(ctx, () => clickHeaderPop(page));
  await sleep(400);
  const preClose = await stateOf(page);
  await popup4.close();                       // 用户 × 卡窗
  const s5 = await pollUntil(page, (s) => s.independent.length === 0, 5000);
  const bravoPaneAlive = await page.evaluate((p) =>
    !!document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`), BRAVO);
  ok('CW4-1 关窗等价：外部关闭卡窗 → 主窗 ≤5s 回笼（win.closed 轮询走同一合并路径）',
    s5.independent.length === 0 && preClose.independent.length === 1 && bravoPaneAlive,
    `indep ${JSON.stringify(preClose.independent)}→${JSON.stringify(s5.independent)} pane=${bravoPaneAlive}`);
  ok('CW4-2 终态 = CW3 快照（等价性全等）',
    s5.tabCount === sSnap.tabCount && s5.activeId === sSnap.activeId &&
    JSON.stringify(s5.tabs) === JSON.stringify(sSnap.tabs));
  ok('R0 主文档零 JS 错误（CW4）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ CW5 reduced-motion 直切 ════════ */
{
  const { ctx, page } = await boot({ colorScheme: 'light', reducedMotion: true });
  await openFile(page, ALPHA);
  await openFile(page, BRAVO);
  const popup = await waitPopup(ctx, () => clickHeaderPop(page));
  const animClass = await page.evaluate((p) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
    return pane.className;
  }, BRAVO);
  ok('CW5-1 RM 独立直切：卡面无 pop 动画类', !animClass.includes('card-pop-anim'), animClass);
  const t0 = Date.now();
  await clickHeaderPop(page);
  const s = await pollUntil(page, (x) => x.independent.length === 0, 2500);
  const paneCls = await page.evaluate((p) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
    return pane ? pane.className : '';
  }, BRAVO);
  ok('CW5-2 RM 合并直切：≤2.5s 收笼 + 无 merge 动画类',
    s.independent.length === 0 && popup.isClosed() && !paneCls.includes('card-merge-anim'),
    `dt=${Date.now() - t0}ms cls=${paneCls}`);
  await ctx.close();
}

/* ════════ CW6 手机 375（canvas 页工具条腿：新标签页姿态直切）════════ */
// 批②⑤⑥重锚 2026-10-31（Canvas 改造批）：手机 canvas 页工具条换代 = 左 ✕ +
// 计数 + 右堆叠视角钮（.g2-fab 液体玻璃）；「独立窗口」钮（.mf-cv-pop）随批⑤
// 退役——手机端零独立窗入口（桌面出口 = 卡头右键菜单，CW1-CW5 已覆盖链路）。
// 本腿改断言：新三件套在位 + 独立窗入口真退役 + 堆叠视角开合（批⑤ 顶部计数）。
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark', viewport: { width: 375, height: 812 } });
  const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  await openFile(page, ALPHA);
  await openFile(page, BRAVO);
  await page.waitForSelector('.mobile-page[data-page="canvas"]', { state: 'attached', timeout: 8000 });
  const onCanvas = await page.evaluate(() =>
    document.querySelector('.mobile-page[data-page="canvas"]')?.classList.contains('live'));
  if (!onCanvas) await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
  await page.waitForSelector('.mf-cv-close', { state: 'visible', timeout: 8000 });
  const chrome = await page.evaluate(() => {
    const q = (sel) => !!document.querySelector(sel);
    return {
      close: q('.mf-cv-close'), stack: q('.mf-cv-stack'),
      count: (document.querySelector('.mf-cv-count') || {}).textContent || '',
      popGone: !q('.mf-cv-pop') && !q('.mf-cv-prev') && !q('.mf-cv-next'),
      g2fab: document.querySelector('.mf-cv-close')?.classList.contains('g2-fab') === true &&
             document.querySelector('.mf-cv-stack')?.classList.contains('g2-fab') === true,
    };
  });
  ok('CW6-1 手机工具条三件套在位（批②：✕/计数/堆叠视角钮，g2-fab 液体玻璃）+ 独立窗/步进钮退役（批⑤）',
    mobile && chrome.close && chrome.stack && chrome.count === '2/2' && chrome.popGone && chrome.g2fab,
    `mobile=${mobile} ${JSON.stringify(chrome)}`);
  // 批⑤：堆叠态顶部显示标签数——开堆叠视角 ⇒ 「N 张文档」计数在顶行。
  await page.click('.mf-cv-stack');
  await page.waitForSelector('.mf-stack-view:not([hidden])', { timeout: 5000 });
  const stackView = await page.evaluate(() => ({
    count: (document.querySelector('.mf-stack-count') || {}).textContent || '',
    cards: document.querySelectorAll('.mf-stack-card').length,
    on: document.querySelectorAll('.mf-stack-card.on').length,
  }));
  ok('CW6-2 堆叠视角：顶部「N 张文档」计数 + 全卡列表 + 活动卡高亮（批⑤）',
    stackView.count === '2 张文档' && stackView.cards === 2 && stackView.on === 1,
    JSON.stringify(stackView));
  // 点卡换入 + 收面。
  await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.mf-stack-card')];
    (cards.find((c) => !c.classList.contains('on')) || cards[0]).click();
  });
  await sleep(400);
  const afterPick = await page.evaluate(() => ({
    stackHidden: document.querySelector('.mf-stack-view').hidden,
    count: (document.querySelector('.mf-cv-count') || {}).textContent || '',
  }));
  ok('CW6-3 堆叠视角点卡换入并收面，工具条计数跟随（换入 alpha = 首卡 ⇒ 1/2）', afterPick.stackHidden && afterPick.count === '1/2',
    JSON.stringify(afterPick));
  ok('R0 主文档零 JS 错误（CW6）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-card-window: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
