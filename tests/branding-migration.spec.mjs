// branding-migration.spec.mjs — rename-day storage migration (批3 L3 task 2).
//
// Contract under test (js/branding.js, module-init side effect):
//   1. Today (brand = nebflow): zero behavior change — key() yields the
//      historical literals, and the two pre-standardization spellings
//      ('nebflow-task-collapsed', 'nebflow:timeFormat') are normalized into
//      the scheme on every boot.
//   2. Rename day (brand = neblink): every legacy nebflow_* localStorage key
//      (including the nebflow_tasks_* family) is copied to the new namespace
//      and removed; existing new-namespace data always wins; the token cookie
//      is COPIED (never deleted); the index.html classic inline script reads
//      the new key first and falls back to the legacy spelling (it runs
//      before module migration, so the fallback must fire).
//
// Self-contained: serves src/main/resources/web via route interception and
// mocks the WS boot handshake — no backend, no sbt. The rename-day scenario
// tampers index.html in the route handler to inject window.__BRAND__.
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/branding-migration.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'e2e-root-session';
const REBRAND = { productName: 'NebLink', lowerName: 'neblink', domain: 'neblink.app', homeDirName: '.neblink' };

/**
 * Minimal boot: configData (onboarding terminal) + sessionList.
 * @param {import('@playwright/test').Page} page
 * @param {{ rebrand?: boolean }} opts rebrand: serve index.html with an
 *   injected window.__BRAND__ for the NebLink rename-day scenario.
 */
async function bootApp(page, opts = {}) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      let body = readFileSync(file);
      if (opts.rebrand && p === '/index.html') {
        // Inject the gateway-provided brand BEFORE the classic inline script
        // (sidebar collapse restore) and any module load.
        const tag = `<script>window.__BRAND__=${JSON.stringify(REBRAND)};</script>`;
        body = Buffer.from(body.toString('utf8').replace('<body>', `<body>\n  ${tag}`));
      }
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(200);
}

function lsDump(page) {
  return page.evaluate(() => {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      out[k] = localStorage.getItem(k);
    }
    return out;
  });
}

test('today (brand=nebflow): keys unchanged, irregular spellings normalized', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // Seed the two pre-standardization spellings + a regular key.
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow-task-collapsed', 'true');
    localStorage.setItem('nebflow:timeFormat', '12h');
    localStorage.setItem('nebflow_locale', 'en');
  });
  await bootApp(page);

  const ls = await lsDump(page);
  // Zero behavior change: key() yields the historical literals.
  const keyOf = await page.evaluate(async () => {
    const { key } = await import('/js/branding.js');
    return { token: key('token'), sessions: key('sessions'), tasksPrefix: key('tasks_') };
  });
  expect(keyOf).toEqual({ token: 'nebflow_token', sessions: 'nebflow_sessions', tasksPrefix: 'nebflow_tasks_' });
  expect(ls['nebflow_locale']).toBe('en');

  // Irregular spellings are normalized TODAY (same-brand boot).
  expect(ls['nebflow_task_collapsed']).toBe('true');
  expect(ls['nebflow_time_format']).toBe('12h');
  expect(ls['nebflow-task-collapsed']).toBeUndefined();
  expect(ls['nebflow:timeFormat']).toBeUndefined();

  expect(pageErrors).toEqual([]);
});

test('rename day (brand=neblink): legacy data migrates, user notices nothing', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'legacy-token-123');
    localStorage.setItem('nebflow_sessions', '{"s1":{"msgs":[]}}');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('nebflow_tasks_abc123', '[{"id":1}]');
    localStorage.setItem('nebflow_sidebar_collapsed', 'true');
    localStorage.setItem('nebflow-task-collapsed', 'true');
    // Conflict case: new-namespace data already exists and must win.
    localStorage.setItem('neblink_input_history', '["new-data-wins"]');
    localStorage.setItem('nebflow_input_history', '["old"]');
    document.cookie = 'nebflow_token=legacy-token-123; path=/';
  });
  await bootApp(page, { rebrand: true });

  const ls = await lsDump(page);
  // Core keys migrated and legacy removed. (neblink_sessions is app-owned:
  // the boot sequence legitimately rewrites it after migration, so assert
  // the legacy key is gone and the new key exists, not the exact value.)
  expect(ls['neblink_token']).toBe('legacy-token-123');
  expect(ls['neblink_sessions']).toBeDefined();
  expect(ls['neblink_locale']).toBe('en');
  expect(ls['neblink_sidebar_collapsed']).toBe('true');
  expect(ls['nebflow_token']).toBeUndefined();
  expect(ls['nebflow_sessions']).toBeUndefined();
  expect(ls['nebflow_locale']).toBeUndefined();

  // Prefix family (nebflow_tasks_*) covered by enumeration.
  expect(ls['neblink_tasks_abc123']).toBe('[{"id":1}]');
  expect(ls['nebflow_tasks_abc123']).toBeUndefined();

  // Irregular spelling lands in the NEW namespace on rename day.
  expect(ls['neblink_task_collapsed']).toBe('true');
  expect(ls['nebflow-task-collapsed']).toBeUndefined();

  // New data wins; the losing legacy key is left untouched.
  expect(ls['neblink_input_history']).toBe('["new-data-wins"]');
  expect(ls['nebflow_input_history']).toBe('["old"]');

  // index.html classic inline script: migration has NOT run when it executes
  // (module init happens later), so the legacy fallback must have fired.
  await expect(page.locator('body')).toHaveClass(/sidebar-collapsed/);

  // Token cookie copied to the new name, legacy cookie preserved.
  const cookies = await page.evaluate(() => document.cookie);
  expect(cookies).toContain('neblink_token=legacy-token-123');
  expect(cookies).toContain('nebflow_token=legacy-token-123');

  // App booted under the new brand: key() serves the new namespace.
  const tokenKey = await page.evaluate(async () => {
    const { key } = await import('/js/branding.js');
    return key('token');
  });
  expect(tokenKey).toBe('neblink_token');

  expect(pageErrors).toEqual([]);
});
