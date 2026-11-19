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
// with BASE_URL. Bootstrap credential resolution: NEBFLOW_TOKEN env first,
// then the running instance's own ~/.nebflow/auth.json — no hardcoded token
// (that hardcode is what historically kept this suite out of CI). The
// bootstrap credential is only used to MINT one-time console tickets; the
// pages themselves authenticate through the HttpOnly session cookie the
// ticket landing sets (stage D retired the URL token rail).

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { establishSession } from './helpers/session-rail.mjs';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const TOKEN = process.env.NEBFLOW_TOKEN ?? (() => {
  try { return JSON.parse(readFileSync(join(homedir(), '.nebflow', 'auth.json'), 'utf8')); }
  catch { return ''; }
})();

test.beforeEach(async ({ page }) => {
  // Capture all console errors for later assertion. _consoleErrorUrls is a
  // parallel array: msg.location().url is the failing resource URL for
  // network-type errors, so benign filters can pair text ↔ URL precisely.
  page._consoleErrors = [];
  page._consoleErrorUrls = [];
  page._pageErrors = [];
  // Benign-noise tracking: 403 responses from the ws.js auth probe
  // (GET /api/nf-authcheck, R9 = O-A). A cookie-blocked context gets 403 from
  // it by design — that IS the "no credential could ride this context" signal,
  // and since stage D the designed landing for that shape is the paste-ticket
  // entry, not a token fallback. (Other 403 baseline sources are matched by
  // pathname directly in filterBenignErrors.) The old benign 400 came from a
  // bare /api/nf-file GET; that endpoint is ticket-only now and answers 401,
  // which carries no benign meaning, so the whitelist moved with the probe
  // target.
  page._benign403Urls = new Set();
  // Whole-session asset watch: any /js/ or /vendor/ 404 at ANY point (not
  // just the networkidle window) means a static-route contract break — the
  // exact failure mode of the viewer plugin 404 incident.
  page._asset404s = [];
  page.on('response', (resp) => {
    if (resp.status() === 403) {
      // ws.js probeCookieAuth (auth probe) deliberately GETs /api/nf-authcheck
      // with no credential in the URL: a cookie-carrying request is accepted
      // (204) and a cookie-blocked one is refused (403). Chromium logs that
      // 403 as an unsuppressible network-layer console error, so it is
      // baseline noise for the cookie-blocked scenario (same whitelist口径 as
      // scripts/e2e-askuser-source-label.mjs BASELINE_NOISE).
      const u = new URL(resp.url());
      if (u.pathname === '/api/nf-authcheck') page._benign403Urls.add(resp.url());
    }
    if (resp.status() === 404) {
      const path = new URL(resp.url()).pathname;
      // /assets/ = P1 bundle chunks; /js/ + /vendor/ = source-tree modules.
      if (path.startsWith('/js/') || path.startsWith('/vendor/') || path.startsWith('/assets/')) page._asset404s.push(resp.url());
    }
  });
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      page._consoleErrors.push(msg.text());
      page._consoleErrorUrls.push(msg.location()?.url ?? '');
    }
  });
  page.on('pageerror', (err) => {
    page._pageErrors.push(err.message);
  });
});

/** Filter out console errors caused by known-baseline noise. Each error's
 *  status text is paired with its OWN resource URL (msg.location().url), so a
 *  403/404 from any other URL stays a real error — no page-wide swallowing:
 *  - 403 from /api/neblink/* (status probe fires unauthenticated by design)
 *    and the boot-time /api/canvas-tabs fetch (Bearer not yet in state).
 *  - 403 from the ws.js auth probe's GET /api/nf-authcheck (403 = "cookie
 *    blocked" by design — see the beforeEach benign-403 watcher).
 *  - 404 from the root-name probe's GET /agents/<root>/agent.json (rootName.js
 *    initRootNameProbe): 404 = "never named" BY DESIGN on a fresh home, and
 *    Chromium logs it as an unsuppressible network-layer console error — same
 *    baseline-noise class as the authcheck 403. */
function filterBenignErrors(page, errors) {
  const isBenign = (text, i) => {
    const url = page._consoleErrorUrls[i] ?? '';
    if (!url) return false;
    let path = '';
    try { path = new URL(url).pathname; } catch { return false; }
    if (text.includes('403')) {
      return path.startsWith('/api/neblink/') || path === '/api/canvas-tabs' || page._benign403Urls.has(url);
    }
    if (text.includes('404')) {
      return /\/agents\/[^/]+\/agent\.json$/.test(path);
    }
    return false;
  };
  return errors.filter((e, i) => !isBenign(e, i));
}

