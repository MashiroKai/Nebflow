#!/usr/bin/env node
// boot-harness.mjs — headless-Chrome boot waterfall meter (CDP, no puppeteer).
// Usage: node boot-harness.mjs <url> <label> <cpuThrottle> <rttMs> <downBps> <outJson>
// Simulates a phone: CPU throttling (Emulation.setCPUThrottlingRate) +
// network conditions (Network.emulateNetworkConditions: rtt + down/up bps).
import WebSocket from 'ws';
import { writeFileSync } from 'node:fs';

const [url, label, cpuArg, rttArg, downArg, out] = process.argv.slice(2);
const cpuRate = Number(cpuArg || 1), rttMs = Number(rttArg || 0), downBps = Number(downArg || -1);
const port = process.env.CDP_PORT || '9222';

const httpGet = (p) => fetch(`http://127.0.0.1:${port}${p}`).then(r => r.json());
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function withTs(target, method, params = {}, sessionId) {
  const res = await fetch(`http://127.0.0.1:${port}/json/protocol`, { method: 'POST' }).catch(() => null);
  return null; // placeholder, unused
}

const list = await httpGet('/json/list');
let page = list.find(t => t.type === 'page');
if (!page) { const created = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' }).then(r => r.json()); page = created; }
const ws = new WebSocket(page.webSocketDebuggerUrl, { perMessageDeflate: false, maxPayload: 256 * 1024 * 1024 });
await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });

let id = 0; const pending = new Map(); const events = [];
const netReqs = new Map(); const netLog = [];
function send(method, params = {}, sessionId) {
  const mid = ++id;
  return new Promise((res, rej) => {
    pending.set(mid, { res, rej });
    ws.send(JSON.stringify({ id: mid, method, params, sessionId }));
  });
}
ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); return; }
  if (!m.method) return;
  events.push({ ts: Date.now(), method: m.method, params: m.params });
  if (m.method === 'Network.requestWillBeSent') {
    netReqs.set(m.params.requestId, { url: m.params.request.url.slice(0, 200), type: m.params.type, start: m.params.timestamp, initiator: m.params.initiator?.type, stack0: m.params.initiator?.stack?.callFrames?.[0]?.url?.split('/').pop() });
  } else if (m.method === 'Network.responseReceived') {
    const r = netReqs.get(m.params.requestId);
    if (r) { r.status = m.params.response.status; r.encodedSize = m.params.response.encodedDataLength; r.fromCache = m.params.response.fromDiskCache; r.mime = m.params.response.mimeType; r.headers = { cc: m.params.response.headers['cache-control'] || m.params.response.headers['Cache-Control'], ce: m.params.response.headers['content-encoding'] || m.params.response.headers['Content-Encoding'] }; }
  } else if (m.method === 'Network.loadingFinished') {
    const r = netReqs.get(m.params.requestId);
    if (r) { r.end = m.params.timestamp; r.size = m.params.encodedDataLength; }
  } else if (m.method === 'Network.loadingFailed') {
    const r = netReqs.get(m.params.requestId);
    if (r) { r.failed = m.params.errorText; r.end = m.params.timestamp; }
  }
});

