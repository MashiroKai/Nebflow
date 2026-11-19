// _canvas-save-failure-harness.mjs — shared harness for the canvas
// "保存失败" notice batch (2026-11-18, P0).
//
// Standalone node utility (no @playwright/test import) so playwright's collector
// never imports it. It brings up a REAL node http server that implements the
// exact single-writer seat semantics mapped from the Scala backend
// (ConsoleTakeover + ConfigRoutes, see the analysis), serves the REAL
// src/main/resources/web tree, and drives the REAL js/canvas.js module. Every
// /api/canvas* call is a genuine HTTP request whose URL/method/status/body are
// recorded — the "network layer" read this batch has to produce.
//
// Seat semantics (faithful subset, non-preemptive):
//   grant  ⇔ seat is empty OR the seat is exactly this (clientId,tabInstanceId)
//   refuse ⇔ a seat exists and it is not this exact triple   (409)
//   release clears the seat iff the full triple+epoch matches
//   NOTE the real backend has NO liveness check on the seat and never clears it
//   on disconnect — a vanished holder keeps the seat. That gap is exactly what
//   this harness reproduces with `seedGhostSeat()`.

import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { extname, join, normalize, resolve } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
export const WEB = resolve(process.env.NF_WEB_ROOT || 'src/main/resources/web');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.woff': 'font/woff',
  '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.mp4': 'video/mp4',
  '.pdf': 'application/pdf', '.map': 'application/json',
};

/** Standalone script: truthful red (exit 1) on smoke failure, never a silent skip. */
export function loadChromium() {
  for (const c of ['playwright', 'playwright-core', '@playwright/test']) {
    try { const m = require(c); if (m.chromium) return m.chromium; } catch { /* next */ }
  }
  throw new Error('playwright/chromium not resolvable — install devDeps or set NEBFLOW_PW_PATH');
}

/**
 * Start the seat-faithful mock server. Returns handles + a network log.
 * @param {number} [port] 0 => ephemeral
 */
export async function startSeatServer(port = 0, opts = {}) {
  const webRoot = normalize(opts.webRoot || WEB);
  /** @type {{clientId:string,tabInstanceId:string,epoch:number}|null} */
  let seat = null;
  let epoch = 0;
  /** @type {Array<{method:string,path:string,status:number,body:string}>} */
  const net = [];
  // PUT refusals can be forced (e.g. to model a server-side write failure).
  const flags = { forcePutStatus: 0 };

  const readBody = (req) => new Promise((res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => res(Buffer.concat(chunks).toString('utf8')));
  });

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const p = decodeURIComponent(url.pathname);
    const json = (status, obj) => {
      const body = JSON.stringify(obj);
      net.push({ method: req.method, path: p, status, body });
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(body);
    };
    try {
      if (p === '/api/canvas-writer' && req.method === 'GET') {
        const c = url.searchParams.get('clientId');
        const t = url.searchParams.get('tabInstanceId');
        const mine = seat && seat.clientId === c && seat.tabInstanceId === t;
        net.push({ method: 'GET', path: p, status: 200, body: mine ? `{role:writer,epoch:${seat.epoch}}` : '{role:reader}' });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(mine ? JSON.stringify({ role: 'writer', epoch: seat.epoch }) : JSON.stringify({ role: 'reader', epoch: null }));
      }
      if (p === '/api/canvas-writer/claim' && req.method === 'POST') {
        const b = JSON.parse((await readBody(req)) || '{}');
        const mine = seat && seat.clientId === b.clientId && seat.tabInstanceId === b.tabInstanceId;
        if (!seat || mine) {
          epoch = epoch + 1;
          seat = { clientId: b.clientId, tabInstanceId: b.tabInstanceId, epoch };
          return json(200, { role: 'writer', epoch });
        }
        return json(409, { error: 'writer_already_held', message: '保存失败，本次修改未保存到服务器。', role: 'reader' });
      }
      if (p === '/api/canvas-writer/release' && req.method === 'POST') {
        const b = JSON.parse((await readBody(req)) || '{}');
        if (seat && seat.clientId === b.clientId && seat.tabInstanceId === b.tabInstanceId && seat.epoch === b.epoch) {
          seat = null;
          return json(200, { role: 'reader' });
        }
        return json(409, { error: 'stale_writer_epoch', message: '保存失败，本次修改未保存到服务器。', role: 'reader' });
      }
      if (p === '/api/canvas-tabs' && req.method === 'GET') {
        return json(200, { v: 2, tabs: [], activeTabId: null });
      }
      if (p === '/api/canvas-tabs' && req.method === 'PUT') {
        const raw = await readBody(req);
        if (flags.forcePutStatus) return json(flags.forcePutStatus, { error: 'forced_write_failure', message: '保存失败，本次修改未保存到服务器。', role: 'reader' });
        let b = {};
        try { b = JSON.parse(raw); } catch { /* ignore */ }
        const mine = seat && seat.clientId === b.clientId && seat.tabInstanceId === b.tabInstanceId && seat.epoch === b.epoch;
        if (mine) return json(200, { ok: true });
        return json(409, { error: 'stale_writer_epoch', message: '保存失败，本次修改未保存到服务器。', role: 'reader' });
      }
      if (p.startsWith('/api/')) return json(200, {});
      // static
      const file = normalize(join(webRoot, p === '/' ? 'index.html' : p));
      if (!file.startsWith(webRoot)) { res.writeHead(403); return res.end('forbidden'); }
      try {
        const body = readFileSync(file);
        res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
        return res.end(body);
      } catch { res.writeHead(404); return res.end('not found'); }
    } catch (e) {
      res.writeHead(500); res.end('harness error ' + String(e && e.message));
    }
  });
  await new Promise((r) => server.listen(port, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;

  return {
    base,
    port: server.address().port,
    net,
    opts: flags,
    webRoot,
    seat: () => seat,
    /** Model a vanished holder: grab the seat for an identity that never releases. */
    seedGhostSeat: async (clientId = 'ghost-client', tabInstanceId = 'ghost-tab') => {
      const r = await fetch(`${base}/api/canvas-writer/claim`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientId, tabInstanceId }),
      });
      return r.status;
    },
    clearSeat: () => { seat = null; },
    close: () => new Promise((r) => server.close(r)),
  };
}

