// Real Scala integration: isolated foreground server per scenario; no API or WS mocks.
// Run: node <playwright-cli> test tests/canvas-tab-live-sync.spec.mjs --workers=1
import { test, expect } from '@playwright/test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';

const root = resolve(process.cwd());
const cp = readFileSync(join(root, 'target/e2e-classpath.txt'), 'utf8').trim();
async function freePort() {
  const s = createServer(); s.listen(0, '127.0.0.1'); await once(s, 'listening');
  const port = s.address().port; await new Promise((ok, fail) => s.close(e => e ? fail(e) : ok()));
  if ([6138, 8080, 8097].includes(port)) throw new Error('protected port selected');
  return port;
}
async function start() {
  const dir = mkdtempSync(join(tmpdir(), 'stand-canvas-e2e-'));
  const home = join(dir, 'home'), data = join(home, '.stand');
  mkdirSync(data, { recursive: true });
  writeFileSync(join(data, 'nebflow.json'), JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' } }));
  writeFileSync(join(data, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
  const port = await freePort(), base = `http://127.0.0.1:${port}`;
  const env = { PATH: process.env.PATH, HOME: home, STAND_HOME: data, NEBFLOW_HOME: data, NEBFLOW_GATEWAY_PORT: String(port), LANG: 'en_US.UTF-8' };
  const child = spawn('java', [`-Duser.home=${home}`, '-cp', cp, 'nebflow.Main', '--foreground', '--home', data, '--port', String(port), '--no-browser'], { cwd: root, env, stdio: ['pipe', 'pipe', 'pipe'] });
  let log = ''; child.stdout.on('data', d => { log += d; }); child.stderr.on('data', d => { log += d; });
  const exit = new Promise(r => child.once('exit', (code, signal) => r({ code, signal })));
  try {
    await expect.poll(async () => { if (child.exitCode !== null) throw new Error(`server exited ${child.exitCode}: ${log.slice(-3000)}`); try { return (await fetch(`${base}/api/health`)).status; } catch { return 0; } }, { timeout: 90000, intervals: [250, 500, 1000] }).toBe(200);
    const token = JSON.parse(readFileSync(join(data, 'auth.json'), 'utf8'));
    return { base, child, exit, token, dir, data, port, log: () => log };
  } catch (e) { child.stdin.write('quit\n'); throw e; }
}
async function stop(server) {
  server.child.stdin.write('quit\n');
  const result = await Promise.race([server.exit, new Promise(r => setTimeout(() => r(null), 20000))]);
  if (!result) throw new Error(`isolated server did not stop gracefully; preserved ${server.dir} port ${server.port}`);
  console.log(`[iso] exit=${result.code} signal=${result.signal} port=${server.port} home=${server.dir}`);
}
async function boot(browser, server, mobile = false) {
  const context = await browser.newContext(mobile ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } : { viewport: { width: 1440, height: 960 } });
  const page = await context.newPage(), frames = [], puts = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('websocket', ws => { if (new URL(ws.url()).pathname === '/ws') ws.on('framereceived', e => { try { const f = JSON.parse(e.payload); if (f.type === 'canvasTabsChanged') frames.push(f); } catch {} }); });
  page.on('response', response => { const req = response.request(); if (new URL(response.url()).pathname === '/api/canvas-tabs' && req.method() === 'PUT') puts.push(response.status()); });
  const ticketResponse = await fetch(`${server.base}/api/auth/console-ticket`, { method: 'POST', headers: { Authorization: `Bearer ${server.token}` } });
  expect(ticketResponse.status, 'mint console ticket').toBe(200);
  const { ticket } = await ticketResponse.json();
  await page.goto(`${server.base}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 30000 });
  await expect.poll(() => page.evaluate(() => document.readyState)).toBe('complete');
  return { context, page, frames, puts, errors };
}
const drive = (page, code) => page.evaluate(`(async () => { const m = await import('/js/canvas.js'); ${code} })()`);
const tab = id => `#canvas-tab-bar .canvas-tab[data-tab-id="${id}"]`;
const order = page => page.evaluate(() => Array.from(document.querySelectorAll('#canvas-tab-bar .canvas-tab')).map(e => e.dataset.tabId));
async function archive(page) { return page.evaluate(async () => { const r = await fetch('/api/canvas-tabs'); return r.ok ? await r.json() : null; }); }
async function frame(b, pred, start = 0) { await expect.poll(() => b.frames.slice(start).some(pred), { timeout: 12000 }).toBe(true); }
async function converge(a, b, ids) { await expect.poll(async () => { const [x,y,z] = await Promise.all([order(a.page), order(b.page), archive(a.page)]); const zids = z?.tabs?.map(t => t.id); return ids.every(id => x.includes(id)) && JSON.stringify(x) === JSON.stringify(y) && JSON.stringify(x) === JSON.stringify(zids); }, { timeout: 15000 }).toBe(true); }

test.describe.configure({ mode: 'serial', timeout: 150000 });
let server, a, b;
test.beforeEach(async ({ browser }) => { server = await start(); a = await boot(browser, server); b = await boot(browser, server, true); });
test.afterEach(async () => { if (a) await a.context.close(); if (b) await b.context.close(); if (server) await stop(server); a = b = server = null; });

test('open: real PUT, WS and DOM', async () => {
  const n = b.frames.length; await drive(a.page, "m.openTab('sync-open-a1','Open',{type:'generic',pinned:true})");
  await frame(b, f => f.tabs?.some(t => t.id === 'sync-open-a1'), n);
  await expect(b.page.locator(tab('sync-open-a1'))).toHaveCount(1, { timeout: 12000 });
  await expect.poll(async () => (await archive(a.page))?.tabs?.some(t => t.id === 'sync-open-a1')).toBe(true);
  expect(a.puts.some(s => s === 200)).toBe(true);
});
test('close: real tombstone and DOM', async () => {
  const id = 'sync-close-b1'; await drive(a.page, `m.openTab('${id}','Close',{type:'generic',pinned:true})`);
  await expect(b.page.locator(tab(id))).toHaveCount(1, { timeout: 12000 }); const n = b.frames.length;
  await drive(a.page, `m.closeTab('${id}')`);
  await frame(b, f => f.tombstones?.some(t => t.id === id), n);
  await expect(b.page.locator(tab(id))).toHaveCount(0, { timeout: 12000 });
  await expect.poll(async () => (await archive(a.page))?.tombstones?.some(t => t.id === id)).toBe(true);
});
test('activation: real archive, WS and DOM', async () => {
  await drive(a.page, "m.openTab('act-1','One',{type:'generic',pinned:true});m.openTab('act-2','Two',{type:'generic',pinned:true})");
  await converge(a,b,['act-1','act-2']); const n = b.frames.length;
  await drive(a.page, "m.setActiveTab('act-1')");
  await frame(b, f => f.activeTabId === 'act-1', n);
  await expect(b.page.locator(tab('act-1') + '.active')).toHaveCount(1, { timeout: 12000 });
  await expect.poll(async () => (await archive(a.page))?.activeTabId).toBe('act-1');
});
test('concurrent opens: real archive and both DOMs converge', async () => {
  const na = a.frames.length, nb = b.frames.length;
  await Promise.all([drive(a.page, "m.openTab('cc-a','A',{type:'generic',pinned:true})"), drive(b.page, "m.openTab('cc-b','B',{type:'generic',pinned:true})")]);
  await frame(a, f => f.tabs?.some(t => t.id === 'cc-a'), na);
  await frame(b, f => f.tabs?.some(t => t.id === 'cc-b'), nb);
  await converge(a,b,['cc-a','cc-b']);
});
test('close and reopen: real archive and DOM order', async () => {
  for (const id of ['ord-1','ord-2','ord-3']) await drive(a.page, `m.openTab('${id}','${id}',{type:'generic',pinned:true})`);
  await converge(a,b,['ord-1','ord-2','ord-3']); await drive(a.page, "m.closeTab('ord-2')");
  await expect(b.page.locator(tab('ord-2'))).toHaveCount(0, { timeout: 12000 }); const n = b.frames.length;
  await drive(a.page, "m.openTab('ord-2','ord-2',{type:'generic',pinned:true})");
  await frame(b, f => f.tabs?.some(t => t.id === 'ord-2'), n);
  await expect.poll(async () => (await order(b.page)).filter(id => id.startsWith('ord-')), { timeout: 12000 }).toEqual(['ord-1','ord-3','ord-2']);
  await converge(a,b,['ord-1','ord-2','ord-3']);
});
