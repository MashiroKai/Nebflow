// notification-banner-lifecycle.spec.mjs — dedupe, dismissal and retention contract.
// Run: npx playwright test tests/notification-banner-lifecycle.spec.mjs
import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };
let server, base;

test.beforeAll(async () => {
  server = createServer(async (req, res) => {
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      if (path === '/') {
        res.writeHead(200, { 'content-type': 'text/html' });
        return res.end('<!doctype html><html><body></body></html>');
      }
      const file = join(WEB, path);
      res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(await readFile(file));
    } catch { res.writeHead(404); res.end('not found'); }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => server.close());

async function setup(page) {
  await page.goto(base);
  await page.evaluate(async () => {
    document.body.innerHTML = '<div id="notification-banner"></div>';
    window.__timers = [];
    window.setTimeout = (fn, delay) => { const timer = { fn, delay, cleared: false }; window.__timers.push(timer); return timer; };
    window.clearTimeout = (timer) => { if (timer) timer.cleared = true; };
    const state = await import('/js/state.js');
    state.default.notifications = [];
    const banner = await import('/js/notificationBanner.js');
    window.__notify = banner.addNotification;
    window.__dismiss = banner.dismissNotification;
    window.__state = state.default;
  });
}

 test('duplicate refresh updates one existing item without resetting its original expiry', async ({ page }) => {
  await setup(page);
  const result = await page.evaluate(() => {
    __notify('task', 'same', { dismissAfter: 1000 });
    const first = __state.notifications[0];
    __notify('task', 'same', { dismissAfter: 5000 });
    return { count: __state.notifications.length, idStable: __state.notifications[0].id === first.id,
      timestampRefreshed: __state.notifications[0].time >= first.time,
      timers: __timers.map(t => t.delay), visible: document.querySelectorAll('.notif-item').length };
  });
  expect(result).toEqual({ count: 1, idStable: true, timestampRefreshed: true, timers: [1000], visible: 1 });
});

test('a dismissed notification can be shown again, and its old timer cannot dismiss the new item', async ({ page }) => {
  await setup(page);
  const result = await page.evaluate(async () => {
    __notify('task', 'same', { dismissAfter: 1000 });
    const oldId = __state.notifications[0].id;
    __dismiss(oldId);
    const oldTimerCleared = __timers.find(t => t.delay === 1000).cleared;
    __timers.find(t => t.delay === 250).fn(); // complete the dismissal animation
    __notify('task', 'same', { dismissAfter: 2000 });
    const newId = __state.notifications[0].id;
    __timers.find(t => t.delay === 1000).fn(); // stale timer addresses the old unique id
    return { oldId, newId, oldTimerCleared, count: __state.notifications.length,
      visibleId: document.querySelector('.notif-item')?.dataset.id };
  });
  expect(result.newId).not.toBe(result.oldId);
  expect(result.count).toBe(1);
  expect(result.oldTimerCleared).toBe(true);
  expect(result.visibleId).toBe(result.newId);
});

test('only the newest three notifications are rendered, while older items remain timed', async ({ page }) => {
  await setup(page);
  const result = await page.evaluate(() => {
    for (let i = 1; i <= 4; i++) __notify('task', `item ${i}`, { dismissAfter: i * 1000 });
    return { visible: [...document.querySelectorAll('.notif-text')].map(el => el.textContent),
      state: __state.notifications.map(n => n.text), timers: __timers.length };
  });
  expect(result.visible).toEqual(['item 4', 'item 3', 'item 2']);
  expect(result.state).toEqual(['item 1', 'item 2', 'item 3', 'item 4']);
  expect(result.timers).toBe(4);
});

test('expired hidden item is removed without disturbing the three visible newer items', async ({ page }) => {
  await setup(page);
  const result = await page.evaluate(() => {
    for (let i = 1; i <= 4; i++) __notify('task', `item ${i}`, { dismissAfter: i * 1000 });
    __timers.find(t => t.delay === 1000).fn();
    return { state: __state.notifications.map(n => n.text), visible: [...document.querySelectorAll('.notif-text')].map(el => el.textContent) };
  });
  expect(result.state).toEqual(['item 2', 'item 3', 'item 4']);
  expect(result.visible).toEqual(['item 4', 'item 3', 'item 2']);
});
