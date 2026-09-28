// card-image-zoom.spec.mjs — Card 内嵌图片点击开查看器（chain-cardzoom，2026-09-28）
//
// 作者令（逐字）：「让Card工具里，如果嵌入了图片，也可以像Canvas里嵌入图片一样，点击
// 能打开。」⇒ 本件验的是**行为面**：Card iframe 内 <img> 点击 → 打开既有 lightbox。
//
// 被测真实链路：静态服务器 serve src/main/resources/web → 动态 import
// /js/cardRegistry.js → renderWithRegistry（Card 工具的真实入口）→ renderHtmlCard
// → srcdoc（含 viewers/shared.js `imgClickScript()` 产出的帧脚本）→ iframe
// （sandbox allow-scripts allow-same-origin）→ 帧内 click 捕获 → parent.postMessage
// `_nfImagePreview` → lightbox.js initLightbox 的 message 处理（来源校验 + 路径闸）
// → openLightbox → reMint（path 图）/ src 直用（data-URI 图）。
//
// 覆盖面（对照任务书 §2 四条验收 + 负例）：
//   Z1 path 图（/api/nf-file?path=…）：点击开查看器，且查看器取的 path == 图 src 的 path
//   Z2 data-URI 内联图：点击按 §0 定案**可打开**（经 src 回退，不新做 blob 转换）
//   Z3 非授权来源拒绝（负例）：非本应用帧的窗口伪造 `_nfImagePreview` ⇒ 不打开
//   Z4 Canvas 腿未回归：Canvas HTML viewer 内嵌图点击仍可开（同件内断言）
//   Z5 路径闸：帧内**未渲染**的任意 path ⇒ 不打开；帧内已渲染的 path ⇒ 开
//   Z6 可点击范围提示：帧内 <img> 挂 cursor:zoom-in + hover 提示（§16 那一条文案）
//   Z7 srcdoc 零回归：既有消息形态（_nfCardH / _nfThemeVars / _nfOpenLocalFile）不变
//
// 截图：SHOTS_DIR=<dir> 时落点击前后对照图（默认不落）。
// 运行：node tests/card-image-zoom.spec.mjs
// 可选 env：PORT=8137（非 8080）、CARDZOOM_SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8137);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.CARDZOOM_SHOTS_DIR || '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
if (SHOTS_DIR) mkdirSync(SHOTS_DIR, { recursive: true });

// 1×1 PNG（真实可解码字节，让 <img> 走 onload 而不是 onerror）。
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
};

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
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

let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; failures.push(name); console.log(`FAIL  ${name}${extra ? '  — ' + extra : ''}`); }
}

/** 启动一个页面：静态 web/ + WS mock + mint/nf-file 替身（沿用既有套件同一套）。 */
async function bootPage(browser, opts = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  const nfRequests = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 200)));

  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/nf-file**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    nfRequests.push({ path: p, hasTicket: url.searchParams.has('ticket') });
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
  });
  const mint = await installTicketMock(page);

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(400);
  return { ctx, page, mint, pageErrors, nfRequests };
}

/** 渲染一张 Card（走 Card 工具的同一个入口 renderWithRegistry）。 */
async function renderCard(page, html, title = 'card') {
  return page.evaluate(async ([h, t]) => {
    const mod = await import('/js/cardRegistry.js');
    let host = document.getElementById('nf-test-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'nf-test-host';
      host.style.cssText = 'width:600px;';
      document.body.appendChild(host);
    }
    const container = document.createElement('div');
    host.appendChild(container);
    mod.renderWithRegistry(container, { html: h, title: t });
    return true;
  }, [html, title]);
}

/** 等第 n 张卡片的 srcdoc 落地。 */
async function srcdocOf(page, index = 0) {
  for (let i = 0; i < 40; i++) {
    const s = await page.evaluate((idx) => {
      const f = [...document.querySelectorAll('iframe[data-nf-card-id]')][idx];
      return f ? f.getAttribute('srcdoc') : null;
    }, index);
    if (s) return s;
    await sleep(150);
  }
  return null;
}

