#!/usr/bin/env node
// canvas-popbtn-visibility.spec.mjs — Canvas 独立窗口按钮显隐验收（P2 续批⑥；
// 机制本体 = 已合并 2631ee821 的 canvas.js syncHeaderActions 落场 + split.css
// 手机档全局隐藏。本 spec = 对已合并实现的**新规范验收腿**（A 固化规范：
// 四组合截图 + DOM 数值断言），不重做实现）。
//
// 判据：
//   V0  页面零 pageerror（四组合）。
//   V1  桌面·非内容物页签（plugins）：胶囊右座 .canvas-pop-btn 离场
//       ([hidden] → display:none 不占标题座位) + 残留 disabled/提示防御位。
//   V2  桌面·内容物文件卡：入口在场可用（label = 独立窗口打开）。
//   V3  手机端：.canvas-pop-btn 恒 display:none（body.mobile-layer 全局隐藏
//       —— 本端没有独立窗口能力，内容物页签同样不显示）。
//   V4  四组合关键帧截图（desktop light/dark × 卡头有/无钮 + mobile light/dark）。
//
// 数据面 = 自包含（静态服务器 + WS 夹具），零后端、零 sbt。
// 运行：node tests/canvas-popbtn-visibility.spec.mjs
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
const PORT = Number(process.env.PORT || 8244);
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

const FILE = { path: '/tmp/popvis-fixture/doc.md', content: '# doc\n\nbody line\n', itemType: 'markdown' };

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

async function mobileToCanvas(page) {
  const isMobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (!isMobile) return;
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(400);
}

/** pop 钮显隐读数。 */
const popState = (page) => page.evaluate(() => {
  const pop = document.querySelector('#canvas-panel .canvas-header .canvas-pop-btn');
  if (!pop) return null;
  const cs = getComputedStyle(pop);
  return {
    hiddenAttr: pop.hidden === true,
    offSeat: cs.display === 'none',
    disabled: !!pop.disabled,
    label: pop.getAttribute('aria-label') || '',
    box: (() => { const r = pop.getBoundingClientRect(); return { w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; })(),
  };
});

async function runLeg({ label, scheme, viewport }) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme, viewport });
  const isMobile = viewport.width < 700;
  const tag = `popvis-${label}-${scheme}`;
  await openFile(page);
  await mobileToCanvas(page);
  await sleep(300);

  if (!isMobile) {
    // V2 内容物文件卡：入口在场可用
    const on = await popState(page);
    ok(`V2[${tag}] 桌面·文件卡：入口在场可用（label=独立窗口打开）`,
      !!on && !on.hiddenAttr && !on.offSeat && !on.disabled && on.label.includes('独立窗口'),
      JSON.stringify(on));
    await page.screenshot({ path: join(OUT, `${tag}-filecard.png`) });

    // V1 非内容物页签：入口离场
    await page.evaluate(async () => { (await import('/js/plugins.js')).openPlugins(); });
    await page.waitForSelector('.canvas-tab-pane[data-tab-id="plugins"]', { timeout: 10000, state: 'attached' });
    await sleep(400);
    const off = await popState(page);
    ok(`V1[${tag}] 桌面·plugins 页签：入口离场（[hidden]→display:none）+ 防御位`,
      !!off && off.hiddenAttr && off.offSeat && off.disabled && off.label.length > 0,
      JSON.stringify(off));
    await page.screenshot({ path: join(OUT, `${tag}-plugins.png`) });
  } else {
    // V3 手机端：内容物页签也恒隐藏（全局规则，无独立窗口能力）
    const on = await popState(page);
    ok(`V3[${tag}] 手机·文件卡：恒 display:none`,
      !!on && on.offSeat === true,
      JSON.stringify(on));
    await page.screenshot({ path: join(OUT, `${tag}-filecard.png`) });
    await page.evaluate(async () => { (await import('/js/plugins.js')).openPlugins(); });
    await sleep(600);
    const off = await popState(page);
    ok(`V3[${tag}] 手机·plugins 页签：恒 display:none`,
      off === null || off.offSeat === true,
      JSON.stringify(off));
  }
  ok(`V0[${tag}] 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await runLeg({ label: 'desktop', scheme: 'light', viewport: { width: 1440, height: 900 } });
await runLeg({ label: 'desktop', scheme: 'dark', viewport: { width: 1440, height: 900 } });
await runLeg({ label: 'mobile', scheme: 'light', viewport: { width: 375, height: 812 } });
await runLeg({ label: 'mobile', scheme: 'dark', viewport: { width: 375, height: 812 } });

await browser.close();
server.close();
console.log(`\ncanvas-popbtn-visibility: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