/** True when the instance serves the P1 bundle (entry script under /assets/). */
async function isBundled(page) {
  return page.evaluate(() => {
    const s = document.querySelector('script[type="module"]');
    return !!(s && s.src.includes('/assets/'));
  });
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
    await establishSession(BASE, TOKEN, page);
    await page.waitForLoadState('networkidle');
    // Allow a moment for deferred scripts (Monaco preload, etc.)
    await page.waitForTimeout(2000);

    // Filter out known non-critical errors (baseline noise: neblink/canvas-tabs
    // 403s, the auth probe's by-design bare 400)
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

    await establishSession(BASE, TOKEN, page);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    expect(failedRequests, `404 JS files:\n${failedRequests.join('\n')}`).toEqual([]);
  });
});

test.describe('Smoke — Flow Canvas', () => {

  test('opens flow canvas and shows mounted flows', async ({ page }) => {
    await establishSession(BASE, TOKEN, page);
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
    await establishSession(BASE, TOKEN, page);
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

  test('GET /api/agents/list is RETIRED (11-route takedown, 2026-09-20)', async ({ request }) => {
    // The route was 仓内零调用点 (classify pass) and taken offline by the
    // device-face hardening batch. This probe asserts it STAYS gone: with the
    // route arm removed the request falls through the route table (404), which
    // is the takedown's observable contract. If the route comes back, this
    // assertion fails and the takedown has been silently reverted.
    const resp = await request.get(`${BASE}/api/agents/list`, {
      headers: { 'Authorization': `Bearer ${TOKEN}` },
    });
    expect(resp.status(), 'GET /api/agents/list must no longer be served').toBe(404);
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
      // Watch the CURRENT auth-probe target: probeCookieAuth GETs
      // /api/nf-authcheck with no credential in the URL (R9 = O-A; it used to
      // read /api/nf-file's missing-'path' 400, which the ticket-only read leg
      // turned into a 401 that no longer distinguishes anything).
      const u = new URL(resp.url());
      if (u.pathname === '/api/nf-authcheck') probeStatuses.push(resp.status());
    });

    await establishSession(BASE, TOKEN, page);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    expect(wsUrls.length, 'expected at least one WebSocket connection').toBeGreaterThan(0);
    // The session cookie (set by the ticket landing) rides the handshake → the
    // URL carries no credential — and since stage D the retired token rail can
    // never re-enter it.
    expect(wsUrls[0], `first WS URL: ${wsUrls[0]}`).not.toContain('token=');
    // Two propositions, asserted separately and at equal strength to the old
    // single `toEqual([400])` (which compressed them into "exactly one 400"):
    //   ① the probe really ran  → count >= 1
    //   ② its conclusion is "the cookie is accepted" → 204 on the new target
    // The old form also pinned the target implicitly; the new one pins it
    // explicitly (the response watcher only records /api/nf-authcheck), which
    // is why this is equal-or-stronger, not a weakening. The old 400 semantic
    // no longer exists on the read leg, so keeping it would have been a gate
    // that cannot fail for the right reason.
    expect(probeStatuses.length, 'auth probe must fire').toBeGreaterThanOrEqual(1);
    expect(probeStatuses[0], 'auth probe target must accept the cookie (204)').toBe(204);

    const wsErrors = page._consoleErrors.filter(e =>
      /WebSocket connection .* failed/i.test(e) || /handshake rejected/i.test(e)
    );
    expect(wsErrors, `WS errors:\n${wsErrors.join('\n')}`).toEqual([]);
  });

  test('cookie-blocked browser lands on the paste-ticket entry (stage D: one degraded face, no token anywhere)', async ({ context, page }) => {
    // Simulate Safari "block all cookies": cookie writes are silently dropped
    // and reads return "" — no credential can ride any request. Stage D
    // removed the token fallback that used to rescue this shape, so the
    // designed landing is the paste-ticket entry: the auth probe answers 403
    // (whitelisted noise), the handshake is refused, and the card plus the
    // notification keep the failure visible while the reconnect loop continues
    // behind the modal.
    await context.addInitScript(() => {
      try {
        Object.defineProperty(document, 'cookie', {
          get() { return ''; },
          set() { /* dropped — cookie blocked */ },
          configurable: true,
        });
      } catch (_) { /* if override fails the assertions below catch it */ }
    });

    const wsUrls = [];
    page.on('websocket', (ws) => wsUrls.push(ws.url()));

    await page.goto(`${BASE}/`);
    // The paste-ticket card is the one degraded-shape face (ws.js onclose).
    await expect(page.locator('.nb-ticket-modal')).toBeVisible({ timeout: 15000 });
    // The 403 signal fired: the probe rode with no cookie and was refused.
    expect(page._benign403Urls.size, 'the blocked-cookie authcheck 403 must have fired').toBeGreaterThanOrEqual(1);
    // The retired rail never re-enters the wire: no handshake may carry a token.
    for (const u of wsUrls) expect(u, `WS URL must not carry a token: ${u}`).not.toContain('token=');
  });
});

