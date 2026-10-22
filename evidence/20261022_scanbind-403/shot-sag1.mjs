// evidence shots for scan-bind-403 batch — SAG-1 (begin 403) before/after faces.
// BEFORE tree = pristine HEAD web (git archive), AFTER tree = working tree.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const AFTER_DIR = resolve(HERE, '../../src/main/resources/web');
const BEFORE_DIR = '/tmp/ab-web/src/main/resources/web';
const PORT = Number(process.env.PORT || 8151);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = resolve(HERE, '../shots');
const MODE = process.argv[2] || 'after';
const WEB_DIR = MODE === 'before' ? BEFORE_DIR : AFTER_DIR;

const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
const page = await ctx.newPage();
await page.addInitScript(() => { try { localStorage.setItem('nebflow_locale', 'zh-CN'); } catch { /* */ } });
await page.routeWebSocket(/\/ws/, (ws) => {
  ws.onMessage(() => {});
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
});
await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await page.route('**/api/social/channels', (r) => r.fulfill({ json: { channels: {}, adapterRegistered: false } }));
await page.route('**/api/social/probe**', (r) => r.fulfill({ json: { adapterRegistered: false, secrets: {} } }));
await page.route('**/api/social/channels/*/scan-bind/begin*', (r) =>
  r.request().method() === 'POST' ? r.fulfill({ status: 403, json: { error: 'forbidden' } }) : r.fallback());
await page.goto(BASE);
await page.click('#social-btn');
await page.waitForSelector('#social-overlay.on', { timeout: 8000 });
await page.waitForFunction(() => {
  const s = document.getElementById('social-section-channels');
  return !!s && s.getAttribute('aria-busy') === 'false';
}, undefined, { timeout: 8000 });
await page.waitForFunction(() => {
  const el = document.querySelector('.social-card[data-channel="weixin-ilink"] [data-scan-status]');
  return !!el && el.textContent.includes('扫码绑定失败');
}, undefined, { timeout: 8000 });
await page.screenshot({ path: join(OUT, `sag1-begin403-${MODE}-1440-light.png`), fullPage: false });
console.log(`shot: ${join(OUT, `sag1-begin403-${MODE}-1440-light.png`)} (${MODE})`);
await browser.close();
await new Promise((r) => server.close(r));
