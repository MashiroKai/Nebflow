#!/usr/bin/env node
// canvas-note-seed-position.spec.mjs — canvas 记事本浮窗「位置感知液态形变」验收
// （P2 续批④ 作者令：「手机 canvas note 点击展开位置跟随 note 按钮」）。
//
// 病根（改前实测）：闭态种子恒 `translateY(10px + seed)`，落点 = dock 右下角正下方
// = **ask 钮**位置；note 钮在 ask 钮左邻一格（`.ask-fab.note-fab` margin），点 note
// 钮展开时形变起手读作「ask 钮长出记事本」。改法 = askDock.js syncNoteSeed 以实测
// rect 写 --note-seed-dx/dy（座位/让位/safe-area 全跟随），CSS 闭态 transform 消费。
//
// 判据：
//   S0  页面零 pageerror（四组合）。
//   S1  闭态种子落点 = note 钮圆心（核心判据）：closed 态 .note-body 视觉中心与
//       .note-fab 命中盒中心横纵偏差各 ≤ 2.5px（桌面/手机、亮/暗四组合）。
//   S2  位置感知机制在位：dock 上 --note-seed-dx/--note-seed-dy 已由 JS 写入
//       （非空、数值型；fallback 只兜 JS 未及的首帧）。
//   S3  开合功能不回退：点 note 钮 → aria-pressed=true + 玻璃面展开到面板档宽；
//       再点收起 → 种子落点回到 note 钮圆心（S1 判据复测）。
//   S4  ask 浮窗回归：ask 闭态种子落点仍 = ask 钮圆心（本批未动 ask 腿）。
//   S5  四组合关键帧截图：closed / morph-mid（~80ms）/ open 各一帧（形变起手
//       从 note 钮长出 = 目视判据帧）。
//
// 数据面 = 自包含（静态服务器 + WS 夹具），零后端、零 sbt。
// 运行：node tests/canvas-note-seed-position.spec.mjs
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
const PORT = Number(process.env.PORT || 8241);
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

const FILE = { path: '/tmp/seed-fixture/doc.md', content: '# doc\n\nbody line\n', itemType: 'markdown' };

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
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'pop.readFile') {
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: FILE.content, itemType: FILE.itemType,
          fileName: (m.path || '').split('/').pop(), size: FILE.content.length,
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
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function openFile(page) {
  await page.evaluate(({ absPath, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title: 'doc.md', content, absPath, pinned: true },
    }));
  }, { absPath: FILE.path, content: FILE.content, itemType: FILE.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${FILE.path}"]`, { timeout: 10000, state: 'attached' });
  await sleep(300);
}

/** 手机档：canvas = 独立整页，入口 = 底 dock canvas 键。 */
async function mobileToCanvas(page) {
  const isMobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (!isMobile) return;
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(400);
}

/** 圆心偏差读数：种子面中心 vs 入口钮命中盒中心（px）。 */
const centers = (page) => page.evaluate(() => {
  const rd = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { cx: r.left + r.width / 2, cy: r.top + r.height / 2, w: r.width, h: r.height };
  };
  const body = rd('.note-dock .note-body');
  const fab = rd('.note-fab');
  const askIn = rd('.ask-dock .ask-input');
  const askFab = rd('.ask-fab:not(.note-fab)');
  const dock = document.querySelector('.note-dock');
  const cs = dock ? getComputedStyle(dock) : null;
  return {
    note: body && fab ? { dx: Math.abs(body.cx - fab.cx), dy: Math.abs(body.cy - fab.cy) } : null,
    ask: askIn && askFab ? { dx: Math.abs(askIn.cx - askFab.cx), dy: Math.abs(askIn.cy - askFab.cy) } : null,
    seedVars: cs ? { dx: cs.getPropertyValue('--note-seed-dx').trim(), dy: cs.getPropertyValue('--note-seed-dy').trim() } : null,
    noteOpen: dock ? !dock.classList.contains('closed') : null,
    notePressed: (() => { const f = document.querySelector('.note-fab'); return f ? f.getAttribute('aria-pressed') : null; })(),
    bodyW: body ? body.w : null,
  };
});

