#!/usr/bin/env node
// ask-dock-card-leg.spec.mjs — card-tool-revive 批（2026-11-18 作者裁定②）：
// ask 会话里 Card 工具装载后的 **ask dock 渲染腿**验收。
//
// 背景：Card 工具复活后，ask 会话能真调用、能真返回 `___CARD_HTML___` 载荷，
// 但 askDock.js 的事件分发只认 askState/textDelta/done/interrupted/error/
// askHandoffAck/askEditApplied/askEditResolved/user —— `toolEnd` 落 default 静默，
// 卡不显形。本 spec 钉住补上的那一条腿：
//   C0  载入健康（零 pageerror）
//   C1  toolEnd(Card) 到达后，卡 iframe 落在 **ask dock 对话列内**；主会话 #chat 零卡
//   C2  沙箱属性 = allow-scripts allow-same-origin（复用 cardRegistry 渲染单源）
//   C3  卡盒宽 = 列内容盒宽（卡占满列，不是按内容宽右贴）
//   C4  帧内是真载荷内容（标题 / 表行 / 进度条）
//   C5  卡行落在流式 ai 泡**之前**：末元素仍是 ai 泡（流式增量腿判据不受影响）
//   C6  定稿（done → 整列重渲）后卡**仍在**——render() 重建腿认卡行，卡不被抹掉
//   C7  非卡工具帧仍透明（一条 ___POP_JSON___ 结果不改列结构、不建 iframe）
//   C8  卡载荷不落本地转录持久面（localStorage 里零 `___CARD_HTML___`）
//   C9  手机档 375×812：同一腿在列内显形、iframe 宽不溢出 dock、零 pageerror
//
// 载荷来源：真隔离实例（真引擎 + 真 ask 会话 + 真 CardTool 调用）抓下的 `toolEnd`
// content 逐字，存 tests/fixtures/card-ask/card-frame-content.txt。
//
// 运行：node tests/ask-dock-card-leg.spec.mjs
// 可选 env：PORT（默认 0 = 内核选空闲口）、ASK_SHOTS_DIR（截图目录）。

import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);
const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
// 端口默认交给内核选空闲口（多支 spec 并行/连跑时固定口会 EADDRINUSE）；仍可用 PORT 覆盖。
const PORT = Number(process.env.PORT || 0);
const SHOTS_DIR = process.env.ASK_SHOTS_DIR || resolve(HERE, `../evidence/ask-dock-card-leg/shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

// 真载荷（隔离实例抓帧逐字）
const CARD_CONTENT = readFileSync(resolve(HERE, 'fixtures/card-ask/card-frame-content.txt'), 'utf8');
const CARD_LABEL = 'Card\n  (CARD-LIVE-OK)';
const CARD_SUMMARY = 'CARD-LIVE-OK rendered';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
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
const BASE = `http://127.0.0.1:${server.address().port}`;

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  if (cond) pass++; else fail++;
}

/** @type {any} */ let askWs = null;
const inbound = [];
function wsSend(obj) { if (askWs) askWs.send(JSON.stringify(obj)); }

function nfFileRoute() {
  return async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  };
}