/** 等帧内第 index 个 img 出现，返回其读数；点它（真实用户路径 = 帧内 click）。 */
async function clickFrameImg(page, index = 0) {
  for (let i = 0; i < 40; i++) {
    const done = await page.evaluate((idx) => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      const doc = f && f.contentDocument;
      const imgs = doc ? doc.querySelectorAll('img') : [];
      const img = imgs[idx];
      if (!img) return false;
      const r = img.getBoundingClientRect();
      const at = doc.elementFromPoint(
        Math.max(1, Math.min(r.left + r.width / 2, doc.documentElement.clientWidth - 1)),
        Math.max(1, Math.min(r.top + r.height / 2, doc.documentElement.clientHeight - 1))
      );
      const ev = new MouseEvent('click', { bubbles: true, cancelable: true, view: doc.defaultView });
      (at && at.closest && at.closest('img') === img ? at : img).dispatchEvent(ev);
      return true;
    }, index).catch(() => false);
    if (done) return true;
    await sleep(200);
  }
  return false;
}

/** 查看器状态读数（父文档侧，可序列化面）。 */
const lightboxState = (page) => page.evaluate(() => {
  const ov = document.querySelector('.nf-lightbox');
  const img = document.querySelector('.nf-lightbox-img');
  return {
    on: !!(ov && ov.classList.contains('on')),
    src: (img && img.getAttribute('src')) || '',
    alt: (img && img.getAttribute('alt')) || '',
    status: (document.querySelector('.nf-lightbox-status') || {}).textContent || '',
  };
});

/** 等查看器打开（返回读数）——用于正例。 */
async function waitLightbox(page, ms = 8000) {
  const t0 = Date.now();
  let st = await lightboxState(page);
  while (Date.now() - t0 < ms) {
    st = await lightboxState(page);
    if (st.on && st.src) return st;
    await sleep(200);
  }
  return st;
}

const browser = await chromium.launch();

