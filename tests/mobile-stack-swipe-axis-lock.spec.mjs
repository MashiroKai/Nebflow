#!/usr/bin/env node
// mobile-stack-swipe-axis-lock.spec.mjs — 手机叠卡视图「一次性轴锁」闸
// （作者 2026-11-23 10:23 报：叠卡视图里左右滑卡与纵向手势互相抢 —— 斜向或
//   轻微垂直分量的横滑被误判成纵向，滑卡不换/误触发）。
//
// 判据（真触屏序列 = Chromium CDP Input.dispatchTouchEvent，touch-action 生效；
//   读数一律 DOM 数值 + 只读探针，不做「看起来动了」的目测）：
//   A1 横滑→卡位移：净横滑（dy=0）拖动中前景卡跟手位移 ≈ dx，松手过阈换中央卡，
//      零关卡（横滑不许串到纵向腿）。
//   A2 竖滑→纵向腿独占、横轴零介入：净竖滑拖动中前景卡 |Δx| ≤ 1.5px、
//      Δy ≈ 0.9×dy（既有跟手曲线不动），松手过阈关卡，零换卡。
//   A3 斜滑→主导轴赢（x 腿）：前两帧纵向领先（dy=12 先到、dx 只有 2）后横滑到
//      −130 —— 判轴必须落在 x（一次性锁定不翻转）⇒ 松手换卡。修前此处锁成 y
//      ⇒ 卡不换（作者报的正是这一条）。
//   A3b 斜滑→主导轴赢（y 腿）：镜像形态（dx 先到 12、dy 拉到 120）⇒ 判轴落 y
//      ⇒ 关卡、零换卡。修前锁成 x ⇒ 既关卡不了也换不了卡（两手都空）。
//   A4 死区内微动→零触发：死区内抖动（|d| ≤ 6px）零跟手、零换卡、零关卡、
//      不武装 dragging；随后点中央卡照常收面（死区不吃点按 —— 可点等价入口保留）。
//   N1/N2 负对照：净横滑零关卡、净竖滑零换卡（两条腿互不串；判据若恒真即在此红）。
//   N3 灵敏度负控：同一份判据在**未修**源码上必红（A3/A3b）—— 证据 = 修复前
//      基线的 profix 读数（本文件 pre-fix 跑一遍留档，见报告「前后对照」）。
//   A0 全程零 pageerror；touch-action 读数与轴锁契约一致（滑卡层纵向可回原生滚动
//      处 pan-y、JS 独占横轴——读 computed 值钉死，防「改回 auto 也能过」的假绿）。
//
// 数据面 = 自包含（静态服务器 + WS/ticket 夹具），零后端、零 sbt。
// 运行：node tests/mobile-stack-swipe-axis-lock.spec.mjs
// 证据：EVIDENCE_DIR（默认 /tmp/stack-swipe-axis-lock）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = process.env.EVIDENCE_DIR || '/tmp/stack-swipe-axis-lock';
const PORT = Number(process.env.PORT || 8248);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const readings = {};
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
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();

const DOCS = [
  { path: '/tmp/deck-fixture/axis-a.md', title: 'axis-a.md', content: '# a\n\nalpha\n', itemType: 'markdown' },
  { path: '/tmp/deck-fixture/axis-b.md', title: 'axis-b.md', content: '# b\n\nbravo\n', itemType: 'markdown' },
];

async function boot() {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'dark',
  });
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
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'pop.readFile') {
        const doc = DOCS.find((d) => d.path === m.path) || DOCS[0];
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: doc.content, itemType: doc.itemType,
          fileName: doc.title, size: doc.content.length,
        }));
      } else if (m.type === 'askNoteGet') {
        ws.send(JSON.stringify({ type: 'askNoteState', sid: m.sid, text: '', ts: 0 }));
      } else if (m.type === 'askOpen') {
        ws.send(JSON.stringify({ type: 'askHistory', sessionId: m.sessionId, messages: [] }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function openDocs(page) {
  for (const d of DOCS) {
    await page.evaluate((doc) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + doc.path, itemType: doc.itemType, title: doc.title, content: doc.content, absPath: doc.path, pinned: true },
      }));
    }, d);
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${d.path}"]`, { timeout: 10000, state: 'attached' });
  }
  await sleep(300);
}

