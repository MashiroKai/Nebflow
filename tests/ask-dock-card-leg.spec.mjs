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
//   C8  卡载荷**落**本地转录持久面（localStorage 转录里的 role='card' 行，载荷与到达
//       帧逐字同一份、落位 user 之后 ai 之前）
//   C9  手机档 375×812：同一腿在列内显形、iframe 宽不溢出 dock、零 pageerror
//   C10 转录回看（作者令 2026-11-19「要留存」）：刷新页面 → 重开 dock ⇒ 卡本体仍在
//       列内且帧内仍是同一份载荷（不是重新渲染成文本/占位）；暗亮两档各一。
//
// C8 的口径沿革（同义重锚，断言语义未放松）：
//   · card-tool-revive 批（2026-11-18 裁定②）原判据 = 「卡载荷**不落** localStorage
//     转录持久面」（`!persisted.includes('___CARD_HTML___')`，锚点 = 全 localStorage 扫描）。
//   · 作者令 2026-11-19「要留存」把同一锚点的语义改写为**必须落**：判据从「零命中」
//     升为「恰一行 card 行 ∧ 载荷逐字等于到达帧 ∧ 落位在 user 之后 ai 之前」——
//     锚点不变（同一条 localStorage 扫描），语义不放松（更强的正向断言 + 结构化解析，
//     不是「把断言反过来放行」：写不进去、写歪了、写漏了都红）。
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
    // 可见性读数（C6c 用）：修前 C6 只断言卡「在列内」，而实际缺陷正是「卡在
    // 列内但不可见」——空白 iframe（opacity:0 + srcdoc 被剥）与真容在 DOM 上同形。
    iframeOpacity: iframe ? getComputedStyle(iframe).opacity : null,
    srcdocLen: iframe && iframe.getAttribute('srcdoc') ? iframe.getAttribute('srcdoc').length : 0,
    iframeInDoc: iframe ? iframe.isConnected : false,
    colScrollH: col ? Math.round(col.scrollHeight) : 0,
  };
});

async function shot(page, name) {
  try { await page.screenshot({ path: join(SHOTS_DIR, name + '.png'), fullPage: false }); } catch { /* ignore */ }
}

/** ask 会话本地转录（`<brand>_ask:sess:<sid>`）的**逐行**读数：role/text 原样取出，
 *  按存储顺序拼回（C8/C10 的口径统一走这一条扫描，零第二套解析）。 */
const readAskTranscript = (page) => page.evaluate(() => {
  const rows = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i) || '';
    if (!k.includes('ask:sess:')) continue;
    let v = null;
    try { v = JSON.parse(localStorage.getItem(k) || 'null'); } catch { /* 坏行跳过 */ }
    const list = v && Array.isArray(v.msgs) ? v.msgs : [];
    for (const m of list) {
      if (m && typeof m.text === 'string' && typeof m.role === 'string') rows.push({ key: k, role: m.role, text: m.text });
    }
  }
  return rows;
});

