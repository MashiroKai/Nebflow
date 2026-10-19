// shoot-console.mjs — brand-standard-console batch 2026-10-19 evidence leg.
// Boots the worktree web/ tree on an isolated static server (8100+ discipline,
// never the host 8080), renders the console in BOTH color schemes with the
// same API/WS mock set as tests/sidebar-restructure.spec.mjs, and captures:
//   shots/console-light.png / console-dark.png   (full page)
//   shots/avatar-light.png / avatar-dark.png     (#activity-avatar close-up)
//   http-checks.json                             (asset reachability probes)
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node shoot-console.mjs
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', '..', 'src', 'main', 'resources', 'web');
const PORT = 8189;
const SHOTS = join(HERE, 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
};

function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch { res.writeHead(404); res.end('not found'); }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  await page.route('**/mock-avatar-*.png', r => r.fulfill({ body: PNG_1PX, contentType: 'image/png' }));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
  await page.route('**/api/plugins', r => r.fulfill({ json: { plugins: [], rejected: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
    });
  });
}

const server = await startServer();
const base = `http://127.0.0.1:${PORT}`;
await mkdir(join(SHOTS), { recursive: true });
const browser = await chromium.launch();

try {
  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
    const page = await ctx.newPage();
    await boot(page);
    await page.goto(base, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    await page.screenshot({ path: join(SHOTS, `console-${scheme}.png`), fullPage: true });
    const avatar = page.locator('#activity-avatar');
    if (await avatar.count()) await avatar.screenshot({ path: join(SHOTS, `avatar-${scheme}.png`) });
    const probe = await page.evaluate(async () => {
      const paths = [
        '/brand/releases/f9e37ba994f63d8f/symbol-light-128.png',
        '/brand/releases/f9e37ba994f63d8f/symbol-dark-128.png',
        '/brand/stand/illustrator/symbol-light.png',
        '/brand/stand/illustrator/symbol-dark.png',
        '/favicon-32.png?v=f9e37ba994f63d8f',
        '/favicon.svg?v=f9e37ba994f63d8f',
        '/brand/black-cat/source.png',
      ];
      const out = {};
      for (const p of paths) { try { const r = await fetch(p); out[p] = r.status; } catch { out[p] = 'ERR'; } }
      return out;
    });
    await writeFile(join(SHOTS, `http-probes-${scheme}.json`), JSON.stringify(probe, null, 2));
    console.log(`[${scheme}]`, JSON.stringify(probe));
    await ctx.close();
  }
  // favicon byte spot-checks into evidence
  for (const [src, dst] of [
    ['favicon-32.png', 'favicon-32.png'], ['favicon.ico', 'favicon.ico'],
    ['brand/releases/f9e37ba994f63d8f/favicon.svg', 'favicon.svg'],
    ['brand/releases/f9e37ba994f63d8f/light-512.png', 'app-icon-light-512.png'],
    ['brand/releases/f9e37ba994f63d8f/dark-512.png', 'app-icon-dark-512.png'],
  ]) {
    await writeFile(join(HERE, dst), await readFile(join(WEB, src)));
  }
  console.log('OK shots + probes + favicon bytes written');
} finally {
  await browser.close();
  server.close();
}