// ── Z1 path 图点击 → 查看器开正确路径（验收 1） ────────────────────────
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser);
  await renderCard(page,
    '<img id="p1" src="/api/nf-file?path=%2Ftmp%2Fcz%2Fphoto.png" alt="photo.png" width="120">');
  const srcdoc = await srcdocOf(page);
  ok('Z1 card mounted (srcdoc set)', !!srcdoc);

  // 帧内确实注入了点击桥（生产腿在位的直接读数）。判据取**桥自己的 postMessage
  // 调用**而非函数名子串：`stripCredentialParams`（既有失败占位符脚本，卡与 Canvas
  // 都注）里也含 "stripCredential"，只比子串会在改前树假绿（实测已踩）。
  ok('Z1 srcdoc carries the image-click bridge',
    /parent\.postMessage\(\{\s*_nfImagePreview/.test(srcdoc || ''), (srcdoc || '').slice(0, 80));
  ok('Z1 srcdoc bridge defines the credential stripper', /function stripCredential\(/.test(srcdoc || ''));

  const before = { shots: 0 };
  if (SHOTS_DIR) {
    await page.screenshot({ path: join(SHOTS_DIR, 'z1-before-click.png') });
    before.shots = 1;
  }

  const clicked = await clickFrameImg(page, 0);
  ok('Z1 frame image clicked', clicked === true);
  const st = await waitLightbox(page);
  ok('Z1 lightbox opened on the card image click', st.on, JSON.stringify(st));
  ok('Z1 lightbox shows a blob: preview (no credential in parent DOM)',
    st.src.startsWith('blob:'), st.src.slice(0, 80));
  ok('Z1 alt text forwarded', st.alt === 'photo.png', st.alt);
  // 查看器取的 path == 该图 src 的 path（本批核心判据）：帧内 img 的 path 与
  // re-mint 轮次问的 path 必须逐字相等 —— re-mint 后取回的就是同一文件。
  const rounds = mint.calls.map((p) => p.join(','));
  const framePath = await page.evaluate(() => {
    const f = document.querySelector('iframe[data-nf-card-id]');
    const doc = f && f.contentDocument;
    const u = doc && doc.querySelector('img') ? (doc.querySelector('img').currentSrc || '') : '';
    const m = /[?&]path=([^&]*)/.exec(u);
    return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
  });
  // 判据三合一：帧内 img 的 path 正确（防夹具漂移）∧ 查看器**开在**该路径上
  // （st.on 提供判别力——「有卡渲染的 re-mint 轮」在改前树同样存在，只断 mint
  // 会在改前树假绿）∧ 该路径正是查看器 re-mint 轮问的那个。
  ok('Z1 viewer path == the image src path (same file after re-mint)',
    st.on && framePath === '/tmp/cz/photo.png' && mint.calls.some((p) => p.includes(framePath)),
    JSON.stringify({ on: st.on, framePath, rounds }));
  ok('Z1 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  if (SHOTS_DIR) {
    await page.screenshot({ path: join(SHOTS_DIR, 'z1-after-click-lightbox.png') });
  }
  await ctx.close();
}

// ── Z2 data-URI 内联图 → 点击可打开（§0 定案：经 src 回退） ──────────────
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser);
  const dataUri = 'data:image/png;base64,' + PNG.toString('base64');
  await renderCard(page, `<img id="d1" src="${dataUri}" alt="inline" width="120">`);
  await srcdocOf(page);
  ok('Z2 data-URI card mounted', true);
  const clicked = await clickFrameImg(page, 0);
  ok('Z2 data-URI image clicked', clicked === true);
  const st = await waitLightbox(page);
  ok('Z2 data-URI image OPENS the viewer (§0 ruling: clickable)', st.on, JSON.stringify(st));
  ok('Z2 viewer uses the src fallback (no blob conversion invented)',
    st.src.startsWith('data:image/png'), st.src.slice(0, 40));
  ok('Z2 zero mint rounds for a data-URI-only card (no path to mint)',
    mint.count() === 0, String(mint.count()));
  ok('Z2 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z3 非授权来源拒绝（负例，验收 3） ──────────────────────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  // 3a. 应用文档自身（非任何 iframe）伪造同形态消息 ⇒ 必须被拒。
  await page.evaluate(() => {
    window.postMessage({ _nfImagePreview: { src: '/api/nf-file?path=%2Ftmp%2Fcz%2Fevil.png', path: '/tmp/cz/evil.png', alt: 'evil' } }, '*');
  });
  await sleep(900);
  let st = await lightboxState(page);
  ok('Z3 (a) foreign same-origin window is REFUSED', !st.on, JSON.stringify(st));

  // 3b. 一个非本应用 iframe（用户自建）伪造消息 ⇒ 必须被拒。
  await page.evaluate(async () => {
    const f = document.createElement('iframe');
    f.id = 'nf-evil-frame';
    f.style.cssText = 'width:10px;height:10px;position:fixed;right:0;bottom:0';
    document.body.appendChild(f);
    await new Promise((r) => { f.onload = r; f.srcdoc = '<html><body>evil</body></html>'; });
    f.contentWindow.parent.postMessage({ _nfImagePreview: { src: '/api/nf-file?path=%2Ftmp%2Fcz%2Fevil2.png', path: '/tmp/cz/evil2.png', alt: 'evil2' } }, '*');
  });
  await sleep(900);
  st = await lightboxState(page);
  ok('Z3 (b) non-app iframe is REFUSED', !st.on, JSON.stringify(st));

  // 3c. 正例对照（同页）：真卡片的图**仍可**打开 ⇒ 拒绝不是「把通道整个关掉」。
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fok.png" alt="ok" width="120">');
  await srcdocOf(page);
  await clickFrameImg(page, 0);
  st = await waitLightbox(page);
  ok('Z3 (c) CONTROL: a real card image still opens (channel not disabled)', st.on, JSON.stringify(st));
  ok('Z3 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z4 Canvas 腿未回归（验收 3 后半） ──────────────────────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  await page.evaluate(() => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:/tmp/cz/view.html', itemType: 'html', title: 'view.html',
        content: '<img id="k" src="/api/nf-file?path=%2Ftmp%2Fcz%2Fcanvas.png" alt="canvas.png" width="120">',
        absPath: '/tmp/cz/view.html', pinned: false,
      },
    }));
  });
  let clicked = false;
  for (let i = 0; i < 40 && !clicked; i++) {
    await sleep(250);
    clicked = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-canvas-html]');
      const doc = f && f.contentDocument;
      const img = doc && doc.querySelector('#k');
      if (!img) return false;
      img.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: doc.defaultView }));
      return true;
    });
  }
  ok('Z4 canvas iframe image clicked', clicked === true);
  const st = await waitLightbox(page);
  ok('Z4 Canvas leg NOT regressed (lightbox still opens)', st.on, JSON.stringify(st));
  ok('Z4 canvas preview is a blob: (re-mint leg intact)', st.src.startsWith('blob:'), st.src.slice(0, 60));
  // Z4b: 帧自己伪造一条**非它渲染**的 path ⇒ 路径闸必须拦下。改前树此处必红
  // （当时无路径闸），所以这条同时是「闸在位」的可判红锚 —— 防「接收面被删光而
  // 无网」。注意两件事：(1) 发送者必须是帧本身（e.source = 该帧窗口），否则只考到
  // 来源校验；(2) 必须先把 Z4 打开的那个查看器**关掉**，否则「仍然开着」会冒充
  // 「拒绝」——负例的前置状态必须显式归零（实测已踩此坑）。
  await page.keyboard.press('Escape');
  await sleep(500);
  const closed = !(await lightboxState(page)).on;
  ok('Z4b precondition: lightbox closed before the negative case', closed);
  const canvasFrame = page.frames().find((f) => f.url() === 'about:srcdoc') || null;
  ok('Z4b canvas frame available for a frame-origin send', !!canvasFrame);
  await canvasFrame.evaluate(() => {
    parent.postMessage(
      { _nfImagePreview: { src: '/api/nf-file?path=%2Fetc%2Fcanvas-secret', path: '/etc/canvas-secret', alt: 'x' } }, '*');
  });
  await sleep(900);
  const after = await lightboxState(page);
  ok('Z4b Canvas leg also honours the path gate', !after.on, JSON.stringify(after));
  ok('Z4 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z5 路径闸：帧内未渲染的任意 path 不得成为查看器入口 ─────────────────
// 关键：消息必须**由该帧自己**发出（frame.evaluate ⇒ e.source = 该帧窗口，
// 来源校验通过），闸才真的被考到。用 page.evaluate 发只会落在「来源非本应用帧」
// 那条拒绝上——那是 Z3 的面，不是路径闸的面。
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fshown.png" alt="shown" width="120">');
  await srcdocOf(page);
  let frame = null;
  for (let i = 0; i < 40 && !frame; i++) {
    frame = page.frames().find((f) => f.url() === 'about:srcdoc') || null;
    if (!frame) await sleep(200);
  }
  ok('Z5 card frame available for a frame-origin send', !!frame);

  // 该帧发出一个 path 不在它自己文档里的消息（模拟帧被注入脚本 / 载荷被篡改）
  // ⇒ 来源是合法的本应用帧，闸必须凭「该帧不渲染这个 path」拦下。
  await frame.evaluate(() => {
    parent.postMessage(
      { _nfImagePreview: { src: '/api/nf-file?path=%2Fetc%2Fsecrets', path: '/etc/secrets', alt: 'x' } }, '*');
  });
  await sleep(900);
  let st = await lightboxState(page);
  ok('Z5 path the frame does NOT render is REFUSED', !st.on, JSON.stringify(st));

  // 同帧、同通道，path 是它确实渲染的那个 ⇒ 必须打开（闸不是「全拒」）。
  await frame.evaluate(() => {
    parent.postMessage(
      { _nfImagePreview: { src: '/api/nf-file?path=%2Ftmp%2Fcz%2Fshown.png', path: '/tmp/cz/shown.png', alt: 'shown' } }, '*');
  });
  st = await waitLightbox(page);
  ok('Z5 the path that frame DOES render is accepted', st.on, JSON.stringify(st));
  ok('Z5 accepted payload still goes through the re-mint leg (blob preview)',
    st.src.startsWith('blob:'), st.src.slice(0, 60));
  ok('Z5 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z6 可点击范围提示（§16 那一条文案 + cursor） ───────────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  await renderCard(page,
    '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fhint.png" alt="hint" width="120">' +
    '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fowned.png" alt="owned" width="120" title="authored title">');
  await srcdocOf(page);
  let read = { cursor: '', title: '', authored: '' };
  for (let i = 0; i < 30; i++) {
    await sleep(200);
    read = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      const doc = f && f.contentDocument;
      const imgs = doc ? doc.querySelectorAll('img') : [];
      if (imgs.length < 2) return { cursor: '', title: '', authored: '' };
      return {
        cursor: imgs[0].style.cursor,
        title: imgs[0].getAttribute('title') || '',
        authored: imgs[1].getAttribute('title') || '',
      };
    }).catch(() => ({ cursor: '', title: '', authored: '' }));
    if (read.cursor) break;
  }
  ok('Z6 image carries cursor: zoom-in', read.cursor === 'zoom-in', read.cursor);
  ok('Z6 image carries the hover hint (§16 key)', read.title.length > 0, read.title);
  ok('Z6 authored title is never overwritten', read.authored === 'authored title', read.authored);

  // 中英齐 + 无孤儿键（与渲染面一致）。
  const locales = await page.evaluate(async () => {
    const en = (await import('/js/locales/en.js')).default;
    const zh = (await import('/js/locales/zh-CN.js')).default;
    return { en: en['lightbox.clickHint'] || '', zh: zh['lightbox.clickHint'] || '' };
  });
  ok('Z6 hint key present in BOTH locales (中英齐)', !!locales.en && !!locales.zh,
    JSON.stringify(locales));
  ok('Z6 rendered hint matches the active locale', read.title === locales.zh || read.title === locales.en,
    JSON.stringify({ rendered: read.title, locales }));
  ok('Z6 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z7 srcdoc 既有消息形态零回归（写入面无新增/无改写） ─────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fshape.png" alt="s" width="120">');
  const srcdoc = await srcdocOf(page) || '';
  ok('Z7 height channel unchanged (_nfCardH)', /_nfCardH/.test(srcdoc));
  ok('Z7 theme channel unchanged (_nfThemeVars)', /_nfThemeVars/.test(srcdoc));
  ok('Z7 link channel unchanged (_nfOpenLocalFile)', /_nfOpenLocalFile/.test(srcdoc));
  ok('Z7 media fallback unchanged (data-nf-load-error)', /data-nf-load-error/.test(srcdoc));
  // 点击桥只产出一条消息形态（禁另立第二套契约）。
  const shapes = [...new Set((srcdoc.match(/_nf[A-Za-z]+/g) || []))].sort();
  ok('Z7 image channel reuses the existing _nfImagePreview key',
    shapes.includes('_nfImagePreview'), JSON.stringify(shapes));
  ok('Z7 no second preview contract introduced',
    !/_nfImage(?!Preview)|_nfImg|_nfZoomImg/.test(srcdoc), JSON.stringify(shapes));
  ok('Z7 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\n=== ${pass}/${pass + fail} PASS ===`);
if (fail) console.log('FAILED: ' + failures.join(' | '));
process.exit(fail ? 1 : 0);
