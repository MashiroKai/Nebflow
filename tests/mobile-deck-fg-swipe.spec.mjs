#!/usr/bin/env node
// mobile-deck-fg-swipe.spec.mjs — 手机堆叠视角「叠卡滑动只动前景卡」验收
// （P2 续批⑤ 作者令：「叠卡滑动只动前景卡（背景卡位移必须为 0，Playwright DOM
//   数值断言）」）。
//
// 病根（改前实测）：layoutDeck 把 deckDragDx 加进**每张卡**的 --dtx（「整列跟手」），
// 纵拖两侧卡也吃 35% 视差 —— 拖前景卡时背景卡跟着平移。改法 = 跟手位移只喂前景卡
// （ak === 0），两侧卡恒在自身环位（--dtx = k·step / --dty = 0）。
//
// 判据（DOM 数值断言，getComputedStyle transform 矩阵 m41/m42 读数）：
//   F0  页面零 pageerror。
//   F1  横拖跟手段（dx=40，未过阈 56）：前景卡 m41 位移 ≈ 40（±3px），背景卡
//       m41/m42 位移 = 0（±0.5px）。
//   F2  横拖松手（未过阈）：前景卡回弹到位（|m41 位移| ≤ 1px），背景卡仍 0；
//       卡数不变、活动卡不变。
//   F3  纵拖跟手段（dy=40，未过阈 48）：前景卡 m42 位移 ≈ 36（=0.9×40，±3px），
//       背景卡 m41/m42 位移 = 0；松手回弹，零关卡（作者「只动前景卡」不破坏
//       既有关卡阈语义）。
//   F4  换卡提交腿保留（dx=90 过阈 56）：松手后活动卡切换；拖动中背景卡位移
//       仍 = 0（背景卡不跟手）。
//   F5  亮/暗两档关键帧截图：rest / mid-drag（前景偏移、背景钉位目视帧）。
//
// 数据面 = 自包含（静态服务器 + WS 夹具），零后端、零 sbt。
// 运行：node tests/mobile-deck-fg-swipe.spec.mjs
// 证据：EVIDENCE_DIR（默认 /tmp/c-morning-frontend/p2047）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = process.env.EVIDENCE_DIR || '/tmp/c-morning-frontend/p2047';
const PORT = Number(process.env.PORT || 8242);
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
  { path: '/tmp/deck-fixture/a.md', title: 'a.md', content: '# a\n\nalpha\n', itemType: 'markdown' },
  { path: '/tmp/deck-fixture/b.md', title: 'b.md', content: '# b\n\nbravo\n', itemType: 'markdown' },
];

async function boot({ colorScheme = 'dark' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, colorScheme });
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

/** 卡位姿读数：每张 .mf-deck-card 的 transform 矩阵平移量（m41/m42）。 */
const cardPoses = (page) => page.evaluate(() => {
  const stage = document.querySelector('.mf-deck');
  if (!stage) return null;
  const out = [];
  for (const card of stage.querySelectorAll('.mf-deck-card')) {
    const m = new DOMMatrix(getComputedStyle(card).transform === 'none' ? '' : getComputedStyle(card).transform);
    out.push({
      id: /** @type {HTMLElement} */ (card).dataset.tabId || '',
      on: card.classList.contains('on'),
      x: +m.m41.toFixed(2), y: +m.m42.toFixed(2),
    });
  }
  return { cards: out, open: !!document.querySelector('.mf-stack-view.open') };
});

/** 指针横/纵拖（轴锁后分步喂 move，模拟跟手序列）。 */
async function drag(page, { dx = 0, dy = 0, steps = 8 } = {}) {
  const box = await page.locator('.mf-deck-card.on').boundingBox();
  const sx = box.x + box.width / 2;
  const sy = box.y + box.height / 2;
  await page.mouse.move(sx, sy);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(sx + (dx * i) / steps, sy + (dy * i) / steps);
    await sleep(16);
  }
  return { sx, sy };
}

