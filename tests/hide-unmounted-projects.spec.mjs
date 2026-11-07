import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openHubPage } from './_mobile-dock-nav.mjs';

const web = join(dirname(fileURLToPath(import.meta.url)), '../src/main/resources/web');
const shots = process.env.HIDE_UNM_SHOT_DIR || '/tmp/hide-unmounted-evidence';
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };
for (const mobile of [false, true]) {
  test.describe(mobile ? 'mobile' : 'desktop', () => {
    test.use({ viewport: mobile ? { width: 390, height: 844 } : { width: 1280, height: 900 }, hasTouch: mobile });
    for (const empty of [false, true]) {
      test(empty ? 'only unloaded then remount' : 'mixed then remount', async ({ page }) => {
        let mounted = empty ? [] : [{ name: 'keep-alpha', workspace: '/tmp/keep' }];
        let unloaded = [{ name: 'gone-one', workspace: '/tmp/gone', unmountedAt: 1 }];
        let socket;
        await page.addInitScript(() => { localStorage.setItem('nebflow_token', 'e2e-token'); localStorage.setItem('nebflow_locale', 'zh-CN'); });
        await page.route('**/*', route => {
          let path = new URL(route.request().url()).pathname;
          if (path === '/') path = '/index.html';
          if (path === '/api/projects') return route.fulfill({ json: { projects: mounted, unmounted: unloaded } });
          if (path === '/api/projects/gone-one/remount') {
            const [project] = unloaded; unloaded = []; mounted = [...mounted, project];
            socket?.send(JSON.stringify({ type: 'projectRemounted', project: project.name, row: project }));
            return route.fulfill({ json: { project: project.name, alreadyMounted: false } });
          }
          if (path.endsWith('/flow-map')) return route.fulfill({ json: { nodes: [], worktrees: [], meta: {} } });
          if (path.startsWith('/api/')) return route.fulfill({ json: {} });
          try { return route.fulfill({ contentType: mime[extname(path)] || 'application/octet-stream', body: readFileSync(join(web, path)) }); }
          catch { return route.fulfill({ status: 404 }); }
        });
        await page.routeWebSocket(/\/ws/, ws => {
          socket = ws;
          const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
          const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
          config(); sessions();
          ws.onMessage(raw => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getConfig') config(); else if (m.type === 'getSessions' || m.type === 'listSessions') sessions(); else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
        });
        await page.goto('http://localhost:1/');
        await page.waitForFunction(async () => { const s = (await import('/js/state.js')).default; return s.ws?.readyState === 1; });
        if (mobile) await openHubPage(page, 'projects'); else await page.evaluate(() => import('/js/projectTab.js').then(m => m.openProjectsTab()));
        const scope = mobile ? page.locator('.mobile-page[data-page="projects"]') : page.locator('.team-scroll').first();
        if (empty) await expect(scope.locator('.team-empty')).toBeVisible();
        else await expect(scope.locator('.project-card[data-project="keep-alpha"]')).toBeVisible();
        await expect(scope.locator('.project-unmounted-group, .project-unm-row, .project-unm-remount')).toHaveCount(0);
        expect(await scope.textContent()).not.toContain('gone-one');
        await page.screenshot({ path: join(shots, `${mobile ? 'mobile' : 'desktop'}-${empty ? 'empty' : 'mixed'}.png`) });
        const status = await page.evaluate(async () => (await fetch('/api/projects/gone-one/remount', { method: 'POST' })).status);
        expect(status).toBe(200);
        await expect(scope.locator('.project-card[data-project="gone-one"]')).toBeVisible();
        if (!empty) await expect(scope.locator('.project-card[data-project="keep-alpha"]')).toBeVisible();
        await expect(scope.locator('.project-unmounted-group')).toHaveCount(0);
        await page.screenshot({ path: join(shots, `${mobile ? 'mobile' : 'desktop'}-${empty ? 'empty' : 'mixed'}-remounted.png`) });
      });
    }
  });
}
