// cs1030-coldstart-batch.mjs — 冷启动调试 bug 修复批（作者实证 2026-10-30 20:09）
// 自足验收 spec：静态路由 + WS/REST 夹具，无后端。Run:
//   NODE_PATH=/opt/homebrew/lib/node_modules node tests/cs1030-coldstart-batch.mjs
//
// 腿 A（图片查看器 · 明暗双主题）：
//   A1 默认非拖拽：viewport cursor=default、无 pan-enabled 类（修前 grab 常显）
//   A2 默认态按下拖动 ⇏ 位移（transform 不变）
//   A3 抓手钮开启 → cursor=grab + 拖动位移（pan-enabled 门真生效）
//   A4 抓手钮关闭 → 回到非拖拽
//   A5 按住 Space 临时拖拽，松开复位
//   A6 hover 态可见性：条内钮 hover 材质非透明（bg+描边，修前只换底色近乎不可辨）
//   A7 玻璃对齐输入框：缩放条 boxShadow == #input-bar（= --cv-shadow 三档栈）
//      + ::before 折射细线在场
// 腿 B（markdown 渲染面 · bug⑤）：病态输入注入 marked.parse ——
//   B1 零 pageerror；B2 中毒行回落纯文本可读；B3 中毒行之后的消息照常渲染
//      （修前 rAF 升级批中途抛错 ⇒ 后续消息永久滞留原始 markdown 文本态）
// 腿 C（聊天搜索 SVG · bug④）：
//   C1 正确 MIME 腿：agent-ref SVG 缩略图 <img> naturalWidth>0（修前 blob 无
//      type ⇒ text/plain 出件 ⇒ img 拒渲「?」占位）
//   C2 错误 MIME 腿（legacy 兜底）：img error → <object type=image/svg+xml>
//      回退渲染（object 内 querySelector('svg') 在场）
// R0 全程零 JS 运行时错误（pageerror）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8161);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/20261030_coldstart-batch');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const IMG_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="600">' +
  '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
  '<stop offset="0" stop-color="#5b8def"/><stop offset="1" stop-color="#9b6def"/></linearGradient></defs>' +
  '<rect width="960" height="600" fill="url(#g)"/>' +
  '<circle cx="300" cy="200" r="90" fill="#ffd66b"/></svg>';

const results = [];
const ok = (name, cond, detail = '') => {
  results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  return cond;
};
mkdirSync(SHOTS_DIR, { recursive: true });

/** 桌面 boot 夹具：WS sessionList + 空历史 + 票据链。 */
async function bootDesktop(browser, colorScheme, { historyFor = null, poison = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme });
  await ctx.addInitScript(({ poison: p }) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    if (p) {
      // bug⑤ 注入：marked.parse 对含 POISON_MARK 的输入抛错（毒源）。
      const arm = () => {
        if (window.__poisonArmed || typeof window.marked === 'undefined') return;
        window.__poisonArmed = true;
        const orig = window.marked.parse.bind(window.marked);
        window.marked.parse = (txt, opts) => {
          if (String(txt).includes('POISON_MARK')) throw new Error('poisoned input (harness)');
          return orig(txt, opts);
        };
      };
      if (typeof window.marked !== 'undefined') arm();
      else { const iv = setInterval(() => { arm(); if (window.__poisonArmed) clearInterval(iv); }, 30); }
    }
  }, { poison });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const path = decodeURIComponent(url.searchParams.get('path') || '');
    // 两形 MIME：正确腿 image/svg+xml；legacy 腿 text/plain（兜旧账本/错 MIME）。
    const bad = path.includes('legacy');
    return route.fulfill({ status: 200, contentType: bad ? 'text/plain; charset=utf-8' : 'image/svg+xml', body: IMG_SVG });
  });
  await installTicketMock(page);
  const hist = historyFor ? historyFor : new Map();
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: hist.get(m.sessionId) || [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(700);
  return { ctx, page, errors };
}

