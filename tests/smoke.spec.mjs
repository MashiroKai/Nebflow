// smoke.spec.mjs — Browser smoke test for Nebflow frontend.
//
// Catches runtime errors that node --check misses:
// - Module import failures
// - Monaco worker configuration errors
// - API endpoint 404s
// - DOM structure issues
//
// Run: npx playwright test tests/smoke.spec.mjs
// Requires: server running on localhost:8080

import { test, expect } from '@playwright/test';

const BASE = 'http://localhost:8080';
const TOKEN = 'Oh5y80hS3xs1RyENe4PVK46mrWtRh_uo6YuwnTBQoyA';

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
