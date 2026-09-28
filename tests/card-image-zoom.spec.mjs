// card-image-zoom.spec.mjs — a Card's embedded images open in the viewer
// (chain-cardzoom, 2026-09-28).
//
// Author's order (verbatim, Chinese): an image embedded in the Card tool must
// open on click exactly as an image embedded in Canvas does. This spec tests
// the BEHAVIOUR: a click on an <img> inside the Card iframe opens the existing
// lightbox.
//
// The real chain under test: a static server serves src/main/resources/web →
// dynamic import of /js/cardRegistry.js → renderWithRegistry (the Card tool's
// real entry point) → renderHtmlCard → srcdoc (carrying the frame script that
// viewers/shared.js `imgClickScript()` produces) → iframe (sandbox
// allow-scripts allow-same-origin) → in-frame click capture →
// parent.postMessage `_nfImagePreview` → lightbox.js initLightbox's message
// handler (source check + path gate) → openLightbox → re-mint (path image) or
// direct `src` use (data-URI image).
//
// Coverage (mapped to the task brief §2's four acceptance items + negatives):
//   Z1 path image (/api/nf-file?path=…): the click opens the viewer, and the
//      path the viewer resolved equals the image src's path
//   Z2 data-URI inline image: opens on click per the §0 ruling (via the src
//      fallback; no blob conversion is invented)
//   Z3 unauthorised source refused (negative): any window that is not an app
//      frame forging `_nfImagePreview` does not open the viewer
//   Z4 Canvas leg not regressed: an image inside the Canvas HTML viewer still
//      opens (asserted in the same file)
//   Z5 path gate: an arbitrary path the frame does not render never opens the
//      viewer; a path the frame does render does open it
//   Z6 click affordance: the in-frame <img> carries cursor:zoom-in plus the
//      hover hint (that single §16 string)
//   Z7 srcdoc zero regression: the existing message shapes (_nfCardH /
//      _nfThemeVars / _nfOpenLocalFile) are unchanged
//   Z8 linked-image precedence (round-1 review, 2026-09-28): an <img> inside a
//      local-file <a href> follows its link and does NOT also open the viewer —
//      the one behaviour this batch changed on the Canvas leg, pinned here so
//      the change has a test guarding it.
//
// Screenshots: with SHOTS_DIR=<dir> the before/after click pair is written
// (nothing is written by default).
// Run: node tests/card-image-zoom.spec.mjs
// Optional env: PORT=8137 (never 8080), CARDZOOM_SHOTS_DIR=<dir>

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

// A 1×1 PNG (real decodable bytes, so the <img> takes onload not onerror).
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

/** Boot one page: static web/ + WS mock + the mint/nf-file doubles (the same
 *  set the existing suites use). */
