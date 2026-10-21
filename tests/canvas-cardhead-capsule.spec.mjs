// canvas-cardhead-capsule.spec.mjs — UI 批⑨ 卡头毛玻璃胶囊 + 手势集验收（作者令 2026-10-21）
//
// 作者令 A/B/C/D 四链路 spec 钉：
//   CC0 胶囊形态 —— .canvas-header 浮层胶囊：absolute top 10 居中、border-radius
//        999px、backdrop blur 玻璃、fit-content（< 面板宽）；#canvas-content 顶满
//        全高（rect.top === slide top，内容不再为 header 预留）；.scroll-under-glass
//        裙边在挂（::after 24px + pointer-events none）；穿底 = 内容 pane 从面板顶
//        铺起（pane top ≤ 胶囊 top，文字可进胶囊区）。
//   CC1 按压触感 —— 按住胶囊 → .is-pressed（computed transform scale < 1），
//        松手即清；未动松手 = 点击界内零动作（页数/激活不变）。
//   CC2 换卡链 —— 按住下滑 70px 释放 = 下一张（deckBuildRing 同环同向：
//        dy>0=+1，deck 同语义）；上滑 70px = 上一张（环绕）。
//   CC3 关卡链 —— 按住左滑 90px 释放 = closeTab（deck 划走关闭同语义），
//        tabCount −1。
//   CC4 引用链 —— 胶囊右键 = 既有 canvas-tab-context-menu（「引用当前页」在挂；
//        点击后 #attachment-preview 出引用 chip）；deck 开时同菜单追加
//        「关闭/关闭其他」三项（stopPropagation 不与面板菜单双弹）。
//   CC5 分区（滚动冲突消解）—— 手势只绑胶囊：胶囊竖滑换卡前后 pane scrollTop
//        不变；内容区滚轮正常滚 pane（零劫持）；胶囊 touch-action:none。
//   CC6 手机 375 —— 无按压前置：卡头直滑上下=换文档卡（docTabs 序）、左右=关卡
//        （closeCard）；长按 450ms → is-longpress 触感 + .mobile-card-ref-btn
//        浮出，点击 → 引用 chip 进 #attachment-preview 且钮收；外部滚动即收；
//        底缘带上滑收层保留（卡头带不再收层 —— ⑨ 语义取代）。
//
// 断言通道 = window.__canvasDeck.state()（open/activeId/tabCount/tabs）+ DOM 计算
// 样式 + 合成 PointerEvent（手机腿）。桌面拖拽走真实 mouse（pointer 事件全链）。
//
// 运行：node tests/canvas-cardhead-capsule.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8166);
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

/* 可变文件表：workspace-open-item 直灌（主窗/移动层同通路）。bravo 长文（140 行）
 * 供 CC0-5 穿底 / CC5 分区滚动。 */
const TALL = '# bravo\n\n' + Array.from({ length: 140 }, (_, i) => `bravo 行 ${i + 1}：滚动穿底样本文本`).join('\n') + '\n';
const FILE_CONTENTS = new Map([
  ['/tmp/ch-fixture/alpha.md', { content: '# alpha\n\nalpha mock body v1\n', itemType: 'markdown' }],
  ['/tmp/ch-fixture/bravo.md', { content: TALL, itemType: 'markdown' }],
]);

async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
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
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

const stateOf = (page) => page.evaluate(() => window.__canvasDeck.state());
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

/** 胶囊标题区中心（避开右缘 pop 钮：取 x=40% 处）。 */
async function capsulePoint(page) {
  return page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    return { x: r.left + r.width * 0.4, y: r.top + r.height / 2, rect: { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height } };
  });
}

/** 真实 mouse 拖拽胶囊（pointer 事件全链：down → 步进 move → up）。 */
async function dragCapsule(page, ddx, ddy, steps = 10) {
  const p = await capsulePoint(page);
  await page.mouse.move(p.x, p.y);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) await page.mouse.move(p.x + (ddx * i) / steps, p.y + (ddy * i) / steps);
  await page.mouse.up();
  await sleep(150);
}

