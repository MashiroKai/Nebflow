// smoke.spec.mjs — Browser smoke test for Nebflow frontend.
//
// Catches runtime errors that node --check misses:
// - Module import failures
// - Monaco worker configuration errors
// - API endpoint 404s
// - DOM structure issues
//
// Run: npx playwright test tests/smoke.spec.mjs
// Requires: server running on localhost:8080 (override via BASE_URL / NEBFLOW_TOKEN)

import { test, expect } from '@playwright/test';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const TOKEN = process.env.NEBFLOW_TOKEN ?? 'Oh5y80hS3xs1RyENe4PVK46mrWtRh_uo6YuwnTBQoyA';

test.beforeEach(async ({ page }) => {
  // Capture all console errors for later assertion
  page._consoleErrors = [];
  page._pageErrors = [];
  // Track which URLs are 403 so we can filter known-benign ones
  page._forbiddenUrls = new Set();
  page.on('response', (resp) => {
    if (resp.status() === 403) page._forbiddenUrls.add(resp.url());
  });
  page.on('console', (msg) => {
    if (msg.type() === 'error') page._consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => {
    page._pageErrors.push(err.message);
  });
});

/** Filter out console errors caused by known-benign 403s (e.g. NebLink auth probe). */
function filterBenignErrors(page, errors) {
  const benignPatterns = [/neblink/i];
  const isBenign = (text) => {
    // Generic "Failed to load resource: 403" — check if it maps to a known-benign URL
    if (text.includes('403')) {
      for (const url of page._forbiddenUrls) {
        if (benignPatterns.some(p => p.test(url))) return true;
      }
    }
    return false;
  };
  return errors.filter(e => !isBenign(e));
}

test.describe('Smoke — page load', () => {

  test('loads without console errors', async ({ page }) => {
    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    // Allow a moment for deferred scripts (Monaco preload, etc.)
    await page.waitForTimeout(2000);

    // Filter out known non-critical errors (NebLink auth probe 403)
    const realErrors = filterBenignErrors(page, page._consoleErrors);

    expect(realErrors, `Console errors:\n${realErrors.join('\n')}`).toEqual([]);
    expect(page._pageErrors, `Page errors:\n${page._pageErrors.join('\n')}`).toEqual([]);
  });

  test('loads all JS modules without 404', async ({ page }) => {
    const failedRequests = [];
    page.on('response', (resp) => {
      if (resp.status() === 404 && resp.url().includes('/js/')) {
        failedRequests.push(resp.url());
      }
    });

    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    expect(failedRequests, `404 JS files:\n${failedRequests.join('\n')}`).toEqual([]);
  });
});

test.describe('Smoke — Flow Canvas', () => {

  test('opens flow canvas and shows mounted flows', async ({ page }) => {
    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    // Click the flow toggle button
    const flowBtn = page.locator('#flow-toggle-btn');
    if (await flowBtn.isVisible()) {
      await flowBtn.click();
      await page.waitForTimeout(500);

      // Check flow canvas content appeared
      const flowList = page.locator('.flow-list, .flow-empty');
      await expect(flowList).toBeVisible({ timeout: 3000 });
    }
  });

  test('Monaco editor loads without worker errors', async ({ page }) => {
    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);

    // Filter out known Monaco issues — we only care about URL/worker errors
    const criticalErrors = page._consoleErrors.filter(e =>
      e.includes('URL is not valid') ||
      e.includes('Failed to load Monaco') ||
      e.includes('workerMain')
    );

    expect(criticalErrors, `Monaco errors:\n${criticalErrors.join('\n')}`).toEqual([]);
  });
});