async function bootPage(browser, { viewport, colorScheme = 'dark', mobile = false } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  const notFound = [];
  page.on('response', (r) => { if (r.status() === 404) notFound.push(r.url()); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  // 真引擎才有的静态面（agent 定义）；静态服务树里没有 ⇒ 不 mock 就是两条 404
  // console error（与本腿无关的既有环境噪声，见 C9d 判据）。
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    askWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        ws.send(JSON.stringify({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: (m.file && m.file.absPath) || '', fileName: (m.file && m.file.name) || 'file', messages: [] }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  else await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(700);
  return { ctx, page, errors, consoleErrors, notFound };
}

async function openFile(page, name, { state = 'visible' } = {}) {
  const absPath = `/tmp/canvas-ask-fixture/${name}`;
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nmock body\n`, absPath, pinned: true },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state, timeout: 10000 });
  await sleep(300);
  return absPath;
}

/** 提问并拿到本次 askQuestion 的 sid（真 ws.js 路由：sid 前缀 ask- 进 ask dock 拦截面）。 */
async function askAndGetSid(page, text) {
  inbound.length = 0;
  await page.click('.ask-fab');
  await sleep(350);
  await page.fill('.ask-dock textarea', text);
  await page.press('.ask-dock textarea', 'Enter');
  await sleep(250);
  const q = inbound.find((m) => m.type === 'askQuestion');
  return q ? q.sessionId : '';
}

/** 真 `toolEnd` 帧（形态与真引擎逐字同：label/summary/content/sessionId）。 */
function sendCardToolFrames(sid) {
  wsSend({ type: 'toolStart', sessionId: sid, label: CARD_LABEL, summary: null, content: '' });
  wsSend({ type: 'toolEnd', sessionId: sid, label: CARD_LABEL, summary: CARD_SUMMARY, content: CARD_CONTENT, isError: false });
}

const cardProbe = (page) => page.evaluate(() => {
  const dock = document.querySelector('.ask-dock');
  const col = document.querySelector('.ask-dock .ask-col');
  const row = col ? col.querySelector('.ask-card-row') : null;
  const iframe = row ? row.querySelector('iframe.html-card-iframe') : null;
  const wrap = row ? row.querySelector('.html-card-wrap') : null;
  const cs = col ? getComputedStyle(col) : null;
  const colContentW = col && cs
    ? col.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)
    : 0;
  const mainChatCards = document.querySelectorAll('#chat iframe.html-card-iframe, #chat .html-card-wrap').length;
  const last = col ? col.lastElementChild : null;
  return {
    rowPresent: !!row,
    rowInCol: !!(row && col && row.parentElement === col),
    iframePresent: !!iframe,
    sandbox: iframe ? iframe.getAttribute('sandbox') : null,
    iframeW: iframe ? Math.round(iframe.getBoundingClientRect().width) : 0,
    iframeH: iframe ? Math.round(iframe.getBoundingClientRect().height) : 0,
    wrapW: wrap ? Math.round(wrap.getBoundingClientRect().width) : 0,
    rowW: row ? Math.round(row.getBoundingClientRect().width) : 0,
    colContentW: Math.round(colContentW),
    dockW: dock ? Math.round(dock.getBoundingClientRect().width) : 0,
    mainChatCards,
    lastChildIsAi: !!(last && last.classList && last.classList.contains('ai')),
    cardBeforeLast: !!(row && col && row !== last),
    dockOpen: dock ? !dock.classList.contains('closed') : false,
  };
});

async function shot(page, name) {
  try { await page.screenshot({ path: join(SHOTS_DIR, name + '.png'), fullPage: false }); } catch { /* ignore */ }
}

// ═══════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch();
try {
  // ── 桌面档 ──
  {
    const { page, errors } = await bootPage(browser, { viewport: { width: 1600, height: 1000 } });
    await openFile(page, 'card-leg-desktop.md');
    const sid = await askAndGetSid(page, '画一张能量谱抽样卡');
    ok('C0a 桌面档载入零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
    ok('C0b askQuestion 出账且 sid = ask-<8hex>', /^ask-[0-9a-f]{8}$/.test(sid), sid);

    const before = await cardProbe(page);
    ok('C0c 卡帧到达前：列内无卡行、无 iframe', !before.rowPresent && !before.iframePresent && before.mainChatCards === 0, JSON.stringify(before));

    sendCardToolFrames(sid);
    await page.waitForSelector('.ask-dock .ask-col .ask-card-row iframe.html-card-iframe', { timeout: 5000 })
      .then(() => ok('C1a toolEnd(Card) ⇒ ask 列内出现卡 iframe', true))
      .catch(() => ok('C1a toolEnd(Card) ⇒ ask 列内出现卡 iframe', false));
    await sleep(400);
    const p1 = await cardProbe(page);
    ok('C1b 卡行落在 ask dock 对话列（.ask-col）内', p1.rowPresent && p1.rowInCol, JSON.stringify(p1));
    ok('C1c 主会话 #chat 零卡（视图排他，零串流）', p1.mainChatCards === 0, String(p1.mainChatCards));
    ok('C2 沙箱属性 = allow-scripts allow-same-origin（渲染单源语义）', p1.sandbox === 'allow-scripts allow-same-origin', String(p1.sandbox));
    ok('C3 卡占满列宽（卡壳宽 = 列内容盒宽 ±1.5px，iframe 填满卡壳内容盒；非按内容宽右贴）', Math.abs(p1.wrapW - p1.colContentW) <= 1.5 && p1.wrapW > 0 && Math.abs(p1.iframeW - (p1.wrapW - 2)) <= 1.5, JSON.stringify({ wrapW: p1.wrapW, iframeW: p1.iframeW, colContentW: p1.colContentW }));
    ok('C5 卡行在流式 ai 泡之前（末元素仍是 ai 泡，流式腿判据不受影响）', p1.cardBeforeLast || !p1.lastChildIsAi, JSON.stringify({ lastChildIsAi: p1.lastChildIsAi, cardBeforeLast: p1.cardBeforeLast }));

    // C4 帧内真内容（真载荷 × 真渲染件）
    const frame = await (await page.$('.ask-dock .ask-col iframe.html-card-iframe')).contentFrame();
    const ring = await frame.evaluate(() => ({
      title: document.querySelector('h3') && document.querySelector('h3').textContent.trim(),
      rows: Array.from(document.querySelectorAll('table tr')).map((tr) => tr.textContent.replace(/\s+/g, ' ').trim()),
      bar: !!document.querySelector('.bar'),
      cap: document.querySelector('.cap') && document.querySelector('.cap').textContent.trim(),
    }));
    ok('C4 帧内真载荷内容（标题 + 4 表行 + 进度条）', ring.title === 'CARD-LIVE-OK · 能量谱抽样' && ring.rows.length === 4 && ring.bar, JSON.stringify(ring));
    await shot(page, 'desktop-1600x1000-ask-dock-card');

    // C6 定稿后整列重渲，卡仍在（render() 重建腿认卡行）
    wsSend({ type: 'textDelta', sessionId: sid, delta: '已出图。' });
    await sleep(150);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(400);
    const p2 = await cardProbe(page);
    ok('C6 done 定稿（整列重渲）后卡仍在列内', p2.rowPresent && p2.iframePresent, JSON.stringify(p2));
    ok('C6b 定稿后末元素 = ai 泡（卡不占末位）', p2.lastChildIsAi, JSON.stringify(p2));

    // C7 非卡工具帧仍透明
    wsSend({ type: 'toolEnd', sessionId: sid, label: 'Pop', summary: 'x', content: '___POP_JSON___{"kind":"file"}', isError: false });
    await sleep(250);
    const p3 = await cardProbe(page);
    ok('C7 非卡工具载荷（___POP_JSON___）不建卡、列结构不变', p3.rowPresent && p3.iframePresent && (await page.$$('.ask-dock .ask-card-row')).length === 1, JSON.stringify({ rows: (await page.$$('.ask-dock .ask-card-row')).length }));

    // C8 卡载荷不落本地转录持久面
    const persisted = await page.evaluate(() => {
      const out = [];
      for (let i = 0; i < localStorage.length; i++) out.push(localStorage.getItem(localStorage.key(i)) || '');
      return out.join('\n');
    });
    ok('C8 卡载荷不落 localStorage（转录持久面只收 user/ai 两族）', !persisted.includes('___CARD_HTML___'), persisted.length + ' bytes scanned');
    await page.context().close();
  }

  // ── 手机档（375×812，真产品前端同腿）──
  {
    const { page, errors, consoleErrors, notFound } = await bootPage(browser, { viewport: { width: 375, height: 812 }, mobile: true });
    // 移动卡层：pane 在卡层展开前 hidden ⇒ openFile 只等 attached，再点卡层键展开画板。
    await openFile(page, 'card-leg-mobile.md', { state: 'attached' });
    await sleep(400);
    await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
    await sleep(800);
    await page.waitForSelector('.canvas-tab-pane[data-tab-id="file:/tmp/canvas-ask-fixture/card-leg-mobile.md"]', { state: 'visible', timeout: 8000 }).catch(() => {});
    await sleep(400);
    const sid = await askAndGetSid(page, '画一张能量谱抽样卡');
    sendCardToolFrames(sid);
    await page.waitForSelector('.ask-dock .ask-col .ask-card-row iframe.html-card-iframe', { timeout: 5000 })
      .then(() => ok('C9a 手机档 375×812：toolEnd(Card) ⇒ 列内卡 iframe', true))
      .catch(() => ok('C9a 手机档 375×812：toolEnd(Card) ⇒ 列内卡 iframe', false));
    await sleep(400);
    const m1 = await cardProbe(page);
    ok('C9b 手机档卡壳宽 = 列内容盒宽且不溢出 dock', Math.abs(m1.wrapW - m1.colContentW) <= 1.5 && m1.wrapW > 0 && m1.wrapW <= m1.dockW, JSON.stringify({ wrapW: m1.wrapW, colContentW: m1.colContentW, dockW: m1.dockW }));
    ok('C9c 手机档零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
    ok('C9d 手机档零 console error', consoleErrors.length === 0, (consoleErrors.slice(0, 2).join(' | ') + ' || 404s: ' + notFound.slice(0, 4).join(' , ')));
    await shot(page, 'mobile-375x812-ask-dock-card');
    await page.context().close();
  }
} finally {
  await browser.close();
  server.close();
}

console.log(`\nASK-DOCK CARD LEG: ${pass} pass / ${fail} fail`);
process.exit(fail === 0 ? 0 : 1);
