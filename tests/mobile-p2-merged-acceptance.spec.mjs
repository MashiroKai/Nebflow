#!/usr/bin/env node
// mobile-p2-merged-acceptance.spec.mjs — #2047 ①②③「已合并 P2 部分」新规范验收
// （机制本体 = 已合并 2631ee821 / 4fc2436bf。本 spec 只补 A 固化规范的验收腿：
// §2.2.1 图标居中 ≤0.5px 机检、§2.1.2 看板玻璃带贴屏顶、①底垫内收机制读数、
// 四组合关键帧截图。实现零触碰 —— 对已合并件只验收，不重做。）
//
// 判据：
//   M0  页面零 pageerror。
//   M1  ①底垫内收机制（消息页）：页体 padding-bottom = 0 + 收编滚区
//       #fm-conversations 吃 dock 净空（>0）—— 已合并修法的机制读数。
//   M2  ②搜索钮图标居中（§2.2.1 机检）：收起态条本体（= g2-fab 圆钮）内
//       图标包围盒中心与命中盒中心横纵偏差各 ≤ 0.5px；glyph 在自身 24 视框
//       内包围盒中心 = 视框中心 ± 0.5（图形本身居中）。
//   M3  ③看板玻璃带贴屏顶：.mf-strip 矩形 top ≤ 1px（改前 = 顶垫 12px 脱离
//       屏顶）；看板页体 padding-top/bottom = 0（几何自管）。
//   M4  四组合关键帧截图（messages 亮/暗收起态 + canvas 亮/暗顶带）。
//
// 数据面 = 自包含（静态服务器 + WS 夹具），零后端、零 sbt。
// 运行：node tests/mobile-p2-merged-acceptance.spec.mjs
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
const PORT = Number(process.env.PORT || 8245);
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

