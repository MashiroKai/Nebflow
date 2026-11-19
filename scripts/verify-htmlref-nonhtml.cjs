// verify-htmlref-nonhtml.cjs — 20260830 author ruling: element-select toggle is
// zero-trace outside HTML tabs. The B6 'crossorigin'/'nodom' capabilities are
// retired; only the HTML viewer offers element selection ('available').
//
// Acceptance:
//   HTML tab  → .canvas-ref-select present + click toggles active (regression)
//   PDF tab   → no .canvas-ref-select (nodom call removed)
//   Image tab → no .canvas-ref-select (nodom call removed)
//   URL tab   → no .canvas-ref-select (crossorigin call removed)
//
// The three viewer render functions are imported directly; /api/nf-file is
// route-mocked (a tiny PNG for image, a real 6-page PDF for pdf). usage: 
//   node scripts/verify-htmlref-nonhtml.cjs [port]  (default 8197)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const PORT = process.argv[2] || '8197';
const BASE = `http://127.0.0.1:${PORT}/index.html`;
const PNG = fs.readFileSync('/tmp/ref-tiny.png');
const PDF = fs.readFileSync('/tmp/nb-c3-assets/test.pdf');

const results = [];
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + JSON.stringify(detail) : ''));
}

const INIT = `
  localStorage.setItem('nebflow_token', 'harness-token');
  class MockWS { static OPEN = 1; static CONNECTING = 0; static CLOSING = 2; static CLOSED = 3;
    constructor(url){ this.url=url; this.readyState=0; window.__wsMock=this; }
    send(){} close(){ this.readyState=3; if(this.onclose) this.onclose(); } }
  window.WebSocket = MockWS;
`;

