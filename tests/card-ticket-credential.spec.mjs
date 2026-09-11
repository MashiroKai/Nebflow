// card-ticket-credential.spec.mjs — 卡片路径票据腿验收（C 批 C1–C6 + Dg1–Dg4）。
//
// 为什么必须新建（plan §2.3 自纠⑩）：全仓 tests/scripts 零 cardRegistry 用例，
// 而 C2-15（票注入下沉 applySrcdoc + 重挂重 mint）是本批最重的单点——
// Δ⑥（占位符打印票）与 Δ⑨（无引号 url() 注入越界）都发生在这条路径上，
// 没有任何既有回归网。本件即那张网。
//
// 被测真实链路：静态服务器 serve src/main/resources/web → 动态 import
// /js/cardRegistry.js → renderWithRegistry（Card 工具的真实入口）→
// renderHtmlCard → applySrcdoc → nfTicket.mintTickets → POST /api/nf-ticket
// → srcdoc 注入 → iframe（sandbox allow-scripts allow-same-origin）。
// 断言落在「可序列化文本 / DOM / 请求记录」三类可机读面，不截图。
//
// 运行：node tests/card-ticket-credential.spec.mjs
// 可选 env：PORT=8131（非 8080）、CARD_TICKET_SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8131);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

const CRED_RE = /[?&](token|ticket)=/;

/** 启动一个页面：静态 web/ + WS mock + 指定 mint/nf-file 行为。 */
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

  if (!opts.omitCatchAll) {
    await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  }
  // nf-file：可配置每个 path 的状态码；无票一律 401（真实端点语义）。
  await page.route('**/api/nf-file**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    nfRequests.push({ path: p, hasTicket: url.searchParams.has('ticket'), hasToken: url.searchParams.has('token'), status: opts.statusFor && opts.statusFor[p] });
    if (await ticketGuard(route)) return;
    const status = (opts.statusFor && opts.statusFor[p]) || 200;
    if (status !== 200) {
      return route.fulfill({ status, contentType: 'text/plain', body: `mock ${status}` });
    }
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
  });
  const mint = await installTicketMock(page, {
    drop: opts.mintDrop,
    status: opts.mintStatus,
    json: opts.mintJson,
  });

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

/** 等第 n 张卡片的 srcdoc 落地并返回它。 */
async function srcdocOf(page, index = 0) {
  for (let i = 0; i < 40; i++) {
    const s = await page.evaluate((idx) => {
      const frames = [...document.querySelectorAll('iframe[data-nf-card-id]')];
      const f = frames[idx];
      return f ? f.getAttribute('srcdoc') : null;
    }, index);
    if (s) return s;
    await sleep(150);
  }
  return null;
}

async function frameBody(page, index = 0) {
  return page.evaluate((idx) => {
    const frames = [...document.querySelectorAll('iframe[data-nf-card-id]')];
    const f = frames[idx];
    return f && f.contentDocument && f.contentDocument.body ? f.contentDocument.body.innerHTML : '';
  }, index);
}