async function bootPage(browser, opts = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    // Passive recorder for every `_nf*` message reaching the PARENT window —
    // an independent listener, so a test can assert WHICH channels a click
    // emitted, not just the end state. Needed by Z8 (the linked-image
    // precedence rule is a statement about the message SET: the link leg fires
    // and the preview leg does not), and harmless elsewhere: it observes only.
    window.__nfMsgs = [];
    window.addEventListener('message', (e) => {
      const d = e.data;
      if (!d || typeof d !== 'object') return;
      const keys = Object.keys(d).filter((k) => k.startsWith('_nf'));
      if (keys.length) window.__nfMsgs.push(keys.join('+'));
    });
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

/** Render one Card through the Card tool's own entry point renderWithRegistry. */
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

/** Wait for the nth card's srcdoc to land. */
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

/** Wait for the frame's nth img, return its readings; click it (the real user
 *  path = an in-frame click). */
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

/** Viewer readings (parent-document side, serialisable face). */
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

/** The `_nf*` message channels that reached the parent window so far (the
 *  passive recorder installed by bootPage). Each entry is the joined key list
 *  of one message, e.g. `_nfOpenLocalFile` or `_nfCardH`. */
const msgsOf = (page) => page.evaluate(() => (window.__nfMsgs || []).slice());

/** Wait for the viewer to open (returns the readings) — for positive cases. */
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

// ── Z1 path image click → the viewer opens the right path (acceptance 1) ──
{
  const { ctx, page, mint, pageErrors } = await bootPage(browser);
  await renderCard(page,
    '<img id="p1" src="/api/nf-file?path=%2Ftmp%2Fcz%2Fphoto.png" alt="photo.png" width="120">');
  const srcdoc = await srcdocOf(page);
  ok('Z1 card mounted (srcdoc set)', !!srcdoc);

  // The click bridge really is injected in the frame (a direct reading that the
  // production leg is in place). The criterion is the bridge's OWN postMessage
  // call, not a function-name substring: `stripCredentialParams` (the existing
  // failure-placeholder script, injected by both the card and Canvas) also
  // contains "stripCredential", so a substring match goes falsely green on the
  // pre-change tree (measured).
  ok('Z1 srcdoc carries the image-click bridge',
    /parent\.postMessage\(\{\s*_nfImagePreview/.test(srcdoc || ''), (srcdoc || '').slice(0, 80));
  ok('Z1 srcdoc bridge defines the credential stripper', /function stripCredential\(/.test(srcdoc || ''));

  if (SHOTS_DIR) {
    await page.screenshot({ path: join(SHOTS_DIR, 'z1-before-click.png') });
  }

  const clicked = await clickFrameImg(page, 0);
  ok('Z1 frame image clicked', clicked === true);
  const st = await waitLightbox(page);
  ok('Z1 lightbox opened on the card image click', st.on, JSON.stringify(st));
  ok('Z1 lightbox shows a blob: preview (no credential in parent DOM)',
    st.src.startsWith('blob:'), st.src.slice(0, 80));
  ok('Z1 alt text forwarded', st.alt === 'photo.png', st.alt);
  // The path the viewer resolved == the image src's path (this batch's core
  // criterion): the in-frame img's path and the path asked for in the re-mint
  // round must be byte-identical — after re-mint the same file comes back.
  const rounds = mint.calls.map((p) => p.join(','));
  const framePath = await page.evaluate(() => {
    const f = document.querySelector('iframe[data-nf-card-id]');
    const doc = f && f.contentDocument;
    const u = doc && doc.querySelector('img') ? (doc.querySelector('img').currentSrc || '') : '';
    const m = /[?&]path=([^&]*)/.exec(u);
    return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
  });
  // Three criteria in one: the in-frame img's path is right (guards against
  // fixture drift) ∧ the viewer is OPEN on that path (st.on carries the
  // discriminating power — a "card rendered, a re-mint round happened" reading
  // also exists on the pre-change tree, so asserting on mint alone would go
  // falsely green) ∧ that path is exactly the one the viewer's re-mint round
  // asked for.
  ok('Z1 viewer path == the image src path (same file after re-mint)',
    st.on && framePath === '/tmp/cz/photo.png' && mint.calls.some((p) => p.includes(framePath)),
    JSON.stringify({ on: st.on, framePath, rounds }));
  ok('Z1 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  if (SHOTS_DIR) {
    await page.screenshot({ path: join(SHOTS_DIR, 'z1-after-click-lightbox.png') });
  }
  await ctx.close();
}

// ── Z2 data-URI inline image → opens on click (§0 ruling: via src) ────────
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

// ── Z3 unauthorised source refused (negative, acceptance 3) ───────────────
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  // 3a. The app document itself (not any iframe) forges the same message shape
  // ⇒ must be refused.
  await page.evaluate(() => {
    window.postMessage({ _nfImagePreview: { src: '/api/nf-file?path=%2Ftmp%2Fcz%2Fevil.png', path: '/tmp/cz/evil.png', alt: 'evil' } }, '*');
  });
  await sleep(900);
  let st = await lightboxState(page);
  ok('Z3 (a) foreign same-origin window is REFUSED', !st.on, JSON.stringify(st));

  // 3b. A frame that is not one this app renders (user-created) forges the
  // message ⇒ must be refused.
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

  // 3c. Positive control (same page): a real card's image STILL opens ⇒ the
  // refusal is not "the whole channel was switched off".
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fok.png" alt="ok" width="120">');
  await srcdocOf(page);
  await clickFrameImg(page, 0);
  st = await waitLightbox(page);
  ok('Z3 (c) CONTROL: a real card image still opens (channel not disabled)', st.on, JSON.stringify(st));
  ok('Z3 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z4 Canvas leg not regressed (acceptance 3, second half) ───────────────
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
  // Z4b: the frame itself forges a path it does NOT render ⇒ the path gate must
  // stop it. This goes red on the pre-change tree (there was no path gate), so
  // it doubles as a discriminating red anchor proving the gate exists — a guard
  // against "the receiving face was deleted and nothing covers it". Two things
  // to note: (1) the sender must be the frame itself (e.source = that frame's
  // window), otherwise only the source check gets exercised; (2) the viewer Z4
  // opened must be CLOSED first, else "still open" impersonates "refused" — a
  // negative case's precondition must be explicitly zeroed (measured pitfall).
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

// ── Z5 path gate: a path the frame does not render is not a viewer entry ──
// Key point: the message must be sent BY THE FRAME ITSELF (frame.evaluate ⇒
// e.source = that frame's window, so the source check passes); only then is the
// gate really exercised. Sending it via page.evaluate lands on the "source is
// not an app frame" refusal instead — that is Z3's face, not the gate's.
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

  // The frame sends a message whose path is not in its own document (simulating
  // an injected script / tampered payload) ⇒ the source is a legitimate app
  // frame, so the gate must stop it on "that frame does not render this path".
  await frame.evaluate(() => {
    parent.postMessage(
      { _nfImagePreview: { src: '/api/nf-file?path=%2Fetc%2Fsecrets', path: '/etc/secrets', alt: 'x' } }, '*');
  });
  await sleep(900);
  let st = await lightboxState(page);
  ok('Z5 path the frame does NOT render is REFUSED', !st.on, JSON.stringify(st));

  // Same frame, same channel, but the path is one it really renders ⇒ must open
  // (the gate is not "refuse everything").
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

// ── Z6 click affordance (that single §16 string + cursor) ─────────────────
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

  // Present in both locales + no orphan key (matching the rendering face).
  const locales = await page.evaluate(async () => {
    const en = (await import('/js/locales/en.js')).default;
    const zh = (await import('/js/locales/zh-CN.js')).default;
    return { en: en['lightbox.clickHint'] || '', zh: zh['lightbox.clickHint'] || '' };
  });
  ok('Z6 hint key present in BOTH locales (en + zh-CN)', !!locales.en && !!locales.zh,
    JSON.stringify(locales));
  ok('Z6 rendered hint matches the active locale', read.title === locales.zh || read.title === locales.en,
    JSON.stringify({ rendered: read.title, locales }));
  ok('Z6 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z7 srcdoc existing message shapes not regressed (no add / no rewrite) ─
{
  const { ctx, page, pageErrors } = await bootPage(browser);
  await renderCard(page, '<img src="/api/nf-file?path=%2Ftmp%2Fcz%2Fshape.png" alt="s" width="120">');
  const srcdoc = await srcdocOf(page) || '';
  ok('Z7 height channel unchanged (_nfCardH)', /_nfCardH/.test(srcdoc));
  ok('Z7 theme channel unchanged (_nfThemeVars)', /_nfThemeVars/.test(srcdoc));
  ok('Z7 link channel unchanged (_nfOpenLocalFile)', /_nfOpenLocalFile/.test(srcdoc));
  ok('Z7 media fallback unchanged (data-nf-load-error)', /data-nf-load-error/.test(srcdoc));
  // The click bridge produces exactly one message shape (no second contract).
  const shapes = [...new Set((srcdoc.match(/_nf[A-Za-z]+/g) || []))].sort();
  ok('Z7 image channel reuses the existing _nfImagePreview key',
    shapes.includes('_nfImagePreview'), JSON.stringify(shapes));
  ok('Z7 no second preview contract introduced',
    !/_nfImage(?!Preview)|_nfImg|_nfZoomImg/.test(srcdoc), JSON.stringify(shapes));
  ok('Z7 no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

// ── Z8 linked-image precedence: a linked image follows its link ───────────
// Round-1 review (2026-09-28) issue ①: the shared frame script carries
// `if(e.defaultPrevented) return;` at the head of its click handler, so an
// <img> wrapped in a link that a sibling capture listener already claimed
// follows the LINK and no longer ALSO opens the preview. The pre-change Canvas
// frame script had no such guard and emitted BOTH messages (measured on both
// trees — see the batch's READINGS.md). That is an intentional tightening: one
// click doing two things at once is the failure mode the guard removes, and the
// link's own destination wins over a nested image. It is a Canvas-leg behaviour
// change all the same, so it is pinned here instead of left implicit.
//
// The LINKED shape is the ordinary authored form (`<a href=…><img …></a>`), and
// it is worth distinguishing from the two shapes that MUST keep opening the
// preview: a bare image (Z1–Z5) and an image inside an in-page `#` anchor
// (nothing claims those, so `defaultPrevented` stays false). Measured: the `#`
// anchor shape reads identically on both trees — no difference to pin.
{
  // 8a. Canvas HTML viewer: the linked image routes to its link only. This goes
  // RED on the pre-change tree (which emitted `_nfOpenLocalFile` AND
  // `_nfImagePreview` for this very click), so it doubles as a discriminating
  // anchor proving the precedence rule is in force rather than untested.
  const { ctx, page, pageErrors } = await bootPage(browser);
  await page.evaluate(() => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:/tmp/cz/linked.html', itemType: 'html', title: 'linked.html',
        content: '<a href="/tmp/cz/target.md" id="lnk"><img id="inner" '
          + 'src="/api/nf-file?path=%2Ftmp%2Fcz%2Flinked.png" alt="linked" width="120"></a>',
        absPath: '/tmp/cz/linked.html', pinned: false,
      },
    }));
  });
  let clicked = false;
  for (let i = 0; i < 40 && !clicked; i++) {
    await sleep(250);
    clicked = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-canvas-html]');
      const doc = f && f.contentDocument;
      const img = doc && doc.querySelector('#inner');
      if (!img) return false;
      img.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: doc.defaultView }));
      return true;
    }).catch(() => false);
  }
  ok('Z8a canvas linked image clicked', clicked === true);
  await sleep(900);
  const msgs = await msgsOf(page);
  const st = await lightboxState(page);
  ok('Z8a canvas linked image routes to its LINK (_nfOpenLocalFile)',
    msgs.includes('_nfOpenLocalFile'), JSON.stringify(msgs));
  ok('Z8a canvas linked image does NOT also open the preview (no _nfImagePreview)',
    !msgs.some((m) => m.includes('_nfImagePreview')), JSON.stringify(msgs));
  ok('Z8a lightbox stays closed for a linked image', !st.on, JSON.stringify(st));
  ok('Z8a no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}
{
  // 8b. Card leg, same shape. This one is a regression PIN rather than a red
  // anchor: the card injected no image producer before this batch, so its
  // linked-image behaviour was already "link only" (measured on both trees —
  // the pre-change card emitted `_nfOpenLocalFile` and nothing else). Asserting
  // it here states both legs in one place and fails if a later change makes the
  // card emit a second message for one click.
  const { ctx, page, pageErrors } = await bootPage(browser);
  await renderCard(page,
    '<a href="/tmp/cz/target.md" id="lnk"><img id="inner" '
    + 'src="/api/nf-file?path=%2Ftmp%2Fcz%2Flinked.png" alt="linked" width="120"></a>');
  await srcdocOf(page);
  let clicked = false;
  for (let i = 0; i < 40 && !clicked; i++) {
    await sleep(200);
    clicked = await page.evaluate(() => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      const doc = f && f.contentDocument;
      const img = doc && doc.querySelector('#inner');
      if (!img) return false;
      img.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: doc.defaultView }));
      return true;
    }).catch(() => false);
  }
  ok('Z8b card linked image clicked', clicked === true);
  await sleep(900);
  const msgs = await msgsOf(page);
  const st = await lightboxState(page);
  ok('Z8b card linked image routes to its LINK (_nfOpenLocalFile)',
    msgs.includes('_nfOpenLocalFile'), JSON.stringify(msgs));
  ok('Z8b card linked image does NOT also open the preview',
    !msgs.some((m) => m.includes('_nfImagePreview')), JSON.stringify(msgs));
  ok('Z8b lightbox stays closed for a linked image', !st.on, JSON.stringify(st));
  ok('Z8b no pageerror', pageErrors.length === 0, pageErrors.join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\n=== ${pass}/${pass + fail} PASS ===`);
if (fail) console.log('FAILED: ' + failures.join(' | '));
process.exit(fail ? 1 : 0);