test.describe('Smoke — lazy-load paths (real backend)', () => {

  test('Canvas renders files via the real readFile chain (lazy viewer modules)', async ({ page }) => {
    const assetReqs = [];
    // Δ①c (B1'): /api/nf-file requests are recorded STRUCTURALLY — status plus
    // whether the URL carried a ticket — never by URL text. Two reasons: the
    // ticket is a credential and must not land in test output, and the whole
    // point of this batch is that the URL carries a ticket and NOT a token.
    const nfFileReqs = [];
    page.on('response', (r) => {
      if (r.url().includes('/js/viewers/')) assetReqs.push(`${r.status()} ${r.url()}`);
      if (r.url().includes('/api/nf-file')) {
        const q = new URL(r.url()).searchParams;
        nfFileReqs.push({ status: r.status(), hasTicket: q.has('ticket'), hasToken: q.has('token') });
      }
    });

    await establishSession(BASE, TOKEN, page);
    await page.waitForLoadState('networkidle');
    // WS open = send button no longer marked disconnected
    await expect(page.locator('#send-btn')).not.toHaveClass(/disconnected/, { timeout: 10000 });

    const bundled = await isBundled(page);
    if (!bundled) {
      // Source mode: drive the REAL chain — WS readFile → fileContent →
      // workspace-open-item → openWorkspaceItem → dynamic import → viewers/*.
      // No DOM mocking — a broken static route or import path fails here.
      await page.evaluate(async (root) => {
        const { sendWs } = await import('/js/ws.js');
        const state = (await import('/js/state.js')).default;
        sendWs({ type: 'readFile', sessionId: state.activeSessionId || 'smoke', path: 'README.md', rootPath: root });
      }, process.cwd());
    } else {
      // Bundle mode (P1): /js/*.js module URLs no longer exist — the entry is
      // a hashed /assets/ chunk and viewers are lazy chunks. Drive the canvas
      // through its DOM event contract (workspace-open-item) with content from
      // the REST file endpoint, and assert BEHAVIOR (rendered DOM), never a
      // specific chunk URL.
      // Δ①d: the read leg is ticket-only (R5) — a bare `&token=` GET is 401 by
      // design now. Mint a ticket for this path first (which also exercises the
      // real POST /api/nf-ticket route end to end), then read.
      const readmePath = join(process.cwd(), 'README.md');
      const mintResp = await page.request.post(`${BASE}/api/nf-ticket`, {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN}` },
        data: { sessionId: 'smoke', paths: [readmePath] },
      });
      const minted = await mintResp.json();
      const readmeTicket = minted?.tickets?.[readmePath]?.t ?? '';
      expect(readmeTicket, 'minting a ticket for README.md must succeed').not.toBe('');
      const content = await (await page.request.get(
        `${BASE}/api/nf-file?path=${encodeURIComponent(readmePath)}&ticket=${encodeURIComponent(readmeTicket)}`
      )).text();
      await page.evaluate(([content, absPath]) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: { id: `file:${absPath}`, itemType: 'markdown', title: 'README.md', content, absPath, pinned: false },
        }));
      }, [content, join(process.cwd(), 'README.md')]);
    }

    await page.locator('.canvas-tab', { hasText: 'README.md' }).waitFor({ timeout: 8000 });
    // Behavior assertion (both modes): the markdown viewer actually rendered.
    await page.waitForFunction(() => {
      for (const p of document.querySelectorAll('.canvas-tab-pane')) {
        if (/nebflow/i.test(p.textContent)) return true;
      }
      return false;
    }, { timeout: 8000 });
    if (!bundled) {
      // Source mode keeps the concrete module-URL assertion.
      expect(
        assetReqs.some(r => r.startsWith('200') && r.includes('/js/viewers/markdown.js')),
        `markdown viewer module must load 200:\n${assetReqs.join('\n')}`
      ).toBe(true);
    }

    // Binary image: content does NOT arrive via WS — the image viewer fetches
    // /api/nf-file itself. Assert both the render and the fetch.
    // (Opened after the markdown assertions: a second preview tab REPLACES
    // the first — VS Code semantics, canvas.js previewKeyOf.)
    if (!bundled) {
      await page.evaluate(async (root) => {
        const { sendWs } = await import('/js/ws.js');
        const state = (await import('/js/state.js')).default;
        sendWs({ type: 'readFile', sessionId: state.activeSessionId || 'smoke', path: 'tests/fixtures/pic.png', rootPath: root });
      }, process.cwd());
    } else {
      await page.evaluate((absPath) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: { id: `file:${absPath}`, itemType: 'image', title: 'pic.png', absPath, pinned: false },
        }));
      }, join(process.cwd(), 'tests', 'fixtures', 'pic.png'));
    }
    await page.locator('.canvas-tab', { hasText: 'pic.png' }).waitFor({ timeout: 8000 });
    await page.waitForSelector('.canvas-image-viewer img', { timeout: 8000 });
    // Δ①c (B1'): the image viewer must really fetch through the new mechanism —
    // 200 AND a ticket on the URL AND no token anywhere on it.
    const nfOk = nfFileReqs.filter(r => r.status === 200);
    expect(
      nfOk.length,
      `image viewer must fetch /api/nf-file 200 (records: ${JSON.stringify(nfFileReqs)})`
    ).toBeGreaterThanOrEqual(1);
    expect(
      nfOk.every(r => r.hasTicket && !r.hasToken),
      `every 200 on /api/nf-file must carry ticket= and never token= (records: ${JSON.stringify(nfOk)})`
    ).toBe(true);

    expect(page._asset404s, `asset 404s:\n${page._asset404s.join('\n')}`).toEqual([]);
    const realErrors = filterBenignErrors(page, page._consoleErrors);
    expect(realErrors, `Console errors:\n${realErrors.join('\n')}`).toEqual([]);
    expect(page._pageErrors, `Page errors:\n${page._pageErrors.join('\n')}`).toEqual([]);
  });

  test('settings modal opens and closes', async ({ page }) => {
    await establishSession(BASE, TOKEN, page);
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

test.describe('Smoke — P1 bundle & compression', () => {

  test('static text assets are gzip-compressed (P0 backend middleware)', async ({ page }) => {
    await establishSession(BASE, TOKEN, page);
    await page.waitForLoadState('networkidle');
    // Probe the module entry (mode-agnostic: /js/main.js or /assets/app-*.js)
    // with an explicit Accept-Encoding and inspect the network-stack numbers.
    const entry = await page.evaluate(() => document.querySelector('script[type="module"]')?.src);
    expect(entry, 'module entry script must exist').toBeTruthy();
    const r = await page.evaluate(async (url) => {
      const resp = await fetch(url, { headers: { 'Accept-Encoding': 'gzip' }, cache: 'no-store' });
      const enc = resp.headers.get('content-encoding');
      await resp.arrayBuffer();
      const entries = performance.getEntriesByName(url);
      const last = entries[entries.length - 1];
      return { enc, transfer: last?.transferSize ?? 0, decoded: last?.decodedBodySize ?? 0 };
    }, entry);
    expect(r.enc, 'entry must be served with Content-Encoding: gzip (P0 middleware)').toBe('gzip');
    expect(
      r.transfer < r.decoded && r.transfer > 0,
      `compressed on the wire: transfer ${r.transfer} < decoded ${r.decoded}`
    ).toBe(true);
  });

  test('design-system CSS order: sapphire last wins (glass-control backdrop-filter)', async ({ page }) => {
    // Highest-risk P1 item: the CSS bundle must keep sapphire.css LAST. This
    // assertion is computed-style based and mode-agnostic — it fails in BOTH
    // modes if the design-system override layer ever loses final say.
    await establishSession(BASE, TOKEN, page);
    await page.waitForLoadState('networkidle');
    const r = await page.evaluate(() => {
      const b = document.createElement('button');
      b.className = 'glass-control';
      document.body.appendChild(b);
      const cs = getComputedStyle(b);
      const out = { backdropFilter: cs.backdropFilter, bg: cs.backgroundColor };
      b.remove();
      return out;
    });
    expect(r.backdropFilter, 'glass-control must carry the sapphire glass backdrop-filter').toContain('blur');
    expect(r.bg, 'glass-control must be semi-transparent glass, not a solid panel').toMatch(/rgba?\(/);
  });
});
