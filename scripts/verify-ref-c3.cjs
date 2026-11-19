// verify-ref-c3.cjs — #303 C3: document-reference page anchors land in the pdf
// viewer (scrollToPage) across the readFile round trip.
//
// Chain under test:
//   reference.js card click → workspace-open-item (anchor.pageStart)
//   → canvas.js openWorkspaceItem (empty content: stash anchor + pop.readFile)
//   → fileContent-response item (no anchor field) → renderFile → pdf.js
//   → deferred scroll once the target page canvas is sized.
// Plus: jump to an already-open tab scrolls the live viewer via
// pane._scrollToPage (no re-render, no second readFile), and a no-anchor
// open stays at scrollTop 0.
//
// A minimal harness page (real base.css + split.css + canvas panel DOM) is
// written into the served web root for the run and removed afterwards.
// /api/nf-file is route-mocked with a real 6-page PDF; pdf.js issues HTTP
// Range requests, so the mock honors them with 206 like the real backend.

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const WEB = __dirname + '/../src/main/resources/web';
const BASE = process.env.C3_BASE || 'http://127.0.0.1:8194';
const PDF_PATH = process.env.C3_PDF || '/tmp/nb-c3-assets/test.pdf';
const HARNESS_HTML = path.join(WEB, '__verify_c3.html');

const results = [];
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : ''));
}

const HTML = `<!DOCTYPE html><html><head>
<meta charset="utf-8">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/split.css">
<style>
  html, body { height: 100%; }
  #canvas-panel { position: fixed; left: 0; top: 0; width: 820px; height: 720px; display: flex; background: #fff; }
  #canvas-slide { width: 100%; height: 100%; display: flex; flex-direction: column; }
  #canvas-content { flex: 1; min-height: 0; position: relative; }
  #canvas-content .canvas-tab-pane { position: absolute; inset: 0; }
</style>
</head><body>
<div id="canvas-panel"><div id="canvas-slide">
  <div class="canvas-header"><div class="canvas-tab-bar" id="canvas-tab-bar"></div><button id="canvas-close-btn"></button></div>
  <div id="canvas-content"></div>
</div></div>
</body></html>`;