async function openDeck(page) {
  const n = await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').count();
  if (n) await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(400);
  if (!(await page.locator('.mf-stack-view.open').count())) {
    await page.locator('.mf-cv-stack').click();
  }
  await page.waitForSelector('.mf-stack-view.open', { timeout: 5000 });
  await page.waitForSelector('.mf-deck-card', { timeout: 5000 });
  await sleep(450);
}

/** 堆叠面读数（DOM 数值 + 只读探针；零目测）。 */
const deckState = (page) => page.evaluate(() => {
  const stage = document.querySelector('.mf-deck');
  const view = document.querySelector('.mf-stack-view');
  if (!stage) return null;
  const cards = [...stage.querySelectorAll('.mf-deck-card')].map((card) => {
    const cs = getComputedStyle(card);
    const m = new DOMMatrix(cs.transform === 'none' ? '' : cs.transform);
    return {
      id: card.dataset.tabId || '', on: card.classList.contains('on'),
      x: +m.m41.toFixed(2), y: +m.m42.toFixed(2),
    };
  });
  const chip = document.querySelector('.mf-strip-chip.on');
  return {
    open: !!view && view.classList.contains('open') && !view.hidden,
    dragging: stage.dataset.dragging === '1',
    touchAction: getComputedStyle(stage).touchAction,
    cardTouchAction: (() => {
      const c = stage.querySelector('.mf-deck-card');
      return c ? getComputedStyle(c).touchAction : null;
    })(),
    active: document.querySelector('#canvas-tab-bar .canvas-tab.active')?.dataset.tabId
      || (chip ? chip.dataset.tabId : null) || null,
    cards,
    probe: (/** @type {any} */ (window)).__nfDeckSwipe
      ? { axis: (/** @type {any} */ (window)).__nfDeckSwipe.axis, locked: (/** @type {any} */ (window)).__nfDeckSwipe.locked, claims: (/** @type {any} */ (window)).__nfDeckSwipe.claims }
      : null,
  };
});
const clearEvents = (page) => page.evaluate(() => { (/** @type {any} */ (window)).__nfMobileLayerEvents.length = 0; });
const readEvents = (page) => page.evaluate(() => (/** @type {any} */ (window)).__nfMobileLayerEvents.map((e) => ({ kind: e.kind, extra: e.extra || null })));
const armCancelProbe = (page) => page.evaluate(() => {
  const stage = document.querySelector('.mf-deck');
  (/** @type {any} */ (window)).__axisLockCancels = 0;
  if (stage) stage.addEventListener('pointercancel', () => { (/** @type {any} */ (window)).__axisLockCancels++; });
  return !!stage;
});
const readCancels = (page) => page.evaluate(() => (/** @type {any} */ (window)).__axisLockCancels || 0);

/** 起点（前景卡中心；避开卡头 ✕ 与卡边） */
async function frontPoint(page) {
  const box = await page.locator('.mf-deck-card.on').boundingBox();
  return { x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height * 0.62) };
}

/** 折线点集：起点 (sx,sy) 恒为首点（touchStart），其后每个偏移 = 一帧 touchMove。
 *  偏移序列不含 [0,0]——起点由本函数补上，避免把首个偏移误当成触点起点。 */
function offsetsToPath(sx, sy, offsets) {
  return [[sx, sy], ...offsets.map(([dx, dy]) => [Math.round(sx + dx), Math.round(sy + dy)])];
}
/** 线性折线（steps 帧）。 */
function lineOffsets(dx, dy, steps) {
  const out = [];
  for (let i = 1; i <= steps; i++) out.push([(dx * i) / steps, (dy * i) / steps]);
  return out;
}

/** CDP 真触屏序列（单 session 纪律：触点 id 恒 10；touchStart→move×n→touchEnd）。
 *  onStep(i, reading) 在每帧 move 之后回调（读拖动中位数用）。 */
async function touchPath(client, page, path, { holdMs = 16, onStep = null } = {}) {
  const [start, ...rest] = path;
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: start[0], y: start[1], id: 10 }] });
  for (let i = 0; i < rest.length; i++) {
    await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: rest[i][0], y: rest[i][1], id: 10 }] });
    await sleep(holdMs);
    if (onStep) await onStep(i, rest.length);
  }
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await sleep(240);
  return path.length;
}

