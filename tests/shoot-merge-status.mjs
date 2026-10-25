// shoot-merge-status.mjs — panel-merge-status 批视觉取证（改后截图，一次性脚本）。
// 用法：node tests/shoot-merge-status.mjs <out-dir>
// 产出：merge-status-queued.png（有队列数据：候合并 + 位次 + 重试 + 拒绝摘要）
//       merge-status-degraded.png（API 404：静态候合并 + 等待合并轮）
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/tmp/merge-status-shots';
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2' };
const ROOT_SID = 'mqst-root-session';
const T0 = Date.now();

async function boot(page, mq) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/merge-queue*', (route) => {
    if (mq == null) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mq) });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = () => {
      ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    };
    send();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.evaluate((sid) => {
    import('/js/state.js').then((m) => {
      m.default.sessionBgAgents[sid] = {
        'delegate-mqst-1': {
          name: '面板候合并状态与队列位次批次', kind: 'Delegate', sessionId: 'delegate-mqst-1',
          status: 'Waiting', startedAt: Date.now() - 300_000,
        },
      };
      m.default.updateBgAgentIndicator();
    });
  }, ROOT_SID);
  await page.click('#bgagent-indicator');
  await page.waitForTimeout(600);
}

const browser = await chromium.launch();
const page = await browser.newPage();
await boot(page, {
  generatedAt: Date.now(),
  queues: [{ projectKey: 'stand', inFlight: false, entries: [{
    delegateId: 'delegate-mqst-1', description: '面板候合并样例批次', branch: 'delegate/delegate-mqst-1',
    baseBranch: 'main', baseWs: '/ws', taskId: null, parentSessionId: ROOT_SID,
    position: 3, total: 5, holder: null, pendingRetry: false, reminderSent: false,
    retryCount: 2, lastAttemptAt: T0 - 60_000, stallNotified: false, stallEscalated: false,
    readyAt: T0 - 300_000,
    lastRefusalReason: 'merge: dirty worktree: reports/20261025_185500_panel-merge-status/REPORT.md · evidence/20261025_shots/panel.png 未收编',
  }] }],
});
await page.screenshot({ path: join(OUT, 'merge-status-queued.png') });

const page2 = await browser.newPage();
await boot(page2, null);
await page2.screenshot({ path: join(OUT, 'merge-status-degraded.png') });

await browser.close();
console.log('shots written to ' + OUT);