test.describe('Smoke — Flow Editor APIs', () => {

  test('GET /api/agents/list returns data', async ({ request }) => {
    const resp = await request.get(`${BASE}/api/agents/list`, {
      headers: { 'Authorization': `Bearer ${TOKEN}` },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.agents).toBeDefined();
    expect(body.agents.length).toBeGreaterThan(0);
  });

  test('GET /api/flow/def/:name returns flow definition', async ({ request }) => {
    // First get a flow name
    const sessionResp = await request.get(`${BASE}/api/flow/status/test-session`, {
      headers: { 'Authorization': `Bearer ${TOKEN}` },
    });
    // Even if 400 (invalid session), the endpoint should exist

    // Try to get flow def for code-review
    const resp = await request.get(`${BASE}/api/flow/def/code-review`, {
      headers: { 'Authorization': `Bearer ${TOKEN}` },
    });
    // 200 if flow exists, 404 if not mounted — both are valid responses
    // (not 500 which means server error)
    expect(resp.status()).toBeLessThan(500);

    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.name).toBe('code-review');
      expect(body.agents).toBeDefined();
    }
  });
});

test.describe('Smoke — WS auth probe-first', () => {

  test('cookie-capable browser connects cookie-first (no token in WS URL, no handshake error)', async ({ page }) => {
    const wsUrls = [];
    page.on('websocket', (ws) => wsUrls.push(ws.url()));
    const probeStatuses = [];
    page.on('response', (resp) => {
      if (resp.url().includes('/api/nf-tasks')) probeStatuses.push(resp.status());
    });

    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    expect(wsUrls.length, 'expected at least one WebSocket connection').toBeGreaterThan(0);
    // Probe said cookies work → token must stay out of the WS URL.
    expect(wsUrls[0], `first WS URL: ${wsUrls[0]}`).not.toContain('token=');
    // The auth probe must have run and seen the cookie (200) — if this flakes
    // to 403 the probe itself is racy and the diagnostic matters.
    expect(probeStatuses, 'auth probe should have fired').toEqual([200]);

    const wsErrors = page._consoleErrors.filter(e =>
      /WebSocket connection .* failed/i.test(e) || /handshake rejected/i.test(e)
    );
    expect(wsErrors, `WS errors:\n${wsErrors.join('\n')}`).toEqual([]);
  });

  test('cookie-blocked browser goes straight to ?token= on the FIRST attempt (zero rejected handshakes)', async ({ context, page }) => {
    // Simulate Safari "block all cookies": cookie writes are silently dropped
    // and reads return "" — the probe fetch then rides with no cookie.
    await context.addInitScript(() => {
      try {
        Object.defineProperty(document, 'cookie', {
          get() { return ''; },
          set() { /* dropped — cookie blocked */ },
          configurable: true,
        });
      } catch (_) { /* if override fails the test assertion below catches it */ }
    });

    const wsUrls = [];
    const wsFrames = [];
    page.on('websocket', (ws) => {
      wsUrls.push(ws.url());
      ws.on('framereceived', (frame) => wsFrames.push(frame.payload));
    });

    await page.goto(`${BASE}/?token=${TOKEN}`);
    // Wait until the WS actually opened and the server greeted us (serverConfig).
    await expect.poll(() => wsFrames.some(p => typeof p === 'string' && p.includes('serverConfig')), {
      timeout: 10000,
      message: 'expected a serverConfig WS frame (connection established)',
    }).toBe(true);

    expect(wsUrls.length, 'expected at least one WebSocket connection').toBeGreaterThan(0);
    // The very first handshake must already carry ?token= — no doomed cookie-only attempt.
    expect(wsUrls[0], `first WS URL: ${wsUrls[0]}`).toContain('token=');

    // No native WS failure line, no fallback-retry warning. (The probe fetch
    // itself logs a benign "Failed to load resource: 403" — that's expected
    // here, so we only assert on WS-specific errors.)
    const wsErrors = page._consoleErrors.filter(e =>
      /WebSocket connection .* failed/i.test(e) || /handshake rejected/i.test(e)
    );
    expect(wsErrors, `WS errors:\n${wsErrors.join('\n')}`).toEqual([]);
  });
});
