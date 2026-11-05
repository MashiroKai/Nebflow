// composer-send-stop-busy-shots.mjs — busy 态（终止钮上位）改前/改后对照截图
// （作者报障 2026-11-03 22:15 第八条：rest 态 stop 是 display:none，作者实拍里
//   的「大发送 vs 小终止」正是 busy 互换态——本脚本把两钮分别置显拍证。）
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8279);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/send-stop-parity';
const PHASE = process.env.PARITY_PHASE || 'phase';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff' };
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const SID = 's-sendstop';
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: 'dark', hasTouch: true, deviceScaleFactor: 2, locale: 'zh-CN' });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  localStorage.setItem('stand_input_history', '[]');
});
await ctx.route('**/api/**', (r) => r.fulfill({ status: 200, json: {} }));
await ctx.routeWebSocket(/\/ws/, (ws) => {
  const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  cfg(); ses();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});
const page = await ctx.newPage();
await page.goto(BASE + '/index.html');
await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
await sleep(1000);

// busy 态：走真实包状态机入口（composer.createTurnToggle 的同款 chrome 语句）
await page.evaluate(() => {
  const send = document.getElementById('send-btn');
  const stop = document.getElementById('stop-btn');
  send.style.display = 'none';
  stop.style.display = 'flex';
});
await sleep(300);
const clip = await page.evaluate(() => {
  const b = document.getElementById('input-bar').getBoundingClientRect();
  return { x: Math.max(0, b.left - 8), y: Math.max(0, b.top - 8), width: Math.min(390, b.width + 16), height: Math.min(220, b.height + 16) };
});
await page.screenshot({ path: join(SHOTS_DIR, `${PHASE}-mobile-busy.png`), clip });
const geo = await page.evaluate(() => {
  const s = (el) => { const r = el.getBoundingClientRect(); return { w: Math.round(r.width * 10) / 10, h: Math.round(r.height * 10) / 10 }; };
  return { send: s(document.getElementById('send-btn')), stop: s(document.getElementById('stop-btn')) };
});
console.log(`${PHASE} busy 态几何: send(display:none 本体) ${JSON.stringify(geo.send)} · stop ${JSON.stringify(geo.stop)}`);

await browser.close();
server.close();
