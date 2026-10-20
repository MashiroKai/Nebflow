#!/usr/bin/env node
// measure.mjs — console boot waterfall measurement (CDP, zero deps, node>=22 builtin WebSocket).
// Scenarios: direct/tunnel(x latency 264ms) x cold(clear cache)/warm(same profile 2nd nav).
// Output: JSON per scenario into ./results/<label>.json + a summary line on stdout.
//
// Usage: node measure.mjs [--base http://127.0.0.1:8080] [--labels direct-cold,direct-warm,tunnel-cold,tunnel-warm]

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const arg = (name, def) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : def;
};
const BASE = (arg('--base', 'http://127.0.0.1:8080') || '').replace(/\/$/, '');
const LABELS = (arg('--labels', 'direct-cold,direct-warm,tunnel-cold,tunnel-warm') || '').split(',');
const OUTDIR = arg('--out', join(import.meta.dirname, 'results'));
const TUNNEL_LATENCY_MS = 264; // per-request RTT floor (audit model, #538 morning reading)
const TUNNEL_THROUGHPUT_BPS = 2.5 * 1024 * 1024; // audit: ~2.5MB/s effective tunnel throughput
const SETTLE_MS = 3000; // post-load settle to catch idle fetches (REST/locale/idle preloads)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class CDP {
  constructor(ws) {
    this.id = 0;
    this.pending = new Map();
    this.handlers = [];
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id) {
        const p = this.pending.get(m.id);
        if (p) {
          this.pending.delete(m.id);
          m.error ? p.rej(new Error(m.error.message)) : p.res(m.result);
        }
      } else for (const h of this.handlers) h(m);
    });
    this.ws = ws;
  }
  send(method, params = {}) {
    return new Promise((res, rej) => {
      const id = ++this.id;
      this.pending.set(id, { res, rej });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  on(fn) { this.handlers.push(fn); }
}

function launchChrome(profileDir) {
  const child = spawn(
    CHROME,
    [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${profileDir}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      '--window-size=1440,900',
      'about:blank',
    ],
    { stdio: 'ignore' }
  );
  return child;
}

async function connect(profileDir) {
  const child = launchChrome(profileDir);
  const portFile = join(profileDir, 'DevToolsActivePort');
  const t0 = Date.now();
  while (Date.now() - t0 < 20000) {
    try {
      const [port, path] = readFileSync(portFile, 'utf8').trim().split('\n');
      const listRes = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await listRes.json();
      const page = targets.find((t) => t.type === 'page');
      const ws = new WebSocket(page.webSocketDebuggerUrl);
      await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', rej, { once: true }); });
      return { child, cdp: new CDP(ws) };
    } catch { await sleep(250); }
  }
  child.kill();
  throw new Error('chrome devtools connect timeout');
}

async function measure(cdp, { label, tunnel, cold }) {
  await cdp.send('Page.enable');
  await cdp.send('Network.enable');
  await cdp.send('Runtime.enable');
  if (cold) {
    await cdp.send('Network.clearBrowserCache').catch(() => {});
    await cdp.send('Network.clearBrowserCookies').catch(() => {});
  }
  await cdp.send('Network.emulateNetworkConditions', tunnel
    ? { offline: false, latency: TUNNEL_LATENCY_MS, downloadThroughput: TUNNEL_THROUGHPUT_BPS, uploadThroughput: TUNNEL_THROUGHPUT_BPS }
    : { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });

  const reqs = new Map();
  const note = (id, patch) => { const r = reqs.get(id); if (r) Object.assign(r, patch); };
  cdp.on((m) => {
    const p = m.params || {};
    if (m.method === 'Network.requestWillBeSent' && p.request.url.startsWith(BASE)) {
      reqs.set(p.requestId, { url: p.request.url.replace(BASE, ''), tStart: p.timestamp, initiator: p.initiator?.type, fromSW: false });
    } else if (m.method === 'Network.responseReceived') {
      note(p.requestId, { status: p.response.status, mime: p.response.mimeType, encHdr: p.response.headers['Content-Encoding'] || p.response.headers['content-encoding'] || null, cc: p.response.headers['Cache-Control'] || p.response.headers['cache-control'] || null });
    } else if (m.method === 'Network.loadingFinished') {
      note(p.requestId, { enc: p.encodedDataLength, tEnd: p.timestamp });
    } else if (m.method === 'Network.loadingFailed') {
      note(p.requestId, { failed: p.errorText, canceled: p.canceled });
    }
  });

  const t0 = Date.now();
  await cdp.send('Page.navigate', { url: BASE + '/' });
  // wait for loadEventEnd (poll), then settle
  const deadline = Date.now() + 120000;
  let nav = null;
  while (Date.now() < deadline) {
    await sleep(250);
    try {
      const r = await cdp.send('Runtime.evaluate', { expression: `(()=>{const n=performance.getEntriesByType('navigation')[0]; if(!n||!n.loadEventEnd) return null; return JSON.stringify({ttfb:n.responseStart,dcl:n.domContentLoadedEventEnd,load:n.loadEventEnd,transfer:n.transferSize,swControlled:!!navigator.serviceWorker?.controller});})()`, returnByValue: true });
      if (r?.result?.value) { nav = JSON.parse(r.result.value); break; }
    } catch { /* page still navigating */ }
  }
  if (!nav) throw new Error(`[${label}] loadEventEnd never fired (120s)`);
  const loadMs = Date.now() - t0;
  await sleep(SETTLE_MS);
  const settleMs = Date.now() - t0;

  const list = [...reqs.values()].filter((r) => r.tEnd !== undefined || r.failed);
  const bytes = list.reduce((a, r) => a + (r.enc || 0), 0);
  const jsReqs = list.filter((r) => /javascript/.test(r.mime || ''));
  const blocking = list.filter((r) => r.status && r.status !== 304 && !(r.enc === 0 && r.status === 200));
  const revalid304 = list.filter((r) => r.status === 304).length;
  const out = {
    label, tunnel, cold, wallToLoadMs: loadMs, wallSettledMs: settleMs,
    nav, requestCount: list.length, totalWireBytes: bytes,
    jsCount: jsReqs.length, jsWireBytes: jsReqs.reduce((a, r) => a + (r.enc || 0), 0),
    revalid304, gzipped: list.filter((r) => r.encHdr).length,
    requests: list.map((r) => ({ url: r.url.slice(0, 120), st: r.status, enc: Math.round(r.enc || 0), cc: r.cc ? r.cc.slice(0, 40) : null, gz: r.encHdr ? 1 : 0, mime: (r.mime || '').split(';')[0] })),
  };
  mkdirSync(OUTDIR, { recursive: true });
  writeFileSync(join(OUTDIR, `${label}.json`), JSON.stringify(out, null, 1));
  console.log(`${label}: load=${loadMs}ms settled=${settleMs}ms reqs=${list.length} wire=${(bytes / 1048576).toFixed(2)}MB jsReqs=${out.jsCount} 304s=${revalid304} sw=${nav.swControlled} DCL=${Math.round(nav.dcl)}ms loadEvent=${Math.round(nav.load)}ms`);
  return out;
}

const profile = mkdtempSync(join(tmpdir(), 'nf-meas-'));
const { child, cdp } = await connect(profile);
try {
  for (const label of LABELS) {
    const tunnel = label.startsWith('tunnel');
    const cold = label.endsWith('cold');
    await measure(cdp, { label, tunnel, cold });
  }
} finally {
  try { await cdp.send('Browser.close'); } catch { child.kill('SIGKILL'); }
  await sleep(300);
}