const FILE = { path: '/tmp/p2acc-fixture/doc.md', content: '# doc\n\nbody line\n', itemType: 'markdown' };

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
    // 30 条会话：消息列表可滚（收起态 = 真实上滑触发，单行滚不动）
    ws.send(JSON.stringify({ type: 'sessionList', sessions: Array.from({ length: 30 }, (_, i) => ({ id: 'sess-' + i, agentName: 'Nebula', title: '会话 ' + i, updatedAt: Date.now() - i * 1000 })), activeId: 'sess-0', folders: [] }));
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
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function runLeg({ scheme }) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme });
  const tag = `p2acc-${scheme}`;

  // ── 消息页腿（①②）──
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  await sleep(500);
  const m1 = await page.evaluate(() => {
    const body = document.querySelector('#mobile-pages .mobile-page[data-page="messages"] .mobile-page-body');
    const conv = document.querySelector('#fm-conversations');
    const cs = body ? getComputedStyle(body) : null;
    const ccs = conv ? getComputedStyle(conv) : null;
    return {
      bodyPadB: cs ? parseFloat(cs.paddingBottom) : null,
      convPadB: ccs ? parseFloat(ccs.paddingBottom) : null,
    };
  });
  ok(`M1[${tag}] ①底垫内收：页体 padding-bottom=0 + 滚区吃 dock 净空（>0）`,
    m1.bodyPadB === 0 && !!m1.convPadB && m1.convPadB > 0,
    JSON.stringify(m1));

  // ②收起态：夹具直切生产类对（setCollapsed 的唯一写点 = `.mh-search-collapsed`
  // 挂 dock ∪ `.g2-fab` 挂条本体 —— 社交数据面不在本 mock，滚动通路不可达；
  // 受测体 = 真实条/图标/全部 CSS，刺激面如实标注）。
  const m2 = await page.evaluate(() => {
    const dock = document.querySelector('.mh-search-dock');
    const bar = document.querySelector('.mh-search-dock .mh-search-bar');
    if (!dock || !bar) return { found: false };
    dock.classList.add('mh-search-collapsed');
    bar.classList.add('g2-fab');
    bar.setAttribute('role', 'button');
    bar.setAttribute('tabindex', '0');
    return { found: true };
  });
  await sleep(500);
  const m2b = await page.evaluate(() => {
    const bar = document.querySelector('.mh-search-dock .mh-search-bar');
    if (!bar) return { found: false };
    const collapsed = bar.closest('.mh-search-dock').classList.contains('mh-search-collapsed');
    const icon = bar.querySelector(':scope > svg') || bar.querySelector(':scope > i svg');
    const br = bar.getBoundingClientRect();
    const ir = icon ? icon.getBoundingClientRect() : null;
    const bb = icon && typeof icon.getBBox === 'function' && icon.ownerSVGElement ? (() => {
      try { const b = icon.getBBox(); const vb = icon.viewBox.baseVal || { width: 24, height: 24 };
        return { dx: Math.abs(b.x + b.width / 2 - vb.width / 2), dy: Math.abs(b.y + b.height / 2 - vb.height / 2) }; }
      catch { return null; }
    })() : null;
    return {
      found: true, collapsed,
      iconDx: ir ? Math.abs((ir.left + ir.width / 2) - (br.left + br.width / 2)) : null,
      iconDy: ir ? Math.abs((ir.top + ir.height / 2) - (br.top + br.height / 2)) : null,
      barW: +br.width.toFixed(1),
      bbox: bb,
    };
  });
  ok(`M2a[${tag}] ②收起态在场（生产类对直切；滚动通路归社交数据面）`, m2b.found && m2b.collapsed, JSON.stringify({ collapsed: m2b.collapsed, barW: m2b.barW }));
  ok(`M2b[${tag}] ②图标居中 ≤0.5px（§2.2.1 命中盒判据）`,
    m2b.iconDx !== null && m2b.iconDx <= 0.5 && m2b.iconDy !== null && m2b.iconDy <= 0.5,
    `dx=${m2b.iconDx == null ? '?' : m2b.iconDx.toFixed(2)} dy=${m2b.iconDy == null ? '?' : m2b.iconDy.toFixed(2)}`);
  if (m2b.bbox) {
    ok(`M2c[${tag}] ②glyph 视框居中 ≤0.5px（§2.2.1 图形本身判据）`,
      m2b.bbox.dx <= 0.5 && m2b.bbox.dy <= 0.5, `dx=${m2b.bbox.dx.toFixed(2)} dy=${m2b.bbox.dy.toFixed(2)}`);
  }
  await page.screenshot({ path: join(OUT, `p2-messages-${scheme}-collapsed.png`) });

  // ── 看板腿（③）──
  await page.evaluate(({ absPath, itemType, title, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true },
    }));
  }, { absPath: FILE.path, itemType: FILE.itemType, title: 'doc.md', content: FILE.content });
  await sleep(400);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(500);
  const m3 = await page.evaluate(() => {
    const strip = document.querySelector('.mf-strip');
    const body = document.querySelector('#mobile-pages .mobile-page[data-page="canvas"] .mobile-page-body');
    const cs = body ? getComputedStyle(body) : null;
    const sr = strip ? strip.getBoundingClientRect() : null;
    return {
      stripTop: sr ? +sr.top.toFixed(1) : null,
      bodyPadT: cs ? parseFloat(cs.paddingTop) : null,
      bodyPadB: cs ? parseFloat(cs.paddingBottom) : null,
    };
  });
  ok(`M3a[${tag}] ③看板玻璃带贴屏顶（top ≤ 1px，改前 12px 脱离）`,
    m3.stripTop !== null && m3.stripTop <= 1, `top=${m3.stripTop}`);
  ok(`M3b[${tag}] ③看板页体顶/底垫 = 0（几何自管面）`,
    m3.bodyPadT === 0 && m3.bodyPadB === 0, JSON.stringify({ t: m3.bodyPadT, b: m3.bodyPadB }));
  await page.screenshot({ path: join(OUT, `p2-canvas-${scheme}-topband.png`) });

  ok(`M0[${tag}] 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
  writeFileSync(join(OUT, `readings-${tag}.json`), JSON.stringify({ m1, m2, m2b, m3 }, null, 1));
  await ctx.close();
}

await runLeg({ scheme: 'light' });
await runLeg({ scheme: 'dark' });

await browser.close();
server.close();
console.log(`\nmobile-p2-merged-acceptance: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