await send('Page.enable');
await send('Network.enable');
if (process.env.KEEP_CACHE !== '1') {
  await send('Network.clearBrowserCache');
  await send('Network.clearBrowserCookies');
}
await send('Performance.enable');
await send('Runtime.enable');
await send('Emulation.setCPUThrottlingRate', { rate: cpuRate });
await send('Network.emulateNetworkConditions', { offline: false, latency: rttMs, downloadThroughput: downBps, uploadThroughput: downBps > 0 ? Math.round(downBps / 4) : -1 });
await send('Page.addScriptToEvaluateOnNewDocument', { source: `
  window.__marks = {};
  new PerformanceObserver(l => { for (const e of l.getEntries()) if (e.name === 'first-contentful-paint') window.__marks.fcp = e.startTime; }).observe({ type: 'paint', buffered: true });
  new PerformanceObserver(l => { for (const e of l.getEntries()) { if (!window.__marks.lcp || e.startTime > window.__marks.lcp) window.__marks.lcp = e.startTime; } }).observe({ type: 'largest-contentful-paint', buffered: true });
  window.__wsOpen = 0; window.__wsFirstFrame = 0;
  const OW = window.WebSocket;
  window.WebSocket = function(u, p) { const s = p ? new OW(u, p) : new OW(u); const t0 = performance.now();
    s.addEventListener('open', () => { window.__wsOpen = performance.now(); });
    s.addEventListener('message', () => { if (!window.__wsFirstFrame) window.__wsFirstFrame = performance.now(); });
    return s; };
  window.__marksReady = () => JSON.stringify({ fcp: window.__marks.fcp || null, lcp: window.__marks.lcp || null, wsOpen: window.__wsOpen || null, wsFirst: window.__wsFirstFrame || null, sessions: (document.querySelectorAll('#nav-agent-list .nav-agent, .nav-session, [data-session-id]').length), domInteractive: (performance.getEntriesByType('navigation')[0] || {}).domInteractive || null, domContentLoaded: (performance.getEntriesByType('navigation')[0] || {}).domContentLoadedEventEnd || null, loadEnd: (performance.getEntriesByType('navigation')[0] || {}).loadEventEnd || null });
` });

const t0 = Date.now();
await send('Page.navigate', { url });
// Run for a fixed observation window (boot on throttled phone takes a while).
const OBS = Number(process.env.OBS_MS || 30000);
await sleep(OBS);
let marks = null;
try { const r = await send('Runtime.evaluate', { expression: 'window.__marksReady()', returnByValue: true }); marks = JSON.parse(r.result.value); } catch (e) { marks = { err: String(e) }; }
const perf = await send('Performance.getMetrics');
const nav = events.filter(e => e.method === 'Network.requestWillBeSent').length;
const finished = [...netReqs.values()];
const totalBytes = finished.reduce((s, r) => s + (r.size || 0), 0);
const summary = {
  label, url, cpuRate, rttMs, downBps, obsMs: OBS, wallMs: Date.now() - t0,
  marks, requestCount: nav, totalBytes,
  restCalls: finished.filter(r => r.url.includes('/api/')).length,
  wsRequests: finished.filter(r => r.type === 'WebSocket').map(r => ({ url: r.url.slice(0, 120), status: r.status })),
  metrics: Object.fromEntries(perf.metrics.filter(m => ['DOMContentContentLoaded', 'DOMContentLoaded', 'FirstContentfulPaint', 'LayoutDuration', 'RecalcStyleDuration', 'ScriptDuration', 'TaskDuration', 'JavaScriptDuration', 'Nodes'].includes(m.name)).map(m => [m.name, m.value])),
  requests: finished.map(r => ({ url: r.url, type: r.type, status: r.status, size: r.size, durMs: (r.end && r.start) ? Math.round((r.end - r.start) * 1000) : null, fromCache: !!r.fromCache, failed: r.failed || undefined, cc: r.headers?.cc, ce: r.headers?.ce, initiator: r.initiator, via: r.stack0 })).sort((a, b) => (a.url > b.url ? 1 : -1)),
};
writeFileSync(out, JSON.stringify(summary, null, 1));
console.log(`${label}: reqs=${summary.requestCount} bytes=${(totalBytes / 1048576).toFixed(2)}MB rest=${summary.restCalls} fcp=${marks.fcp && Math.round(marks.fcp)} dcl=${marks.domContentLoaded && Math.round(marks.domContentLoaded)} load=${marks.loadEnd && Math.round(marks.loadEnd)} wsOpen=${marks.wsOpen && Math.round(marks.wsOpen)} firstFrame=${marks.wsFirst && Math.round(marks.wsFirst)} sessions=${marks.sessions}`);
ws.close();
process.exit(0);