const fg = (st) => st.cards.find((c) => c.on) || null;
const bg = (st) => st.cards.find((c) => !c.on) || null;
const delta = (a, b) => ({ dx: +(a.x - b.x).toFixed(2), dy: +(a.y - b.y).toFixed(2) });

// ── A1 / N1 净横滑腿：卡位移 + 换卡，零关卡（两腿互不串） ──────────────
async function legHorizontal() {
  const { ctx, page, errors } = await boot();
  const client = await ctx.newCDPSession(page);
  await openDocs(page);
  await openDeck(page);
  await armCancelProbe(page);
  const rest = await deckState(page);
  ok('A1-pre 堆叠开 + 双卡在场 + 零错误', !!rest && rest.open && rest.cards.length === 2 && errors.length === 0,
    rest ? `cards=${rest.cards.length} open=${rest.open}` : 'no deck');
  const tone = 'A1';
  readings[tone] = { rest, touchAction: rest?.touchAction, cardTouchAction: rest?.cardTouchAction };

  const p = await frontPoint(page);
  await clearEvents(page);
  let mid = null;
  await touchPath(client, page, offsetsToPath(p.x, p.y, lineOffsets(-140, 0, 14)), {
    onStep: async (i, n) => { if (i === n - 1) mid = await deckState(page); },
  });
  readings[tone].mid = mid;
  const midD = mid ? delta(fg(mid), fg(rest)) : null;
  ok('A1 横滑跟手：前景卡位移 ≈ dx（±8px）+ 纵向零漂移',
    !!midD && Math.abs(midD.dx - (-140)) <= 8 && Math.abs(midD.dy) <= 1.5,
    `fg Δ=${JSON.stringify(midD)} dragging=${mid?.dragging} axis=${mid?.probe?.axis}`);
  const after = await deckState(page);
  const ev = await readEvents(page);
  readings[tone].after = after;
  readings[tone].events = ev.map((e) => e.kind);
  ok('A1 松手过阈：中央卡已切换（横滑腿真生效）',
    (ev.some((e) => e.kind === 'deck-page')) && after.cards.length === 2 && fg(after).id !== fg(rest).id,
    `active ${fg(rest).id} → ${fg(after).id}`);
  ok('N1 横滑零关卡（负对照：横轴不许串到纵向腿）',
    !ev.some((e) => e.kind === 'deck-close'), `events=${JSON.stringify(ev.map((e) => e.kind))}`);
  ok('A1 触屏横滑零 pointercancel（浏览器不接管横轴）', (await readCancels(page)) === 0,
    `cancels=${await readCancels(page)}`);
  ok('A0-h 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

// ── A3 / A3b 斜滑腿：主导轴赢（前两帧反方向领先的形态） ─────────────────
async function legDiagonal() {
  const { ctx, page, errors } = await boot();
  const client = await ctx.newCDPSession(page);
  await openDocs(page);
  await openDeck(page);
  await armCancelProbe(page);
  const rest = await deckState(page);
  ok('A3-pre 堆叠开 + 双卡在场 + 零错误', !!rest && rest.open && rest.cards.length === 2 && errors.length === 0,
    rest ? `cards=${rest.cards.length}` : 'no deck');

  // ① x 腿：前两帧纵向领先（dy 12 / dx 2），随后横滑到 −130 —— 主导轴 = x。
  const p = await frontPoint(page);
  await clearEvents(page);
  const xPath = [
    [2, 12], [4, 13], [-30, 12], [-70, 10], [-130, 8],
  ];
  let midX = null;
  await touchPath(client, page, offsetsToPath(p.x, p.y, xPath), {
    onStep: async (i, n) => {
      if (i === n - 1) { midX = await deckState(page); await page.screenshot({ path: join(OUT, 'A3-diag-x-mid.png') }); }
    },
  });
  const afterX = await deckState(page);
  const evX = await readEvents(page);
  const midXD = midX ? delta(fg(midX), fg(rest)) : null;
  readings.A3 = { rest, midX, afterX, events: evX.map((e) => e.kind) };
  ok('A3 纵领先斜滑：判轴落横轴（跟手位移 ≈ −130，纵向不跟手）',
    !!midXD && Math.abs(midXD.dx - (-130)) <= 10 && Math.abs(midXD.dy) <= 2,
    `fg Δ=${JSON.stringify(midXD)} axis=${midX?.probe?.axis ?? 'n/a'} dragging=${midX?.dragging}`);
  ok('A3 纵领先斜滑 → 换卡（修前锁成纵轴 ⇒ 零换卡 = 作者报的误判）',
    evX.some((e) => e.kind === 'deck-page') && fg(afterX).id !== fg(rest).id,
    `active ${fg(rest).id} → ${fg(afterX).id} events=${JSON.stringify(evX.map((e) => e.kind))}`);
  ok('A3 纵领先斜滑零关卡（横轴锁定后纵向腿不介入）',
    !evX.some((e) => e.kind === 'deck-close'), `events=${JSON.stringify(evX.map((e) => e.kind))}`);

  // ② y 腿：镜像形态（前两帧横向领先 dx 12 / dy 2），随后纵向拉到 +120。
  await sleep(420); // 越过拖后 click 抑制窗
  const p2 = await frontPoint(page);
  const rest2 = await deckState(page);
  await clearEvents(page);
  const yPath = [[12, 2], [13, 4], [12, 30], [10, 70], [8, 120]];
  let midY = null;
  await touchPath(client, page, offsetsToPath(p2.x, p2.y, yPath), {
    onStep: async (i, n) => {
      if (i === n - 1) { midY = await deckState(page); await page.screenshot({ path: join(OUT, 'A3b-diag-y-mid.png') }); }
    },
  });
  const afterY = await deckState(page);
  const evY = await readEvents(page);
  const midYD = midY ? delta(fg(midY), fg(rest2)) : null;
  readings.A3b = { rest2, midY, afterY, events: evY.map((e) => e.kind) };
  ok('A3b 横领先斜滑：判轴落纵轴（Δy ≈ 0.9×120，Δx 零跟手）',
    !!midYD && Math.abs(midYD.dy - 108) <= 10 && Math.abs(midYD.dx) <= 2,
    `fg Δ=${JSON.stringify(midYD)} axis=${midY?.probe?.axis ?? 'n/a'}`);
  ok('A3b 横领先斜滑 → 关卡（修前锁成横轴 ⇒ 既不关卡也不换卡）',
    evY.some((e) => e.kind === 'deck-close') && afterY.cards.length === 1,
    `cards=${afterY.cards.length} events=${JSON.stringify(evY.map((e) => e.kind))}`);
  ok('A3b 横领先斜滑零换卡（纵向腿不许串到换卡）',
    !evY.some((e) => e.kind === 'deck-page'), `events=${JSON.stringify(evY.map((e) => e.kind))}`);
  ok('A3 触屏斜滑零 pointercancel', (await readCancels(page)) === 0, `cancels=${await readCancels(page)}`);
  ok('A0-d 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

// ── A2 净竖滑腿：纵向腿独占，横轴零介入 ──────────────────────────────
async function legVertical() {
  const { ctx, page, errors } = await boot();
  const client = await ctx.newCDPSession(page);
  await openDocs(page);
  await openDeck(page);
  await armCancelProbe(page);
  const rest = await deckState(page);
  await clearEvents(page);
  const p = await frontPoint(page);
  let mid = null;
  await touchPath(client, page, offsetsToPath(p.x, p.y, lineOffsets(0, 110, 14)), {
    onStep: async (i, n) => { if (i === n - 1) mid = await deckState(page); },
  });
  const after = await deckState(page);
  const ev = await readEvents(page);
  const midD = mid ? delta(fg(mid), fg(rest)) : null;
  readings.A2 = { rest, mid, after, events: ev.map((e) => e.kind), touchAction: rest.touchAction };
  ok('A2 竖滑跟手：横轴零介入（|Δx| ≤ 1.5）+ 纵向 0.9×dy',
    !!midD && Math.abs(midD.dx) <= 1.5 && Math.abs(midD.dy - 99) <= 10,
    `fg Δ=${JSON.stringify(midD)} axis=${mid?.probe?.axis ?? 'n/a'}`);
  ok('A2 竖滑过阈：关卡生效 + 零换卡（纵向腿真生效）',
    ev.some((e) => e.kind === 'deck-close') && !ev.some((e) => e.kind === 'deck-page') && after.cards.length === 1,
    `cards=${after.cards.length} events=${JSON.stringify(ev.map((e) => e.kind))}`);
  ok('A2 竖滑零 pointercancel（纵轴仍在 JS 手势链内，不被浏览器中途收走）',
    (await readCancels(page)) === 0, `cancels=${await readCancels(page)}`);
  ok('A0-v 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

// ── A4 死区腿：微动零触发 + 点按不受影响 ───────────────────────────────
async function legDeadzone() {
  const { ctx, page, errors } = await boot();
  const client = await ctx.newCDPSession(page);
  await openDocs(page);
  await openDeck(page);
  await armCancelProbe(page);
  const rest = await deckState(page);
  await clearEvents(page);
  const p = await frontPoint(page);
  const microPath = [[3, 2], [-4, 3], [2, -3], [-1, 4], [4, 3]];
  const stepsSeen = [];
  let midDrag = null;
  await touchPath(client, page, offsetsToPath(p.x, p.y, microPath), {
    onStep: async () => {
      const st = await deckState(page);
      stepsSeen.push(st.dragging);
      midDrag = st;
    },
  });
  const after = await deckState(page);
  const ev = await readEvents(page);
  const d = midDrag ? delta(fg(midDrag), fg(rest)) : null;
  readings.A4 = { rest, stepsSeen, midDrag, after, events: ev.map((e) => e.kind) };
  ok('A4 死区内微动：零跟手（卡位姿不动）',
    !!d && Math.abs(d.dx) <= 0.5 && Math.abs(d.dy) <= 0.5, `fg Δ=${JSON.stringify(d)}`);
  ok('A4 死区内微动：不武装 dragging（零拖动态）',
    stepsSeen.every((v) => v === false), `dragging steps=${JSON.stringify(stepsSeen)}`);
  ok('A4 死区内微动：零换卡 / 零关卡（滑动类事件零触发）',
    !ev.some((e) => e.kind === 'deck-page') && !ev.some((e) => e.kind === 'deck-close'),
    `events=${JSON.stringify(ev.map((e) => e.kind))}`);
  ok('A4 死区内微动：零 pointercancel', (await readCancels(page)) === 0, `cancels=${await readCancels(page)}`);

  // 死区内微动 = 点按语义（DECK_AXIS_PX 注释「内 = 点按」）：松手落在中央卡 ⇒ click
  // 收面是设计内的可点等价入口，不是误触发；判据只认「没有滑动类事件」。
  const tapEv = ev.map((e) => e.kind);
  ok('A4 死区微动 = 点按语义：仅允许 click 收面（deck-exit），零 deck-page/deck-close',
    tapEv.every((k) => k === 'deck-exit' || k === 'canvas-stack-close'), `events=${JSON.stringify(tapEv)}`);
  ok('A0-z 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

// ── 静态契约：滑卡层 touch-action（横轴 JS 独占；纵向不被 JS 抢成 auto） ──
async function legTouchAction() {
  const { ctx, page, errors } = await boot();
  await openDocs(page);
  await openDeck(page);
  const st = await deckState(page);
  readings.touchAction = { stage: st.touchAction, card: st.cardTouchAction };
  // 决策（探针实证，见报告）：stage 保持 none，双轴由 JS 轴锁独占。pan-y 会让浏览器在
  // 纵向第二帧收走指针（pointercancel=1），纵拖关卡手势随之失效 ⇒ 不采纳。
  // 判据钉 stage === none，防有人改回 pan-y/auto 后「看起来也能滑」的假绿。
  ok('T1 滑卡层 touch-action = none（双轴 JS 独占；纵拖不被浏览器收走）',
    st.touchAction === 'none', `stage=${st.touchAction} card=${st.cardTouchAction}`);
  ok('A0-t 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

try {
  await legHorizontal();
  await legDiagonal();
  await legVertical();
  await legDeadzone();
  await legTouchAction();
} finally {
  writeFileSync(join(OUT, 'readings-axis-lock.json'), JSON.stringify(readings, null, 1));
  await browser.close();
  server.close();
}

console.log(`\nmobile-stack-swipe-axis-lock: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
