#!/usr/bin/env node
// test-sw-update.mjs — SW staleness-chain live test (console-load-perf 批).
// Serves a COPY tree on :8101, then:
//   boot1 fresh profile      → SW installs
//   boot2                    → fully cached (warm)
//   deep-edit js/utils.js    → boot3 must serve the STALE cached copy (1-nav lag,
//                              by design) AND background-reval; boot4 serves NEW
//   index edit               → boot5 must trigger purge + reload (index is the
//                              freshness judge)
// Prints PASS/FAIL per assertion.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, appendFileSync, cpSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { createReadStream, statSync } from 'node:fs';
import { createGzip } from 'node:zlib';
import { extname, join as pjoin, normalize, resolve } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const WORKTREE_WEB = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-d52d18d7/src/main/resources/web';
const PORT = 8101;
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── tree copy + static server (same semantics as serve.mjs) ──
const TREE = mkdtempSync(join(tmpdir(), 'nf-swtest-')) + '/web';
cpSync(WORKTREE_WEB, TREE, { recursive: true });
const ROOT = resolve(TREE);
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' };
createServer((req, res) => {
  try {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/') p = '/index.html';
    const file = normalize(pjoin(ROOT, p));
    if (!file.startsWith(ROOT)) { res.writeHead(404).end(); return; }
    const st = statSync(file);
    if (!st.isFile()) { res.writeHead(404).end(); return; }
    const etag = `"${st.size.toString(16)}-${st.mtimeMs.toString(16)}"`;
    if ((req.headers['if-none-match'] || '').includes(etag)) { res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-cache' }).end(); return; }
    const headers = { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache', ETag: etag, 'Last-Modified': st.mtime.toUTCString() };
    const pipe = () => { res.writeHead(200, headers); createReadStream(file).pipe(res); };
    // utils.js 人工 +3s：让 SWR 后台 reval 慢于 boot settle 窗，boot3 的
    // 「缓存即答=旧 / 旁路=新」对证才可观测（否则 reval 先完成，时机证据丢失）。
    if (file.endsWith('utils.js')) setTimeout(pipe, 3000);
    else pipe();
  } catch { res.writeHead(404).end(); }
}).listen(PORT, '127.0.0.1');

// ── CDP minimal client ──
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id) { const p = this.pending.get(m.id); if (p) { this.pending.delete(m.id); m.error ? p.rej(new Error(m.error.message)) : p.res(m.result); } } }); }
  send(method, params = {}) { return new Promise((res, rej) => { const id = ++this.id; this.pending.set(id, { res, rej }); this.ws.send(JSON.stringify({ id, method, params })); }); }
}
const profile = mkdtempSync(join(tmpdir(), 'nf-swprof-'));
const child = spawn(CHROME, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' });
let cdp;
const portFile = join(profile, 'DevToolsActivePort');
for (let i = 0; i < 80; i++) { try { const [port, path] = readFileSync(portFile, 'utf8').trim().split('\n'); const t = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((x) => x.type === 'page'); const ws = new WebSocket(t.webSocketDebuggerUrl); await new Promise((r, j) => { ws.addEventListener('open', r, { once: true }); ws.addEventListener('error', j, { once: true }); }); cdp = new CDP(ws); break; } catch { await sleep(250); } }
if (!cdp) { console.error('FAIL: no CDP'); process.exit(1); }
const evalx = async (expr) => (await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.value;

async function boot(label) {
  await cdp.send('Page.enable');
  await cdp.send('Page.navigate', { url: BASE + '/' });
  for (let i = 0; i < 240; i++) { await sleep(250); if (await evalx(`(()=>{const n=performance.getEntriesByType('navigation')[0]; return n&&n.loadEventEnd>0})()`)) break; }
  await sleep(1500);
  return evalx(`JSON.stringify({sw: !!navigator.serviceWorker?.controller, marked: typeof marked !== 'undefined', lucideIcons: document.querySelectorAll('svg[class*="lucide"]').length})`).then((v) => JSON.parse(v || '{}'));
}
const cachedBody = () => evalx(`(async()=>{const c=await caches.open('nfsw-v1'); const r=await c.match('${BASE}/js/utils.js'); return r? (await r.text()).includes('SWTEST_MARKER_A')?'A':((await r.clone().text()).includes('SWTEST_MARKER_B')?'B':'other') : 'none'})()`);

const results = [];
const check = (name, ok, detail) => { results.push([name, ok, detail]); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`); };

// boot1: install
let b = await boot('boot1');
check('boot1 SW installed', b.sw === true, JSON.stringify(b));
check('boot1 app booted (marked global)', b.marked === true);
// boot2: warm
b = await boot('boot2');
check('boot2 SW controlled warm', b.sw === true);
// deep edit
const utils = join(TREE, 'js/utils.js');
appendFileSync(utils, '\n// SWTEST_MARKER_A\n');
b = await boot('boot3');
const pair3 = JSON.parse(await evalx(`(async()=>{const staleServed=await (await fetch('/js/utils.js')).text(); const netFresh=await (await fetch('/js/utils.js?nfsw=1')).text(); return JSON.stringify({staleHasA: staleServed.includes('SWTEST_MARKER_A'), netHasA: netFresh.includes('SWTEST_MARKER_A')})})()`));
check('boot3 serves STALE from SW cache by design (1-nav lag)', pair3.staleHasA === false, JSON.stringify(pair3));
check('boot3 ?nfsw=1 bypass reaches fresh network copy', pair3.netHasA === true, JSON.stringify(pair3));
b = await boot('boot4');
check('boot4 serves fresh deep edit via background reval', (await cachedBody()) === 'A', 'cached marker=' + (await cachedBody()));
// index edit → purge + reload
const idx = join(TREE, 'index.html');
const before = readFileSync(idx, 'utf8');
writeFileSync(idx, before.replace('</head>', '<!-- SWTEST_INDEX_BUMP --></head>'));
const navs = [];
cdp.ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.method === 'Page.frameNavigated' && m.params.frame.parentId === undefined) navs.push(Date.now()); });
b = await boot('boot5');
check('boot5 (post-index-edit) still booted', b.marked === true && b.sw === true, JSON.stringify(b));
b = await boot('boot6');
check('boot6 steady after index bump', b.marked === true);
const freshIndex = (await cachedBody('x')) || 'n/a';
const cacheHasFresh = await evalx(`(async()=>{const c=await caches.open('nfsw-v1'); const r=await c.match('${BASE}/js/utils.js'); return r? (await r.text()).includes('SWTEST_MARKER_A')?'A':'stale':'none'})()`);
check('boot6 cache holds post-purge fresh asset', cacheHasFresh === 'A', 'marker=' + cacheHasFresh);

console.log('\n== SUMMARY ==');
const fails = results.filter(([, ok]) => !ok).length;
console.log(fails === 0 ? 'ALL PASS' : `${fails} FAIL`);
child.kill('SIGKILL');
rmSync(TREE, { recursive: true, force: true });
process.exit(fails === 0 ? 0 : 1);
