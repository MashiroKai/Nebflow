// social-panel-fix-shots.mjs — social-fix-confirm batch (author order
// 2026-10-09, 社交接口面板三处 UI 修正): visual self-check screenshots.
//
// Family: tests/activity-bar-shots.mjs (playwright-core + route mocks + a
// self-contained static server on a random port; no real backend, no port
// taken from the host, browser closed in finally). Output goes to
// SOCIAL_FIX_SHOTS_DIR (default /tmp/nebflow-coldstart/general/social-panel-fix-shots):
//   social-fix-panel-light.png / social-fix-panel-dark.png   — two bound rows,
//       no appId anywhere (fix ②), header insets symmetric (fix ①)
//   social-fix-hover-light.png / social-fix-hover-dark.png   — the unbind chip
//       hovered: the danger 10% wash (fix ③a)
//   social-fix-confirm-light.png / social-fix-confirm-dark.png — the shared
//       glass confirm carrying the channel name + 解除绑定/取消 (fix ③b)
//
// Run: node tests/social-panel-fix-shots.mjs

import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.SOCIAL_FIX_SHOTS_DIR || '/tmp/nebflow-coldstart/general/social-panel-fix-shots';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

/** Both visible channels bound (the screenshot's frame): identifiers
 *  plaintext, the secret as a `_ref` path, the probe triple clean, the adapter
 *  registered — the mock mirror of the real created face. */
const apiState = {
  channels: {
    'weixin-ilink': { enabled: true, fields: { ilink_bot_id: 'wxid_mock_bot', ilink_user_id: 'wxid_mock_user', bot_token_ref: '~/.nebflow/secrets/social-weixin-bot-token' } },
    feishu: { enabled: true, fields: { app_id: 'cli_mock0123456789ab', app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret' } },
  },
  probes: {
    'weixin-ilink': { bot_token: { exists: true, modeOk: true, readable: true } },
    feishu: { app_secret: { exists: true, modeOk: true, readable: true } },
  },
  registered: { 'weixin-ilink': true, feishu: true },
};

const server = createServer(async (req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) {
    const json = (o) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
    if (p === '/api/social/channels') return json({ channels: apiState.channels });
    if (p.startsWith('/api/social/probe')) {
      const id = new URL(req.url, 'http://x').searchParams.get('channel');
      return json({ adapterRegistered: apiState.registered[id] === true, secrets: apiState.probes[id] || {} });
    }
    // C1: the live app the running bridge holds — rendered NOWHERE by the
    // social-fix-confirm face; the shot proves the absence visually.
    if (p === '/api/social/channels/feishu/connection') {
      return json({ channel: 'feishu', enabled: true, adapterRegistered: true, connected: true, liveAppId: 'cli_aa4d3f152538dcb4', liveAppIdFp: 'abcdef', liveAppName: null, storedAppId: 'cli_aa4d3f152538dcb4', storedAppIdFp: '9999', fingerprintMatch: 'match' });
    }
    if (p === '/api/social/channels/feishu/default-session') return json({ sessionId: 'sess-nebula' });
    if (p === '/api/sessions') return json({ sessions: [{ id: 'sess-nebula', name: 'Nebula' }] });
    return json({});
  }
  try {
    const data = await readFile(normalize(join(WEB, p)));
    res.writeHead(200, { 'Content-Type': MIME[extname(p)] || 'application/octet-stream' });
    res.end(data);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
await mkdir(OUT, { recursive: true });

for (const scheme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 760, height: 620 }, colorScheme: scheme, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    try { localStorage.setItem('nebflow_token', 'smoke-token'); localStorage.setItem('nebflow_locale', 'zh-CN'); } catch { /* ignore */ }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
  await page.goto(base);
  await page.waitForSelector('#social-btn svg', { timeout: 8000 });
  await page.click('#social-btn');
  await page.waitForSelector('#social-overlay.on', { timeout: 4000 });
  await page.waitForFunction(() => document.getElementById('social-section-channels')?.getAttribute('aria-busy') === 'false', undefined, { timeout: 8000 });
  await page.waitForTimeout(500);

  // ① + ② the bound face: two static rows, no appId box, symmetric insets
  await page.screenshot({ path: join(OUT, `social-fix-panel-${scheme}.png`) });

  // ③a the unbind chip hovered: the danger wash (feishu row's chip)
  await page.hover('.social-card[data-channel="feishu"] [data-archive]');
  await page.waitForTimeout(400); // the control's transition budget
  await page.screenshot({ path: join(OUT, `social-fix-hover-${scheme}.png`) });

  // ③b the shared glass confirm, channel name interpolated
  await page.click('.social-card[data-channel="feishu"] [data-archive]');
  await page.waitForFunction(() => document.getElementById('modal-overlay')?.classList.contains('on'), undefined, { timeout: 4000 });
  await page.waitForTimeout(350); // the dialog's entrance animation
  await page.screenshot({ path: join(OUT, `social-fix-confirm-${scheme}.png`) });

  // leave the state clean for the next context (cancel, never confirm)
  await page.click('#delete-cancel');
  await page.waitForFunction(() => !document.getElementById('modal-overlay').classList.contains('on'), undefined, { timeout: 4000 });
  await ctx.close();
}

await browser.close();
server.close();
console.log(`social-fix-confirm shots → ${OUT}`);