async function runLeg({ scheme }) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme });
  await openDocs(page);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(400);
  const tag = `deck-fg-${scheme}`;

  await page.locator('.mf-cv-stack').click();
  await page.waitForSelector('.mf-stack-view.open', { timeout: 5000 });
  await page.waitForSelector('.mf-deck-card', { timeout: 5000 });
  await sleep(500);
  const rest = await cardPoses(page);
  ok(`F0-pre[${tag}] 堆叠开 + 双卡在场`, !!rest && rest.open && rest.cards.length === 2,
    rest ? JSON.stringify(rest.cards) : 'no deck');

  // ── F1 横拖跟手（dx=40 未过阈）：只动前景卡 ──
  await drag(page, { dx: 40 });
  await sleep(60);
  await page.screenshot({ path: join(OUT, `${tag}-mid-drag-x.png`) });
  const midX = await cardPoses(page);
  const fgX = midX.cards.find((c) => c.on);
  const bgX = midX.cards.find((c) => !c.on);
  const fg0 = rest.cards.find((c) => c.on);
  const bg0 = rest.cards.find((c) => !c.on);
  const fgDx = fgX.x - fg0.x, fgDy = fgX.y - fg0.y;
  const bgDx = bgX.x - bg0.x, bgDy = bgX.y - bg0.y;
  ok(`F1[${tag}] 横拖跟手：前景卡位移 ≈ 40（±3）`, Math.abs(fgDx - 40) <= 3 && Math.abs(fgDy) <= 1,
    `fg dx=${fgDx.toFixed(2)} dy=${fgDy.toFixed(2)}`);
  ok(`F1[${tag}] 横拖跟手：背景卡位移 = 0（±0.5）`, Math.abs(bgDx) <= 0.5 && Math.abs(bgDy) <= 0.5,
    `bg dx=${bgDx.toFixed(2)} dy=${bgDy.toFixed(2)}`);

  // ── F2 松手未过阈：回弹，零换卡 ──
  await page.mouse.up();
  await sleep(550);
  const afterX = await cardPoses(page);
  const fg2 = afterX.cards.find((c) => c.on);
  const bg2 = afterX.cards.find((c) => !c.on);
  ok(`F2[${tag}] 松手回弹：前景回位 + 背景恒 0 + 活动卡不变`,
    afterX.cards.length === 2 && fg2.id === fg0.id
    && Math.abs(fg2.x - fg0.x) <= 1 && Math.abs(bg2.x - bg0.x) <= 0.5 && Math.abs(bg2.y - bg0.y) <= 0.5,
    `fg dx=${(fg2.x - fg0.x).toFixed(2)} bg dx=${(bg2.x - bg0.x).toFixed(2)}`);

  // ── F3 纵拖跟手（dy=40 未过阈 48）：前景下坠 0.9×，背景零位移 ──
  await sleep(100); // 越过 350ms click 抑制窗
  await drag(page, { dy: 40 });
  await sleep(60);
  await page.screenshot({ path: join(OUT, `${tag}-mid-drag-y.png`) });
  const midY = await cardPoses(page);
  const fgY = midY.cards.find((c) => c.on);
  const bgY = midY.cards.find((c) => !c.on);
  ok(`F3[${tag}] 纵拖跟手：前景位移 ≈ 0.9×40（±3）`,
    Math.abs((fgY.y - fg0.y) - 36) <= 3 && Math.abs(fgY.x - fg0.x) <= 1,
    `fg dy=${(fgY.y - fg0.y).toFixed(2)}`);
  ok(`F3[${tag}] 纵拖跟手：背景卡位移 = 0（±0.5）`,
    Math.abs(bgY.x - bg0.x) <= 0.5 && Math.abs(bgY.y - bg0.y) <= 0.5,
    `bg dx=${(bgY.x - bg0.x).toFixed(2)} dy=${(bgY.y - bg0.y).toFixed(2)}`);
  await page.mouse.up();
  await sleep(550);
  const afterY = await cardPoses(page);
  ok(`F3[${tag}] 纵拖未过阈：零关卡（卡数不变）`, afterY.cards.length === 2, `cards=${afterY.cards.length}`);

  // ── F4 换卡提交腿（dx=90 过阈 56）：拖动中背景仍零位移，松手活动卡切换 ──
  await sleep(100);
  await drag(page, { dx: 90 });
  await sleep(60);
  const midS = await cardPoses(page);
  const fgS = midS.cards.find((c) => c.on);
  const bgS = midS.cards.find((c) => !c.on);
  ok(`F4[${tag}] 换卡拖动中：背景卡位移仍 = 0（±0.5）`,
    Math.abs(bgS.x - bg0.x) <= 0.5 && Math.abs(bgS.y - bg0.y) <= 0.5,
    `bg dx=${(bgS.x - bg0.x).toFixed(2)}`);
  await page.mouse.up();
  await sleep(700);
  const afterS = await cardPoses(page);
  const fg4 = afterS.cards.find((c) => c.on);
  ok(`F4[${tag}] 换卡提交：活动卡已切换`, afterS.cards.length === 2 && fg4.id !== fg0.id,
    `active ${fg0.id} → ${fg4.id}`);
  await page.screenshot({ path: join(OUT, `${tag}-after-switch.png`) });

  ok(`F0[${tag}] 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
  writeFileSync(join(OUT, `readings-${tag}.json`), JSON.stringify({ rest, midX, afterX, midY, midS, afterS }, null, 1));
  await ctx.close();
}

await runLeg({ scheme: 'light' });
await runLeg({ scheme: 'dark' });

await browser.close();
server.close();
console.log(`\nmobile-deck-fg-swipe: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