async function runLeg({ label, scheme, viewport }) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme, viewport });
  await openFile(page);
  await mobileToCanvas(page);
  const tag = `note-seed-${label}-${scheme}-${viewport.width}x${viewport.height}`;

  const rest = await centers(page);
  ok(`S1[${tag}] 闭态种子落点 = note 钮圆心（Δ≤2.5px）`,
    !!rest.note && rest.note.dx <= 2.5 && rest.note.dy <= 2.5,
    rest.note ? `dx=${rest.note.dx.toFixed(2)} dy=${rest.note.dy.toFixed(2)}` : 'missing');
  ok(`S2[${tag}] 位置感知变量已由 JS 写入（--note-seed-dx/dy 数值型）`,
    !!rest.seedVars && rest.seedVars.dx !== '' && rest.seedVars.dy !== ''
    && Number.isFinite(parseFloat(rest.seedVars.dx)) && Number.isFinite(parseFloat(rest.seedVars.dy)),
    rest.seedVars ? `dx=${rest.seedVars.dx} dy=${rest.seedVars.dy}` : 'missing');

  // 开：形变起手帧（~80ms，--lm-dur 280 中段）+ 落定
  const fabBox = await page.locator('.note-fab').boundingBox();
  await page.locator('.note-fab').click();
  await sleep(80);
  await page.screenshot({ path: join(OUT, `${tag}-morph-mid.png`) });
  await sleep(500);
  await page.screenshot({ path: join(OUT, `${tag}-open.png`) });
  const open = await centers(page);
  const dockWpx = await page.evaluate(() => {
    const d = document.querySelector('.note-dock');
    return d ? getComputedStyle(d).width : '';
  });
  ok(`S3a[${tag}] 开：aria-pressed=true + 玻璃面展到面板档宽`,
    open.notePressed === 'true' && open.noteOpen === true
    && !!open.bodyW && open.bodyW > (parseFloat(dockWpx) || 0) * 0.9,
    `pressed=${open.notePressed} open=${open.noteOpen} bodyW=${open.bodyW} dockW=${dockWpx}`);

  // 收：种子回落 note 钮
  await page.locator('.note-fab').click();
  await sleep(450);
  await page.screenshot({ path: join(OUT, `${tag}-closed.png`) });
  const back = await centers(page);
  ok(`S3b[${tag}] 收：种子落点回到 note 钮圆心（Δ≤2.5px）`,
    !!back.note && back.note.dx <= 2.5 && back.note.dy <= 2.5 && back.notePressed === 'false',
    back.note ? `dx=${back.note.dx.toFixed(2)} dy=${back.note.dy.toFixed(2)} pressed=${back.notePressed}` : 'missing');

  // ask 回归 = 不劣于批前基线（本批只动 note 腿）：桌面批前 dy=0；手机批前
  // 实测 dy=8（ask 闭态 colwrap 残高 53px + 固定 translateY —— 批前既有几何，
  // 位置感知机制本批只落在 note 窗；ask 种子位置列入回执发现项，不在本批扩权）。
  const askTol = label === 'mobile' ? 8.5 : 2.5;
  ok(`S4[${tag}] ask 浮窗回归：不劣于批前基线（Δ≤${askTol}px）`,
    !!rest.ask && rest.ask.dx <= askTol && rest.ask.dy <= askTol,
    rest.ask ? `dx=${rest.ask.dx.toFixed(2)} dy=${rest.ask.dy.toFixed(2)}` : 'missing');
  ok(`S0[${tag}] 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));

  writeFileSync(join(OUT, `readings-${tag}.json`), JSON.stringify({ rest, open, back, fabBox }, null, 1));
  await ctx.close();
}

await runLeg({ label: 'desktop', scheme: 'light', viewport: { width: 1440, height: 900 } });
await runLeg({ label: 'desktop', scheme: 'dark', viewport: { width: 1440, height: 900 } });
await runLeg({ label: 'mobile', scheme: 'light', viewport: { width: 375, height: 812 } });
await runLeg({ label: 'mobile', scheme: 'dark', viewport: { width: 375, height: 812 } });

await browser.close();
server.close();
console.log(`\ncanvas-note-seed-position: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
