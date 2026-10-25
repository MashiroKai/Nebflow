// authgate-projects-403.spec.mjs — 项目列表 403 族收口验收（scan-bind-403 批
// 2026-10-22）。
//
// 作者实报症状「项目列表加载失败 projects 403」的静默根：fetchProjectsRaw 被
// 401/403 拒时只把错误文本抛给面板错误态（"projects 403"），不拉 sessionGate
// 的可见横幅 + 自愈 reload（nfTicket #520 在 2026-10-18 已收口铸票腿；本件把
// 项目列表腿并入同一纪律）。引擎重启清零宿主会话（内存态，设计权衡）或 relay
// 门会话过期时，控制台首个项目列表读取即触发本腿。
//
// 契约：
//   ① /api/projects 403 ⇒ fetchProjectsRaw 拒绝（消息仍带状态码，供面板错误态
//      显示）+ 可见横幅（data-source="projects"）+ 自愈 reload 腿被拉动；
//   ② /api/projects 401 ⇒ 同一收口（两状态同语义）；
//   ③ /api/projects 503（真传输错）⇒ 拒绝照旧、零横幅零 reload（5xx 静默降级
//      面不变）。
//
// 运行：node tests/authgate-projects-403.spec.mjs   （PORT 可换）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8147);
const BASE = `http://127.0.0.1:${PORT}`;

const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; failures.push(name); console.log(`FAIL  ${name}${extra ? '  — ' + extra : ''}`); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const LAB_HTML = '<!doctype html><html><head><meta charset="utf-8"><title>lab</title></head><body>lab</body></html>';

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await page.route('**/lab.html', (route) => route.fulfill({ contentType: 'text/html', body: LAB_HTML }));
  let projectsStatus = 403;
  await page.route('**/api/projects', (route) =>
    route.fulfill({ status: projectsStatus, contentType: 'application/json', body: '{"error":"denied"}' }));

  await page.goto(`${BASE}/lab.html`);
  await page.evaluate(async () => {
    const m = await import('/js/sessionGate.js');
    window.__gate = m;
    m._sessionGateTestHooks().setReload(() => { window.__reloads = (window.__reloads || 0) + 1; });
    // 稳定性批 2026-10-26：noteAuthRejected 先走静默重座腿；lab 无 portal 面 ⇒
    // 缝为「重座不可用」，可见面判定确定化（重座行为本体在 session-gate.spec ①c/①d 钉）。
    m._sessionGateTestHooks().setReseat(async () => false);
    window.__nd = await import('/js/nodeData.js');
  });

  // ① 403 ⇒ 拒绝消息带状态码 + 横幅 + reload 腿。
  const msg403 = await page.evaluate(async () => {
    try { await window.__nd.fetchProjectsRaw(); return ''; }
    catch (e) { return String(e && e.message); }
  });
  ok('① 403 ⇒ fetchProjectsRaw 拒绝且消息带状态码', msg403 === 'projects 403', `msg=${JSON.stringify(msg403)}`);
  await sleep(300);
  ok('① 403 ⇒ 可见横幅出现', await page.locator('[data-session-gate="1"]').count() === 1);
  ok('① 横幅带触发面读数 projects', (await page.locator('[data-session-gate="1"]').getAttribute('data-source')) === 'projects');
  await sleep(1500); // > RELOAD_DELAY_MS
  ok('① 自愈 reload 腿被拉动', (await page.evaluate(() => window.__reloads)) === 1);

  // ② 复位单飞与冷却后 401 ⇒ 同一收口。
  await page.evaluate(() => window.__gate._sessionGateTestHooks().reset());
  projectsStatus = 401;
  const msg401 = await page.evaluate(async () => {
    try { await window.__nd.fetchProjectsRaw(); return ''; }
    catch (e) { return String(e && e.message); }
  });
  await sleep(200);
  ok('② 401 ⇒ 同一收口（横幅单例仍在、消息带状态码）',
    msg401 === 'projects 401' && await page.locator('[data-session-gate="1"]').count() === 1,
    `msg=${JSON.stringify(msg401)}`);

  // ③ 5xx ⇒ 拒绝照旧、零新增横幅零 reload（静默降级面保持）。
  await page.evaluate(() => window.__gate._sessionGateTestHooks().reset());
  projectsStatus = 503;
  const reloadsBefore = await page.evaluate(() => window.__reloads);
  const msg503 = await page.evaluate(async () => {
    try { await window.__nd.fetchProjectsRaw(); return ''; }
    catch (e) { return String(e && e.message); }
  });
  await sleep(300);
  ok('③ 503 ⇒ 拒绝消息带状态码（降级语义不变）', msg503 === 'projects 503', `msg=${JSON.stringify(msg503)}`);
  ok('③ 503 ⇒ 零新增横幅零 reload',
    (await page.evaluate(() => document.querySelectorAll('[data-session-gate="1"]').length)) === 1
    && (await page.evaluate(() => window.__reloads)) === reloadsBefore,
    `reloads=${await page.evaluate(() => window.__reloads)} before=${reloadsBefore}`);
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILURES: ' + failures.join(' | ')); process.exit(1); }
