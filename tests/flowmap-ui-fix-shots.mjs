// flowmap-ui-fix-shots.mjs — 亮暗双主题视觉验收截图（作者 09-05 报告两处修复）
//   edge-hover-light.png / edge-hover-dark.png   连线 hover 态（加亮可读）
//   titlebar-light.png                            标题栏「← 项目名」
//   titlebar-long-dark.png                        长项目名 ellipsis（暗色）
// Run: node tests/flowmap-ui-fix-shots.mjs  → /tmp/nb-flowmap-ui-fix/shots/
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = '/tmp/nb-flowmap-ui-fix/shots';
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json' };
const T0 = Date.now();
const LONG_NAME = 'L'.repeat(120);
const N = (id, name, agent, status, out) => ({ id, name, agent, status, in: [], out, hasWorktree: false, worktree: null, result: null, retries: 0, createdAt: T0, completedAt: null, ttlLeftSec: null });
const FM_ALPHA = () => ({ nodes: [N('n1', '研究', 'researcher', 'completed', 'n2'), N('n2', '写作', 'writer', 'completed', 'n3'), N('n3', '评审', 'reviewer', 'running', null)], worktrees: [], meta: { project: 'alpha', updatedAt: T0 } });

async function boot(page) {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = () => {
      ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', name: 'root', agentName: 'Nebula' }] }));
    };
    ws.onMessage(() => send()); send();
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === 's1' && s.ws && s.ws.readyState === 1;
  }, null, { timeout: 15000 });
}

async function openFM(page, project) {
  await page.click('#projects-btn');
  await page.waitForSelector(`.project-card[data-project="${project}"]`);
  await page.click(`.project-card[data-project="${project}"] [data-open-flowmap="${project}"]`);
  await page.waitForSelector('.flowmap-view-body .fm-node');
  await page.mouse.move(2, 2, { steps: 1 });
  await page.waitForTimeout(200);
}

const browser = await chromium.launch();

for (const scheme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: scheme });
  const page = await ctx.newPage();

  // 连线 hover 态
  await boot(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'a', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_ALPHA() }));
  await openFM(page, 'alpha');
  const pt = await page.evaluate(() => {
    const p = document.querySelector('path[data-edge-id="n1=>n2"]');
    const L = p.getTotalLength(); const m = p.getScreenCTM();
    for (const f of [0.5, 0.35, 0.65, 0.25, 0.75]) {
      const pt = p.getPointAtLength(L * f);
      const x = m.a * pt.x + m.c * pt.y + m.e, y = m.b * pt.x + m.d * pt.y + m.f;
      if (document.elementFromPoint(x, y) === p) return { x, y };
    }
    return null;
  });
  await page.mouse.move(pt.x, pt.y, { steps: 1 });
  await page.waitForTimeout(500); // 0.35s 过渡收敛
  await page.screenshot({ path: join(OUT, `edge-hover-${scheme}.png`) });
  await ctx.close();

  // 标题栏（dark 额外长名态；light 用 alpha）
  const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: scheme });
  const page2 = await ctx2.newPage();
  await boot(page2);
  await page2.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'a', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page2.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_ALPHA() }));
  await openFM(page2, 'alpha');
  await page2.screenshot({ path: join(OUT, `titlebar-${scheme}.png`) });
  await ctx2.close();
}

// 长名（暗色，双份保险补一张长名 light 也无妨——按验收最低 4 张，这里给暗色长名）
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });
  const page = await ctx.newPage();
  await boot(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: LONG_NAME, workspace: '/w/x', agentFile: 'x', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route(new RegExp(`/api/projects/${LONG_NAME}/flow-map`), (r) => r.fulfill({
    json: { nodes: [N('m1', '研究', 'researcher', 'running', null)], worktrees: [], meta: { project: LONG_NAME, updatedAt: T0 } },
  }));
  await openFM(page, LONG_NAME);
  await page.screenshot({ path: join(OUT, 'titlebar-long-dark.png') });
  await ctx.close();
}

await browser.close();
console.log('shots done →', OUT);