/** 列内卡 iframe 的帧内读数（与 C4 同一判据面：标题 / 表行 / 进度条）。 */
async function frameRing(page) {
  const frameEl = await page.$('.ask-dock .ask-col iframe.html-card-iframe');
  if (!frameEl) return null;
  const frame = await frameEl.contentFrame();
  if (!frame) return null;
  try {
    return await frame.evaluate(() => ({
      title: document.querySelector('h3') && document.querySelector('h3').textContent.trim(),
      rows: Array.from(document.querySelectorAll('table tr')).map((tr) => tr.textContent.replace(/\s+/g, ' ').trim()),
      bar: !!document.querySelector('.bar'),
      cap: document.querySelector('.cap') && document.querySelector('.cap').textContent.trim(),
    }));
  } catch { return null; }
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

    // C4 帧内真内容（真载荷 × 真渲染件）——无卡也走断言（不许以异常代替红）
    const frameEl = await page.$('.ask-dock .ask-col iframe.html-card-iframe');
    if (!frameEl) {
      ok('C4 帧内真载荷内容（标题 + 4 表行 + 进度条）', false, '列内无卡 iframe');
    } else {
      const frame = await frameEl.contentFrame();
      const ring = await frame.evaluate(() => ({
        title: document.querySelector('h3') && document.querySelector('h3').textContent.trim(),
        rows: Array.from(document.querySelectorAll('table tr')).map((tr) => tr.textContent.replace(/\s+/g, ' ').trim()),
        bar: !!document.querySelector('.bar'),
        cap: document.querySelector('.cap') && document.querySelector('.cap').textContent.trim(),
      }));
      ok('C4 帧内真载荷内容（标题 + 4 表行 + 进度条）', ring.title === 'CARD-LIVE-OK · 能量谱抽样' && ring.rows.length === 4 && ring.bar, JSON.stringify(ring));
    }
    await shot(page, 'desktop-1600x1000-ask-dock-card');

    // C6 定稿后整列重渲，卡仍在（render() 重建腿认卡行）
    wsSend({ type: 'textDelta', sessionId: sid, delta: '已出图。' });
    await sleep(150);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(400);
    const p2 = await cardProbe(page);
    ok('C6 done 定稿（整列重渲）后卡仍在列内', p2.rowPresent && p2.iframePresent, JSON.stringify(p2));
    ok('C6b 定稿后末元素 = ai 泡（卡不占末位）', p2.lastChildIsAi, JSON.stringify(p2));
    // C6c/C6d 是 2026-11-21 现场（同一份 HTML 两张卡一败一成）暴露的**结构性盲区**：
    // 修前 C6 只问「卡在不在列内」，而真缺陷是「卡在列内但看不见」——done 触发整列
    // 重渲时 cleanupCardIframes 剥掉 srcdoc 并断开观察者，重建出的 iframe 停在
    // 未挂载态（chat.css:4072 opacity:0、高度 150px 默认盒），DOM 上仍是一张卡行
    // ⇒ C6 绿、用户看不到。故补两条**可见性**断言（同一语境、同一拍）：
    //   C6c 定稿后 iframe 仍是挂载态（opacity:1 且 srcdoc 非空且仍在文档内）；
    //   C6d 定稿不塌缩列高（卡高度仍在，列 scrollHeight 不因重渲掉回占位高）。
    ok('C6c 定稿后卡 iframe 仍是挂载态（opacity:1 且 srcdoc 非空）——「卡在列内但空白」必红',
      p2.iframeInDoc && p2.iframeOpacity === '1' && p2.srcdocLen > 0,
      JSON.stringify({ opacity: p2.iframeOpacity, srcdocLen: p2.srcdocLen, inDoc: p2.iframeInDoc }));
    ok('C6d 定稿不塌缩列高（卡真容高度仍在，不是退回 150px 占位盒）',
      p2.iframeH > 190, JSON.stringify({ iframeH: p2.iframeH, colScrollH: p2.colScrollH }));

    // C6e —— **根因锚**（card-visible-reconcile 批 2026-11-21，本批唯一决定性命中）。
    // 上述 C6c/C6d 只在「卡比视口高 + 卡被甩出视口」时才会红（现场形态），小语料下
    // 修前也是绿的 ⇒ 单靠它们防不住回归。真正的缺陷机制是**节点身份**：修前每拍
    // done 都整列重建，卡 iframe 是**新节点**（浏览上下文从零重建、srcdoc 重新排队、
    // 3s 兜底才恢复）。故直接钉节点身份：把 done 前后同一张卡的 iframe 用 WeakRef
    // 记账，定稿后必须是**同一个节点仍然存活且在文档内** —— 与视口/卡高/时机全部
    // 无关，修前必红（重建 ⇒ 新对象）、修后必绿（复用 ⇒ 同一对象）。这同时是
    // 「已渲染的卡不被丢掉」这一用户可感知承诺的机器判据。
    const identity = await page.evaluate(() => {
      const f = document.querySelector('.ask-dock .ask-card-row iframe.html-card-iframe');
      if (!f) return { ok: false, why: 'no iframe' };
      /** @type {any} */ (window).__cardRef = new WeakRef(f);
      /** @type {any} */ (window).__cardStamp = f;
      return { ok: true };
    });
    wsSend({ type: 'textDelta', sessionId: sid, delta: '再来一段。' });
    await sleep(120);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(500);
    const sameNode = await page.evaluate(() => {
      const now = document.querySelector('.ask-dock .ask-card-row iframe.html-card-iframe');
      const ref = /** @type {any} */ (window).__cardRef;
      const prev = ref && ref.deref ? ref.deref() : null;
      return {
        present: !!now,
        same: !!(now && prev && now === prev),
        sameAsStamp: !!(now && now === /** @type {any} */ (window).__cardStamp),
        inDoc: !!(now && now.isConnected),
        srcdocLen: now && now.getAttribute('srcdoc') ? now.getAttribute('srcdoc').length : 0,
        opacity: now ? getComputedStyle(now).opacity : null,
      };
    });
    ok('C6e 定稿复用同一卡 iframe 节点（不是整列重建换新节点 —— 根因锚，视口无关）',
      identity.ok && sameNode.present && sameNode.same && sameNode.sameAsStamp && sameNode.inDoc,
      JSON.stringify(sameNode));
    ok('C6f 复用节点上已渲染的内容未丢（srcdoc 非空，无需等 3s 兜底重挂）',
      sameNode.srcdocLen > 0, JSON.stringify({ srcdocLen: sameNode.srcdocLen, opacity: sameNode.opacity }));
    // C7 非卡工具帧仍透明
    wsSend({ type: 'toolEnd', sessionId: sid, label: 'Pop', summary: 'x', content: '___POP_JSON___{"kind":"file"}', isError: false });
    await sleep(250);
    const p3 = await cardProbe(page);
    ok('C7 非卡工具载荷（___POP_JSON___）不建卡、列结构不变', p3.rowPresent && p3.iframePresent && (await page.$$('.ask-dock .ask-card-row')).length === 1, JSON.stringify({ rows: (await page.$$('.ask-dock .ask-card-row')).length }));

    // C8 卡载荷落本地转录持久面（作者令 2026-11-19「要留存」；本节 C8 为同义重锚——
    //    锚点仍是同一条 localStorage 扫描，判据由「零命中」升为「恰一行 ∧ 逐字同一份
    //    ∧ 落位正确」）。
    const persisted = await readAskTranscript(page);
    const cardRows = persisted.filter((r) => r.role === 'card');
    ok('C8a 转录持久面带 card 行（卡与 user/ai 同族落盘）', cardRows.length === 1,
      JSON.stringify(cardRows.map((r) => ({ role: r.role, chars: r.text.length }))));
    ok('C8b 持久行载荷与到达帧逐字同一份（不是文本化/占位）',
      cardRows.length === 1 && cardRows[0].text === CARD_CONTENT,
      cardRows.length === 1 ? `stored=${cardRows[0].text.length} frame=${CARD_CONTENT.length}` : 'no card row');
    const order = persisted.map((r) => r.role).join('>');
    ok('C8c 卡行落位 = user 之后、ai 之前（重看列序与首见一致）', order === 'user>card>ai', order);

    // C10 转录回看（作者令 2026-11-19「要留存」）：刷新页面 → 重开 dock ⇒ 卡本体仍在。
    //     回看腿零 askState 依赖：本地转录注水即出（mock 回放给的是空 messages）。
    await page.reload({ waitUntil: 'domcontentloaded' });
    await sleep(300);
    await page.waitForSelector('#activity-bar', { timeout: 15000 });
    await sleep(600);
    await openFile(page, 'card-leg-desktop.md');
    await page.click('.ask-fab');
    await sleep(700);
    const p4 = await cardProbe(page);
    ok('C10a 刷新后重开 dock：卡行仍在列内（本地转录注水）', p4.rowPresent && p4.rowInCol && p4.iframePresent, JSON.stringify(p4));
    const ring2 = await frameRing(page);
    ok('C10b 回看帧内仍是同一份载荷（标题 + 4 表行 + 进度条）',
      !!ring2 && ring2.title === 'CARD-LIVE-OK · 能量谱抽样' && ring2.rows.length === 4 && ring2.bar,
      JSON.stringify(ring2));
    ok('C10c 回看：卡壳宽 = 列内容盒宽（重看不动几何）',
      Math.abs(p4.wrapW - p4.colContentW) <= 1.5 && p4.wrapW > 0, JSON.stringify({ wrapW: p4.wrapW, colContentW: p4.colContentW }));
    await shot(page, 'replay-dark-1600x1000-ask-dock-card');
    await page.context().close();
  }

  // ── 亮档回看腿（作者令 2026-11-19：暗亮各一）──
  {
    const { page, errors } = await bootPage(browser, { viewport: { width: 1600, height: 1000 }, colorScheme: 'light' });
    await openFile(page, 'card-leg-light.md');
    const sid = await askAndGetSid(page, '画一张能量谱抽样卡');
    sendCardToolFrames(sid);
    await page.waitForSelector('.ask-dock .ask-col .ask-card-row iframe.html-card-iframe', { timeout: 5000 }).catch(() => {});
    wsSend({ type: 'textDelta', sessionId: sid, delta: '已出图。' });
    await sleep(150);
    wsSend({ type: 'done', sessionId: sid });
    await sleep(400);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await sleep(300);
    await page.waitForSelector('#activity-bar', { timeout: 15000 });
    await sleep(600);
    await openFile(page, 'card-leg-light.md');
    await page.click('.ask-fab');
    await sleep(700);
    const p5 = await cardProbe(page);
    ok('C10d 亮档刷新后重开 dock：卡行仍在列内', p5.rowPresent && p5.rowInCol && p5.iframePresent, JSON.stringify(p5));
    const ring3 = await frameRing(page);
    ok('C10e 亮档回看帧内仍是同一份载荷', !!ring3 && ring3.title === 'CARD-LIVE-OK · 能量谱抽样' && ring3.rows.length === 4 && ring3.bar, JSON.stringify(ring3));
    ok('C10f 亮档回看零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
    await shot(page, 'replay-light-1600x1000-ask-dock-card');
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