/** Init script shared by every harness page: tokens + the definitive toast tally
 *  (toasts auto-dismiss in 4s, so counting live nodes would undercount). */
export const HARNESS_INIT = () => {
  try {
    localStorage.setItem('nebflow_token', 'harness-token');
    localStorage.setItem('neblink_token', 'harness-token');
    localStorage.setItem('nebflow_device_client_id', 'harness-client');
  } catch { /* ignore */ }
  const record = (muts) => {
    for (const m of muts) for (const n of m.addedNodes) {
      if (n && n.nodeType === 1 && n.classList && n.classList.contains('nebflow-toast')) {
        const msg = n.querySelector('.nebflow-toast-msg');
        (window.__toasts = window.__toasts || []).push(msg ? msg.textContent : n.textContent);
      }
    }
  };
  const attach = () => new MutationObserver(record).observe(document.body, { childList: true, subtree: true });
  if (document.body) attach(); else document.addEventListener('DOMContentLoaded', attach);
};

/** Mock only the WS control channel; http stays real (the harness server). */
export async function attachWsMock(page) {
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify({ llm: { providers: {} }, features: {} }) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'test', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'test', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 'test', messages: [], hasMore: false, offset: 0 }));
  });
}

/** Boot a real page against the harness server; mock only the WS control channel. */
export async function bootPage(browser, base, { width = 1440, height = 900, mobile = false } = {}) {
  const context = await browser.newContext({
    viewport: { width, height },
    isMobile: mobile, hasTouch: mobile,
    deviceScaleFactor: 1, locale: 'zh-CN',
  });
  await context.addInitScript(HARNESS_INIT);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message)));
  await attachWsMock(page);
  await page.goto(base, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await page.waitForTimeout(400);
  return { context, page, errors };
}

/** Drive one canvas tab write, then let the 500ms debounce + fetch settle. */
export async function driveWrite(page, id = 't1') {
  await page.evaluate(async (tabId) => {
    const m = await import('/js/canvas.js');
    m.openTab(tabId, 'Tab ' + tabId, { pinned: true });
  }, id);
  await page.waitForTimeout(1400);
}

/** Toast texts recorded so far (definitive, survives auto-dismiss). */
export async function toasts(page) {
  return page.evaluate(() => (window.__toasts || []).slice());
}

/** Reset the in-page toast tally + local canvas archive between reload phases. */
export async function resetTally(page) {
  await page.evaluate(() => { window.__toasts = []; });
}