async function boot(page) {
  const errs = [];
  page.on('pageerror', e => errs.push(String(e)));
  // C 批（票据腿）：viewer 渲染前会 POST /api/nf-ticket（image.js/pdf.js 的
  // ticketUrl）。静态 harness 的 http.server 对 POST 不回 2xx JSON，若不显式
  // mock，mint 降级 → 无票 URL → nf-file 401 → renderFile catch →
  // "Failed to render"。假票按请求里的 paths 确定性生成。
  await page.route('**/api/nf-ticket*', (route) => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch (e) { paths = []; }
    const tickets = {};
    (Array.isArray(paths) ? paths : []).forEach((p, i) => {
      tickets[p] = { t: 'harness-ticket-' + i, exp: Date.now() + 3600000, uses: -1 };
    });
    return route.fulfill({ json: { tickets, rejected: [] } });
  });
  // 票据腿判据：无票请求计数（真实端点会 401 —— 这里记录成显式断言）。
  const state = { ticketless: 0 };
  page.__nfTicketState = state;
  // Mock /api/nf-file for image (png) and pdf range/206 like the real backend.
  await page.route('**/api/nf-file*', (route) => {
    const url = route.request().url();
    if (!url.includes('ticket=')) state.ticketless++;
    const isPdf = url.includes('test.pdf');
    const body = isPdf ? PDF : PNG;
    const range = route.request().headers()['range'];
    if (range && isPdf) {
      const m = /bytes=(\d+)-(\d*)/.exec(range);
      const s = m ? parseInt(m[1], 10) : 0;
      const e = m && m[2] ? Math.min(parseInt(m[2], 10), body.length - 1) : body.length - 1;
      return route.fulfill({ status: 206, contentType: 'application/pdf', body: body.subarray(s, e + 1),
        headers: { 'Content-Range': `bytes ${s}-${e}/${body.length}`, 'Accept-Ranges': 'bytes' } });
    }
    return route.fulfill({ status: 200, contentType: isPdf ? 'application/pdf' : 'image/png', body,
      headers: { 'Accept-Ranges': 'bytes', 'Content-Length': String(body.length) } });
  });
  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 8000 });
  await page.evaluate(() => {
    const w = window.__wsMock; w.readyState = 1; if (w.onopen) w.onopen();
    const f = o => w.onmessage({ data: JSON.stringify(o) });
    f({ type: 'serverConfig', config: { configured: true, onboarding: 'done' } });
    f({ type: 'configData', config: JSON.stringify({ providers: {} }) });
    f({ type: 'sessionList', sessions: [{ id: 'sess1', title: 'S', agentName: 'Nebula', createdAt: Date.now() }], activeId: 'sess1' });
    f({ type: 'agentList', agents: [] });
  });
  await page.waitForTimeout(300);
  return errs;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1100, height: 800 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await page.addInitScript(INIT);
  const errs = await boot(page);

  const out = await page.evaluate(async () => {
    const sleep = (ms) => new Promise(r => setTimeout(r, ms));
    const res = {};
    const mkPane = (id) => {
      const p = document.createElement('div');
      p.className = 'canvas-tab-pane active';
      p.id = id;
      p.style.width = '800px'; p.style.height = '600px';
      document.body.appendChild(p);
      return p;
    };
    const hasSel = (p) => !!p.querySelector('.canvas-ref-select');

    // 1. HTML viewer — element select toggle present + toggles active
    const html = await import('/js/viewers/html.js');
    const paneHtml = mkPane('pane-html');
    html.default.render(paneHtml, { content: '<!DOCTYPE html><html><body><p style="padding:20px">Hello</p></body></html>', absPath: '/t.html', fileName: 't.html' });
    await sleep(300);
    const htmlBtn = paneHtml.querySelector('.canvas-ref-select');
    res.htmlSelectPresent = !!htmlBtn;
    if (htmlBtn) { htmlBtn.click(); await sleep(50); res.htmlActive = htmlBtn.classList.contains('active'); }
    res.htmlNoDomHint = paneHtml.textContent;

    // 2. PDF viewer — no .canvas-ref-select (nodom removed)
    const pdf = await import('/js/viewers/pdf.js');
    const panePdf = mkPane('pane-pdf');
    await pdf.default.render(panePdf, { absPath: '/test.pdf', fileName: 'test.pdf' });
    await sleep(400);
    res.pdfSelectAbsent = !hasSel(panePdf);
    res.pdfPages = panePdf.querySelectorAll('.pdf-pages canvas').length;

    // 3. Image viewer — no .canvas-ref-select (nodom removed)
    const image = await import('/js/viewers/image.js');
    const paneImg = mkPane('pane-img');
    await image.default.render(paneImg, { absPath: '/img.png', fileName: 'img.png', size: 73 });
    // wait for the img onload (which clears + rebuilds the pane)
    for (let i = 0; i < 40 && !paneImg.querySelector('img'); i++) await sleep(50);
    const img = paneImg.querySelector('img');
    if (img && !img.naturalWidth) await img.decode().catch(() => {});
    await sleep(200);
    res.imageSelectAbsent = !hasSel(paneImg);
    res.imageLoaded = !!(img && img.naturalWidth);

    // 4. URL (cross-origin) tab — no .canvas-ref-select (crossorigin removed)
    const canvas = await import('/js/canvas.js');
    await canvas.openWorkspaceItem({ id: 'url:https://example.com', itemType: 'url', url: 'https://example.com', title: 'example' });
    await sleep(500);
    // Scope to the real canvas tab area (#canvas-content) — the mock panes for
    // html/pdf/image are appended to <body> and must not pollute this check.
    const urlPanes = [...document.querySelectorAll('#canvas-content .canvas-tab-pane')];
    res.urlSelectAbsent = urlPanes.length > 0 && urlPanes.every(p => !hasSel(p));
    res.urlPaneCount = urlPanes.length;

    return res;
  });

  ok('HTML toggle present (html-viewer available mode)', out.htmlSelectPresent, out.htmlSelectPresent);
  ok('HTML toggle enters active on click', out.htmlActive === true, out.htmlActive);
  ok('PDF viewer renders pages', out.pdfPages > 0, out.pdfPages);
  ok('PDF pane has NO .canvas-ref-select', out.pdfSelectAbsent === true, out.pdfSelectAbsent);
  ok('Image pane loaded', out.imageLoaded === true, out.imageLoaded);
  ok('Image pane has NO .canvas-ref-select', out.imageSelectAbsent === true, out.imageSelectAbsent);
  ok('URL pane has NO .canvas-ref-select', out.urlSelectAbsent === true, { urlSelectAbsent: out.urlSelectAbsent, urlPaneCount: out.urlPaneCount });
  ok('no page errors', errs.length === 0, errs);
  // C 批（票据腿）：每个 nf-file 请求都必须带票（新机制纳入门禁）。
  const ticketless = (page.__nfTicketState && page.__nfTicketState.ticketless) || 0;
  ok('every /api/nf-file request carries a ticket', ticketless === 0, { ticketless });

  await browser.close();
  const failed = results.filter(r => !r.pass).length;
  console.log('\n=== ' + (results.length - failed) + '/' + results.length + ' PASS ===');
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error('HARNESS ERROR', e); process.exit(2); });
