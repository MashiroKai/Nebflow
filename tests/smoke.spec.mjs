// smoke.spec.mjs — Browser smoke test for Nebflow frontend.
//
// Catches runtime errors that node --check misses:
// - Module import failures
// - Monaco worker configuration errors
// - API endpoint 404s
// - DOM structure issues
//
// Run: npx playwright test tests/smoke.spec.mjs
// Requires: a running Nebflow server. Defaults to localhost:8080; override
// with BASE_URL. Token resolution: NEBFLOW_TOKEN env first, then the running
// instance's own ~/.nebflow/auth.json — no hardcoded token (that hardcode is
// what historically kept this suite out of CI).

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const TOKEN = process.env.NEBFLOW_TOKEN ?? (() => {
  try { return JSON.parse(readFileSync(join(homedir(), '.nebflow', 'auth.json'), 'utf8')); }
  catch { return ''; }
})();

test.beforeEach(async ({ page }) => {
  // Capture all console errors for later assertion
  page._consoleErrors = [];
  page._pageErrors = [];
  // Track which URLs are 403 so we can filter known-benign ones
  page._forbiddenUrls = new Set();
  // Whole-session asset watch: any /js/ or /vendor/ 404 at ANY point (not
  // just the networkidle window) means a static-route contract break — the
  // exact failure mode of the viewer plugin 404 incident.
  page._asset404s = [];
  page.on('response', (resp) => {
    if (resp.status() === 403) page._forbiddenUrls.add(resp.url());
    if (resp.status() === 404) {
      const path = new URL(resp.url()).pathname;
      if (path.startsWith('/js/') || path.startsWith('/vendor/')) page._asset404s.push(resp.url());
    }
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

/** Dismiss the first-boot onboarding overlay if present — it intercepts
 *  pointer events page-wide and would block every click-based test on a
 *  fresh instance (CI). Clicks the real 跳过 button (sends 'skipped', which
 *  is exempt from the backend probe gate). */
async function dismissOnboarding(page) {
  const skip = page.locator('#ob-skip');
  if (await skip.isVisible({ timeout: 3000 }).catch(() => false)) {
    await skip.click();
    await expect(page.locator('.onboarding-overlay')).toHaveCount(0, { timeout: 5000 });
  }
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
    await dismissOnboarding(page);

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

test.describe('Smoke — lazy-load paths (real backend)', () => {

  test('Canvas renders files via the real readFile chain (lazy viewer modules)', async ({ page }) => {
    const assetReqs = [];
    page.on('response', (r) => {
      if (r.url().includes('/js/viewers/') || r.url().includes('/api/nf-file')) {
        assetReqs.push(`${r.status()} ${r.url()}`);
      }
    });

    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    // WS open = send button no longer marked disconnected
    await expect(page.locator('#send-btn')).not.toHaveClass(/disconnected/, { timeout: 10000 });

    // Drive the REAL chain: WS readFile → fileContent → workspace-open-item →
    // openWorkspaceItem → dynamic import('./fileViewers.js') → viewers/*.js.
    // No DOM mocking — a broken static route or import path fails here.
    await page.evaluate(async (root) => {
      const { sendWs } = await import('/js/ws.js');
      const state = (await import('/js/state.js')).default;
      sendWs({ type: 'readFile', sessionId: state.activeSessionId || 'smoke', path: 'README.md', rootPath: root });
    }, process.cwd());

    await page.locator('.canvas-tab', { hasText: 'README.md' }).waitFor({ timeout: 8000 });
    await page.waitForFunction(() => {
      for (const p of document.querySelectorAll('.canvas-tab-pane')) {
        if (/nebflow/i.test(p.textContent)) return true;
      }
      return false;
    }, { timeout: 8000 });
    expect(
      assetReqs.some(r => r.startsWith('200') && r.includes('/js/viewers/markdown.js')),
      `markdown viewer module must load 200:\n${assetReqs.join('\n')}`
    ).toBe(true);

    // Binary image: content does NOT arrive via WS — the image viewer fetches
    // /api/nf-file itself. Assert both the render and the fetch.
    // (Opened after the markdown assertions: a second preview tab REPLACES
    // the first — VS Code semantics, canvas.js previewKeyOf.)
    await page.evaluate(async (root) => {
      const { sendWs } = await import('/js/ws.js');
      const state = (await import('/js/state.js')).default;
      sendWs({ type: 'readFile', sessionId: state.activeSessionId || 'smoke', path: 'tests/fixtures/pic.png', rootPath: root });
    }, process.cwd());
    await page.locator('.canvas-tab', { hasText: 'pic.png' }).waitFor({ timeout: 8000 });
    await page.waitForSelector('.canvas-image-viewer img', { timeout: 8000 });
    expect(
      assetReqs.some(r => r.startsWith('200') && r.includes('/api/nf-file')),
      `image viewer must fetch /api/nf-file 200:\n${assetReqs.join('\n')}`
    ).toBe(true);

    expect(page._asset404s, `asset 404s:\n${page._asset404s.join('\n')}`).toEqual([]);
    const realErrors = filterBenignErrors(page, page._consoleErrors);
    expect(realErrors, `Console errors:\n${realErrors.join('\n')}`).toEqual([]);
    expect(page._pageErrors, `Page errors:\n${page._pageErrors.join('\n')}`).toEqual([]);
  });

  test('task list collapse bar → 查看全部 → archive tab (real /api/nf-tasks)', async ({ page }) => {
    const nfTaskReqs = [];
    page.on('response', (r) => {
      if (r.url().includes('/api/nf-tasks')) nfTaskReqs.push(r.status());
    });

    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#send-btn')).not.toHaveClass(/disconnected/, { timeout: 10000 });
    await dismissOnboarding(page);

    // A fresh instance has no tasks, so the collapse bar never appears on its
    // own. Feed the same entry point the WS taskListUpdate handler uses
    // (renderTaskList) with one completed-today task, then click through the
    // real UI: bar → expand → 查看全部 → archive Canvas tab.
    await page.evaluate(async () => {
      const { renderTaskList } = await import('/js/taskList.js');
      renderTaskList([{
        id: 'smoke-task-1',
        subject: 'Smoke completed task',
        status: 'completed',
        completedAt: new Date().toISOString(),
        notes: [],
      }], null, 'smoke');
    });
    await page.locator('.task-today-bar').click();
    await page.locator('.task-today-all').click();

    // Archive opens as a Canvas tab and fetches the real /api/nf-tasks.
    await page.locator('.canvas-tab', { hasText: /任务档案|Task Archive/ }).waitFor({ timeout: 8000 });
    await expect.poll(() => nfTaskReqs.length, { timeout: 8000 }).toBeGreaterThan(0);
    expect(nfTaskReqs[0], '/api/nf-tasks must be 200').toBe(200);

    expect(page._asset404s, `asset 404s:\n${page._asset404s.join('\n')}`).toEqual([]);
    const realErrors = filterBenignErrors(page, page._consoleErrors);
    expect(realErrors, `Console errors:\n${realErrors.join('\n')}`).toEqual([]);
    expect(page._pageErrors, `Page errors:\n${page._pageErrors.join('\n')}`).toEqual([]);
  });

  test('settings modal opens and closes', async ({ page }) => {
    await page.goto(`${BASE}/?token=${TOKEN}`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#send-btn')).not.toHaveClass(/disconnected/, { timeout: 10000 });
    await dismissOnboarding(page);

    await page.locator('#settings-btn').click();
    await expect(page.locator('#settings-overlay')).toHaveClass(/on/, { timeout: 5000 });
    await page.locator('#settings-modal-close').click();
    await expect(page.locator('#settings-overlay')).not.toHaveClass(/on/, { timeout: 5000 });

    expect(page._asset404s, `asset 404s:\n${page._asset404s.join('\n')}`).toEqual([]);
    const realErrors = filterBenignErrors(page, page._consoleErrors);
    expect(realErrors, `Console errors:\n${realErrors.join('\n')}`).toEqual([]);
    expect(page._pageErrors, `Page errors:\n${page._pageErrors.join('\n')}`).toEqual([]);
  });
});
