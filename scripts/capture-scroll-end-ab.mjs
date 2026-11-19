// capture-scroll-end-ab.mjs <label> <webRoot> <outDir>
// 同机位取证：逐页「滚到底」静帧（375×812）+ 作者引用的 compare-set-top 同机位设置页顶视图
// + 设置页「顶→底」滚动录像（webm，供 ffprobe 实测）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, renameSync, readdirSync } from 'node:fs';
import { join, extname, normalize, resolve } from 'node:path';

const LABEL = process.argv[2] || 'run';
const WEB = resolve(process.argv[3] || join(import.meta.dirname, '..', 'src', 'main', 'resources', 'web'));
const OUT = resolve(process.argv[4] || '/tmp/neb-scroll-end');
mkdirSync(OUT, { recursive: true });
const VID = join(OUT, '_video');
mkdirSync(VID, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'scroll-end-root-session';

const SHOTS = [
  { name: 'settings-top', id: 'settings', face: '.mobile-page[data-page="settings"] #settings-content', rest: true },
  { name: 'settings-bottom', id: 'settings', face: '.mobile-page[data-page="settings"] #settings-content' },
  { name: 'projects-bottom', id: 'projects', face: '.mobile-page[data-page="projects"] .team-scroll' },
  { name: 'plugins-bottom', id: 'plugins', face: '.mobile-page[data-page="plugins"] #plugins-content' },
  { name: 'search-bottom', id: 'search', face: '.mobile-page[data-page="search"] #search-results' },
  { name: 'reminder-bottom', id: 'reminder', face: '.mobile-page[data-page="reminder"] .reminder-panel-body' },
  { name: 'background-bottom', id: 'background', face: '.mobile-page[data-page="background"] #bg-dropdown' },
  { name: 'profile-bottom', id: 'profile', face: '.mobile-page[data-page="profile"] .ap-pane' },
];

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'dark',
  recordVideo: { dir: VID, size: { width: 375, height: 812 } },
});
const page = await ctx.newPage();
await page.emulateMedia({ colorScheme: 'dark' });
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  if (p.startsWith('/api/')) {
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', email: 'me@example.com', displayName: 'Mashiro' }, peers: [] }) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
  catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
  const sess = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  cfg(); sess();
  ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getConfig') cfg(); else if (m.type === 'getSessions') sess(); else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
});
await page.goto('http://localhost:1/');
await page.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws && s.ws.readyState === 1; }, SID, { timeout: 20000 });
await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="workbench"].live'));
await page.waitForTimeout(400);

const SPACER = (faceSel) => {
  const face = document.querySelector(faceSel);
  if (!face) return null;
  const d = document.createElement('div');
  d.setAttribute('data-neb-spacer', '1');
  d.style.cssText = 'height:700px;flex:0 0 auto;';
  d.textContent = '填充块';
  face.insertBefore(d, face.firstChild);
  face.style.scrollBehavior = 'auto';
  return true;
};

for (const s of SHOTS) {
  await page.evaluate(async (id) => { (await import('/js/mobile/navController.js')).navigateTo(id); }, s.id);
  await page.waitForFunction((id) => !!document.querySelector(`.mobile-page[data-page="${id}"].live`), s.id, { timeout: 8000 });
  await page.waitForTimeout(900);
  if (!s.rest) {
    await page.evaluate(SPACER, s.face);
    await page.waitForTimeout(120);
    await page.evaluate((sel) => { const f = document.querySelector(sel); f.style.scrollBehavior = 'auto'; f.scrollTop = f.scrollHeight; }, s.face);
    await page.waitForTimeout(500);
    await page.evaluate((sel) => { const f = document.querySelector(sel); if (f.scrollTop < f.scrollHeight - f.clientHeight - 2) f.scrollTop = f.scrollHeight; }, s.face);
    await page.waitForTimeout(400);
  }
  await page.screenshot({ path: join(OUT, `${LABEL}-${s.name}.png`) });
  console.log(`shot ${LABEL}-${s.name}.png`);
  await page.evaluate(() => { for (const d of document.querySelectorAll('[data-neb-spacer]')) d.remove(); });
}

// 录像：设置页顶 → 底（自然滚动态，展示内容从 dock 下方穿过 + 滚到底末行完整）
await page.evaluate(async () => { (await import('/js/mobile/navController.js')).navigateTo('settings'); });
await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="settings"].live'));
await page.waitForTimeout(800);
await page.evaluate(() => {
  const f = document.querySelector('.mobile-page[data-page="settings"] #settings-content');
  f.scrollTop = 0;
});
await page.waitForTimeout(600);
await page.evaluate(async () => {
  const f = document.querySelector('.mobile-page[data-page="settings"] #settings-content');
  f.style.scrollBehavior = 'auto';
  const target = f.scrollHeight;
  const t0 = performance.now();
  await new Promise((done) => {
    const step = () => {
      const t = Math.min(1, (performance.now() - t0) / 3200);
      f.scrollTop = target * t;
      if (t < 1) requestAnimationFrame(step); else done();
    };
    step();
  });
});
await page.waitForTimeout(1200);
const video = page.video();
await page.close();
if (video) {
  const p = await video.path();
  const dest = join(VID, `${LABEL}-settings-scrolltoend.webm`);
  try { renameSync(p, dest); console.log('video ' + dest); } catch { console.log('video kept at ' + p); }
}
await ctx.close();
await browser.close();
console.log('files: ' + readdirSync(OUT).filter((f) => f.endsWith('.png')).join(', '));
