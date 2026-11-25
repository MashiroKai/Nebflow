import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Mock WS only; never a real gateway or proof of persistence.
const ROOT = resolve(process.env.TIMEZONE_WEB_ROOT || join(dirname(fileURLToPath(import.meta.url)), '../src/main/resources/web'));
const SID = 'zone-focused-session';
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };
test.use({ timezoneId: 'America/New_York', viewport: { width: 1280, height: 800 } });

test('creation region and device region survive correction and refresh', async ({ page }) => {
  const frames = [];
  let row = { id: 'zone-1', sessionId: SID, kind: 'todo', content: 'zone test', name: 'zone test', triggerAt: 2051254800000,
    createdAt: 1, enabled: true, triggered: false, priority: 0, completed: false,
    due: { type: 'once', dueAt: 2051254800000, zoneId: 'Europe/London', wallClock: '2035-01-01T09:00', originalOffset: null }, nextDueAt: 2051254800000 };
  await page.addInitScript(() => { localStorage.setItem('nebflow_token', 'e2e-token'); localStorage.setItem('nebflow_locale', 'zh-CN'); });
  await page.route('**/*', route => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname); if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: p === '/api/sessions' ? JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) : '{}' });
    const file = normalize(join(ROOT, p)); if (!file.startsWith(ROOT + '/')) return route.fulfill({ status: 403, body: '' });
    try { return route.fulfill({ status: 200, contentType: mime[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: '' }); }
  });
  await page.routeWebSocket(/\/ws/, ws => {
    const send = data => ws.send(JSON.stringify(data));
    const sessions = () => send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
    const config = () => send({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    config(); sessions();
    ws.onMessage(raw => { let m; try { m = JSON.parse(raw); } catch { return; } frames.push(m);
      if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
      else if (m.type === 'getConfig') config();
      else if (m.type === 'ping') send({ type: 'pong' });
      else if (m.type === 'getHistory') send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 });
      else if (m.type === 'listScheduledTasks') send({ type: 'scheduledTaskList', sessionId: SID, scope: 'all', tasks: [row] });
      else if (m.type === 'updateScheduledTask' && m.creationZone) {
        row = { ...row, due: { ...row.due, zoneId: m.creationZone } };
        send({ type: 'scheduledTaskUpdated', sessionId: SID, task: row });
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async sid => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws?.readyState === 1; }, SID);
  await expect.poll(() => frames.some(f => f.type === 'listScheduledTasks' && f.scope === 'all')).toBe(true);
  await page.locator('.tfl-capsule').click();
  const item = page.locator('.tfl-row[data-id="zone-1"]');
  await expect(item.locator('.tfl-row-creation-zone')).toContainText('Europe/London');
  await expect(item.locator('.tfl-row-creation-zone')).toContainText('America/New_York');
  await item.locator('.tfl-row-main').click();
  const panel = item.locator('.tfl-edit-panel');
  await expect(panel.locator('.tfl-creation-zone-info')).toContainText('2035-01-01T09:00');
  await panel.locator('.tfl-creation-zone-input').fill('Asia/Tokyo');
  await panel.locator('.tfl-creation-zone-submit').click();
  await expect.poll(() => frames.some(f => f.type === 'updateScheduledTask' && f.id === 'zone-1' && f.sessionId === SID && f.creationZone === 'Asia/Tokyo')).toBe(true);
  await expect(panel.locator('.tfl-creation-zone-status')).toHaveText('Saved');
  await page.reload();
  await page.locator('.tfl-capsule').click();
  await expect(page.locator('.tfl-row[data-id="zone-1"] .tfl-row-creation-zone')).toContainText('Asia/Tokyo');
});