const headerInfo = (page) => page.evaluate(() => {
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  const cs = getComputedStyle(hdr);
  const after = getComputedStyle(hdr, '::after');
  const slide = document.getElementById('canvas-slide').getBoundingClientRect();
  const content = document.getElementById('canvas-content').getBoundingClientRect();
  const pane = document.querySelector('.canvas-tab-pane.active');
  const pr = pane ? pane.getBoundingClientRect() : null;
  return {
    position: cs.position, radius: cs.borderRadius, blur: cs.backdropFilter, top: cs.top,
    hClass: hdr.className,
    width: hdr.getBoundingClientRect().width,
    slideW: slide.width, slideL: slide.left, slideR: slide.right, slideT: slide.top,
    hdrL: hdr.getBoundingClientRect().left, hdrT: hdr.getBoundingClientRect().top, hdrB: hdr.getBoundingClientRect().bottom,
    contentT: content.top, contentB: content.bottom,
    paneT: pr ? pr.top : null,
    skirtH: after.height, skirtPtr: after.pointerEvents,
    touchAction: cs.touchAction,
  };
});

/* ════════ 桌面腿：CC0 形态 / CC1 按压 / CC2 换卡 / CC5 分区 / CC4 引用 / CC3 关卡 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });
  await openFile(page, '/tmp/ch-fixture/alpha.md');
  await openFile(page, '/tmp/ch-fixture/bravo.md');      // bravo 活动页签；ring=[bravo, alpha]
  // 关掉 boot 自带面板页签（plugins），换卡环只剩两张文件卡 —— 期望值确定化
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const s0 = await stateOf(page);
  ok('D0 前置：双文件页签开，bravo 活动', s0.tabCount === 2 && s0.activeId === 'file:/tmp/ch-fixture/bravo.md',
    `n=${s0.tabCount} active=${s0.activeId}`);

  /* 换卡期望 = 环邻位（页签条 DOM 序 = tabs Map 序 = deckBuildRing 源序） */
  const ringNeighbor = (dir) => page.evaluate((d) => {
    const ids = [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')].map((el) => el.dataset.tabId);
    const cur = window.__canvasDeck.state().activeId;
    const i = ids.indexOf(cur);
    return ids[(i + d + ids.length) % ids.length];
  }, dir);
  const nextOf = await ringNeighbor(1);
  const prevOf = await ringNeighbor(-1);

  /* ── CC0 胶囊形态 ── */
  const h = await headerInfo(page);
  ok('CC0-1 胶囊定位：absolute + top 10px + 圆角 999px',
    h.position === 'absolute' && h.top === '10px' && (h.radius === '999px' || h.radius === '9999px'), `${h.position}/${h.top}/${h.radius}`);
  ok('CC0-2 毛玻璃：backdrop blur 在挂 + .scroll-under-glass 类在挂',
    h.blur !== 'none' && h.hClass.includes('scroll-under-glass'), `${h.blur}`);
  ok('CC0-3 fit-content 胶囊：宽 < 面板宽 + 水平居中（±6px）',
    h.width < h.slideW - 40 && approx(h.hdrL - h.slideL, h.slideR - (h.hdrL + h.width), 6),
    `w=${h.width.toFixed(0)} slide=${h.slideW.toFixed(0)} off=${(h.hdrL - h.slideL).toFixed(1)}/${(h.slideR - h.hdrL - h.width).toFixed(1)}`);
  ok('CC0-4 内容全高：#canvas-content 顶贴 slide 顶（不再为 header 预留）',
    approx(h.contentT, h.slideT, 1), `content.top=${h.contentT.toFixed(1)} slide.top=${h.slideT.toFixed(1)}`);
  ok('CC0-5 穿底结构：内容 pane 顶 ≤ 胶囊顶（文字可进胶囊区）',
    h.paneT !== null && h.paneT <= h.hdrT + 1, `pane.top=${h.paneT?.toFixed(1)} hdr.top=${h.hdrT.toFixed(1)}`);
  ok('CC0-6 裙边在挂：::after 高 24px + pointer-events none（穿底渐隐载体）',
    h.skirtH === '24px' && h.skirtPtr === 'none', `h=${h.skirtH} pe=${h.skirtPtr}`);
  ok('CC0-7 手势面分区：胶囊 touch-action:none（不劫持内容滚动）', h.touchAction === 'none', h.touchAction);

  /* ── CC1 按压触感 ── */
  const p1 = await capsulePoint(page);
  await page.mouse.move(p1.x, p1.y);
  await page.mouse.down();
  await sleep(120);
  const pressed = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const m = new DOMMatrixReadOnly(getComputedStyle(hdr).transform === 'none' ? undefined : getComputedStyle(hdr).transform);
    return { cls: hdr.classList.contains('is-pressed'), scale: m.a };
  });
  await page.mouse.up();
  await sleep(120);
  const released = await page.evaluate(() => !document.querySelector('#canvas-panel .canvas-header').classList.contains('is-pressed'));
  ok('CC1-1 按住 = 按压形（.is-pressed + scale<1）', pressed.cls && pressed.scale < 0.999, `cls=${pressed.cls} scale=${pressed.scale.toFixed(3)}`);
  ok('CC1-2 松手即清 + 点击界内零动作（页数/激活不变）',
    released && (await stateOf(page)).tabCount === 2 && (await stateOf(page)).activeId === s0.activeId);

  /* ── CC2 换卡链（下滑=下一张 / 上滑=上一张，deck 同环同向）── */
  await dragCapsule(page, 0, 70);
  const sDown = await pollUntil(page, (x) => x.activeId === nextOf);
  ok('CC2-1 下滑 70px 释放 = 下一张（ring 邻位 +1，deck dy>0 同向）',
    sDown.activeId === nextOf, `active=${sDown.activeId} expect=${nextOf}`);
  const prevOf2 = await ringNeighbor(-1);              // 换卡后重算（激活已易主）
  await dragCapsule(page, 0, -70);
  const sUp = await pollUntil(page, (x) => x.activeId === prevOf2);
  ok('CC2-2 上滑 70px 释放 = 上一张（ring 环绕 −1）',
    sUp.activeId === prevOf2, `active=${sUp.activeId} expect=${prevOf2}`);

  /* ── CC5 分区：胶囊手势不滚动内容；内容滚轮正常 ── */
  const scrolled = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const scrollers = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
    if (scrollers.length) { scrollers[0].scrollTop = 40; return scrollers[0].scrollTop; }
    return -1;
  });
  ok('CC5-0 前置：bravo 长文可滚（scroller scrollTop=40）', scrolled === 40, `scrollTop=${scrolled}`);
  const paneBox = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const r = pane.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: Math.min(r.top + 220, r.bottom - 40) };
  });
  await page.mouse.move(paneBox.x, paneBox.y);
  await page.mouse.wheel(0, 160);
  await sleep(250);
  const afterWheel = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const sc = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
    return sc.length ? sc[0].scrollTop : -1;
  });
  ok('CC5-1 内容区滚轮正常滚 pane（零劫持，scrollTop 40→>40）', afterWheel > 40, `scrollTop=${afterWheel}`);
  await dragCapsule(page, 0, 30);                        // 次阈竖拖（轴锁成立但 <48 换卡阈）
  const sPart = await stateOf(page);
  const scrollKept = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const sc = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
    return sc.length ? sc[0].scrollTop : -1;
  });
  ok('CC5-2 胶囊手势零滚动劫持：次阈竖拖后激活不变 + pane scrollTop 不变',
    sPart.activeId === sUp.activeId && scrollKept === afterWheel, `active=${sPart.activeId} scroll=${scrollKept} (was ${afterWheel})`);

  /* ── CC4 引用链（右键菜单）── */
  const p4 = await capsulePoint(page);
  await page.mouse.click(p4.x, p4.y, { button: 'right' });
  await sleep(200);
  const menu1 = await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    return m ? { text: m.textContent, n: m.querySelectorAll('button').length } : null;
  });
  ok('CC4-1 胶囊右键 = 既有引用菜单（.canvas-tab-context-menu + 引用当前页）',
    !!menu1 && menu1.text.includes('引用当前页'), menu1 ? `btn=${menu1.n}` : 'no menu');
  await page.evaluate(() => { const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent.includes('引用当前页')); if (b) b.click(); });
  await sleep(250);
  const chip1 = await page.evaluate(() => {
    const att = document.getElementById('attachment-preview');
    return att ? att.children.length : -1;
  });
  ok('CC4-2 引用当前页点击 → #attachment-preview 出引用 chip（消息引用机制形态复用）', chip1 > 0, `children=${chip1}`);

  // deck 开：胶囊仍在面板顶层（deck 卡不盖顶带）—— 右键 = 同一张菜单三项
  //（引用/关闭/关闭其他，与面板级菜单同款 actions；单菜不双弹）
  await page.click('#deck-fab');
  await sleep(700);
  const sDeck = await stateOf(page);
  ok('CC4-3 前置：deck 开', sDeck.open && sDeck.M >= 2, `open=${sDeck.open} M=${sDeck.M}`);
  const p4b = await capsulePoint(page);
  await page.mouse.click(p4b.x, p4b.y, { button: 'right' });
  await sleep(200);
  const menu2 = await page.evaluate(() => {
    const ms = document.querySelectorAll('.canvas-tab-context-menu');
    const m = ms[ms.length - 1];
    return m ? { count: ms.length, text: m.textContent } : null;
  });
  ok('CC4-4 deck 态胶囊右键：单菜单 + 三项（引用当前页/关闭/关闭其他，stopPropagation 不双弹）',
    !!menu2 && menu2.count === 1 && menu2.text.includes('引用当前页') && menu2.text.includes('关闭其他'),
    menu2 ? `menus=${menu2.count} text=${menu2.text.slice(0, 40)}` : 'no menu');
  await page.keyboard.press('Escape');
  await sleep(400);

  /* ── CC3 关卡链（左滑 = closeTab）── */
  const nBefore = (await stateOf(page)).tabCount;
  await dragCapsule(page, -90, 0);
  const sClose = await pollUntil(page, (x) => x.tabCount === nBefore - 1);
  ok('CC3-1 左滑 90px 释放 = 关卡（closeTab 共核，tabCount −1 且落邻位）',
    sClose.tabCount === nBefore - 1, `n=${sClose.tabCount} (was ${nBefore}) active=${sClose.activeId}`);
  ok('R0 主文档零 JS 错误（桌面腿）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 手机腿 375：CC6 直滑换卡/关卡 + 长按引用钮 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark', viewport: { width: 375, height: 812 } });
  const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  await openFile(page, '/tmp/ch-fixture/alpha.md');
  await openFile(page, '/tmp/ch-fixture/bravo.md');
  // 关掉 boot 自带面板页签（plugins）—— 文档卡环确定化（docTabs 本就滤面板 pane）
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  await page.evaluate(() => {
    const key = document.querySelector('.mh-dd[data-dd="right"] [data-face="canvas"]');
    if (key) key.click();
  });
  await sleep(900);
  await page.waitForSelector('.mobile-card-head', { timeout: 8000 });
  const headInfo = await page.evaluate(() => {
    const head = document.querySelector('.mobile-card-head');
    const pill = head.closest('.canvas-header');
    const cs = getComputedStyle(pill);
    const r = head.getBoundingClientRect();
    return { title: head.querySelector('.mobile-card-title').textContent, touch: cs.touchAction,
      x: r.left + r.width * 0.35, y: r.top + r.height / 2, pillPos: cs.position, pillRadius: cs.borderRadius };
  });
  ok('CC6-0 前置：手机卡片层 + 卡头胶囊（通栏变体 + touch-action none）',
    mobile && headInfo.touch === 'none' && headInfo.pillRadius === '999px',
    `title=${headInfo.title} touch=${headInfo.touch} r=${headInfo.pillRadius}`);

  /** 合成 touch PointerEvent 序列（down → 步进 move → up）。 */
  async function swipe(x, y, dx, dy, steps = 8) {
    await page.dispatchEvent('.mobile-card-head', 'pointerdown',
      { pointerId: 7, pointerType: 'touch', isPrimary: true, button: 0, buttons: 1, clientX: x, clientY: y, bubbles: true });
    for (let i = 1; i <= steps; i++) {
      await page.dispatchEvent('.mobile-card-head', 'pointermove',
        { pointerId: 7, pointerType: 'touch', isPrimary: true, button: 0, buttons: 1, clientX: x + (dx * i) / steps, clientY: y + (dy * i) / steps, bubbles: true });
      await sleep(16);
    }
    await page.dispatchEvent('.mobile-card-head', 'pointerup',
      { pointerId: 7, pointerType: 'touch', isPrimary: true, button: 0, buttons: 0, clientX: x + dx, clientY: y + dy, bubbles: true });
    await sleep(300);
  }

  const titleOf = () => page.evaluate(() => document.querySelector('.mobile-card-title')?.textContent || '');

  // 直滑下滑 = 下一张（docTabs=[alpha, bravo]，bravo 活动 → alpha）
  await swipe(headInfo.x, headInfo.y, 0, 70);
  const t1 = await titleOf();
  ok('CC6-1 直滑下滑 = 下一张文档卡（无按压前置，bravo→alpha）', t1 === 'alpha.md', `title=${t1}`);
  // 直滑上滑 = 上一张（alpha → bravo 环绕）
  await swipe(headInfo.x, headInfo.y, 0, -70);
  const t2 = await titleOf();
  ok('CC6-2 直滑上滑 = 上一张（alpha→bravo）', t2 === 'bravo.md', `title=${t2}`);
  // 直滑左 = 关卡（closeCard，tabCount 2→1）
  const n0 = (await stateOf(page)).tabCount;
  await swipe(headInfo.x, headInfo.y, -90, 0);
  await sleep(400);
  const s6 = await pollUntil(page, (x) => x.tabCount === 1);
  ok('CC6-3 直滑左 = 关卡（tabCount 2→1）', n0 === 2 && s6.tabCount === 1, `n=${s6.tabCount}`);

  // 长按 450ms → 触感 + 引用钮浮出；点击 → 引用 chip + 钮收
  const lp = await page.evaluate(() => {
    const head = document.querySelector('.mobile-card-head');
    const r = head.getBoundingClientRect();
    return { x: r.left + r.width * 0.35, y: r.top + r.height / 2 };
  });
  await page.dispatchEvent('.mobile-card-head', 'pointerdown',
    { pointerId: 9, pointerType: 'touch', isPrimary: true, button: 0, buttons: 1, clientX: lp.x, clientY: lp.y, bubbles: true });
  await sleep(700);                                       // > 450ms 长按阈
  const lpState = await page.evaluate(() => {
    const head = document.querySelector('.mobile-card-head');
    const pill = head.closest('.canvas-header');
    const btn = document.querySelector('.mobile-card-ref-btn');
    const m = new DOMMatrixReadOnly(getComputedStyle(pill).transform === 'none' ? undefined : getComputedStyle(pill).transform);
    return { feel: head.classList.contains('is-longpress') || pill.classList.contains('is-longpress'),
      scale: m.a, btn: !!btn, btnText: btn ? btn.textContent : '' };
  });
  ok('CC6-4 长按触感 + 引用钮浮出（is-longpress + .mobile-card-ref-btn）',
    lpState.feel && lpState.scale < 0.999 && lpState.btn && lpState.btnText.length > 0,
    `feel=${lpState.feel} scale=${lpState.scale.toFixed(3)} btn=${lpState.btn}(${lpState.btnText})`);
  await page.evaluate(() => document.querySelector('.mobile-card-ref-btn').click());
  await sleep(300);
  const afterRef = await page.evaluate(() => {
    const att = document.getElementById('attachment-preview');
    return { chip: att ? att.children.length : -1, btnGone: !document.querySelector('.mobile-card-ref-btn'),
      feelCleared: !document.querySelector('.mobile-card-head').classList.contains('is-longpress') };
  });
  ok('CC6-5 引用钮点击 → 引用 chip 进 #attachment-preview + 钮收 + 触感清',
    afterRef.chip > 0 && afterRef.btnGone && afterRef.feelCleared,
    `chip=${afterRef.chip} btnGone=${afterRef.btnGone} cleared=${afterRef.feelCleared}`);
  ok('R1 主文档零 JS 错误（手机腿）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-cardhead-capsule: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
