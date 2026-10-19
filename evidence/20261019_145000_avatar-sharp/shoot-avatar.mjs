// shoot-avatar.mjs — avatar-sharp batch 2026-10-19 evidence leg.
// Diagnosis + before/after capture for「头像 slot 很模糊」.
//
// Boots the worktree web/ tree on an isolated static server (never the host
// 8080), injects window.__BRAND__ with the avatar supply under test (the
// gateway would inject the same fields — brand.conf logoUrl/logoDarkUrl),
// and captures BOTH avatar consumers (#activity-avatar, settings avatar) at
// deviceScaleFactor 1 and 2, light and dark schemes.
//
// Variants (VARIANT env, or matrix of all):
//   before-128       current supply: release symbol-{light,dark}-128.png (art 128×105)
//   before-128-nopx  same 128px PNG but image-rendering:pixelated disabled
//                    (isolates the pixel-art leftover rule's contribution)
//   ctrl-512         control: illustrator symbol-{light,dark}.png (512×419 source)
//   after-svg        fix: illustrator svg stand-symbol-{black,white}.svg
//   after-svg-rel    fix as shipped: release dir symbol-{light,dark}.svg
//
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node shoot-avatar.mjs [variant …]
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', '..', 'src', 'main', 'resources', 'web');
const PORT = 8191;
const SHOTS = join(HERE, 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json',
};

const REL = '/brand/releases/f9e37ba994f63d8f'; // pre-fix revision (128px supply)
const REL_NEW = '/brand/releases/71a456e2d388b27e'; // avatar-sharp revision (SVG supply)
const VARIANTS = {
  // Pre-fix tree state = 128px supply + `image-rendering: pixelated` slot rule.
  // forcePixelated re-applies the retired rule so the before state stays
  // reproducible after the CSS fix lands (the first capture ran pre-fix).
  'before-128': { light: `${REL}/symbol-light-128.png`, dark: `${REL}/symbol-dark-128.png`, forcePixelated: true },
  'before-128-nopx': { light: `${REL}/symbol-light-128.png`, dark: `${REL}/symbol-dark-128.png`, noPixelated: true },
  'ctrl-512': { light: '/brand/stand/illustrator/symbol-light.png', dark: '/brand/stand/illustrator/symbol-dark.png' },
  'after-svg': { light: '/brand/stand/illustrator/svg/stand-symbol-black.svg', dark: '/brand/stand/illustrator/svg/stand-symbol-white.svg' },
  'after-svg-rel': { light: `${REL_NEW}/symbol-light.svg`, dark: `${REL_NEW}/symbol-dark.svg` },
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

async function boot(page, variant) {
  await page.addInitScript((v) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    window.__BRAND__ = { name: 'stand', logoUrl: v.light, logoDarkUrl: v.dark };
  }, variant);
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

const requested = process.argv.slice(2);
const names = requested.length ? requested : Object.keys(VARIANTS);
const server = await startServer();
const base = `http://127.0.0.1:${PORT}`;
await mkdir(SHOTS, { recursive: true });
const browser = await chromium.launch();
const report = [];

try {
  for (const name of names) {
    const variant = VARIANTS[name];
    if (!variant) { console.error(`unknown variant ${name}`); process.exitCode = 1; continue; }
    for (const scheme of ['light', 'dark']) {
      for (const dsf of [1, 2]) {
        const ctx = await browser.newContext({
          viewport: { width: 1440, height: 900 }, colorScheme: scheme, deviceScaleFactor: dsf,
        });
        const page = await ctx.newPage();
        await boot(page, variant);
        await page.goto(base, { waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(2000);
        if (variant.noPixelated) {
          await page.addStyleTag({ content: '.activity-avatar-logo,.settings-avatar-logo{image-rendering:auto !important}' });
          await page.waitForTimeout(150);
        }
        if (variant.forcePixelated) {
          await page.addStyleTag({ content: '.activity-avatar-logo,.settings-avatar-logo{image-rendering:pixelated !important}' });
          await page.waitForTimeout(150);
        }
        const tag = `${name}-${scheme}-dsf${dsf}`;
        // Slot 1: activity-bar avatar, logged-out resting state (dim+grayscale rule).
        const act = page.locator('#activity-avatar');
        await act.screenshot({ path: join(SHOTS, `${tag}-activity.png`) });
        // Slot 1b: same slot in the paired (logged-in) state — full opacity + glow.
        await page.evaluate(() => document.getElementById('activity-avatar').classList.add('paired'));
        await page.waitForTimeout(150);
        await act.screenshot({ path: join(SHOTS, `${tag}-activity-paired.png`) });
        // Slot 2: settings avatar entry (settings modal).
        await page.click('#settings-btn');
        await page.waitForSelector('#settings-avatar-entry', { timeout: 5000 });
        await page.waitForTimeout(400);
        const set = page.locator('#settings-avatar-entry');
        await set.screenshot({ path: join(SHOTS, `${tag}-settings.png`) });
        // Read back the actually painted supply + geometry for the report.
        const info = await page.evaluate(() => {
          const img = document.querySelector('#activity-avatar img.activity-avatar-logo');
          const simg = document.querySelector('#settings-avatar-entry img.settings-avatar-logo');
          const r = (el) => el ? { src: el.currentSrc || el.src, css: [el.clientWidth, el.clientHeight], natural: [el.naturalWidth, el.naturalHeight] } : null;
          return { activity: r(img), settings: r(simg), dsf: window.devicePixelRatio };
        });
        report.push({ tag, ...info });
        console.log(`[${tag}]`, JSON.stringify(info));
        await ctx.close();
      }
    }
  }
  await writeFile(join(SHOTS, 'report.json'), JSON.stringify(report, null, 2));
  console.log('OK shots written to', SHOTS);
} finally {
  await browser.close();
  server.close();
}