(async () => {
  fs.writeFileSync(HARNESS_HTML, HTML);
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 900, height: 800 } });
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));

    // C 批（票据腿）：pdf.js 渲染前 POST /api/nf-ticket（ticketUrl）。
    // 静态 harness 的 http.server 对 POST 不回 2xx JSON ⇒ 必须显式假票 mock。
    await page.route('**/api/nf-ticket*', (route) => {
      let paths = [];
      try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch (e) { paths = []; }
      const tickets = {};
      (Array.isArray(paths) ? paths : []).forEach((p, i) => {
        tickets[p] = { t: 'harness-ticket-' + i, exp: Date.now() + 3600000, uses: -1 };
      });
      return route.fulfill({ json: { tickets, rejected: [] } });
    });
    const ticketState = { ticketless: 0 };
    await page.route('**/api/nf-file*', (route) => {
      if (!route.request().url().includes('ticket=')) ticketState.ticketless++;
      const body = fs.readFileSync(PDF_PATH);
      const range = route.request().headers()['range'];
      if (range) {
        const m = /bytes=(\d+)-(\d*)/.exec(range);
        const start = m ? parseInt(m[1], 10) : 0;
        const end = m && m[2] ? Math.min(parseInt(m[2], 10), body.length - 1) : body.length - 1;
        return route.fulfill({
          status: 206, contentType: 'application/pdf', body: body.subarray(start, end + 1),
          headers: { 'Content-Range': `bytes ${start}-${end}/${body.length}`, 'Accept-Ranges': 'bytes' },
        });
      }
      return route.fulfill({
        status: 200, contentType: 'application/pdf', body,
        headers: { 'Accept-Ranges': 'bytes', 'Content-Length': String(body.length) },
      });
    });

    await page.goto(BASE + '/__verify_c3.html', { waitUntil: 'domcontentloaded' });

    const out = await page.evaluate(async () => {
      const res = {};
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

      const canvas = await import('/js/canvas.js');
      const refmod = await import('/js/reference.js');
      const state = (await import('/js/state.js')).default;

      const evts = [];
      window.addEventListener('workspace-open-item', (e) => evts.push(e.detail));

      // Mock the WS send channel (canvas.js empty-content branch sends
      // pop.readFile via ws.js sendWs → state.ws).
      state.activeSessionId = 'sess-c3';
      const sent = [];
      state.ws = { readyState: 1, send: (m) => sent.push(JSON.parse(m)) };

      // ── T1: reference card click dispatches the anchor ──
      const dref = refmod.makeReference({
        refType: 'document',
        source: { kind: 'canvas', path: '/test.pdf', fileName: 'test.pdf', mimeType: 'application/pdf' },
        anchor: { pageStart: 4, pageEnd: 5 },
      });
      const card = refmod.renderRefBlock(dref, { mode: 'message' });
      document.body.appendChild(card);
      card.click();
      res.clickDetail = evts[0] || null;

      // ── T2: empty-content open → stash + pop.readFile ──
      await canvas.openWorkspaceItem(res.clickDetail);
      await sleep(80); // dynamic imports (ws.js/state.js) settle
      res.readFileFrames = sent.filter((m) => m.type === 'pop.readFile');

      // ── T3: fileContent-response item (no anchor) → render uses the stash ──
      await canvas.openWorkspaceItem({
        id: 'file:/test.pdf', title: 'test.pdf', itemType: 'pdf', content: '',
        absPath: '/test.pdf', size: 9024, path: '/test.pdf',
      });
      // Poll until the page-4 canvas is sized and the container scrolled.
      let pages = null;
      for (let i = 0; i < 120; i++) {
        pages = document.querySelector('.pdf-pages');
        const c4 = pages && pages.querySelector('canvas[data-page="4"]');
        if (c4 && c4.style.height && pages.scrollTop > 0) break;
        await sleep(100);
      }
      const c4 = pages && pages.querySelector('canvas[data-page="4"]');
      res.anchorScroll = pages ? {
        scrollTop: pages.scrollTop,
        dy: c4 ? Math.round(c4.getBoundingClientRect().top - pages.getBoundingClientRect().top) : null,
        scrollToPageExposed: typeof document.querySelector('.canvas-tab-pane')._scrollToPage === 'function',
      } : null;

      // Let the tab-activation refresh debounce (300ms) fire and settle so it
      // cannot leak into the T4 frame count (canvas.js scheduleFileRefresh is
      // pre-existing behavior on any activation with a stale _lastRefreshAt).
      await sleep(450);

      // ── T4: jump to an already-open tab → live scroll, no re-render ──
      const pagesElBefore = document.querySelector('.pdf-pages');
      const before = sent.filter((m) => m.type === 'pop.readFile').length;
      await canvas.openWorkspaceItem({
        id: 'file:/test.pdf', title: 'test.pdf', itemType: '', content: '',
        absPath: '/test.pdf', path: '/test.pdf', anchor: { pageStart: 2 },
      });
      await sleep(150);
      const c2 = document.querySelector('canvas[data-page="2"]');
      const pages2 = document.querySelector('.pdf-pages');
      res.reJump = {
        scrollTop: pages2 ? pages2.scrollTop : null,
        dy2: c2 && pages2 ? Math.round(c2.getBoundingClientRect().top - pages2.getBoundingClientRect().top) : null,
        newReadFile: sent.filter((m) => m.type === 'pop.readFile').length - before,
        samePagesNode: pages2 === pagesElBefore,
      };

      // ── T5: no-anchor control opens at scrollTop 0 ──
      await canvas.openWorkspaceItem({ id: 'file:/test2.pdf', title: 'test2.pdf', itemType: '', content: '', absPath: '/test2.pdf', path: '/test2.pdf' });
      await sleep(60);
      await canvas.openWorkspaceItem({ id: 'file:/test2.pdf', title: 'test2.pdf', itemType: 'pdf', content: '', absPath: '/test2.pdf', size: 9024, path: '/test2.pdf' });
      // test2 replaced the preview tab — its pane is the live one now.
      let pagesB = null;
      for (let i = 0; i < 120; i++) {
        pagesB = document.querySelector('.pdf-pages');
        const cb2 = pagesB && pagesB.querySelector('canvas[data-page="2"]');
        if (cb2 && cb2.style.height) break; // a bit of rendering done
        await sleep(100);
      }
      res.noAnchor = pagesB ? { scrollTop: pagesB.scrollTop } : null;

      return res;
    });

    ok('T1 card click → workspace-open-item carries anchor.pageStart=4',
      out.clickDetail && out.clickDetail.anchor && out.clickDetail.anchor.pageStart === 4,
      JSON.stringify(out.clickDetail && out.clickDetail.anchor));
    ok('T2 empty-content open sends pop.readFile',
      out.readFileFrames.length >= 1 && out.readFileFrames[0].path === '/test.pdf',
      JSON.stringify(out.readFileFrames));
    ok('T3 stashed anchor scrolls pdf to page 4',
      out.anchorScroll && out.anchorScroll.scrollTop > 0 && out.anchorScroll.dy !== null && Math.abs(out.anchorScroll.dy - 12) < 60,
      JSON.stringify(out.anchorScroll));
    ok('T3b pane exposes _scrollToPage',
      out.anchorScroll && out.anchorScroll.scrollToPageExposed === true);
    ok('T4 re-jump scrolls live viewer up to page 2',
      out.reJump && out.reJump.dy2 !== null && Math.abs(out.reJump.dy2 - 12) < 60 && out.reJump.scrollTop < out.anchorScroll.scrollTop,
      JSON.stringify(out.reJump));
    ok('T4b re-jump re-renders nothing (same .pdf-pages node, no new readFile)',
      out.reJump && out.reJump.samePagesNode === true && out.reJump.newReadFile === 0,
      `sameNode=${out.reJump && out.reJump.samePagesNode} newReadFile=${out.reJump && out.reJump.newReadFile}`);
    ok('T5 no-anchor open stays at scrollTop 0',
      out.noAnchor && out.noAnchor.scrollTop === 0, JSON.stringify(out.noAnchor));

    ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));
    // C 批（票据腿）门禁：nf-file 请求必须带票。
    ok('every /api/nf-file request carries a ticket', ticketState.ticketless === 0, { ticketless: ticketState.ticketless });
  } finally {
    await browser.close();
    try { fs.unlinkSync(HARNESS_HTML); } catch {}
  }

  const failed = results.filter((r) => !r.pass);
  console.log('\n=== SUMMARY ===');
  console.log(`${results.length - failed.length}/${results.length} PASS`);
  process.exit(failed.length ? 1 : 0);
})();