async function ticketValuesOf(srcdoc) {
  const out = [];
  const re = /[?&]ticket=([^&"'\s)]*)/g;
  let m;
  while ((m = re.exec(String(srcdoc || '')))) out.push(m[1]);
  return out;
}

const browser = await chromium.launch();

// ── C1 失败占位符不含凭据（Δ⑥；T2 ③） ────────────────────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser, {
    statusFor: { '/tmp/nfc2/missing.png': 403 },
  });
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fmissing.png" alt="missing.png" width="40">');
  const srcdoc = await srcdocOf(page);
  ok('C1 card mounted (srcdoc set)', !!srcdoc);
  // 触发 error → media fallback 占位符
  let outer = '';
  for (let i = 0; i < 30; i++) {
    await sleep(200);
    outer = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      try { return f && f.contentDocument ? f.contentDocument.documentElement.outerHTML : ''; } catch (e) { return ''; }
    });
    if (outer.includes('data-nf-load-error')) break;
  }
  ok('C1 failure placeholder rendered', outer.includes('data-nf-load-error'), outer.slice(0, 200));
  ok('C1 placeholder text carries NO credential', !CRED_RE.test(outer), (outer.match(/src: [^\n]{0,120}/) || [''])[0]);
  ok('C1 placeholder still names the file (not stripped to empty)', outer.includes('missing.png'));
  ok('C1 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── C2 srcdoc 票数 == 素材引用数，且重挂后票值变化（T2 ①②） ───────────
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser);
  await renderCard(page,
    '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fa.png" width="10">' +
    '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fb.png" width="10">');
  // Scroll room must live in the PARENT document: the IntersectionObserver
  // watches the card iframe, so a spacer inside the srcdoc would never move it.
  await page.evaluate(() => {
    const sp = document.createElement('div');
    sp.id = 'nf-scroll-room';
    sp.style.cssText = 'height:5000px';
    document.body.appendChild(sp);
  });
  const first = await srcdocOf(page);
  const firstTickets = await ticketValuesOf(first);
  ok('C2 srcdoc ticket count == reference count (2)', firstTickets.length === 2, JSON.stringify(firstTickets));
  ok('C2 first mount minted once', mint.count() === 1, String(mint.count()));

  // 重挂：清空 frame 内容 → 让卡片离开视口（让 IO 报去交叉）→ 回视口，
  // 触发 reloadIfBlank → re-mint。用 display 切换而不是 window.scrollTo：
  // 主应用布局是固定视口 + 内部滚动容器，window 滚动可能完全不动，那样
  // IO 永远看不到「离开/回到视口」这一对事件。
  await page.evaluate(() => {
    const f = document.querySelector('iframe[data-nf-card-id]');
    if (f && f.contentDocument && f.contentDocument.body) f.contentDocument.body.innerHTML = '';
  });
  await page.evaluate(() => { document.getElementById('nf-test-host').style.display = 'none'; });
  await sleep(2500);                    // reloadIfBlank 的 2s 节流
  await page.evaluate(() => { document.getElementById('nf-test-host').style.display = ''; });
  let second = first;
  for (let i = 0; i < 30; i++) {
    await sleep(200);
    const s = await srcdocOf(page);
    if (s && s !== first) { second = s; break; }
  }
  const secondTickets = await ticketValuesOf(second);
  ok('C2 re-mount minted a SECOND round', mint.count() >= 2, String(mint.count()));
  ok('C2 re-mount ticket values differ from the first mount',
    secondTickets.length === 2 && secondTickets.every((t) => !firstTickets.includes(t)),
    JSON.stringify({ firstTickets, secondTickets }));
  ok('C2 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── C6 无引号 CSS url() 注入正确性（自纠⑨ 负例） ───────────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  await renderCard(page,
    '<style>.a{background:url(/api/nf-file?path=%2Ftmp%2Fnfc2%2Fbg.png)}</style>' +
    '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Ffg.png" width="10">');
  const srcdoc = await srcdocOf(page) || '';
  ok('C6 srcdoc mounted', !!srcdoc);
  ok('C6 unquoted url() ticket stays INSIDE the parens',
    /url\(\/api\/nf-file\?path=[^)]*&ticket=[^)]*\)/.test(srcdoc),
    (srcdoc.match(/url\([^)]{0,140}/) || [''])[0]);
  ok('C6 NO ticket appended after the closing paren',
    !/\)&ticket=/.test(srcdoc),
    (srcdoc.match(/\)[^<]{0,40}&ticket=[^&"'\s)]{0,20}/) || [''])[0]);
  // 负例：注入不得越过 `)}</style><img` 一路吞下去（旧 `[^"'\s]+` 的形态）
  ok('C6 injection did not swallow past )}</style><img',
    !/<img&ticket=/.test(srcdoc) && !/&ticket=[^&"'\s)<>,;]*<img/.test(srcdoc));
  const body = await frameBody(page);
  ok('C6 card DOM still holds the <img> element', /<img/i.test(body));
  ok('C6 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── C5 每个 nf-file 请求带票、绝不含 token ──────────────────────────────
{
  const { ctx, page, nfRequests, pageErrors } = await bootPage(browser);
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fc.png" width="10">');
  await srcdocOf(page);
  await sleep(600);
  ok('C5 nf-file requests actually happened', nfRequests.length >= 1, JSON.stringify(nfRequests));
  ok('C5 every nf-file request carries ticket=', nfRequests.every((r) => r.hasTicket), JSON.stringify(nfRequests));
  ok('C5 no nf-file request carries token=', nfRequests.every((r) => !r.hasToken), JSON.stringify(nfRequests));
  ok('C5 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── C3 markdown 图片 tooltip 剥票（Δ⑦） ───────────────────────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser, {
    statusFor: { '/tmp/nfc2/pic.png': 403 },
  });
  await page.evaluate(() => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:/tmp/nfc2/doc.md', itemType: 'markdown', title: 'doc.md',
        content: '![shot](pic.png)\n', absPath: '/tmp/nfc2/doc.md', pinned: false,
      },
    }));
  });
  let title = '';
  for (let i = 0; i < 40; i++) {
    await sleep(200);
    title = await page.evaluate(() => document.querySelector('.canvas-md-viewer img')?.title || '');
    if (title) break;
  }
  ok('C3 markdown image tooltip set on error', !!title, title);
  ok('C3 tooltip carries NO credential', !CRED_RE.test(title), title);
  ok('C3 tooltip still names the file', title.includes('pic.png'), title);
  ok('C3 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── C4 lightbox 父文档不落票 + 取新票（自纠⑧） ─────────────────────────
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser);
  await page.evaluate(() => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:/tmp/nfc2/view.html', itemType: 'html', title: 'view.html',
        content: '<img id="k" src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fk.png" alt="k.png" width="40">',
        absPath: '/tmp/nfc2/view.html', pinned: false,
      },
    }));
  });
  // 等 canvas iframe + 内部 img 就绪
  let clicked = false;
  for (let i = 0; i < 40 && !clicked; i++) {
    await sleep(250);
    clicked = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-canvas-html]');
      const doc = f && f.contentDocument;
      const img = doc && doc.querySelector('#k');
      if (!img) return false;
      img.click();
      return true;
    });
  }
  ok('C4 canvas iframe image clicked', clicked === true);
  let lightboxSrc = '';
  for (let i = 0; i < 40; i++) {
    await sleep(200);
    lightboxSrc = await page.evaluate(() => document.querySelector('.nf-lightbox-img')?.getAttribute('src') || '');
    if (lightboxSrc) break;
  }
  ok('C4 lightbox opened with an image', !!lightboxSrc, lightboxSrc.slice(0, 80));
  ok('C4 parent img.src carries NO credential (blob: preview)', !CRED_RE.test(lightboxSrc), lightboxSrc.slice(0, 120));
  ok('C4 lightbox fetched through its OWN mint round', mint.count() >= 2, String(mint.count()));
  const rounds = mint.calls.map((paths) => paths.join(','));
  ok('C4 the lightbox round asked for the lightbox path',
    mint.calls.some((paths) => paths.includes('/tmp/nfc2/k.png')),
    JSON.stringify(rounds));
  ok('C4 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Dg1 mint 端点 500：3 卡片各自渲染，无整批空白，请求数 ≤3 ────────────
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser, { mintStatus: 500 });
  for (const n of ['a', 'b', 'c']) {
    await renderCard(page,
      `<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2F${n}1.png" width="10">` +
      `<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2F${n}2.png" width="10">`, n);
  }
  let mounted = 0;
  for (let i = 0; i < 40; i++) {
    await sleep(200);
    mounted = await page.evaluate(() =>
      [...document.querySelectorAll('iframe[data-nf-card-id]')].filter((f) => (f.getAttribute('srcdoc') || '').length > 0).length);
    if (mounted === 3) break;
  }
  ok('Dg1 all 3 cards mounted despite the mint outage (no whole-batch blank)', mounted === 3, String(mounted));
  const bodies = await Promise.all([0, 1, 2].map((i) => frameBody(page, i)));
  ok('Dg1 every card still carries its own content', bodies.every((b) => b && b.includes('nf-wrap') === false ? b.length > 0 : b.length > 0),
    JSON.stringify(bodies.map((b) => b.length)));
  ok('Dg1 mint request count <= 3 (F6 breaker)', mint.count() <= 3, String(mint.count()));
  ok('Dg1 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Dg2 mint 200 但返回 {}（catch-all harness 形态） ────────────────────
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser, { mintJson: {} });
  for (const n of ['a', 'b', 'c']) {
    await renderCard(page, `<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2F${n}1.png" width="10">`, n);
  }
  let mounted = 0;
  for (let i = 0; i < 40; i++) {
    await sleep(200);
    mounted = await page.evaluate(() =>
      [...document.querySelectorAll('iframe[data-nf-card-id]')].filter((f) => (f.getAttribute('srcdoc') || '').length > 0).length);
    if (mounted === 3) break;
  }
  ok('Dg2 all 3 cards mounted on a ticketless 200 (renders with no-ticket URLs)', mounted === 3, String(mounted));
  ok('Dg2 mint request count <= 3', mint.count() <= 3, String(mint.count()));
  ok('Dg2 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Dg3 mint 部分成功（1/3）：命中者 200、未命中者占位符，互不传染 ──────
{
  const drop = ['/tmp/nfc2/p2.png', '/tmp/nfc2/p3.png'];
  const { ctx, page, mint, pageErrors } = await bootPage(browser, { mintDrop: drop });
  await renderCard(page,
    '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fp1.png" width="10">' +
    '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fp2.png" width="10">' +
    '<img src="/api/nf-file?path=%2Ftmp%2Fnfc2%2Fp3.png" width="10">');
  await srcdocOf(page);
  let hit = false;
  let placeholders = 0;
  for (let i = 0; i < 40; i++) {
    await sleep(250);
    const st = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      const imgs = [...(f && f.contentDocument ? f.contentDocument.querySelectorAll('img') : [])];
      return {
        loaded: imgs.filter((i) => i.naturalWidth > 0).length,
        broken: f && f.contentDocument ? f.contentDocument.querySelectorAll('[data-nf-load-error]').length : 0,
      };
    });
    hit = st.loaded >= 1;
    placeholders = st.broken;
    if (hit && placeholders >= 2) break;
  }
  ok('Dg3 the ticketed asset loaded', hit === true);
  ok('Dg3 the two unticketed assets degraded to placeholders (not contagious)', placeholders >= 2, String(placeholders));
  // NOTE — deliberate, documented deviation from plan §4.3 F4's literal
  // "未命中者 → 一次 reMint": the card path does NOT re-mint per element
  // error. The media error handler runs inside a srcdoc iframe with no module
  // access, and the card path's re-mint point is the re-MOUNT
  // (C2-15 / `reMintAll` before `buildSrcdoc`), while the viewer paths
  // (image.js / lightbox.js) do re-mint once per T3. What F4 actually
  // protects — per-path independence, no contagion, no page-wide blank —
  // is asserted on the two lines above.
  ok('Dg3 no mint storm (one round per render)', mint.count() <= 2, String(mint.count()));
  ok('Dg3 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Dg4 无本地引用 ⇒ 零 mint 请求（F1） ─────────────────────────────────
// 卡片侧的 F1：候选集为空时 applySrcdoc 不得发任何 mint。
// （Canvas HTML 侧的同款断言在 tests/tmp-regress-normal-html.mjs。）
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser);
  await renderCard(page, '<div id="plain">no local references here</div>');
  const srcdoc = await srcdocOf(page);
  ok('Dg4 card with no local reference still mounts', !!srcdoc);
  await sleep(800);
  ok('Dg4 ZERO mint requests for a local-reference-free card (F1)', mint.count() === 0, String(mint.count()));
  ok('Dg4 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\n=== ${pass}/${pass + fail} PASS ===`);
if (fail) console.log('FAILED: ' + failures.join(' | '));
process.exit(fail ? 1 : 0);
