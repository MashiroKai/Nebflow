// provider-panel-shots.spec.mjs — 服务商统一面板的并排实拍（provider-add-tabs 批，
// 作者令「以图为主：改前/改后并排实拍（375 亮/暗 + 1440），标签切换各态各一张」）。
//
// 与 provider-panel-overflow.spec.mjs 共用同一 harness 口径（自持静态服 + route
// mock API/WS，零真实引擎；NB_WEB_ROOT 换根即为旧树读数）：
//   · 375 亮/暗、1440 亮/暗：设置页全景（含统一服务商面板）；
//   · 375 亮：各类型标签各一张（LLM / Image generation 列表态；Jev 面已随
//     JeV 退役删除——jev-retire 批 2026-11-16）；
//   · 375 亮：新增表单（LLM 弹窗骨架）一张；
//   · 全部落 captures/provider-panel/<tag>.png（对照树跑同名前缀 old-*）。
// Run: npx playwright test tests/provider-panel-shots.spec.mjs
//      NB_WEB_ROOT=/tmp/nf-old-web SHOT_PREFIX=old- npx playwright test …

import { test } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..');
const WEB = process.env.NB_WEB_ROOT ? resolve(process.env.NB_WEB_ROOT) : resolve(REPO_ROOT, 'src', 'main', 'resources', 'web');
const PREFIX = process.env.SHOT_PREFIX || '';
const OUT = resolve(REPO_ROOT, 'captures', 'provider-panel');
const PORT = 8273; // 8100+ 自持；永不碰宿主 8080/6138
const BASE = `http://127.0.0.1:${PORT}`;
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon', '.json': 'application/json' };

const SID = 'prov-shots-root';
const NOW = Date.now();

const CONFIG = {
  llm: {
    providers: {
      anthropic: {
        baseUrl: 'https://api.anthropic.example/v1/',
        apiKey: '***',
        protocol: 'anthropic',
        models: [{ id: 'claude-3-7-sonnet-20250219-extended-thinking-preview', contextWindow: 200000 }],
        unavailableWindows: [{ from: '23:00', to: '07:00', days: [6, 7] }],
      },
      kimi: { baseUrl: 'https://api.moonshot.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'kimi-k2', contextWindow: 128000 }] },
    },
  },
};
const IMAGEGEN = {
  compiledIn: true, enabled: true, defaultProvider: 'zhipu',
  providers: { zhipu: { type: 'cogview', model: 'cogview-4-250304-flash-preview-long', keyConfigured: true } },
};
// （jev-retire 批 2026-11-16：JEV 夹具与 jev 域投影随 JeV 退役摘除——面板只剩
// llm/imagegen 两域。）
const TYPES = {
  domains: [{ id: 'llm', enabledSwitch: false }, { id: 'imagegen', enabledSwitch: true }],
  adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview', 'openai-images', 'imagen'] },
};

function startServer() {
  return new Promise((done) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
        res.end(data);
      } catch { res.writeHead(404); res.end('not found'); }
    });
    server.listen(PORT, '127.0.0.1', () => done(server));
  });
}
let server;
test.beforeAll(async () => { server = await startServer(); mkdirSync(OUT, { recursive: true }); });
test.afterAll(async () => { if (server) server.close(); });

async function bootApp(page, theme) {
  await page.emulateMedia({ colorScheme: theme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'en');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await page.route('**/api/**', (route) => {
    const p = new URL(route.request().url()).pathname;
    if (p === '/api/config/provider-types') return route.fulfill({ json: TYPES });
    if (p === '/api/config/imagegen') return route.fulfill({ json: IMAGEGEN });
    if (p === '/api/config/provider-keys') return route.fulfill({ json: { providers: {} } });
    if (p === '/api/secrets') return route.fulfill({ json: { secrets: [] } });
    if (p === '/api/decision/health') return route.fulfill({ json: { decision: { status: 'up', reason: '' } } });
    if (p === '/api/sessions') return route.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) return route.fulfill({ json: { mode: 'follow', chain: null, effectiveChain: [] } });
    return route.fulfill({ json: {} });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = JSON.stringify(CONFIG);
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: cfg, configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
  await page.goto(`${BASE}/index.html`);
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await page.waitForTimeout(400);
}

async function openSettings(page) {
  await page.evaluate(async () => {
    (await import('/js/sidebar.js')).openSettingsPanel();
    document.getElementById('settings-overlay')?.classList.add('on');
  });
  // 双树兼容：新树（本批）等统一面板；旧树（NB_WEB_ROOT=/tmp/nf-old-web 对照，
  // 改前形态）面板不存在——等旧设置节在场即可（截图腿照常落盘，用作改前对照）。
  await page.waitForSelector('#provider-panel-host .nf-provider-panel, #settings-content .settings-section', { timeout: 15000 });
  await page.waitForTimeout(500);
}

const CELLS = [
  { width: 375, height: 812, theme: 'light', tag: '375-light' },
  { width: 375, height: 812, theme: 'dark', tag: '375-dark' },
  { width: 1440, height: 900, theme: 'light', tag: '1440-light' },
  { width: 1440, height: 900, theme: 'dark', tag: '1440-dark' },
];

for (const cell of CELLS) {
  test(`shot settings @ ${cell.tag}`, async ({ page }) => {
    await page.setViewportSize({ width: cell.width, height: cell.height });
    await bootApp(page, cell.theme);
    await openSettings(page);
    // 滚到服务商面板（截图主体）。
    await page.evaluate(() => document.getElementById('provider-panel-host')?.scrollIntoView({ block: 'start' }));
    await page.waitForTimeout(200);
    await page.screenshot({ path: join(OUT, `${PREFIX}${cell.tag}.png`), fullPage: false });
  });
}

test('shot tab states + add form @ 375-light', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await bootApp(page, 'light');
  await openSettings(page);
  // 旧树（NB_WEB_ROOT 对照）无统一面板与类型标签——本腿只对新树有意义。
  const hasPanel = await page.evaluate(() => !!document.getElementById('provider-panel-host'));
  test.skip(!hasPanel, 'old-tree comparison: no unified panel (new-face-only leg)');
  const ids = await page.evaluate(() => [...document.querySelectorAll('.nf-ptab')].map((b) => b.dataset.providerType || ''));
  for (const id of ids) {
    await page.evaluate((tid) => document.querySelector(`.nf-ptab[data-provider-type="${tid}"]`)?.click(), id);
    await page.waitForTimeout(250);
    await page.evaluate(() => document.getElementById('provider-panel-host')?.scrollIntoView({ block: 'start' }));
    await page.waitForTimeout(150);
    await page.screenshot({ path: join(OUT, `${PREFIX}tab-${id}.png`), fullPage: false });
  }
  // 新增表单（LLM 骨架）。
  await page.evaluate(() => {
    document.querySelector('.nf-ptab')?.click();
    document.getElementById('btn-add-provider')?.click();
  });
  await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 8000 });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${PREFIX}form-add-llm.png`), fullPage: false });
});
