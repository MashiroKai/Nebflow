// Settings identity regression: the retired identity capsule is no longer rendered.
// Exercise the current account card and six-group settings DOM instead. REST/WS
// inputs are mocked; this is a browser consumer test, not an instance test.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json' };
async function boot(page, mobile) {
  await page.setViewportSize(mobile ? { width: 390, height: 844 } : { width: 1600, height: 1000 });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'test');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/neblink/status') return route.fulfill({ json: { loggedIn: false, peers: [] } });
    if (p.startsWith('/api/') || p.startsWith('/agents/')) return route.fulfill({ json: {} });
    const file = normalize(join(web, p));
    if (!file.startsWith(web)) return route.fulfill({ status: 403 });
    try { return route.fulfill({ status: 200, contentType: mime[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404 }); }
  });
  await page.routeWebSocket(/\/ws/, ws => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    config();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
    ws.onMessage(raw => { try { if (JSON.parse(raw).type === 'getConfig') config(); } catch {} });
  });
  await page.goto('http://localhost:1/');
  if (mobile) {
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
    await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
    await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  } else await page.locator('#settings-btn').click();
  await expect(page.locator('#settings-content')).toBeVisible();
}
for (const mobile of [false, true]) {
  test(`${mobile ? 'mobile' : 'desktop'} settings account and six ordered groups`, async ({ page }) => {
    await boot(page, mobile);
    const groups = page.locator('#settings-content > [data-panel-group]');
    await expect(groups).toHaveCount(6);
    expect(await groups.evaluateAll(nodes => nodes.map(n => n.dataset.panelGroup))).toEqual(['account', 'general', 'models', 'runtime', 'advanced', 'about']);
    await expect(page.locator('[data-panel-group="account"] #account-card')).toBeVisible();
    await expect(page.locator('#cfg-language')).toHaveCount(1);
    await expect(page.locator('#settings-identity-card')).toHaveCount(0);
  });
}