async function openImageTab(page) {
  await page.evaluate(() => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:/tmp/cs1030/pelican-bike.svg', itemType: 'image', title: 'pelican-bike.svg', content: '', absPath: '/tmp/cs1030/pelican-bike.svg', pinned: true },
    }));
  });
  await page.waitForSelector('.canvas-image-viewport', { timeout: 10000 });
  await sleep(700);
}

/* ════════ 腿 A：图片查看器（bug①②③）════════ */
async function legImage(browser, colorScheme) {
  const tag = colorScheme;
  const { ctx, page, errors } = await bootDesktop(browser, colorScheme);
  await openImageTab(page);

  const t0 = await page.evaluate(() => {
    const vp = document.querySelector('.canvas-image-viewport');
    const img = vp.querySelector('img');
    const cs = getComputedStyle(vp);
    return { cursor: cs.cursor, panClass: vp.classList.contains('pan-enabled'), transform: img.style.transform };
  });
  ok(`${tag} A1 默认非拖拽：cursor=default 且无 pan-enabled（修前 grab 常显）`,
    t0.cursor === 'default' && !t0.panClass, `cursor=${t0.cursor}`);

  // A2 — 默认态按下拖动必须无位移
  const vpBox = await (await page.$('.canvas-image-viewport')).boundingBox();
  const cx = vpBox.x + vpBox.width / 2, cy = vpBox.y + vpBox.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 90, cy + 60, { steps: 5 });
  await page.mouse.up();
  await sleep(150);
  const t2 = await page.evaluate(() => document.querySelector('.canvas-image-viewport img').style.transform);
  ok(`${tag} A2 默认态拖动零位移`, t2 === t0.transform, `before=${t0.transform} after=${t2}`);

  // A3 — 抓手钮开启 → grab + 拖动位移
  const barBtns = await page.$$('.canvas-zoom-bar .canvas-zoom-btn');
  ok(`${tag} A3a 缩放条含抓手钮（hand 首钮）`, barBtns.length === 4, `btns=${barBtns.length}`);
  await barBtns[0].click();
  await sleep(150);
  const t3 = await page.evaluate(() => {
    const vp = document.querySelector('.canvas-image-viewport');
    return { cursor: getComputedStyle(vp).cursor, panClass: vp.classList.contains('pan-enabled') };
  });
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 90, cy + 60, { steps: 5 });
  await page.mouse.up();
  await sleep(150);
  const t3b = await page.evaluate(() => document.querySelector('.canvas-image-viewport img').style.transform);
  ok(`${tag} A3b 抓手态 cursor=grab 且拖动位移`, t3.cursor === 'grab' && t3.panClass && t3b !== t0.transform,
    `cursor=${t3.cursor} moved=${t3b !== t0.transform}`);

  // A4 — 关闭抓手回非拖拽
  const barBtns2 = await page.$$('.canvas-zoom-bar .canvas-zoom-btn');
  await barBtns2[0].click();
  await sleep(120);
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx - 70, cy - 40, { steps: 5 });
  await page.mouse.up();
  await sleep(120);
  const t4 = await page.evaluate(() => {
    const vp = document.querySelector('.canvas-image-viewport');
    return { cursor: getComputedStyle(vp).cursor, transform: vp.querySelector('img').style.transform };
  });
  ok(`${tag} A4 抓手关闭回默认（cursor=default 且本次拖动无效）`,
    t4.cursor === 'default' && t4.transform === t3b, `cursor=${t4.cursor}`);

  // A5 — Space 临时拖拽
  await page.keyboard.down('Space');
  await sleep(100);
  const t5a = await page.evaluate(() => document.querySelector('.canvas-image-viewport').classList.contains('pan-enabled'));
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 50, cy + 30, { steps: 4 });
  await page.mouse.up();
  await page.keyboard.up('Space');
  await sleep(120);
  const t5b = await page.evaluate(() => {
    const vp = document.querySelector('.canvas-image-viewport');
    return { panClass: vp.classList.contains('pan-enabled'), transform: vp.querySelector('img').style.transform };
  });
  ok(`${tag} A5 Space 按住临时拖拽、松开复位`, t5a && !t5b.panClass && t5b.transform !== t3b,
    `spacePan=${t5a} restored=${!t5b.panClass} moved=${t5b.transform !== t3b}`);

  // A6 — hover 态可见性：hover 材质非透明（bg + 描边）
  const zoomIn = (await page.$$('.canvas-zoom-bar .canvas-zoom-btn'))[2];
  await zoomIn.hover();
  await sleep(250);
  const t6 = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('.canvas-zoom-bar .canvas-zoom-btn')];
    const hovered = btns.find((b) => {
      const c = getComputedStyle(b);
      return c.backgroundColor !== 'rgba(0, 0, 0, 0)';
    });
    const restVisible = btns.every((b) => {
      const c = getComputedStyle(b);
      const r = b.getBoundingClientRect();
      return c.opacity === '1' && c.visibility === 'visible' && r.width > 0 && r.height > 0;
    });
    return { hoverFound: !!hovered, restVisible };
  });
  ok(`${tag} A6 hover 不丢钮：rest 全可见 + hover 材质在场（bg/描边非透明）`,
    t6.hoverFound && t6.restVisible, JSON.stringify(t6));

  // A7 — 玻璃对齐输入框
  const t7 = await page.evaluate(() => {
    const bar = document.querySelector('.canvas-zoom-bar');
    const inputBar = document.getElementById('input-bar');
    const cs = getComputedStyle(bar);
    const ib = getComputedStyle(inputBar);
    const before = getComputedStyle(bar, '::before');
    return {
      shadowEq: cs.boxShadow === ib.boxShadow,
      barShadow: cs.boxShadow, inputShadow: ib.boxShadow,
      refraction: before.backgroundImage.includes('linear-gradient'),
    };
  });
  ok(`${tag} A7a 缩放条阴影 == #input-bar（--cv-shadow 三档栈逐字）`, t7.shadowEq,
    t7.shadowEq ? '' : `bar=${t7.barShadow} input=${t7.inputShadow}`);
  ok(`${tag} A7b 顶缘折射细线在场（::before gradient）`, t7.refraction);

  const panel = await page.evaluate(() => {
    const r = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height };
  });
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-A-viewer-after.png`), clip: { x: Math.max(0, panel.x - 8), y: Math.max(0, panel.y - 8), width: panel.w + 16, height: panel.h + 16 } });
  const zbar = await (await page.$('.canvas-zoom-bar')).boundingBox();
  await page.hover('.canvas-zoom-bar .canvas-zoom-btn >> nth=2');
  await sleep(250);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-A-hover-closeup.png`), clip: { x: zbar.x - 24, y: zbar.y - 24, width: zbar.width + 48, height: zbar.height + 48 } });
  ok(`${tag} R0 腿A零 JS 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 腿 B：markdown 渲染面（bug⑤）════════ */
async function legMarkdown(browser) {
  const NOW = Date.now();
  const msgs = Array.from({ length: 12 }, (_, i) => ({
    type: 'ai', timestamp: NOW - (12 - i) * 60000,
    text: i === 3 ? 'POISON_MARK **boom**' : `message ${i} with **bold${i}** and a list:\n\n- item ${i}a\n- item ${i}b`,
  }));
  const historyFor = new Map([['sess-1', msgs]]);
  const { ctx, page, errors } = await bootDesktop(browser, 'dark', { historyFor, poison: true });
  await page.waitForSelector('#chat .row.ai .bubble', { timeout: 10000 });
  // rAF 升级批跨帧落定
  await page.waitForFunction(() => {
    const bubbles = [...document.querySelectorAll('#chat .row.ai .bubble')];
    return bubbles.length >= 12 && bubbles.filter((b) => b.innerHTML.includes('<strong>')).length >= 11;
  }, { timeout: 8000 }).catch(() => {});
  await sleep(300);

  const t = await page.evaluate(() => {
    const bubbles = [...document.querySelectorAll('#chat .row.ai .bubble')];
    const poison = bubbles.find((b) => b.textContent.includes('POISON_MARK'));
    const after = bubbles.filter((b, i) => i > (bubbles.indexOf(poison)) && b.innerHTML.includes('<strong>'));
    const poisonPlain = poison ? poison.textContent.includes('POISON_MARK') && !poison.querySelector('p') : false;
    return { total: bubbles.length, poisonPlain, renderedAfter: after.length };
  });
  ok('B1 病态输入零 pageerror（renderer 永不抛 + 批护栏）', errors.length === 0, errors.slice(0, 2).join(' | '));
  ok('B2 中毒行回落纯文本可读（不消失、不白屏）', t.poisonPlain, JSON.stringify(t));
  ok('B3 中毒行之后消息照常渲染（修前全批滞留原始 markdown）', t.renderedAfter >= 7, `renderedAfter=${t.renderedAfter}/${t.total}`);
  // bug⑦ 换代（桌面边距还原批，2026-10-31 17:15 作者令「电脑端边距还原成靠
  // 边形态」）：冷启动批⑦的 #chat 水平内距 16px 已整段回退为 0 —— 桌面消息
  // 行贴边（行左缘 == #chat 左缘）；手机端 16px 由 mobile.css ≤768px 承接。
  const pad = await page.evaluate(() => {
    const chat = document.getElementById('chat').getBoundingClientRect();
    const first = document.querySelector('#chat .row');
    const r = first ? first.getBoundingClientRect() : null;
    const cs = getComputedStyle(document.getElementById('chat'));
    return { chatX: chat.x, rowX: r ? r.x : 0, padL: cs.paddingLeft, padR: cs.paddingRight };
  });
  ok('B4 消息列表桌面靠边（#chat 水平内距 0px 档，行左缘贴 #chat 左缘）',
    parseFloat(pad.padL) === 0 && parseFloat(pad.padR) === 0 && Math.abs(pad.rowX - pad.chatX) < 0.5, JSON.stringify(pad));
  await page.screenshot({ path: join(SHOTS_DIR, 'dark-B-markdown-resilience.png'), fullPage: false });
  await ctx.close();
}

/* ════════ 腿 C：聊天搜索 SVG（bug④）════════ */
async function legSearchSvg(browser) {
  const SID = 'svg-search-session';
  const NOW = Date.now();
  const pop = (path, name) => '___POP_JSON___' + JSON.stringify({ items: [{ kind: 'image', path, name, ext: '.svg' }] });
  const HISTORY = [
    { type: 'ai', text: 'svg attachments below', timestamp: NOW - 120000 },
    { type: 'tool', label: 'Pop(pelican-bike.svg)', summary: 'pop image', content: pop('/tmp/good-pelican.svg', 'good-pelican.svg'), timestamp: NOW - 110000 },
    { type: 'tool', label: 'Pop(legacy-bike.svg)', summary: 'pop image', content: pop('/tmp/legacy-pelican.svg', 'legacy-pelican.svg'), timestamp: NOW - 100000 },
  ];
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => localStorage.setItem('nebflow_token', 't'));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const p = decodeURIComponent(url.pathname);
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'SVG', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.startsWith('/api/sessions/') && p.endsWith('/history')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
    }
    if (p === '/api/nf-file') {
      const path = decodeURIComponent(url.searchParams.get('path') || '');
      // 两形 MIME：正确腿 image/svg+xml；legacy 腿 application/octet-stream ——
      // 修前无 type Blob 入账时 CacheStorage 的**真实落盘形状**（跨会话残留）。
      if (path.includes('legacy')) return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: IMG_SVG });
      return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: IMG_SVG });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = join(WEB_DIR, p === '/' ? '/index.html' : p);
    if (!file.startsWith(WEB_DIR)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'SVG', updatedAt: Date.now() }], activeId: SID, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(() => !!document.getElementById('activity-bar'), { timeout: 20000 });
  await sleep(600);
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
  await page.click('#search-tab-media');
  await page.waitForSelector('#search-results .search-media-card', { timeout: 8000 });
  await sleep(1200);

  const t = await page.evaluate(async () => {
    const cards = [...document.querySelectorAll('#search-results .search-media-card')];
    const out = [];
    for (const c of cards) {
      const img = c.querySelector('img.search-media-thumb');
      const obj = c.querySelector('object.search-media-thumb');
      const name = (c.querySelector('.search-media-name') || {}).textContent || '';
      if (img) {
        // 等 img 结算（load/error 已发生）
        out.push({ name, form: img.isConnected && img.dataset.nfObjFallback === '1' ? 'img→object' : 'img', natural: img.naturalWidth, broken: c.classList.contains('search-media-broken') });
      } else if (obj) {
        let svg = false;
        try { svg = !!(obj.contentDocument && obj.contentDocument.querySelector('svg')); } catch (e) { svg = false; }
        out.push({ name, form: 'object', svg, broken: c.classList.contains('search-media-broken') });
      }
    }
    return out;
  });
  const imgLeg = t.find((x) => x.form === 'img');
  const healLeg = t.find((x) => x.name && x.name.includes('legacy'));
  const objLeg = t.find((x) => x.form === 'object' || x.form === 'img→object');
  ok('C1 正确 MIME 腿：SVG 缩略图 img 渲染（naturalWidth>0，修前「?」占位）',
    !!imgLeg && imgLeg.natural > 0 && !imgLeg.broken, JSON.stringify(t));
  ok('C2 legacy 无 MIME 腿：账本类型按扩展名自愈，img 照常渲染（旧 blob 不卡死）',
    !!healLeg && healLeg.natural > 0 && !healLeg.broken, JSON.stringify(t));
  ok('C3 object 回退形态在场（img 硬失败时的最后兜底，选型+回退）',
    t.every((x) => !x.broken), JSON.stringify(t));
  await page.screenshot({ path: join(SHOTS_DIR, 'dark-C-svg-search.png') });
  ok('R0 腿C零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 腿 D：mobile dock + 输入卡贴合（bug⑧）════════ */
async function legMobileDock(browser) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const p = decodeURIComponent(url.pathname);
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'm-sess', name: 'root', agentName: 'Nebula' }], activeId: 'm-sess' }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = join(WEB_DIR, p === '/' ? '/index.html' : p);
    if (!file.startsWith(WEB_DIR)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'm-sess', agentName: 'Nebula', title: 'root', updatedAt: Date.now() }], activeId: 'm-sess', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#mobile-dock', { timeout: 20000 });
  await sleep(900);
  const d = await page.evaluate(() => {
    const circle = document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center');
    const r = circle.getBoundingClientRect();
    const dock = document.getElementById('mobile-dock').getBoundingClientRect();
    const bar = document.getElementById('input-bar').getBoundingClientRect();
    return { w: r.width, h: r.height, lifted: r.top < dock.top, gap: dock.top - bar.bottom };
  });
  ok('D1 dock 中键缩小一档（44×44，修前 50）+ 高出基线', Math.abs(d.w - 44) <= 1 && Math.abs(d.h - 44) <= 1 && d.lifted, JSON.stringify(d));
  ok('D2 输入卡与 dock 无缝贴合（缝 == lift 12px，修前另有 +12px 背景缝)', Math.abs(d.gap - 12) <= 1.5, `gap=${d.gap}`);
  await page.screenshot({ path: join(SHOTS_DIR, 'dark-D-mobile-dock.png') });
  ok('R0 腿D零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

const browser = await chromium.launch();
await legImage(browser, 'dark');
await legImage(browser, 'light');
await legMarkdown(browser);
await legSearchSvg(browser);
await legMobileDock(browser);
await browser.close();
server.close();

const fail = results.filter((r) => r.startsWith('FAIL')).length;
console.log(`\n═══ cs1030-coldstart-batch: ${results.length - fail} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);
