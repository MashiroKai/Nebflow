// activity-bar-shots.mjs — visual self-check screenshots for Activity Bar v1.1
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'smoke-session-1';

const browser = await chromium.launch();

async function makePage(colorScheme) {
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme, deviceScaleFactor: 2 })).newPage();
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], folders: [], activeId: SID }));
  });
  await page.goto('http://localhost:1/');
  await page.waitForTimeout(1500);
  return page;
}

const clip = { x: 0, y: 0, width: 340, height: 500 };

// Light theme: expanded (files active)
let page = await makePage('light');
await page.screenshot({ path: '/tmp/ab-v11-light-expanded.png', clip });
// hover the files button
await page.hover('#files-btn');
await page.waitForTimeout(250);
await page.screenshot({ path: '/tmp/ab-v11-light-hover.png', clip });
// collapsed
await page.click('#files-btn');
await page.waitForTimeout(500);
await page.screenshot({ path: '/tmp/ab-v11-light-collapsed.png', clip });
await page.close();

// Dark theme: expanded + collapsed
page = await makePage('dark');
await page.screenshot({ path: '/tmp/ab-v11-dark-expanded.png', clip });
await page.click('#files-btn');
await page.waitForTimeout(500);
await page.screenshot({ path: '/tmp/ab-v11-dark-collapsed.png', clip });
await page.close();

await browser.close();
console.log('shots saved to /tmp/ab-v11-*.png');
